package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.layoutinspector.FigmaSvgBackgroundMode
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
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
 * Completeness gate for [JsonRpcServer]'s `renderNow` encoder (issue #3073).
 *
 * `encodeRenderPayload` can carry a [PreviewOverrides] field to the renderer in exactly two ways: a
 * **typed wire token** (`widthPx=…;uiMode=dark;…`) or the base64 `overrides=<bag>` **extension
 * bag**. A field on neither path is accepted by the protocol, merged by `PreviewOverrideMerge`,
 * documented in `Messages.kt` as honoured — and then silently dropped on the wire, so the render
 * comes back with default pixels and no error. That happened five separate times (`permissions`,
 * `gestures`, `lottie`, `namedOverrides`, `themeProvider`, each fixed one at a time) and had eight
 * more live instances when #3073 was filed (`clockEpochMillis`, `placeholderActive`, `ambient`,
 * `focus`, `keyboard`, `touchOverlay`, `remoteCompose`, `launcherWidget`).
 *
 * Rather than fix it a ninth time, this test walks `PreviewOverrides.serializer().descriptor` and
 * asserts every declared field survives a real `initialize` → `renderNow` round-trip on one of the
 * two paths. Adding a field to [PreviewOverrides] without either giving it a token or letting it
 * ride the bag now fails here instead of in a user's render.
 *
 * Sibling to [PermissionsOverrideEncodingTest] / [ThemeProviderOverrideEncodingTest], which pin the
 * per-field semantics; this one only asks "does it reach the renderer at all".
 */
class PreviewOverridesEncodingCompletenessTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  /**
   * The fields [JsonRpcServer.encodeRenderPayload] emits as typed `key=value` tokens. Kept as the
   * *only* hand-maintained list in the test: everything else is expected to ride the bag, which is
   * exactly the invariant the encoder's denylist `copy(...)` establishes.
   */
  private val wireTokens =
    setOf(
      "widthPx",
      "heightPx",
      "density",
      "localeTag",
      "fontScale",
      "uiMode",
      "orientation",
      "device",
      "captureAdvanceMs",
      "inspectionMode",
      "slotMode",
      "clearBackground",
    )

  /**
   * Every [PreviewOverrides] field set to a non-default value, so serializing it (with
   * `encodeDefaults = false`) yields a JSON object whose keys are the full field set. The
   * descriptor check below fails loudly if a newly added field is missing from here.
   */
  private val fullyPopulated =
    PreviewOverrides(
      widthPx = 411,
      heightPx = 891,
      minWidthPx = 100,
      minHeightPx = 120,
      maxWidthPx = 800,
      maxHeightPx = 900,
      density = 2.75f,
      localeTag = "fr-FR",
      fontScale = 1.3f,
      uiMode = UiMode.DARK,
      orientation = Orientation.LANDSCAPE,
      device = "id:pixel_5",
      captureAdvanceMs = 64L,
      clockEpochMillis = 1_700_000_000_000L,
      inspectionMode = false,
      slotMode = true,
      placeholderActive = true,
      clearBackground = true,
      svgBackground = FigmaSvgBackgroundMode.FULL_BLEED,
      material3Theme = Material3ThemeOverrides(colorScheme = mapOf("primary" to "#FF3366FF")),
      themeProvider = "com.example.BrandDarkThemeCatalog",
      wallpaper = WallpaperOverride(seedColor = "#FF8800"),
      ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT),
      gestures = GestureOverride(showHints = true, invoke = GestureKindOverride.PRIMARY),
      focus = FocusOverride(tabIndex = 2, direction = FocusDirection.Next),
      touchOverlay = true,
      talkBack = true,
      keyboard = KeyboardOverride(visible = true, pressedKey = "a"),
      permissions =
        PermissionsOverride(
          grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
        ),
      remoteCompose = RemoteComposeOverride(profile = RemoteComposeProfile.ANDROIDX),
      launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(width = 4, height = 2)),
      lottie = LottieOverride(progress = 0.42f),
      namedOverrides = mapOf("title" to PreviewOverrideValue.StringValue("Hello")),
    )

  /**
   * Guards the fixture itself: a field added to [PreviewOverrides] but not to [fullyPopulated]
   * would otherwise sail through the encoding assertion below (nothing to drop → nothing to
   * notice).
   */
  @Test
  fun fixtureCoversEveryDeclaredField() {
    val declared = declaredFieldNames()
    val populated =
      json.encodeToString(PreviewOverrides.serializer(), fullyPopulated).let {
        json.parseToJsonElement(it).jsonObject.keys
      }
    val missing = declared - populated
    assertTrue(
      "PreviewOverrides gained field(s) $missing — add non-default value(s) for them to " +
        "PreviewOverridesEncodingCompletenessTest.fullyPopulated so the encoding assertion " +
        "actually exercises them.",
      missing.isEmpty(),
    )
  }

  /** The list of typed tokens must stay a subset of the real field set. */
  @Test
  fun wireTokensNameRealFields() {
    val unknown = wireTokens - declaredFieldNames()
    assertTrue(
      "wireTokens names non-existent PreviewOverrides field(s): $unknown",
      unknown.isEmpty(),
    )
  }

  @Test(timeout = 60_000)
  fun everyOverrideFieldReachesTheRendererOnSomePath() {
    val payload =
      renderAndCapturePayload(json.encodeToString(PreviewOverrides.serializer(), fullyPopulated))
    val tokens =
      payload
        .split(';')
        .mapNotNull { it.trim().takeIf { t -> '=' in t } }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val bagKeys = decodeExtensionBagKeys(payload)

    val dropped =
      declaredFieldNames().filterNot { field ->
        if (field in wireTokens) field in tokens else field in bagKeys
      }
    assertTrue(
      "renderNow.overrides field(s) $dropped reach the renderer on no path — they are neither a " +
        "typed wire token nor present in the base64 `overrides=` bag, so a caller setting them " +
        "gets default pixels and no error (issue #3073). Payload: $payload",
      dropped.isEmpty(),
    )
  }

  @Test(timeout = 60_000)
  fun tokenisedFieldsAreNotRestatedInTheBag() {
    // The bag is built by nulling the tokenised fields, so nothing travels twice — the tokens are
    // the single source of truth for size/locale/uiMode/device, and `device` in particular has
    // already been resolved into widthPx/heightPx/density by the encoder.
    val payload =
      renderAndCapturePayload(json.encodeToString(PreviewOverrides.serializer(), fullyPopulated))
    val duplicated = decodeExtensionBagKeys(payload).intersect(wireTokens)
    assertTrue(
      "tokenised field(s) restated in the extension bag: $duplicated",
      duplicated.isEmpty(),
    )
  }

  @Test(timeout = 60_000)
  fun bagIsOmittedWhenOnlyTokenisedFieldsAreSet() {
    // Byte-identical-payload guard: an overrides object that is fully covered by typed tokens must
    // not start emitting an (empty) bag now that the emptiness check is structural.
    val payload = renderAndCapturePayload("""{"uiMode":"dark","widthPx":320}""")
    assertTrue("overrides= bag must be omitted: '$payload'", "overrides=" !in payload)
  }

  @Test(timeout = 60_000)
  fun previouslyDroppedFieldRidesTheBag() {
    // Spot-check the headline regression from #3073 rather than trusting the descriptor walk
    // alone: the deterministic wall clock (#1968) and the loading-state pin (#2646).
    val payload =
      renderAndCapturePayload("""{"clockEpochMillis":1700000000000,"placeholderActive":true}""")
    val bag = decodeExtensionBag(payload)
    assertEquals(1_700_000_000_000L, bag.clockEpochMillis)
    assertEquals(true, bag.placeholderActive)
  }

  private fun declaredFieldNames(): Set<String> {
    val descriptor = PreviewOverrides.serializer().descriptor
    return (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
  }

  private fun decodeExtensionBag(payload: String): PreviewOverrides =
    json.decodeFromString(PreviewOverrides.serializer(), decodeExtensionBagJson(payload))

  private fun decodeExtensionBagKeys(payload: String): Set<String> =
    json.parseToJsonElement(decodeExtensionBagJson(payload)).jsonObject.keys

  private fun decodeExtensionBagJson(payload: String): String {
    val token =
      payload.split(';').firstOrNull { it.trim().startsWith("overrides=") }
        ?: error("payload must carry an overrides= token: '$payload'")
    val b64 = token.substringAfter('=').trim()
    return String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
  }

  /**
   * Spins up a [JsonRpcServer] backed by a payload-capturing host with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received. Mirrors [ThemeProviderOverrideEncodingTest]'s
   * helper — kept file-local for the same reason.
   */
  private fun renderAndCapturePayload(overrides: String): String {
    val sourceKt = java.nio.file.Files.createTempFile("overrides-completeness-test", ".kt")
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

    val host = PayloadCapturingCompletenessHost()
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
      Thread({ server.run() }, "overrides-completeness-test").apply { isDaemon = true }
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
        "overrides-completeness-test-reader",
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
 * synchronously with a stub success result. File-local copy of the shape
 * [ThemeProviderOverrideEncodingTest] uses, so the two tests take no cross-file dependency.
 */
private class PayloadCapturingCompletenessHost : RenderHost {
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
                    classLoaderName = "overrides-completeness-test",
                  )
                )
              }
              // Never enqueued here: `submit` only accepts a Render. Present so the `when` stays
              // exhaustive over `RenderRequest` (issue #3749 added ParameterRows).
              is RenderRequest.ParameterRows -> {}
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-completeness-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingCompletenessHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
