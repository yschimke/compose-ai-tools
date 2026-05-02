// Daemon end-to-end test harness — see docs/daemon/TEST-HARNESS.md.
//
// The harness plays the role of VS Code against a real daemon JVM over
// JSON-RPC. Renderer-agnostic by construction: only depends on
// `:daemon:core` for protocol types + `RenderHost` interface +
// `JsonRpcServer`. **No** dependency on `:daemon:android` or
// `:daemon:desktop` — the v0 harness spawns its own
// `FakeDaemonMain` (in `src/main/kotlin/.../FakeDaemonMain.kt`) which wires
// `JsonRpcServer` onto a `FakeHost`. Once B-desktop.1.5 lands, v1.5 flips
// `-Pharness.host=real` and consumes the real launcher descriptor; that
// classpath continues to live in the bench module, never here.
//
// Plain `org.jetbrains.kotlin.jvm` — no Android plugins, no Compose. NOT
// published to Maven.

import java.net.URLClassLoader

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
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
  // Protocol types, JsonRpcServer, RenderHost interface, RenderRequest/RenderResult — all the
  // wire-shaped seams the harness needs to play "VS Code". Core module re-exposes
  // kotlinx-serialization-json as `api`, so the harness picks it up transitively.
  implementation(project(":daemon:core"))

  testImplementation(libs.junit)
  // B2.0 — ASM is used only by `S3_5RecompileSaveLoopRealModeTest` to mint two `.class` files
  // with the same FQN (`ee.schimke.composeai.daemon.MutableSquare`) but different colour
  // constants. Lets the test exercise the daemon's disposable user-classloader without a
  // dual-sourceset Gradle plumbing detour. See CLASSLOADER.md § Implementation seams.
  testImplementation(libs.asm)
  testImplementation(libs.asm.commons)

  // D-harness.v1.5a — real-mode (`-Pharness.host=real`) S1 needs the desktop daemon's main classes
  // (`DaemonMain`, `DesktopHost`, `RenderEngine`, `RenderSpec`, `PreviewManifestRouter`) plus the
  // `RedSquare` fixture composable on the *test* classpath so the harness's
  // `RealDesktopHarnessLauncher` can spawn them. Option A from the v1.5a task brief: adding the
  // deps as `testImplementation` does NOT widen the harness's production classpath, so the
  // renderer-agnostic invariant ([DESIGN § 4](docs/daemon/DESIGN.md#renderer-agnostic-surface))
  // continues to hold where it matters. The simpler alternative (Option B — a Gradle task that
  // resolves `:daemon:desktop`'s `runtimeClasspath` and writes it to a file the test
  // reads) was rejected for boilerplate without a concrete benefit at v1.5a scope.
  //
  // We deliberately do NOT apply Compose plugins here — that would force every `.kt` in the
  // harness's production source set to live on a Compose runtime classpath. Instead the harness
  // pulls in the `tests` configuration of `:daemon:desktop` (java-test-fixtures-style)
  // so the `RedFixturePreviews.RedSquare` composable already compiled by the desktop daemon's
  // test source set is on this module's test runtime classpath.
  testImplementation(project(":daemon:desktop"))
  testImplementation(testFixtures(project(":daemon:desktop")))
  // Compose runtime/foundation/ui + the per-OS Skiko native bundle propagate transitively via
  // `testFixtures(project(":daemon:desktop"))` above — see that module's
  // `testFixturesImplementation(compose.desktop.currentOs)` for why the testFixtures variant is
  // the seam (production `compileOnly(compose.desktop.currentOs)` keeps the Skiko bundle off the
  // published POM, so nothing else on the harness's classpath would otherwise pull it).

  // D-harness.v2 — Android target (`-Ptarget=android`). Strategy diverges from desktop:
  //
  // **Why we don't repeat desktop's `testImplementation(project(":daemon:android"))`
  // pattern.** Plain-JVM consumers can't natively pull an `com.android.library` module's runtime
  // classpath. AGP exposes the library as an AAR with AAR-shaped transitive dependencies (e.g.
  // `roborazzi`, `androidx.compose.*` are AAR-only); a plain JVM consumer doesn't run AGP's
  // AAR→JAR transforms. The fixture composables further need AGP-generated R.jars for transitive
  // AARs (`androidx.customview.poolingcontainer.R$id`) which are only synthesized inside the
  // daemon module's debug-unit-test runtime classpath setup.
  //
  // **What we do instead.** `:daemon:android` exposes a `daemonHarnessClasspathFile`
  // consumable configuration whose single artefact is a text file listing the absolute paths of
  // every JAR on its `debugUnitTestRuntimeClasspath` (+ AGP-generated R.jar + SDK android.jar).
  // The harness consumes that file via the `androidDaemonClasspath` configuration declared
  // below, exposes the path as a system property to the test JVM, and `RealAndroidHarnessLauncher`
  // reads + splits it at spawn time. Plain-text content; zero AGP variant attributes on the
  // consumer side.
  //
  // The fixture composables (`RedSquare`, `BlueSquare`, `GreenSquare`, `SlowSquare`,
  // `BoomComposable`) live in the android module's testFixtures source set and are on the same
  // text-file classpath via `bundleLibRuntimeToJarDebugTestFixtures`. The harness's *test*
  // classpath itself does NOT import the android module — production renderer-agnostic invariant
  // intact.
}

// D-harness.v2 — pull the Android daemon's runtime classpath (resolved inside the daemon module
// itself, which is Android-aware) as a text file the harness reads at test time. The
// `:daemon:android` module exposes a `daemonHarnessClasspathFile` consumable
// configuration that produces a single text-file artifact listing the absolute paths of every
// JAR on its `debugRuntimeClasspath` + `debugTestFixturesRuntimeClasspath`. Plain-text artefact
// — no AGP variants on the consumer side. See that module's `writeDaemonClasspath` task.
val androidDaemonClasspath: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes {
    attribute(
      Attribute.of("ee.schimke.composeai.daemon.harness.classpath", String::class.java),
      "android",
    )
  }
}

dependencies { androidDaemonClasspath(project(":daemon:android")) }

tasks.withType<Test>().configureEach {
  // Make the classpath descriptor file's path available to the test JVM via a system property.
  // The harness reads it in `RealAndroidHarnessLauncher.classpathFromProperty()` and splits each
  // line into a `File`. The `dependsOn` ensures the file exists before the test runs (resolution
  // of the configuration triggers the daemon module's `writeDaemonClasspath` task).
  inputs.files(androidDaemonClasspath)
  systemProperty(
    "composeai.harness.androidDaemonClasspath",
    androidDaemonClasspath.singleFile.absolutePath,
  )
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach {
  useJUnit()
  // D-harness.v1.5a — `-Pharness.host=fake|real` flag. Default `fake` keeps the existing 7
  // scenarios self-contained (no Compose Desktop spawn cost when verifying scenario logic).
  // Real-mode flips the launcher in `HarnessTestSupport.launcherFor(...)` and unblocks
  // `S1LifecycleRealModeTest`, which asserts a real Compose render's PNG against an in-repo
  // baseline.
  systemProperty("composeai.harness.host", findProperty("harness.host") ?: "fake")
  // D-harness.v2 — `-Ptarget=desktop|android` flag. Default `desktop` keeps the v1.5a/b real-mode
  // tests pointed at the desktop daemon. `target=android` activates the parallel
  // `*AndroidRealModeTest.kt` test classes which spawn the real `:daemon:android`
  // `DaemonMain` via `RealAndroidHarnessLauncher`. Tests skip via `Assume.assumeTrue` when
  // target doesn't match — both target sets coexist in the same JUnit suite.
  systemProperty("composeai.harness.target", findProperty("harness.target") ?: "desktop")
}

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .harness.FakeDaemonMain`. Mirrors `:daemon:desktop`'s `runDaemonMain` so the
// FakeDaemonMain entry point is locally runnable for sanity-checking without spinning up a JUnit
// scenario. Not used by CI — `HarnessClient` spawns its own subprocess via ProcessBuilder.
tasks.register<JavaExec>("runFakeDaemonMain") {
  group = "application"
  description = "Runs FakeDaemonMain against a fixture directory (-Dcomposeai.harness.fixtureDir)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("ee.schimke.composeai.daemon.harness.FakeDaemonMain")
}

// Classloader-forensics diff invocation — see docs/daemon/CLASSLOADER-FORENSICS.md.
//
// Reads the two configuration dumps (Configuration A from `:renderer-android`'s
// `ClassloaderForensicsTest`; Configuration B from `:daemon:android`'s
// `ClassloaderForensicsDaemonTest`) and produces a diff (Markdown + JSON) under
// `daemon/harness/build/reports/classloader-forensics/diff.{md,json}` for human review.
//
// Wired here (in :daemon:harness) rather than in either daemon module because the diff
// is a developer-invoked diagnostic that crosses both modules' boundaries — putting it in either
// individual module would imply a one-way dependency that doesn't reflect what the diff is for.
//
// Reflective into `:daemon:core` to avoid widening the harness's main classpath; the
// forensics library is already on the harness's `testRuntimeClasspath` via :daemon:core.
//
// Configuration-cache discipline: a dedicated `classloaderForensicsLib` configuration captures the
// `:daemon:core` jars at configuration time so the doLast lambda doesn't reach through
// `project(...)` at execution time (cross-project task references aren't config-cache-safe).
val classloaderForensicsLib: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  description = "Resolved JARs for the ClassloaderForensics library (used by dumpClassloaderDiff)."
}

dependencies { classloaderForensicsLib(project(":daemon:core")) }

val dumpClassloaderDiff by tasks.registering {
  description =
    "Diff the standalone (:renderer-android) and daemon (:daemon:android) classloader " +
      "forensics dumps. Writes daemon/harness/build/reports/classloader-forensics/diff.{md,json}. v1 — " +
      "developer-invoked diagnostic, not a CI gate."
  group = "verification"
  val standaloneJsonProvider =
    project(":renderer-android")
      .layout
      .buildDirectory
      .file("reports/classloader-forensics/standalone.json")
  val daemonJsonProvider =
    project(":daemon:android")
      .layout
      .buildDirectory
      .file("reports/classloader-forensics/daemon.json")
  val diffMdFile = layout.buildDirectory.file("reports/classloader-forensics/diff.md")
  val diffJsonFile = layout.buildDirectory.file("reports/classloader-forensics/diff.json")
  inputs.file(standaloneJsonProvider)
  inputs.file(daemonJsonProvider)
  outputs.file(diffMdFile)
  outputs.file(diffJsonFile)

  // Snapshot the resolved JAR list at configuration time — Provider<Set<FileSystemLocation>> is
  // configuration-cache-safe; cross-project task references aren't.
  val libFiles = classloaderForensicsLib.elements

  doLast {
    val standalone = standaloneJsonProvider.get().asFile
    val daemon = daemonJsonProvider.get().asFile
    require(standalone.exists()) {
      "Configuration A dump missing — run :renderer-android:test --tests \"*ClassloaderForensicsTest\" first.\n" +
        "Expected: ${standalone.absolutePath}"
    }
    require(daemon.exists()) {
      "Configuration B dump missing — run :daemon:android:test --tests \"*ClassloaderForensicsDaemonTest\" first.\n" +
        "Expected: ${daemon.absolutePath}"
    }
    val urls = libFiles.get().map { it.asFile.toURI().toURL() }.toTypedArray()
    val parentLoader: ClassLoader =
      Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val cl: ClassLoader = URLClassLoader(urls, parentLoader)
    val forensics =
      Class.forName("ee.schimke.composeai.daemon.forensics.ClassloaderForensics", true, cl)
    val instance = forensics.getField("INSTANCE").get(null)
    val fileClass = File::class.java
    val diffMethod = forensics.getMethod("diff", fileClass, fileClass, fileClass, fileClass)
    val diffMd = diffMdFile.get().asFile
    val diffJson = diffJsonFile.get().asFile
    diffMd.parentFile.mkdirs()
    diffMethod.invoke(instance, standalone, daemon, diffMd, diffJson)
    logger.lifecycle("Classloader forensics diff written to: ${diffMd.absolutePath}")
    logger.lifecycle("                                  + : ${diffJson.absolutePath}")
  }
}

// D-harness.v1.5b — regenerate the in-repo PNG baselines under
// `daemon/harness/baselines/desktop/<scenario>/`. Runs every real-mode scenario in
// "capture" mode: pixel-diffs are skipped and the captured PNG always overwrites the baseline.
// See `daemon/harness/CONTRIBUTING.md` for when to run this. Two runs in a row should
// produce byte-identical PNGs; if they don't, the renderer has a non-determinism worth chasing.
val regenerateBaselines by
  tasks.registering(Test::class) {
    description =
      "Run every harness scenario in capture mode; overwrites in-repo baseline PNGs " +
        "(D-harness.v1.5b + v2). Pick target with `-Ptarget=desktop|android`; defaults to " +
        "desktop. Captures into daemon/harness/baselines/<target>/<scenario>/<id>.png."
    group = "verification"
    systemProperty("composeai.harness.host", "real")
    systemProperty("composeai.harness.target", findProperty("harness.target") ?: "desktop")
    systemProperty("composeai.harness.regenerate", "true")
    useJUnit()
    val baseTest = tasks.test.get()
    classpath = baseTest.classpath
    testClassesDirs = baseTest.testClassesDirs
    // Real-mode-only — fake-mode tests don't drive baselines (they pixel-diff against in-fixture
    // PNGs, not the in-repo ones).
    filter { includeTestsMatching("*RealModeTest") }
    outputs.upToDateWhen { false }
  }
