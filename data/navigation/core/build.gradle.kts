plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-navigation-core",
    displayName = "Compose Preview - Navigation Data Product Core",
    description =
      "Shared navigation data-product model classes (NavigationPayload, NavigationIntent, NavigationBackPressedState) for Compose Preview.",
  )
  inceptionYear.set("2026")
}
