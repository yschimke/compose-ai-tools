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
 * What it does carry across is the catalog's own statement about the kit, when it makes one:
 * `@OverrideVariant(kitAxis = "Show avatar", kitValue = "True")` names the kit's spelling directly,
 * and those names ride on the seed into the sidecar for a resolver to prefer over its alias tables.
 * Projecting them is not translating them — this module never checks a declaration against a kit,
 * because it has no kit to check against; it only puts the author's words where the resolver can
 * read them. They were dead metadata until it did (compose-ai-tools#4086).
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
 * The mode a capture is drawn in, when a design map should prefer one: the mode design kits draw
 * their frames in. Diffing a dark render against a light reference reports the whole palette as a
 * finding, so where a light capture exists it is the one that pairs with the reference.
 */
const LIGHT_MODE = "Light";

/** The tag discovery puts in the id of an `@OverrideVariant` reseed: `…_VARIANT_<name>`. */
const VARIANT_TAG = "_VARIANT_";

/** Whether a capture is an `@OverrideVariant` reseed rather than a base capture. */
function isVariantCapture(preview) {
  return String(preview.id ?? "").includes(VARIANT_TAG);
}

/**
 * A capture's id split into the composable it captures and the mode it was drawn in.
 *
 * Discovery builds an id as `<class>.<function>[_<mode>][_VARIANT_<name>]`, where the mode segment
 * is the `@Preview` name a multipreview gives the capture — `Light` / `Dark` for a themed pair, and
 * EMPTY for an unnamed single capture. Splitting on the function name rather than pattern-matching
 * the tail is what lets a dark-first catalog be recognised at all: its ids carry no mode segment to
 * match against.
 *
 * A capture whose id does not contain its own function name is left as its own subject with an
 * empty mode — it cannot be grouped with anything, so it selects itself.
 */
export function captureIdentity(preview) {
  const head = String(preview.id ?? "").split(VARIANT_TAG)[0];
  const marker = preview.functionName ? `.${preview.functionName}` : null;
  const at = marker ? head.lastIndexOf(marker) : -1;
  if (at < 0) return { subject: head, mode: "" };
  const cut = at + marker.length;
  return { subject: head.slice(0, cut), mode: head.slice(cut).replace(/^_/, "") };
}

/**
 * The one mode of a composable's captures that pairs with its design reference, or `null` when the
 * captures do not say which that would be.
 *
 * LIGHT wins whenever it is published, which is every catalog that renders a themed pair — design
 * kits draw their frames in light mode, so a dark render diffed against a light reference reports
 * the whole palette as a finding.
 *
 * A composable that publishes exactly ONE mode pairs with that one, whatever it is. A dark-first
 * catalog — a Wear watch face is a black screen, so its component multipreview is a single dark
 * capture — names no `Light` capture anywhere, and demanding one projected it to an empty map: a
 * file that reads as "nothing here corresponds to the kit" rather than "the projector could not see
 * these", and that `--strict` cannot fire on either, since there is nothing to be strict about
 * (compose-ai-tools#4192).
 *
 * Several modes with no light among them is the case that stays unselected. Picking one would be
 * guessing which of `Dark` and `Coral` the kit drew, and pairing the wrong one diffs a whole
 * palette — so it is reported instead (`diagnostics.ambiguousMode`).
 */
function preferredMode(modes, widthByMode, baseBreakpointDp) {
  if (modes.has(LIGHT_MODE)) return LIGHT_MODE;
  if (modes.size === 1) return [...modes][0];

  // A BREAKPOINT FAN-OUT is not an ambiguous mode, and telling them apart is what this arm is for.
  //
  // A multipreview that renders one function at several screen sizes produces several captures of
  // one composable, exactly like a themed pair does — and the id segment they are told apart by is
  // the same segment. Read as modes they are unresolvable (`Light` is nowhere among
  // `wearos_small_round` / `wearos_large_round`), so every full-screen component of a Wear catalog
  // dropped out of the map the moment it gained a second size, and `--strict` failed on it.
  //
  // They are distinguishable by a fact the id does not carry: each capture names a `device`, and
  // the devices have DIFFERENT WIDTHS. A palette does not change the frame's width, so a set of
  // captures whose modes map one-to-one onto distinct device widths is a size axis rather than a
  // colour one, and one of them can be picked on the merits.
  const sized = [...modes].map((mode) => [mode, widthByMode.get(mode)]);
  const widths = sized.map(([, width]) => width);
  if (!widths.every((width) => Number.isFinite(width))) return null;
  if (new Set(widths).size !== widths.length) return null;

  // NARROWEST by default, because that is the size a kit draws: a design kit publishes its screen
  // artwork at one size and leaves adaptation to the implementation, and the narrowest is the one
  // every larger screen is an adaptation OF. `baseBreakpointDp` overrides it for a kit that draws
  // somewhere else. A named base this composable does not render falls back to the narrowest
  // rather than dropping the component: rendering a subset of the catalog's breakpoints is a
  // legitimate thing for one screen to do, and it is not a reason to publish no map row for it.
  sized.sort((a, b) => a[1] - b[1]);
  const named = Number.isFinite(baseBreakpointDp)
    ? sized.find(([, width]) => width === baseBreakpointDp)
    : null;
  return (named ?? sized[0])[0];
}

/**
 * The device width a capture was drawn at, or `null` when it names no device.
 *
 * Read from `params.device` rather than `params.widthDp` alone: a device-less preview carries no
 * width at all, and a `wrapSandbox` bound is not a screen size. Only a capture that names a device
 * is claiming to be a picture of a screen.
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
    // A VARIANT capture rides the same device as its base, so it agrees rather than conflicts —
    // but read the base's width first, since that is the one the fan-out is defined by.
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
     * The device width of a capture that is a NON-BASE breakpoint of a fan-out, or `null`.
     *
     * This is what turns the sizes the base did not take into cells rather than into silence. A
     * capture qualifies only when its subject resolved to some other mode and both that mode and
     * this one name a device width — so a `Dark` capture standing beside a chosen `Light` one,
     * which is a mode and not a size, is never mistaken for a breakpoint.
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
/**
 * Attach a kit-side declaration to the one seed it can belong to.
 *
 * `kitAxis` / `kitValue` name the design kit's own spelling for *a* knob — `content=avatar` is the
 * kit's `Show avatar=True` — and the annotation carries one pair per variant, so a variant that
 * turns two knobs gives no way to say which of them the axis names. It attaches to a lone seed;
 * with several, the declaration is reported and dropped rather than guessed at, since guessing
 * would pin the wrong axis and resolve to a confidently wrong node.
 *
 * A `null` declaration (neither field) leaves the seeds exactly as they were, which is every
 * variant written before these fields existed.
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
  // `props` names the axis; `state` is the annotation's shorthand for the one axis common enough
  // to have its own parameter. Either is a declaration, so neither is inferred —
  // `@CatalogVariant(state = "disabled")` says the state axis as plainly as
  // `props = ["state=disabled"]` would.
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

  // A cell that declares the kit's WHOLE assignment compares against that assignment, and the knob
  // seeds do not enter resolution at all.
  //
  // Not a merge, and the reason is that a resolver has to place EVERY seed it is given: one extra
  // knob seed that aliases to nothing kills the whole cell, so carrying both vectors would make a
  // fully-declared cell fail for the sake of information the declaration already supersedes. The
  // knobs still say how the render was produced — that is the renderer's business and it is
  // recorded on the preview — while `kitProps` says what it is compared against. Keeping those two
  // apart is what lets a catalog hold a better default than the kit and still compare honestly.
  //
  // Each entry is emitted as its own seed carrying both halves of the declaration, which is the
  // shape a resolver already reads per seed. The `key` is the kit's own axis name rather than a
  // knob key: there is no knob to name here, and inventing one would be a third spelling of a fact
  // that already has two.
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

  // An interaction variant seeds no knob — the renderer drives real hover, focus or press against
  // the composed node instead, so the difference lives in the harness rather than in the data.
  // A design kit models it as a value of the same `State` axis that carries Enabled and Disabled,
  // so it enters resolution as a seed of the `state` knob and reaches the kit through the alias
  // that knob already has. Without this the variant declares nothing: `seeds` is empty, an empty
  // vector matches every sibling, and the render is dropped as "names no axis".
  const interaction = overrides.interaction;
  const drivenState =
    interaction && interaction !== "None" && !seeds.some((s) => s.key === "state")
      ? { key: "state", raw: String(interaction).toLowerCase() }
      : undefined;

  // A COMPONENT's own `kitAxis` is a DEFAULT for its cells — "every variant of this one turns the
  // same kit property" — so a cell that names its own axis wins, and a cell that names only its
  // exceptional VALUE still inherits the axis, which is the case the default exists for. A default
  // that cannot be placed is silent, where the explicit form is reported: one is a blanket that
  // need not cover everything, the other is an assertion about this cell that could not be
  // honoured.
  const componentDefault = catalog?.role === "COMPONENT" ? catalog.kitAxis : undefined;
  const axis = overrides.kitAxis ?? componentDefault;
  const explicit = Boolean(overrides.kitAxis || overrides.kitValue);

  // The interaction axis is not a knob anybody seeded — the harness drives it — so it does not
  // count towards "which knob does this declaration name". A cell seeding `size=l` and pressing
  // the component still names one knob, and its declaration belongs to that one. Only an
  // interaction-only cell has the state seed as its subject.
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

export function variantSeeds(preview) {
  const catalog = preview.catalog;
  const { seeds: fold } = catalog?.role === "VARIANT" ? foldSeeds(catalog) : { seeds: [] };
  const { seeds: cell } = cellSeeds(preview.overrides, catalog);
  if (!fold.length) return cell;
  if (!cell.length) return fold;

  // BOTH: an `@OverrideVariant` cell on a `@CatalogVariant` render — the folded component's own
  // matrix. The render sits at the product of the two axes (`type=wave` AND `progress=1.0`), so it
  // declares both; taking either half alone would resolve to a sibling node and diff the wrong
  // frame. Folding a component used to cost it its whole matrix for want of this.
  //
  // The CELL wins a key collision. Both describe the same render, but the cell's value is what the
  // renderer actually seeded, and the fold's is the default it seeded over.
  //
  // Its kit AXIS survives that, though. The axis is a fact about the knob — what the kit calls the
  // thing being turned — and the collision only changes which way it is turned, so dropping the
  // fold's seed wholesale would lose the one name a resolver cannot work out for itself, silently.
  // The fold's `kitValue` does not survive: it described the value the cell has just replaced.
  const foldAxes = new Map(fold.filter((s) => s.kitAxis).map((s) => [s.key, s.kitAxis]));
  const merged = cell.map((s) =>
    !s.kitAxis && foldAxes.has(s.key) ? { ...s, kitAxis: foldAxes.get(s.key) } : s,
  );
  const seeded = new Set(cell.map((s) => s.key));
  return [...fold.filter((s) => !seeded.has(s.key)), ...merged];
}

/** The name a variant render goes by, for a report and for the design-map `state` slot. */
function variantName(preview, seeds) {
  const catalog = preview.catalog;
  const cell = preview.overrides?.name;
  if (catalog?.role === "VARIANT") {
    // Named for the FOLD's own axis, not for the merged vector — `wave`, not `wave-1.0` — so a
    // folded component's cells read as `wave-full` / `wave-quarter` under it, the same shape a
    // top-level component's cells have.
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
 * Declarations this render could not place on a seed, one entry per variant that names a kit axis
 * or value it has more than one knob to hang it on.
 *
 * Reported rather than silently dropped: somebody took the trouble to spell the kit's own name,
 * and silence would leave them believing the render compares against a node it never reached —
 * the exact failure `kitAxis` exists to remove.
 */
export function declarationMisses(preview) {
  const catalog = preview.catalog;
  const fold = catalog?.role === "VARIANT" ? foldSeeds(catalog).unattached : [];
  const cell = cellSeeds(preview.overrides, catalog).unattached;
  return [...fold, ...cell].map((miss) => ({ previewId: preview.id, ...miss }));
}

/**
 * Every variant render, grouped by the component it folds under.
 *
 * Both annotation forms are collected. The `@CatalogVariant` form was invisible to the first cut of
 * this projection, which is why a FAB size axis read as unauthored while `FabSmall`/`FabMedium`/
 * `FabLarge` sat in the catalog all along.
 */
export function variantRendersByComponent(previews, selection = selectCaptures(previews)) {
  const byComponent = new Map();
  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog) continue;

    // An `@OverrideVariant` render is a reseed of the SAME composable, so it keeps the parent's
    // COMPONENT role and is distinguished only by the `_VARIANT_` tag discovery puts in its id.
    // A `@CatalogVariant` render is its own composable, so it carries the VARIANT role and an
    // ordinary base-capture id.
    //
    // A VARIANT role with a `_VARIANT_` id is the third case, and it used to fall through both
    // tests into the `continue` below: a folded component carrying a matrix of its own. Discovery
    // emitted those renders all along — they were simply never projected, so the kit nodes they
    // sit on went uncompared, and a component could not be folded without deleting its cells.
    // Either way only the selected capture participates — one declaration per variant, in the same
    // mode its component's base reference pairs with.
    const isOverrideVariant = catalog.role === "COMPONENT" && isVariantCapture(preview);
    const isCatalogVariant = catalog.role === "VARIANT";

    // A BREAKPOINT capture is the third form, and it is not an annotation at all — it is the same
    // composable drawn on a wider screen by a multipreview. It folds under its component like any
    // other cell, seeded with the width it was drawn at, so the sizes the base did not take are
    // published rather than discarded.
    //
    // Taken BEFORE the `participates` gate, because a non-base breakpoint is by definition the
    // capture that did not participate. An `@OverrideVariant` cell of a non-base breakpoint is
    // skipped, though — that is the product of two axes and would multiply the sheet by every
    // size; the base breakpoint carries the component's matrix.
    if (!isOverrideVariant && !isCatalogVariant) {
      const widthDp = catalog.role === "COMPONENT" ? selection.breakpointOf(preview) : null;
      if (widthDp === null) continue;
      const seeds = [{ key: "breakpoint", raw: String(widthDp) }];
      const list = byComponent.get(catalog.componentId) ?? [];
      list.push({ previewId: preview.id, name: `${widthDp}dp`, seeds });
      byComponent.set(catalog.componentId, list);
      continue;
    }
    if (!selection.participates(preview)) continue;

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
  const selection = selectCaptures(previews, { baseBreakpointDp: opts.baseBreakpointDp });
  const variantRenders = variantRendersByComponent(previews, selection);

  const components = [];
  const declarations = [];
  /**
   * Whether a component reaches a design reference at all, and what it said if not.
   *
   * Read from the ANNOTATIONS, before and independently of capture selection — which is the whole
   * point. A component publishing several modes with no Light among them is `ambiguousMode`, and
   * `participates()` is false for every one of its captures; computing absence inside the capture
   * loop therefore dropped such a component out of `unmapped` / `statedAbsent` entirely and
   * reported it only as an ambiguous mode. A stated absence would then be fatal under
   * `--strict --allow-stated-absence`, which is exactly the case that flag exists to accept.
   *
   * Keyed by componentId rather than pushed per preview, because a component's absence is one fact
   * however many captures it publishes.
   */
  const unmappedIds = new Map();
  const statedAbsentIds = new Map();
  for (const preview of previews) {
    const catalog = preview.catalog;
    if (!catalog || catalog.role !== "COMPONENT" || catalog.reference) continue;
    if (isVariantCapture(preview)) continue;
    const id = catalog.componentId;
    if (catalog.noReference) statedAbsentIds.set(id, catalog.noReference);
    else if (!statedAbsentIds.has(id)) unmappedIds.set(id, true);
  }
  /** Components carrying neither a reference nor a stated reason for its absence. */
  const unmapped = [...unmappedIds.keys()].filter((id) => !statedAbsentIds.has(id));
  /**
   * Components whose reference is absent for a STATED reason. Reported apart from `unmapped`
   * because they are the opposite situation: someone looked, and what they found is that the kit
   * has nothing live to point at. Rolling the two together is what made a retired pattern read as
   * neglect.
   */
  const statedAbsent = [...statedAbsentIds].map(([componentId, reason]) => ({
    componentId,
    reason,
  }));
  /** Every component that reaches no reference, however its absence was spelled. */
  const referencelessIds = new Set([...unmapped, ...statedAbsentIds.keys()]);

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

  // Only the captures that participate: a variant declares once, and reporting its dark capture
  // beside its light one would double every line of a list that exists to be acted on.
  const unplacedDeclarations = previews
    .filter((preview) => preview.catalog && selection.participates(preview))
    .flatMap(declarationMisses);

  return {
    map: { components },
    variants: { schema: DESIGN_MAP_VARIANTS_SCHEMA, components: declarations },
    diagnostics: {
      unmapped,
      statedAbsent,
      unplacedDeclarations,
      // Composables whose captures name no mode a reference could pair with — several modes, none
      // of them light. Reported rather than guessed at: pairing `Dark` when the kit drew `Coral`
      // diffs a whole palette.
      // An ambiguous mode is only ever a problem BECAUSE a reference needs one capture to pair
      // with. A component that reaches no reference has nothing to pair, so which of its captures
      // the kit drew is not a question anyone is asking — reporting it would be noise on top of the
      // absence already reported above, and under --strict it would be a second, unfixable failure
      // for the same component.
      ambiguousMode: selection.ambiguous.filter(
        (a) => !a.componentIds.length || a.componentIds.some((id) => !referencelessIds.has(id)),
      ),
      variantRenders: declarations.reduce((n, d) => n + d.renders.length, 0),
      withSet: components.filter((c) => c.refSet).length,
    },
  };
}
