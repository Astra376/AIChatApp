# OpenRouter image comparison — 7 September 2026

All new Meek images now use OpenRouter. The final model choices prioritize portrait quality and stable character identity over the lowest price.

| Use | Selected model | Reason |
| --- | --- | --- |
| Standard realistic portraits | FLUX.2 Pro | Convincing skin, hair, fabric and lighting; good reference refinement. |
| Standard anime / illustrated portraits | Seedream 5.0 Lite | Clean illustrated output and good preservation of the selected preview's style during refinement. |
| Character expressions, both styles | Nano Banana 2 | Clearer emotions and more stable face, hair, clothing and framing than the cheaper editors in these samples. |
| Scene / app backgrounds | Nano Banana 2 | Exact Klein 9B requests returned HTTP 404. No silent substitution with Klein 4B or a Fal endpoint. |
| Realistic preview choices | FLUX.2 Pro, 512 × 512 | Native small output verified; $0.03 minimum still applies. |
| Stylized preview choices | Nano Banana 2, 512 × 512 | Seedream rejected 512px and requires at least 2K. |
| Ultra portrait refinement | Nano Banana Pro, 1024 × 1024 | Existing premium choice preserved; upgrading the exact 512px FLUX reference succeeded. |
| Generated app icons / references with unknown art style | Nano Banana 2 | Keeps a general image editor available without guessing that an uploaded or older portrait is photographic. |

## Actual test evidence

The bounded comparison made **28 distinct model requests: 25 images and 3 explicit API rejections**. OpenRouter reported **$1.5541585 total usage**, approximately **$1.55**, for the successful outputs. This excludes payment fees, taxes and storage. Re-running the comparison reused completed job IDs and saved images; it did not buy the completed cases again.

The fixtures use two fictional adult characters, photographic and anime. Both candidate editors and Nano Banana received the **same source image** for laughter, sadness and anger. Each expression starts from the original portrait, so errors cannot accumulate by editing an earlier expression. Additional cases check preview dimensions, cross-model refinement and two background scenes. Three targeted Nano Banana Pro calls check fine identity details and Ultra refinement.

All 25 output files were decoded and visually reviewed. This is a small practical comparison, not evidence of universal or perfect identity consistency. Even Nano Banana Pro lost a small eyebrow marking in the sadness case. No model is claimed to preserve every detail perfectly.

[Raw results, exact models, image URLs, dimensions, durations and costs](image-model-evaluation.json) · [Successful comparison run](https://github.com/Astra376/Meek/actions/runs/34125110954) · [Original output artifact](https://github.com/Astra376/Meek/actions/runs/34125110954/artifacts/10019807873)

## Measured costs and timings

| Request | Actual output | Reported USD / image | Observed duration |
| --- | --- | --- | --- |
| FLUX.2 Pro, text to image | 512px / 1024px square | $0.030 | 8.7–13.3s |
| FLUX.2 Pro, one reference | 1024px square | $0.045 | 12.8–17.0s |
| Seedream 5.0 Lite | 2048px square | $0.035 | 29.5–47.6s for timed successful edits |
| Nano Banana 2 preview | 512px square | $0.046105 | 8.8s |
| Nano Banana 2, portraits / edits / backgrounds | 1024px square or 768 × 1376 | $0.067856–$0.0686665 | 9.9–27.4s |
| Nano Banana Pro, reference editing | 1024px square | $0.136478–$0.136856 | 17.6–18.8s |

These are observed request costs and wall times, not fixed price or latency guarantees. FLUX editing costs more than a text-only portrait because the reference adds cost. The native 512px FLUX request did not reduce its $0.03 minimum. Nano Banana prices include text and image tokens. The initial Seedream image was recovered after an interrupted completion write, so its provider duration is not reported.

## Visual observations

**FLUX Pro portraits:** realistic textures and coherent facial anatomy. It slightly changed exposure during refinement but preserved the selected identity. Its expression edits moved framing/hair and changed facial proportions; sadness and anger were weak compared with Nano Banana. Use it for portraits, not the emotion set.

**Seedream portraits:** clean linework, controlled shading and a good illustrated appearance. Refining the Nano reference preserved the mature character's identity and drawing style. Its expression edits shifted the crop and produced much less distinct sadness/anger. Laughter introduced sharper teeth. These edits are not the default despite the lower price.

**Nano Banana 2 expressions:** substantially clearer emotional intent while keeping the original hairstyle, pendant or pin, clothing, background and framing. Some fine markings and shading changed, especially the anime sadness image. Good enough to be the selected editor, without claiming perfect consistency.

**Nano Banana Pro:** produced a good full-resolution portrait from the 512px FLUX reference. Its two anime edits did not reliably preserve the tiny marking that Nano Banana 2 missed, so the comparison did not justify doubling the default expression cost. It remains the Ultra refinement option.

**Backgrounds:** Nano Banana produced coherent cafe geometry and a clean illustrated forest. Both have calm areas suitable behind blurred chat content. Klein 9B produced no images because OpenRouter rejected the exact model ID; its visual quality could not be evaluated on this provider.

## Inspect the same-reference expression comparisons

| Case | Reference | Candidate edit | Nano Banana 2 |
| --- | --- | --- | --- |
| Photo · Laughter | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux.jpg) | [FLUX](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux_e0.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_nano_e0.jpg) |
| Photo · Sadness | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux.jpg) | [FLUX](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux_e1.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_nano_e1.jpg) |
| Photo · Anger | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux.jpg) | [FLUX](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux_e2.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_nano_e2.jpg) |
| Anime · Laughter | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream.jpg) | [Seedream](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream_e0.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_nano_e0.jpg) |
| Anime · Sadness | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream.jpg) | [Seedream](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream_e1.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_nano_e1.jpg) |
| Anime · Anger | [Original](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream.jpg) | [Seedream](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream_e2.jpg) | [Nano Banana 2](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_nano_e2.jpg) |

Targeted Pro checks: [anime sadness](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_pro_e1.jpg), [anime anger](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_pro_e2.jpg), [512px portrait upgrade](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_refine_pro.jpg).

Other samples: [FLUX portrait](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_flux.jpg), [Seedream portrait](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_seedream.jpg), [512px anime preview](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_nano_preview.jpg), [FLUX refinement](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_photo_refine_flux.jpg), [Seedream refinement](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_anime_refine_seedream.jpg), [cafe background](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_cafe_nano.jpg), [forest background](https://character-chat-worker.robloxproxy.workers.dev/v1/assets/portraits%2Fimage_evaluation%2Fopenrouter_images_20260907_v1_forest_nano.jpg).

## Implementation and verification

- Image generation, refinement, emotions, app icons and backgrounds all use OpenRouter's dedicated image API. Text-provider restrictions are independent of image routing.
- Original bytes, actual content type, model, style and reported cost are stored in R2. Owned-reference validation remains enforced.
- Native Durable Object alarms keep expression generation alive after leaving the screen. Stable job IDs prevent duplicate paid submissions. A result saved before an interrupted completion write is recovered from R2.
- Explicit model rejection can fall back to Nano Banana. Ambiguous provider timeouts do not silently buy another image.
- The comparison uses fixed synthetic fixtures and an expiring CI credential, removed after each run. The endpoint has no public arbitrary-prompt generation mode.
- Previously purchased Fal image jobs can finish through a read-only compatibility path. Fal remains in use for the existing voice service only.
- Backend checks cover routing, reference ownership, actual MIME validation, timeout behavior, duplicate job claims and interrupted-result recovery. Backend-only pushes skip Android builds; the installed APK needs no update for this migration.

API documentation: [OpenRouter image generation](https://openrouter.ai/docs/guides/overview/multimodal/image-generation), [live image catalog](https://openrouter.ai/api/v1/images/models), [FLUX endpoint pricing](https://openrouter.ai/api/v1/images/models/black-forest-labs/flux.2-pro/endpoints), [Seedream endpoint pricing](https://openrouter.ai/api/v1/images/models/bytedance-seed/seedream-5-0-lite/endpoints).
