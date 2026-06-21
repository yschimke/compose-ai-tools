package ee.schimke.composeai.fakeemulator

import okio.Path

/**
 * Tunables for a [FakeEmulator]. Ports default to 0 (ephemeral); read the bound ports back after
 * start.
 */
data class FakeEmulatorConfig(
  val display: DisplaySize = DisplaySize(1080, 2340, 420),
  /** Even console port; 0 = ephemeral. Real emulators use 5554, 5556, … */
  val consolePort: Int = 0,
  /** Odd adb port; 0 = ephemeral. Real emulators use 5555, 5557, … */
  val adbPort: Int = 0,
  val avdName: String = "Compose_Preview",
  val propertyOverrides: Map<String, String> = emptyMap(),
  val enableConsole: Boolean = true,
  /** Write the Studio discovery file. Off by default — it touches user runtime dirs. */
  val writeDiscovery: Boolean = false,
  /** Recorded in the discovery file so Studio can reach our gRPC control endpoint. */
  val grpcPort: Int? = null,
  val grpcToken: String? = null,
  val avdDir: String = ".",
)

/**
 * A fake Android emulator: an ADB device transport, an emulator console, and (optionally) a Studio
 * discovery registration, all fronting a [FrameSource] (the display) and a [PreviewLauncher] (what
 * `am start … PreviewActivity` drives). The gRPC control surface lives in `:fake-emulator-grpc` and
 * is layered on top via the same [FrameSource]; its port is passed in [FakeEmulatorConfig.grpcPort]
 * purely so it lands in the discovery file.
 */
class FakeEmulator(
  private val config: FakeEmulatorConfig,
  val frameSource: FrameSource,
  private val previewLauncher: PreviewLauncher = PreviewLauncher.NOOP,
  /**
   * Render-relevant device settings the ADB shell mutates (and the gRPC controller shares). The app
   * observes this to re-render the preview under Studio's UI toggles. Defaults to a fresh
   * controller.
   */
  val settings: DeviceSettingsController = DeviceSettingsController(),
  private val discovery: DiscoveryRegistration = DiscoveryRegistration(),
) : AutoCloseable {
  private var console: EmulatorConsole? = null
  private var adbServer: AdbTransportServer? = null
  private var registrationFile: Path? = null

  var serial: String = "fake-emulator"
    private set

  val adbPort: Int
    get() = adbServer?.boundPort ?: error("not started")

  val consolePort: Int
    get() = console?.boundPort ?: -1

  fun start(): FakeEmulator {
    if (config.enableConsole) {
      console =
        EmulatorConsole(config.consolePort, config.avdName, onKill = { close() }).also {
          it.start()
        }
    }
    serial = if (console != null) "emulator-${console!!.boundPort}" else "fake-${config.adbPort}"

    val properties =
      LinkedHashMap(DeviceProperties.defaults(serial, config.display)).apply {
        putAll(config.propertyOverrides)
      }
    val interpreter = ShellInterpreter(properties, frameSource, previewLauncher, settings)
    val resolver = EmulatorAdbServices(interpreter)
    val banner = AdbBanner.build(properties)
    adbServer = AdbTransportServer(config.adbPort, banner, resolver).also { it.start() }

    // Now that adb port is bound, fix the serial to the emulator-<console> form (already set) and
    // write discovery referencing both ports + the gRPC endpoint.
    if (config.writeDiscovery) {
      registrationFile =
        discovery.write(
          DiscoveryRegistration.Registration(
            pid = ProcessHandle.current().pid(),
            consolePort = console?.boundPort ?: adbServer!!.boundPort,
            adbPort = adbServer!!.boundPort,
            avdName = config.avdName,
            avdDir = config.avdDir,
            grpcPort = config.grpcPort,
            grpcToken = config.grpcToken,
          )
        )
    }
    return this
  }

  override fun close() {
    registrationFile?.let { discovery.delete(it) }
    registrationFile = null
    runCatching { adbServer?.close() }
    runCatching { console?.close() }
  }
}
