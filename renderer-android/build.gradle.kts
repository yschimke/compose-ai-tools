@file:Suppress("DEPRECATION") // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement
// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"
// See gradle-plugin/build.gradle.kts for how CI sets PLUGIN_VERSION.
version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.3.3-SNAPSHOT"

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
    // `AndroidSingleVariantLibrary` in `mavenPublishing {}` below wires the
    // `singleVariant("release")` publication for us — don't declare it twice.
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

// GitHub Packages repo kept alongside Maven Central for internal/CI convenience.
// Vanniktech's `AndroidSingleVariantLibrary` creates the release publication
// (with sources + javadoc jar) — do not create one manually; it clashes.
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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    if (!version.toString().endsWith("SNAPSHOT")) {
        signAllPublications()
    }

    coordinates("ee.schimke.composeai", "renderer-android", version.toString())

    pom {
        name.set("Compose Preview — Android Renderer")
        description.set(
            "Robolectric-based renderer for Jetpack Compose @Preview functions, " +
                "used by the compose-preview Gradle plugin to produce PNGs off-device.",
        )
        url.set("https://github.com/yschimke/compose-ai-tools")
        inceptionYear.set("2025")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("yschimke")
                name.set("Yuri Schimke")
                url.set("https://github.com/yschimke")
            }
        }
        scm {
            url.set("https://github.com/yschimke/compose-ai-tools")
            connection.set("scm:git:https://github.com/yschimke/compose-ai-tools.git")
            developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-ai-tools.git")
        }
    }
}
