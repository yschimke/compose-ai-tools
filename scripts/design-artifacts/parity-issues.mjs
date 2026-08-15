/** Pure producer for the catalog's `parity/issues.json`. */

export const ISSUES_SCHEMA = "compose-preview-issues/v1";
export const LOCATOR_FENCE = "compose-parity-locator/v1";

const REPO = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
const FENCE = /```compose-parity-locator\/v1\s*\n([\s\S]*?)\n```/g;
const AREA = new Set(["spec", "component", "preview", "renderer", "comparison"]);
const PARITY = new Set(["regression", "known-difference", "verification-needed"]);

export function canonicalIssueUrl(value) {
  const match = /^https:\/\/(?:www\.)?github\.com\/([^/]+)\/([^/]+)\/issues\/([1-9][0-9]*)\/?$/i.exec(String(value ?? "").trim());
  if (!match) return null;
  const repository = `${match[1]}/${match[2]}`.toLowerCase();
  if (!REPO.test(repository)) return null;
  return { repository, number: Number(match[3]), url: `https://github.com/${repository}/issues/${Number(match[3])}` };
}

/** Parse exactly one visible locator fence. A malformed or duplicate block is an explicit error. */
export function parseLocator(body) {
  const matches = [...String(body ?? "").matchAll(FENCE)];
  if (matches.length !== 1) return { ok: false, error: matches.length ? "multiple locator blocks" : "missing locator block" };
  const fields = Object.create(null);
  for (const line of matches[0][1].split(/\r?\n/)) {
    const colon = line.indexOf(":");
    if (colon < 1) return { ok: false, error: `malformed locator line: ${line}` };
    const key = line.slice(0, colon).trim();
    const value = line.slice(colon + 1).trim();
    if (Object.hasOwn(fields, key)) return { ok: false, error: `duplicate locator field: ${key}` };
    fields[key] = value;
  }
  const required = ["repository", "system", "component", "preview", "reference", "variant", "overrides", "revision"];
  const missing = required.filter((key) => !Object.hasOwn(fields, key) || fields[key] === "");
  if (missing.length) return { ok: false, error: `missing locator field(s): ${missing.join(", ")}` };
  if (!REPO.test(fields.repository)) return { ok: false, error: "invalid repository" };
  let overrides;
  try { overrides = JSON.parse(fields.overrides); } catch { return { ok: false, error: "invalid overrides JSON" }; }
  if (!overrides || Array.isArray(overrides) || typeof overrides !== "object") return { ok: false, error: "overrides must be an object" };
  const canonicalOverrides = Object.fromEntries(Object.keys(overrides).sort().map((key) => [key, overrides[key]]));
  if (JSON.stringify(canonicalOverrides) !== fields.overrides) return { ok: false, error: "overrides are not canonical JSON" };
  return { ok: true, locator: { repository: fields.repository.toLowerCase(), system: fields.system, component: fields.component, previewId: fields.preview, referenceId: fields.reference, variant: fields.variant, overrides: canonicalOverrides, revision: fields.revision } };
}

function labelValue(labels, prefix, allowed) {
  const values = (labels ?? []).map((label) => typeof label === "string" ? label : label?.name).filter(Boolean);
  return values.map((label) => label.toLowerCase()).find((label) => label.startsWith(prefix) && allowed.has(label.slice(prefix.length)))?.slice(prefix.length) ?? null;
}

/** Build an index and report mangled locator blocks instead of silently losing them. */
export function buildIssueIndex(issues, { generatedAt = new Date().toISOString(), onError = () => {} } = {}) {
  const byIdentity = new Map();
  for (const issue of issues ?? []) {
    const identity = canonicalIssueUrl(issue?.html_url ?? issue?.url);
    if (!identity) { onError(issue, "invalid issue URL"); continue; }
    const parsed = parseLocator(issue?.body);
    if (!parsed.ok) { onError(issue, parsed.error); continue; }
    if (parsed.locator.repository !== identity.repository) { onError(issue, "locator repository does not match issue URL"); continue; }
    const row = {
      repository: identity.repository,
      number: identity.number,
      title: String(issue?.title ?? "").trim(),
      url: identity.url,
      state: issue?.state === "closed" ? "closed" : "open",
      area: labelValue(issue?.labels, "area:", AREA),
      parity: labelValue(issue?.labels, "parity:", PARITY),
      system: parsed.locator.system,
      component: parsed.locator.component,
      previewIds: [parsed.locator.previewId],
      referenceIds: [parsed.locator.referenceId],
    };
    if (!row.title) { onError(issue, "missing title"); continue; }
    byIdentity.set(`${row.repository}/${row.number}`, row);
  }
  return { schema: ISSUES_SCHEMA, generatedAt, issues: [...byIdentity.values()].sort((a, b) => a.repository.localeCompare(b.repository) || a.number - b.number) };
}
