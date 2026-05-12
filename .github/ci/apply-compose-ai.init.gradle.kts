// Gradle init script for integration tests.
//
// Drives the "Zero-Code Integration" path documented in
// skills/compose-preview/SKILL.md and README.md, with two CI-specific
// adjustments:
//
//   1. mavenLocal() is added at the settings level (pluginManagement +
//      dependencyResolutionManagement) and at every project's buildscript
//      level. The build-plugin job seeds $HOME/.m2 with the freshly-built
//      SNAPSHOT plugin + renderer-android AAR + daemon-* artifacts, and we
//      need every resolution path the plugin touches to see it. The
//      settings-level dependencyResolutionManagement entry is what lets
//      consumer projects with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`
//      (e.g. WearTilesKotlin) still resolve the renderer AAR.
//
//   2. The plugin version is pulled from $COMPOSE_AI_PLUGIN_VERSION
//      (exported by the workflow). `latest.release` from the SKILL example
//      doesn't match `-SNAPSHOT`, and integration CI explicitly wants the
//      bundle's version rather than whatever's drifted on remote.
//
// When the consumer build is invoked with COMPOSE_AI_TOOLS=true in the
// environment, this script applies `ee.schimke.composeai.preview` to every
// project that already applies one of `com.android.application`,
// `com.android.library`, or `org.jetbrains.compose`. Without the env var
// the script only seeds repositories — safe to leave on Gradle's init.d
// path for unrelated builds on the same runner.
//
// Applying via buildscript-classpath injection (rather than via the init
// script's own classpath with `initscript { dependencies { classpath … } }`)
// keeps the plugin's classes in the same classloader scope as the
// consumer's AGP, so reflective `getByType<AndroidComponentsExtension>()`
// lookups inside the plugin see matching Class identities. The previous
// patch-the-`plugins{}`-block approach achieved the same thing through the
// consumer's plugin classpath; this is the equivalent for the
// don't-touch-the-source-tree path.

val pluginVersion: String = System.getenv("COMPOSE_AI_PLUGIN_VERSION")
    ?: error(
        "COMPOSE_AI_PLUGIN_VERSION must be set when apply-compose-ai.init.gradle.kts " +
            "is on Gradle's init.d path",
    )

gradle.settingsEvaluated {
    pluginManagement.repositories.mavenLocal()
    dependencyResolutionManagement.repositories.mavenLocal()
}

allprojects {
    buildscript {
        repositories {
            mavenLocal()
            gradlePluginPortal()
            mavenCentral()
            google()
        }
        dependencies {
            add(
                "classpath",
                "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:$pluginVersion",
            )
        }
    }

    afterEvaluate {
        if (System.getenv("COMPOSE_AI_TOOLS") != "true") return@afterEvaluate
        val triggers = listOf(
            "com.android.application",
            "com.android.library",
            "org.jetbrains.compose",
        )
        if (triggers.none { plugins.hasPlugin(it) }) return@afterEvaluate
        if (plugins.hasPlugin("ee.schimke.composeai.preview")) return@afterEvaluate
        pluginManager.apply("ee.schimke.composeai.preview")
        println("Applied ee.schimke.composeai.preview to $name via init script")
    }
}
