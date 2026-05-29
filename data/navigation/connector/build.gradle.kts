plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-navigation-core"))
  api(project(":daemon:core"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-navigation-connector",
    displayName = "Compose Preview - Navigation Data Product Connector",
    description =
      "Daemon-side navigation data-product connector: serves the data/navigation kind from on-disk artefacts written by the Android producer.",
  )
  inceptionYear.set("2026")
}
