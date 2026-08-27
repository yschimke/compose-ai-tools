package ee.schimke.composeai.cli.serve

/**
 * The typefaces the **client-side** Remote Compose lanes render with, registered for the served
 * pages that paint a `.rc` document in the browser.
 *
 * The vendored TypeScript player's `cssFontStackFor`
 * (`third_party/remote-compose-player/src/web/CanvasPaintContext.ts`) maps a document's built-in
 * family ids onto the concrete faces Android's `fonts.xml` resolves them to — `Roboto, sans-serif`,
 * `"Noto Serif", serif`, `"Droid Sans Mono", monospace`. The concrete names are only a *request*: a
 * page that has registered no such faces falls straight through to the CSS generic and paints in
 * whatever the viewer's own machine calls `sans-serif`. That is silent in two ways — wrong
 * outlines, and wrong *metrics* (Roboto's line box is 1.17em against a typical container fallback's
 * 1.12em) — and it loses weights the fallback has no file for, so text a document asks for at
 * Medium renders Regular. Registering the faces is what turns the player's request into a match
 * (issue #3480).
 *
 * These are the **same files** the snapshot renderer rasterizes with (vendored from Robolectric's
 * nativeruntime) and the same ones the offline parity harness registers, so the browser lane and
 * the baked PNG beside it are comparable rather than merely similar:
 * * [FACES] mirrors `FONT_FACES` in
 *   [`rc-fonts.mjs`](../../../../../../../../scripts/design-artifacts/rc-fonts.mjs) —
 *   `rc-fonts.test.mjs` reads *this file* and fails when the two tables disagree, so a rename or a
 *   weight-range edit cannot silently reintroduce font substitution on one side only;
 * * the font bytes reach the CLI jar by a `processResources` copy from the vendored directory
 *   `rc-fonts.mjs` reads (`cli/build.gradle.kts`), not a second committed copy, and
 *   `ServeRcFontsTest` asserts every declared face is actually on the classpath.
 *
 * *Named* families (`Orbitron`, `google:`-prefixed) are not here: the player fetches those itself
 * through `WebFonts.ts` and repaints via `onFontLoaded`. This is only about the four faces behind
 * the generic families, which no one fetches.
 */
internal object ServeRcFonts {
  /** Classpath directory the `processResources` copy stages the vendored faces into. */
  private const val RESOURCE_DIR = "/rc-fonts"

  /**
   * URL prefix the faces and their stylesheet are served under, origin-absolute like `/rc-player`.
   */
  const val URL_BASE: String = "/rc-fonts"

  /** Name of the generated stylesheet: the [FACES] as `@font-face` rules. */
  const val STYLESHEET: String = "fonts.css"

  /**
   * One vendored face.
   *
   * [weightRange] is the span the rule *serves*, not the file's nominal weight, and the spans are
   * contiguous on purpose. Declared at discrete weights, a request for an in-between weight — Wear
   * M3's `bodyLarge` asks for 450 — is resolved by CSS's matching rules, which for a target in
   * 400..500 search *upward* first and so land on Medium, rendering heavier than the baked raster.
   * Giving each file a contiguous range makes every request resolve to a real file with no
   * interpolation and no synthetic emboldening: Regular serves everything below Medium's nominal
   * 500, Medium serves 500 and up.
   */
  data class Face(val family: String, val weightRange: String, val file: String)

  /**
   * The vendored faces, in the order [css] declares them. Mirrors `rc-fonts.mjs`'s `FONT_FACES`.
   */
  val FACES: List<Face> =
    listOf(
      Face("Roboto", "1 499", "Roboto-Regular.ttf"),
      Face("Roboto", "500 1000", "Roboto-Medium.ttf"),
      Face("Noto Serif", "1 1000", "NotoSerif-Regular.ttf"),
      Face("Droid Sans Mono", "1 1000", "DroidSansMono.ttf"),
    )

  /**
   * `<link>` for the generated stylesheet, for the `<head>` of a page with a client-side RC lane.
   */
  fun linkTag(): String = "<link rel=\"stylesheet\" href=\"$URL_BASE/$STYLESHEET\">"

  /**
   * The stylesheet: one `@font-face` per [FACES] entry, pointing at this server's own `/rc-fonts/`
   * routes.
   *
   * A face whose file is missing from the jar is **omitted** rather than declared against a 404 —
   * the lane then paints that generic in the browser's own fallback, exactly as it did before this
   * existed, instead of the browser retrying a dead URL on every paint.
   */
  fun css(): String =
    FACES.filter { it.file in presentFiles }
      .joinToString("") { face ->
        "@font-face{font-family:\"${face.family}\";font-weight:${face.weightRange};" +
          "font-style:normal;src:url(\"$URL_BASE/${face.file}\") format(\"truetype\");}\n"
      }

  /** Classpath resource path for a declared face file, or null when [name] names none. */
  fun resourceFor(name: String): String? =
    if (FACES.any { it.file == name }) "$RESOURCE_DIR/$name" else null

  /**
   * Which declared faces this build actually carries. Resolved once (the jar doesn't change under a
   * running server) so rendering a page costs no classpath lookups.
   */
  private val presentFiles: Set<String> by lazy {
    FACES.map { it.file }
      .filter { file -> ServeRcFonts::class.java.getResource("$RESOURCE_DIR/$file") != null }
      .toSet()
  }
}
