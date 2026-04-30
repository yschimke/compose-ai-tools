package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test for [JsonRpcServer] over piped streams. Drives the full happy-path lifecycle:
 *
 * initialize → initialized → renderNow → renderStarted → renderFinished → shutdown → exit
 *
 * Uses a [FakeRenderHost] that bypasses any backend (no Robolectric sandbox, no desktop Compose
 * runtime) so the test is deterministic and fast (sub-second). The real-host smoke test for the
 * Robolectric backend lives in `:daemon:android`'s `DaemonHostTest`, which is separately gated
 * because Robolectric cold-boot is non-deterministic and incompatible with Gradle's default
 * same-JVM test ordering (multiple `JUnitCore.runClasses` invocations in one JVM intermittently
 * hang on the second sandbox bootstrap).
 *
 * **Why in-process rather than spawning a real subprocess?** B1.5's DoD asked for a
 * `ProcessBuilder`-spawned daemon JVM. We deferred that for two reasons:
 *
 * 1. The descriptor produced by Stream A's `composePreviewDaemonStart` task lives in
 *    `samples/android/build/...` and isn't available to a unit test classpath without a Gradle
 *    dependency on the consumer module — that would be a circular dep (`:daemon:android` consumes
 *    `:samples:android`).
 * 2. The real value the DoD wanted to prove — request → response → notification round-trip with a
 *    working host — is fully exercised here, including the no-mid-render-cancellation invariant (we
 *    drain the in-flight queue before resolving `shutdown`). A subprocess wrapper would only add
 *    `ProcessBuilder` plumbing on top.
 *
 * A subprocess smoke test belongs in Stream C's `daemonClient.ts` integration test (C1.3 DoD),
 * which spawns the real launcher descriptor end-to-end. We track a "Stream B subprocess smoke test"
 * follow-up under B1.5a (the no-mid-render-cancellation enforcement task) since both want the same
 * ProcessBuilder harness.
 */
class JsonRpcServerIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun full_lifecycle_renders_one_preview_and_emits_finished_notification() {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread = Thread({ server.run() }, "json-rpc-server-test").apply { isDaemon = true }
    serverThread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    val readerThread =
      Thread(
          {
            try {
              while (true) {
                val frame = reader.readFrame() ?: break
                val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
                received.put(obj)
              }
            } catch (_: Throwable) {
              // EOF / pipe close — fine, test asserts on what we got.
            }
          },
          "json-rpc-server-test-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    try {
      // 1. initialize
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":1,
                  "clientVersion":"test",
                  "workspaceRoot":"/tmp",
                  "moduleId":":test",
                  "moduleProjectDir":"/tmp",
                  "capabilities":{"visibility":true,"metrics":false}
                }}
        """
          .trimIndent(),
      )
      val initResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull("initialize response should arrive", initResponse)
      val initResult = initResponse!!["result"]!!.jsonObject
      assertEquals(1, initResult["protocolVersion"]?.jsonPrimitive?.intOrNull)
      assertEquals("test", initResult["daemonVersion"]?.jsonPrimitive?.contentOrNull)
      assertNotNull("pid should be present", initResult["pid"])

      // 2. initialized notification
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // 3. renderNow for one preview
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
                  "previews":["preview-A"],
                  "tier":"fast"
                }}
        """
          .trimIndent(),
      )
      val renderResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
      assertNotNull("renderNow response should arrive", renderResponse)
      val renderResult = renderResponse!!["result"]!!.jsonObject
      val queued = renderResult["queued"].toString()
      assertTrue("queued should contain preview-A: $queued", queued.contains("preview-A"))

      // 4. renderStarted + renderFinished notifications.
      val started =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderStarted" }
      assertNotNull("renderStarted notification should arrive", started)
      assertEquals(
        "preview-A",
        started!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished notification should arrive", finished)
      val finishedParams = finished!!["params"]!!.jsonObject
      assertEquals("preview-A", finishedParams["id"]?.jsonPrimitive?.contentOrNull)
      val pngPath = finishedParams["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("pngPath should be present (placeholder until B1.4)", pngPath)
      assertTrue(
        "pngPath should be the B1.4-stub placeholder, was $pngPath",
        pngPath!!.contains("daemon-stub-"),
      )

      // 5. shutdown — must drain in-flight (already drained here) and
      //    respond with null result. Per DESIGN.md § 9 enforcement, no
      //    Thread.interrupt() on the render thread.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":3,"method":"shutdown"}""")
      val shutdownResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      assertNotNull("shutdown response should arrive", shutdownResponse)
      assertNull(
        "shutdown result must be null per PROTOCOL.md § 3",
        shutdownResponse!!["result"]?.let {
          if (it is JsonPrimitive && it.contentOrNull == null) null else it
        },
      )

      // 6. exit — server should call onExit(0).
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(
        "server should invoke onExit() within 10s of exit notification",
        exitLatch.await(10, TimeUnit.SECONDS),
      )
      assertEquals(0, exitCode.get())

      // No render thread interruption observed by the fake host — proves
      // the no-mid-render-cancellation invariant from DESIGN.md § 9.
      assertEquals(
        "JsonRpcServer must never interrupt the render thread",
        0,
        host.interruptCount.get(),
      )
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(10_000)
    }
  }

  /**
   * B2.2 phase 1 — when the daemon is constructed with a non-empty [PreviewIndex], the `initialize`
   * response's `manifest` block reports the absolute path of the loaded `previews.json` and the
   * count of previews known to the daemon. Mirrors the `initialize.classpathFingerprint` pin in the
   * B2.1 test below.
   */
  @Test(timeout = 30_000)
  fun initialize_manifest_reports_preview_index_path_and_count() {
    val tmpDir = java.nio.file.Files.createTempDirectory("preview-index-test")
    val previewsJson = tmpDir.resolve("previews.json")
    java.nio.file.Files.writeString(
      previewsJson,
      """
      {
        "module": ":t",
        "variant": "debug",
        "previews": [
          {"id":"A","className":"com.x.AKt","functionName":"A"},
          {"id":"B","className":"com.x.BKt","functionName":"B","sourceFile":"B.kt"}
        ]
      }
      """
        .trimIndent(),
    )
    val index = PreviewIndex.loadFromFile(previewsJson)

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-test-manifest").apply { isDaemon = true }
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
        "json-rpc-server-test-manifest-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull("initialize response should arrive", init)
      val manifest = init!!["result"]!!.jsonObject["manifest"]!!.jsonObject
      assertEquals(
        previewsJson.toAbsolutePath().toString(),
        manifest["path"]?.jsonPrimitive?.contentOrNull,
      )
      assertEquals(2, manifest["previewCount"]?.jsonPrimitive?.intOrNull)

      // Tear down cleanly.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
      assertEquals(0, exitCode.get())
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      tmpDir.toFile().deleteRecursively()
      serverThread.join(10_000)
    }
  }

  /**
   * B2.2 phase 1 back-compat — when the daemon is constructed without a [PreviewIndex] (the
   * default), `initialize.manifest` reports `path = ""` and `previewCount = 0`. Pins the pre-B2.2
   * stub shape so existing in-process callers that don't yet wire the index keep receiving the
   * empty placeholder.
   */
  @Test(timeout = 30_000)
  fun initialize_manifest_defaults_to_empty_index() {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { _ -> exitLatch.countDown() },
      )
    Thread({ server.run() }, "json-rpc-server-default-manifest").apply { isDaemon = true }.start()

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
        "json-rpc-server-default-manifest-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull(init)
      val manifest = init!!["result"]!!.jsonObject["manifest"]!!.jsonObject
      assertEquals("", manifest["path"]?.jsonPrimitive?.contentOrNull)
      assertEquals(0, manifest["previewCount"]?.jsonPrimitive?.intOrNull)

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
    }
  }

  /**
   * B2.1 — `fileChanged({ kind: "classpath" })` against a daemon with a [ClasspathFingerprint]
   * configured. When the cheap-signal file's bytes change AND the (synthetic) classpath hash
   * drifts, the daemon must emit `classpathDirty` exactly once and then exit cleanly.
   */
  @Test(timeout = 30_000)
  fun classpath_fingerprint_dirty_emits_classpathDirty_and_exits() {
    val tmpDir = java.nio.file.Files.createTempDirectory("classpath-fp-test").toFile()
    val cheapFile = java.io.File(tmpDir, "libs.versions.toml").apply { writeText("a = 1\n") }
    val classpathJar = java.io.File(tmpDir, "fake.jar").apply { writeBytes(ByteArray(64) { 1 }) }
    val fingerprint =
      ClasspathFingerprint(
        cheapSignalFiles = listOf(cheapFile),
        classpathEntries = listOf(classpathJar),
      )

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        classpathFingerprint = fingerprint,
        // Aggressive: tests should exit fast.
        classpathDirtyGraceMs = 100,
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-test-classpath").apply { isDaemon = true }
    serverThread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    val readerThread =
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
          "json-rpc-server-test-classpath-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    try {
      // initialize → initialized
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull(init)
      // initialize must surface the authoritative classpath hash.
      val initFp =
        init!!["result"]!!.jsonObject["classpathFingerprint"]?.jsonPrimitive?.contentOrNull ?: ""
      assertEquals(
        "initialize.classpathFingerprint must be SHA-256 hex (64 chars)",
        ClasspathFingerprint.SHA_256_HEX_LENGTH,
        initFp.length,
      )
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // Modify the cheap-signal file AND the classpath JAR (so both hashes drift).
      Thread.sleep(20)
      cheapFile.writeText("a = 2\n")
      classpathJar.writeBytes(ByteArray(128) { 2 })

      // Send fileChanged({ kind: "classpath" }) — daemon recomputes the cheap hash, sees a
      // mismatch, then the authoritative hash, sees a mismatch, then emits classpathDirty.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","method":"fileChanged","params":{
              "path":"${cheapFile.absolutePath.replace("\\","/")}",
              "kind":"classpath","changeType":"modified"}}""",
      )

      val dirty =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "classpathDirty" }
      assertNotNull("classpathDirty notification must arrive", dirty)
      val dirtyParams = dirty!!["params"]!!.jsonObject
      assertEquals("fingerprintMismatch", dirtyParams["reason"]?.jsonPrimitive?.contentOrNull)
      val detail = dirtyParams["detail"]?.jsonPrimitive?.contentOrNull ?: ""
      assertTrue(
        "detail must reference both hashes; got '$detail'",
        detail.contains("cheapHash") && detail.contains("classpathHash"),
      )

      // Now `renderNow` must be refused with ClasspathDirty (-32002).
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":42,"method":"renderNow","params":{
              "previews":["x"],"tier":"fast"}}""",
      )
      val rejected = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 42 }
      assertNotNull(rejected)
      assertEquals(
        JsonRpcServer.ERR_CLASSPATH_DIRTY,
        rejected!!["error"]!!.jsonObject["code"]?.jsonPrimitive?.intOrNull,
      )

      // The daemon should self-exit within the grace window — assert exit code 0.
      assertTrue(
        "daemon should exit cleanly within grace window",
        exitLatch.await(5, TimeUnit.SECONDS),
      )
      assertEquals(0, exitCode.get())
    } finally {
      tmpDir.deleteRecursively()
    }
  }

  /**
   * B2.1 — false-alarm path: cheap hash drifts but the authoritative classpath hash does NOT (e.g.
   * a comment-only edit in `build.gradle.kts`). Daemon must NOT emit `classpathDirty` and must keep
   * accepting `renderNow`.
   */
  @Test(timeout = 30_000)
  fun classpath_fingerprint_false_alarm_does_not_emit_classpathDirty() {
    val tmpDir = java.nio.file.Files.createTempDirectory("classpath-fp-fa-test").toFile()
    val cheapFile = java.io.File(tmpDir, "build.gradle.kts").apply { writeText("// hi\n") }
    // No classpath entries → classpath hash is constant regardless of cheap-file edits.
    val fingerprint =
      ClasspathFingerprint(cheapSignalFiles = listOf(cheapFile), classpathEntries = emptyList())

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        classpathFingerprint = fingerprint,
        classpathDirtyGraceMs = 100,
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    Thread({ server.run() }, "json-rpc-server-fa").apply { isDaemon = true }.start()

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
        "json-rpc-server-fa-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // Modify only the cheap file's bytes.
      Thread.sleep(20)
      cheapFile.writeText("// edited\n")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","method":"fileChanged","params":{
              "path":"${cheapFile.absolutePath.replace("\\","/")}",
              "kind":"classpath","changeType":"modified"}}""",
      )

      // Confirm renderNow still works (1.5s margin for FakeRenderHost to process).
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":7,"method":"renderNow","params":{
              "previews":["ok"],"tier":"fast"}}""",
      )
      val ok = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 7 }
      assertNotNull("renderNow should be accepted on false-alarm path", ok)
      assertNotNull(ok!!["result"])

      // Tear down cleanly — daemon must NOT have self-exited.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
      assertEquals(0, exitCode.get())
    } finally {
      tmpDir.deleteRecursively()
    }
  }

  /**
   * B2.2 phase 2 — `fileChanged({kind: source, path: <.kt>})` against a daemon configured with a
   * non-empty [PreviewIndex] AND a wired [IncrementalDiscovery] must emit `discoveryUpdated` within
   * a couple of seconds with the diff against the cached index.
   *
   * The synthetic classpath has no `@Preview`-bearing classes, so the scoped scan returns empty and
   * the diff carries `removed = [the index's preview]`.
   */
  @Test(timeout = 30_000)
  fun fileChanged_source_emits_discoveryUpdated() {
    val tmpDir = java.nio.file.Files.createTempDirectory("phase2-discovery-test")
    // The "source" file we'll claim changed. Cheap pre-filter trips on text match (`@Preview`).
    val sourceKt = tmpDir.resolve("Foo.kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview\nfun Foo() {}\n")

    // Seed the index with a preview anchored to the source file's absolute path. The scan returns
    // empty (no compiled classes in the synthetic classpath); the diff's `removed` slot picks up
    // the indexed preview because its `sourceFile` matches the saved path.
    val previewDto =
      PreviewInfoDto(
        id = "Foo",
        className = "com.example.FooKt",
        methodName = "Foo",
        sourceFile = sourceKt.toAbsolutePath().toString(),
      )
    val index = PreviewIndex.fromMap(path = sourceKt, byId = mapOf("Foo" to previewDto))
    val discovery =
      IncrementalDiscovery(
        classpath = listOf(tmpDir),
        knownPreviewAnnotationFqns = setOf("androidx.compose.ui.tooling.preview.Preview"),
      )

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        incrementalDiscovery = discovery,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-test-discovery").apply { isDaemon = true }
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
        "json-rpc-server-test-discovery-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull(init)
      // capability flips true once IncrementalDiscovery is wired.
      val caps = init!!["result"]!!.jsonObject["capabilities"]!!.jsonObject
      assertEquals(true, caps["incrementalDiscovery"]?.jsonPrimitive?.boolean)
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // Send fileChanged for the source `.kt`. Daemon spawns a worker, scans, diffs, emits.
      val abs = sourceKt.toAbsolutePath().toString().replace("\\", "\\\\")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","method":"fileChanged","params":{
              "path":"$abs","kind":"source","changeType":"modified"}}""",
      )

      val discoveryNotif =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "discoveryUpdated" }
      assertNotNull("discoveryUpdated notification must arrive", discoveryNotif)
      val params = discoveryNotif!!["params"]!!.jsonObject
      val removed =
        params["removed"]?.let {
          (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonPrimitive.content }
        } ?: emptyList()
      assertEquals(listOf("Foo"), removed)
      assertEquals(0, params["totalPreviews"]?.jsonPrimitive?.intOrNull)
      // Verify the index was updated in-place.
      assertEquals(0, index.size)

      // Tear down.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      tmpDir.toFile().deleteRecursively()
      serverThread.join(10_000)
    }
  }

  /**
   * B2.3 — when the host returns a `RenderResult.metrics` map carrying the four B2.3 keys,
   * `JsonRpcServer.renderFinishedFromResult` translates them into a structured [RenderMetrics] on
   * the wire.
   */
  @Test(timeout = 30_000)
  fun renderFinished_metrics_populated_when_host_supplies_all_four_b23_keys() {
    val hostMetrics: Map<String, Long> =
      mapOf(
        "tookMs" to 7L,
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 100L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 200L,
        RenderMetrics.KEY_SANDBOX_AGE_RENDERS to 5L,
        RenderMetrics.KEY_SANDBOX_AGE_MS to 4321L,
      )
    runRenderAndPollFinished(
      host = FakeRenderHost(metricsToReturn = hostMetrics),
      assertOnFinished = { params ->
        val metricsField = params["metrics"]
        assertNotNull("renderFinished.metrics must be present (B2.3)", metricsField)
        assertNotEquals(
          "renderFinished.metrics must not be JsonNull (B2.3)",
          JsonNull,
          metricsField,
        )
        val metricsObj = metricsField!!.jsonObject
        assertEquals(100L, metricsObj[RenderMetrics.KEY_HEAP_AFTER_GC_MB]?.jsonPrimitive?.long)
        assertEquals(200L, metricsObj[RenderMetrics.KEY_NATIVE_HEAP_MB]?.jsonPrimitive?.long)
        assertEquals(5L, metricsObj[RenderMetrics.KEY_SANDBOX_AGE_RENDERS]?.jsonPrimitive?.long)
        assertEquals(4321L, metricsObj[RenderMetrics.KEY_SANDBOX_AGE_MS]?.jsonPrimitive?.long)
        // tookMs at the top level too — already wired pre-B2.3.
        assertEquals(7L, params["tookMs"]?.jsonPrimitive?.longOrNull)
      },
    )
  }

  /**
   * B2.3 back-compat — when the host returns `metrics = null` (the B1.5-era stub hosts), the
   * wire-level `renderFinished.metrics` stays null. Pins the pre-B2.3 behaviour for hosts that
   * don't measure anything.
   */
  @Test(timeout = 30_000)
  fun renderFinished_metrics_null_when_host_supplies_null() {
    runRenderAndPollFinished(
      host = FakeRenderHost(metricsToReturn = null),
      assertOnFinished = { params ->
        val metricsField = params["metrics"]
        val isNullOrAbsent = metricsField == null || metricsField is JsonNull
        assertTrue(
          "renderFinished.metrics must stay null when host returns null metrics: got $metricsField",
          isNullOrAbsent,
        )
      },
    )
  }

  /**
   * B2.3 partial-map path — when the host populates *some* B2.3 keys but not all four, the wire
   * still emits `metrics: null` (no half-populated objects) and the daemon warn-logs the gap.
   * Pinned here to lock the JsonRpcServer behaviour; the partial-map outcome itself is unit- tested
   * in [RenderMetricsFromFlatMapTest].
   */
  @Test(timeout = 30_000)
  fun renderFinished_metrics_null_when_host_supplies_partial_map() {
    val partial: Map<String, Long> =
      mapOf(
        RenderMetrics.KEY_HEAP_AFTER_GC_MB to 1L,
        RenderMetrics.KEY_NATIVE_HEAP_MB to 2L,
        // Missing sandboxAgeRenders + sandboxAgeMs.
      )
    runRenderAndPollFinished(
      host = FakeRenderHost(metricsToReturn = partial),
      assertOnFinished = { params ->
        val metricsField = params["metrics"]
        val isNullOrAbsent = metricsField == null || metricsField is JsonNull
        assertTrue(
          "renderFinished.metrics must stay null on partial-map host return: got $metricsField",
          isNullOrAbsent,
        )
      },
    )
  }

  /**
   * Drives the initialize → renderNow → renderFinished round-trip against a custom [FakeRenderHost]
   * and lets the caller assert on the `renderFinished.params` JSON. Factored out of the three B2.3
   * tests above so they can each focus on the metrics shape.
   */
  private fun runRenderAndPollFinished(
    host: FakeRenderHost,
    assertOnFinished: (JsonObject) -> Unit,
  ) {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-test-b23").apply { isDaemon = true }
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
        "json-rpc-server-test-b23-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":true}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast"}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 })
      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished notification should arrive", finished)
      val params = finished!!["params"]!!.jsonObject
      assertEquals("preview-A", params["id"]?.jsonPrimitive?.contentOrNull)
      assertOnFinished(params)

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  /** Helper: writes one Content-Length-framed JSON message. */
  private fun writeFrame(out: PipedOutputStream, json: String) {
    val payload = json.toByteArray(Charsets.UTF_8)
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
      // Otherwise drop (e.g. an interleaved notification we don't care about).
    }
    return null
  }
}

/**
 * In-test [RenderHost] implementation that mimics a real backend's submit/shutdown contract without
 * bootstrapping anything heavy (Robolectric sandbox, Compose desktop runtime, …). Renderer-agnostic
 * by design: it lives alongside [JsonRpcServer] in `:daemon:core`, away from any specific render
 * backend.
 *
 * Renders complete instantly on a single dedicated worker thread, mirroring the real backends'
 * "single render thread" guarantee. The [interruptCount] counter spies on `Thread.interrupt()`
 * calls — the no-mid-render-cancellation invariant requires this to stay at zero.
 */
private class FakeRenderHost(
  /**
   * If non-null, every successful render returns this map verbatim as `RenderResult.metrics`. Used
   * by the B2.3 integration tests to drive `JsonRpcServer.renderFinishedFromResult` through its
   * happy / partial / null branches.
   */
  private val metricsToReturn: Map<String, Long>? = null
) : RenderHost {

  val interruptCount = java.util.concurrent.atomic.AtomicInteger(0)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  @Volatile private var stopped = false
  private val worker =
    Thread(
        {
          while (!stopped) {
            val req =
              try {
                queue.poll(100, TimeUnit.MILLISECONDS)
              } catch (_: InterruptedException) {
                interruptCount.incrementAndGet()
                Thread.currentThread().interrupt()
                return@Thread
              } ?: continue
            when (req) {
              is RenderRequest.Render -> {
                val result =
                  RenderResult(
                    id = req.id,
                    classLoaderHashCode = 0,
                    classLoaderName = "fake",
                    metrics = metricsToReturn,
                  )
                results.computeIfAbsent(req.id) { LinkedBlockingQueue() }.put(result)
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "fake-render-host",
      )
      .apply { isDaemon = true }

  private val results =
    java.util.concurrent.ConcurrentHashMap<Long, LinkedBlockingQueue<RenderResult>>()

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    val q = results.computeIfAbsent(request.id) { LinkedBlockingQueue() }
    return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("FakeRenderHost.submit($request) timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
