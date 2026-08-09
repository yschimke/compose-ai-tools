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

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Limits
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.BitmapData
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A document carrying a URL-encoded bitmap must parse.
 *
 * `Limits.ENABLE_IMAGE_URLS` is a public mutable global in `remote-core` that ships `false`. While
 * it is off, `BitmapData.read` throws `URL image not supported [<id>]` for any `BitmapData` with
 * `ENCODING_URL`, and because that read happens inside `inflateFromBuffer` the throw fails the
 * *whole* document, not just the image. [parseDocument] opts in, so the document parses and the
 * image slot is simply left unresolved — nothing here supplies a loader, and parsing a URL is not
 * fetching one.
 *
 * A real regression, not a hypothetical: two Home Assistant picture-entity previews
 * (`PictureEntity_AppMode_{Dark,Light}`, whose app-mode strategy references the camera frame by URL
 * instead of baking it) were the only 2 of that catalog's 122 documents this lane could not render,
 * while the JS and CMP players drew both. It was easy to misread as a missing operation, because
 * the parse died reporting an opcode — yet 106 documents carrying that same opcode parsed fine, and
 * the real trigger was the image encoding. Hence [urlBitmapIsRejectedWhenTheGlobalIsOff]: it pins
 * the actual failure mode so the two are told apart next time.
 */
class RcJvmUrlBitmapParseTest {

  /**
   * Smallest document that reaches the branch: a header plus one URL-form `BitmapData`. Written
   * through upstream's own `BitmapData.apply` overload — the one that packs `type` and `encoding`
   * into the high halves of the width/height fields — so the test exercises the real wire layout
   * instead of a local re-implementation of it that could drift.
   */
  private fun urlBitmapDocument(imageId: Int): ByteArray {
    val buffer = RemoteComposeBuffer()
    buffer.header(WIDTH, HEIGHT, DENSITY, /* capabilities= */ 0L)
    val wire = buffer.buffer
    BitmapData.apply(
      wire,
      imageId,
      BitmapData.TYPE_PNG,
      WIDTH.toShort(),
      BitmapData.ENCODING_URL,
      HEIGHT.toShort(),
      URL.toByteArray(),
    )
    return wire.buffer.copyOf(wire.size())
  }

  @Test
  fun urlBitmapDocumentParses() {
    val document = parseDocument(urlBitmapDocument(imageId = 42))

    val bitmap = document.operations.filterIsInstance<BitmapData>().singleOrNull()
    assertNotNull("the URL bitmap should survive the parse", bitmap)
    assertEquals(42, bitmap!!.id)
    assertEquals(WIDTH, bitmap.width)
    assertEquals(HEIGHT, bitmap.height)
  }

  /**
   * The failure being guarded against, asserted against the shipped default so this still means
   * something if AndroidX ever flips it: with the global off the parse dies, and it dies naming the
   * *image*, never an operation code.
   */
  @Test
  fun urlBitmapIsRejectedWhenTheGlobalIsOff() {
    val document = urlBitmapDocument(imageId = 7)
    val restore = Limits.ENABLE_IMAGE_URLS
    val failure =
      try {
        Limits.ENABLE_IMAGE_URLS = false
        runCatching {
            ByteArrayInputStream(document).use {
              CoreDocument().initFromBuffer(RemoteComposeBuffer.fromInputStream(it))
            }
          }
          .exceptionOrNull()
      } finally {
        Limits.ENABLE_IMAGE_URLS = restore
      }

    assertNotNull("expected the parse to fail while URL images are disabled", failure)
    assertEquals("URL image not supported [7]", failure!!.message)
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 64
    const val DENSITY = 1f
    const val URL = "https://example.invalid/camera.png"
  }
}
