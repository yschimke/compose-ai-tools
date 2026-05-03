package ee.schimke.composeai.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class ComposeAiJvmConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureCommonJvm()
  }
}

private fun Project.configureCommonJvm() {
  pluginManager.withPlugin("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    }
    tasks.withType<Test>().configureEach { useJUnit() }
  }
}
