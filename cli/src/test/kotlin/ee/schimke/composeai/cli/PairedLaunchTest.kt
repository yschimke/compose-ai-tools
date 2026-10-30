package ee.schimke.composeai.cli

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairedLaunchTest {

  @Test
  fun `each simulator line carries its device`() {
    assertEquals(
      """{"device":"watch","event":"ready"}""",
      PairedLaunch.tag("""{"event":"ready"}""", "watch"),
    )
    assertEquals("""{"device":"phone"}""", PairedLaunch.tag("{}", "phone"))
    assertEquals("not json", PairedLaunch.tag("not json", "phone"))
  }

  @Test
  fun `a command names its device, or none`() {
    assertEquals("watch", PairedLaunch.deviceOf("""{"cmd":"info","device":"watch"}"""))
    assertNull(PairedLaunch.deviceOf("""{"cmd":"info"}"""))
    assertNull(PairedLaunch.deviceOf("garbage"))
  }

  @Test
  fun `the paired simulators get the hub, their node and a watch display`() {
    val options =
      LaunchCommand.Options(
        sdk = 35,
        qualifiers = LaunchCommand.DEFAULT_WATCH_QUALIFIERS,
        fontScale = null,
        headless = true,
        control = true,
        installProviders = true,
        dataLayer = LaunchCommand.DataLayerNode(4321, LaunchCommand.WATCH),
      )
    val command =
      LaunchCommand.simulatorCommand(
        java = java.io.File("java"),
        classpath = emptyList(),
        apk = java.io.File("wear.apk"),
        classes = emptyList(),
        options = options,
        robolectricProperties = emptyMap(),
        robolectricJvmArgs = emptyList(),
      )
    assert("-Dcomposeai.simulator.datalayer.hub=4321" in command)
    assert("-Dcomposeai.simulator.datalayer.node=watch-sim" in command)
    assert("-Dcomposeai.simulator.datalayer.name=Simulated Watch" in command)
    assert("-Dcomposeai.simulator.qualifiers=w227dp-h227dp-round-watch-xhdpi" in command)
  }

  @Test
  fun `the hub relays a frame to the other node and says bye when one leaves`() {
    DataLayerHub().start().use { hub ->
      val phone = Socket(InetAddress.getLoopbackAddress(), hub.port)
      val watch = Socket(InetAddress.getLoopbackAddress(), hub.port)
      watch.soTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while (hub.connected < 2 && System.nanoTime() < deadline) Thread.onSpinWait()
      val hello = """{"type":"hello","from":"phone-sim"}"""
      DataOutputStream(phone.getOutputStream()).apply {
        writeInt(hello.length)
        write(hello.toByteArray())
        flush()
      }
      val input = DataInputStream(watch.getInputStream())
      fun next(): String = ByteArray(input.readInt()).also { input.readFully(it) }.decodeToString()

      assertEquals(hello, next())
      phone.close()
      assertEquals("""{"type":"bye","from":"phone-sim"}""", next())
      watch.close()
    }
  }
}
