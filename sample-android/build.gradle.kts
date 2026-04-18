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

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
