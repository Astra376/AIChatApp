import type { Env } from "../../env";
import { ensureNotificationSchema } from "../../db/ensureNotificationSchema";
import { completeChatText } from "../../providers/openrouter";
import { formatRoleplayMessage } from "./formatRoleplay";
import { deliverNotificationEmails } from "../notifications/email";
import { processRecommendations } from "../notifications";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
export function offlineSchedule(userMessageCount: number): number[] {
  if (userMessageCount >= 40) return [15 * MINUTE, HOUR, 6 * HOUR, DAY, 3 * DAY, 7 * DAY, 30 * DAY, 90 * DAY];
  if (userMessageCount >= 10) return [HOUR, 6 * HOUR, DAY, 3 * DAY, 7 * DAY, 30 * DAY, 90 * DAY];
  if (userMessageCount >= 3) return [6 * HOUR, DAY, 7 * DAY, 30 * DAY, 90 * DAY];
  return [DAY, 7 * DAY, 30 * DAY, 90 * DAY];
}
export function dueOfflineStage(userMessageCount: number, idleMilliseconds: number, lastDeliveredStage = -1): number | null {
  const schedule = offlineSchedule(userMessageCount);
  let due = -1;
  // Missed intervals collapse to the latest due interval, never a catch-up notification burst.
  for (let stage = 0; stage < schedule.length; stage++) if (idleMilliseconds >= schedule[stage]) due = stage;
  return due >= 0 && due > lastDeliveredStage ? due : null;
}
interface Candidate {
  id: string; owner_user_id: string; character_id: string; version: number;
  name: string; system_prompt: string; avatar_url: string | null;
  anchor_id: string; anchor_at: number; user_count: number; delivered_stage: number;
}

export async function latestOfflineTranscript(env: Env, conversationId: string) {
  const result = await env.DB.prepare(`SELECT role, content FROM (
    SELECT m.role, COALESCE(r.content, m.content) AS content, m.position FROM messages m
    LEFT JOIN assistant_regenerations r ON r.id = m.selected_regeneration_id AND r.message_id = m.id
    WHERE m.conversation_id = ? ORDER BY m.position DESC LIMIT 30
  ) ORDER BY position ASC`).bind(conversationId).all<{role: "user" | "assistant"; content: string}>();
  return result.results ?? [];
}

export async function saveOfflineMessage(env: Env, candidate: Candidate, stage: number, text: string, now: number): Promise<boolean> {
  const id = `offline:${candidate.id}:${candidate.anchor_id}:${stage}`;
  // The insert, conversation revision and activity item commit together. User activity, a new reply,
  // an edit/rewind, or a changed preference during generation invalidates this write without locking chat.
  const result = await env.DB.batch([
    env.DB.prepare(`INSERT OR IGNORE INTO messages
      (id, conversation_id, position, role, content, edited, created_at, updated_at, selected_regeneration_id)
      SELECT ?, c.id, COALESCE((SELECT MAX(position) + 1 FROM messages WHERE conversation_id = c.id), 0), 'assistant', ?, 0, ?, ?, NULL
      FROM conversations c JOIN characters ch ON ch.id = c.character_id LEFT JOIN notification_settings s ON s.user_id = c.owner_user_id
      LEFT JOIN user_presence p ON p.user_id = c.owner_user_id
      WHERE c.id = ? AND c.version = ? AND COALESCE(c.active_run_expires_at, 0) <= ?
        AND (ch.visibility != 'private' OR ch.owner_user_id = c.owner_user_id)
        AND COALESCE(s.chat_messages_enabled, 1) = 1
        AND COALESCE(p.last_seen_at, 0) < ?
        AND (SELECT id FROM messages WHERE conversation_id = c.id AND role = 'user' ORDER BY position DESC LIMIT 1) = ?
        AND (SELECT COUNT(*) FROM notifications WHERE user_id = c.owner_user_id AND kind = 'chat' AND created_at > ?) < 3`)
      .bind(id, text, now, now, candidate.id, candidate.version, now, now - 5 * MINUTE, candidate.anchor_id, now - DAY),
    env.DB.prepare(`UPDATE conversations SET last_message_at = ?, updated_at = ?, unread_count = unread_count + 1,
      has_unread_badge = 1, version = version + 1 WHERE id = ? AND version = ?
      AND EXISTS (SELECT 1 FROM messages WHERE id = ?)`)
      .bind(now, now, candidate.id, candidate.version, id),
    env.DB.prepare(`INSERT OR IGNORE INTO notifications
      (id, user_id, kind, title, body, character_id, conversation_id, avatar_url, created_at, updated_at, dedup_key)
      SELECT ?, ?, 'chat', ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM messages WHERE id = ?)`)
      .bind(id, candidate.owner_user_id, `${candidate.name} sent you a message`, text.slice(0, 240), candidate.character_id,
        candidate.id, candidate.avatar_url, now, now, id, id),
    env.DB.prepare(`UPDATE offline_deliveries SET state = CASE WHEN EXISTS (SELECT 1 FROM messages WHERE id = ?) THEN 'delivered' ELSE 'obsolete' END,
      message_id = ?, updated_at = ? WHERE conversation_id = ? AND anchor_message_id = ? AND stage = ?`)
      .bind(id, id, now, candidate.id, candidate.anchor_id, stage)
  ]);
  return (result[0]?.meta.changes ?? 0) > 0;
}

async function processCandidate(env: Env, candidate: Candidate, now: number): Promise<void> {
  const stage = dueOfflineStage(candidate.user_count, now - candidate.anchor_at, candidate.delivered_stage);
  if (stage === null) return;
  const claimed = await env.DB.prepare(`INSERT INTO offline_deliveries (conversation_id, anchor_message_id, stage, state, updated_at)
    VALUES (?, ?, ?, 'generating', ?) ON CONFLICT(conversation_id, anchor_message_id, stage) DO UPDATE SET
      state = 'generating', attempts = attempts + 1, updated_at = excluded.updated_at
    WHERE offline_deliveries.state IN ('failed', 'generating') AND offline_deliveries.updated_at < ? AND offline_deliveries.attempts < 2`)
    .bind(candidate.id, candidate.anchor_id, stage, now, now - 30 * MINUTE).run();
  if (!claimed.meta.changes) return;
  try {
    const transcript = await latestOfflineTranscript(env, candidate.id);
    const text = formatRoleplayMessage(await completeChatText(env, [
      {role: "system", content: candidate.system_prompt + "\n\nThe user is away. Continue naturally in character with one short, relevant message based on the latest conversation. Do not pressure, guilt, claim an emergency, or mention a notification schedule. Ask at most one question. Keep it under 80 words. Do not repeat earlier follow-ups."},
      ...transcript
    ], {maxTokens: 220, temperature: 0.85}));
    if (!text.trim()) throw new Error("Empty offline reply");
    await saveOfflineMessage(env, candidate, stage, text, Date.now());
  } catch {
    await env.DB.prepare("UPDATE offline_deliveries SET state = CASE WHEN attempts >= 2 THEN 'obsolete' ELSE 'failed' END, updated_at = ? WHERE conversation_id = ? AND anchor_message_id = ? AND stage = ?")
      .bind(Date.now(), candidate.id, candidate.anchor_id, stage).run();
  }
}

export async function processOfflineMessages(env: Env) {
  await ensureNotificationSchema(env);
  const now = Date.now();
  const result = await env.DB.prepare(`WITH candidates AS (SELECT c.id, c.owner_user_id, c.character_id, c.version,
      ch.name, ch.system_prompt, ch.avatar_url, latest.id AS anchor_id, latest.created_at AS anchor_at,
      (SELECT COUNT(*) FROM messages WHERE conversation_id = c.id AND role = 'user') AS user_count,
      COALESCE((SELECT MAX(stage) FROM offline_deliveries d WHERE d.conversation_id = c.id
        AND d.anchor_message_id = latest.id AND d.state IN ('delivered', 'obsolete')), -1) AS delivered_stage
    FROM conversations c JOIN characters ch ON ch.id = c.character_id
    JOIN messages latest ON latest.id = (SELECT id FROM messages WHERE conversation_id = c.id AND role = 'user' ORDER BY position DESC LIMIT 1)
    LEFT JOIN notification_settings s ON s.user_id = c.owner_user_id
    LEFT JOIN user_presence p ON p.user_id = c.owner_user_id
    WHERE latest.created_at < ? AND COALESCE(c.active_run_expires_at, 0) <= ?
      AND COALESCE(p.last_seen_at, 0) < ? AND COALESCE(s.chat_messages_enabled, 1) = 1
      AND (ch.visibility != 'private' OR ch.owner_user_id = c.owner_user_id)
      AND (SELECT COUNT(*) FROM notifications n WHERE n.user_id = c.owner_user_id AND n.kind = 'chat' AND n.created_at > ?) < 3
    ), due AS (
      SELECT candidates.*, (SELECT MAX(CAST(key AS INTEGER)) FROM json_each(
        CASE WHEN user_count >= 40 THEN '${JSON.stringify(offlineSchedule(40))}'
          WHEN user_count >= 10 THEN '${JSON.stringify(offlineSchedule(10))}'
          WHEN user_count >= 3 THEN '${JSON.stringify(offlineSchedule(3))}'
          ELSE '${JSON.stringify(offlineSchedule(1))}' END) WHERE value <= ? - anchor_at) AS due_stage
      FROM candidates
    ) SELECT * FROM due WHERE due_stage > delivered_stage
      AND NOT EXISTS (SELECT 1 FROM offline_deliveries d WHERE d.conversation_id = due.id
        AND d.anchor_message_id = due.anchor_id AND d.stage = due.due_stage
        AND (d.state IN ('delivered', 'obsolete') OR d.updated_at >= ? OR d.attempts >= 2))
    ORDER BY COALESCE((SELECT MAX(updated_at) FROM offline_deliveries d WHERE d.conversation_id = due.id), 0) ASC,
      anchor_at DESC LIMIT 6`)
    .bind(now - 15 * MINUTE, now, now - 5 * MINUTE, now - DAY, now, now - 30 * MINUTE).all<Candidate>();
  const eligible = (result.results ?? []).filter(c => dueOfflineStage(c.user_count, now - c.anchor_at, c.delivered_stage) !== null).slice(0, 6);
  for (let offset = 0; offset < eligible.length; offset += 2) {
    await Promise.allSettled(eligible.slice(offset, offset + 2).map(candidate => processCandidate(env, candidate, now)));
  }
  await processRecommendations(env, now);
  await deliverNotificationEmails(env, now);
}
