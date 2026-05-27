package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [AndroidPreviewSupport.hasPreviewDependency].
 *
 * The check walks the current module's declared `*Implementation` / `*Api` / `*RuntimeOnly` buckets
 * — declared intent, no classpath resolution, no cross-project access. That keeps the gate
 * compatible with Gradle's Isolated Projects mode (a cross-project `project(":foo")` recursion
 * existed for issue #241 but had to be dropped — see the function's KDoc for the workaround
 * consumers can apply).
 */
class HasPreviewDependencyTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `direct declared dep on a preview signal is detected`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("debugImplementation")
    project.dependencies.add(
      "debugImplementation",
      "org.jetbrains.compose.components:components-ui-tooling-preview:0.0.0-stub",
    )

    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isTrue()
  }

  @Test
  fun `unrelated declared dep returns false`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("debugImplementation")
    project.dependencies.add("debugImplementation", "com.google.guava:guava:33.0.0-jre")

    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isFalse()
  }

  @Test
  fun `module without any declarable buckets returns false without throwing`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    // Mirrors a fresh module before AGP has wired its variant configurations — the gate must
    // tolerate the empty state instead of NPE'ing.
    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isFalse()
  }

  @Test
  fun `preview signal on a sibling project is NOT followed across module boundaries`() {
    // Regression marker for the issue #241 trade-off: the cross-project walk that used to catch
    // `:app -> :shared (preview tooling)` was dropped to comply with Isolated Projects. Consumers
    // hitting this shape now either declare the preview-tooling dep on `:app` too, or apply
    // `id("ee.schimke.composeai.preview")` to `:shared` directly so previews are discovered there.
    // Pin the current behaviour here so a future revert reintroducing the recursion gets caught
    // by a failing test (and prompts the IP-safe redesign tracked in the follow-up issue).
    val rootProject = ProjectBuilder.builder().withName("root").withProjectDir(tmp.root).build()
    val lib =
      ProjectBuilder.builder()
        .withName("lib")
        .withProjectDir(tmp.newFolder("lib"))
        .withParent(rootProject)
        .build()
    val app =
      ProjectBuilder.builder()
        .withName("app")
        .withProjectDir(tmp.newFolder("app"))
        .withParent(rootProject)
        .build()

    lib.plugins.apply("java-library")
    app.plugins.apply("java")
    lib.dependencies.add(
      "api",
      "org.jetbrains.compose.components:components-ui-tooling-preview:1.7.5",
    )

    val implementation = app.configurations.getByName("implementation")
    implementation.dependencies.add(app.dependencies.project(mapOf("path" to ":lib")))

    assertThat(AndroidPreviewSupport.hasPreviewDependency(app, "debug")).isFalse()
  }
}
