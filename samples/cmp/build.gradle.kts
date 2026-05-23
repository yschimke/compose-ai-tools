@file:Suppress("DEPRECATION")

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.uiTooling)
  implementation(compose.components.uiToolingPreview)
  // `@ScrollingPreview(modes = [LONG, GIF])` — drives the desktop renderer's scroll path. The
  // gradle plugin's discovery picks up the annotation by FQN even without it on the consumer's
  // compile classpath, but the sample composable references the annotation directly so the
  // dependency is needed for compilation.
  implementation(project(":preview-annotations"))
}
