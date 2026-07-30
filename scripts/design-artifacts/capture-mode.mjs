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
