package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.ApkFormFactor
import ee.schimke.composeai.cli.LaunchCommand
import ee.schimke.composeai.cli.ManifestSignals
import ee.schimke.composeai.cli.SystemApk
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class VirtualDeviceTest {

  private fun temp(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("device").toFile()
    try {
      block(dir)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the system APK's manifest reads back as the package and form factor`() {
    val wear = ManifestSignals.read(SystemApk.manifest(ApkFormFactor.WEAR, 35))
    assertEquals(SystemApk.PACKAGE, wear.packageName)
    assertEquals(setOf("android.hardware.type.watch"), wear.requiredFeatures)
    val phone = ManifestSignals.read(SystemApk.manifest(ApkFormFactor.PHONE, 35))
    assertEquals(SystemApk.PACKAGE, phone.packageName)
    assertTrue(phone.features.isEmpty())
  }

  @Test
  fun `the system APK is written once and detected as its form factor`() = temp { dir ->
    val apk = SystemApk.ensure(dir, ApkFormFactor.WEAR, 35)
    assertEquals(apk, SystemApk.ensure(dir, ApkFormFactor.WEAR, 35))
    assertEquals(ApkFormFactor.WEAR, ApkFormFactor.detect(apk))
    assertEquals(SystemApk.PACKAGE, ApkFormFactor.packageOf(apk))
  }

  @Test
  fun `shell lines split into sequences, filters and redirections`() {
    val steps =
      ShellSyntax.parse("""getprop ro.x; echo 'a b' && false || echo "c\"d" | grep c 2>/dev/null""")
    assertEquals(4, steps.size)
    assertEquals(listOf(listOf("getprop", "ro.x")), steps[0].commands)
    assertEquals(listOf(listOf("echo", "a b")), steps[1].commands)
    assertEquals(ShellSyntax.Joiner.AND, steps[2].after)
    assertEquals(ShellSyntax.Joiner.OR, steps[3].after)
    assertEquals(listOf(listOf("echo", "c\"d"), listOf("grep", "c")), steps[3].commands)
    assertTrue(steps[3].discardErrors)

    val redirected = ShellSyntax.parse("screencap -p > /sdcard/s.png").single()
    assertEquals(listOf(listOf("screencap", "-p")), redirected.commands)
    assertEquals("/sdcard/s.png", redirected.redirect)
    assertEquals(
      listOf("pm", "install", "it's"),
      ShellSyntax.parse("""pm install 'it'\''s'""").single().commands.single(),
    )
  }

  @Test
  fun `device paths resolve into the device's own areas only`() = temp { dir ->
    val storage = DeviceStorage(dir)
    assertEquals(File(dir, "data/local/tmp/a.apk"), storage.resolve("/data/local/tmp/./x/../a.apk"))
    assertEquals(File(dir, "sdcard/Download"), storage.resolve("/storage/emulated/0/Download"))
    assertNull(storage.resolve("/data/data/com.example/files"))
    assertNull(storage.resolve("/data/local/tmp/../../system/bin"))
    assertTrue(storage.isSystemDirectory("/data/local/"))
  }

  @Test
  fun `simulator answers print as a device's commands do`() {
    fun obj(vararg pairs: Pair<String, Any>) =
      JsonObject(
        pairs.associate { (k, v) -> k to (v as? JsonObject ?: JsonPrimitive(v.toString())) }
      )
    assertEquals(
      listOf("Starting: Intent { cmp=com.example/.Main }"),
      AdbText.render(
        listOf("am", "start"),
        obj("cmd" to "startActivity", "intent" to obj("component" to "com.example/.Main")),
        JsonObject(emptyMap()),
      ),
    )
    assertEquals(
      listOf("Broadcast completed: result=1, data=\"Index=[2]\""),
      AdbText.render(
        listOf("am", "broadcast"),
        obj("cmd" to "debugSurface"),
        JsonObject(mapOf("index" to JsonPrimitive(2))),
      ),
    )
    assertEquals(
      listOf("Physical size: 454x454"),
      AdbText.render(
        listOf("wm", "size"),
        obj("cmd" to "display"),
        JsonObject(mapOf("width" to JsonPrimitive(454), "height" to JsonPrimitive(454))),
      ),
    )
    assertEquals(
      listOf("1"),
      AdbText.render(listOf("settings", "get"), obj("cmd" to "settings"), obj("output" to "1")),
    )
    assertEquals(
      "the app has no tile .X",
      AdbText.error(
        "java.lang.IllegalArgumentException: the app has no tile .X at ee.schimke.composeai.simulator.TileHost.find(TileHost.java:435)"
      ),
    )
  }

  @Test
  fun `display pixels come from the qualifiers`() {
    assertEquals(Triple(454, 454, 320), LaunchCommand.displayPixels(ApkFormFactor.WEAR.qualifiers))
    assertEquals(Triple(540, 960, 240), LaunchCommand.displayPixels(ApkFormFactor.PHONE.qualifiers))
    assertEquals(Triple(840, 1680, 420), LaunchCommand.displayPixels("w320dp-h640dp-420dpi"))
  }

  @Test
  fun `the discovery file names the ports and token Studio connects with`() = temp { dir ->
    val config =
      VirtualDevice.Config(
        formFactor = ApkFormFactor.WEAR,
        sdk = 35,
        consolePort = 5556,
        avdName = "Compose_Preview_Watch",
        dir = dir,
        systemApk = File(dir, "system.apk"),
        widthPx = 454,
        heightPx = 454,
        densityDpi = 320,
      )
    val avd = AvdFiles.writeAvd(config)
    val ini = File(avd, "config.ini").readText()
    assertTrue("tag.id=android-wear" in ini, ini)
    assertTrue("hw.lcd.width=454" in ini, ini)
    val discovery = AvdFiles.discovery(config, avd, 8556, "t0k3n").toMap()
    assertEquals("5556", discovery["port.serial"])
    assertEquals("5557", discovery["port.adb"])
    assertEquals("8556", discovery["grpc.port"])
    assertEquals("t0k3n", discovery["grpc.token"])
    assertTrue("-qt-hide-window" in discovery.getValue("cmdline"))
    assertEquals("emulator-5556", config.serial)
  }

  @Test
  fun `the adb transport connects and runs a service on an opened stream`() {
    val server =
      AdbDeviceServer(
          port = 0.let { java.net.ServerSocket(0).use { it.localPort } },
          banner = { "device::ro.product.model=Test;features=shell_v2" },
          services = { name ->
            if (name.startsWith("shell:"))
              AdbService { it.write("ran " + name.removePrefix("shell:")) }
            else null
          },
        )
        .start()
    server.use {
      Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
        val input = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        AdbWire.write(
          out,
          AdbWire.Message(AdbWire.A_CNXN, AdbWire.VERSION, 4096, "host::\u0000".toByteArray()),
        )
        val cnxn = AdbWire.read(input)
        assertEquals(AdbWire.A_CNXN, cnxn.command)
        assertTrue(String(cnxn.data).startsWith("device::ro.product.model=Test"))

        AdbWire.write(
          out,
          AdbWire.Message(AdbWire.A_OPEN, 7, 0, "shell:echo hi\u0000".toByteArray()),
        )
        val okay = AdbWire.read(input)
        assertEquals(AdbWire.A_OKAY, okay.command)
        assertEquals(7, okay.arg1)
        val collected = ByteArrayOutputStream()
        while (true) {
          val message = AdbWire.read(input)
          if (message.command == AdbWire.A_CLSE) break
          assertEquals(AdbWire.A_WRTE, message.command)
          collected.write(message.data)
          AdbWire.write(out, AdbWire.Message(AdbWire.A_OKAY, 7, message.arg0))
        }
        assertEquals("ran echo hi", collected.toString())

        // A service the device does not serve is closed at once.
        AdbWire.write(out, AdbWire.Message(AdbWire.A_OPEN, 8, 0, "jdwp:123\u0000".toByteArray()))
        val refused = AdbWire.read(input)
        assertEquals(AdbWire.A_CLSE, refused.command)
        assertEquals(8, refused.arg1)
      }
    }
  }
}
