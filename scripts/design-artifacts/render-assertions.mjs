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
    // An assertion states its predicate EITHER declaratively (`require`) or as code (`check`).
    // Never both: two sources of truth for one verdict is a bug waiting for the day they disagree.
    const hasCheck = typeof a.check === "function";
    const hasRequire = a.require !== undefined;
    if (hasCheck && hasRequire) {
      errors.push(`${at}: give either "require" or "check", not both`);
    } else if (!hasCheck && !hasRequire) {
      errors.push(`${at}: needs a "require" path or a "check" function`);
    } else if (hasRequire) {
      const require = a.require;
      if (!require || typeof require !== "object" || Object.keys(require).length !== 1) {
        errors.push(`${at}: "require" must name exactly one path`);
      } else if (paths) {
        const [path] = Object.keys(require);
        if (!paths.includes(path))
          errors.push(
            `${at}: unknown path "${path}" for ${a.product} (known: ${paths.join(", ")})`,
          );
      }
    }

    if (a.exceptions !== undefined && !Array.isArray(a.exceptions)) {
      errors.push(`${at}: "exceptions" must be an array`);
      return;
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
 * Whether a product carried nothing for one preview.
 *
 * Framework-owned on purpose. "The render produced no data" must not be a judgement a `check`
 * makes, because a `check` that forgets the empty case returns "holds" and reports a pass over
 * nothing — the exact silent-coverage hole this file exists to close. A `check` never sees an
 * empty product.
 */
export function productIsEmpty(product, data) {
  if (data == null) return true;
  if (product === "fonts-used") return !(data.fonts?.length > 0);
  if (product === "compose-semantics") {
    let seen = 0;
    const walk = (node) => {
      if (!node || typeof node !== "object") return;
      seen++;
      for (const child of node.children ?? []) walk(child);
    };
    walk(data.root ?? data);
    return seen === 0;
  }
  return false;
}

/**
 * Compile one declarative `require` into the same `check(data)` contract a code assertion supplies.
 *
 * The declarative form is sugar, not a second engine: it lowers to a function and is then evaluated
 * by exactly the code path a hand-written `check` takes. Two evaluators is how the two forms would
 * start disagreeing about what "stale exception" means.
 *
 * Returns null when the assertion holds, or a string naming what was observed.
 */
export function compileRequire(product, path, expected) {
  // Only `anyNode.*` is an existence check. `noFont.*` reads as "no font did X", a universal over
  // the negated predicate — `predicateFor` already negates, so it stays an every-check.
  const anyOf = path.startsWith("anyNode");
  const holds = predicateFor(path, expected);
  return (data) => {
    const observations = observe(product, path, data);
    if (observations.length === 0) return `${path} selected no value`;
    if (anyOf) {
      if (observations.some(holds)) return null;
      return `no node matched (saw ${observations
        .map((o) => o.value)
        .slice(0, 4)
        .join(", ")})`;
    }
    const bad = observations.filter((o) => !holds(o));
    if (bad.length === 0) return null;
    return bad
      .slice(0, 4)
      .map((o) => `${o.value} (${o.where})`)
      .join(", ");
  };
}

/** The `check(data)` an assertion means, whether it supplied one or declared a `require`. */
export function checkFor(assertion) {
  if (typeof assertion.check === "function") return assertion.check;
  const [path, expected] = Object.entries(assertion.require)[0];
  return compileRequire(assertion.product, path, expected);
}

/**
 * Evaluate one assertion over `{preview -> product data}`.
 *
 * The predicate is a `check(data)` returning null when it holds or a string naming what was
 * observed; a declarative `require` is compiled into one. Everything a `check` must NOT be trusted
 * with stays here: a preview whose product is empty is `no-data` rather than a pass, an assertion
 * matching no preview at all is not a pass, an exception that no longer excuses anything fails, and
 * a `check` that THROWS fails rather than being skipped. Fail-closed has to cover the escape hatch,
 * or the escape hatch is the hole.
 */
export function evaluate(assertion, byPreview) {
  const { product, exceptions = [], appliesTo } = assertion;
  const check = checkFor(assertion);
  const patterns = appliesTo?.previews ?? ["*"];
  const excused = new Set(exceptions.map((e) => e.preview));

  // A throwing check is a failing check. Returning "holds" on a crash would let a typo in a
  // catalog's assertion read as green forever.
  const verdict = (data) => {
    try {
      return check(data) ?? null;
    } catch (e) {
      return `check threw: ${e.message}`;
    }
  };

  const failures = [];
  const noData = [];
  let checked = 0;

  for (const [preview, data] of Object.entries(byPreview)) {
    if (!patterns.some((p) => previewMatches(p, preview))) continue;
    if (excused.has(preview)) continue;
    checked++;

    if (productIsEmpty(product, data)) {
      noData.push(preview);
      continue;
    }
    const detail = verdict(data);
    if (detail !== null) failures.push({ preview, detail });
  }

  // An exception naming a preview that now passes, or that no longer exists, is a lie about the
  // codebase — so it fails too, rather than accumulating quietly. A preview whose product is empty
  // is not evidence either way, so it does not make the exception stale.
  const stale = exceptions
    .filter((e) => {
      const data = byPreview[e.preview];
      if (data === undefined) return true;
      if (productIsEmpty(product, data)) return false;
      return verdict(data) === null;
    })
    .map((e) => e.preview);

  return { id: assertion.id, checked, failures, noData, staleExceptions: stale };
}

/** A human-readable report. A failure that does not name the observed value is half a failure. */
export function formatResult(assertion, result) {
  const lines = [];
  // A declarative assertion can say what it expected; a code one can only be named. Both still
  // name the observed value per preview, which is the half of a failure report that costs a
  // reader a round trip when it is missing.
  const expectation = assertion.require
    ? (([path, expected]) => `expected ${path} ${JSON.stringify(expected)}`)(
        Object.entries(assertion.require)[0],
      )
    : "check did not hold";
  if (result.failures.length > 0) {
    lines.push(
      `FAIL ${result.id}: ${expectation} — ` +
        `${result.failures.length} of ${result.checked} previews differ`,
    );
    for (const f of result.failures.slice(0, 10))
      lines.push(`       ${f.preview}: observed ${f.detail}`);
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

/**
 * Merge several sources' indexed products into one `{product: {preview: data}}`.
 *
 * [indexed] is `[{source, products}]` in the order the caller supplied them. A preview id that two
 * sources both contribute is a named collision, NOT a last-one-wins overwrite: if the first source
 * violated an assertion and the second passes, overwriting exits 0 on a violation that was read
 * and discarded — the exact false pass this file exists to prevent. The first contributor's data
 * is kept so the violation is still reported alongside the collision. Two modules that genuinely
 * share a preview id need namespacing at the render (`modulePreviewId`), not a merge rule here.
 */
export function mergeProducts(indexed) {
  const products = { "fonts-used": {}, "compose-semantics": {} };
  const sourceOf = { "fonts-used": {}, "compose-semantics": {} };
  const collisions = [];
  for (const { source, products: found } of indexed) {
    for (const product of Object.keys(products)) {
      for (const [id, data] of Object.entries(found?.[product] ?? {})) {
        const seen = sourceOf[product][id];
        if (seen !== undefined) {
          collisions.push(`${product} "${id}" supplied by both ${seen} and ${source}`);
          continue;
        }
        sourceOf[product][id] = source;
        products[product][id] = data;
      }
    }
  }
  return { products, collisions };
}

/**
 * Normalise what a `.json` document or a `.mjs` module exported into one `{assertions}` document.
 *
 * A module exports `assertions` (an array) — either as a named export or as the default — so the
 * code form and the declarative form reach the runner as the same shape. Anything else is a
 * structural error rather than an empty list: a module whose export name is a typo would otherwise
 * contribute nothing and read as coverage.
 */
export function asAssertionsDocument(loaded) {
  if (Array.isArray(loaded)) return { assertions: loaded };
  if (loaded && typeof loaded === "object") {
    if (Array.isArray(loaded.assertions)) return { assertions: loaded.assertions };
    if (Array.isArray(loaded.default)) return { assertions: loaded.default };
    if (Array.isArray(loaded.default?.assertions)) return { assertions: loaded.default.assertions };
  }
  return { assertions: undefined };
}
