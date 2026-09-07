import type { Env, RequestContext } from "../../env";
import { AppError } from "../../lib/errors";
export type Cadence = "monthly" | "annual";
export interface Market { country: string; currency: string; monthly: number; }
// Merchant prices in currency minor units. These are provisioning targets;
// checkout is enabled only after matching real Stripe Prices are configured.
export const ULTRA_MARKETS: Market[] = [
  { country: "US", currency: "usd", monthly: 1399 },
  { country: "AU", currency: "aud", monthly: 1999 },
  { country: "CA", currency: "cad", monthly: 1899 },
  { country: "GB", currency: "gbp", monthly: 1099 },
  { country: "CH", currency: "chf", monthly: 1199 },
  { country: "NZ", currency: "nzd", monthly: 2199 },
  { country: "DE", currency: "eur", monthly: 1299 },
  { country: "FR", currency: "eur", monthly: 1299 },
  { country: "ES", currency: "eur", monthly: 1099 },
  { country: "IT", currency: "eur", monthly: 1199 },
  { country: "NL", currency: "eur", monthly: 1299 },
  { country: "IE", currency: "eur", monthly: 1299 },
  { country: "AT", currency: "eur", monthly: 1299 },
  { country: "BE", currency: "eur", monthly: 1299 },
  { country: "PT", currency: "eur", monthly: 899 },
  { country: "GR", currency: "eur", monthly: 799 },
  { country: "PL", currency: "pln", monthly: 2999 },
  { country: "RO", currency: "ron", monthly: 2499 },
  { country: "CZ", currency: "czk", monthly: 19900 },
  { country: "JP", currency: "jpy", monthly: 1499 },
  { country: "KR", currency: "krw", monthly: 14900 },
  { country: "SG", currency: "sgd", monthly: 1799 },
  { country: "MY", currency: "myr", monthly: 1999 },
  { country: "TH", currency: "thb", monthly: 14900 },
  { country: "CO", currency: "cop", monthly: 1499900 },
  { country: "CL", currency: "clp", monthly: 4999 },
  { country: "PE", currency: "pen", monthly: 1499 },
  { country: "IN", currency: "inr", monthly: 39900 },
  { country: "BR", currency: "brl", monthly: 2499 },
  { country: "MX", currency: "mxn", monthly: 9900 },
  { country: "PH", currency: "php", monthly: 19900 },
  { country: "ID", currency: "idr", monthly: 5900000 },
  { country: "VN", currency: "vnd", monthly: 99000 },
  { country: "ZA", currency: "zar", monthly: 7999 },
  { country: "EG", currency: "egp", monthly: 9999 },
  { country: "PK", currency: "pkr", monthly: 69900 },
  { country: "BD", currency: "bdt", monthly: 49900 },
  { country: "NG", currency: "ngn", monthly: 299900 },
  { country: "KE", currency: "kes", monthly: 39900 },
  { country: "TR", currency: "try", monthly: 14999 }
];
export function annualPrice(monthlyMinor: number): number { return Math.round(monthlyMinor * 12 * 70 / 100); }
export function billingCountry(context: RequestContext): string {
  // Cloudflare geolocation is server-owned; a supplied body/header cannot select a discounted region.
  const country = (context.request as Request & { cf?: { country?: string } }).cf?.country;
  return typeof country === "string" && /^[A-Z]{2}$/.test(country) ? country : "US";
}
export function marketForCountry(country: string): Market {
  return ULTRA_MARKETS.find(market => market.country === country) ?? ULTRA_MARKETS[0];
}
export type PriceCatalog = Record<string, Partial<Record<Cadence, string>>>;
export function priceCatalog(env: Env): PriceCatalog {
  const result: PriceCatalog = {};
  if (env.STRIPE_ULTRA_PRICE_CATALOG?.trim()) {
    let parsed: unknown;
    try { parsed = JSON.parse(env.STRIPE_ULTRA_PRICE_CATALOG); }
    catch { throw new AppError(503, "BILLING_CONFIGURATION", "Subscriptions are temporarily unavailable."); }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new AppError(503, "BILLING_CONFIGURATION", "Subscriptions are temporarily unavailable.");
    for (const [country, offers] of Object.entries(parsed)) {
      if (!/^[A-Z]{2}$/.test(country) || !offers || typeof offers !== "object") continue;
      for (const cadence of ["monthly", "annual"] as const) {
        const id = (offers as Record<string, unknown>)[cadence];
        if (typeof id === "string" && /^price_[a-zA-Z0-9]+$/.test(id)) (result[country] ??= {})[cadence] = id;
      }
    }
  }
  if (!result.US?.monthly && env.STRIPE_ULTRA_PRICE_ID) (result.US ??= {}).monthly = env.STRIPE_ULTRA_PRICE_ID;
  return result;
}
export function allowedStripePriceIds(env: Env): string[] {
  return [...new Set(Object.values(priceCatalog(env)).flatMap(offers => Object.values(offers)).filter((id): id is string => Boolean(id)))];
}
