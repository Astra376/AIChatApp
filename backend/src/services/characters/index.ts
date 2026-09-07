import { characterVoiceStatement } from "../voice";
import type { RequestContext } from "../../env";
import {
  getCharacterById,
  getLikedCharacters,
  getOwnedCharacters,
  insertCharacter,
  likeCharacter,
  unlikeCharacter,
  updateCharacter
} from "../../db/queries/characters";
import { AppError, assert, forbidden } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { completeChatText } from "../../providers/openrouter";
import { toCharacterDto } from "./characterDto";

export interface CharacterWriteInput {
  name: string;
  tagline: string;
  greeting: string;
  description: string;
  systemPrompt: string;
  definitionPrivate: boolean;
  visibility: "public" | "unlisted" | "private";
  avatarUrl: string | null;
  voiceId?: string | null;
}

export function parseCharacterVisibility(value: string): CharacterWriteInput["visibility"] {
  if (value === "private" || value === "unlisted") return value;
  return "public";
}

export async function createOwnedCharacter(context: RequestContext, input: CharacterWriteInput) {
  const id = createId("character");
  const voiceStatements = input.voiceId ? [await characterVoiceStatement(context, id, input.voiceId, input.visibility)] : [];
  await insertCharacter(context.env, {
    id,
    ownerUserId: context.user!.userId,
    name: input.name,
    tagline: input.tagline,
    greeting: input.greeting,
    description: input.description,
    systemPrompt: input.systemPrompt,
    definitionPrivate: input.definitionPrivate,
    visibility: input.visibility,
    avatarUrl: input.avatarUrl,
    now: Date.now()
  }, voiceStatements);
  return getCharacter(context, id);
}

export async function updateOwnedCharacter(context: RequestContext, characterId: string, input: CharacterWriteInput) {
  const current = await getCharacterById(context.env, context.user!.userId, characterId);
  if (!current) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  if (current.owner_user_id !== context.user!.userId) {
    forbidden("You can only edit your own characters.");
  }

  const voiceStatements = input.voiceId ? [await characterVoiceStatement(context, characterId, input.voiceId, input.visibility)] : [];
  await updateCharacter(context.env, {
    id: characterId,
    ownerUserId: context.user!.userId,
    name: input.name,
    tagline: input.tagline,
    greeting: input.greeting,
    description: input.description,
    systemPrompt: input.systemPrompt,
    definitionPrivate: input.definitionPrivate,
    visibility: input.visibility,
    avatarUrl: input.avatarUrl,
    now: Date.now()
  }, voiceStatements);

  return getCharacter(context, characterId);
}

export async function getCharacter(context: RequestContext, characterId: string) {
  const record = await getCharacterById(context.env, context.user!.userId, characterId);
  if (!record) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  if (record.visibility === "private" && record.owner_user_id !== context.user!.userId) {
    forbidden("Private characters are only visible to their owner.");
  }
  return toCharacterDto(record, context.user!.userId);
}

export async function getMyCharacters(context: RequestContext, cursor: number, limit: number) {
  const records = await getOwnedCharacters(context.env, context.user!.userId, cursor, limit);
  return {
    items: records.map((record) => toCharacterDto(record, context.user!.userId)),
    nextCursor: records.length === limit ? String(cursor + records.length) : null
  };
}

export async function getMyLikedCharacters(context: RequestContext, cursor: number, limit: number) {
  const records = await getLikedCharacters(context.env, context.user!.userId, cursor, limit);
  return {
    items: records.map((record) => toCharacterDto(record, context.user!.userId)),
    nextCursor: records.length === limit ? String(cursor + records.length) : null
  };
}

export async function likePublicCharacter(context: RequestContext, characterId: string) {
  const record = await getCharacterById(context.env, context.user!.userId, characterId);
  assert(record?.visibility === "public", 404, "CHARACTER_NOT_FOUND", "Public character not found.");
  await likeCharacter(context.env, context.user!.userId, characterId, Date.now());
  const updated = await getCharacterById(context.env, context.user!.userId, characterId);
  assert(updated?.visibility === "public", 404, "CHARACTER_NOT_FOUND", "Public character not found.");
  return {
    likedByMe: Boolean(updated.liked_by_me),
    likeCount: updated.like_count
  };
}

export async function generateCharacterGreeting(context: RequestContext, input: { name: string; description: string }) {
  const messages = [
    {
      role: "system" as const,
      content: [
        "Write an in-character opening greeting for a roleplay AI character.",
        "Return only the greeting text.",
        "Use exactly two sentences.",
        "Do not include quotation marks around the full response."
      ].join(" ")
    },
    {
      role: "user" as const,
      content: `Character name: ${input.name}\nCharacter description: ${input.description}`
    }
  ];

  const greeting = await completeChatText(context.env, messages, {
    maxTokens: 400,
    temperature: 0.8
  });
  return { greeting: greeting.trim().slice(0, 1_200) };
}

export async function unlikePublicCharacter(context: RequestContext, characterId: string) {
  const record = await getCharacterById(context.env, context.user!.userId, characterId);
  assert(record?.visibility === "public", 404, "CHARACTER_NOT_FOUND", "Public character not found.");
  await unlikeCharacter(context.env, context.user!.userId, characterId);
  const updated = await getCharacterById(context.env, context.user!.userId, characterId);
  assert(updated?.visibility === "public", 404, "CHARACTER_NOT_FOUND", "Public character not found.");
  return {
    likedByMe: Boolean(updated.liked_by_me),
    likeCount: updated.like_count
  };
}
