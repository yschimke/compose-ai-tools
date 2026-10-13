plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
  api(project(":bundle-format")) {
    // Source preparation does not use the format's optional Android launcher helper.
    exclude(group = "ee.schimke.composeai", module = "daemon-client")
  }
  api("ee.schimke.composeai:preview-discovery")
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

application {
  applicationName = "compose-preview-producer"
  mainClass.set("ee.schimke.composeai.bundle.producer.BundleProducerCli")
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "bundle-producer",
    displayName = "Compose Preview — Bundle Producer",
    description =
      "Offline discovery and runnable bundle preparation from compiled JVM inputs; no renderer or build-system runtime.",
  )
  inceptionYear.set("2026")
}

kotlin {
  explicitApi()
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

tasks.named("check") { dependsOn("checkKotlinAbi") }

// Guard the independently consumable source-only producer's runtime closure.
val checkProducerRuntime by tasks.registering {
  val runtime: FileCollection = configurations.getByName("runtimeClasspath")
  inputs.files(runtime)
  doLast {
    val forbidden =
      runtime.files.filter {
        it.name.startsWith("daemon-client-") ||
          it.name.startsWith("daemon-core-") ||
          it.name.startsWith("daemon-bta-") ||
          it.name.startsWith("kotlin-build-tools-api-") ||
          it.name.startsWith("compose-preview-") ||
          it.name.startsWith("render-host-") ||
          it.name.startsWith("skiko-") ||
          it.name.startsWith("ktor-")
      }
    check(forbidden.isEmpty()) { "Source producer acquired runtime dependencies: $forbidden" }
  }
}

tasks.named("check") { dependsOn(checkProducerRuntime) }
