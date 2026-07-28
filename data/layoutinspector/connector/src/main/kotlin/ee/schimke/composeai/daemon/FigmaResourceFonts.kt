package ee.schimke.composeai.daemon

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide map from the **captured** identity of an Android resource-backed font
 * (`res/font/<resId>` — the only stable handle `ResourceFont` exposes, see
 * `ComposeSemanticsDataProducer.fontIdentity`) to a real, readable font file the
 * `compose/figma-svg` export can embed.
 *
 * Without it a resource-backed `FontFamily` — the `FontFamily(Font(R.font.montserrat_regular, …))`
 * pattern every branded Android app uses — reached the SVG as a numeric resource id with no
 * matching `@font-face`, so browsers fell back to `sans-serif` and the vector's glyph widths, line
 * wrapping and ellipsis positions all drifted from the PNG (issue #2886). The id names nothing a
 * consumer can resolve, and the bytes only exist inside the render's resource table, so recovering
 * the face has to happen on the render side and be handed across.
 *
 * The Android render's font recorder (`FontResolverRecorder`) populates this as resolutions happen
 * — the same "publish during the render, read at post-capture" contract [FigmaSvgRenderedFonts]
 * uses, and for the same reason: the recorder and the SVG export are independent post-capture
 * extensions with no ordering guarantee between them. It lives in this module because the export
 * reads it and the recorder already depends on this one.
 *
 * Entries accumulate for the process rather than resetting per preview: a resource id maps to the
 * same bytes for the life of the render JVM, and a whole-catalog render resolves the same handful
 * of faces over and over.
 */
object FigmaResourceFonts {

  private val paths = ConcurrentHashMap<String, String>()

  /**
   * The captured identity for an Android font resource — matching what
   * `ComposeSemanticsDataProducer` writes into `typography.fontFamily` for a `ResourceFont`.
   */
  fun identityFor(resId: Int): String = "res/font/$resId"

  /**
   * Record that [identity] (a value from [identityFor]) is available on disk at [path] — an
   * absolute `.ttf`/`.otf` the export can read and subset. Later registrations for the same
   * identity win, so a re-extraction after a cleared temp dir heals the mapping.
   */
  fun register(identity: String, path: String) {
    if (identity.isBlank() || path.isBlank()) return
    paths[identity] = path
  }

  /** The registered file for [identity], or null when nothing recovered that face. */
  fun pathFor(identity: String): String? = paths[identity]

  /** Drop every registration. Tests only — production accumulates for the process's life. */
  fun clear() {
    paths.clear()
  }
}
