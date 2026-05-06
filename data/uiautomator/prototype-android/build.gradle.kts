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
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.uiautomator.prototype" }

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}
