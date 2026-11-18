// Rules s.4: company/commercial product names, including the sponsor's marks.
// Generic development tools (npm, pytest, python, typescript, etc.) are allowed.
export const BRAND_DENYLIST = [
  "redis", "milvus", "qdrant", "pinecone", "weaviate", "chroma", "github",
  "gitlab", "slack", "aws", "s3", "azure", "gcp", "google", "vercel", "netlify",
  "openai", "gpt", "claude", "anthropic", "gemini", "docker", "kubernetes",
  "stripe", "datadog", "sentry", "notion", "jira", "supabase", "firebase",
  "cloudflare", "heroku", "elasticsearch", "kafka", "snowflake", "databricks",
  "huggingface", "llama", "mistral", "meta", "oculus", "quest", "horizon",
  "pgvector", "lancedb", "excel", "firefox", "chromium", "webkit",
  // Repo-host CLI/pages and hosting/entry portals (#202). "gh" is whole-word only.
  "gh", "gh-pages", "gh pages", "devpost", "ngrok", "fly.io", "itch.io",
] as const;

// Longest first so "gh-pages" reports as one hit, not "gh"; escape "." in domains.
const BRAND_RE = [...BRAND_DENYLIST].sort((a, b) => b.length - a.length)
  .map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|");

export function brandMatches(text: string): string[] {
  return [...text.matchAll(new RegExp(`\\b(?:${BRAND_RE})\\b`, "gi"))].map((m) => m[0]);
}

// Scan every string value, including options, plans and future text fields.
// Keys and file names are identifiers, not displayed copy.
export function textValues(value: unknown): string[] {
  if (typeof value === "string") return [value];
  if (Array.isArray(value)) return value.flatMap(textValues);
  if (value && typeof value === "object") return Object.values(value).flatMap(textValues);
  return [];
}
