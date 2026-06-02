plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// `:renderer-xr` — offline producer of the SpatialScene wire format (see
// docs/design/SPATIAL_SCENE_CONTRACT.md). It recovers the real Compose-XR subspace layout under a
// fake XR runtime (the technique proven by `:samples:xr-spatial`'s `SubspaceLayoutPoseTest`) and
// maps each tagged panel's pose/size into the published `SpatialScene` DTO the VS Code 3D viewer
// consumes — no headset, no OpenXR, no SceneCore native code.
//
// The heavy XR / compose-test libraries are `compileOnly`, mirroring `:renderer-android`: the
// recorder compiles against them, and the render runtime (this module's own Robolectric tests
// today; the gradle plugin when wired) supplies them. That keeps them off any future consumer's
// classpath. The `*-testing` fake runtimes + their `ServiceLoader` registration live only in the
// test source set.

android {
  namespace = "ee.schimke.composeai.renderer.xr"
  // `androidx.xr.compose` declares `minCompileSdk = 36`.
  compileSdk = 36
  buildFeatures { compose = true }
  testOptions {
    unitTests.all {
      it.jvmArgs("-Xmx2048m")
      // Mirror the capture JVM/system properties the gradle plugin sets on its render Test task
      // (AndroidPreviewSupport) so `captureRoboImage` can rasterise Compose under Robolectric.
      it.systemProperty("robolectric.graphicsMode", "NATIVE")
      it.systemProperty("robolectric.looperMode", "PAUSED")
      it.systemProperty("robolectric.conscryptMode", "OFF")
      it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
      it.systemProperty("roborazzi.test.record", "true")
    }
  }
}

dependencies {
  // The SpatialScene wire DTO the recorder emits.
  api(project(":preview-data-api"))

  compileOnly(platform(libs.compose.bom.stable))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.activity.compose)
  compileOnly(libs.xr.compose)
  compileOnly(libs.xr.compose.testing)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  // Per-panel texture capture (captureRoboImage). compileOnly — provided by the render runtime.
  compileOnly(libs.roborazzi)
  compileOnly(libs.roborazzi.compose)

  // Own unit tests run the recorder under Robolectric against the fake XR runtime (registered for
  // ServiceLoader in src/test/resources/META-INF/services). SDK 35 so it renders on the JDK 17
  // lane.
  testImplementation(platform(libs.compose.bom.stable))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.activity.compose)
  testImplementation(libs.xr.compose)
  testImplementation(libs.xr.compose.testing)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("androidx.xr.runtime:runtime-testing:1.0.0-alpha14")
  testImplementation("androidx.xr.scenecore:scenecore-testing:1.0.0-alpha15")
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
