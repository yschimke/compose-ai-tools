// Thin CLI over `render-session-subprocess` for non-Gradle build systems (Bazel rules, Amper tasks;
// see `contrib/README.md`), invoked as
//   `java -cp <resolved-classpath> ee.schimke.composeai.render.cli.RenderCli \
//      --descriptor X --previews Foo,Bar`
// The library API is `:render-session-api`; this is purely a CLI adapter.

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

// Slim library JAR: callers resolve the runtime closure themselves. `Main-Class:` is a convenience
// for build systems that materialise the closure alongside; `java -jar` on the bare JAR won't work
// (no `Class-Path:`, not shaded).
tasks.named<Jar>("jar").configure {
  manifest { attributes("Main-Class" to "ee.schimke.composeai.render.cli.RenderCli") }
}
