import { describe, expect, it } from "vitest";
import { parseOfflineGeneration } from "./offline";

describe("offline message and notification metadata", () => {
  it("separates a varied title from the actual roleplay message", () => {
    const result = parseOfflineGeneration(JSON.stringify({
      message: '*I wave.* "Ready for our next adventure?"',
      notificationTitle: "Astrid has an idea for your next adventure"
    }), "Astrid");
    expect(result).toEqual({ message: '*I wave.*\n\n"Ready for our next adventure?"',
      title: "Astrid has an idea for your next adventure" });
  });
  it("accepts a fenced object and preserves plain-text compatibility", () => {
    expect(parseOfflineGeneration('```json\n{"message":"Hello again!","notificationTitle":"Astrid wants to catch up"}\n```', "Astrid"))
      .toEqual({ message: "Hello again!", title: "Astrid wants to catch up" });
    expect(parseOfflineGeneration("*I smile.*\n\nStill up for a walk?", "Astrid"))
      .toEqual({ message: "*I smile.*\n\nStill up for a walk?", title: "Astrid sent you a message" });
  });
  it.each([undefined, null, 4, "Someone else misses you", "Astrid", "Astrid " + "x".repeat(120),
    "Astrid says hello\r\nBcc: another@example.com", "Astrid shared https://example.com",
    "Astrid shared <b>news</b>", "Astrid **misses** you", "Astrid \u202ehas news"])
  ("falls back for invalid title %j without discarding the message", title => {
    expect(parseOfflineGeneration(JSON.stringify({message: "I found a quiet spot by the lake.", notificationTitle: title}), "Astrid"))
      .toEqual({message: "I found a quiet spot by the lake.", title: "Astrid sent you a message"});
  });
  it.each(['{"message":"unfinished', '{"notificationTitle":"Astrid misses you"}',
    '{"message":"   "}', '{"message":42}', '[]'])
  ("does not insert malformed metadata as a character message: %s", raw => {
    expect(() => parseOfflineGeneration(raw, "Astrid")).toThrow();
  });
});
