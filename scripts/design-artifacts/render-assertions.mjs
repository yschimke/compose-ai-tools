// Evaluate a module's declared render assertions against the data products a render produced.
//
// WHY THIS EXISTS
//
// A catalog can say WHAT to render, and the visual diff can say whether a render CHANGED. Neither
// can say whether a render is still TRUE to a property the project declared — "every sticker in
// this sheet types in Google Sans Flex", "every scrolling Wear screen shows a position indicator".
//
// That gap is not hypothetical. `:glimmer-catalog` rendered all seven Glimmer type roles at weight
// 400 for its entire life: the axes carrying 520/650/750 were filtered away by
// `Paint.setFontVariationSettings` against a static font instance, silently. It passed the render,
// passed `failOnFallback` (the FAMILY resolved), and passed the visual diff (it was wrong from the
// first render, so nothing changed). Every fact needed to catch it was already on disk —
// `fonts-used.json` named the family, `compose-semantics` carried
// `typography.fontVariationSettings` per text node — and nothing read it against an expectation.
//
// So this asserts over the data products a render ALREADY writes. It never inspects a render
// itself: a new assertable property is a data-product question, answered there, not here.
//
// See issue #5467 for the design and its open questions.

/** Products this version knows how to read, and the named paths each one exposes. */
export const SUPPORTED = {
  "fonts-used": ["everyFont.resolvedFamily", "everyFont.requestedFamily", "noFont.fellBackFrom"],
  "compose-semantics": [
    "everyTextNode.typography.fontFamily",
    "everyTextNode.typography.fontVariationSettings",
    "anyNode.role",
  ],
};

/**
 * Structural validation of a render-assertions document.
 *
 * Deliberately strict about the SHAPE and deliberately narrow about the PREDICATES: an unknown
 * product or path is an error rather than a silently-skipped assertion, because an assertion that
 * quietly does nothing is worse than no assertion — it reads as coverage that is not there.
 */
export function validateAssertions(doc) {
  const errors = [];
  if (doc == null || typeof doc !== "object") return ["not an object"];
  const list = doc.assertions;
  if (!Array.isArray(list)) return ["`assertions` must be an array"];

  const seen = new Set();
  list.forEach((a, i) => {
    const at = `assertions[${i}]`;
    if (!a || typeof a !== "object") {
      errors.push(`${at}: not an object`);
      return;
    }
    if (typeof a.id !== "string" || a.id.trim() === "") errors.push(`${at}: needs a non-empty id`);
    else if (seen.has(a.id)) errors.push(`${at}: duplicate id "${a.id}"`);
    else seen.add(a.id);

    // Every assertion carries its reason. A rule nobody can justify later is a rule nobody dares
    // delete — the same bar `glimmer-samples/quarantine.json` sets for its entries.
    if (typeof a.because !== "string" || a.because.trim() === "")
      errors.push(`${at}: needs a "because" saying what being wrong would mean`);

    const paths = SUPPORTED[a.product];
    if (!paths) {
      errors.push(
        `${at}: unknown product "${a.product}" (known: ${Object.keys(SUPPORTED).join(", ")})`,
      );
    }
    const require = a.require;
    if (!require || typeof require !== "object" || Object.keys(require).length !== 1) {
      errors.push(`${at}: "require" must name exactly one path`);
    } else if (paths) {
      const [path] = Object.keys(require);
      if (!paths.includes(path))
        errors.push(`${at}: unknown path "${path}" for ${a.product} (known: ${paths.join(", ")})`);
    }

    for (const [j, ex] of (a.exceptions ?? []).entries()) {
      const et = `${at}.exceptions[${j}]`;
      if (typeof ex?.preview !== "string") errors.push(`${et}: needs a "preview"`);
      if (typeof ex?.reason !== "string" || ex.reason.trim() === "")
        errors.push(`${et}: needs a "reason" — a silent allowlist rots`);
    }
  });
  return errors;
}

/** `*` and `*Foo*` globbing, which is all any real `appliesTo` has needed. */
export function previewMatches(pattern, preview) {
  if (pattern === "*") return true;
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*");
  return new RegExp(`^${escaped}$`).test(preview);
}

/** The values a named path selects from one preview's product, as `{value, where}` observations. */
export function observe(product, path, data) {
  const out = [];
  if (product === "fonts-used") {
    for (const f of data?.fonts ?? []) {
      if (path === "everyFont.resolvedFamily")
        out.push({ value: f.resolvedFamily, where: `${f.requestedFamily} ${f.weight}` });
      else if (path === "everyFont.requestedFamily")
        out.push({ value: f.requestedFamily, where: `${f.weight}` });
      else if (path === "noFont.fellBackFrom")
        out.push({ value: f.fellBackFrom ?? null, where: f.resolvedFamily });
    }
    return out;
  }
  if (product === "compose-semantics") {
    const walk = (node) => {
      if (!node || typeof node !== "object") return;
      const id = node.nodeId ?? node.text ?? "node";
      if (path === "everyTextNode.typography.fontFamily" && node.text != null)
        out.push({ value: node.typography?.fontFamily ?? null, where: id });
      if (path === "everyTextNode.typography.fontVariationSettings" && node.text != null)
        out.push({ value: node.typography?.fontVariationSettings ?? null, where: id });
      if (path === "anyNode.role") out.push({ value: node.role ?? null, where: id });
      for (const child of node.children ?? []) walk(child);
    };
    walk(data?.root ?? data);
    return out;
  }
  return out;
}

/**
 * The predicate one `require` entry means, for one observed value.
 *
 * `noFont.*` is an absence check, `"contains X"` a substring check, anything else exact equality.
 * Extracted rather than inlined because `evaluate` applies it twice — once to find failures, once
 * to find exceptions that no longer excuse anything — and two copies that drift would make a stale
 * exception report the opposite of the truth.
 */
export function predicateFor(path, expected) {
  if (path.startsWith("noFont")) return (o) => o.value == null || o.value.length === 0;
  if (typeof expected === "string" && expected.startsWith("contains ")) {
    const needle = expected.slice("contains ".length);
    return (o) => String(o.value ?? "").includes(needle);
  }
  return (o) => o.value === expected;
}

/**
 * Evaluate one assertion over `{preview -> product data}`.
 *
 * `anyNode.*` paths hold when ONE observation matches; every other path holds when EVERY
 * observation does. A preview that produced no observations at all is not a pass: it is reported
 * as `no-data`, because an assertion silently matching nothing is the failure mode this whole file
 * exists to prevent.
 */
export function evaluate(assertion, byPreview) {
  const { product, require: req, exceptions = [], appliesTo } = assertion;
  const [path, expected] = Object.entries(req)[0];
  // Only `anyNode.*` is an existence check. `noFont.*` reads as "no font did X", which is a
  // universal over the negated predicate — `predicateFor` already negates, so it stays an
  // every-check. Treating it as an any-check would pass a sheet where one font of twenty resolved.
  const anyOf = path.startsWith("anyNode");
  const holds = predicateFor(path, expected);
  const patterns = appliesTo?.previews ?? ["*"];
  const excused = new Set(exceptions.map((e) => e.preview));

  const failures = [];
  const noData = [];
  let checked = 0;

  for (const [preview, data] of Object.entries(byPreview)) {
    if (!patterns.some((p) => previewMatches(p, preview))) continue;
    if (excused.has(preview)) continue;
    checked++;

    const observations = observe(product, path, data);
    if (observations.length === 0) {
      noData.push(preview);
      continue;
    }
    if (anyOf) {
      if (!observations.some(holds))
        failures.push({ preview, observed: observations.map((o) => o.value) });
    } else {
      const bad = observations.filter((o) => !holds(o));
      if (bad.length > 0)
        failures.push({ preview, observed: bad.map((o) => `${o.value} (${o.where})`) });
    }
  }

  // An exception naming a preview that now passes, or that no longer exists, is a lie about the
  // codebase — so it fails too, rather than accumulating quietly.
  const stale = exceptions
    .filter((e) => {
      const data = byPreview[e.preview];
      if (data === undefined) return true;
      const observations = observe(product, path, data);
      if (observations.length === 0) return false;
      return anyOf ? observations.some(holds) : observations.every(holds);
    })
    .map((e) => e.preview);

  return { id: assertion.id, checked, failures, noData, staleExceptions: stale };
}

/** A human-readable report. A failure that does not name the observed value is half a failure. */
export function formatResult(assertion, result) {
  const lines = [];
  const [path, expected] = Object.entries(assertion.require)[0];
  if (result.failures.length > 0) {
    lines.push(
      `FAIL ${result.id}: expected ${path} ${JSON.stringify(expected)} — ` +
        `${result.failures.length} of ${result.checked} previews differ`,
    );
    for (const f of result.failures.slice(0, 10))
      lines.push(`       ${f.preview}: observed ${f.observed.slice(0, 4).join(", ")}`);
    lines.push(`       because: ${assertion.because}`);
  }
  // Zero previews checked is not a pass. An `appliesTo` whose pattern no longer matches anything,
  // a module dropped from the render, an assertion applied to an empty set — each leaves a rule
  // that reads as coverage while asserting nothing, which is the same hole as a stale exception.
  if (result.checked === 0 && result.staleExceptions.length === 0)
    lines.push(
      `FAIL ${result.id}: matched no preview — an assertion that checks nothing is not a pass`,
    );
  if (result.noData.length > 0)
    lines.push(
      `FAIL ${result.id}: no ${assertion.product} data for ${result.noData.length} preview(s) ` +
        `(${result.noData.slice(0, 5).join(", ")}) — an assertion matching nothing is not a pass`,
    );
  for (const p of result.staleExceptions)
    lines.push(`FAIL ${result.id}: exception for "${p}" is stale — it passes now, or it is gone`);
  if (lines.length === 0) lines.push(`ok   ${result.id} (${result.checked} previews)`);
  return lines.join("\n");
}

/** The two sidecar suffixes each supported product is carried under inside a preview bundle. */
const SIDECAR_SUFFIX = { "fonts-used": ".fonts.json", "compose-semantics": ".semantics.json" };

/**
 * Index a bundle's `previews/<id>.<suffix>` sidecars into `{product: {preview: data}}`.
 *
 * Takes the raw `{path: bytes}` entry map rather than a zip so the indexing stays pure and
 * testable; the CLI does the decode. An unparseable sidecar is NOT skipped the way
 * `fontsPayloadsFromBundle` skips one — a best-effort manifest can shrug off a corrupt record,
 * but an assertion that silently loses its input reports a pass it never checked. It is surfaced
 * as an unreadable preview so the caller can fail on it.
 */
export function productsFromEntries(entries) {
  const products = { "fonts-used": {}, "compose-semantics": {} };
  const unreadable = [];
  for (const [path, bytes] of Object.entries(entries ?? {})) {
    if (!path.startsWith("previews/")) continue;
    for (const [product, suffix] of Object.entries(SIDECAR_SUFFIX)) {
      if (!path.endsWith(suffix)) continue;
      const id = path.slice("previews/".length, path.length - suffix.length);
      try {
        products[product][id] = JSON.parse(
          typeof bytes === "string" ? bytes : new TextDecoder().decode(bytes),
        );
      } catch (e) {
        unreadable.push(`${path}: ${e.message}`);
      }
    }
  }
  return { products, unreadable };
}

/**
 * Evaluate every assertion in [doc] against `{product: {preview: data}}`.
 *
 * Returns `{ok, results, report}`. A document that does not validate never evaluates: a malformed
 * assertion set failing open would be the exact silent-coverage hole this file argues against.
 */
export function runAssertions(doc, products) {
  const errors = validateAssertions(doc);
  if (errors.length > 0)
    return {
      ok: false,
      results: [],
      report: errors.map((e) => `FAIL invalid render-assertions document — ${e}`).join("\n"),
    };

  const results = doc.assertions.map((a) => evaluate(a, products[a.product] ?? {}));
  const ok = results.every(
    (r) =>
      r.failures.length === 0 &&
      r.noData.length === 0 &&
      r.staleExceptions.length === 0 &&
      r.checked > 0,
  );
  const report = doc.assertions.map((a, i) => formatResult(a, results[i])).join("\n");
  return { ok, results, report };
}
