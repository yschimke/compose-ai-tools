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

/** [HostNativesProvision], with the fetch seam faked: no network. */
class HostNativesProvisionTest {
  private val tmp = Files.createTempDirectory("host-natives-test-").toFile()
  private val env: (String) -> String? = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null }

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `platform token maps host-natives yml's build matrix`() {
    assertEquals("linux-x86_64", HostNativesProvision.platformToken("Linux", "amd64"))
    assertEquals("linux-x86_64", HostNativesProvision.platformToken("Linux", "x86_64"))
    // One universal dylib serves both Mac architectures.
    assertEquals("macos-universal", HostNativesProvision.platformToken("Mac OS X", "aarch64"))
    assertEquals("macos-universal", HostNativesProvision.platformToken("Mac OS X", "x86_64"))
    assertNull(HostNativesProvision.platformToken("Linux", "aarch64"))
    assertNull(HostNativesProvision.platformToken("Windows 11", "amd64"))
  }

  @Test
  fun `asset url is the host-natives prerelease of this repository`() {
    // Spelled out: this literal is the contract with host-natives.yml's publish job.
    assertEquals(
      "https://github.com/yschimke/compose-ai-tools/releases/download/" +
        "host-natives-v1.0.0/host-natives-macos-universal-1.0.0.tar.gz",
      HostNativesProvision.assetUrl("1.0.0", "macos-universal"),
    )
    assertEquals(
      File("/home/u/.cache/composeai/host-natives/1.0.0/linux-x86_64"),
      HostNativesProvision.cacheDir("1.0.0", "linux-x86_64", env = { null }, userHome = "/home/u"),
    )
  }

  private fun apk(vararg entries: Pair<String, String>): File {
    val file = File(tmp, "app-${entries.size}-${System.nanoTime()}.apk")
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return file
  }

  @Test
  fun `an APK carrying graphics-path 1_0_1 needs the host build at API 34 and up`() {
    val app =
      apk(
        "lib/arm64-v8a/libandroidx.graphics.path.so" to "",
        HostNativesProvision.GRAPHICS_PATH_VERSION_FILE to "1.0.1\n",
      )
    assertEquals(setOf("androidx.graphics.path"), HostNativesProvision.neededBy(app))
    assertTrue(HostNativesProvision.wanted(app, 34))
    assertTrue(HostNativesProvision.wanted(app, 36))
    // Below 34 graphics-path reaches the Skia-layout code the host build does not serve.
    assertFalse(HostNativesProvision.wanted(app, 33))
  }

  @Test
  fun `an unverified graphics-path version, or none carried, needs nothing`() {
    val newer =
      apk(
        "lib/x86_64/libandroidx.graphics.path.so" to "",
        HostNativesProvision.GRAPHICS_PATH_VERSION_FILE to "1.1.0",
      )
    assertEquals(emptySet(), HostNativesProvision.neededBy(newer))
    assertEquals(emptySet(), HostNativesProvision.neededBy(apk("lib/x86_64/libother.so" to "")))
    // No version marker: the library is still the only build that loads at all.
    assertEquals(
      setOf("androidx.graphics.path"),
      HostNativesProvision.neededBy(apk("lib/x86_64/libandroidx.graphics.path.so" to "")),
    )
    assertEquals(emptySet(), HostNativesProvision.neededBy(File(tmp, "missing.apk")))
  }

  private fun makeTarball(dest: File, withLibrary: Boolean = true) {
    val staging = File(tmp, "stage-${System.nanoTime()}").apply { mkdirs() }
    File(staging, HostNativesProvision.PROPERTIES)
      .writeText(
        "version=1.0.0\nplatform=linux-x86_64\ngraphics-path.library=libandroidx.graphics.path.so\n"
      )
    if (withLibrary) File(staging, "libandroidx.graphics.path.so").writeText("elf")
    val p =
      ProcessBuilder("tar", "-czf", dest.absolutePath, "-C", staging.absolutePath, ".")
        .redirectErrorStream(true)
        .start()
    check(p.waitFor() == 0) { "tar pack failed" }
  }

  @Test
  fun `ensureCached unpacks once and then serves the cache`() {
    val urls = mutableListOf<String>()
    val fetcher = HostNativesProvision.Fetcher { url, dest ->
      urls += url
      makeTarball(dest)
    }
    fun provision() =
      HostNativesProvision.ensureCached(
        "1.0.0",
        fetcher,
        env = env,
        log = {},
        noNetwork = false,
        platform = "linux-x86_64",
      )
    val dir = provision()
    assertEquals(File(tmp, "composeai/host-natives/1.0.0/linux-x86_64"), dir)
    assertTrue(File(dir, "libandroidx.graphics.path.so").isFile)
    assertEquals(dir, provision())
    assertEquals(1, urls.size)
  }

  @Test
  fun `an incomplete tarball or a failed fetch skips`() {
    val logs = mutableListOf<String>()
    assertNull(
      HostNativesProvision.ensureCached(
        "1.0.0",
        { _, dest -> makeTarball(dest, withLibrary = false) },
        env = env,
        log = { logs += it },
        noNetwork = false,
        platform = "linux-x86_64",
      )
    )
    assertNull(
      HostNativesProvision.ensureCached(
        "1.0.0",
        { _, _ -> error("HTTP 404") },
        env = env,
        log = { logs += it },
        noNetwork = false,
        platform = "linux-x86_64",
      )
    )
    assertTrue(logs.last().contains("HTTP 404"), logs.toString())
    assertFalse(File(tmp, "composeai/host-natives/1.0.0/linux-x86_64").exists())
  }

  @Test
  fun `the network switch refuses an uncached copy`() {
    assertFailsWith<NetworkDisabledException> {
      HostNativesProvision.ensureCached(
        "1.0.0",
        { _, _ -> error("must not fetch") },
        env = env,
        log = {},
        noNetwork = true,
        platform = "linux-x86_64",
      )
    }
  }
}
