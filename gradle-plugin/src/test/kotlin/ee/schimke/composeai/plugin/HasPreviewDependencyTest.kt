package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [AndroidPreviewSupport.hasPreviewDependency] — the config-time gate that
 * decides whether to register `composePreview*` tasks for a variant.
 *
 * The gate is intentionally cheap and IP-safe: declared `*Implementation` / `*Api` / `*RuntimeOnly`
 * inspection only, no classpath resolution, no `Project.findProject` / `evaluationDependsOn`
 * cross-project access. Two-tier:
 * 1. **Direct preview-tooling coord** → pass.
 * 2. **Any declared `project(":...")` dep** → pass (we register tasks AND wire
 *    [ValidatePreviewToolingPresentTask] as a `dependsOn` of the render so the authoritative check
 *    happens at task action time against `${variant}RuntimeClasspath`'s resolved graph).
 *
 * The actual transitive verification lives in [ValidatePreviewToolingPresentTaskTest] / functional
 * tests.
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
    assertThat(AndroidPreviewSupport.hasDirectPreviewDependency(project)).isTrue()
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
  fun `project dep on a sibling registers tier-2 gate pass even without direct tooling`() {
    // Issue #1549 path: `:app` declares `project(":lib")` but no direct preview tooling. The
    // config-time gate over-approximates ("yes, tasks should register") so the resolved-graph
    // walk in [ValidatePreviewToolingPresentTask] gets a chance to confirm or reject at task
    // action time. Pins the contract that the cheap gate accepts the CMP-Android shape.
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
    val implementation = app.configurations.getByName("implementation")
    implementation.dependencies.add(app.dependencies.project(mapOf("path" to ":lib")))

    assertThat(AndroidPreviewSupport.hasPreviewDependency(app, "debug")).isTrue()
    assertThat(AndroidPreviewSupport.hasDirectPreviewDependency(app)).isFalse()
    assertThat(AndroidPreviewSupport.hasAnyProjectDependency(app)).isTrue()
  }
}
