package ee.schimke.composeai.daemon

import java.io.File

/**
 * Android's Google-Fonts WOFF2 resolver for the `compose/figma-svg` export's embedded `<text>`
 * faces. Extracted from the former Android `ComposeFigmaSvgExtension` (now shared in
 * `:data-layoutinspector-connector`) so [RobolectricHost] can inject it into that shared extension.
 *
 * Returns null when font embedding is switched off (`-Dcomposeai.svg.embedFonts=false`). Unlike the
 * desktop resolver there is no fidelity-measurement exception — Android runs no `FigmaSvgFidelity`
 * pass. On Android the render itself is Roboto, so the embedded face is the exact match; downloads
 * are cached under the shared font cache dir, honouring the offline switch.
 */
internal fun androidFigmaFontResolver(): FigmaFontResolver? {
  fun on(prop: String) = System.getProperty(prop)?.lowercase() == "true"
  if (System.getProperty("composeai.svg.embedFonts")?.lowercase() == "false") return null
  return GoogleFontsWoff2Resolver(
    cacheDir = System.getProperty("composeai.fonts.cacheDir")?.let { File(it) },
    offline = on("composeai.fonts.offline"),
  )
}
