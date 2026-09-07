/** Conversation state describes fiction, never a diagnosis of the user. */
export interface TimelineEvent { text: string; fictionalTime: string | null; sourcePosition: number }
export interface SceneState { summary: string; location: string | null; fictionalTime: string | null; timeline: TimelineEvent[]; sourcePosition: number }
export const EMOTION_KEYS = ["trust", "affection", "stress", "energy", "openness", "joy", "sadness", "anger", "fear", "curiosity", "jealousy", "hope", "loneliness", "shame", "pride", "guilt"] as const;
export type EmotionKey = typeof EMOTION_KEYS[number];
export type EmotionState = Record<EmotionKey, number> & {
  mood: string; reason: string; sourcePosition: number; momentum: Partial<Record<EmotionKey, number>>;
};
export const PERSONALITY_KEYS = ["warmth", "confidence", "playfulness", "formality", "assertiveness", "volatility", "resilience", "adaptability"] as const;
export type PersonalityKey = typeof PERSONALITY_KEYS[number];
export type PersonalityState = Record<PersonalityKey, number> & { description: string; sourcePosition: number };
export interface PsychologyState {
  cornerstone: string; beliefs: string[]; desires: string[]; secretDesires: string[]; lifeStory: string;
  dailyLife: string; relationships: string; significantEvents: string[]; sourcePosition: number;
}
export type StorySource = { position: number; content: string };
type JsonObject = Record<string, unknown>;
function object(value: unknown): JsonObject | null { return typeof value === "object" && value != null && !Array.isArray(value) ? value as JsonObject : null; }
function text(value: unknown, limit: number): string { return typeof value === "string" ? value.trim().slice(0, limit) : ""; }
function score(value: unknown, fallback = 50): number { return typeof value === "number" && Number.isFinite(value) ? Math.max(0, Math.min(100, Math.round(value))) : fallback; }
function lines(value: unknown, previous: string[] = []): string[] {
  return Array.isArray(value) ? [...new Set(value.map(item => text(item, 500)).filter(Boolean))].slice(0, 24) : previous;
}
export function parseStoredState<T>(value: string | null | undefined): T | null { try { return value ? JSON.parse(value) as T : null; } catch { return null; } }

// Wall-clock message dates never enter this clock. The summarizer must quote
// an actual story cue, so a week away from the app cannot age the scene.
export function groundedFictionalTime(value: unknown, sources: StorySource[]): string | null {
  const candidate = text(value, 160);
  return candidate && sources.some(source => source.content.toLowerCase().includes(candidate.toLowerCase())) ? candidate : null;
}
export function hasGroundedEvidence(value: unknown, sources: StorySource[]): boolean {
  const input = object(value);
  const quote = text(input?.sourceQuote, 400);
  return quote.length >= 4 && sources.some(source => source.position === input?.sourcePosition && source.content.toLowerCase().includes(quote.toLowerCase()));
}
export function normalizeSceneState(value: unknown, sources: StorySource[], previous: SceneState | null): SceneState | null {
  const input = object(value);
  if (!input || !sources.length) return previous;
  const timeline = [...(previous?.timeline ?? [])];
  for (const raw of Array.isArray(input.timeline) ? input.timeline : []) {
    const entry = object(raw);
    if (!entry || !Number.isInteger(entry.sourcePosition)) continue;
    const source = sources.find(message => message.position === entry.sourcePosition);
    const eventText = text(entry.text, 500);
    if (!source || !eventText || !hasGroundedEvidence(entry, [source]) || timeline.some(existing => existing.text.toLowerCase() === eventText.toLowerCase())) continue;
    timeline.push({ text: eventText, sourcePosition: source.position, fictionalTime: groundedFictionalTime(entry.fictionalTime, [source]) });
  }
  const grounded = hasGroundedEvidence(input, sources);
  return {
    summary: grounded ? text(input.summary, 1_200) || previous?.summary || "" : previous?.summary || "",
    location: grounded ? text(input.location, 160) || previous?.location || null : previous?.location || null,
    fictionalTime: groundedFictionalTime(input.fictionalTime, sources) ?? previous?.fictionalTime ?? null,
    timeline: timeline.sort((a, b) => a.sourcePosition - b.sourcePosition).slice(-48),
    sourcePosition: sources.at(-1)!.position
  };
}
const calmDefaults: Partial<Record<EmotionKey, number>> = { stress: 15, sadness: 10, anger: 5, fear: 10, jealousy: 5, loneliness: 10, shame: 5, guilt: 5, curiosity: 55, hope: 55 };
export function normalizeEmotionState(value: unknown, sourcePosition: number): EmotionState | null {
  const input = object(value);
  if (!input) return null;
  return {
    ...Object.fromEntries(EMOTION_KEYS.map(key => [key, score(input[key], calmDefaults[key] ?? 50)])) as Record<EmotionKey, number>,
    mood: text(input.mood, 80) || "Settled", reason: text(input.reason, 300), sourcePosition, momentum: {}
  };
}
export function normalizePersonalityState(value: unknown, sourcePosition: number): PersonalityState | null {
  const input = object(value);
  if (!input) return null;
  return {
    ...Object.fromEntries(PERSONALITY_KEYS.map(key => [key, score(input[key], key === "volatility" ? 35 : 50)])) as Record<PersonalityKey, number>,
    description: text(input.description, 500), sourcePosition
  };
}
export function normalizePsychologyState(value: unknown, sourcePosition: number, previous: PsychologyState | null = null): PsychologyState | null {
  const input = object(value);
  if (!input) return previous;
  return {
    cornerstone: text(input.cornerstone, 1_200) || previous?.cornerstone || "The relationships and experiences that shaped who I am.",
    beliefs: lines(input.beliefs, previous?.beliefs), desires: lines(input.desires, previous?.desires), secretDesires: lines(input.secretDesires, previous?.secretDesires),
    lifeStory: text(input.lifeStory, 4_000) || previous?.lifeStory || "", dailyLife: text(input.dailyLife, 2_000) || previous?.dailyLife || "",
    relationships: text(input.relationships, 2_000) || previous?.relationships || "", significantEvents: lines(input.significantEvents, previous?.significantEvents), sourcePosition
  };
}

/** Damped momentum permits continuity and gradual change; a grounded major
 * event can move emotions faster, while basic temperament changes slowly. */
export function evolveEmotionState(value: unknown, previous: EmotionState | null, personality: PersonalityState | null, sources: StorySource[]): EmotionState | null {
  if (!hasGroundedEvidence(value, sources)) return previous;
  const input = object(value)!;
  const position = sources.at(-1)?.position ?? -1;
  const target = normalizeEmotionState(input, position)!;
  const baseline = previous ?? normalizeEmotionState({}, -1)!;
  const volatility = (personality?.volatility ?? 35) / 100;
  const resilience = (personality?.resilience ?? 50) / 100;
  const major = input.impact === "major";
  const cap = major ? 24 + volatility * 22 : 3 + volatility * 10;
  const momentum: Partial<Record<EmotionKey, number>> = {};
  const scores = Object.fromEntries(EMOTION_KEYS.map(key => {
    // Missing dimensions cannot accidentally reset to neutral.
    if (typeof input[key] !== "number") return [key, baseline[key]];
    const delta = target[key] - baseline[key];
    const oldVelocity = baseline.momentum?.[key] ?? 0;
    const velocity = oldVelocity * (0.45 - resilience * 0.2) + delta * (major ? 0.75 : 0.18 + volatility * 0.2);
    // Momentum must not overshoot the event-supported target.
    const movement = Math.sign(delta) * Math.min(Math.abs(delta), cap, Math.max(0, velocity * Math.sign(delta)));
    momentum[key] = Math.round(movement * 10) / 10;
    return [key, score(baseline[key] + movement)];
  })) as Record<EmotionKey, number>;
  return { ...scores, mood: text(input.mood, 80) || baseline.mood, reason: target.reason || baseline.reason, sourcePosition: position, momentum };
}
export function evolvePersonalityState(value: unknown, previous: PersonalityState | null, sources: StorySource[]): PersonalityState | null {
  if (!hasGroundedEvidence(value, sources)) return previous;
  const input = object(value)!;
  const base = previous ?? normalizePersonalityState({}, -1)!;
  const target = normalizePersonalityState(value, sources.at(-1)!.position)!;
  const cap = input.impact === "major" ? 3 + base.adaptability * 0.05 : 1;
  return {
    ...Object.fromEntries(PERSONALITY_KEYS.map(key => [key, typeof input[key] === "number"
      ? score(base[key] + Math.max(-cap, Math.min(cap, target[key] - base[key]))) : base[key]])) as Record<PersonalityKey, number>,
    description: target.description || base.description, sourcePosition: sources.at(-1)!.position
  };
}
export function rewindSceneState(scene: SceneState | null, position: number): SceneState | null {
  if (!scene) return null;
  const timeline = scene.timeline.filter(event => event.sourcePosition < position);
  return scene.sourcePosition < position ? {...scene, timeline} : {
    summary: "", location: null, fictionalTime: timeline.at(-1)?.fictionalTime ?? null,
    timeline, sourcePosition: timeline.at(-1)?.sourcePosition ?? -1
  };
}
/** Advanced creator detail is kept private and applied once as character
 * background. Payment gates editing, not whether an existing character still
 * remembers its defining details after its creator's subscription expires. */
export function formatAdvancedCharacterDefinition(value: unknown): string {
  const definition = text(value, 8_000);
  return definition ? `ADDITIONAL CHARACTER DEFINITION (private fictional background; preserve user agency and current persona):\n${definition}` : "";
}

export const NATURAL_CHARACTER_BEHAVIOR = [
  "Stay faithful to the character's established personality, motives, knowledge, boundaries and relationships; do not flatten different characters into the same agreeable voice.",
  "Speak and narrate your own actions in FIRST PERSON: I, me, my. Address the user in SECOND PERSON: you, your. Use the current user persona's name naturally when addressing them, without repeating a name mechanically in every message.",
  "Never invent an action, statement, decision or feeling for the user. You may acknowledge only actions they have actually supplied; leave their next response and consent to them. Describe yourself and the established scene instead.",
  "Choose reply length from the moment. A word, fragment, one sentence, or a brief exchange is often enough. Use longer replies only when the scene or the user's request earns them; do not write a mini-essay on every turn.",
  "Emotions have inertia and mixed feelings can coexist. Preserve a character's current emotional direction, while meaningful events can interrupt it. Temperament, beliefs and desires may evolve through sustained experience; do not reset or reinvent them each turn.",
  "Use abbreviations, lowercase, CAPS, emojis or emoticons only when they suit this character and situation. Avoid habitual catchphrases, repeated questions, repeated gestures and formulaic endings.",
  "Put spoken dialogue and action/narration in separate paragraphs. Use *italics* for action/narration, **bold** for emphasis, and ordinary text for speech; keep formatting light.",
  "Story time advances only from explicit scene cues; a gap between real messages does not imply that hours or days passed in the fiction.",
  "Use remembered facts and emotional state naturally, without exposing numerical scores, memory machinery or internal deliberation. The character's private thoughts are not a chain-of-thought transcript."
].join("\n");
