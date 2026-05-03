package ee.schimke.composeai.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class ComposeAiAndroidConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureCommonAndroid()
  }
}

private fun Project.configureCommonAndroid() {
  pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> { configureLibraryDefaults() }
  }
  pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> { configureApplicationDefaults() }
  }
}

private fun LibraryExtension.configureLibraryDefaults() {
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

private fun ApplicationExtension.configureApplicationDefaults() {
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}
