package ee.schimke.composeai.cli.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.ServiceLoader

/**
 * Service-loader SPI implemented by an enclosing host (such as `:cli`) so out-of-process helper
 * entrypoints in `:simulator-cli` (like [ApkDwfCompilerMain]) automatically inherit host
 * provisioning hooks without a compile dependency on the host.
 */
interface SimulatorHostEnvironmentProvider {
  fun create(): SimulatorHostEnvironment
}

/**
 * Host callbacks supplied by the enclosing CLI (`compose-preview`) when running
 * [AndroidSimulatorCommand], [ApkDwfCompilerMain], or
 * [ee.schimke.composeai.cli.HostNativesProvision].
 *
 * Isolates `:simulator-cli` from `:cli`'s daemon sidecar, Robolectric-deps, Skiko, and network
 * policy implementations so the simulator host orchestration can be moved to its own repository
 * without dragging the rest of `:cli` with it.
 */
data class SimulatorHostEnvironment(
  /** Ensures the Android daemon sidecar (`lib-daemon-android`) is installed; returns true if ok. */
  val ensureAndroidSidecar: () -> Boolean = { true },
  /**
   * Returns Robolectric offline system properties (e.g. from `COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR`
   * or a passed directory), or an empty map when online.
   */
  val robolectricOfflineProperties: (dependencyDir: String?) -> Map<String, String> = { dir ->
    defaultRobolectricOfflineProperties(dir)
  },
  /**
   * Ensures `android-all-instrumented` for [sdk] is cached and returns the offline properties that
   * point Robolectric at it, or an empty map to let Robolectric fetch on its own.
   */
  val provisionAndroidAll: (robolectricJars: List<File>, sdk: Int) -> Map<String, String> =
    { _, _ ->
      emptyMap()
    },
  /** Prepares host Skiko natives for `lib-rcjvm` DWF extraction, or null if unavailable. */
  val prepareSkikoNative: (rcJvmJars: List<File>) -> File? = { defaultPrepareSkikoNative() },
  /** Whether softer offline mode (`COMPOSE_PREVIEW_OFFLINE=1` or hard no-network) is active. */
  val isOffline: () -> Boolean = ::defaultIsOffline,
  /** Whether hard no-network mode (`COMPOSE_PREVIEW_NO_NETWORK=1`) is active. */
  val isNetworkDisabled: () -> Boolean = ::defaultIsNetworkDisabled,
  /** Creates a Ktor HTTP client for downloading host-native archives and the index. */
  val httpClientFactory: () -> HttpClient = { HttpClient(OkHttp) { followRedirects = true } },
  /** Finds the first positional argument in [args], skipping value-consuming flags. */
  val firstPositional: (List<String>) -> String? = ::firstSimulatorPositional,
  /** Finds every positional argument in [args], skipping value-consuming flags. */
  val positionals: (List<String>) -> List<String> = ::dwfRuntimePositionals,
) {
  companion object {
    const val DEFAULT_REPO: String = "yschimke/compose-ai-tools"
    private const val ROBOLECTRIC_DEPS_DIR_ENV = "COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR"
    private const val SKIKO_DIR_PROPERTY = "composeai.cli.skikoDir"
    private const val NO_NETWORK_ENV = "COMPOSE_PREVIEW_NO_NETWORK"
    private const val NO_NETWORK_PROPERTY = "composeai.network"
    private const val OFFLINE_ENV = "COMPOSE_PREVIEW_OFFLINE"
    private const val OFFLINE_PROPERTY = "composeai.bundle.offline"

    val STANDALONE: SimulatorHostEnvironment = SimulatorHostEnvironment()

    /**
     * Discovers a host-supplied [SimulatorHostEnvironment] via [ServiceLoader], falling back to
     * [STANDALONE] when `:simulator-cli` runs without an enclosing host on the classpath.
     */
    fun discover(
      classLoader: ClassLoader = SimulatorHostEnvironment::class.java.classLoader,
    ): SimulatorHostEnvironment =
      ServiceLoader.load(SimulatorHostEnvironmentProvider::class.java, classLoader)
        .firstOrNull()
        ?.create() ?: STANDALONE

    internal fun defaultPrepareSkikoNative(
      skikoDir: String? = System.getProperty(SKIKO_DIR_PROPERTY),
    ): File? {
      val dir = skikoDir?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
      if (!dir.isDirectory) return null
      return dir
        .listFiles { f ->
          f.isFile && f.name.startsWith("skiko-awt-runtime-") && f.name.endsWith(".jar")
        }
        ?.sortedBy { it.name }
        ?.firstOrNull()
    }

    internal fun defaultIsNetworkDisabled(
      env: (String) -> String? = System::getenv,
      property: (String) -> String? = System::getProperty,
    ): Boolean {
      val fromEnv = env(NO_NETWORK_ENV)?.trim()?.lowercase()
      if (fromEnv == "1" || fromEnv == "true") return true
      val fromProperty = property(NO_NETWORK_PROPERTY)?.trim()?.lowercase()
      return fromProperty == "off" || fromProperty == "false" || fromProperty == "none"
    }

    internal fun defaultIsOffline(
      env: (String) -> String? = System::getenv,
      property: (String) -> String? = System::getProperty,
    ): Boolean =
      defaultIsNetworkDisabled(env, property) ||
        property(OFFLINE_PROPERTY).toBoolean() ||
        env(OFFLINE_ENV) == "1"

    internal fun defaultRobolectricOfflineProperties(
      dependencyDir: String? = System.getenv(ROBOLECTRIC_DEPS_DIR_ENV),
    ): Map<String, String> {
      if (dependencyDir.isNullOrBlank()) return emptyMap()
      val dir = File(dependencyDir).absoluteFile
      require(dir.isDirectory) { "$ROBOLECTRIC_DEPS_DIR_ENV is not a directory: $dir" }
      return mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to dir.path)
    }
  }
}
