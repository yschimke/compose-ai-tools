// Escaping for the markup both halves of #3824 emit.
//
// HTML text, HTML attributes, JS string literals, URL path segments, and reading a PNG's
// dimensions out of its header. No project dependency, no framework — `java.util` and arithmetic.
//
// It lived in `:cli:serve` as an `internal object`, which made it unreachable from `WebEmbed` once
// that moved to `:bundle-format`, and unreachable from a separated CLI at all. Eleven server files
// share it; putting it in `:bundle-format` alongside `WebEmbed` would have been the cheap move and
// a false one — generic escaping is not part of a bundle's format, and this codebase has just
// finished renaming one type that claimed to be about a format it was not (`SvgContentBox`).
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "common-web-escaping",
    displayName = "Compose Preview — Web Escaping",
    description =
      "HTML, JS-string and URL-segment escaping, plus PNG header dimensions, shared by the " +
        "compose-preview server's pages and the bundle's web-embed gallery.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — a published contract both halves of the #3824 split compile against across a
  // repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
