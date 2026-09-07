export interface Env {
  DB: D1Database;
  ASSETS: R2Bucket;
  GOOGLE_WEB_CLIENT_ID: string;
  SESSION_HMAC_SECRET: string;
  OPENROUTER_API_KEY: string;
  OPENROUTER_MODEL: string;
  OPENROUTER_FALLBACK_MODELS?: string;
  OPENROUTER_PROVIDERS?: string;
  OPENROUTER_ULTRA_MODEL?: string;
  OPENROUTER_ULTRA_PROVIDERS?: string;
  RESEND_API_KEY?: string;
  NOTIFICATION_EMAIL_FROM?: string;
  NOTIFICATION_APP_URL?: string;
  STRIPE_SECRET_KEY?: string;
  STRIPE_WEBHOOK_SECRET?: string;
  STRIPE_ULTRA_PRICE_ID?: string;
  STRIPE_ULTRA_PRICE_CATALOG?: string;
  PLAY_SERVICE_ACCOUNT_JSON?: string;
  PLAY_PACKAGE_NAME?: string;
  PLAY_ULTRA_PRODUCT_ID?: string;
  PLAY_ULTRA_BASE_PLANS?: string;
  BILLING_RETURN_URL?: string;
  ULTRA_PREVIEW_ENABLED?: string;
  FAL_API_KEY: string;
  FAL_MODEL: string;
  FAL_ULTRA_MODEL?: string;
  FAL_BACKGROUND_MODEL?: string;
  R2_PUBLIC_BASE_URL: string;
}

export interface RequestContext {
  request: Request;
  env: Env;
  url: URL;
  params: Record<string, string>;
  user?: SessionClaims;
  waitUntil?: (promise: Promise<unknown>) => void;
}

export interface SessionClaims {
  userId: string;
  tokenType: "access" | "refresh";
  exp: number;
}

export function requireConfigured(env: Env, keys: Array<keyof Env>): void {
  for (const key of keys) {
    const value = env[key];
    if (typeof value === "string" && value.trim().length === 0) {
      throw new Error(`Missing required environment variable: ${String(key)}`);
    }
  }
}
