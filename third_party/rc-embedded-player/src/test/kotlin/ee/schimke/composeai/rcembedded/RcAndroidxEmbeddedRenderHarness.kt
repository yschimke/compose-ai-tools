/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.RemoteImageSupport
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The androidx.dev counterpart to [RcEmbeddedRenderHarness]. It deliberately duplicates the small
 * host harness while importing the upstream package, so a diff page compares the exact same bytes,
 * density, bounds, settling, and Robolectric canvas through two independently compiled players: the
 * vendored `ee.schimke...` artifact and AndroidX's snapshot `androidx.compose...` artifact.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcAndroidxEmbeddedRenderHarness(private val entry: RcEmbeddedRenderHarness.Entry) {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun render() {
    val inputDir = inputDir()
    assumeTrue("no $INPUT_PROPERTY configured — nothing to rasterize", inputDir != null)
    val outputDir =
      File(
        requireNotNull(System.getProperty(OUTPUT_PROPERTY)) {
          "$OUTPUT_PROPERTY must be set alongside $INPUT_PROPERTY"
        }
      )
    outputDir.mkdirs()

    val png = File(outputDir, "${entry.id}.png")
    val err = File(outputDir, "${entry.id}.error")
    png.delete()
    err.delete()

    runCatching { renderToBitmap(File(inputDir, "${entry.id}.rc").readBytes()) }
      .onSuccess { bitmap ->
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
      .onFailure { t -> err.writeText("${t::class.java.simpleName}: ${t.message?.take(500)}") }
  }

  private fun renderToBitmap(bytes: ByteArray): Bitmap {
    composeRule.setContent {
      val hostDensity = LocalDensity.current
      val documentDensity = Density(entry.density, hostDensity.fontScale)
      CompositionLocalProvider(LocalDensity provides documentDensity) {
        Box(
          Modifier.size(
            with(documentDensity) { entry.width.toDp() },
            with(documentDensity) { entry.height.toDp() },
          )
        ) {
          RemoteImageSupport.enableEncodedImageReferences()
          val document = remember { RemoteDocument(bytes) }
          ExperimentalRemoteDocumentPlayer(document = document, modifier = Modifier.fillMaxSize())
        }
      }
    }

    composeRule.waitForIdle()
    val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
    root.measure(
      MeasureSpec.makeMeasureSpec(entry.width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(entry.height, MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, entry.width, entry.height)

    return Bitmap.createBitmap(entry.width, entry.height, Bitmap.Config.ARGB_8888).also {
      root.draw(Canvas(it))
    }
  }

  companion object {
    private const val INPUT_PROPERTY = "rc.androidx.embedded.input"
    private const val OUTPUT_PROPERTY = "rc.androidx.embedded.output"

    private fun inputDir(): File? =
      System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory }

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun documents(): List<Array<Any>> {
      val dir =
        inputDir() ?: return listOf(arrayOf(RcEmbeddedRenderHarness.Entry("<none staged>", 1, 1)))
      val manifest = File(dir, "manifest.json")
      if (!manifest.isFile) {
        return listOf(arrayOf(RcEmbeddedRenderHarness.Entry("<no manifest.json>", 1, 1)))
      }
      return Json.decodeFromString<List<RcEmbeddedRenderHarness.Entry>>(manifest.readText())
        .filter { File(dir, "${it.id}.rc").isFile }
        .map { arrayOf(it) }
    }
  }
}
