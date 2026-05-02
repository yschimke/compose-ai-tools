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
    // D2 — attachA11y on by default so the data-product surface advertises a11y kinds.
    // Flipping this to false would silently break the focus-mode overlay and the
    // VS Code `composePreview.a11y.alwaysSubscribe` setting; defaults are pinned here
    // so a regression is loud.
    assertThat(daemon.attachA11y.get()).isTrue()
  }

  @Test
  fun `attachA11y is settable per consumer`() {
    val project = ProjectBuilder.builder().build()
    val daemon = project.objects.newInstance(DaemonExtension::class.java)
    daemon.attachA11y.set(false)
    assertThat(daemon.attachA11y.get()).isFalse()
  }

  @Test
  fun `daemon block is reachable via legacy experimental namespace`() {
    val project = ProjectBuilder.builder().build()
    val experimental = project.objects.newInstance(ExperimentalExtension::class.java)

    // The action-form configuration entry point — the shape consumers will
    // type in build scripts.
    experimental.daemon { enabled.set(true) }

    assertThat(experimental.daemon.enabled.get()).isTrue()
  }

  @Test
  fun `daemon block is reachable via top-level composePreview namespace`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.daemon { enabled.set(false) }

    assertThat(extension.daemon.enabled.get()).isFalse()
  }
}
