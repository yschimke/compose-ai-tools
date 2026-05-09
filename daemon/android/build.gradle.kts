// Daemon module — see docs/daemon/DESIGN.md § 6 for layout, § 9 for the
// sandbox-holder pattern (B1.3), and § 11 for SandboxScope discipline.
//
// Mirrors :renderer-android's plugin and dependency choices intentionally.
// The daemon needs the same Robolectric / Compose / Roborazzi stack on its
// classpath as the existing JUnit render path, because B1.4 (later) duplicates
// the render body into this module. For B1.1–B1.3 the runtime deps are present
// but only the protocol types and the dummy-@Test sandbox holder are used.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-android` —
// pairs with `daemon-core` so the Android (Robolectric) daemon backend is
// consumable by coordinate. Single artifact across the supported Compose +
// Roborazzi range; Compose / Roborazzi / UI-test / activity-compose stay
// `compileOnly` and are supplied by the consumer's runtime, same pattern
// `:renderer-android` already uses (see DESIGN.md § 17 for the decision and
// § 19 for the captureToImage fallback if Roborazzi's API ever does break).
// Pre-1.0; expect API breakage across minor versions.

import java.util.concurrent.Callable

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  // D-harness.v2 — `PreviewManifestRouter` (mirrors :daemon:desktop's) reads a JSON
  // manifest mapping previewId to RenderSpec for harness-driven real-mode runs. Plugin only adds
  // the @Serializable processor; kotlinx-serialization-json is already on the classpath via
  // `:daemon:core`'s `api(libs.kotlinx.serialization.json)`.
  alias(libs.plugins.kotlin.serialization)
}

// The daemon is launched as a local JVM process by the Gradle plugin (or
// directly via the published JAR for embedders), so the older-Kotlin-runtime
// ABI dance done in :renderer-android (via tapmoc.configureKotlinCompatibility)
// is not required here. We get the project's default Kotlin stdlib at runtime.

android {
  namespace = "ee.schimke.composeai.daemon"
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
  implementation(project(":data-fonts-connector"))
  implementation(project(":data-render-connector"))
  implementation(project(":data-history-connector"))
  implementation(project(":data-layoutinspector-connector"))
  implementation(project(":data-resources-connector"))
  implementation(project(":data-strings-connector"))
  implementation(project(":data-theme-connector"))
  implementation(project(":data-wallpaper-connector"))
  // Wear OS ambient-mode connector — Robolectric shadow of `AmbientLifecycleObserver`,
  // `AmbientStateController`, the `AroundComposable` extension that primes it, and the
  // `AmbientInputDispatchObserver` that wakes on activating gestures during recording.
  implementation(project(":data-ambient-connector"))
  // Display-filter connector — DisplayFilterDataProductRegistry + DisplayFilterDataProducer.
  // DaemonMain reads `composeai.displayfilter.filters` via `DisplayFilterConfig` to decide
  // whether to register the extension; the host's render pipeline reads the same prop and
  // calls `DisplayFilterDataProducer.writeArtifacts(...)` post-capture (RenderEngine wiring
  // lands in a follow-up).
  implementation(project(":data-displayfilter-connector"))
  // Focus / keyboard-traversal connector — `FocusController`, the `AroundComposable` extension
  // that installs `LocalInputModeManager provides KeyboardInputModeManager` and drives the focus
  // walk from a `LaunchedEffect`, the `FocusPreviewOverrideExtension` planner consuming
  // `renderNow.overrides.focus`, and the post-capture `FocusOverlay`. The renderer-android plugin
  // path consumes the same connector — see `:renderer-android`'s `implementation` line.
  implementation(project(":data-focus-connector"))
  // Pseudolocale connector — `Pseudolocalizer` (en-XA / ar-XB transforms), `PseudolocaleResources`
  // / `PseudolocaleContext` runtime resource interception, and the
  // `PseudolocalePreviewOverrideExtension` planner mapped to `localeTag` in {en-XA, ar-XB}. No
  // build-time `pseudoLocalesEnabled` / `resConfigs` requirement on the consumer.
  implementation(project(":data-pseudolocale-connector"))
  // UIAutomator-shaped Selector + UiObject — RobolectricHost.performUiAutomatorAction decodes
  // selector JSON via decodeSelectorJson and walks the SemanticsNode tree via
  // UiAutomator.findObject(rule, selector, useUnmergedTree).
  implementation(project(":data-uiautomator-core"))
  // `uia.*` script-event descriptors + `UiAutomatorDataProducer` / `UiAutomatorDataProductRegistry`
  // (#874). DaemonMain registers an Extension(id="uiautomator", ...) carrying these descriptors
  // and the registry; AndroidRecordingSession registers a handler per id that routes to
  // dispatchUiAutomator; RenderEngine.kt calls the producer's `writeArtifacts(...)` post-capture.
  implementation(project(":data-uiautomator-connector"))

  // Android-platform-specific UIAutomator hierarchy producer — `RenderEngine.kt` installs
  // `UiAutomatorHierarchyExtension` and runs the typed extension contract to compute the
  // `UiAutomatorHierarchyPayload` before handing it to `UiAutomatorDataProducer`.
  implementation(project(":data-uiautomator-hierarchy-android"))

  // Inherit the renderer's Compose/Roborazzi helpers (GoogleFontInterceptor,
  // AnimationInspector, ScrollDriver, PixelSystemFontAliases, RenderManifest,
  // PreviewRenderStrategy, etc.) — see DESIGN.md § 7.
  implementation(project(":renderer-android"))
  implementation(libs.okio)

  // D2.2 — accessibility data product (registry + producer + image processor) lives in its
  // own module pair: `:data-a11y-connector` brings `:data-a11y-core` (the generic ATF +
  // overlay code, published) along transitively. `RenderEngine.kt` constructs
  // `AccessibilityImageProcessor` and calls `AccessibilityDataProducer.writeArtifacts(...)`
  // through this dep. `api` so downstream `:samples:android-daemon-bench` etc. can also see
  // `AccessibilityFinding` / `AccessibilityNode` without adding their own project dep.
  api(project(":data-a11y-connector"))

  // Android-platform-specific hierarchy producer — `RenderEngine.kt` installs
  // `AccessibilityHierarchyExtension` and runs the typed extension contract instead of calling
  // `AccessibilityChecker.analyze` directly. Pairs with `:data-a11y-core`'s consumers
  // (TouchTargetsExtension, OverlayExtension) and any future `:data-a11y-hierarchy-desktop`.
  implementation(project(":data-a11y-hierarchy-android"))

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
  compileOnly(libs.compose.runtime.tracing)
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
  testImplementation(libs.compose.runtime.tracing)
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
  "testFixturesImplementation"(libs.compose.material3)
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
  // Wrap the artifactView's lazy `FileCollection` in a `Callable`-backed `project.files(...)` so
  // resolution of `debugUnitTestRuntimeClasspath` is deferred until task execution rather than
  // happening during task-graph build. Mirrors the pattern in
  // `gradle-plugin/.../AndroidPreviewSupport.kt`'s rendererJars wireup. Without this, Gradle
  // queries the artifactView while computing `writeDaemonClasspath`'s task dependencies, which
  // races with concurrent variant resolution under `org.gradle.parallel=true` and throws
  // `ConcurrentModificationException` from inside AGP's variant attribute machinery.
  val testRuntimeJarsView =
    testRuntimeCfg.incoming.artifactView { attributes { attribute(artifactTypeAttr, "jar") } }.files
  val testRuntimeJars = project.files(Callable { testRuntimeJarsView })
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

// Use the new (non-deprecated) AndroidSingleVariantLibrary constructor — takes typed JavadocJar /
// SourcesJar instead of booleans. `JavadocJar.Empty()` ships an empty javadoc jar (Maven Central
// requires the file to exist) without invoking javadoc/Dokka. javadoc 17 fails on AndroidX
// dependencies' Kotlin metadata version mismatch (`expected version is 1.4.2`); Dokka would work
// but is heavyweight for an artifact whose value is in KDocs the sources jar carries anyway.
// `:renderer-android` still uses the deprecated (Boolean, Boolean) overload — fine to migrate
// independently when its release bumps next.
val androidSingleVariantLibrary =
  com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
    javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
    sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
    variant = "release",
  )

// AGP `testFixtures { enable = true }` ships `daemon-android-<version>-test-fixtures.aar` as
// part of the published `release` component. The fixture composables (`RedSquare`/`BlueSquare`/
// etc.) are internal harness aids, not for consumers. Skip the testFixtures publication
// configurations from the `release` component. The configuration names are AGP's own
// `releaseTestFixturesVariantRelease{Api,Runtime,Source}Publication` (the "Variant…Publication"
// suffix is the AGP-side naming, NOT the standard `releaseTestFixtures*Elements` from
// `java-test-fixtures`); we observed them with a one-off diagnostic.
//
// In-build `testFixtures(project(":daemon:android"))` consumption from `:daemon:harness` is
// unaffected — that uses the project dep, not the published artifact.
afterEvaluate {
  val releaseComponent =
    components.findByName("release") as? org.gradle.api.component.AdhocComponentWithVariants
  releaseComponent?.let { component ->
    // Only the Api and Runtime publications are actually attached to the `release` component as
    // variants — `releaseTestFixturesVariantReleaseSourcePublication` exists as a configuration
    // but isn't part of the component, so trying to skip it errors with "Variant for
    // configuration ... does not exist in component". The .module file confirms only Api and
    // Runtime variants leak; skipping those two also drops the test-fixtures.aar artifact.
    listOf(
        "releaseTestFixturesVariantReleaseApiPublication",
        "releaseTestFixturesVariantReleaseRuntimePublication",
      )
      .forEach { name ->
        configurations.findByName(name)?.let {
          component.withVariantsFromConfiguration(it) { skip() }
        }
      }
  }
}

artifacts { add(daemonHarnessClasspathFile.name, writeDaemonClasspath) }

mavenPublishing { configure(androidSingleVariantLibrary) }

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-android",
    displayName = "Compose Preview — Daemon Android",
    description =
      "Robolectric-based Android backend of the compose-preview daemon: holds a long-lived Robolectric sandbox open via the dummy-@Test trick, renders @Preview composables to PNG. Compose / Roborazzi / UI-test stay compileOnly — consumer supplies runtime versions, same as :renderer-android. Pre-1.0; pairs with daemon-core.",
  )
  inceptionYear.set("2025")
}
