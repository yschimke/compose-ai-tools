// `:data-launcher-widget-connector` — shared Compose Multiplatform module hosting the
// launcher-widget container-size extension (`LauncherWidgetExtension` `AroundComposable`) + its
// planner (`LauncherWidgetPreviewOverrideExtension`). Consumed by both `:daemon:desktop` and
// `:daemon:android`. Mirrors `:data-touch-overlay-connector`'s shape: pure
// `androidx.compose.foundation` / `androidx.compose.ui` APIs (`Modifier.size`), no Android-specific
// surface, so one module covers both backends.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.foundation)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-launcher-widget-connector",
    displayName = "Compose Preview - Launcher Widget Data Product Connector",
    description =
      "Daemon-side launcher-widget container-size connector: wraps the preview body in a sized `Box` so a held preview can be laid out at a specific whole-cell size on the host's launcher grid. Activated via `renderNow.overrides.launcherWidget = LauncherWidgetOverride(cells = ...)`.",
  )
  inceptionYear.set("2026")
}
