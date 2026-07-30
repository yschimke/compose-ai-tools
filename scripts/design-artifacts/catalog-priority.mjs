/**
 * Per-entry / per-axis render priority for a catalog spec (issue #2950).
 *
 * A catalog declares which entries MUST be baked into the published bundle and which may be left to
 * the preview server to produce on demand:
 *
 *   - `priority: "required"` (the default) — rendered, joined, and subject to the strict
 *     completeness gate exactly as before.
 *   - `priority: "deferred"` — recorded in `catalog.json` as live-only and NOT counted as a missing
 *     render or missing-semantics failure. The entry is still discovered (so it stays addressable on
 *     the serve host, which re-renders it from the carried live bundle / buildable source); it is
 *     simply not rasterised in CI.
 *
 * Two forms, because they buy different things — and, right now, because only one of them takes
 * effect:
 *
 *   - **per axis** — `modePriority: { "light": "required", "*": "deferred" }`. **Active today.** Bakes
 *     one sticker per component and leaves the remaining palettes to the (already interactive) theme
 *     switcher on the serve host. Measured against a nine-theme catalog this is the bigger lever,
 *     because the fan-out lives in the BASE entries rather than the variants. Degrades safely: the
 *     component keeps its untagged primary sticker in `components[]`, so the served catalog browses as
 *     before with one fewer baked palette.
 *   - **per entry** — `priority` on a component or one of its `variants`. **Recorded but not yet acted
 *     on** — see [ENTRY_DEFERRAL_SERVED]. Once the serve host can route it, a wholly-deferred entry
 *     names a `@Preview` function nothing else needs, so it can be dropped from the render itself (see
 *     [renderFilterPatterns]) — real build time, not just a smaller bundle.
 *
 * Deferral is always explicit and always opt-in: the default stays `required`, so a spec that says
 * nothing behaves exactly as it did. Deferral that TAKES EFFECT also needs a live path (a carried live
 * bundle or a buildable `source`), or the deferred entries would just be coverage silently dropped
 * from the published sheet — the driver enforces that, and `validateSpec` mirrors it when the caller
 * knows. The requirement is keyed on the *effective* priority, so an inert entry-level annotation
 * imposes nothing.
 *
 * Pure and dependency-free (node built-ins only, no `@design-parity/*`, no I/O) so it unit-tests
 * without an `npm ci`, like its sibling `catalog-variants.mjs` / `catalog-spec.mjs`.
 */

export const REQUIRED = "required";
export const DEFERRED = "deferred";

/** The priority values a spec may name. Anything else is a validation error. */
export const PRIORITIES = Object.freeze([REQUIRED, DEFERRED]);

/** Wildcard key in `modePriority`, matching every mode with no explicit entry. */
export const MODE_WILDCARD = "*";

/**
 * Whether the preview server can serve an ENTRY-level deferral yet.
 *
 * `false` until compose-ai-tools#2965 lands. `ServeCatalogStore` builds both its preview
 * registration and its catalog-id → daemon-id alias from `components[].images` alone — it doesn't
 * decode the `deferred[]` records — so a wholly-deferred entry or variant, which has no `images[]`
 * record at all, would be *absent* from `serve --catalogs` rather than rendered on demand.
 *
 * While it is `false` the declared deferral is honoured as `required`: the entry renders and bakes
 * exactly as it would without the annotation. That is deliberately the *inert* reading rather than
 * either extreme — refusing the publish would block a catalog for annotating ahead of the server,
 * and honouring the deferral would quietly drop the entry from the served sheet. A spec can be
 * authored now, causes no change in the meantime, and starts actually deferring when this flips.
 * The gap is reported by [declaredEntryDeferrals] so it can't go unnoticed.
 *
 * ONE switch drives every consumer — [effectivePriority] below, and through it the render filter,
 * the variant split and the driver's join — so the render set and the published set can never
 * disagree about which entries are baked. Flipping it to `true` is the last step of #2965.
 *
 * The AXIS form (`modePriority`) is unaffected and active today: a mode-deferred image leaves its
 * component in `components[]` with the untagged primary sticker, so the served catalog browses as
 * before with one fewer baked palette — a reduction the catalog asked for, not a missing entry.
 */
export const ENTRY_DEFERRAL_SERVED = false;

/**
 * The priority a component / variant entry **declares**, defaulting to `required`.
 * An unrecognised value also reads as `required` — validation rejects it up front, and failing
 * *closed* (bake it) is the safe reading if one ever slips through.
 *
 * This is the authored value, for validation and reporting. Everything that decides whether an
 * entry is actually rendered/baked must use [effectivePriority] instead.
 */
export function entryPriority(entry) {
  return entry?.priority === DEFERRED ? DEFERRED : REQUIRED;
}

/**
 * The priority an entry is actually treated with: its declared value once the serve host can route
 * a deferred entry, else `required` (see [ENTRY_DEFERRAL_SERVED]).
 */
export function effectivePriority(entry) {
  return ENTRY_DEFERRAL_SERVED ? entryPriority(entry) : REQUIRED;
}

/**
 * Entries and variants whose declared `priority: "deferred"` is currently being ignored because the
 * serve host can't route it yet. Empty once [ENTRY_DEFERRAL_SERVED] flips. The driver logs these so
 * a catalog author sees that the annotation is recorded but not yet acted on — a tracked gap rather
 * than a silent one.
 *
 * @returns {Array<{componentId: string, preview: string, kind: "entry"|"variant"}>}
 */
export function declaredEntryDeferrals(spec) {
  if (ENTRY_DEFERRAL_SERVED) return [];
  const out = [];
  for (const { component } of components(spec)) {
    if (entryPriority(component) === DEFERRED) {
      out.push({
        componentId: component.componentId,
        preview: component.preview,
        kind: "entry",
      });
    }
    for (const variant of component?.variants ?? []) {
      if (entryPriority(variant) === DEFERRED) {
        out.push({
          componentId: component.componentId,
          preview: variant.preview,
          kind: "variant",
        });
      }
    }
  }
  return out;
}

/**
 * The priority the spec declares for one mode (theme) of the sticker sheet, from `modePriority`:
 * an exact key wins, then the `*` wildcard, then the `required` default.
 */
export function modePriority(spec, mode) {
  const table = spec?.modePriority;
  if (!table || typeof table !== "object" || mode == null) return REQUIRED;
  const exact = table[mode];
  if (exact === DEFERRED || exact === REQUIRED) return exact;
  const wildcard = table[MODE_WILDCARD];
  return wildcard === DEFERRED ? DEFERRED : REQUIRED;
}

/**
 * The declared mode a **daemon preview id** renders in, or null when the id names none.
 *
 * The theme fan-out lives inside one `@Preview` function — a multipreview member (`@CatalogModes` →
 * `Foo_Light` / `Foo_Dark`) or one of several `@Preview` annotations — and the mode's name is the
 * only trace of
 * it on the id, appended as a trailing segment. So this reads that segment back and resolves it
 * against the modes the spec declares, which is what lets the render-side id filter (issue #2966)
 * skip a deferred palette instead of merely leaving it out of the publish.
 *
 * Matched case-insensitively (`modes: ["light"]` vs the annotation's `name = "Light"`), longest mode
 * first so `dark` can't shadow a declared `highContrastDark`, and only at a segment boundary — the
 * mode must be the whole id, follow a separator (`_`, `-`, `.`, space), or start at an upper-case
 * letter (`FooLight`). Without the boundary rule a mode named `on` would match `ButtonSwitchOn`'s
 * unrelated tail and silently defer a required render.
 */
export function modeOfPreviewId(id, modes) {
  const text = String(id ?? "");
  if (text.length === 0) return null;
  const declared = (modes ?? [])
    .filter((m) => typeof m === "string" && m.length > 0)
    .sort((a, b) => b.length - a.length);
  const lower = text.toLowerCase();
  for (const mode of declared) {
    const suffix = mode.toLowerCase();
    if (!lower.endsWith(suffix)) continue;
    const start = text.length - suffix.length;
    if (start === 0) return mode;
    const before = text[start - 1];
    const boundary = /[^A-Za-z0-9]/.test(before) || /[A-Z]/.test(text[start]);
    if (boundary) return mode;
  }
  return null;
}

/**
 * Whether a rendered image's mode is deferred. Only an image that *names* a theme can be deferred:
 * the untagged sticker is the component's primary render (the one the grid shows and the Figma
 * import carries), so `modePriority` can thin the palette fan-out without ever leaving a component
 * with no baked pixels at all.
 */
export function isImageDeferred(spec, image) {
  return modePriority(spec, image?.theme) === DEFERRED;
}

/** Every component entry in the spec, with its group, flattened. */
function components(spec) {
  return (spec?.groups ?? []).flatMap((group) =>
    (group?.components ?? []).map((component) => ({ group, component })),
  );
}

/**
 * True when the spec defers anything that actually takes effect — a mode, or (once the serve host
 * can route it) an entry or variant. Deliberately keyed on the EFFECTIVE priority: this gates the
 * live-path requirement, and demanding a live bundle for a deferral that is currently inert would
 * block a publish for no benefit.
 */
export function specDefersAnything(spec) {
  if (deferredModes(spec).length > 0) return true;
  return components(spec).some(
    ({ component }) =>
      effectivePriority(component) === DEFERRED ||
      (component?.variants ?? []).some((v) => effectivePriority(v) === DEFERRED),
  );
}

/**
 * The modes the spec defers, resolved against its declared `modes`. The `*` wildcard alone (with no
 * `modes` list to expand it over) still counts as deferring — reported as `"*"` — because the export
 * resolves it per rendered image, not from this list.
 */
export function deferredModes(spec) {
  const table = spec?.modePriority;
  if (!table || typeof table !== "object") return [];
  const declared = (spec?.modes ?? []).filter((m) => typeof m === "string");
  const named = Object.keys(table).filter((k) => k !== MODE_WILDCARD);
  const modes = [...new Set([...declared, ...named])];
  const deferred = modes.filter((mode) => modePriority(spec, mode) === DEFERRED);
  if (deferred.length === 0 && table[MODE_WILDCARD] === DEFERRED) return [MODE_WILDCARD];
  return deferred;
}

/**
 * Every `preview` the spec references, split by whether EVERY reference to it is deferred.
 *
 * The "every reference" rule is the load-bearing part: two entries may name the same `@Preview`
 * function, and skipping its render because one of them is deferred would silently break the other.
 * A function is therefore only droppable when nothing required points at it.
 *
 * @returns {{ required: string[], deferred: string[] }} sorted, unique function names.
 */
export function previewNamesByPriority(spec) {
  const required = new Set();
  const deferred = new Set();
  const note = (preview, priority) => {
    if (typeof preview !== "string" || preview.length === 0) return;
    (priority === DEFERRED ? deferred : required).add(preview);
  };
  // EFFECTIVE priority, so the render filter can never drop a preview the driver is still going to
  // bake — the failure that would leave a "required" entry with no PNG and trip the completeness
  // gate. While [ENTRY_DEFERRAL_SERVED] is false nothing is deferred, so `deferred` comes back empty
  // and [renderFilterPatterns] returns no filter at all.
  for (const { component } of components(spec)) {
    note(component?.preview, effectivePriority(component));
    for (const variant of component?.variants ?? []) {
      note(variant?.preview, effectivePriority(variant));
    }
  }
  for (const name of required) deferred.delete(name);
  return { required: [...required].sort(), deferred: [...deferred].sort() };
}

/**
 * The `--preview` / `-PcomposePreview.filter` patterns that render just the entries this catalog
 * still needs baked — i.e. the required preview functions, once at least one function is wholly
 * deferred. Empty when the spec defers no entry, which is the signal to render everything (the
 * historical behaviour, and what every catalog without `priority` gets).
 *
 * Deliberately a POSITIVE list rather than an exclusion: the renderer's filter fails fast when it
 * matches nothing, so naming what must render turns a stale spec into a loud failure instead of a
 * silently empty render. Plain names are substring-matched by `PreviewNameFilter`, so a required
 * name that is a substring of a deferred one widens the render rather than narrowing it — the
 * deferred entry may then be rasterised anyway, costing time but never correctness: the export keys
 * deferral off the spec, not off whether a PNG happens to exist.
 *
 * Mode-level deferral contributes nothing here, and can't: the fan-out it thins lives *inside* one
 * `@Preview` function (a multipreview member, or one of several `@Preview` annotations), and this
 * filter selects
 * whole functions. The mode axis is skipped by the sibling **id** filter instead — see
 * `deferred-preview-ids.mjs`, which needs the discovered ids and therefore runs after discovery
 * rather than in the build-free pre-flight (issue #2966).
 */
export function renderFilterPatterns(spec) {
  const { required, deferred } = previewNamesByPriority(spec);
  if (deferred.length === 0) return [];
  return required;
}

/**
 * Split a component's folded images into the ones to bake and the ones to leave to the serve host,
 * per `modePriority`.
 *
 * @returns {{ baked: object[], deferred: object[] }} `deferred` keeps the original image objects so
 *   the caller can record their axes (theme / state / props) in the manifest.
 */
export function splitDeferredImages(images, spec) {
  const baked = [];
  const deferred = [];
  for (const image of images ?? []) {
    (isImageDeferred(spec, image) ? deferred : baked).push(image);
  }
  return { baked, deferred };
}

/**
 * A component's variants split by priority, so the caller can fold only the required ones and record
 * the rest as live-only. Returns the component unchanged (same object) when nothing is deferred, so
 * the common path allocates nothing.
 *
 * @returns {{ component: object, deferredVariants: object[] }}
 */
export function splitDeferredVariants(component) {
  const variants = component?.variants ?? [];
  const deferredVariants = variants.filter((v) => effectivePriority(v) === DEFERRED);
  if (deferredVariants.length === 0) return { component, deferredVariants: [] };
  return {
    component: { ...component, variants: variants.filter((v) => effectivePriority(v) === REQUIRED) },
    deferredVariants,
  };
}

/**
 * The `@Preview` function behind one folded image: the component's own, unless a `variants` entry
 * accounts for the image's axes. A variant's images are re-tagged with its `state` / `props` /
 * `theme` by `foldVariants`, and that tagging is the only trace of where they came from — so this
 * reads it back. Used to name the right function on a deferred-by-mode record: pointing the serve
 * host at the component's default preview for a variant's sticker would re-render the wrong thing.
 *
 * A variant with no distinguishing axis is skipped rather than matched, because it can't be told
 * apart from the default here. `validateSpec` rejects such a variant anyway (it must carry at least
 * one of `state` / `props` / `theme`), so this only guards a spec that bypassed validation.
 */
export function previewForImage(component, image) {
  for (const variant of component?.variants ?? []) {
    if (variant.state === undefined && variant.theme === undefined && !variant.props) continue;
    if (variant.state !== undefined && variant.state !== image?.state) continue;
    if (variant.theme !== undefined && variant.theme !== image?.theme) continue;
    if (
      variant.props &&
      Object.entries(variant.props).some(([k, v]) => image?.props?.[k] !== v)
    )
      continue;
    return variant.preview;
  }
  return component?.preview;
}

/**
 * A one-line-able summary of what a spec defers, for the driver's log and the pre-flight's `--json`.
 * `entries` / `variants` count spec entries; `modes` names the deferred axis values.
 */
export function deferralPlan(spec) {
  let entries = 0;
  let variants = 0;
  for (const { component } of components(spec)) {
    if (effectivePriority(component) === DEFERRED) entries += 1;
    for (const variant of component?.variants ?? []) {
      if (effectivePriority(variant) === DEFERRED) variants += 1;
    }
  }
  const { required, deferred } = previewNamesByPriority(spec);
  return {
    entries,
    variants,
    modes: deferredModes(spec),
    deferredPreviews: deferred,
    requiredPreviews: required,
    renderFilter: renderFilterPatterns(spec),
    defersAnything: specDefersAnything(spec),
    // Declared-but-inert entry deferrals, so the pre-flight's `--json` surfaces the tracked gap
    // alongside the effective counts above.
    ignoredEntryDeferrals: declaredEntryDeferrals(spec),
  };
}
