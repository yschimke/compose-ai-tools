// In-process Kotlin compilation for the daemon and the playground, over the Kotlin Build Tools API.
//
// Split out of `:daemon:core` for #3824. This is the LAST of serve's imports from that module, and
// unlike the three splits before it — `:daemon-protocol`, `:daemon-devices`,
// `DaemonLaunchDescriptor` — it is not a shape that had been filed in the wrong drawer. It is
// behaviour, and the coupling is real: a preview server that offers a playground compiles the
// snippets it is given, so it needs a compiler. Publishing it is a decision that an extracted
// server keeps the playground rather than reaching for a compile service.
//
// **The dependency this publishes is the point.** `DiagnosticCollector` implements the Build Tools
// API's `KotlinLogger`, and `BtaCompileSession.compile` takes one; those types are on this module's
// compile ABI, so the BTA is `api` here. It was `implementation` in `:daemon:core`, which was wrong
// on a published artifact for as long as those signatures were public: a consumer compiling against
// `daemon-core` could name `BtaCompileSession` and then fail to resolve the `KotlinLogger` its
// method wanted, because `implementation` keeps a dependency off the consumer's compile classpath.
//
// The `impl` side stays out. `kotlin-build-tools-impl` is loaded reflectively at first use through
// `SharedApiClassesClassLoader`, which delegates API lookups up to this classpath — which is why
// the API jar has to be here and the impl jar does not.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-bta`. The package is unchanged
// (`ee.schimke.composeai.daemon.bta`) and `:daemon:core` exposes this module as `api`, so nothing
// downstream edits an import.
//
// Note the package is shared with `:daemon:bta-host`, which declares `BtaCompiler` in it. That
// split predates this move — it was `daemon-core` + `bta-host` before and is `daemon-bta` +
// `bta-host` now — so this neither creates nor worsens it, but it is a split package and worth
// closing separately if either module ever needs to be a JPMS module.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // `CompileErrorDetail` and `SourceChangeSet` are protocol shapes on this module's own surface.
  api(project(":daemon-protocol"))

  // `api`, not `implementation` — see the header. These types are in public signatures.
  api("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  // Okio file IO for the benchmark entry point's source scan.
  implementation(project(":common-io"))

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-bta",
    displayName = "Compose Preview — In-Process Kotlin Compile",
    description =
      "In-process Kotlin compilation over the Build Tools API, as the compose-preview daemon " +
        "and playground drive it: warm compile sessions, incremental runs and diagnostic capture.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — this is a published contract both halves of the #3824 split compile against
  // across a repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
