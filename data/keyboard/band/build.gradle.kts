// `:data-keyboard-band` is the one copy of the fake-Gboard band both keyboard connectors draw.
//
// It used to be two copies — `SoftKeyboardBand.kt` in `:data-keyboard-connector` and
// `SoftKeyboardBandDesktop.kt` in `:data-keyboard-connector-desktop`, kept in lockstep by a comment
// asking whoever touched one to bump the other. That contract failed: the desktop copy quietly lost
// three glyph comments and the `@param` docs, and grew a `MIN_SCREEN_HEIGHT_FOR_BAND_DP` constant
// the Android copy kept somewhere else (#5165).
//
// **Why this can be one plain-JVM module.** The old header justified the fork with
// "`:data-keyboard-connector` is `android.library`, so its outputs can't be consumed from
// `:daemon:desktop`'s JVM classpath" — which argues for the *desktop* module being plain JVM (it
// is), not for a second band. The band itself only touches `compose.runtime`, `compose.ui` and
// `compose.foundation`, all of which exist on both platforms, so the dependency inverts: a JVM
// module both connectors consume, exactly like `:data-render-compose`.
//
// Ships `SoftKeyboardBand` plus the two shared layout tokens (`KEYBOARD_HEIGHT_DP`, which the
// Android connector also publishes as the synthetic IME inset, and `MIN_SCREEN_HEIGHT_FOR_BAND_DP`,
// the "is this surface a screen" floor both connectors gate on). All three are `public` only
// because the callers live in other modules; consumers reach the keyboard through the data
// extension, never by calling the band.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  // Foundation widgets (`Box`, `Column`, `Row`, `BasicText`) and the runtime the Compose compiler
  // plugin generates against. `compileOnly` so this module's published POM adds no Compose
  // dependency of its own: each connector already brings its own Compose — the Android one
  // deliberately against the older `compose-bom-compat` so its AAR's bytecode stays
  // binary-backward-compatible with consumers pinned to older BOMs (see that module's
  // `compileOnly` block for the long-form story), the desktop one against Compose Multiplatform.
  // Both resolve to the same `androidx.compose.*` classes this module compiles against, and
  // keeping them off the runtime classpath here keeps that choice with the connectors.
  compileOnly(libs.jetbrains.compose.runtime)
  compileOnly(libs.jetbrains.compose.ui)
  compileOnly(libs.jetbrains.compose.foundation)

  testImplementation(libs.jetbrains.compose.runtime)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.jetbrains.compose.foundation)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-keyboard-band",
    displayName = "Compose Preview - Soft Keyboard Band",
    description =
      "The Gboard-shaped soft-keyboard band drawn over previews by the soft-keyboard data-product connectors, shared by the Android (:data-keyboard-connector) and Compose Multiplatform Desktop (:data-keyboard-connector-desktop) sides.",
  )
  inceptionYear.set("2026")
}
