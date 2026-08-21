/** Pure producer for the catalog's `parity/issues.json`. */

export const ISSUES_SCHEMA = "compose-preview-issues/v1";
export const LOCATOR_FENCE = "compose-parity-locator/v1";

const REPO = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/;
/**
 * Both delimiters, anchored to a line and tolerating the one to three leading spaces CommonMark
 * allows — a block indented inside a list item renders as an ordinary fence on GitHub, so a
 * column-zero-only pattern reads a perfectly visible locator as absent. Content lines keep their
 * indentation; the field parser already trims each key and value.
 */
const FENCE = /^ {0,3}```compose-parity-locator\/v1\s*\n([\s\S]*?)\n {0,3}```/gm;
/**
 * Just the opening line of a fence, so a locator that FAILS to close can be told apart from one
 * that was never there. [FENCE] needs both delimiters, so a body whose closing ``` was deleted
 * matches zero times and looks identical to an ordinary issue — which would send a real,
 * damaged parity report down the silent-skip path in [buildIssueIndex]. Anchored to a line so a
 * body merely *mentioning* the fence name in prose is not mistaken for one.
 */
const FENCE_OPEN = /^ {0,3}```compose-parity-locator\/v1[^\S\n]*$/gm;
const AREA = new Set(["spec", "component", "preview", "renderer", "comparison"]);
const PARITY = new Set(["regression", "known-difference", "verification-needed"]);

/**
 * Every key the writer always emits. `revision` is deliberately absent: `ServeIssueReport.locator`
 * fills it from the session's delivery provenance, and a session that has none — a developer's
 * local `compose-preview serve` over a bundle directory, or a live daemon — omits the line
 * entirely. Requiring it here rejected the whole locator, so an issue filed from exactly the
 * session a developer reports from never reached the index. The index carries no revision column
 * either, so requiring it bought nothing even when it was there.
 */
const REQUIRED_FIELDS = ["repository", "system", "component", "preview", "reference", "variant", "overrides"];

/**
 * The subset whose value may not be blank. `variant` is the exception and the reason this list is
 * separate from [REQUIRED_FIELDS]: `ServeIssueReport.variantFor` returns "" for a preview id that
 * carries no `__` axes, and "no axes" is a fact about the preview, not a mangled body.
 */
const NON_EMPTY_FIELDS = ["repository", "system", "component", "preview", "reference", "overrides"];

/**
 * Order override keys the way the Kotlin writer does — by Unicode **code point**.
 *
 * JavaScript's default `Array.prototype.sort` compares UTF-16 **code units**, which puts every
 * astral-plane key (surrogates `D800`–`DFFF`) below `E000`–`FFFF` instead of above it. So a
 * canonical block written by `ServeIssueReport.canonicalOverrides` and re-serialised here came back
 * in a different order and was refused as "not canonical" — a cross-engine disagreement produced by
 * the validator rather than by the body. The existing round-trip test did not catch it because its
 * one astral key sorts identically under both orders.
 */
function compareCodePoints(a, b) {
  const left = [...a];
  const right = [...b];
  for (let i = 0; i < Math.min(left.length, right.length); i++) {
    const delta = left[i].codePointAt(0) - right[i].codePointAt(0);
    if (delta !== 0) return delta;
  }
  return left.length - right.length;
}

export function canonicalIssueUrl(value) {
  const match = /^https:\/\/(?:www\.)?github\.com\/([^/]+)\/([^/]+)\/issues\/([1-9][0-9]*)\/?$/i.exec(String(value ?? "").trim());
  if (!match) return null;
  const repository = `${match[1]}/${match[2]}`.toLowerCase();
  if (!REPO.test(repository)) return null;
  return { repository, number: Number(match[3]), url: `https://github.com/${repository}/issues/${Number(match[3])}` };
}

/**
 * The one error that means "this is not a parity report" rather than "this parity report is
 * broken". Every repository is mostly ordinary issues — a dependency dashboard, a docs nit — and
 * none of them carry a locator. Conflating the two makes the producer fail on a healthy repo.
 */
export const NO_LOCATOR = "missing locator block";

/** Parse exactly one visible locator fence. A malformed or duplicate block is an explicit error. */
export function parseLocator(body) {
  const text = String(body ?? "");
  const matches = [...text.matchAll(FENCE)];
  // Count openers too: one that never closed is invisible to [FENCE], and a body carrying a good
  // block plus a dangling opener is ambiguous about which frame it describes.
  const openers = [...text.matchAll(FENCE_OPEN)].length;
  if (matches.length > 1 || openers > 1) return { ok: false, error: "multiple locator blocks" };
  if (matches.length === 0) return { ok: false, error: openers ? "unterminated locator block" : NO_LOCATOR };
  const fields = Object.create(null);
  for (const line of matches[0][1].split(/\r?\n/)) {
    const colon = line.indexOf(":");
    if (colon < 1) return { ok: false, error: `malformed locator line: ${line}` };
    const key = line.slice(0, colon).trim();
    const value = line.slice(colon + 1).trim();
    if (Object.hasOwn(fields, key)) return { ok: false, error: `duplicate locator field: ${key}` };
    fields[key] = value;
  }
  // Split three ways rather than one, because the writer can legitimately emit an empty `variant`
  // and no `revision` at all, and treating either as "missing" drops the whole issue.
  const missing = REQUIRED_FIELDS.filter((key) => !Object.hasOwn(fields, key));
  if (missing.length) return { ok: false, error: `missing locator field(s): ${missing.join(", ")}` };
  const blank = NON_EMPTY_FIELDS.filter((key) => fields[key] === "");
  if (blank.length) return { ok: false, error: `empty locator field(s): ${blank.join(", ")}` };
  if (Object.hasOwn(fields, "revision") && fields.revision === "") return { ok: false, error: "empty locator field(s): revision" };
  if (!REPO.test(fields.repository)) return { ok: false, error: "invalid repository" };
  let overrides;
  try { overrides = JSON.parse(fields.overrides); } catch { return { ok: false, error: "invalid overrides JSON" }; }
  if (!overrides || Array.isArray(overrides) || typeof overrides !== "object") return { ok: false, error: "overrides must be an object" };
  const canonicalOverrides = Object.fromEntries(Object.keys(overrides).sort(compareCodePoints).map((key) => [key, overrides[key]]));
  if (JSON.stringify(canonicalOverrides) !== fields.overrides) return { ok: false, error: "overrides are not canonical JSON" };
  return { ok: true, locator: { repository: fields.repository.toLowerCase(), system: fields.system, component: fields.component, previewId: fields.preview, referenceId: fields.reference, variant: fields.variant, overrides: canonicalOverrides, revision: Object.hasOwn(fields, "revision") ? fields.revision : null } };
}

function labelValue(labels, prefix, allowed) {
  const values = (labels ?? []).map((label) => typeof label === "string" ? label : label?.name).filter(Boolean);
  return values.map((label) => label.toLowerCase()).find((label) => label.startsWith(prefix) && allowed.has(label.slice(prefix.length)))?.slice(prefix.length) ?? null;
}

/**
 * Build an index and report mangled locator blocks instead of silently losing them.
 *
 * An issue with **no** locator block at all goes to `onSkip`, not `onError`: it is an ordinary
 * issue, and a repository is mostly those. `onSkip` is told whether the issue carries an `area:` or
 * `parity:` label, because an issue classified as parity work with no locator is a report filed
 * without its identity — worth saying out loud, unlike the dependency dashboard.
 */
export function buildIssueIndex(issues, { generatedAt = new Date().toISOString(), onError = () => {}, onSkip = () => {} } = {}) {
  const byIdentity = new Map();
  for (const issue of issues ?? []) {
    const identity = canonicalIssueUrl(issue?.html_url ?? issue?.url);
    if (!identity) { onError(issue, "invalid issue URL"); continue; }
    const parsed = parseLocator(issue?.body);
    if (!parsed.ok && parsed.error === NO_LOCATOR) {
      const labelled = Boolean(labelValue(issue?.labels, "area:", AREA) ?? labelValue(issue?.labels, "parity:", PARITY));
      onSkip(issue, { labelled });
      continue;
    }
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
