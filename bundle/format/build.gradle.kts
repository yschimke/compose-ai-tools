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
// Boundary: this module depends only on `:common-io` (plus Okio, kotlinx-serialization, and the
// JDK). It must not grow a dependency on `:cli`, the daemon protocol, or the data products — a
// bundle reader that needs the CLI to make sense is not a format module. Declarations that were
// `internal` to `:cli` are `public` here; that is the extraction, not a widening for its own sake.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
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
