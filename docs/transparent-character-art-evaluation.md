# Separate profile portraits and transparent character artwork

Tested 7 September 2026 through the production Worker's OpenRouter Images API. No Fal image submissions and no built-in image-generation substitution.

| Role | Selected model | Output |
|---|---|---|
| Realistic profile picture | FLUX.2 Pro | Square head-and-shoulders portrait, opaque background; native 512px previews |
| Anime / stylized profile picture | Nano Banana 2 | Square head-and-shoulders portrait, opaque background; native 512px previews |
| Neutral character layer and expressions | GPT Image 1, high quality | 1024 × 1536 RGBA PNG, full head and upper body, true alpha background |
| Scene background | Nano Banana 2 | Independent vertical scene, as selected by the earlier comparison |

All six GPT Image 1 body/edit requests completed in 53.4–59.4 seconds. Both styles were tested with a neutral upper-body reference, joyful expression, and sad expression. Realistic expressions retained the face, cardigan, necklace and body placement; stylized expressions retained the forelock, clothing and body placement. Small facial/style differences remain possible: this is reference-guided generation, not a claim of pixel-identical identity. The neutral body is the reference for every expression to limit accumulated drift.

Riverflow Pro produced genuine transparent anime artwork, but took 110–115 seconds for successful calls and exceeded the 150-second limit on the realistic body and sad anime expression. It is not selected for production. The two dependent realistic expression tests were skipped without a provider call.

The profile tests produced correctly cropped 1024px opaque images: FLUX had detailed photographic skin/hair; Nano Banana 2 produced the requested illustrated style. Seedream is no longer selected for profiles. Existing avatars remain the identity references and are not silently replaced.

## Alpha and compositing checks

Each of the six selected GPT PNGs has an actual alpha channel, with 33.6–48.8% nearly fully transparent pixels and 50.8–65.4% opaque pixels. These are not solid PNGs or painted checkerboards. RGB color underneath zero-alpha pixels is irrelevant to compositing; the Android test renders the actual RGBA image through Coil over two colored scene regions in both light and dark themes.

Production validates PNG signature, dimensions, CRC and decoded alpha before storing artwork. An opaque/empty/corrupt response fails without being published. Stored images carry verified alpha metadata and use the separate `character-art` namespace. Legacy opaque expression portraits are excluded. Native Compose `ContentScale.Fit` retains the complete image instead of cropping the head/body. Text opacity is unchanged.

## Cost and reliability

The fixed comparison made 12 submitted requests: 10 completed, two timed out; two dependent cases were skipped. Reported successful-call cost was **$1.9829475**. Timeout billing was not returned and is not included. See [original results and alpha measurements](transparent-character-art-evaluation.json).

GPT Image 1 cost $0.25247 for each neutral body and about $0.25353 per expression, approximately **$1.52 for a six-image character set**, generated once rather than on each message. The more expensive native-alpha model is restricted to body artwork; inexpensive profile previews remain separate. Failed expressions can be retried without repurchasing completed expressions.

Every body attempt receives a durable ID in the database before submission. Concurrent pollers and lost acknowledgements reuse that ID. Expressions wait for the neutral image; old-avatar completions cannot overwrite new-avatar artwork. A failure does not block chat, does not publish a background rectangle, and does not trigger a paid retry loop.

The live comparison itself completed, but its temporary-secret cleanup hit Cloudflare error 10215 after a Worker version change. The token expires automatically. The following deployment explicitly removes the comparison credential after deploying the current Worker version; no API key values are read or logged.

## Sources

- [OpenRouter Images API](https://openrouter.ai/docs/api/api-reference/images/generate-an-image)
- [OpenRouter image capability catalog](https://openrouter.ai/api/v1/images/models)
- [GPT Image 1 capabilities](https://openrouter.ai/openai/gpt-image-1)
- [Riverflow Pro capabilities](https://openrouter.ai/sourceful/riverflow-v2.5-pro)
- [Live comparison workflow](https://github.com/Astra376/Meek/actions/runs/34127658966)
