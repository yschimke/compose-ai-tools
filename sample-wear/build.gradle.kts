plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("ee.schimke.composeai.preview")
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
}
