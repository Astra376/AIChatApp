import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Env, RequestContext } from "../../env";
import { activeUltraUserIdsSql, createCheckout, ensureUltraSchema, getUltra, hasUltra, requireUltra } from "./index";
import { annualPrice, billingCountry, marketForCountry, priceCatalog, ULTRA_MARKETS } from "./pricing";
import { playAccountId, validatePlayPurchase, verifyPlayPurchase } from "./play";

function database(extra: Partial<Env> = {}) {
  const db = new DatabaseSync(":memory:");
  function statement(sql: string, args: any[] = []) {
    return {
      bind(...values: any[]) { return statement(sql, values); },
      async run() { return { meta: { changes: Number(db.prepare(sql).run(...args).changes) } }; },
      async first() { return db.prepare(sql).get(...args) ?? null; },
      async all() { return { results: db.prepare(sql).all(...args) }; }
    };
  }
  const env = { ...extra, DB: { prepare: statement, async batch(items: ReturnType<typeof statement>[]) {
    const results = []; for (const item of items) results.push(await item.run()); return results;
  } } } as unknown as Env;
  const context = { env, user: { userId: "owner" }, request: new Request("https://example.com"), params: {}, url: new URL("https://example.com") } as RequestContext;
  return { db, env, context };
}
afterEach(() => vi.unstubAllGlobals());

it("uses 13.99 USD, 19.99 AUD and a 30% annual discount with affordable regional targets", () => {
  expect(marketForCountry("US")).toMatchObject({ monthly: 1399, currency: "usd" });
  expect(marketForCountry("AU")).toMatchObject({ monthly: 1999, currency: "aud" });
  expect(marketForCountry("IN")).toMatchObject({ monthly: 39900, currency: "inr" });
  expect(annualPrice(1399)).toBe(11752);
  expect(annualPrice(1999)).toBe(16792);
  expect(new Set(ULTRA_MARKETS.map(item => item.country)).size).toBe(ULTRA_MARKETS.length);
});
it("uses only server geolocation and rejects invalid merchant catalogs", () => {
  const request = new Request("https://example.com?country=IN", { headers: { "CF-IPCountry": "IN" } });
  expect(billingCountry({ request } as RequestContext)).toBe("US");
  Object.defineProperty(request, "cf", { value: { country: "AU" } });
  expect(billingCountry({ request } as RequestContext)).toBe("AU");
  expect(() => priceCatalog({ STRIPE_ULTRA_PRICE_CATALOG: "broken" } as Env)).toThrow();
});
it("keeps checkout unavailable without provisioning, while exposing local price targets", async () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  const { db, context } = database();
  try {
    const info = await getUltra(context);
    expect(info.active).toBe(false); expect(info.available).toBe(false);
    expect(info.offers.map(item => [item.cadence, item.unitAmount, item.available])).toEqual([["monthly",1399,false],["annual",11752,false]]);
    await expect(createCheckout(context, "request_1234567890", "annual")).rejects.toMatchObject({ code: "BILLING_UNAVAILABLE" });
    expect(fetch).not.toHaveBeenCalled();
  } finally { db.close(); }
});
it("validates actual Stripe price before checkout and rejects mismatched amounts", async () => {
  const { db, context } = database({ STRIPE_SECRET_KEY: "test", STRIPE_WEBHOOK_SECRET: "test", BILLING_RETURN_URL: "https://example.com/return",
    STRIPE_ULTRA_PRICE_CATALOG: JSON.stringify({US:{monthly:"price_Month",annual:"price_Year"}}) });
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    calls.push(url);
    return new Response(JSON.stringify({active:true,currency:"usd",unit_amount:99,recurring:{interval:url.endsWith("Year")?"year":"month",interval_count:1}}));
  }));
  try {
    await expect(createCheckout(context,"request_1234567890","annual")).rejects.toMatchObject({code:"BILLING_UNAVAILABLE"});
    expect(calls.every(url=>url.includes("/prices/"))).toBe(true);
  } finally { db.close(); }
});
it("uses the same verified entitlement set for access and creator ranking", async () => {
  const { db, env } = database({ STRIPE_ULTRA_PRICE_CATALOG: JSON.stringify({US:{annual:"price_Annual"}}),
    PLAY_SERVICE_ACCOUNT_JSON:"configured",PLAY_ULTRA_PRODUCT_ID:"meek_ultra" });
  try {
    await ensureUltraSchema(env);
    const now=Date.now();
    for (const [user,price,status,end] of [["paid","price_Annual","active",now+60000],["expired","price_Annual","active",now-1],
      ["wrong_price","price_Other","active",now+60000],["canceled","price_Annual","canceled",now+60000]]) {
      db.prepare("INSERT INTO subscriptions VALUES (?,?,?,?,?,?,?)").run(user,user,"customer",status,price,end,now);
    }
    db.prepare("INSERT INTO play_subscriptions VALUES ('token','play_user','purchase','meek_ultra','active',?,?)").run(now+60000,now);
    db.prepare("INSERT INTO play_subscriptions VALUES ('stale','stale_user','purchase2','meek_ultra','active',?,?)").run(now+60000,now-3_600_001);
    const query=activeUltraUserIdsSql(env);
    expect(db.prepare(query.sql).all(...query.bindings as any[]).map(row=>row.user_id).sort()).toEqual(["paid","play_user"]);
    expect(await hasUltra(env,"paid")).toBe(true); expect(await hasUltra(env,"play_user")).toBe(true);
    for (const user of ["expired","wrong_price","canceled","other"]) await expect(requireUltra(env,user)).rejects.toMatchObject({code:"ULTRA_REQUIRED"});
  } finally { db.close(); }
});
it("handles unconfigured ranking and concurrent schema initialization", async () => {
  const { db, env }=database();
  try {
    await Promise.all([ensureUltraSchema(env),ensureUltraSchema(env)]);
    const query=activeUltraUserIdsSql(env);
    expect(db.prepare(query.sql).all(...query.bindings as any[])).toEqual([]);
  } finally { db.close(); }
});

const now=Date.now();
function purchase(changes: Record<string, unknown> = {}) {
  return {subscriptionState:"SUBSCRIPTION_STATE_ACTIVE",acknowledgementState:"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    externalAccountIdentifiers:{obfuscatedExternalAccountId:"owner_hash"},
    lineItems:[{productId:"ultra",expiryTime:new Date(now+60000).toISOString(),offerDetails:{basePlanId:"monthly"}}],...changes};
}
describe("Play receipt validation", () => {
  it("allows an active or canceled-but-still-paid period and a grace period", () => {
    for (const subscriptionState of ["SUBSCRIPTION_STATE_ACTIVE","SUBSCRIPTION_STATE_CANCELED","SUBSCRIPTION_STATE_IN_GRACE_PERIOD"])
      expect(validatePlayPurchase(purchase({subscriptionState}),"ultra","owner_hash",["monthly"],now)).toBe(now+60000);
  });
  it("rejects account transfer, wrong products/plans, pending, revoked and expired purchases", () => {
    expect(()=>validatePlayPurchase(purchase(),"ultra","other_hash",[],now)).toThrow();
    expect(()=>validatePlayPurchase(purchase(),"different_product","owner_hash",[],now)).toThrow();
    expect(()=>validatePlayPurchase(purchase(),"ultra","owner_hash",["annual"],now)).toThrow();
    for (const subscriptionState of ["SUBSCRIPTION_STATE_PENDING","SUBSCRIPTION_STATE_EXPIRED","SUBSCRIPTION_STATE_ON_HOLD","SUBSCRIPTION_STATE_PAUSED"])
      expect(()=>validatePlayPurchase(purchase({subscriptionState}),"ultra","owner_hash",[],now)).toThrow();
    expect(()=>validatePlayPurchase(purchase(),"ultra","owner_hash",[],now+60001)).toThrow();
  });
  it("verifies through Google, acknowledges once, grants only the owner, and revokes expired receipts", async () => {
    const keys=await crypto.subtle.generateKey({name:"RSASSA-PKCS1-v1_5",modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:"SHA-256"},true,["sign","verify"]);
    const key=Buffer.from(await crypto.subtle.exportKey("pkcs8",keys.privateKey)).toString("base64");
    const {db,env,context}=database({PLAY_SERVICE_ACCOUNT_JSON:JSON.stringify({client_email:"test-integration@example.com",private_key:`-----BEGIN PRIVATE KEY-----\n${key}\n-----END PRIVATE KEY-----`}),PLAY_ULTRA_PRODUCT_ID:"ultra",PLAY_ULTRA_BASE_PLANS:"monthly"});
    let revoked=false,acknowledgements=0;
    const account=await playAccountId("owner");
    vi.stubGlobal("fetch",vi.fn(async (url:string)=> {
      if(url.includes("oauth2"))return new Response(JSON.stringify({access_token:"verified-service-token",expires_in:3600}));
      if(url.endsWith(":acknowledge")){acknowledgements++;return new Response("{}");}
      return new Response(JSON.stringify(purchase({externalAccountIdentifiers:{obfuscatedExternalAccountId:account},
        subscriptionState:revoked?"SUBSCRIPTION_STATE_EXPIRED":"SUBSCRIPTION_STATE_ACTIVE",acknowledgementState:acknowledgements?"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED":"ACKNOWLEDGEMENT_STATE_PENDING"})));
    }));
    try {
      expect(await verifyPlayPurchase(context,"purchase_token_123")).toEqual({active:true});
      expect(await hasUltra(env,"owner")).toBe(true);
      await verifyPlayPurchase(context,"purchase_token_123"); expect(acknowledgements).toBe(1);
      await expect(verifyPlayPurchase({...context,user:{...context.user!,userId:"other"}},"purchase_token_123")).rejects.toMatchObject({code:"PURCHASE_ACCOUNT_MISMATCH"});
      revoked=true;
      await expect(verifyPlayPurchase(context,"purchase_token_123")).rejects.toMatchObject({code:"PURCHASE_INACTIVE"});
      expect(await hasUltra(env,"owner")).toBe(false);
    } finally {db.close();}
  });
});
