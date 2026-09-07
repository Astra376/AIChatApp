import { describe, it, expect, vi, afterEach } from "vitest";
import { hasUltra, handleStripeWebhook, subscriptionGrantsUltra, verifyStripeSignature } from "./index";
import type { Env, RequestContext } from "../../env";

async function signature(body: string, secret: string, timestamp: number) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const bytes = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${timestamp}.${body}`)));
  return `t=${timestamp},v1=${Array.from(bytes, b => b.toString(16).padStart(2,"0")).join("")}`;
}
afterEach(() => vi.unstubAllGlobals());
describe("Ultra entitlements", () => {
  it("does not grant expired, unpaid, canceled or past-due subscriptions", () => {
    const now = Date.now();
    for (const status of ["canceled", "unpaid", "past_due", "incomplete", "paused"]) expect(subscriptionGrantsUltra(status, now + 1000, now)).toBe(false);
    expect(subscriptionGrantsUltra("active", now - 1, now)).toBe(false);
    expect(subscriptionGrantsUltra("active", now + 1, now)).toBe(true);
  });
  it("does not query or grant Ultra when billing is unconfigured", async () => {
    expect(await hasUltra({} as Env, "u")).toBe(false);
  });
  it("rejects altered, stale and invalid signatures", async () => {
    const now = Date.now(), timestamp = Math.floor(now / 1000), body = '{"type":"checkout.session.completed"}';
    const signed = await signature(body, "secret", timestamp);
    expect(await verifyStripeSignature(body, signed, "secret", now)).toBe(true);
    expect(await verifyStripeSignature(body + " ", signed, "secret", now)).toBe(false);
    expect(await verifyStripeSignature(body, signed, "secret", now + 301_000)).toBe(false);
    expect(await verifyStripeSignature(body, "t=NaN,v1=bad", "secret", now)).toBe(false);
  });
  it("retrieves current Stripe state and revokes when the Ultra price is removed", async () => {
    const mutations: string[] = [];
    const stmt = (sql: string) => ({ bind: (..._values: unknown[]) => ({ run: async () => { mutations.push(sql); return {}; } }) });
    const env = { STRIPE_SECRET_KEY: "sk_test", STRIPE_WEBHOOK_SECRET: "hook", STRIPE_ULTRA_PRICE_ID: "price_ultra", BILLING_RETURN_URL: "https://example.com/return",
      DB: { prepare: stmt, batch: async () => [] } } as unknown as Env;
    const body = JSON.stringify({ type: "customer.subscription.updated", data: { object: { id: "sub_1", status: "active" } } });
    const header = await signature(body, "hook", Math.floor(Date.now() / 1000));
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "sub_1", customer: "cus_1", metadata: { user_id: "user" }, status: "active", items: { data: [{ price: { id: "price_other" } }] } })));
    vi.stubGlobal("fetch", fetch);
    const context = { env, request: new Request("https://example.com/v1/ultra/webhook", { method: "POST", body, headers: { "Stripe-Signature": header } }) } as RequestContext;
    await handleStripeWebhook(context);
    expect(fetch.mock.calls[0][0]).toBe("https://api.stripe.com/v1/subscriptions/sub_1");
    expect(mutations.some(sql => sql.includes("expires_at = 0"))).toBe(true);
  });
});
