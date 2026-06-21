package ee.schimke.composeai.fakeemulator

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Writes the running-emulator registration file Android Studio reads to discover live emulators and
 * their gRPC endpoint. A real emulator drops `pid_<pid>.ini` in its discovery directory; Studio's
 * `RunningEmulatorCatalog` scans that directory, so writing the same file makes the fake emulator
 * appear in "Running Devices" and tells Studio where our gRPC + token live.
 *
 * Linux discovery dir: `$XDG_RUNTIME_DIR/avd/running`, falling back to `<tmp>/avd/running`.
 */
class DiscoveryRegistration(
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  private val env: (String) -> String? = System::getenv,
  private val tmpDir: String = System.getProperty("java.io.tmpdir"),
  /** Override the discovery directory outright (tests, non-Linux hosts). */
  private val overrideDir: Path? = null,
) {
  data class Registration(
    val pid: Long,
    val consolePort: Int,
    val adbPort: Int,
    val avdName: String,
    val avdDir: String,
    val grpcPort: Int?,
    val grpcToken: String?,
  )

  fun discoveryDir(): Path {
    overrideDir?.let {
      return it
    }
    val xdg = env("XDG_RUNTIME_DIR")
    val base = if (!xdg.isNullOrBlank()) xdg else tmpDir
    return base.toPath() / "avd" / "running"
  }

  fun registrationFile(pid: Long): Path = discoveryDir() / "pid_$pid.ini"

  /** Write the registration; returns the file written so the caller can delete it on shutdown. */
  fun write(registration: Registration): Path {
    val dir = discoveryDir()
    fileSystem.createDirectories(dir)
    val file = dir / "pid_${registration.pid}.ini"
    val text = buildString {
      appendLine("port.serial=${registration.consolePort}")
      appendLine("port.adb=${registration.adbPort}")
      appendLine("avd.name=${registration.avdName}")
      appendLine("avd.dir=${registration.avdDir}")
      registration.grpcPort?.let { appendLine("grpc.port=$it") }
      registration.grpcToken?.let { appendLine("grpc.token=$it") }
    }
    fileSystem.write(file) { writeUtf8(text) }
    return file
  }

  fun delete(file: Path) {
    runCatching { fileSystem.delete(file) }
  }
}
