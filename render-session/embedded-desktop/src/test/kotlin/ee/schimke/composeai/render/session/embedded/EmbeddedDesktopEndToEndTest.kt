package ee.schimke.composeai.render.session.embedded

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.render.session.RenderSessionConfig
import java.io.File
import org.junit.Test

/**
 * Embedded Compose Desktop backend against the `:samples:cmp` daemon descriptor: handshake,
 * `extensions/list`, close. Self-skips when the descriptor is absent (built by
 * `./gradlew :samples:cmp:composePreviewDaemonStart`). Rendering is left out: hosting the full
 * Compose Desktop + Skiko classpath in the test JVM costs far more class-loading.
 */
class EmbeddedDesktopEndToEndTest {

  @Test
  fun `opens session against samples_cmp and lists extensions`() {
    val descriptor = locateSamplesCmpDescriptor()
    val previews = File(descriptor.parentFile, "previews.json")
    if (!descriptor.isFile || !previews.isFile) {
      System.err.println(
        "[EmbeddedDesktopEndToEndTest] skipping — descriptor or previews.json missing " +
          "(run `:samples:cmp:composePreviewDaemonStart` + `:samples:cmp:composePreviewDiscover`)"
      )
      return
    }

    EmbeddedDesktopRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptor,
          workspaceRoot = projectRoot(),
          workspaceName = "compose-ai-tools",
        )
      )
      .use { session ->
        assertThat(session.modulePath).isEqualTo(":samples:cmp")
        assertThat(session.initializeResult.daemonVersion).isNotEmpty()

        // The desktop daemon always advertises at least `device/clip` + `device/background` —
        // confirming the handshake came back with a populated extension descriptor rather than
        // the empty default the wire defaults to on parse-only failures.
        val ids = session.listExtensions().extensions.map { it.id }.toSet()
        assertThat(ids).contains("device/clip")
        assertThat(ids).contains("device/background")
      }
  }

  private fun locateSamplesCmpDescriptor(): File {
    val repoRoot = projectRoot()
    return File(repoRoot, "samples/cmp/build/compose-previews/daemon-launch.json")
  }

  /**
   * Walk up from the test JVM's working dir until we find the repo's `settings.gradle.kts`. The
   * test classpath places the working dir somewhere under `render-session/embedded-desktop/` during
   * gradle runs, but absolute paths matter for the descriptor lookup.
   */
  private fun projectRoot(): File {
    var dir: File? = File(".").canonicalFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error(
      "Could not locate repo root (no settings.gradle.kts ancestor of ${File(".").canonicalFile})"
    )
  }
}
