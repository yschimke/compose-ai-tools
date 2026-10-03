package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class ThemePinningTest {

  @Test
  fun `the gate compares major and minor Kotlin lines`() {
    assertThat(ThemePinning.isSupported("2.4.20", "2.4.20")).isTrue()
    assertThat(ThemePinning.isSupported("2.4.0", "2.4.20")).isTrue()
    assertThat(ThemePinning.isSupported("2.4.30-RC", "2.4.20")).isTrue()
    assertThat(ThemePinning.isSupported("2.3.21", "2.4.20")).isFalse()
    assertThat(ThemePinning.isSupported("2.5.0", "2.4.20")).isFalse()
    assertThat(ThemePinning.isSupported(null, "2.4.20")).isFalse()
  }

  @Test
  fun `a KMP module pins only its JVM and Android targets`() {
    val project = project()
    listOf(
        "desktopMainRuntimeOnly",
        "kotlinCompilerPluginClasspathDesktopMain",
        "iosArm64MainRuntimeOnly",
        "kotlinCompilerPluginClasspathIosArm64Main",
        "kotlinCompilerPluginClasspathMetadataCommonMain",
      )
      .forEach { project.configurations.create(it) }
    ThemePinning.wire(project, multiplatform = true, RUNTIME, PLUGIN)

    assertThat(project.deps("desktopMainRuntimeOnly")).containsExactly(RUNTIME)
    assertThat(project.deps("kotlinCompilerPluginClasspathDesktopMain")).containsExactly(PLUGIN)
    assertThat(project.deps("iosArm64MainRuntimeOnly")).isEmpty()
    assertThat(project.deps("kotlinCompilerPluginClasspathIosArm64Main")).isEmpty()
    assertThat(project.deps("kotlinCompilerPluginClasspathMetadataCommonMain")).isEmpty()
  }

  @Test
  fun `the pair completes whichever half is created second`() {
    val pluginFirst = project()
    ThemePinning.wire(pluginFirst, multiplatform = true, RUNTIME, PLUGIN)
    pluginFirst.configurations.create("kotlinCompilerPluginClasspathDesktopMain")
    assertThat(pluginFirst.deps("kotlinCompilerPluginClasspathDesktopMain")).isEmpty()
    pluginFirst.configurations.create("desktopMainRuntimeOnly")
    assertThat(pluginFirst.deps("kotlinCompilerPluginClasspathDesktopMain")).containsExactly(PLUGIN)

    val runtimeFirst = project()
    ThemePinning.wire(runtimeFirst, multiplatform = true, RUNTIME, PLUGIN)
    runtimeFirst.configurations.create("desktopMainRuntimeOnly")
    runtimeFirst.configurations.create("kotlinCompilerPluginClasspathDesktopMain")
    assertThat(runtimeFirst.deps("kotlinCompilerPluginClasspathDesktopMain"))
      .containsExactly(PLUGIN)
  }

  @Test
  fun `a compiler plugin classpath with no runtime bucket is never attached`() {
    val project = project()
    ThemePinning.wire(project, multiplatform = true, RUNTIME, PLUGIN)
    project.configurations.create("kotlinCompilerPluginClasspathServerMain")
    assertThat(project.deps("kotlinCompilerPluginClasspathServerMain")).isEmpty()
  }

  @Test
  fun `a plain JVM or Android module pairs runtimeOnly with every compilation`() {
    val project = project()
    listOf("runtimeOnly", "kotlinCompilerPluginClasspathMain", "kotlinCompilerPluginClasspathDebug")
      .forEach { project.configurations.create(it) }
    ThemePinning.wire(project, multiplatform = false, RUNTIME, PLUGIN)

    assertThat(project.deps("runtimeOnly")).containsExactly(RUNTIME)
    assertThat(project.deps("kotlinCompilerPluginClasspathMain")).containsExactly(PLUGIN)
    assertThat(project.deps("kotlinCompilerPluginClasspathDebug")).containsExactly(PLUGIN)
  }

  @Test
  fun `the compiler plugin's Kotlin is baked into the plugin`() {
    assertThat(ThemePinning.compilerPluginKotlin).matches("""\d+\.\d+\.\d+.*""")
  }

  private fun project(): Project = ProjectBuilder.builder().build()

  private fun Project.deps(configuration: String): List<String> =
    configurations.getByName(configuration).dependencies.map {
      "${it.group}:${it.name}:${it.version}"
    }

  private companion object {
    const val RUNTIME = "ee.schimke.composeai:theme-pin-runtime:1.0"
    const val PLUGIN = "ee.schimke.composeai:theme-pin-compiler-plugin:1.0"
  }
}
