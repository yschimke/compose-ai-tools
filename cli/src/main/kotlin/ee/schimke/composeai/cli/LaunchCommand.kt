package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.cli.device.DeviceHost
import ee.schimke.composeai.cli.device.VirtualDevice
import ee.schimke.composeai.io.composeAiCacheDir
import java.awt.GraphicsEnvironment
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
    val adb = "--adb" in args
    val verbose = "--verbose" in args || "-v" in args
    val control = args.flagValue("--control")
    if (control != null && control != "stdio") fail("--control supports only `stdio`")
    if (adb && args.flagValue("--project") != null) {
      fail("--project reloads a supervised simulator; --adb runs its own device: drop one")
    }
    val live = liveReload()
    val liveWatch = "--live" in args
    // A warm JVM waiting for the next restart: worth its memory when restarts are the loop.
    val standby = "--no-standby" !in args && ("--standby" in args || live != null)
    if (liveWatch && live == null) fail("--live needs --project <dir>: the build to reload from")
    if ("--standby" in args && adb) {
      fail("--standby keeps a simulator for `restart`; --adb has none, drop it")
    }
    // No APK boots the device itself: the system UI with nothing installed (`--adb` installs
    // into it later). --project is not "no APK": it builds the one to launch.
    // `--app <device>:<apk>` (repeatable): a topology of devices and their apps.
    val appSpecs =
      args.flagValuesAll("--app").map { spec ->
        runCatching { LaunchTopology.parse(spec) }
          .getOrElse { fail(it.message ?: "bad --app $spec") }
          .also { if (!it.apk.isFile) fail("no such APK for --app: ${it.apk}") }
      }
    val positional = CliFlags.firstPositional(args)?.let { File(it).absoluteFile }
    val apk =
      positional
        ?: appSpecs.firstOrNull()?.apk
        ?: live?.let { reload ->
          System.err.println(
            "compose-preview launch: building ${reload.assembleTask} for the APK to launch"
          )
          val (build, built) = reload.assemble()
          built ?: fail("${reload.assembleTask} failed:\n" + build.errors.joinToString("\n"))
        }
    if (apk != null && !apk.isFile) fail("no such APK: $apk")
    if (standby && apk == null) fail("--standby restarts an app: launch <app.apk> --standby")

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
    // released, and another launch could prune the jar from under a running simulator. An update or
    // a reload's rebuilt APK adds its entry here too.
    val inUse = Collections.synchronizedList(mutableListOf<Closeable>())
    fun finish(exit: Int): Nothing {
      synchronized(inUse) { inUse.forEach { runCatching { it.close() } } }
      exitProcess(exit)
    }
    val locked = ConcurrentHashMap.newKeySet<String>()
    fun conversionOf(apk: File): Conversion {
      val start = System.nanoTime()
      val entry =
        cache.ensure(apk, stamp) { out, previous ->
          System.err.println(
            "compose-preview launch: converting ${apk.name} (once per APK" +
              (if (previous != null) "; reusing the unchanged classes of its previous build)"
              else ")")
          )
          convert(java, converterJars, apk, out, androidJar, previous)
        }
      // Once per entry: a second lock on the same file from this process throws
      // (OverlappingFileLockException), as an update to an APK already running would.
      if (locked.add(entry.classesJar.absolutePath)) inUse += cache.use(entry)
      val conversion =
        Conversion(apk, entry, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
      if (verbose || !entry.cached) {
        System.err.println(
          "compose-preview launch: classes ${if (entry.cached) "cached" else "converted"} " +
            "in ${conversion.ms} ms: ${entry.classesJar} ${entry.summary}"
        )
      }
      return conversion
    }
    fun classesOf(apk: File): File = conversionOf(apk).entry.classesJar
    val pair =
      args
        .flagValue("--pair")
        ?.let { File(it).absoluteFile }
        ?.also { if (!it.isFile) fail("no such APK to pair: $it") }
    if (adb && pair != null) fail("--adb runs one device; drop --pair")
    if (apk == null && pair != null)
      fail("--pair needs the phone's APK: launch <phone.apk> --pair <watch.apk>")
    // Other apps on the same device, each in a simulator of its own (DeviceBus).
    val installs =
      args.flagValuesAll("--install").map { path ->
        File(path).absoluteFile.also { if (!it.isFile) fail("no such APK to install: $it") }
      }
    if (installs.isNotEmpty() && apk == null) {
      fail("--install adds apps beside one: launch <app.apk> --install <other.apk>")
    }
    if (adb && (installs.isNotEmpty() || appSpecs.isNotEmpty())) {
      fail("--adb runs one app on one device; drop --install and --app")
    }
    if ("--prepare" in args) {
      if (apk == null) fail("--prepare converts an APK: launch <app.apk> --prepare")
      println(classesOf(apk).absolutePath)
      pair?.let { println(classesOf(it).absolutePath) }
      installs.forEach { println(classesOf(it).absolutePath) }
      (if (positional == null) appSpecs.drop(1) else appSpecs).forEach {
        println(classesOf(it.apk).absolutePath)
      }
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
    val playServices =
      args.flagValue("--play-services")?.also {
        if (it !in PLAY_SERVICES_STATES) {
          fail("--play-services takes ${PLAY_SERVICES_STATES.joinToString(" | ")}")
        }
      }
    val detected =
      apk?.let { ApkFormFactor.inspect(it) } ?: ApkFormFactor.Detected(ApkFormFactor.PHONE, null)
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
        "compose-preview launch: ${formFactor.id} ${if (apk == null) "device" else "app"}; " +
          "display ${formFactor.qualifiers}" +
          (if (args.flagValue("--qualifiers") != null || args.flagValue("--device") != null)
            " (overridden by --qualifiers / --device)"
          else "")
      )
    }

    val headless = "--headless" in args || GraphicsEnvironment.isHeadless()
    if (headless && control == null && !adb) {
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
        playServices = playServices,
        fontScale = args.flagValue("--font-scale"),
        fingerprint = args.flagValue("--fingerprint"),
        maxHeap =
          args.flagValue("--max-heap")?.also {
            if (!Regex("[0-9]+[kKmMgG]?").matches(it)) fail("--max-heap takes a size, e.g. 2g")
          } ?: defaultMaxHeap(physicalMemoryBytes()),
        headless = headless,
        // With --adb this process drives the simulator's channel, client or not.
        control = control != null || adb,
        installProviders = "--no-providers" !in args,
        showAgent = "--hide-agent" !in args,
        displays = args.flagValuesAll("--display"),
        // A device with nothing started on it shows its system UI.
        launcher =
          apk == null ||
            adb ||
            "--launcher" in args ||
            args.flagValuesAll("--widget").isNotEmpty() ||
            args.flagValuesAll("--tile").isNotEmpty(),
        noLaunch = apk == null || adb,
        widgets = args.flagValuesAll("--widget"),
        tiles = args.flagValuesAll("--tile"),
        jvmOptions =
          System.getenv(JVM_OPTIONS_ENV)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            ?: emptyList(),
      )
    val extraJars = args.flagValuesAll("--jar").map { File(it) }
    val robolectricProperties = offline.ifEmpty {
      provisionAndroidAll(robolectricJars, options.sdk)
    }
    fun commandFor(launch: SimulatorSupervisor.Launch, options: Options): List<String> =
      simulatorCommand(
        java = java.binary,
        classpath = simulatorJars + robolectricJars + androidJar,
        apk = launch.apk,
        classes = launch.classes + extraJars,
        options = options.copy(properties = options.properties + launch.properties),
        robolectricProperties = robolectricProperties,
        robolectricJvmArgs = AndroidBundleLaunch().jvmArgs(),
      )

    /** The APK (and its converted classes) under every live-reload overlay. */
    val bases = ConcurrentHashMap<SimulatorSupervisor, Conversion>()

    /**
     * `reload`: compiles the project and restarts the app on the result — changed classes over the
     * converted APK, relinked resources, or a rebuilt APK when only packaging delivers the change.
     */
    fun reload(live: LiveReload, supervisor: SimulatorSupervisor, command: JsonObject): JsonObject {
      val start = System.nanoTime()
      val keepData = command.boolean("keepData") ?: (command.boolean("clearData") != true)
      val plan =
        synchronized(live) {
          live.prepare(bases.getValue(supervisor).apk, full = command.boolean("full") == true)
        }
      fun JsonObject.with(vararg extra: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(
          this +
            extra +
            ("totalMs" to JsonPrimitive(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)))
        )
      return when (plan) {
        is LiveReload.Plan.Failed ->
          throw IllegalStateException(
            "the build failed; the app keeps running.\n" + plan.build.errors.joinToString("\n")
          )
        is LiveReload.Plan.Unchanged -> buildJsonObject {
            put("mode", "unchanged")
            put("build", plan.build.toJson())
          }
            .with()
        is LiveReload.Plan.Overlay -> {
          val restarted =
            supervisor.restart(
              SimulatorSupervisor.Launch(
                plan.resourceApk,
                listOfNotNull(plan.overlay) + bases.getValue(supervisor).entry.classesJar,
              ),
              keepData,
              reason = "reload",
            )
          live.applied()
          JsonObject(restarted - "totalMs")
            .with(
              "mode" to JsonPrimitive(if (plan.resources) "resources" else "classes"),
              "build" to plan.build.toJson(),
              "classesChanged" to JsonPrimitive(plan.changedClasses.size),
              "classes" to
                kotlinx.serialization.json.JsonArray(
                  plan.changedClasses.take(MAX_LISTED_CLASSES).map(::JsonPrimitive)
                ),
              "resourcesChanged" to JsonPrimitive(plan.resources),
              "resourceIdsChanged" to JsonPrimitive(plan.resourceIds),
              "prepareMs" to JsonPrimitive(plan.prepareMs),
            )
        }
        is LiveReload.Plan.Rebuild -> {
          val converted = conversionOf(plan.apk)
          bases[supervisor] = converted
          val restarted =
            supervisor.restart(
              SimulatorSupervisor.Launch(plan.apk, listOf(converted.entry.classesJar)),
              keepData,
              reason = "reload",
            )
          JsonObject(restarted - "totalMs")
            .with(
              "mode" to JsonPrimitive("apk"),
              "why" to JsonPrimitive(plan.reason),
              "build" to plan.build.toJson(),
              "conversion" to converted.toJson(),
            )
        }
      }
    }

    /** `update`: another APK (a rebuild of this one) in a fresh simulator, keeping the data. */
    fun update(supervisor: SimulatorSupervisor, command: JsonObject): JsonObject {
      val next =
        command.string("apk")?.let { File(it).absoluteFile }
          ?: throw IllegalArgumentException("update needs `apk`, the path of the new APK")
      require(next.isFile) { "no such APK: $next" }
      val keepData = command.boolean("keepData") ?: (command.boolean("clearData") != true)
      if (keepData) {
        val running = ApkFormFactor.packageOf(supervisor.launch.apk)
        val updated = ApkFormFactor.packageOf(next)
        require(running == updated) {
          "$next is $updated, not $running: its data cannot carry over (pass keepData: false)"
        }
      }
      val converted = conversionOf(next)
      bases[supervisor] = converted
      val restarted =
        supervisor.restart(
          SimulatorSupervisor.Launch(next, listOf(converted.entry.classesJar)),
          keepData,
          reason = "update",
        )
      return JsonObject(restarted + ("conversion" to converted.toJson()))
    }

    // The simulator --project builds (reload, standby): the first app's.
    var reloadable = PHONE
    fun supervise(
      device: String?,
      conversion: Conversion,
      options: Options,
    ): SimulatorSupervisor {
      val launch = SimulatorSupervisor.Launch(conversion.apk, listOf(conversion.entry.classesJar))
      if (verbose) {
        System.err.println(
          "compose-preview launch: ${device?.let { "$it: " } ?: ""}" +
            commandFor(launch, options).joinToString(" ")
        )
      }
      return SimulatorSupervisor(
          device = device,
          initial = launch,
          command = { commandFor(it, options) },
          emit = PairedLaunch::emit,
          log = PairedLaunch::log,
          control = control != null,
          extraCommand = { supervisor, cmd, json ->
            when (cmd) {
              "update" -> update(supervisor, json)
              "reload" ->
                live
                  ?.takeIf { device == null || device == reloadable }
                  ?.let { reload(it, supervisor, json) }
                  ?: throw IllegalStateException(
                    if (live != null) "reload is $reloadable's: --project builds that app"
                    else "reload needs a launch with --project <dir>, the app's Gradle build"
                  )
              else -> null
            }
          },
          // Only when something can bring the app back (a reload command or --live): otherwise
          // the launch would wait on after its simulator exited.
          outliveExits = outlivesExits(live != null, control != null, liveWatch),
          // The watch of a pair is not what reloads: no second JVM waiting for it.
          standby = standby && control != null && (device == null || device == reloadable),
        )
        .also {
          bases[it] = conversion
          it.event(conversion.event())
        }
    }
    val systemApk =
      SystemApk.ensure(composeAiCacheDir("simulator").resolve("system"), formFactor, options.sdk)
    /** The installed APK's classes, or none for the system package. */
    fun bootCommand(installed: File): List<String> =
      commandFor(
        SimulatorSupervisor.Launch(
          installed,
          if (installed == systemApk) emptyList() else listOf(classesOf(installed)),
        ),
        options,
      )

    if (adb) {
      if (args.flagValue("--data-layer") != null)
        fail("--adb and --data-layer agent are not combined yet")
      // The device draws nothing itself (the simulator JVM owns the window), so its AWT use — the
      // gRPC endpoint's image scaling — stays out of the macOS Dock and app switcher. Set before
      // the
      // toolkit starts; `java.awt.headless` would be too late, `isHeadless()` above having cached
      // it.
      System.setProperty("apple.awt.UIElement", "true")
      val consolePort = DeviceHost.freeConsolePort()
      val (width, height, dpi) = displayPixels(options.qualifiers, options.device)
      val config =
        VirtualDevice.Config(
          formFactor = formFactor,
          sdk = options.sdk,
          consolePort = consolePort,
          avdName =
            if (formFactor == ApkFormFactor.WEAR) "Compose_Preview_Watch"
            else "Compose_Preview_Phone",
          dir =
            composeAiCacheDir("simulator").resolve("devices/emulator-$consolePort").also {
              it.deleteRecursively()
              it.mkdirs()
            },
          systemApk = systemApk,
          widthPx = width,
          heightPx = height,
          densityDpi = dpi,
          fingerprint = options.fingerprint,
        )
      val virtual =
        VirtualDevice(
          config,
          simulatorCommand = { installed ->
            bootCommand(installed).also {
              if (verbose) System.err.println("compose-preview launch: ${it.joinToString(" ")}")
            }
          },
          onLine = { line ->
            if (control != null) {
              synchronized(System.out) {
                println(line)
                System.out.flush()
              }
            }
          },
          log = { if (verbose) System.err.println("compose-preview launch: $it") },
        )
      val grpcPort =
        args.flagValue("--grpc-port")?.let { it.toIntOrNull() ?: fail("--grpc-port takes a port") }
          ?: DeviceHost.grpcPortFor(consolePort)
      exitProcess(DeviceHost(virtual, grpcPort, control = control != null, initialApk = apk).run())
    }
    if (apk == null) {
      val command = bootCommand(systemApk)
      if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
      exitProcess(ProcessBuilder(command).inheritIO().runTiedToLauncher())
    }
    // The normal and `--pair` launches run behind a SimulatorSupervisor (`restart`, `update`).
    val conversion = conversionOf(apk)
    // What every reload compares against: the classes this APK was built from.
    live?.recordBaseline()
    val classes = conversion.entry.classesJar

    // The APK argument is the first app of its device: the watch's for a Wear app, else the
    // phone's. --pair adds the watch's, --install apps beside it, --app any device's.
    val primaryDevice = if (formFactor == ApkFormFactor.WEAR) WATCH else PHONE
    if (pair != null && primaryDevice == WATCH) {
      fail(
        "--pair pairs a watch with a phone app; this is a Wear app (use --app to say which is which)"
      )
    }
    val topology = buildList {
      add(
        if (positional == null && appSpecs.isNotEmpty()) appSpecs.first()
        else LaunchTopology.App(primaryDevice, apk)
      )
      val first = first()
      pair?.let { add(LaunchTopology.App(WATCH, it)) }
      installs.forEach { add(LaunchTopology.App(first.device, it)) }
      addAll(if (positional == null) appSpecs.drop(1) else appSpecs)
    }

    val dataLayerMode = args.flagValue("--data-layer")
    if (dataLayerMode != null) {
      if (dataLayerMode != "agent") fail("--data-layer supports only `agent`")
      if (topology.size > 1) {
        fail("--data-layer agent plays the paired device itself; drop --pair, --install and --app")
      }
      if (control == null) fail("--data-layer agent needs --control stdio: the agent is the peer")
      val device = if (formFactor == ApkFormFactor.WEAR) WATCH else PHONE
      val supervisor =
        supervise(
          null,
          conversion,
          options.copy(dataLayer = DataLayerNode(hubPort = 0, device = device, agent = true)),
        )
      finish(PairedLaunch(listOf(device to supervisor), control = true).run())
    }
    /** `--live`: every settled edit under the project's sources reloads [supervisor]. */
    fun watch(live: LiveReload, supervisor: SimulatorSupervisor) {
      live.watch { changed ->
        val first = changed.first()
        supervisor.execute {
          System.err.println(
            "compose-preview launch: ${changed.size} source change(s) (${first.fileName}…); reloading"
          )
          val event =
            try {
              JsonObject(
                mapOf("event" to JsonPrimitive("reload")) +
                  reload(live, supervisor, JsonObject(emptyMap()))
              )
            } catch (e: Exception) {
              System.err.println("compose-preview launch: reload failed: ${e.message}")
              buildJsonObject {
                put("event", "reload")
                put("ok", false)
                put("error", e.message ?: "$e")
              }
            }
          supervisor.event(event)
        }
      }
    }

    // A lone --app still names its device, which a plain launch would call the phone.
    if (topology.size > 1 || appSpecs.isNotEmpty()) {
      val exit =
        launchTopology(
          topology,
          conversion,
          options,
          pairQualifiers = args.flagValue("--pair-qualifiers"),
          conversionOf = ::conversionOf,
          supervise = { name, conv, own -> supervise(name, conv, own) },
          onNames = { first -> reloadable = first },
          onPrimary = { if (live != null && liveWatch) watch(live, it) },
        )
      live?.close()
      finish(exit)
    }

    if (control == null && live == null) {
      // A window only: nothing to supervise, so the simulator has the terminal to itself.
      val command = commandFor(SimulatorSupervisor.Launch(apk, listOf(classes)), options)
      if (verbose) System.err.println("compose-preview launch: ${command.joinToString(" ")}")
      finish(ProcessBuilder(command).inheritIO().runTiedToLauncher())
    }
    val supervisor = supervise(null, conversion, options)
    if (live != null && liveWatch) watch(live, supervisor)
    val exit = PairedLaunch(listOf(PHONE to supervisor), control = control != null).run()
    live?.close()
    finish(exit)
  }

  /**
   * A [topology] of devices and apps, each app in a simulator JVM of its own, as each app runs in a
   * process of its own:
   * - the apps of one device share a device bus (`DeviceBus`, over a [DataLayerHub] relay) and know
   *   each other's manifests; the device's first app is on screen, the others start their process
   *   without an activity, as an app does when something calls into it;
   * - devices share the Wearable Data Layer, one node each, which delivers between the same package
   *   on both sides; a watch is drawn round with the rotary input (`--pair-qualifiers`), and the
   *   first device keeps the launch's display options.
   *
   * Each simulator is a `device` of the control channel ([LaunchTopology.names]); a command naming
   * none goes to the first app.
   */
  private fun launchTopology(
    topology: List<LaunchTopology.App>,
    first: Conversion,
    options: Options,
    pairQualifiers: String?,
    conversionOf: (File) -> Conversion,
    supervise: (String, Conversion, Options) -> SimulatorSupervisor,
    onNames: (String) -> Unit,
    onPrimary: (SimulatorSupervisor) -> Unit,
  ): Int {
    val (names, aliases) =
      runCatching {
        LaunchTopology.names(topology) { ApkFormFactor.packageOf(it) ?: it.nameWithoutExtension }
      }
        .getOrElse { fail(it.message ?: "$it") }
    onNames(names.first())
    val devices = LaunchTopology.devices(topology)
    val primaryDevice = topology.first().device
    val dataLayer = if (devices.size > 1) DataLayerHub().start() else null
    val buses =
      devices
        .filter { device -> topology.count { it.device == device } > 1 }
        .associateWith { DataLayerHub().start() }
    val hasWatch = devices.any(LaunchTopology::isWatch)
    val supervisors = topology.mapIndexed { i, app ->
      var own = options
      // A device named a watch is one: the first app keeps the launch's own Wear display options
      // when its APK is a Wear app, and gets the watch's otherwise.
      val watch = LaunchTopology.isWatch(app.device)
      if (watch && (app.device != primaryDevice || own.formFactor != ApkFormFactor.WEAR.id)) {
        own =
          own.copy(
            qualifiers = pairQualifiers ?: ApkFormFactor.WEAR.qualifiers,
            // Drawn round, with the rotary input; --device presets are phone-sized, so the watch
            // keeps its own display, and the phone's projected activity is not the watch's.
            formFactor = ApkFormFactor.WEAR.id,
            launchActivity = null,
            device = null,
            // --display and --widget are the phone's; the watch has its one round display.
            displays = emptyList(),
            widgets = emptyList(),
          )
      } else if (hasWatch && !LaunchTopology.isWatch(app.device)) {
        // --tile is the watch's.
        own = own.copy(tiles = emptyList())
      }
      dataLayer?.let { own = own.copy(dataLayer = DataLayerNode(it.port, app.device)) }
      buses[app.device]?.let { bus ->
        own =
          own.copy(
            properties =
              own.properties +
                mapOf(
                  "composeai.simulator.device.bus" to bus.port.toString(),
                  "composeai.simulator.device.peers" to
                    LaunchTopology.deviceMates(topology, app).joinToString(File.pathSeparator) {
                      it.apk.path
                    },
                )
          )
      }
      if (!LaunchTopology.isFirstOnDevice(topology, app)) {
        own =
          own.copy(
            headless = true,
            noLaunch = true,
            launcher = false,
            displays = emptyList(),
            widgets = emptyList(),
            tiles = emptyList(),
          )
      }
      names[i] to supervise(names[i], if (i == 0) first else conversionOf(app.apk), own)
    }
    onPrimary(supervisors.first().second)
    val exit = PairedLaunch(supervisors, control = options.control, aliases = aliases).run()
    dataLayer?.close()
    buses.values.forEach { it.close() }
    return exit
  }

  /** An APK's converted classes, and what getting them cost: a cache hit, or a conversion. */
  data class Conversion(val apk: File, val entry: ApkConversionCache.Entry, val ms: Long) {
    fun toJson(): JsonObject = buildJsonObject {
      put("apk", apk.path)
      put("cached", entry.cached)
      put("ms", ms)
      put("classesJar", entry.classesJar.path)
      runCatching { Json.parseToJsonElement(entry.summary) }.getOrNull()?.let { put("summary", it) }
    }

    /** The control channel's `conversion` event, sent before the simulator starts. */
    fun event(): JsonObject = JsonObject(mapOf("event" to JsonPrimitive("conversion")) + toJson())
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
    /** One of [PLAY_SERVICES_STATES]; installed when null. */
    val playServices: String? = null,
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
    /** Extra simulator JVM options, from [JVM_OPTIONS_ENV] (`-Xlog:…` while profiling a launch). */
    val jvmOptions: List<String> = emptyList(),
    /** Further system properties: a restarted simulator's data to restore, its launch time. */
    val properties: Map<String, String> = emptyMap(),
    /**
     * Boot as a device: the APK is installed but not started (`launch` with no APK, the system
     * package; `--adb`, whatever is installed), so the system UI is what shows.
     */
    val noLaunch: Boolean = false,
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

  /** `--project`: the app's Gradle build, for `reload` and `--live`. */
  private fun liveReload(): LiveReload? {
    val project = args.flagValue("--project")?.let { File(it).absoluteFile } ?: return null
    if (!File(project, "settings.gradle.kts").isFile && !File(project, "settings.gradle").isFile) {
      fail("--project $project is not a Gradle build (no settings.gradle(.kts))")
    }
    val module =
      (args.flagValue("--module") ?: "app").let { if (it.startsWith(":")) it else ":$it" }
    val workDir = java.nio.file.Files.createTempDirectory("compose-preview-live").toFile()
    Runtime.getRuntime().addShutdownHook(Thread { workDir.deleteRecursively() })
    return LiveReload(
      projectDir = project,
      module = module,
      variant = args.flagValue("--variant") ?: "debug",
      gradleArguments = args.flagValuesAll("--gradle-arg"),
      workDir = workDir,
      log = { System.err.println(it) },
    )
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

  private fun fail(message: String): Nothing {
    System.err.println("compose-preview launch: $message")
    exitProcess(1)
  }

  companion object {
    const val LAUNCH_MAIN = "ee.schimke.composeai.simulator.LaunchMain"
    const val CONVERTER_MAIN = "ee.schimke.composeai.simulator.ApkClassConverter"

    /**
     * Whether a launch outlives its app exiting: only with a project and something that can reload
     * it, the control channel's `reload` or `--live`. Otherwise nothing could bring the app back,
     * and the CLI would wait on after its simulator exited.
     */
    fun outlivesExits(project: Boolean, control: Boolean, live: Boolean): Boolean =
      project && (control || live)

    /** Extra options for the simulator JVM, whitespace-separated. */
    const val JVM_OPTIONS_ENV = "COMPOSE_PREVIEW_SIMULATOR_JVM_OPTIONS"

    /** How many changed classes a reload's answer names. */
    private const val MAX_LISTED_CLASSES = 20

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

    /**
     * The display's size in pixels and its density, from Robolectric qualifiers
     * (`w227dp-h227dp-round-watch-xhdpi` is 454×454 at 320 dpi); a phone's default when they name
     * no size.
     */
    fun displayPixels(qualifiers: String, device: String? = null): Triple<Int, Int, Int> {
      val parts = qualifiers.split('-')
      val launchDpi =
        parts.firstNotNullOfOrNull {
          DENSITIES[it] ?: Regex("""(\d+)dpi""").matchEntire(it)?.groupValues?.get(1)?.toInt()
        } ?: 160
      // A --device preset replaces the size, and the density where it has its own, as
      // `DisplayGeometry.launchQualifiers` in `:simulator-host` does.
      DEVICE_SIZES_DP[device]?.let { (w, h, presetDpi) ->
        val dpi = presetDpi ?: launchDpi
        return Triple(w * dpi / 160, h * dpi / 160, dpi)
      }
      val dpi = launchDpi
      val w =
        parts.firstNotNullOfOrNull {
          Regex("""w(\d+)dp""").matchEntire(it)?.groupValues?.get(1)?.toInt()
        } ?: 360
      val h =
        parts.firstNotNullOfOrNull {
          Regex("""h(\d+)dp""").matchEntire(it)?.groupValues?.get(1)?.toInt()
        } ?: 640
      return Triple(w * dpi / 160, h * dpi / 160, dpi)
    }

    /** [DEVICES]' sizes in dp and own density (`DisplayGeometry.PRESETS` in `:simulator-host`). */
    private val DEVICE_SIZES_DP: Map<String?, Triple<Int, Int, Int?>> =
      mapOf(
        "phone" to Triple(411, 891, null),
        "foldable" to Triple(673, 841, null),
        "foldable-folded" to Triple(411, 891, null),
        "tablet" to Triple(1280, 800, null),
        "desktop" to Triple(1920, 1080, 160),
      )

    private val DENSITIES =
      mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
      )

    /**
     * What `--play-services` takes (`PlayServices.State` in `:simulator-host`), which the control
     * channel's `playServices` command switches between at run time.
     */
    val PLAY_SERVICES_STATES = listOf("installed", "none", "outdated", "disabled", "invalid")

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
      addAll(options.jvmOptions)
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
      options.playServices?.let { properties["composeai.simulator.playServices"] = it }
      options.fontScale?.let { properties["composeai.simulator.fontScale"] = it }
      options.fingerprint?.let { properties["composeai.simulator.fingerprint"] = it }
      if (options.control) properties["composeai.simulator.control"] = "stdio"
      if (!options.installProviders) properties["composeai.simulator.skipProviders"] = "true"
      if (!options.showAgent) properties["composeai.simulator.showAgent"] = "false"
      if (options.launcher) properties["composeai.simulator.launcher"] = "true"
      if (options.noLaunch) properties["composeai.simulator.noLaunch"] = "true"
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
      properties.putAll(options.properties)
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
      compose-preview launch [--form-factor wear] [--adb] [options]

      Run an APK in Robolectric's simulator: a window showing the app, driven by mouse and
      keyboard (right-click is Back). The APK's dex is converted to JVM classes once and cached
      by content; Robolectric comes from the Android daemon sidecar. With no APK the device
      boots to its system UI alone: the home screen, or on a watch the watch face.

      --adb makes the simulator a device adb and Android Studio see, as an emulator is:
      emulator-5554 (the first free port pair), with adb install / uninstall / shell / push /
      pull, the emulator console, and the emulator gRPC endpoint Studio's Running Devices
      window streams the display over. An install swaps in a simulator booted on the new APK.

      --control stdio adds a JSON-lines control channel on stdin/stdout: tap, swipe, type,
      read the UI hierarchy, take screenshots, start activities and services, send broadcasts,
      query providers; "restart" and "update" {"apk":…} replace the app in a fresh JVM, its
      data kept. See docs/SIMULATOR.md. This is what agents and the MCP server use. The
      window stays up, so you watch the agent drive the app: each tap and swipe it sends is
      drawn over the window (never into its screenshots) and the command shown along the
      bottom. --headless drops the window; with no display there is none anyway.

      Needs Java 21+ (--java, or COMPOSE_PREVIEW_SIMULATOR_JAVA) and an Android SDK platform
      (ANDROID_HOME or local.properties sdk.dir).

      Options:
        --control stdio       JSON-lines control channel on stdin/stdout.
        --adb                 Serve the device to adb and Android Studio (see above); an
                              APK given too is installed, not started.
        --grpc-port <n>       With --adb, the emulator gRPC port (default 8554 + the
                              console port's offset from 5554).
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
        --play-services <s>   Google Play services as availability checks report it:
                              installed (default; SUCCESS, version 25.42.34), none
                              (SERVICE_MISSING), outdated, disabled or invalid, for an
                              app's error paths. With --pair, both devices.
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
        --install <other.apk> Install another app on the same device (repeatable), in a
                              simulator of its own: each app's PackageManager lists the
                              others, and calls between them (App Functions) cross over the
                              device's bus. The first APK is on screen; the others start
                              without an activity. With --control stdio, a command's
                              "device":"phone/<package>" (or the package) sends it to that app.
        --app <device>:<apk>  Run an app on a device (repeatable): the whole topology, as
                              --app phone:a.apk --app phone:b.apk --app watch:w.apk. A device
                              named watch (or watch-…) is a Wear OS watch, any other a phone.
                              Apps on a device share it as with --install; devices share the
                              Wearable Data Layer, which delivers between the same package on
                              each. An app alone on its device is "device":<device>; apps
                              sharing one are <device>/<package>. Without an APK argument the
                              first --app is the one on screen.
        --project <dir>       The app's Gradle build: the APK becomes optional (it is built),
                              and the control channel's "reload" compiles an edit and restarts
                              the app on just the changed classes, with no dexing or APK.
        --module <name>       With --project: the app module (default app).
        --variant <name>      With --project: the variant (default debug).
        --live                With --project: reload after every edit to the sources.
        --gradle-arg <arg>    With --project: a Gradle argument (repeatable), e.g.
                              --max-workers=2 or --configuration-cache.
        --standby             Keep a second simulator JVM prepared up to the app's own code,
                              so "restart" and "reload" take seconds (the default with
                              --project; --no-standby turns it off). Needs an APK; not
                              with --adb.
        --java <path>         The JDK (home or java binary) to run the simulator on.
        --prepare             Convert and cache the APK, print the classes jar, and exit.
        --verbose, -v         Print the cache entry and the launched command.
        --help, -h            Show this help.
      """
        .trimIndent()
  }
}
