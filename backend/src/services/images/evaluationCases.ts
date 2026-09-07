import { IMAGE_MODELS, type ImageInput } from "../../providers/openrouterImages";
export interface EvaluationCase { id: string; label: string; image: ImageInput; reference?: string; }
const photo = "Editorial photograph of a fictional adult woman, age 29, head and upper torso, three-quarter view. Warm olive skin with natural pores and a small mole below her left eye, hazel-green eyes, slightly asymmetric smile, shoulder-length wavy dark brown hair with one copper streak on her right. Burgundy cable-knit cardigan, small silver crescent pendant. Hands outside the frame. Quiet slate-grey studio background, soft window light from the left, 85mm portrait lens. Natural unretouched skin, believable hair and fabric, restrained color, a calm neutral expression. No text, no watermark, no beauty-filter skin.";
const anime = "Professional hand-drawn anime character key visual of a fictional adult man, age 28, head and upper torso, three-quarter view. Short navy-blue hair with one white forelock over his left brow, amber eyes, small notch in the right eyebrow, small silver ear cuff on the left. Ochre bomber jacket over a black turtleneck, an enamel cloud pin on the left lapel. Hands outside the frame. Quiet slate-grey background, soft lighting from the left. Confident varied ink line weights, controlled cel shading, expressive mature facial design, crisp but natural edges, restrained detail. Calm neutral expression. No text, no watermark, no plastic 3D rendering.";
const cafe = "Atmospheric empty neighborhood cafe at dusk, warm amber pendant lights, worn wooden tables, rain on the large windows, a quiet street outside. Coherent architecture and perspective, believable furniture. Vertical scene behind a phone chat, large calm areas and restrained light, no people, no lettering, no watermark. Photographic lighting. This image will be heavily blurred in the app.";
const forest = "An illustrated moonlit forest clearing, graceful birch trunks, mossy stones, soft distant lantern glow, a narrow footpath. Coherent depth and calm negative space, controlled painted shapes, deep indigo and muted teal palette. Vertical background behind a phone chat, no characters, no text, no watermark. This image will be heavily blurred in the app.";
const expression = (emotion: string) => `Edit this exact reference image. Change only the expression to ${emotion}. Preserve the identical person, face geometry, nose, jaw, eyes, hair shape/color/forelock, identifying marks, age, skin tone, clothing, pendant/pin/ear cuff. Keep the same three-quarter view, crop, pose, lighting, background and photographic or illustrated style. Subtle natural expression, no caricature, no text or new objects.`;
export const legacyImageEvaluationCases: EvaluationCase[] = [
  { id: "photo_flux", label: "Photo · FLUX Pro", image: { model: IMAGE_MODELS.realistic, prompt: photo } },
  { id: "photo_nano", label: "Photo · Nano Banana 2", image: { model: IMAGE_MODELS.nano, prompt: photo } },
  { id: "anime_seedream", label: "Anime · Seedream Lite", image: { model: "bytedance-seed/seedream-5-0-lite", prompt: anime } },
  { id: "anime_nano", label: "Anime · Nano Banana 2", image: { model: IMAGE_MODELS.nano, prompt: anime } },
  { id: "photo_preview_512", label: "FLUX · 512px capability", image: { model: IMAGE_MODELS.realistic, prompt: photo, size: "512x512", preview: true } },
  { id: "anime_preview_512", label: "Seedream · 512px capability", image: { model: "bytedance-seed/seedream-5-0-lite", prompt: anime, size: "512x512", preview: true } },
  { id: "anime_nano_preview", label: "Nano · 512px anime preview", image: { model: IMAGE_MODELS.nano, prompt: anime, preview: true } },
  ...(["joyful laughter", "quiet sadness", "contained anger"] as const).flatMap((emotion, index) => [
    { id: `photo_flux_e${index}`, label: `FLUX · ${emotion}`, reference: "photo_flux", image: { model: IMAGE_MODELS.realistic, prompt: expression(emotion) } },
    { id: `photo_nano_e${index}`, label: `Nano · ${emotion} (FLUX reference)`, reference: "photo_flux", image: { model: IMAGE_MODELS.nano, prompt: expression(emotion) } },
    { id: `anime_seedream_e${index}`, label: `Seedream · ${emotion}`, reference: "anime_seedream", image: { model: "bytedance-seed/seedream-5-0-lite", prompt: expression(emotion) } },
    { id: `anime_nano_e${index}`, label: `Nano · ${emotion} (Seedream reference)`, reference: "anime_seedream", image: { model: IMAGE_MODELS.nano, prompt: expression(emotion) } }
  ]),
  { id: "photo_refine_flux", label: "FLUX · refine Nano reference", reference: "photo_nano", image: { model: IMAGE_MODELS.realistic, prompt: "Enhance this exact portrait at full resolution. Preserve the identical person, face, hair, clothes, pose, crop, expression, lighting and photographic style. Do not redesign anything." } },
  { id: "anime_refine_seedream", label: "Seedream · refine Nano reference", reference: "anime_nano", image: { model: "bytedance-seed/seedream-5-0-lite", prompt: "Enhance this exact portrait at full resolution. Preserve the identical person, face, hair, clothes, pose, crop, expression, lighting and anime art style. Do not redesign anything." } },
  { id: "anime_pro_e1", label: "Nano Pro · quiet sadness (Seedream reference)", reference: "anime_seedream", image: { model: IMAGE_MODELS.premium, prompt: expression("quiet sadness") } },
  { id: "anime_pro_e2", label: "Nano Pro · contained anger (Seedream reference)", reference: "anime_seedream", image: { model: IMAGE_MODELS.premium, prompt: expression("contained anger") } },
  { id: "photo_refine_pro", label: "Nano Pro · upgrade 512px FLUX preview", reference: "photo_preview_512", image: { model: IMAGE_MODELS.premium, prompt: "Enhance this exact portrait at full resolution. Preserve the identical person, face, hair, clothes, pose, crop, expression, lighting and photographic style. Do not redesign anything." } },
  { id: "cafe_klein", label: "Cafe · Klein 9B", image: { model: IMAGE_MODELS.background, prompt: cafe, aspectRatio: "9:16" } },
  { id: "forest_klein", label: "Forest · Klein 9B", image: { model: IMAGE_MODELS.background, prompt: forest, aspectRatio: "9:16" } },
  { id: "cafe_nano", label: "Cafe · Nano Banana 2", image: { model: IMAGE_MODELS.nano, prompt: cafe, aspectRatio: "9:16" } },
  { id: "forest_nano", label: "Forest · Nano Banana 2", image: { model: IMAGE_MODELS.nano, prompt: forest, aspectRatio: "9:16" } }
];

// A separate immutable run keeps previous paid comparisons cached.
const body = "Use the reference solely for the exact character identity and art style. Create a clean upper-body character layer, vertical 2:3, from the complete top of the hair to the hips. Include both full shoulders, both arms and relaxed hands. Keep hair, ears and arms inside the canvas with clear margin at the top and sides. Same face, age, hair, eye color, skin, identifying marks, outfit and jewelry. Extend the outfit naturally below the portrait crop. Face toward the viewer, neutral relaxed expression. High quality finished artwork. The background must be genuinely transparent alpha, including all space around the body and between arms and torso. No scenery, rectangle, backdrop, floor, cast shadow, checkerboard, text, border or watermark. Do not draw a close-up portrait or a full-length standing figure.";
const bodyExpression = (emotion: string) => `Edit this exact transparent upper-body character layer. Change only the facial expression to ${emotion}, clearly readable but natural. Preserve the identical person, face geometry, hair, identifying marks, clothing and jewelry. Preserve the exact canvas dimensions, head and body position, size, pose, arm and hand positions, crop and art style. Full head to hips, with clear margin around hair and arms. Keep every background pixel genuinely transparent, including gaps within the silhouette. No background, floor, cast shadow, checkerboard, text or new objects.`;
export const transparentImageEvaluationCases: EvaluationCase[] = [
  { id: "profile_photo", label: "FLUX realistic profile crop", image: { model: IMAGE_MODELS.realistic, prompt: photo + " IMPORTANT: square profile picture, close-up head and shoulders only, face large, entire hair visible, opaque studio background. No waist-up framing." } },
  { id: "profile_anime", label: "Nano Banana 2 stylized profile crop", image: { model: IMAGE_MODELS.nano, prompt: anime + " IMPORTANT: square profile picture, close-up head and shoulders only, face large, entire hair visible, opaque studio background. No waist-up framing." } },
  ...(["photo", "anime"] as const).flatMap(style => (["river", "gpt"] as const).flatMap(provider => {
    const id = `${style}_${provider}`;
    const model = provider === "river" ? IMAGE_MODELS.transparent : IMAGE_MODELS.transparentAlternative;
    const settings: ImageInput = { model, prompt: body, background: "transparent", aspectRatio: "2:3", ...(provider === "gpt" ? { quality: "high" as const } : {}) };
    return [
      { id, label: `${style} upper body / ${provider}`, reference: `profile_${style}`, image: settings },
      { id: `${id}_joy`, label: `${style} joy / ${provider}`, reference: id, image: { ...settings, prompt: bodyExpression("warm joyful laughter") } },
      { id: `${id}_sad`, label: `${style} sadness / ${provider}`, reference: id, image: { ...settings, prompt: bodyExpression("quiet sadness") } }
    ];
  }))
];
export const imageEvaluationCasesForRun = (run: string) => run.startsWith("transparent_body_") ? transparentImageEvaluationCases : legacyImageEvaluationCases;
