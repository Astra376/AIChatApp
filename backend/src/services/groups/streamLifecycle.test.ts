import { describe, expect, it, vi, beforeEach } from "vitest";
import { database } from "./testDatabase";
import { createGroup, requireGroup, stopGroupRun } from "./storage";
import { sendGroupMessage } from "./index";
import { streamChatText } from "../../providers/openrouter";
import { consolidateGroupMemory } from "./memory";

vi.mock("../../providers/openrouter", () => ({streamChatText: vi.fn()}));
vi.mock("../personas", () => ({resolveGroupPersonaPrompt: async () => "The user is Sam."}));
vi.mock("../chat/modelPolicy", () => ({resolveAutomaticModel: async (env: unknown) => ({env, reasoning: {enabled: false}, maxTokens: 100})}));
vi.mock("./memory", () => ({groupMemoryContext: async () => ({prompts: {leo: ""}, shortTermLimit: 4000}), consolidateGroupMemory: vi.fn()}));
vi.mock("./router", async original => ({...await original<typeof import("./router")>(), chooseGroupSpeakers: async () => ["leo"]}));
vi.mock("./storage", async original => {
  const actual = await original<typeof import("./storage")>();
  return {...actual, stopGroupRun: vi.fn(actual.stopGroupRun)};
});
beforeEach(() => { vi.clearAllMocks(); vi.mocked(consolidateGroupMemory).mockResolvedValue(undefined); });

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return {promise, resolve};
}

describe("group stream terminal contract", () => {
  it("does not emit an error until the partial reply is saved and its lease is released", async () => {
    const {context, env, sqlite} = database();
    const enteredCleanup = deferred(), allowCleanup = deferred();
    const originalStorage = await vi.importActual<typeof import("./storage")>("./storage");
    vi.mocked(stopGroupRun).mockImplementationOnce(async (...args) => {
      enteredCleanup.resolve();
      await allowCleanup.promise;
      await originalStorage.stopGroupRun(...args);
    });
    vi.mocked(streamChatText).mockImplementation(async function* () { yield "Partial reply"; throw new Error("Provider disconnected"); });
    try {
      const group = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const response = await sendGroupMessage(context(), group.id, "user_message_1", "Hello");
      const reader = response.body!.getReader();
      const events: Array<Record<string, unknown>> = [];
      const consuming = (async () => {
        while (true) {
          const next = await reader.read();
          if (next.done) break;
          for (const line of new TextDecoder().decode(next.value).split("\n")) if (line.startsWith("data: ")) events.push(JSON.parse(line.slice(6)));
        }
      })();
      await enteredCleanup.promise;
      await Promise.resolve();
      expect(events.some(event => event.type === "error")).toBe(false);
      expect((await requireGroup(context(), group.id)).active_run_id).toBeTruthy();
      allowCleanup.resolve();
      await consuming;
      expect(events.filter(event => event.type === "error")).toHaveLength(1);
      expect((await requireGroup(context(), group.id)).active_run_id).toBeNull();
      expect(sqlite.prepare("SELECT content,status FROM group_messages WHERE role='assistant'").get())
        .toMatchObject({content: "Partial reply", status: "interrupted"});
    } finally { allowCleanup.resolve(); sqlite.close(); }
  });
  it("keeps a successful terminal successful when background memory fails", async () => {
    const {context, sqlite} = database();
    vi.mocked(streamChatText).mockImplementation(async function* () { yield "Hello Sam"; });
    vi.mocked(consolidateGroupMemory).mockRejectedValue(new Error("Memory unavailable"));
    try {
      const group = await createGroup(context(), "Friends", ["astrid", "leo"]);
      const response = await sendGroupMessage(context(), group.id, "user_message_2", "Hello");
      const events = (await response.text()).split("\n").filter(line => line.startsWith("data: ")).map(line => JSON.parse(line.slice(6)));
      expect(events.filter(event => event.type === "done")).toHaveLength(1);
      expect(events.filter(event => event.type === "error")).toHaveLength(0);
      expect((await requireGroup(context(), group.id)).active_run_id).toBeNull();
    } finally { sqlite.close(); }
  });
});
