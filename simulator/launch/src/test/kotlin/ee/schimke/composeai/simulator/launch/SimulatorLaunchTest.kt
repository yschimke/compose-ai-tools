package ee.schimke.composeai.simulator.launch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulatorLaunchTest {
  @Test
  fun `the converter runs from simulator-core, simulator-wear and dex2jar`() {
    val jars =
      listOf(
        File("simulator-host-1.jar"),
        File("simulator-core-1.jar"),
        File("simulator-wearable-1.jar"),
        File("simulator-wear-1.jar"),
      )
    assertEquals(
      listOf("simulator-core-1.jar", "simulator-wear-1.jar", "dex-tools.jar"),
      ApkConverter.converterJars(jars, listOf(File("dex-tools.jar"))).map { it.name },
    )
    assertFailsWith<IllegalStateException> {
      ApkConverter.converterJars(listOf(File("simulator-host-1.jar")), emptyList())
    }
  }

  @Test
  fun `a paired device joins the hub under its node name`() {
    assertEquals(
      mapOf(
        SimulatorJvm.PROPERTY_DATALAYER_HUB to "4100",
        SimulatorJvm.PROPERTY_DATALAYER_NODE to "watch-sim",
        SimulatorJvm.PROPERTY_DATALAYER_NAME to "Simulated Watch",
      ),
      SimulatorJvm.dataLayerProperties(4100, "watch"),
    )
  }

  @Test
  fun `no robolectric, no android-all name`() {
    assertEquals(null, RobolectricAndroidAll.fileName(emptyList(), 35))
    assertEquals(null, RobolectricAndroidAll.fileName(listOf(File("/no/such.jar")), 35))
  }

  /**
   * Off unless `ROBOLECTRIC_SIDECAR_DIR` names a `lib-daemon-android/`: the name its Robolectric
   * gives SDK 35 is an `android-all-instrumented` jar for Android 15.
   */
  @Test
  fun `the sidecar's robolectric names each level's android-all`() {
    val sidecar = System.getenv("ROBOLECTRIC_SIDECAR_DIR")?.let(::File)
    org.junit.Assume.assumeTrue(sidecar != null && sidecar.isDirectory)
    val jars = sidecar!!.listFiles { f -> f.name.endsWith(".jar") }!!.toList()
    val name = RobolectricAndroidAll.fileName(jars, 35)
    kotlin.test.assertTrue(name!!.startsWith("android-all-instrumented-15-robolectric-"), name)
    kotlin.test.assertTrue(name != RobolectricAndroidAll.fileName(jars, 34))
  }

  @Test
  fun `the heap is an eighth of the machine between 768 MB and 2 GB`() {
    assertEquals("768m", SimulatorJvm.defaultMaxHeap(null))
    assertEquals("1024m", SimulatorJvm.defaultMaxHeap(8L shl 30))
    assertEquals("2048m", SimulatorJvm.defaultMaxHeap(64L shl 30))
  }

  @Test
  fun `the simulator JVM's shape, around the caller's properties`() {
    val command =
      SimulatorJvm.command(
        java = File("/jdk/bin/java"),
        classpath = listOf(File("/l/simulator-host-1.jar"), File("/l/robolectric.jar")),
        apk = File("/a.apk"),
        classes = listOf(File("/c.jar")),
        robolectricJvmArgs = listOf("-Dr=1"),
        properties = linkedMapOf("k" to "v"),
        maxHeap = "1g",
      )
    assertEquals(
      listOf(
        "/jdk/bin/java",
        "-Dr=1",
        "-Xmx1g",
        "-XX:G1PeriodicGCInterval=5000",
        "--enable-native-access=ALL-UNNAMED",
        "-javaagent:/l/simulator-host-1.jar",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "-Djava.awt.headless=true",
        "-Dk=v",
        "-cp",
        "/l/simulator-host-1.jar${File.pathSeparator}/l/robolectric.jar",
        SimulatorJvm.LAUNCH_MAIN,
        "/a.apk",
        "/c.jar",
      ),
      command,
    )
  }
}
