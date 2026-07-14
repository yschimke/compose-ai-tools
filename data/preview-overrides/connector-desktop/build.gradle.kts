@file:Suppress("DEPRECATION")

// `:data-preview-overrides-connector-desktop` is the JVM-side counterpart to
// `:data-preview-overrides-connector`. The portable connector installs `LocalPreviewOverrideHost`
// and drains the `compose/overrides` product; this desktop connector adds the piece that only the
// CMP-desktop backend can provide: it wraps `org.jetbrains.compose.resources.LocalResourceReader`
// so every `stringResource(...)` a preview loads becomes an editable override knob automatically —
// no `previewOverrideString(...)` needed. A resource lookup already tells us the text that rendered
// and gives us a stable key to seed a replacement against, so the reader records the knob and
// substitutes the daemon-seeded value in one pass.
//
// CMP Desktop's `stringResource` doesn't walk `LocalContext.resources`, so — exactly like the
// pseudolocale desktop connector — we intercept at the byte-level reader rather than swapping a
// `Resources` subclass the way the Android connector does. The recorded knobs ride the same
// `PreviewOverrideController` (and thus the same `compose/overrides` product) as the explicit
// `previewOverride*` knobs, so no new data product is introduced here.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":daemon:core"))
  api(project(":data-render-compose"))
  api(project(":data-preview-overrides-core"))
  api(project(":data-preview-overrides-runtime"))
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  // `org.jetbrains.compose.resources.LocalResourceReader` — the byte-level interceptor we wrap so a
  // `stringResource(...)` lookup records an editable knob and returns the seeded replacement. The
  // `internal` env-swap path isn't reachable from outside the resources module at CMP 1.10.x, so
  // this published handle is the one that works (same reasoning as the pseudolocale connector).
  implementation(libs.jetbrains.compose.components.resources)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-preview-overrides-connector-desktop",
    displayName = "Compose Preview - Named Override Data Product Connector (Desktop)",
    description =
      "Daemon-side connector for Compose Multiplatform Desktop that makes every stringResource(...) lookup an editable preview-override knob: wraps LocalResourceReader to record the resource string as a compose/overrides declaration and substitute the daemon-seeded replacement, without requiring an explicit previewOverride* call.",
  )
  inceptionYear.set("2026")
}
