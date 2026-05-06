// `:data-uiautomator-prototype-android` — exploratory prototype.
//
// Investigates whether a UIAutomator-shaped query/action API is useful for the Robolectric
// renderer + interactive-session host. Answers two questions:
//
//   1. Can selectors run against the local View tree by going through `AccessibilityNodeInfo`
//      (`view.createAccessibilityNodeInfo()`) instead of UiAutomation's on-device pipe?
//   2. Of UIAutomator's surface, what's reusable inside Robolectric and what isn't?
//
// Findings are written up in the KDoc on `UiAutomatorPrototype.kt`. **Not published.** If we
// promote this, it lands as `:data-uiautomator-core` next to `:data-a11y-core` with a
// hierarchy producer and connector matching the `:data-a11y-*` shape.
plugins {
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.uiautomator.prototype" }

dependencies {
  // Selector JSON wire format — needed so the matcher can travel across the daemon bridge
  // (DispatchUiAutomator envelope, see docs/daemon/INTERACTIVE-ANDROID.md) and the MCP
  // record_preview surface without forcing host code onto the prototype's classpath.
  implementation(libs.kotlinx.serialization.json)

  // Compose-side traversal walks `SemanticsNode` and dispatches actions through
  // `SemanticsActions` lambdas. `compileOnly` for the same reason `:renderer-android` does it
  // — the consumer's classpath supplies the actual runtime, and we don't want to pin a
  // specific Compose version onto downstream projects. Tests use full runtime classes via
  // `testImplementation`.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.runtime)
  compileOnly("androidx.compose.ui:ui-test-junit4")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
}
