/**
 * The `capture` axis of a catalog spec entry (component or variant).
 *
 * The export represents every catalogued entry as a static PNG sticker: the candidate join keeps
 * only previews carrying `previews/<id>.png` (see bundle-previews.mjs), and the completeness gate
 * then reports anything the spec named but the join dropped as a **missing render**, refusing to
 * publish the whole system.
 *
 * Some ordinary previews produce no static PNG, and — unlike the annotation-driven cases
 * (`@AnimatedPreview`, `@FocusedPreview(gif = true)`, `@ScrollingPreview` LONG/GIF) — nothing in the
 * source says so (issue #2946). Before this axis existed the only way to publish was to delete the
 * entry, which silently dropped real coverage from the sticker sheet.
 *
 * A caution learned the hard way while diagnosing #2957: this bucket is *not* a taxonomy of preview
 * shapes. The previews that motivated it were assumed to be "`AndroidView`-hosted" and "horologist
 * `ScalingLazyColumn`" cases, and neither guess survived reading the render log — every one of them
 * had simply **failed to render**, for three unrelated reasons (an app-owned `?attr/…` a library
 * module's host activity couldn't resolve; `hiltViewModel()` in a preview; a composable-method
 * lookup miss). A PNG-less preview is a symptom, not a diagnosis: check the render's
 * `<png>.error.json` before concluding anything about it, and prefer fixing the render over
 * declaring `capture: "none"`.
 *
 * `"capture": "none"` is the declaration: the entry stays in the spec (so the gap is recorded where
 * the inventory lives), it is excluded from the candidate join like any other PNG-less preview, but
 * it does NOT count as a missing render. `"static"` (the default) keeps the strict behaviour — a spec
 * entry that renders nothing still sinks the publish, which is the point of the gate.
 *
 * The value names the *outcome the gate tests* — this entry exports no sticker — rather than a guess
 * at the cause. Two of the three motivating cases aren't animated at all, so `"animated"` would
 * describe them wrongly, and it would burn the name a future mode that really does export a GIF
 * wants. `"none"` also reads cleanly against the default: `"static"` ⇒ one PNG, `"none"` ⇒ nothing.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`; shared by the spec validator
 * (catalog-spec.mjs), the variant fold (catalog-variants.mjs) and the spec→candidate join in
 * generate-design-catalog.mjs, so the three can't disagree about what a `capture` value means.
 */

/** Every value a spec entry's `capture` may take. */
export const CAPTURE_MODES = ["static", "none"];

/** An entry's declared capture mode; absent ⇒ `"static"`. */
export function captureMode(entry) {
  return entry?.capture ?? "static";
}

/**
 * Whether an entry declares that it exports no sticker, i.e. that it is exempt from the
 * missing-render gate.
 */
export function exportsNoSticker(entry) {
  return captureMode(entry) === "none";
}

/**
 * The `@Preview` function names a [spec] declares export no sticker (`"capture": "none"`), across
 * components and their variants.
 *
 * These are the only entries with **no render-side artifact at all**, which is what makes them worth
 * naming separately. Every other PNG-less preview still leaves something the render itself wrote — a
 * `ScrollMode.GIF` capture leaves `previews/<id>.gif`, a `@ColorCatalog` / `@ThemeCatalog` sheet
 * leaves `previews/<id>.catalog.json` — so it is visible whether or not the *semantics* pass (which
 * is best-effort, per preview) succeeded for it. A `"capture": "none"` entry has neither, so after a
 * partial semantics failure it is indistinguishable from a preview an exclusion ate.
 *
 * `verify-shard-renders.mjs` exempts them for exactly that reason: it is the one class where "no
 * artifact" cannot be read as "lost", and it is declared rather than guessed. The completeness gate
 * exempts the same entries from the missing-render check, so the two agree about what the
 * declaration buys.
 *
 * **A function is named only when EVERY entry referencing it declares `none`.** Entries are not
 * one-to-one with renders: `select` picks a single value out of a multipreview's fan-out, so two
 * entries can share one `preview` and mean different ids — one breakpoint declared sticker-less
 * while another is required. Returning the function name in that case would exempt the required
 * render too, and this list is consumed by a caller that only knows function names, so the
 * over-exemption would silently blind the shard check to a real loss. Resolving `select` against the
 * fan-out is the precise answer and belongs with the machinery that already does it; until then the
 * conservative rule is the right one, because the two ways of being wrong are not symmetric.
 * Under-exempting costs a false alarm carrying a message that names the alternative; over-exempting
 * costs exactly the silence this whole check exists to end.
 *
 * @param {{groups?: Array<object>}} spec a parsed catalog spec.
 * @returns {string[]} sorted, de-duplicated `@Preview` function names.
 */
export function noStickerPreviewNames(spec) {
  const declared = new Map();
  for (const group of Array.isArray(spec?.groups) ? spec.groups : []) {
    for (const comp of Array.isArray(group?.components) ? group.components : []) {
      for (const entry of [comp, ...(Array.isArray(comp?.variants) ? comp.variants : [])]) {
        if (typeof entry?.preview !== "string") continue;
        const allNoneSoFar = declared.get(entry.preview) ?? true;
        declared.set(entry.preview, allNoneSoFar && exportsNoSticker(entry));
      }
    }
  }
  return [...declared.entries()]
    .filter(([, allNone]) => allNone)
    .map(([preview]) => preview)
    .sort();
}
