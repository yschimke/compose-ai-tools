package ee.schimke.composeai.cli.serve

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * [RcSwiftServerRenderer] against a stand-in `rc-swift-render`: a Python script speaking the same
 * one-shot flags and worker frames, so the lane's plumbing — host gating, binary resolution, the
 * pooled path, the one-shot fallback and the release download — is tested on any CI host. Whether
 * the real binary draws is rc-players' `scripts/check-swift-render.sh`.
 *
 * `os.name` / `os.arch` are faked to a Mac on Apple silicon per test and restored after.
 */
class RcSwiftServerRendererTest {

  @TempDir lateinit var temp: Path

  private val saved = mutableMapOf<String, String?>()

  private fun setProperty(key: String, value: String?) {
    if (key !in saved) saved[key] = System.getProperty(key)
    if (value == null) System.clearProperty(key) else System.setProperty(key, value)
  }

  @BeforeTest
  fun fakeAppleSilicon() {
    setProperty("os.name", "Mac OS X")
    setProperty("os.arch", "aarch64")
    // Never touch the developer's real cache, and never reach GitHub from a test.
    setProperty(
      RcSwiftServerRenderer.SYS_PROP_CACHE_DIR,
      File(temp.toFile(), "cache").absolutePath,
    )
    setProperty(RcSwiftServerRenderer.SYS_PROP_DOWNLOAD, "off")
    setProperty(RcSwiftServerRenderer.SYS_PROP_BINARY, null)
    RcSwiftServerRenderer.resetForTests()
  }

  @AfterTest
  fun restore() {
    RcSwiftServerRenderer.resetForTests()
    for ((key, value) in saved) {
      if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
  }

  @Test
  fun `only macOS on Apple silicon is offered the lane`() {
    setProperty("os.name", "Linux")
    setProperty("os.arch", "amd64")
    setProperty(RcSwiftServerRenderer.SYS_PROP_BINARY, fakeBinary().absolutePath)
    assertFalse(RcSwiftServerRenderer.isAvailable())
    val result = RcSwiftServerRenderer.render(DOC, SPEC)
    assertIs<RcJvmServerRenderer.RenderResult.Unavailable>(result)
    assertTrue(result.reason.contains("macOS on Apple silicon"), result.reason)
  }

  @Test
  fun `a named binary renders through the pooled worker`() {
    setProperty(RcSwiftServerRenderer.SYS_PROP_BINARY, fakeBinary().absolutePath)
    assertTrue(RcSwiftServerRenderer.isAvailable())
    val result =
      RcSwiftServerRenderer.render(
        DOC,
        SPEC,
        theme = RcJvmServerRenderer.RenderTheme.DARK,
      )
    assertIs<RcJvmServerRenderer.RenderResult.Ok>(result)
    // The stand-in echoes what it was asked for, so this pins the frame the pool sent.
    assertEquals("worker 64x32 dark doc=${DOC.size}", String(result.bytes))
  }

  @Test
  fun `scaled text takes the one-shot path`() {
    setProperty(RcSwiftServerRenderer.SYS_PROP_BINARY, fakeBinary().absolutePath)
    val result = RcSwiftServerRenderer.render(DOC, SPEC.copy(fontScale = 1.5f))
    assertIs<RcJvmServerRenderer.RenderResult.Ok>(result)
    assertEquals("oneshot 64x32 light fontScale=1.5", String(result.bytes))
  }

  @Test
  fun `a missing named binary is unavailable rather than silently replaced`() {
    setProperty(RcSwiftServerRenderer.SYS_PROP_BINARY, File(temp.toFile(), "nope").absolutePath)
    setProperty(RcSwiftServerRenderer.SYS_PROP_DOWNLOAD, null)
    assertFalse(RcSwiftServerRenderer.isAvailable())
    assertIs<RcJvmServerRenderer.RenderResult.Unavailable>(RcSwiftServerRenderer.render(DOC, SPEC))
  }

  @Test
  fun `the release asset is downloaded, verified and cached`() {
    val zip = assetZip(fakeBinary().readBytes())
    withRelease(zip, sha256(zip)) { base ->
      setProperty(RcSwiftServerRenderer.SYS_PROP_DOWNLOAD, null)
      setProperty(RcSwiftServerRenderer.SYS_PROP_VERSION, "9.9.9")
      setProperty(RcSwiftServerRenderer.SYS_PROP_RELEASE_BASE_URL, base)
      assertTrue(RcSwiftServerRenderer.isAvailable(), "a downloadable asset counts as available")
      val result = RcSwiftServerRenderer.render(DOC, SPEC)
      assertIs<RcJvmServerRenderer.RenderResult.Ok>(result)
      val cached =
        File(
          temp.toFile(),
          "cache/9.9.9/${RcSwiftServerRenderer.ASSET_NAME}/bin/" + "rc-swift-render",
        )
      assertTrue(cached.canExecute(), "unpacked into the versioned cache")
      assertContentEquals(fakeBinary().readBytes(), cached.readBytes())
    }
  }

  @Test
  fun `a checksum mismatch is refused and remembered`() {
    val zip = assetZip(fakeBinary().readBytes())
    withRelease(zip, "0".repeat(64)) { base ->
      setProperty(RcSwiftServerRenderer.SYS_PROP_DOWNLOAD, null)
      setProperty(RcSwiftServerRenderer.SYS_PROP_VERSION, "9.9.9")
      setProperty(RcSwiftServerRenderer.SYS_PROP_RELEASE_BASE_URL, base)
      val result = RcSwiftServerRenderer.render(DOC, SPEC)
      assertIs<RcJvmServerRenderer.RenderResult.Unavailable>(result)
      assertTrue(result.reason.contains("checksum mismatch"), result.reason)
      assertFalse(RcSwiftServerRenderer.isAvailable(), "a failed download is not retried per call")
    }
  }

  private fun fakeBinary(): File =
    File(temp.toFile(), "rc-swift-render").apply {
      if (!isFile) {
        writeText(FAKE_RC_SWIFT_RENDER)
        setExecutable(true)
      }
    }

  private fun assetZip(binary: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry("${RcSwiftServerRenderer.ASSET_NAME}/bin/rc-swift-render"))
      zip.write(binary)
      zip.closeEntry()
    }
    return out.toByteArray()
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun withRelease(zip: ByteArray, checksum: String, block: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val asset = "/v9.9.9/${RcSwiftServerRenderer.ASSET_NAME}.zip"
    server.createContext("/") { exchange ->
      val body =
        when (exchange.requestURI.path) {
          asset -> zip
          "$asset.sha256" -> "$checksum  ${RcSwiftServerRenderer.ASSET_NAME}.zip\n".toByteArray()
          else -> null
        }
      if (body == null) {
        exchange.sendResponseHeaders(404, -1)
      } else {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      exchange.close()
    }
    server.start()
    try {
      block("http://127.0.0.1:${server.address.port}")
    } finally {
      server.stop(0)
    }
  }

  private companion object {
    val DOC = "a remote compose document".toByteArray()
    val SPEC = RcJvmRenderSpec(widthPx = 64, heightPx = 32, density = 2f)

    /** `rc-swift-render`'s two modes, answering with a description of the request. */
    val FAKE_RC_SWIFT_RENDER =
      """
      #!/usr/bin/env python3
      import struct, sys
      if sys.argv[1:] == ["--worker"]:
          out, inp = sys.stdout.buffer, sys.stdin.buffer
          out.write(struct.pack(">ii", 0x52435731, 2)); out.flush()
          while True:
              head = inp.read(4)
              if len(head) < 4: sys.exit(0)
              rid, w, h, _density, _fmt, theme, seeds_len = struct.unpack(">iiiiiii", inp.read(28))
              inp.read(seeds_len)
              (doc_len,) = struct.unpack(">i", inp.read(4))
              inp.read(doc_len)
              body = ("worker %dx%d %s doc=%d" % (w, h, "dark" if theme == 1 else "light", doc_len)).encode()
              out.write(struct.pack(">iiii", 0x52435231, rid, 0, len(body)) + body); out.flush()
      args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
      with open(args["--output"], "w") as f:
          f.write("oneshot %sx%s %s fontScale=%s" % (args["--width"], args["--height"], args["--theme"], args["--fontScale"]))
      """
        .trimIndent()
  }
}
