// The preview-bundle format: reading, writing, signing and unpacking a `.previewbundle` —
// everything a bundle reader needs, without the CLI's argument parsing and orchestration (those
// `bundle` subcommands stay in `:cli`).
//
// Depends only on `:common-io` and `:preview-data-api` (plus Okio, kotlinx-serialization, the JDK);
// it must not depend on `:cli`, the daemon protocol or data products. Published, so the preview
// server can depend on it by coordinate.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":common-web-escaping"))
  // `api` so `:cli` keeps seeing Okio's `Path` / `FileSystem` transitively, as it did while these
  // files were its own sources.
  api(libs.composeai.common.io)

  // `previews.json` — the packer writes the published `PreviewManifest` DTO into the bundle, and
  // reading a bundle's per-preview labels means parsing it. `api`, as `:cli` does, so the DTOs stay
  // on the consumer's compile classpath exactly as they were before the split.
  api(libs.composeai.preview.data.api)

  // The Android launch facts (`--add-opens`, `robolectric.*` flags and properties, SDK range) are
  // the daemon's; `AndroidBundleLaunch` is their bundle-shaped view. No daemon type leaks into this
  // module's API.
  implementation(libs.composeai.daemon.client)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "bundle-format",
    displayName = "Compose Preview — Bundle Format",
    description =
      "The `.previewbundle` format: manifest DTO, well-known entry names, sidecar injectors, " +
        "deterministic zip helpers, the detached signature scheme, classpath hydration, and the " +
        "Android resource/launch support. Read by the CLI, the daemon and an extracted preview " +
        "server; published so none of them has to reach into the CLI to read a bundle.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — every declaration states its visibility, every public one its return type.
  // This is a published contract an extracted preview server compiles against across a repo
  // boundary (#3824), so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  // ABI dump gate, as on every other contract module. `checkKotlinAbi` diffs the real public ABI
  // against the committed dump in `api/`, so a surface change is a diff in review rather than a
  // downstream break. Regenerate with `./gradlew :bundle-format:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
