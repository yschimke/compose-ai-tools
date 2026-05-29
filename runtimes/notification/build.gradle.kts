@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:notification-preview-runtime` — the composable-helper authoring path for notification
// previews. Pairs with the `@NotificationPreview` annotation that ships in `:preview-annotations`:
// the annotation drives the FQN-discovered NOTIFICATION strategy (renderer-android builds the
// `Notification` directly and emits a `.notification.json` sidecar); this module's
// `NotificationContent` composable hosts a built `Notification` inside an existing `@Preview`
// composable so authors can stack uiMode / locale / fontScale knobs via multi-preview meta-
// annotations.
//
// Standalone on purpose — no compile dep on `:renderer-android` so the runtime can be used in
// Bazel modules or JVM unit tests that don't carry the full Robolectric renderer. The sidecar JSON
// shape is duplicated locally rather than imported.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.preview.notification"

  buildFeatures { compose = true }
}

dependencies {
  // Compose deps mirror `:renderer-android`'s `compileOnly` model — the consumer module brings
  // its own Compose BOM, and we compile against the older `compose-bom-compat` so emitted
  // bytecode runs unchanged against newer consumer Compose versions. See
  // `:renderer-android`'s build script for the long-form rationale.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)

  // The helper inflates via `android.app.Notification.Builder.recoverBuilder` +
  // `createBigContentView` — pure platform APIs, no AndroidX runtime dep needed. Consumers
  // building the `Notification` they pass to `NotificationContent` typically reach for
  // `androidx.core`'s `NotificationCompat`, but they already carry it for their own notification
  // posting paths; we don't pin a version onto their classpath from here.

  // Robolectric-based recomposition test for `NotificationContent`. Compose UI test deps are
  // `testImplementation` only — they don't leak into the published AAR. We use the same
  // `compose-bom-compat` we compile against so the test JVM resolves the exact symbols the main
  // source set was built with.
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
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
    artifactId = "notification-preview-runtime",
    displayName = "Compose Preview — Notification Runtime",
    description =
      "Composable helper that inflates a `Notification` factory into a surrounding Compose @Preview " +
        "tree. Pairs with the `@NotificationPreview` annotation in `:preview-annotations` for the " +
        "composable-helper authoring path.",
  )
  inceptionYear.set("2026")
}
