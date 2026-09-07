import type { Env, RequestContext } from "../../env";
import { ensureGroupSchema } from "../../db/ensureGroupSchema";
import { AppError, assert } from "../../lib/errors";
import { RequestDeadline } from "../../lib/deadline";
import { createId } from "../../lib/ids";
import { streamChatText } from "../../providers/openrouter";
import { resolveAutomaticModel } from "../chat/modelPolicy";
import { resolveUserPersonaPrompt } from "../personas";
import { getNotificationSettings } from "../notifications";
import { chooseGroupSpeakers, groupCharacterContext, type GroupCharacter, type GroupTrigger } from "./router";
import { claimGroupContinuation, claimGroupSend, groupCharacters, insertGroupReply, persistGroupReply,
  recentGroupMessages, requireGroup, stopGroupRun, toGroupMessage, type GroupMessageRecord, type GroupRecord } from "./storage";

import { consolidateGroupMemory, groupMemoryContext } from "./memory";

type Emit = (event: Record<string, unknown>) => void;
// The database lease fences runs across Worker isolates; this controller also
// stops provider work immediately when the stop request hits the same isolate.
const localRuns = new Map<string, AbortController>();
export async function stopGroup(context: RequestContext, groupId: string, runId: string) {
  await requireGroup(context, groupId);
  await stopGroupRun(context.env, groupId, runId);
  localRuns.get(`${groupId}:${runId}`)?.abort(new DOMException("Stopped", "AbortError"));
}

async function runGroup(env: Env, group: GroupRecord, runId: string, trigger: GroupTrigger,
  characters: GroupCharacter[], emit: Emit, background = false): Promise<void> {
  const abort = new AbortController();
  const key = `${group.id}:${runId}`;
  localRuns.set(key, abort);
  const deadline = new RequestDeadline(20_000, 80_000, abort.signal);
  let partial: {id: string; content: string} | undefined;
  try {
    const transcript = await recentGroupMessages(env, group.id);
    const [speakers, identity, continuity] = await Promise.all([
      chooseGroupSpeakers(env, characters, transcript, trigger, deadline.signal),
      resolveUserPersonaPrompt(env, group.owner_user_id),
      groupMemoryContext(env, group, characters, transcript)
    ]);
    deadline.touch();
    const latestUser = [...transcript].reverse().find(message => message.role === "user")?.content ?? "";
    const policy = await resolveAutomaticModel(env, group.owner_user_id, `group:${group.id}`, latestUser, group.user_turn,
      characters.some(character => /thoughtful|reflective|analytical|philosoph/i.test(character.system_prompt)));
    for (const characterId of speakers) {
      const character = characters.find(member => member.id === characterId)!;
      const messageId = createId("group_message");
      assert(await insertGroupReply(env, group, runId, messageId, characterId), 409, "GROUP_STOPPED", "This group reply was stopped.");
      partial = {id: messageId, content: ""};
      emit({type: "speaker", runId, messageId, characterId, characterName: character.name, avatarUrl: character.avatar_url,
        reasoning: policy.reasoningEnabled, status: policy.reasoningEnabled ? "Considering" : null});
      let persistedAt = Date.now();
      // The second speaker sees the first speaker's actual message, not a script.
      for await (const chunk of streamChatText(policy.env, groupCharacterContext(character, characters, transcript, `${identity}\n${continuity.prompts[character.id]}`, continuity.shortTermLimit), deadline.signal,
        {reasoning: policy.reasoning, maxTokens: policy.maxTokens})) {
        deadline.touch();
        partial.content += chunk;
        assert(partial.content.length <= 16_000, 502, "GROUP_REPLY_TOO_LONG", "The group reply was too long. Please continue the chat.");
        if (Date.now() - persistedAt >= 600) {
          assert(await persistGroupReply(env, group, runId, messageId, characterId, partial.content, false, background),
            409, "GROUP_STOPPED", "This group reply was stopped.");
          persistedAt = Date.now();
        }
        emit({type: "delta", runId, messageId, textDelta: chunk});
      }
      assert(partial.content.trim(), 502, "EMPTY_GROUP_REPLY", "The character didn't send a reply. Try continuing the chat.");
      assert(await persistGroupReply(env, group, runId, messageId, characterId, partial.content, true, background),
        409, "GROUP_STOPPED", "This group reply was stopped.");
      const message = await env.DB.prepare("SELECT * FROM group_messages WHERE id = ? AND group_id = ?")
        .bind(messageId, group.id).first<GroupMessageRecord>();
      if (!message) throw new AppError(409, "GROUP_STOPPED", "This group was removed.");
      transcript.push(message);
      if (background) {
        await env.DB.prepare(`INSERT OR IGNORE INTO notifications
          (id,user_id,kind,title,body,character_id,conversation_id,avatar_url,created_at,updated_at,dedup_key)
          SELECT ?, ?, 'group', ?, ?, ?, ?, ?, ?, ?, ?
          WHERE EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND owner_user_id = ? AND last_seen_at < ?)
            AND (SELECT COUNT(*) FROM notifications WHERE user_id = ? AND kind IN ('chat','group') AND created_at >= ?) < 3`)
          .bind(createId("notification"), group.owner_user_id, `${character.name} in ${group.name}`, message.content.slice(0, 240),
            character.id, group.id, character.avatar_url, message.created_at, message.created_at, `group:${message.id}`,
            group.id, group.owner_user_id, message.created_at - 5_000, group.owner_user_id, Date.now() - 86_400_000).run();
      }
      emit({type: "message_done", runId, message: toGroupMessage(message, characters)});
      partial = undefined;
    }
    await stopGroupRun(env, group.id, runId);
    emit({type: "done", runId});
    await consolidateGroupMemory(env, group.id, characters);
  } catch (error) {
    const cancelled = abort.signal.aborted || error instanceof AppError && error.code === "GROUP_STOPPED";
    emit({type: "error", runId, code: cancelled ? "GROUP_STOPPED" : error instanceof AppError ? error.code : "GROUP_INTERRUPTED",
      message: cancelled ? "Reply stopped." : error instanceof AppError ? error.message : "The group reply was interrupted. You can continue the chat."});
  } finally {
    deadline.dispose();
    localRuns.delete(key);
    await stopGroupRun(env, group.id, runId, partial).catch(() => {});
  }
}

function streamingResponse(context: RequestContext, accepted: Record<string, unknown>, operation: (emit: Emit) => Promise<void>) {
  const encoder = new TextEncoder();
  let connected = true;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const send = (text: string) => {
        if (!connected) return;
        try { controller.enqueue(encoder.encode(text)); } catch { connected = false; }
      };
      const emit: Emit = event => send(`data: ${JSON.stringify(event)}\n\n`);
      emit(accepted);
      heartbeat = setInterval(() => send(": keep-alive\n\n"), 10_000);
      const completion = operation(emit).catch(() => {
        emit({type: "error", runId: accepted.runId, code: "GROUP_INTERRUPTED", message: "The group reply was interrupted. Please try again."});
      }).finally(() => {
        clearInterval(heartbeat);
        if (connected) { connected = false; try { controller.close(); } catch {} }
      });
      // Reopening gets current persisted text and the lease through the detail API.
      context.waitUntil?.(completion);
    },
    cancel() { connected = false; clearInterval(heartbeat); }
  });
  return new Response(stream, {headers: {"Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache, no-transform",
    "X-Accel-Buffering": "no", "Access-Control-Allow-Origin": "*"}});
}

export async function sendGroupMessage(context: RequestContext, id: string, userMessageId: string, content: string) {
  const group = await requireGroup(context, id);
  const characters = await groupCharacters(context.env, group);
  const claim = await claimGroupSend(context.env, group, userMessageId, content);
  const accepted = {type: "accepted_send", runId: claim.runId, userMessage: toGroupMessage(claim.message, characters)};
  return streamingResponse(context, accepted, async emit => {
    if (claim.duplicate) { emit({type: "done", runId: claim.runId}); return; }
    await runGroup(context.env, claim.group, claim.runId, "user", characters, emit);
  });
}
export async function continueGroup(context: RequestContext, id: string, trigger: "continue" | "typing" | "quiet") {
  const group = await requireGroup(context, id);
  const characters = await groupCharacters(context.env, group);
  const runId = await claimGroupContinuation(context.env, group, trigger);
  const requestRun = runId ?? createId("group_idle");
  return streamingResponse(context, {type: "accepted_continue", runId: requestRun}, async emit => {
    if (runId) await runGroup(context.env, group, runId, trigger, characters, emit);
    else emit({type: "done", runId: requestRun});
  });
}
export async function processGroupFollowups(env: Env) {
  await ensureGroupSchema(env);
  const now = Date.now();
  const groups = (await env.DB.prepare(`SELECT * FROM chat_groups WHERE last_seen_at <= ? AND last_user_at >= ?
    AND last_user_message_id IS NOT NULL AND COALESCE(last_autonomy_anchor_id, '') != last_user_message_id
    AND COALESCE(active_run_expires_at, 0) <= ? AND last_autonomy_at <= ? ORDER BY last_user_at DESC LIMIT 8`)
    .bind(now - 15 * 60_000, now - 7 * 86_400_000, now, now - 6 * 3_600_000).all<GroupRecord>()).results ?? [];
  for (const group of groups) {
    const settings = await getNotificationSettings(env, group.owner_user_id);
    if (!settings.chatMessagesEnabled) continue;
    const delivered = await env.DB.prepare("SELECT COUNT(*) AS count FROM notifications WHERE user_id = ? AND kind IN ('chat','group') AND created_at >= ?")
      .bind(group.owner_user_id, now - 86_400_000).first<{count: number}>();
    if ((delivered?.count ?? 0) >= 3) continue;
    const characters = await groupCharacters(env, group, false);
    if (characters.length < 2) continue;
    const runId = await claimGroupContinuation(env, group, "away");
    if (runId) await runGroup(env, group, runId, "away", characters, () => {}, true);
  }
}
