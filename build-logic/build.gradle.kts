plugins { `kotlin-dsl` }

dependencies {
  implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
  implementation("com.gradleup.tapmoc:tapmoc-gradle-plugin:${libs.versions.tapmoc.get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation(
    "com.vanniktech:gradle-maven-publish-plugin:${libs.versions.maven.publish.get()}"
  )
}

gradlePlugin {
  plugins {
    register("composeAiAndroidConventions") {
      id = "composeai.android-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiAndroidConventionsPlugin"
    }
    register("composeAiAndroidLibraryConventions") {
      id = "composeai.android-library-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiAndroidConventionsPlugin"
    }
    register("composeAiJvmConventions") {
      id = "composeai.jvm-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiJvmConventionsPlugin"
    }
    register("composeAiMavenPublishing") {
      id = "composeai.maven-publishing"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiMavenPublishingPlugin"
    }
    register("composeAiTapmocConventions") {
      id = "composeai.tapmoc-conventions"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiTapmocConventionsPlugin"
    }
  }
}
