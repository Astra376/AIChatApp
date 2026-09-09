import type { RouteDefinition } from "./types";
import { json } from "../lib/response";
import { parseJson, requireString } from "../lib/validation";
import { autoCreateCharacter, generateEmotionPortraits, readCharacterPsychology, readEmotionPortraits, saveCharacterPsychology } from "../services/characterPsychology";
export const characterPsychologyRoutes: RouteDefinition[] = [
  { method: "POST", path: "/v1/characters/auto-create", auth: true, handler: async context => {
    const body = await parseJson<{ idea?: string }>(context.request);
    return json(await autoCreateCharacter(context, requireString(body.idea, "idea", 4000)));
  } },
  { method: "GET", path: "/v1/characters/:characterId/psychology", auth: true, handler: async c => json(await readCharacterPsychology(c, c.params.characterId)) },
  { method: "PATCH", path: "/v1/characters/:characterId/psychology", auth: true, handler: async c => json(await saveCharacterPsychology(c, c.params.characterId, await parseJson(c.request))) },
  { method: "GET", path: "/v1/characters/:characterId/emotion-portraits", auth: true, handler: async c => json(await readEmotionPortraits(c, c.params.characterId)) },
  { method: "POST", path: "/v1/characters/:characterId/emotion-portraits", auth: true, handler: async c => json(await generateEmotionPortraits(c, c.params.characterId)) }
];
