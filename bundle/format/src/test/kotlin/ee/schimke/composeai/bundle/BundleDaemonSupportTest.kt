package ee.schimke.composeai.bundle

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BundleDaemonSupportTest {
  private val tmp = Files.createTempDirectory("bundle-sidecar-test-").toFile()
  private val previousDaemon = System.getProperty("composeai.cli.libDaemonDesktopDir")
  private val previousSkiko = System.getProperty(CLI_SKIKO_DIR_PROPERTY)

  @AfterTest
  fun cleanup() {
    restoreProperty("composeai.cli.libDaemonDesktopDir", previousDaemon)
    restoreProperty(CLI_SKIKO_DIR_PROPERTY, previousSkiko)
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
    System.setProperty(CLI_SKIKO_DIR_PROPERTY, skikoDir.absolutePath)

    assertEquals(listOf(skikoJar, daemonJar), locateBundleSidecarJars("lib-daemon-desktop"))
  }

  private fun restoreProperty(name: String, value: String?) {
    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
  }
}
