import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";

const mocks = vi.hoisted(() => ({
  ensureConversationStreamingSchema: vi.fn(),
  getCharacterById: vi.fn(),
  findConversationByOwnerAndCharacter: vi.fn(),
  getConversationById: vi.fn(),
  getConversationSummaryById: vi.fn(),
  insertConversation: vi.fn(),
  insertMessage: vi.fn(),
  listConversationSummaries: vi.fn(),
  listMessages: vi.fn(),
  listRegenerationsForConversation: vi.fn(),
  updateConversationActivity: vi.fn()
}));

vi.mock("../../db/ensureConversationStreamingSchema", () => ({
  ensureConversationStreamingSchema: mocks.ensureConversationStreamingSchema
}));

vi.mock("../../db/queries/characters", () => ({
  getCharacterById: mocks.getCharacterById
}));

vi.mock("../../db/queries/conversations", () => ({
  findConversationByOwnerAndCharacter: mocks.findConversationByOwnerAndCharacter,
  getConversationById: mocks.getConversationById,
  getConversationSummaryById: mocks.getConversationSummaryById,
  insertConversation: mocks.insertConversation,
  insertMessage: mocks.insertMessage,
  listConversationSummaries: mocks.listConversationSummaries,
  listMessages: mocks.listMessages,
  listRegenerationsForConversation: mocks.listRegenerationsForConversation,
  updateConversationActivity: mocks.updateConversationActivity
}));

import { createConversation, getConversationDetail } from ".";
import { conversationRoutes } from "../../routes/conversations";

const character = {
  id: "character-1",
  owner_user_id: "creator-1",
  owner_display_name: "Creator",
  name: "Character",
  tagline: "Tagline",
  greeting: "Welcome",
  description: "Description",
  system_prompt: "Prompt",
  definition_private: 0,
  visibility: "public",
  avatar_url: "https://example.test/avatar.jpg",
  public_chat_count: 1,
  like_count: 2,
  liked_by_me: 0,
  last_active_at: 10,
  created_at: 10,
  updated_at: 10
};

function context(request = new Request("https://example.test/v1/conversations")): RequestContext {
  return {
    request,
    env: { DB: {} } as Env,
    url: new URL(request.url),
    params: {},
    user: {
      userId: "viewer-1",
      tokenType: "access",
      exp: Number.MAX_SAFE_INTEGER
    }
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getCharacterById.mockResolvedValue(character);
});

describe("createConversation", () => {
  it("reuses the latest conversation when forceNew is false", async () => {
    mocks.findConversationByOwnerAndCharacter.mockResolvedValue({
      id: "conversation-latest",
      owner_user_id: "viewer-1",
      character_id: "character-1",
      updated_at: 20,
      started_at: 10,
      last_message_at: 20,
      version: 0,
      active_run_id: null,
      active_run_expires_at: null
    });
    mocks.getConversationSummaryById.mockResolvedValue({
      id: "conversation-latest",
      character_id: "character-1",
      character_name: "Character",
      character_avatar_url: "https://example.test/avatar.jpg",
      updated_at: 20,
      started_at: 10,
      last_message_at: 20,
      last_preview: "Latest reply",
      unread_count: 0,
      has_unread_badge: 0
    });

    await expect(createConversation(context(), "character-1", false)).resolves.toMatchObject({
      id: "conversation-latest",
      lastPreview: "Latest reply"
    });
    expect(mocks.findConversationByOwnerAndCharacter).toHaveBeenCalledWith(
      expect.anything(),
      "viewer-1",
      "character-1"
    );
    expect(mocks.insertConversation).not.toHaveBeenCalled();
  });

  it("always inserts an independent conversation when forceNew is true", async () => {
    const result = await createConversation(context(), "character-1", true);

    expect(result.id).toMatch(/^conversation_/);
    expect(result.id).not.toBe("conversation-latest");
    expect(mocks.findConversationByOwnerAndCharacter).not.toHaveBeenCalled();
    expect(mocks.insertConversation).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        id: result.id,
        ownerUserId: "viewer-1",
        characterId: "character-1"
      })
    );
    expect(mocks.insertMessage).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        conversation_id: result.id,
        role: "assistant",
        content: "Welcome"
      })
    );
  });

  it("preserves forceNew=true through the HTTP request contract", async () => {
    const route = conversationRoutes.find((candidate) =>
      candidate.method === "POST" && candidate.path === "/v1/conversations"
    );
    const request = new Request("https://example.test/v1/conversations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        characterId: "character-1",
        forceNew: true
      })
    });

    const response = await route!.handler(context(request));
    const body = await response.json() as { id: string };

    expect(response.status).toBe(201);
    expect(body.id).toMatch(/^conversation_/);
    expect(mocks.findConversationByOwnerAndCharacter).not.toHaveBeenCalled();
    expect(mocks.insertConversation).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ id: body.id })
    );
  });
});

describe("conversation stream recovery metadata", () => {
  it("returns the exact live run only to its owner and suppresses expired leases", async () => {
    const record = {
      id: "conversation", owner_user_id: "viewer-1", character_id: "character-1", version: 1,
      active_run_id: "run-known", active_run_expires_at: Date.now() + 30_000
    };
    mocks.getConversationById.mockResolvedValue(record);
    mocks.listMessages.mockResolvedValue([]);
    mocks.listRegenerationsForConversation.mockResolvedValue([]);
    expect(await getConversationDetail(context(), "conversation")).toMatchObject({
      activeRunId: "run-known", activeRunExpiresAt: record.active_run_expires_at
    });
    mocks.getConversationById.mockResolvedValue({ ...record, active_run_expires_at: Date.now() - 1 });
    expect(await getConversationDetail(context(), "conversation")).toMatchObject({ activeRunId: null, activeRunExpiresAt: null });
    mocks.getCharacterById.mockClear();
    mocks.listMessages.mockClear();
    mocks.getConversationById.mockResolvedValue({ ...record, owner_user_id: "someone-else" });
    await expect(getConversationDetail(context(), "conversation")).rejects.toMatchObject({ status: 403 });
    expect(mocks.getCharacterById).not.toHaveBeenCalled();
    expect(mocks.listMessages).not.toHaveBeenCalled();
  });
});
