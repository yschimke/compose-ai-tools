package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fixture for the refusal tests below: no scrollable anywhere in the composition, so the LONG / GIF
 * scroll driver's semantics query finds nothing and `renderScrollPreview` declines.
 */
@Composable
fun NoScrollableFixture() {
  Box(modifier = Modifier.fillMaxSize().background(Color.Red))
}

/**
 * Issue #2191 — when `renderScrollPreview` declines ("no scrollable found"), the desktop renderer
 * must NOT fall through to a single-frame render into a *data product* output
 * (`data/render-scroll-{long,gif}/`): that writes PNG bytes into a `.gif`-named product, or stamps
 * the unscrolled first viewport into the long-scroll path, and the panel shows a still frame under
 * a "scroll gif" label as if capture succeeded. Mirrors the Android renderer's rule
 * (RobolectricRenderTest's `productFellThrough`): write the structured `.error.json` sidecar
 * instead. Primary captures (`renders/<id>.png`) keep the "produce SOMETHING on disk" fall-through.
 */
class ScrollProductRefusalTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.ScrollProductRefusalTestKt"

  private fun rendererArgs(outputFile: File, scrollMode: String): Array<String> =
    arrayOf(
      fixtureClass,
      "NoScrollableFixture",
      "100",
      "160",
      "1.0",
      "true",
      "0",
      outputFile.absolutePath,
      "", // wrapperClassName
      "false", // wrapWidth
      "false", // wrapHeight
      "", // previewParameterProviderFqn
      "0", // previewParameterLimit
      "", // localeTag
      scrollMode,
      "VERTICAL",
      "0", // scrollMaxScrollPx
      "0", // scrollFrameIntervalMs
    )

  @Test
  fun `gif data product with no scrollable writes an error sidecar, not png bytes into the gif`() {
    val previewsRoot = tempFolder.newFolder("compose-previews")
    val outputFile = previewsRoot.resolve("data/render-scroll-gif/NoScrollableFixture.gif")

    main(rendererArgs(outputFile, scrollMode = "GIF"))

    assertFalse("no .gif must be written when capture declined", outputFile.exists())
    val sidecar = File(outputFile.parentFile, outputFile.name + ".error.json")
    assertTrue("structured error sidecar must be written", sidecar.exists())
    val json = sidecar.readText()
    assertTrue(json.contains("compose-preview-error/v1"))
    assertTrue(json.contains("no scrollable composable found"))
    assertTrue(json.contains("refusing to"))
    assertTrue(json.contains("data product path"))
  }

  @Test
  fun `long data product with no scrollable writes an error sidecar, not the unscrolled frame`() {
    val previewsRoot = tempFolder.newFolder("compose-previews")
    val outputFile = previewsRoot.resolve("data/render-scroll-long/NoScrollableFixture.png")

    main(rendererArgs(outputFile, scrollMode = "LONG"))

    assertFalse("no long-scroll PNG must be written when capture declined", outputFile.exists())
    val sidecar = File(outputFile.parentFile, outputFile.name + ".error.json")
    assertTrue("structured error sidecar must be written", sidecar.exists())
    assertTrue(sidecar.readText().contains("no scrollable composable found"))
  }

  @Test
  fun `refusal deletes a stale product left behind by a previous fall-through`() {
    val previewsRoot = tempFolder.newFolder("compose-previews")
    val outputFile = previewsRoot.resolve("data/render-scroll-gif/NoScrollableFixture.gif")
    outputFile.parentFile.mkdirs()
    // A PNG-bytes-in-.gif artefact from a run predating the refusal — must not survive.
    outputFile.writeBytes(byteArrayOf(1, 2, 3))

    main(rendererArgs(outputFile, scrollMode = "GIF"))

    assertFalse("stale mislabelled product must be deleted", outputFile.exists())
    assertTrue(File(outputFile.parentFile, outputFile.name + ".error.json").exists())
  }

  @Test
  fun `primary capture keeps the single-frame fall-through`() {
    val previewsRoot = tempFolder.newFolder("compose-previews")
    val outputFile = previewsRoot.resolve("renders/NoScrollableFixture.png")

    main(rendererArgs(outputFile, scrollMode = "LONG"))

    assertTrue("capture path still falls through to a single frame", outputFile.exists())
    assertTrue(outputFile.length() > 0)
    assertFalse(File(outputFile.parentFile, outputFile.name + ".error.json").exists())
  }

  @Test
  fun `isDataProductOutput matches the data grandparent convention`() {
    assertTrue(isDataProductOutput(File("/x/data/render-scroll-gif/a.gif")))
    assertTrue(isDataProductOutput(File("/x/data/render-scroll-long/a.png")))
    assertFalse(isDataProductOutput(File("/x/renders/a.png")))
    assertFalse(isDataProductOutput(File("a.png")))
  }
}
