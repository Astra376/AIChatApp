import { ensureNotificationSchema } from "../../db/ensureNotificationSchema";
import type { RequestContext } from "../../env";
import { ensureConversationStreamingSchema } from "../../db/ensureConversationStreamingSchema";
import { getCharacterById } from "../../db/queries/characters";
import {
  findConversationByOwnerAndCharacter,
  getConversationById,
  getConversationSummaryById,
  insertConversation,
  insertMessage,
  listConversationSummaries,
  listMessages,
  listRegenerationsForConversation,
  updateConversationActivity
} from "../../db/queries/conversations";
import { AppError, forbidden } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { toCharacterDto } from "../characters/characterDto";

function toConversationSummary(record: Awaited<ReturnType<typeof listConversationSummaries>>[number]) {
  return {
    id: record.id,
    characterId: record.character_id,
    characterName: record.character_name,
    characterAvatarUrl: record.character_avatar_url,
    updatedAt: record.updated_at,
    startedAt: record.started_at,
    lastMessageAt: record.last_message_at,
    lastPreview: record.last_preview,
    unreadCount: record.unread_count,
    hasUnreadBadge: record.has_unread_badge === 1
  };
}

export async function listConversations(context: RequestContext, cursor: number, limit: number) {
  await ensureConversationStreamingSchema(context.env);
  const rows = await listConversationSummaries(context.env, context.user!.userId, cursor, limit);
  return {
    items: rows.map(toConversationSummary),
    nextCursor: rows.length === limit ? String(cursor + rows.length) : null
  };
}

export async function createConversation(
  context: RequestContext,
  characterId: string,
  forceNew = false
) {
  await ensureConversationStreamingSchema(context.env);
  const [character, existing] = await Promise.all([
    getCharacterById(context.env, context.user!.userId, characterId),
    forceNew ? Promise.resolve(null) : findConversationByOwnerAndCharacter(context.env, context.user!.userId, characterId)
  ]);
  if (!character) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  if (character.visibility === "private" && character.owner_user_id !== context.user!.userId) {
    forbidden("Private characters are only visible to their owner.");
  }

  if (existing) {
    const summary = await getConversationSummaryById(context.env, context.user!.userId, existing.id);
    if (summary) {
      return toConversationSummary(summary);
    }

    return {
      id: existing.id,
      characterId: character.id,
      characterName: character.name,
      characterAvatarUrl: character.avatar_url,
      updatedAt: existing.updated_at,
      startedAt: existing.started_at,
      lastMessageAt: existing.last_message_at,
      lastPreview: "",
      unreadCount: 0,
      hasUnreadBadge: false
    };
  }

  const now = Date.now();
  const conversationId = createId("conversation");
  await insertConversation(context.env, {
    id: conversationId,
    ownerUserId: context.user!.userId,
    characterId,
    now
  });
  const greeting = character.greeting.trim();
  if (greeting) {
    await insertMessage(context.env, {
      id: createId("message"),
      conversation_id: conversationId,
      position: 0,
      role: "assistant",
      content: greeting,
      edited: 0,
      created_at: now,
      updated_at: now,
      selected_regeneration_id: null
    });
    await updateConversationActivity(context.env, conversationId, now);
  }

  return {
    id: conversationId,
    characterId: character.id,
    characterName: character.name,
    characterAvatarUrl: character.avatar_url,
    updatedAt: now,
    startedAt: now,
    lastMessageAt: greeting ? now : null,
    lastPreview: greeting,
    unreadCount: 0,
    hasUnreadBadge: false
  };
}

export async function getConversationDetail(context: RequestContext, conversationId: string) {
  await ensureConversationStreamingSchema(context.env);
  const conversation = await getConversationById(context.env, conversationId);
  if (!conversation) {
    throw new AppError(404, "CONVERSATION_NOT_FOUND", "Conversation not found.");
  }
  if (conversation.owner_user_id !== context.user!.userId) {
    forbidden("Conversations are private to their owner.");
  }

  const [character, messages, regenerations] = await Promise.all([
    getCharacterById(context.env, context.user!.userId, conversation.character_id),
    listMessages(context.env, conversationId),
    listRegenerationsForConversation(context.env, conversationId)
  ]);
  if (!character) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  const grouped = new Map<string, typeof regenerations>();
  for (const regeneration of regenerations) {
    const list = grouped.get(regeneration.message_id) ?? [];
    list.push(regeneration);
    grouped.set(regeneration.message_id, list);
  }

  return {
    id: conversation.id,
    ownerUserId: conversation.owner_user_id,
    conversationVersion: conversation.version,
    // Only the owner can read this endpoint. The exact run ID lets a fresh
    // client recover a stopped stream without cancelling a newer run by guess.
    activeRunId: conversation.active_run_expires_at != null && conversation.active_run_expires_at > Date.now()
      ? conversation.active_run_id : null,
    activeRunExpiresAt: conversation.active_run_expires_at != null && conversation.active_run_expires_at > Date.now()
      ? conversation.active_run_expires_at : null,
    character: toCharacterDto(character, context.user!.userId),
    messages: messages.map((message) => ({
      id: message.id,
      conversationId: message.conversation_id,
      position: message.position,
      role: message.role,
      content: message.content,
      edited: Boolean(message.edited),
      createdAt: message.created_at,
      updatedAt: message.updated_at,
      selectedRegenerationId: message.selected_regeneration_id,
      regenerations: (grouped.get(message.id) ?? []).map((regeneration) => ({
        id: regeneration.id,
        messageId: regeneration.message_id,
        content: regeneration.content,
        createdAt: regeneration.created_at
      }))
    }))
  };
}

export async function markConversationRead(context: RequestContext, conversationId: string) {
  await ensureConversationStreamingSchema(context.env);
  const conversation = await getConversationById(context.env, conversationId);
  if (!conversation) {
    throw new AppError(404, "CONVERSATION_NOT_FOUND", "Conversation not found.");
  }
  if (conversation.owner_user_id !== context.user!.userId) {
    forbidden("Conversations are private to their owner.");
  }

  await ensureNotificationSchema(context.env);
  await context.env.DB.batch([
    context.env.DB.prepare("UPDATE conversations SET unread_count = 0, has_unread_badge = 0 WHERE id = ?").bind(conversationId),
    context.env.DB.prepare("UPDATE notifications SET read_at = COALESCE(read_at, ?) WHERE user_id = ? AND conversation_id = ?")
      .bind(Date.now(), context.user!.userId, conversationId)
  ]);
}
