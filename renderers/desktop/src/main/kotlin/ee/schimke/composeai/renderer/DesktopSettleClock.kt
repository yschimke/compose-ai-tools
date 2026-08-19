package ee.schimke.composeai.renderer

import androidx.compose.ui.ImageComposeScene
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi

/** One 60Hz frame, the step a settle walk advances in. Mirrors discovery's `SETTLE_FRAME_MS`. */
public const val SETTLE_FRAME_MS: Long = 16L

/**
 * Hard ceiling on a settle window, in milliseconds. Mirrors `preview-annotations`' `MAX_SETTLE_MS`
 * — discovery clamps to it, and this repeats the bound so a hand-built argv can't ask the renderer
 * to walk forever.
 */
public const val MAX_SETTLE_MS: Int = 5_000

/**
 * The virtual clock a `@SettledPreview` still is captured on.
 *
 * ### Why this exists
 *
 * The still path draws through [ImageComposeScene], and `scene.render(nanoTime)` drives Compose's
 * frame clock but *not* `kotlinx.coroutines.delay`. A scene left on its default coroutine context
 * resolves a `LaunchedEffect { delay(100) }` against **wall time**, so the reveal it gates never
 * arrives inside a render that takes microseconds — which is exactly the "captures as its first
 * frame" report in issue #4202, and why raising the nanoTime alone changes nothing.
 *
 * Passing this dispatcher as the scene's `coroutineContext` puts `delay` on the same virtual
 * timeline as the frame clock: [advanceTo] resumes every continuation whose deadline has passed, in
 * order, before the matching `render(nanoTime)` observes the state they wrote.
 *
 * ### The quiescence signal
 *
 * Because the dispatcher owns the delay queue, it can answer the question the pixels can't: *is
 * something still scheduled to happen?* A reveal that hasn't started yet looks pixel-identical to a
 * settled one — sampling frames alone latches on the empty container and calls it stable. But a
 * pending `delay` is visible here, and a running animation shows up as
 * [ImageComposeScene.hasInvalidations]. A frame with neither is genuinely finished, so the walk can
 * stop at the reveal's end instead of always paying the full window.
 *
 * Deliberately installed **only** on settled captures: every other still keeps the scene's default
 * context and renders exactly the bytes it did before.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class DesktopSettleClock : CoroutineDispatcher(), Delay {

  /** Current virtual time, in milliseconds. */
  public var nowMs: Long = 0L
    private set

  private val ready = ArrayDeque<Runnable>()
  private val scheduled = PriorityQueue<Scheduled>(compareBy({ it.dueMs }, { it.seq }))
  private var seq = 0L

  private class Scheduled(val dueMs: Long, val seq: Long, val resume: Runnable)

  public override fun dispatch(context: CoroutineContext, block: Runnable) {
    ready.addLast(block)
  }

  public override fun scheduleResumeAfterDelay(
    timeMillis: Long,
    continuation: CancellableContinuation<Unit>,
  ) {
    val entry =
      Scheduled(nowMs + timeMillis.coerceAtLeast(0), seq++) {
        with(continuation) { resumeUndispatched(Unit) }
      }
    scheduled.add(entry)
    // Drop the entry when its coroutine is cancelled — a `LaunchedEffect { delay(…) }` whose key
    // changes, or whose composable leaves the composition, cancels the continuation but would
    // otherwise leave a corpse in the queue until its original deadline. [hasScheduledWork] cannot
    // tell a corpse from live work, so auto settle would walk the full [maxMs] bound instead of
    // stopping when the composition actually went quiet.
    continuation.invokeOnCancellation { scheduled.remove(entry) }
  }

  /** Whether any `delay` is still outstanding — the half of quiescence pixels cannot see. */
  public fun hasScheduledWork(): Boolean = scheduled.isNotEmpty()

  /**
   * Whether anything at all is still owed: a delay not yet due, **or** a runnable already
   * dispatched but not yet executed.
   *
   * The second half matters because `scene.render(...)` itself can dispatch work — a recomposition
   * that conditionally introduces a `LaunchedEffect` queues it *after* the preceding [advanceTo]
   * drained. With nothing scheduled and no invalidation left, a quiescence check that only
   * consulted [hasScheduledWork] would stop right there and capture the pre-reveal frame
   * (issue #4202 review).
   */
  public fun hasPendingWork(): Boolean = ready.isNotEmpty() || scheduled.isNotEmpty()

  /** Runs everything dispatched but not yet executed, including work those runnables dispatch. */
  public fun drain() {
    while (true) ready.removeFirstOrNull()?.run() ?: break
  }

  /**
   * Moves virtual time to [targetMs], resuming each delayed continuation at its own deadline rather
   * than all at once — a `delay(50)` chained after a `delay(50)` must see 100ms, not 50.
   */
  public fun advanceTo(targetMs: Long) {
    drain()
    while (true) {
      val next = scheduled.peek() ?: break
      if (next.dueMs > targetMs) break
      scheduled.poll()
      nowMs = maxOf(nowMs, next.dueMs)
      next.resume.run()
      drain()
    }
    nowMs = maxOf(nowMs, targetMs)
    drain()
  }
}

/**
 * Advances [scene] on [clock] until the composition is quiescent, or until [windowMs] of virtual
 * time is spent, and returns the virtual time the walk stopped at.
 *
 * [autoDetect] `false` walks the whole window — the caller asked for an exact coordinate
 * (`@SettledPreview(afterMs = …)`), and stopping early at a lull the author didn't mean would make
 * the annotation's number a lie.
 *
 * Quiescence is "nothing scheduled and nothing invalidated", checked *after* a frame is rendered so
 * the frame that lands the last animation value is the one being judged. An animation that never
 * ends never satisfies it and simply runs out the window, which is the documented behaviour for
 * pointing this annotation at a spinner.
 */
public fun settleScene(
  scene: ImageComposeScene,
  clock: DesktopSettleClock,
  windowMs: Int,
  autoDetect: Boolean,
): Long {
  val bound = windowMs.coerceIn(0, MAX_SETTLE_MS).toLong()
  var t = 0L
  while (t < bound) {
    t = minOf(t + SETTLE_FRAME_MS, bound)
    clock.advanceTo(t)
    // Closed rather than left to a cleaner: each frame owns a Skia surface, and a pooled render
    // worker walks this loop once per settled capture for the life of the JVM.
    scene.render(t * 1_000_000L).close()
    // Drain what the render itself dispatched before judging the frame, so an effect the
    // recomposition just queued counts against quiescence rather than being missed entirely.
    clock.drain()
    if (autoDetect && !clock.hasPendingWork() && !scene.hasInvalidations()) return t
  }
  return bound
}
