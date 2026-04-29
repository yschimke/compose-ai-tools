package ee.schimke.composeai.renderer

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.daemon.forensics.ClassloaderForensics
import ee.schimke.composeai.daemon.forensics.RobolectricConfigSnapshot
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Configuration A — working standalone path — for the
 * [classloader-forensics dump design](../../../../../../../docs/daemon/CLASSLOADER-FORENSICS.md).
 *
 * Mirrors `RobolectricRenderTest`'s bootstrap shape (see that class for the full
 * `@Config`/`@GraphicsMode` rationale): a JUnit `@Test` annotated `@RunWith(RobolectricTestRunner)`
 * + `@Config(sdk = 35)` + `@GraphicsMode(NATIVE)`. The dump runs after Robolectric's sandbox
 * bootstrap so every survey class resolves through the active `InstrumentingClassLoader` (or its
 * parent), reflecting the loaded-class graph as seen by the working `getDeclaredComposableMethod`
 * code path.
 *
 * Output: `renderer-android/build/reports/classloader-forensics/standalone.json`. The diff tool
 * (`./gradlew :daemon:harness:dumpClassloaderDiff`) consumes both this JSON and the daemon
 * counterpart's JSON to produce `diff.{json,md}`.
 *
 * Sanity check 1 from the design (run twice, dumps byte-identical modulo timestamps) is exercised
 * by simply re-running this test; the diff tool's "no smoking guns" assertion when comparing the
 * dump against itself catches non-determinism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassloaderForensicsTest {

  @Test
  fun captureStandaloneClassloaderForensics() {
    // Force class loading for the user preview fixture (the synthetic file class
    // `ClassloaderForensicsFixturesKt`) so `Class.forName(...)` inside the forensics library
    // resolves it via the same path the production renderer would. We resolve by FQN rather than
    // by `::class.java` because the fixture function is `@Composable` and Kotlin rejects bare
    // function references at non-Composable call sites.
    @Suppress("UNUSED_VARIABLE")
    val ensureFixtureLoaded =
      Class.forName("ee.schimke.composeai.renderer.ClassloaderForensicsFixturesKt").name

    val out =
      File("build/reports/classloader-forensics/standalone.json").apply { parentFile?.mkdirs() }
    val survey = COMMON_SURVEY_SET + STANDALONE_USER_PREVIEW
    val cfg = captureRobolectricConfigSnapshot()
    ClassloaderForensics.capture(
      surveySet = survey,
      robolectricConfig = cfg,
      contextHint = "standalone-control",
      out = out,
    )
    println("ClassloaderForensicsTest wrote ${out.absolutePath}")
  }

  /**
   * Best-effort snapshot of the active Robolectric sandbox configuration. Most fields come from
   * the public [RuntimeEnvironment] API; the instrumentation-filter lists require reflection into
   * `InstrumentingClassLoader.config` (a private field) and are populated when reachable, empty
   * otherwise. Documented inline so a future Robolectric bump that renames the field surfaces here
   * cleanly rather than as a silent gap.
   */
  private fun captureRobolectricConfigSnapshot(): RobolectricConfigSnapshot {
    val applicationClassName: String =
      try {
        val app: android.app.Application = ApplicationProvider.getApplicationContext()
        app.javaClass.name
      } catch (_: Throwable) {
        ""
      }
    val mainLooper: Looper? =
      try {
        Looper.getMainLooper()
      } catch (_: Throwable) {
        null
      }
    val looperMode = System.getProperty("robolectric.looperMode") ?: "<default>"
    val graphicsMode = System.getProperty("robolectric.graphicsMode") ?: "<default>"

    val instrumentingClassLoader = Thread.currentThread().contextClassLoader
    val instrumentingClassLoaderId =
      "0x" + Integer.toHexString(System.identityHashCode(instrumentingClassLoader))

    val (instrumentedPackages, doNotInstrumentPackages, doNotAcquirePackages) =
      readInstrumentationFilters(instrumentingClassLoader)

    return RobolectricConfigSnapshot(
      apiLevel =
        try {
          RuntimeEnvironment.getApiLevel()
        } catch (_: Throwable) {
          -1
        },
      qualifiers =
        try {
          RuntimeEnvironment.getQualifiers() ?: ""
        } catch (_: Throwable) {
          ""
        },
      fontScale =
        try {
          RuntimeEnvironment.getFontScale()
        } catch (_: Throwable) {
          1.0f
        },
      applicationClassName = applicationClassName,
      graphicsMode = graphicsMode,
      looperMode = looperMode + " (mainLooper.thread=${mainLooper?.thread?.name ?: "<null>"})",
      instrumentedPackages = instrumentedPackages,
      doNotInstrumentPackages = doNotInstrumentPackages,
      doNotAcquirePackages = doNotAcquirePackages,
      sandboxFactoryClassName = "org.robolectric.internal.SandboxFactory",
      instrumentingClassLoaderIdentity = instrumentingClassLoaderId,
    )
  }

  /**
   * Best-effort reflective read of `InstrumentingClassLoader`'s configuration filters. Robolectric
   * 4.x stores the active `InstrumentationConfiguration` in a `config` field on the class loader;
   * its accessors expose three sets we care about. The field name has stayed stable across 4.10–
   * 4.16 but is technically internal; a future rename would surface as empty lists in the dump
   * (visible in the diff) rather than a thrown exception.
   */
  private fun readInstrumentationFilters(loader: ClassLoader?):
    Triple<List<String>, List<String>, List<String>> {
    if (loader == null) return Triple(emptyList(), emptyList(), emptyList())
    return try {
      val configField =
        loader.javaClass.declaredFields.firstOrNull { f ->
          f.type.name.endsWith("InstrumentationConfiguration")
        } ?: return Triple(emptyList(), emptyList(), emptyList())
      configField.isAccessible = true
      val cfg = configField.get(loader) ?: return Triple(emptyList(), emptyList(), emptyList())
      val cfgClass = cfg.javaClass
      val instrumented =
        readStringSet(cfg, cfgClass, "getInstrumentedPackages", "instrumentedPackages")
      val doNotInstrument =
        readStringSet(cfg, cfgClass, "getDoNotInstrumentPackages", "doNotInstrumentPackages") +
          // Older versions also kept doNotInstrumentClasses — surface them as a separate prefix.
          readStringSet(cfg, cfgClass, "getDoNotInstrumentClasses", "doNotInstrumentClasses")
      val doNotAcquire =
        readStringSet(cfg, cfgClass, "getDoNotAcquirePackages", "doNotAcquirePackages")
      Triple(instrumented.sorted(), doNotInstrument.sorted(), doNotAcquire.sorted())
    } catch (_: Throwable) {
      Triple(emptyList(), emptyList(), emptyList())
    }
  }

  private fun readStringSet(
    target: Any,
    cls: Class<*>,
    accessorName: String,
    fieldName: String,
  ): List<String> {
    return try {
      val accessor =
        try {
          cls.getMethod(accessorName)
        } catch (_: NoSuchMethodException) {
          null
        }
      if (accessor != null) {
        val v = accessor.invoke(target)
        @Suppress("UNCHECKED_CAST")
        return when (v) {
          is Set<*> -> v.filterIsInstance<String>().toList()
          is Collection<*> -> v.filterIsInstance<String>().toList()
          else -> emptyList()
        }
      }
      // Fall back to direct field access.
      val field = cls.declaredFields.firstOrNull { it.name == fieldName } ?: return emptyList()
      field.isAccessible = true
      val v = field.get(target)
      when (v) {
        is Set<*> -> v.filterIsInstance<String>().toList()
        is Collection<*> -> v.filterIsInstance<String>().toList()
        else -> emptyList()
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  companion object {

    /**
     * Survey set per CLASSLOADER-FORENSICS.md § "Survey set". Mandatory + optional combined; we
     * include every entry the design enumerates so the diff has the broadest possible spotlight.
     * Daemon-only entries are added in `:daemon:android`'s sister test, not here.
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
        // Sample-of-opportunity: cheap, might surface unexpected divergence
        "kotlinx.coroutines.CoroutineDispatcher",
        "kotlin.reflect.KClass",
        "androidx.lifecycle.ViewModel",
        "androidx.compose.runtime.internal.ComposableLambdaImpl",
        // Sanity-check anchor: must be identical across configurations
        "java.lang.String",
        "kotlin.Unit",
      )

    /**
     * Configuration A's "user preview class" — `:renderer-android`'s test fixture. Configuration B
     * substitutes its own daemon-side fixture (`RedFixturePreviewsKt`) loaded via the child
     * `URLClassLoader` instead.
     */
    val STANDALONE_USER_PREVIEW: List<String> =
      listOf("ee.schimke.composeai.renderer.ClassloaderForensicsFixturesKt")
  }
}
