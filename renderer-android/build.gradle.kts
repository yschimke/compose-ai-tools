plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "ee.schimke.composeai"
version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.1.0-SNAPSHOT"

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

    publishing {
        singleVariant("release")
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

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(
                providers.environmentVariable("GITHUB_REPOSITORY")
                    .map { "https://maven.pkg.github.com/$it" }
                    .orElse("https://maven.pkg.github.com/yschimke/compose-ai-tools"),
            )
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "renderer-android"
            }
        }
    }
}
