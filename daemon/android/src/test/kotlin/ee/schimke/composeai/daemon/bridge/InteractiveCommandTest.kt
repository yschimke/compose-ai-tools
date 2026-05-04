package ee.schimke.composeai.daemon.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape coverage for the v3 Android-interactive bridge primitives (INTERACTIVE-ANDROID.md
 * § 3, PR A scope). The held-rule loop and host-side `AndroidInteractiveSession` land in PR B —
 * here we only verify that the cross-classloader handoff surface itself is wired correctly:
 *
 *  * [InteractiveCommand] is a sealed family carrying only `java.*` types and bridge-package
 *    types, so it crosses the Robolectric do-not-acquire boundary unchanged.
 *  * [SandboxSlot.interactiveCommands] enqueues and dequeues each variant in FIFO order.
 *  * [DaemonHostBridge.reset] drains every slot's interactive queue and re-points slot 0 at a
 *    fresh `LinkedBlockingQueue`-equivalent (i.e. a leftover command from a previous host lifecycle
 *    cannot leak into the next `start`).
 *  * [DaemonHostBridge.configureSlotCount] allocates `interactiveCommands` for every standalone
 *    slot, so a v3-capable `sandboxCount=2` configuration has a queue on slot 1.
 *
 * Plain JUnit (no `@RunWith(SandboxHoldingRunner::class)`) — the bridge is host-side state, not
 * sandboxed; the do-not-acquire rule only applies inside Robolectric's `InstrumentingClassLoader`.
 */
class InteractiveCommandTest {

  @After
  fun tearDown() {
    // Subsequent tests in the suite (e.g. `RobolectricHostTest`) start from
    // `DaemonHostBridge.reset()` themselves, but resetting here too keeps the per-test isolation
    // explicit and makes `slotsRef` shrink back to slot 0 between cases in this file.
    DaemonHostBridge.reset()
  }

  @Test
  fun queueRoundTripsEveryVariantInFifoOrder() {
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    val startLatch = CountDownLatch(1)
    val startError = AtomicReference<Throwable?>(null)
    val dispatchLatch = CountDownLatch(1)
    val dispatchError = AtomicReference<Throwable?>(null)
    val closeLatch = CountDownLatch(1)

    val start =
      InteractiveCommand.Start(
        streamId = "stream-A",
        previewClassName = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        previewFunctionName = "RedSquare",
        widthPx = 320,
        heightPx = 320,
        density = 2.0f,
        backgroundColor = 0L,
        showBackground = true,
        device = null,
        outputBaseName = "stream-A-frame",
        replyLatch = startLatch,
        replyError = startError,
      )
    val dispatch =
      InteractiveCommand.Dispatch(
        streamId = "stream-A",
        kind = "click",
        pixelX = 160,
        pixelY = 160,
        replyLatch = dispatchLatch,
        replyError = dispatchError,
      )
    val render = InteractiveCommand.Render(streamId = "stream-A", requestId = 42L)
    val close = InteractiveCommand.Close(streamId = "stream-A", replyLatch = closeLatch)

    slot.interactiveCommands.put(start)
    slot.interactiveCommands.put(dispatch)
    slot.interactiveCommands.put(render)
    slot.interactiveCommands.put(close)

    val drained = mutableListOf<InteractiveCommand>()
    while (true) {
      val next = slot.interactiveCommands.poll(1, TimeUnit.SECONDS) ?: break
      drained.add(next)
      if (drained.size == 4) break
    }

    assertEquals(listOf<InteractiveCommand>(start, dispatch, render, close), drained)
    assertTrue(
      "queue should be empty after draining all four commands",
      slot.interactiveCommands.isEmpty(),
    )
    // Reply scaffolding round-trips — i.e. the same latch / AtomicReference instances we put in
    // come back out by reference, not via copy. PR B's session class relies on this for the
    // host-side await semantics.
    val drainedStart = drained[0] as InteractiveCommand.Start
    assertSame(startLatch, drainedStart.replyLatch)
    assertSame(startError, drainedStart.replyError)
    assertNull("replyError defaults to null", drainedStart.replyError.get())
  }

  @Test
  fun resetClearsLeftoverInteractiveCommands() {
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    slot.interactiveCommands.put(
      InteractiveCommand.Render(streamId = "stream-leftover", requestId = 1L)
    )
    assertEquals(1, slot.interactiveCommands.size)

    DaemonHostBridge.reset()

    val slotAfterReset = DaemonHostBridge.slot(0)
    assertSame(
      "slot 0's instance is stable across reset (legacy aliasing contract)",
      slot,
      slotAfterReset,
    )
    assertTrue(
      "reset must drain leftover interactive commands so a previous host lifecycle does not " +
        "leak Start/Dispatch/Close into the next session",
      slotAfterReset.interactiveCommands.isEmpty(),
    )
  }

  @Test
  fun configureSlotCountAllocatesInteractiveQueuePerSlot() {
    DaemonHostBridge.reset()

    DaemonHostBridge.configureSlotCount(2)
    val slot0 = DaemonHostBridge.slot(0)
    val slot1 = DaemonHostBridge.slot(1)

    assertNotSame(
      "standalone slot 1 must have its own interactive queue distinct from slot 0's",
      slot0.interactiveCommands,
      slot1.interactiveCommands,
    )
    val cmd =
      InteractiveCommand.Render(streamId = "stream-pinned-to-slot-1", requestId = 99L)
    slot1.interactiveCommands.put(cmd)
    assertTrue(
      "enqueue on slot 1 must not appear on slot 0 — sandbox-pool isolation",
      slot0.interactiveCommands.isEmpty(),
    )
    assertEquals(cmd, slot1.interactiveCommands.poll(1, TimeUnit.SECONDS))
  }

  @Test
  fun dispatchStateRecreateRoundTripsThroughTheBridge() {
    // Pin the cross-classloader contract for the new variant: only `java.*` types in the
    // payload, round-trips through `interactiveCommands` by reference. Parameterless — the
    // recreate target is the held composition this stream is driving.
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    val cmd =
      InteractiveCommand.DispatchStateRecreate(
        streamId = "stream-A",
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    slot.interactiveCommands.put(cmd)

    val drained = slot.interactiveCommands.poll(1, TimeUnit.SECONDS)
    assertSame("queue must round-trip the same instance, not a copy", cmd, drained)
    val asRecreate = drained as InteractiveCommand.DispatchStateRecreate
    assertSame(replyLatch, asRecreate.replyLatch)
    assertSame(replyError, asRecreate.replyError)
    assertSame(replyApplied, asRecreate.replyApplied)
    assertNull("replyError defaults to null", asRecreate.replyError.get())
    assertFalse("replyApplied defaults to false", asRecreate.replyApplied.get())
  }

  @Test
  fun dispatchPreviewReloadRoundTripsThroughTheBridge() {
    // Pin the cross-classloader contract for the new variant: only `java.*` types in the
    // payload, round-trips through `interactiveCommands` by reference. No event-specific fields
    // — reload is parameterless; the held composition is implicit.
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    val cmd =
      InteractiveCommand.DispatchPreviewReload(
        streamId = "stream-A",
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    slot.interactiveCommands.put(cmd)

    val drained = slot.interactiveCommands.poll(1, TimeUnit.SECONDS)
    assertSame("queue must round-trip the same instance, not a copy", cmd, drained)
    val asReload = drained as InteractiveCommand.DispatchPreviewReload
    assertSame(replyLatch, asReload.replyLatch)
    assertSame(replyError, asReload.replyError)
    assertSame(replyApplied, asReload.replyApplied)
    assertNull("replyError defaults to null", asReload.replyError.get())
    assertFalse("replyApplied defaults to false", asReload.replyApplied.get())
  }

  @Test
  fun dispatchLifecycleRoundTripsThroughTheBridge() {
    // Pin the cross-classloader contract for the new variant: only `java.*` types in the
    // payload, round-trips through `interactiveCommands` by reference (so the host's latch /
    // atomic refs come back out unchanged for the await pattern). Mirrors
    // `dispatchSemanticsActionRoundTripsThroughTheBridge` for the lifecycle variant.
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyApplied = AtomicBoolean(false)
    val cmd =
      InteractiveCommand.DispatchLifecycle(
        streamId = "stream-A",
        lifecycleEvent = "pause",
        replyLatch = replyLatch,
        replyError = replyError,
        replyApplied = replyApplied,
      )
    slot.interactiveCommands.put(cmd)

    val drained = slot.interactiveCommands.poll(1, TimeUnit.SECONDS)
    assertSame("queue must round-trip the same instance, not a copy", cmd, drained)
    val asLifecycle = drained as InteractiveCommand.DispatchLifecycle
    assertSame(replyLatch, asLifecycle.replyLatch)
    assertSame(replyError, asLifecycle.replyError)
    assertSame(replyApplied, asLifecycle.replyApplied)
    assertNull("replyError defaults to null", asLifecycle.replyError.get())
    assertFalse("replyApplied defaults to false", asLifecycle.replyApplied.get())
  }

  @Test
  fun dispatchSemanticsActionRoundTripsThroughTheBridge() {
    // Pin the cross-classloader contract for the new variant: only `java.*` types in the payload,
    // round-trips through `interactiveCommands` by reference (so the host's latch / atomic refs
    // come back out unchanged for the await pattern). Mirrors the assertion shape the input
    // `Dispatch` variant gets in `queueRoundTripsEveryVariantInFifoOrder`.
    DaemonHostBridge.reset()
    val slot = DaemonHostBridge.slot(0)
    val replyLatch = CountDownLatch(1)
    val replyError = AtomicReference<Throwable?>(null)
    val replyMatched = AtomicBoolean(false)
    val cmd =
      InteractiveCommand.DispatchSemanticsAction(
        streamId = "stream-A",
        actionKind = "click",
        nodeContentDescription = "Save",
        replyLatch = replyLatch,
        replyError = replyError,
        replyMatched = replyMatched,
      )
    slot.interactiveCommands.put(cmd)

    val drained = slot.interactiveCommands.poll(1, TimeUnit.SECONDS)
    assertSame("queue must round-trip the same instance, not a copy", cmd, drained)
    val asAction = drained as InteractiveCommand.DispatchSemanticsAction
    assertSame(replyLatch, asAction.replyLatch)
    assertSame(replyError, asAction.replyError)
    assertSame(replyMatched, asAction.replyMatched)
    assertNull("replyError defaults to null", asAction.replyError.get())
    assertFalse("replyMatched defaults to false", asAction.replyMatched.get())
  }
}
