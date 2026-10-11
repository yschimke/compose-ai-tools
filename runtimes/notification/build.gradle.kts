// `:notification-preview-runtime` — `NotificationContent` hosts a built `Notification` inside an
// ordinary `@Preview`, so authors can stack uiMode / locale / fontScale multipreviews. The
// alternative is `@NotificationPreview` (FQN-discovered by the renderer).
//
// No dependency on `:renderer-android`, so it works in Bazel modules and plain JVM tests; the
// sidecar JSON shape is duplicated locally.

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
  implementation(libs.composeai.common.io)
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
