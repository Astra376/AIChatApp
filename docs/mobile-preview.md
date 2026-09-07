# Meek mobile preview

This branch preserves the native Compose layout and repairs the chat lifecycle while adding the features requested in the September 7 brief.

## Chat reliability and presentation

- An app-owned operation survives leaving a chat. Reopening shows received text; it does not replay a finished reply.
- Streaming deltas arrive before completion, with optional native haptics enabled by default. Scrolling follows new content until a real user drag moves into history.
- Sending a new message locks the earlier assistant's alternatives. The remaining eligible alternative pager uses native Compose animation.
- Edits and rewinds update local state immediately, target the tapped message ID, reject duplicate operations, and roll back on failure. Backend updates use targeted SQL with revision and generation-run fences.
- Stream timeouts and explicit cancellation release generation state. The new reasoning label is negotiated with a request header so older app versions keep their existing stream protocol.
- Compact headers, gesture-safe composer insets, darkened surfaces, continuous edge fades, opaque white roleplay text, rounded press feedback, tighter cards, matching placeholders, and fixed Top Picks description space retain the original design.
- Search suggestions rank actual recent searches, with public character activity filling cold starts.
- Cached conversation observation checks the signed-in owner. Scene detection and regeneration observation read bounded message windows.

## Characters, personas and groups

- Character drafts persist. Owners can reopen the editor, upload a usable portrait, or use AI to draft appearance, life, psychology and dialogue.
- Four 512-class portrait previews precede the selected full-resolution image. Ultra uses Nano Banana Pro for the full-resolution portrait.
- Reference-based upper-body emotion portraits use persistent provider queue jobs. Their status can advance after leaving the creator; the chat overlays the current expression subtly behind the transcript.
- Psychology includes editable emotion and personality sliders, a radar, cornerstone, beliefs, desires, significant events, life and relationships. The current chat state evolves using grounded events and emotional momentum.
- Standard memory limits are 4,000 short-term, 8,000 mid-term and 32,000 long-term characters. Ultra limits are 16,000 / 16,000 / 64,000. Fictional scene time stays separate from real notification time. Removed or edited events invalidate derived memory immediately.
- Personal personas support name, pronouns, appearance and backstory. Direct and group chats have individual persona selection. Creator defaults, a personal default and the account identity have explicit precedence; groups use the personal default rather than picking one member's creator default.
- The native Create sheet opens characters, personas, groups and custom voices.
- Groups contain two to six characters. A context router chooses relevant speakers; later participants see preceding replies. Bounded typing, idle and away follow-ups avoid forced round-robin exchanges. Each character has separate continuity and evolving state.

## Activity, voices and Ultra

- Activity supports individual/all dismissal and navigation to chats, characters, creators and groups. Character unread counts aggregate by character and sort the unread rail.
- Character follow-ups adapt to engagement. Varied notification and email titles are generated alongside the message in one request. Follower bursts are grouped, and followed-creator updates and dormant-user recommendations are limited. Notification categories and email have separate switches.
- Android phone notifications use WorkManager. Android power management can delay its periodic work; this is not a real-time FCM delivery service.
- Read-aloud checks media volume before requesting paid audio. Official/community voices are available to everyone; describing or sampling a custom voice requires Ultra. Video samples are reduced to audio.
- Meek Standard uses DeepSeek V4 Flash 0731 through the configured Venice route. Meek Ultra uses DeepSeek V4 Pro 0813. Automatic mode normally stays fast and uses bounded reasoning/Pro allowances when the turn warrants it. Provider latency routing is used; no dedicated provider priority capacity is claimed.
- Ultra also unlocks chat fonts, advanced character detail, expanded memory, stronger portrait generation, profile frames/fonts/banners/featured characters/widgets, app backgrounds and launcher presets. Active Ultra creators receive a discovery boost while exact search relevance and visibility rules remain authoritative.
- Uploaded/generated arbitrary app icons create Android-confirmed home-screen shortcuts. Android launcher activity icons themselves use predefined resources.
- USD 13.99 and AUD 19.99 monthly targets, lower regional targets, and a 30% annual discount are implemented. These are initial price targets, not experimentally established revenue-maximizing prices.

## External setup and verification boundaries

Stripe and Google Play checkout stay unavailable until real merchant products and verification credentials are configured. This is intentionally deferred; no purchase is simulated as successful. Email delivery requires a verified Resend sender and API configuration. See [setup.md](setup.md).

Backend checks cover streaming, cancellation, exact transcript mutations, ownership, memory invalidation, persona precedence, group leases, image jobs, entitlements, regional prices and notification limits. Android CI compiles the app, runs unit tests, exercises Compose interactions on an API 35 emulator and records a chat screenshot before publishing the preview APK.

Tests use controlled provider responses and do not send real emails, make purchases or charge paid model/image/voice requests. Real model quality, image identity consistency, device-specific performance and delivery timing require observation on actual devices and configured services. The psychology controls support fictional characterization; they are not a scientifically complete model of a human mind or a guarantee of indistinguishability from people.
