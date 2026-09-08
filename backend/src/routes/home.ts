import { discover, recommendedPage } from "../services/home/recommendations";
import { getTrendingSearches } from "../services/home/trending";
import { getHomeFeed, searchHome } from "../services/home";
import { json } from "../lib/response";
import { clampPageSize, parseCursor, requireString } from "../lib/validation";
import type { RouteDefinition } from "./types";

export const homeRoutes: RouteDefinition[] = [
  {method:"GET",path:"/v1/home/discover",auth:true,handler:async context=>json(await discover(context))},
  {method:"GET",path:"/v1/home/discover/:categoryId",auth:true,handler:async context=>json(await recommendedPage(
    context,context.url.searchParams.get("cursor"),30,context.params.categoryId,context.url.searchParams.get("version")))},
  { method: "GET", path: "/v1/home/trending", auth: true,
    handler: async context => json(await getTrendingSearches(context)) },
  {
    method: "GET",
    path: "/v1/home/feed",
    auth: true,
    handler: async (context) => {
      const page = await recommendedPage(
        context,
        context.url.searchParams.get("cursor"),
        clampPageSize(context.url.searchParams.get("pageSize"), 20)
      );
      return json(page);
    }
  },
  {
    method: "GET",
    path: "/v1/home/search",
    auth: true,
    handler: async (context) => {
      const page = await searchHome(
        context,
        requireString(context.url.searchParams.get("q"), "q", 120),
        parseCursor(context.url.searchParams.get("cursor")),
        clampPageSize(context.url.searchParams.get("pageSize"), 20)
      );
      return json(page);
    }
  }
];

