package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `composePreview.excludeActivities` reaches `composePreviewDiscover` as a task input (so editing
 * it re-runs discovery and is part of the configuration-cache entry), with `.Relative` names
 * resolved against the Android namespace.
 */
class ExcludeActivitiesWiringTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `excludeActivities defaults to empty`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

    assertThat(extension.excludeActivities.get()).isEmpty()
    assertThat(discoverTask(project, extension).excludeActivities.get()).isEmpty()
  }

  @Test
  fun `the discover task reads excludeActivities from the extension lazily`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    val discover = discoverTask(project, extension)

    // Set after the task is registered, as a consumer's `composePreview { }` block can be.
    extension.excludeActivities.add("com.example.quest.QuestActivity")

    assertThat(discover.excludeActivities.get()).containsExactly("com.example.quest.QuestActivity")
  }

  @Test
  fun `excludeActivities is a discover task input`() {
    val getter = DiscoverPreviewsTask::class.java.getMethod("getExcludeActivities")
    assertThat(getter.isAnnotationPresent(org.gradle.api.tasks.Input::class.java)).isTrue()
  }

  @Test
  fun `relative entries resolve against the namespace`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    val namespace = project.objects.property(String::class.java)
    val resolved = AndroidPreviewSupport.resolvedExcludeActivities(extension, namespace)

    extension.excludeActivities.addAll(
      ".QuestActivity",
      "com.example.quest.QuestActivity",
      "com.other.PanelActivity",
    )
    namespace.set("com.example.quest")

    assertThat(resolved.get())
      .containsExactly("com.example.quest.QuestActivity", "com.other.PanelActivity")
      .inOrder()
  }

  @Test
  fun `a missing namespace leaves entries as written`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    val extension = project.extensions.create("composePreview", PreviewExtension::class.java)
    val namespace = project.objects.property(String::class.java)
    extension.excludeActivities.add("com.example.quest.QuestActivity")

    assertThat(AndroidPreviewSupport.resolvedExcludeActivities(extension, namespace).get())
      .containsExactly("com.example.quest.QuestActivity")
  }

  private fun discoverTask(
    project: org.gradle.api.Project,
    extension: PreviewExtension,
  ): DiscoverPreviewsTask {
    project.configurations.create("debugRuntimeClasspath") {
      isCanBeResolved = true
      isCanBeConsumed = false
    }
    return ComposePreviewTasks.registerDiscoverTask(
        project,
        project.files(),
        { "debugRuntimeClasspath" },
        project.layout.buildDirectory.dir("compose-previews"),
        extension,
      ) {}
      .get()
  }
}
