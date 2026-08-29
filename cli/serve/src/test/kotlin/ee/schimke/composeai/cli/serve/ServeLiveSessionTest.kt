package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeLiveSessionTest {

  /** A `setOverrides` message, as the viewer sends one when a knob changes. */
  private val SET_DARK_OVERRIDES = """{"type":"setOverrides","overrides":{"uiMode":"dark"}}"""

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-live").toFile().also { it.deleteOnExit() }

  private fun host(session: FakeRenderSession): ServeRenderHost =
    ServeRenderHost(session, listOf(ServePreview(previewId, "Red")), renderTimeoutSeconds = 30)

  private fun typeOf(text: String): String =
    Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

  @Test
  fun `tryStart returns null when streaming is unsupported`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false
    host(session).use { h ->
      assertNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
      )
    }
  }

  @Test
  fun `tryStart forwards the daemon's original failure to onUnavailable`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false → streamStart throws
    host(session).use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          system = "compose-m3",
          onUnavailable = { reason = it },
        )
      )
      // The daemon's own exception message is carried through, not swallowed into a log.
      assertEquals("streaming not supported", reason)
    }
  }

  @Test
  fun `tryStart forwards the generic no-held-session reason when the daemon sent none`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true, heldSession = false)
    host(session).use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          system = "compose-m3",
          onUnavailable = { reason = it },
        )
      )
      assertTrue(
        reason?.contains("could not hold an interactive session") == true,
        "expected the generic held-session reason, got: $reason",
      )
    }
  }

  @Test
  fun `tryStart prefers the daemon's fallbackReason for a non-held session`() {
    // The daemon accepted stream/start but couldn't hold the session AND told us why — surface it
    // rather than the generic text (Codex #2515 review).
    val session =
      FakeRenderSession(
        newRenderRoot(),
        streaming = true,
        heldSession = false,
        heldFallbackReason = "UnsupportedOperationException: interactive session already held",
      )
    host(session).use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          system = "compose-m3",
          onUnavailable = { reason = it },
        )
      )
      assertEquals("UnsupportedOperationException: interactive session already held", reason)
    }
  }

  @Test
  fun `tryStart refuses an undeclared themeProvider in the opening query`() {
    // The socket's *initial* overrides are validated too, not just later setOverrides / switch
    // messages — otherwise a direct WebSocket client asking for a theme this catalog never
    // declared would be silently subscribed to a default-themed stream, and a later frame would
    // clear the viewer's error overlay while the wrong stream kept running (Codex #2923 review).
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    val themed =
      ServeRenderHost(
        session,
        listOf(ServePreview(previewId, "Red")),
        declaredThemes = listOf(ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog")),
        renderTimeoutSeconds = 30,
      )
    themed.use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          mapOf("themeProvider" to "com.example.NopeThemeCatalog"),
          send = {},
          system = "compose-m3",
          onUnavailable = { reason = it },
        )
      )
      assertNotNull(reason)
      assertTrue(
        reason.contains("com.example.NopeThemeCatalog"),
        "expected the rejected provider in the reason, got: $reason",
      )
    }
  }

  @Test
  fun `tryStart accepts a declared themeProvider in the opening query`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    val themed =
      ServeRenderHost(
        session,
        listOf(ServePreview(previewId, "Red")),
        declaredThemes = listOf(ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog")),
        renderTimeoutSeconds = 30,
      )
    themed.use { h ->
      val live =
        ServeLiveSession.tryStart(
          h,
          previewId,
          mapOf("themeProvider" to "com.example.BrandDarkThemeCatalog"),
          send = {},
          system = "compose-m3",
        )
      assertNotNull(live)
      live.close()
    }
  }

  @Test
  fun `daemon-pushed frames are forwarded as frame messages`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add, system = "compose-m3")
      )
      val fsid = assertNotNull(session.lastFrameStreamId)
      val payload = Base64.getEncoder().encodeToString("xy".toByteArray())

      session.emitStreamFrame(fsid, seq = 5, payloadBase64 = payload)

      assertEquals(1, sent.size)
      val obj = Json.parseToJsonElement(sent[0]).jsonObject
      assertEquals("frame", obj.getValue("type").jsonPrimitive.content)
      assertEquals(payload, obj.getValue("dataBase64").jsonPrimitive.content)
      // The socket numbers its own frames from 0 rather than relaying the daemon's per-stream
      // counter (which is 5 here) — see the next test for why that matters.
      assertEquals("0", obj.getValue("seq").jsonPrimitive.content)
    }
  }

  @Test
  fun `frame seq keeps climbing across a stream restart`() {
    // Issue #4285. Every `setOverrides` closes the held daemon stream and opens a replacement whose
    // own `seq` counts from zero. Relaying that made the socket's sequence jump BACKWARDS on any
    // knob change — harmless while the browser painted whatever it received, and fatal once the
    // client uses `seq` to drop stale frames: a viewer several frames in would reject the entire
    // restarted stream and freeze the lane for good.
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = sent::add,
            system = "compose-m3",
          )
        )
      val payload = Base64.getEncoder().encodeToString("xy".toByteArray())
      session.emitStreamFrame(assertNotNull(session.lastFrameStreamId), 7, payloadBase64 = payload)
      session.emitStreamFrame(assertNotNull(session.lastFrameStreamId), 8, payloadBase64 = payload)

      // Restart the held stream the way a knob change does; the new one numbers from zero again.
      live.onClientMessage(SET_DARK_OVERRIDES)
      session.emitStreamFrame(assertNotNull(session.lastFrameStreamId), 0, payloadBase64 = payload)
      session.emitStreamFrame(assertNotNull(session.lastFrameStreamId), 1, payloadBase64 = payload)

      val seqs =
        sent
          .map { Json.parseToJsonElement(it).jsonObject }
          .filter { it["type"]?.jsonPrimitive?.content == "frame" }
          .map { it.getValue("seq").jsonPrimitive.content.toLong() }
      assertEquals(listOf(0L, 1L, 2L, 3L), seqs)
    }
  }

  @Test
  fun `requested codec is forwarded to stream start and webp frames keep their codec`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          StreamCodec.WEBP,
          null,
          sent::add,
          system = "compose-m3",
        )
      )
      assertEquals(StreamCodec.WEBP, session.lastCodec)
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 1,
        payloadBase64 = "AA",
        codec = StreamCodec.WEBP,
      )
      val obj = Json.parseToJsonElement(sent.last()).jsonObject
      assertEquals("webp", obj.getValue("codec").jsonPrimitive.content)
    }
  }

  @Test
  fun `unchanged heartbeat frames (no payload) are not forwarded`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add, system = "compose-m3")
      )
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 0,
        payloadBase64 = null,
      )
      assertTrue(sent.isEmpty())
    }
  }

  @Test
  fun `frames and heartbeats are recorded for the status lane`() {
    // #4281 — `/status.json` could say how many sockets were open and nothing about what they were
    // achieving, because streamed frames never pass through the render host's own perf counters.
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val stats = LiveFramePerfStats()
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = {},
            system = "compose-m3",
            frameStats = stats,
          )
        )
      val fsid = assertNotNull(session.lastFrameStreamId)
      val payload = Base64.getEncoder().encodeToString(ByteArray(64))
      session.emitStreamFrame(fsid, seq = 1, payloadBase64 = payload)
      session.emitStreamFrame(fsid, seq = 2, payloadBase64 = null) // unchanged heartbeat
      session.emitStreamFrame(fsid, seq = 3, payloadBase64 = payload)

      val open = assertNotNull(stats.snapshot("compose-m3"))
      assertEquals(2L, open.frames)
      assertEquals(1L, open.heartbeats)
      assertEquals(payload.length * 2L, open.payloadBytes)
      assertEquals(1, open.openSockets)
      assertEquals(previewId, open.streams.single().previewId)

      live.close()
      val closed = assertNotNull(stats.snapshot("compose-m3"))
      assertEquals(0, closed.openSockets)
      assertEquals(2L, closed.frames, "totals survive the socket that produced them")
    }
  }

  @Test
  fun `a socket that never opened a stream is not counted as live`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false
    host(session).use { h ->
      val stats = LiveFramePerfStats()
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          system = "compose-m3",
          frameStats = stats,
        )
      )
      assertEquals(0, assertNotNull(stats.snapshot("compose-m3")).openSockets)
    }
  }

  @Test
  fun `input messages dispatch interactive input`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      live.onClientMessage("""{"type":"input","kind":"click","pixelX":10,"pixelY":20}""")
      assertEquals(1, session.interactiveInputs.size)
      val input = session.interactiveInputs[0]
      assertEquals(InteractiveInputKind.CLICK, input.kind)
      assertEquals(10, input.pixelX)
      assertEquals(20, input.pixelY)
    }
  }

  @Test
  fun `pointer drag, scroll and key inputs are forwarded with their fields`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      live.onClientMessage(
        """{"type":"input","kind":"pointerDown","pixelX":3,"pixelY":4,"pointerId":1}"""
      )
      live.onClientMessage(
        """{"type":"input","kind":"pointerMove","pixelX":7,"pixelY":9,"pointerId":1}"""
      )
      live.onClientMessage(
        """{"type":"input","kind":"pointerUp","pixelX":7,"pixelY":9,"pointerId":1}"""
      )
      live.onClientMessage("""{"type":"input","kind":"rotaryScroll","scrollDeltaY":-12.5}""")
      live.onClientMessage("""{"type":"input","kind":"keyDown","keyCode":"66"}""")

      val kinds = session.interactiveInputs.map { it.kind }
      assertEquals(
        listOf(
          InteractiveInputKind.POINTER_DOWN,
          InteractiveInputKind.POINTER_MOVE,
          InteractiveInputKind.POINTER_UP,
          InteractiveInputKind.ROTARY_SCROLL,
          InteractiveInputKind.KEY_DOWN,
        ),
        kinds,
      )
      assertEquals(1, session.interactiveInputs[0].pointerId)
      assertEquals(-12.5f, session.interactiveInputs[3].scrollDeltaY)
      assertEquals("66", session.interactiveInputs[4].keyCode)
    }
  }

  @Test
  fun `an unknown input kind yields an error and dispatches nothing`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = sent::add,
            system = "compose-m3",
          )
        )
      live.onClientMessage("""{"type":"input","kind":"telepathy"}""")
      assertEquals("error", typeOf(sent.last()))
      assertTrue(session.interactiveInputs.isEmpty())
    }
  }

  @Test
  fun `setOverrides restarts the held stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      val first = assertNotNull(session.lastFrameStreamId)
      assertEquals(1, session.streamStarts.get())

      live.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"dark"}}""")

      assertEquals(2, session.streamStarts.get(), "new overrides should restart the stream")
      assertEquals(listOf(first), session.streamStops, "the previous stream should be stopped")
    }
  }

  /**
   * A declared knob reaches the daemon **typed**, both in the socket's opening query and in the
   * `setOverrides` that follows it.
   *
   * The lane's existing coverage is all `uiMode`, which is a display axis; a named knob travels a
   * different route (`knob.<key>` → `knobKindsFor` → `PreviewOverrides.namedOverrides`) and reaches
   * a different part of the render. Untyped it would land as a `StringValue("true")`, which a
   * `previewOverrideBoolean` knob cannot read — so the composition would fall back to the author
   * default and the live lane would quietly render the un-overridden state while the viewer showed
   * the box ticked. That is the shape reported in yschimke/wear-m3-catalog#66, and this pins the
   * serve half of it.
   */
  @Test
  fun `a declared knob reaches the daemon typed, on connect and on setOverrides`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    val preview =
      ServePreview(
        previewId,
        "Red",
        overrides =
          listOf(
            ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration(
              key = "secondary",
              type = "bool",
              label = "secondary",
              default =
                ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue(false),
            )
          ),
      )
    ServeRenderHost(session, listOf(preview), renderTimeoutSeconds = 30).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            mapOf("knob.secondary" to "true"),
            send = {},
            system = "compose-m3",
          )
        )
      assertEquals(
        mapOf(
          "secondary" to
            ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue(true)
        ),
        session.lastStreamOverrides?.namedOverrides,
        "the connect query's knob should reach stream/start typed",
      )

      // …and the replay the viewer sends on open, which REPLACES the whole bag.
      live.onClientMessage("""{"type":"setOverrides","overrides":{"knob.secondary":"true"}}""")
      assertEquals(
        mapOf(
          "secondary" to
            ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue(true)
        ),
        session.lastStreamOverrides?.namedOverrides,
        "setOverrides' knob should reach the restarted stream typed",
      )
    }
  }

  @Test
  fun `a dark-first system drops uiMode from live setOverrides and switch messages`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      // The REAL per-system policy, resolved from the catalog id — not an injected stand-in. A
      // uiMode the parser would reject proves it was dropped before parsing.
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = sent::add,
            system = "confetti-wear",
          )
        )

      live.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"chartreuse"}}""")
      live.onClientMessage(
        """{"type":"switch","previewId":"$previewId","overrides":{"uiMode":"chartreuse"}}"""
      )

      assertEquals(2, session.streamStarts.get())
      assertTrue(sent.none { typeOf(it) == "error" })
    }
  }

  @Test
  fun `a light-capable system keeps uiMode on the live lane`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = sent::add,
            system = "compose-m3",
          )
        )

      // Same message, non-Wear catalog: the override reaches the parser, which rejects the bogus
      // value and does NOT restart the stream.
      live.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"chartreuse"}}""")

      assertEquals(1, session.streamStarts.get())
      assertTrue(sent.any { typeOf(it) == "error" })
    }
  }

  @Test
  fun `two live sessions for the same preview share one daemon stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val a = CopyOnWriteArrayList<String>()
      val b = CopyOnWriteArrayList<String>()
      assertNotNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), send = a::add, system = "compose-m3")
      )
      assertNotNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), send = b::add, system = "compose-m3")
      )

      assertEquals(1, session.streamStarts.get(), "two clients should ride one daemon stream/start")
      assertEquals(1, h.activeStreamCount())

      // One upstream frame fans out to both clients.
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 4,
        payloadBase64 = "AA",
      )
      assertEquals(1, a.size)
      assertEquals(1, b.size)
    }
  }

  @Test
  fun `switch moves the connection to another preview`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    val blue = "com.example.Blue"
    ServeRenderHost(
        session,
        listOf(ServePreview(previewId, "Red"), ServePreview(blue, "Blue")),
        renderTimeoutSeconds = 30,
      )
      .use { h ->
        val live =
          assertNotNull(
            ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
          )
        val firstFsid = assertNotNull(session.lastFrameStreamId)
        assertEquals(1, session.streamStarts.get())

        live.onClientMessage("""{"type":"switch","previewId":"$blue"}""")

        assertEquals(2, session.streamStarts.get(), "switch opens a stream for the new preview")
        assertEquals(
          listOf(firstFsid),
          session.streamStops,
          "the previous preview's stream is dropped",
        )
      }
  }

  @Test
  fun `switching to an unknown preview errors and keeps the current stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(
            h,
            previewId,
            emptyMap(),
            send = sent::add,
            system = "compose-m3",
          )
        )
      val fsid = assertNotNull(session.lastFrameStreamId)

      live.onClientMessage("""{"type":"switch","previewId":"com.example.Missing"}""")

      assertEquals("error", typeOf(sent.last()))
      assertTrue(session.streamStops.isEmpty(), "a failed switch must not drop the working stream")
      // The original stream is still live.
      session.emitStreamFrame(fsid, seq = 1, payloadBase64 = "AA")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `a visibility message reaches the daemon stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      val fsid = assertNotNull(session.lastFrameStreamId)

      live.onClientMessage("""{"type":"visibility","visible":false}""")
      live.onClientMessage("""{"type":"visibility","visible":true}""")

      assertEquals(
        listOf<Triple<String, Boolean, Int?>>(Triple(fsid, false, null), Triple(fsid, true, null)),
        session.streamVisibilities.toList(),
      )
    }
  }

  @Test
  fun `a hidden socket re-states its visibility on the stream an override change opens`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      live.onClientMessage("""{"type":"visibility","visible":false,"fps":2}""")

      // A knob change while the tab is hidden restarts the held stream — and the daemon starts
      // every stream visible, so without re-stating it the lane silently returns to full rate
      // against a client that never said it was looking again.
      live.onClientMessage(SET_DARK_OVERRIDES)
      val restarted = assertNotNull(session.lastFrameStreamId)

      assertEquals(
        Triple(restarted, false, 2),
        session.streamVisibilities.last(),
        "the restarted stream should be told it is hidden too",
      )
    }
  }

  @Test
  fun `closing the live session stops the stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live =
        assertNotNull(
          ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}, system = "compose-m3")
        )
      val fsid = assertNotNull(session.lastFrameStreamId)
      live.close()
      assertEquals(listOf(fsid), session.streamStops)
    }
  }
}
