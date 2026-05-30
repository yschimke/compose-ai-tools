// Thin CLI over `render-session-subprocess` for non-Gradle build systems. Lives in the outer
// build (not in the gradle-plugin includeBuild) because it depends on `:render-session-subprocess`
// and `:render-session-api`, both outer-build modules.
//
// Phase A of the contrib refactor (see `contrib/README.md`): contrib repo Bazel rules and
// Amper tasks shell out to
//   `java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli \
//      --descriptor X --previews Foo,Bar`
// to drive a render, rather than re-implementing the JSON-RPC subprocess dance from
// scratch. The library API surface lives in `:render-session-api`; this module is purely a
// CLI adapter.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Public `RenderSession` contract surfaced in the CLI's behaviour — the args map directly
  // onto `renderNow(previewIds, tier, reason, ...)` and the printed result onto the
  // `renderFinished` notification payload.
  api(project(":render-session-api"))
  // Subprocess backend is the only implementation today; the CLI is therefore subprocess-only.
  // If an in-process backend lands later, the CLI's `--mode embedded` flag would route here.
  implementation(project(":render-session-subprocess"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-cli",
    displayName = "Compose Preview — Render CLI",
    description =
      "`java -cp` CLI over the render-session-subprocess library. Lets non-Gradle build " +
        "systems (Bazel rules, Amper tasks) drive a render against an existing " +
        "`daemon-launch.json` without buying into a Kotlin/JVM client.",
  )
  inceptionYear.set("2026")
}

// Slim library JAR; the intended invocation is
//   `java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli ...`
// with the build system resolving the runtime closure (render-session-subprocess,
// render-session-api, daemon-core, mcp, kotlinx-serialization) through its own dep system.
// The `Main-Class:` stamp is a convenience for build systems that have already materialised
// the full runtime closure next to the artifact (Bazel `runtime_jars`, hand-rolled `lib/`);
// `java -jar` against the bare published JAR will NOT work — no `Class-Path:` manifest
// entry, no shaded uber-JAR. Same pattern as `:preview-discovery` and
// `:daemon-launch-builder`.
tasks.named<Jar>("jar").configure {
  manifest { attributes("Main-Class" to "ee.schimke.composeai.render.cli.RenderCli") }
}
