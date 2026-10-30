package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.ApkFormFactor
import java.io.File

/**
 * The files an emulator leaves for Android Studio to find it by: an AVD directory with a
 * `config.ini` (the display size and density, and `tag.id` saying phone or watch), and a discovery
 * file, `pid_<pid>.ini`, in the registration directory every running emulator writes to, naming its
 * console, adb and gRPC ports and the gRPC token. Studio's emulator catalog reads the directory and
 * connects to the gRPC endpoint of each live process listed there.
 */
internal object AvdFiles {

  /** `config.ini`'s hardware entries, also `EmulatorStatus.hardwareConfig`. */
  fun hardware(config: VirtualDevice.Config): List<Pair<String, String>> {
    val wear = config.formFactor == ApkFormFactor.WEAR
    return listOf(
      "AvdId" to config.avdName,
      "avd.ini.displayname" to config.avdName.replace('_', ' '),
      "abi.type" to "x86_64",
      "hw.device.manufacturer" to "Compose Preview",
      "hw.device.name" to if (wear) "wearos_large_round" else "medium_phone",
      "hw.lcd.width" to config.widthPx.toString(),
      "hw.lcd.height" to config.heightPx.toString(),
      "hw.lcd.density" to config.densityDpi.toString(),
      "hw.initialOrientation" to "portrait",
      "hw.keyboard" to "yes",
      "hw.arc" to "false",
      "hw.sensor.hinge" to "no",
      "showDeviceFrame" to "no",
      "tag.id" to if (wear) "android-wear" else "google_apis",
      "tag.display" to if (wear) "Wear OS" else "Google APIs",
      "target" to "android-${config.sdk}",
    )
  }

  /** Writes the AVD directory and returns it. */
  fun writeAvd(config: VirtualDevice.Config): File {
    val dir = File(config.dir, "${config.avdName}.avd")
    dir.mkdirs()
    File(dir, "config.ini")
      .writeText(hardware(config).joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" })
    return dir
  }

  /** The emulator's registration directory on this host, as the emulator and Studio compute it. */
  fun registrationDirectory(env: Map<String, String> = System.getenv()): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
      os.contains("mac") -> File(home, "Library/Caches/TemporaryItems/avd/running")
      os.contains("win") ->
        File(env["LOCALAPPDATA"] ?: "$home\\AppData\\Local", "Temp\\avd\\running")
      !env["XDG_RUNTIME_DIR"].isNullOrBlank() -> File(env["XDG_RUNTIME_DIR"], "avd/running")
      else -> File("/tmp/android-${System.getProperty("user.name")}/avd/running")
    }
  }

  /**
   * Writes `pid_<pid>.ini` for this process. `-qt-hide-window` in `cmdline` is how an embedded
   * emulator is told apart from one with its own window: Studio shows that kind in Running Devices.
   */
  fun writeDiscovery(
    config: VirtualDevice.Config,
    avdDir: File,
    grpcPort: Int,
    token: String,
  ): File {
    val dir = registrationDirectory()
    dir.mkdirs()
    val file = File(dir, "pid_${ProcessHandle.current().pid()}.ini")
    file.writeText(
      discovery(config, avdDir, grpcPort, token).joinToString("\n", postfix = "\n") { (k, v) ->
        "$k=$v"
      }
    )
    file.deleteOnExit()
    return file
  }

  fun discovery(
    config: VirtualDevice.Config,
    avdDir: File,
    grpcPort: Int,
    token: String,
  ): List<Pair<String, String>> =
    listOf(
      "port.serial" to config.consolePort.toString(),
      "port.adb" to config.adbPort.toString(),
      "avd.name" to config.avdName,
      "avd.dir" to avdDir.absolutePath,
      "avd.id" to config.avdName,
      "cmdline" to
        "\"compose-preview\" \"launch\" \"-avd\" \"${config.avdName}\" \"-qt-hide-window\" \"-grpc-use-token\"",
      "grpc.port" to grpcPort.toString(),
      "grpc.token" to token,
    )
}
