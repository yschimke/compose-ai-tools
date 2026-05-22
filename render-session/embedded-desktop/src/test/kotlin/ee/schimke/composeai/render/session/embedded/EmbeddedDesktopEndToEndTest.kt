package ee.schimke.composeai.render.session.embedded

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.render.session.RenderSessionConfig
import java.io.File
import org.junit.Test

/**
 * Integration coverage for the embedded Compose Desktop backend against the in-repo `:samples:cmp`
 * daemon descriptor. The test self-skips when the descriptor isn't on disk — the build it depends
 * on is `./gradlew :samples:cmp:composePreviewDaemonStart`, run separately or by CI before this
 * test fires.
 *
 * What's exercised: the full handshake (open + initialize), one read-only protocol call
 * (`extensions/list`), and graceful close. The point is to prove the round-trip works without
 * pulling the whole render-and-fetch flow into a unit test — that surface is huge and rendering a
 * real preview from inside the test JVM would force the test runner to host the full Compose
 * Desktop + Skiko classpath, which is an order of magnitude more class-loading than this module
 * already pays.
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
