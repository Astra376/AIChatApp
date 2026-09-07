export { ImageGenerationJob } from "./services/images/jobs";
import type { Env, RequestContext } from "./env";
import { processOfflineMessages } from "./services/chat/offline";
import { requireAuth } from "./lib/auth";
import { handleError, json } from "./lib/response";
import { assetRoutes } from "./routes/assets";
import { authRoutes } from "./routes/auth";
import { characterRoutes } from "./routes/characters";
import { chatRoutes } from "./routes/chat";
import { conversationRoutes } from "./routes/conversations";
import { notificationRoutes } from "./routes/notifications";
import { voiceRoutes } from "./routes/voice";
import { billingRoutes } from "./routes/billing";
import { chatModelRoutes } from "./routes/chatModel";
import { groupRoutes } from "./routes/groups";
import { personaRoutes } from "./routes/personas";
import { customizationRoutes } from "./routes/customization";
import { characterPsychologyRoutes } from "./routes/characterPsychology";
import { processGroupFollowups } from "./services/groups";
import { resumeEmotionPortraits } from "./services/characterPsychology";
import { homeRoutes } from "./routes/home";
import { imageRoutes } from "./routes/images";
import { profileRoutes } from "./routes/profile";
import type { RouteDefinition } from "./routes/types";

const routes: RouteDefinition[] = [
  ...assetRoutes,
  ...notificationRoutes,
  ...voiceRoutes,
  ...billingRoutes,
  ...chatModelRoutes,
  ...groupRoutes,
  ...personaRoutes,
  ...customizationRoutes,
  ...characterPsychologyRoutes,
  ...authRoutes,
  ...profileRoutes,
  ...characterRoutes,
  ...homeRoutes,
  ...conversationRoutes,
  ...chatRoutes,
  ...imageRoutes
];

export function matchPath(template: string, actualPath: string): Record<string, string> | null {
  const templateSegments = template.split("/").filter(Boolean);
  const actualSegments = actualPath.split("/").filter(Boolean);
  const wildcardIndex = templateSegments.findIndex((segment) => segment.startsWith("*"));
  if (wildcardIndex >= 0 && wildcardIndex !== templateSegments.length - 1) return null;
  if (
    wildcardIndex < 0 && templateSegments.length !== actualSegments.length
    || wildcardIndex >= 0 && actualSegments.length < wildcardIndex + 1
  ) {
    return null;
  }

  const params: Record<string, string> = {};
  for (let index = 0; index < templateSegments.length; index += 1) {
    const templateSegment = templateSegments[index];
    if (templateSegment.startsWith("*")) {
      params[templateSegment.slice(1)] = actualSegments
        .slice(index)
        .map(decodeURIComponent)
        .join("/");
      return params;
    }
    const actualSegment = actualSegments[index];
    if (templateSegment.startsWith(":")) {
      params[templateSegment.slice(1)] = decodeURIComponent(actualSegment);
      continue;
    }
    if (templateSegment !== actualSegment) return null;
  }

  return params;
}

export default {
  async fetch(request: Request, env: Env, executionContext: ExecutionContext): Promise<Response> {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
            "Access-Control-Allow-Headers": "Authorization,Content-Type,X-Chat-Status"
          }
        });
      }

      const url = new URL(request.url);
      const route = routes.find((candidate) => candidate.method === request.method && matchPath(candidate.path, url.pathname));
      if (!route) {
        return json({ code: "NOT_FOUND", message: "Route not found." }, { status: 404 });
      }

      const params = matchPath(route.path, url.pathname) ?? {};
      const context: RequestContext = {
        request,
        env,
        url,
        params,
        waitUntil: (promise) => executionContext.waitUntil(promise)
      };

      if (route.auth) {
        context.user = await requireAuth(context);
      }

      const response = await route.handler(context);
      const headers = new Headers(response.headers);
      headers.set("Access-Control-Allow-Origin", "*");
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers
      });
    } catch (error) {
      return handleError(error);
    }
  },
  async scheduled(event: object, env: Env, ctx: { waitUntil(promise: Promise<any>): void }) {
    ctx.waitUntil(processOfflineMessages(env));
    ctx.waitUntil(processGroupFollowups(env));
    ctx.waitUntil(resumeEmotionPortraits(env));
  }
};
