@file:Suppress("DEPRECATION")

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.runtime)
  implementation(compose.components.uiToolingPreview)
  // Pure-JVM accent / bidi transforms + the `Pseudolocale` enum used to detect `en-XA` / `ar-XB`
  // tags. Renderer applies the around-composable inline (LocalLayoutDirection.Rtl for ar-XB) and
  // rewrites the locale tag before it reaches `LocaleList`.
  implementation(project(":data-pseudolocale-core"))

  testImplementation(libs.junit)
}
