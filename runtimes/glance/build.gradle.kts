// `:glance-preview-runtime` — `GlanceAppWidgetContent(widget = ...)` materialises a
// `GlanceAppWidget` to `RemoteViews` via `composeForPreview(...)` (Glance 1.2.0+) and inflates it
// in the surrounding `@Preview`, as `AppWidgetHost.createView(...)` does on-device. The alternative
// is Glance's own `@Preview` (native discovery). No dependency on `:renderer-android`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.preview.glance"

  buildFeatures { compose = true }
}

dependencies {
  // Compose deps mirror `:notification-preview-runtime`'s `compileOnly` model — the consumer
  // module brings its own Compose BOM. Compile against the older `compose-bom-compat` so
  // emitted bytecode runs unchanged against newer consumer Compose versions.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)

  // Glance is the actual runtime dep — the helper calls `GlanceAppWidget.composeForPreview(...)`.
  // 1.2.0+ is required for that API; pin via libs.versions.toml so a consumer-side bump moves
  // the entire toolchain together.
  api(libs.glance.appwidget)

  // `LauncherWidgetMetadataChannel` — the helper reflectively reads `widget.previewSizeMode` and
  // offers it into the per-render channel so `LauncherWidgetDataProductRegistry` can surface the
  // declared supported sizes / resize-axes on the payload. Without this dep the helper still
  // renders the widget; the payload just doesn't carry the size-mode constraints.
  implementation(libs.composeai.data.launcher.widget.connector)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "glance-preview-runtime",
    displayName = "Compose Preview — Glance Runtime",
    description =
      "Composable helper that materialises a `GlanceAppWidget` to `RemoteViews` via " +
        "`GlanceAppWidget.composeForPreview(...)` and inflates the result inside the surrounding " +
        "Compose `@Preview` tree — the composable-helper authoring path for Glance app-widget " +
        "previews. Sister to `notification-preview-runtime`.",
  )
  inceptionYear.set("2026")
}
