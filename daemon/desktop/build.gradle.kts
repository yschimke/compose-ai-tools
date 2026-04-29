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
// NOT published to Maven. Consumed only by the Gradle plugin's daemon launch
// descriptor (Stream A); classpath wireup for the desktop target lands in a
// later Stream A task.

plugins {
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

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

dependencies {
  // Renderer-agnostic protocol types, JsonRpcServer, RenderHost interface,
  // and RenderRequest/RenderResult data classes — see DESIGN.md § 4. The
  // core module re-exposes kotlinx-serialization-json as `api`, so we don't
  // re-declare it here.
  implementation(project(":daemon:core"))

  // Inherit the desktop renderer's Compose Multiplatform / Skiko stack.
  // Compose Desktop ships per-platform native Skiko binaries via the
  // `compose.desktop.currentOs` accessor used in :renderer-desktop, so this
  // module gets the host platform's Skiko bundle for free — no extra config
  // here. B-desktop.1.4 (RenderEngine) duplicates the desktop render body
  // into this module on top of that classpath.
  implementation(project(":renderer-desktop"))

  // Compose runtime / foundation / ui — the B-desktop.1.4 RenderEngine body
  // imports `ImageComposeScene`, `@Composable`, `currentComposer`,
  // `getDeclaredComposableMethod`, and a few Modifier / layout helpers. These
  // are runtime-transitive through `:renderer-desktop` but not compile-visible
  // here; declare them explicitly so the duplicated render body compiles
  // against the same coordinates `:renderer-desktop` resolves.
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.components.uiToolingPreview)

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
  "testFixturesImplementation"(compose.runtime)
  "testFixturesImplementation"(compose.foundation)
  "testFixturesImplementation"(compose.ui)
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
