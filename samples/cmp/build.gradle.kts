@file:Suppress("DEPRECATION")

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.composeai.preview")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.ui.tooling)
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)
  // `@ScrollingPreview(modes = [LONG, GIF])` — drives the desktop renderer's scroll path. The
  // gradle plugin's discovery picks up the annotation by FQN even without it on the consumer's
  // compile classpath, but the sample composable references the annotation directly so the
  // dependency is needed for compilation.
  implementation(project(":preview-annotations"))
  // `LottiePreview(...)` — renders a Lottie `.json` asset (from src/main/resources) at a fixed
  // progress through the desktop renderer. Brings Compottie transitively.
  implementation(project(":lottie-preview-runtime"))
  // `previewOverride*` — opt-in editable knobs (label / list length / per-item indexed values) the
  // daemon can seed and a served bundle can present as editable controls.
  implementation(project(":data-preview-overrides-runtime"))
}
