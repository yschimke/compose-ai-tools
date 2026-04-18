plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("ee.schimke.composeai.preview")
}

composePreview {
    accessibilityChecks {
        // Sample includes deliberately-broken previews (BadButton,
        // TinyTapTarget, TinyNativeButton) used as demo data for the CLI /
        // VSCode surfacing of a11y findings. Flip to `true` to exercise the
        // opt-in path; defaults off to keep the sample's render build
        // byte-identical to the non-a11y baseline.
        enabled = false
    }
}

android {
    namespace = "com.example.sampleandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.sampleandroid"
        minSdk = 24
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

    testOptions {
        unitTests.all {
            it.jvmArgs("-Xmx2048m")
        }
    }
}

// `ScrollPreviewPixelTest` reads PNGs under `build/compose-previews/renders/`
// produced by `renderAllPreviews`. Wire the AGP unit-test tasks to depend on
// it so `./gradlew :sample-android:check` renders the PNGs first then
// pixel-asserts against them. Targeting the `test{Debug,Release}UnitTest`
// tasks by name — `tasks.withType<Test>()` would also grab the plugin's own
// `renderPreviews` Test task and create a circular dependency.
afterEvaluate {
    listOf("testDebugUnitTest", "testReleaseUnitTest").forEach { name ->
        tasks.findByName(name)?.dependsOn("renderAllPreviews")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    // Roborazzi's per-preview clock control annotation. Source-retained
    // metadata read by `DiscoverPreviewsTask` — the annotation itself has no
    // runtime behaviour in production builds.
    implementation(libs.roborazzi.annotations)
    // Our `@ScrollingPreview` lives here — same role as above, read by FQN
    // at discovery time; no runtime behaviour.
    implementation(project(":preview-annotations"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
