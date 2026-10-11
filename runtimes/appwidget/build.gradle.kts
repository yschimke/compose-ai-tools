// `:appwidget-preview-runtime` — `AppWidgetContent { ctx -> RemoteViews(...) }` inflates a
// `RemoteViews` into the surrounding `@Preview` via `RemoteViews.apply(context, parent)`, as
// `AppWidgetHost.createView(...)` does on-device. `<appwidget-provider>` metadata is found from the
// inflated layout id, and its `supportedCells` / `resizeAxes` flow via
// `LauncherWidgetMetadataChannel` into the launcher-widget data product.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.preview.appwidget"

  buildFeatures { compose = true }
}

dependencies {
  // Compose deps mirror `:notification-preview-runtime`'s `compileOnly` model — the consumer
  // module brings its own Compose BOM. Compile against the older `compose-bom-compat` so
  // emitted bytecode runs unchanged against newer consumer Compose versions.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)

  // `LauncherWidgetMetadataChannel` — the helper looks up the inflated layout id against
  // `AppWidgetManager.installedProviders` and offers the matching provider's `min/maxResizeWidth/
  // Height`, `targetCellWidth/Height`, `resizeMode` into the per-render channel so the
  // launcher-widget data product surfaces the constraints on its payload. Without this dep the
  // helper still inflates the widget; the payload just doesn't carry the discovered metadata.
  implementation(libs.composeai.data.launcher.widget.connector)

  // `translate(...)` reads `AppWidgetProviderInfo` fields + `Context.resources.displayMetrics`
  // — both real Android types, so the unit tests run under Robolectric to get a working
  // application context. `RuntimeEnvironment.getApplication()` is enough; no full sandbox
  // setup needed.
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}

android { testOptions { unitTests { isIncludeAndroidResources = true } } }

composeAiMavenPublishing {
  coordinates(
    artifactId = "appwidget-preview-runtime",
    displayName = "Compose Preview — AppWidget Runtime",
    description =
      "Composable helper that inflates a `RemoteViews` factory into the surrounding Compose " +
        "`@Preview` tree and auto-discovers `<appwidget-provider>` metadata via " +
        "`AppWidgetManager.installedProviders` — the composable-helper authoring path for " +
        "legacy `RemoteViews`-backed App Widget previews. Sister to `notification-preview-runtime` " +
        "and `glance-preview-runtime`.",
  )
  inceptionYear.set("2026")
}
