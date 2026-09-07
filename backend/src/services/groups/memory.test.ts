import { describe, expect, it } from "vitest";
import { normalizeEmotionState, normalizePersonalityState } from "../chat/sceneMemory";
import { updateGroupMemory } from "./memory";
import type { GroupMessageRecord } from "./storage";
import { groupCharacterContext } from "./router";
const characters = [{id: "astrid", name: "Astrid", avatar_url: null, tagline: "Reflective", system_prompt: "Reserved"},
  {id: "leo", name: "Leo", avatar_url: null, tagline: "Playful", system_prompt: "Playful"}];
const sources: GroupMessageRecord[] = [{id: "m1", group_id: "g", role: "user", character_id: null, content: "Two days later, I tell Astrid the truth about the letter.",
  position: 4, created_at: 0, run_id: null, status: "complete"}];
const previous = () => ({shortTerm: "", midTerm: "", longTerm: "I like coffee", scene: null,
  members: {astrid: {emotion: normalizeEmotionState({anger: 10}, -1), personality: normalizePersonalityState({volatility: 20}, -1), psychology: null},
    leo: {emotion: normalizeEmotionState({anger: 5}, -1), personality: normalizePersonalityState({}, -1), psychology: null}}});

describe("group continuity", () => {
  it("stores only evidenced new facts and deduplicates existing memory", () => {
    const memory = updateGroupMemory(previous(), {facts: [
      {text: "Astrid knows about the letter", sourcePosition: 4, sourceQuote: "tell Astrid the truth about the letter"},
      {text: "I like coffee", sourcePosition: 4, sourceQuote: "tell Astrid the truth"},
      {text: "Invented user decision", sourcePosition: 4, sourceQuote: "I destroyed the letter"}
    ]}, characters, sources, false);
    expect(memory.longTerm).toBe("I like coffee\nAstrid knows about the letter");
  });
  it("evolves each participant separately with bounded emotional momentum", () => {
    const memory = updateGroupMemory(previous(), {members: {astrid: {emotion: {anger: 90, sourcePosition: 4,
      sourceQuote: "the truth about the letter", impact: "ordinary"}}}}, characters, sources, false);
    expect(memory.members.astrid.emotion!.anger).toBeGreaterThan(10);
    expect(memory.members.astrid.emotion!.anger).toBeLessThan(20);
    expect(memory.members.leo.emotion!.anger).toBe(5);
  });
  it("uses story time cues and refuses unsupported clock claims", () => {
    const grounded = updateGroupMemory(previous(), {scene: {summary: "The letter is discussed", fictionalTime: "Two days later",
      sourcePosition: 4, sourceQuote: "the truth about the letter"}}, characters, sources, false);
    expect(grounded.scene?.fictionalTime).toBe("Two days later");
    const invalid = updateGroupMemory(previous(), {scene: {fictionalTime: "Three months later", sourcePosition: 4,
      sourceQuote: "the truth about the letter"}}, characters, sources, false);
    expect(invalid.scene?.fictionalTime).toBeNull();
  });
  it("keeps recent context bounded while preserving the newest contributions", () => {
    const transcript = Array.from({length: 30}, (_, index) => ({id: String(index), role: "user" as const, character_id: null, content: String(index).padStart(2, "0") + "x".repeat(998)}));
    const prompt = groupCharacterContext(characters[0], characters, transcript, "Sam", 4_000);
    expect(prompt).toHaveLength(5);
    expect(prompt[1].content).toContain("26");
    expect(prompt.at(-1)!.content).toContain("29");
  });
});
