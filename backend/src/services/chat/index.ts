import type { Env, RequestContext } from "../../env";
import { ensureConversationStreamingSchema } from "../../db/ensureConversationStreamingSchema";
import { getCharacterById, incrementCharacterActivity } from "../../db/queries/characters";
import {
  claimConversationRun,
  finishStoppedConversationRun,
  getConversationById,
  getConversationSummaryById,
  getMessageById,
  insertMessage,
  insertRegeneration,
  listContextMessages,
  releaseConversationRun,
  updateConversationActivity,
  updateMessageSelection
} from "../../db/queries/conversations";
import type { StoppedReplySnapshot } from "../../db/queries/conversations";
import { AppError, forbidden } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { streamChatText } from "../../providers/openrouter";
import { requireLatestAssistant } from "./rules";
import { editMessageAtomically, rewindToMessageAtomically, selectRegenerationAtomically } from "../../db/queries/transcriptMutations";
import { hasUltra } from "../billing";
import {
  buildCharacterMemoryPrompt,
  composeCharacterSystemPrompt,
  scheduleCharacterMemoryConsolidation
} from "./memory";
import { formatRoleplayMessage } from "./formatRoleplay";

// Provider work has a single 60-second budget across all attempts. Leave time
// for D1 finalization, while abandoned runs expire without a multi-minute lock.
const RUN_LOCK_WINDOW_MS = 75_000;
const MAX_MODEL_INPUT_CHARACTERS = 200_000;
const MIN_RECENT_TRANSCRIPT_CHARACTERS = 16_000;

type TranscriptMessage = {
  id: string;
  conversationId: string;
  position: number;
  role: "user" | "assistant";
  content: string;
  edited: boolean;
  createdAt: number;
  updatedAt: number;
  selectedRegenerationId: string | null;
  regenerations: Array<{
    id: string;
    messageId: string;
    content: string;
    createdAt: number;
  }>;
};

function toConversationSummary(record: NonNullable<Awaited<ReturnType<typeof getConversationSummaryById>>>) {
  return {
    id: record.id,
    characterId: record.character_id,
    characterName: record.character_name,
    characterAvatarUrl: record.character_avatar_url,
    updatedAt: record.updated_at,
    startedAt: record.started_at,
    lastMessageAt: record.last_message_at,
    lastPreview: record.last_preview
  };
}

function toMessageDto(message: {
  id: string;
  conversationId: string;
  position: number;
  role: "user" | "assistant";
  content: string;
  edited: boolean;
  createdAt: number;
  updatedAt: number;
  selectedRegenerationId: string | null;
  regenerations?: Array<{
    id: string;
    messageId: string;
    content: string;
    createdAt: number;
  }>;
}) {
  return {
    id: message.id,
    conversationId: message.conversationId,
    position: message.position,
    role: message.role,
    content: message.content,
    edited: message.edited,
    createdAt: message.createdAt,
    updatedAt: message.updatedAt,
    selectedRegenerationId: message.selectedRegenerationId,
    regenerations: (message.regenerations ?? []).map((regeneration) => ({
      id: regeneration.id,
      messageId: regeneration.messageId,
      content: regeneration.content,
      createdAt: regeneration.createdAt
    }))
  };
}

function toStreamError(error: unknown): { code: string; message: string } {
  if (error instanceof AppError) {
    return {
      code: error.code,
      message: error.message
    };
  }

  return {
    code: "INTERNAL_ERROR",
    message: "Something went wrong."
  };
}

async function loadTranscript(context: RequestContext, conversationId: string): Promise<TranscriptMessage[]> {
  const messages = await listContextMessages(context.env, conversationId);

  return messages.map((message) => ({
    id: message.id,
    conversationId: message.conversation_id,
    position: message.position,
    role: message.role,
    content: message.content,
    edited: Boolean(message.edited),
    createdAt: message.created_at,
    updatedAt: message.updated_at,
    selectedRegenerationId: message.selected_regeneration_id,
    regenerations: []
  }));
}

function visibleContent(message: TranscriptMessage): string {
  return (
    message.regenerations.find((regeneration) => regeneration.id === message.selectedRegenerationId)?.content ??
    message.content
  );
}

export function selectRecentMessages<T extends { content: string }>(
  messages: T[],
  maxCharacters: number
): T[] {
  if (maxCharacters <= 0) return [];
  const selected: T[] = [];
  let usedCharacters = 0;
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    const estimatedSize = message.content.length + 24;
    if (selected.length > 0 && usedCharacters + estimatedSize > maxCharacters) break;
    selected.unshift(message);
    usedCharacters += estimatedSize;
  }
  return selected;
}

export function messagesForContinuation(
  messages: Array<{ role: "system" | "user" | "assistant"; content: string }>,
  latestRole: "user" | "assistant" | undefined
) {
  if (latestRole === "user") return messages;
  return [
    ...messages,
    {
      role: "user" as const,
      content: "Continue the scene naturally in character. Do not summarize. Do not wait for me to speak first."
    }
  ];
}

async function requireOwnedConversation(context: RequestContext, conversationId: string) {
  await ensureConversationStreamingSchema(context.env);
  const conversation = await getConversationById(context.env, conversationId);
  if (!conversation) {
    throw new AppError(404, "CONVERSATION_NOT_FOUND", "Conversation not found.");
  }
  if (conversation.owner_user_id !== context.user!.userId) {
    forbidden("Conversations are private to their owner.");
  }
  return conversation;
}

function assertConversationUnlocked(conversation: Awaited<ReturnType<typeof getConversationById>>) {
  if (
    conversation?.active_run_id &&
    conversation.active_run_expires_at != null &&
    conversation.active_run_expires_at > Date.now()
  ) {
    throw new AppError(409, "STREAM_IN_PROGRESS", "Wait for the current reply to finish before changing the transcript.");
  }
}

async function acquireConversationRun(context: RequestContext, conversationId: string): Promise<string> {
  const runId = createId("run");
  const now = Date.now();
  const claimed = await claimConversationRun(context.env, conversationId, runId, now, now + RUN_LOCK_WINDOW_MS);
  if (!claimed) {
    throw new AppError(409, "STREAM_IN_PROGRESS", "Wait for the current reply to finish before changing the transcript.");
  }
  return runId;
}

async function buildAssistantContext(
  context: RequestContext,
  conversation: NonNullable<Awaited<ReturnType<typeof getConversationById>>>,
  options: {
    untilPosition?: number;
    appendedUserContent?: string;
  } = {}
) {
  const conversationId = conversation.id;
  const [character, transcript, memoryPrompt, ultra] = await Promise.all([
    getCharacterById(context.env, context.user!.userId, conversation.character_id),
    loadTranscript(context, conversationId),
    buildCharacterMemoryPrompt(context, conversationId),
    hasUltra(context.env, context.user!.userId)
  ]);
  if (!character) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  const fullVisibleTranscript = transcript
    .filter((message) => options.untilPosition == null || message.position < options.untilPosition)
    .map((message) => ({
      role: message.role,
      content: visibleContent(message)
    }));

  if (options.appendedUserContent) {
    fullVisibleTranscript.push({
      role: "user",
      content: options.appendedUserContent
    });
  }
  const systemContent = composeCharacterSystemPrompt(character.system_prompt, memoryPrompt);
  const transcriptBudget = Math.max(
    MIN_RECENT_TRANSCRIPT_CHARACTERS,
    MAX_MODEL_INPUT_CHARACTERS - systemContent.length
  );
  const visibleTranscript = selectRecentMessages(fullVisibleTranscript, transcriptBudget);

  return {
    conversation,
    character,
    transcript,
    modelEnv: ultra && context.env.OPENROUTER_ULTRA_MODEL ? {
      ...context.env,
      OPENROUTER_MODEL: context.env.OPENROUTER_ULTRA_MODEL,
      OPENROUTER_FALLBACK_MODELS: "",
      // Venice does not currently serve the verified Pro 0813 model. Use the
      // Pro provider policy rather than inheriting a Flash-only restriction.
      OPENROUTER_PROVIDERS: context.env.OPENROUTER_ULTRA_PROVIDERS ?? ""
    } : context.env,
    messages: [
      {
        role: "system" as const,
        content: systemContent
      },
      ...visibleTranscript
    ]
  };
}

function sseEvent(payload: object): Uint8Array {
  return new TextEncoder().encode(`data: ${JSON.stringify(payload)}\n\n`);
}

function safeEnqueue(controller: ReadableStreamDefaultController<Uint8Array>, payload: object): void {
  try {
    controller.enqueue(sseEvent(payload));
  } catch {
    // Ignore enqueue failures after the client has disconnected.
  }
}

function safeClose(controller: ReadableStreamDefaultController<Uint8Array>): void {
  try {
    controller.close();
  } catch {
    // Ignore double-close errors.
  }
}

function createLinkedAbortController(sourceSignal: AbortSignal): {
  abortController: AbortController;
  unlink: () => void;
} {
  const abortController = new AbortController();
  const forwardAbort = () => {
    if (!abortController.signal.aborted) {
      abortController.abort(sourceSignal.reason);
    }
  };

  if (sourceSignal.aborted) {
    forwardAbort();
  } else {
    sourceSignal.addEventListener("abort", forwardAbort, { once: true });
  }

  let linked = !sourceSignal.aborted;
  return {
    abortController,
    unlink: () => {
      if (!linked) return;
      linked = false;
      sourceSignal.removeEventListener("abort", forwardAbort);
    }
  };
}

function throwIfAborted(signal: AbortSignal): void {
  if (!signal.aborted) return;
  if (signal.reason instanceof Error) throw signal.reason;
  throw new DOMException("The operation was aborted.", "AbortError");
}

async function finishConversationStream(
  context: RequestContext,
  conversationId: string,
  runId: string,
  unlinkRequestAbort: () => void,
  controller: ReadableStreamDefaultController<Uint8Array>,
  leaseAlreadyReleased = false
): Promise<void> {
  unlinkRequestAbort();
  try {
    if (!leaseAlreadyReleased) {
      await releaseConversationRun(context.env, conversationId, runId);
    }
  } catch (error) {
    // A lease expiry remains the last-resort recovery path if D1 itself is
    // unavailable. Do not leave the response open or retain abort listeners.
    console.error("Failed to release conversation stream lease.", error);
  } finally {
    safeClose(controller);
  }
}

async function releaseConversationRunBeforeTerminal(
  context: RequestContext,
  conversationId: string,
  runId: string
): Promise<boolean> {
  try {
    await releaseConversationRun(context.env, conversationId, runId);
    return true;
  } catch (error) {
    // The final cleanup path retries this release. Logging here keeps a D1
    // failure observable without suppressing the terminal stream event.
    console.error("Failed to release conversation stream lease before terminal event.", error);
    return false;
  }
}

async function streamAssistantReply(
  messages: Array<{ role: "system" | "user" | "assistant"; content: string }>,
  onChunk: (chunk: string) => void,
  signal: AbortSignal,
  modelEnv: Env
): Promise<string> {
  let fullText = "";
  for await (const chunk of streamChatText(modelEnv, messages, signal)) {
    fullText += chunk;
    onChunk(chunk);
  }

  const finalText = formatRoleplayMessage(fullText);
  if (!finalText) {
    throw new AppError(502, "OPENROUTER_EMPTY", "The model returned an empty response.");
  }

  return finalText;
}

async function requireMutableMessage(context: RequestContext, messageId: string) {
  const message = await getMessageById(context.env, messageId);
  if (!message) throw new AppError(404, "MESSAGE_NOT_FOUND", "Message not found.");
  const conversation = await requireOwnedConversation(context, message.conversation_id);
  assertConversationUnlocked(conversation);
  return message;
}

export async function editMessage(context: RequestContext, messageId: string, newContent: string) {
  const content = newContent.trim();
  if (!content) throw new AppError(400, "CHAT_RULE_ERROR", "Message content cannot be empty.");
  const message = await requireMutableMessage(context, messageId);
  const updated = await editMessageAtomically(
    context.env, context.user!.userId, message.conversation_id, messageId, content, Date.now()
  );
  if (!updated) throw new AppError(409, "TRANSCRIPT_CHANGED", "The conversation changed. Please try again.");
  scheduleCharacterMemoryConsolidation(context, message.conversation_id, message.position);
}

export async function rewindConversation(context: RequestContext, messageId: string) {
  const message = await requireMutableMessage(context, messageId);
  const deleted = await rewindToMessageAtomically(context.env, context.user!.userId, message.conversation_id, messageId, Date.now());
  if (deleted > 0) scheduleCharacterMemoryConsolidation(context, message.conversation_id, message.position + 1);
}

export async function selectRegeneration(context: RequestContext, messageId: string, regenerationId: string | null) {
  const message = await requireMutableMessage(context, messageId);
  const selected = await selectRegenerationAtomically(
    context.env, context.user!.userId, message.conversation_id, messageId, regenerationId, Date.now()
  );
  if (!selected) throw new AppError(400, "CHAT_RULE_ERROR", "Only a version of the latest assistant reply can be selected.");
  scheduleCharacterMemoryConsolidation(context, message.conversation_id, message.position);
}

export async function cancelAssistantRun(context: RequestContext, conversationId: string, runId: string, partial?: StoppedReplySnapshot) {
  await requireOwnedConversation(context, conversationId);
  // Match the exact run so delayed stop requests cannot cancel a newer reply.
  // Assistant writes are fenced by this same lease in their INSERT statement.
  await finishStoppedConversationRun(context.env, conversationId, runId,
    partial ? { ...partial, text: formatRoleplayMessage(partial.text) } : undefined);
}

export async function continueAssistantAndStream(context: RequestContext, conversationId: string): Promise<Response> {
  const conversation = await requireOwnedConversation(context, conversationId);
  const runId = await acquireConversationRun(context, conversationId);
  let unlinkRequestAbort = () => {};
  let leaseReleased = false;

  try {
    const { character, transcript, messages, modelEnv } = await buildAssistantContext(context, conversation);
    const continuationMessages = messagesForContinuation(messages, transcript.at(-1)?.role);
    const assistantMessageId = `message_${runId}`;
    const assistantPosition = (transcript.at(-1)?.position ?? -1) + 1;
    const linkedAbort = createLinkedAbortController(context.request.signal);
    const abortController = linkedAbort.abortController;
    unlinkRequestAbort = linkedAbort.unlink;
    let partialText = "";
    let finalizationPhase: "streaming" | "full" | "partial" | "settled" = "streaming";

    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        safeEnqueue(controller, {
          type: "accepted_continue",
          runId,
          conversationVersion: conversation.version,
          assistantMessageId
        });

        try {
          const finalText = await streamAssistantReply(
            continuationMessages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal,
            modelEnv
          );
          throwIfAborted(abortController.signal);
          finalizationPhase = "full";

          const assistantNow = Date.now();
          const assistantMessage = {
            id: assistantMessageId,
            conversationId,
            position: assistantPosition,
            role: "assistant" as const,
            content: finalText,
            edited: false,
            createdAt: assistantNow,
            updatedAt: assistantNow,
            selectedRegenerationId: null
          };

          await insertMessage(context.env, {
            id: assistantMessage.id,
            conversation_id: assistantMessage.conversationId,
            position: assistantMessage.position,
            role: assistantMessage.role,
            content: assistantMessage.content,
            edited: 0,
            created_at: assistantMessage.createdAt,
            updated_at: assistantMessage.updatedAt,
            selected_regeneration_id: null
          }, runId);
          await Promise.all([
            updateConversationActivity(context.env, conversationId, assistantNow),
            incrementCharacterActivity(context.env, character.id, assistantNow)
          ]);

          const [summary, updatedConversation] = await Promise.all([
            getConversationSummaryById(context.env, context.user!.userId, conversationId),
            getConversationById(context.env, conversationId)
          ]);
          if (!summary || !updatedConversation) {
            throw new AppError(500, "CONVERSATION_SYNC_FAILED", "Conversation state could not be finalized.");
          }

          leaseReleased = await releaseConversationRunBeforeTerminal(context, conversationId, runId);
          safeEnqueue(controller, {
            type: "completed_send",
            runId,
            conversationVersion: updatedConversation.version,
            assistantMessage: toMessageDto(assistantMessage),
            conversationSummary: toConversationSummary(summary)
          });
          scheduleCharacterMemoryConsolidation(context, conversationId);
          finalizationPhase = "settled";
        } catch (error) {
          if (finalizationPhase === "streaming" && (abortController.signal.aborted || partialText.trim())) {
            finalizationPhase = "partial";
            const stoppedText = formatRoleplayMessage(partialText);
            if (stoppedText) {
              const stoppedAt = Date.now();
              await insertMessage(context.env, {
                id: assistantMessageId,
                conversation_id: conversationId,
                position: assistantPosition,
                role: "assistant",
                content: stoppedText,
                edited: 0,
                created_at: stoppedAt,
                updated_at: stoppedAt,
                selected_regeneration_id: null
              }, runId);
              await updateConversationActivity(context.env, conversationId, stoppedAt);
              await incrementCharacterActivity(context.env, character.id, stoppedAt);
              scheduleCharacterMemoryConsolidation(context, conversationId);
            }
            finalizationPhase = "settled";
          }
          if (!abortController.signal.aborted) {
            leaseReleased = await releaseConversationRunBeforeTerminal(context, conversationId, runId);
            safeEnqueue(controller, {
              type: "failed",
              runId,
              ...toStreamError(error)
            });
          }
        } finally {
          await finishConversationStream(
            context,
            conversationId,
            runId,
            unlinkRequestAbort,
            controller,
            leaseReleased
          );
        }
      },
      cancel(reason) {
        if (!abortController.signal.aborted) {
          abortController.abort(reason);
        }
      }
    });

    return new Response(stream, {
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no"
      }
    });
  } catch (error) {
    unlinkRequestAbort();
    await releaseConversationRun(context.env, conversationId, runId);
    throw error;
  }
}

export async function sendMessageAndStream(
  context: RequestContext,
  conversationId: string,
  userMessageId: string,
  content: string
): Promise<Response> {
  const conversation = await requireOwnedConversation(context, conversationId);
  const runId = await acquireConversationRun(context, conversationId);
  let unlinkRequestAbort = () => {};
  let leaseReleased = false;

  try {
    const duplicate = await getMessageById(context.env, userMessageId);
    if (duplicate) {
      throw new AppError(409, "DUPLICATE_MESSAGE_ID", "This message has already been sent.");
    }

    const { character, transcript, messages, modelEnv } = await buildAssistantContext(context, conversation, {
      appendedUserContent: content
    });
    const assistantMessageId = `message_${runId}`;
    const userNow = Date.now();
    const userPosition = (transcript.at(-1)?.position ?? -1) + 1;
    const userMessage = {
      id: userMessageId,
      conversationId,
      position: userPosition,
      role: "user" as const,
      content,
      edited: false,
      createdAt: userNow,
      updatedAt: userNow,
      selectedRegenerationId: null
    };

    await insertMessage(context.env, {
      id: userMessage.id,
      conversation_id: userMessage.conversationId,
      position: userMessage.position,
      role: userMessage.role,
      content: userMessage.content,
      edited: 0,
      created_at: userMessage.createdAt,
      updated_at: userMessage.updatedAt,
      selected_regeneration_id: null
    }, runId);

    const linkedAbort = createLinkedAbortController(context.request.signal);
    const abortController = linkedAbort.abortController;
    unlinkRequestAbort = linkedAbort.unlink;
    let partialText = "";
    let finalizationPhase: "streaming" | "full" | "partial" | "settled" = "streaming";
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        safeEnqueue(controller, {
          type: "accepted_send",
          runId,
          conversationVersion: conversation.version,
          userMessage: toMessageDto(userMessage),
          assistantMessageId
        });

        try {
          const finalText = await streamAssistantReply(
            messages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal,
            modelEnv
          );
          throwIfAborted(abortController.signal);
          finalizationPhase = "full";

          const assistantNow = Date.now();
          const assistantMessage = {
            id: assistantMessageId,
            conversationId,
            position: userPosition + 1,
            role: "assistant" as const,
            content: finalText,
            edited: false,
            createdAt: assistantNow,
            updatedAt: assistantNow,
            selectedRegenerationId: null
          };

          await insertMessage(context.env, {
            id: assistantMessage.id,
            conversation_id: assistantMessage.conversationId,
            position: assistantMessage.position,
            role: assistantMessage.role,
            content: assistantMessage.content,
            edited: 0,
            created_at: assistantMessage.createdAt,
            updated_at: assistantMessage.updatedAt,
            selected_regeneration_id: null
          }, runId);
          await Promise.all([
            updateConversationActivity(context.env, conversationId, assistantNow),
            incrementCharacterActivity(context.env, character.id, assistantNow)
          ]);

          const [summary, updatedConversation] = await Promise.all([
            getConversationSummaryById(context.env, context.user!.userId, conversationId),
            getConversationById(context.env, conversationId)
          ]);
          if (!summary || !updatedConversation) {
            throw new AppError(500, "CONVERSATION_SYNC_FAILED", "Conversation state could not be finalized.");
          }

          leaseReleased = await releaseConversationRunBeforeTerminal(context, conversationId, runId);
          safeEnqueue(controller, {
            type: "completed_send",
            runId,
            conversationVersion: updatedConversation.version,
            assistantMessage: toMessageDto(assistantMessage),
            conversationSummary: toConversationSummary(summary)
          });
          scheduleCharacterMemoryConsolidation(context, conversationId);
          finalizationPhase = "settled";
        } catch (error) {
          if (finalizationPhase === "streaming" && (abortController.signal.aborted || partialText.trim())) {
            finalizationPhase = "partial";
            const stoppedText = formatRoleplayMessage(partialText);
            if (stoppedText) {
              const stoppedAt = Date.now();
              await insertMessage(context.env, {
                id: assistantMessageId,
                conversation_id: conversationId,
                position: userPosition + 1,
                role: "assistant",
                content: stoppedText,
                edited: 0,
                created_at: stoppedAt,
                updated_at: stoppedAt,
                selected_regeneration_id: null
              }, runId);
              await updateConversationActivity(context.env, conversationId, stoppedAt);
              await incrementCharacterActivity(context.env, character.id, stoppedAt);
              scheduleCharacterMemoryConsolidation(context, conversationId);
            }
            finalizationPhase = "settled";
          }
          if (!abortController.signal.aborted) {
            leaseReleased = await releaseConversationRunBeforeTerminal(context, conversationId, runId);
            safeEnqueue(controller, {
              type: "failed",
              runId,
              ...toStreamError(error)
            });
          }
        } finally {
          await finishConversationStream(
            context,
            conversationId,
            runId,
            unlinkRequestAbort,
            controller,
            leaseReleased
          );
        }
      },
      cancel(reason) {
        if (!abortController.signal.aborted) {
          abortController.abort(reason);
        }
      }
    });

    return new Response(stream, {
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no"
      }
    });
  } catch (error) {
    unlinkRequestAbort();
    await releaseConversationRun(context.env, conversationId, runId);
    throw error;
  }
}

export async function regenerateLatestAssistantAndStream(
  context: RequestContext,
  messageId: string
): Promise<Response> {
  const message = await getMessageById(context.env, messageId);
  if (!message) {
    throw new AppError(404, "MESSAGE_NOT_FOUND", "Message not found.");
  }
  const conversation = await requireOwnedConversation(context, message.conversation_id);
  const runId = await acquireConversationRun(context, message.conversation_id);
  let unlinkRequestAbort = () => {};
  let leaseReleased = false;

  try {
    const { transcript, messages, modelEnv } = await buildAssistantContext(context, conversation, { untilPosition: message.position });
    let latest: TranscriptMessage;
    try {
      latest = requireLatestAssistant(transcript, messageId) as TranscriptMessage;
    } catch (error) {
      throw new AppError(400, "CHAT_RULE_ERROR", (error as Error).message);
    }

    const regenerationId = `regen_${runId}`;
    const linkedAbort = createLinkedAbortController(context.request.signal);
    const abortController = linkedAbort.abortController;
    unlinkRequestAbort = linkedAbort.unlink;
    let partialText = "";
    let finalizationPhase: "streaming" | "full" | "partial" | "settled" = "streaming";

    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        safeEnqueue(controller, {
          type: "accepted_regenerate",
          runId,
          conversationVersion: conversation.version,
          messageId: latest.id,
          assistantMessageId: latest.id
        });

        try {
          const finalText = await streamAssistantReply(
            messages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal,
            modelEnv
          );
          throwIfAborted(abortController.signal);
          finalizationPhase = "full";

          const regenerationNow = Date.now();
          const regeneration = {
            id: regenerationId,
            messageId: latest.id,
            content: finalText,
            createdAt: regenerationNow
          };

          await insertRegeneration(context.env, {
            id: regeneration.id,
            message_id: regeneration.messageId,
            content: regeneration.content,
            created_at: regeneration.createdAt
          }, runId);
          await updateMessageSelection(context.env, {
            messageId: latest.id,
            selectedRegenerationId: regeneration.id,
            updatedAt: regenerationNow
          }, runId);
          await updateConversationActivity(context.env, message.conversation_id, regenerationNow);

          const [summary, updatedConversation] = await Promise.all([
            getConversationSummaryById(context.env, context.user!.userId, message.conversation_id),
            getConversationById(context.env, message.conversation_id)
          ]);
          if (!summary || !updatedConversation) {
            throw new AppError(500, "CONVERSATION_SYNC_FAILED", "Conversation state could not be finalized.");
          }

          leaseReleased = await releaseConversationRunBeforeTerminal(
            context,
            message.conversation_id,
            runId
          );
          safeEnqueue(controller, {
            type: "completed_regenerate",
            runId,
            conversationVersion: updatedConversation.version,
            messageId: latest.id,
            regeneration: {
              id: regeneration.id,
              messageId: regeneration.messageId,
              content: regeneration.content,
              createdAt: regeneration.createdAt
            },
            selectedRegenerationId: regeneration.id,
            conversationSummary: toConversationSummary(summary)
          });
          scheduleCharacterMemoryConsolidation(
            context,
            message.conversation_id,
            latest.position
          );
          finalizationPhase = "settled";
        } catch (error) {
          if (finalizationPhase === "streaming" && (abortController.signal.aborted || partialText.trim())) {
            finalizationPhase = "partial";
            const stoppedText = formatRoleplayMessage(partialText);
            if (stoppedText) {
              const stoppedAt = Date.now();
              await insertRegeneration(context.env, {
                id: regenerationId,
                message_id: latest.id,
                content: stoppedText,
                created_at: stoppedAt
              }, runId);
              await updateMessageSelection(context.env, {
                messageId: latest.id,
                selectedRegenerationId: regenerationId,
                updatedAt: stoppedAt
              }, runId);
              await updateConversationActivity(context.env, message.conversation_id, stoppedAt);
              scheduleCharacterMemoryConsolidation(
                context,
                message.conversation_id,
                latest.position
              );
            }
            finalizationPhase = "settled";
          }
          if (!abortController.signal.aborted) {
            leaseReleased = await releaseConversationRunBeforeTerminal(
              context,
              message.conversation_id,
              runId
            );
            safeEnqueue(controller, {
              type: "failed",
              runId,
              ...toStreamError(error)
            });
          }
        } finally {
          await finishConversationStream(
            context,
            message.conversation_id,
            runId,
            unlinkRequestAbort,
            controller,
            leaseReleased
          );
        }
      },
      cancel(reason) {
        if (!abortController.signal.aborted) {
          abortController.abort(reason);
        }
      }
    });

    return new Response(stream, {
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-store, no-transform",
        "X-Accel-Buffering": "no"
      }
    });
  } catch (error) {
    unlinkRequestAbort();
    await releaseConversationRun(context.env, message.conversation_id, runId);
    throw error;
  }
}
