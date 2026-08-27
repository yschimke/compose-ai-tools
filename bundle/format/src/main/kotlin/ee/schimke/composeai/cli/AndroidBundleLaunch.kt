package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.util.Properties
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Pure-logic assembly of the inputs a standalone Android (Robolectric) preview render needs when
 * replaying a packed `backend="android"` bundle outside Gradle — the Android counterpart of the
 * desktop spawn in [BundleRenderer]. This is the **Phase 1 foundation**: the deterministic,
 * unit-testable pieces (JVM `--add-opens` args Robolectric needs on JDK 17+, the Robolectric system
 * properties, the synthesized package-level `robolectric.properties`, the SDK-level clamp, and
 * `android.jar` discovery from the local SDK).
 *
 * What is intentionally NOT here yet (Phase 2, validated in the SDK-gated Android CI chain because
 * none of it is runnable without an Android SDK + Robolectric runtime):
 * - packaging `:renderer-android` / `:daemon:android` into the CLI distribution (today only the
 *   desktop sidecars ship — see `cli/build.gradle.kts`), and
 * - recording the consumer's `compileSdk` in the bundle manifest (we default + allow an override
 *   until then).
 *
 * Bundle-side packing of Android-merged resources now exists for the **daemon** path (schema v6,
 * [ee.schimke.composeai.plugin.BundleAndroidResources]): a protolayout-IR bundle carries the merged
 * resource APK + manifest + generated R classes, and [BundleDaemonCommand] rebuilds the Robolectric
 * `test_config.properties` from them so the tile renderer resolves its theme on a detached daemon.
 *
 * The constants below MUST stay in lockstep with the Gradle plugin's
 * [ee.schimke.composeai.plugin.AndroidPreviewClasspath] (`buildJvmArgs`, `buildSystemProperties`)
 * and `GenerateRobolectricPropertiesTask`, which the in-workspace Android render task uses. The CLI
 * links a different module graph, so we re-declare them here — same pattern as [BundleReader]
 * mirroring the on-disk bundle schema. Keep them in sync if the plugin side changes.
 */
class AndroidBundleLaunch(
  sdkLevel: Int = DEFAULT_SDK,
  /**
   * When false (default) the synthesized `robolectric.properties` pins `application=
   * android.app.Application` so the consumer's own `Application.onCreate()` is skipped — preview
   * rendering must not run app bootstrap (Firebase, splash screens, etc.). Set true only when the
   * consumer's Application is preview-safe.
   */
  private val useConsumerApplication: Boolean = false,
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * Absolute path of the shared, machine-local GoogleFont download cache the renderer's
   * `ShadowFontsContractCompat` reads via the `composeai.fonts.cacheDir` system property. Defaults
   * to `$XDG_CACHE_HOME/composeai/fonts` (else `~/.cache/composeai/fonts`) — the SAME directory the
   * Gradle plugin's `composeAiFontsCacheDir` computes, so a `bundle`/serve render reuses the faces
   * the pack-time render already downloaded. Injected for tests.
   */
  private val fontsCacheDir: String = composeAiCacheDir("fonts").absolutePath,
) {

  /** Clamped to Robolectric 4.16.x's supported `android-all` range — see [MIN_SDK] / [MAX_SDK]. */
  val sdkLevel: Int = sdkLevel.coerceIn(MIN_SDK, MAX_SDK)

  /**
   * JVM args the spawned Robolectric process needs on JDK 17+. Mirrors
   * `AndroidPreviewClasspath.buildJvmArgs()` plus `--enable-native-access` (which the desktop spawn
   * also passes). Without the `--add-opens` set Robolectric's reflective access into `java.base`
   * internals fails with `IllegalAccessException` on SDK 36 sandboxes (issue #1328).
   */
  fun jvmArgs(): List<String> =
    listOf(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )

  /**
   * Robolectric render flags — plus the shared GoogleFont download cache dir — shared by the
   * one-shot renderer ([BundleRenderer]), the detached daemon ([BundleDaemonCommand]), and the
   * serve host ([ee.schimke.composeai.cli.serve.ServeBundleDaemon], which forwards this map as its
   * backend `extraSystemProperties`). Mirrors the `robolectric.*` flags and
   * `composeai.fonts.cacheDir` from `AndroidPreviewClasspath.buildSystemProperties(...)`, so a
   * downloadable `Font(GoogleFont(...))` resolves the same on a detached/serve render as it does
   * under Gradle — without it the shadow's cache is disabled and such text silently falls back to
   * the platform default. The daemon uses just these — it routes previews via
   * `composeai.daemon.userClassDirs` / `previewsJsonPath`, not the render-batch props.
   */
  fun robolectricSystemProperties(): Map<String, String> = buildMap {
    put("robolectric.graphicsMode", "NATIVE")
    put("robolectric.looperMode", "PAUSED")
    put("robolectric.conscryptMode", "OFF")
    put("robolectric.pixelCopyRenderMode", "hardware")
    put("roborazzi.test.record", "true")
    put("composeai.fonts.cacheDir", fontsCacheDir)
    // An unresolved downloadable font fails its preview by default (`FontResolutionDiagnostics`).
    // Forward the opt-out when this process carries it, so a detached/serve operator can set
    // `-Dcomposeai.fonts.failOnFallback=false` on the CLI JVM and the child daemon honours it —
    // else a cold-cache render on the live server fails previews with no downgrade path. Unset ⇒
    // absent ⇒ the renderer's own default (fatal) applies.
    System.getProperty("composeai.fonts.failOnFallback")?.let {
      put("composeai.fonts.failOnFallback", it)
    }
  }

  /**
   * [robolectricSystemProperties] plus the one-shot renderer's batch I/O props: the renderer reads
   * `composeai.render.manifest` (the extracted `previews.json`) and `composeai.render.outputDir` to
   * render the whole manifest in a single subprocess.
   */
  fun systemProperties(manifestPath: String, outputDir: String): Map<String, String> =
    robolectricSystemProperties() +
      linkedMapOf(
        "composeai.render.manifest" to manifestPath,
        "composeai.render.outputDir" to outputDir,
      )

  /**
   * The package-level `robolectric.properties` body Robolectric merges for
   * `RobolectricRenderTest`'s package. Mirrors `GenerateRobolectricPropertiesTask`'s output:
   * `sdk` + `graphicsMode` + the GoogleFont shadow registration, and (unless
   * [useConsumerApplication]) the stub `application=`.
   */
  fun robolectricPropertiesBody(): String = buildString {
    appendLine("sdk=$sdkLevel")
    appendLine("graphicsMode=NATIVE")
    if (!useConsumerApplication) appendLine("application=android.app.Application")
    append("shadows=ee.schimke.composeai.renderer.ShadowFontsContractCompat")
  }

  /**
   * Materialise [robolectricPropertiesBody] at the classpath path Robolectric looks it up by —
   * `<root>/ee/schimke/composeai/renderer/robolectric.properties` (the renderer test's package).
   * Returns [root], which the caller prepends to the subprocess classpath so this config wins over
   * any copy baked into the shipped renderer jar. Creates parent dirs as needed.
   */
  fun writeRobolectricConfig(root: File): File {
    val pkgDir = File(root, RENDERER_PKG_PATH).apply { mkdirs() }
    fileSystem.write(File(pkgDir, "robolectric.properties").path.toPath()) {
      writeUtf8(robolectricPropertiesBody() + "\n")
    }
    return root
  }

  companion object {
    /** Floor of Robolectric 4.16.x's `android-all-instrumented` range (API 21, LOLLIPOP). */
    const val MIN_SDK: Int = 21
    /** Ceiling of the bundled Robolectric's supported range (API 36). */
    const val MAX_SDK: Int = 36
    /**
     * SDK level used when the bundle doesn't pin one. Bundles don't yet record the consumer's
     * `compileSdk` (Phase 2), so default to a recent, widely-available level; override with
     * `-Dcomposeai.bundle.androidSdk=<n>`.
     */
    const val DEFAULT_SDK: Int = 35

    private const val RENDERER_PKG_PATH = "ee/schimke/composeai/renderer"

    /** `-Dcomposeai.bundle.androidSdk=<n>` override for [DEFAULT_SDK]. */
    fun sdkLevelFromSystemProperty(
      prop: String? = System.getProperty("composeai.bundle.androidSdk")
    ): Int = prop?.trim()?.toIntOrNull() ?: DEFAULT_SDK

    /**
     * Resolve `android.jar` from the local Android SDK, mirroring
     * `AndroidPreviewClasspath.resolveBootClasspathFallback`: `sdk.dir` in [localPropertiesFile]
     * first, then the `ANDROID_HOME` / `ANDROID_SDK_ROOT` env vars, then the highest-versioned
     * `platforms/android-N/android.jar` under the resolved root. Returns null when no SDK is
     * reachable — the caller turns that into an actionable diagnostic rather than a crash.
     */
    fun resolveAndroidJar(
      localPropertiesFile: File?,
      env: (String) -> String? = { System.getenv(it) },
      fileSystem: FileSystem = SystemFileSystem,
    ): File? {
      val root = sdkRoot(localPropertiesFile, env, fileSystem) ?: return null
      return highestPlatformAndroidJar(root)
    }

    private fun sdkRoot(
      localPropertiesFile: File?,
      env: (String) -> String?,
      fileSystem: FileSystem = SystemFileSystem,
    ): File? {
      localPropertiesFile
        ?.takeIf { it.isFile }
        ?.let { f ->
          val props =
            Properties().apply { fileSystem.read(f.path.toPath()) { load(inputStream()) } }
          props
            .getProperty("sdk.dir")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
              return File(it)
            }
        }
      for (name in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        env(name)
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            return File(it)
          }
      }
      return null
    }

    private fun highestPlatformAndroidJar(sdkRoot: File): File? {
      val platforms = File(sdkRoot, "platforms").takeIf { it.isDirectory } ?: return null
      return platforms
        .listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
        .orEmpty()
        .mapNotNull { dir ->
          val jar = File(dir, "android.jar").takeIf { it.isFile } ?: return@mapNotNull null
          val level = dir.name.removePrefix("android-").toIntOrNull() ?: return@mapNotNull null
          level to jar
        }
        .maxByOrNull { it.first }
        ?.second
    }
  }
}
