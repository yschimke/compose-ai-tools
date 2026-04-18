plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("ee.schimke.composeai.preview")
}

composePreview {
    accessibilityChecks {
        // Sample wires a deliberately-broken `BadWearButtonPreview` so the
        // `.a11y.png` for Wear exercises the stacked (legend-below) layout.
        // Flip to `true` and re-run to see the annotation; defaults off so
        // `./gradlew check` stays clean.
        enabled = false
    }
}

android {
    namespace = "com.example.samplewear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.samplewear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear Tiles — for the `@androidx.wear.tiles.tooling.preview.Preview` sample
    // rendered via TilePreviewRenderer in renderer-android.
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.renderer)
    implementation(libs.wear.tiles.tooling.preview)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.tooling.preview)
    // `@ScrollingPreview` — read by FQN at discovery time; no runtime cost.
    implementation(project(":preview-annotations"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// `LongScrollPreviewPixelTest` reads PNGs produced by `renderAllPreviews`.
// Same dependency wiring as sample-android's pixel tests; targets the AGP
// unit-test tasks by name so we don't include the plugin's own `renderPreviews`
// Test task (which would create a circular dep).
//
// `tasks.matching { ... }.configureEach { ... }` is the Isolated-Projects-
// safe lazy pattern — the predicate fires as each task is registered, so we
// don't need the discouraged `afterEvaluate` block to wait for AGP to wire
// the unit-test tasks.
val pixelTestUnitTestTasks = setOf("testDebugUnitTest", "testReleaseUnitTest")
tasks.matching { it.name in pixelTestUnitTestTasks }.configureEach {
    dependsOn("renderAllPreviews")
}
