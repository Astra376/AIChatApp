import { ensurePlaySchema, hasPlayUltra, playConfigured } from "./play";
import type { Env, RequestContext } from "../../env";
import { AppError, assert } from "../../lib/errors";
import { allowedStripePriceIds, annualPrice, billingCountry, marketForCountry, priceCatalog, type Cadence } from "./pricing";

const schema = new WeakMap<D1Database, Promise<void>>();
export async function ensureBillingSchema(env: Env): Promise<void> {
  let pending = schema.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([env.DB.prepare(`CREATE TABLE IF NOT EXISTS subscriptions (
      subscription_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, customer_id TEXT NOT NULL,
      status TEXT NOT NULL, price_id TEXT NOT NULL, expires_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL)`), env.DB.prepare("CREATE INDEX IF NOT EXISTS subscriptions_user ON subscriptions(user_id)"),
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS preview_ultra (
        user_id TEXT PRIMARY KEY, cadence TEXT NOT NULL, enabled INTEGER NOT NULL, updated_at INTEGER NOT NULL)`)]).then(() => undefined);
    schema.set(env.DB, pending);
    pending.catch(() => schema.delete(env.DB));
  }
  await pending;
}

export function subscriptionGrantsUltra(status: string, expiresAt: number, now = Date.now()): boolean {
  return (status === "active" || status === "trialing") && expiresAt > now;
}

async function hasStripeUltra(env: Env, userId: string): Promise<boolean> {
  const prices = allowedStripePriceIds(env);
  if (!prices.length) return false;
  await ensureBillingSchema(env);
  const row = await env.DB.prepare(`SELECT subscription_id FROM subscriptions
    WHERE user_id = ? AND price_id IN (${prices.map(() => "?").join(",")}) AND status IN ('active', 'trialing') AND expires_at > ? LIMIT 1`)
    .bind(userId, ...prices, Date.now()).first();
  return row !== null;
}

export async function hasUltra(env: Env, userId: string): Promise<boolean> {
  return await hasPreviewUltra(env, userId) || await hasStripeUltra(env, userId) || await hasPlayUltra(env, userId);
}

// Preview entitlements never create receipts, call payment providers, or survive
// activation of real billing. All access checks use this same server-owned gate.
export function previewBillingEnabled(env: Env): boolean {
  return env.ULTRA_PREVIEW_ENABLED === "true" && !env.STRIPE_SECRET_KEY?.trim()
    && !env.PLAY_SERVICE_ACCOUNT_JSON?.trim();
}
async function hasPreviewUltra(env: Env, userId: string): Promise<boolean> {
  if (!previewBillingEnabled(env)) return false;
  await ensureBillingSchema(env);
  return await env.DB.prepare("SELECT user_id FROM preview_ultra WHERE user_id = ? AND enabled = 1")
    .bind(userId).first() !== null;
}
export async function setPreviewUltra(context: RequestContext, enabled: boolean, cadence: Cadence = "monthly") {
  assert(previewBillingEnabled(context.env), 404, "PREVIEW_UNAVAILABLE", "Subscription testing is unavailable.");
  assert(typeof enabled === "boolean", 400, "INVALID_REQUEST", "Choose whether Ultra is enabled.");
  assert(cadence === "monthly" || cadence === "annual", 400, "INVALID_REQUEST", "Choose monthly or annual billing.");
  await ensureBillingSchema(context.env);
  await context.env.DB.prepare(`INSERT INTO preview_ultra (user_id,cadence,enabled,updated_at) VALUES (?,?,?,?)
    ON CONFLICT(user_id) DO UPDATE SET cadence=excluded.cadence, enabled=excluded.enabled, updated_at=excluded.updated_at`)
    .bind(context.user!.userId, cadence, enabled ? 1 : 0, Date.now()).run();
  return getUltra(context);
}

export async function requireUltra(env: Env, userId: string, feature = "This feature"): Promise<void> {
  assert(await hasUltra(env, userId), 403, "ULTRA_REQUIRED", `${feature} requires Ultra.`);
}

function configured(env: Env): boolean {
  return Boolean(env.STRIPE_SECRET_KEY?.trim() && env.STRIPE_WEBHOOK_SECRET?.trim()
    && allowedStripePriceIds(env).length && env.BILLING_RETURN_URL?.startsWith("https://"));
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
async function offersForRegion(context: RequestContext) {
  const country = billingCountry(context);
  const market = marketForCountry(country);
  const configuredPrices = priceCatalog(context.env)[market.country] ?? {};
  const offers = await Promise.all((["monthly", "annual"] as const).map(async cadence => {
    const id = configuredPrices[cadence];
    const expectedAmount = cadence === "annual" ? annualPrice(market.monthly) : market.monthly;
    const interval = cadence === "annual" ? "year" : "month";
    const price = configured(context.env) && id
      ? await stripe<StripePrice>(context.env, `prices/${encodeURIComponent(id)}`).catch(() => undefined) : undefined;
    const available = price?.active === true && price.currency === market.currency && price.unit_amount === expectedAmount
      && price.recurring?.interval === interval && price.recurring.interval_count === 1;
    return { cadence, available, currency: market.currency, unitAmount: expectedAmount, interval, intervalCount: 1,
      annualSavingsPercent: cadence === "annual" ? 30 : 0 };
  }));
  return { country, market: market.country, offers };
}
export async function getUltra(context: RequestContext) {
  const [stripeActive, playActive, regional, previewActive] = await Promise.all([hasStripeUltra(context.env, context.user!.userId),
    hasPlayUltra(context.env, context.user!.userId), offersForRegion(context), hasPreviewUltra(context.env, context.user!.userId)]);
  const active = stripeActive || playActive || previewActive;
  const monthly = regional.offers[0];
  return { active, billingProvider: stripeActive ? "stripe" : playActive ? "play" : previewActive ? "preview" : null, previewAvailable: previewBillingEnabled(context.env), previewActive, playAvailable: playConfigured(context.env), available: regional.offers.some(offer => offer.available), ...regional,
    model: context.env.OPENROUTER_ULTRA_MODEL || "deepseek/deepseek-v4-pro-0813",
    currency: monthly.currency, unitAmount: monthly.unitAmount, interval: monthly.interval, intervalCount: 1,
    capabilities: { customVoices: active, customFonts: active, profileCustomization: active, customBackgrounds: active,
      appIcons: active, premiumPortraits: active, advancedCharacterDetails: active, expandedMemory: active } };
}

export async function createCheckout(context: RequestContext, requestKey: string, cadence: Cadence = "monthly") {
  assert(/^[a-zA-Z0-9_-]{16,100}$/.test(requestKey), 400, "INVALID_REQUEST", "Invalid checkout request.");
  assert(cadence === "monthly" || cadence === "annual", 400, "INVALID_REQUEST", "Choose monthly or annual billing.");
  assert(!(await hasUltra(context.env, context.user!.userId)), 409, "ULTRA_ACTIVE", "Ultra is already active.");
  const region = await offersForRegion(context);
  assert(region.offers.find(offer => offer.cadence === cadence)?.available, 503, "BILLING_UNAVAILABLE", "This subscription is not available yet.");
  const price = priceCatalog(context.env)[region.market]?.[cadence];
  assert(price, 503, "BILLING_UNAVAILABLE", "This subscription is not available yet.");
  const params = new URLSearchParams({ mode: "subscription", "line_items[0][price]": price,
    "line_items[0][quantity]": "1", client_reference_id: context.user!.userId, billing_address_collection: "required",
    "subscription_data[metadata][user_id]": context.user!.userId, "subscription_data[metadata][country]": region.country,
    "subscription_data[metadata][cadence]": cadence,
    success_url: context.env.BILLING_RETURN_URL ?? "", cancel_url: context.env.BILLING_RETURN_URL ?? "" });
  // Do not supply payment_method_types: Stripe Checkout enables eligible cards and wallets,
  // including Google Pay, from the merchant's Dashboard payment-method configuration.
  const session = await stripe<{ url: string }>(context.env, "checkout/sessions", params, `ultra-${context.user!.userId}-${region.market}-${cadence}-${requestKey}`);
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
  const item = current.items.data.find(value => allowedStripePriceIds(context.env).includes(value.price.id));
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

export async function ensureUltraSchema(env: Env): Promise<void> { await Promise.all([ensureBillingSchema(env), ensurePlaySchema(env)]); }
export function activeUltraUserIdsSql(env: Env): {sql:string;bindings:unknown[]} {
  const prices=allowedStripePriceIds(env), clauses:string[]=[], bindings:unknown[]=[];
  if(prices.length) { clauses.push(`SELECT user_id FROM subscriptions WHERE price_id IN (${prices.map(()=>"?").join(",")}) AND status IN ('active','trialing') AND expires_at>?`); bindings.push(...prices,Date.now()); }
  if(previewBillingEnabled(env)) clauses.push("SELECT user_id FROM preview_ultra WHERE enabled=1");
  if(playConfigured(env)) { clauses.push("SELECT user_id FROM play_subscriptions WHERE product_id=? AND status='active' AND expires_at>? AND verified_at>?"); bindings.push(env.PLAY_ULTRA_PRODUCT_ID,Date.now(),Date.now()-3_600_000); }
  return {sql:clauses.length?clauses.join(" UNION "):"SELECT user_id FROM subscriptions WHERE 0",bindings};
}
