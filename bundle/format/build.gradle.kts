// The preview-bundle *format* — reading, writing, signing, and unpacking a `.previewbundle` zip —
// split out of `:cli` for issue #3824.
//
// The CLI's `bundle` subcommands (`pack`, `embed`, `render`, `repack`, `merge`, `inspect`,
// `extract`) stay in `:cli`: they are argument parsing and process orchestration. What lives here
// is everything a *reader* of a bundle needs and nothing a command-line does — the well-known
// entry names, the manifest DTO, the sidecar injectors, the deterministic zip helpers, the
// detached signature scheme, the classpath hydration, and the Android resource/launch support.
//
// Package note: types keep the `ee.schimke.composeai.cli` package for source-compat — they were in
// `:cli` before the extraction, and every call site (including `:cli`'s own `serve`) already
// imports them from there. Same pattern `:gradle-preview-driver` used for its step-B carve-out.
//
// Boundary: this module depends only on `:common-io` and `:preview-data-api` (plus Okio,
// kotlinx-serialization, and the JDK). It must not grow a dependency on `:cli`, the daemon
// protocol, or the data products — a bundle reader that needs the CLI to make sense is not a
// format module.
//
// Published, and named in the preview-server contract probe: `serve` reads bundles, so an
// extracted server has to be able to depend on this by coordinate rather than by reaching into
// `:cli`. That is the whole point of the split, and it is why the package is
// `ee.schimke.composeai.bundle` rather than the `…cli` package these files carried on the way out.
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
  api(project(":common-io"))

  // `previews.json` — the packer writes the published `PreviewManifest` DTO into the bundle, and
  // reading a bundle's per-preview labels means parsing it. `api`, as `:cli` does, so the DTOs stay
  // on the consumer's compile classpath exactly as they were before the split.
  api(project(":preview-data-api"))

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
