plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-glimmer-environment-connector",
    displayName = "Compose Preview - Glimmer Environment Connector",
    description =
      "Post-capture environment backdrops and additive compositing for Glimmer previews.",
  )
  inceptionYear.set("2026")
}
