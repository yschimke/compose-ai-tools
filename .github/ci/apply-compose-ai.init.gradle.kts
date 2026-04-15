// Gradle init script for integration tests.
//
// Auto-applies the Compose AI preview plugin to every Android
// application/library module and every Compose Multiplatform module it sees.
// The plugin JAR is resolved from mavenLocal(), where the integration workflow
// put it via `./gradlew :gradle-plugin:publishToMavenLocal`.
//
// Dropped into $GRADLE_USER_HOME/init.d/ so that both direct `./gradlew` calls
// and the compose-preview CLI (which goes through the Gradle Tooling API) load
// it automatically — no modifications to the target repo are required.
//
// We apply by Class reference, not by plugin id. The initscript classpath is
// visible to subprojects' classloaders, but Gradle's plugin-id resolution
// (plugin-marker lookup) does NOT consult the init-script classpath from
// inside convention plugins / precompiled script plugins — so
// `pluginManager.apply("ee.schimke.composeai.preview")` fails with
// "Plugin with id ... not found". Applying the Class works reliably.

import ee.schimke.composeai.plugin.ComposePreviewPlugin

initscript {
    val pluginVersion = System.getenv("COMPOSE_AI_PLUGIN_VERSION") ?: "0.1.0-SNAPSHOT"
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath("ee.schimke.composeai:gradle-plugin:$pluginVersion")
    }
}

allprojects {
    val applyComposeAi: () -> Unit = {
        if (!pluginManager.hasPlugin("ee.schimke.composeai.preview")) {
            pluginManager.apply(ComposePreviewPlugin::class.java)
            logger.lifecycle("[compose-ai-tools] applied to $path")
        }
    }
    plugins.withId("com.android.application") { applyComposeAi() }
    plugins.withId("com.android.library") { applyComposeAi() }
    plugins.withId("org.jetbrains.compose") { applyComposeAi() }
}
