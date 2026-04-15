plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ee.schimke.composeai.renderer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.robolectric)
    implementation(libs.junit)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.roborazzi)
    implementation(libs.roborazzi.compose)

    // Tiles rendering is reflection-driven at runtime (the consumer module
    // supplies the actual classes on the JUnit classpath), so we only need
    // these to compile TilePreviewRenderer — a consumer without tile deps
    // will never hit the TILE branch in RobolectricRenderTestBase.
    compileOnly(libs.wear.tiles)
    compileOnly(libs.wear.tiles.renderer)
    compileOnly(libs.wear.tiles.tooling.preview)
    compileOnly(libs.wear.protolayout)
    compileOnly(libs.wear.protolayout.expression)
}
