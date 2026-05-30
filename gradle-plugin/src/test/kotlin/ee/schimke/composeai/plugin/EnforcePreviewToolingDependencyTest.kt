package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of `composePreview.enforcePreviewToolingDependency` — the explicit escape hatch
 * covering the CMP-Android `:composeApp -> :shared` shape that the IP-banned cross-project walk
 * used to handle implicitly (issues #241, #1546, #1549).
 *
 * The plugin's `apply(...)` sets the convention chain — gradleProperty first, then `true`. An
 * explicit `composePreview { enforcePreviewToolingDependency = … }` in the build script still wins
 * via `Property.set`.
 */
class EnforcePreviewToolingDependencyTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `default convention is true`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.plugins.apply(ComposePreviewPlugin::class.java)

    val extension = project.extensions.getByType(PreviewExtension::class.java)
    assertThat(extension.enforcePreviewToolingDependency.get()).isTrue()
  }

  @Test
  fun `consumer DSL set wins over default`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.plugins.apply(ComposePreviewPlugin::class.java)

    val extension = project.extensions.getByType(PreviewExtension::class.java)
    extension.enforcePreviewToolingDependency.set(false)

    assertThat(extension.enforcePreviewToolingDependency.get()).isFalse()
  }
}
