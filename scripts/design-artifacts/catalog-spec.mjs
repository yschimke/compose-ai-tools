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

// The built-in Compose preview annotation. Any function annotated with it — or
// with a *multipreview* annotation (an annotation class itself meta-annotated
// with @Preview, e.g. @CatalogModes) — is a rendered preview whose function name
// a spec `preview` can reference.
const PREVIEW_ANNOTATION = "Preview";

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

/** The set of `@Name` identifiers named in a leading-annotation run. */
function annotationNames(run) {
  const names = new Set();
  for (const m of run.matchAll(/@([\w.]+)/g)) {
    // Keep only the simple name (`a.b.CatalogModes` → `CatalogModes`) — Kotlin
    // call sites usually import and use the short name, which is what a
    // multipreview `annotation class` is declared under.
    const parts = m[1].split(".");
    names.add(parts[parts.length - 1]);
  }
  return names;
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
 * @returns {{ previews: string[], annotations: string[] }}
 *   `previews`: sorted unique function names. `annotations`: the multipreview
 *   annotation names recognised (built-in `Preview` + discovered + extra).
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
  for (const text of texts) {
    for (const m of text.matchAll(funRe)) {
      const names = annotationNames(m[1]);
      if ([...names].some((a) => markers.has(a))) previews.add(m[2]);
    }
  }

  return {
    previews: [...previews].sort(),
    annotations: [...markers].sort(),
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

/** Every `preview` a spec references, top-level and inside `variants`, each with
 *  a human-readable JSON-ish path for diagnostics. */
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
 * @param {object} spec
 * @param {object} [opts]
 * @param {string[]|Set<string>} [opts.knownPreviews]
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
    return { errors, warnings };
  }
  if (!Array.isArray(spec.groups) || spec.groups.length === 0) {
    errors.push("`groups`, when present, must be a non-empty array");
    return { errors, warnings };
  }

  const componentIds = new Map(); // componentId -> first path
  const previewToPaths = new Map(); // preview name -> [paths]

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
      if (typeof comp?.preview !== "string" || comp.preview.length === 0) {
        errors.push(`${cp}.preview is required (an exact @Preview function name)`);
      } else {
        pushMulti(previewToPaths, comp.preview, cp);
      }
      const variants = comp?.variants;
      if (variants !== undefined) {
        if (!Array.isArray(variants)) {
          errors.push(`${cp}.variants must be an array when present`);
        } else {
          variants.forEach((v, vi) => {
            const vp = `${cp}.variants[${vi}]`;
            const allowedKeys = new Set(["preview", "caption", "state", "props", "theme"]);
            for (const key of Object.keys(v ?? {})) {
              if (!allowedKeys.has(key)) {
                errors.push(
                  `${vp}.${key} is not supported; expected only ${[...allowedKeys]
                    .map((k) => `\`${k}\``)
                    .join(", ")}`,
                );
              }
            }
            if (typeof v?.preview !== "string" || v.preview.length === 0) {
              errors.push(`${vp}.preview is required`);
            } else {
              pushMulti(previewToPaths, v.preview, vp);
            }
            if (v?.state === undefined && v?.props === undefined && v?.theme === undefined) {
              errors.push(
                `${vp} has neither \`state\`, \`props\` nor \`theme\` — it would overwrite the default artifact`,
              );
            }
            if (v?.theme !== undefined && v.theme !== "light" && v.theme !== "dark") {
              errors.push(`${vp}.theme must be "light" or "dark" when present`);
            }
          });
        }
      }
    });
  });

  // A preview name used by two components folds both into one candidate at
  // render time (the join keys on function name) — almost always a copy-paste
  // bug, so flag it.
  for (const [preview, paths] of previewToPaths) {
    if (paths.length > 1) {
      warnings.push(
        `preview "${preview}" is referenced ${paths.length}× (${paths.join(", ")}) — these fold into one sticker`,
      );
    }
  }

  const known = opts.knownPreviews
    ? opts.knownPreviews instanceof Set
      ? opts.knownPreviews
      : new Set(opts.knownPreviews)
    : null;
  if (known) {
    for (const [preview, paths] of previewToPaths) {
      if (!known.has(preview)) {
        const hint = closest(preview, [...known]);
        const suffix = hint ? ` — did you mean "${hint}"?` : "";
        errors.push(
          `preview "${preview}" (${paths[0]}) matches no @Preview function in the scanned module${suffix}`,
        );
      }
    }
    const referenced = new Set(previewToPaths.keys());
    const orphans = [...known].filter((p) => !referenced.has(p));
    if (orphans.length > 0) {
      warnings.push(
        `${orphans.length} @Preview function(s) not in the catalog: ${orphans.slice(0, 12).join(", ")}${orphans.length > 12 ? ", …" : ""}`,
      );
    }
  }

  return { errors, warnings };
}

function pushMulti(map, key, value) {
  const arr = map.get(key);
  if (arr) arr.push(value);
  else map.set(key, [value]);
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
