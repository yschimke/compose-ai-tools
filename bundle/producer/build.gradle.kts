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

// `preview-discovery` is declared without a version and substituted from the included
// `gradle-plugin` build, so the POM would carry no `<version>` and Maven Central rejects the whole
// release deployment (2.58.0 to 2.60.0). Write the resolved version instead.
publishing {
  publications.withType<MavenPublication>().configureEach {
    versionMapping { allVariants { fromResolutionResult() } }
  }
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
          it.name.startsWith("daemon-compile-") ||
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

// Keep the independently published producer JAR/distribution archives reproducible too.
tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

tasks.named<Tar>("distTar") {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}
