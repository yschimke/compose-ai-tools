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
    if ("--prepare" in args) {
      println(entry.classesJar.absolutePath)
      return
    }

    val headless = "--headless" in args || GraphicsEnvironment.isHeadless()
    if (headless && control == null) {
      System.err.println(
        "compose-preview launch: no display, so no window; pass --control stdio to drive the app " +
          "headless."
      )
    }
    val command =
      simulatorCommand(
        java = java.binary,
        classpath = simulatorJars + robolectricJars + androidJar,
        apk = apk,
        classes = listOf(entry.classesJar) + args.flagValuesAll("--jar").map { File(it) },
        options =
          Options(
            sdk =
              (args.flagValue("--sdk")?.toIntOrNull() ?: AndroidBundleLaunch.DEFAULT_SDK).coerceIn(
                AndroidBundleLaunch.MIN_SDK,
                AndroidBundleLaunch.MAX_SDK,
              ),
            qualifiers = args.flagValue("--qualifiers") ?: DEFAULT_QUALIFIERS,
            fontScale = args.flagValue("--font-scale"),
            headless = headless,
            control = control != null,
            installProviders = "--no-providers" !in args,
            showAgent = "--hide-agent" !in args,
          ),
        robolectricProperties = offline,
        robolectricJvmArgs = AndroidBundleLaunch().jvmArgs(),
      )
    if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
    val exit = ProcessBuilder(command).inheritIO().runTiedToLauncher()
    exitProcess(exit)
  }

  /** Simulator settings that become system properties of the launched JVM. */
  data class Options(
    val sdk: Int,
    val qualifiers: String,
    val fontScale: String?,
    val headless: Boolean,
    val control: Boolean,
    val installProviders: Boolean,
    /** Draw the control channel's taps, swipes and commands over the window. */
    val showAgent: Boolean = true,
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
    const val DEFAULT_QUALIFIERS = "w360dp-h640dp-hdpi"

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
      options.fontScale?.let { properties["composeai.simulator.fontScale"] = it }
      if (options.control) properties["composeai.simulator.control"] = "stdio"
      if (!options.installProviders) properties["composeai.simulator.skipProviders"] = "true"
      if (!options.showAgent) properties["composeai.simulator.showAgent"] = "false"
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
                              (default $DEFAULT_QUALIFIERS; e.g. w411dp-h891dp-xxhdpi-night).
        --font-scale <f>      Font scale (default 1.0).
        --sdk <n>             Android API level to simulate (default ${AndroidBundleLaunch.DEFAULT_SDK}).
        --jar <path>          Extra classes jar ahead of Robolectric (repeatable).
        --no-providers        Do not create the manifest's content providers at startup.
        --java <path>         The JDK (home or java binary) to run the simulator on.
        --prepare             Convert and cache the APK, print the classes jar, and exit.
        --verbose, -v         Print the cache entry and the launched command.
        --help, -h            Show this help.
      """
        .trimIndent()
  }
}
