// Daemon module — see docs/daemon/DESIGN.md § 6 for layout, § 9 for the
// sandbox-holder pattern (B1.3), and § 11 for SandboxScope discipline.
//
// Mirrors :renderer-android's plugin and dependency choices intentionally.
// The daemon needs the same Robolectric / Compose / Roborazzi stack on its
// classpath as the existing JUnit render path, because B1.4 (later) duplicates
// the render body into this module. For B1.1–B1.3 the runtime deps are present
// but only the protocol types and the dummy-@Test sandbox holder are used.
//
// NOT published to Maven. The daemon is consumed only by the Gradle plugin's
// DaemonBootstrapTask (Stream A) which builds a launch descriptor pointing at
// the local module's classpath.

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  // D-harness.v2 — `PreviewManifestRouter` (mirrors :daemon:desktop's) reads a JSON
  // manifest mapping previewId to RenderSpec for harness-driven real-mode runs. Plugin only adds
  // the @Serializable processor; kotlinx-serialization-json is already on the classpath via
  // `:daemon:core`'s `api(libs.kotlinx.serialization.json)`.
  alias(libs.plugins.kotlin.serialization)
}

// The daemon is launched as a local JVM process by the Gradle plugin, never
// shipped as a published artifact, so the older-Kotlin-runtime ABI dance done
// in :renderer-android (via tapmoc.configureKotlinCompatibility) is not
// required here. We get the project's default Kotlin stdlib at runtime.

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

android {
  namespace = "ee.schimke.composeai.daemon"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  // D-harness.v2 — promote the fixture composables (RedSquare/BlueSquare/GreenSquare/SlowSquare/
  // BoomComposable) from this module's `src/test/...` into a real testFixtures source set so
  // `:daemon:harness`'s test runtime classpath can pull them via
  // `testImplementation(testFixtures(project(":daemon:android")))` — same shape
  // `:daemon:desktop` already uses for the desktop testFixtures.
  //
  // AGP 9 supports test fixtures on android library variants; the testFixtures source set
  // produces a published JAR-shape artefact that JVM consumers (the harness is plain
  // `org.jetbrains.kotlin.jvm`) can consume via the standard `testFixtures(project(...))` accessor.
  testFixtures { enable = true }
}

dependencies {
  // Renderer-agnostic protocol types, JsonRpcServer, RenderHost interface,
  // and RenderRequest/RenderResult data classes — see DESIGN.md § 4. The
  // core module re-exposes kotlinx-serialization-json as `api`, so we don't
  // re-declare it here.
  implementation(project(":daemon:core"))

  // Inherit the renderer's Compose/Roborazzi helpers (AccessibilityChecker,
  // GoogleFontInterceptor, AnimationInspector, ScrollDriver,
  // PixelSystemFontAliases, RenderManifest, PreviewRenderStrategy, etc.) —
  // see DESIGN.md § 7.
  implementation(project(":renderer-android"))

  // The daemon process holds a Robolectric sandbox open via a dummy @Test
  // (DESIGN.md § 9), so JUnit + Robolectric must be on the *main* classpath,
  // not just test. DaemonHost in src/main runs the JUnit core programmatically.
  implementation(libs.robolectric)
  implementation(libs.junit)

  // Compose UI test deps — needed by the per-preview render body that B1.4
  // copied in. Listed compileOnly to mirror :renderer-android's contract
  // (consumer module supplies the actual runtime versions). Roborazzi is
  // declared compileOnly here because B1.4's RenderEngine references
  // `captureRoboImage` / `RoborazziOptions` directly; runtime resolution
  // comes via :renderer-android's `implementation(libs.roborazzi.compose)`.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.compose.material3)
  compileOnly(libs.compose.runtime)
  compileOnly(libs.compose.ui.tooling.preview)
  compileOnly(libs.activity.compose)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  compileOnly("androidx.compose.ui:ui-test-manifest")
  compileOnly(libs.roborazzi)
  compileOnly(libs.roborazzi.compose)

  // Test classpath: real runtime versions for the RobolectricHost
  // sandbox-reuse assertion. Protocol round-trip and JsonRpcServer
  // framing/integration tests now live in :daemon:core.
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.compose.ui.tooling.preview)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)

  // D-harness.v2 — testFixtures source set holds RedFixturePreviews.kt so its `RedSquare`,
  // `BlueSquare`, `GreenSquare`, `SlowSquare`, `BoomComposable` composables are consumable from
  // `:daemon:harness`'s test runtime classpath via
  // `testImplementation(testFixtures(project(":daemon:android")))`. Mirrors
  // `:daemon:desktop`'s testFixtures wireup. The Compose foundation/runtime/ui surface
  // is the only piece the fixture composables touch — listed compileOnly because the consumer
  // (`renderer-android` for unit tests, `:daemon:harness` for the real-mode S1-S8 Android
  // tests) supplies the runtime versions.
  "testFixturesImplementation"(platform(libs.compose.bom.compat))
  "testFixturesImplementation"(libs.compose.runtime)
  "testFixturesImplementation"(libs.compose.foundation)
  "testFixturesImplementation"(libs.compose.ui)
}

// D-harness.v2 — the spawned daemon's runtime classpath is built from the daemon module's
// `debugUnitTestRuntimeClasspath` (see the `writeDaemonClasspath` task below). That config is
// already wired up by AGP for the standalone JUnit / Robolectric path (`./gradlew
// :daemon:android:test`) — same classes the daemon needs at runtime when spawned as a
// subprocess by the harness. No separate `daemonRuntimeExtras` configuration is needed: every
// dependency `RenderEngineTest` already exercises (Compose UI test rule, Roborazzi, R.jars for
// transitive AARs, the right multiplatform-Android variant of compose-ui-test) is on this
// classpath.

// D-harness.v2 — the harness (`:daemon:harness`) needs the runtime classpath of this
// module + its testFixtures (where the `RedSquare`/`BlueSquare`/etc fixture composables live)
// in order to spawn the Android daemon as a subprocess for `-Ptarget=android` real-mode runs.
// `:daemon:harness` is plain `org.jetbrains.kotlin.jvm` so it can't directly consume an
// `com.android.library` module's variants — AGP exposes the daemon as an AAR with AAR-shaped
// transitive deps (e.g. roborazzi, androidx.compose.* are AAR-only), and a plain JVM consumer
// can't run AGP's AAR→JAR transforms.
//
// Workaround: this task resolves the daemon's runtime classpath inside this module (which is
// Android-aware by construction), writes the absolute paths to a text file, and surfaces it via
// a custom `daemonClasspathDescriptor` consumable configuration the harness reads at test time.
// See `daemon/harness/build.gradle.kts`'s `androidDaemonClasspath` consumer.
val daemonRuntimeClasspathFile = layout.buildDirectory.file("daemon-harness/runtime-classpath.txt")

// AGP's `debugRuntimeClasspath` configuration carries the resolved runtime, but iterating its
// `elements` re-resolves the project itself with default attributes that hit ambiguity (many
// `android-*` secondary variants). Use an `ArtifactView` with the standard `artifactType=jar`
// filter so we select the classes JAR from each transitive component (and the local module's
// JAR) cleanly.
// Note: AGP 9 creates the per-variant runtime classpath configurations lazily; they aren't
// available at script-level resolution time. Use `afterEvaluate` to access them once AGP has
// finished registering its variants. The descriptor task itself is registered eagerly so
// `:daemon:harness`'s `androidDaemonClasspath` configuration can wire up `dependsOn`
// without needing afterEvaluate semantics.
val writeDaemonClasspath by tasks.registering {
  description =
    "Resolves :daemon:android's debug runtime classpath + testFixtures into a text " +
      "file the harness's RealAndroidHarnessLauncher reads at test time. (D-harness.v2)"
  group = "verification"
  val outputFileProvider = daemonRuntimeClasspathFile
  outputs.file(outputFileProvider)
}

// AGP's SDK android.jar — needed on the spawned daemon's classpath so JUnit / Robolectric can
// introspect annotations referencing `android.app.Application` etc. before sandbox bootstrap. The
// gradle-plugin's `AndroidPreviewClasspath.buildTestClasspath` already does the same thing for
// the standalone JUnit path; we mirror it here for the daemon path.
val androidComponents =
  extensions.getByType(com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java)
val androidBootClasspath = androidComponents.sdkComponents.bootClasspath

afterEvaluate {
  val artifactTypeAttr = Attribute.of("artifactType", String::class.java)
  // Use the daemon module's *test* runtime classpath as the source of truth — AGP fully
  // configures it for the standalone JUnit/Robolectric path (`./gradlew
  // :daemon:android:test`), including AGP-generated R.jars for transitive AARs, the
  // correct multiplatform variant of `androidx.compose.ui:ui-test-junit4` (resolves to
  // `-android` not `-jvmstubs`), and `androidx.customview.poolingcontainer:R$id`. The harness's
  // spawned daemon needs the same classes at runtime — it's literally running the same render
  // body in the same kind of Robolectric sandbox, just from a different process. The
  // testFixtures source set's classes are ALSO on this classpath (Gradle's `testRuntimeClasspath`
  // includes the testFixtures jar by convention).
  val testRuntimeCfg = configurations.findByName("debugUnitTestRuntimeClasspath")
  val mainBundleTask = tasks.findByName("bundleLibRuntimeToJarDebug")
  val tfBundleTask = tasks.findByName("bundleLibRuntimeToJarDebugTestFixtures")
  if (testRuntimeCfg == null || mainBundleTask == null || tfBundleTask == null) {
    return@afterEvaluate
  }
  val testRuntimeJars =
    testRuntimeCfg.incoming.artifactView { attributes { attribute(artifactTypeAttr, "jar") } }.files
  val mainBundleFiles = mainBundleTask.outputs.files
  val tfBundleFiles = tfBundleTask.outputs.files
  // AGP-generated R.jar containing the transitive AAR's R.id classes (e.g.
  // `androidx.customview.poolingcontainer.R$id`). Without this on the spawned daemon's classpath,
  // `setContent` fails with NoClassDefFoundError when Compose's PoolingContainer accesses the
  // generated id constants. Standalone `:daemon:android:test` gets it for free via
  // AGP's wireup; we mirror it here.
  val unitTestRJarTask = tasks.findByName("processDebugUnitTestResources")
  val unitTestRJarFiles = unitTestRJarTask?.outputs?.files
  val bootClasspathProvider = androidBootClasspath
  writeDaemonClasspath.configure {
    dependsOn(mainBundleTask, tfBundleTask)
    if (unitTestRJarTask != null) {
      dependsOn(unitTestRJarTask)
    }
    inputs.files(testRuntimeJars, mainBundleFiles, tfBundleFiles)
    if (unitTestRJarFiles != null) {
      inputs.files(unitTestRJarFiles)
    }
    inputs.files(bootClasspathProvider)
    val outFile = daemonRuntimeClasspathFile
    doLast {
      val all = linkedSetOf<String>()
      // Local module's main JAR + testFixtures JAR first so the daemon's `RenderEngine` and the
      // fixture composables outrank any clash from external deps.
      mainBundleFiles.forEach { all.add(it.absolutePath) }
      tfBundleFiles.forEach { all.add(it.absolutePath) }
      // R.jar from `processDebugUnitTestResources` (transitive AAR R-classes).
      unitTestRJarFiles
        ?.filter { it.exists() && it.name.endsWith(".jar") }
        ?.forEach { all.add(it.absolutePath) }
      // The full test runtime classpath. Brings multiplatform-Android variants of
      // androidx.compose.ui:ui-test-*, all transitive deps.
      testRuntimeJars.forEach { all.add(it.absolutePath) }
      // SDK android.jar last (bottom of classpath).
      bootClasspathProvider.get().forEach { all.add(it.asFile.absolutePath) }
      outFile.get().asFile.apply {
        parentFile.mkdirs()
        writeText(all.joinToString(System.lineSeparator()))
      }
    }
  }
}

// Consumable configuration that surfaces the classpath text file as an artifact. The harness
// declares a counterpart `androidDaemonClasspath` configuration with matching attributes and
// reads `it.singleFile`. Plain-text content; no AGP transforms involved on the consumer side.
val daemonHarnessClasspathFile by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
  attributes {
    attribute(
      Attribute.of("ee.schimke.composeai.daemon.harness.classpath", String::class.java),
      "android",
    )
  }
}

artifacts { add(daemonHarnessClasspathFile.name, writeDaemonClasspath) }
