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
/**
 * The `parity:` vocabulary, which is also the answer set of the report form's "Where does it
 * belong?" control (`ServeWeb.reportClassificationHtml`). `upstream` and `catalog` were added with
 * it: the reporter is looking at both pictures and is the person best placed to say which side a
 * difference lives on, and `verification-needed` — already here — is what they pick when they
 * cannot. A value this set does not know is dropped from the index silently, so this list and
 * `ServeParityIssuesStore.PARITY` have to move together.
 */
const PARITY = new Set(["regression", "known-difference", "verification-needed", "upstream", "catalog"]);

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
 * Reserved in `v1` for the selection batch 03 records, and unwritten until then.
 *
 * Batch 01 called for both keys *before* the writer, the parsers and the shared fixture froze, and
 * that did not happen: element selection would then have had to add two keys to a `v1` that strict
 * parsers reject and permissive ones — these two, which ignore unknown fields — silently discard.
 * A report carrying a selection would have been indexed with the selection dropped and no error
 * anywhere. Reserving them now costs a fixture case; retrofitting them costs a version bump across
 * the writer, this producer and the Kotlin reader.
 *
 * `bounds` names its space rather than being a bare rectangle, per D1: the tag index publishes
 * `render-pixels` and the canonical-plane transform belongs to the comparison, so a rectangle with
 * no space is exactly the ambiguity that makes an element which never moved report as `moved`.
 * `v1` therefore accepts only the space both producers actually emit.
 */
const OPTIONAL_FIELDS = ["element", "bounds"];
const BOUNDS_SPACE = "render-pixels";
/** Code-point order, which is what [canonicalJson] produces and what the writer emits. */
const BOUNDS_KEYS = ["height", "space", "width", "x", "y"];

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

/** Re-serialise an object with its keys in code-point order — the writer's rule, for both maps. */
function canonicalJson(value) {
  return JSON.stringify(Object.fromEntries(Object.keys(value).sort(compareCodePoints).map((key) => [key, value[key]])));
}

/**
 * `element` is a **JSON string**, not a bare value, and that is load-bearing rather than tidy.
 *
 * The block is line-oriented `key: value`, so a bare tag containing a newline does not stay one
 * field: `row\nrevision: injected` parses as element `row` plus a revision nobody wrote, and a tag
 * carrying a fence delimiter can end the block early and take the whole issue out of the index. Tag
 * indexes preserve arbitrary strings, so the writer cannot assume the tag is well-behaved. JSON
 * quoting makes every such value expressible and unambiguous — and, incidentally, is the only way a
 * tag with leading or trailing whitespace survives a format whose readers trim.
 */
function parseElement(raw) {
  let element;
  try { element = JSON.parse(raw); } catch { return { error: "element must be a JSON string" }; }
  if (typeof element !== "string") return { error: "element must be a JSON string" };
  if (element === "") return { error: "empty locator field(s): element" };
  if (JSON.stringify(element) !== raw) return { error: "element is not canonical JSON" };
  return { element };
}

function parseBounds(raw) {
  let bounds;
  try { bounds = JSON.parse(raw); } catch { return { error: "invalid bounds JSON" }; }
  if (!bounds || Array.isArray(bounds) || typeof bounds !== "object") return { error: "bounds must be an object" };
  const keys = Object.keys(bounds).sort(compareCodePoints);
  if (keys.length !== BOUNDS_KEYS.length || keys.some((key, i) => key !== BOUNDS_KEYS[i])) {
    return { error: `bounds must carry exactly ${BOUNDS_KEYS.join(", ")}` };
  }
  if (bounds.space !== BOUNDS_SPACE) return { error: `bounds space must be ${BOUNDS_SPACE}` };
  for (const key of ["x", "y", "width", "height"]) {
    if (!Number.isSafeInteger(bounds[key])) return { error: `bounds ${key} must be an integer` };
  }
  // The **origin may be negative**: a uniquely tagged node can extend above or left of the render
  // root, and both tag-index producers emit signed coordinates for exactly that case
  // (`ServeSemanticsTags` asks only for `right > left` / `bottom > top`, `tag-index.mjs` parses
  // `-?\d+`, and `ServeTagIndex` validates only the extent). Refusing a signed origin here would
  // mean batch 03 could not copy the bounds it was handed. Clipping belongs to the comparison's
  // plane transform, not to this validator.
  if (bounds.width < 1 || bounds.height < 1) return { error: "bounds must have a positive extent" };
  // Same rule the overrides carry: the block is canonical bytes, so a record and its re-serialised
  // form are comparable without parsing. A hand-edited body that reorders the keys is refused
  // rather than quietly accepted into a fingerprint someone later compares by string.
  if (canonicalJson(bounds) !== raw) return { error: "bounds are not canonical JSON" };
  return { bounds };
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

/**
 * Parse **every** visible locator fence in a body.
 *
 * One issue may legitimately name several components: an umbrella report like the Elevated shadow
 * level covers `Button/`, `Card/` and `ToggleButton/Elevated`, and one block can only say one of
 * them. Each block is a complete locator; [buildIssueIndex] emits one row per block, so the issue
 * reaches every component page it is about. What a body may **not** do is contradict itself — the
 * blocks share one repository and one system, and no component may appear twice, since two rows
 * with one identity would collapse against each other in the reader.
 */
export function parseLocators(body) {
  const text = String(body ?? "");
  const matches = [...text.matchAll(FENCE)];
  const openers = [...text.matchAll(FENCE_OPEN)].length;
  // An opener with no closer is invisible to [FENCE]; if the counts disagree, some block in this
  // body failed to close and the body is damaged rather than merely multi-component.
  if (openers > matches.length) return { ok: false, error: "unterminated locator block" };
  if (matches.length === 0) return { ok: false, error: NO_LOCATOR };
  const locators = [];
  for (const [index, match] of matches.entries()) {
    const parsed = parseLocatorBlock(match[1]);
    if (!parsed.ok) return matches.length === 1 ? parsed : { ok: false, error: `locator block ${index + 1}: ${parsed.error}` };
    locators.push(parsed.locator);
  }
  const [first] = locators;
  for (const locator of locators.slice(1)) {
    if (locator.repository !== first.repository) return { ok: false, error: "locator blocks disagree about the repository" };
    if (locator.system !== first.system) return { ok: false, error: "locator blocks disagree about the system" };
  }
  const components = new Set();
  const previews = new Set();
  const references = new Set();
  for (const locator of locators) {
    if (components.has(locator.component)) return { ok: false, error: `duplicate component in locator blocks: ${locator.component}` };
    components.add(locator.component);
    // A served preview belongs to one component, and `issuesForPreview` matches rows by preview id
    // as well as by component — so two blocks claiming one preview put the same issue on that
    // preview's page twice and make its badge say two issues. That is a mistyped body, not a shape
    // the index should carry.
    if (previews.has(locator.previewId)) return { ok: false, error: `duplicate preview in locator blocks: ${locator.previewId}` };
    previews.add(locator.previewId);
    // Same reasoning for the reference: `DesignReference.id` is unique within a served session, and
    // the focused comparison selects rows by it, so two blocks sharing one reference would render
    // the same issue twice on that comparison's page.
    if (references.has(locator.referenceId)) return { ok: false, error: `duplicate reference in locator blocks: ${locator.referenceId}` };
    references.add(locator.referenceId);
  }
  return { ok: true, locators };
}

/**
 * Parse a body carrying exactly one fence. Kept beside [parseLocators] because most callers — and
 * every per-block case in the shared fixture — describe a single locator, and because "this body
 * says two different things" is a distinct answer from "here are its two components".
 */
export function parseLocator(body) {
  const parsed = parseLocators(body);
  if (!parsed.ok) return parsed;
  if (parsed.locators.length > 1) return { ok: false, error: "multiple locator blocks" };
  return { ok: true, locator: parsed.locators[0] };
}

/** Parse the content of one fence — every line between its delimiters. */
function parseLocatorBlock(content) {
  const fields = Object.create(null);
  for (const line of content.split(/\r?\n/)) {
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
  for (const key of OPTIONAL_FIELDS) {
    if (Object.hasOwn(fields, key) && fields[key] === "") return { ok: false, error: `empty locator field(s): ${key}` };
  }
  if (!REPO.test(fields.repository)) return { ok: false, error: "invalid repository" };
  let overrides;
  try { overrides = JSON.parse(fields.overrides); } catch { return { ok: false, error: "invalid overrides JSON" }; }
  if (!overrides || Array.isArray(overrides) || typeof overrides !== "object") return { ok: false, error: "overrides must be an object" };
  const canonicalOverrides = Object.fromEntries(Object.keys(overrides).sort(compareCodePoints).map((key) => [key, overrides[key]]));
  if (canonicalJson(canonicalOverrides) !== fields.overrides) return { ok: false, error: "overrides are not canonical JSON" };
  let element = null;
  if (Object.hasOwn(fields, "element")) {
    const parsed = parseElement(fields.element);
    if (parsed.error) return { ok: false, error: parsed.error };
    element = parsed.element;
  }
  let bounds = null;
  if (Object.hasOwn(fields, "bounds")) {
    const parsed = parseBounds(fields.bounds);
    if (parsed.error) return { ok: false, error: parsed.error };
    bounds = parsed.bounds;
  }
  return { ok: true, locator: { repository: fields.repository.toLowerCase(), system: fields.system, component: fields.component, previewId: fields.preview, referenceId: fields.reference, variant: fields.variant, overrides: canonicalOverrides, element, bounds, revision: Object.hasOwn(fields, "revision") ? fields.revision : null } };
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
    const parsed = parseLocators(issue?.body);
    if (!parsed.ok && parsed.error === NO_LOCATOR) {
      const labelled = Boolean(labelValue(issue?.labels, "area:", AREA) ?? labelValue(issue?.labels, "parity:", PARITY));
      onSkip(issue, { labelled });
      continue;
    }
    if (!parsed.ok) { onError(issue, parsed.error); continue; }
    if (parsed.locators[0].repository !== identity.repository) { onError(issue, "locator repository does not match issue URL"); continue; }
    const title = String(issue?.title ?? "").trim();
    if (!title) { onError(issue, "missing title"); continue; }
    // One row per locator, so an umbrella issue reaches every component it names. The row carries
    // the identity only: a selection recorded in the locator belongs to the acceptance that cites
    // it, not to an index whose whole job is "which issues touch this component".
    for (const locator of parsed.locators) {
      const row = {
        repository: identity.repository,
        number: identity.number,
        title,
        url: identity.url,
        state: issue?.state === "closed" ? "closed" : "open",
        area: labelValue(issue?.labels, "area:", AREA),
        parity: labelValue(issue?.labels, "parity:", PARITY),
        system: locator.system,
        component: locator.component,
        previewIds: [locator.previewId],
        referenceIds: [locator.referenceId],
      };
      byIdentity.set(`${row.repository}/${row.number}#${row.component}`, row);
    }
  }
  return { schema: ISSUES_SCHEMA, generatedAt, issues: [...byIdentity.values()].sort((a, b) => a.repository.localeCompare(b.repository) || a.number - b.number || a.component.localeCompare(b.component)) };
}
