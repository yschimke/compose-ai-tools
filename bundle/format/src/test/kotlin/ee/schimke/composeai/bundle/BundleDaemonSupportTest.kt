package ee.schimke.composeai.bundle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BundleDaemonSupportTest {
  private val tmp = Files.createTempDirectory("bundle-sidecar-test-").toFile()
  private val previousDaemon = System.getProperty("composeai.cli.libDaemonDesktopDir")
  private val previousAndroid = System.getProperty("composeai.cli.libDaemonAndroidDir")
  private val previousSkiko = System.getProperty("composeai.cli.skikoDir")
  private val previousProvisioner = System.getProperty("composeai.cli.skikoProvisionerClass")

  @AfterTest
  fun cleanup() {
    restoreProperty("composeai.cli.libDaemonDesktopDir", previousDaemon)
    restoreProperty("composeai.cli.libDaemonAndroidDir", previousAndroid)
    restoreProperty("composeai.cli.skikoDir", previousSkiko)
    restoreProperty("composeai.cli.skikoProvisionerClass", previousProvisioner)
    FakeDesktopNativeProvisioner.reset()
    tmp.deleteRecursively()
  }

  @Test
  fun `desktop daemon locator prepends provisioned Skiko jar`() {
    val daemonDir = tmp.resolve("lib-daemon-desktop").apply { mkdirs() }
    val daemonJar = daemonDir.resolve("daemon.jar").apply { writeBytes(byteArrayOf()) }
    val skikoDir = tmp.resolve("skiko").apply { mkdirs() }
    val skikoJar =
      skikoDir.resolve("skiko-awt-runtime-linux-x64-1.0.jar").apply { writeBytes(byteArrayOf()) }
    System.setProperty("composeai.cli.libDaemonDesktopDir", daemonDir.absolutePath)
    System.setProperty("composeai.cli.skikoDir", skikoDir.absolutePath)

    assertEquals(listOf(skikoJar, daemonJar), locateBundleSidecarJars("lib-daemon-desktop"))
  }

  @Test
  fun `desktop provisioner is lazy and limited to desktop daemon lookup`() {
    val daemonDir = tmp.resolve("lib-daemon-desktop").apply { mkdirs() }
    val daemonJar = daemonDir.resolve("daemon.jar").apply { writeBytes(byteArrayOf()) }
    val androidDir = tmp.resolve("lib-daemon-android").apply { mkdirs() }
    val androidJar = androidDir.resolve("android.jar").apply { writeBytes(byteArrayOf()) }
    val skikoDir = tmp.resolve("skiko").apply { mkdirs() }
    val skikoJar =
      skikoDir.resolve("skiko-awt-runtime-linux-x64-1.0.jar").apply { writeBytes(byteArrayOf()) }
    System.setProperty("composeai.cli.libDaemonDesktopDir", daemonDir.absolutePath)
    System.setProperty("composeai.cli.libDaemonAndroidDir", androidDir.absolutePath)
    FakeDesktopNativeProvisioner.result = skikoJar
    System.setProperty(
      "composeai.cli.skikoProvisionerClass",
      FakeDesktopNativeProvisioner::class.java.name,
    )

    assertEquals(listOf(androidJar), locateBundleSidecarJars("lib-daemon-android"))
    assertEquals(0, FakeDesktopNativeProvisioner.calls)
    assertEquals(listOf(skikoJar, daemonJar), locateBundleSidecarJars("lib-daemon-desktop"))
    assertEquals(1, FakeDesktopNativeProvisioner.calls)
    assertEquals(listOf(skikoJar, daemonJar), locateBundleSidecarJars("lib-daemon-desktop"))
    assertEquals(2, FakeDesktopNativeProvisioner.calls)
  }

  @Test
  fun `configured native does not hide an empty desktop daemon sidecar`() {
    val daemonDir = tmp.resolve("lib-daemon-desktop").apply { mkdirs() }
    val skikoDir = tmp.resolve("skiko").apply { mkdirs() }
    skikoDir.resolve("skiko-awt-runtime-linux-x64-1.0.jar").writeBytes(byteArrayOf(1))
    System.setProperty("composeai.cli.libDaemonDesktopDir", daemonDir.absolutePath)
    System.setProperty("composeai.cli.skikoDir", skikoDir.absolutePath)

    assertEquals(emptyList(), locateBundleSidecarJars("lib-daemon-desktop"))
  }

  private fun restoreProperty(name: String, value: String?) {
    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
  }
}

object FakeDesktopNativeProvisioner {
  var calls: Int = 0
  var result: File? = null

  @JvmStatic
  fun provisionSkikoNativeForDesktopSidecar(sidecarJars: List<File>): File? {
    assertEquals(1, sidecarJars.size)
    calls++
    return result
  }

  fun reset() {
    calls = 0
    result = null
  }
}
