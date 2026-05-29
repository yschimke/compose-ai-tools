plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-scroll-core"))
  api(project(":daemon:core"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-scroll-connector",
    displayName = "Compose Preview - Scroll Data Product Connector",
    description =
      "Daemon-side scroll data-product connector: advertises render/scroll/long and " +
        "render/scroll/gif kinds as requiresRerender=true producers so a missing scroll " +
        "artefact triggers a per-preview re-render via data/fetch instead of a module-wide " +
        "Gradle round-trip.",
  )
  inceptionYear.set("2026")
}
