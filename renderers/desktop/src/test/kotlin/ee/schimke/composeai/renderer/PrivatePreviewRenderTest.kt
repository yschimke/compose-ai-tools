package ee.schimke.composeai.renderer

import ee.schimke.composeai.scroll.ScrollAxis
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Kotlin `private fun` previews must render through the **standalone** desktop renderer — the one
 * `composePreviewRenderAll` / `compose-preview bundle render` runs, and the one `compose-preview
 * serve --module` bootstraps with before it hands over to its daemon.
 *
 * Before the fix every path here resolved the preview with `getDeclaredComposableMethod` (which
 * finds private members) and then invoked it without opening the JVM method, so a private preview
 * died with `IllegalAccessException: ComposableMethod cannot access … with modifiers "private
 * static final"`. The desktop daemon and the Android renderer already called
 * `asMethod().isAccessible = true`, so the very same preview rendered through MCP and failed
 * through Gradle — and Serve could exit during bootstrap on a preview its own daemon would have
 * drawn (issue #3873).
 *
 * One test per rendering path (default, animated, focus, scroll) plus the `@PreviewParameter`
 * fan-out, because each path resolves its own `ComposableMethod` and the fix has to reach all of
 * them. Every fixture in [PrivatePreviewFixtures] is `private` — making one public would retire the
 * regression silently.
 */
class PrivatePreviewRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.PrivatePreviewFixturesKt"

  @Test
  fun `a private parameterless preview renders on the default path`() {
    val out = File(tempFolder.newFolder("default"), "private-red.png")

    renderPreview(
      className = fixtureClass,
      functionName = "PrivateRedSquare",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = false,
      wrapHeight = false,
      previewArgs = emptyList(),
      localeTag = null,
    )

    val image = decode(out)
    assertEquals(
      "private preview must paint its own fill",
      0xFFEF5350.toInt(),
      image.getRGB(32, 32),
    )
  }

  /**
   * The `main` entrypoint is what the Gradle plugin and Serve's bootstrap actually invoke, and the
   * fan-out it writes is where Serve reads a parameterized preview's rows back out of. A private
   * preview over a private provider must therefore produce **both** row PNGs and no `.error.json` —
   * a missing row here is a row missing from the catalog.
   */
  @Test
  fun `a private parameterized preview renders every provider row through main`() {
    val renders = tempFolder.newFolder("fanout")
    val out = File(renders, "private-swatch.png")

    main(
      rendererArgs(
        functionName = "PrivateParameterizedSwatch",
        outputFile = out,
        providerFqn = "ee.schimke.composeai.renderer.PrivateSwatchProvider",
        providerLimit = "10",
      )
    )

    val crimson = File(renders, "private-swatch_Crimson.png")
    val teal = File(renders, "private-swatch_Teal.png")
    assertNoErrorSidecar(out)
    assertNoErrorSidecar(crimson)
    assertNoErrorSidecar(teal)
    assertEquals("Crimson row paints its value", 0xFFDC143C.toInt(), decode(crimson).getRGB(32, 32))
    assertEquals("Teal row paints its value", 0xFF008B8B.toInt(), decode(teal).getRGB(32, 32))
  }

  @Test
  fun `a private preview renders on the animated path`() {
    val out = File(tempFolder.newFolder("animated"), "private-sweep.gif")

    renderAnimatedPreview(
      className = fixtureClass,
      functionName = "PrivateSweepingDot",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      durationMs = 500,
      frameIntervalMs = 100,
      showCurves = false,
    )

    assertTrue("GIF must exist and be non-empty", out.exists() && out.length() > 0)
    assertEquals("500ms / 100ms → 5 frames", 5, gifFrameCount(out))
  }

  @Test
  fun `a private preview renders on the focus path`() {
    val out = File(tempFolder.newFolder("focus"), "private-focus.png")

    val drove =
      renderFocusPreview(
        className = fixtureClass,
        functionName = "PrivateFocusableButtonRow",
        widthPx = 800,
        heightPx = 400,
        density = 2.0f,
        showBackground = true,
        backgroundColor = 0L,
        outputFile = out,
        wrapperClassName = null,
        wrapWidth = true,
        wrapHeight = true,
        previewArgs = emptyList(),
        localeTag = null,
        focus = DesktopFocusIntent(tabIndex = 0),
      )

    assertTrue("focus drive must land on the private fixture's first button", drove)
    assertTrue("focused PNG must exist and be non-empty", out.exists() && out.length() > 0)
  }

  @Test
  fun `a private preview renders on the scroll path`() {
    val out = File(tempFolder.newFolder("scroll"), "private-scroll.png")

    val drove =
      renderScrollPreview(
        className = fixtureClass,
        functionName = "PrivateColourBandedList",
        widthPx = 100,
        heightPx = 160,
        density = 1.0f,
        showBackground = true,
        backgroundColor = 0L,
        outputFile = out,
        wrapperClassName = null,
        previewArgs = emptyList(),
        localeTag = null,
        scrollMode = DesktopScrollMode.END,
        axis = ScrollAxis.VERTICAL,
        maxScrollPx = 0,
        frameIntervalMs = 0,
      )

    assertTrue("END drive must find the private fixture's list", drove)
    // The green row exists only past the end of the scroll, so a green pixel proves the private
    // composable was invoked *and* driven, not just that a file appeared.
    val image = decode(out)
    assertTrue(
      "END capture must reach the private list's green tail row",
      (0 until image.height).any { y -> image.getRGB(image.width / 2, y) == 0xFF00FF00.toInt() },
    )
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    assertTrue(
      "rendered image must exist: ${file.absolutePath}",
      file.exists() && file.length() > 0,
    )
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) }
      ?: error("couldn't decode ${file.absolutePath}")
  }

  private fun assertNoErrorSidecar(target: File) {
    val sidecar = File(target.parentFile, target.name + ".error.json")
    assertFalse(
      "render must not fail: ${if (sidecar.exists()) sidecar.readText() else ""}",
      sidecar.exists(),
    )
  }

  private fun gifFrameCount(file: File): Int {
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    ImageIO.createImageInputStream(ByteArrayInputStream(file.readBytes())).use { stream ->
      reader.input = stream
      return reader.getNumImages(true)
    }
  }

  /** The positional arg list `RenderPreviewsTask.invokeRenderer` sends, defaulted. */
  private fun rendererArgs(
    functionName: String,
    outputFile: File,
    providerFqn: String,
    providerLimit: String,
  ): Array<String> =
    arrayOf(
      fixtureClass,
      functionName,
      "64",
      "64",
      "1.0",
      "true",
      "0",
      outputFile.absolutePath,
      "", // wrapperClassName
      "false", // wrapWidth
      "false", // wrapHeight
      providerFqn,
      providerLimit,
      "", // localeTag
    )
}
