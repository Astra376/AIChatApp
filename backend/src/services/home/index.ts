import { recordSearch } from "./trending";
import type { RequestContext } from "../../env";
import { getPublicFeed, searchPublicCharacters } from "../../db/queries/characters";
import { toCharacterDto } from "../characters/characterDto";

export async function getHomeFeed(context: RequestContext, cursor: number, limit: number) {
  const rows = await getPublicFeed(context.env, context.user!.userId, cursor, limit);
  return {
    items: rows.map((record) => toCharacterDto(record, context.user!.userId)),
    nextCursor: rows.length === limit ? String(cursor + rows.length) : null
  };
}

export async function searchHome(context: RequestContext, query: string, cursor: number, limit: number) {
  const rows = await searchPublicCharacters(context.env, context.user!.userId, query, cursor, limit);
  if (cursor === 0 && rows.length > 0) {
    const recording = recordSearch(context, query).catch(() => undefined);
    if (context.waitUntil) context.waitUntil(recording);
    else await recording;
  }
  return {
    items: rows.map((record) => toCharacterDto(record, context.user!.userId)),
    nextCursor: rows.length === limit ? String(cursor + rows.length) : null
  };
}
