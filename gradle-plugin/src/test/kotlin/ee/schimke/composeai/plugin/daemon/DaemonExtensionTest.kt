package ee.schimke.composeai.plugin.daemon

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.plugin.PreviewExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Defaults guard for the `composePreview.daemon { … }` block. Locks in the v1 contract from
 * `docs/daemon/CONFIG.md` — any change to a default needs a corresponding doc update and a
 * deliberate test edit.
 */
class DaemonExtensionTest {

  @Test
  fun `daemon extension exposes documented defaults`() {
    val project = ProjectBuilder.builder().build()
    val daemon = project.objects.newInstance(DaemonExtension::class.java)

    // Master switch off-by-default — the daemon is available unless the build opts out.
    assertThat(daemon.disabled.get()).isFalse()
    // Heap ceiling: DESIGN.md § 9, recycle policy.
    assertThat(daemon.maxHeapMb.get()).isEqualTo(1024)
    // Belt-and-braces render-count cap.
    assertThat(daemon.maxRendersPerSandbox.get()).isEqualTo(1000)
    // Warm spare on by default — pays double idle memory for zero
    // user-visible recycle pause. Off-by-default would be a regression.
    assertThat(daemon.warmSpare.get()).isTrue()
  }

  @Test
  fun `daemon block is reachable directly under composePreview`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.objects.newInstance(PreviewExtension::class.java)

    // The action-form configuration entry point — the shape consumers will type in build scripts.
    extension.daemon { disabled.set(true) }

    assertThat(extension.daemon.disabled.get()).isTrue()
  }
}
