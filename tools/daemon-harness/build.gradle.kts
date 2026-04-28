// Daemon end-to-end test harness — see docs/daemon/TEST-HARNESS.md and
// docs/daemon/TODO.md § D-harness.v0.
//
// The harness plays the role of VS Code against a real daemon JVM over
// JSON-RPC. Renderer-agnostic by construction: only depends on
// `:renderer-daemon-core` for protocol types + `RenderHost` interface +
// `JsonRpcServer`. **No** dependency on `:renderer-android-daemon` or
// `:renderer-desktop-daemon` — the v0 harness spawns its own
// `FakeDaemonMain` (in `src/main/kotlin/.../FakeDaemonMain.kt`) which wires
// `JsonRpcServer` onto a `FakeHost`. Once B-desktop.1.5 lands, v1.5 flips
// `-Pharness.host=real` and consumes the real launcher descriptor; that
// classpath continues to live in the bench module, never here.
//
// Plain `org.jetbrains.kotlin.jvm` — no Android plugins, no Compose. NOT
// published to Maven.

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
  implementation(project(":renderer-daemon-core"))

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
  // resolves `:renderer-desktop-daemon`'s `runtimeClasspath` and writes it to a file the test
  // reads) was rejected for boilerplate without a concrete benefit at v1.5a scope.
  //
  // We deliberately do NOT apply Compose plugins here — that would force every `.kt` in the
  // harness's production source set to live on a Compose runtime classpath. Instead the harness
  // pulls in the `tests` configuration of `:renderer-desktop-daemon` (java-test-fixtures-style)
  // so the `RedFixturePreviews.RedSquare` composable already compiled by the desktop daemon's
  // test source set is on this module's test runtime classpath.
  testImplementation(project(":renderer-desktop-daemon"))
  testImplementation(testFixtures(project(":renderer-desktop-daemon")))
  // Skiko native bundle. Compose runtime/foundation/ui propagate transitively via
  // `:renderer-desktop-daemon` (its own runtime classpath), so we don't re-declare them here.
  // `:renderer-desktop` already brings `compose.desktop.currentOs` for the production renderer,
  // so the harness's test classpath inherits the per-OS Skiko bundle automatically.

  // D-harness.v2 — Android target (`-Ptarget=android`). Strategy diverges from desktop:
  //
  // **Why we don't repeat desktop's `testImplementation(project(":renderer-android-daemon"))`
  // pattern.** Plain-JVM consumers can't natively pull an `com.android.library` module's runtime
  // classpath. AGP exposes the library as an AAR with AAR-shaped transitive dependencies (e.g.
  // `roborazzi`, `androidx.compose.*` are AAR-only); a plain JVM consumer doesn't run AGP's
  // AAR→JAR transforms. The fixture composables further need AGP-generated R.jars for transitive
  // AARs (`androidx.customview.poolingcontainer.R$id`) which are only synthesized inside the
  // daemon module's debug-unit-test runtime classpath setup.
  //
  // **What we do instead.** `:renderer-android-daemon` exposes a `daemonHarnessClasspathFile`
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
// `:renderer-android-daemon` module exposes a `daemonHarnessClasspathFile` consumable
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

dependencies { androidDaemonClasspath(project(":renderer-android-daemon")) }

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
  // `*AndroidRealModeTest.kt` test classes which spawn the real `:renderer-android-daemon`
  // `DaemonMain` via `RealAndroidHarnessLauncher`. Tests skip via `Assume.assumeTrue` when
  // target doesn't match — both target sets coexist in the same JUnit suite.
  systemProperty("composeai.harness.target", findProperty("harness.target") ?: "desktop")
}

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .harness.FakeDaemonMain`. Mirrors `:renderer-desktop-daemon`'s `runDaemonMain` so the
// FakeDaemonMain entry point is locally runnable for sanity-checking without spinning up a JUnit
// scenario. Not used by CI — `HarnessClient` spawns its own subprocess via ProcessBuilder.
tasks.register<JavaExec>("runFakeDaemonMain") {
  group = "application"
  description = "Runs FakeDaemonMain against a fixture directory (-Dcomposeai.harness.fixtureDir)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("ee.schimke.composeai.daemon.harness.FakeDaemonMain")
}

// D-harness.v1.5b — regenerate the in-repo PNG baselines under
// `tools/daemon-harness/baselines/desktop/<scenario>/`. Runs every real-mode scenario in
// "capture" mode: pixel-diffs are skipped and the captured PNG always overwrites the baseline.
// See `tools/daemon-harness/CONTRIBUTING.md` for when to run this. Two runs in a row should
// produce byte-identical PNGs; if they don't, the renderer has a non-determinism worth chasing.
val regenerateBaselines by
  tasks.registering(Test::class) {
    description =
      "Run every harness scenario in capture mode; overwrites in-repo baseline PNGs " +
        "(D-harness.v1.5b + v2). Pick target with `-Ptarget=desktop|android`; defaults to " +
        "desktop. Captures into tools/daemon-harness/baselines/<target>/<scenario>/<id>.png."
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
