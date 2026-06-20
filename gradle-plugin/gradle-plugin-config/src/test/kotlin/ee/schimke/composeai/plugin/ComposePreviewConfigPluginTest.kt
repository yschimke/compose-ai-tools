package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Contract for the configuration-only plugin (`ee.schimke.composeai.preview.config`): it must
 * contribute the `composePreview { }` DSL and the discovery marker, and nothing else — no render /
 * discovery tasks, no Gradle-version enforcement. That "config without runtime" shape is the whole
 * reason the plugin exists, so these assertions guard it.
 */
class ComposePreviewConfigPluginTest {

  @Test
  fun `registers the composePreview extension`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("ee.schimke.composeai.preview.config")

    val extension =
      project.extensions.findByName(ComposePreviewDsl.EXTENSION_NAME) as? PreviewExtension
    assertThat(extension).isNotNull()
    // Default variant convention is wired by the shared helper, not just by the runtime plugin.
    assertThat(extension!!.variant.get()).isEqualTo("debug")
  }

  @Test
  fun `registers the applied marker task`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("ee.schimke.composeai.preview.config")

    assertThat(project.tasks.findByName(ComposePreviewDsl.APPLIED_TASK_NAME)).isNotNull()
  }

  @Test
  fun `does not register render or discovery tasks`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("ee.schimke.composeai.preview.config")

    // The config-only plugin must never pull the rendering runtime in. Those tasks are the
    // runtime plugin's responsibility and only exist once the CLI injects it.
    assertThat(project.tasks.findByName("composePreviewDiscover")).isNull()
    assertThat(project.tasks.findByName("composePreviewRender")).isNull()
    assertThat(project.tasks.findByName("composePreviewRenderAll")).isNull()
  }

  @Test
  fun `applying twice reuses the same extension instance`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("ee.schimke.composeai.preview.config")
    val first = project.extensions.getByName(ComposePreviewDsl.EXTENSION_NAME)

    // Second apply is a no-op (Gradle dedups plugin application), but the create-or-find helper
    // is what guarantees coexistence with the runtime plugin without a double-registration crash.
    project.pluginManager.apply("ee.schimke.composeai.preview.config")
    val second = project.extensions.getByName(ComposePreviewDsl.EXTENSION_NAME)

    assertThat(second).isSameInstanceAs(first)
  }
}
