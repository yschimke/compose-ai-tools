// A minimal Compose Multiplatform Desktop module whose @Preview functions the
// hosted server renders. Everything resolves from public repositories; the
// compose-preview CLI auto-injects the PUBLISHED ee.schimke.composeai.preview
// plugin (pinned to the CLI's own version) at render time — nothing to declare.
//
// Versions are pinned to match the published plugin's tested stack
// (Kotlin 2.3.21 / Compose Multiplatform 1.10.3). Deliberately foundation-only
// (no material3) to avoid the Compose-MP material3 version-skew the upstream
// catalog calls out, and to keep the dependency graph tiny.
plugins {
  kotlin("jvm") version "2.4.0"
  id("org.jetbrains.compose") version "1.10.3"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

repositories {
  mavenCentral()
  google()
}

dependencies {
  // The desktop Compose stack (runtime, foundation, ui, Skiko) for the current OS.
  implementation(compose.desktop.currentOs)
  // Provides the androidx.compose.ui.tooling.preview.Preview annotation on desktop.
  implementation(compose.components.uiToolingPreview)
}
