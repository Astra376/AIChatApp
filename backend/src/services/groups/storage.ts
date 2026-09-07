import type { Env, RequestContext } from "../../env";
import { ensureGroupSchema } from "../../db/ensureGroupSchema";
import { AppError, assert } from "../../lib/errors";
import { createId } from "../../lib/ids";
import type { GroupCharacter, GroupTranscriptMessage, GroupTrigger } from "./router";

export interface GroupRecord {
  id: string; owner_user_id: string; name: string; created_at: number; updated_at: number; version: number;
  user_turn: number; active_run_id: string | null; active_run_expires_at: number | null;
  last_user_message_id: string | null; last_user_at: number | null; last_seen_at: number;
  typing_started_at: number | null; typing_at: number | null;
  last_autonomy_at: number; last_autonomy_anchor_id: string | null; unread_count: number;
}
export interface GroupMessageRecord extends GroupTranscriptMessage {
  group_id: string; position: number; created_at: number; run_id: string | null; status: "streaming" | "complete" | "interrupted";
}
export async function requireGroup(context: RequestContext, id: string): Promise<GroupRecord> {
  await ensureGroupSchema(context.env);
  const group = await context.env.DB.prepare("SELECT * FROM chat_groups WHERE id = ? AND owner_user_id = ?")
    .bind(id, context.user!.userId).first<GroupRecord>();
  if (!group) throw new AppError(404, "GROUP_NOT_FOUND", "Group not found.");
  return group;
}
export async function groupCharacters(env: Env, group: GroupRecord, requireAll = true): Promise<GroupCharacter[]> {
  const rows = await env.DB.prepare(`SELECT c.id, c.name, c.avatar_url, c.system_prompt, c.tagline,
    CASE WHEN c.visibility != 'private' OR c.owner_user_id = ? THEN 1 ELSE 0 END AS accessible
    FROM group_members gm JOIN characters c ON c.id = gm.character_id WHERE gm.group_id = ? ORDER BY gm.position`)
    .bind(group.owner_user_id, group.id).all<GroupCharacter & {accessible: number}>();
  const characters = rows.results ?? [];
  if (requireAll) assert(characters.length >= 2 && characters.every(character => character.accessible), 409,
    "GROUP_MEMBER_UNAVAILABLE", "A character in this group is no longer available.");
  return characters.filter(character => character.accessible);
}
export function toGroupMessage(row: GroupMessageRecord, characters: GroupCharacter[]) {
  const character = characters.find(candidate => candidate.id === row.character_id);
  return {id: row.id, groupId: row.group_id, position: row.position, role: row.role,
    characterId: row.character_id, characterName: character?.name ?? null, avatarUrl: character?.avatar_url ?? null,
    content: row.content, createdAt: row.created_at, status: row.status};
}
function groupSummary(group: GroupRecord, characters: GroupCharacter[]) {
  const active = (group.active_run_expires_at ?? 0) > Date.now();
  return {id: group.id, name: group.name, characters: characters.map(character => ({id: character.id, name: character.name, avatarUrl: character.avatar_url})),
    unreadCount: group.unread_count, activeRunId: active ? group.active_run_id : null,
    activeRunExpiresAt: active ? group.active_run_expires_at : null, createdAt: group.created_at, updatedAt: group.updated_at};
}
export async function getGroup(context: RequestContext, id: string, beforePosition?: number, pageSize = 100) {
  const group = await requireGroup(context, id);
  if (group.active_run_id && (group.active_run_expires_at ?? 0) <= Date.now()) await expireGroupRun(context.env, id);
  const [characters, messages] = await Promise.all([
    groupCharacters(context.env, group, false),
    context.env.DB.prepare(`SELECT * FROM group_messages WHERE group_id = ? ${beforePosition !== undefined ? "AND position < ?" : ""}
      AND (content != '' OR status = 'streaming') ORDER BY position DESC LIMIT ?`)
      .bind(id, ...(beforePosition !== undefined ? [beforePosition] : []), pageSize).all<GroupMessageRecord>()
  ]);
  const rows = (messages.results ?? []).reverse();
  return {...groupSummary(group, characters), messages: rows.map(row => toGroupMessage(row, characters)),
    nextBeforePosition: rows.length === pageSize && rows[0].position > 0 ? rows[0].position : null};
}
export async function listGroups(context: RequestContext, cursor: number, limit: number) {
  await ensureGroupSchema(context.env);
  const groups = (await context.env.DB.prepare("SELECT * FROM chat_groups WHERE owner_user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?")
    .bind(context.user!.userId, limit, cursor).all<GroupRecord>()).results ?? [];
  if (!groups.length) return {items: [], nextCursor: null};
  const members = (await context.env.DB.prepare(`SELECT gm.group_id, c.id, c.name, c.avatar_url FROM group_members gm
    JOIN characters c ON c.id = gm.character_id WHERE gm.group_id IN (${groups.map(() => "?").join(",")})
    AND (c.visibility != 'private' OR c.owner_user_id = ?) ORDER BY gm.position`)
    .bind(...groups.map(group => group.id), context.user!.userId).all<GroupCharacter & {group_id: string}>()).results ?? [];
  return {items: groups.map(group => groupSummary(group, members.filter(member => member.group_id === group.id))),
    nextCursor: groups.length === limit ? String(cursor + limit) : null};
}
export async function createGroup(context: RequestContext, name: string, characterIds: unknown) {
  assert(Array.isArray(characterIds) && characterIds.length >= 2 && characterIds.length <= 6
    && characterIds.every(id => typeof id === "string" && id.length > 0 && id.length <= 200)
    && new Set(characterIds).size === characterIds.length, 400, "INVALID_GROUP_MEMBERS", "Choose between two and six different characters.");
  await ensureGroupSchema(context.env);
  const accessible = (await context.env.DB.prepare(`SELECT id FROM characters WHERE id IN (${characterIds.map(() => "?").join(",")})
    AND (visibility != 'private' OR owner_user_id = ?)`)
    .bind(...characterIds, context.user!.userId).all<{id: string}>()).results ?? [];
  assert(accessible.length === characterIds.length, 404, "CHARACTER_NOT_FOUND", "One or more characters are unavailable.");
  const id = createId("group");
  const now = Date.now();
  await context.env.DB.batch([
    context.env.DB.prepare("INSERT INTO chat_groups (id, owner_user_id, name, created_at, updated_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(id, context.user!.userId, name, now, now, now),
    ...characterIds.map((characterId, position) => context.env.DB.prepare("INSERT INTO group_members (group_id, character_id, position) VALUES (?, ?, ?)")
      .bind(id, characterId, position))
  ]);
  return getGroup(context, id);
}

export async function recentGroupMessages(env: Env, groupId: string): Promise<GroupMessageRecord[]> {
  const rows = await env.DB.prepare("SELECT * FROM group_messages WHERE group_id = ? AND content != '' ORDER BY position DESC LIMIT 30")
    .bind(groupId).all<GroupMessageRecord>();
  return (rows.results ?? []).reverse();
}
export const groupSendRunId = (userMessageId: string) => `group_run_${userMessageId}`;
export async function claimGroupSend(env: Env, group: GroupRecord, userMessageId: string, content: string) {
  const runId = groupSendRunId(userMessageId);
  const now = Date.now();
  const results = await env.DB.batch([
    env.DB.prepare(`UPDATE chat_groups SET active_run_id = ?, active_run_expires_at = ?, version = version + 1,
      user_turn = user_turn + 1, last_user_message_id = ?, last_user_at = ?, updated_at = ?, last_seen_at = ?,
      unread_count = 0, typing_at = NULL, typing_started_at = NULL
      WHERE id = ? AND owner_user_id = ? AND COALESCE(active_run_expires_at, 0) <= ? AND NOT EXISTS (SELECT 1 FROM group_messages WHERE id = ?)`)
      .bind(runId, now + 90_000, userMessageId, now, now, now, group.id, group.owner_user_id, now, userMessageId),
    env.DB.prepare(`UPDATE group_messages SET status = 'interrupted' WHERE group_id = ? AND status = 'streaming' AND run_id != ?
      AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND active_run_id = ?)`)
      .bind(group.id, runId, group.id, runId),
    env.DB.prepare(`INSERT OR IGNORE INTO group_messages (id, group_id, position, role, character_id, content, created_at, run_id, status)
      SELECT ?, g.id, COALESCE((SELECT MAX(position) + 1 FROM group_messages WHERE group_id = g.id), 0), 'user', NULL, ?, ?, ?, 'complete'
      FROM chat_groups g WHERE g.id = ? AND g.active_run_id = ? AND g.last_user_message_id = ?`)
      .bind(userMessageId, content, now, runId, group.id, runId, userMessageId)
  ]);
  const message = await env.DB.prepare("SELECT * FROM group_messages WHERE id = ? AND group_id = ? AND role = 'user'")
    .bind(userMessageId, group.id).first<GroupMessageRecord>();
  if (!message) throw new AppError(409, "GROUP_BUSY", "A group reply is already in progress. Stop it before sending another message.");
  assert(message.content === content, 409, "DUPLICATE_MESSAGE_ID", "This message ID has already been used.");
  const updated = await env.DB.prepare("SELECT * FROM chat_groups WHERE id = ?").bind(group.id).first<GroupRecord>();
  return {runId, message, group: updated!, duplicate: (results[2]?.meta.changes ?? 0) === 0};
}
export async function claimGroupContinuation(env: Env, group: GroupRecord, trigger: Exclude<GroupTrigger, "user">) {
  const now = Date.now();
  const runId = createId("group_auto");
  // Eligibility and claiming are one statement so simultaneous timer, cron, and manual requests cannot duplicate a turn.
  const claim = await env.DB.prepare(`UPDATE chat_groups SET active_run_id = ?, active_run_expires_at = ?,
    last_autonomy_at = ?, last_autonomy_anchor_id = last_user_message_id
    WHERE id = ? AND COALESCE(active_run_expires_at, 0) <= ? AND last_user_message_id IS NOT NULL
      AND last_user_message_id = ?
      ${trigger === "continue" ? "" : "AND COALESCE(last_autonomy_anchor_id, '') != last_user_message_id"}
      AND last_autonomy_at <= ?
      ${trigger === "typing" ? "AND typing_started_at <= ? AND typing_at >= ?" : ""}
      ${trigger === "away" ? "AND last_seen_at <= ? AND last_user_at >= ?" : ""}`)
    .bind(runId, now + 90_000, now, group.id, now, group.last_user_message_id, now - (trigger === "away" ? 6 * 3_600_000 : trigger === "typing" ? 60_000 : 5_000),
      ...(trigger === "typing" ? [now - 30_000, now - 20_000] : []),
      ...(trigger === "away" ? [now - 15 * 60_000, now - 7 * 86_400_000] : [])).run();
  return claim.meta.changes ? runId : null;
}

export async function updateGroupPresence(context: RequestContext, id: string, typing: boolean) {
  await requireGroup(context, id);
  const now = Date.now();
  await context.env.DB.prepare(`UPDATE chat_groups SET last_seen_at = ?, unread_count = 0,
    typing_started_at = CASE WHEN ? = 0 THEN NULL WHEN COALESCE(typing_at, 0) < ? THEN ? ELSE COALESCE(typing_started_at, ?) END,
    typing_at = CASE WHEN ? = 1 THEN ? ELSE NULL END WHERE id = ? AND owner_user_id = ?`)
    .bind(now, typing ? 1 : 0, now - 20_000, now, now, typing ? 1 : 0, now, id, context.user!.userId).run();
}

export async function persistGroupReply(env: Env, group: GroupRecord, runId: string, messageId: string, characterId: string, content: string, complete: boolean, background: boolean) {
  const now = Date.now();
  const results = await env.DB.batch([
    env.DB.prepare(`UPDATE group_messages SET content = ?, status = ? WHERE id = ? AND group_id = ? AND run_id = ? AND status = 'streaming'
      AND EXISTS (SELECT 1 FROM chat_groups g JOIN characters c ON c.id = ? WHERE g.id = group_messages.group_id
        AND g.active_run_id = ? AND g.active_run_expires_at > ? AND (c.visibility != 'private' OR c.owner_user_id = g.owner_user_id))`)
      .bind(content, complete ? "complete" : "streaming", messageId, group.id, runId, characterId, runId, now),
    env.DB.prepare(`UPDATE chat_groups SET updated_at = ?, version = version + ?,
      unread_count = unread_count + CASE WHEN ? = 1 AND last_seen_at < ? THEN 1 ELSE 0 END
      WHERE id = ? AND active_run_id = ? AND changes() > 0`)
      .bind(now, complete ? 1 : 0, complete && background ? 1 : 0, now - 5_000, group.id, runId)
  ]);
  return (results[0]?.meta.changes ?? 0) > 0;
}
export async function insertGroupReply(env: Env, group: GroupRecord, runId: string, messageId: string, characterId: string) {
  const result = await env.DB.prepare(`INSERT OR IGNORE INTO group_messages
    (id, group_id, position, role, character_id, content, created_at, run_id, status)
    SELECT ?, g.id, COALESCE((SELECT MAX(position) + 1 FROM group_messages WHERE group_id = g.id), 0), 'assistant', ?, '', ?, ?, 'streaming'
    FROM chat_groups g JOIN group_members gm ON gm.group_id = g.id AND gm.character_id = ?
    JOIN characters c ON c.id = gm.character_id
    WHERE g.id = ? AND g.active_run_id = ? AND g.active_run_expires_at > ?
      AND (c.visibility != 'private' OR c.owner_user_id = g.owner_user_id)`)
    .bind(messageId, characterId, Date.now(), runId, characterId, group.id, runId, Date.now()).run();
  return (result.meta.changes ?? 0) > 0;
}
export async function stopGroupRun(env: Env, groupId: string, runId: string, partial?: {id: string; content: string}) {
  const statements: D1PreparedStatement[] = [];
  if (partial) statements.push(env.DB.prepare(`UPDATE group_messages SET content = ? WHERE id = ? AND group_id = ? AND run_id = ?
    AND role = 'assistant' AND status = 'streaming' AND length(content) <= length(?)
    AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND active_run_id = ?)`)
    .bind(partial.content, partial.id, groupId, runId, partial.content, groupId, runId));
  statements.push(
    env.DB.prepare(`UPDATE group_messages SET status = 'interrupted' WHERE group_id = ? AND run_id = ? AND status = 'streaming'
      AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND active_run_id = ?)`)
      .bind(groupId, runId, groupId, runId),
    env.DB.prepare(`DELETE FROM group_messages WHERE group_id = ? AND run_id = ? AND content = '' AND role = 'assistant'
      AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND active_run_id = ?)`)
      .bind(groupId, runId, groupId, runId),
    env.DB.prepare("UPDATE chat_groups SET active_run_id = NULL, active_run_expires_at = NULL WHERE id = ? AND active_run_id = ?")
      .bind(groupId, runId)
  );
  await env.DB.batch(statements);
}

export async function deleteGroup(context: RequestContext, id: string) {
  await requireGroup(context, id);
  await context.env.DB.prepare("DELETE FROM chat_groups WHERE id = ? AND owner_user_id = ?").bind(id, context.user!.userId).run();
}

export async function expireGroupRun(env: Env, groupId: string) {
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`UPDATE group_messages SET status = 'interrupted' WHERE group_id = ? AND status = 'streaming'
      AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND COALESCE(active_run_expires_at, 0) <= ?)` ).bind(groupId, groupId, now),
    env.DB.prepare(`DELETE FROM group_messages WHERE group_id = ? AND role = 'assistant' AND content = ''
      AND EXISTS (SELECT 1 FROM chat_groups WHERE id = ? AND COALESCE(active_run_expires_at, 0) <= ?)` ).bind(groupId, groupId, now),
    env.DB.prepare("UPDATE chat_groups SET active_run_id = NULL, active_run_expires_at = NULL WHERE id = ? AND COALESCE(active_run_expires_at, 0) <= ?").bind(groupId, now)
  ]);
}
