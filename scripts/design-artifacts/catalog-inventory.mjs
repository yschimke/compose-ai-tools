import { breakpointMatcher } from "./catalog-breakpoints.mjs";

// Build a catalog inventory from the annotation-derived `catalog` metadata
// discovery stamps onto each preview in `previews.json` (`PreviewInfo.catalog`,
// from @CatalogComponent / @CatalogVariant / @CatalogGroup — compose-ai-tools
// Phase 1), and merge a hand-written `catalog.spec.json` on top of it.
//
// This is the "good defaults, override with the spec" layer: the annotations are
// the source of truth for the component inventory (id, group, section, caption,
// variant tagging), living next to the `@Preview`; `catalog.spec.json` becomes an
// OPTIONAL override that only needs to carry cover-sheet fields (system / title /
// breakpoints / display / cross-system wiring) plus any per-component tweak.
//
// The output is exactly the `{ groups: [{ name, section?, components: [...] }] }`
// shape a hand-written spec uses, so it drops straight into the existing
// spec→candidate join (`catalogFromCandidates` in generate-design-catalog.mjs):
// derive the annotation inventory, `mergeCatalogGroups(annotation, committed)`,
// and feed the merged groups to the join unchanged.
//
// Pure and dependency-free (node built-ins only, no `@design-parity/*`, no I/O) so
// its unit tests run without an `npm ci`, like the sibling `catalog-variants.mjs`.

/** The `@Preview` function name a candidate is joined on (the spec's `preview`). */
function previewName(preview) {
  return preview?.functionName ?? preview?.id;
}

/**
 * Expand one annotation component across the breakpoints its function rendered at.
 *
 * A multipreview renders one function at several device sizes and the candidate join keys on
 * function name, so without this every size folds onto one component — right for a component
 * documented at whatever breakpoints it happens to render, wrong for one that wants a card per
 * breakpoint. Each expansion carries the spec `select` the export already understands, so the
 * fan-out needs nothing downstream: it produces exactly what two hand-written spec entries would.
 *
 * - **No breakpoints** (`perBreakpoint` unset, or nothing resolved) → the component unchanged.
 *   Every pre-existing annotation catalog takes this path.
 * - **One** → the component *selected* to that breakpoint, keeping its plain id. One breakpoint is
 *   one card, and suffixing it would move a published sticker's URL to say what the id already says.
 * - **Several** → one component per breakpoint, id suffixed `<id>/<size>`. The suffix is the
 *   breakpoint name verbatim — the same vocabulary `breakpoints` declares and `select` names, so
 *   there is no casing convention to get wrong in one place and not the other.
 */
function expandSizes(component, sizes) {
  if (sizes.length === 0) return [component];
  if (sizes.length === 1) return [{ ...component, select: { size: sizes[0] } }];
  return sizes.map((size) => ({
    ...component,
    componentId: `${component.componentId}/${size}`,
    select: { size },
  }));
}

/**
 * The breakpoints one `@Preview` function actually rendered at, in the catalog's declared order.
 *
 * `@CatalogComponent(perBreakpoint = true)` says *split this component per breakpoint*, not *which*
 * breakpoints — because the multipreview annotation on the same function already decides that, and
 * restating the list in the annotation would be a second source of truth that can disagree with the
 * render. So the names come from the renders: each preview record's `@Preview(device = …)` / width,
 * resolved through the same [breakpointMatcher] that tags the baked stickers.
 *
 * Ordered by the `breakpoints` table rather than by bundle order, so the fan-out is deterministic
 * and reads small→large the way the catalog declares it.
 */
function renderedBreakpoints(previews, functionName, breakpoints) {
  const matcher = breakpointMatcher(breakpoints);
  if (!matcher) return [];
  const seen = new Set();
  for (const preview of previews) {
    if (previewName(preview) !== functionName) continue;
    const captures =
      Array.isArray(preview?.captures) && preview.captures.length > 0 ? preview.captures : [{}];
    for (const capture of captures) {
      const size = matcher({ ...(preview?.params ?? {}), ...(capture?.params ?? {}) });
      if (size !== undefined) seen.add(size);
    }
  }
  return (breakpoints ?? []).map((b) => b?.size).filter((size) => seen.has(size));
}

/**
 * Parse `@CatalogComponent.related` entries into the spec's object shape.
 *
 * The annotation carries `"<system>"`, `"<system>=<componentId>"` or
 * `"<system>=<componentId>=<label>"` strings, because annotations cannot hold a `Map` — the same
 * bargain `@CatalogComponent.breakpointKit` and `@CatalogVariant.props` strike. Discovery records
 * them verbatim and never parses them, so this is the ONE parser: two parsers is how two spellings
 * come to disagree.
 *
 * Split at most three ways, so a `label` containing `=` survives intact. An empty `<componentId>`
 * is omitted rather than emitted blank, because its absence MEANS "the same id as mine" (the
 * id-parity case, which is most of them) while an empty string would read as a declared id that
 * resolves against nothing.
 *
 * An entry naming no system is unpublishable — `applyRelated` would drop it — so it is REPORTED
 * rather than dropped in silence. A malformed entry otherwise costs only its own link and never
 * the build, exactly as a malformed `breakpointKit` entry costs only its mapping.
 *
 * @param {unknown} entries the `catalog.related` list as discovery recorded it.
 * @returns {{links: Array<{system: string, componentId?: string, label?: string}>,
 *   invalid: string[]}}
 */
export function parseRelated(entries) {
  const links = [];
  const invalid = [];
  for (const entry of Array.isArray(entries) ? entries : []) {
    if (typeof entry !== "string" || entry.trim() === "") {
      // An empty or non-string entry says nothing at all, so there is nothing to report about it
      // beyond its own uselessness — but it IS reported, since writing one was an attempt to
      // declare a link.
      invalid.push(String(entry ?? ""));
      continue;
    }
    const [rawSystem = "", rawComponentId = "", ...rest] = entry.split("=");
    const system = rawSystem.trim();
    if (!system) {
      invalid.push(entry);
      continue;
    }
    const componentId = rawComponentId.trim();
    // Re-joined rather than taken as `rest[0]`: `split` has already cut a label containing `=`
    // into pieces, and the label is the last field, so everything after the second `=` is it.
    const label = rest.join("=").trim();
    links.push({
      system,
      ...(componentId ? { componentId } : {}),
      ...(label ? { label } : {}),
    });
  }
  return { links, invalid };
}

/**
 * Build a catalog-spec-shaped inventory (`{ groups, orphanVariants }`) from a
 * list of preview records carrying `catalog` identity.
 *
 * Each preview's `catalog` is the discovery-resolved [CatalogEntry]:
 * `{ role: "COMPONENT" | "VARIANT", componentId, group?, section?, caption?,
 *    reference?, referenceSet?, parallel?, state?, props?: [{ key, value }] }`. A
 * COMPONENT becomes a component entry keyed on the preview's function name; a
 * VARIANT folds under the parent component named by its `componentId`
 * (== `@CatalogVariant.of`).
 *
 * A light/dark multipreview emits several records sharing one function name and
 * one `catalog`, so components dedupe by `componentId` and variants by
 * `(parentId, function name)` — first record wins. Components and their groups
 * keep first-seen order. A variant whose parent component isn't present is
 * returned under `orphanVariants` (a spec-authoring / annotation mismatch the
 * caller can surface) rather than silently dropped.
 *
 * A component whose annotation sets `perBreakpoint` fans out into one component per breakpoint its
 * function rendered at — resolved from the previews' own device/width against [breakpoints], never
 * from a list restated in the annotation. Reported on `withoutBreakpoints` when the flag is set but
 * nothing resolves (no `breakpoints` table, or renders whose device the table doesn't name), in
 * which case the component is kept whole rather than dropped.
 *
 * @param {Array<{functionName?: string, id?: string, catalog?: object, params?: object}>} previews
 * @param {{breakpoints?: Array<{size: string, widthDp?: number, device?: string}>}} [opts]
 * @returns {{ groups: Array<object>, orphanVariants: Array<{parentId: string, preview: string}>,
 *   withoutBreakpoints: string[],
 *   invalidRelated: Array<{componentId: string, entry: string}> }}
 */
export function inventoryFromPreviews(previews, opts = {}) {
  const list = Array.isArray(previews) ? previews : [];
  const groupOrder = [];
  const groupsByName = new Map(); // name -> { name, section?, components: [] }
  const componentById = new Map(); // componentId -> component (for variant attach)
  const seenVariant = new Set(); // `${parentId}\0${preview}`
  const orphanVariants = [];
  const withoutBreakpoints = [];
  const invalidRelated = [];

  // Components first, so a variant can attach even when its parent appears later.
  for (const preview of list) {
    const cat = preview?.catalog;
    if (!cat || cat.role !== "COMPONENT") continue;
    const id = cat.componentId;
    if (!id || componentById.has(id)) continue; // dedupe (light/dark share the id)
    const groupName = cat.group || "Components";
    let group = groupsByName.get(groupName);
    if (!group) {
      group = { name: groupName, components: [] };
      if (cat.section != null) group.section = cat.section;
      groupsByName.set(groupName, group);
      groupOrder.push(groupName);
    } else if (cat.section != null && group.section === undefined) {
      group.section = cat.section;
    }
    const base = { componentId: id, preview: previewName(preview) };
    if (cat.caption != null) base.caption = cat.caption;
    if (cat.reference != null) base.reference = cat.reference;
    if (cat.referenceSet != null) base.referenceSet = cat.referenceSet;
    if (cat.noReference != null) base.noReference = cat.noReference;
    if (cat.parallel != null) base.parallel = cat.parallel;
    // Links into OTHER catalogs. Parsed here and nowhere else; see `parseRelated`.
    const related = parseRelated(cat.related);
    if (related.links.length > 0) base.related = related.links;
    for (const entry of related.invalid) invalidRelated.push({ componentId: id, entry });
    // The function the component's recordings live on, when they are not beside its sticker. Read
    // by `motionPreviewFor` in catalog-motion.mjs exactly like the spec field of the same name — a
    // spec entry still wins, via `mergeComponent`'s field merge.
    if (cat.motionPreview != null) base.motionPreview = cat.motionPreview;
    if (typeof cat.referenceContentsOnly === "boolean") {
      base.referenceContentsOnly = cat.referenceContentsOnly;
    }
    // Only a `perBreakpoint` component consults the renders at all, so an ordinary catalog does no
    // extra work and takes exactly the path it always did.
    const sizes = cat.perBreakpoint
      ? renderedBreakpoints(list, base.preview, opts.breakpoints)
      : [];
    if (cat.perBreakpoint && sizes.length === 0) withoutBreakpoints.push(id);
    for (const component of expandSizes(base, sizes)) {
      // The FIRST expansion owns the plain id for variant attachment: an `@CatalogVariant(of = …)`
      // names the parent by its annotated id, which the fan-out has suffixed, so there is no
      // suffixed id for it to name. Attaching to the first breakpoint keeps such a variant working
      // (it folds onto that component's sticker) instead of silently orphaning it.
      if (!componentById.has(id)) componentById.set(id, component);
      componentById.set(component.componentId, component);
      group.components.push(component);
    }
  }

  for (const preview of list) {
    const cat = preview?.catalog;
    if (!cat || cat.role !== "VARIANT") continue;
    const parentId = cat.componentId;
    const name = previewName(preview);
    const key = `${parentId}\0${name}`;
    if (seenVariant.has(key)) continue;
    seenVariant.add(key);
    const variant = { preview: name };
    if (cat.size) variant.select = { size: cat.size };
    if (cat.state != null) variant.state = cat.state;
    if (Array.isArray(cat.props) && cat.props.length > 0) {
      variant.props = {};
      for (const prop of cat.props) if (prop?.key != null) variant.props[prop.key] = prop.value;
    }
    if (cat.caption != null) variant.caption = cat.caption;
    // Kit correspondence travels with the variant, not with its parent. A variant is a distinct
    // render that can diverge from the kit on its own, so it keeps its own `parallel` (which is
    // what the cross-system compare page pairs on) and its own reference / noReference. Several
    // variants of one parent may legitimately name the same `parallel`: the sibling system often
    // draws one component where this one draws a family.
    if (cat.parallel != null) variant.parallel = cat.parallel;
    if (cat.reference != null) variant.reference = cat.reference;
    if (cat.referenceSet != null) variant.referenceSet = cat.referenceSet;
    if (cat.noReference != null) variant.noReference = cat.noReference;
    if (cat.referenceContentsOnly === false) variant.referenceContentsOnly = false;
    const parent = componentById.get(parentId);
    if (!parent) {
      orphanVariants.push({ parentId, preview: name });
      continue;
    }
    (parent.variants ??= []).push(variant);
  }

  const groups = groupOrder.map((name) => {
    const group = groupsByName.get(name);
    const out = { name: group.name, components: group.components };
    if (group.section !== undefined) out.section = group.section;
    return out;
  });
  return { groups, orphanVariants, withoutBreakpoints, invalidRelated };
}

/**
 * Layer a committed `catalog.spec.json`'s groups over the annotation-derived
 * defaults, component by component: the spec ALWAYS wins where it speaks.
 *
 * Precedence (`annotation default < spec override`):
 * - A componentId in both is field-merged — spec fields overlay annotation fields,
 *   so a spec entry can override just the caption (or group placement) while the
 *   annotation still supplies the `preview` join key. Its `variants` are unioned by
 *   `preview` (spec variant wins per function). The component is placed in the
 *   SPEC's group (the spec is authoritative for grouping when it names a
 *   component).
 * - A componentId only in the spec is taken verbatim (a purely hand-authored
 *   component, e.g. one whose `@Preview` predates the annotations).
 * - A componentId only in the annotations is appended, into its annotation group
 *   (matched by name, else created after the spec's groups).
 *
 * Group order: spec groups first in their declared order, then any
 * annotation-only groups. `section` is taken from whichever side defines it, spec
 * first.
 *
 * @param {Array<object>} baseGroups annotation-derived groups (from [inventoryFromPreviews]).
 * @param {Array<object>} overrideGroups the committed spec's `groups`.
 * @returns {Array<object>} merged groups, ready for the spec→candidate join.
 */
export function mergeCatalogGroups(baseGroups, overrideGroups) {
  const base = Array.isArray(baseGroups) ? baseGroups : [];
  const override = Array.isArray(overrideGroups) ? overrideGroups : [];

  const baseById = new Map();
  for (const group of base) {
    for (const component of group.components ?? []) {
      if (!baseById.has(component.componentId)) baseById.set(component.componentId, component);
    }
  }

  const covered = new Set();
  const resultGroups = [];
  const resultByName = new Map();
  const ensureGroup = (name, section) => {
    let group = resultByName.get(name);
    if (!group) {
      group = { name, components: [] };
      if (section !== undefined) group.section = section;
      resultByName.set(name, group);
      resultGroups.push(group);
    } else if (section !== undefined && group.section === undefined) {
      group.section = section;
    }
    return group;
  };

  // 1. Spec groups, in declared order — spec components win, field-merged over a
  //    same-id annotation component when present.
  for (const group of override) {
    const target = ensureGroup(group.name, group.section);
    for (const component of group.components ?? []) {
      const baseComponent = baseById.get(component.componentId);
      target.components.push(
        baseComponent ? mergeComponent(baseComponent, component) : component,
      );
      covered.add(component.componentId);
    }
  }

  // 2. Annotation-only components, in annotation order, into their group.
  for (const group of base) {
    for (const component of group.components ?? []) {
      if (covered.has(component.componentId)) continue;
      covered.add(component.componentId);
      ensureGroup(group.name, group.section).components.push(component);
    }
  }

  return resultGroups;
}

/**
 * Order groups by a spec-declared `groupOrder` (a cover-sheet list of group names), so a catalog can
 * keep its inventory in annotations yet still control the group/tab display order — which is
 * presentation config, not per-component code metadata, and which the annotation-derived order
 * (first-seen in source) otherwise can't express when a module's source order differs from the
 * intended catalog order.
 *
 * Groups named in [groupOrder] sort to the front in that order; any group NOT named keeps its
 * original relative position, after the named ones. A stable sort, so it's a no-op when `groupOrder`
 * is absent/empty or already matches — existing catalogs are unaffected.
 *
 * @param {Array<{name: string}>} groups
 * @param {string[]|undefined} groupOrder
 * @returns {Array<object>} the groups reordered.
 */
export function applyGroupOrder(groups, groupOrder) {
  const order = Array.isArray(groupOrder) ? groupOrder : [];
  if (order.length === 0) return groups;
  const rank = new Map(order.map((name, i) => [name, i]));
  return groups
    .map((group, i) => ({ group, i }))
    .sort((a, b) => {
      const ar = rank.has(a.group.name) ? rank.get(a.group.name) : Infinity;
      const br = rank.has(b.group.name) ? rank.get(b.group.name) : Infinity;
      return ar !== br ? ar - br : a.i - b.i;
    })
    .map((entry) => entry.group);
}

/** Field-merge a spec component over its annotation default (spec wins per field). */
function mergeComponent(base, override) {
  const merged = { ...base, ...override };
  // Fill a missing spec `preview` from the annotation, so a spec entry can override
  // metadata without restating the function-name join key.
  if (override.preview == null && base.preview != null) merged.preview = base.preview;
  const variants = mergeVariants(base.variants, override.variants);
  if (variants) merged.variants = variants;
  else delete merged.variants;
  return merged;
}

/** Union two variant lists by `preview`, spec winning per function, base order first. */
function mergeVariants(baseVariants, overrideVariants) {
  const base = Array.isArray(baseVariants) ? baseVariants : [];
  const override = Array.isArray(overrideVariants) ? overrideVariants : [];
  if (base.length === 0 && override.length === 0) return undefined;
  const byPreview = new Map();
  const order = [];
  for (const variant of base) {
    if (!byPreview.has(variant.preview)) order.push(variant.preview);
    byPreview.set(variant.preview, variant);
  }
  for (const variant of override) {
    if (byPreview.has(variant.preview)) {
      byPreview.set(variant.preview, { ...byPreview.get(variant.preview), ...variant });
    } else {
      order.push(variant.preview);
      byPreview.set(variant.preview, variant);
    }
  }
  return order.map((preview) => byPreview.get(preview));
}
