// Kotlin scripting host for `compose-preview script <path.composepreview.kts>`.
//
// See issue #1084 for the design discussion. This is the MVP slice — host JARs
// ride on the default CLI runtime classpath (~50 MB tarball bloat), no jar
// cache, no classloader split, no lazy-fetch / `--with-scripting` install
// hook. Splitting onto a sidecar classpath is tracked on the same issue.
//
// `:cli-scripting` consumes `:cli` for the `PreviewResult` / `Command`
// surfaces the script DSL exposes; `:cli` only depends on this module via
// `runtimeOnly` + reflective dispatch from `Main.kt`'s `script` case, so the
// compile graph stays acyclic.

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // The script DSL surfaces `PreviewResult` (`AccessibilityFinding` via
  // `result.a11yFindings`) directly to user scripts, plus borrows
  // `Command` / `ReportCommand` / `extensionGradleArgs()` infrastructure to
  // run the same render pipeline the canned-report commands use.
  implementation(project(":cli"))

  // JSR-223-style scripting host. `kotlin-scripting-jvm-host` pulls
  // `kotlin-compiler-embeddable` transitively (the ~50 MB driver).
  implementation(libs.kotlin.scripting.common)
  implementation(libs.kotlin.scripting.jvm)
  implementation(libs.kotlin.scripting.jvm.host)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
