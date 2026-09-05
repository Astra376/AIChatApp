import type { RequestContext } from "../../env";
import { ensureConversationStreamingSchema } from "../../db/ensureConversationStreamingSchema";
import { getCharacterById, incrementCharacterActivity } from "../../db/queries/characters";
import {
  claimConversationRun,
  deleteMessagesAfter,
  finishStoppedConversationRun,
  getConversationById,
  getConversationSummaryById,
  getMessageById,
  insertMessage,
  insertRegeneration,
  listMessages,
  listRegenerationsForConversation,
  releaseConversationRun,
  updateConversationActivity,
  updateMessageContent,
  updateMessageSelection,
  updateRegenerationContent
} from "../../db/queries/conversations";
import type { StoppedReplySnapshot } from "../../db/queries/conversations";
import { AppError, forbidden } from "../../lib/errors";
import { createId } from "../../lib/ids";
import { streamChatText } from "../../providers/openrouter";
import { editTranscriptMessage, requireLatestAssistant, requireRegenerationSelection, rewindTranscript } from "./rules";
import {
  buildCharacterMemoryPrompt,
  composeCharacterSystemPrompt,
  scheduleCharacterMemoryConsolidation
} from "./memory";
import { formatRoleplayMessage } from "./formatRoleplay";

// Provider attempts are bounded (25s idle, 90s total). Keep a final safety
// lease beyond their maximum combined duration, rather than a four-minute lock.
const RUN_LOCK_WINDOW_MS = 130_000;
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
  const messages = await listMessages(context.env, conversationId);
  const regenerations = await listRegenerationsForConversation(context.env, conversationId);
  const grouped = new Map<string, typeof regenerations>();
  for (const regeneration of regenerations) {
    const list = grouped.get(regeneration.message_id) ?? [];
    list.push(regeneration);
    grouped.set(regeneration.message_id, list);
  }

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
    regenerations: (grouped.get(message.id) ?? []).map((regeneration) => ({
      id: regeneration.id,
      messageId: regeneration.message_id,
      content: regeneration.content,
      createdAt: regeneration.created_at
    }))
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
  conversationId: string,
  options: {
    untilPosition?: number;
    appendedUserContent?: string;
  } = {}
) {
  const conversation = await requireOwnedConversation(context, conversationId);
  const character = await getCharacterById(context.env, context.user!.userId, conversation.character_id);
  if (!character) {
    throw new AppError(404, "CHARACTER_NOT_FOUND", "Character not found.");
  }
  const transcript = await loadTranscript(context, conversationId);
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
  const memoryPrompt = await buildCharacterMemoryPrompt(context, conversationId);
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
  context: RequestContext,
  messages: Array<{ role: "system" | "user" | "assistant"; content: string }>,
  onChunk: (chunk: string) => void,
  signal: AbortSignal
): Promise<string> {
  let fullText = "";
  for await (const chunk of streamChatText(context.env, messages, signal)) {
    fullText += chunk;
    onChunk(chunk);
  }

  const finalText = formatRoleplayMessage(fullText);
  if (!finalText) {
    throw new AppError(502, "OPENROUTER_EMPTY", "The model returned an empty response.");
  }

  return finalText;
}

export async function editMessage(context: RequestContext, messageId: string, newContent: string) {
  const message = await getMessageById(context.env, messageId);
  if (!message) {
    throw new AppError(404, "MESSAGE_NOT_FOUND", "Message not found.");
  }
  const conversation = await requireOwnedConversation(context, message.conversation_id);
  assertConversationUnlocked(conversation);

  const transcript = await loadTranscript(context, message.conversation_id);
  let target: { targetMessageId: string; targetRegenerationId: string | null };
  try {
    target = editTranscriptMessage(transcript, messageId, newContent);
  } catch (error) {
    throw new AppError(400, "CHAT_RULE_ERROR", (error as Error).message);
  }

  const now = Date.now();
  if (target.targetRegenerationId) {
    await updateRegenerationContent(context.env, target.targetRegenerationId, newContent.trim());
    await updateMessageSelection(context.env, {
      messageId: target.targetMessageId,
      selectedRegenerationId: target.targetRegenerationId,
      updatedAt: now,
      edited: true
    });
  } else {
    await updateMessageContent(context.env, {
      messageId: target.targetMessageId,
      content: newContent.trim(),
      edited: true,
      updatedAt: now
    });
  }
  await updateConversationActivity(context.env, message.conversation_id, now);
  scheduleCharacterMemoryConsolidation(context, message.conversation_id, message.position);
}

export async function rewindConversation(context: RequestContext, messageId: string) {
  const message = await getMessageById(context.env, messageId);
  if (!message) {
    throw new AppError(404, "MESSAGE_NOT_FOUND", "Message not found.");
  }
  const conversation = await requireOwnedConversation(context, message.conversation_id);
  assertConversationUnlocked(conversation);

  const transcript = await loadTranscript(context, message.conversation_id);
  let remaining: TranscriptMessage[];
  try {
    remaining = rewindTranscript(transcript, messageId) as TranscriptMessage[];
  } catch (error) {
    throw new AppError(400, "CHAT_RULE_ERROR", (error as Error).message);
  }
  const last = remaining.at(-1);
  await deleteMessagesAfter(context.env, message.conversation_id, last?.position ?? -1);
  await updateConversationActivity(context.env, message.conversation_id, Date.now());
  scheduleCharacterMemoryConsolidation(
    context,
    message.conversation_id,
    (last?.position ?? -1) + 1
  );
}

export async function selectRegeneration(context: RequestContext, messageId: string, regenerationId: string | null) {
  const message = await getMessageById(context.env, messageId);
  if (!message) {
    throw new AppError(404, "MESSAGE_NOT_FOUND", "Message not found.");
  }
  const conversation = await requireOwnedConversation(context, message.conversation_id);
  assertConversationUnlocked(conversation);

  const transcript = await loadTranscript(context, message.conversation_id);
  if (regenerationId != null) {
    try {
      requireRegenerationSelection(transcript, messageId, regenerationId);
    } catch (error) {
      throw new AppError(400, "CHAT_RULE_ERROR", (error as Error).message);
    }
  }
  await updateMessageSelection(context.env, {
    messageId,
    selectedRegenerationId: regenerationId,
    updatedAt: Date.now()
  });
  await updateConversationActivity(context.env, message.conversation_id, Date.now());
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
    const { character, transcript, messages } = await buildAssistantContext(context, conversationId);
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
            context,
            continuationMessages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal
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
          await updateConversationActivity(context.env, conversationId, assistantNow);
          await incrementCharacterActivity(context.env, character.id, assistantNow);

          const summary = await getConversationSummaryById(context.env, context.user!.userId, conversationId);
          const updatedConversation = await getConversationById(context.env, conversationId);
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
        "Cache-Control": "no-store"
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
  await requireOwnedConversation(context, conversationId);
  const runId = await acquireConversationRun(context, conversationId);
  let unlinkRequestAbort = () => {};
  let leaseReleased = false;

  try {
    const duplicate = await getMessageById(context.env, userMessageId);
    if (duplicate) {
      throw new AppError(409, "DUPLICATE_MESSAGE_ID", "This message has already been sent.");
    }

    const { conversation, character, transcript, messages } = await buildAssistantContext(context, conversationId, {
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
            context,
            messages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal
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
          await updateConversationActivity(context.env, conversationId, assistantNow);
          await incrementCharacterActivity(context.env, character.id, assistantNow);

          const summary = await getConversationSummaryById(context.env, context.user!.userId, conversationId);
          const updatedConversation = await getConversationById(context.env, conversationId);
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
        "Cache-Control": "no-store"
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
  await requireOwnedConversation(context, message.conversation_id);
  const runId = await acquireConversationRun(context, message.conversation_id);
  let unlinkRequestAbort = () => {};
  let leaseReleased = false;

  try {
    const transcript = await loadTranscript(context, message.conversation_id);
    let latest: TranscriptMessage;
    try {
      latest = requireLatestAssistant(transcript, messageId) as TranscriptMessage;
    } catch (error) {
      throw new AppError(400, "CHAT_RULE_ERROR", (error as Error).message);
    }

    const { conversation, messages } = await buildAssistantContext(context, message.conversation_id, {
      untilPosition: latest.position
    });
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
            context,
            messages,
            (chunk) => {
              partialText += chunk;
              safeEnqueue(controller, {
                type: "delta",
                runId,
                textDelta: chunk
              });
            },
            abortController.signal
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

          const summary = await getConversationSummaryById(context.env, context.user!.userId, message.conversation_id);
          const updatedConversation = await getConversationById(context.env, message.conversation_id);
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
        "Cache-Control": "no-store"
      }
    });
  } catch (error) {
    unlinkRequestAbort();
    await releaseConversationRun(context.env, message.conversation_id, runId);
    throw error;
  }
}
