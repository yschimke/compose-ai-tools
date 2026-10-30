package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.ApkFormFactor
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A simulated device that outlives the app on it: `compose-preview launch --adb`. The device is
 * this process — its adb transport, emulator console and gRPC endpoint — and the simulator JVM is
 * what currently runs on it: the system UI alone at first, then whatever `adb install` put there.
 * An install or uninstall swaps the simulator JVM for one booted on the new package, since a
 * Robolectric sandbox is built around one APK; the adb connection stays up across it, as a device's
 * does across an app's process restart.
 *
 * One app at a time: installing a second package replaces the first.
 */
internal class VirtualDevice(
  val config: Config,
  /** The simulator JVM's command for [apk]: the system APK, or an installed one. */
  private val simulatorCommand: (apk: File) -> List<String>,
  /** Lines the simulator prints that are not this device's replies; a control client's. */
  private val onLine: (String) -> Unit,
  val log: (String) -> Unit,
) {

  data class Config(
    val formFactor: ApkFormFactor,
    val sdk: Int,
    /** The console port; adb's is the next one, as an emulator's are. */
    val consolePort: Int,
    val avdName: String,
    /** The device's own storage: `/data/local/tmp`, `/sdcard`, installed APKs. */
    val dir: File,
    val systemApk: File,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val fingerprint: String? = null,
  ) {
    val adbPort: Int
      get() = consolePort + 1

    val serial: String
      get() = "emulator-$consolePort"
  }

  /** An installed package. */
  data class Installed(val packageName: String, val apk: File)

  private val lock = ReentrantLock()
  @Volatile private var link: SimulatorLink? = null
  @Volatile
  var installed: Installed? = null
    private set

  /** Completes with the exit code when the device should end: its simulator died, or `kill`. */
  val exit = CompletableFuture<Int>()

  val storage = DeviceStorage(config.dir)
  val packages = PackageInstaller(this)
  val shell = DeviceShell(this)
  val logcat = LogcatHub()

  val bootTimeMs: Long = System.currentTimeMillis()

  /**
   * Boots the device: on [apk] already installed when there is one, as `launch --adb app.apk` asks,
   * so it boots once rather than empty and then again for the install. An APK that cannot be
   * installed leaves the device empty, and why is returned; a simulator that cannot start throws.
   */
  fun start(apk: File? = null): Throwable? = lock.withLock {
    val staged = apk?.let { runCatching { stage(it) } }
    val installedApk = staged?.getOrNull()
    installed = installedApk
    boot(installedApk?.apk ?: config.systemApk)
    staged?.exceptionOrNull()
  }

  /** An event line of the device's own, to the control client beside the simulator's. */
  fun event(line: String) = onLine(line)

  /** The running simulator, once it is up; waits out an install's restart. */
  fun simulator(timeoutMs: Long = 180_000): SimulatorLink {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      if (exit.isDone) throw SimulatorLink.SimulatorError("the device has shut down")
      val current = link
      if (current != null) {
        try {
          current.ready.get(maxOf(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
          return current
        } catch (e: java.util.concurrent.ExecutionException) {
          // A simulator replaced while booting is not the device failing.
          if (link === current)
            throw SimulatorLink.SimulatorError(e.cause?.message ?: "boot failed")
        } catch (e: java.util.concurrent.TimeoutException) {
          throw SimulatorLink.SimulatorError("the simulator did not boot in ${timeoutMs}ms")
        }
      }
      if (System.currentTimeMillis() > deadline) {
        throw SimulatorLink.SimulatorError("the simulator did not boot in ${timeoutMs}ms")
      }
      Thread.sleep(100)
    }
  }

  val booted: Boolean
    get() = link?.ready?.isDone == true && link?.ready?.isCompletedExceptionally == false

  /** Installs [apk] (copied into the device first), restarting the simulator on it. */
  fun install(apk: File): Installed {
    val staged = stage(apk)
    lock.withLock {
      installed?.let {
        if (it.packageName != staged.packageName) log("replacing ${it.packageName}")
      }
      installed = staged
      boot(staged.apk)
    }
    simulator()
    onLine("""{"event":"installed","package":"${staged.packageName}"}""")
    return staged
  }

  /** Checks [apk]'s package and copies it to where an installed APK lives on the device. */
  private fun stage(apk: File): Installed {
    val packageName =
      ApkFormFactor.packageOf(apk) ?: throw IllegalArgumentException("not an APK: no manifest")
    if (packageName == SYSTEM_PACKAGE) throw IllegalArgumentException("cannot replace the system")
    // The manifest's package names a directory here, so it must be one, as the platform's
    // package parser requires before anything is written.
    if (!PACKAGE_NAME.matches(packageName)) {
      throw IllegalArgumentException("invalid package name in the manifest: $packageName")
    }
    val apps = File(config.dir, "data/app").canonicalFile
    val target = File(apps, "$packageName/base.apk")
    check(target.canonicalFile.parentFile.parentFile == apps) { "$packageName escapes data/app" }
    target.parentFile.mkdirs()
    if (apk.canonicalFile != target.canonicalFile) apk.copyTo(target, overwrite = true)
    return Installed(packageName, target)
  }

  /** Uninstalls [packageName], back to the system UI alone; false when it is not installed. */
  fun uninstall(packageName: String): Boolean {
    lock.withLock {
      val current = installed ?: return false
      if (current.packageName != packageName) return false
      installed = null
      current.apk.parentFile.deleteRecursively()
      boot(config.systemApk)
    }
    simulator()
    onLine("""{"event":"uninstalled","package":"$packageName"}""")
    return true
  }

  /** `adb reboot`: the simulator starts again on what is installed. */
  fun reboot() {
    lock.withLock { boot(installed?.apk ?: config.systemApk) }
    simulator()
  }

  /**
   * Replaces the running simulator with one booted on [apk]. The old one is dropped from [link]
   * before it is stopped, so only the current simulator ending ends the device.
   */
  private fun boot(apk: File) {
    val old = link
    link = null
    old?.stop()
    val started =
      SimulatorLink(simulatorCommand(apk)) { line -> if (!logcat.offer(line)) onLine(line) }
    link = started
    // A follower outlives the simulator an install replaces: the new one follows for it too.
    if (logcat.active) started.ready.thenRun { followLog(started) }
    started.onExit().thenAccept { process ->
      if (link === started) exit.complete(process.exitValue())
    }
  }

  /**
   * Follows the log for an `adb logcat`, handing each new entry to [listener] until the returned
   * subscription is closed.
   */
  fun followLog(listener: (LogcatEntry) -> Unit): AutoCloseable =
    logcat.subscribe(
      listener,
      arm = { followLog(simulator()) },
      disarm = {
        link
          ?.takeIf { it.alive }
          ?.let { simulator -> runCatching { simulator.request(LogcatHub.STOP) } }
      },
    )

  private fun followLog(simulator: SimulatorLink) {
    runCatching { simulator.request(LogcatHub.FOLLOW) }
      .onFailure { log("logcat: could not follow (${it.message})") }
  }

  fun stop() {
    lock.withLock {
      val old = link
      link = null
      old?.stop()
    }
    exit.complete(0)
  }

  /** `getprop`: what an emulator's system image reports, for this device. */
  fun properties(): Map<String, String> {
    val wear = config.formFactor == ApkFormFactor.WEAR
    val abi = if (System.getProperty("os.arch").contains("aarch64")) "arm64-v8a" else "x86_64"
    val model = if (wear) "Compose Preview Watch" else "Compose Preview Simulator"
    return linkedMapOf(
      "dev.bootcomplete" to (if (booted) "1" else "0"),
      "init.svc.bootanim" to (if (booted) "stopped" else "running"),
      "persist.sys.locale" to "en-US",
      "persist.sys.timezone" to java.util.TimeZone.getDefault().id,
      "ro.adb.secure" to "0",
      "ro.boot.qemu" to "1",
      "ro.boot.qemu.avd_name" to config.avdName,
      "ro.build.characteristics" to (if (wear) "nosdcard,watch" else "emulator"),
      "ro.build.fingerprint" to
        (config.fingerprint
          ?: "composeai/simulator/simulator:${release()}/SIM/1:userdebug/dev-keys"),
      "ro.build.id" to "SIM",
      "ro.build.tags" to "dev-keys",
      "ro.build.type" to "userdebug",
      "ro.build.version.codename" to "REL",
      "ro.build.version.release" to release(),
      "ro.build.version.sdk" to config.sdk.toString(),
      "ro.debuggable" to "1",
      "ro.kernel.qemu" to "1",
      "ro.kernel.qemu.avd_name" to config.avdName,
      "ro.product.brand" to "composeai",
      "ro.product.cpu.abi" to abi,
      "ro.product.cpu.abilist" to
        "$abi,arm64-v8a,x86_64,x86,armeabi-v7a,armeabi".split(',').distinct().joinToString(","),
      "ro.product.device" to "simulator",
      "ro.product.locale" to "en-US",
      "ro.product.manufacturer" to "Compose Preview",
      "ro.product.model" to model,
      "ro.product.name" to (if (wear) "simulator_wear" else "simulator_phone"),
      "ro.secure" to "1",
      "ro.serialno" to config.serial,
      "ro.sf.lcd_density" to config.densityDpi.toString(),
      "service.bootanim.exit" to (if (booted) "1" else "0"),
      "sys.boot_completed" to (if (booted) "1" else "0"),
    )
  }

  /** The `CNXN` banner: identity, then the features this device's services implement. */
  fun banner(): String {
    val p = properties()
    return "device::ro.product.name=${p["ro.product.name"]};" +
      "ro.product.model=${p["ro.product.model"]};ro.product.device=${p["ro.product.device"]};" +
      "features=${FEATURES.joinToString(",")}"
  }

  private fun release(): String = RELEASES[config.sdk] ?: config.sdk.toString()

  companion object {
    /**
     * The generated package the device boots with nothing installed
     * ([ee.schimke.composeai.cli.SystemApk]).
     */
    const val SYSTEM_PACKAGE = ee.schimke.composeai.cli.SystemApk.PACKAGE

    /**
     * What `adb` may use: the v2 shell (exit codes, separate stderr), `exec:cmd` and `abb_exec:`
     * for streamed installs, and `push` creating directories. Not `stat_v2`/`ls_v2`, so the sync
     * protocol stays at `STAT`/`LIST`.
     */
    /** A Java package name of two or more segments, as `PackageParser` validates one. */
    val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    val FEATURES = listOf("shell_v2", "cmd", "abb_exec", "fixed_push_mkdir")

    private val RELEASES =
      mapOf(
        30 to "11",
        31 to "12",
        32 to "12",
        33 to "13",
        34 to "14",
        35 to "15",
        36 to "16",
        37 to "17",
      )
  }
}
