/**
 * The pure joins behind a catalog's cross-system pairing — the `matches.html` compare page, and
 * what the published manifest tells a preview server about that pairing.
 *
 * `compareWith` used to be a bare slug and could only ever name a SIBLING MODULE of the same
 * project: the driver resolved its spec at `samples/design-catalog-<slug>/catalog.spec.json` in
 * the same checkout, and baked its rendered thumbnails out of this repository's own
 * `design-artifacts/<slug>` branch. Both assumptions break the moment the two catalogs being
 * compared live in different repositories — which is the normal shape once a design system's
 * reference catalog has moved out into a repo of its own and something here wants to compare
 * against it. [normalizeCompareWith] widens the field to an object without changing what a plain
 * string has always meant.
 *
 * [primaryReferencesByComponentId] is the other half: the join that lets the compare page carry a
 * DESIGN column beside the two implementations. A published catalog's `references/index.json`
 * (`compose-preview-references/v1`, written by design-references.mjs) records the design-kit
 * artwork each rendered sticker is reproducing, keyed by serve preview id and carrying the
 * originating `componentId` in `source.attributes`. Inverting it onto componentId is what turns
 * two rows of pixels into three: kit, origin, port.
 */

/**
 * Widen a `compareWith` declaration to its object form.
 *
 * A string stays exactly what it was — a sibling system in this project, whose spec is read from
 * the neighbouring module directory and whose renders come from this repository's branches. The
 * object form adds `repo` (the sibling's repository, when it is not this one), `spec` (an explicit
 * path to its cover sheet, relative to this catalog's own spec), `designTitle` (the heading for
 * the design column) and `design: false` (opt out of that column entirely).
 *
 * @param {string|{system: string, repo?: string, spec?: string, designTitle?: string, design?: boolean}|null|undefined} compareWith
 * @returns {{system: string, repo?: string, spec?: string, designTitle?: string, design?: boolean}|null}
 */
export function normalizeCompareWith(compareWith) {
  if (!compareWith) return null;
  if (typeof compareWith === "string") return compareWith.trim() ? { system: compareWith } : null;
  if (typeof compareWith !== "object") return null;
  // A blank `system` has to be rejected as hard as a missing one. The falsy check above already
  // drops `"compareWith": ""`, and letting `{ "system": "" }` through instead of matching it would
  // resolve a sibling spec at `design-catalog-/catalog.spec.json` and fetch a `design-artifacts//`
  // URL — a pairing that is configured, invalid, and published rather than skipped.
  if (typeof compareWith.system !== "string" || compareWith.system.trim() === "") return null;
  return compareWith;
}

/**
 * What a `compareWith` declaration looks like on the PUBLISHED manifest (`catalog.json` `meta`).
 *
 * `compareWith` has been publish-time-only: `generate-design-catalog.mjs` reads it to build
 * `matches.html` and nothing carries it any further, so a preview server serving the catalog knows
 * each component's `parallel` counterpart id (it is on the wire, `PreviewData.kt`) but not WHICH
 * SYSTEM that id belongs to. Half a pairing is not resolvable: the server cannot turn `parallel`
 * into a render without the sibling's slug. Carrying the slug is what lets it (issue #4621).
 *
 * Deliberately NARROWER than [normalizeCompareWith]. Only the fields a consumer of the published
 * catalog can act on travel:
 *
 *   * `system` — the sibling's slug, which is also its path on a preview server serving both.
 *   * `repo` — carried exactly when the spec declares one, so a consumer that does not already
 *     host the sibling knows where to look. The common same-repo pairing declares none and
 *     publishes none.
 *
 * `spec` is a path in the producing checkout and means nothing to a reader of the manifest;
 * `designTitle` and `design` are `matches.html`'s presentation and belong to that page. Publishing
 * them would invite a consumer to depend on build-time layout of a repository it cannot see.
 *
 * Takes no "self repo" to diff against on purpose. The generator's own `repo` is resolved well
 * after the catalog is built (it can shell out to `git`), and hoisting it just to elide a
 * redundant `repo:` would move that side effect above the argument validation that currently
 * exits first. Echoing a declared repo is harmless; reordering process startup to avoid echoing
 * it is not worth it.
 *
 * @param {Parameters<typeof normalizeCompareWith>[0]} compareWith the raw spec field
 * @returns {{system: string, repo?: string}|null} null when there is no usable pairing
 */
export function manifestCompareWith(compareWith) {
  const normalized = normalizeCompareWith(compareWith);
  if (!normalized) return null;
  const system = normalized.system.trim();
  const repo = typeof normalized.repo === "string" ? normalized.repo.trim() : "";
  return repo ? { system, repo } : { system };
}

/**
 * Invert a `compose-preview-references/v1` manifest onto `componentId → {path, uri}`.
 *
 * Only `tier: "primary"` records are eligible. A component's secondary references document one
 * cell of its variant matrix (a disabled state, a size), and picking one of those for a
 * single-thumbnail column would show a reader the wrong picture while looking entirely correct —
 * the failure mode this filter exists to prevent. Records written before the tier field existed
 * carry no tier at all and are treated as primary, which is what they were.
 *
 * First primary wins, so a manifest listing several for one component is stable rather than
 * order-dependent on the last write.
 *
 * `previewId` is carried through as well as the raster path: it is what a caller needs to check
 * that the record still corresponds to something published, which matters when the manifest was
 * read from a branch that is about to be rewritten.
 *
 * @param {{references?: object[]}|null|undefined} manifest a fetched `references/index.json`
 * @returns {Map<string, {path: string, previewId?: string, uri?: string}>}
 */
export function primaryReferencesByComponentId(manifest) {
  const out = new Map();
  for (const reference of manifest?.references ?? []) {
    if (reference?.tier && reference.tier !== "primary") continue;
    const componentId = reference?.source?.attributes?.componentId;
    const path = reference?.raster?.path;
    if (typeof componentId !== "string" || typeof path !== "string" || path === "") continue;
    if (out.has(componentId)) continue;
    out.set(componentId, {
      path,
      ...(typeof reference.previewId === "string" ? { previewId: reference.previewId } : {}),
      ...(reference.source.uri ? { uri: reference.source.uri } : {}),
    });
  }
  return out;
}
