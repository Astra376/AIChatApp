import { json } from "../lib/response";
import { parseJson } from "../lib/validation";
import { deletePersona, getConversationPersona, getGroupPersona, listPersonas, savePersona, selectConversationPersona, selectGroupPersona, setDefaultPersona } from "../services/personas";
import type { RouteDefinition } from "./types";

export const personaRoutes: RouteDefinition[] = [
  {method:"GET",path:"/v1/personas",auth:true,handler:async c=>json(await listPersonas(c))},
  {method:"POST",path:"/v1/personas",auth:true,handler:async c=>json(await savePersona(c,await parseJson(c.request)),{status:201})},
  {method:"PATCH",path:"/v1/personas/default",auth:true,handler:async c=>json(await setDefaultPersona(c,(await parseJson<{personaId:unknown}>(c.request)).personaId))},
  {method:"PATCH",path:"/v1/personas/:personaId",auth:true,handler:async c=>json(await savePersona(c,await parseJson(c.request),c.params.personaId))},
  {method:"DELETE",path:"/v1/personas/:personaId",auth:true,handler:async c=>{await deletePersona(c,c.params.personaId);return json({ok:true});}},
  {method:"GET",path:"/v1/conversations/:conversationId/persona",auth:true,handler:async c=>json(await getConversationPersona(c,c.params.conversationId))},
  {method:"PATCH",path:"/v1/conversations/:conversationId/persona",auth:true,handler:async c=>json(await selectConversationPersona(c,c.params.conversationId,await parseJson(c.request)))},
  {method:"GET",path:"/v1/groups/:groupId/persona",auth:true,handler:async c=>json(await getGroupPersona(c,c.params.groupId))},
  {method:"PATCH",path:"/v1/groups/:groupId/persona",auth:true,handler:async c=>json(await selectGroupPersona(c,c.params.groupId,await parseJson(c.request)))}
];
