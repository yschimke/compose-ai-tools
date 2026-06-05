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
 * 2. **Compose compiler plugin applied AND any declared `project(":...")` dep** → pass. The
 *    Compose-plugin gate keeps the tier-2 path scoped to modules that actually compile Compose
 *    code, so utility / network modules that auto-inject the plugin (via the CLI's init script
 *    `withPlugin("com.android.library") { applyComposeAiPreview() }` hook) stay silent. Tasks
 *    register AND we wire [ValidatePreviewToolingPresentTask] as a `dependsOn` of the render so the
 *    authoritative check happens at task action time against `${variant}RuntimeClasspath`'s
 *    resolved graph.
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
    // Auto-inject applies the plugin to *every* AGP module — including pure utility / network
    // modules with `project(":...")` deps but no Compose (the nowinandroid `:core:network` failure
    // shape). Without the Compose-plugin guard, the tier-2 path would register tasks on those
    // modules and `registerAndroidTasks` would inject ui-test-manifest into testImplementation,
    // leaking Compose into builds that didn't want it. The Compose Kotlin compiler plugin isn't on
    // this test's classpath so we can't *apply* it to make the affirmative case work in a unit
    // test (functional + integration tests cover that); what we can pin here is the
    // no-Compose-plugin case stays closed even with declared project deps.
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
