/**
 * `completeness.exemptSemantics` — the componentIds a catalog declares as expected to be
 * sticker-only, so their absent semantics tree doesn't sink the publish (issue #4117).
 *
 * Under repository-wide discovery (`--generate-fallbacks`) every preview-enabled module's previews
 * enter the catalog as fallback inventory, and that inventory includes **synthetic Activity
 * renders** — `app/MainActivity`, `wear/WearMainActivity`, … — which legitimately capture no
 * semantics: the app cold-starts with no data and no network under the renderer, so the frame is
 * near-empty and the semantics pass has nothing to walk. `withoutSemantics` counted them anyway,
 * and the completeness gate then refused to publish the whole system.
 *
 * Neither existing escape hatch reaches this:
 *
 *  - **Nothing to delete from the spec.** These entries are not declared — they arrive through
 *    discovery, so there is no `components[]` line to withhold the way a hand-authored catalog can
 *    withhold a blank preview.
 *  - **`--allow-incomplete` is too blunt.** It switches the gate off wholesale, so a genuine
 *    regression in a *declared* component stops failing the publish too — the exact trade-off
 *    `deferred` was built to avoid.
 *  - **The mode filter doesn't reach it.** `deferred-preview-ids.mjs` derives its exclusions from
 *    spec-declared deferred modes, so it can only exempt what the spec names.
 *
 * So this follows the `deferred` precedent — recorded and reported separately, deliberately kept
 * OUT of `withoutSemantics`, with the gate staying strict over everything else — rather than the
 * all-or-nothing flag. The exempt entries keep their place in `catalog.json`: their pixels are fine
 * and worth serving, it is only the semantics half of the gate they are excused from.
 *
 * Scope, deliberately narrow on two axes:
 *
 *  - **Semantics only.** A *missing render* is a different failure — no pixels at all, which is
 *    what the gate exists to catch — and stays fatal. An entry that genuinely cannot rasterise
 *    declares `"capture": "none"` instead (capture-mode.mjs).
 *  - **Opt-in per catalog.** The alternative — exempting all discovery-supplied fallback inventory
 *    by default, on the grounds that a fallback component was never promised by the catalog author
 *    — silently widens the gate's blind spot for every repository-wide catalog at once. An explicit
 *    list says which ids, in the spec, where a reviewer sees it.
 *
 * Drift is reported rather than tolerated: a pattern that matches nothing is named on every run
 * ([unusedPatterns]), so an exemption left behind by a renamed or deleted preview shows up as a
 * warning instead of quietly standing by to excuse something it was never written for.
 *
 * Pure and dependency-free (node built-ins only, no `@design-parity/*`, no I/O) so its unit tests
 * run without an `npm ci`, like its siblings.
 */

/** The declared `completeness` block, or undefined when the spec doesn't carry one. */
function completenessBlock(spec) {
  const block = spec?.completeness;
  return block && typeof block === "object" && !Array.isArray(block) ? block : undefined;
}

/**
 * The `completeness.exemptSemantics` patterns a spec declares, cleaned up: non-strings and empty
 * strings dropped, duplicates collapsed, declared order kept (the report reads back in the order
 * the spec is written).
 *
 * Shape errors are the validator's business (`validateSpec`), not this module's — here a malformed
 * value simply exempts nothing, which is the safe direction: the gate stays strict.
 *
 * @param {object} spec parsed `catalog.spec.json`.
 * @returns {string[]}
 */
export function exemptSemanticsPatterns(spec) {
  const declared = completenessBlock(spec)?.exemptSemantics;
  if (!Array.isArray(declared)) return [];
  const out = [];
  const seen = new Set();
  for (const pattern of declared) {
    if (typeof pattern !== "string") continue;
    const trimmed = pattern.trim();
    if (trimmed.length === 0 || seen.has(trimmed)) continue;
    seen.add(trimmed);
    out.push(trimmed);
  }
  return out;
}

/**
 * Whether one componentId matches one exemption pattern.
 *
 * The pattern is matched against the WHOLE id — the same `module/componentId` string the
 * "no semantics for: …" warning prints, so an id can be copied straight out of a failing run — with
 * `*` as the only metacharacter, standing for any run of characters **including `/`**. One
 * wildcard rule, no `**` vocabulary to get wrong: a leading `*` before `/MainActivity` reaches a
 * nested module's `feature/home/MainActivity` as well as `app/MainActivity`, and `*Activity`
 * covers both without knowing how deep the module path goes.
 *
 * Anchored at both ends, because the alternative (substring matching, as the CLI's preview filters
 * use) over-matches on an axis where over-matching silently excuses work nobody exempted — the same
 * hazard `anchoredExclusions` exists for in `deferred-preview-ids.mjs`. `Activity` on its own
 * therefore matches only an id named exactly that.
 */
export function matchesExemption(pattern, componentId) {
  if (typeof pattern !== "string" || typeof componentId !== "string") return false;
  const source = pattern
    .split("*")
    .map((literal) => literal.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
    .join(".*");
  return new RegExp(`^${source}$`).test(componentId);
}

/**
 * Split the semantics-less componentIds into the ones the gate still counts and the ones the spec
 * excused, and name the patterns that matched nothing.
 *
 * @param {string[]} componentIds the `withoutSemantics` list from the candidate join.
 * @param {string[]} patterns from [exemptSemanticsPatterns].
 * @returns {{ counted: string[], exempt: string[], unusedPatterns: string[] }} `counted` and
 *   `exempt` keep the input order; `unusedPatterns` keeps the spec's declared order.
 */
export function partitionExemptSemantics(componentIds, patterns) {
  const ids = Array.isArray(componentIds) ? componentIds : [];
  const list = Array.isArray(patterns) ? patterns : [];
  if (list.length === 0) return { counted: [...ids], exempt: [], unusedPatterns: [] };

  const used = new Set();
  const counted = [];
  const exempt = [];
  for (const id of ids) {
    const matched = list.filter((pattern) => matchesExemption(pattern, id));
    if (matched.length === 0) {
      counted.push(id);
      continue;
    }
    for (const pattern of matched) used.add(pattern);
    exempt.push(id);
  }
  return {
    counted,
    exempt,
    unusedPatterns: list.filter((pattern) => !used.has(pattern)),
  };
}
