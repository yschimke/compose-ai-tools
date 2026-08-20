/**
 * Finds motion captures that the render produced and no catalog component claims.
 *
 * ### The failure this exists for
 *
 * Motion publishing is **component-scoped**, end to end: `motionArtifactsFor` collects a function's
 * animated captures, `foldMotion` folds them onto `components[].motion[]`, and only then does
 * `catalog-motion-publish.mjs` copy bytes into `motion/` on the delivery branch. Which function a
 * component reads is `motionPreviewFor(component)` — its own `preview`, or its explicit
 * `motionPreview`.
 *
 * A recording on a function **no component names** therefore has nowhere to go. It renders (the
 * bytes are in the bundle), it is dropped at the join, and the catalog publishes with no Motion
 * section — with nothing anywhere saying so. `checkMotionCarried` cannot see it: that guard compares
 * what the join *resolved* against the written manifest, and here the join resolved nothing, so
 * there was never a declaration to go missing.
 *
 * That is not hypothetical. `wear-m3-catalog` authored five `@AnimatedPreview` captures on
 * standalone functions — deliberately, since a motion annotation on a component rides every
 * `@OverrideVariant` cell and publishes byte-identical duplicates, and a capture needs a pinned
 * canvas the wrapped stickers do not have — and every published run since dropped all five in
 * silence. `@CatalogComponent(motionPreview = …)` and the spec field of the same name are the wiring;
 * this is the check that says so when the wiring is missing.
 *
 * A finding here is a **warning, not a gate**: a module may legitimately carry a recording it has
 * not catalogued yet, and refusing to publish a whole system over it would be worse than the silence
 * it replaces. What it must not do is stay quiet.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`, like its sibling axis modules.
 */

import { motionDeclarationOf, motionPreviewFor } from "./catalog-motion.mjs";

/**
 * The `@Preview` functions whose captures no component in [groups] would collect.
 *
 * Matched off the manifest's own capture declarations rather than off bundle entry paths: a
 * capture whose file never got written is a *render* failure, reported by `render-failures.mjs`,
 * and reporting it here too would name one broken recording twice under two different diagnoses.
 * The declaration is what says "this function was authored to move".
 *
 * @param {object|Array<object>} bundles one render bundle, or the list the join reads motion from.
 * @param {Array<{components?: Array<object>}>} groups the merged inventory (annotations + spec).
 * @returns {Array<{functionName: string, kinds: string[]}>} unclaimed functions, sorted by name,
 *   each with the distinct motion kinds it declared (`interaction` / `animation`).
 */
export function unclaimedMotionPreviews(bundles, groups) {
  const list = Array.isArray(bundles) ? bundles : [bundles];
  const claimed = new Set();
  for (const group of Array.isArray(groups) ? groups : []) {
    for (const component of group?.components ?? []) {
      const name = motionPreviewFor(component);
      if (name) claimed.add(name);
    }
  }

  const kindsByFunction = new Map();
  for (const bundle of list) {
    for (const preview of bundle?.previews ?? []) {
      const functionName = preview.functionName ?? preview.id;
      if (!functionName || claimed.has(functionName)) continue;
      for (const capture of preview.captures ?? []) {
        const declaration = motionDeclarationOf(capture);
        if (!declaration) continue;
        let kinds = kindsByFunction.get(functionName);
        if (!kinds) kindsByFunction.set(functionName, (kinds = new Set()));
        kinds.add(declaration.kind);
      }
    }
  }

  return [...kindsByFunction]
    .map(([functionName, kinds]) => ({ functionName, kinds: [...kinds].sort() }))
    .sort((a, b) => a.functionName.localeCompare(b.functionName));
}

/**
 * The warning line for a set of [unclaimed] findings, or `null` when there is nothing to say.
 *
 * Split from the detector so the message is testable without a console, and so the caller stays a
 * two-liner. Names every function rather than a count: the fix is per-function (point a component's
 * `motionPreview` at it, or catalogue it), so a reader needs the names to act.
 *
 * @param {string} system the catalog system id, for the log prefix every driver line carries.
 * @param {Array<{functionName: string, kinds: string[]}>} unclaimed
 * @returns {string|null}
 */
export function unclaimedMotionWarning(system, unclaimed) {
  if (!unclaimed?.length) return null;
  const named = unclaimed.map((u) => `${u.functionName} (${u.kinds.join("+")})`).join(", ");
  return (
    `[${system}] motion: ${unclaimed.length} @Preview function(s) declare captures that no ` +
    `catalog component claims, so their bytes publish nowhere — ${named}. Motion is collected ` +
    `per component: point a component at the recording with ` +
    `@CatalogComponent(motionPreview = "<function>") or a catalog.spec.json component's ` +
    `\`motionPreview\`, or give the function its own @CatalogComponent.`
  );
}
