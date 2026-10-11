/**
 * Project a discovery manifest into a `design-map.json` — the correspondence file design-parity
 * reads to know which design node a code component is meant to look like.
 *
 * The map is derived from annotations rather than hand-maintained, so it cannot drift when a
 * preview is renamed:
 *
 *     @CatalogComponent(id = "Button/Filled", reference = "figma:<fileKey>/<nodeId>")
 *
 * It lives here, not in design-parity, because every field it reads is defined and emitted by this
 * repository's discovery.
 *
 * It does not resolve variant knobs to design nodes (that needs a kit vocabulary, which
 * `@design-parity/kit-index` owns). Variant renders are emitted as declarations in a sidecar
 * ({@link DESIGN_MAP_VARIANTS_SCHEMA}), carrying any author-stated `kitAxis`/`kitValue` for a
 * resolver to prefer. The sidecar is a separate file because the design-map schema forbids extra
 * keys.
 *
 * Pure and dependency-free so it unit-tests without `npm ci`; the I/O is `emit-design-map.mjs`.
 */

/** The sidecar `schema` string a resolver must match before reading variant declarations. */
export const DESIGN_MAP_VARIANTS_SCHEMA = "compose-preview-design-map-variants/v1";

/**
 * The mode design kits draw their frames in. A dark render diffed against a light reference reports
 * the whole palette, so a light capture is preferred when one exists.
 */
const LIGHT_MODE = "Light";

/** The tag discovery puts in the id of an `@OverrideVariant` reseed: `…_VARIANT_<name>`. */
const VARIANT_TAG = "_VARIANT_";

/**
 * The head of a capture's id with the `_VARIANT_<name>` reseed suffix removed, and whether there
 * was one — `{ head, reseed }`.
 *
 * `_VARIANT_` is a legal substring of a function name, so split at the LAST tag and require
 * `.<functionName>` to survive in the head: `…FooKt.Icon_VARIANT_Only_Light` is a base capture,
 * `…FooKt.Icon_Light_VARIANT_pressed` a reseed. A capture naming no function keeps the plain
 * reading.
 */
function splitVariantTag(preview) {
  const id = String(preview.id ?? "");
  const at = id.lastIndexOf(VARIANT_TAG);
  if (at < 0) return { head: id, reseed: false };
  const head = id.slice(0, at);
  const marker = preview.functionName ? `.${preview.functionName}` : null;
  if (marker && !head.includes(marker)) return { head: id, reseed: false };
  return { head, reseed: true };
}

/** Whether a capture is an `@OverrideVariant` reseed rather than a base capture. */
function isVariantCapture(preview) {
  return splitVariantTag(preview).reseed;
}

/**
 * A capture's id split into the composable it captures and the mode it was drawn in.
 *
 * Ids are `<class>.<function>[_<mode>][_VARIANT_<name>]`, the mode being the multipreview name
 * (empty for a single capture). Splitting on the function name lets dark-first catalogs, whose ids
 * carry no mode, be recognised. An id not containing its function name selects itself.
 */
export function captureIdentity(preview) {
  const { head } = splitVariantTag(preview);
  const marker = preview.functionName ? `.${preview.functionName}` : null;
  const at = marker ? head.lastIndexOf(marker) : -1;
  if (at < 0) return { subject: head, mode: "" };
  const cut = at + marker.length;
  return { subject: head.slice(0, cut), mode: head.slice(cut).replace(/^_/, "") };
}

/**
 * The one mode of a composable's captures that pairs with its design reference, or `null`.
 *
 * Light wins when published (kits draw in light). A single mode pairs with itself (e.g. dark-only
 * Wear catalogs). Several modes with no light is ambiguous and reported as
 * `diagnostics.ambiguousMode` rather than guessed.
 */
function preferredMode(modes, widthByMode, baseBreakpointDp) {
  if (modes.has(LIGHT_MODE)) return LIGHT_MODE;
  if (modes.size === 1) return [...modes][0];

  // A breakpoint fan-out is not an ambiguous mode: captures of one composable at several sizes use
  // the same id segment as a themed pair, but name devices of distinct widths. Modes mapping
  // one-to-one onto distinct widths are a size axis, and one can be picked.
  const sized = [...modes].map((mode) => [mode, widthByMode.get(mode)]);
  const widths = sized.map(([, width]) => width);
  if (!widths.every((width) => Number.isFinite(width))) return null;
  if (new Set(widths).size !== widths.length) return null;

  // Narrowest by default — kits draw at one size and larger screens adapt it. `baseBreakpointDp`
  // overrides; a named base this composable doesn't render falls back to the narrowest.
  sized.sort((a, b) => a[1] - b[1]);
  const named = Number.isFinite(baseBreakpointDp)
    ? sized.find(([, width]) => width === baseBreakpointDp)
    : null;
  return (named ?? sized[0])[0];
}

/**
 * The device width a capture was drawn at, or `null` when it names no device (a device-less
 * `widthDp` is a sandbox bound, not a screen size).
 */
function deviceWidthDp(preview) {
  const params = preview?.params;
  if (!params?.device) return null;
  return Number.isFinite(params.widthDp) ? params.widthDp : null;
}

/**
 * Which capture of each composable participates in the projection — one per composable, never one
 * per rendered mode, since a component maps to a single design node.
 *
 * @returns {{participates: (preview: object) => boolean, ambiguous: Array<object>}}
 */
export function selectCaptures(previews, { baseBreakpointDp } = {}) {
  const modesBySubject = new Map();
  const componentsBySubject = new Map();
  const widthsBySubject = new Map();
  for (const preview of previews) {
    if (!preview?.catalog) continue;
    const { subject, mode } = captureIdentity(preview);
    const modes = modesBySubject.get(subject) ?? new Set();
    modes.add(mode);
    modesBySubject.set(subject, modes);
    const ids = componentsBySubject.get(subject) ?? new Set();
    if (preview.catalog.componentId) ids.add(preview.catalog.componentId);
    componentsBySubject.set(subject, ids);
    const widths = widthsBySubject.get(subject) ?? new Map();
    // A variant capture shares its base's device; the base's width defines the fan-out.
    if (!widths.has(mode)) widths.set(mode, deviceWidthDp(preview));
    widthsBySubject.set(subject, widths);
  }

  const chosen = new Map();
  const ambiguous = [];
  for (const [subject, modes] of modesBySubject) {
    const widths = widthsBySubject.get(subject) ?? new Map();
    const mode = preferredMode(modes, widths, baseBreakpointDp);
    if (mode === null) {
      ambiguous.push({
        subject,
        componentIds: [...(componentsBySubject.get(subject) ?? [])].sort(),
        modes: [...modes].sort(),
      });
    } else {
      chosen.set(subject, mode);
    }
  }
  ambiguous.sort((a, b) => a.subject.localeCompare(b.subject));

  return {
    ambiguous,
    participates(preview) {
      const { subject, mode } = captureIdentity(preview);
      return chosen.has(subject) && chosen.get(subject) === mode;
    },
    /**
     * The device width of a capture that is a non-base breakpoint of a fan-out, or `null`. Requires
     * both this mode and the chosen one to name device widths, so a `Dark` beside a chosen `Light`
     * is never mistaken for a breakpoint.
     */
    breakpointOf(preview) {
      const { subject, mode } = captureIdentity(preview);
      if (!chosen.has(subject) || chosen.get(subject) === mode) return null;
      const widths = widthsBySubject.get(subject);
      const base = widths?.get(chosen.get(subject));
      const here = widths?.get(mode);
      return Number.isFinite(base) && Number.isFinite(here) ? here : null;
    },
  };
}

/** design-parity addresses a code subject as `<path>#<function>`. */
export function codeHandle(preview, { prefix = "catalog" } = {}) {
  const path = preview.sourceFile ? `${prefix}/${preview.sourceFile}` : prefix;
  return `${path}#${preview.functionName}`;
}

/**
 * The design source a reference handle names; design-parity picks its adapter from this.
 */
export function sourceForRef(ref) {
  const scheme = String(ref).split(":")[0];
  return scheme === "figma" ? "figma" : "claude-design";
}

/**
 * Attach a kit-side declaration to the one seed it can belong to.
 *
 * `kitAxis`/`kitValue` name the kit's spelling for one knob, so they attach only to a lone seed;
 * with several, the declaration is reported as unattached rather than guessed. No declaration
 * leaves the seeds unchanged.
 */
function declareKitNames(seeds, kitAxis, kitValue) {
  if (!kitAxis && !kitValue) return { seeds, unattached: [] };
  if (seeds.length !== 1) {
    return { seeds, unattached: [{ kitAxis, kitValue, seeds: seeds.map((s) => s.key) }] };
  }
  return {
    seeds: [
      {
        ...seeds[0],
        ...(kitAxis ? { kitAxis } : {}),
        ...(kitValue ? { kitValue } : {}),
      },
    ],
    unattached: [],
  };
}

function foldSeeds(catalog) {
  // `state` is shorthand for the `state` prop; both are declarations, nothing is inferred.
  const props = [...(catalog.props ?? [])];
  if (catalog.state && !props.some((p) => p.key === "state")) {
    props.push({ key: "state", value: catalog.state });
  }
  return declareKitNames(
    props.map((p) => ({ key: p.key, raw: p.value })),
    catalog.kitAxis,
    catalog.kitValue,
  );
}

function cellSeeds(overrides, catalog) {
  if (!overrides) return { seeds: [], unattached: [] };

  // A cell declaring the kit's whole assignment (`kitProps`) is compared against that alone: a
  // resolver must place every seed, so extra knob seeds would only make the cell fail. The knobs
  // stay recorded on the preview as how it was rendered. Each entry becomes a seed keyed by the
  // kit's own axis name.
  if (overrides.kitProps?.length) {
    return {
      seeds: overrides.kitProps.map((p) => ({
        key: p.key,
        raw: p.value,
        kitAxis: p.key,
        kitValue: p.value,
      })),
      unattached: [],
    };
  }

  const seeds = overrides.props?.length
    ? overrides.props.map((p) => ({ key: p.key, raw: p.value }))
    : (overrides.seeds ?? []).map((s) => ({ key: s.key, raw: s.raw }));

  // An interaction variant (hover/focus/press) seeds no knob; kits model it on the `State` axis, so
  // it enters resolution as a `state` seed. Otherwise the empty vector would match every sibling.
  const interaction = overrides.interaction;
  const drivenState =
    interaction && interaction !== "None" && !seeds.some((s) => s.key === "state")
      ? { key: "state", raw: String(interaction).toLowerCase() }
      : undefined;

  // A component's `kitAxis` is a default for its cells: a cell naming its own axis wins. An
  // unplaceable default is silent; an explicit cell declaration is reported.
  const componentDefault = catalog?.role === "COMPONENT" ? catalog.kitAxis : undefined;
  const axis = overrides.kitAxis ?? componentDefault;
  const explicit = Boolean(overrides.kitAxis || overrides.kitValue);

  // The harness-driven interaction axis doesn't count as a declarable knob unless it is the only
  // one.
  const declarable = seeds.length ? seeds : drivenState ? [drivenState] : [];
  const declared = explicit
    ? declareKitNames(declarable, axis, overrides.kitValue)
    : axis && declarable.length === 1
      ? declareKitNames(declarable, axis, undefined)
      : { seeds: declarable, unattached: [] };

  return {
    seeds: seeds.length && drivenState ? [...declared.seeds, drivenState] : declared.seeds,
    unattached: declared.unattached,
  };
}

/**
 * The knobs one variant render turns, normalised to `{ key, raw }`. Both forms name their axes:
 *
 *   `@OverrideVariant(name = "l", strings = ["size=l"])` — a reseed of the same composable: role
 *     COMPONENT, `_VARIANT_` in the id, knobs on `overrides`.
 *
 *   `@CatalogVariant(of = "Fab/Standard", props = ["size=large"])` — its own composable: role
 *     VARIANT, knobs in `catalog.props`.
 *
 * `overrides.props` (the full `@PreviewAxis` assignment, defaults included) is preferred over
 * `overrides.seeds` (non-default values only), so cells pair against kits that spell defaults.
 */
export function variantSeeds(preview) {
  const catalog = preview.catalog;
  const { seeds: fold } = catalog?.role === "VARIANT" ? foldSeeds(catalog) : { seeds: [] };
  const { seeds: cell } = cellSeeds(preview.overrides, catalog);
  if (!fold.length) return cell;
  if (!cell.length) return fold;

  // An `@OverrideVariant` cell on a `@CatalogVariant` render sits at the product of both axes, so
  // it declares both. The cell wins a key collision (it is what was actually seeded), but the
  // fold's kit axis name survives; the fold's `kitValue` does not.
  const foldAxes = new Map(fold.filter((s) => s.kitAxis).map((s) => [s.key, s.kitAxis]));
  const merged = cell.map((s) =>
    !s.kitAxis && foldAxes.has(s.key) ? { ...s, kitAxis: foldAxes.get(s.key) } : s,
  );
  const seeded = new Set(cell.map((s) => s.key));
  return [...fold.filter((s) => !seeded.has(s.key)), ...merged];
}

/**
 * How a folded variant names itself in reference diagnostics: `<parentId> [<axis>=<value> …]`.
 * Not the bare `componentId` (that's the parent's) nor [variantName] (which can collide when two
 * variants share a state); these labels are map keys, so collisions would drop records.
 */
export function variantAbsenceId(preview) {
  const parent = preview.catalog?.componentId ?? "(unnamed)";
  const axes = variantSeeds(preview)
    .map((seed) => `${seed.key}=${seed.raw}`)
    .join(" ");
  // A variant may declare `noReference` without naming any axis; fall back to its function name so
  // the reason isn't attributed to the parent.
  const label = axes || preview.overrides?.name || preview.functionName || captureIdentity(preview).subject;
  return label ? `${parent} [${label}]` : parent;
}

/** The name a variant render goes by, for a report and for the design-map `state` slot. */
function variantName(preview, seeds) {
  const catalog = preview.catalog;
  const cell = preview.overrides?.name;
  if (catalog?.role === "VARIANT") {
    // Named for the fold's own axis (`wave`, not `wave-1.0`), so cells read like a top-level
    // component's.
    const fold =
      catalog.state ??
      foldSeeds(catalog)
        .seeds.map((s) => s.raw)
        .join("-");
    return cell ? `${fold}-${cell}` : fold;
  }
  return cell ?? seeds.map((s) => `${s.key}=${s.raw}`).join(", ");
}

/**
 * Declarations this render could not place on a seed (a kit axis or value with more than one knob
 * to hang it on). Reported so an author doesn't assume a pairing that never happened.
 */
export function declarationMisses(preview) {
  // This cell declares the kit has nothing for it, so its kit names aren't meant to resolve.
  if (preview.overrides?.noReference) return [];
  const catalog = preview.catalog;
  const fold = catalog?.role === "VARIANT" ? foldSeeds(catalog).unattached : [];
  const cell = cellSeeds(preview.overrides, catalog).unattached;
  return [...fold, ...cell].map((miss) => ({ previewId: preview.id, ...miss }));
}

/**
 * The kit axis/value a component declares for a non-base breakpoint width, or `null`.
 *
 * [entries] are `@CatalogComponent.breakpointKit` strings, `"<widthDp>=<kitAxis>=<kitValue>"`,
 * split on the first two `=` only since `kitValue` may contain `=`. A malformed entry is pushed
 * onto [malformed] (reported as `diagnostics.invalidBreakpointKit`) and degrades to the bare
 * `breakpoint=<width>` seed. A well-formed entry for another width is not a fault. First match
 * wins.
 */
export function breakpointKitNames(entries, widthDp, malformed = []) {
  // Inspect every entry before returning so the malformed report doesn't depend on list order.
  let match = null;
  for (const entry of entries ?? []) {
    if (typeof entry !== "string") {
      malformed.push({ entry: String(entry), reason: "not a string" });
      continue;
    }
    const firstEq = entry.indexOf("=");
    const secondEq = firstEq < 0 ? -1 : entry.indexOf("=", firstEq + 1);
    if (secondEq < 0) {
      malformed.push({ entry, reason: "expected <widthDp>=<kitAxis>=<kitValue>" });
      continue;
    }
    const rawWidth = entry.slice(0, firstEq).trim();
    const width = Number(rawWidth);
    if (rawWidth === "" || !Number.isFinite(width)) {
      malformed.push({ entry, reason: `width '${rawWidth}' is not a number` });
      continue;
    }
    const kitAxis = entry.slice(firstEq + 1, secondEq).trim();
    const kitValue = entry.slice(secondEq + 1).trim();
    if (!kitAxis || !kitValue) {
      malformed.push({ entry, reason: "kitAxis and kitValue must both be non-empty" });
      continue;
    }
    // Well-formed but for another size: not a fault, and the common case.
    if (width !== widthDp) continue;
    match ??= { kitAxis, kitValue };
  }
  return match;
}

/** Every variant render (both annotation forms), grouped by the component it folds under. */
export function variantRendersByComponent(
  previews,
  selection = selectCaptures(previews),
  invalidBreakpointKit = [],
) {
  const byComponent = new Map();
  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog) continue;

    // `@OverrideVariant`: same composable, COMPONENT role, `_VARIANT_` id. `@CatalogVariant`: its
    // own composable, VARIANT role. A VARIANT role with a `_VARIANT_` id is a folded component's
    // own matrix. Only the selected capture participates.
    const isOverrideVariant = catalog.role === "COMPONENT" && isVariantCapture(preview);
    const isCatalogVariant = catalog.role === "VARIANT";

    // A breakpoint capture (same composable on a wider screen) folds under its component seeded
    // with its width. Taken before the `participates` gate, since non-base breakpoints never
    // participate. `@OverrideVariant` cells of non-base breakpoints are skipped to avoid
    // multiplying the matrix.
    if (!isOverrideVariant && !isCatalogVariant) {
      const widthDp = catalog.role === "COMPONENT" ? selection.breakpointOf(preview) : null;
      if (widthDp === null) continue;
      // `breakpointKit` says what this size means to the kit; without it the seed is a bare
      // `breakpoint=225` that no kit vocabulary contains. Opt-in, since most kits have no size
      // axis.
      const malformed = [];
      const kit = breakpointKitNames(catalog.breakpointKit, widthDp, malformed);
      for (const bad of malformed) {
        invalidBreakpointKit.push({ componentId: catalog.componentId, ...bad });
      }
      const seeds = [
        {
          key: "breakpoint",
          raw: String(widthDp),
          ...(kit?.kitAxis ? { kitAxis: kit.kitAxis } : {}),
          ...(kit?.kitValue ? { kitValue: kit.kitValue } : {}),
        },
      ];
      const list = byComponent.get(catalog.componentId) ?? [];
      list.push({ previewId: preview.id, name: `${widthDp}dp`, seeds });
      byComponent.set(catalog.componentId, list);
      continue;
    }
    if (!selection.participates(preview)) continue;

    // A variant naming no axis can't be looked up, so it is dropped unless it states the kit
    // publishes nothing for it, which still belongs in the sidecar.
    const seeds = variantSeeds(preview);
    if (!seeds.length && !preview.overrides?.noReference) continue;

    // A `@CatalogVariant` may state its own kit correspondence. `noReference` means there is
    // nothing to compare against, so it is dropped here (already reported as a stated absence)
    // rather than scored against the parent.
    if (isCatalogVariant && catalog.noReference) continue;

    const list = byComponent.get(catalog.componentId) ?? [];
    list.push({
      previewId: preview.id,
      name: variantName(preview, seeds),
      seeds,
      // An authored finding about this cell: a resolver must preserve it and skip node lookup.
      ...(isOverrideVariant && preview.overrides?.noReference
        ? { noReference: preview.overrides.noReference }
        : {}),
      // The variant's own kit cell, so a resolver pairs it directly. Additive; schema string
      // unchanged.
      ...(isCatalogVariant && catalog.reference ? { reference: catalog.reference } : {}),
    });
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
  const selection = selectCaptures(previews, { baseBreakpointDp: opts.baseBreakpointDp });
  const invalidBreakpointKit = [];
  const variantRenders = variantRendersByComponent(previews, selection, invalidBreakpointKit);

  const components = [];
  const declarations = [];
  /**
   * Whether a component reaches a design reference, and what it said if not. Read from the
   * annotations independently of capture selection, so an ambiguous-mode component still lands in
   * `unmapped`/`statedAbsent`. Keyed by componentId: absence is one fact per component.
   */
  const unmappedIds = new Map();
  /**
   * Stated absences, keyed by a collision-safe, domain-tagged identity with the display label kept
   * separately. Never keyed on the label: `props = ["a=b c=d"]` renders the same as two props.
   */
  const statedAbsentIds = new Map();
  /** Capture subjects whose absence is stated — a variant is named by subject, not by component. */
  const referencelessSubjects = new Set();
  /** Component ids whose absence is stated, for the componentId-keyed ambiguity filter below. */
  const statedAbsentComponentIds = new Set();
  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog || catalog.reference) continue;
    if (isVariantCapture(preview)) continue;
    // A `@CatalogVariant`'s stated absence is reported like a component's, under the variant's own
    // label (not the parent id), keyed by capture subject.
    if (catalog.role === "VARIANT") {
      if (!catalog.noReference) continue; // silence under a parent is the parent's business
      const subject = captureIdentity(preview).subject;
      statedAbsentIds.set(`subject:${subject}`, {
        label: variantAbsenceId(preview),
        reason: catalog.noReference,
      });
      // The ambiguity filter matches on componentId (the parent's, for a VARIANT), so record the
      // variant's own subject instead.
      referencelessSubjects.add(subject);
      continue;
    }
    if (catalog.role !== "COMPONENT") continue;
    const id = catalog.componentId;
    if (catalog.noReference) {
      statedAbsentIds.set(`component:${id}`, { label: id, reason: catalog.noReference });
      statedAbsentComponentIds.add(id);
      // Unconditional: [unmapped] below filters through [statedAbsentComponentIds], since a stated
      // absence may be read from a later capture.
    } else unmappedIds.set(id, true);
  }
  /** Components carrying neither a reference nor a stated reason for its absence. */
  const unmapped = [...unmappedIds.keys()].filter((id) => !statedAbsentComponentIds.has(id));
  /**
   * Components whose reference is absent for a stated reason — kept apart from `unmapped`, since
   * someone looked and the kit has nothing to point at.
   */
  const statedAbsent = [...statedAbsentIds.values()].map(({ label, reason }) => ({
    componentId: label,
    reason,
  }));
  /**
   * Every component that reaches no reference, for the ambiguity filter. Variants are suppressed
   * through [referencelessSubjects] instead.
   */
  const referencelessIds = new Set([...unmapped, ...statedAbsentComponentIds]);

  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog || catalog.role !== "COMPONENT") continue;
    if (isVariantCapture(preview) || !selection.participates(preview)) continue;

    if (!catalog.reference) continue;

    const code = codeHandle(preview, opts);
    components.push({
      code,
      source: sourceForRef(catalog.reference),
      ref: catalog.reference,
      // The component set, when named: `ref` is what parity diffs against; `refSet` is what a
      // whole-screen import matches instances through.
      ...(catalog.referenceSet ? { refSet: catalog.referenceSet } : {}),
      // Figma normally exports only the referenced node; keep an explicit opt-out.
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

  /** Folded cells that deliberately correspond to nothing the kit published. */
  const statedAbsentCells = previews
    .filter(
      (preview) =>
        isVariantCapture(preview) &&
        preview.overrides?.noReference &&
        selection.participates(preview),
    )
    .map((preview) => ({
      componentId: variantAbsenceId(preview),
      previewId: preview.id,
      reason: preview.overrides.noReference,
    }))
    .sort((a, b) => a.componentId.localeCompare(b.componentId));

  // Only participating captures, so each variant is listed once.
  const unplacedDeclarations = previews
    .filter((preview) => preview.catalog && selection.participates(preview))
    .flatMap(declarationMisses);

  return {
    map: { components },
    variants: { schema: DESIGN_MAP_VARIANTS_SCHEMA, components: declarations },
    diagnostics: {
      unmapped,
      statedAbsent,
      // Kept apart from component absences: a folded cell is valid inventory under plain --strict.
      statedAbsentCells,
      unplacedDeclarations,
      // Composables with several modes and none light. Skipped for components with no reference,
      // where the mode question is moot and would only add an unfixable --strict failure.
      ambiguousMode: selection.ambiguous.filter(
        (a) =>
          !referencelessSubjects.has(a.subject) &&
          (!a.componentIds.length || a.componentIds.some((id) => !referencelessIds.has(id))),
      ),
      // Unparseable `breakpointKit` entries, which otherwise silently degrade to the bare seed.
      // Deduplicated: one entry is re-read per non-base capture.
      invalidBreakpointKit: [
        ...new Map(
          invalidBreakpointKit.map((e) => [`${e.componentId}\u0000${e.entry}`, e]),
        ).values(),
      ],
      variantRenders: declarations.reduce((n, d) => n + d.renders.length, 0),
      withSet: components.filter((c) => c.refSet).length,
    },
  };
}
