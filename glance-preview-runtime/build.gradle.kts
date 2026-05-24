@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:glance-preview-runtime` — composable-helper authoring path for Glance app-widget
// previews. Sister to `:notification-preview-runtime`. `GlanceAppWidgetContent(widget = ...)`
// materialises a `GlanceAppWidget` to `RemoteViews` via the public 1.2.0+
// `GlanceAppWidget.composeForPreview(...)` API and inflates the resulting tree inside the
// surrounding Compose `@Preview` — the same `RemoteViews.apply(context, parent)` path
// `AppWidgetHost.createView(...)` takes on-device.
//
// Standalone on purpose — no compile dep on `:renderer-android` so the runtime can be used in
// Bazel modules or JVM unit tests that don't carry the full Robolectric renderer. Pairs with a
// (future) FQN-discovered `androidx.glance.preview.Preview` strategy in `:renderer-android` so
// consumers can choose between authoring with Glance's own preview annotation (native discovery
// path) or the composable-helper authoring path (this module).

plugins {
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
  implementation(project(":data-launcher-widget-connector"))
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
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
