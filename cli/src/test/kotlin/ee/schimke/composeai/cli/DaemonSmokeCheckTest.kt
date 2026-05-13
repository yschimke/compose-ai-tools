package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.DaemonClient
import ee.schimke.composeai.mcp.DaemonClientFactory
import ee.schimke.composeai.mcp.DaemonSpawn
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DaemonSmokeCheckTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun newTempDir(): File {
    val dir = Files.createTempDirectory("daemon-smoke-test").toFile()
    tempDirs += dir
    return dir
  }

  private fun writeDescriptor(modulePath: String, dir: File, body: String): File {
    val moduleDir = File(dir, modulePath.trimStart(':').replace(':', File.separatorChar))
    val descriptorDir = File(moduleDir, "build/compose-previews").apply { mkdirs() }
    return File(descriptorDir, "daemon-launch.json").apply { writeText(body) }
  }

  private fun defaultDescriptorJson(modulePath: String, enabled: Boolean = true): String =
    """
    {
      "schemaVersion": 1,
      "modulePath": "$modulePath",
      "variant": "debug",
      "enabled": $enabled,
      "mainClass": "fake.Main",
      "classpath": [],
      "jvmArgs": [],
      "systemProperties": {},
      "workingDirectory": "/tmp/fake",
      "manifestPath": ""
    }
    """
      .trimIndent()

  // -- Pure interpretation tests --------------------------------------------

  @Test
  fun `descriptor missing maps to error with mcp install remediation`() {
    val outcome = DaemonSmokeOutcome.DescriptorMissing(File("/some/path/daemon-launch.json"))
    val check = interpretDaemonSmoke(":app", outcome)

    assertEquals("project.app.daemon-smoke", check.id)
    assertEquals("error", check.status)
    assertTrue(check.message.contains("daemon descriptor missing"))
    assertTrue(check.detail!!.contains("/some/path/daemon-launch.json"))
    assertEquals("compose-preview mcp install", check.remediation?.commands?.single())
  }

  @Test
  fun `descriptor unreadable maps to error with reason in detail`() {
    val outcome =
      DaemonSmokeOutcome.DescriptorUnreadable(File("/x/daemon-launch.json"), "bad JSON at line 4")
    val check = interpretDaemonSmoke(":app", outcome)

    assertEquals("error", check.status)
    assertTrue(check.detail!!.contains("bad JSON at line 4"))
  }

  @Test
  fun `descriptor disabled maps to error with mcp install remediation`() {
    val outcome = DaemonSmokeOutcome.DescriptorDisabled(File("/x/daemon-launch.json"))
    val check = interpretDaemonSmoke(":auth:composables", outcome)

    assertEquals("project.auth:composables.daemon-smoke", check.id)
    assertEquals("error", check.status)
    assertTrue(check.message.contains("enabled=false"))
    assertEquals("compose-preview mcp install", check.remediation?.commands?.single())
  }

  @Test
  fun `spawn failure surfaces with composePreviewDaemonStart remediation`() {
    val outcome = DaemonSmokeOutcome.SpawnFailed("Cannot run program 'java': not found")
    val check = interpretDaemonSmoke(":app", outcome)

    assertEquals("error", check.status)
    assertTrue(check.message.contains("daemon JVM failed to spawn"))
    val remediation = check.remediation
    assertNotNull(remediation)
    assertTrue(remediation.commands.any { it.contains("composePreviewDaemonStart") })
  }

  @Test
  fun `initialize failure shows elapsed ms and re-bootstrap remediation`() {
    val outcome =
      DaemonSmokeOutcome.InitializeFailed(elapsedMs = 1234, reason = "timeout after 30s")
    val check = interpretDaemonSmoke(":app", outcome)

    assertEquals("error", check.status)
    assertTrue(check.message.contains("1234ms"))
    assertTrue(check.detail!!.contains("timeout after 30s"))
    assertTrue(check.remediation!!.commands.any { it.contains("discoverPreviews") })
  }

  @Test
  fun `ok outcome shows latency version and pid`() {
    val outcome =
      DaemonSmokeOutcome.Ok(elapsedMs = 580, daemonVersion = "1.2.3", pid = 42, protocolVersion = 2)
    val check = interpretDaemonSmoke(":app", outcome)

    assertEquals("ok", check.status)
    assertTrue(check.message.contains("580ms"))
    assertTrue(check.message.contains("v1.2.3"))
    assertTrue(check.message.contains("pid 42"))
    assertEquals("protocol v2", check.detail)
  }

  @Test
  fun `root module path maps to project root daemon-smoke id`() {
    val outcome =
      DaemonSmokeOutcome.Ok(elapsedMs = 1, daemonVersion = "x", pid = 1, protocolVersion = 2)
    val check = interpretDaemonSmoke(":", outcome)
    assertEquals("project.root.daemon-smoke", check.id)
  }

  // -- daemonDescriptorFile path resolution ---------------------------------

  @Test
  fun `descriptor path mirrors gradle to filesystem convention`() {
    val root = newTempDir()
    assertEquals(
      File(root, "auth/composables/build/compose-previews/daemon-launch.json"),
      daemonDescriptorFile(root, ":auth:composables"),
    )
    assertEquals(
      File(root, "app/build/compose-previews/daemon-launch.json"),
      daemonDescriptorFile(root, "app"),
    )
    assertEquals(
      File(root, "build/compose-previews/daemon-launch.json"),
      daemonDescriptorFile(root, ":"),
    )
  }

  // -- runDaemonSmokeTest descriptor branches -------------------------------

  @Test
  fun `runDaemonSmokeTest returns DescriptorMissing when file is absent`() {
    val outcome = runDaemonSmokeTest(projectDir = newTempDir(), modulePath = ":app")
    assertTrue(outcome is DaemonSmokeOutcome.DescriptorMissing)
  }

  @Test
  fun `runDaemonSmokeTest returns DescriptorUnreadable on malformed JSON`() {
    val dir = newTempDir()
    writeDescriptor(":app", dir, "{ not valid json")

    val factory = DaemonClientFactory { _, _ -> error("factory should not be called") }
    val outcome = runDaemonSmokeTest(projectDir = dir, modulePath = ":app", factory = factory)
    assertTrue(outcome is DaemonSmokeOutcome.DescriptorUnreadable)
  }

  @Test
  fun `runDaemonSmokeTest short-circuits when enabled=false`() {
    val dir = newTempDir()
    writeDescriptor(":app", dir, defaultDescriptorJson(":app", enabled = false))

    val factory = DaemonClientFactory { _, _ -> error("factory should not be called") }
    val outcome = runDaemonSmokeTest(projectDir = dir, modulePath = ":app", factory = factory)
    assertTrue(outcome is DaemonSmokeOutcome.DescriptorDisabled)
  }

  @Test
  fun `runDaemonSmokeTest maps factory exceptions to SpawnFailed`() {
    val dir = newTempDir()
    writeDescriptor(":app", dir, defaultDescriptorJson(":app"))

    val factory = DaemonClientFactory { _, _ -> throw RuntimeException("no /bin/java here") }
    val outcome = runDaemonSmokeTest(projectDir = dir, modulePath = ":app", factory = factory)
    val failed = outcome as DaemonSmokeOutcome.SpawnFailed
    assertTrue(failed.reason.contains("no /bin/java here"))
  }

  // -- runDaemonSmokeTest end-to-end against a piped fake -------------------

  @Test
  fun `runDaemonSmokeTest reports Ok when fake daemon answers initialize`() {
    val dir = newTempDir()
    writeDescriptor(":app", dir, defaultDescriptorJson(":app"))

    val fake = StubDaemonSpawn().also { it.start() }
    try {
      val factory = DaemonClientFactory { _, _ -> fake }
      val outcome = runDaemonSmokeTest(projectDir = dir, modulePath = ":app", factory = factory)
      val ok = outcome as DaemonSmokeOutcome.Ok
      assertEquals("1.2.3-stub", ok.daemonVersion)
      assertEquals(4321L, ok.pid)
      assertEquals(2, ok.protocolVersion)
      assertTrue(ok.elapsedMs >= 0)
      assertEquals(1, fake.initializeCount.get())
    } finally {
      fake.shutdown()
    }
  }

  @Test
  fun `runDaemonSmokeTest reports InitializeFailed when fake returns an error response`() {
    val dir = newTempDir()
    writeDescriptor(":app", dir, defaultDescriptorJson(":app"))

    val fake = StubDaemonSpawn(initializeError = "boom").also { it.start() }
    try {
      val factory = DaemonClientFactory { _, _ -> fake }
      val outcome = runDaemonSmokeTest(projectDir = dir, modulePath = ":app", factory = factory)
      val failed = outcome as DaemonSmokeOutcome.InitializeFailed
      assertTrue(failed.reason.contains("boom"))
    } finally {
      fake.shutdown()
    }
  }
}

/**
 * Minimal in-process daemon stand-in. Speaks the daemon protocol's `Content-Length` framed JSON-RPC
 * over piped streams so the production [DaemonClient] can drive a real `initialize` round-trip in
 * unit tests — no subprocess, no fixture dependency on the `:mcp` test sources.
 *
 * Hand-rolls just the two methods the smoke test exercises: `initialize` (success or error) and
 * `shutdown` (always succeeds). Notifications (`initialized`, `exit`) are drained and ignored.
 */
private class StubDaemonSpawn(private val initializeError: String? = null) : DaemonSpawn {
  private val mcpToDaemon = PipedOutputStream()
  private val daemonReadIn = PipedInputStream(mcpToDaemon, 64 * 1024)
  private val daemonToMcp = PipedOutputStream()
  private val mcpReadIn = PipedInputStream(daemonToMcp, 64 * 1024)
  private val running = AtomicBoolean(true)
  private lateinit var _client: DaemonClient

  val initializeCount = AtomicInteger(0)

  fun start() {
    Thread({ runReader() }, "stub-daemon-reader").apply {
      isDaemon = true
      start()
    }
  }

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = mcpReadIn,
        output = mcpToDaemon,
        onNotification = onNotification,
        onClose = onClose,
        threadName = "stub-daemon-client",
      )
    return _client
  }

  override fun shutdown() {
    if (!running.compareAndSet(true, false)) return
    runCatching { daemonToMcp.close() }
    runCatching { daemonReadIn.close() }
  }

  private fun runReader() {
    try {
      while (running.get()) {
        val frame = readFrame(daemonReadIn) ?: return
        val obj =
          kotlinx.serialization.json.Json.parseToJsonElement(String(frame, Charsets.UTF_8))
            .jsonObject
        val id = obj["id"]?.jsonPrimitive?.long
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        if (id != null && method == "initialize") {
          initializeCount.incrementAndGet()
          if (initializeError != null) sendError(id, -32000, initializeError)
          else sendOkInitialize(id)
        } else if (id != null && method == "shutdown") {
          sendResultNull(id)
        }
      }
    } catch (_: IOException) {
      // EOF on shutdown
    }
  }

  private fun sendOkInitialize(id: Long) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("result") {
        put("protocolVersion", 2)
        put("daemonVersion", "1.2.3-stub")
        put("pid", 4321L)
        putJsonObject("capabilities") {
          put("incrementalDiscovery", true)
          put("sandboxRecycle", false)
          put("leakDetection", JsonArray(emptyList()))
        }
        put("classpathFingerprint", "stub-fingerprint")
        putJsonObject("manifest") {
          put("path", "")
          put("previewCount", 0)
        }
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendResultNull(id: Long) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", JsonNull)
    }
    sendFrame(payload.toString())
  }

  private fun sendError(id: Long, code: Int, message: String) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendFrame(jsonText: String) {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(daemonToMcp) {
      daemonToMcp.write(header)
      daemonToMcp.write(bytes)
      daemonToMcp.flush()
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val buf = ByteArrayOutputStream(64)
    while (true) {
      val line = readHeaderLine(input, buf) ?: return null
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header line: '$line'")
      if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
        contentLength = line.substring(colon + 1).trim().toInt()
      }
    }
    if (contentLength < 0) error("missing Content-Length")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) return null
      off += n
    }
    return payload
  }

  private fun readHeaderLine(input: InputStream, buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      if (b == '\n'.code) {
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }
}
