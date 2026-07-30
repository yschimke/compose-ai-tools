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

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.player.compose.embedded.JvmRemoteContext
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Exercises [JvmRemoteContext.loadBitmap] — the one platform-bound member of the draw
 * `RemoteContext` — on a plain desktop JVM, decoding through skiko with no Android and no
 * Robolectric.
 *
 * The contract under test is the one `resolveBitmap` depends on: `loadBitmap(id, …)` decodes the
 * bytes and caches the result under `id`, so a subsequent read of the store yields a Compose
 * [ImageBitmap] of the document's declared size. The store is untyped, so the test reads the id
 * back from its own [SnapshotRemoteComposeState] rather than through a (non-existent) typed getter
 * — the same value the jvm image seam's `resolveImage` will later read.
 *
 * Like [DesktopTextPlatformTest] this needs skiko's natives (decode + raster both touch them), so
 * it skips loudly where they cannot load rather than failing every method with a cryptic linkage
 * error.
 */
class JvmRemoteContextBitmapTest {

  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "JvmRemoteContextBitmapTest skipped entirely: skiko's native library did not load, so " +
          "nothing here exercised the jvm bitmap decode. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  /** A solid-red W×H image encoded as PNG bytes. */
  private fun redPng(width: Int, height: Int): ByteArray {
    val rgba = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
      rgba[i * 4] = 0xFF.toByte() // R
      rgba[i * 4 + 3] = 0xFF.toByte() // A
    }
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val image = Image.makeRaster(info, rgba, width * 4)
    return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
  }

  @Test
  fun `an inline png decodes to an image bitmap of the declared size`() {
    val state = SnapshotRemoteComposeState()
    val context = JvmRemoteContext(state)
    context.loadBitmap(ID, BitmapData.ENCODING_INLINE, BitmapData.TYPE_PNG_8888, 3, 2, redPng(3, 2))

    val cached = state.getFromId(ID)
    assertTrue("cached value is a Compose ImageBitmap, got $cached", cached is ImageBitmap)
    cached as ImageBitmap
    assertEquals(3, cached.width)
    assertEquals(2, cached.height)
  }

  @Test
  fun `raw rgba pixels build an image bitmap of the declared size`() {
    val width = 2
    val height = 2
    val pixels = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
      pixels[i * 4 + 2] = 0xFF.toByte() // B
      pixels[i * 4 + 3] = 0xFF.toByte() // A
    }
    val state = SnapshotRemoteComposeState()
    val context = JvmRemoteContext(state)
    context.loadBitmap(
      ID,
      BitmapData.ENCODING_INLINE,
      BitmapData.TYPE_RAW8888,
      width,
      height,
      pixels,
    )

    val cached = state.getFromId(ID)
    assertTrue("cached value is a Compose ImageBitmap, got $cached", cached is ImageBitmap)
    cached as ImageBitmap
    assertEquals(width, cached.width)
    assertEquals(height, cached.height)
  }

  @Test
  fun `a non-inline encoding caches nothing`() {
    val state = SnapshotRemoteComposeState()
    val context = JvmRemoteContext(state)
    // ENCODING_URL carries no inline bytes — the (deferred) host loader's job, not this decode.
    context.loadBitmap(ID, BitmapData.ENCODING_URL, BitmapData.TYPE_PNG_8888, 3, 2, redPng(3, 2))
    assertNull("a URL-encoded bitmap must not be decoded from inline bytes", state.getFromId(ID))
  }

  @Test
  fun `a raw buffer too short for the declared size caches nothing rather than crashing`() {
    val state = SnapshotRemoteComposeState()
    val context = JvmRemoteContext(state)
    // 4×4 RGBA needs 64 bytes; hand it 8 and the decode must reject, not read out of bounds.
    context.loadBitmap(ID, BitmapData.ENCODING_INLINE, BitmapData.TYPE_RAW8888, 4, 4, ByteArray(8))
    assertNull(state.getFromId(ID))
  }

  @Test
  fun `malformed png bytes cache nothing rather than crashing`() {
    val state = SnapshotRemoteComposeState()
    val context = JvmRemoteContext(state)
    context.loadBitmap(
      ID,
      BitmapData.ENCODING_INLINE,
      BitmapData.TYPE_PNG_8888,
      3,
      2,
      byteArrayOf(1, 2, 3, 4, 5),
    )
    assertNull(state.getFromId(ID))
  }

  private companion object {
    const val ID = 42

    var skikoLoadFailure: String? = null

    /**
     * Whether Skia is callable at all — decided once by touching a class that loads the native lib.
     */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
  }
}
