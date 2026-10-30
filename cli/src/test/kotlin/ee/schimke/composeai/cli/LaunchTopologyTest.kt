package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchTopologyTest {

  private fun app(device: String, name: String) =
    LaunchTopology.App(device, File("/apks/$name.apk"))

  @Test
  fun `an app spec is a device and an apk`() {
    val parsed = LaunchTopology.parse("watch:/tmp/w.apk")
    assertEquals("watch", parsed.device)
    assertEquals(File("/tmp/w.apk"), parsed.apk)
    assertFailsWith<IllegalArgumentException> { LaunchTopology.parse("/tmp/w.apk") }
    assertFailsWith<IllegalArgumentException> { LaunchTopology.parse("Phone:/tmp/w.apk") }
  }

  @Test
  fun `watch names are watches`() {
    assertTrue(LaunchTopology.isWatch("watch"))
    assertTrue(LaunchTopology.isWatch("watch-2"))
    assertFalse(LaunchTopology.isWatch("phone"))
    assertFalse(LaunchTopology.isWatch("watchtower"))
  }

  @Test
  fun `a pair keeps its phone and watch names`() {
    val apps = listOf(app("phone", "a"), app("watch", "w"))
    val (names, aliases) = LaunchTopology.names(apps) { it.nameWithoutExtension }
    assertEquals(listOf("phone", "watch"), names)
    assertEquals(mapOf("a" to "phone", "w" to "watch"), aliases)
  }

  @Test
  fun `apps sharing a device are named by package, the device naming the first`() {
    val apps = listOf(app("phone", "a"), app("phone", "b"), app("watch", "a"), app("watch", "c"))
    val (names, aliases) = LaunchTopology.names(apps) { it.nameWithoutExtension }
    assertEquals(listOf("phone/a", "phone/b", "watch/a", "watch/c"), names)
    assertEquals("phone/a", aliases["phone"])
    assertEquals("watch/a", aliases["watch"])
    // b and c are on one device each, so their package alone names them; a is on both.
    assertEquals("phone/b", aliases["b"])
    assertEquals("watch/c", aliases["c"])
    assertFalse("a" in aliases)
    assertTrue(LaunchTopology.isFirstOnDevice(apps, apps[2]))
    assertFalse(LaunchTopology.isFirstOnDevice(apps, apps[3]))
    assertEquals(listOf(apps[0]), LaunchTopology.deviceMates(apps, apps[1]))
    assertEquals(listOf("phone", "watch"), LaunchTopology.devices(apps))
  }

  @Test
  fun `one package twice on a device is refused`() {
    assertFailsWith<IllegalArgumentException> {
      LaunchTopology.names(listOf(app("phone", "a"), app("phone", "a"))) { it.nameWithoutExtension }
    }
  }

  @Test
  fun `a device's name follows the app on its screen`() {
    val apps = listOf(app("phone", "a"), app("phone", "b"), app("watch", "w"))
    val (names, aliases) = LaunchTopology.names(apps) { it.nameWithoutExtension }
    val screens = LaunchTopology.Screens(apps, names, aliases)
    assertEquals("phone/a", screens.aliases["phone"])
    screens.follow("""{"device":"phone/b","package":"b","onScreen":true,"event":"foreground"}""")
    assertEquals("phone/b", screens.aliases["phone"])
    // The app leaving the screen moves nothing; the one coming forward does.
    screens.follow("""{"device":"phone/a","package":"a","onScreen":false,"event":"foreground"}""")
    assertEquals("phone/b", screens.aliases["phone"])
    // An app alone on its device is its device already.
    screens.follow("""{"device":"watch","package":"w","onScreen":true,"event":"foreground"}""")
    assertFalse("watch" in screens.aliases)
    screens.follow("""{"device":"phone/a","id":3,"ok":true,"result":{"onScreen":true}}""")
    assertEquals("phone/b", screens.aliases["phone"])
  }
}
