package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of `composePreview.failOnMissingPreviewTooling` — the opt-in for the task-time
 * validator that hard-fails `composePreviewRender` when the resolved runtime classpath has no
 * preview-tooling coord. Default is `false` so aggregator modules (the `:demo-app` shape from
 * yschimke/homeassistant-remotecompose) pass through; a CLI override via
 * `-PcomposePreview.failOnMissingPreviewTooling=true` flips a single run; an explicit
 * `composePreview { failOnMissingPreviewTooling = … }` in the build script still wins via
 * `Property.set`.
 */
class FailOnMissingPreviewToolingTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `default convention is false`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.plugins.apply(ComposePreviewPlugin::class.java)

    val extension = project.extensions.getByType(PreviewExtension::class.java)
    assertThat(extension.failOnMissingPreviewTooling.get()).isFalse()
  }

  @Test
  fun `consumer DSL set wins over default`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.plugins.apply(ComposePreviewPlugin::class.java)

    val extension = project.extensions.getByType(PreviewExtension::class.java)
    extension.failOnMissingPreviewTooling.set(true)

    assertThat(extension.failOnMissingPreviewTooling.get()).isTrue()
  }
}
