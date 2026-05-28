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
 * — declared intent, no classpath resolution, no `Project.findProject` / `evaluationDependsOn`
 * cross-project access. For transitive cross-module preview tooling (issue #241, the CMP-Android
 * `:composeApp` → `:shared` shape) the gate delegates to [CrossProjectMetadataService], which
 * parses `settings.gradle.kts` + each subproject's `build.gradle[.kts]` off disk — also IP-safe.
 * See [CrossProjectMetadataTest] for the parser-level coverage of that fallback.
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
  fun `direct project dep falls through to false when the metadata service is not registered`() {
    // With no [CrossProjectMetadataService] registered (this `ProjectBuilder` harness skips
    // plugin application), a `project(":lib")` dep that would otherwise trigger the deep walk
    // falls through to false. Mirrors the manual-test seam: production code uses
    // [CrossProjectMetadataServiceTest] / functional tests for the deep-walk-enabled case.
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
  }
}
