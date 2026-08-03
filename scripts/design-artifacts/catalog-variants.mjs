import { exportsNoSticker } from "./capture-mode.mjs";

/**
 * Fold a catalog spec component's `variants` onto its default render.
 *
 * A catalog spec component names one default `preview` plus, optionally, a list
 * of `variants` — each its own `@Preview` function, tagged by one of three axes:
 * a `state` (`pressed`, `focused`, `disabled`, `off`, `unchecked`, …), named
 * `props` (a content axis, e.g. `content: icon+label`), or a `theme`
 * (`light`/`dark`). This helper joins them: the default preview's images stay
 * first (they keep their own `state`, usually `"default"`, so the grid hero is
 * the resting component), and every variant's images are appended, **re-tagged**
 * — a `state` variant replaces `Image.state`, a `props` variant merges onto
 * `Image.props` while keeping the default state, and a `theme` variant replaces
 * `Image.theme` so a screen whose light and dark renders are two separate
 * `@Preview` functions (`FooScreen` + `FooScreenDark`) folds into one component
 * carrying both a `…__light` and a `…__dark` sticker. That pairing is what lets
 * the preview server serve the baked dark PNG for a night-mode browse instead of
 * bumping the request onto the live render daemon — the light/dark render is the
 * point of `@LightDarkPreview`, and this is its multi-function counterpart for
 * previews that already split the two themes across two functions.
 * The result is one sticker whose variants the catalog manifest gives
 * collision-free paths (`images/<id>/ideal__<state>[__theme][__size][__k-v…].png`,
 * the props segment keeping a props-only variant distinct from the default), and
 * that the single-component view can surface as secondary previews.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests
 * without an `npm ci`. Consumed by the vendored `catalogFromCandidates` join in
 * `generate-design-catalog.mjs`. Mirrors the `@design-parity/catalog-export`
 * fold so the workflow render matches the parity flow.
 *
 * @param {Array<{state?: string}>} defaultImages the default preview's images.
 * @param {{componentId: string, variants?: Array<{state?: string, props?: Record<string,unknown>, preview: string}>}} component
 *   the spec component (its `variants` drive the fold).
 * @param {Map<string, {images: Array<object>}>} byFunction rendered candidates
 *   keyed by `@Preview` function name.
 * @returns {{ideal: Array<object>, missing: string[], noSticker: string[]}} the
 *   merged image list, any variant previews that produced no render (as
 *   `"<componentId> [<label>]"` where the label is the variant's state and/or
 *   `k=v` props), so the caller's completeness gate can still refuse a
 *   half-rendered sticker, and — reported separately, NOT as missing — the
 *   render-less variants that declared `"capture": "none"` (see
 *   capture-mode.mjs).
 */
export function foldVariants(defaultImages, component, byFunction) {
  const ideal = [...defaultImages];
  const missing = [];
  const noSticker = [];
  const outputKeys = new Set();
  for (const image of defaultImages) {
    recordOutputKey(outputKeys, image, component.componentId);
  }
  for (const variant of component.variants ?? []) {
    const candidate = byFunction.get(variant.preview);
    if (!candidate || candidate.images.length === 0) {
      // A variant that declares `"capture": "none"` has no sticker to fold in by design — record it
      // so the export can say so, but keep it out of the completeness gate.
      const label = `${component.componentId} [${variantLabel(variant)}]`;
      if (exportsNoSticker(variant)) noSticker.push(label);
      else missing.push(label);
      continue;
    }
    // A single @Preview function can fan out into multiple images whose axes are promoted from
    // annotation parameters before this fold (fontScale is the first such axis). When the spec
    // explicitly names that same function as a variant, the matching image is already present in
    // `defaultImages`. Folding the whole merged candidate again would retag every image with the
    // variant axes and create duplicate output keys. Treat an already-tagged image as satisfying
    // the same-function variant; variants backed by a different function still retain the
    // authoritative re-tagging behavior below.
    if (
      candidate.images === defaultImages &&
      defaultImages.some((image) => imageHasVariantAxes(image, variant))
    ) {
      continue;
    }
    for (const image of candidate.images) {
      const tagged = { ...image };
      if (variant.state !== undefined) tagged.state = variant.state;
      if (variant.props) tagged.props = { ...image.props, ...variant.props };
      if (variant.theme !== undefined) tagged.theme = variant.theme;
      recordOutputKey(outputKeys, tagged, component.componentId);
      ideal.push(tagged);
    }
  }
  return { ideal, missing, noSticker };
}

/**
 * Whether `image` already carries EVERY axis `variant` declares — the inverse of the re-tagging
 * above. False for a variant that declares no axis at all, so an under-specified variant claims
 * nothing rather than claiming the first image it sees.
 *
 * Exported because `design-references.mjs` needs the same inverse to decide which `@Preview`
 * function produced a published sticker. Sharing it is the point: a second implementation would
 * drift from the fold — miss the `fontScale` numeric coercion, or stop at the first declared axis
 * and let one variant claim another's sticker.
 */
export function imageHasVariantAxes(image, variant) {
  let hasExplicitAxis = false;
  if (variant.state !== undefined) {
    hasExplicitAxis = true;
    if ((image.state ?? "default") !== variant.state) return false;
  }
  if (variant.theme !== undefined) {
    hasExplicitAxis = true;
    if (image.theme !== variant.theme) return false;
  }
  for (const [key, expected] of Object.entries(variant.props ?? {})) {
    hasExplicitAxis = true;
    if (!axisValueEquals(key, image.props?.[key], expected)) return false;
  }
  return hasExplicitAxis;
}

function axisValueEquals(key, actual, expected) {
  if (Object.is(actual, expected)) return true;
  if (key !== "fontScale") return false;
  const actualNumber = Number(actual);
  const expectedNumber = Number(expected);
  return (
    Number.isFinite(actualNumber) &&
    Number.isFinite(expectedNumber) &&
    actualNumber === expectedNumber
  );
}

/**
 * Refuse two images that the exporter would name from the same effective variant axes. Catching
 * this before `buildCatalog` writes either PNG prevents last-write-wins pixels paired with stale
 * manifest metadata.
 */
function recordOutputKey(seen, image, componentId) {
  const props = Object.fromEntries(
    Object.entries(image.props ?? {}).sort(([a], [b]) => a.localeCompare(b)),
  );
  const key = JSON.stringify({
    variant: image.variant ?? "ideal",
    state: image.state ?? "default",
    theme: image.theme ?? null,
    size: image.size ?? null,
    props,
  });
  if (seen.has(key)) {
    throw new Error(
      `catalog component "${componentId}" produces duplicate output axes ${key}; ` +
        "each variant must have a unique state, theme, size, or props value",
    );
  }
  seen.add(key);
}

/** A short label for a variant, for the missing-render report: its state, props and/or theme. */
export function variantLabel(variant) {
  const parts = [
    ...(variant.state ? [variant.state] : []),
    ...Object.entries(variant.props ?? {}).map(([k, v]) => `${k}=${v}`),
    ...(variant.theme ? [variant.theme] : []),
  ];
  return parts.join(", ") || variant.preview;
}
