import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("composeai.base-conventions")
  // Published so external consumers can resolve `ee.schimke.composeai:renderer-xr:<version>` —
  // the gradle plugin adds it to a consumer's render configuration when it auto-enables the XR
  // path (AndroidPreviewSupport, the `xrPreviewsEnabled` branch). `composeai.maven-publishing`
  // brings the android/jvm/kotlin conventions too, so the explicit `android-conventions` apply is
  // dropped here (mirrors `:renderer-android`). Without this the XR render path resolves nowhere
  // for an external consumer — only the in-repo `includeBuild` path (the `Render XR composite`
  // sample job) worked before.
  id("composeai.maven-publishing")
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

  // `XrSubspaceRenderer` projects each panel's semantics via the daemon-side connector's
  // `ComposeSemanticsDataProducer.buildPayload` (single-sourcing the `compose/semantics`
  // projection). `compileOnly` keeps it off this module's published POM — the plugin adds it to the
  // XR render configuration at render time, mirroring the Robolectric/Roborazzi/XR-fakes model.
  compileOnly(project(":data-layoutinspector-connector"))

  compileOnly(platform(libs.compose.bom.stable))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.activity.compose)
  compileOnly(libs.xr.compose)
  compileOnly(libs.xr.compose.testing)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  // `XrSubspaceRenderTest` (the render entry the plugin's task runs) references Robolectric's
  // parameterised runner + shadows and JUnit annotations; both are provided at render time by the
  // task classpath (same model as `:renderer-android`'s `RobolectricRenderTest`).
  compileOnly(libs.robolectric)
  compileOnly(libs.junit)
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
  // `XrSubspaceRendererTest` drives `XrSubspaceRenderer.render` directly (not through the Gradle
  // plugin that puts the connector on the render config), so the spatial-semantics projection needs
  // the connector on the test runtime — otherwise the producer is silently skipped (its
  // `NoClassDefFoundError` is caught) and never exercised.
  testImplementation(project(":data-layoutinspector-connector"))
  testImplementation(libs.xr.compose)
  testImplementation(libs.xr.compose.testing)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("androidx.xr.runtime:runtime-testing:1.0.0-alpha14")
  testImplementation("androidx.xr.scenecore:scenecore-testing:1.0.0-alpha15")
  // Fake ARCore perception runtime (settable `FakeRuntimeArDevice` head pose) so the recorder tests
  // can drive `rotateToLookAtUser` offline via FakeXrHeadPose; registered for `ServiceLoader` in
  // src/main/resources/META-INF/services. Test-only (and added to the render task classpath by the
  // gradle plugin), mirroring how the scene/rendering fakes stay off this module's main classpath.
  testImplementation(libs.xr.arcore.testing)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

// Single `release` variant publication — `XrSubspaceRenderTest` (the render entry the plugin's
// composePreviewRenderXr task runs) and the fake-runtime ServiceLoader registrations both live in
// `src/main`, so the release AAR carries everything the plugin needs; no testFixtures publication.
// The heavy XR / compose-test libs are `compileOnly`, so they stay out of the published POM and the
// plugin supplies the `*-testing` fakes on the render configuration itself (mirrors
// `:renderer-android`).
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
    artifactId = "renderer-xr",
    displayName = "Compose Preview — XR Renderer",
    description =
      "Offline producer of the SpatialScene wire format — recovers a Compose-XR Subspace layout " +
        "under a fake XR runtime and maps each tagged panel's pose/size into the SpatialScene DTO. " +
        "Consumed by the compose-preview Gradle plugin's composePreviewRenderXr task.",
  )
  inceptionYear.set("2025")
}
