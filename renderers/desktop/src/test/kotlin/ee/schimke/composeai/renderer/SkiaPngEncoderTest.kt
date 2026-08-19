package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encode has to survive skiko changing `Image.encodeToData`'s parameter list under it — 0.150.0
 * added one, which is what broke every desktop capture against Compose Multiplatform 1.12.0-beta01
 * while the task still exited 0 (compose-ai-tools#4190).
 *
 * The version this build resolves can only ever prove ONE of the shapes, so the shape it is not
 * compiled against is exercised against a stand-in with a bridge of the same form. That is the
 * whole reason [SkiaPngEncoder.bind] takes the owner class rather than reaching for `Image` itself.
 */
class SkiaPngEncoderTest {

  /** The pre-0.150 bridge: `encodeToData(format, quality)`. */
  class Skiko0148 {
    companion object {
      @JvmStatic var received: List<Any?> = emptyList()

      @JvmStatic
      @Suppress("FunctionName", "UNUSED_PARAMETER")
      fun `encodeToData$default`(
        receiver: Any?,
        format: EncodedImageFormat?,
        quality: Int,
        mask: Int,
        marker: Any?,
      ): String {
        received = listOf(receiver, format, quality, mask, marker)
        return "encoded"
      }
    }
  }

  /** The 0.150 bridge: `encodeToData(format, quality, compressionLevel)`. */
  class Skiko0150 {
    companion object {
      @JvmStatic var received: List<Any?> = emptyList()

      @JvmStatic
      @Suppress("FunctionName", "UNUSED_PARAMETER")
      fun `encodeToData$default`(
        receiver: Any?,
        format: EncodedImageFormat?,
        quality: Int,
        compressionLevel: Int,
        mask: Int,
        marker: Any?,
      ): String {
        received = listOf(receiver, format, quality, compressionLevel, mask, marker)
        return "encoded"
      }
    }
  }

  @Test
  fun `binds the two-parameter bridge and asks skiko for its own defaults`() {
    val receiver = Any()
    val binding = SkiaPngEncoder.bind(Skiko0148::class.java)
    assertEquals("encoded", binding.invoke(receiver))
    assertEquals("Image.encodeToData(EncodedImageFormat, int)", binding.description)
    // Format is ours; the mask clears only bit 0, so every other parameter takes skiko's default
    // rather than a value invented on this side of the classpath.
    assertEquals(listOf(receiver, EncodedImageFormat.PNG, 0, -2, null), Skiko0148.received)
  }

  @Test
  fun `binds the three-parameter bridge skiko 0_150 introduced, unchanged otherwise`() {
    val receiver = Any()
    val binding = SkiaPngEncoder.bind(Skiko0150::class.java)
    assertEquals("encoded", binding.invoke(receiver))
    assertEquals("Image.encodeToData(EncodedImageFormat, int, int)", binding.description)
    // The added parameter is masked out too — the renderer never names a compression level it would
    // then have to keep in step with skiko's own.
    assertEquals(listOf(receiver, EncodedImageFormat.PNG, 0, 0, -2, null), Skiko0150.received)
  }

  @Test
  fun `refuses a class with no bridge at all, rather than encoding nothing quietly`() {
    val thrown = runCatching { SkiaPngEncoder.bind(String::class.java) }.exceptionOrNull()
    assertTrue("expected a failure, got $thrown", thrown is IllegalStateException)
    assertTrue(thrown!!.message!!.contains("encodeToData"))
  }

  @Test
  fun `encodes a real render against whatever skiko this build resolves`() {
    val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f))
    val bytes =
      try {
        scene.setContent {
          Layout(modifier = Modifier.fillMaxSize().background(Color.Red)) { _, constraints ->
            layout(constraints.maxWidth, constraints.maxHeight) {}
          }
        }
        scene.render().encodePngData()?.bytes
      } finally {
        scene.close()
      }
    assertTrue("no PNG bytes", bytes != null && bytes.isNotEmpty())
    // The PNG signature, so a silently-empty or non-PNG encode cannot pass.
    assertArrayEquals(
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
      bytes!!.copyOfRange(0, 4),
    )
    assertTrue(SkiaPngEncoder.diagnostic, SkiaPngEncoder.diagnostic.contains("encodeToData("))
  }
}
