import type { Env, RequestContext } from "../../env";
import { AppError, assert } from "../../lib/errors";
import { json } from "../../lib/response";
import { parseJson, requireString } from "../../lib/validation";
import type { RouteDefinition } from "../../routes/types";
const schema = new WeakMap<D1Database, Promise<void>>();
export function playConfigured(env: Env): boolean { return Boolean(env.PLAY_SERVICE_ACCOUNT_JSON?.trim() && env.PLAY_ULTRA_PRODUCT_ID?.trim()); }
export async function ensurePlaySchema(env: Env) {
  let pending = schema.get(env.DB);
  if (!pending) {
    pending = env.DB.batch([
      env.DB.prepare(`CREATE TABLE IF NOT EXISTS play_subscriptions (token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,
        purchase_token TEXT NOT NULL,product_id TEXT NOT NULL,status TEXT NOT NULL,expires_at INTEGER NOT NULL,verified_at INTEGER NOT NULL)`),
      env.DB.prepare("CREATE INDEX IF NOT EXISTS play_subscriptions_user ON play_subscriptions(user_id)")
    ]).then(() => undefined);
    schema.set(env.DB,pending); pending.catch(() => schema.delete(env.DB));
  }
  await pending;
}
function base64(bytes: Uint8Array) { return btoa(String.fromCharCode(...bytes)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,""); }
export async function playAccountId(userId: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(`meek-play:${userId}`))),value => value.toString(16).padStart(2,"0")).join("");
}
const tokens = new Map<string,{token:string,expires:number}>();
async function serviceToken(env: Env): Promise<string> {
  assert(playConfigured(env),503,"PLAY_UNAVAILABLE","Google Play subscriptions are not available yet.");
  let account: {client_email:string;private_key:string};
  try {
    account = JSON.parse(env.PLAY_SERVICE_ACCOUNT_JSON!);
    if (!account || typeof account.client_email !== "string" || typeof account.private_key !== "string") throw new Error("Invalid account");
  } catch { throw new AppError(503, "PLAY_UNAVAILABLE", "Google Play subscriptions are not available yet."); }
  const existing = tokens.get(account.client_email); if(existing && existing.expires>Date.now()+60_000)return existing.token;
  const encoded = (value: unknown) => base64(new TextEncoder().encode(JSON.stringify(value)));
  const now=Math.floor(Date.now()/1000);
  const unsigned=`${encoded({alg:"RS256",typ:"JWT"})}.${encoded({iss:account.client_email,scope:"https://www.googleapis.com/auth/androidpublisher",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3600})}`;
  const der = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----/g,"").replace(/\s/g,"")), value=>value.charCodeAt(0));
  const key=await crypto.subtle.importKey("pkcs8",der,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const jwt=`${unsigned}.${base64(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,new TextEncoder().encode(unsigned))))}`;
  const response=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:jwt}),signal:AbortSignal.timeout(15_000)});
  assert(response.ok,502,"PLAY_VERIFY_UNAVAILABLE","Google Play verification is temporarily unavailable.");
  const result=await response.json() as {access_token:string;expires_in:number};
  assert(typeof result.access_token === "string" && result.access_token.length > 0 && Number.isFinite(result.expires_in),
    502,"PLAY_VERIFY_UNAVAILABLE","Google Play verification is temporarily unavailable.");
  tokens.set(account.client_email,{token:result.access_token,expires:Date.now()+result.expires_in*1000});return result.access_token;
}
interface PlayPurchase {subscriptionState:string;acknowledgementState?:string;externalAccountIdentifiers?:{obfuscatedExternalAccountId?:string};
  lineItems:Array<{productId:string;expiryTime:string;offerDetails?:{basePlanId?:string}}>;}
export function validatePlayPurchase(purchase: PlayPurchase, productId: string, accountId:string, basePlans:string[]=[], now=Date.now()): number {
  assert(purchase && purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId===accountId,403,"PURCHASE_ACCOUNT_MISMATCH","This purchase belongs to a different account.");
  assert(["SUBSCRIPTION_STATE_ACTIVE","SUBSCRIPTION_STATE_IN_GRACE_PERIOD","SUBSCRIPTION_STATE_CANCELED"].includes(purchase.subscriptionState),403,"PURCHASE_INACTIVE","This subscription is not active.");
  assert(Array.isArray(purchase.lineItems),403,"PURCHASE_INVALID","Google Play returned an incomplete subscription.");
  const item=purchase.lineItems.find(item=>item.productId===productId && (!basePlans.length || basePlans.includes(item.offerDetails?.basePlanId??"")));
  const expires=Date.parse(item?.expiryTime??"");
  assert(item && Number.isFinite(expires) && expires>now,403,"PURCHASE_EXPIRED","This subscription has expired or uses an unavailable plan.");
  return expires;
}
export async function verifyPlayPurchase(context:RequestContext, purchaseToken:string) {
  const env=context.env,userId=context.user!.userId;
  assert(playConfigured(env),503,"PLAY_UNAVAILABLE","Google Play subscriptions are not available yet.");
  assert(purchaseToken.length>=10 && purchaseToken.length<=4000,400,"INVALID_PURCHASE","Invalid purchase token.");
  await ensurePlaySchema(env);
  const hash=await playAccountId(purchaseToken);
  const prior=await env.DB.prepare("SELECT user_id FROM play_subscriptions WHERE token_hash=?").bind(hash).first<{user_id:string}>();
  assert(!prior || prior.user_id===userId,403,"PURCHASE_ACCOUNT_MISMATCH","This purchase belongs to a different account.");
  const auth=await serviceToken(env),pkg=encodeURIComponent(env.PLAY_PACKAGE_NAME||"com.example.aichat");
  const response=await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,{headers:{Authorization:`Bearer ${auth}`},signal:AbortSignal.timeout(15_000)});
  if(!response.ok) {
    if(response.status===404||response.status===410)await env.DB.prepare("UPDATE play_subscriptions SET status='expired',expires_at=0,verified_at=? WHERE token_hash=? AND user_id=?").bind(Date.now(),hash,userId).run();
    assert(false,response.status >= 500 ? 502 : 403,response.status >= 500 ? "PLAY_VERIFY_UNAVAILABLE" : "PURCHASE_INVALID","Google Play could not verify this purchase. Restore purchases to try again.");
  }
  const purchase=await response.json() as PlayPurchase;
  let expires:number;
  try {expires=validatePlayPurchase(purchase,env.PLAY_ULTRA_PRODUCT_ID!,await playAccountId(userId),env.PLAY_ULTRA_BASE_PLANS?.split(",").map(value=>value.trim()).filter(Boolean));}
  catch(error) {await env.DB.prepare("UPDATE play_subscriptions SET status='expired',expires_at=0,verified_at=? WHERE token_hash=? AND user_id=?").bind(Date.now(),hash,userId).run();throw error;}
  if(purchase.acknowledgementState==="ACKNOWLEDGEMENT_STATE_PENDING") {
    const acknowledged=await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/subscriptions/${encodeURIComponent(env.PLAY_ULTRA_PRODUCT_ID!)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,{method:"POST",headers:{Authorization:`Bearer ${auth}`,"Content-Type":"application/json"},body:"{}",signal:AbortSignal.timeout(15_000)});
    assert(acknowledged.ok,502,"PURCHASE_ACKNOWLEDGEMENT","Google Play is still confirming this purchase. Refresh your subscription status.");
  }
  await env.DB.prepare(`INSERT INTO play_subscriptions(token_hash,user_id,purchase_token,product_id,status,expires_at,verified_at) VALUES(?,?,?,?,'active',?,?)
    ON CONFLICT(token_hash) DO UPDATE SET status='active',expires_at=excluded.expires_at,verified_at=excluded.verified_at WHERE play_subscriptions.user_id=excluded.user_id`)
    .bind(hash,userId,purchaseToken,env.PLAY_ULTRA_PRODUCT_ID!,expires,Date.now()).run();
  return {active:true};
}
export async function hasPlayUltra(env:Env,userId:string):Promise<boolean> {
  if(!playConfigured(env))return false;
  await ensurePlaySchema(env);
  const row=await env.DB.prepare("SELECT purchase_token,verified_at FROM play_subscriptions WHERE user_id=? AND product_id=? AND status='active' AND expires_at>? ORDER BY verified_at DESC LIMIT 1")
    .bind(userId,env.PLAY_ULTRA_PRODUCT_ID,Date.now()).first<{purchase_token:string;verified_at:number}>();
  if(!row)return false;
  if(row.verified_at>Date.now()-3_600_000)return true;
  try {await verifyPlayPurchase({env,user:{userId}} as RequestContext,row.purchase_token);return true;}catch{return false;}
}
export const playBillingRoutes:RouteDefinition[]=[
  {method:"GET",path:"/v1/ultra/play-config",auth:true,handler:async context=>json({available:playConfigured(context.env),productId:context.env.PLAY_ULTRA_PRODUCT_ID??null,obfuscatedAccountId:await playAccountId(context.user!.userId)})},
  {method:"POST",path:"/v1/ultra/play-verify",auth:true,handler:async context=>{const body=await parseJson<{purchaseToken?:string}>(context.request);return json(await verifyPlayPurchase(context,requireString(body.purchaseToken,"purchaseToken",4000)));}}
];
