/**
 * Project a discovery manifest into a `design-map.json` — the correspondence file design-parity
 * reads to know which design node a code component is meant to look like.
 *
 * ## Why this is a projection and not a config file
 *
 * design-parity joins a code subject to a design reference through a `design-map.json` entry:
 * `{ code, source, ref, previewId }`. Hand-maintaining that for a catalog's worth of previews is
 * exactly the mapping-config sprawl a catalog exists to avoid — and it drifts the moment a preview
 * is renamed, silently, because the join keys on the fully-qualified preview id.
 *
 * So the map is DERIVED. Every catalogued component already carries its seed-kit handle on the
 * annotation this repo defines:
 *
 *     @CatalogComponent(id = "Button/Filled", reference = "figma:<fileKey>/<nodeId>")
 *
 * `composePreviewDiscover` writes that through to `previews.json` as `catalog.reference`, so this
 * module is a pure projection of the annotations. Keeping the ref in code is the point: a JSON map
 * keyed on preview names drifts when a preview is renamed, and fails silently when it does.
 *
 * ## Why this lives HERE and not in design-parity
 *
 * Every field it reads — `catalog.reference`, `referenceSet`, `noReference`,
 * `referenceContentsOnly`, `catalog.props`, `overrides.seeds` — is defined in this repository, by
 * `@CatalogComponent` / `@CatalogVariant` / `@OverrideVariant` and emitted by this repository's
 * discovery. Rename a field and the projection has to change in the same commit; putting the two
 * on opposite sides of a repo boundary is how a manifest reader goes quietly stale.
 *
 * ## What this deliberately does NOT do
 *
 * It does not resolve a variant knob to a design node. `size=l` is a fact about the Compose API;
 * `Size=Large` is a fact about somebody's design kit, and translating between them needs that kit's
 * published vocabulary — which this repo has no business holding, and which
 * [`@design-parity/kit-index`](https://github.com/yschimke/design-parity/tree/main/packages/kit-index)
 * does hold.
 *
 * So the variant renders come out as **declarations**, in a sidecar
 * ({@link DESIGN_MAP_VARIANTS_SCHEMA}): "this preview is the same component with these knobs
 * turned". A resolver that owns a kit index turns each into a tagged `ref`/`previewId` pair beside
 * the base one. A repo with no kit index still gets a valid map of base references, which is the
 * majority of the value and costs no design-tool credential.
 *
 * The sidecar is a separate file rather than another key on the map because the design-map schema
 * sets `additionalProperties: false` — a map carrying an extra key would fail its own validator.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its siblings `catalog-image-path.mjs` / `catalog-variants.mjs`. The I/O around it is
 * `emit-design-map.mjs`.
 */

/** The sidecar `schema` string a resolver must match before reading variant declarations. */
export const DESIGN_MAP_VARIANTS_SCHEMA = "compose-preview-design-map-variants/v1";

/**
 * The capture a component's base reference pairs with.
 *
 * One entry per component, not per rendered mode — and the LIGHT capture, because that is the mode
 * design kits draw their frames in. Diffing a dark render against a light reference reports the
 * whole palette as a finding.
 */
const LIGHT_CAPTURE = /_Light$/;

/** A light capture that is also an `@OverrideVariant` render: `…_Light_VARIANT_<name>`. */
const LIGHT_VARIANT_CAPTURE = /_Light_VARIANT_/;

/** design-parity addresses a code subject as `<path>#<function>`. */
export function codeHandle(preview, { prefix = "catalog" } = {}) {
  const path = preview.sourceFile ? `${prefix}/${preview.sourceFile}` : prefix;
  return `${path}#${preview.functionName}`;
}

/**
 * The design source a reference handle names. design-parity dispatches its adapter on this, so a
 * wrong answer picks a driver that cannot read the ref at all.
 */
export function sourceForRef(ref) {
  const scheme = String(ref).split(":")[0];
  return scheme === "figma" ? "figma" : "claude-design";
}

/**
 * The knobs one variant render turns, normalised to `{ key, raw }`.
 *
 * A variant reaches us two ways and both NAME their axis — nothing is inferred from a function
 * name:
 *
 *   `@OverrideVariant(name = "l", strings = ["size=l"])` — a reseeded render of the same
 *     composable. Arrives as role COMPONENT with `_VARIANT_` in the id and the knobs on
 *     `overrides`.
 *
 *   `@CatalogVariant(of = "Fab/Standard", props = ["size=large"])` — its own composable, because
 *     the difference is more than a knob. Arrives as role VARIANT with the knobs in `catalog.props`.
 *
 * For the first form, `overrides.props` is preferred over `overrides.seeds` when present. They are
 * not the same list: `seeds` holds only the values that differ from the composable's defaults,
 * while `props` — emitted for a `@PreviewAxis` cross product — carries the FULL axis assignment,
 * defaults included. A cell that knows its own axes pairs by construction; one described only by
 * its non-default seeds is missing the axes it happens to sit at, and a kit that spells its default
 * size explicitly in a combination cell then has nothing to match against.
 */
export function variantSeeds(preview) {
  const catalog = preview.catalog;
  if (catalog?.role === "VARIANT") {
    // `props` names the axis; `state` is the annotation's shorthand for the one axis common enough
    // to have its own parameter. Either is a declaration, so neither is inferred —
    // `@CatalogVariant(state = "disabled")` says the state axis as plainly as
    // `props = ["state=disabled"]` would.
    const props = [...(catalog.props ?? [])];
    if (catalog.state && !props.some((p) => p.key === "state")) {
      props.push({ key: "state", value: catalog.state });
    }
    return props.map((p) => ({ key: p.key, raw: p.value }));
  }

  const overrides = preview.overrides;
  if (!overrides) return [];
  if (overrides.props?.length) {
    return overrides.props.map((p) => ({ key: p.key, raw: p.value }));
  }
  return (overrides.seeds ?? []).map((s) => ({ key: s.key, raw: s.raw }));
}

/** The name a variant render goes by, for a report and for the design-map `state` slot. */
function variantName(preview, seeds) {
  const catalog = preview.catalog;
  if (catalog?.role === "VARIANT") {
    return catalog.state ?? seeds.map((s) => s.raw).join("-");
  }
  return preview.overrides?.name ?? seeds.map((s) => `${s.key}=${s.raw}`).join(", ");
}

/**
 * Every variant render, grouped by the component it folds under.
 *
 * Both annotation forms are collected. The `@CatalogVariant` form was invisible to the first cut of
 * this projection, which is why a FAB size axis read as unauthored while `FabSmall`/`FabMedium`/
 * `FabLarge` sat in the catalog all along.
 */
export function variantRendersByComponent(previews) {
  const byComponent = new Map();
  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog) continue;

    // An `@OverrideVariant` render is a reseed of the SAME composable, so it keeps the parent's
    // COMPONENT role and is distinguished only by the `_VARIANT_` tag discovery puts in its id.
    // A `@CatalogVariant` render is its own composable, so it carries the VARIANT role and an
    // ordinary light-capture id. Either way only the light capture participates.
    const isOverrideVariant =
      catalog.role === "COMPONENT" && LIGHT_VARIANT_CAPTURE.test(preview.id);
    const isCatalogVariant = catalog.role === "VARIANT" && LIGHT_CAPTURE.test(preview.id);
    if (!isOverrideVariant && !isCatalogVariant) continue;

    // A variant that names no axis says only "this is different", which is not enough to look
    // anything up in a kit. Dropped rather than guessed at from the function name.
    const seeds = variantSeeds(preview);
    if (!seeds.length) continue;

    const list = byComponent.get(catalog.componentId) ?? [];
    list.push({ previewId: preview.id, name: variantName(preview, seeds), seeds });
    byComponent.set(catalog.componentId, list);
  }
  return byComponent;
}

/**
 * Project a discovery manifest into a design map plus its unresolved variant declarations.
 *
 * @param {Array<object>} previews `previews.json`'s `previews` array.
 * @param {{prefix?: string}} [opts] `prefix` is the path segment prepended to each `sourceFile` to
 *   form the code handle — the module the previews live in, as a reviewer would name it.
 * @returns {{map: object, variants: object, diagnostics: object}} the map, the sidecar, and what
 *   was skipped and why. Nothing is thrown for a missing reference: an unmapped component is a
 *   fact to report, not a failure.
 */
export function projectDesignMap(previews, opts = {}) {
  const variantRenders = variantRendersByComponent(previews);

  const components = [];
  const declarations = [];
  /** Components carrying neither a reference nor a stated reason for its absence. */
  const unmapped = [];
  /**
   * Components whose reference is absent for a STATED reason. Reported apart from `unmapped`
   * because they are the opposite situation: someone looked, and what they found is that the kit
   * has nothing live to point at. Rolling the two together is what made a retired pattern read as
   * neglect.
   */
  const statedAbsent = [];

  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog || catalog.role !== "COMPONENT") continue;
    if (!LIGHT_CAPTURE.test(preview.id)) continue;

    if (!catalog.reference) {
      if (catalog.noReference) {
        statedAbsent.push({ componentId: catalog.componentId, reason: catalog.noReference });
      } else {
        unmapped.push(catalog.componentId);
      }
      continue;
    }

    const code = codeHandle(preview, opts);
    components.push({
      code,
      source: sourceForRef(catalog.reference),
      ref: catalog.reference,
      // The component SET, when the annotation names one. `ref` stays the one variant parity diffs
      // against; `refSet` is what a whole-screen import matches an instance through, since a screen
      // rarely uses the exact variant this sticker pictures. Absent unless the annotation says so.
      ...(catalog.referenceSet ? { refSet: catalog.referenceSet } : {}),
      // Figma normally exports only the referenced node. Preserve an explicit per-component opt-out
      // when the annotation says this reference intentionally relies on overlapping sheet content.
      ...(catalog.referenceContentsOnly === false ? { referenceContentsOnly: false } : {}),
      previewId: preview.id,
    });

    const renders = variantRenders.get(catalog.componentId) ?? [];
    if (renders.length) {
      declarations.push({
        code,
        componentId: catalog.componentId,
        reference: catalog.reference,
        basePreviewId: preview.id,
        renders,
      });
    }
  }

  components.sort((a, b) => a.code.localeCompare(b.code));
  declarations.sort((a, b) => a.code.localeCompare(b.code));
  unmapped.sort();
  statedAbsent.sort((a, b) => a.componentId.localeCompare(b.componentId));

  return {
    map: { components },
    variants: { schema: DESIGN_MAP_VARIANTS_SCHEMA, components: declarations },
    diagnostics: {
      unmapped,
      statedAbsent,
      variantRenders: declarations.reduce((n, d) => n + d.renders.length, 0),
      withSet: components.filter((c) => c.refSet).length,
    },
  };
}
