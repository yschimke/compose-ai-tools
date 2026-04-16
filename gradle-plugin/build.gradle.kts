plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"
version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.1.0-SNAPSHOT"

gradlePlugin {
    website.set("https://github.com/yschimke/compose-ai-tools")
    vcsUrl.set("https://github.com/yschimke/compose-ai-tools.git")
    plugins {
        create("composePreview") {
            id = "ee.schimke.composeai.preview"
            implementationClass = "ee.schimke.composeai.plugin.ComposePreviewPlugin"
            displayName = "Compose Preview Plugin"
            description = "Discover and render Jetpack Compose / Compose Multiplatform @Preview functions to PNG"
            tags.set(listOf("compose", "preview", "android", "jetpack-compose", "rendering"))
        }
    }
}

// Publish to GitHub Packages by default.
// To switch to Gradle Plugin Portal later: apply the `com.gradle.plugin-publish`
// plugin and remove the publishing block below — it wraps this config automatically.
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

dependencies {
    implementation(libs.classgraph)
    implementation(libs.kotlinx.serialization.json)
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(gradleTestKit())
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// Functional tests use Gradle TestKit
val functionalTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val functionalTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val functionalTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnit()
}

tasks.check {
    dependsOn(functionalTestTask)
}

// Bake the plugin's own version into a resource so it can resolve a matching
// `renderer-android` AAR at runtime for external consumers (who apply the
// plugin via GitHub Packages rather than includeBuild).
val generatePluginVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/plugin-version-resource")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("ee/schimke/composeai/plugin/plugin-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$pluginVersion\n")
    }
}

sourceSets.main.get().resources.srcDir(generatePluginVersionResource)
