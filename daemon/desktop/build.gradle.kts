// Renderer-desktop daemon module — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface") and § 6 (module layout).
//
// Desktop counterpart of `:daemon:android`. Both modules implement
// the renderer-agnostic surface contributed by `:daemon:core`
// (`RenderHost`, `JsonRpcServer`, the @Serializable protocol types) and only
// differ in their concrete `RenderHost` implementation:
//
//  - `:daemon:android` → `RobolectricHost` (Robolectric sandbox + the
//    dummy-`@Test` runner trick from DESIGN.md § 9).
//  - `:daemon:desktop` → `DesktopHost` (long-lived JVM + render
//    thread; per-render `ImageComposeScene`). B-desktop.1.4 lands the real
//    render body in `RenderEngine.kt`; B-desktop.1.5 wires `DaemonMain` to
//    `JsonRpcServer` from core.
//
// Compose Multiplatform module (Kotlin JVM + compose plugins). The compose
// plugins are required so B-desktop.1.4's `RenderEngine` can compile against
// `androidx.compose.runtime.Composable` / `androidx.compose.ui.ImageComposeScene`,
// and so the test source set can declare `@Preview` / `@Composable` fixtures.
// The same plugin set is used by `:renderer-desktop`.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-desktop` —
// pairs with `daemon-core` so the desktop daemon is consumable by coordinate.
// Pre-1.0; see DESIGN.md § 17.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  // D-harness.v1.5a — `PreviewManifestRouter` reads a JSON manifest mapping previewId to
  // RenderSpec for harness-driven real-mode runs. Plugin only adds the @Serializable processor;
  // kotlinx-serialization-json is already on the classpath via `:daemon:core`'s
  // `api(libs.kotlinx.serialization.json)`.
  alias(libs.plugins.kotlin.serialization)
  // D-harness.v1.5a — exposes the `RedSquare` fixture composable to `:daemon:harness`'s
  // test classpath via `testFixtures(project(":daemon:desktop"))`, so the real-mode
  // S1 doesn't need its own Compose plugin / fixture duplication. Test source set's
  // `RedFixturePreviews` is unchanged; the testFixtures source set re-exports `RedSquare` for
  // cross-module consumers.
  `java-test-fixtures`
}

dependencies {
  // Renderer-agnostic protocol types, JsonRpcServer, RenderHost interface,
  // and RenderRequest/RenderResult data classes — see DESIGN.md § 4. The
  // core module re-exposes kotlinx-serialization-json as `api`, so we don't
  // re-declare it here.
  implementation(project(":daemon:core"))
  implementation(project(":data-render-connector"))
  implementation(project(":data-history-connector"))
  implementation(project(":data-theme-connector"))
  implementation(project(":data-recomposition-connector"))

  // Compose runtime / foundation / ui — the B-desktop.1.4 RenderEngine body
  // imports `ImageComposeScene`, `@Composable`, `currentComposer`,
  // `getDeclaredComposableMethod`, and a few Modifier / layout helpers.
  // Platform-agnostic surface; resolves to `*-desktop` variants on JVM
  // consumers via Compose Multiplatform's variant selection.
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.material3)
  implementation(compose.components.uiToolingPreview)

  // `compose.desktop.currentOs` bakes the *build host's* Skiko platform into
  // the published POM (e.g. `desktop-jvm-linux-x64` when CI builds on Linux),
  // which would lock consumers to that platform. Declare as `compileOnly` so
  // it stays on our compile/test classpath but does NOT escape into the
  // published POM. Consumers resolve their own host's Skiko via
  // `implementation(compose.desktop.currentOs)` in their own build, the
  // standard Compose Desktop library pattern.
  compileOnly(compose.desktop.currentOs)

  testImplementation(libs.junit)
  // Tests declare a small fixture composable + drive RenderEngine against it,
  // so the test classpath needs the same Compose Multiplatform stack.
  testImplementation(compose.desktop.currentOs)
  testImplementation(compose.runtime)
  testImplementation(compose.foundation)
  testImplementation(compose.ui)
  testImplementation(compose.material3)
  testImplementation(compose.components.uiToolingPreview)

  // testFixtures source set holds `RedFixturePreviews.kt` so its `RedSquare` composable can be
  // consumed by `:daemon:harness`'s real-mode S1 (D-harness.v1.5a) without requiring that
  // module to apply Compose plugins. The Compose runtime/ui deps below mirror the test
  // declarations above; only the foundation + runtime + ui surface area the fixtures actually
  // touch is needed here.
  //
  // `compose.desktop.currentOs` is added so the harness (which consumes
  // `testFixtures(project(":daemon:desktop"))`) inherits the per-OS Skiko native bundle
  // transitively. Without it the spawned daemon JVM dies in `ImageComposeScene.<init>` with
  // `LibraryLoadException: Cannot find libskiko-linux-x64.so.sha256` — the production
  // `compileOnly(compose.desktop.currentOs)` above keeps the bundle off the published POM, so
  // nothing else on the harness's classpath would otherwise pull it. testFixtures variants are
  // skipped from the publishable component (see the `afterEvaluate` block below), so this stays
  // out of `daemon-desktop`'s POM too.
  "testFixturesImplementation"(compose.desktop.currentOs)
  "testFixturesImplementation"(compose.runtime)
  "testFixturesImplementation"(compose.foundation)
  "testFixturesImplementation"(compose.ui)
  "testFixturesImplementation"(compose.material3)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .DaemonMain`. Lets local verification of the daemon happen without applying the
// `application` plugin (which would add `distZip`/`distTar`/etc. tasks we don't need yet). Wire-up
// to the Gradle plugin's daemon launch descriptor lands in a later Stream A task; this task is a
// debug entry point — `runDaemonMain` blocks waiting for stdin (the JSON-RPC channel) and exits
// when the client sends `shutdown` + `exit` (or stdin closes).
tasks.register<JavaExec>("runDaemonMain") {
  group = "application"
  description =
    "Runs DaemonMain (B-desktop.1.5: JsonRpcServer + DesktopHost). Blocks waiting for stdin."
  classpath =
    sourceSets["main"].runtimeClasspath + files(tasks.named("jar").map { (it as Jar).archiveFile })
  mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
  standardInput = System.`in`
  dependsOn("jar")
}

// `java-test-fixtures` adds testFixtures-* "Elements" configurations that Vanniktech's
// auto-detection picks up and ships as `-test-fixtures.jar` / `-test-fixtures-sources.jar`.
// The fixtures (`RedSquare`, etc.) are internal harness aids — do not publish them. Skip the
// publishable testFixtures variants from the `java` component. Done in `afterEvaluate` because
// Vanniktech adds the sources/javadoc variants to the component lazily — calling
// `withVariantsFromConfiguration` before `addVariantsFromConfiguration` fails (the variant
// isn't yet attached to the component even though the configuration exists).
afterEvaluate {
  val javaComponent = components["java"] as org.gradle.api.component.AdhocComponentWithVariants
  listOf(
      "testFixturesApiElements",
      "testFixturesRuntimeElements",
      "testFixturesSourcesElements",
      "testFixturesJavadocElements",
    )
    .forEach { name ->
      configurations.findByName(name)?.let {
        javaComponent.withVariantsFromConfiguration(it) { skip() }
      }
    }
}

// GitHub Packages mirror — same shape as `:renderer-android` and `:preview-annotations`.

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-desktop",
    displayName = "Compose Preview — Daemon Desktop",
    description =
      "Compose Multiplatform desktop backend of the compose-preview daemon: long-lived JVM + Skiko render thread, per-render ImageComposeScene. Pre-1.0; pairs with daemon-core.",
  )
  inceptionYear.set("2025")
}
