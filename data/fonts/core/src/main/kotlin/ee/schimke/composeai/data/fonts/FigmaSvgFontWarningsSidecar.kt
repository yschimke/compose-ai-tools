package ee.schimke.composeai.data.fonts

/**
 * The `compose/figma-svg` export's font-warning sidecar, named once so the two ends agree.
 *
 * The export writes it beside `compose-figma.svg` whenever it drew text in missing-glyph boxes
 * because the render used a family the export could not name; a healthy export leaves no file, so
 * its **presence is the signal**. That made it worth exactly nothing for a year: the producer
 * (`:data-layoutinspector-connector`) wrote it into the render's data dir, and no reader ever went
 * looking, so a degraded sticker sheet published with the warning still sitting on the build
 * machine's disk.
 *
 * The reader is `:cli`, which does not depend on the connector, so the name lives here — the one
 * module both already depend on — rather than being spelled twice and drifting.
 */
public object FigmaSvgFontWarningsSidecar {

  /** Filename written beside the SVG in the render's per-preview data dir. */
  public const val FILE: String = "compose-figma-fonts.warnings.json"
}
