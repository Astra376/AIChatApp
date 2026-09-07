import { notifyCharacterPublished } from "../services/notifications";
import {
  createOwnedCharacter,
  getCharacter,
  getMyCharacters,
  getMyLikedCharacters,
  generateCharacterGreeting,
  likePublicCharacter,
  parseCharacterVisibility,
  unlikePublicCharacter,
  updateOwnedCharacter
} from "../services/characters";
import { json } from "../lib/response";
import { clampPageSize, parseCursor, parseJson, requireString } from "../lib/validation";
import type { RouteDefinition } from "./types";

function optionalTrimmedString(value: unknown, field: string, maxLength: number): string {
  if (value == null || value === "") return "";
  return requireString(value, field, maxLength);
}

export const characterRoutes: RouteDefinition[] = [
  {
    method: "POST",
    path: "/v1/characters",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<Record<string, unknown>>(context.request);
      const created = await createOwnedCharacter(context, {
        name: requireString(body.name, "name", 80),
        tagline: optionalTrimmedString(body.tagline, "tagline", 50),
        greeting: requireString(body.greeting, "greeting", 1_200),
        description: optionalTrimmedString(body.description, "description", 500),
        systemPrompt: requireString(body.systemPrompt, "systemPrompt", 64_000),
        definitionPrivate: body.definitionPrivate === true,
        visibility: parseCharacterVisibility(requireString(body.visibility, "visibility", 20)),
        avatarUrl: typeof body.avatarUrl === "string" ? body.avatarUrl : null,
        voiceId: body.voiceId == null ? null : requireString(body.voiceId, "voiceId", 250)
      });
      if (created.visibility === "public") context.waitUntil?.(notifyCharacterPublished(context.env, created.id).catch(error => console.error("Character notification failed", error)));
      return json(created, { status: 201 });
    }
  },
  {
    method: "POST",
    path: "/v1/characters/generate-greeting",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<Record<string, unknown>>(context.request);
      return json(await generateCharacterGreeting(context, {
        name: requireString(body.name, "name", 80),
        description: requireString(body.description, "description", 1_200)
      }));
    }
  },
  {
    method: "GET",
    path: "/v1/characters/me",
    auth: true,
    handler: async (context) => {
      const page = await getMyCharacters(
        context,
        parseCursor(context.url.searchParams.get("cursor")),
        clampPageSize(context.url.searchParams.get("pageSize"))
      );
      return json(page);
    }
  },
  {
    method: "GET",
    path: "/v1/characters/me/liked",
    auth: true,
    handler: async (context) => {
      const page = await getMyLikedCharacters(
        context,
        parseCursor(context.url.searchParams.get("cursor")),
        clampPageSize(context.url.searchParams.get("pageSize"))
      );
      return json(page);
    }
  },
  {
    method: "PATCH",
    path: "/v1/characters/:characterId",
    auth: true,
    handler: async (context) => {
      const body = await parseJson<Record<string, unknown>>(context.request);
      const updated = await updateOwnedCharacter(context, context.params.characterId, {
        name: requireString(body.name, "name", 80),
        tagline: optionalTrimmedString(body.tagline, "tagline", 50),
        greeting: requireString(body.greeting, "greeting", 1_200),
        description: optionalTrimmedString(body.description, "description", 500),
        systemPrompt: requireString(body.systemPrompt, "systemPrompt", 64_000),
        definitionPrivate: body.definitionPrivate === true,
        visibility: parseCharacterVisibility(requireString(body.visibility, "visibility", 20)),
        avatarUrl: typeof body.avatarUrl === "string" ? body.avatarUrl : null,
        voiceId: body.voiceId == null ? null : requireString(body.voiceId, "voiceId", 250)
      });
      if (updated.visibility === "public") context.waitUntil?.(notifyCharacterPublished(context.env, updated.id).catch(error => console.error("Character notification failed", error)));
      return json(updated);
    }
  },
  {
    method: "GET",
    path: "/v1/characters/:characterId",
    auth: true,
    handler: async (context) => json(await getCharacter(context, context.params.characterId))
  },
  {
    method: "POST",
    path: "/v1/characters/:characterId/like",
    auth: true,
    handler: async (context) => {
      return json(await likePublicCharacter(context, context.params.characterId));
    }
  },
  {
    method: "DELETE",
    path: "/v1/characters/:characterId/like",
    auth: true,
    handler: async (context) => {
      return json(await unlikePublicCharacter(context, context.params.characterId));
    }
  }
];
