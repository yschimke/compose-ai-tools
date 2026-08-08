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

/**
 * The `port` / `land` half of the same qualifier string, or null when there is nothing to say.
 *
 * **The frame decides, not the request.** Normally the two agree: every lane rotates the frame to
 * satisfy an `orientation` override before a qualifier is built (issue #3547), so the dimensions
 * already encode it. They diverge in exactly one case — explicit `widthPx`/`heightPx` outrank the
 * rotation (PROTOCOL.md § 5), so `widthPx=1000;heightPx=200;orientation=portrait` keeps its
 * landscape frame. Trusting the request there would tell the resource framework and
 * `Configuration.orientation` a shape the bitmap does not have, which is the frame-vs-qualifier
 * contradiction the rotation work exists to remove, arriving from the other direction.
 *
 * Deriving from the dimensions is self-correcting: when the rotation did happen they already carry
 * the requested orientation, and when it was outranked they carry the truth.
 *
 * [requested] (`"port"` / `"land"`, anything else ignored) is consulted only for a **square**
 * frame, where the dimensions genuinely cannot say. A square frame with no request reports `port`,
 * which is what Android's own `Configuration` does at equal width and height.
 *
 * Shared with [previewSizeQualifiers] by every qualifier builder so a one-shot render and a
 * held-session render of the same preview cannot disagree about its orientation.
 */
fun previewOrientationQualifier(widthDp: Int, heightDp: Int, requested: String?): String? {
  if (widthDp <= 0 || heightDp <= 0) return null
  if (widthDp != heightDp) return if (widthDp > heightDp) "land" else "port"
  return when (requested) {
    "port",
    "land" -> requested
    else -> "port"
  }
}
