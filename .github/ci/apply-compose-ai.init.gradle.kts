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
// Application uses `pluginManager.withPlugin(...)`, NOT `afterEvaluate`.
// `AndroidPreviewSupport.configure` wires `androidComponents.finalizeDsl`
// and `onVariants` callbacks that have to be registered before AGP locks
// the DSL — `afterEvaluate` runs after that lock, the callbacks never
// fire, and `discoverPreviews` ships zero previews. `withPlugin` fires
// synchronously as soon as AGP is applied (during the consumer's
// `plugins { }` block, before the rest of the build script body), which
// is the same moment the old patch-the-`plugins{}`-block approach landed
// the plugin. That's also early enough for the script body to reference
// the `composePreview { }` extension (e.g. the daemon-enable block CI
// appends before `composePreviewDaemonStart`).
//
// Applying via buildscript-classpath injection (rather than via the init
// script's own classpath with `initscript { dependencies { classpath … } }`)
// keeps the plugin's classes in the same classloader scope as the
// consumer's AGP, so reflective `getByType<AndroidComponentsExtension>()`
// lookups inside the plugin see matching Class identities.

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

    if (System.getenv("COMPOSE_AI_TOOLS") != "true") return@allprojects

    fun applyComposeAiPreview() {
        if (plugins.hasPlugin("ee.schimke.composeai.preview")) return
        pluginManager.apply("ee.schimke.composeai.preview")
        println("Applied ee.schimke.composeai.preview to $name via init script")
    }

    pluginManager.withPlugin("com.android.application") { applyComposeAiPreview() }
    pluginManager.withPlugin("com.android.library") { applyComposeAiPreview() }
    pluginManager.withPlugin("org.jetbrains.compose") { applyComposeAiPreview() }
}
