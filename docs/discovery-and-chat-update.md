# Discovery and chat update

This rebuild restores the full requested UI update and the surviving backend source. The backend source was recovered from its source map; the Android changes were rebuilt against `fc3129f`.

## App behavior

- Psychology uses a brain icon. Scene displays the current setting and fictional time in a card, followed by the story timeline, newest first.
- Home shows unread characters first, then recent chats, deduplicated by character. Its first four recommendations appear above Top Picks and are excluded from the lower recommendations. Continue portraits are 94 dp, with rounded images and unclipped text. Section gaps are adjusted.
- Discover displays smaller horizontal character rows selected and ranked for the user. Header chevrons open three-column category pages; a dropdown switches between that user's same saved categories.
- Top and bottom navigation slide away on downward scrolling and return on upward scrolling. Loading indicators and skeletons wait 220 ms before appearing.
- Profile backgrounds fill the screen; banners sit behind the profile identity and follower statistics. Empty notifications include a large bell treatment.
- Four bundled white anime silhouettes replace missing portraits. Asset choice is deterministic so it does not change between app launches.
- Chat bubbles are slightly more transparent. Background scenes are dimmed and software-blurred, including Android 9–11. The transparent character layer itself renders at full opacity above the dimming layers.
- Reply controls use plain arrows without shadows. The first reply exposes a regenerate icon, without a variant counter until an alternative exists. Continue removes controls on the earlier reply immediately. More space separates the newest message from the composer fade; Send/Stop has the same circular background color as the text field.

## Chat reliability

Compose's native `HorizontalPager` owns gesture recognition and settling. Variant selection saves do not rebuild the pager. A generation keeps the same page index through streaming, the Room commit, and final reveal. The repository publishes the regeneration ID before Room emits the appended variant, and the active generation's base variants remain fixed until reveal completes. Pager animations are owned by its composition scope, so network completion cannot cancel a swipe halfway.

Each page measures its natural height. Only the settled page's height changes the transcript, animated over 160 ms. Incoming long replies cannot move the transcript during a swipe. Hidden pages are removed from accessibility semantics.

Typing uses one frame clock at 60 Unicode code points per second. Network completion does not reveal the rest in one jump. Final formatting changes preserve the reveal position. Already-received text displays immediately when reopening a chat. Manual scroll intent is handled synchronously through native nested scrolling before auto-follow can react to the next chunk.

## Recommendation design and model choice

The old feed primarily ranked catalog activity, popularity and creator entitlements. The replacement combines weighted user turns, repeat days and sessions, likes, recency, public-character text similarity, semantic similarity, collaborative overlap, and bounded catalog-quality priors. Diminishing returns prevent one long chat dominating forever. Diversity reranking reduces repeated creators and nearly identical characters.

The architecture separates candidate retrieval, ranking and presentation, consistent with the retrieval/ranking split described in [Google's recommendation research](https://research.google/pubs/deep-neural-networks-for-youtube-recommendations/). It is a practical hybrid for this catalog; no claim of measured state-of-the-art engagement performance is made without offline or live engagement evaluation.

Cached `openai/text-embedding-3-small` vectors at 256 dimensions add semantic matching. OpenAI documents recommendation and clustering uses and dimension reduction in its [embedding guide](https://developers.openai.com/api/docs/guides/embeddings). [OpenRouter lists the model at $0.02 per million input tokens](https://openrouter.ai/openai/text-embedding-3-small). Only public catalog metadata is sent for embedding. Behavioral weighting stays on the backend. TF-IDF supplies an immediate fallback while embeddings warm. A generative model on every app open would add latency, cost and category churn; a custom trained ranker requires reliable labeled engagement data that this repository does not currently contain.

Categories combine genres and personality facets with useful terms from the user's strongest public-character interests. Versioned D1 snapshots keep categories, order and pagination stable across opening and closing the app. Snapshots refresh after six hours or stronger activity changes after a 30-minute minimum; useful shelves retain their positions. Android caches previews by account. Access is checked again when serving cards, so a character made private is omitted from an old snapshot.

## Scene generation and cost

The authoritative endpoint receives a conversation ID and reads its selected replies and valid scene memory. It distinguishes an actual setting transition from dialogue, memories, plans or emotion changes. A new setting requires an exact supporting quote at a real transcript position. Unchanged or unsupported scene updates preserve the current setting.

Scene identity uses character, stable place, lighting and weather; dialogue and wording do not create new images. R2 reuses known environments, including returns to earlier locations. D1 leases prevent overlapping requests from buying duplicate images. The client serializes requests and debounces scene checks after a reply, edit, rewind or variant selection. Failed requests retain the previous scene.

Background generation switches from Gemini Flash Image to [FLUX.2 Klein 4B](https://openrouter.ai/black-forest-labs/flux.2-klein-4b), listed at $0.014 for the first generated megapixel and $0.001 for each subsequent megapixel. Prompts always specify hand-painted 2D anime scenery with no photography, people, text or 3D rendering. Scene tracking and image generation remain probabilistic; live scene/style quality must be evaluated with actual roleplay output, not inferred from tests.

## Verification

The backend suite includes ranking signals, semantic matching, stable user/version pagination, current visibility, evidence validation, scene reuse, overlapping requests and ownership. Android tests cover manual-scroll follow, pending variant saves, long-page settling, streamed height growth, constant reveal after completion, rapid chunks, reopening, Continue controls, first regeneration and draft-to-commit centering. The GitHub workflow runs the complete chat interaction class on an Android emulator before publishing the APK and deploying the backend.
