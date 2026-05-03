plugins { `kotlin-dsl` }

dependencies {
  implementation(
    "com.vanniktech:gradle-maven-publish-plugin:${libs.versions.maven.publish.get()}"
  )
}

gradlePlugin {
  plugins {
    register("composeAiMavenPublishing") {
      id = "composeai.maven-publishing"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiMavenPublishingPlugin"
    }
  }
}
