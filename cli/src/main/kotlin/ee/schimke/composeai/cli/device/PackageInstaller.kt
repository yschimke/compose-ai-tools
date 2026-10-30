package ee.schimke.composeai.cli.device

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `pm` / `cmd package` as far as packages go: `install` (from a pushed file or streamed with `-S`),
 * the session commands Android Studio and `adb install-multiple` use (`install-create`,
 * `install-write`, `install-commit`, `install-abandon`), `uninstall`, `list packages`, `path`,
 * `list features` and `list users`. Everything else of `pm` the simulator answers.
 *
 * Output follows the platform's `PackageManagerShellCommand` word for word where tools parse it:
 * `Success`, `Failure [REASON: message]`, `Success: created install session [id]`.
 */
internal class PackageInstaller(private val device: VirtualDevice) {

  private class Session(val dir: File)

  private val sessions = ConcurrentHashMap<Int, Session>()
  private val nextSession = AtomicInteger(1_000_000 + (System.nanoTime() % 1000).toInt())

  /** Runs `pm <args>`, or returns null for a subcommand the simulator answers instead. */
  fun run(args: List<String>, stdin: InputStream, out: ShellOutput): Int? {
    val sub = args.firstOrNull() ?: return out.fail("pm: no command")
    val rest = args.drop(1)
    return when (sub) {
      "install" -> install(rest, stdin, out)
      "install-create" -> {
        val id = nextSession.getAndIncrement()
        sessions[id] =
          Session(Files.createTempDirectory(device.config.dir.toPath(), "session").toFile())
        out.line("Success: created install session [$id]")
      }
      "install-write" -> write(rest, stdin, out)
      "install-commit" -> commit(rest, out)
      "install-abandon",
      "install-destroy" -> {
        val id = rest.lastOrNull()?.toIntOrNull()
        sessions.remove(id)?.dir?.deleteRecursively()
        out.line("Success")
      }
      "uninstall" -> {
        val pkg =
          rest.lastOrNull { !it.startsWith("-") && it != "0" }
            ?: return out.fail("pm uninstall: no package")
        if (device.uninstall(pkg)) out.line("Success")
        else out.line("Failure [DELETE_FAILED_INTERNAL_ERROR]", exit = 1)
      }
      "list" ->
        when (rest.firstOrNull()) {
          "packages" -> listPackages(rest.drop(1), out)
          "features" -> features().forEach { out.line("feature:$it") }.let { 0 }
          "users" -> {
            out.line("Users:")
            out.line("\tUserInfo{0:Owner:c13} running")
          }
          "libraries" -> 0
          else -> null
        }
      "path" -> {
        val pkg = rest.lastOrNull() ?: return out.fail("pm path: no package")
        val apk = apkOf(pkg) ?: return out.line("", exit = 1)
        out.line("package:$apk")
      }
      "get-max-users" -> out.line("Maximum supported users: 1")
      "get-install-location" -> out.line("0[auto]")
      else -> null
    }
  }

  private fun install(args: List<String>, stdin: InputStream, out: ShellOutput): Int {
    val options = Options.parse(args)
    val apk =
      if (options.positional.isEmpty() || options.positional.single() == "-") {
        val size = options.size ?: return out.fail("Error: APK content must be streamed with -S")
        val file = Files.createTempFile(device.config.dir.toPath(), "stream", ".apk").toFile()
        file.outputStream().use { copy(stdin, it, size) }
        file
      } else {
        devicePath(options.positional.first()) ?: return out.failure("INSTALL_FAILED_INVALID_URI")
      }
    return commitApks(listOf(apk), out)
  }

  private fun write(args: List<String>, stdin: InputStream, out: ShellOutput): Int {
    val options = Options.parse(args)
    val id = options.positional.getOrNull(0)?.toIntOrNull()
    val session =
      sessions[id] ?: return out.failure("INSTALL_FAILED_INTERNAL_ERROR", "no session $id")
    val name = options.positional.getOrNull(1) ?: "base.apk"
    val source = options.positional.getOrNull(2)
    val target =
      File(session.dir, File(name).name.let { if (it.endsWith(".apk")) it else "$it.apk" })
    val written =
      if (source == null || source == "-") {
        val size = options.size ?: return out.fail("Error: -S is required to stream")
        target.outputStream().use { copy(stdin, it, size) }
      } else {
        val file = devicePath(source) ?: return out.failure("INSTALL_FAILED_INVALID_URI")
        file.copyTo(target, overwrite = true).length()
      }
    return out.line("Success: streamed $written bytes")
  }

  private fun commit(args: List<String>, out: ShellOutput): Int {
    val id = args.lastOrNull()?.toIntOrNull()
    val session =
      sessions.remove(id) ?: return out.failure("INSTALL_FAILED_INTERNAL_ERROR", "no session $id")
    val apks = session.dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
    try {
      return commitApks(apks, out)
    } finally {
      session.dir.deleteRecursively()
    }
  }

  /**
   * Installs the base of [apks]. Splits (config, ABI, density, dynamic features) are not merged:
   * the simulator runs one APK, so the base is what runs, and a split is reported on stderr.
   */
  private fun commitApks(apks: List<File>, out: ShellOutput): Int {
    if (apks.isEmpty()) return out.failure("INSTALL_FAILED_INVALID_APK", "no APK in the session")
    val base =
      apks.firstOrNull { it.name == "base.apk" }
        ?: apks.firstOrNull { !it.name.startsWith("split") && "config." !in it.name }
        ?: apks.maxBy { it.length() }
    for (split in apks - base) {
      out.err("compose-preview simulator: ignoring split ${split.name}; only the base APK runs")
    }
    return try {
      val installed = device.install(base)
      device.log("installed ${installed.packageName}")
      out.line("Success")
    } catch (e: Exception) {
      out.failure("INSTALL_FAILED_INTERNAL_ERROR", e.message ?: e.toString())
    }
  }

  private fun listPackages(args: List<String>, out: ShellOutput): Int {
    val showPath = "-f" in args
    val thirdParty = "-3" in args
    val system = "-s" in args
    val filter = args.lastOrNull {
      !it.startsWith("-") && it != "0" && args.getOrNull(args.indexOf(it) - 1) != "--user"
    }
    val all = buildList {
      if (!thirdParty) add(VirtualDevice.SYSTEM_PACKAGE to device.config.systemApk.path)
      if (!system)
        device.installed?.let { add(it.packageName to "/data/app/${it.packageName}/base.apk") }
    }
    for ((pkg, path) in all) {
      if (filter != null && !pkg.contains(filter)) continue
      out.line(if (showPath) "package:$path=$pkg" else "package:$pkg")
    }
    return 0
  }

  fun apkOf(pkg: String): String? =
    when {
      device.installed?.packageName == pkg -> "/data/app/$pkg/base.apk"
      pkg == VirtualDevice.SYSTEM_PACKAGE -> "/system/app/Simulator/Simulator.apk"
      else -> null
    }

  fun features(): List<String> = buildList {
    add("android.hardware.bluetooth")
    add("android.hardware.bluetooth_le")
    add("android.hardware.faketouch")
    add("android.hardware.location")
    add("android.hardware.location.gps")
    add("android.hardware.screen.portrait")
    add("android.hardware.touchscreen")
    add("android.hardware.wifi")
    add("android.software.app_widgets")
    if (device.config.formFactor == ee.schimke.composeai.cli.ApkFormFactor.WEAR) {
      add("android.hardware.type.watch")
    } else {
      add("android.hardware.screen.landscape")
      add("android.software.home_screen")
    }
  }

  private fun devicePath(path: String): File? = device.storage.resolve(path)?.takeIf { it.isFile }

  private class Options(val size: Long?, val positional: List<String>) {
    companion object {
      /** `pm install` options that take a value; every other option is a flag. */
      private val WITH_VALUE =
        setOf(
          "-S",
          "-i",
          "-p",
          "--user",
          "--install-reason",
          "--pkg",
          "--abi",
          "--originating-uri",
          "--referrer",
          "--install-location",
          "--force-uuid",
          "--staged-ready-timeout",
          "--update-ownership",
          "--apex-timeout",
        )

      fun parse(args: List<String>): Options {
        var size: Long? = null
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
          val arg = args[i]
          when {
            arg == "-S" -> size = args.getOrNull(++i)?.toLongOrNull()
            arg in WITH_VALUE -> i++
            arg.startsWith("-") && arg != "-" -> Unit
            else -> positional += arg
          }
          i++
        }
        return Options(size, positional)
      }
    }
  }

  private companion object {
    fun copy(input: InputStream, output: java.io.OutputStream, size: Long): Long {
      val buffer = ByteArray(64 * 1024)
      var left = size
      while (left > 0) {
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) break
        output.write(buffer, 0, n)
        left -= n
      }
      return size - left
    }
  }
}
