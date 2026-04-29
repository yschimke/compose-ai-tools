package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bridge.DaemonHostBridge
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuration B — daemon path — for the
 * [classloader-forensics dump design](../../../../../../../docs/daemon/CLASSLOADER-FORENSICS.md).
 *
 * Spawns a real [RobolectricHost] (which holds a sandbox open via [SandboxHoldingRunner], the
 * daemon's variant of `RobolectricTestRunner` that registers the bridge package as
 * `doNotAcquirePackage`), wires up a [UserClassLoaderHolder] pointing at the testFixtures classes
 * directory (so `ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare` is resolved via the
 * disposable child `URLClassLoader`), and submits a forensic-dump request whose payload routes to
 * [RobolectricHost.SandboxRunner.runForensicDump] inside the sandbox.
 *
 * **Why through the host?** The dump must run *inside the sandbox* with the child URLClassLoader
 * active. Calling [ee.schimke.composeai.daemon.forensics.ClassloaderForensics.capture] directly
 * from this test thread would just dump the test JVM's classloader graph — not what the daemon
 * sees during a render. Routing via [RobolectricHost.submit] guarantees the dump lands on the same
 * sandbox thread that `RenderEngine.render` would run on, with the same context classloader
 * discipline (`Thread.currentThread().contextClassLoader = effectiveLoader`).
 *
 * **Forensic-dump payload routing.** Per the design's "don't widen the core's sealed hierarchy"
 * constraint, we use `RobolectricHost.FORENSIC_DUMP_PREFIX` to route through the existing
 * `RenderRequest.Render.payload` field rather than introducing a new `RenderRequest` variant.
 *
 * Output: `daemon/android/build/reports/classloader-forensics/daemon.json`. The diff tool
 * (`./gradlew :daemon:harness:dumpClassloaderDiff`) consumes this together with
 * `:renderer-android`'s `standalone.json` to produce `diff.{json,md}`.
 */
class ClassloaderForensicsDaemonTest {

  @Test
  fun captureDaemonClassloaderForensics() {
    val outDir = File("build/reports/classloader-forensics").apply { mkdirs() }
    val outFile = File(outDir, "daemon.json")

    // Stage the testFixtures classes into a fixture directory the holder will expose to its child
    // URLClassLoader. We *could* point the holder directly at the testFixtures bundle the AGP
    // build produces, but copying a tiny subset (RedFixturePreviewsKt + ancillaries) into a
    // dedicated dir guarantees the holder's child loader serves only the user-class artefacts —
    // the same shape `B-desktop.1.1`'s testFixtures wired up.
    val fixtureDir = stageFixtureClassesDir()
    val holder =
      UserClassLoaderHolder(
        urls = listOf(fixtureDir.toURI().toURL()),
        // B2.0-followup — the child URLClassLoader's parent must be the **sandbox** classloader,
        // not the test thread's app loader. Without this, the v0 forensics dump showed 18 of 31
        // shared classes (Compose runtime, Robolectric internals, etc.) loaded via the app loader
        // in the daemon path while Configuration A loaded them via the SandboxClassLoader —
        // classloader-identity skew that breaks getDeclaredComposableMethod's parameter-type match.
        // The supplier reads `DaemonHostBridge.sandboxClassLoaderRef`, set inside the sandbox by
        // `SandboxHoldingRunner.holdSandboxOpen` before any render request is dispatched.
        parentSupplier = {
          DaemonHostBridge.currentSandboxClassLoader()
            ?: error(
              "DaemonHostBridge.sandboxClassLoaderRef is null — sandbox prologue didn't run."
            )
        },
      )

    val survey = (COMMON_SURVEY_SET + DAEMON_ONLY_SURVEY + DAEMON_USER_PREVIEW)

    val host = RobolectricHost(userClassloaderHolder = holder)
    host.start()
    try {
      val payload =
        "${RobolectricHost.FORENSIC_DUMP_PREFIX}${outFile.absolutePath};" +
          "${RobolectricHost.FORENSIC_SURVEY_KEY}=${survey.joinToString(",")}"
      val request = RenderRequest.Render(payload = payload)
      val result = host.submit(request, timeoutMs = 180_000)
      // `pngPath` carries the dump path back via the same field (no need to widen RenderResult).
      val reported = result.pngPath
      assertTrue(
        "host should report the dump path through pngPath; got $reported",
        reported != null && reported == outFile.absolutePath,
      )
    } finally {
      host.shutdown()
    }

    assertTrue("daemon dump file must exist: ${outFile.absolutePath}", outFile.exists())
    assertTrue("daemon dump file must not be empty", outFile.length() > 0)
    val text = outFile.readText()
    // Sanity probe: the dump must include at least one daemon-only class (proving the survey ran
    // inside the sandbox where the daemon classes are reachable, not on a plain JVM classpath).
    assertTrue(
      "dump should contain DaemonHostBridge entry",
      text.contains("ee.schimke.composeai.daemon.bridge.DaemonHostBridge"),
    )
    println("ClassloaderForensicsDaemonTest wrote ${outFile.absolutePath}")
  }

  /**
   * Copies (or links) the fixture classes from the testFixtures classes dir into a temp directory
   * the [UserClassLoaderHolder] can serve. We resolve the fixture class's `getResource(".class")`
   * to find the underlying classes-dir at test time so we don't hard-code an AGP-internal path
   * (which has changed across AGP minor versions).
   */
  private fun stageFixtureClassesDir(): File {
    val tempDir = Files.createTempDirectory("forensics-userClasses").toFile()
    tempDir.deleteOnExit()

    // Resolve the testFixtures classes-dir at runtime. `RedFixturePreviewsKt` is the synthetic Kt
    // file class for the testFixtures source set; its classloader resource lookup gives us the
    // URL of the .class which we can walk back to the classes-root.
    val resourceName = "ee/schimke/composeai/daemon/RedFixturePreviewsKt.class"
    val url =
      (Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())
        .getResource(resourceName)
        ?: error("Can't locate testFixtures class on the test classpath: $resourceName")
    val urlString = url.toString()
    // Two cases: directory-style URL (file:.../classes/.../RedFixturePreviewsKt.class) or
    // jar-style (jar:file:.../testFixtures.jar!/ee/schimke/...). For the standard AGP unit-test
    // classpath, testFixtures classes appear directory-style.
    if (urlString.startsWith("file:")) {
      val classFile = File(url.toURI())
      // Walk up to the classes-root by stripping the package path.
      val pkgDepth = "ee/schimke/composeai/daemon".count { it == '/' } + 1
      var root: File = classFile.parentFile ?: error("classFile has no parent: $classFile")
      repeat(pkgDepth) {
        root = root.parentFile ?: error("ran off the top of the classes-dir walking up from $classFile")
      }
      // Copy *only* the classes under `ee/schimke/composeai/daemon/Red*.class` etc — the survey
      // doesn't need the rest of the testFixtures, but copying the whole directory tree is
      // cheaper than mirroring exact class names.
      root.copyRecursively(tempDir, overwrite = true)
      return tempDir
    } else if (urlString.startsWith("jar:file:")) {
      // Extract from the JAR into tempDir. Bounded-cost — only the classes under
      // `ee/schimke/composeai/daemon/`.
      val jarPath = urlString.removePrefix("jar:file:").substringBefore("!").let { File(it) }
      java.util.zip.ZipFile(jarPath).use { jar ->
        for (e in jar.entries()) {
          if (!e.name.startsWith("ee/schimke/composeai/daemon/") || e.isDirectory) continue
          val out = File(tempDir, e.name)
          out.parentFile?.mkdirs()
          jar.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
      }
      return tempDir
    } else {
      error("Unsupported testFixtures URL shape: $urlString")
    }
  }

  companion object {

    /**
     * Mirrors `:renderer-android`'s `ClassloaderForensicsTest.COMMON_SURVEY_SET` so the
     * standalone vs daemon diff is apples-to-apples. Duplicated rather than promoted to
     * `:daemon:core` because the survey-set is a test-side concern, and promoting would
     * widen the renderer-agnostic surface for a list that's likely to evolve as the diagnostic
     * matures.
     */
    val COMMON_SURVEY_SET: List<String> =
      listOf(
        // Compose runtime
        "androidx.compose.runtime.Composer",
        "androidx.compose.runtime.Composition",
        "androidx.compose.runtime.Recomposer",
        "androidx.compose.runtime.LaunchedEffect",
        // Compose UI
        "androidx.compose.ui.platform.LocalContext",
        "androidx.compose.ui.platform.LocalConfiguration",
        "androidx.compose.ui.platform.LocalView",
        // Compose UI test
        "androidx.compose.ui.test.junit4.AndroidComposeTestRule",
        "androidx.compose.ui.test.junit4.ComposeTestRule",
        // Compose runtime reflect — the type that throws the NoSuchMethodException we're chasing
        "androidx.compose.runtime.reflect.ComposableMethod",
        // Roborazzi
        "com.github.takahirom.roborazzi.RoborazziKt",
        "com.github.takahirom.roborazzi.RoborazziOptions",
        // Robolectric
        "org.robolectric.RuntimeEnvironment",
        "org.robolectric.shadows.ShadowApplication",
        "org.robolectric.shadows.ShadowResources",
        "org.robolectric.RobolectricTestRunner",
        "org.robolectric.internal.bytecode.InstrumentingClassLoader",
        "org.robolectric.internal.SandboxFactory",
        // Android framework
        "android.app.Activity",
        "android.content.res.Resources",
        "androidx.activity.ComponentActivity",
        "android.os.Looper",
        // JUnit
        "org.junit.runner.RunWith",
        "org.junit.runners.model.Statement",
        "org.junit.runners.JUnit4",
        // Sample-of-opportunity
        "kotlinx.coroutines.CoroutineDispatcher",
        "kotlin.reflect.KClass",
        "androidx.lifecycle.ViewModel",
        "androidx.compose.runtime.internal.ComposableLambdaImpl",
        // Sanity-check anchor
        "java.lang.String",
        "kotlin.Unit",
      )

    /** Daemon-only entries per the design's "Daemon-only" section. */
    val DAEMON_ONLY_SURVEY: List<String> =
      listOf(
        "ee.schimke.composeai.daemon.bridge.DaemonHostBridge",
        "ee.schimke.composeai.daemon.RobolectricHost",
        "ee.schimke.composeai.daemon.RenderEngine",
        "ee.schimke.composeai.daemon.SandboxHoldingRunner",
        "ee.schimke.composeai.daemon.UserClassLoaderHolder",
        // The child URLClassLoader's class — captured indirectly via java.net.URLClassLoader's
        // FQN (the holder's ChildFirstURLClassLoader is private). The diff highlights whether
        // the child loader resolves through the same `URLClassLoader.class` instance both sides.
        "java.net.URLClassLoader",
      )

    /**
     * Configuration B's "user preview class" — same FQN the standalone fixture mirrors but loaded
     * via the disposable child URLClassLoader. Diagnostic: if the diff shows this class loaded via
     * `InstrumentingClassLoader` directly (not via the child), the child-first delegation isn't
     * actually child-first.
     */
    val DAEMON_USER_PREVIEW: List<String> =
      listOf("ee.schimke.composeai.daemon.RedFixturePreviewsKt")
  }
}
