/**
 * Bundle preview-list helpers for the catalog export.
 *
 * Kept in its own module (not inline in `generate-design-catalog.mjs`) so it can be unit-tested: the
 * generate script runs its whole pipeline at import (top-level `await`), so importing it from a test
 * would execute the CLI.
 */

/**
 * Build the bundle view handed to `@design-parity/candidate`'s `bundleToCandidates`, keeping only
 * previews that carry a static `previews/<id>.png` raster. Returns `{ bundle, dropped }` where
 * `bundle` is a shallow clone with a filtered `previews` array (the original is left untouched) and
 * `dropped` is the ids removed.
 *
 * `bundleToCandidates` represents every preview as a PNG sticker and throws `InvalidBundleError` on a
 * missing `previews/<id>.png`, so a preview whose only artifact is non-raster would fail the whole
 * catalog export. Two kinds of preview have no PNG:
 *   - animated captures — a `@ScrollingPreview` `ScrollMode.GIF` preview emits only
 *     `previews/<id>.gif` (e.g. the wear catalog's `CardScalingScrollGif`); it isn't an importable
 *     static sticker, so it's simply excluded from the candidate join.
 *   - catalog-token sheets — `@ColorCatalog` / `@TypographyCatalog` / `@ThemeCatalog` metadata
 *     previews are rendered PNG-less by design, but their `previews/<id>.catalog.json` sidecars are
 *     read separately via `catalogTokensFromBundle(bundle)` to export `themeTokens`.
 *   - previews that simply produce no still — an `AndroidView`-hosted composable, a horologist
 *     `ScalingLazyColumn` screen. Nothing in the annotations marks these, so a spec that catalogues
 *     one declares it with `"capture": "none"` (see capture-mode.mjs / issue #2946); they are
 *     dropped here like the rest, but don't count as a missing render.
 *
 * Because this returns a **clone** and never mutates `bundle.previews`, those catalog-token sheets
 * stay in the original bundle for the token pass (and layout wireframes / fonts manifest, which
 * iterate the full list) — only the candidate join sees the filtered view.
 */
/**
 * `functionName -> [daemon preview id, …]` across every bundle, in bundle order. Falsy entries are
 * skipped so callers can pass `[bundle, extraBundle]` with no supplementary render; the first
 * bundle to claim an id wins, matching the primary-is-authoritative fold everywhere else.
 *
 * The live lane for a DEFERRED catalog entry (issue #2950) keys off this: its preview was never
 * rasterised, so it has no `image.previewId` to bridge from — but it is still listed in the
 * bundle's `previews.json`, because the bundle task carries every selected preview and simply omits
 * the PNG for one that didn't render. That listing is what makes a deferred entry addressable on
 * the serve host instead of lost.
 */
export function daemonPreviewIdsByFunction(bundles) {
  const out = new Map();
  const seen = new Set();
  for (const bundle of Array.isArray(bundles) ? bundles : [bundles]) {
    if (!bundle) continue;
    for (const preview of bundle.previews ?? []) {
      if (seen.has(preview.id)) continue;
      seen.add(preview.id);
      const fn = preview.functionName ?? preview.id;
      out.set(fn, [...(out.get(fn) ?? []), preview.id]);
    }
  }
  return out;
}

export function candidatePreviewBundle(bundle) {
  const dropped = [];
  const previews = (bundle.previews ?? []).filter((preview) => {
    if (bundle.entries?.[`previews/${preview.id}.png`]) return true;
    dropped.push(preview.id);
    return false;
  });
  return { bundle: { ...bundle, previews }, dropped };
}

/** Zip directory every per-preview artifact lands under. */
const PREVIEWS_DIR = "previews/";

/**
 * The ids of previews the bundle actually **captured** — those carrying at least one
 * `previews/<id>.*` artifact of any kind.
 *
 * Deliberately "any artifact", not "a PNG": [candidatePreviewBundle] already answers the PNG
 * question, and the two are asking different things. Plenty of previews are PNG-less *by design*
 * — an animated `ScrollMode.GIF` capture emits only a `.gif`, a `@ColorCatalog` / `@ThemeCatalog`
 * sheet only a `.catalog.json`, a `"capture": "none"` entry nothing raster at all — so a PNG check
 * would call all of those a loss. What no captured preview ever lacks is *every* artifact: a pack
 * runs `--with-semantics`, so even a raster-less preview comes back with its `.semantics.json`.
 *
 * A preview an `--exclude-preview-id` pattern matched, by contrast, is skipped in **both** passes
 * (that is the whole point of the CLI applying the same exclusion list to the render and the
 * semantics capture), so it comes back with nothing at all under `previews/`. That asymmetry is
 * what makes "no artifact whatsoever" a precise signal for *excluded* rather than *PNG-less*, and
 * it is what `verify-shard-renders.mjs` compares the shard plans against after a sharded merge.
 *
 * A render **failure** counts as captured, deliberately: a preview that blew up leaves
 * `previews/<id>.error.json` and no PNG, and it is not what this signal is looking for. An excluded
 * preview never reaches the renderer, so it can never carry an error sidecar — meaning the sidecar
 * cannot mask an exclusion loss, only cause a misleading one. Render failures have their own
 * reporting (`render-failures.mjs`, and the completeness gate); crediting them here keeps the shard
 * check from failing a run with the wrong diagnosis.
 *
 * The one hole in that reasoning is that semantics capture is **best-effort** — a missing daemon
 * descriptor, a failed session open, or an empty capture leaves `packSemanticsBlob` returning null
 * and the pack succeeding anyway — so a bundle can carry no `.semantics.json` at all. A raster-less
 * preview then really does have zero artifacts for a reason that has nothing to do with sharding.
 * [bundleCapturedSemantics] is how the caller detects that and declines to judge; see
 * `verifyShardRenders`.
 *
 * Ids are returned in the caller's namespace: bundle entries are stored under filename-safe ids
 * while shard plans and `design-map.json` are authored against the canonical discovery id, so
 * [rawIdFor] maps one to the other (`rawPreviewIdForEntry` from `@design-parity/candidate`). It
 * defaults to the entry id, which is correct whenever no sanitising happened.
 *
 * @param {{previews?: Array<{id: string}>, entries?: Record<string, unknown>}} bundle
 * @param {(bundle: object, entry: object) => string} [rawIdFor]
 * @returns {Set<string>} the captured ids.
 */
export function capturedPreviewIds(bundle, rawIdFor = (_bundle, entry) => entry.id) {
  const byEntryId = new Map((bundle?.previews ?? []).map((p) => [p.id, p]));
  const captured = new Set();
  for (const path of Object.keys(bundle?.entries ?? {})) {
    if (!path.startsWith(PREVIEWS_DIR)) continue;
    const rest = path.slice(PREVIEWS_DIR.length);
    // An artifact is `<id>.<ext>`, and neither half is dot-free in general (`Foo.semantics.json`,
    // and an id may carry a dot of its own), so walk every dot and keep the LONGEST declared id
    // that fits. Taking the first match instead would be wrong exactly where ids nest: with both
    // `pkg.Screen` and `pkg.Screen.Dark` declared, `previews/pkg.Screen.Dark.png` would be credited
    // to `pkg.Screen` and the longer preview — fully rendered — would be reported as lost. That is
    // the same substring confusion that caused the bug this check exists to catch, so it would be a
    // poor place to repeat it.
    let longest;
    for (let dot = rest.indexOf("."); dot > 0; dot = rest.indexOf(".", dot + 1)) {
      const entry = byEntryId.get(rest.slice(0, dot));
      if (entry) longest = entry;
    }
    if (longest) captured.add(rawIdFor(bundle, longest));
  }
  return captured;
}

/**
 * True when [bundle] carries at least one `previews/<id>.semantics.json` — i.e. the semantics pass
 * actually ran and produced something.
 *
 * Load-bearing for [capturedPreviewIds]'s "any artifact" reading. `packSemanticsBlob` is documented
 * best-effort: a missing `daemon-launch.json`, a session that would not open, or a capture that
 * returned nothing all warn to stderr and leave the *already-written* bundle alone, so `bundle pack`
 * exits 0 having carried no semantics for any preview. In that state a preview with no raster of its
 * own — an animated `ScrollMode.GIF` capture, a token sheet, a `"capture": "none"` entry — has
 * genuinely zero artifacts, and "no artifact" no longer means "excluded". A caller that would
 * otherwise fail a run on that signal should check here first and decline to judge instead.
 *
 * @param {{entries?: Record<string, unknown>}} bundle
 * @returns {boolean}
 */
export function bundleCapturedSemantics(bundle) {
  return Object.keys(bundle?.entries ?? {}).some(
    (path) => path.startsWith(PREVIEWS_DIR) && path.endsWith(".semantics.json"),
  );
}
