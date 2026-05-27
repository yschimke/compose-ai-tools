package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the wire-side leg of issue #1400's permissions override pipeline.
 *
 * [JsonRpcServer.encodeRenderPayload] builds a base64-encoded [PreviewOverrides] bag for the
 * extension-driven fields the renderer's planners consume (material3-theme, wallpaper, permissions,
 * ...). Before this fix the encoder only included `material3Theme` + `wallpaper`, so a client that
 * sent `renderNow.overrides.permissions = …` saw the field silently dropped: the planner read
 * `request.permissions == null` and `PermissionsOverrideExtension` seeded the controller with
 * `null`, leaving Robolectric's manifest baseline in place. The panel's `setPermissionsOverride` →
 * host → daemon path would have looked like it worked end-to-end (pixels round-tripped, no error)
 * but `ContextCompat.checkSelfPermission` would never have observed the requested grant.
 *
 * Drives a full JSON-RPC `initialize` → `renderNow` round-trip against a [PayloadCapturingHost] and
 * asserts the encoded payload carries the permissions field inside the `overrides=<base64>` token.
 * Sibling to [DeviceOverrideEncodingTest] — same plumbing, different override field.
 */
class PermissionsOverrideEncodingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun permissionsOverrideIsEncodedIntoTheExtensionBag() {
    val captured =
      renderAndCapturePayload(
        overrides =
          """{"permissions":{"grants":{
                  "android.permission.CAMERA":"granted",
                  "android.permission.RECORD_AUDIO":"denied"
                }}}"""
      )
    val bag = decodeExtensionBag(captured)
    assertNotNull("permissions bag must be present in the encoded payload", bag.permissions)
    val grants = bag.permissions!!.grants
    assertEquals(PermissionGrantStateOverride.GRANTED, grants["android.permission.CAMERA"])
    assertEquals(PermissionGrantStateOverride.DENIED, grants["android.permission.RECORD_AUDIO"])
  }

  @Test(timeout = 30_000)
  fun permissionsOverrideRidesAlongsideThemeAndWallpaperInTheSameBag() {
    // Sanity: the three extension-driven fields share a single base64 bag; sending all three
    // packs them into one token rather than one per field.
    val captured =
      renderAndCapturePayload(
        overrides =
          """{
                "material3Theme":{"sourceColor":"#FF3366FF"},
                "wallpaper":{"seedColor":"#FF884422"},
                "permissions":{"grants":{"android.permission.CAMERA":"granted"}}
              }"""
      )
    val bag = decodeExtensionBag(captured)
    assertNotNull("material3Theme must round-trip", bag.material3Theme)
    assertNotNull("wallpaper must round-trip", bag.wallpaper)
    assertNotNull("permissions must round-trip", bag.permissions)
  }

  @Test(timeout = 30_000)
  fun absentPermissionsOverrideOmitsTheBagWhenNoOtherExtensionFieldIsSet() {
    // Mirror `DeviceOverrideEncodingTest.noDeviceOverrideLeavesDimensionsAlone` — the bag is
    // only emitted when at least one extension-driven field is present, so a permissions-free
    // payload that also lacks theme/wallpaper produces no `overrides=` token at all.
    val captured = renderAndCapturePayload(overrides = """{"uiMode":"dark"}""")
    assertTrue(
      "overrides= bag must be omitted when no extension-driven field is set: '$captured'",
      "overrides=" !in captured,
    )
  }

  private fun decodeExtensionBag(payload: String): PreviewOverrides {
    val token =
      payload.split(';').firstOrNull { it.trim().startsWith("overrides=") }
        ?: error("payload must carry an overrides= token: '$payload'")
    val b64 = token.substringAfter('=').trim()
    val raw = String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
    return json.decodeFromString(PreviewOverrides.serializer(), raw)
  }

  /**
   * Spins up a [JsonRpcServer] backed by a [PayloadCapturingHost] with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received. Mirrors
   * [DeviceOverrideEncodingTest.renderAndCapturePayload] verbatim — kept here as a private helper
   * rather than promoted to a shared utility because both tests are the only callers and the
   * plumbing is mechanical.
   */
  private fun renderAndCapturePayload(overrides: String): String {
    val sourceKt = java.nio.file.Files.createTempFile("permissions-override-test", ".kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview fun A() {}\n")
    val previewDto =
      PreviewInfoDto(
        id = "preview-A",
        className = "com.example.AKt",
        methodName = "A",
        sourceFile = sourceKt.toAbsolutePath().toString(),
      )
    val index = PreviewIndex.fromMap(path = sourceKt, byId = mapOf("preview-A" to previewDto))

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = PayloadCapturingPermissionsHost()
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "permissions-override-encoding-test").apply { isDaemon = true }
    serverThread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
              received.put(obj)
            }
          } catch (_: Throwable) {}
        },
        "permissions-override-encoding-test-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast","overrides":$overrides}}""",
      )
      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished must arrive within timeout", finished)

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
      return host.lastPayload.get() ?: error("host never received a render request")
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      try {
        java.nio.file.Files.deleteIfExists(sourceKt)
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  private fun writeFrame(out: PipedOutputStream, jsonStr: String) {
    val payload = jsonStr.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 10_000,
    matcher: (JsonObject) -> Boolean,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
      if (matcher(msg)) return msg
    }
    return null
  }
}

/**
 * Captures the [RenderRequest.Render.payload] string the daemon submits, then completes the render
 * synchronously with a stub success result. Mirrors `DeviceOverrideEncodingTest`'s
 * `PayloadCapturingHost` shape — separate file-local copy so the two tests don't take a cross-file
 * dependency on a private helper.
 */
private class PayloadCapturingPermissionsHost : RenderHost {
  val lastPayload: AtomicReference<String?> = AtomicReference(null)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()

  @Volatile private var stopped = false

  private val worker: Thread =
    Thread(
        {
          while (!stopped) {
            when (val req = queue.poll(50, TimeUnit.MILLISECONDS)) {
              null -> continue
              is RenderRequest.Render -> {
                lastPayload.set(req.payload)
                results.put(
                  RenderResult(
                    id = req.id,
                    classLoaderHashCode = 0,
                    classLoaderName = "permissions-override-encoding-test",
                  )
                )
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-permissions-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingPermissionsHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
