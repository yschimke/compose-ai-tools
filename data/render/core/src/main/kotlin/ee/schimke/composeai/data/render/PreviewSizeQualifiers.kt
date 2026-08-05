package ee.schimke.composeai.data.render

/**
 * The size half of a Robolectric resource-qualifier string for one preview, in Android's
 * [qualifier grammar order](https://developer.android.com/guide/topics/resources/providing-resources#QualifierRules):
 * `sw<n>dp` (smallest width) before `w<n>dp` (available width) before `h<n>dp` (available height).
 *
 * `sw<n>dp` is the load-bearing part. Robolectric's baseline qualifiers carry `sw320dp`, and
 * `RuntimeEnvironment.setQualifiers("+…")` applies **incrementally** — a token we don't emit keeps
 * whatever the previous state had. So a render that overrode only `w`/`h` produced an internally
 * inconsistent `Configuration`: a `device = "id:wearos_large_round"` preview reported a 227dp
 * viewport but still claimed `smallestScreenWidthDp == 320`. Composables that legitimately inspect
 * the full configuration (and `values-sw…dp/` resource selection) then disagreed with the pixels
 * they were rendering into. See issue #3309.
 *
 * `smallestScreenWidthDp` is the smallest of the two axes — the width the device would report in
 * its narrowest rotation — matching what Studio derives for the device a preview is pinned to.
 *
 * Shared by all three qualifier builders (the standalone renderer's
 * `RobolectricRenderTest.applyPreviewQualifiers`, the daemon's
 * `RenderEngine.applyPreviewQualifiers`, and `RobolectricHost`'s held-session copy) so the three
 * can't drift on it: a preview rendered one-shot and the same preview rendered in a held session
 * must land on the same `Configuration`.
 *
 * A non-positive axis is dropped — the caller has nothing to say about it, so the previous
 * qualifier state stands.
 */
fun previewSizeQualifiers(widthDp: Int, heightDp: Int): List<String> = buildList {
  listOf(widthDp, heightDp).filter { it > 0 }.minOrNull()?.let { add("sw${it}dp") }
  if (widthDp > 0) add("w${widthDp}dp")
  if (heightDp > 0) add("h${heightDp}dp")
}
