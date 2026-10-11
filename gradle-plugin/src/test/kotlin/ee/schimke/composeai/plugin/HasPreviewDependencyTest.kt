package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins [AndroidPreviewSupport.hasPreviewDependency], the config-time gate for registering
 * `composePreview*` tasks. Cheap and Isolated-Projects-safe: inspects declared dependencies only,
 * no resolution or cross-project access. Passes on:
 * 1. a direct preview-tooling coord, or
 * 2. the Compose compiler plugin applied AND a declared `project(":...")` dep. The Compose check
 *    keeps auto-injected utility modules silent; [ValidatePreviewToolingPresentTask] then verifies
 *    the resolved graph at task time.
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
  fun `pure-XR module passes the gate via the androidx_xr_compose signal`() {
    // An @XrSubspacePreview-only module declares androidx.xr.compose but no traditional
    // ui-tooling-preview coord and no project deps. It must still pass the registration gate, or
    // onVariants returns before registerAndroidTasks and composePreviewRenderXr never registers —
    // defeating the zero-config XR path.
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("implementation")
    project.dependencies.add("implementation", "androidx.xr.compose:compose:1.0.0-alpha14")

    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isTrue()
  }

  @Test
  fun `module without any declarable buckets returns false without throwing`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    // Mirrors a fresh module before AGP has wired its variant configurations — the gate must
    // tolerate the empty state instead of NPE'ing.
    assertThat(AndroidPreviewSupport.hasPreviewDependency(project, "debug")).isFalse()
  }

  @Test
  fun `tier-2 gate stays closed without the Compose plugin even when project deps exist`() {
    // Auto-inject applies the plugin to every AGP module; without the Compose-plugin guard, a
    // no-Compose module with project deps (nowinandroid `:core:network`) would get tasks and leaked
    // Compose test deps. The Compose plugin isn't on this classpath, so only the negative case is
    // pinned here.
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

    assertThat(AndroidPreviewSupport.hasPreviewDependency(app, "debug")).isFalse()
    assertThat(AndroidPreviewSupport.hasAnyProjectDependency(app)).isTrue()
    assertThat(AndroidPreviewSupport.isComposeModule(app)).isFalse()
  }
}
