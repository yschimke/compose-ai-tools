package ee.schimke.composeai.render.session.embedded

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import java.io.File
import org.junit.Test

/**
 * Smoke coverage for the embedded Desktop backend. Opening a real session needs the daemon-launch
 * descriptor produced by `composePreviewDaemonStart` against the in-repo `:samples:cmp` module,
 * which isn't built in the unit-test classpath — that side is exercised end-to-end by
 * `:samples:cmp:renderAllPreviews` runs once we wire one up.
 *
 * These tests cover the error-handling and contract surface that doesn't need a live daemon:
 * descriptor-missing, the `isAvailable()` probe, the `RenderSessionBackend.Embedded` advertisement.
 */
class EmbeddedDesktopRenderSessionTest {

  @Test
  fun `backend kind is Embedded`() {
    assertThat(EmbeddedDesktopRenderSessions.backendKind).isEqualTo(RenderSessionBackend.Embedded)
  }

  @Test
  fun `isAvailable returns true when the daemon classpath is on the calling JVM`() {
    // This module has `:daemon:desktop` on its runtime classpath, so the probe must resolve.
    assertThat(EmbeddedDesktopRenderSessions.isAvailable()).isTrue()
  }

  @Test
  fun `open throws cleanly when the descriptor file is missing`() {
    val tempDir = java.nio.file.Files.createTempDirectory("embedded-desktop-test").toFile()
    tempDir.deleteOnExit()
    val missingDescriptor = File(tempDir, "build/compose-previews/daemon-launch.json")

    val ex =
      runCatching {
          EmbeddedDesktopRenderSessions.open(
            RenderSessionConfig(descriptorPath = missingDescriptor, workspaceRoot = tempDir)
          )
        }
        .exceptionOrNull()

    assertThat(ex).isInstanceOf(RenderSessionException::class.java)
    assertThat(ex!!.message).contains("Daemon launch descriptor not found")
    assertThat(ex.message).contains(missingDescriptor.path)
  }

  @Test
  fun `open throws cleanly when the descriptor is unreadable JSON`() {
    val tempDir = java.nio.file.Files.createTempDirectory("embedded-desktop-test-bad").toFile()
    tempDir.deleteOnExit()
    val badDescriptor =
      File(tempDir, "build/compose-previews/daemon-launch.json").apply {
        parentFile.mkdirs()
        writeText("not valid json {")
      }

    val ex =
      runCatching {
          EmbeddedDesktopRenderSessions.open(
            RenderSessionConfig(descriptorPath = badDescriptor, workspaceRoot = tempDir)
          )
        }
        .exceptionOrNull()

    assertThat(ex).isInstanceOf(RenderSessionException::class.java)
    assertThat(ex!!.message).contains("unreadable")
  }
}
