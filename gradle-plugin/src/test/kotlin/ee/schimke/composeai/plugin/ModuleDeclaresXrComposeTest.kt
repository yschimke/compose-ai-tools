package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [AndroidPreviewSupport.moduleDeclaresXrCompose] — the config-time signal
 * that auto-enables the XR subspace render path (registers `composePreviewRenderXr` + pulls the
 * minCompileSdk-36 XR `*-testing` fakes onto the render config) without any `composePreview { }`
 * configuration in the consumer build.
 *
 * Same declarative, IP-safe shape as [AndroidPreviewSupport.hasDirectPreviewDependency]: declared
 * `*Implementation` / `*Api` / `*RuntimeOnly` inspection only, no classpath resolution. The gate
 * must stay closed for non-XR modules so they never pay for the heavyweight XR fakes.
 */
class ModuleDeclaresXrComposeTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `declared androidx_xr_compose dep is detected`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("implementation")
    project.dependencies.add("implementation", "androidx.xr.compose:compose:1.0.0-alpha14")

    assertThat(AndroidPreviewSupport.moduleDeclaresXrCompose(project)).isTrue()
  }

  @Test
  fun `xr compose dep in a variant-scoped bucket is detected`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("debugImplementation")
    project.dependencies.add("debugImplementation", "androidx.xr.compose:compose:1.0.0-alpha14")

    assertThat(AndroidPreviewSupport.moduleDeclaresXrCompose(project)).isTrue()
  }

  @Test
  fun `unrelated dep returns false`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()

    project.configurations.create("implementation")
    project.dependencies.add("implementation", "com.google.guava:guava:33.0.0-jre")
    // A sibling androidx group must not trip the exact-group match.
    project.dependencies.add("implementation", "androidx.compose.ui:ui:1.7.0")

    assertThat(AndroidPreviewSupport.moduleDeclaresXrCompose(project)).isFalse()
  }

  @Test
  fun `module without any declarable buckets returns false without throwing`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    assertThat(AndroidPreviewSupport.moduleDeclaresXrCompose(project)).isFalse()
  }
}
