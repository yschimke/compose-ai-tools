package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.awt.GraphicsEnvironment
import java.io.Closeable
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview launch <app.apk>`: runs an APK in Robolectric's simulator — a desktop window
 * showing the app, driven by mouse and keyboard — and, with `--control stdio`, a line-oriented JSON
 * control channel on stdin/stdout for agents and the MCP server (`docs/SIMULATOR.md`).
 *
 * The pieces, all cached so a second launch of the same APK starts in seconds:
 * - the APK's dex, converted to JVM classes by `:simulator-host`'s `ApkClassConverter` in its own
 *   JVM ([ApkConversionCache]);
 * - Robolectric and its native graphics runtime: the Android daemon sidecar
 *   ([DaemonSidecarProvision]), the same one Android previews render with;
 * - `lib-simulator/` (Robolectric's `simulator` and `:simulator-host`) and `lib-dex2jar/`, staged
 *   in the CLI distribution;
 * - `android.jar` from a local SDK, which Robolectric's configuration classes link against.
 *
 * The APK itself is Robolectric's resource APK: its compiled resources and binary manifest are read
 * as-is.
 */
internal class LaunchCommand(private val args: List<String>) {

  fun run() {
    if ("--help" in args || "-h" in args) {
      println(USAGE)
      return
    }
    val apk =
      CliFlags.firstPositional(args)?.let { File(it).absoluteFile }
        ?: fail("expected an APK: compose-preview launch <app.apk>")
    if (!apk.isFile) fail("no such APK: $apk")
    val verbose = "--verbose" in args || "-v" in args
    val control = args.flagValue("--control")
    if (control != null && control != "stdio") fail("--control supports only `stdio`")

    val java =
      when (val result = SimulatorJava.resolve(args.flagValue("--java"))) {
        is SimulatorJava.Result.Found -> result.java
        is SimulatorJava.Result.NotFound ->
          fail(
            "the simulator needs Java ${SimulatorJava.MIN_FEATURE}+ (Robolectric's simulator " +
              "uses Java 21 APIs). Looked at: ${result.tried.joinToString("; ")}. Pass " +
              "--java <jdk>, or set ${SimulatorJava.ENV}."
          )
      }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = findLocalProperties())
        ?: fail(
          "needs an Android SDK platform's android.jar — set ANDROID_HOME / ANDROID_SDK_ROOT, " +
            "or run from a project whose local.properties has sdk.dir."
        )
    val simulatorJars = sidecar("lib-simulator")
    val dex2jarJars = sidecar("lib-dex2jar")
    val hostJar =
      simulatorJars.firstOrNull { it.name.startsWith("simulator-host") }
        ?: fail("lib-simulator/ has no simulator-host jar")

    // Before provisioning: under the network switch Robolectric needs pre-provisioned android-all.
    val offline = robolectricOfflineProperties()
    if (!DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)) {
      fail("could not provision the Android daemon sidecar, which carries Robolectric")
    }
    val robolectricJars = locateBundleSidecarJars("lib-daemon-android")
    if (robolectricJars.isEmpty()) {
      fail(
        "the Android daemon sidecar is missing; looked in " +
          bundleSidecarSearchDescription("lib-daemon-android")
      )
    }
    robolectricMismatch(simulatorJars, robolectricJars)?.let {
      System.err.println("compose-preview launch: warning: $it")
    }

    val converterJars = listOf(hostJar) + dex2jarJars
    val cache = ApkConversionCache(composeAiCacheDir("simulator"))
    val stamp = ApkConversionCache.converterStamp(converterJars, androidJar)
    // The in-use locks on the cache entries the simulators run from. Kept reachable, and closed
    // only once every simulator has exited ([finish]): a lock whose channel were collected would be
    // released, and another launch could prune the jar from under a running simulator.
    val inUse = mutableListOf<Closeable>()
    fun finish(exit: Int): Nothing {
      inUse.forEach { runCatching { it.close() } }
      exitProcess(exit)
    }
    fun classesOf(apk: File): File {
      val entry =
        cache.ensure(apk, stamp) { out, previous ->
          System.err.println(
            "compose-preview launch: converting ${apk.name} (once per APK" +
              (if (previous != null) "; reusing the unchanged classes of its previous build)"
              else ")")
          )
          convert(java, converterJars, apk, out, androidJar, previous)
        }
      inUse += cache.use(entry)
      if (verbose || !entry.cached) {
        System.err.println(
          "compose-preview launch: classes ${if (entry.cached) "cached" else "converted"}: " +
            "${entry.classesJar} ${entry.summary}"
        )
      }
      return entry.classesJar
    }
    val pair =
      args
        .flagValue("--pair")
        ?.let { File(it).absoluteFile }
        ?.also { if (!it.isFile) fail("no such APK to pair: $it") }
    val classes = classesOf(apk)
    val pairClasses = pair?.let { classesOf(it) }
    if ("--prepare" in args) {
      println(classes.absolutePath)
      pairClasses?.let { println(it.absolutePath) }
      return
    }

    val device = args.flagValue("--device")
    if (device != null) {
      if (device !in DEVICES) fail("unknown --device $device (${DEVICES.joinToString(", ")})")
      if (args.flagValue("--qualifiers") != null) {
        fail(
          "--device sets the display; drop --qualifiers, or send the qualifiers command after launch"
        )
      }
    }
    val network = args.flagValue("--network")
    if (network != null) {
      val bad = network.split(',').map { it.trim() }.filter { it !in NETWORK_MODES }
      if (bad.isNotEmpty()) {
        fail("unknown --network ${bad.joinToString(",")} (${NETWORK_MODES.joinToString(", ")})")
      }
    }
    val detected = ApkFormFactor.inspect(apk)
    val formFactor =
      args.flagValue("--form-factor")?.let {
        ApkFormFactor.byId(it)
          ?: fail("--form-factor takes ${ApkFormFactor.entries.joinToString(" | ") { f -> f.id }}")
      } ?: detected.formFactor
    // A glasses app pairs a phone launcher with its projected activity: on the glasses' display
    // the projected one runs. Without one (a launcher that is itself projected, or a forced
    // `--form-factor glimmer`), the launcher does.
    val launchActivity = detected.projectedActivity.takeIf { formFactor == ApkFormFactor.GLIMMER }
    if (formFactor != ApkFormFactor.PHONE) {
      System.err.println(
        "compose-preview launch: ${formFactor.id} app; display ${formFactor.qualifiers}" +
          (if (args.flagValue("--qualifiers") != null || args.flagValue("--device") != null)
            " (overridden by --qualifiers / --device)"
          else "")
      )
    }

    val headless = "--headless" in args || GraphicsEnvironment.isHeadless()
    if (headless && control == null) {
      System.err.println(
        "compose-preview launch: no display, so no window; pass --control stdio to drive the app " +
          "headless."
      )
    }
    val options =
      Options(
        sdk =
          (args.flagValue("--sdk")?.toIntOrNull() ?: AndroidBundleLaunch.DEFAULT_SDK).coerceIn(
            AndroidBundleLaunch.MIN_SDK,
            AndroidBundleLaunch.MAX_SDK,
          ),
        qualifiers = args.flagValue("--qualifiers") ?: formFactor.qualifiers,
        formFactor = formFactor.id,
        launchActivity = launchActivity,
        device = device,
        network = network,
        fontScale = args.flagValue("--font-scale"),
        fingerprint = args.flagValue("--fingerprint"),
        maxHeap =
          args.flagValue("--max-heap")?.also {
            if (!Regex("[0-9]+[kKmMgG]?").matches(it)) fail("--max-heap takes a size, e.g. 2g")
          } ?: defaultMaxHeap(physicalMemoryBytes()),
        headless = headless,
        control = control != null,
        installProviders = "--no-providers" !in args,
        showAgent = "--hide-agent" !in args,
        displays = args.flagValuesAll("--display"),
        launcher =
          "--launcher" in args ||
            args.flagValuesAll("--widget").isNotEmpty() ||
            args.flagValuesAll("--tile").isNotEmpty(),
        widgets = args.flagValuesAll("--widget"),
        tiles = args.flagValuesAll("--tile"),
      )
    val extraJars = args.flagValuesAll("--jar").map { File(it) }
    val robolectricProperties = offline.ifEmpty {
      provisionAndroidAll(robolectricJars, options.sdk)
    }
    fun commandFor(apk: File, classes: File, options: Options) =
      simulatorCommand(
        java = java.binary,
        classpath = simulatorJars + robolectricJars + androidJar,
        apk = apk,
        classes = listOf(classes) + extraJars,
        options = options,
        robolectricProperties = robolectricProperties,
        robolectricJvmArgs = AndroidBundleLaunch().jvmArgs(),
      )
    val dataLayerMode = args.flagValue("--data-layer")
    if (dataLayerMode != null) {
      if (dataLayerMode != "agent") fail("--data-layer supports only `agent`")
      if (pair != null) fail("--data-layer agent plays the paired device itself; drop --pair")
      if (control == null) fail("--data-layer agent needs --control stdio: the agent is the peer")
      val device = if (formFactor == ApkFormFactor.WEAR) WATCH else PHONE
      val command =
        commandFor(
          apk,
          classes,
          options.copy(dataLayer = DataLayerNode(hubPort = 0, device = device, agent = true)),
        )
      if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
      finish(ProcessBuilder(command).inheritIO().runTiedToLauncher())
    }
    if (pair == null || pairClasses == null) {
      val command = commandFor(apk, classes, options)
      if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
      finish(ProcessBuilder(command).inheritIO().runTiedToLauncher())
    }

    val hub =
      DataLayerHub(
          log = { if (verbose) System.err.println("compose-preview launch: datalayer hub: $it") }
        )
        .start()
    // --launcher gives each its own system UI; --widget is the phone's, --tile the watch's.
    val phone =
      commandFor(
        apk,
        classes,
        options.copy(dataLayer = DataLayerNode(hub.port, PHONE), tiles = emptyList()),
      )
    val watch =
      commandFor(
        pair,
        pairClasses,
        options.copy(
          qualifiers = args.flagValue("--pair-qualifiers") ?: ApkFormFactor.WEAR.qualifiers,
          // Drawn round, with the rotary input; --device presets are phone-sized, so the watch
          // keeps its own display, and the phone's projected activity is not the watch's.
          formFactor = ApkFormFactor.WEAR.id,
          launchActivity = null,
          device = null,
          // --display connects to the phone; the watch has its one round display.
          displays = emptyList(),
          widgets = emptyList(),
          dataLayer = DataLayerNode(hub.port, WATCH),
        ),
      )
    if (verbose) {
      System.err.println("compose-preview launch: phone: ${phone.joinToString(" ")}")
      System.err.println("compose-preview launch: watch: ${watch.joinToString(" ")}")
    }
    val exit = PairedLaunch(listOf(PHONE to phone, WATCH to watch), control != null).run()
    hub.close()
    finish(exit)
  }

  /**
   * A simulator's node of the simulated Wearable Data Layer: paired through the hub on [hubPort],
   * or with the control channel as every peer ([agent]).
   */
  data class DataLayerNode(val hubPort: Int, val device: String, val agent: Boolean = false) {
    val nodeId: String
      get() = "$device-sim"

    val displayName: String
      get() = "Simulated ${device.replaceFirstChar { it.uppercase() }}"
  }

  /** Simulator settings that become system properties of the launched JVM. */
  data class Options(
    val sdk: Int,
    val qualifiers: String,
    /**
     * [ApkFormFactor.id]; `info` reports it. A display with the `round` qualifier is drawn round.
     */
    val formFactor: String = ApkFormFactor.PHONE.id,
    /** The activity to start instead of the launcher: a Glimmer app's projected activity. */
    val launchActivity: String? = null,
    val fontScale: String?,
    val headless: Boolean,
    val control: Boolean,
    val installProviders: Boolean,
    /** Draw the control channel's taps, swipes and commands over the window. */
    val showAgent: Boolean = true,
    /** Set for each simulator of `--pair`: the hub both connect to. */
    val dataLayer: DataLayerNode? = null,
    /** A device preset ([DEVICES]) sizing the display in place of [qualifiers]. */
    val device: String? = null,
    /** [NETWORK_MODES], comma-separated (`cellular,airplane`); Wi-Fi when null. */
    val network: String? = null,
    /** `Build.FINGERPRINT`, in place of Robolectric's `robolectric`. */
    val fingerprint: String? = null,
    /** The simulator JVM's `-Xmx` (each one, with `--pair`); the JVM's default when null. */
    val maxHeap: String? = null,
    /**
     * Secondary displays connected at launch (`--display`, repeatable), each a size in dp with
     * options: `1280x720@xhdpi,name=TV,type=external`.
     */
    val displays: List<String> = emptyList(),
    /**
     * A system UI under the app (`--launcher`): on a Wear app the watch face, launcher,
     * notification stream and tiles carousel; on any other a home screen with HOME, icons,
     * shortcuts and app widgets.
     */
    val launcher: Boolean = false,
    /** Widget providers placed on the home screen at launch (`--widget`, repeatable). */
    val widgets: List<String> = emptyList(),
    /**
     * Tiles and Wear Widgets (service class names) in the watch's carousel at launch (`--tile`).
     */
    val tiles: List<String> = emptyList(),
  )

  private fun convert(
    java: SimulatorJava.Java,
    converterJars: List<File>,
    apk: File,
    out: File,
    androidJar: File,
    previous: File?,
  ): String {
    for ((attempt, heap) in CONVERTER_HEAPS.withIndex()) {
      val process =
        ProcessBuilder(
            converterCommand(java.binary, converterJars, apk, out, androidJar, heap, previous)
          )
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      val summary = process.inputStream.bufferedReader().readText()
      val exit = process.waitFor()
      if (exit == 0) return summary.lineSequence().lastOrNull { it.startsWith("{") } ?: "{}"
      val next = CONVERTER_HEAPS.getOrNull(attempt + 1)
      check(exit == OUT_OF_MEMORY_EXIT && next != null) { "the APK converter exited $exit" }
      // The converter's temporary jars, which an exit on OutOfMemoryError leaves behind.
      out.absoluteFile.parentFile
        .listFiles { f -> f.name.startsWith("dex2jar-") && f.name.endsWith(".jar") }
        .orEmpty()
        .forEach { it.delete() }
      System.err.println(
        "compose-preview launch: the converter ran out of its $heap heap; retrying with $next"
      )
    }
    error("unreachable")
  }

  /**
   * Robolectric's system properties for an online launch: SDK [sdk]'s `android-all` provisioned
   * into [RobolectricDepsProvision.defaultDir] (from Robolectric's own Maven-local copy when it has
   * one) and Robolectric pointed there offline.
   *
   * Left to itself, Robolectric's `MavenDependencyResolver` fetches under one lock for every
   * artifact on the host, `~/.robolectric-download-lock`: held for a whole download, so a launch
   * whose jar is already there waits behind another's 200 MB fetch, with no network timeout; and
   * deleted on release, so with three or more JVMs (`--pair`, several agents) two end up holding it
   * at once. Provisioning here takes a lock per artifact once, before any simulator JVM starts.
   * When it cannot, the launch goes on online as before, and says why.
   */
  private fun provisionAndroidAll(robolectricJars: List<File>, sdk: Int): Map<String, String> =
    try {
      val artifact = RobolectricDepsProvision.artifactFor(robolectricJars, sdk).artifact
      val directory = RobolectricDepsProvision.defaultDir()
      RobolectricDepsProvision.ensure(
        artifact,
        directory,
        seed = RobolectricDepsProvision.mavenLocalCopy(artifact),
      )
      robolectricOfflineProperties(directory.path)
    } catch (e: Exception) {
      System.err.println(
        "compose-preview launch: could not provision android-all for SDK $sdk ($e); " +
          "Robolectric will fetch it"
      )
      emptyMap()
    }

  private fun sidecar(name: String): List<File> =
    locateBundleSidecarJars(name).ifEmpty {
      fail(
        "this CLI install has no `$name/`, which `launch` runs from. Looked in " +
          bundleSidecarSearchDescription(name)
      )
    }

  private fun findLocalProperties(): File? {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    repeat(8) {
      val d = dir ?: return null
      File(d, "local.properties")
        .takeIf { it.isFile }
        ?.let {
          return it
        }
      dir = d.parentFile
    }
    return null
  }

  private fun fail(message: String): Nothing {
    System.err.println("compose-preview launch: $message")
    exitProcess(1)
  }

  companion object {
    const val LAUNCH_MAIN = "ee.schimke.composeai.simulator.LaunchMain"
    const val CONVERTER_MAIN = "ee.schimke.composeai.simulator.ApkClassConverter"

    /**
     * Collector settings for every simulator JVM.
     *
     * Android's graphics objects — bitmaps, render nodes, canvases — are small on the Java heap and
     * own native memory that is freed only when the collector finds them unreachable. An app idling
     * on an animated screen allocates them every frame but too little heap to trigger a collection,
     * so native memory grows without bound: Confetti went from 850 MB to 2.3 GB in two idle minutes
     * with 116 MB of live heap, and to 5.4 GB in longer runs whatever its `-Xmx`. A periodic
     * collection, only when none has run for that long, keeps it flat (990 MB).
     */
    val SIMULATOR_JVM_ARGS = listOf("-XX:G1PeriodicGCInterval=5000")

    /**
     * The simulator's heap without `--max-heap`: an eighth of the machine, between 768 MB and 2 GB.
     * The JVM's own default, a quarter of the machine, lets G1 grow far past an app's live set
     * (about 120 MB for Confetti and Now in Android) before it collects, and `--pair` runs two.
     */
    fun defaultMaxHeap(physicalMemoryBytes: Long?): String {
      val mb = (physicalMemoryBytes ?: 0L) / (1L shl 20) / 8
      return "${mb.coerceIn(768L, 2048L)}m"
    }

    private fun physicalMemoryBytes(): Long? =
      (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
          as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize

    /**
     * The converter's heap, then the one it retries with if that runs out. It translates one class
     * at a time, so its live set is the dex files and a header per class: about 260 MB for Home
     * Assistant's 61,000 classes. Without a cap the JVM's default heap, a quarter of the machine,
     * lets G1 grow to twice that before it collects.
     */
    val CONVERTER_HEAPS = listOf("1g", "3g")

    /** `-XX:+ExitOnOutOfMemoryError`'s exit status. */
    const val OUT_OF_MEMORY_EXIT = 3

    /** The converter JVM's command line. Pure, for tests. */
    fun converterCommand(
      java: File,
      converterJars: List<File>,
      apk: File,
      out: File,
      androidJar: File,
      heap: String,
      previous: File? = null,
    ): List<String> =
      listOfNotNull(
        java.path,
        "-Xmx$heap",
        // A single-threaded batch job: the serial collector's footprint is the smallest, and it
        // spends no threads on concurrent marking.
        "-XX:+UseSerialGC",
        "-XX:+ExitOnOutOfMemoryError",
        "-cp",
        converterJars.joinToString(File.pathSeparator) { it.path },
        CONVERTER_MAIN,
        apk.path,
        out.path,
        androidJar.path,
        previous?.path,
      )

    /**
     * A phone-shaped display that fits on a laptop screen: Robolectric's window draws one device
     * pixel per screen pixel, so a 420 dpi phone would be 2400 pixels tall.
     */
    val DEFAULT_QUALIFIERS = ApkFormFactor.PHONE.qualifiers

    /** The two simulators of `--pair`, as the control channel's `device` names them. */
    const val PHONE = "phone"
    const val WATCH = "watch"

    /**
     * The simulator's device presets (`DisplayGeometry.PRESETS` in `:simulator-host`), which the
     * control channel's `device` command switches between at run time.
     */
    val DEVICES = listOf("phone", "foldable", "foldable-folded", "tablet", "desktop")

    /**
     * What `--network` takes (`NetworkModel.Mode` in `:simulator-host`, plus `airplane`), which the
     * control channel's `network` command switches between at run time.
     */
    val NETWORK_MODES =
      listOf("wifi", "cellular", "ethernet", "vpn", "captive", "offline", "airplane")

    /** The launched JVM's command line. Pure, for tests. */
    fun simulatorCommand(
      java: File,
      classpath: List<File>,
      apk: File,
      classes: List<File>,
      options: Options,
      robolectricProperties: Map<String, String>,
      robolectricJvmArgs: List<String>,
    ): List<String> = buildList {
      add(java.path)
      addAll(robolectricJvmArgs)
      options.maxHeap?.let { add("-Xmx$it") }
      addAll(SIMULATOR_JVM_ARGS)
      add("--enable-native-access=ALL-UNNAMED")
      // NetworkGate wraps java.net's socket and resolver, so the simulated network decides what
      // the app's real traffic reaches.
      add("--add-opens=java.base/java.net=ALL-UNNAMED")
      if (options.headless) add("-Djava.awt.headless=true")
      val properties =
        linkedMapOf(
          "robolectric.logging.enabled" to "true",
          "robolectric.logging" to "stdout",
          "robolectric.createActivityContexts" to "true",
          "robolectric.useEmbeddedViewRoot" to "true",
          "robolectric.deviceconfig.sdk" to options.sdk.toString(),
          "composeai.simulator.qualifiers" to options.qualifiers,
        )
      properties["composeai.simulator.formFactor"] = options.formFactor
      options.launchActivity?.let { properties["composeai.simulator.launchActivity"] = it }
      options.device?.let { properties["composeai.simulator.device"] = it }
      options.network?.let { properties["composeai.simulator.network"] = it }
      options.fontScale?.let { properties["composeai.simulator.fontScale"] = it }
      options.fingerprint?.let { properties["composeai.simulator.fingerprint"] = it }
      if (options.control) properties["composeai.simulator.control"] = "stdio"
      if (!options.installProviders) properties["composeai.simulator.skipProviders"] = "true"
      if (!options.showAgent) properties["composeai.simulator.showAgent"] = "false"
      if (options.launcher) properties["composeai.simulator.launcher"] = "true"
      if (options.tiles.isNotEmpty()) {
        properties["composeai.simulator.tiles"] = options.tiles.joinToString(",")
      }
      options.dataLayer?.let {
        if (it.agent) properties["composeai.simulator.datalayer.agent"] = "true"
        else properties["composeai.simulator.datalayer.hub"] = it.hubPort.toString()
        properties["composeai.simulator.datalayer.node"] = it.nodeId
        properties["composeai.simulator.datalayer.name"] = it.displayName
      }
      if (options.displays.isNotEmpty()) {
        properties["composeai.simulator.displays"] = options.displays.joinToString(";")
      }
      if (options.launcher) properties["composeai.simulator.launcher"] = "true"
      if (options.widgets.isNotEmpty()) {
        properties["composeai.simulator.widgets"] = options.widgets.joinToString(";")
      }
      properties.putAll(robolectricProperties)
      for ((key, value) in properties) add("-D$key=$value")
      add("-cp")
      add(classpath.joinToString(File.pathSeparator) { it.path })
      add(LAUNCH_MAIN)
      add(apk.path)
      classes.forEach { add(it.path) }
    }

    /**
     * Robolectric's `simulator` jar and the sidecar's Robolectric must be one release: the
     * simulator reaches into Robolectric internals. A mismatch is reported, not fatal, since a
     * patch-level skew usually works.
     */
    fun robolectricMismatch(simulatorJars: List<File>, robolectricJars: List<File>): String? {
      val simulator = versionOf(simulatorJars, "simulator") ?: return null
      val robolectric = versionOf(robolectricJars, "robolectric") ?: return null
      if (simulator == robolectric) return null
      return "Robolectric simulator $simulator runs on the sidecar's Robolectric $robolectric"
    }

    private fun versionOf(jars: List<File>, artifact: String): String? {
      // Sidecar jars carry an ordering prefix: `0109-robolectric-4.17.jar`.
      val pattern = Regex("""^(?:\d+-)?${Regex.escape(artifact)}-(\d[^-]*(?:-[a-z]+-\d+)?)\.jar$""")
      return jars.firstNotNullOfOrNull { pattern.find(it.name)?.groupValues?.get(1) }
    }

    val USAGE =
      """
      compose-preview launch <app.apk> [options]

      Run an APK in Robolectric's simulator: a window showing the app, driven by mouse and
      keyboard (right-click is Back). The APK's dex is converted to JVM classes once and cached
      by content; Robolectric comes from the Android daemon sidecar.

      --control stdio adds a JSON-lines control channel on stdin/stdout: tap, swipe, type,
      read the UI hierarchy, take screenshots, start activities and services, send broadcasts,
      query providers. See docs/SIMULATOR.md. This is what agents and the MCP server use. The
      window stays up, so you watch the agent drive the app: each tap and swipe it sends is
      drawn over the window (never into its screenshots) and the command shown along the
      bottom. --headless drops the window; with no display there is none anyway.

      Needs Java 21+ (--java, or COMPOSE_PREVIEW_SIMULATOR_JAVA) and an Android SDK platform
      (ANDROID_HOME or local.properties sdk.dir).

      Options:
        --control stdio       JSON-lines control channel on stdin/stdout.
        --headless            No window (the default when there is no display).
        --hide-agent          Do not draw the control channel's actions over the window.
        --qualifiers <q>      Resource qualifiers for the display, Robolectric syntax
                              (default by form factor; e.g. w411dp-h891dp-xxhdpi-night).
        --form-factor <f>     phone | wear | glimmer | xr; detected from the APK when absent.
                              phone ${ApkFormFactor.PHONE.qualifiers}, wear ${ApkFormFactor.WEAR.qualifiers}
                              (drawn round), glimmer ${ApkFormFactor.GLIMMER.qualifiers},
                              xr ${ApkFormFactor.XR.qualifiers} (a home-space panel).
        --device <preset>     Start as phone, foldable (open, with its fold), foldable-folded,
                              tablet or desktop, at hdpi; in place of --qualifiers.
        --network <mode>      Start on wifi (default), cellular (metered), ethernet, vpn,
                              captive (Wi-Fi without internet) or offline; add ,airplane
                              (or just airplane) for airplane mode. Offline, the app's
                              real connections fail as on a device.
        --display <spec>      Connect a secondary display at launch (repeatable): a size in
                              dp with an optional density, then name=, type= (external,
                              virtual, rear) — e.g. 1280x720@mdpi,name=TV. Each gets its
                              own window; Presentation and launch-display activities use it.
        --launcher            A system UI under the app. On a phone, a home screen: HOME and
                              Back from the app's root go there, its icons reopen the app, a
                              long press shows its shortcuts, and the app's widgets are placed,
                              sized, updated and tapped there as a phone's launcher does. On a
                              watch: watch face, launcher, notification stream, tiles carousel
                              and quick settings, reached by back, the side button and swipes.
        --widget <provider>   Start with the app's widget <provider> (a receiver class or its
                              short name) on the home screen (repeatable; implies --launcher).
        --tile <service>      Wear: a tile or Wear Widget in the carousel at launch
                              (repeatable; implies --launcher). With --pair, the watch's.
        --font-scale <f>      Font scale (default 1.0).
        --max-heap <size>     Each simulator JVM's maximum heap, e.g. 2g (default: an eighth
                              of the machine's memory, 768m to 2g, for each of a pair).
        --fingerprint <f>     Build.FINGERPRINT (default Robolectric's "robolectric", which some
                              apps check to skip real work under tests; MeshCore's watch does).
        --sdk <n>             Android API level to simulate (default ${AndroidBundleLaunch.DEFAULT_SDK}).
        --jar <path>          Extra classes jar ahead of Robolectric (repeatable).
        --no-providers        Do not create the manifest's content providers at startup.
        --pair <watch.apk>    Also run a Wear OS APK in a second simulator, paired with this
                              one over a simulated Wearable Data Layer: messages, requests,
                              data items and capabilities cross between the two apps. With
                              --control stdio, a command's "device":"watch" sends it to the
                              watch, and every output line carries its "device"; every log
                              line is prefixed [phone] or [watch].
        --data-layer agent    Simulate the Wearable Data Layer with no second simulator: the
                              control channel plays the peer devices. What the app sends is a
                              "datalayer" event of kind "send"; {"cmd":"dataLayer","action":...}
                              connects, disconnects, puts, deletes, messages and replies.
        --pair-qualifiers <q> The paired watch's display (default ${ApkFormFactor.WEAR.qualifiers}).
        --java <path>         The JDK (home or java binary) to run the simulator on.
        --prepare             Convert and cache the APK, print the classes jar, and exit.
        --verbose, -v         Print the cache entry and the launched command.
        --help, -h            Show this help.
      """
        .trimIndent()
  }
}
