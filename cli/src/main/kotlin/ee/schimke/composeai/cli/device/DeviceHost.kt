package ee.schimke.composeai.cli.device

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `launch --adb`: brings a [VirtualDevice] up behind the three doors an emulator has — the adb
 * transport on the odd port, the console on the even one below it, and the gRPC endpoint with its
 * discovery file — and tells a running adb server about it, as the emulator does with
 * `host:emulator:<port>`. A server started later finds it anyway, scanning 5555–5585.
 */
internal class DeviceHost(
  private val device: VirtualDevice,
  private val grpcPort: Int,
  /** A `--control stdio` client: lines it sends go to the simulator, and its lines come back. */
  private val control: Boolean,
  /** An APK given with `--adb`, installed once the device is up as `adb install` would. */
  private val initialApk: java.io.File? = null,
) {

  fun run(): Int {
    val config = device.config
    val adb =
      AdbDeviceServer(
          config.adbPort,
          banner = device::banner,
          services = DeviceServices(device)::resolve,
          log = { device.log("adb: $it") },
        )
        .start()
    val avdDir = AvdFiles.writeAvd(config)
    var discovery: java.io.File? = null
    val console = EmulatorConsole(device, avdDir.absolutePath) { discovery?.absolutePath }.start()
    val token =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    val grpc =
      try {
        EmulatorGrpc(device, grpcPort, token).start()
      } catch (e: IOException) {
        device.log("gRPC: could not listen on $grpcPort: ${e.message}")
        null
      }
    discovery = grpc?.let { AvdFiles.writeDiscovery(config, avdDir, it.port, token) }
    val hook = Thread {
      device.stop()
      discovery?.delete()
    }
    Runtime.getRuntime().addShutdownHook(hook)

    device.start()
    if (control) bridgeStdin()
    System.err.println(
      "compose-preview launch: device ${config.serial} (${config.avdName}): adb on ${config.adbPort}, " +
        "console on ${config.consolePort}" +
        (grpc?.let { ", emulator gRPC on ${it.port}" } ?: "") +
        ". `adb devices` lists it; `adb connect localhost:${config.adbPort}` if it does not."
    )
    registerWithAdbServer(config.adbPort)
    // What a control client (the MCP server's simulator_adb) addresses the device by.
    device.event(
      buildJsonObject {
        put("event", "device")
        put("serial", config.serial)
        put("adbPort", config.adbPort)
        put("consolePort", config.consolePort)
        grpc?.let { put("grpcPort", it.port) }
        put("avd", config.avdName)
        put("formFactor", config.formFactor.id)
      }
        .toString()
    )
    initialApk?.let { apk ->
      Thread {
        runCatching {
          device.simulator()
          device.install(apk)
        }
          .onFailure {
            device.event(
              buildJsonObject {
                put("event", "installFailed")
                put("apk", apk.path)
                put("error", it.message ?: it.toString())
              }
                .toString()
            )
            System.err.println(
              "compose-preview launch: installing ${apk.name} failed: ${it.message}"
            )
          }
      }
        .apply { isDaemon = true }
        .start()
    }

    val exit = device.exit.get()
    adb.close()
    console.close()
    grpc?.close()
    discovery?.delete()
    try {
      Runtime.getRuntime().removeShutdownHook(hook)
    } catch (_: IllegalStateException) {
      // Shutting down already.
    }
    return exit
  }

  private fun bridgeStdin() {
    Thread(
        {
          System.`in`.bufferedReader().useLines { lines ->
            for (line in lines) {
              if (line.isBlank()) continue
              runCatching { device.simulator().forward(line) }
                .onFailure {
                  println("""{"ok":false,"error":"${it.message?.replace("\"", "'")}"}""")
                }
            }
          }
          // Closing stdin ends the session, as it does without --adb.
          device.stop()
        },
        "device-stdin",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  /** `host:emulator:<port>` to the adb server on 5037, if one is running. */
  private fun registerWithAdbServer(adbPort: Int) {
    val port = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037
    runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
        val request = "host:emulator:$adbPort"
        socket
          .getOutputStream()
          .write((String.format("%04x", request.length) + request).toByteArray())
        socket.getOutputStream().flush()
      }
    }
      .onFailure { device.log("no adb server on $port to register with (${it.message})") }
  }

  companion object {
    /**
     * The first console port from 5554 whose pair (console, console + 1) is free, as the emulator
     * picks; adb scans those pairs up to 5585.
     */
    fun freeConsolePort(): Int {
      for (console in 5554..5584 step 2) {
        if (free(console) && free(console + 1) && free(grpcPortFor(console))) return console
      }
      throw IllegalStateException("no free emulator port pair between 5554 and 5585")
    }

    /** The emulator's gRPC ports start at 8554, one per console port. */
    fun grpcPortFor(consolePort: Int): Int = 8554 + (consolePort - 5554)

    /**
     * Whether [port] is free where adb connects to it. Binding alone is not enough: Java sets
     * `SO_REUSEADDR`, under which macOS lets a bind to 127.0.0.1 succeed over another process's
     * wildcard listener — a running emulator's — and the two devices would share a serial.
     */
    fun free(port: Int): Boolean {
      val answers =
        try {
          Socket().use {
            it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200)
          }
          true
        } catch (_: IOException) {
          false
        }
      if (answers) return false
      return try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
        true
      } catch (_: IOException) {
        false
      }
    }
  }
}
