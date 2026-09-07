package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
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
   * Family-keyed registrations, scoped to the preview that made them.
   *
   * A `res/font/<resId>` handle names one concrete face and resolves to the same bytes for the life
   * of the render JVM, so [paths] keeps those for the process. A FAMILY does not: which file
   * `Roboto Flex` resolved to is a fact about one render, and the catalog's own documents disagree
   * about it — a typography specimen declaring `wght`/`wdth` axes resolves the family's VARIABLE
   * file, while a sticker that declares no axes resolves a static per-weight instance.
   *
   * Held process-wide, the specimen's registration outlived it and every later preview in the same
   * catalog run embedded the variable file instead of the one it drew: 1.6 MB of `gvar` for a
   * document that varies nothing, in an SVG that came to 4.4 MB. The recorder publishes during the
   * render and the export reads at post-capture, so the window is a preview — the same window
   * [FigmaSvgRenderedFonts] is already scoped to, and for the same reason.
   */
  private val previewPaths = ConcurrentHashMap<String, String>()

  /**
   * Drop the family-keyed registrations of the preview that just ended.
   *
   * Called as each preview starts composing, beside [FigmaSvgRenderedFonts.begin]. Resource-id
   * registrations are deliberately kept: those are process facts, and re-extracting them per
   * preview would cost a whole-catalog render the same work over and over for no gain.
   */
  fun beginPreview() {
    previewPaths.clear()
  }

  /**
   * The captured identity for an Android font resource — matching what
   * `ComposeSemanticsDataProducer` writes into `typography.fontFamily` for a `ResourceFont`.
   */
  fun identityFor(resId: Int): String = "res/font/$resId"

  /**
   * Record that [identity] (a value from [identityFor]) is available on disk at [path] — an
   * absolute `.ttf`/`.otf` the export can read and subset. Later registrations for the same
   * identity win, so a re-extraction after a cleared temp dir heals the mapping.
   *
   * This face-agnostic form suits a per-face identity like `res/font/<resId>`, where the handle
   * already names one concrete weight/style.
   */
  fun register(identity: String, path: String) {
    if (identity.isBlank() || path.isBlank()) return
    paths[identity] = path
  }

  /**
   * Weight/style-qualified registration, for an identity that names a **family** rather than a face
   * — a downloadable `GoogleFont("Lato")` reaches the capture as the bare family, but Lato 400 and
   * Lato 600 are different files with different metrics. Registering those under the bare name
   * would embed whichever landed last for every weight the export asks about.
   */
  fun register(identity: String, weight: Int, italic: Boolean, path: String) {
    if (identity.isBlank() || path.isBlank()) return
    previewPaths[key(identity, weight, italic)] = path
  }

  /** The registered file for [identity], or null when nothing recovered that face. */
  fun pathFor(identity: String): String? = paths[identity]

  /**
   * The registered file for a specific face: the weight/style-qualified registration when one
   * exists, else the face-agnostic one. The fallback is what keeps per-face identities
   * (`res/font/<resId>`) resolving through the same lookup.
   */
  fun pathFor(identity: String, weight: Int, italic: Boolean): String? =
    previewPaths[key(identity, weight, italic)] ?: paths[identity]

  /**
   * The CSS/Figma family declared inside one font file, or null when [bytes] are not a readable
   * font.
   *
   * This is shared by both sides of the SVG font audit: the export uses it to name `@font-face`,
   * and the Android render recorder uses it to state which family a file-backed Compose font
   * actually drew. Reading those identities through different mechanisms let an embedded Remote
   * Compose text run be recorded under its cache filename while the exported bytes correctly said
   * `Roboto`; the audit treated that spelling difference as a lost face and replaced every text run
   * with `ComposeAI Missing Font` (issue #4935).
   *
   * The typographic/preferred family (`name` ID 16) wins over the legacy family (`name` ID 1),
   * matching CSS's family + weight model for faces such as Montserrat Medium.
   */
  fun familyName(bytes: ByteArray): String? =
    typographicFamily(bytes)
      ?: runCatching {
        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).family
      }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

  private fun key(identity: String, weight: Int, italic: Boolean): String =
    "$identity|$weight|$italic"

  private fun typographicFamily(bytes: ByteArray): String? = runCatching {
    val naming =
      org.apache.fontbox.ttf
        .TTFParser(true)
        .parse(org.apache.pdfbox.io.RandomAccessReadBuffer(bytes))
        .naming ?: return@runCatching null
    naming.nameRecords
      .firstOrNull { it.nameId == NAME_ID_TYPOGRAPHIC_FAMILY }
      ?.string
      ?.trim()
      ?.takeIf { it.isNotBlank() }
  }
    .getOrNull()

  /** Drop every registration. Tests only — production accumulates for the process's life. */
  fun clear() {
    paths.clear()
    previewPaths.clear()
  }

  private const val NAME_ID_TYPOGRAPHIC_FAMILY = 16
}
