package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.simulator.SimulatorHostEnvironment
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class SimulatorCliEnvironmentTest {
  @Test
  fun `SimulatorHostEnvironment discover resolves the CLI provider on the CLI classpath`() {
    val discovered = SimulatorHostEnvironment.discover()
    assertNotSame(
      SimulatorHostEnvironment.STANDALONE,
      discovered,
      "Expected ServiceLoader to discover CliSimulatorHostEnvironmentProvider",
    )
    val platform =
      SkikoNativeProvision.platformArtifact(
        System.getProperty("os.name") ?: "",
        System.getProperty("os.arch") ?: "",
      ) ?: return
    val tempDir = Files.createTempDirectory("skiko-discover-test").toFile()
    try {
      val fakeSkikoJar = File(tempDir, SkikoNativeProvision.jarName("0.9.4", platform))
      ZipOutputStream(fakeSkikoJar.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("libskiko-test.so"))
        zip.write(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        zip.closeEntry()
      }
      val previous = System.getProperty("composeai.cli.skikoDir")
      System.setProperty("composeai.cli.skikoDir", tempDir.absolutePath)
      try {
        val resolved =
          discovered.prepareSkikoNative(
            listOf(File(tempDir, "skiko-awt-0.9.4.jar").apply { writeBytes(byteArrayOf(0)) }),
          )
        assertEquals(fakeSkikoJar.canonicalFile, resolved?.canonicalFile)
      } finally {
        if (previous == null) System.clearProperty("composeai.cli.skikoDir")
        else System.setProperty("composeai.cli.skikoDir", previous)
      }
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
