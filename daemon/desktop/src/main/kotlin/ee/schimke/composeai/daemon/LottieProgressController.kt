package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-static, per-preview hold of the last Lottie scrub position.
 *
 * The panel's timeline slider re-renders a `kind=LOTTIE` preview via
 * `renderNow.overrides.lottie.progress`, but `renderNow` overrides are batch-wide and one-shot — so
 * an unrelated re-render (a save-triggered render, the view-open warmup) that carries no Lottie
 * override would repaint at the authored/default progress (frame 0), losing the scrub and leaving
 * the slider out of sync with the image. This holder remembers the last progress **per preview id**
 * so [RenderEngine] re-applies it whenever a render arrives without an explicit override — keeping
 * the rendered frame (and therefore the slider) pinned at the scrubbed position across renders.
 *
 * Keyed by previewId rather than a single global so scrubbing preview A never bleeds into preview
 * B. State is intentionally JVM-lifetime: the daemon *is* the live preview session, so "until the
 * daemon restarts" is the right scope for a sticky scrub. A fresh scrub overwrites the entry; there
 * is no implicit clear (the slider always sends an explicit 0..1, so scrubbing back to frame 0 is
 * itself a remembered position).
 *
 * Threading: the daemon renders on a single render thread, but `renderNow` seeding and reads can
 * interleave with no strict ordering, so the backing map is concurrent.
 */
object LottieProgressController {
  private val byPreview = ConcurrentHashMap<String, Float>()

  /** Record the latest scrub [progress] (clamped into `0f..1f`) for [previewId]. */
  fun remember(previewId: String, progress: Float) {
    byPreview[previewId] = progress.coerceIn(0f, 1f)
  }

  /** Last remembered scrub for [previewId], or `null` if it was never scrubbed. */
  fun progressFor(previewId: String): Float? = byPreview[previewId]

  /** Drop the remembered scrub for [previewId]. */
  fun clear(previewId: String) {
    byPreview.remove(previewId)
  }

  /** Test hook — wipe every remembered scrub. */
  fun resetForTest() {
    byPreview.clear()
  }
}
