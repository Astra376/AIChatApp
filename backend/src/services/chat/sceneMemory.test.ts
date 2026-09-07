import {describe, expect, it} from "vitest";
import {evolveEmotionState, evolvePersonalityState, groundedFictionalTime, normalizeEmotionState, normalizePersonalityState, normalizeSceneState, rewindSceneState, NATURAL_CHARACTER_BEHAVIOR} from "./sceneMemory";
const sources = [{position: 4, content: 'Three days later, I discover you kept the promise.'}];
const evidence = {sourcePosition: 4, sourceQuote: 'you kept the promise'};
const calm = normalizeEmotionState({mood: 'Wary', trust: 20, stress: 40}, 2)!;
const grounded = normalizePersonalityState({volatility: 10, resilience: 80, adaptability: 30}, 2)!;

describe('grounded fictional continuity', () => {
  it('only accepts actual story time cues, never elapsed wall-clock guesses', () => {
    expect(groundedFictionalTime('Three days later', sources)).toBe('Three days later');
    expect(groundedFictionalTime('one week later', sources)).toBeNull();
    expect(groundedFictionalTime('September 7, 2026', sources)).toBeNull();
  });
  it('requires the exact event source and a supporting quote for timeline changes', () => {
    const state = normalizeSceneState({timeline: [
      {...evidence, text: 'The promise was kept.', fictionalTime: 'Three days later'},
      {sourcePosition: 999, sourceQuote: 'you kept the promise', text: 'A fabricated event'},
      {sourcePosition: 4, sourceQuote: 'you betrayed me', text: 'A second fabrication'}
    ]}, sources, null)!;
    expect(state.timeline).toEqual([{text: 'The promise was kept.', fictionalTime: 'Three days later', sourcePosition: 4}]);
  });
  it('removes events and state from an abandoned story branch immediately', () => {
    const state = normalizeSceneState({...evidence, summary: 'We reconcile', location: 'The shore', timeline: [{...evidence, text: 'The promise was kept.'}]}, sources, null)!;
    const rewind = rewindSceneState(state, 4)!;
    expect(rewind.timeline).toEqual([]);
    expect(rewind.summary).toBe('');
    expect(rewind.location).toBeNull();
  });
});
describe('character emotional momentum', () => {
  it('rejects unsupported changes and preserves current emotions', () => {
    expect(evolveEmotionState({mood: 'Ecstatic', trust: 100, sourcePosition: 4, sourceQuote: 'we got married'}, calm, grounded, sources)).toBe(calm);
  });
  it('moves grounded characters gradually and keeps omitted dimensions unchanged', () => {
    const next = evolveEmotionState({...evidence, mood: 'Cautiously hopeful', trust: 100}, calm, grounded, sources)!;
    expect(next.trust).toBeGreaterThan(calm.trust);
    expect(next.trust - calm.trust).toBeLessThanOrEqual(4);
    expect(next.stress).toBe(calm.stress);
    expect(next.momentum.trust).toBeGreaterThan(0);
  });
  it('allows more reactive characters to move faster on the same event', () => {
    const stable = evolveEmotionState({...evidence, trust: 100}, calm, grounded, sources)!;
    const reactive = evolveEmotionState({...evidence, trust: 100}, calm, {...grounded, volatility: 90}, sources)!;
    expect(reactive.trust).toBeGreaterThan(stable.trust);
  });
  it('permits a supported major event to interrupt emotional inertia', () => {
    const ordinary = evolveEmotionState({...evidence, trust: 100}, calm, grounded, sources)!;
    const major = evolveEmotionState({...evidence, trust: 100, impact: 'major'}, calm, grounded, sources)!;
    expect(major.trust - calm.trust).toBeGreaterThan(ordinary.trust - calm.trust);
    expect(major.trust).toBeLessThan(100);
  });
  it('does not overshoot a small supported target due to stored momentum', () => {
    const next = evolveEmotionState({...evidence, trust: 21}, {...calm, momentum: {trust: 10}}, grounded, sources)!;
    expect(next.trust).toBe(21);
  });
  it('lets temperament itself evolve slowly without resetting unspecified traits', () => {
    const next = evolvePersonalityState({...evidence, warmth: 100, volatility: 100}, grounded, sources)!;
    expect(next.warmth - grounded.warmth).toBe(1);
    expect(next.volatility - grounded.volatility).toBe(1);
    expect(next.resilience).toBe(grounded.resilience);
  });
  it('puts self-perspective and user agency directly into the shared character policy', () => {
    expect(NATURAL_CHARACTER_BEHAVIOR).toContain('FIRST PERSON');
    expect(NATURAL_CHARACTER_BEHAVIOR).toContain('SECOND PERSON');
    expect(NATURAL_CHARACTER_BEHAVIOR).toContain('Never invent an action');
    expect(NATURAL_CHARACTER_BEHAVIOR).toContain('separate paragraphs');
  });
});
