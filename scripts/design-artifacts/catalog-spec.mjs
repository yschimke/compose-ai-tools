// Catalog-spec tooling: discover a module's @Preview functions from its Kotlin
// source, and validate / scaffold a `catalog.spec.json` against them — WITHOUT a
// render. The authoritative check stays the render + completeness gate in
// generate-design-catalog.mjs (a spec `preview` that renders nothing is reported
// there); this is the fast, build-free pre-flight that catches the common failure
// — a `preview` string that doesn't match any @Preview function name — before a
// ~90-minute Design Artifacts job surfaces it as a late "missing" entry.
//
// Pure library (node built-ins only, no @design-parity import) so its unit tests
// run without `npm ci`. The CLI wrappers are validate-catalog-spec.mjs and
// init-catalog-spec.mjs.

import { CAPTURE_MODES, exportsNoSticker } from "./capture-mode.mjs";
import { catalogBreakpoints } from "./catalog-breakpoints.mjs";
import { SELECT_AXES, selectOf } from "./catalog-select.mjs";
import {
  DEFERRED,
  MODE_WILDCARD,
  PRIORITIES,
  modePriority as modePriorityOf,
  specDefersAnything,
  defersEveryPreview,
} from "./catalog-priority.mjs";

// The built-in Compose preview annotation. Any function annotated with it — or
// with a *multipreview* annotation (an annotation class itself meta-annotated
// with @Preview, e.g. @CatalogModes) — is a rendered preview whose function name
// a spec `preview` can reference.
const PREVIEW_ANNOTATION = "Preview";

// Capture annotations that decide whether a preview function renders a static
// `renders/<id>.png` at all. Mirrors `emitStaticCross` in PreviewDiscovery.kt
// (gradle-plugin/preview-discovery): a function whose ONLY capture annotations are
// single-output GIF producers (`@AnimatedPreview`, `@FocusedPreview(gif = true)`,
// `@ScrollingPreview` with only the data-product modes LONG/GIF) suppresses the
// static cross-product, so no PNG is written. `@ScrollingPreview(modes = [LONG])`
// still writes a PNG (the stitched long shot IS the render), so only the GIF cases
// end up PNG-less.
//
// This matters for the spec because the catalog export's `candidatePreviewBundle()`
// keeps only previews carrying `previews/<id>.png` — a GIF-only preview is dropped
// from the candidate join and then reported missing by the completeness gate. See
// bundle-previews.mjs and issue #2865.
const ANIMATED_PREVIEW_ANNOTATION = "AnimatedPreview";
const FOCUSED_PREVIEW_ANNOTATION = "FocusedPreview";
const SCROLLING_PREVIEW_ANNOTATION = "ScrollingPreview";
const ROBO_OPTIONS_ANNOTATION = "RoboComposePreviewOptions";
// `ScrollMode` values that are emitted as data products (a tall stitched PNG /
// scrolling GIF) rather than as ordinary captures.
const PRODUCT_SCROLL_MODES = new Set(["LONG", "GIF"]);

// A leading run of Kotlin annotations, e.g. `@CatalogModes @Preview(name = "x") `.
// Each annotation is `@Name` optionally followed by a `(...)` argument list. The
// arg matcher allows one level of nested parens (`@Preview(widthDp = f(1))`);
// discovery also blanks string-literal *contents* first (see blankStringContents),
// so parens inside a string argument (`@Preview(name = "Now Playing (x)")`) can't
// prematurely end the match.
const ANNOTATION = String.raw`@[\w.]+(?:\s*\((?:[^()]|\([^()]*\))*\))?`;
const LEADING_ANNOTATIONS = String.raw`((?:${ANNOTATION}\s*)+)`;
// Modifiers that may sit between the annotation run and the `fun` / `annotation`
// keyword (`@Preview @Composable private fun X`). `@Composable` is itself an
// annotation so it's absorbed by the run; these are the bare keyword modifiers.
const MODIFIERS = String.raw`(?:(?:public|private|internal|protected|inline|expect|actual|override|suspend|operator|infix|tailrec|external|final|open)\s+)*`;

/** Strip Kotlin line comments and block comments (KDoc included) so a
 *  commented-out `@Preview fun` or prose mentioning `@Preview` doesn't register.
 *  String literals are left intact — annotation/declaration syntax never lives
 *  inside a string, so this is enough for discovery. */
export function stripComments(source) {
  let out = "";
  let i = 0;
  const n = source.length;
  while (i < n) {
    const c = source[i];
    const d = source[i + 1];
    if (c === "/" && d === "/") {
      i += 2;
      while (i < n && source[i] !== "\n") i++;
    } else if (c === "/" && d === "*") {
      i += 2;
      while (i < n && !(source[i] === "*" && source[i + 1] === "/")) i++;
      i += 2;
    } else if (c === '"' && d === '"' && source[i + 2] === '"') {
      // Raw string `"""…"""` — opaque, no escapes. Copy through the closing triple.
      out += '"""';
      i += 3;
      while (i < n && !(source[i] === '"' && source[i + 1] === '"' && source[i + 2] === '"')) {
        out += source[i++];
      }
      out += '"""';
      i += 3;
    } else if (c === '"' || c === "'") {
      // Ordinary string / char literal — opaque, with `\` escapes. A `//` or `/*`
      // inside must NOT be read as a comment.
      const quote = c;
      out += c;
      i++;
      while (i < n && source[i] !== quote) {
        if (source[i] === "\\" && i + 1 < n) {
          out += source[i] + source[i + 1];
          i += 2;
        } else {
          out += source[i++];
        }
      }
      if (i < n) out += source[i++]; // closing quote
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Replace the *contents* of every string / char literal with spaces (quotes
 *  kept), so parens, braces, `@`, and `fun` inside a string can't be mistaken for
 *  code during discovery. Runs after stripComments. `@Preview(name = "a (b)")`
 *  becomes `@Preview(name = "      ")`, so the annotation arg list closes cleanly. */
export function blankStringContents(source) {
  let out = "";
  let i = 0;
  const n = source.length;
  const blank = (s) => s.replace(/[^\n]/g, " ");
  while (i < n) {
    const c = source[i];
    if (c === '"' && source[i + 1] === '"' && source[i + 2] === '"') {
      out += '"""';
      i += 3;
      let inner = "";
      while (i < n && !(source[i] === '"' && source[i + 1] === '"' && source[i + 2] === '"')) {
        inner += source[i++];
      }
      out += blank(inner) + '"""';
      i += 3;
    } else if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      i++;
      let inner = "";
      while (i < n && source[i] !== quote) {
        if (source[i] === "\\" && i + 1 < n) {
          inner += source[i] + source[i + 1];
          i += 2;
        } else {
          inner += source[i++];
        }
      }
      out += blank(inner);
      if (i < n) out += source[i++]; // closing quote
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** The `@Name` / `@Name(args…)` entries of a leading-annotation run, each with its
 *  simple name (`a.b.CatalogModes` → `CatalogModes`) — Kotlin call sites usually
 *  import and use the short name, which is what a multipreview `annotation class`
 *  is declared under — and its raw argument text (`""` when there is no arg list). */
function annotationEntries(run) {
  const entryRe = new RegExp(String.raw`@([\w.]+)(\s*\((?:[^()]|\([^()]*\))*\))?`, "g");
  const entries = [];
  for (const m of run.matchAll(entryRe)) {
    const parts = m[1].split(".");
    entries.push({ name: parts[parts.length - 1], args: m[2] ?? "" });
  }
  return entries;
}

/** The set of `@Name` identifiers named in a leading-annotation run. */
function annotationNames(run) {
  return new Set(annotationEntries(run).map((e) => e.name));
}

/**
 * Whether a function carrying this leading-annotation run renders a static
 * `previews/<id>.png`.
 *
 * Mirrors `emitStaticCross` in PreviewDiscovery.kt: the scroll × time × focus
 * cross-product normally emits at least one PNG capture, but it is suppressed when
 * the function's only capture annotations are single-output producers —
 * `@AnimatedPreview`, `@FocusedPreview(gif = true)`, or a `@ScrollingPreview` whose
 * modes are all data products (LONG/GIF, which land under `data/…` rather than
 * `previews/<id>.png`). Those functions render, but have no static sticker for the
 * catalog export to join on.
 *
 * Source-level detection is sound here because all three annotations are
 * `@Target(FUNCTION)` — they can't hide inside a multipreview annotation class.
 */
/** The comma-separated entries of a Kotlin array literal's inner text. */
function arrayItems(inner) {
  return inner
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

/** The inner text of an annotation argument's array literal — `name = [...]` when
 *  named, else the first positional `[...]`. Returns null when there is none. */
function arrayArg(args, name) {
  const named = args.match(new RegExp(String.raw`\b${name}\s*=\s*\[([^\]]*)\]`));
  if (named) return named[1];
  // A positional array is only unambiguous when no other argument is named.
  if (/\w+\s*=/.test(args)) return null;
  return args.match(/\[([^\]]*)\]/)?.[1] ?? null;
}

/** The `ScrollMode` names a `@ScrollingPreview` argument list selects. Kotlin allows
 *  both the qualified `ScrollMode.GIF` and a directly imported `GIF`, so accept
 *  either — matching only inside the `modes` array keeps sibling arguments
 *  (`frameIntervalMs = DEFAULT_GIF_FRAME_INTERVAL_MS`) from registering as modes. */
function scrollModeList(args) {
  const inner = arrayArg(args, "modes");
  if (inner === null) return [];
  return arrayItems(inner)
    .map((item) => item.match(/(?:ScrollMode\s*\.\s*)?(TOP|END|LONG|GIF)$/)?.[1])
    .filter(Boolean);
}

/** How many capture steps a `@FocusedPreview(gif = true)` yields — mirrors
 *  `readFocusSteps`: the `traverse` directions when non-empty, else the distinct
 *  non-negative `indices` (default `[0]`). `0` when the annotation isn't GIF-mode.
 *  An `indices`/`traverse` value that isn't a literal array reads as the default,
 *  which keeps an unparseable annotation on the PNG-capable (lenient) side. */
function focusGifSteps(args) {
  if (!/\bgif\s*=\s*true\b/.test(args)) return 0;
  const traverse = arrayArg(args, "traverse");
  if (traverse !== null && arrayItems(traverse).length > 0) return arrayItems(traverse).length;
  const indices = arrayArg(args, "indices");
  if (indices === null) return 1; // default `indices = [0]`
  const values = arrayItems(indices)
    .map((item) => Number(item))
    .filter((n) => Number.isInteger(n) && n >= 0);
  return new Set(values).size;
}

export function rendersStaticPng(run) {
  const entries = annotationEntries(run);
  const named = (name) => entries.filter((e) => e.name === name);

  const animated = named(ANIMATED_PREVIEW_ANNOTATION).length > 0;
  const focused = named(FOCUSED_PREVIEW_ANNOTATION);
  // `extractFocusGifSpec` returns null below two steps — a one-frame GIF wouldn't
  // animate — so a singleton `gif = true` (notably the default `indices = [0]`)
  // falls back to the ordinary focus fan-out and DOES render a PNG.
  const focusGif = focused.some((e) => focusGifSteps(e.args) >= 2);
  // `@FocusedPreview(gif = true)` supersedes the per-step focus fan-out (see
  // `effectiveFocuses` in PreviewDiscovery.kt), so focus steps only count when no
  // GIF-mode annotation is present.
  const focusSteps = !focusGif && focused.length > 0;

  const scrollModes = named(SCROLLING_PREVIEW_ANNOTATION).flatMap((e) => {
    const modes = scrollModeList(e.args);
    // No explicit `modes` → the annotation default, `[ScrollMode.END]`.
    return modes.length > 0 ? modes : ["END"];
  });
  const captureScrolls = scrollModes.filter((m) => !PRODUCT_SCROLL_MODES.has(m));
  const productScrolls = scrollModes.filter((m) => PRODUCT_SCROLL_MODES.has(m));

  // `@RoboComposePreviewOptions(manualClockOptions = [...])` fans the function out
  // into one PNG per virtual-time stop. `extractRoboTimings` reads each entry's
  // `advanceTimeMillis`, so an empty (or stop-less) array yields no timings at all
  // and doesn't hold the static cross-product open.
  const timings = named(ROBO_OPTIONS_ANNOTATION).some(
    (e) => /\bmanualClockOptions\s*=/.test(e.args) && /\badvanceTimeMillis\s*=/.test(e.args),
  );

  return (
    captureScrolls.length > 0 ||
    timings ||
    focusSteps ||
    (!animated && !focusGif && productScrolls.length === 0)
  );
}

/**
 * Discover the @Preview (and multipreview) function names declared across a set
 * of Kotlin source texts.
 *
 * @param {string[]} sources  Kotlin file contents.
 * @param {object}   [opts]
 * @param {string[]} [opts.extraAnnotations]  Extra multipreview annotation
 *   *simple names* to treat as preview markers — for annotations declared in
 *   another module (imported), which a source-only scan can't see meta-annotated.
 * @returns {{ previews: string[], annotations: string[], pngLess: string[] }}
 *   `previews`: sorted unique function names. `annotations`: the multipreview
 *   annotation names recognised (built-in `Preview` + discovered + extra).
 *   `pngLess`: the subset of `previews` that render no static `previews/<id>.png`
 *   (GIF-only / data-product-only captures — see [rendersStaticPng]); the catalog
 *   export drops these from the candidate join, so a spec must not reference them.
 */
export function discoverPreviews(sources, opts = {}) {
  const texts = sources.map((s) => blankStringContents(stripComments(s)));

  // Preview markers: the built-in annotation, any caller-supplied extras, plus
  // multipreview annotation classes discovered below.
  const markers = new Set([PREVIEW_ANNOTATION, ...(opts.extraAnnotations ?? [])]);

  // Fixpoint over `annotation class` declarations: an annotation class whose own
  // leading run references a known marker is itself a marker (multipreview).
  // Iterated so a chain (A meta-annotated @Preview; B meta-annotated @A) resolves.
  const annoDeclRe = new RegExp(
    `${LEADING_ANNOTATIONS}${MODIFIERS}annotation\\s+class\\s+(\\w+)`,
    "g",
  );
  const declarations = [];
  for (const text of texts) {
    for (const m of text.matchAll(annoDeclRe)) {
      declarations.push({ run: m[1], name: m[2] });
    }
  }
  let grew = true;
  while (grew) {
    grew = false;
    for (const { run, name } of declarations) {
      if (markers.has(name)) continue;
      const names = annotationNames(run);
      if ([...names].some((a) => markers.has(a))) {
        markers.add(name);
        grew = true;
      }
    }
  }

  // Functions whose leading run references any marker are previews.
  const funRe = new RegExp(`${LEADING_ANNOTATIONS}${MODIFIERS}fun\\s+(\\w+)`, "g");
  const previews = new Set();
  // A function name is PNG-less only when EVERY declaration of it is (an overload
  // or same-named function in another file that does render a sticker keeps the
  // name joinable).
  const pngLess = new Set();
  const staticNames = new Set();
  for (const text of texts) {
    for (const m of text.matchAll(funRe)) {
      const names = annotationNames(m[1]);
      if (![...names].some((a) => markers.has(a))) continue;
      previews.add(m[2]);
      if (rendersStaticPng(m[1])) staticNames.add(m[2]);
      else pngLess.add(m[2]);
    }
  }

  return {
    previews: [...previews].sort(),
    annotations: [...markers].sort(),
    pngLess: [...pngLess].filter((name) => !staticNames.has(name)).sort(),
  };
}

/**
 * True when any source declares a `@CatalogComponent` — i.e. the module supplies (at least part of)
 * its catalog inventory from annotations rather than the spec. Lets a caller with source access tell
 * [validateSpec] (via `annotatedInventory`) to reject a `groups`-less spec that ALSO has no
 * annotated inventory (which would otherwise pass validation, render, then crash at the join).
 * Comments are stripped first so a commented-out or prose mention doesn't count.
 */
export function hasCatalogAnnotations(sources) {
  return sources.some((s) => /@CatalogComponent\b/.test(stripComments(s)));
}

/**
 * The componentIds an annotated module declares — every `@CatalogComponent(id = "…")` in [sources].
 * The annotation-supplied half of a catalog's inventory, which a spec with no `groups` (compose-m3,
 * wear-m3) carries *nowhere* else, so a spec-only check can't see them.
 *
 * Used to resolve `display.hero`: the hero names a componentId, and for those catalogs the only
 * place that id exists is the annotation next to the `@Preview`. Comments are stripped first so a
 * commented-out annotation doesn't count. A source-only scan, so it reads the literal id and skips a
 * computed one — the same conservative bargain [discoverPreviews] makes.
 *
 * @param {string[]} sources  Kotlin file contents.
 * @returns {string[]} sorted unique componentIds.
 */
export function discoverComponentIds(sources) {
  const ids = new Set();
  const re = /@CatalogComponent\s*\(([^)]*)\)/g;
  for (const source of sources) {
    for (const m of stripComments(source).matchAll(re)) {
      // `id` is the annotation's FIRST parameter, spelled either `id = "…"` or positionally. Try the
      // named form anywhere in the argument list first, then fall back to a leading positional
      // string. Deliberately two anchored patterns rather than one alternation over "any string
      // literal": the arguments run over several lines, so a pattern loose enough to skip the
      // leading newline+indent is also loose enough to match a LATER positional argument (`group`,
      // `caption`) and mint it as a componentId.
      const named = m[1].match(/(?:^|,)\s*id\s*=\s*"([^"]+)"/);
      const positional = m[1].match(/^\s*"([^"]+)"/);
      const id = named?.[1] ?? positional?.[1];
      if (id) ids.add(id);
    }
  }
  return [...ids].sort();
}

/** Every static, motion, and variant preview a spec references, each with a human-readable
 *  JSON-ish path for diagnostics. */
export function specPreviewRefs(spec) {
  const refs = [];
  const groups = Array.isArray(spec?.groups) ? spec.groups : [];
  groups.forEach((group, gi) => {
    const comps = Array.isArray(group?.components) ? group.components : [];
    comps.forEach((comp, ci) => {
      const base = `groups[${gi}].components[${ci}]`;
      if (typeof comp?.preview === "string") {
        refs.push({ preview: comp.preview, path: `${base} (${comp.componentId ?? "?"})` });
      }
      if (typeof comp?.motionPreview === "string") {
        refs.push({ preview: comp.motionPreview, path: `${base}.motionPreview` });
      }
      const variants = Array.isArray(comp?.variants) ? comp.variants : [];
      variants.forEach((v, vi) => {
        if (typeof v?.preview === "string") {
          refs.push({ preview: v.preview, path: `${base}.variants[${vi}]` });
        }
      });
    });
  });
  return refs;
}

/** Levenshtein distance, for "did you mean" suggestions on a mismatched name. */
export function editDistance(a, b) {
  const m = a.length;
  const n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  let prev = Array.from({ length: n + 1 }, (_, j) => j);
  let cur = new Array(n + 1);
  for (let i = 1; i <= m; i++) {
    cur[0] = i;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
    }
    [prev, cur] = [cur, prev];
  }
  return prev[n];
}

/** The closest candidate to `name` within a small edit budget, or null. */
export function closest(name, candidates) {
  let best = null;
  let bestDist = Infinity;
  for (const c of candidates) {
    const d = editDistance(name, c);
    if (d < bestDist) {
      bestDist = d;
      best = c;
    }
  }
  // Only suggest when the names are genuinely close (short typo), scaled to length.
  const budget = Math.max(2, Math.floor(name.length / 3));
  return best !== null && bestDist <= budget ? best : null;
}

/**
 * Validate a parsed catalog spec.
 *
 * Structural checks always run. Name-resolution checks run only when
 * `knownPreviews` is supplied (the discovered @Preview function names): each
 * spec `preview` must match one, and unreferenced previews are surfaced as a
 * coverage warning.
 *
 * `display.hero` is resolved the same way, against the union of the spec's own componentIds, any
 * `knownComponentIds` the caller scanned out of the module's `@CatalogComponent` annotations, and
 * `knownPreviews`. An unresolvable hero is a silent failure at serve time — the preview server just
 * falls through to its own representative pick — so it is caught here instead.
 *
 * @param {object} spec
 * @param {object} [opts]
 * @param {string[]|Set<string>} [opts.knownPreviews]
 * @param {string[]|Set<string>} [opts.knownComponentIds]  componentIds declared by
 *   `@CatalogComponent` annotations in the module (see [discoverComponentIds]).
 * @param {string[]|Set<string>} [opts.pngLessPreviews]  Discovered preview functions
 *   that render no static `previews/<id>.png` (see [discoverPreviews]'s `pngLess`).
 *   Referencing one is an error: `candidatePreviewBundle()` drops it from the
 *   candidate join and the completeness gate then reports the component missing.
 *   An entry that declares `"capture": "none"` is exempt — it has said so.
 * @param {boolean} [opts.liveBundle]  Whether the publish has a live path (a carried
 *   live bundle or a buildable `source`) the serve host can re-render deferred entries
 *   from. `false` rejects every `priority: "deferred"` — deferring without one is not a
 *   cheaper build, it is coverage silently dropped from the published sheet. Omitted
 *   (the structural-only CLI path, which can't know how the publish will be invoked)
 *   stays lenient; the driver enforces it for real at export time.
 * @param {boolean} [opts.annotatedInventory]  Whether the module supplies catalog entries via
 *   `@CatalogComponent` annotations (see [hasCatalogAnnotations]). `false` (scanned, none found)
 *   means `spec.groups` is the whole inventory, which unlocks two checks that need that certainty:
 *   a `groups`-less spec is rejected as empty, and an all-deferred spec is rejected (issue #2993).
 *   Omitted (no module scan) stays lenient so a hybrid annotation-plus-spec catalog isn't
 *   wrongly rejected.
 * @returns {{ errors: string[], warnings: string[] }}
 */
export function validateSpec(spec, opts = {}) {
  const errors = [];
  const warnings = [];

  if (!spec || typeof spec !== "object") {
    return { errors: ["spec is not a JSON object"], warnings };
  }
  if (typeof spec.system !== "string" || spec.system.length === 0) {
    errors.push("`system` is required (a stable slug, e.g. \"meshcore-mobile\")");
  }
  if (typeof spec.title !== "string" || spec.title.length === 0) {
    errors.push("`title` is required (the human-readable catalog name)");
  }
  // Render priority (issue #2950). Checked before `groups` so a cover-sheet-only spec's
  // `modePriority` is validated too, and so a bad value is reported once rather than per entry.
  errors.push(...modePriorityErrors(spec));
  warnings.push(...modePriorityWarnings(spec));
  // Like `modePriority`, a cover-sheet-level block: checked before `groups` so an annotation-only
  // catalog — which is exactly the repository-wide shape this exemption exists for — is checked too.
  errors.push(...completenessErrors(spec));
  if (opts.liveBundle === false && specDefersAnything(spec)) {
    errors.push(
      "this spec defers coverage (`priority: \"deferred\"` / `modePriority`) but the publish has no " +
        "live path — a deferred entry is only resolvable where the serve host can re-render it. " +
        "Publish with --publish-live-bundle (or a buildable --source-module), or drop the deferral.",
    );
  }
  // An all-deferred catalog (issue #2993): every referenced preview is deferred, so the render
  // filter would be empty — which both design-artifacts workflows read as "render everything", the
  // exact opposite of what deferral asks for, while the published bundle carries no baked sticker at
  // all. Rejected here rather than served by a render-none sentinel, matching the positive-list
  // philosophy: a catalog must keep at least one entry required.
  //
  // Only fires when the spec's `groups` are known to be the complete inventory — `annotatedInventory
  // === false` means the module was scanned and declares no `@CatalogComponent`. A hybrid catalog can
  // carry its required entries as annotations and place only deferred overrides in `spec.groups`; the
  // driver merges those annotation entries in as `required` (`mergeCatalogGroups`), so it is not truly
  // all-deferred, and an empty pre-flight filter correctly renders the annotation-supplied ones. When
  // the caller can't tell (`annotatedInventory` undefined — the structural-only path), stay lenient,
  // the same bargain the `groups`-omitted and `liveBundle` checks make.
  if (opts.annotatedInventory === false && defersEveryPreview(spec)) {
    errors.push(
      "this catalog defers every entry (`priority: \"deferred\"`) — no `required` preview is left, so " +
        "the render filter would be empty and both workflows would read that as *render everything* " +
        "instead of skipping the deferred renders, while the published bundle would carry no baked " +
        "stickers. Keep at least one entry `required`.",
    );
  }
  // `groups` is optional: a catalog can supply its whole component inventory from
  // `@CatalogComponent` / `@CatalogVariant` annotations (compose-ai-tools' catalog-annotations)
  // and carry only cover-sheet fields here. An ABSENT `groups` therefore validates — UNLESS the
  // caller passed `annotatedInventory: false` (it scanned the module and found no
  // `@CatalogComponent`), in which case the catalog has no inventory at all and would render then
  // crash, so fail here with a clear message. When the caller can't tell (`annotatedInventory`
  // undefined, e.g. the structural-only CLI path), stay lenient. When `groups` IS present it must
  // be a non-empty array (an explicit `[]` is a mistake, not "annotation-supplied").
  if (spec.groups === undefined) {
    if (opts.annotatedInventory === false) {
      errors.push(
        "`groups` is omitted but the module declares no @CatalogComponent — the catalog has no " +
          "inventory. Declare `groups`, or add @CatalogComponent / @CatalogVariant to the module's " +
          "@Preview functions.",
      );
    }
    // A cover-sheet-only spec still declares its hero here, and its componentIds live wholly in the
    // module's annotations — so resolve against those alone.
    errors.push(...heroErrors(spec, opts, new Set()));
    return { errors, warnings };
  }
  if (!Array.isArray(spec.groups) || spec.groups.length === 0) {
    errors.push("`groups`, when present, must be a non-empty array");
    return { errors, warnings };
  }

  const componentIds = new Map(); // componentId -> first path
  // The breakpoint names a `select.size` may legitimately name — the spec's own, or the Wear
  // default table it inherits. Undefined for a catalog with no size axis at all, in which case a
  // selection can't be checked against anything and the shape check alone applies.
  const declaredSizes = declaredBreakpointSizes(spec);
  const previewToPaths = new Map(); // preview name -> [paths]
  // `preview name -> Set of select signatures`, so the "referenced twice" warning below can tell a
  // copy-paste duplicate from the legitimate case this exists for: two entries splitting one
  // multipreview's breakpoints between them.
  const previewToSelects = new Map();
  // The subset of the above whose referring entry did NOT declare `"capture": "none"`. A PNG-less
  // preview is only an error for those: a `"none"` entry is *declaring* the absence.
  const staticRefPaths = new Map(); // preview name -> [paths]
  const motionRefPaths = new Map(); // motion preview name -> [paths]

  spec.groups.forEach((group, gi) => {
    const gp = `groups[${gi}]`;
    if (typeof group?.name !== "string" || group.name.length === 0) {
      errors.push(`${gp}.name is required`);
    }
    if (!Array.isArray(group?.components) || group.components.length === 0) {
      errors.push(`${gp}.components must be a non-empty array`);
      return;
    }
    group.components.forEach((comp, ci) => {
      const cp = `${gp}.components[${ci}]`;
      if (typeof comp?.componentId !== "string" || comp.componentId.length === 0) {
        errors.push(`${cp}.componentId is required`);
      } else if (componentIds.has(comp.componentId)) {
        errors.push(
          `${cp}.componentId "${comp.componentId}" is a duplicate (also at ${componentIds.get(comp.componentId)})`,
        );
      } else {
        componentIds.set(comp.componentId, cp);
      }
      errors.push(...captureErrors(comp, cp));
      errors.push(...selectErrors(comp, cp, declaredSizes));
      if (typeof comp?.preview !== "string" || comp.preview.length === 0) {
        errors.push(`${cp}.preview is required (an exact @Preview function name)`);
      } else {
        // Deferred entries stay in the resolution set on purpose: the serve host renders them from
        // the same module, so a `preview` that matches no @Preview function is just as broken when
        // it is deferred — it is simply broken later, on a viewer's request, instead of in CI.
        pushMulti(previewToPaths, comp.preview, cp);
        recordSelect(previewToSelects, comp.preview, comp);
        if (!exportsNoSticker(comp)) pushMulti(staticRefPaths, comp.preview, cp);
      }
      if (comp?.motionPreview !== undefined) {
        if (typeof comp.motionPreview !== "string" || comp.motionPreview.length === 0) {
          errors.push(`${cp}.motionPreview must be a non-empty @Preview function name`);
        } else {
          pushMulti(motionRefPaths, comp.motionPreview, cp);
        }
      }
      errors.push(...priorityErrors(comp, cp));
      const variants = comp?.variants;
      if (variants !== undefined) {
        if (!Array.isArray(variants)) {
          errors.push(`${cp}.variants must be an array when present`);
        } else {
          variants.forEach((v, vi) => {
            const vp = `${cp}.variants[${vi}]`;
            const allowedKeys = new Set([
              "preview",
              "caption",
              "state",
              "props",
              "theme",
              "select",
              "capture",
              "priority",
              // Kit correspondence, the same five fields a component carries. A variant is
              // compared in its own right rather than through its parent, so nesting a render
              // under one does not cost it its `parallel` (what the cross-system compare page
              // pairs on) or its reference.
              "parallel",
              "reference",
              "referenceSet",
              "noReference",
              "referenceContentsOnly",
            ]);
            for (const key of Object.keys(v ?? {})) {
              if (!allowedKeys.has(key)) {
                errors.push(
                  `${vp}.${key} is not supported; expected only ${[...allowedKeys]
                    .map((k) => `\`${k}\``)
                    .join(", ")}`,
                );
              }
            }
            errors.push(...captureErrors(v, vp));
            if (typeof v?.preview !== "string" || v.preview.length === 0) {
              errors.push(`${vp}.preview is required`);
            } else {
              pushMulti(previewToPaths, v.preview, vp);
              recordSelect(previewToSelects, v.preview, v);
              if (!exportsNoSticker(v)) pushMulti(staticRefPaths, v.preview, vp);
            }
            errors.push(...selectErrors(v, vp, declaredSizes));
            // A `select` distinguishes a variant as surely as a tag does: its images carry the
            // selected axis value, so they land on their own `…__<size>.png` rather than over the
            // default's.
            if (
              v?.state === undefined &&
              v?.props === undefined &&
              v?.theme === undefined &&
              selectOf(v) === undefined
            ) {
              errors.push(
                `${vp} has neither \`state\`, \`props\`, \`theme\` nor \`select\` — it would overwrite the default artifact`,
              );
            }
            if (v?.theme !== undefined && v.theme !== "light" && v.theme !== "dark") {
              errors.push(`${vp}.theme must be "light" or "dark" when present`);
            }
            // Typed here rather than left to the JSON schema: `catalog.spec.schema.json` is a
            // `$schema` hint for editors and is not enforced by any build step, so this function
            // is the only gate a malformed spec actually meets.
            for (const key of ["parallel", "reference", "referenceSet", "noReference"]) {
              if (v?.[key] !== undefined && typeof v[key] !== "string") {
                errors.push(`${vp}.${key} must be a string when present`);
              }
            }
            if (
              v?.referenceContentsOnly !== undefined &&
              typeof v.referenceContentsOnly !== "boolean"
            ) {
              errors.push(`${vp}.referenceContentsOnly must be a boolean when present`);
            }
            errors.push(...priorityErrors(v, vp));
          });
        }
      }
    });
  });

  // A preview name used by two components folds both into one candidate at
  // render time (the join keys on function name) — almost always a copy-paste
  // bug, so flag it. UNLESS every reference `select`s a different value: that is the
  // supported way to split a multipreview's breakpoints across entries without splitting
  // the @Preview function, and each reference then names its own sticker rather than
  // folding onto one.
  for (const [preview, paths] of previewToPaths) {
    if (paths.length > 1 && !selectsAreDistinct(previewToSelects.get(preview), paths.length)) {
      warnings.push(
        `preview "${preview}" is referenced ${paths.length}× (${paths.join(", ")}) — these fold into one sticker`,
      );
    }
  }

  const known = toSet(opts.knownPreviews);
  // PNG-less previews are still legitimately discovered @Preview functions, so they
  // resolve — but they can't be catalog entries.
  const pngLess = toSet(opts.pngLessPreviews) ?? new Set();
  if (known) {
    for (const [preview, paths] of previewToPaths) {
      if (!known.has(preview)) {
        const hint = closest(preview, [...known]);
        const suffix = hint ? ` — did you mean "${hint}"?` : "";
        errors.push(
          `preview "${preview}" (${paths[0]}) matches no @Preview function in the scanned module${suffix}`,
        );
      } else if (pngLess.has(preview) && staticRefPaths.has(preview)) {
        // Entries that declared `"capture": "none"` are exempt — they have *said* the preview exports
        // no sticker, which is exactly what this error asks for. Only the undeclared refs fail.
        errors.push(
          `preview "${preview}" (${staticRefPaths.get(preview)[0]}) renders no static PNG — it is an ` +
            `animated/data-product capture (@AnimatedPreview, @FocusedPreview(gif = true), or ` +
            `@ScrollingPreview with only LONG/GIF modes). The catalog export drops PNG-less previews ` +
            `from the candidate join, so this entry would be reported missing by the completeness ` +
            `gate. Point it at a static @Preview function (a plain @Preview sibling of the animated ` +
            `one works), or declare the entry \`"capture": "none"\` to keep it in the spec as a known ` +
            `sticker-less component.`,
        );
      }
    }
    for (const [preview, paths] of motionRefPaths) {
      if (!known.has(preview)) {
        const hint = closest(preview, [...known]);
        const suffix = hint ? ` — did you mean "${hint}"?` : "";
        errors.push(
          `motion preview "${preview}" (${paths[0]}) matches no @Preview function in the scanned module${suffix}`,
        );
      }
    }
    const referenced = new Set([...previewToPaths.keys(), ...motionRefPaths.keys()]);
    // PNG-less previews can't be catalogued at all, so their absence isn't a
    // coverage gap worth reporting.
    const orphans = [...known].filter((p) => !referenced.has(p) && !pngLess.has(p));
    if (orphans.length > 0) {
      warnings.push(
        `${orphans.length} @Preview function(s) not in the catalog: ${orphans.slice(0, 12).join(", ")}${orphans.length > 12 ? ", …" : ""}`,
      );
    }
  }

  errors.push(...heroErrors(spec, opts, new Set(componentIds.keys())));

  return { errors, warnings };
}

/**
 * Resolve `display.hero` against everything that could name a preview: the spec's own
 * [specComponentIds], the module's annotated componentIds, and the `@Preview` function names. The
 * server ([ServeBundleHost.declaredHeroPreviewId]) accepts any of the three, so validation has to
 * accept all three too — the point is only to catch a hero that matches *nothing*, which the server
 * would silently ignore.
 */
function heroErrors(spec, opts, specComponentIds) {
  const hero = spec?.display?.hero;
  if (typeof hero !== "string" || hero.length === 0) return [];
  // Without a module scan the candidate set is only half the picture (a hero may legitimately name a
  // `@Preview` function this spec never lists), so a structural-only run stays lenient — the same
  // bargain the `preview` checks make.
  if (opts.knownPreviews === undefined && opts.knownComponentIds === undefined) return [];
  const candidates = new Set([
    ...specComponentIds,
    ...(opts.knownComponentIds ?? []),
    ...(opts.knownPreviews ?? []),
  ]);
  if (candidates.size === 0 || candidates.has(hero)) return [];
  // `@CatalogComponent(perBreakpoint = true)` mints `<id>/<breakpoint>` components, and WHICH
  // breakpoints those are comes from the renders — which this build-free source scan can't see. So
  // a hero naming one resolves on its parent id. Deliberately lenient rather than guessing at a
  // breakpoint table the module doesn't state: the exact check runs at export time, against the
  // real inventory (`heroResolvesInInventory`), where every id is known.
  if (candidates.has(hero.slice(0, hero.lastIndexOf("/")))) return [];
  const hint = closest(hero, [...candidates]);
  return [
    `display.hero "${hero}" matches no componentId or @Preview function${hint ? ` — did you mean "${hint}"?` : ""}`,
  ];
}

/**
 * Validate an entry's optional `capture` axis (component or variant). Only the values in
 * [CAPTURE_MODES] mean anything to the export, and a typo (`"animation"`, `"gif"`) would read as the
 * default `"static"` and silently sink the publish on the very component it was meant to exempt —
 * so it is an error here rather than a shrug at render time.
 */
function captureErrors(entry, path) {
  const capture = entry?.capture;
  if (capture === undefined) return [];
  if (typeof capture !== "string" || !CAPTURE_MODES.includes(capture)) {
    return [
      `${path}.capture must be one of ${CAPTURE_MODES.map((m) => `"${m}"`).join(", ")} ` +
        `(absent ⇒ "static")`,
    ];
  }
  return [];
}

/**
 * Reject a `priority` that isn't one of the documented values. Deliberately an ERROR rather than a
 * lenient fallback: `entryPriority` reads anything unrecognised as `required`, so a typo
 * (`"defered"`, `"optional"`) would otherwise bake the entry and look like the deferral simply
 * didn't save anything — the confusing failure this check exists to prevent.
 */
function priorityErrors(entry, path) {
  const value = entry?.priority;
  if (value === undefined) return [];
  if (typeof value === "string" && PRIORITIES.includes(value)) return [];
  return [
    `${path}.priority must be one of ${PRIORITIES.map((p) => `"${p}"`).join(", ")} ` +
      `(got ${JSON.stringify(value)})`,
  ];
}

/**
 * Structural checks for the `completeness` block (issue #4117).
 *
 * An ERROR rather than a lenient skip, on the same reasoning as `capture` and `priority`: the
 * consumer ignores a malformed value (`exemptSemanticsPatterns` exempts nothing), so a typed-wrong
 * exemption would present as the gate failing on the very entry it was written to excuse — with
 * nothing pointing back at the spec.
 */
function completenessErrors(spec) {
  const block = spec?.completeness;
  if (block === undefined) return [];
  if (typeof block !== "object" || block === null || Array.isArray(block)) {
    return ["`completeness` must be an object (currently only `exemptSemantics` lives in it)"];
  }
  const errors = [];
  // `$comment` is allowed alongside the fields, as it is at the top level: an exemption is a
  // judgement call ("these renders capture nothing by their nature") and the reason belongs next to
  // the list, not in a distant header — JSON has nowhere else to put it.
  for (const key of Object.keys(block)) {
    if (key !== "exemptSemantics" && key !== "$comment") {
      errors.push(`completeness.${key} is not a known field (did you mean \`exemptSemantics\`?)`);
    }
  }
  // Allowed is not the same as untyped. The schema declares `$comment` a string, and
  // `validate-catalog-spec.mjs` runs THIS validator rather than the schema — so without the check a
  // number, object, array or null sails through the advertised structural pre-flight and is
  // rejected later by whatever schema-aware tooling the author reaches for, which is the worst
  // possible order to learn it in.
  if ("$comment" in block && typeof block.$comment !== "string") {
    errors.push("`completeness.$comment` must be a string");
  }
  const exempt = block.exemptSemantics;
  if (exempt === undefined) return errors;
  if (!Array.isArray(exempt)) {
    errors.push(
      "`completeness.exemptSemantics` must be an array of componentId patterns " +
        '(e.g. ["*Activity", "app/getting-started"])',
    );
    return errors;
  }
  exempt.forEach((pattern, i) => {
    if (typeof pattern !== "string" || pattern.trim().length === 0) {
      errors.push(
        `completeness.exemptSemantics[${i}] must be a non-empty componentId pattern ` +
          `(got ${JSON.stringify(pattern)})`,
      );
    }
  });
  return errors;
}

/** Structural checks for the `modePriority` axis table. */
function modePriorityErrors(spec) {
  const table = spec?.modePriority;
  if (table === undefined) return [];
  if (typeof table !== "object" || table === null || Array.isArray(table)) {
    return ['`modePriority` must be an object mapping mode name (or "*") to a priority'];
  }
  const errors = [];
  for (const [mode, value] of Object.entries(table)) {
    if (typeof value !== "string" || !PRIORITIES.includes(value)) {
      errors.push(
        `modePriority["${mode}"] must be one of ${PRIORITIES.map((p) => `"${p}"`).join(", ")} ` +
          `(got ${JSON.stringify(value)})`,
      );
    }
  }
  return errors;
}

/**
 * Soft checks for `modePriority`: a table naming a mode the spec doesn't declare is usually a typo,
 * and a table that defers *every* declared mode leaves only the untagged primary sticker baked —
 * legal (that IS the "one sticker per component" configuration) but worth saying out loud.
 */
function modePriorityWarnings(spec) {
  const table = spec?.modePriority;
  if (!table || typeof table !== "object" || Array.isArray(table)) return [];
  const declared = (spec?.modes ?? []).filter((m) => typeof m === "string");
  const warnings = [];
  if (declared.length > 0) {
    const unknown = Object.keys(table).filter(
      (mode) => mode !== MODE_WILDCARD && !declared.includes(mode),
    );
    if (unknown.length > 0) {
      warnings.push(
        `modePriority names mode(s) not in \`modes\`: ${unknown.join(", ")} — ` +
          `declared modes are ${declared.join(", ")}`,
      );
    }
    const kept = declared.filter((mode) => modePriorityOf(spec, mode) !== DEFERRED);
    if (kept.length === 0) {
      warnings.push(
        "modePriority defers every declared mode — only each component's untagged primary sticker " +
          "will be baked, and every themed palette comes from the live preview server",
      );
    }
  }
  return warnings;
}

/** Normalise an optional array-or-Set option to a Set, or null when absent. */
function toSet(value) {
  if (!value) return null;
  return value instanceof Set ? value : new Set(value);
}

function pushMulti(map, key, value) {
  const arr = map.get(key);
  if (arr) arr.push(value);
  else map.set(key, [value]);
}

/**
 * Structural checks for an entry's optional `select` (component or variant).
 *
 * All errors rather than warnings, for the same reason `capture` and `priority` are: an unusable
 * selection doesn't degrade gracefully. An unknown axis would be ignored by `selectImages` and the
 * entry would quietly fold in every render of its function — the opposite of what it asked for —
 * while a mistyped breakpoint name matches nothing and sinks the publish much later, as a missing
 * render on an entry whose `preview` is demonstrably fine.
 *
 * [declaredSizes] is undefined for a catalog with no size axis (no `breakpoints`, no Wear default),
 * where a `select.size` can't be checked against anything; the value check is skipped rather than
 * guessed at.
 */
function selectErrors(entry, path, declaredSizes) {
  const select = entry?.select;
  if (select === undefined) return [];
  if (typeof select !== "object" || select === null || Array.isArray(select)) {
    return [`${path}.select must be an object, e.g. { "size": "largeRound" }`];
  }
  const errors = [];
  if (Object.keys(select).length === 0) {
    errors.push(
      `${path}.select is empty — drop it, or name an axis (${SELECT_AXES.map((a) => `\`${a}\``).join(", ")})`,
    );
  }
  for (const [axis, value] of Object.entries(select)) {
    if (!SELECT_AXES.includes(axis)) {
      errors.push(
        `${path}.select.${axis} is not a selectable axis; expected only ` +
          `${SELECT_AXES.map((a) => `\`${a}\``).join(", ")}`,
      );
      continue;
    }
    if (typeof value !== "string" || value.length === 0) {
      errors.push(`${path}.select.${axis} must be a non-empty string`);
      continue;
    }
    if (axis === "size" && declaredSizes && !declaredSizes.has(value)) {
      const hint = closest(value, [...declaredSizes]);
      errors.push(
        `${path}.select.size "${value}" is not a declared breakpoint` +
          (hint ? ` — did you mean "${hint}"?` : ` (declared: ${[...declaredSizes].join(", ")})`),
      );
    }
  }
  return errors;
}

/** Record one reference's `select` signature (`""` for an unselected reference). */
function recordSelect(map, preview, entry) {
  const select = selectOf(entry);
  const signature = select
    ? Object.entries(select)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([axis, value]) => `${axis}=${value}`)
        .join(",")
    : "";
  const seen = map.get(preview);
  if (seen) seen.add(signature);
  else map.set(preview, new Set([signature]));
}

/**
 * Whether every reference to one preview selects a DIFFERENT value — the deliberate split of a
 * multipreview across entries. Requires each reference to select something: an unselected reference
 * folds in every render including the ones its siblings selected, so mixing the two forms really
 * does double up stickers and stays worth a warning.
 */
function selectsAreDistinct(signatures, references) {
  if (!signatures || signatures.size !== references) return false;
  return !signatures.has("");
}

/**
 * The breakpoint names a `select.size` may name, or undefined when the catalog declares no size
 * axis at all — including the Wear default table a Wear catalog inherits by omitting `breakpoints`,
 * since the export tags its stickers with those names and a spec must be able to select them.
 */
function declaredBreakpointSizes(spec) {
  const declared = catalogBreakpoints(spec);
  if (declared === undefined) return undefined;
  const sizes = new Set(
    declared.filter((b) => typeof b?.size === "string" && b.size.length > 0).map((b) => b.size),
  );
  return sizes.size > 0 ? sizes : undefined;
}

/**
 * Build a skeleton `catalog.spec.json` object from discovered preview names —
 * one flat "Components" group, each preview a component keyed by its own name,
 * with an empty caption to fill in. A starting point to edit, not a finished
 * spec.
 */
export function buildSkeletonSpec({ system, title, module, modes, previews, schema } = {}) {
  const spec = {};
  if (schema) spec.$schema = schema;
  spec.system = system ?? "TODO-system-slug";
  spec.title = title ?? "TODO Catalog Title";
  if (module) spec.module = module;
  spec.modes = modes ?? ["light", "dark"];
  spec.groups = [
    {
      name: "Components",
      components: (previews ?? []).map((preview) => ({
        componentId: preview,
        preview,
        caption: "",
      })),
    },
  ];
  return spec;
}
