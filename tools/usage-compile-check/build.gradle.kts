// `:tools:usage-compile-check` — does a generated usage snippet actually compile?
//
// The Source panel says a snippet is "the plain Compose that produces this render". This module is
// what holds that claim to something: it compiles the corpus `UsageSnippetCorpusTest` generates
// against **Compose and material3 only** — the classpath a developer pasting the snippet into their
// own app has, and deliberately NOT the catalog's own classpath, which would let catalog-internal
// helpers resolve and hide exactly the leakage worth finding.
//
// Sources come from `-PusageCorpus=<dir>`; with none the module is empty and compiles trivially, so
// a normal build is unaffected. Driven by `scripts/usage-corpus.sh`, which generates the corpus,
// runs this, and attributes each `e:` diagnostic back to the snippet it came from.
plugins {
  // Every project with a build file applies this — `settings.gradle.kts` derives the
  // `ktfmtCheckAll` / `ktfmtFormatAll` aggregate from exactly that invariant, so a module without
  // it breaks the aggregate task rather than merely skipping formatting.
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

val usageCorpus: String? = providers.gradleProperty("usageCorpus").orNull

kotlin {
  sourceSets["main"].kotlin.setSrcDirs(usageCorpus?.let { listOf(file(it)) } ?: emptyList<Any>())
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)
  // Material icons: `Icons.Filled.*` shows up in real catalog previews.
  implementation(compose.materialIconsExtended)
}

// Diagnostics are the product here: a snippet that does not compile is the finding, so the compile
// must report every error it can rather than stopping at the first file.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions { allWarningsAsErrors.set(false) }
}
