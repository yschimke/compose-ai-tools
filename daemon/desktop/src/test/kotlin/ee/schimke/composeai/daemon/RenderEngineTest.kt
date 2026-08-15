package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B-desktop.1.4 verification — exercises the real Compose Desktop render body.
 *
 * Two tests:
 * * **redSquareRendersToValidPng** — submit one render through a [DesktopHost] backed by a real
 *   [RenderEngine]; assert the PNG file exists, decodes, and is mostly red. Mirrors the "is this
 *   mostly red?" assertion pattern from `samples/android/.../ScrollPreviewPixelTest.kt`.
 * * **tenSequentialRendersExposeWarmRuntime** — log per-render wall-clock for 10 sequential renders
 *   so we can see whether the warm-runtime amortisation is working (first render pays JIT warmup;
 *   subsequent renders should be faster). The test only fails if a render itself fails — the timing
 *   data is for the agent to report back, not a CI assertion.
 *
 * Pixel-diff helper is inlined here rather than imported from `:daemon:harness`'s `PixelDiff` to
 * avoid a circular dep (`:daemon:desktop` ← `:daemon:harness` would invert the dependency graph the
 * harness was built around). v2 reconciles by hoisting `PixelDiff` into a shared utilities module
 * if a third call-site needs it.
 */
class RenderEngineTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun redSquareRendersToValidPng() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=RedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=red-square"
        )
      val result = host.submit(request, timeoutMs = 60_000)

      // pngPath populated and the file actually exists.
      assertNotNull("pngPath must be populated by the real render body", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      // tookMs metric is populated.
      val metrics = result.metrics
      assertNotNull("metrics must be populated", metrics)
      assertTrue("metrics must contain tookMs", metrics!!.containsKey("tookMs"))
      assertTrue(
        "tookMs should be a sane wall-clock value (was ${metrics["tookMs"]})",
        metrics["tookMs"]!! in 0..60_000,
      )

      // PNG decodes and is mostly red. Use a wide channel tolerance because Compose's @Preview
      // surface composition + Skia's PNG encoder can introduce a few LSB of channel drift; the
      // assertion is "the rendered fill is the colour we asked for", not a pixel-perfect compare.
      val bytes = pngFile.readBytes()
      val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      assertEquals(64, img!!.width)
      assertEquals(64, img.height)
      val expectedRgb = 0xEF5350
      val matchPct = pixelMatchPct(img, expectedRgb, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun renderAlsoProducesSemanticsArtifacts() {
    // End-to-end: a real desktop render must drop the always-on `compose/semantics` JSON sidecar
    // AND the `compose/semantics-wireframe` artefacts (SVG + baked PNG) into the data dir, keyed by
    // the preview id, alongside the PNG. The plain JSON sidecar (compose-semantics.json) is what
    // `bundle pack --with-semantics` and design-parity read — the desktop backend previously wrote
    // only the wireframe and omitted it, so semantics never reached the bundle (issue #1885
    // follow-up).
    val outputDir = tempFolder.newFolder("renders-wireframe")
    val dataDir = tempFolder.newFolder("data-wireframe")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=RedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=wireframe-red"
        )
      host.submit(request, timeoutMs = 60_000)

      val previewDir = File(dataDir, "wireframe-red")
      val semantics = File(previewDir, "compose-semantics.json")
      assertTrue(
        "compose-semantics.json sidecar must be produced: ${semantics.absolutePath}",
        semantics.exists(),
      )
      assertTrue(
        "compose-semantics.json must carry a node tree",
        semantics.readText().contains("\"root\""),
      )
      val svg = File(previewDir, "compose-semantics-wireframe.svg")
      val png = File(previewDir, "compose-semantics-wireframe.png")
      assertTrue("wireframe SVG must be produced: ${svg.absolutePath}", svg.exists())
      assertTrue("wireframe SVG must be valid", svg.readText().trimStart().startsWith("<svg"))
      assertTrue("wireframe PNG must be produced: ${png.absolutePath}", png.exists())
      assertTrue("wireframe PNG must be non-empty", png.length() > 0)
      // The layered `compose/figma-svg` export rides the same captured trees as the wireframe.
      val figma = File(previewDir, "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      assertTrue("figma SVG must be valid", figmaSvg.trimStart().startsWith("<svg"))
      // The export is layered: at least the root composable is emitted as a named `<g id=…>` group.
      assertTrue("figma SVG must carry named layer groups", figmaSvg.contains("<g id="))
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgVariantsWithOnePreviewIdRemainIsolated() {
    val outputDir = tempFolder.newFolder("renders-figma-variants")
    val dataDir = tempFolder.newFolder("data-figma-variants")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val basePayload =
        "previewId=dark-aware;" +
          "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
          "functionName=DarkAwareSquare;" +
          "widthPx=48;heightPx=48;density=1.0;showBackground=false;"
      val lightResult =
        host.submit(
          RenderRequest.Render(
            payload = basePayload + "uiMode=light;outputBaseName=dark-aware-light"
          ),
          timeoutMs = 60_000,
        )
      val darkResult =
        host.submit(
          RenderRequest.Render(
            payload = basePayload + "uiMode=dark;outputBaseName=dark-aware-dark"
          ),
          timeoutMs = 60_000,
        )

      assertEquals("dark-aware-light", lightResult.outputBaseName)
      assertEquals("dark-aware-dark", darkResult.outputBaseName)
      val lightFile = dataDir.resolve("dark-aware-light/compose-figma.svg")
      val darkFile = dataDir.resolve("dark-aware-dark/compose-figma.svg")
      assertTrue(
        "light variant SVG must be produced: ${lightFile.absolutePath}",
        lightFile.exists(),
      )
      assertTrue("dark variant SVG must be produced: ${darkFile.absolutePath}", darkFile.exists())
      val lightSvg = lightFile.readText()
      val darkSvg = darkFile.readText()
      assertTrue("light SVG must carry the light render", lightSvg.contains("fill=\"#FFFFFF\""))
      assertTrue("dark SVG must carry the dark render", darkSvg.contains("fill=\"#000000\""))
      assertNotEquals("desktop variants must not replay one captured tree", lightSvg, darkSvg)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportRastersOpaqueImage() {
    // End-to-end hybrid export: a real render of a screen containing an opaque `Image` must emit
    // the
    // Image as an `<image>` layer in `compose-figma.svg` AND crop the referenced background-free
    // raster out of the captured frame into `figma-raster/` — so the SVG never dangles a reference
    // (the reason the hybrid was previously opt-in). The crop must land on the Image's pixels
    // (green)
    // rather than the surrounding screen (red), proving the node bounds map onto the frame 1:1.
    val outputDir = tempFolder.newFolder("renders-figma-raster")
    val dataDir = tempFolder.newFolder("data-figma-raster")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=OpaqueImageSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=figma-raster"
        )
      host.submit(request, timeoutMs = 60_000)

      val previewDir = File(dataDir, "figma-raster")
      val figma = File(previewDir, "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      assertTrue(
        "figma SVG must emit the opaque Image as an <image> layer",
        figmaSvg.contains("<image "),
      )
      assertTrue(
        "figma SVG must reference a figma-raster PNG",
        figmaSvg.contains("""href="figma-raster/"""),
      )

      val rasterDir = File(previewDir, "figma-raster")
      val pngs = rasterDir.listFiles { f -> f.extension == "png" }?.toList().orEmpty()
      assertTrue("hybrid export must write the referenced raster PNG(s)", pngs.isNotEmpty())
      // The cropped raster must carry the Image's pixels (green), not the red screen behind it.
      val cropped = javax.imageio.ImageIO.read(pngs.first())
      assertNotNull("raster PNG must decode", cropped)
      val center = java.awt.Color(cropped.getRGB(cropped.width / 2, cropped.height / 2))
      assertTrue(
        "raster crop must land on the Image (green-dominant), got $center",
        center.green > center.red && center.green > center.blue,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportRoundsRawPixelCorner() {
    // End-to-end raw-pixel corner: a real render of a box clipped with `RoundedCornerShape(20f)`
    // (a PxCornerSize, which the dp `cornerRadius` token can't express) must export a *rounded*
    // rect, not a sharp one. Exercises the whole chain — ModifierTokenResolver.cornerRadiusPxWire
    // reflecting the PxCornerSize → the `cornerRadiusPx` token → FigmaSvgModel → the rounded SVG.
    val outputDir = tempFolder.newFolder("renders-px-corner")
    val dataDir = tempFolder.newFolder("data-px-corner")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=PxCornerSquare;" +
              "widthPx=100;heightPx=100;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=px-corner"
        )
      host.submit(request, timeoutMs = 60_000)

      val figma = File(File(dataDir, "px-corner"), "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      // The 20px corner is uniform, so the renderer emits a rounded `<rect rx ry>` — its presence
      // proves the raw-px corner survived instead of dropping to a sharp rectangle.
      assertTrue(
        "figma SVG must round the RoundedCornerShape(20f) corner (rx=20), got:\n$figmaSvg",
        figmaSvg.contains("""rx="20""""),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportUnwrapsAnimatedMorphShape() {
    // End-to-end Wear-M3 button corner: a real render of a box filled through an
    // `AnimatedMorphShape`-shaped wrapper must export the wrapper's *resting* corner, not a sharp
    // rect. Exercises the whole chain — ModifierTokenResolver.effectiveCornerShape unwrapping the
    // resting `shape` field → the dp `cornerRadius` token → FigmaSvgModel → the rounded SVG.
    //
    // This is the Horologist `VolumeScreen` bug (issue #3254): `Stepper` always routes its
    // increase/decrease buttons through `animateButtonShape`, so the volume buttons were always
    // wrapped, always missed every corner path, and always exported as blue squares painted over
    // their correctly-rounded raster.
    val outputDir = tempFolder.newFolder("renders-morph-corner")
    val dataDir = tempFolder.newFolder("data-morph-corner")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=AnimatedMorphShapeButton;" +
              "widthPx=200;heightPx=200;density=2.0;" +
              "showBackground=true;" +
              "outputBaseName=morph-corner"
        ),
        timeoutMs = 60_000,
      )

      val figma = File(File(dataDir, "morph-corner"), "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      // The resting shape is a full pill on a 120×96px box → a uniform 48px corner.
      assertTrue(
        "figma SVG must round the wrapped resting corner (rx=48), got:\n$figmaSvg",
        figmaSvg.contains("""rx="48""""),
      )
      // And it must stay an editable rounded rect rather than degrading to the sampled polyline
      // fallback — that path is only for shapes no corner can describe.
      assertFalse(
        "a resolvable corner must not fall through to the outline sampler, got:\n$figmaSvg",
        figmaSvg.contains("<path d=\"M0.5,0"),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportVectorisesUnreducibleShape() {
    // The general guard behind the unwrap above: a shape that reduces to no corners at all must
    // export its actual sampled outline, not a sharp rectangle standing in for geometry the
    // exporter never established (issue #3254). The fixture's shape is a triangle, so the apex
    // point (half width, top) has to appear in the emitted path.
    val outputDir = tempFolder.newFolder("renders-generic-outline")
    val dataDir = tempFolder.newFolder("data-generic-outline")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=GenericOutlineTriangle;" +
              "widthPx=200;heightPx=200;density=2.0;" +
              "showBackground=true;" +
              "outputBaseName=generic-outline"
        ),
        timeoutMs = 60_000,
      )

      val figma = File(File(dataDir, "generic-outline"), "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      // The 120×96 box is centred on the 200×200 canvas, so the triangle spans x 40..160, y 52..148
      // with its apex at x=100. A `<path>` carrying that apex proves the real outline survived.
      val path =
        Regex("""<path d="(M[^"]+)"[^>]*fill="#04409F"""").find(figmaSvg)?.groupValues?.get(1)
      assertNotNull("an unreducible shape must export as a sampled path, got:\n$figmaSvg", path)
      assertTrue("the outline must be closed, got: $path", path!!.endsWith("Z"))
      val points =
        path.removeSuffix(" Z").split(" ").mapNotNull { token ->
          token.drop(1).split(",").let { xy ->
            if (xy.size == 2)
              (xy[0].toDoubleOrNull() ?: return@mapNotNull null) to
                (xy[1].toDoubleOrNull() ?: return@mapNotNull null)
            else null
          }
        }
      assertTrue("expected a sampled polyline, got: $path", points.size > 8)
      assertTrue(
        "the triangle apex (~100, ~52) must be present, got: $path",
        points.any { (x, y) ->
          kotlin.math.abs(x - 100.0) < 2.0 && kotlin.math.abs(y - 52.0) < 2.0
        },
      )
      // The base corners pin the other two vertices, so the shape is a triangle and not a bounding
      // box drawn as a path.
      assertTrue(
        "the bottom-left vertex (~40, ~148) must be present, got: $path",
        points.any { (x, y) ->
          kotlin.math.abs(x - 40.0) < 2.0 && kotlin.math.abs(y - 148.0) < 2.0
        },
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgExportChamfersCutCorner() {
    // End-to-end cut corner: a real render of a box clipped with `CutCornerShape(20.dp)` must
    // export
    // a bevelled path (straight `L` segments), not a rounded one (`A` arcs). Exercises the whole
    // chain — ModifierTokenResolver emits `shape="cut"`, FigmaSvgModel sets `cut`, the renderer
    // draws chamfers.
    val outputDir = tempFolder.newFolder("renders-cut-corner")
    val dataDir = tempFolder.newFolder("data-cut-corner")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=CutCornerSquare;" +
              "widthPx=100;heightPx=100;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=cut-corner"
        )
      host.submit(request, timeoutMs = 60_000)

      val figma = File(File(dataDir, "cut-corner"), "compose-figma.svg")
      assertTrue("figma layered SVG must be produced: ${figma.absolutePath}", figma.exists())
      val figmaSvg = figma.readText()
      assertTrue("cut corner must be a <path>, got:\n$figmaSvg", figmaSvg.contains("<path"))
      assertTrue("cut corner must chamfer (straight L segments)", figmaSvg.contains(" L"))
      assertFalse("cut corner must not round (no arc A commands)", figmaSvg.contains(" A"))
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun figmaSvgFidelityScoresARender() {
    // End-to-end fidelity harness: with -Dcomposeai.figma.fidelity=true, a real render of a
    // composite themed card must, alongside the SVG, drop a `render | figma-svg | diff` composite
    // and
    // a score sidecar — the SVG rasterised (Skia), aligned to the render, and scored by
    // FigmaFidelity.
    // The score must be a real fraction in (0,1] (the card reproduces well but not
    // pixel-perfectly),
    // and the composite must be the three side-by-side panels.
    System.setProperty("composeai.figma.fidelity", "true")
    val outputDir = tempFolder.newFolder("renders-fidelity")
    val dataDir = tempFolder.newFolder("data-fidelity")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=FidelityCardPreview;" +
              "widthPx=120;heightPx=120;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=fidelity-card"
        )
      host.submit(request, timeoutMs = 60_000)

      val previewDir = File(dataDir, "fidelity-card")
      val composite = File(previewDir, FigmaSvgFidelity.FILE_COMPOSITE)
      val scoreFile = File(previewDir, FigmaSvgFidelity.FILE_SCORE)
      assertTrue(
        "fidelity composite must be produced: ${composite.absolutePath}",
        composite.exists(),
      )
      assertTrue(
        "fidelity score sidecar must be produced: ${scoreFile.absolutePath}",
        scoreFile.exists(),
      )

      val scoreJson = scoreFile.readText()
      assertTrue("score json must carry a score field: $scoreJson", scoreJson.contains("\"score\""))
      val score = Regex("\"score\":([0-9.]+)").find(scoreJson)?.groupValues?.get(1)?.toDouble()
      assertNotNull("score must parse: $scoreJson", score)
      assertTrue(
        "score must be a real fraction in (0,1], was $score",
        score!! > 0.0 && score <= 1.0,
      )

      // The composite is three render-width panels wide (render | figma-svg | diff) + gutters.
      val img = javax.imageio.ImageIO.read(composite)
      assertNotNull("composite must decode", img)
      assertTrue(
        "composite must be at least 3x render width wide, was ${img!!.width}",
        img.width >= 120 * 3,
      )
    } finally {
      host.shutdown()
      System.clearProperty("composeai.figma.fidelity")
    }
  }

  @Test
  fun figmaSvgFidelityScoresAlphaZeroRecordButton() {
    // Issue #2853 end-to-end: a real render of the alpha-zero record button (a recording circle
    // faded to alpha 0 over a mic, in an input-bar row) must produce a fidelity composite + score.
    // The score guards the whole render→SVG→raster path for the alpha-zero clipped background — a
    // leaked opaque circle would drag it down.
    assertFidelityScored(
      functionName = "AlphaZeroRecordButton",
      widthPx = 200,
      heightPx = 56,
      baseName = "fidelity-record-button",
    )
  }

  @Test
  fun figmaSvgFidelityScoresAnimatedLayoutVector() {
    // Issue #2853 end-to-end: a square create icon scaled through a graphics layer (Jetchat's
    // animating FAB). The score guards the render→SVG→raster path for a scaled vector — the
    // double-counted `scale(6.54)` blow-up would misplace the icon and drag it down.
    assertFidelityScored(
      functionName = "VectorIconInAnimatedLayout",
      widthPx = 96,
      heightPx = 96,
      baseName = "fidelity-animated-fab",
    )
  }

  @Test
  fun figmaSvgDrawsAPaddedIconAtItsPaintedSize() {
    // Issue #2853 end-to-end, the padded icon: Jetchat's `InputSelectorButton`
    // (`IconButton { Icon(Modifier.padding(8.dp).size(56.dp)) }`) and its `RecordButton`
    // (`Icon(Modifier.sizeIn(minWidth = 56.dp).padding(18.dp))`). The padding ahead of the painter
    // insets the box the glyph is drawn into, so fitting the vector to the node's own box drew each
    // glyph at its *button's* size — the oversized action icons and microphone of
    // `Conversation/Input`.
    //
    // Asserted against the render itself rather than a golden string: every glyph the SVG emits
    // must land on the pixels the PNG actually painted. That is the fidelity the issue asks for,
    // and
    // it fails loudly whichever way the geometry drifts.
    val outputDir = tempFolder.newFolder("renders-padded-icon")
    val dataDir = tempFolder.newFolder("data-padded-icon")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=IconButtonRowInputBar;" +
                "widthPx=240;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=padded-icon"
          ),
          timeoutMs = 60_000,
        )

      val svg = File(dataDir, "padded-icon/compose-figma.svg").readText()
      // Each glyph rides in `translate(x y) scale(s s)` and paints the viewport's 2..22 square, so
      // the SVG says the white block covers [x + 2s, x + 20s] on each axis.
      val groups =
        Regex("""translate\(([-0-9.]+) ([-0-9.]+)\) scale\(([0-9.]+) ([0-9.]+)\)""")
          .findAll(svg)
          .map { m -> m.groupValues.drop(1).map { it.toDouble() } }
          .toList()
      assertTrue(
        "all three icons must emit a vector group, got ${groups.size}:\n$svg",
        groups.size >= 3,
      )

      val png = javax.imageio.ImageIO.read(File(result.pngPath!!))
      for ((x, y, sx, sy) in groups.map { listOf(it[0], it[1], it[2], it[3]) }) {
        assertEquals("a square glyph must stay square", sx, sy, 0.01)
        val left = x + 2 * sx
        val top = y + 2 * sy
        val side = 20 * sx
        // The white glyph the SVG claims is here must be white in the render too — centre, and just
        // inside each edge. An oversized fit (the bug) puts the SVG's block over the dark
        // background instead.
        val probes =
          listOf(
            left + side / 2 to top + side / 2,
            left + 2 to top + 2,
            left + side - 2 to top + side - 2,
          )
        for ((px, py) in probes) {
          assertTrue(
            "the SVG places a glyph outside the render entirely ($px,$py in " +
              "${png.width}×${png.height})",
            px >= 0 && py >= 0 && px < png.width && py < png.height,
          )
          val rgb = png.getRGB(px.toInt(), py.toInt())
          val bright = ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF)) / 3
          assertTrue(
            "the render must paint the glyph where the SVG places it " +
              "(probe $px,$py in a ${side}px glyph at $left,$top was $bright/255)",
            bright > 128,
          )
        }
      }
    } finally {
      host.shutdown()
    }
  }

  private fun assertFidelityScored(
    functionName: String,
    widthPx: Int,
    heightPx: Int,
    baseName: String,
  ) {
    System.setProperty("composeai.figma.fidelity", "true")
    val outputDir = tempFolder.newFolder("renders-$baseName")
    val dataDir = tempFolder.newFolder("data-$baseName")
    val engine = RenderEngine(outputDir = outputDir, dataDir = dataDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=$functionName;" +
              "widthPx=$widthPx;heightPx=$heightPx;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=$baseName"
        ),
        timeoutMs = 60_000,
      )

      val previewDir = File(dataDir, baseName)
      val composite = File(previewDir, FigmaSvgFidelity.FILE_COMPOSITE)
      val scoreFile = File(previewDir, FigmaSvgFidelity.FILE_SCORE)
      assertTrue(
        "fidelity composite must be produced: ${composite.absolutePath}",
        composite.exists(),
      )
      assertTrue(
        "fidelity score sidecar must be produced: ${scoreFile.absolutePath}",
        scoreFile.exists(),
      )

      val scoreJson = scoreFile.readText()
      val score = Regex("\"score\":([0-9.]+)").find(scoreJson)?.groupValues?.get(1)?.toDouble()
      assertNotNull("score must parse: $scoreJson", score)
      assertTrue(
        "score must be a real fraction in (0,1], was $score",
        score!! > 0.0 && score <= 1.0,
      )
    } finally {
      host.shutdown()
      System.clearProperty("composeai.figma.fidelity")
    }
  }

  @Test
  fun privateComposableRendersToValidPng() {
    // Regression: Kotlin `private fun` previews compile to JVM-private static methods. The daemon
    // resolves them via `getDeclaredComposableMethod` but the reflective `invoke` threw
    // `IllegalAccessException: … cannot access a member … with modifiers "private static final"`
    // until [RenderEngine] started calling `asMethod().isAccessible = true` after resolution. The
    // `samples/android/.../Previews.kt`'s `RedBoxPreview` ships such a preview on purpose.
    val outputDir = tempFolder.newFolder("renders-private")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    try {
      val request =
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=PrivateRedSquare;" +
              "widthPx=64;heightPx=64;density=1.0;" +
              "showBackground=true;" +
              "outputBaseName=private-red-square"
        )
      val result = host.submit(request, timeoutMs = 60_000)

      assertNotNull("private @Composable must render a PNG, not blank", result.pngPath)
      val pngFile = File(result.pngPath!!)
      assertTrue("rendered PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      val img = ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      val matchPct = pixelMatchPct(img!!, 0xEF5350, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to #EF5350; got ${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun previewParameterRowRendersTheAddressedProviderValue() {
    // Issue #3749: the manifest carries base ids only (discovery reads bytecode and can't
    // instantiate a provider), so every row past 0 was unreachable — `serve` and `render_preview`
    // showed one state for a screen whose states come from a provider. A `previewParameterRow`
    // token now selects which value binds. Desktop twin of `:daemon:android`'s test of the same
    // name; SquareTintProvider yields green (#43A047) then blue (#1E88E5), so row 1 must be blue.
    val outputDir = tempFolder.newFolder("renders-preview-parameter-row")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir))
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=ThemedTintedSquare;" +
                "previewParameterProvider=ee.schimke.composeai.daemon.SquareTintProvider;" +
                "previewParameterRow=PARAM_1;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=preview-parameter-square_PARAM_1"
          ),
          timeoutMs = 60_000,
        )

      assertNotNull("a row-addressed @PreviewParameter render must produce a PNG", result.pngPath)
      val img = ByteArrayInputStream(File(result.pngPath!!).readBytes()).use { ImageIO.read(it) }
      assertNotNull("PNG must decode via javax.imageio", img)
      val matchPct = pixelMatchPct(img!!, 0x1E88E5, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to the provider's SECOND value #1E88E5; got " +
          "${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun previewParameterRowPrefersAnExactLabelOverCaseFolding() {
    // Review follow-up on issue #3749: `PreviewParameterLabels` treats `Dark` and `dark` as
    // DISTINCT labels, so a provider yielding both emits two fan-out files on a case-sensitive
    // filesystem. Folding case unconditionally mapped both ids onto the first value, silently
    // rendering the wrong state for the second. CaseTintProvider yields ("Dark" → green,
    // "dark" → blue), so the lower-case id must reach the blue one.
    val outputDir = tempFolder.newFolder("renders-preview-parameter-case")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir))
    host.start()
    try {
      fun renderRow(row: String): Int {
        val result =
          host.submit(
            RenderRequest.Render(
              payload =
                "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                  "functionName=CaseLabelledSquare;" +
                  "previewParameterProvider=ee.schimke.composeai.daemon.CaseTintProvider;" +
                  "previewParameterRow=$row;" +
                  "widthPx=64;heightPx=64;density=1.0;" +
                  "showBackground=true;" +
                  "outputBaseName=case-square_$row"
            ),
            timeoutMs = 60_000,
          )
        val img = ByteArrayInputStream(File(result.pngPath!!).readBytes()).use { ImageIO.read(it) }
        return img!!.getRGB(32, 32) and 0xFFFFFF
      }

      assertEquals("exact 'Dark' must bind the first value", 0x43A047, renderRow("Dark"))
      assertEquals("exact 'dark' must bind the second value", 0x1E88E5, renderRow("dark"))
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun previewParameterRowRejectsAnAmbiguousCaseFoldedLabel() {
    // With two rows differing only by case, a request that matches neither exactly is genuinely
    // ambiguous — answer with the row list rather than picking one.
    val outputDir = tempFolder.newFolder("renders-preview-parameter-ambiguous")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir))
    host.start()
    try {
      val failure = runCatching {
        host.submit(
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=CaseLabelledSquare;" +
                "previewParameterProvider=ee.schimke.composeai.daemon.CaseTintProvider;" +
                "previewParameterRow=DARK;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "outputBaseName=case-square_DARK"
          ),
          timeoutMs = 60_000,
        )
      }
        .exceptionOrNull()
      val message =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" ")
      assertTrue(
        "an ambiguous case-folded row must fail with the row list; got: $message",
        message.contains("has no row named 'DARK'") && message.contains("Dark"),
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun previewParameterWithoutARowStillRendersValueZero() {
    // The unaddressed contract is unchanged, and it stays cheap: with no row token the provider is
    // enumerated with `take(1)`, so an infinite `generateSequence` provider is still safe.
    val outputDir = tempFolder.newFolder("renders-preview-parameter-base")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir))
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=ThemedTintedSquare;" +
                "previewParameterProvider=ee.schimke.composeai.daemon.SquareTintProvider;" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=preview-parameter-square"
          ),
          timeoutMs = 60_000,
        )

      val img = ByteArrayInputStream(File(result.pngPath!!).readBytes()).use { ImageIO.read(it) }
      val matchPct = pixelMatchPct(img!!, 0x43A047, perChannelTolerance = 8)
      assertTrue(
        "expected ≥ 95% of pixels close to the provider's FIRST value #43A047; got " +
          "${"%.2f".format(matchPct * 100)}%",
        matchPct >= 0.95,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun tenSequentialRendersExposeWarmRuntime() {
    val outputDir = tempFolder.newFolder("renders-warmup")
    val engine = RenderEngine(outputDir = outputDir)
    val host = DesktopHost(engine = engine)
    host.start()
    val perRenderMs = mutableListOf<Long>()
    val totalStartNs = System.nanoTime()
    try {
      for (i in 1..10) {
        val request =
          RenderRequest.Render(
            payload =
              "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                "functionName=${if (i % 2 == 0) "BlueSquare" else "RedSquare"};" +
                "widthPx=64;heightPx=64;density=1.0;" +
                "showBackground=true;" +
                "outputBaseName=warmup-$i"
          )
        val startNs = System.nanoTime()
        val result = host.submit(request, timeoutMs = 60_000)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000L
        perRenderMs.add(tookMs)
        assertNotNull("render $i pngPath must be populated", result.pngPath)
        assertTrue("render $i PNG must exist", File(result.pngPath!!).exists())
      }
      val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
      val firstMs = perRenderMs.first()
      val warmMedianMs = perRenderMs.drop(1).sorted().let { it[it.size / 2] }
      val ratio = if (warmMedianMs == 0L) Double.NaN else firstMs.toDouble() / warmMedianMs
      // Free-form report so the agent can copy it into the task summary. Tests don't assert on
      // these numbers — perf assertions are intentionally not gated in unit tests (D2.x / D-harness
      // own that). We just want them visible in `gradle test --info` output.
      println(
        "RenderEngineTest 10-render warm-up: total=${totalMs}ms first=${firstMs}ms " +
          "warm-median=${warmMedianMs}ms ratio=${"%.2f".format(ratio)} per-render=$perRenderMs"
      )
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  /**
   * Returns the fraction of pixels in [img] whose RGB channels are within [perChannelTolerance] of
   * the expected `0xRRGGBB` colour. Inlined here rather than imported from the harness's
   * `PixelDiff` to avoid the circular dep noted in the file KDoc.
   */
  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }
}
