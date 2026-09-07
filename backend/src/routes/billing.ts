import { playBillingRoutes } from "../services/billing/play";
import { json } from "../lib/response";
import { parseJson, requireString } from "../lib/validation";
import { createCheckout, createPortal, getUltra, handleStripeWebhook } from "../services/billing";
import type { RouteDefinition } from "./types";

export const billingRoutes: RouteDefinition[] = [
  ...playBillingRoutes,
  { method: "GET", path: "/v1/ultra", auth: true, handler: async context => json(await getUltra(context)) },
  { method: "POST", path: "/v1/ultra/checkout", auth: true, handler: async context => {
    const body = await parseJson<{ requestKey?: string; cadence?: "monthly" | "annual" }>(context.request);
    return json(await createCheckout(context, requireString(body.requestKey, "requestKey", 100), body.cadence ?? "monthly"));
  } },
  { method: "POST", path: "/v1/ultra/portal", auth: true, handler: async context => json(await createPortal(context)) },
  { method: "POST", path: "/v1/ultra/webhook", handler: async context => json(await handleStripeWebhook(context)) }
];
