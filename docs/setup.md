# Setup Guide

This repo is split into:

- `android-app/`: the Android client
- `backend/`: the Cloudflare Worker

The Android app now expects the real Worker for auth, persistence, portraits, and model responses.

## 1. Install the local toolchain

Install these on your machine first:

- Android Studio with Android SDK Platform 35 and JDK 17
- Node.js 20+
- npm 10+
- Wrangler 4 (`npm install -g wrangler` is fine if you prefer a global install)

This workspace did not have Java, Gradle, Node, or npm available, so I could not generate the full Gradle wrapper jar or run the builds here.

## 2. Android project settings

The Android build flags live in:

- `/home/connor/projects/AIChat/AIChatApp/android-app/gradle.properties`

Edit these keys there:

```properties
AI_CHAT_API_BASE_URL=https://YOUR_WORKER_SUBDOMAIN.workers.dev/
AI_CHAT_GOOGLE_WEB_CLIENT_ID=YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com
```

What each one does:

- `AI_CHAT_API_BASE_URL`
  Must end with `/`.
  This is the deployed Cloudflare Worker base URL.
- `AI_CHAT_GOOGLE_WEB_CLIENT_ID`
  This must be the Google OAuth **Web application** client ID, not the Android one.

## 3. Google sign-in setup

Because the app uses Credential Manager + Google ID tokens, create these Google Cloud OAuth clients:

1. Create an **Android** OAuth client.
2. Package name must be:
   `com.example.aichat`
3. Add your debug SHA-1 and release SHA-1 fingerprints to that Android OAuth client.
4. Create a **Web application** OAuth client.
5. Put the **Web application client ID** in both places:
   - `/home/connor/projects/AIChat/AIChatApp/android-app/gradle.properties` as `AI_CHAT_GOOGLE_WEB_CLIENT_ID`
   - `/home/connor/projects/AIChat/AIChatApp/backend/wrangler.toml` under `[vars]` as `GOOGLE_WEB_CLIENT_ID`

You do not need `google-services.json` for this implementation because the app is not using Firebase Auth SDKs.

## 4. Cloudflare Worker setup

### 4.1 Install backend dependencies

```bash
cd /home/connor/projects/AIChat/AIChatApp/backend
npm install
```

### 4.2 Create the Cloudflare resources

Create a D1 database:

```bash
wrangler d1 create character-chat
```

Create an R2 bucket:

```bash
wrangler r2 bucket create character-chat-assets
```

Then update:

- `/home/connor/projects/AIChat/AIChatApp/backend/wrangler.toml`

Fill in these fields:

- `database_id`
  Use the value returned by `wrangler d1 create`
- `database_name`
  Keep `character-chat` unless you intentionally renamed it
- `bucket_name`
  Keep `character-chat-assets` unless you intentionally renamed it
- `GOOGLE_WEB_CLIENT_ID`
  Put your Web OAuth client ID here
- `OPENROUTER_MODEL`
  Put the OpenRouter model slug you want to use
- `OPENROUTER_FALLBACK_MODELS`
  Optional comma-separated model slugs, in priority order, used when the primary model is unavailable
- `OPENROUTER_PORTRAIT_REALISTIC_MODEL` / `OPENROUTER_PORTRAIT_STYLIZED_MODEL`
  Models for the selected full-resolution portrait, classified by the requested art style
- `OPENROUTER_EXPRESSION_REALISTIC_MODEL` / `OPENROUTER_EXPRESSION_STYLIZED_MODEL`
  Reference-editing models selected after the character-consistency comparison
- `OPENROUTER_BACKGROUND_MODEL`
  Model for scene and appearance backgrounds
- `OPENROUTER_PORTRAIT_PREVIEW_MODEL`
  Nano Banana 2 for native 512px stylized previews; realistic previews use FLUX Pro at 512px
- `OPENROUTER_PORTRAIT_PREMIUM_MODEL`
  Nano Banana Pro for Ultra portrait refinement
- `R2_PUBLIC_BASE_URL`
  Put the public base URL for your bucket or asset domain

### 4.3 Apply the database schema

Run the backend migrations from:

- `/home/connor/projects/AIChat/AIChatApp/backend/src/db/migrations/0001_initial.sql`
- `/home/connor/projects/AIChat/AIChatApp/backend/src/db/migrations/0002_conversation_streaming.sql`

Commands:

```bash
wrangler d1 execute character-chat --remote --file=src/db/migrations/0001_initial.sql
wrangler d1 execute character-chat --remote --file=src/db/migrations/0002_conversation_streaming.sql
wrangler d1 execute character-chat --remote --file=src/db/migrations/0003_offline_messaging.sql
```

### 4.4 Create Worker secrets

These secrets must be created with `wrangler secret put` from inside `/home/connor/projects/AIChat/AIChatApp/backend`.

Create the session signing secret:

```bash
wrangler secret put SESSION_HMAC_SECRET
```

Use a long random value, for example 32+ bytes from a password manager or:

```bash
openssl rand -base64 48
```

Create the OpenRouter API key:

```bash
wrangler secret put OPENROUTER_API_KEY
```

Create the fal API key only for voice synthesis and voice creation:

```bash
wrangler secret put FAL_API_KEY
```

Do **not** put these secrets in `wrangler.toml`.

### 4.5 Deploy the Worker

```bash
npm run deploy
```

After deployment, copy the Worker URL into:

- `/home/connor/projects/AIChat/AIChatApp/android-app/gradle.properties`

Set:

```properties
AI_CHAT_API_BASE_URL=https://YOUR_WORKER_SUBDOMAIN.workers.dev/
```

## 5. Run the Android app

After the Worker is deployed:

1. Set `AI_CHAT_API_BASE_URL` to your Worker URL
2. Set `AI_CHAT_GOOGLE_WEB_CLIENT_ID` to your Web client ID
3. Open `/home/connor/projects/AIChat/AIChatApp/android-app` in Android Studio
4. Let Android Studio install missing SDK components
5. If the Gradle wrapper jar is missing, open Terminal in `android-app/` and run:

```bash
gradle wrapper
```

6. Sync the project again
7. Connect your Android phone with USB debugging enabled, or start an emulator
8. Select the `app` run configuration
9. Press Run

## 6. Test commands

Run Android unit tests from `/home/connor/projects/AIChat/AIChatApp/android-app`:

```bash
./gradlew testDebugUnitTest
```

Run backend tests from `/home/connor/projects/AIChat/AIChatApp/backend`:

```bash
npm test
```

## 7. What is ready now vs later

Ready now:

- full Android app shell
- sign-in gate
- home/search
- character studio
- chats list
- chat screen
- destructive edit
- destructive rewind
- latest-only regenerate
- variant selection for latest assistant
- light/dark themes
- Room-backed local state
- backend scaffold

Needs real credentials/resources to go live:

- Google token verification
- Worker deployment
- D1 persistence in Cloudflare
- R2 asset serving
- OpenRouter model responses
- OpenRouter image generation

## 8. Voices, notifications and Ultra (September 2026)

The deployed Worker needs the existing `FAL_API_KEY`, `SESSION_HMAC_SECRET`, `DB`
and `ASSETS` bindings for voices. Voice and billing tables are created on first use;
`src/db/migrations/0009_voices_and_ultra.sql` also declares them for provisioned
installations. Google Play receipt storage is declared in
`0016_play_subscriptions.sql`; notification tables are in `0007_notifications.sql`.

### Voices

Settings → Voices opens the official and community catalog. The character studio
can select a voice, create one from a description, or import an audio/video sample.
Everyone can use official/community voices. Creating a custom voice requires a
verified Ultra subscription; the API enforces this before uploads or inference,
and the create action opens an Ultra upgrade screen for Standard users.
Android extracts the video's audio locally with `MediaExtractor`/`MediaMuxer`; no
video is uploaded. Clear recordings must be 3 seconds–5 minutes and under 10 MB;
video extraction keeps at most its first minute. The server saves reusable voice
embeddings in private R2 storage and discards the source recording after cloning.
Private voices can only be assigned to private characters.

The implementation uses the existing fal account and Qwen3-TTS 1.7B endpoints:

| Operation | Endpoint | Published provider price, checked 7 September 2026 |
| --- | --- | --- |
| Read aloud | `fal-ai/qwen-3-tts/text-to-speech/1.7b` | $0.09 / 1,000 characters |
| Describe a voice | `fal-ai/qwen-3-tts/voice-design/1.7b` | $0.09 / 1,000 preview characters |
| Reusable sample embedding | `fal-ai/qwen-3-tts/clone-voice/1.7b` | $0.0008 / minute |

Sources: [speech synthesis](https://fal.ai/models/fal-ai/qwen-3-tts/text-to-speech/1.7b),
[voice design](https://fal.ai/models/fal-ai/qwen-3-tts/voice-design/1.7b),
[voice cloning](https://fal.ai/models/fal-ai/qwen-3-tts/clone-voice/1.7b).
These are provider costs, not subscription prices. The same provider supports the
nine published official voices, description design and reusable sample voices;
this avoids another provider key or MiniMax's per-created-voice charges.

Read aloud checks Android's **media volume before making a synthesis request**.
Completed audio is cached by account, selected voice and text; replay incurs no
new inference. The server verifies conversation ownership, reads the selected
reply version and issues expiring audio URLs. Native media playback stops when
leaving the chat or when audio focus is lost. New inference is bounded and is not
automatically retried. Live paid voice generation has not been exercised by the
automated tests; they verify access, caching and request construction.

### Notification email

Android notifications use WorkManager with a 15-minute minimum interval. Android
may defer work during Doze or battery saving, so this is not an exact delivery
alarm. In-app activity and server-generated unread messages remain available
without an email provider. Android 13+ still requires the system notification
permission.

For email delivery, verify an owned sending domain in
[Resend](https://resend.com/docs/add-a-domain), then configure the Worker:

```bash
npx wrangler secret put RESEND_API_KEY
```

Set these non-secret values in the Worker's environment (or `[vars]`):

| Variable | Value to supply |
| --- | --- |
| `NOTIFICATION_EMAIL_FROM` | A sender on the Resend-verified domain, such as your chosen Meek notifications address |
| `NOTIFICATION_APP_URL` | Your actual app-opening HTTPS link; if omitted the current implementation uses `meek://activity` |

Emails use the account email, character message titles, a direct app link and a
signed unsubscribe link. The scheduled delivery checks settings, inactivity,
read/dismissed state and rate limits before sending. Missing email configuration
skips delivery; it never records an email as sent. The sender address remains the
verified app address. No email has been sent as part of automated validation.
Offline generation produces a varied, discreet character-named subject alongside
the message in the same model request. Invalid titles fall back to the standard
“sent you a message” subject; title metadata never enters the chat transcript.

### Ultra subscription activation (configuration can be added later)

The app and Worker include both payment adapters. **No merchant connection,
purchase, live charge or subscription product was created during this change.**
Unconfigured checkout stays disabled; development and all Standard features work
without Stripe. The GitHub APK uses hosted Stripe Checkout. A Play-installed build
uses native Google Play Billing 9.1.0 with Play's localized product prices,
pending-payment handling, restore purchases and server verification.

Regional provisioning targets are in `backend/src/services/billing/pricing.ts`.
The base monthly prices are USD $13.99 and AUD $19.99. Annual billing is 30% less
than twelve monthly payments (USD $117.52/year, AUD $167.92/year after rounding to
currency minor units). Forty markets include lower local price targets such as
INR ₹399/month. These are editable merchant targets, not claims of measured
income parity or automatically optimized sales. Unsupported markets fall back to
USD. Configure matching monthly and annual prices in each merchant catalog.

#### Stripe, when ready

Create recurring Ultra prices and configure a Customer Portal with cancellation
and payment-method management. Checkout supports eligible cards and Google Pay
through Stripe Dashboard's enabled payment methods. Set Worker secrets
`STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` privately with Wrangler. Set:

| Variable | Value to supply |
| --- | --- |
| `STRIPE_ULTRA_PRICE_CATALOG` | JSON by country and cadence, e.g. `{"US":{"monthly":"price_realMonthly","annual":"price_realAnnual"},"AU":{"monthly":"price_realAuMonthly","annual":"price_realAuAnnual"}}`; replace every example ID |
| `STRIPE_ULTRA_PRICE_ID` | Optional legacy US monthly price ID, used only if not present in the catalog |
| `BILLING_RETURN_URL` | Your HTTPS page displayed after checkout/portal; app status refreshes when resumed |

The Worker validates the actual Stripe Price's currency, amount and billing
interval before enabling an offer or starting checkout. Country comes from
Cloudflare's server geolocation, never a client-selected request field.

Add a webhook at
`https://character-chat-worker.robloxproxy.workers.dev/v1/ultra/webhook` for
`checkout.session.completed` and `customer.subscription.created`, `.updated`,
and `.deleted`. Keys, prices and webhook must use the same Stripe mode. The Worker
verifies the raw-body signature and fetches current provider state, so delayed
webhooks cannot restore a canceled subscription. Only configured prices with an
active/trialing paid period grant access. A browser redirect grants no entitlement.

#### Google Play, when ready

Provision the app and subscription in Play Console, including the monthly and
annual base plans and their localized prices. Configure an Android Publisher
service account with subscription access. Set these Worker values:

| Variable | Value to supply |
| --- | --- |
| `PLAY_SERVICE_ACCOUNT_JSON` | Private service account JSON, stored as a Worker secret |
| `PLAY_PACKAGE_NAME` | Published Android package; defaults to `com.example.aichat` |
| `PLAY_ULTRA_PRODUCT_ID` | The actual Play subscription product ID |
| `PLAY_ULTRA_BASE_PLANS` | Comma-separated allowlist of the real monthly and annual base plan IDs |

Purchase flows include a stable hashed account identifier. The Worker reads the
receipt from Google's subscriptions v2 API, verifies its app/product/base plan,
account and future paid period, then acknowledges it server-side. A token cannot
be moved between Meek accounts. Pending, on-hold, paused and expired subscriptions
do not grant access; an active grace period and canceled-but-still-paid period do.
Play entitlements refresh against Google at most hourly when accessed, and expire
at the paid period boundary. Restore purchases also refreshes immediately. Instant
refund/revocation handling via Real-time Developer Notifications is not wired;
this is a bounded polling implementation with up to a one-hour revocation delay.

Shared `requireUltra` checks protect custom voice creation, manual Ultra model
selection and all paid customization APIs. `activeUltraUserIdsSql` uses the same
configured products and current entitlement criteria for creator ranking. The UI
opens the correct Stripe or Play subscription management screen for the account.

Tests use in-memory SQLite and mocked provider responses, including owner checks,
wrong products/plans, expiration, cancellation, signature forgery, acknowledgment,
regional price validation and unconfigured operation. Merchant sandbox and Play
license-tester purchases remain deployment checks once accounts are configured.
References: [Stripe Checkout](https://docs.stripe.com/api/checkout/sessions/create),
[Stripe signatures](https://docs.stripe.com/webhooks/signature),
[Google Play integration](https://developer.android.com/google/play/billing/integrate),
[Google Play test setup](https://developer.android.com/google/play/billing/test).

### Chat model routing

The current Worker configuration uses:

```toml
OPENROUTER_MODEL = "deepseek/deepseek-v4-flash-0731"
OPENROUTER_PROVIDERS = "venice"
OPENROUTER_ULTRA_MODEL = "deepseek/deepseek-v4-pro-0813"
OPENROUTER_FALLBACK_MODELS = ""
```

Flash is restricted to the configured Venice provider instead of silently
switching model revisions. Manual Ultra selection requires server entitlement
verification. Automatic mode also allows bounded Pro/reasoning turns for Standard
accounts (one Pro and four total reasoning turns per day, shared across chats).
Pro uses its own optional `OPENROUTER_ULTRA_PROVIDERS` restriction;
by default it uses the available OpenRouter provider set. The Flash Venice
restriction must not be inherited by Pro because that provider does not list
Pro 0813. Provider availability, provider policies and latency can change;
no unconditional speed or content-policy guarantee is encoded in the UI.


## OpenRouter image pipeline

All new images use `POST https://openrouter.ai/api/v1/images` with the existing
`OPENROUTER_API_KEY`; text-provider restrictions are not passed to image requests.
Image bytes are validated and written directly to R2. The model and art style are
stored with portraits, so a generic refinement prompt does not change an anime
portrait into a photograph. Expressions always receive the chosen portrait as an
image reference. A missing/rejected model can fall back to Nano Banana on OpenRouter;
an ambiguous timeout does not silently submit another paid generation.

The IMAGE_JOBS Durable Object binding and `image-jobs-v1` SQLite migration keep
expression calls alive independently of the Android screen and the HTTP request.
Already-purchased Fal jobs have a read-only completion path; no new Fal image request
is submitted. Existing saved images remain available. Fal is used for voices only.

The bounded live comparison is defined in `backend/evaluation/run.json` and
`src/services/images/evaluationCases.ts`. Changing the manifest triggers evaluation
after the backend's normal tests and deployment. CI installs an expiring evaluation
secret, runs only fixed synthetic cases with stable job IDs, saves original outputs
and reported costs as an artifact, then deletes the secret. Repeated polling never
creates additional images. There is no public arbitrary-prompt evaluation endpoint.
Backend-only pushes skip the Android build.

The reviewed defaults use FLUX.2 Pro for realistic portraits and Seedream 5.0 Lite
for stylized portraits. FLUX supports 512px previews but still bills $0.03 per
image; Seedream rejects 512px and requires at least 2K, so stylized preview choices
use native 512px Nano Banana 2. The selected preview is passed as an image reference
for its full-resolution refinement. Klein 9B returned HTTP 404 on OpenRouter, so
backgrounds use Nano Banana 2. See the actual outputs, editing observations, and
measured costs in [the image comparison](image-model-evaluation.md).

API references: [OpenRouter Image API](https://openrouter.ai/docs/guides/overview/multimodal/image-generation),
[image model catalog](https://openrouter.ai/api/v1/images/models).
