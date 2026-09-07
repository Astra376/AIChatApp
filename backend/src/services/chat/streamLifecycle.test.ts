import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";

const schemaMocks = vi.hoisted(() => ({
  ensureConversationStreamingSchema: vi.fn()
}));

const characterMocks = vi.hoisted(() => ({
  getCharacterById: vi.fn(),
  incrementCharacterActivity: vi.fn()
}));

const conversationMocks = vi.hoisted(() => ({
  claimConversationRun: vi.fn(),
  deleteMessagesAfter: vi.fn(),
  getConversationById: vi.fn(),
  getConversationSummaryById: vi.fn(),
  getMessageById: vi.fn(),
  insertMessage: vi.fn(),
  insertRegeneration: vi.fn(),
  listContextMessages: vi.fn(),
  listRegenerationsForConversation: vi.fn(),
  releaseConversationRun: vi.fn(),
  updateConversationActivity: vi.fn(),
  updateMessageContent: vi.fn(),
  updateMessageSelection: vi.fn(),
  updateRegenerationContent: vi.fn()
}));

const openRouterMocks = vi.hoisted(() => ({
  streamChatText: vi.fn()
}));

const memoryMocks = vi.hoisted(() => ({
  buildCharacterMemoryPrompt: vi.fn(),
  composeCharacterSystemPrompt: vi.fn(),
  scheduleCharacterMemoryConsolidation: vi.fn()
}));

vi.mock("../../db/ensureConversationStreamingSchema", () => schemaMocks);
vi.mock("../../db/queries/characters", () => characterMocks);
vi.mock("../../db/queries/conversations", () => conversationMocks);
vi.mock("../../providers/openrouter", () => openRouterMocks);
vi.mock("./memory", () => memoryMocks);

import {
  continueAssistantAndStream,
  regenerateLatestAssistantAndStream,
  sendMessageAndStream
} from ".";

const CONVERSATION_ID = "conversation-1";
const CHARACTER_ID = "character-1";
const USER_ID = "user-1";
const USER_MESSAGE_ID = "message-user-new";
const EXISTING_USER_MESSAGE_ID = "message-user-existing";
const ASSISTANT_MESSAGE_ID = "message-assistant-existing";
const PARTIAL_TEXT = "partial reply";
const COMPLETE_TEXT = "complete reply";

type Operation = "SEND" | "CONTINUE" | "REGENERATE";
type StopPoint = "before first chunk" | "mid-stream" | "late finalization";

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T | PromiseLike<T>) => void;
  reject: (reason?: unknown) => void;
}

function deferred<T = void>(): Deferred<T> {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function abortError(signal: AbortSignal): Error {
  return signal.reason instanceof Error
    ? signal.reason
    : new DOMException("The operation was aborted.", "AbortError");
}

async function waitForAbort(signal: AbortSignal): Promise<never> {
  if (signal.aborted) throw abortError(signal);
  return new Promise<never>((_resolve, reject) => {
    signal.addEventListener("abort", () => reject(abortError(signal)), { once: true });
  });
}

function conversationRecord() {
  return {
    id: CONVERSATION_ID,
    owner_user_id: USER_ID,
    character_id: CHARACTER_ID,
    updated_at: 1_000,
    started_at: 500,
    last_message_at: 1_000,
    version: 7,
    active_run_id: null,
    active_run_expires_at: null
  };
}

function summaryRecord() {
  return {
    id: CONVERSATION_ID,
    character_id: CHARACTER_ID,
    character_name: "Character",
    character_avatar_url: null,
    updated_at: 2_000,
    started_at: 500,
    last_message_at: 2_000,
    last_preview: COMPLETE_TEXT,
    unread_count: 0,
    has_unread_badge: 0
  };
}

function userMessageRecord() {
  return {
    id: EXISTING_USER_MESSAGE_ID,
    conversation_id: CONVERSATION_ID,
    position: 0,
    role: "user" as const,
    content: "Hello",
    edited: 0,
    created_at: 600,
    updated_at: 600,
    selected_regeneration_id: null
  };
}

function assistantMessageRecord() {
  return {
    id: ASSISTANT_MESSAGE_ID,
    conversation_id: CONVERSATION_ID,
    position: 1,
    role: "assistant" as const,
    content: "Original reply",
    edited: 0,
    created_at: 700,
    updated_at: 700,
    selected_regeneration_id: null
  };
}

function createContext(): {
  context: RequestContext;
  requestAbortController: AbortController;
  removeAbortListener: ReturnType<typeof vi.spyOn>;
} {
  const requestAbortController = new AbortController();
  const request = new Request(`https://example.test/v1/conversations/${CONVERSATION_ID}/messages:stream`, {
    method: "POST",
    signal: requestAbortController.signal
  });
  const removeAbortListener = vi.spyOn(request.signal, "removeEventListener");

  return {
    context: {
      request,
      env: { DB: {} } as unknown as Env,
      url: new URL(request.url),
      params: {},
      user: {
        userId: USER_ID,
        tokenType: "access",
        exp: Number.MAX_SAFE_INTEGER
      }
    },
    requestAbortController,
    removeAbortListener
  };
}

function configureTranscript(operation: Operation): void {
  if (operation === "SEND") {
    conversationMocks.listContextMessages.mockResolvedValue([]);
    conversationMocks.getMessageById.mockResolvedValue(null);
    return;
  }

  if (operation === "CONTINUE") {
    conversationMocks.listContextMessages.mockResolvedValue([userMessageRecord()]);
    return;
  }

  const latestAssistant = assistantMessageRecord();
  conversationMocks.listContextMessages.mockResolvedValue([userMessageRecord(), latestAssistant]);
  conversationMocks.getMessageById.mockImplementation(async (_env, messageId: string) =>
    messageId === ASSISTANT_MESSAGE_ID ? latestAssistant : null
  );
}

async function startOperation(operation: Operation, context: RequestContext): Promise<Response> {
  if (operation === "SEND") {
    return sendMessageAndStream(context, CONVERSATION_ID, USER_MESSAGE_ID, "New user message");
  }
  if (operation === "CONTINUE") {
    return continueAssistantAndStream(context, CONVERSATION_ID);
  }
  return regenerateLatestAssistantAndStream(context, ASSISTANT_MESSAGE_ID);
}

function insertedMessages() {
  return conversationMocks.insertMessage.mock.calls.map((call) => call[1] as {
    id: string;
    role: "user" | "assistant";
    content: string;
  });
}

function insertedRegenerations() {
  return conversationMocks.insertRegeneration.mock.calls.map((call) => call[1] as {
    id: string;
    message_id: string;
    content: string;
  });
}

function configureProvider(stopPoint: StopPoint): {
  started: Deferred<void>;
  chunkConsumed: Deferred<void>;
} {
  const started = deferred();
  const chunkConsumed = deferred();

  openRouterMocks.streamChatText.mockImplementation(
    async function* (_env: Env, _messages: unknown, signal: AbortSignal) {
      started.resolve();
      if (stopPoint === "before first chunk") {
        await waitForAbort(signal);
      }

      yield stopPoint === "late finalization"
        ? `  ${COMPLETE_TEXT}  `
        : `  ${PARTIAL_TEXT}  `;
      chunkConsumed.resolve();

      if (stopPoint === "mid-stream") {
        await waitForAbort(signal);
      }
    }
  );

  return { started, chunkConsumed };
}

beforeEach(() => {
  vi.resetAllMocks();

  schemaMocks.ensureConversationStreamingSchema.mockResolvedValue(undefined);
  characterMocks.getCharacterById.mockResolvedValue({
    id: CHARACTER_ID,
    owner_user_id: "creator-1",
    owner_display_name: "Creator",
    name: "Character",
    tagline: "Tagline",
    greeting: "Greeting",
    description: "Description",
    system_prompt: "Stay in character.",
    definition_private: 0,
    visibility: "public",
    avatar_url: null,
    public_chat_count: 10,
    like_count: 3,
    liked_by_me: 0,
    last_active_at: 1_000,
    created_at: 100,
    updated_at: 200
  });
  characterMocks.incrementCharacterActivity.mockResolvedValue(undefined);

  conversationMocks.claimConversationRun.mockResolvedValue(true);
  conversationMocks.getConversationById.mockImplementation(async () => conversationRecord());
  conversationMocks.getConversationSummaryById.mockResolvedValue(summaryRecord());
  conversationMocks.insertMessage.mockResolvedValue(undefined);
  conversationMocks.insertRegeneration.mockResolvedValue(undefined);
  conversationMocks.listContextMessages.mockResolvedValue([]);
  conversationMocks.listRegenerationsForConversation.mockResolvedValue([]);
  conversationMocks.releaseConversationRun.mockResolvedValue(undefined);
  conversationMocks.updateConversationActivity.mockResolvedValue(undefined);
  conversationMocks.updateMessageSelection.mockResolvedValue(undefined);

  memoryMocks.buildCharacterMemoryPrompt.mockResolvedValue("");
  memoryMocks.composeCharacterSystemPrompt.mockImplementation(
    (systemPrompt: string, memoryPrompt: string) =>
      memoryPrompt ? `${systemPrompt}\n\n${memoryPrompt}` : systemPrompt
  );
});

describe.each<Operation>(["SEND", "CONTINUE", "REGENERATE"])(
  "%s stream lifecycle",
  (operation) => {
    it("exposes accepted and incremental delta events before generation completes", async () => {
      configureTranscript(operation);
      const finish = deferred();
      openRouterMocks.streamChatText.mockImplementation(async function* () {
        yield "first chunk";
        await finish.promise;
        yield " second chunk";
      });
      const { context } = createContext();
      const response = await startOperation(operation, context);
      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      expect(decoder.decode((await reader.read()).value)).toContain('"type":"accepted_');
      expect(decoder.decode((await reader.read()).value)).toContain('"textDelta":"first chunk"');
      expect(insertedMessages().filter((message) => message.role === "assistant")).toHaveLength(0);
      expect(insertedRegenerations()).toHaveLength(0);
      finish.resolve();
      let remaining = "";
      for (let next = await reader.read(); !next.done; next = await reader.read()) remaining += decoder.decode(next.value);
      expect(remaining).toContain('"textDelta":" second chunk"');
      expect(remaining).toContain('"type":"completed_');
    });

    it("stops before the first chunk without deleting or fabricating transcript state", async () => {
      configureTranscript(operation);
      const provider = configureProvider("before first chunk");
      const { context, requestAbortController, removeAbortListener } = createContext();

      const response = await startOperation(operation, context);
      await provider.started.promise;
      requestAbortController.abort(new DOMException("User stopped generation.", "AbortError"));
      const responseText = await response.text();

      const messages = insertedMessages();
      if (operation === "SEND") {
        expect(messages).toHaveLength(1);
        expect(messages[0]).toMatchObject({
          id: USER_MESSAGE_ID,
          role: "user",
          content: "New user message"
        });
      } else {
        expect(messages).toEqual([]);
      }
      expect(insertedRegenerations()).toEqual([]);
      expect(conversationMocks.updateMessageSelection).not.toHaveBeenCalled();
      expect(responseText).not.toContain('"type":"failed"');
      expect(conversationMocks.releaseConversationRun).toHaveBeenCalledTimes(1);
      expect(removeAbortListener).toHaveBeenCalled();
    });

    it("persists one nonblank partial and preserves SEND/REGENERATE semantics on a mid-stream stop", async () => {
      configureTranscript(operation);
      const provider = configureProvider("mid-stream");
      const { context, requestAbortController, removeAbortListener } = createContext();

      const response = await startOperation(operation, context);
      await provider.chunkConsumed.promise;
      requestAbortController.abort(new DOMException("User stopped generation.", "AbortError"));
      const responseText = await response.text();

      if (operation === "REGENERATE") {
        const regenerations = insertedRegenerations();
        expect(regenerations).toHaveLength(1);
        expect(regenerations[0]).toMatchObject({
          message_id: ASSISTANT_MESSAGE_ID,
          content: PARTIAL_TEXT
        });
        expect(conversationMocks.updateMessageSelection).toHaveBeenCalledTimes(1);
        expect(conversationMocks.updateMessageSelection).toHaveBeenCalledWith(
          expect.anything(),
          expect.objectContaining({
            messageId: ASSISTANT_MESSAGE_ID,
            selectedRegenerationId: regenerations[0].id
          }),
          expect.any(String)
        );
        expect(insertedMessages()).toEqual([]);
      } else {
        const messages = insertedMessages();
        const assistantMessages = messages.filter((message) => message.role === "assistant");
        expect(assistantMessages).toHaveLength(1);
        expect(assistantMessages[0].content).toBe(PARTIAL_TEXT);
        if (operation === "SEND") {
          expect(messages.filter((message) => message.role === "user")).toHaveLength(1);
        }
        expect(insertedRegenerations()).toEqual([]);
      }

      expect(responseText).not.toContain('"type":"failed"');
      expect(conversationMocks.releaseConversationRun).toHaveBeenCalledTimes(1);
      expect(removeAbortListener).toHaveBeenCalled();
    });

    it("allows an already-started full finalization to finish without a duplicate after a late stop", async () => {
      configureTranscript(operation);
      const provider = configureProvider("late finalization");
      const finalizationStarted = deferred();
      const allowFinalization = deferred();

      if (operation === "REGENERATE") {
        conversationMocks.insertRegeneration.mockImplementation(async () => {
          finalizationStarted.resolve();
          await allowFinalization.promise;
        });
      } else {
        conversationMocks.insertMessage.mockImplementation(async (_env, input) => {
          if ((input as { role: string }).role === "assistant") {
            finalizationStarted.resolve();
            await allowFinalization.promise;
          }
        });
      }

      const { context, requestAbortController, removeAbortListener } = createContext();
      const response = await startOperation(operation, context);
      await provider.chunkConsumed.promise;
      await finalizationStarted.promise;

      requestAbortController.abort(new DOMException("Late client disconnect.", "AbortError"));
      allowFinalization.resolve();
      const responseText = await response.text();

      if (operation === "REGENERATE") {
        const regenerations = insertedRegenerations();
        expect(regenerations).toHaveLength(1);
        expect(regenerations[0].content).toBe(COMPLETE_TEXT);
        expect(conversationMocks.updateMessageSelection).toHaveBeenCalledTimes(1);
        expect(insertedMessages()).toEqual([]);
        expect(responseText).toContain('"type":"completed_regenerate"');
      } else {
        const messages = insertedMessages();
        const assistantMessages = messages.filter((message) => message.role === "assistant");
        expect(assistantMessages).toHaveLength(1);
        expect(assistantMessages[0].content).toBe(COMPLETE_TEXT);
        expect(messages.filter((message) => message.role === "user")).toHaveLength(
          operation === "SEND" ? 1 : 0
        );
        expect(insertedRegenerations()).toEqual([]);
        expect(responseText).toContain('"type":"completed_send"');
      }

      expect(conversationMocks.releaseConversationRun).toHaveBeenCalledTimes(1);
      expect(removeAbortListener).toHaveBeenCalled();
    });
  }
);

it("uses a bounded lease longer than the provider budget and also settles through ReadableStream.cancel", async () => {
  configureTranscript("CONTINUE");
  const provider = configureProvider("mid-stream");
  const { context, removeAbortListener } = createContext();
  const response = await startOperation("CONTINUE", context);
  await provider.chunkConsumed.promise;

  const reader = response.body!.getReader();
  await reader.cancel("UI stopped generation");

  await vi.waitFor(() => {
    expect(conversationMocks.releaseConversationRun).toHaveBeenCalledTimes(1);
  });

  const claimCall = conversationMocks.claimConversationRun.mock.calls[0];
  const claimedAt = claimCall[3] as number;
  const expiresAt = claimCall[4] as number;
  expect(expiresAt - claimedAt).toBe(75_000);
  expect(insertedMessages().filter((message) => message.role === "assistant")).toHaveLength(1);
  expect(removeAbortListener).toHaveBeenCalled();
});
