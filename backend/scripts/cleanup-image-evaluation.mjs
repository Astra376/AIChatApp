// Remove the short-lived comparison credential after a fresh deployment.
// Values are never fetched or printed. Running without the secret is a no-op.
const token = process.env.CLOUDFLARE_API_TOKEN;
const account = process.env.CLOUDFLARE_ACCOUNT_ID;
if (token && account) {
  const endpoint = `https://api.cloudflare.com/client/v4/accounts/${account}/workers/scripts/character-chat-worker/secrets`;
  async function call(url, method = "GET") {
    const response = await fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20_000) });
    const result = await response.json();
    if (!response.ok || !result.success) throw new Error(`Could not remove comparison credential: Cloudflare ${result.errors?.[0]?.code || response.status}`);
    return result.result;
  }
  const secrets = await call(endpoint);
  if (secrets.some(secret => secret.name === "IMAGE_EVALUATION_TOKEN")) await call(`${endpoint}/IMAGE_EVALUATION_TOKEN`, "DELETE");
  console.log("Image comparison access is closed.");
}
