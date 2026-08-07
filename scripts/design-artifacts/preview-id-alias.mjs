/**
 * Reconcile the two spellings of a preview id that a bundle carries.
 *
 * `bundle pack` writes every zip entry (`previews/<id>.png`, `ir/<id>.rc`, …) and both bundled
 * manifests under a *sanitised* id: the plugin's `sanitizeBundleEntryId` maps every character
 * outside `[A-Za-z0-9._-]` to `_`, so a `@Preview(name = "Extra Large Round")` is stored as
 * `…EdgeButtonSticker_Extra_Large_Round`. The raw discovery id — the one with the spaces, and the
 * one the daemon still answers to — is kept alongside it in the manifest's `rawPreviewIds`, which
 * is parallel to `previewIds`.
 *
 * The candidate reader hands back candidates keyed by the **raw** id while `previews.json` lists
 * the **sanitised** one, so any `previewById.get(candidate.previewId)` silently misses for every
 * preview whose name contains a space (or any other sanitised character). The lookup returns
 * `undefined`, the pass skips that image, and the failure surfaces far away — a Wear breakpoint
 * render keeping the generic `compact` width class instead of its declared `smallRound`, and so
 * reported as a *missing* render for a preview that rendered perfectly.
 *
 * The parallel-array mapping is exact, including the `_<n>` suffix `assignBundleEntryIds` adds when
 * two raw ids sanitise to the same form — which is why it is preferred over re-deriving the
 * sanitised form character by character. That derivation stays as the fallback for bundles packed
 * before `rawPreviewIds` existed.
 */

/**
 * The plugin's `sanitizeBundleEntryId`, mirrored. Kept in sync with
 * `gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/BundlePreviewTask.kt`.
 *
 * @param {string} id
 * @returns {string}
 */
export function sanitizeBundleEntryId(id) {
  return String(id).replace(/[^A-Za-z0-9._-]/g, "_");
}

/**
 * `rawId → bundle-entry id`, from a bundle manifest's parallel `rawPreviewIds` / `previewIds`.
 *
 * Returns an empty map when the manifest predates `rawPreviewIds` or the two arrays disagree in
 * length — a mismatched pair cannot be zipped safely, and a wrong alias is worse than none (it
 * would point a candidate at a *different* preview's params). `resolvePreviewId` still falls back
 * to the character substitution in that case.
 *
 * Only differing pairs are recorded: an id that needs no sanitising resolves by exact match.
 *
 * @param {{previewIds?: string[], rawPreviewIds?: string[]} | undefined} manifest
 * @returns {Map<string, string>}
 */
export function previewIdAliases(manifest) {
  const ids = manifest?.previewIds;
  const raw = manifest?.rawPreviewIds;
  const aliases = new Map();
  if (!Array.isArray(ids) || !Array.isArray(raw) || ids.length !== raw.length) return aliases;
  for (let i = 0; i < raw.length; i += 1) {
    if (typeof raw[i] === "string" && typeof ids[i] === "string" && raw[i] !== ids[i]) {
      aliases.set(raw[i], ids[i]);
    }
  }
  return aliases;
}

/**
 * The id [previews] is keyed by, for a candidate's (possibly raw) preview id.
 *
 * Exact match first — an id already in bundle-entry form must never be rewritten — then the
 * manifest alias, then the character substitution for bundles with no `rawPreviewIds`.
 *
 * @param {string | undefined} id
 * @param {Map<string, string> | undefined} aliases
 * @returns {string | undefined}
 */
export function resolvePreviewId(id, aliases) {
  if (typeof id !== "string") return undefined;
  return aliases?.get(id) ?? id;
}

/**
 * Look a preview up by either spelling of its id.
 *
 * A **declared alias wins over an exact match**, which looks backwards until you take the collision
 * case seriously. `assignBundleEntryIds` can bundle raw `Foo_A B` as `Foo_A_B` and raw `Foo_A_B` as
 * `Foo_A_B_1`. The second candidate's raw id is then *also* an exact key for the first candidate's
 * preview, so trying the exact lookup first hands it the wrong preview's params — silently, and
 * with every symptom this module was written to remove (a wrong breakpoint, a wrong font scale, a
 * render deduplicated against something it isn't).
 *
 * The alias is authoritative wherever it exists because candidates carry raw ids and the map is
 * built from the manifest's own raw→bundled pairing. Ids needing no sanitising are deliberately not
 * recorded, so the exact lookup still handles them — and a bundle whose manifests already use one
 * spelling has no aliases at all and resolves entirely by exact match.
 *
 * @param {Map<string, object>} previewById keyed by the ids `previews.json` carries
 * @param {string | undefined} id
 * @param {Map<string, string> | undefined} aliases
 * @returns {object | undefined}
 */
export function findPreview(previewById, id, aliases) {
  if (typeof id !== "string") return undefined;
  const aliased = aliases?.get(id);
  if (aliased !== undefined) {
    const byAlias = previewById.get(aliased);
    if (byAlias !== undefined) return byAlias;
  }
  return previewById.get(id) ?? previewById.get(sanitizeBundleEntryId(id));
}
