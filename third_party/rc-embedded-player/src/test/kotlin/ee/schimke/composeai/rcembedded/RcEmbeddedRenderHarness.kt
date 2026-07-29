package ee.schimke.composeai.rcembedded

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rasterizes captured Remote Compose documents through the **embedded** player (`RcPlayer`) so the
 * `rc-compare` page can diff them against the baked PNG next to the TypeScript player's render.
 *
 * This is a harness, not an assertion test: it renders whatever the driver points it at and writes
 * PNGs. `rc-compare.mjs` drives it — it unpacks a catalog bundle's `ir/<id>.rc` entries plus the
 * matching baked PNG dimensions into an input directory, runs this, and reads the output back.
 * With no input directory configured the test is skipped, so a normal `check` run doesn't need one.
 *
 * **Robolectric is a stopgap.** The whole reason the player is being split into a CMP android/jvm
 * module is so this lane can rasterize on a plain JVM through Compose Desktop's Skia backend with no
 * Android runtime at all. Until that lands, `@GraphicsMode(NATIVE)` gives real pixels here.
 *
 * Density matters for parity: the catalog captures at dpi 320, so the documents carry dp→px factors
 * for density 2.0. `xhdpi` is that density; rendering at any other one re-lays-out the document and
 * every row would diff on geometry rather than on renderer behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcEmbeddedRenderHarness {

  @get:Rule val composeRule = createComposeRule()

  /** One document to rasterize: `<id>.rc` in the input dir, rendered at the baked PNG's size. */
  @Serializable data class Entry(val id: String, val width: Int, val height: Int)

  @Test
  fun renderAll() {
    val inputDir = System.getProperty(INPUT_PROPERTY)?.let(::File)
    assumeTrue(
      "no $INPUT_PROPERTY configured — nothing to rasterize",
      inputDir != null && inputDir.isDirectory,
    )
    val outputDir = File(requireNotNull(System.getProperty(OUTPUT_PROPERTY)) {
      "$OUTPUT_PROPERTY must be set alongside $INPUT_PROPERTY"
    })
    outputDir.mkdirs()

    val manifest = File(inputDir, "manifest.json")
    val entries = Json.decodeFromString<List<Entry>>(manifest.readText())

    // Failures are recorded per entry, not thrown: one document the player chokes on should still
    // leave the other 23 rows on the compare page. The driver reports a missing PNG as an
    // unrendered row, and `errors.txt` carries the reason so it lands in the page's note column.
    val errors = StringBuilder()
    for (entry in entries) {
      val rc = File(inputDir, "${entry.id}.rc")
      if (!rc.isFile) {
        errors.appendLine("${entry.id}\tmissing ${rc.name}")
        continue
      }
      runCatching { render(rc.readBytes(), entry.width, entry.height) }
        .onSuccess { bitmap ->
          File(outputDir, "${entry.id}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
        }
        .onFailure { t ->
          errors.appendLine("${entry.id}\t${t::class.java.simpleName}: ${t.message?.take(300)}")
        }
    }
    File(outputDir, "errors.txt").writeText(errors.toString())
  }

  /**
   * Renders [bytes] into a [width]×[height] px box and captures it.
   *
   * The box is sized in **px converted through the current density** rather than in dp so the
   * capture comes back at exactly the baked PNG's pixel dimensions — `pixelmatch` needs both sides
   * the same size, and a dp-sized box would round differently per document.
   */
  private fun render(bytes: ByteArray, width: Int, height: Int): Bitmap {
    composeRule.setContent {
      val density = LocalDensity.current
      Box(
        Modifier.size(
            with(density) { width.toDp() },
            with(density) { height.toDp() },
          )
          .semantics { testTag = CAPTURE_TAG }
      ) {
        ExperimentalRemoteDocumentPlayer(
          document = RemoteDocument(bytes),
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    // The player drives animation off the frame clock and decodes bitmaps lazily on first draw, so
    // let the composition settle before capturing rather than grabbing the first frame.
    composeRule.waitForIdle()
    return composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
  }

  private companion object {
    const val INPUT_PROPERTY = "rc.embedded.input"
    const val OUTPUT_PROPERTY = "rc.embedded.output"
    const val CAPTURE_TAG = "rc-embedded-capture"
  }
}
