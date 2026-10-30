package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.awt.GraphicsEnvironment
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
    fun classesOf(apk: File): File {
      val entry =
        cache.ensure(apk, stamp) { out ->
          System.err.println("compose-preview launch: converting ${apk.name} (once per APK)")
          convert(java, converterJars, apk, out, androidJar)
        }
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
        fontScale = args.flagValue("--font-scale"),
        fingerprint = args.flagValue("--fingerprint"),
        maxHeap =
          args.flagValue("--max-heap")?.also {
            if (!Regex("[0-9]+[kKmMgG]?").matches(it)) fail("--max-heap takes a size, e.g. 2g")
          },
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
    fun commandFor(apk: File, classes: File, options: Options) =
      simulatorCommand(
        java = java.binary,
        classpath = simulatorJars + robolectricJars + androidJar,
        apk = apk,
        classes = listOf(classes) + extraJars,
        options = options,
        robolectricProperties = offline,
        robolectricJvmArgs = AndroidBundleLaunch().jvmArgs(),
      )
    if (pair == null || pairClasses == null) {
      val command = commandFor(apk, classes, options)
      if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
      val exit = ProcessBuilder(command).inheritIO().runTiedToLauncher()
      exitProcess(exit)
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
    exitProcess(exit)
  }

  /** A paired simulator's node of the simulated Wearable Data Layer. */
  data class DataLayerNode(val hubPort: Int, val device: String) {
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
  ): String {
    val process =
      ProcessBuilder(
          java.binary.path,
          "-cp",
          converterJars.joinToString(File.pathSeparator) { it.path },
          CONVERTER_MAIN,
          apk.path,
          out.path,
          androidJar.path,
        )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val summary = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check(exit == 0) { "the APK converter exited $exit" }
    return summary.lineSequence().lastOrNull { it.startsWith("{") } ?: "{}"
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
      add("--enable-native-access=ALL-UNNAMED")
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
        properties["composeai.simulator.datalayer.hub"] = it.hubPort.toString()
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
        --max-heap <size>     Each simulator JVM's maximum heap, e.g. 2g (default: the JVM's,
                              a quarter of the machine's memory, for each of a pair).
        --fingerprint <f>     Build.FINGERPRINT (default Robolectric's "robolectric", which some
                              apps check to skip real work under tests; MeshCore's watch does).
        --sdk <n>             Android API level to simulate (default ${AndroidBundleLaunch.DEFAULT_SDK}).
        --jar <path>          Extra classes jar ahead of Robolectric (repeatable).
        --no-providers        Do not create the manifest's content providers at startup.
        --pair <watch.apk>    Also run a Wear OS APK in a second simulator, paired with this
                              one over a simulated Wearable Data Layer: messages, requests,
                              data items and capabilities cross between the two apps. With
                              --control stdio, a command's "device":"watch" sends it to the
                              watch, and every output line carries its "device".
        --pair-qualifiers <q> The paired watch's display (default ${ApkFormFactor.WEAR.qualifiers}).
        --java <path>         The JDK (home or java binary) to run the simulator on.
        --prepare             Convert and cache the APK, print the classes jar, and exit.
        --verbose, -v         Print the cache entry and the launched command.
        --help, -h            Show this help.
      """
        .trimIndent()
  }
}
