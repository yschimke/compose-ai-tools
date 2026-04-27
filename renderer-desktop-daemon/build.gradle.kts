// Renderer-desktop daemon module — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface") and § 6 (module layout).
//
// Desktop counterpart of `:renderer-android-daemon`. Both modules implement
// the renderer-agnostic surface contributed by `:renderer-daemon-core`
// (`RenderHost`, `JsonRpcServer`, the @Serializable protocol types) and only
// differ in their concrete `RenderHost` implementation:
//
//  - `:renderer-android-daemon` → `RobolectricHost` (Robolectric sandbox + the
//    dummy-`@Test` runner trick from DESIGN.md § 9).
//  - `:renderer-desktop-daemon` → `DesktopHost` (long-lived `Recomposer` +
//    Skiko `Surface`). Lands in B-desktop.1.3; this module currently only
//    holds the skeleton + a placeholder `DaemonMain` that prints "hello".
//    B-desktop.1.5 wires `DaemonMain` to the existing `JsonRpcServer` from
//    core.
//
// Plain JVM module (no Android plugins) — Compose Desktop's Skiko native
// bundle is contributed transitively via `:renderer-desktop`.
//
// NOT published to Maven. Consumed only by the Gradle plugin's daemon launch
// descriptor (Stream A); classpath wireup for the desktop target lands in a
// later Stream A task.

plugins { alias(libs.plugins.kotlin.jvm) }

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
  implementation(project(":renderer-daemon-core"))

  // Inherit the desktop renderer's Compose Multiplatform / Skiko stack.
  // Compose Desktop ships per-platform native Skiko binaries via the
  // `compose.desktop.currentOs` accessor used in :renderer-desktop, so this
  // module gets the host platform's Skiko bundle for free — no extra config
  // here. B-desktop.1.4 (RenderEngine) duplicates the desktop render body
  // into this module on top of that classpath.
  implementation(project(":renderer-desktop"))

  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .DaemonMain`. Lets local verification of the placeholder `main` happen without applying the
// `application` plugin (which would add `distZip`/`distTar`/etc. tasks we don't need yet). Wire-up
// to the Gradle plugin's daemon launch descriptor lands in a later Stream A task; this task is
// purely for local "does the JAR run?" sanity checks.
tasks.register<JavaExec>("runDaemonMain") {
  group = "application"
  description = "Runs the placeholder DaemonMain (B-desktop.1.1 skeleton)."
  classpath =
    sourceSets["main"].runtimeClasspath +
      files(tasks.named("jar").map { (it as Jar).archiveFile })
  mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
  dependsOn("jar")
}
