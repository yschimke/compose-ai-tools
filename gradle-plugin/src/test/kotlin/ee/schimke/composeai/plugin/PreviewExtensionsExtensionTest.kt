package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class PreviewExtensionsExtensionTest {

  @Test
  fun `a11y preview extension disabled by default`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isFalse()
  }

  @Test
  fun `enabling all checks for a11y preview extension enables accessibility render pass`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.previewExtensions.a11y(Action<A11yPreviewExtension> { enableAllChecks() })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `generic a11y preview extension block can enable all checks`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.previewExtensions.extension(
      "a11y",
      Action<PreviewExtensionConfig> { enableAllChecks() },
    )

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `selecting an a11y check enables accessibility render pass`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.previewExtensions.a11y(Action<A11yPreviewExtension> { checks.add("atf") })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isTrue()
  }

  @Test
  fun `selecting an unrelated a11y check leaves accessibility render pass disabled`() {
    val project = ProjectBuilder.builder().build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    extension.previewExtensions.a11y(Action<A11yPreviewExtension> { checks.add("contrast") })

    assertThat(AndroidPreviewSupport.resolveA11yEnabled(project, extension).get()).isFalse()
  }
}
