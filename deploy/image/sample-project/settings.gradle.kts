// Self-contained project served by the prebuilt compose-preview image. It applies
// the PUBLISHED Compose plugins from public repositories — no reference to the
// compose-ai-tools repo's build-logic — so the released CLI can build + render it
// with only a Maven round-trip (no compiling the tool from source).
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}

rootProject.name = "preview-host"
