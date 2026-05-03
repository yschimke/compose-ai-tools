plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-render-core",
    displayName = "Compose Preview — Render Data Product (Core)",
    description =
      "Shared render data-product protocol: preview context, theme payloads, device clip, render trace, and failure records exchanged by renderer and daemon connectors.",
  )
  inceptionYear.set("2026")
}
