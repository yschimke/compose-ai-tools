plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"
// CI sets PLUGIN_VERSION: release.yml passes the git tag (stripped of `v`);
// snapshot.yml passes `<last-tag-patch+1>-SNAPSHOT`. The string below is only
// used for local `publishToMavenLocal`; bump it after each tag so local
// builds produce a version ahead of the last release.
version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.3.4-SNAPSHOT"

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

// Publish to both GitHub Packages (legacy / CI convenience) and Maven Central
// via the new Central Portal. Snapshots (version ending in `-SNAPSHOT`) route
// automatically to `https://central.sonatype.com/repository/maven-snapshots/`.
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
    // Central Portal (https://central.sonatype.com) is the only target in
    // vanniktech ≥ 0.34. `automaticRelease = true` promotes to the central
    // repo without a manual "release" click in the Portal UI. Snapshots route
    // to https://central.sonatype.com/repository/maven-snapshots/ automatically.
    publishToMavenCentral(automaticRelease = true)
    // Vanniktech auto-detects `java-gradle-plugin` and publishes the plugin +
    // marker artifacts with sources/javadoc jars. (If we ever apply the
    // `com.gradle.plugin-publish` plugin for Plugin Portal, swap in
    // `configure(GradlePublishPlugin())`.)
    // Only require signing for non-snapshot builds — snapshots are unsigned on
    // the Central snapshots repo, which is convenient for local/dev publishes.
    if (!version.toString().endsWith("SNAPSHOT")) {
        signAllPublications()
    }

    coordinates("ee.schimke.composeai", "compose-preview-plugin", version.toString())

    pom {
        name.set("Compose Preview Gradle Plugin")
        description.set(
            "Gradle plugin to discover and render Jetpack Compose / Compose Multiplatform " +
                "@Preview functions to PNG outside Android Studio.",
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
