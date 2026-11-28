package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.simulator.launch.ApkConversionCache
import ee.schimke.composeai.simulator.launch.ApkConverter
import ee.schimke.composeai.simulator.launch.DataLayerHub
import ee.schimke.composeai.simulator.launch.RobolectricAndroidAll
import ee.schimke.composeai.simulator.launch.SimulatorJvm
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A device of an [AppSimulatorSession]: its form factor and display. */
public enum class AppSimulatorDevice(
  /** The control channel's and `compose-preview android-simulator --pair`'s name for it. */
  public val id: String,
  /** `composeai.simulator.formFactor`. */
  public val formFactor: String,
  /** The display, as Robolectric qualifiers (the CLI's defaults for the form factor). */
  public val qualifiers: String,
) {
  PHONE("phone", "phone", "w360dp-h640dp-hdpi"),
  WATCH("watch", "wear", "w227dp-h227dp-round-watch-xhdpi"),
}

/**
 * What to run: one app on a phone or a watch, or both, paired over the simulated Wearable Data
 * Layer (as `compose-preview android-simulator phone.apk --pair watch.apk`). The Data Layer
 * delivers only between apps of the same package, as on devices.
 */
public data class AppSimulatorLaunch(
  /** The APK for each device to run: one entry, or [AppSimulatorDevice.PHONE] and `WATCH` both. */
  public val apks: Map<AppSimulatorDevice, File>,
  /** `--network`: `wifi`, `cellular`, `ethernet`, `vpn`, `captive` or `offline`; null is wifi. */
  public val network: String? = null,
  /** `--launcher`: a home screen (phone) or the watch face and system UI (watch) under the app. */
  public val launcher: Boolean = false,
  /** Each simulator JVM's `-Xmx`; null is [SimulatorJvm.defaultMaxHeap]. */
  public val maxHeap: String? = null,
  /** The Android API level simulated. */
  public val sdk: Int = AndroidBundleLaunch.DEFAULT_SDK.coerceAtLeast(MIN_SDK),
  /** More system properties for every simulator JVM, after the launcher's own. */
  public val properties: Map<String, String> = emptyMap(),
) {
  init {
    require(apks.isNotEmpty()) { "an AppSimulatorLaunch needs an APK" }
  }

  public companion object {
    /** The lowest API level the simulator runs: its real draw traversals need API 33. */
    public const val MIN_SDK: Int = 33
  }
}

/**
 * Running simulators, one JVM per device, each a JSON-lines control channel (the commands are
 * `compose-preview android-simulator --control stdio`'s, `docs/SIMULATOR.md`). Thread-safe:
 * commands from several threads are answered by id.
 */
public interface AppSimulatorSession : AutoCloseable {
  public val devices: List<AppSimulatorDevice>

  /** True while every device's JVM runs. */
  public val alive: Boolean

  /**
   * Wait until every device has reported `ready` (its app's first activity is up).
   *
   * @return false when [timeoutMillis] passed or a JVM exited first.
   */
  public fun awaitReady(timeoutMillis: Long): Boolean

  /**
   * Send [command] (`{"cmd":…, …}`) to [device] and wait for its answer.
   *
   * @return the response's `result`, or null when it carries none.
   * @throws IllegalStateException on `ok: false` (with the simulator's `error`), a timeout, or the
   *   JVM having exited.
   */
  public fun command(
    device: AppSimulatorDevice,
    command: JsonObject,
    timeoutMillis: Long = 30_000,
  ): JsonElement?

  /** The OS process ids of every device's JVM, for measuring the session. */
  public fun pids(): List<Long>

  /**
   * Freeze every JVM (`SIGSTOP`): no CPU while nobody uses it, memory kept. [resume] undoes it.
   * Never call while a [command] is waiting: its answer cannot arrive until resumed.
   */
  public fun pause()

  public fun resume()

  /** The last lines [device]'s JVM wrote to stderr, for diagnosing a failed start. */
  public fun stderrTail(device: AppSimulatorDevice): List<String>
}

/**
 * Launches the Robolectric simulator on registered APKs in this process's own children — a phone, a
 * watch, or both paired — for a host that drives it (the preview server's simulator sessions)
 * rather than going through `compose-preview android-simulator`. One simulator JVM per device and
 * no launcher JVM: the CLI's own costs about 85 MB a session.
 *
 * What it shares with the CLI, through `:simulator-launch`: the converted-APK cache
 * (`<cache>/composeai/simulator/`, keyed by APK content and converter, so a conversion either makes
 * serves the other), the converter's invocation, the simulator JVM's command line, and the Data
 * Layer hub that pairs two. It reads the same sidecars a server distribution or CLI install carries
 * (`lib-simulator/`, `lib-daemon-android/`, `lib-dex2jar/`, as [locateBundleSidecarJars] finds
 * them) and Robolectric's `android-all` where the render host already looks for it.
 *
 * Not carried over from the CLI: host builds of an app's native libraries (`HostNativesProvision`),
 * live reload, the virtual adb device and scenario runs. Pass `java.library.path` in
 * [AppSimulatorLaunch.properties] for an app whose native code must load.
 */
public object AppSimulatorLauncher {
  /** `COMPOSE_PREVIEW_SIMULATOR_JAVA`: a Java 21+ home or `java` binary for the simulator JVMs. */
  public const val JAVA_ENV: String = "COMPOSE_PREVIEW_SIMULATOR_JAVA"

  public const val MIN_JAVA: Int = 21

  private val JSON = Json { ignoreUnknownKeys = true }
  private const val STDERR_LINES = 60
  private const val CLOSE_GRACE_SECONDS = 5L

  internal data class Environment(
    val java: File,
    val simulatorJars: List<File>,
    val robolectricJars: List<File>,
    val dex2jarJars: List<File>,
    val androidJar: File,
  )

  /** Why a simulator cannot start on this host, or null when it can. */
  public fun unavailableReason(): String? = resolveEnvironment().second

  internal fun resolveEnvironment(
    env: (String) -> String? = System::getenv,
  ): Pair<Environment?, String?> {
    val java =
      javaBinary(env)
        ?: return null to
          "the simulator needs Java $MIN_JAVA+ (set \$$JAVA_ENV to a JDK $MIN_JAVA home, or run on one)"
    val simulatorJars = locateBundleSidecarJars("lib-simulator")
    if (simulatorJars.none { it.name.startsWith(SimulatorJvm.HOST_JAR_PREFIX) }) {
      return null to
        "the simulator needs `lib-simulator/` (looked in ${bundleSidecarSearchDescription("lib-simulator")})"
    }
    val robolectricJars = locateBundleSidecarJars("lib-daemon-android")
    if (robolectricJars.isEmpty()) {
      return null to
        "the simulator needs `lib-daemon-android/` (looked in " +
          "${bundleSidecarSearchDescription("lib-daemon-android")})"
    }
    val dex2jarJars = locateBundleSidecarJars("lib-dex2jar")
    if (dex2jarJars.isEmpty()) {
      return null to
        "the simulator needs `lib-dex2jar/` to convert APKs (looked in " +
          "${bundleSidecarSearchDescription("lib-dex2jar")})"
    }
    val androidJar =
      DwfApkDaemonBridge.locateAndroidJar()
        ?: return null to
          "the simulator needs an Android SDK platform's android.jar (set ANDROID_HOME or ANDROID_SDK_ROOT)"
    return Environment(java, simulatorJars, robolectricJars, dex2jarJars, androidJar) to null
  }

  /** `$JAVA_ENV` (a home or a binary), else this JVM's own, when it is Java [MIN_JAVA]+. */
  internal fun javaBinary(env: (String) -> String? = System::getenv): File? {
    val candidates =
      listOfNotNull(
        env(JAVA_ENV)?.takeIf { it.isNotBlank() }?.let(::File),
        File(System.getProperty("java.home")),
      )
    for (candidate in candidates) {
      val home = if (candidate.isFile) candidate.parentFile?.parentFile else candidate
      val binary = if (candidate.isFile) candidate else File(candidate, "bin/java")
      if (!binary.canExecute() || home == null) continue
      val feature = featureVersion(home) ?: continue
      if (feature >= MIN_JAVA) return binary
    }
    return null
  }

  /** The feature version a JDK's `release` file names (`JAVA_VERSION="21.0.2"`), or null. */
  internal fun featureVersion(javaHome: File): Int? = runCatching {
    File(javaHome, "release")
      .readLines()
      .first { it.startsWith("JAVA_VERSION=") }
      .substringAfter('=')
      .trim('"')
      .split('.', '-', '+')
      .first()
      .toInt()
  }
    .getOrNull()

  /**
   * [apk]'s converted classes, converting it now if no entry for it exists. Start-up does this
   * itself; calling it ahead (when an APK is registered) moves a first conversion, which can take a
   * minute, off the first session's path.
   *
   * @throws IllegalStateException when the simulator is unavailable or the conversion fails.
   */
  public fun prepare(apk: File): File {
    val (environment, reason) = resolveEnvironment()
    checkNotNull(environment) { reason ?: "the simulator is unavailable" }
    return convert(environment, apk).classesJar
  }

  private fun cache(): ApkConversionCache = ApkConversionCache(composeAiCacheDir("simulator"))

  private fun convert(environment: Environment, apk: File): ApkConversionCache.Entry {
    val converterJars =
      ApkConverter.converterJars(environment.simulatorJars, environment.dex2jarJars)
    val provided = ApkConverter.providedJars(environment.robolectricJars)
    // The CLI's stamp exactly, so an APK either converts serves the other.
    val stamp = ApkConversionCache.converterStamp(converterJars + provided, environment.androidJar)
    return cache().ensure(apk, stamp) { out, previous ->
      ApkConverter.convert(
        environment.java,
        converterJars,
        apk,
        out,
        environment.androidJar,
        previous,
        provided = provided,
      )
    }
  }

  /**
   * Start [launch]'s simulators and return once every JVM runs (not once its app is up: see
   * [AppSimulatorSession.awaitReady]). [onEvent] receives each line a simulator prints unasked —
   * `ready`, `activity`, `notification`, … — with the device it came from, on a reader thread.
   *
   * @throws IllegalStateException when the simulator is unavailable or an APK cannot be converted.
   */
  public fun start(
    launch: AppSimulatorLaunch,
    onEvent: (AppSimulatorDevice, JsonObject) -> Unit = { _, _ -> },
  ): AppSimulatorSession {
    val (environment, reason) = resolveEnvironment()
    checkNotNull(environment) { reason ?: "the simulator is unavailable" }
    val cache = cache()
    val held = mutableListOf<Closeable>()
    val processes = mutableListOf<DeviceProcess>()
    var hub: DataLayerHub? = null
    try {
      val conversions = launch.apks.mapValues { (_, apk) -> convert(environment, apk) }
      // Held for the session's life, so the cache never prunes a jar a running simulator reads.
      conversions.values.forEach { held += cache.use(it) }
      val paired = launch.apks.size > 1
      hub = if (paired) DataLayerHub().start() else null
      val robolectric = robolectricProperties(environment, launch.sdk)
      for ((device, apk) in launch.apks.entries.sortedBy { it.key.ordinal }) {
        val command =
          command(
            environment = environment,
            launch = launch,
            device = device,
            apk = apk,
            classes = conversions.getValue(device).classesJar,
            hubPort = hub?.port,
            robolectricProperties = robolectric,
          )
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE).start()
        processes += DeviceProcess(device, process, onEvent)
      }
      return Session(processes.toList(), hub, held.toList())
    } catch (e: Throwable) {
      processes.forEach { it.close() }
      hub?.close()
      held.forEach { runCatching { it.close() } }
      throw e
    }
  }

  /**
   * Robolectric pointed at a provisioned directory, offline, only when it holds [sdk]'s own
   * `android-all` (a directory with another level's would leave the simulator unable to start);
   * otherwise nothing, and Robolectric resolves the jar through Maven as it does by default.
   */
  private fun robolectricProperties(environment: Environment, sdk: Int): Map<String, String> =
    offlineProperties(
      DwfApkDaemonBridge.resolveRobolectricDepsDir(),
      RobolectricAndroidAll.fileName(environment.robolectricJars, sdk),
    )

  /** Offline properties for [dir] when it holds [fileName]; none otherwise. Pure, for tests. */
  internal fun offlineProperties(dir: File?, fileName: String?): Map<String, String> =
    if (dir != null && fileName != null && File(dir, fileName).isFile) {
      mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to dir.absolutePath)
    } else {
      emptyMap()
    }

  /** [device]'s JVM command line. Pure, for tests. */
  internal fun command(
    environment: Environment,
    launch: AppSimulatorLaunch,
    device: AppSimulatorDevice,
    apk: File,
    classes: File,
    hubPort: Int?,
    robolectricProperties: Map<String, String>,
    physicalMemoryBytes: Long? = physicalMemoryBytes(),
  ): List<String> {
    val properties =
      SimulatorJvm.baseProperties(launch.sdk, device.qualifiers, device.formFactor).apply {
        put(SimulatorJvm.PROPERTY_CONTROL, "stdio")
        put(SimulatorJvm.PROPERTY_SHOW_AGENT, "false")
        launch.network?.let { put(SimulatorJvm.PROPERTY_NETWORK, it) }
        if (launch.launcher) put(SimulatorJvm.PROPERTY_LAUNCHER, "true")
        hubPort?.let { putAll(SimulatorJvm.dataLayerProperties(it, device.id)) }
        putAll(launch.properties)
        putAll(robolectricProperties)
      }
    return SimulatorJvm.command(
      java = environment.java,
      classpath = environment.simulatorJars + environment.robolectricJars + environment.androidJar,
      apk = apk,
      classes = listOf(classes),
      robolectricJvmArgs = AndroidBundleLaunch().jvmArgs(),
      properties = properties,
      maxHeap = launch.maxHeap ?: SimulatorJvm.defaultMaxHeap(physicalMemoryBytes),
      headless = true,
    )
  }

  private fun physicalMemoryBytes(): Long? =
    (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        as? com.sun.management.OperatingSystemMXBean)
      ?.totalMemorySize

  /**
   * [command] as the line sent, with [id] as its correlation id: written last, so an `id` the
   * caller left in the command can never stand in for it and misroute the reply. Pure, for tests.
   */
  internal fun commandLine(id: Long, command: JsonObject): String =
    JsonObject(command + ("id" to JsonPrimitive(id))).toString()

  /** One device's JVM: its control channel, replies matched by id, events passed on. */
  private class DeviceProcess(
    val device: AppSimulatorDevice,
    val process: Process,
    private val onEvent: (AppSimulatorDevice, JsonObject) -> Unit,
  ) {
    private val writer =
      BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val stderr = ArrayDeque<String>()
    val ready = CompletableFuture<Unit>()

    init {
      daemon("app-simulator-${device.id}-out") {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach(::onLine)
        }
        val ended = IllegalStateException("the ${device.id} simulator exited (${exit()})")
        pending.values.forEach { it.completeExceptionally(ended) }
        pending.clear()
        ready.completeExceptionally(ended)
      }
      daemon("app-simulator-${device.id}-err") {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach { line ->
            synchronized(stderr) {
              if (stderr.size == STDERR_LINES) stderr.removeFirst()
              stderr.addLast(line.take(2_000))
            }
          }
        }
      }
    }

    private fun exit(): String =
      if (process.isAlive) "its output closed" else "exit ${process.waitFor()}"

    private fun onLine(line: String) {
      val trimmed = line.trim()
      if (!trimmed.startsWith("{")) return
      val obj = runCatching { JSON.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
      val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
      if (id != null && obj.containsKey("ok")) {
        pending.remove(id)?.complete(obj)
        return
      }
      if (!obj.containsKey("event")) return
      if ((obj["event"] as? JsonPrimitive)?.contentOrNull == "ready") ready.complete(Unit)
      runCatching { onEvent(device, obj) }
    }

    fun command(id: Long, command: JsonObject, timeoutMillis: Long): JsonElement? {
      check(process.isAlive) { "the ${device.id} simulator is not running" }
      val future = CompletableFuture<JsonObject>()
      pending[id] = future
      val line = commandLine(id, command)
      try {
        synchronized(writer) {
          writer.write(line)
          writer.newLine()
          writer.flush()
        }
      } catch (e: java.io.IOException) {
        pending.remove(id)
        throw IllegalStateException("the ${device.id} simulator is not accepting commands", e)
      }
      val name = (command["cmd"] as? JsonPrimitive)?.contentOrNull ?: "command"
      val reply =
        try {
          future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
          pending.remove(id)
          throw IllegalStateException("$name on ${device.id} timed out after ${timeoutMillis}ms")
        } catch (e: ExecutionException) {
          throw (e.cause as? IllegalStateException) ?: IllegalStateException("${e.cause}")
        }
      if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
        val error = (reply["error"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
        throw IllegalStateException("$name on ${device.id}: $error")
      }
      return reply["result"]
    }

    fun stderrTail(): List<String> = synchronized(stderr) { stderr.toList() }

    fun pids(): List<Long> = listOf(process.pid()) + process.descendants().map { it.pid() }.toList()

    fun signal(sig: String) {
      if (!process.isAlive) return
      runCatching {
        ProcessBuilder(listOf("kill", "-$sig") + pids().map { it.toString() })
          .redirectErrorStream(true)
          .start()
          .waitFor(5, TimeUnit.SECONDS)
      }
    }

    fun close() {
      signal("CONT")
      runCatching { synchronized(writer) { writer.close() } }
      // Closing stdin ends the simulator; whatever outlives a grace is killed with its children.
      if (
        !runCatching { process.waitFor(CLOSE_GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
      ) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
      }
    }
  }

  private class Session(
    private val processes: List<DeviceProcess>,
    private val hub: DataLayerHub?,
    private val held: List<Closeable>,
  ) : AppSimulatorSession {
    private val ids = AtomicLong(1)
    @Volatile private var paused = false

    override val devices: List<AppSimulatorDevice> = processes.map { it.device }

    override val alive: Boolean
      get() = processes.all { it.process.isAlive }

    private fun of(device: AppSimulatorDevice): DeviceProcess =
      processes.firstOrNull { it.device == device }
        ?: throw IllegalArgumentException(
          "this session runs ${devices.map { it.id }}, not ${device.id}",
        )

    override fun awaitReady(timeoutMillis: Long): Boolean = runCatching {
      CompletableFuture.allOf(*processes.map { it.ready }.toTypedArray())
        .get(timeoutMillis, TimeUnit.MILLISECONDS)
      true
    }
      .getOrDefault(false)

    override fun command(
      device: AppSimulatorDevice,
      command: JsonObject,
      timeoutMillis: Long,
    ): JsonElement? = of(device).command(ids.getAndIncrement(), command, timeoutMillis)

    override fun pids(): List<Long> = processes.flatMap { it.pids() }

    override fun pause() {
      processes.forEach { it.signal("STOP") }
      paused = true
    }

    override fun resume() {
      if (paused) processes.forEach { it.signal("CONT") }
      paused = false
    }

    override fun stderrTail(device: AppSimulatorDevice): List<String> = of(device).stderrTail()

    override fun close() {
      processes.forEach { runCatching { it.close() } }
      hub?.close()
      held.forEach { runCatching { it.close() } }
    }
  }

  private fun daemon(name: String, body: () -> Unit) =
    Thread({ runCatching(body) }, name).apply {
      isDaemon = true
      start()
    }
}
