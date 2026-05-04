package ee.schimke.composeai.plugin.daemon

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.plugin.PreviewExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Defaults guard for the `composePreview.daemon { … }` block. Locks in the stable daemon contract
 * from `docs/daemon/CONFIG.md` — any change to a default needs a corresponding doc update and a
 * deliberate test edit.
 */
class DaemonExtensionTest {

  @Test
  fun `daemon extension exposes documented defaults`() {
    val project = ProjectBuilder.builder().build()
    val daemon = project.objects.newInstance(DaemonExtension::class.java)

    // Master switch on by default — editor integrations use the daemon path unless users
    // explicitly opt out.
    assertThat(daemon.enabled.get()).isTrue()
    // Heap ceiling: DESIGN.md § 9, recycle policy.
    assertThat(daemon.maxHeapMb.get()).isEqualTo(1024)
    // Belt-and-braces render-count cap.
    assertThat(daemon.maxRendersPerSandbox.get()).isEqualTo(1000)
    // Warm spare on by default — pays double idle memory for zero
    // user-visible recycle pause. Off-by-default would be a regression.
    assertThat(daemon.warmSpare.get()).isTrue()
  }

  @Test
  fun `daemon block is reachable via top-level composePreview namespace`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.daemon { enabled.set(false) }

    assertThat(extension.daemon.enabled.get()).isFalse()
  }
}
