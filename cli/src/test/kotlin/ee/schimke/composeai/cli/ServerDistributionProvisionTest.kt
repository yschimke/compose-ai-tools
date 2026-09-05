package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServerDistributionProvision] — the CLI fetching the server `serve` launches, which is what makes
 * a documented install able to serve at all (#5183).
 *
 * No network: the fetch seam is faked with a tarball this test builds, so the unpack, the
 * completeness rule and the staging swap are exercised on real bytes rather than mocked away. The
 * URL and asset name are pinned as literals — they are the contract with compose-preview-server's
 * release workflow, and a test that derived them from the same constants the code derives them from
 * would pass through a repointing, including a wrong one.
 */
class ServerDistributionProvisionTest {

  private val tmp = Files.createTempDirectory("server-provision-test-").toFile()
  private val cacheRoot = File(tmp, "cache")

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `the asset name and url are the release workflow's`() {
    assertEquals(
      "compose-preview-server-3.0.0.tar.gz",
      ServerDistributionProvision.assetName("3.0.0"),
    )
    assertEquals(
      "https://github.com/yschimke/compose-preview-server/releases/download/" +
        "v3.0.0/compose-preview-server-3.0.0.tar.gz",
      ServerDistributionProvision.assetUrl("3.0.0"),
    )
  }

  /** Gradle's application plugin writes both launchers; only one of them runs on Windows. */
  @Test
  fun `the launcher name follows the host`() {
    assertEquals("compose-preview-server", ServerDistributionProvision.binaryName(osName = "Linux"))
    assertEquals(
      "compose-preview-server",
      ServerDistributionProvision.binaryName(osName = "Mac OS X"),
    )
    assertEquals(
      "compose-preview-server.bat",
      ServerDistributionProvision.binaryName(osName = "Windows 11"),
    )
  }

  @Test
  fun `the environment overrides the pinned version`() {
    assertEquals("9.9.9", ServerDistributionProvision.version { "9.9.9" })
    assertEquals("9.9.9", ServerDistributionProvision.version { "  9.9.9 " })
  }

  /**
   * A blank override is not a version. Left unguarded, `COMPOSE_PREVIEW_SERVER_VERSION=` — which is
   * what an unset shell variable expands to in a script — would build a URL ending in `v/` and 404.
   */
  @Test
  fun `a blank override falls back to the pin`() {
    assertEquals(SERVE_VERSION, ServerDistributionProvision.version { "  " })
    assertEquals(SERVE_VERSION, ServerDistributionProvision.version { null })
  }

  @Test
  fun `a fetched distribution is unpacked, made executable and cached`() {
    val log = mutableListOf<String>()

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = false,
        fetcher = fakeRelease("compose-preview-server-3.0.0"),
        log = log::add,
      )

    val resolved = assertNotNull(binary, log.toString())
    assertEquals(File(cacheRoot, "3.0.0/bin/compose-preview-server").path, resolved.path)
    assertTrue(resolved.canExecute(), "the launcher is not executable")
    assertTrue(File(cacheRoot, "3.0.0/lib/server.jar").isFile, "the distribution's lib/ is missing")
    // The tarball's own wrapper directory must not survive into the cache path — a reader deriving
    // `<cache>/<version>/bin/…` would miss it.
    assertFalse(File(cacheRoot, "3.0.0/compose-preview-server-3.0.0").exists())
  }

  /** The one download is worth a word: silence for 120 MB reads as a hang. */
  @Test
  fun `the first fetch says what it is doing`() {
    val log = mutableListOf<String>()

    ServerDistributionProvision.ensure(
      version = "3.0.0",
      cacheRoot = cacheRoot,
      osName = "Linux",
      offline = false,
      fetcher = fakeRelease("compose-preview-server-3.0.0"),
      log = log::add,
    )

    assertContains(log.joinToString("\n"), "fetching the preview server 3.0.0")
  }

  @Test
  fun `a cached distribution is not fetched again`() {
    stageComplete(File(cacheRoot, "3.0.0"))
    var fetches = 0

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = false,
        fetcher = { _, _ -> fetches++ },
        log = {},
      )

    assertNotNull(binary)
    assertEquals(0, fetches)
  }

  /**
   * An interrupted unpack that left `bin/` behind must not short-circuit every later run — that is
   * a `serve` that fails on a missing main class until someone wipes the cache by hand.
   */
  @Test
  fun `a half-written cache is re-fetched rather than trusted`() {
    val dir = File(cacheRoot, "3.0.0")
    File(dir, "bin").mkdirs()
    File(dir, "bin/compose-preview-server").writeText("#!/bin/sh\n")
    assertNull(
      ServerDistributionProvision.cached(env = { "3.0.0" }, cacheRoot = cacheRoot, osName = "Linux")
    )

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = false,
        fetcher = fakeRelease("compose-preview-server-3.0.0"),
        log = {},
      )

    assertNotNull(binary)
    assertTrue(File(cacheRoot, "3.0.0/lib/server.jar").isFile)
  }

  @Test
  fun `offline explains itself rather than reaching the network`() {
    val log = mutableListOf<String>()
    var fetches = 0

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = true,
        fetcher = { _, _ -> fetches++ },
        log = log::add,
      )

    assertNull(binary)
    assertEquals(0, fetches)
    assertContains(log.joinToString("\n"), "offline mode is enabled")
  }

  /** A failed download is a null and a sentence, never an exception out of `serve`. */
  @Test
  fun `a download failure is reported, not thrown`() {
    val log = mutableListOf<String>()

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = false,
        fetcher = { _, _ -> error("HTTP 404") },
        log = log::add,
      )

    assertNull(binary)
    assertContains(log.joinToString("\n"), "HTTP 404")
    assertFalse(File(cacheRoot, "3.0.0").exists(), "a failed fetch left a cache directory behind")
  }

  /** An archive that is not a server distribution is refused rather than cached as one. */
  @Test
  fun `an archive without the expected layout is refused`() {
    val log = mutableListOf<String>()

    val binary =
      ServerDistributionProvision.ensure(
        version = "3.0.0",
        cacheRoot = cacheRoot,
        osName = "Linux",
        offline = false,
        fetcher = { _, dest -> tarGzOf(File(tmp, "junk").apply { mkdirs() }, dest) },
        log = log::add,
      )

    assertNull(binary)
    assertContains(log.joinToString("\n"), "is not a compose-preview-server distribution")
  }

  /**
   * The wrapper directory is found by its layout, not by rebuilding its name, so a release that
   * renames or drops the wrapper still installs.
   */
  @Test
  fun `the distribution root is found whatever the wrapper is called`() {
    val flat = File(tmp, "flat").also { stageComplete(it) }
    assertEquals(flat, ServerDistributionProvision.distributionRoot(flat, "compose-preview-server"))

    val wrapped = File(tmp, "wrapped")
    stageComplete(File(wrapped, "some-other-name-1.2.3"))
    assertEquals(
      File(wrapped, "some-other-name-1.2.3"),
      ServerDistributionProvision.distributionRoot(wrapped, "compose-preview-server"),
    )

    assertNull(
      ServerDistributionProvision.distributionRoot(
        File(tmp, "empty").apply { mkdirs() },
        "compose-preview-server",
      )
    )
  }

  // --- helpers ------------------------------------------------------------

  /**
   * A fetcher that writes a tarball shaped like the real release asset: `<wrapper>/bin` + `lib`.
   */
  private fun fakeRelease(wrapper: String) = ServerDistributionProvision.Fetcher { _, dest ->
    val staging = File(tmp, "release-${wrapper}")
    staging.deleteRecursively()
    stageComplete(File(staging, wrapper))
    tarGzOf(staging, dest)
  }

  /** The two halves `isComplete` requires: an executable launcher and a non-empty `lib/`. */
  private fun stageComplete(dir: File) {
    File(dir, "bin").mkdirs()
    File(dir, "lib").mkdirs()
    File(dir, "bin/compose-preview-server").writeText("#!/bin/sh\necho serve\n")
    File(dir, "lib/server.jar").writeText("jar")
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
