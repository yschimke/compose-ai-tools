package ee.schimke.composeai.cli

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

class DataLayerHubTest {

  private fun send(out: DataOutputStream, json: String) {
    val bytes = json.toByteArray()
    out.writeInt(bytes.size)
    out.write(bytes)
    out.flush()
  }

  private fun read(input: DataInputStream): String {
    val bytes = ByteArray(input.readInt())
    input.readFully(bytes)
    return bytes.decodeToString()
  }

  @Test
  fun `a bye names the app that left, as a node can have several`() {
    DataLayerHub().start().use { hub ->
      val watch = Socket(InetAddress.getLoopbackAddress(), hub.port)
      val phoneApp = Socket(InetAddress.getLoopbackAddress(), hub.port)
      val watchIn = DataInputStream(watch.getInputStream())
      // Both connected before the hello, so the watch hears it and later the bye.
      while (hub.connected < 2) Thread.sleep(10)
      send(
        DataOutputStream(phoneApp.getOutputStream()),
        """{"type":"hello","from":"phone-sim","package":"com.a"}""",
      )
      assertEquals("""{"type":"hello","from":"phone-sim","package":"com.a"}""", read(watchIn))
      phoneApp.close()
      assertEquals("""{"type":"bye","from":"phone-sim","package":"com.a"}""", read(watchIn))
      watch.close()
    }
  }

  @Test
  fun `a hello reads its node and package`() {
    val hello = """{"type":"hello","from":"watch-sim","package":"com.w"}""".toByteArray()
    assertEquals("watch-sim", DataLayerHub.helloFrom(hello))
    assertEquals("com.w", DataLayerHub.helloPackage(hello))
    assertEquals(null, DataLayerHub.helloPackage("""{"type":"message"}""".toByteArray()))
  }
}
