plugins {
  id("composeai.maven-publishing")
  `java-gradle-plugin`
  `kotlin-dsl`
  id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

// Configuration-only plugin + the shared `composePreview { }` DSL, split out so a build can apply
// `ee.schimke.composeai.preview.config` without pinning the runtime (no tasks, AGP, renderer or
// Gradle-version floor); the CLI injects `:gradle-plugin` at its own version.
//
// Both plugins resolve this artifact and Gradle picks one copy, so keep the public DSL
// backwards-compatible.

gradlePlugin {
  website.set("https://github.com/yschimke/compose-ai-tools")
  vcsUrl.set("https://github.com/yschimke/compose-ai-tools.git")
  plugins {
    create("composePreviewConfig") {
      id = "ee.schimke.composeai.preview.config"
      implementationClass = "ee.schimke.composeai.plugin.ComposePreviewConfigPlugin"
      displayName = "Compose Preview Configuration Plugin"
      description =
        "Configuration-only Compose Preview plugin: contributes the composePreview { } DSL and an " +
          "applied marker without pinning or enforcing the rendering runtime, so the compose-preview " +
          "CLI can supply the runtime at its own version."
      tags.set(listOf("compose", "preview", "android", "jetpack-compose", "configuration"))
    }
  }
}

dependencies {
  // `composePreview { }` DSL references the resource-preview enums (`AdaptiveShape`,
  // `ResourceType`,
  // `DEFAULT_RESOURCE_FILMSTRIP_FRACTIONS`, …) from the schema library. `api` so the runtime plugin
  // (which depends on this module) keeps seeing them transitively.
  api(project(":preview-discovery"))

  // `ComposePreviewAppliedTask` serializes the marker JSON.
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(gradleTestKit())
}

// Own resource name, not `plugin-version.properties`: both jars can share a buildscript classpath.
// Read by `ConfigPluginVersion`.
val generateConfigPluginVersionResource =
  tasks.register("generateConfigPluginVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/config-plugin-version-resource")
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDir)
    doLast {
      val file =
        outputDir.get().file("ee/schimke/composeai/plugin/config-plugin-version.properties").asFile
      file.parentFile.mkdirs()
      file.writeText("version=$pluginVersion\n")
    }
  }

sourceSets.main.get().resources.srcDir(generateConfigPluginVersionResource)

composeAiMavenPublishing {
  coordinates(
    artifactId = "compose-preview-config",
    displayName = "Compose Preview — Configuration Plugin",
    description =
      "Configuration-only Compose Preview Gradle plugin and shared composePreview { } DSL. Lets a build commit preview configuration without pinning the rendering runtime; the compose-preview CLI supplies the runtime at its own version.",
  )
  inceptionYear.set("2026")
}
