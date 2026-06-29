package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for [captureDecodableFrame] — the bounded re-capture the
 * multi-frame paths wrap around `captureRoboImage` so a single undecodable
 * frame PNG from Robolectric's NATIVE graphics backend doesn't fail an
 * otherwise-green render (the Compose Preview
 * `ShaderRaymarchAnimatedPreview … frame_39 … ImageIO could not read it`
 * flake). The `capture` lambda stands in for `captureRoboImage`; no
 * Robolectric required.
 */
class CaptureDecodableFrameTest {

    private fun writeGoodPng(file: File) {
        ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB), "png", file)
    }

    @Test
    fun `returns on the first attempt when the frame decodes`() {
        val file = File.createTempFile("frame_ok_", ".png").apply { deleteOnExit() }
        var attempts = 0
        captureDecodableFrame(file, role = "animation") { f ->
            attempts++
            writeGoodPng(f)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `re-captures past a transient undecodable frame`() {
        val file = File.createTempFile("frame_flaky_", ".png").apply { deleteOnExit() }
        var attempts = 0
        captureDecodableFrame(file, role = "animation") { f ->
            attempts++
            // First write lands a corrupt frame (no PNG signature); the
            // re-capture produces a clean one, mirroring the glitch clearing
            // on a fresh encode.
            if (attempts == 1) f.writeBytes("not a png".toByteArray()) else writeGoodPng(f)
        }
        assertEquals(2, attempts)
        // The good frame is what survives on disk.
        assertEquals(8, FramePngReader.decode(file, role = "animation").width)
    }

    @Test
    fun `re-throws the decode error after attempts are exhausted`() {
        val file = File.createTempFile("frame_bad_", ".png").apply { deleteOnExit() }
        var attempts = 0
        val error = try {
            captureDecodableFrame(file, role = "focus GIF") { f ->
                attempts++
                f.writeBytes(ByteArray(0)) // every attempt writes an empty frame
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals(FRAME_CAPTURE_ATTEMPTS, attempts)
        assertTrue("expected a decode error", error != null)
        assertTrue(error!!.message, error.message!!.contains("focus GIF"))
        assertTrue(error.message, error.message!!.contains("empty"))
    }
}
