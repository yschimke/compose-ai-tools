package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class DataPluginsExtensionTest {

  @Test
  fun `a11y plugin disabled by default`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isFalse()
  }

  @Test
  fun `enabling all checks for a11y plugin enables accessibility render pass`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.dataPlugins.a11y(Action<A11yDataPluginExtension> { enableAllChecks() })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `generic a11y plugin block can enable all checks`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.dataPlugins.plugin("a11y", Action<DataPluginExtension> { enableAllChecks() })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `selecting an a11y check enables accessibility render pass`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.dataPlugins.a11y(Action<A11yDataPluginExtension> { checks.add("atf") })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `selecting an unrelated a11y check leaves accessibility render pass disabled`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.dataPlugins.a11y(Action<A11yDataPluginExtension> { checks.add("contrast") })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isFalse()
  }
}
