# Cheaper transparent upper-body artwork

Tested 7 September 2026 using OpenRouter's native Images API, existing production profile references, and the same neutral/expression prompts used by the app. Production selects **GPT Image 1 Mini, medium quality** for realistic and illustrated upper-body artwork. Profile portraits remain FLUX.2 Pro and Nano Banana 2 respectively. Scene backgrounds remain Nano Banana 2.

| Tier | Measured neutral | Measured expression | Estimated six-image set | Completed request time |
|---|---:|---:|---:|---|
| Previous GPT Image 1 high | $0.25247 | about $0.25353 | about $1.52 | 53–59 seconds |
| GPT Image 1 medium | $0.066230 | $0.067285 | $0.402655 | 23–29 seconds |
| GPT Image 1 Mini high, anime only | $0.052820 | $0.053986 | $0.322750 | 47–48 seconds |
| **GPT Image 1 Mini medium** | **$0.015572** | **$0.016738** | **$0.099262** | **22–27 seconds** |

USD includes the input and output cost returned by OpenRouter. Six-image estimates use one measured neutral plus five times the measured sadness edit; this bounded comparison did not purchase every emotion. Mini medium is approximately **93.5% cheaper** than the prior high-quality route. Actual cost varies with inputs, pricing, and failed provider work.

## Visual and alpha review

All four selected Mini medium outputs completed: realistic/anime neutral bodies and reference-guided sadness edits. They are 1024×1536 RGBA PNGs with fully transparent corner pixels, 40.58–46.09% nearly clear pixels, and 53.62–58.74% opaque pixels. The existing signature/CRC/dimension/alpha validation still runs before publishing any artwork.

The realistic pair retains the recognizable face, hair, burgundy cardigan and necklace, with a visible sad expression. The illustrated pair retains the white forelock, dark hair, mustard jacket and cloud badge. Medium is suitable for the darkened layer behind chat text, but it is not pixel-identical: the anime edit adds texture and changes face proportions slightly; jewelry and garment detail also vary. The more expensive tested tiers also showed detail/shape drift. The Mini high anime edit additionally has nonzero alpha in its corners and offers no compelling value improvement here.

The fixed 12-case manifest returned 10 completed images, one local `IMAGE_ALPHA_UNSUPPORTED` rejection at zero milliseconds, and one dependent skip. The rejected case was realistic Mini high; it cannot be counted as a quality result. Other Mini cases, including both selected styles, completed successfully. No paid retry was made for that rejected pair. Successful calls reported a total of $0.438456. Original results and pixel measurements are in [the evaluation JSON](affordable-character-art-evaluation.json); original image files are in the workflow artifact.

## Production change

Model and quality are independently configurable per style. Medium is the default; requesting PNG transparency no longer forces the high-price tier. Missing model configuration also defaults to Mini. Existing finished artwork stays cached; changing this configuration does not regenerate or rebill existing sets. Every expression still references its neutral body, with durable attempt IDs and no automatic paid fallback/retry loop. No new removal service, GPU deployment, or Android update is required.

Nano Banana 2 plus background removal was considered. At the measured Nano price of about $0.068 per image, generation alone exceeds the selected native-alpha route before removal compute or API charges. A separate removal stage therefore adds cost and another failure point for this use case.

## Evidence

- [Live comparison and original images](https://github.com/Astra376/Meek/actions/runs/34131054104)
- [OpenRouter Mini capabilities](https://openrouter.ai/openai/gpt-image-1-mini)
- [OpenAI Mini model pricing](https://developers.openai.com/api/docs/models/gpt-image-1-mini)
- [OpenRouter native image capability catalog](https://openrouter.ai/api/v1/images/models)
- [Prior high-quality comparison](transparent-character-art-evaluation.md)
