@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:data-uiautomator-connector` — daemon-side glue for the UIAutomator-shaped script events.
//
// Advertises a single `uiautomator` `DataExtensionDescriptor` carrying the `uia.*` script-event
// surface: click, longClick, scrollForward/Backward, requestFocus, expand, collapse, dismiss,
// inputText. `:daemon:android`'s `DaemonMain` wraps this in an `Extension(...)` registration and
// `:daemon:android`'s `AndroidRecordingSession` registers a per-id handler that decodes the
// event's `selector` `JsonObject` and routes through
// `interactive.dispatchUiAutomator(actionKind, selectorJson, useUnmergedTree, inputText)`.
//
// Mirrors `:data-a11y-connector`'s shape (descriptor here; handler registration in
// :daemon:android). Not coupled to `:data-uiautomator-core`'s matcher types — the connector's job
// is the wire-level descriptor advertisement; the actual selector decode happens sandbox-side in
// `RobolectricHost.performUiAutomatorAction`.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.uiautomator.connector" }

dependencies {
  // Typed payload + `UiAutomatorDataProducts.Hierarchy` key — the producer/registry below
  // serialise / advertise this kind. Re-exported via `api` so `:daemon:android` can refer to
  // `UiAutomatorHierarchyPayload` without adding a second `project` dep.
  api(project(":data-uiautomator-core"))

  // `DataProductRegistry` interface, `DataProductCapability` / `DataProductAttachment` wire
  // types — re-exported via `api` for the same reason `:data-a11y-connector` does.
  api(project(":daemon:core"))

  // `RecordingScriptEventDescriptor` + `DataExtensionDescriptor` types.
  api(project(":data-render-core"))

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
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
    artifactId = "data-uiautomator-connector",
    displayName = "Compose Preview — UIAutomator Data Product (Connector)",
    description =
      "Daemon-side connector for the UIAutomator-shaped script events (uia.click, uia.inputText, " +
        "etc.): advertises a single uiautomator DataExtensionDescriptor that the Compose preview " +
        "daemon registers when running on the Android backend.",
  )
  inceptionYear.set("2026")
}
