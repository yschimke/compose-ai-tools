package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RecordingScriptHandlerRegistry] — the renderer-agnostic dispatch hub the desktop
 * and Android recording sessions both build from. Pins:
 *
 * - Registered handlers receive the event verbatim and their evidence is returned unchanged.
 * - Unregistered kinds yield well-formed `unsupportedEvidence` (defense in depth — the MCP layer
 *   filters most unknown kinds, but a direct daemon client could still send one).
 * - The dispatch context (`tNanos` / `tMs`) reaches the handler so it can drive virtual time.
 * - `appliedEvidence` / `unsupportedEvidence` copy the agent's `label`, `checkpointId`,
 *   `lifecycleEvent`, and `tags` onto the evidence so trace context survives.
 */
class RecordingScriptHandlerRegistryTest {

  @Test
  fun `dispatch routes to the registered handler and forwards context`() {
    var observedTNanos = -1L
    var observedTMs = -1L
    val registry =
      RecordingScriptHandlerRegistry(
        mapOf(
          "click" to
            RecordingScriptEventHandler { event, ctx ->
              observedTNanos = ctx.tNanos
              observedTMs = ctx.tMs
              appliedEvidence(event, "click handled")
            }
        )
      )

    val event = RecordingScriptEvent(tMs = 33L, kind = "click", pixelX = 10, pixelY = 20)
    val ctx = SimpleRecordingDispatchContext(tNanos = 33_000_000L, tMs = 33L)
    val evidence = registry.dispatch(event, ctx)

    assertEquals(33_000_000L, observedTNanos)
    assertEquals(33L, observedTMs)
    assertEquals(RecordingScriptEventStatus.APPLIED, evidence.status)
    assertEquals("click handled", evidence.message)
    assertEquals("click", evidence.kind)
    assertEquals(33L, evidence.tMs)
  }

  @Test
  fun `dispatch returns unsupported for unregistered kinds`() {
    val registry = RecordingScriptHandlerRegistry(emptyMap())
    val event = RecordingScriptEvent(tMs = 0L, kind = "unknown.kind", label = "L")

    val evidence = registry.dispatch(event, SimpleRecordingDispatchContext(0L, 0L))

    assertEquals(RecordingScriptEventStatus.UNSUPPORTED, evidence.status)
    assertEquals("unknown.kind", evidence.kind)
    assertEquals("L", evidence.label)
    assertTrue(
      "diagnostic must mention the kind so an agent can locate the offending event; " +
        "got '${evidence.message}'",
      evidence.message?.contains("unknown.kind") == true,
    )
  }

  @Test
  fun `appliedEvidence copies the agent's trace metadata onto the evidence`() {
    val event =
      RecordingScriptEvent(
        tMs = 7L,
        kind = "recording.probe",
        label = "after-restore",
        checkpointId = "c1",
        lifecycleEvent = "resume",
        tags = listOf("audit", "state-restoration"),
      )

    val evidence = appliedEvidence(event, "probe marker reached")

    assertEquals("after-restore", evidence.label)
    assertEquals("c1", evidence.checkpointId)
    assertEquals("resume", evidence.lifecycleEvent)
    assertEquals(listOf("audit", "state-restoration"), evidence.tags)
    assertEquals("probe marker reached", evidence.message)
    assertEquals(RecordingScriptEventStatus.APPLIED, evidence.status)
  }

  @Test
  fun `unsupportedEvidence copies trace metadata and forces a message`() {
    val event = RecordingScriptEvent(tMs = 12L, kind = "keyDown", keyCode = "Enter", label = "L")

    val evidence = unsupportedEvidence(event, "key dispatch is not implemented")

    assertEquals(RecordingScriptEventStatus.UNSUPPORTED, evidence.status)
    assertEquals("key dispatch is not implemented", evidence.message)
    assertEquals("L", evidence.label)
    assertEquals("keyDown", evidence.kind)
  }

  @Test
  fun `knownKinds reflects the registered handler set`() {
    val sentinel = RecordingScriptEventHandler { event, _ -> appliedEvidence(event, "noop") }
    val registry =
      RecordingScriptHandlerRegistry(mapOf("click" to sentinel, "recording.probe" to sentinel))

    assertEquals(setOf("click", "recording.probe"), registry.knownKinds())
  }

  @Test
  fun `wireName round-trips with toInteractiveInputKindOrNull for every enum value`() {
    // Live mode synthesises a RecordingScriptEvent from a typed RecordingInputParams via
    // `kind.wireName()`; the scripted path resolves the same wire name back to the enum via
    // `toInteractiveInputKindOrNull`. Pin that the round-trip is bijective for every enum value
    // so adding a new InteractiveInputKind keeps both directions wired (the reverse map is
    // derived, so this test catches "added the enum, forgot the wire-name entry").
    for (kind in InteractiveInputKind.entries) {
      val wire = kind.wireName()
      assertEquals(
        "wireName -> toInteractiveInputKindOrNull must be the identity on enum '$kind'",
        kind,
        wire.toInteractiveInputKindOrNull(),
      )
    }
  }

  @Test
  fun `dispatch fires observers before the handler runs`() {
    val seen = mutableListOf<String>()
    val observer = RecordingScriptDispatchObserver { event, _ -> seen += "observer:${event.kind}" }
    val handler = RecordingScriptEventHandler { event, _ ->
      seen += "handler:${event.kind}"
      appliedEvidence(event)
    }
    val registry =
      RecordingScriptHandlerRegistry(
        handlers = mapOf("input.click" to handler),
        observers = listOf(observer),
      )

    registry.dispatch(
      RecordingScriptEvent(tMs = 0L, kind = "input.click"),
      SimpleRecordingDispatchContext(0L, 0L),
    )

    assertEquals(listOf("observer:input.click", "handler:input.click"), seen)
  }

  @Test
  fun `observer faults are isolated and do not poison dispatch`() {
    val faulty = RecordingScriptDispatchObserver { _, _ -> throw RuntimeException("boom") }
    val handler = RecordingScriptEventHandler { event, _ -> appliedEvidence(event, "ok") }
    val registry =
      RecordingScriptHandlerRegistry(
        handlers = mapOf("input.click" to handler),
        observers = listOf(faulty),
      )

    val evidence =
      registry.dispatch(
        RecordingScriptEvent(tMs = 0L, kind = "input.click"),
        SimpleRecordingDispatchContext(0L, 0L),
      )

    assertEquals(RecordingScriptEventStatus.APPLIED, evidence.status)
    assertEquals("ok", evidence.message)
  }

  @Test
  fun `observers fire even when no handler is registered`() {
    var observerSaw: String? = null
    val observer = RecordingScriptDispatchObserver { event, _ -> observerSaw = event.kind }
    val registry =
      RecordingScriptHandlerRegistry(handlers = emptyMap(), observers = listOf(observer))

    registry.dispatch(
      RecordingScriptEvent(tMs = 0L, kind = "input.click"),
      SimpleRecordingDispatchContext(0L, 0L),
    )

    assertEquals("input.click", observerSaw)
  }

  @Test
  fun `dispatch returns the handler's own evidence object verbatim`() {
    val sentinelEvidence = appliedEvidence(RecordingScriptEvent(tMs = 0L, kind = "click"))
    val registry =
      RecordingScriptHandlerRegistry(
        mapOf("click" to RecordingScriptEventHandler { _, _ -> sentinelEvidence })
      )

    val returned =
      registry.dispatch(
        RecordingScriptEvent(tMs = 0L, kind = "click"),
        SimpleRecordingDispatchContext(0L, 0L),
      )

    assertSame("registry must not re-wrap the handler's evidence", sentinelEvidence, returned)
  }
}
