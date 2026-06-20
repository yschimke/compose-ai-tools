plugins {
  id("composeai.maven-publishing")
  `java-gradle-plugin`
  `kotlin-dsl`
  id("org.jetbrains.kotlin.plugin.serialization") version embeddedKotlinVersion
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

// Configuration-only Compose Preview plugin + the shared `composePreview { }` DSL surface.
//
// Split out of `:gradle-plugin` so a consumer can apply `id("ee.schimke.composeai.preview.config")`
// to commit preview configuration WITHOUT pinning the rendering runtime: this artifact carries only
// the DSL extension types and the `composePreviewApplied` marker — no render/discovery tasks, no
// AGP,
// no renderer, and (critically) no Gradle-version floor. The `compose-preview` CLI auto-injects the
// full runtime plugin (`:gradle-plugin`, at the CLI's own version) when it drives a render, and
// that
// plugin reuses the extension/marker this module defines.
//
// Because the runtime `:gradle-plugin` depends on this module for the shared extension TYPE, this
// is
// the one artifact that must stay binary-stable across versions: when a consumer pins the config
// plugin at version X and the CLI injects the runtime at version Y, both resolve this artifact and
// Gradle conflict-resolves to a single copy. Keep the public DSL surface backwards-compatible.

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

// Bake the config plugin's version into a module-specific resource (NOT
// `plugin-version.properties`,
// which the runtime plugin owns — both jars can share a buildscript classpath, so the resource
// paths
// must not collide). Read back by `ConfigPluginVersion`.
val generateConfigPluginVersionResource by tasks.registering {
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
