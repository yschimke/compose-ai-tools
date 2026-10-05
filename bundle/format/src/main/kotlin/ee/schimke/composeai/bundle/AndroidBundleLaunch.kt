package ee.schimke.composeai.bundle

import ee.schimke.composeai.daemon.client.AndroidSdk
import ee.schimke.composeai.daemon.client.RobolectricConfig
import ee.schimke.composeai.daemon.client.RobolectricLaunch
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.util.Properties
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The inputs a standalone Android (Robolectric) render needs to replay a packed `backend="android"`
 * bundle outside Gradle, the counterpart of the desktop spawn in [BundleRenderer]: JVM args, system
 * properties, the synthesized `robolectric.properties`, the SDK clamp, and `android.jar` discovery.
 *
 * The Robolectric config bodies, packages and SDK range belong to the daemon's renderer and come
 * from `ee.schimke.composeai.daemon.client.RobolectricConfig` / `AndroidSdk`, so a rename there
 * can't silently desync this. The Gradle plugin's
 * [ee.schimke.composeai.plugin.AndroidPreviewClasspath] still keeps its own copy, since it is a
 * separate build with no daemon dependency.
 *
 * Not yet done: bundles don't record the consumer's `compileSdk`, and the one-shot lane packs no
 * merged manifest (only the daemon lane uses [AndroidBundleResources]).
 */
public class AndroidBundleLaunch(
  sdkLevel: Int = DEFAULT_SDK,
  /**
   * When false, the synthesized config pins `application=android.app.Application` so the consumer's
   * `Application.onCreate()` never runs during preview rendering.
   */
  private val useConsumerApplication: Boolean = false,
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * The GoogleFont download cache `ShadowFontsContractCompat` reads (`composeai.fonts.cacheDir`).
   * Defaults to the same directory the Gradle plugin uses, so pack-time downloads are reused.
   */
  private val fontsCacheDir: String = composeAiCacheDir("fonts").absolutePath,
) {

  /** Clamped to Robolectric 4.16.x's supported `android-all` range — see [MIN_SDK] / [MAX_SDK]. */
  public val sdkLevel: Int = sdkLevel.coerceIn(MIN_SDK, MAX_SDK)

  /** The daemon's JVM args for Robolectric on JDK 17+ (the `--add-opens` set, #1328). */
  public fun jvmArgs(): List<String> = RobolectricLaunch.jvmArgs()

  /**
   * Robolectric render flags, shared by [BundleRenderer], [BundleDaemonCommand] and
   * [ee.schimke.composeai.cli.serve.ServeBundleDaemon]. Taken from the daemon so properties like
   * `composeai.fonts.offline` reach every Android lane (#5371). [fontsCacheDir] overrides the
   * daemon's own resolution.
   */
  public fun robolectricSystemProperties(): Map<String, String> =
    RobolectricLaunch.systemProperties() + ("composeai.fonts.cacheDir" to fontsCacheDir)

  /**
   * [robolectricSystemProperties] plus the one-shot renderer's batch I/O props: the renderer reads
   * `composeai.render.manifest` (the extracted `previews.json`) and `composeai.render.outputDir` to
   * render the whole manifest in a single subprocess.
   */
  public fun systemProperties(manifestPath: String, outputDir: String): Map<String, String> =
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
  public fun robolectricPropertiesBody(): String = robolectricConfig().composableLaneBody()

  /**
   * The app-tour lane's `robolectric.properties` body: [robolectricPropertiesBody] without the stub
   * `application=` line, since an Activity is the app (Hilt / Koin activities fail on the stub).
   *
   * On this one-shot path no merged manifest is packed yet, so Robolectric falls back to its
   * default Application. The line stays absent rather than pinned so packing the manifest later
   * fixes this lane with no further change.
   */
  public fun appTourRobolectricPropertiesBody(): String = robolectricConfig().appTourLaneBody()

  private fun robolectricConfig(): RobolectricConfig =
    RobolectricConfig(sdkLevel = sdkLevel, useConsumerApplication = useConsumerApplication)

  /**
   * Write both lanes' `robolectric.properties` under [root] in their packages, and return [root]
   * for the caller to prepend to the subprocess classpath so it wins over any copy in the renderer
   * jar.
   */
  public fun writeRobolectricConfig(root: File): File {
    val pkgDir = File(root, RENDERER_PKG_PATH).apply { mkdirs() }
    fileSystem.write(File(pkgDir, "robolectric.properties").path.toPath()) {
      writeUtf8(robolectricPropertiesBody() + "\n")
    }
    val appTourDir = File(root, APP_TOUR_PKG_PATH).apply { mkdirs() }
    fileSystem.write(File(appTourDir, "robolectric.properties").path.toPath()) {
      writeUtf8(appTourRobolectricPropertiesBody() + "\n")
    }
    return root
  }

  public companion object {
    /** Floor of the bundled Robolectric's `android-all-instrumented` range (API 21, LOLLIPOP). */
    public const val MIN_SDK: Int = AndroidSdk.MIN_SDK

    /** Ceiling of the bundled Robolectric's supported range (API 36). */
    public const val MAX_SDK: Int = AndroidSdk.MAX_SDK
    /**
     * SDK level used when the bundle doesn't pin one (bundles don't record `compileSdk` yet);
     * override with `-Dcomposeai.bundle.androidSdk=<n>`.
     */
    public const val DEFAULT_SDK: Int = AndroidSdk.DEFAULT_SDK

    private val RENDERER_PKG_PATH = RobolectricConfig.RENDERER_PACKAGE.replace('.', '/')

    /**
     * A sibling of [RENDERER_PKG_PATH], never a child, so it doesn't inherit the stub Application.
     */
    private val APP_TOUR_PKG_PATH = RobolectricConfig.APP_TOUR_PACKAGE.replace('.', '/')

    /** `-Dcomposeai.bundle.androidSdk=<n>` override for [DEFAULT_SDK]. */
    public fun sdkLevelFromSystemProperty(
      prop: String? = System.getProperty("composeai.bundle.androidSdk")
    ): Int = prop?.trim()?.toIntOrNull() ?: DEFAULT_SDK

    /**
     * The highest-versioned `platforms/android-N/android.jar` under the SDK named by `sdk.dir` in
     * [localPropertiesFile], else `ANDROID_HOME` / `ANDROID_SDK_ROOT`; null when unreachable.
     * Mirrors `AndroidPreviewClasspath.resolveBootClasspathFallback`.
     */
    public fun resolveAndroidJar(
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
