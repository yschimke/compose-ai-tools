/**
 * Pick ONE axis value out of a multipreview's fan-out, so a catalog entry can target a single
 * breakpoint without the module splitting its `@Preview` function in two.
 *
 * A multipreview annotation (`@WearPreviewDevices`, a local `@CatalogWearBreakpoints`, …) renders
 * one function at several device sizes. The join keys candidates on function name, so all of those
 * renders fold into ONE spec entry carrying one image per size — which is right when the entry
 * means "this component, at every breakpoint we document", and wrong when the catalog wants a card
 * per breakpoint with its own id and caption. Without a selector the only way to get the latter is
 * to hand-split the Kotlin: two `@Preview` functions delegating to a shared private composable, two
 * spec entries, and the multipreview's other axes (font scales) dropped on the floor.
 *
 * `select` removes that surgery. Two entries may name the SAME `preview` as long as they select
 * different values, and each keeps its own `componentId`, caption, and sticker path:
 *
 *     { "componentId": "Home/SmallRound", "preview": "HomeListViewPreview",
 *       "select": { "size": "smallRound" }, "caption": "Home — small round." }
 *     { "componentId": "Home/LargeRound", "preview": "HomeListViewPreview",
 *       "select": { "size": "largeRound" }, "caption": "Home — large round." }
 *
 * Only the `size` axis is selectable today: it is the one axis the serve grid keeps as separate
 * cards (theme, state and props fold into one card with a switcher), so it is the only one an
 * author currently has to reach for Kotlin to control. The shape is an object rather than a bare
 * string so a second axis needs no spec migration.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its siblings `catalog-variants.mjs` / `catalog-priority.mjs`.
 */

/** The image axes a `select` may name. Anything else is a spec error, not a silent no-op. */
export const SELECT_AXES = Object.freeze(["size"]);

/**
 * The `select` an entry (component or variant) declares, or undefined when it selects nothing.
 * An empty object counts as "no selection" so `{}` behaves like an absent key rather than
 * filtering everything away.
 */
export function selectOf(entry) {
  const select = entry?.select;
  if (!select || typeof select !== "object" || Array.isArray(select)) return undefined;
  return Object.keys(select).length > 0 ? select : undefined;
}

/**
 * Keep only the images matching every axis [select] names. An image that carries NO value for a
 * selected axis never matches: a selector is a positive statement about which render is wanted, and
 * an untagged image is precisely the one whose axis couldn't be resolved (see
 * `applySpecBreakpoints` — a width no breakpoint declares leaves the candidate reader's canonical
 * size in place). Silently letting it through would hand the entry a render from the wrong device.
 */
export function selectImages(images, select) {
  const axes = selectOf({ select });
  if (!axes) return [...(images ?? [])];
  return (images ?? []).filter((image) =>
    Object.entries(axes).every(([axis, value]) => image?.[axis] === value),
  );
}

/** A short human tag for a selection — `size=largeRound` — for missing-render reports. */
export function selectLabel(select) {
  return Object.entries(select ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([axis, value]) => `${axis}=${value}`)
    .join(", ");
}

/**
 * Apply a spec component's `select` to the candidate its `preview` resolved to.
 *
 * Returns the images the entry should be built from, plus a `missing` label when the selection
 * matched nothing. That case is deliberately NOT the same report as "the preview never rendered":
 * the function did render, just not at the axis value asked for, so the message names what it DID
 * produce — turning a hunt through the module into a typo fix (or the discovery that a breakpoint
 * is undeclared, see `undeclaredBreakpointDevices`).
 *
 * With no selection the candidate's own array is returned BY IDENTITY, not copied: `foldVariants`
 * recognises a same-function variant partly by identity, and a defensive copy here would quietly
 * change the fold for every catalog that selects nothing.
 *
 * @returns {{images: Array<object>, missing: string | null}}
 */
export function selectComponentImages(component, candidate) {
  const select = selectOf(component);
  if (!select) return { images: candidate?.images ?? [], missing: null };
  const images = selectImages(candidate?.images, select);
  if (images.length > 0) return { images, missing: null };
  const detail = Object.keys(select)
    .map((axis) => `${axis} ∈ {${availableAxisValues(candidate?.images, axis).join(", ")}}`)
    .join("; ");
  return {
    images,
    missing:
      `${component.componentId} [select ${selectLabel(select)}; ` +
      `${component.preview} renders ${detail}]`,
  };
}

/**
 * The distinct values an axis actually took across [images], sorted — the "you asked for X, this
 * function renders Y and Z" half of a failed selection's error message. Untagged images are
 * reported as `<untagged>` rather than dropped, because "the function rendered two images and
 * neither carries a size" is the likeliest cause of a miss (an undeclared breakpoint width) and
 * hiding it would send the author hunting in the wrong file.
 */
export function availableAxisValues(images, axis) {
  const values = new Set(
    (images ?? []).map((image) => image?.[axis] ?? "<untagged>"),
  );
  return [...values].sort();
}
