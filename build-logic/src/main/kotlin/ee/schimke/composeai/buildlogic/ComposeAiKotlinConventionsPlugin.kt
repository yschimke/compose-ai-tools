package ee.schimke.composeai.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

class ComposeAiKotlinConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureCommonKotlin()
  }
}

private fun Project.configureCommonKotlin() {
  pluginManager.withPlugin("com.gradleup.tapmoc") {
    val kotlinCoreLibraries =
      extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("kotlinCoreLibraries")
        .get()
        .requiredVersion

    configureKotlinCompatibility(version = kotlinCoreLibraries)
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
      compilerOptions.freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
    extensions.configure<TapmocExtension> { checkDependencies() }
  }
}
