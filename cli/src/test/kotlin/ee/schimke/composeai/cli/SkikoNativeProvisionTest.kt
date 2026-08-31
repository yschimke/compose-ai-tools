package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkikoNativeProvisionTest {
  private val tmp = Files.createTempDirectory("skiko-provision-test-").toFile()

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `platform artifact maps all published hosts`() {
    assertEquals("linux-x64", SkikoNativeProvision.platformArtifact("Linux", "amd64"))
    assertEquals("linux-arm64", SkikoNativeProvision.platformArtifact("Linux", "aarch64"))
    assertEquals("macos-x64", SkikoNativeProvision.platformArtifact("Mac OS X", "x86_64"))
    assertEquals("macos-arm64", SkikoNativeProvision.platformArtifact("Darwin", "arm64"))
    assertEquals("windows-x64", SkikoNativeProvision.platformArtifact("Windows 11", "amd64"))
    assertEquals("windows-arm64", SkikoNativeProvision.platformArtifact("Windows", "aarch64"))
    assertNull(SkikoNativeProvision.platformArtifact("FreeBSD", "amd64"))
  }

  @Test
  fun `version and Maven coordinates derive from shipped API jar`() {
    assertEquals(
      "0.144.6",
      SkikoNativeProvision.skikoVersion(
        listOf(File("skiko-awt-runtime-linux-x64-0.144.6.jar"), File("skiko-awt-0.144.6.jar"))
      ),
    )
    assertEquals(
      "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-linux-arm64/" +
        "0.144.6/skiko-awt-runtime-linux-arm64-0.144.6.jar",
      SkikoNativeProvision.artifactUrl("0.144.6", "linux-arm64"),
    )
    assertEquals(
      File(tmp, "0.144.6/macos-arm64/skiko-awt-runtime-macos-arm64-0.144.6.jar"),
      SkikoNativeProvision.cacheJar(tmp, "0.144.6", "macos-arm64"),
    )
  }

  @Test
  fun `valid cached jar avoids download`() {
    val cached = SkikoNativeProvision.cacheJar(tmp, "1.0", "linux-x64")
    writeNativeJar(cached, "libskiko-linux-x64.so")
    var fetched = false

    val result =
      SkikoNativeProvision.ensureAvailable(
        "1.0",
        "linux-x64",
        null,
        tmp,
        offline = false,
        fetcher = SkikoNativeProvision.Fetcher { _, _ -> fetched = true },
      )

    assertEquals(cached, result)
    assertFalse(fetched)
  }

  @Test
  fun `download is validated and moved into versioned cache`() {
    val result =
      SkikoNativeProvision.ensureAvailable(
        "1.0",
        "windows-arm64",
        null,
        tmp,
        offline = false,
        fetcher =
          SkikoNativeProvision.Fetcher { _, destination ->
            writeNativeJar(destination, "skiko-windows-arm64.dll")
          },
      )

    assertTrue(result.isFile)
    assertTrue(SkikoNativeProvision.isValidNativeJar(result))
    assertTrue(result.parentFile.listFiles()?.none { it.name.endsWith(".tmp") } == true)
  }

  @Test
  fun `explicit directory uses matching jar without download`() {
    val configured = File(tmp, "configured")
    val expected = File(configured, "skiko-awt-runtime-macos-x64-1.0.jar")
    writeNativeJar(expected, "libskiko-macos-x64.dylib")
    var fetched = false

    val result =
      SkikoNativeProvision.ensureAvailable(
        "1.0",
        "macos-x64",
        configured,
        File(tmp, "cache"),
        offline = true,
        fetcher = SkikoNativeProvision.Fetcher { _, _ -> fetched = true },
      )

    assertEquals(expected, result)
    assertFalse(fetched)
  }

  @Test
  fun `explicit directory rejects a corrupt matching jar`() {
    val configured = File(tmp, "configured")
    File(configured, "skiko-awt-runtime-linux-x64-1.0.jar").apply {
      parentFile.mkdirs()
      writeText("not a jar")
    }

    val failure =
      assertFailsWith<IllegalStateException> {
        SkikoNativeProvision.ensureAvailable(
          "1.0",
          "linux-x64",
          configured,
          File(tmp, "cache"),
          offline = true,
          fetcher = SkikoNativeProvision.Fetcher { _, _ -> error("must not fetch") },
        )
      }

    assertTrue(failure.message.orEmpty().contains("does not contain a valid"))
  }

  @Test
  fun `offline miss explains prewarming and override`() {
    val failure =
      assertFailsWith<IllegalStateException> {
        SkikoNativeProvision.ensureAvailable(
          "1.0",
          "linux-x64",
          null,
          tmp,
          offline = true,
          fetcher = SkikoNativeProvision.Fetcher { _, _ -> error("must not fetch") },
        )
      }

    assertTrue(failure.message.orEmpty().contains("offline mode"))
    assertTrue(failure.message.orEmpty().contains("-Dcomposeai.cli.skikoDir=<dir>"))
  }

  @Test
  fun `invalid download leaves no live cache`() {
    val expected = SkikoNativeProvision.cacheJar(tmp, "1.0", "linux-x64")

    val failure =
      assertFailsWith<IllegalStateException> {
        SkikoNativeProvision.ensureAvailable(
          "1.0",
          "linux-x64",
          null,
          tmp,
          offline = false,
          fetcher = SkikoNativeProvision.Fetcher { _, destination -> destination.writeText("bad") },
        )
      }

    assertTrue(failure.message.orEmpty().contains("not a valid native jar"))
    assertFalse(expected.exists())
  }

  private fun writeNativeJar(file: File, entryName: String) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry(entryName))
      zip.write(byteArrayOf(1, 2, 3))
      zip.closeEntry()
    }
  }
}
