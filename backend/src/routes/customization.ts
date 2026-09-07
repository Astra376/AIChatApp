import { json } from "../lib/response";
import { parseJson, requireString } from "../lib/validation";
import { assert } from "../lib/errors";
import { getAppearance, saveAppearance, getShowcase, uploadAppearance, generateAppearance, getAppearanceAsset } from "../services/customization";
import type { RouteDefinition } from "./types";
export const customizationRoutes: RouteDefinition[] = [
  {method:"GET",path:"/v1/me/appearance",auth:true,handler:async c=>json(await getAppearance(c))},
  {method:"PATCH",path:"/v1/me/appearance",auth:true,handler:async c=>json(await saveAppearance(c,await parseJson(c.request)))},
  {method:"GET",path:"/v1/profiles/:userId/showcase",auth:true,handler:async c=>json(await getShowcase(c,c.params.userId))},
  {method:"POST",path:"/v1/me/appearance/assets",auth:true,handler:async c=>{
    assert(Number(c.request.headers.get("Content-Length")??0)<=10_100_000,413,"INVALID_IMAGE","Choose an image under 10 MB.");
    const form=await c.request.formData(), file=form.get("image");
    assert(file instanceof File,400,"INVALID_IMAGE","Choose an image.");
    return json(await uploadAppearance(c,requireString(form.get("kind"),"kind",20),file));
  }},
  {method:"POST",path:"/v1/me/appearance/generate",auth:true,handler:async c=>{
    const b=await parseJson<{kind?:string;prompt?:string;requestKey?:string}>(c.request);
    return json(await generateAppearance(c,{kind:requireString(b.kind,"kind",20),prompt:requireString(b.prompt,"prompt",1200),requestKey:requireString(b.requestKey,"requestKey",100)}));
  }},
  {method:"GET",path:"/v1/appearance-assets/:id",handler:getAppearanceAsset}
];
