import type { Env, RequestContext } from "../../env";

const schemaReady = new WeakMap<object, Promise<void>>();
function ensureSearchSchema(env: Env): Promise<void> {
  let ready = schemaReady.get(env.DB);
  if (!ready) {
    ready = env.DB.prepare(`CREATE TABLE IF NOT EXISTS search_interest (
      query TEXT NOT NULL, user_id TEXT NOT NULL, day INTEGER NOT NULL,
      PRIMARY KEY(query, user_id, day)
    )`).run().then(() => undefined).catch(error => { schemaReady.delete(env.DB); throw error; });
    schemaReady.set(env.DB, ready);
  }
  return ready;
}

export function normalizeSearchQuery(query: string): string {
  return query.trim().replace(/\s+/g, " ").toLocaleLowerCase().slice(0, 120);
}

export async function recordSearch(context: RequestContext, query: string): Promise<void> {
  const normalized = normalizeSearchQuery(query);
  if (normalized.length < 2) return;
  await ensureSearchSchema(context.env);
  const day = Math.floor(Date.now() / 86_400_000);
  await context.env.DB.batch([
    context.env.DB.prepare("INSERT OR IGNORE INTO search_interest(query,user_id,day) VALUES(?,?,?)")
      .bind(normalized, context.user!.userId, day),
    context.env.DB.prepare("DELETE FROM search_interest WHERE day < ?").bind(day - 7)
  ]);
}

export async function getTrendingSearches(context: RequestContext) {
  await ensureSearchSchema(context.env);
  const day = Math.floor(Date.now() / 86_400_000);
  const rows = await context.env.DB.prepare(`SELECT query, COUNT(*) AS popularity
    FROM search_interest WHERE day >= ? GROUP BY query
    ORDER BY popularity DESC, MAX(day) DESC, query ASC LIMIT 10`).bind(day - 7)
    .all<{ query: string }>();
  const queries = (rows.results ?? []).map(row => row.query);
  // Cold starts use real public character activity, never invented search counts.
  if (queries.length < 10) {
    const popular = await context.env.DB.prepare(`SELECT name FROM characters WHERE visibility = 'public'
      ORDER BY public_chat_count DESC, last_active_at DESC LIMIT 20`).all<{ name: string }>();
    const seen = new Set(queries.map(normalizeSearchQuery));
    for (const row of popular.results ?? []) {
      const key = normalizeSearchQuery(row.name);
      if (!seen.has(key)) { queries.push(row.name); seen.add(key); }
      if (queries.length >= 10) break;
    }
  }
  return { queries };
}
