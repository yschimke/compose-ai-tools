package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [XrCompositeProvision] — the CLI's auto-provisioner for the native
 * `xr-composite` binary. No real network: the fetch seam is faked. Verifies the version+platform →
 * asset-name / URL / cache-path derivation, the "already cached → no download" short-circuit, and
 * the "download failure → graceful continue (null, no throw)" branch.
 */
class XrCompositeProvisionTest {

  private val tmp = Files.createTempDirectory("xr-provision-test-").toFile()

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `platform token maps the published release matrix`() {
    assertEquals("linux-x86_64", XrCompositeProvision.platformToken("Linux", "amd64"))
    assertEquals("linux-x86_64", XrCompositeProvision.platformToken("Linux", "x86_64"))
    assertEquals("macos-arm64", XrCompositeProvision.platformToken("Mac OS X", "aarch64"))
    assertEquals("macos-arm64", XrCompositeProvision.platformToken("Mac OS X", "arm64"))
    assertEquals("windows-x86_64", XrCompositeProvision.platformToken("Windows 11", "amd64"))
  }

  @Test
  fun `platform token is null for unpublished combos`() {
    assertNull(XrCompositeProvision.platformToken("Linux", "aarch64"))
    assertNull(XrCompositeProvision.platformToken("Mac OS X", "x86_64"))
    assertNull(XrCompositeProvision.platformToken("FreeBSD", "amd64"))
  }

  @Test
  fun `asset name and url match the release packaging`() {
    assertEquals(
      "xr-composite-linux-x86_64-0.13.1.tar.gz",
      XrCompositeProvision.assetName("0.13.1", "linux-x86_64"),
    )
    assertEquals(
      "https://github.com/yschimke/compose-ai-tools/releases/download/" +
        "v0.13.1/xr-composite-macos-arm64-0.13.1.tar.gz",
      XrCompositeProvision.assetUrl("0.13.1", "macos-arm64"),
    )
  }

  @Test
  fun `cache path honours XDG_CACHE_HOME and falls back to home cache`() {
    val xdg =
      XrCompositeProvision.cacheBinary(
        version = "0.13.1",
        platform = "linux-x86_64",
        env = { if (it == "XDG_CACHE_HOME") "/xdg" else null },
        userHome = "/home/u",
      )
    assertEquals(File("/xdg/composeai/xr-composite/0.13.1/linux-x86_64/xr-composite"), xdg)

    val home =
      XrCompositeProvision.cacheBinary(
        version = "0.13.1",
        platform = "windows-x86_64",
        env = { null },
        userHome = "/home/u",
      )
    assertEquals(
      File("/home/u/.cache/composeai/xr-composite/0.13.1/windows-x86_64/xr-composite.exe"),
      home,
    )
  }

  /**
   * Build a real `.tar.gz` of `xr-composite` + `materials/m.txt` via system tar (round-trips
   * unpack).
   */
  private fun makeTarball(dest: File) {
    val staging = File(tmp, "stage-${dest.name}")
    File(staging, "materials").mkdirs()
    File(staging, "xr-composite").writeText("#!/bin/sh\necho fake\n")
    File(staging, "materials/m.txt").writeText("mat")
    val p =
      ProcessBuilder("tar", "-czf", dest.absolutePath, "-C", staging.absolutePath, ".")
        .redirectErrorStream(true)
        .start()
    check(p.waitFor() == 0) { "tar pack failed" }
  }

  @Test
  fun `ensureCached downloads unpacks and reports the binary`() {
    val logs = mutableListOf<String>()
    var fetches = 0
    val binary =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = { _, dst ->
          fetches++
          makeTarball(dst)
        },
        env = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null },
        userHome = tmp.absolutePath,
        log = { logs.add(it) },
      )
    // Only assert the unpack/report contract when the host platform has a published asset.
    if (XrCompositeProvision.currentPlatformToken() == null) {
      assertNull(binary)
      return
    }
    assertEquals(1, fetches)
    assertTrue(binary != null && binary.isFile, "binary should be unpacked: $logs")
    assertTrue(File(binary!!.parentFile, "materials/m.txt").isFile, "materials should unpack")
  }

  @Test
  fun `ensureCached is idempotent — second call does not re-download`() {
    if (XrCompositeProvision.currentPlatformToken() == null) return
    var fetches = 0
    val fetcher = XrCompositeProvision.Fetcher { _, dst ->
      fetches++
      makeTarball(dst)
    }
    val first =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = fetcher,
        env = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null },
        userHome = tmp.absolutePath,
        log = {},
      )
    val second =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = fetcher,
        env = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null },
        userHome = tmp.absolutePath,
        log = {},
      )
    assertEquals(1, fetches, "second call must hit the cache, not re-download")
    assertEquals(first, second)
  }

  @Test
  fun `ensureCached swallows download failure and returns null`() {
    if (XrCompositeProvision.currentPlatformToken() == null) return
    val logs = mutableListOf<String>()
    val binary =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = { _, _ -> error("HTTP 404") },
        env = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null },
        userHome = tmp.absolutePath,
        log = { logs.add(it) },
      )
    assertNull(binary, "a 404 must not throw — it skips gracefully")
    assertTrue(
      logs.any { it.contains("could not provision") },
      "should log a concise skip note: $logs",
    )
    // No binary left behind in the cache.
    val expected =
      XrCompositeProvision.cacheBinary(
        version = "0.13.1",
        platform = XrCompositeProvision.currentPlatformToken()!!,
        env = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null },
        userHome = tmp.absolutePath,
      )
    assertFalse(expected.isFile)
  }

  /** A `.tar.gz` with ONLY `xr-composite` (no `materials/`) — simulates an interrupted unpack. */
  private fun makeBinaryOnlyTarball(dest: File) {
    val staging = File(tmp, "stage-bin-${dest.name}").apply { mkdirs() }
    File(staging, "xr-composite").writeText("#!/bin/sh\necho fake\n")
    val p =
      ProcessBuilder("tar", "-czf", dest.absolutePath, "-C", staging.absolutePath, ".")
        .redirectErrorStream(true)
        .start()
    check(p.waitFor() == 0) { "tar pack failed" }
  }

  @Test
  fun `ensureCached re-provisions a partial cache instead of trusting it`() {
    if (XrCompositeProvision.currentPlatformToken() == null) return
    val platform = XrCompositeProvision.currentPlatformToken()!!
    val env: (String) -> String? = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null }
    // Pre-seed a partial cache: the binary, but no materials/ (an earlier interrupted unpack).
    val binary = XrCompositeProvision.cacheBinary("0.13.1", platform, env, tmp.absolutePath)
    binary.parentFile.mkdirs()
    binary.writeText("stale partial")
    var fetches = 0
    val result =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = { _, dst ->
          fetches++
          makeTarball(dst)
        },
        env = env,
        userHome = tmp.absolutePath,
        log = {},
      )
    assertEquals(1, fetches, "a partial cache must NOT short-circuit — it re-provisions")
    assertTrue(result != null && result.isFile)
    assertTrue(File(result!!.parentFile, "materials").isDirectory, "materials/ is now present")
  }

  @Test
  fun `ensureCached leaves no partial cache when the unpack is incomplete`() {
    if (XrCompositeProvision.currentPlatformToken() == null) return
    val platform = XrCompositeProvision.currentPlatformToken()!!
    val env: (String) -> String? = { if (it == "XDG_CACHE_HOME") tmp.absolutePath else null }
    val logs = mutableListOf<String>()
    val result =
      XrCompositeProvision.ensureCached(
        version = "0.13.1",
        fetcher = { _, dst -> makeBinaryOnlyTarball(dst) }, // no materials/ → incomplete layout
        env = env,
        userHome = tmp.absolutePath,
        log = { logs.add(it) },
      )
    assertNull(result, "an incomplete unpack must not report a usable binary")
    assertTrue(
      logs.any { it.contains("incomplete") },
      "should log the incomplete-layout skip: $logs",
    )
    // The live cache path must NOT be left half-populated for the next run to trust.
    val cached = XrCompositeProvision.cacheBinary("0.13.1", platform, env, tmp.absolutePath)
    assertFalse(cached.isFile, "no partial binary should remain in the live cache path")
  }
}
