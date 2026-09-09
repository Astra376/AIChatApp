import type { Env, RequestContext } from "../../env";
import { ensureChatModelSchema } from "../../db/ensureChatModelSchema";
import { assert } from "../../lib/errors";
import { hasUltra } from "../billing";

export const STANDARD_MODEL = "deepseek/deepseek-v4-flash-0731";
export const ULTRA_MODEL = "deepseek/deepseek-v4-pro-0813";
export type ChatModelMode = "auto" | "standard" | "ultra";
export type ChatFont = "default" | "sans" | "serif" | "mono" | "rounded";
export type ChatReasoning = { enabled: false; exclude: true }
  | { enabled: true; effort: "low"; exclude: true };

export interface ChatModelResolution {
  env: Env;
  mode: ChatModelMode;
  modelId: string;
  displayName: "Meek Standard" | "Meek Ultra";
  reasoning: ChatReasoning;
  reasoningEnabled: boolean;
  maxTokens: number;
}

export interface ChatModelPreferences {
  conversationId: string;
  mode: ChatModelMode;
  effectiveModel: "Meek Standard" | "Meek Ultra";
  modelId: string;
  ultra: boolean;
  availableModes: ChatModelMode[];
  chatFont: ChatFont;
}

interface PreferenceRow { mode: ChatModelMode | null; chat_font: ChatFont | null; user_turn: number }
type Complexity = "simple" | "complex" | "demanding";

// These are hard shared limits, not prompts or client-side restrictions. A
// reservation remains spent after provider failure to bound retried requests.
export const AUTOMATIC_MODEL_LIMITS = {
  free: { reasoningPerDay: 4, ultraPerDay: 1, minimumTurnGap: 4 },
  ultra: { reasoningPerDay: 40, ultraPerDay: 12, minimumTurnGap: 3 }
} as const;

export function classifyChatComplexity(content: string, thoughtfulCharacter = false): Complexity {
  const text = content.trim().toLowerCase();
  const words = text.split(/\s+/).filter(Boolean).length;
  // Brief conversational turns and long but routine descriptions remain fast.
  if (text.length < 120 || words < 20) return "simple";
  const reasoningRequest = /\b(?:work out|figure out|reason through|step.by.step|solve|prove|deduce|analy[sz]e|reconcile|compare|trade.?offs?|consequences|contradiction|strategy)\b/.test(text);
  const constraints = (text.match(/\b(?:because|unless|although|however|provided that|but|if|while|despite|instead|must|cannot|can't)\b/g) ?? []).length;
  const sceneChange = /\b(?:years? later|months? later|days? later|new scene|scene changes?|meanwhile|flashback|time skip|time jump|suddenly discovers?|everything changes?|reveals? that)\b/.test(text);
  const emotionalConflict = /\b(?:conflicted|betray(?:al|ed)|forgive|loyalty|can't decide|cannot decide|what should i do|torn between|moral dilemma)\b/.test(text);
  if (reasoningRequest && constraints >= 2 && words >= 45 || sceneChange && constraints >= 3 && words >= 70) return "demanding";
  if (reasoningRequest && constraints >= 1 || sceneChange && words >= 40
    || thoughtfulCharacter && emotionalConflict && constraints >= 1 && words >= 35) return "complex";
  return "simple";
}

function modelResolution(env: Env, mode: ChatModelMode, tier: "standard" | "ultra", reasoningEnabled = false): ChatModelResolution {
  const modelId = tier === "ultra" ? ULTRA_MODEL : STANDARD_MODEL;
  return {
    env: {
      ...env,
      OPENROUTER_MODEL: modelId,
      OPENROUTER_FALLBACK_MODELS: "",
      // A Flash-only provider restriction must not be inherited by Pro.
      OPENROUTER_PROVIDERS: tier === "ultra" ? env.OPENROUTER_ULTRA_PROVIDERS ?? "" : env.OPENROUTER_PROVIDERS ?? ""
    },
    mode,
    modelId,
    displayName: tier === "ultra" ? "Meek Ultra" : "Meek Standard",
    // Both dated DeepSeek checkpoints support low effort. Use the OpenRouter
    // reasoning envelope; never forward private reasoning text to the client.
    reasoning: reasoningEnabled ? { enabled: true, effort: "low", exclude: true } : { enabled: false, exclude: true },
    reasoningEnabled,
    // Includes hidden reasoning; this is deliberately bounded for chat latency.
    maxTokens: reasoningEnabled ? 2048 : 1200
  };
}

/** Cheap default for background messages and the group speaker router. */
export async function modelEnvironmentForUser(env: Env, _userId: string): Promise<Env> {
  return modelResolution(env, "auto", "standard").env;
}

async function reserveReasoning(env: Env, userId: string, scopeId: string, turn: number, tier: "standard" | "ultra", ultra: boolean): Promise<boolean> {
  if (!Number.isSafeInteger(turn) || turn < 0) return false;
  const limits = ultra ? AUTOMATIC_MODEL_LIMITS.ultra : AUTOMATIC_MODEL_LIMITS.free;
  const now = Date.now();
  const day = new Date(now).toISOString().slice(0, 10);
  // SQLite serializes this single write. The checks and reservation cannot race
  // across concurrent groups, Workers, direct chats, or repeated regenerations.
  const result = await env.DB.prepare(`INSERT OR IGNORE INTO chat_reasoning_reservations
    (user_id, scope_id, user_turn, usage_day, model_tier, reserved_at)
    SELECT ?, ?, ?, ?, ?, ?
    WHERE NOT EXISTS (SELECT 1 FROM chat_reasoning_reservations
      WHERE user_id = ? AND scope_id = ? AND user_turn > ?)
      AND (SELECT COUNT(*) FROM chat_reasoning_reservations WHERE user_id = ? AND usage_day = ?) < ?
      AND (? <> 'ultra' OR (SELECT COUNT(*) FROM chat_reasoning_reservations
        WHERE user_id = ? AND usage_day = ? AND model_tier = 'ultra') < ?)`)
    .bind(userId, scopeId, turn, day, tier, now,
      userId, scopeId, turn - limits.minimumTurnGap,
      userId, day, limits.reasoningPerDay, tier, userId, day, limits.ultraPerDay).run();
  return (result.meta?.changes ?? 0) === 1;
}

async function automaticModel(env: Env, userId: string, scopeId: string, content: string, turn: number,
  ultra: boolean, thoughtfulCharacter = false): Promise<ChatModelResolution> {
  const complexity = classifyChatComplexity(content, thoughtfulCharacter);
  if (complexity === "simple") return modelResolution(env, "auto", "standard");
  await ensureChatModelSchema(env);
  if (complexity === "demanding" && await reserveReasoning(env, userId, scopeId, turn, "ultra", ultra)) {
    return modelResolution(env, "auto", "ultra", true);
  }
  if (await reserveReasoning(env, userId, scopeId, turn, "standard", ultra)) {
    return modelResolution(env, "auto", "standard", true);
  }
  return modelResolution(env, "auto", "standard");
}

/** Caller must first authorize the direct/group scope and supply its saved user-turn count. */
export async function resolveAutomaticModel(env: Env, userId: string, scopeId: string, content: string, turn: number,
  thoughtfulCharacter = false): Promise<ChatModelResolution> {
  if (classifyChatComplexity(content, thoughtfulCharacter) === "simple") return modelResolution(env, "auto", "standard");
  return automaticModel(env, userId, scopeId, content, turn, await hasUltra(env, userId), thoughtfulCharacter);
}

async function ownedPreferences(context: RequestContext, conversationId: string): Promise<PreferenceRow> {
  assert(context.user, 401, "UNAUTHORIZED", "Sign in to continue.");
  await ensureChatModelSchema(context.env);
  const row = await context.env.DB.prepare(`SELECT p.mode, p.chat_font,
    (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.role = 'user') AS user_turn
    FROM conversations c LEFT JOIN chat_model_preferences p ON p.conversation_id = c.id
    WHERE c.id = ? AND c.owner_user_id = ?`)
    .bind(conversationId, context.user.userId).first<PreferenceRow>();
  assert(row, 404, "CONVERSATION_NOT_FOUND", "Conversation not found.");
  return row;
}

function preferenceDto(conversationId: string, row: PreferenceRow, ultra: boolean): ChatModelPreferences {
  // Expiry revokes manual Ultra and custom fonts without blocking the chat.
  const mode = row.mode === "ultra" && !ultra ? "auto" : row.mode ?? "auto";
  return {
    conversationId, mode, ultra,
    effectiveModel: mode === "ultra" ? "Meek Ultra" : "Meek Standard",
    modelId: mode === "ultra" ? ULTRA_MODEL : STANDARD_MODEL,
    availableModes: ultra ? ["auto", "standard", "ultra"] : ["auto", "standard"],
    chatFont: ultra ? row.chat_font ?? "default" : "default"
  };
}

export async function getChatModelPreferences(context: RequestContext, conversationId: string): Promise<ChatModelPreferences> {
  const row = await ownedPreferences(context, conversationId);
  return preferenceDto(conversationId, row, await hasUltra(context.env, context.user!.userId));
}

export async function updateChatModelPreferences(context: RequestContext, conversationId: string,
  changes: { mode?: unknown; chatFont?: unknown }): Promise<ChatModelPreferences> {
  assert(changes.mode !== undefined || changes.chatFont !== undefined, 400, "INVALID_REQUEST", "Choose a model or chat font.");
  if (changes.mode !== undefined) assert(["auto", "standard", "ultra"].includes(String(changes.mode)), 400, "INVALID_MODEL_MODE", "Choose Auto, Meek Standard, or Meek Ultra.");
  if (changes.chatFont !== undefined) assert(["default", "sans", "serif", "mono", "rounded"].includes(String(changes.chatFont)), 400, "INVALID_CHAT_FONT", "Choose an available chat font.");
  const row = await ownedPreferences(context, conversationId);
  const ultra = await hasUltra(context.env, context.user!.userId);
  assert(changes.mode !== "ultra" || ultra, 403, "ULTRA_REQUIRED", "Meek Ultra requires an Ultra subscription.");
  assert(changes.chatFont === undefined || changes.chatFont === "default" || ultra, 403, "ULTRA_REQUIRED", "Custom chat fonts require an Ultra subscription.");
  const current = preferenceDto(conversationId, row, ultra);
  const mode = (changes.mode ?? current.mode) as ChatModelMode;
  const chatFont = (changes.chatFont ?? current.chatFont) as ChatFont;
  await context.env.DB.prepare(`INSERT INTO chat_model_preferences (conversation_id, mode, chat_font, updated_at)
    SELECT id, ?, ?, ? FROM conversations WHERE id = ? AND owner_user_id = ?
    ON CONFLICT(conversation_id) DO UPDATE SET mode = excluded.mode, chat_font = excluded.chat_font, updated_at = excluded.updated_at`)
    .bind(mode, chatFont, Date.now(), conversationId, context.user!.userId).run();
  return preferenceDto(conversationId, { ...row, mode, chat_font: chatFont }, ultra);
}

export async function resolveChatModel(context: RequestContext, conversationId: string, latestUserContent = "",
  _conversationVersion?: number, thoughtfulCharacter = false, appendingUserTurn = false): Promise<ChatModelResolution> {
  const row = await ownedPreferences(context, conversationId);
  if (row.mode === "standard") return modelResolution(context.env, "standard", "standard");
  const ultra = await hasUltra(context.env, context.user!.userId);
  if (row.mode === "ultra" && ultra) return modelResolution(context.env, "ultra", "ultra", classifyChatComplexity(latestUserContent, thoughtfulCharacter) !== "simple");
  return automaticModel(context.env, context.user!.userId, `conversation:${conversationId}`,
    latestUserContent, row.user_turn + (appendingUserTurn ? 1 : 0), ultra, thoughtfulCharacter);
}
