package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.DaemonSidecarProvision.Sidecar
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DaemonSidecarProvision] — the CLI fetching the render daemons it launches, which is what lets an
 * installed CLI render after the daemons moved to compose-preview-daemon (#5336).
 *
 * No network: the fetch seam is faked with archives this test builds, so the unpack, the
 * completeness rule and the per-directory swap run on real bytes. The asset names and URL are
 * pinned as literals — they are the contract with compose-preview-daemon's release workflow.
 */
class DaemonSidecarProvisionTest {

  private val tmp = Files.createTempDirectory("daemon-sidecar-test-").toFile()
  private val cacheRoot = File(tmp, "cache")

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `the asset names and urls are the release workflow's`() {
    assertEquals(
      "compose-preview-desktop-daemon-3.0.0.tar.gz",
      DaemonSidecarProvision.assetName("3.0.0", Sidecar.DESKTOP),
    )
    assertEquals(
      "compose-preview-android-daemon-3.0.0.zip",
      DaemonSidecarProvision.assetName("3.0.0", Sidecar.ANDROID),
    )
    assertEquals(
      "https://github.com/yschimke/compose-preview-daemon/releases/download/" +
        "v3.0.0/compose-preview-desktop-daemon-3.0.0.tar.gz",
      DaemonSidecarProvision.assetUrl("3.0.0", Sidecar.DESKTOP),
    )
  }

  @Test
  fun `the environment overrides the pinned version, blank falls back`() {
    assertEquals("9.9.9", DaemonSidecarProvision.version { " 9.9.9 " })
    assertEquals(PREVIEW_DAEMON_VERSION, DaemonSidecarProvision.version { "  " })
    assertEquals(PREVIEW_DAEMON_VERSION, DaemonSidecarProvision.version { null })
  }

  @Test
  fun `the sidecar properties are the ones the lookup reads`() {
    assertEquals(
      "composeai.cli.libDaemonDesktopDir",
      DaemonSidecarProvision.sidecarProperty("lib-daemon-desktop"),
    )
    assertEquals(
      "composeai.cli.libRendererDir",
      DaemonSidecarProvision.sidecarProperty("lib-renderer"),
    )
    assertEquals(
      "composeai.cli.libDaemonAndroidDir",
      DaemonSidecarProvision.sidecarProperty("lib-daemon-android"),
    )
  }

  @Test
  fun `a fetched desktop tarball lands as two sidecar directories`() {
    val log = mutableListOf<String>()

    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.DESKTOP,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = fakeDesktopRelease(),
        log = log::add,
      )

    val resolved = assertNotNull(dir, log.toString())
    assertEquals(File(cacheRoot, "3.0.0").path, resolved.path)
    assertTrue(File(resolved, "lib-daemon-desktop/daemon-desktop-3.0.0.jar").isFile)
    assertTrue(File(resolved, "lib-renderer/renderer-desktop-3.0.0.jar").isFile)
    assertContains(log.joinToString("\n"), "fetching the desktop render daemon 3.0.0")
  }

  @Test
  fun `a fetched android zip lands as its sidecar directory`() {
    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.ANDROID,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = fakeAndroidRelease(),
        log = {},
      )

    val resolved = assertNotNull(dir)
    assertTrue(File(resolved, "lib-daemon-android/0000-daemon-android.jar").isFile)
  }

  /** The two archives share one version directory; fetching one must not disturb the other. */
  @Test
  fun `the desktop and android archives coexist in one version directory`() {
    DaemonSidecarProvision.ensure(
      Sidecar.DESKTOP,
      version = "3.0.0",
      cacheRoot = cacheRoot,
      offline = false,
      fetcher = fakeDesktopRelease(),
      log = {},
    )
    DaemonSidecarProvision.ensure(
      Sidecar.ANDROID,
      version = "3.0.0",
      cacheRoot = cacheRoot,
      offline = false,
      fetcher = fakeAndroidRelease(),
      log = {},
    )

    val dir = File(cacheRoot, "3.0.0")
    assertTrue(DaemonSidecarProvision.isComplete(dir, Sidecar.DESKTOP))
    assertTrue(DaemonSidecarProvision.isComplete(dir, Sidecar.ANDROID))
  }

  @Test
  fun `a cached sidecar is not fetched again`() {
    stageDesktop(File(cacheRoot, "3.0.0"))
    var fetches = 0

    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.DESKTOP,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = { _, _ -> fetches++ },
        log = {},
      )

    assertNotNull(dir)
    assertEquals(0, fetches)
  }

  /** One of the two desktop directories is not a desktop daemon; a torn unpack is re-fetched. */
  @Test
  fun `a half-written cache is re-fetched rather than trusted`() {
    val dir = File(cacheRoot, "3.0.0")
    File(dir, "lib-daemon-desktop").mkdirs()
    File(dir, "lib-daemon-desktop/daemon-desktop-3.0.0.jar").writeText("jar")
    assertFalse(DaemonSidecarProvision.isComplete(dir, Sidecar.DESKTOP))
    var fetches = 0

    val resolved =
      DaemonSidecarProvision.ensure(
        Sidecar.DESKTOP,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = { url, dest ->
          fetches++
          fakeDesktopRelease().fetchTo(url, dest)
        },
        log = {},
      )

    assertNotNull(resolved)
    assertEquals(1, fetches)
    assertTrue(DaemonSidecarProvision.isComplete(dir, Sidecar.DESKTOP))
  }

  @Test
  fun `offline explains itself rather than reaching the network`() {
    val log = mutableListOf<String>()
    var fetches = 0

    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.ANDROID,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = true,
        fetcher = { _, _ -> fetches++ },
        log = log::add,
      )

    assertNull(dir)
    assertEquals(0, fetches)
    assertContains(log.joinToString("\n"), "offline mode is enabled")
    assertContains(log.joinToString("\n"), "compose-preview-android-daemon-3.0.0.zip")
  }

  @Test
  fun `a download failure is reported, not thrown`() {
    val log = mutableListOf<String>()

    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.DESKTOP,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = { _, _ -> error("HTTP 404") },
        log = log::add,
      )

    assertNull(dir)
    assertContains(log.joinToString("\n"), "could not fetch the desktop render daemon")
    assertContains(log.joinToString("\n"), "HTTP 404")
    assertFalse(File(cacheRoot, "3.0.0").exists(), "a failed fetch must leave no version dir")
  }

  @Test
  fun `an archive without the expected layout is refused`() {
    val log = mutableListOf<String>()

    val dir =
      DaemonSidecarProvision.ensure(
        Sidecar.DESKTOP,
        version = "3.0.0",
        cacheRoot = cacheRoot,
        offline = false,
        fetcher = { _, dest ->
          val staging = File(tmp, "wrong")
          File(staging, "lib-something").mkdirs()
          File(staging, "lib-something/x.jar").writeText("jar")
          tarGzOf(staging, dest)
        },
        log = log::add,
      )

    assertNull(dir)
    assertContains(log.joinToString("\n"), "not a compose-preview-desktop-daemon-3.0.0.tar.gz")
  }

  @Test
  fun `a zip entry that escapes the destination is refused`() {
    val zip = File(tmp, "evil.zip")
    ZipOutputStream(zip.outputStream()).use { out ->
      out.putNextEntry(ZipEntry("../escaped.jar"))
      out.write("jar".toByteArray())
      out.closeEntry()
    }

    val failed = runCatching { DaemonSidecarProvision.unpackZip(zip, File(tmp, "out")) }

    assertTrue(failed.isFailure)
    assertFalse(File(tmp, "escaped.jar").exists())
  }

  /** `install` sets the lookup properties only for directories nothing already points at. */
  @Test
  fun `install points the sidecar lookup at the cache without overriding an explicit choice`() {
    val explicit = File(tmp, "my-renderer").apply { mkdirs() }
    File(explicit, "renderer.jar").writeText("jar")
    val properties = Sidecar.DESKTOP.directories.map(DaemonSidecarProvision::sidecarProperty)
    val previous = properties.associateWith { System.getProperty(it) }
    try {
      System.setProperty("composeai.cli.libRendererDir", explicit.absolutePath)
      System.clearProperty("composeai.cli.libDaemonDesktopDir")
      val dir = File(cacheRoot, "3.0.0").also(::stageDesktop)

      val ok = DaemonSidecarProvision.install(Sidecar.DESKTOP, log = {}, provision = { dir })

      assertTrue(ok)
      assertEquals(explicit.absolutePath, System.getProperty("composeai.cli.libRendererDir"))
      assertEquals(
        File(dir, "lib-daemon-desktop").absolutePath,
        System.getProperty("composeai.cli.libDaemonDesktopDir"),
      )
    } finally {
      previous.forEach { (k, v) ->
        if (v == null) System.clearProperty(k) else System.setProperty(k, v)
      }
    }
  }

  private fun fakeDesktopRelease() = DaemonSidecarProvision.Fetcher { _, dest ->
    val staging = File(tmp, "release-desktop")
    staging.deleteRecursively()
    stageDesktop(staging)
    tarGzOf(staging, dest)
  }

  private fun fakeAndroidRelease() = DaemonSidecarProvision.Fetcher { _, dest ->
    dest.parentFile?.mkdirs()
    ZipOutputStream(dest.outputStream()).use { out ->
      out.putNextEntry(ZipEntry("lib-daemon-android/"))
      out.closeEntry()
      out.putNextEntry(ZipEntry("lib-daemon-android/0000-daemon-android.jar"))
      out.write("jar".toByteArray())
      out.closeEntry()
    }
  }

  /** The two halves `isComplete` requires of the desktop archive, each with a jar. */
  private fun stageDesktop(dir: File) {
    File(dir, "lib-daemon-desktop").mkdirs()
    File(dir, "lib-renderer").mkdirs()
    File(dir, "lib-daemon-desktop/daemon-desktop-3.0.0.jar").writeText("jar")
    File(dir, "lib-renderer/renderer-desktop-3.0.0.jar").writeText("jar")
  }

  private fun tarGzOf(contents: File, dest: File) {
    dest.parentFile?.mkdirs()
    val proc =
      ProcessBuilder("tar", "-czf", dest.absolutePath, "-C", contents.absolutePath, ".")
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor() == 0) { "tar failed: $output" }
  }
}
