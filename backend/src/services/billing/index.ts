import type { Env, RequestContext } from "../../env";
import { AppError, assert } from "../../lib/errors";

const schema = new WeakMap<D1Database, Promise<void>>();
export async function ensureBillingSchema(env: Env): Promise<void> {
  let pending = schema.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([env.DB.prepare(`CREATE TABLE IF NOT EXISTS subscriptions (
      subscription_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, customer_id TEXT NOT NULL,
      status TEXT NOT NULL, price_id TEXT NOT NULL, expires_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL)`), env.DB.prepare("CREATE INDEX IF NOT EXISTS subscriptions_user ON subscriptions(user_id)")]).then(() => undefined);
    schema.set(env.DB, pending);
    pending.catch(() => schema.delete(env.DB));
  }
  await pending;
}

export function subscriptionGrantsUltra(status: string, expiresAt: number, now = Date.now()): boolean {
  return (status === "active" || status === "trialing") && expiresAt > now;
}

export async function hasUltra(env: Env, userId: string): Promise<boolean> {
  if (!env.STRIPE_ULTRA_PRICE_ID) return false;
  await ensureBillingSchema(env);
  const row = await env.DB.prepare(`SELECT subscription_id FROM subscriptions
    WHERE user_id = ? AND price_id = ? AND status IN ('active', 'trialing') AND expires_at > ? LIMIT 1`)
    .bind(userId, env.STRIPE_ULTRA_PRICE_ID, Date.now()).first();
  return row !== null;
}

function configured(env: Env): boolean {
  return Boolean(env.STRIPE_SECRET_KEY?.trim() && env.STRIPE_WEBHOOK_SECRET?.trim()
    && env.STRIPE_ULTRA_PRICE_ID?.trim() && env.BILLING_RETURN_URL?.startsWith("https://"));
}

async function stripe<T>(env: Env, path: string, body?: URLSearchParams, idempotencyKey?: string): Promise<T> {
  assert(configured(env), 503, "BILLING_UNAVAILABLE", "Ultra subscriptions are not available yet.");
  const response = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${env.STRIPE_SECRET_KEY}`, "Stripe-Version": "2025-06-30.basil",
      ...(body ? { "Content-Type": "application/x-www-form-urlencoded" } : {}),
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}) },
    body, signal: AbortSignal.timeout(15_000)
  });
  assert(response.ok, 502, "BILLING_ERROR", "The payment service is unavailable. Please try again.");
  return response.json() as Promise<T>;
}

interface StripePrice { active: boolean; currency: string; unit_amount: number | null; recurring?: { interval: string; interval_count: number }; }
export async function getUltra(context: RequestContext) {
  const active = await hasUltra(context.env, context.user!.userId);
  const available = configured(context.env);
  let price: StripePrice | undefined;
  if (available) price = await stripe(context.env, `prices/${encodeURIComponent(context.env.STRIPE_ULTRA_PRICE_ID!)}`);
  return { active, available: available && price?.active === true && Boolean(price?.recurring) && price?.unit_amount != null,
    model: context.env.OPENROUTER_ULTRA_MODEL || "deepseek/deepseek-v4-pro-0813",
    currency: price?.currency ?? null, unitAmount: price?.unit_amount ?? null,
    interval: price?.recurring?.interval ?? null, intervalCount: price?.recurring?.interval_count ?? 1 };
}

export async function createCheckout(context: RequestContext, requestKey: string) {
  assert(/^[a-zA-Z0-9_-]{16,100}$/.test(requestKey), 400, "INVALID_REQUEST", "Invalid checkout request.");
  assert(!(await hasUltra(context.env, context.user!.userId)), 409, "ULTRA_ACTIVE", "Ultra is already active.");
  const params = new URLSearchParams({ mode: "subscription", "line_items[0][price]": context.env.STRIPE_ULTRA_PRICE_ID ?? "",
    "line_items[0][quantity]": "1", client_reference_id: context.user!.userId,
    "subscription_data[metadata][user_id]": context.user!.userId,
    success_url: context.env.BILLING_RETURN_URL ?? "", cancel_url: context.env.BILLING_RETURN_URL ?? "" });
  const session = await stripe<{ url: string }>(context.env, "checkout/sessions", params, `ultra-${context.user!.userId}-${requestKey}`);
  assert(new URL(session.url).origin === "https://checkout.stripe.com", 502, "BILLING_ERROR", "Invalid checkout URL.");
  return { url: session.url };
}

export async function createPortal(context: RequestContext) {
  await ensureBillingSchema(context.env);
  const row = await context.env.DB.prepare("SELECT customer_id FROM subscriptions WHERE user_id = ? ORDER BY updated_at DESC LIMIT 1")
    .bind(context.user!.userId).first<{ customer_id: string }>();
  assert(row, 404, "NO_SUBSCRIPTION", "No subscription was found.");
  const result = await stripe<{ url: string }>(context.env, "billing_portal/sessions", new URLSearchParams({
    customer: row.customer_id, return_url: context.env.BILLING_RETURN_URL ?? ""
  }));
  assert(new URL(result.url).origin === "https://billing.stripe.com", 502, "BILLING_ERROR", "Invalid billing URL.");
  return result;
}

export async function verifyStripeSignature(raw: string, header: string, secret: string, now = Date.now()): Promise<boolean> {
  const parts = header.split(",").map(part => part.split("=", 2));
  const timestamp = Number(parts.find(([key]) => key === "t")?.[1]);
  if (!Number.isFinite(timestamp) || Math.abs(now / 1000 - timestamp) > 300) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  for (const [type, signature] of parts) {
    if (type !== "v1" || !/^[0-9a-f]{64}$/.test(signature ?? "")) continue;
    const bytes = Uint8Array.from(signature.match(/../g)!, value => Number.parseInt(value, 16));
    if (await crypto.subtle.verify("HMAC", key, bytes, new TextEncoder().encode(`${timestamp}.${raw}`))) return true;
  }
  return false;
}

interface Subscription {
  id: string; customer: string; status: string; metadata?: { user_id?: string };
  items: { data: Array<{ price: { id: string }; current_period_end?: number }> };
  current_period_end?: number;
}
export async function handleStripeWebhook(context: RequestContext) {
  assert(configured(context.env), 503, "BILLING_UNAVAILABLE", "Billing is not configured.");
  const raw = await context.request.text();
  assert(raw.length <= 256_000, 413, "PAYLOAD_TOO_LARGE", "Webhook payload is too large.");
  assert(await verifyStripeSignature(raw, context.request.headers.get("Stripe-Signature") ?? "", context.env.STRIPE_WEBHOOK_SECRET!),
    400, "INVALID_SIGNATURE", "Invalid webhook signature.");
  const event = JSON.parse(raw) as { type: string; data: { object: { id?: string; subscription?: string } } };
  const subscriptionId = event.type.startsWith("customer.subscription.") ? event.data.object.id
    : event.type === "checkout.session.completed" ? event.data.object.subscription : undefined;
  if (!subscriptionId) return { received: true };
  // Read current provider state: delayed/out-of-order webhooks cannot restore an expired entitlement.
  const current = await stripe<Subscription>(context.env, `subscriptions/${encodeURIComponent(subscriptionId)}`);
  const userId = current.metadata?.user_id;
  if (!userId) return { received: true };
  const item = current.items.data.find(value => value.price.id === context.env.STRIPE_ULTRA_PRICE_ID);
  await ensureBillingSchema(context.env);
  if (!item) {
    await context.env.DB.prepare("UPDATE subscriptions SET status = 'canceled', expires_at = 0, updated_at = ? WHERE subscription_id = ?")
      .bind(Date.now(), current.id).run();
    return { received: true };
  }
  await context.env.DB.prepare(`INSERT INTO subscriptions
    (subscription_id,user_id,customer_id,status,price_id,expires_at,updated_at) VALUES (?,?,?,?,?,?,?)
    ON CONFLICT(subscription_id) DO UPDATE SET status=excluded.status, price_id=excluded.price_id,
    expires_at=excluded.expires_at, updated_at=excluded.updated_at`)
    .bind(current.id, userId, current.customer, current.status, item.price.id,
      (item.current_period_end ?? current.current_period_end ?? 0) * 1000, Date.now()).run();
  return { received: true };
}
