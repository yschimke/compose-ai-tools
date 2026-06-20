package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension

/**
 * `AroundComposable` extension that overlays an "MotionEvent-style" visualization of pointer events
 * on top of a held-scene preview — a translucent ring at every currently-pressed pointer, a fading
 * "whoosh" motion trail behind each moving pointer, a short-lived expanding pulse (a filled,
 * alpha-fading disc + outline ring) wherever a touch went down or came up, and a two-finger pinch
 * "caliper" (dashed rubber-band + centroid magnitude ring + directional chevrons, plus a rotation
 * arc and a pan arrow relative to the gesture's start) whenever exactly two pointers are down;
 * three or more draw a convex-hull outline instead, and any multi-touch shows a dot badge of the
 * live pointer count. A lone finger held in place past a timeout grows a long-press progress arc
 * and a confirm flash; a fast release emits a fling velocity arrow. Releases are colour-coded by
 * whether the composition actually consumed the pointer: an ordinary (consumed) lift is the amber
 * up flash, while a release that nothing collected — observed via [PointerInputChange.isConsumed]
 * on the Final pass — is drawn as the distinct red dashed-ring + ✕ "unhandled" marker, so a touch
 * that fell through reads as the exception. Activated for live recording sessions (and any one-shot
 * render that flips `renderNow.overrides.touchOverlay`) so the agent reviewing the captured APNG /
 * mp4 can see exactly where each finger landed, when, and what happened next.
 *
 * **Why an around-composable, not a PNG post-processor.** A first cut of this lived in
 * [DesktopRecordingSession.writeFramePng] as a Skia overlay drawn after `scene.render()`. The
 * around-composable form is strictly better:
 * - **Backend-portable.** The overlay is plain Compose drawing (`Canvas` + `drawCircle`), so the
 *   exact same extension works under Android's Robolectric host the day live mode lands there.
 * - **Honest pointer source.** [Modifier.pointerInput] with [PointerEventPass.Initial] observes
 *   every pointer event the held scene sees (whether the dispatch came from
 *   [DesktopRecordingSession]'s multi-pointer `sendPointerEvent`, an `interactive/input`
 *   notification, or a future direct UI dispatch). No special-case branches per dispatch path — the
 *   touches the agent sees are the touches the composition processed.
 * - **Animated correctly.** The pulse fade-out runs on Compose's frame clock via `LaunchedEffect {
 *   withFrameNanos { … } }`, so the overlay advances at the same virtual time as the rest of the
 *   composition (scripted recordings tick at `recordingFps`; live recordings tick at wall-clock).
 *
 * **State model.** Seven pieces of [remember]-scoped state:
 * - `activePointers: MutableMap<Long, Offset>` — per-id `PointerId.value` → current natural-px
 *   position. `Press` / `Move` add/update entries, `Release` removes them.
 * - `trail: MutableList<TrailSample>` — recent `(id, position, ms)` samples per pointer. Every
 *   down/move/up appends one; the `LaunchedEffect` loop prunes samples older than
 *   [TRAIL_LIFETIME_MS]. Drawn as a tapering, age-faded comet so a fast drag leaves a "whoosh"
 *   streak while a stationary tap (one sample, no segments) leaves nothing.
 * - `pulses: MutableList<Pulse>` — short-lived dispatch markers. Each pulse carries the position,
 *   the frame-clock ms at which it was emitted, and its kind (DOWN vs UP) for colour. The
 *   `LaunchedEffect` loop also prunes expired pulses so the buffer stays bounded across long
 *   recordings.
 * - `pinchBaselineSpan: Float` (+ `pinchBaselineCentroid`, `pinchBaselineAngle`) — finger spread,
 *   centroid, and pair angle latched when the second pointer lands, cleared when the count drops
 *   back below two. The caliper measures its magnitude ring, pan arrow, and rotation arc against
 *   these, so it reports the gesture's own change rather than the content's (unobservable, possibly
 *   clamped) transform.
 * - `presses: MutableMap<Long, PressState>` — per-pointer down position/time plus a disqualified
 *   flag (set on slop travel *or* when the gesture becomes multi-touch) and a one-shot long-press
 *   latch. Drives the long-press progress arc and its confirm pulse.
 * - `flings: MutableList<Fling>` — release velocity vectors, emitted when a pointer lifts faster
 *   than [FLING_MIN_SPEED_PX_PER_MS] and pruned over [FLING_LIFETIME_MS].
 * - `consumedEver: MutableMap<Long, Boolean>` — per-pointer "did anything downstream consume this?"
 *   accumulated on the Final pass and read at release to pick the UP vs UNHANDLED up-marker.
 *
 * **Pointer pass.** The observer uses [PointerEventPass.Initial] so it sees events BEFORE the inner
 * content does, and never calls `.consume()` — the inner composition still receives every touch.
 * Without `Initial`, a child that consumes the event before bubble-up (`Final` pass) would starve
 * the overlay of anything to draw.
 *
 * Lives in `:data-touch-overlay-connector` (shared `kotlin.jvm` + `compose.multiplatform` module).
 * Both `:daemon:desktop` and `:daemon:android` depend on this module and register
 * [TouchOverlayPreviewOverrideExtension] in their respective `RenderEngine`'s
 * `previewOverrideExtensions` list — no per-backend fork needed because the source uses only
 * portable Compose APIs (`androidx.compose.foundation`, `androidx.compose.ui`,
 * `androidx.compose.runtime`).
 */
class TouchOverlayExtension :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    val activePointers = remember { mutableStateMapOf<Long, Offset>() }
    val trail = remember { mutableStateListOf<TrailSample>() }
    val pulses = remember { mutableStateListOf<Pulse>() }
    // Per-pointer press bookkeeping for the long-press affordance: where/when each finger went down
    // and whether it has since travelled past slop (which disqualifies it as a long-press).
    val presses = remember { mutableStateMapOf<Long, PressState>() }
    // Release "fling" markers: a velocity vector emitted when a pointer lifts fast enough.
    val flings = remember { mutableStateListOf<Fling>() }
    // Per-pointer "did the composition consume this pointer at any point?" — set on the Final pass,
    // read when the pointer lifts so an unconsumed (fell-through) release is marked distinctly.
    val consumedEver = remember { mutableStateMapOf<Long, Boolean>() }
    var nowMs by remember { mutableLongStateOf(0L) }
    // Span between the two pointers at the instant the pinch began (1→2 pressed transition). The
    // caliper's magnitude ring is measured against this, so it shows how far the *gesture* has
    // spread — not the content's (possibly clamped) zoom, which the overlay can't see anyway. 0
    // means "no pinch in progress".
    var pinchBaselineSpan by remember { mutableFloatStateOf(0f) }
    // Centroid + pair angle latched alongside the span (at the 1→2 transition) so the two-finger
    // caliper can also report pan (centroid travel) and rotation (angle turned) since the gesture
    // began.
    var pinchBaselineCentroid by remember { mutableStateOf(Offset.Zero) }
    var pinchBaselineAngle by remember { mutableFloatStateOf(0f) }

    // Pulse fade timer + buffer trim. Read of `nowMs` inside the draw scope below subscribes that
    // scope to this state, so updating `nowMs` invalidates the Canvas and re-paints the fade. The
    // `withFrameNanos` callback also runs the prune so expired entries don't accumulate forever.
    LaunchedEffect(Unit) {
      while (true) {
        withFrameNanos { frameNs ->
          nowMs = frameNs / 1_000_000L
          // Remove expired pulses in-place. `removeAll` walks once; ok for the small list size
          // (typically < 32 pulses live at any time given the 400ms lifetime).
          pulses.removeAll { nowMs - it.emittedAtMs >= PULSE_LIFETIME_MS }
          trail.removeAll { nowMs - it.emittedAtMs >= TRAIL_LIFETIME_MS }
          flings.removeAll { nowMs - it.emittedAtMs >= FLING_LIFETIME_MS }
          // Long-press fire: a single stationary finger held past the timeout emits one confirm
          // pulse. Gated on a lone pointer so a pinch/multi-touch never counts as a long-press.
          if (activePointers.size == 1) {
            val id = activePointers.keys.first()
            val press = presses[id]
            if (
              press != null &&
                !press.disqualified &&
                !press.longPressFired &&
                nowMs - press.downAtMs >= LONG_PRESS_MS
            ) {
              pulses.add(Pulse(press.downAt, nowMs, PulseKind.LONG_PRESS))
              press.longPressFired = true
            }
          }
        }
      }
    }

    Box(
      modifier =
        Modifier.fillMaxSize().pointerInput(Unit) {
          val slop = viewConfiguration.touchSlop
          awaitPointerEventScope {
            while (true) {
              // Releases found on the Initial pass, held until the Final pass reveals whether the
              // pointer was consumed (→ UP) or fell through unhandled (→ UNHANDLED).
              val released = mutableMapOf<Long, Pair<Offset, Long>>()
              // Initial pass so we see events BEFORE the inner content. We never call `consume()`
              // so the inner composition (the user's @Preview, with its own
              // `Modifier.transformable` / `Modifier.clickable` / etc.) still receives the full
              // event sequence — this layer is a pure observer.
              val event = awaitPointerEvent(PointerEventPass.Initial)
              for (change in event.changes) {
                val id = change.id.value
                // Stamp pulses with the event's own `uptimeMillis` rather than the
                // composition-side `nowMs`. The latter only advances when `withFrameNanos`
                // resumes (i.e., on a rendered frame), so a CLICK dispatched between renders
                // would stamp its DOWN pulse with a stale (much earlier) `nowMs`. By the time
                // the next `render()` advances `nowMs` to wall time, the DOWN pulse would
                // already look expired (age ≥ PULSE_LIFETIME_MS) and get pruned/faded before
                // the user ever saw it — so clicks rendered as nothing while drags still
                // showed via their persistent active-pointer ring. `change.uptimeMillis` is
                // populated from the dispatcher's `sendPointerEvent(timeMillis = …)`, which
                // is the same monotonic clock that drives `withFrameNanos`, so the pruning
                // comparison stays consistent.
                val eventMs = change.uptimeMillis
                if (change.pressed) {
                  // First press for this id → emit a DOWN pulse so the first frame after the down
                  // shows a bright ring even if no `Move` arrives. Subsequent same-id events just
                  // update the position.
                  if (!activePointers.containsKey(id)) {
                    pulses.add(Pulse(change.position, eventMs, PulseKind.DOWN))
                    // Start tracking this press for the long-press affordance.
                    presses[id] = PressState(change.position, eventMs)
                    // New press → assume unconsumed until the Final pass proves otherwise.
                    consumedEver[id] = false
                  } else {
                    // Subsequent move — once it travels past slop it can't be a long-press.
                    val press = presses[id]
                    if (press != null && !press.disqualified) {
                      if ((change.position - press.downAt).getDistance() > slop) {
                        press.disqualified = true
                      }
                    }
                  }
                  activePointers[id] = change.position
                  // Append a trail sample on every down + move so the comet follows the finger. A
                  // tap yields a single sample (no segment); a drag yields a fading streak.
                  trail.add(TrailSample(id, change.position, eventMs))
                  // Latch the starting finger spread the moment the second pointer joins, so the
                  // caliper has a reference to measure the pinch against. Re-tries until non-zero
                  // in case both fingers momentarily land on the same point.
                  if (activePointers.size >= 2) {
                    // A second finger means no in-flight press is a lone stationary pointer any
                    // more — disqualify them all so no long-press fires after a pinch / two-finger
                    // gesture, even once it drops back to a single finger.
                    presses.values.forEach { it.disqualified = true }
                    if (pinchBaselineSpan == 0f) {
                      val pts = activePointers.values.toList()
                      pinchBaselineSpan = (pts[0] - pts[1]).getDistance()
                      pinchBaselineCentroid =
                        Offset((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f)
                      pinchBaselineAngle =
                        kotlin.math.atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
                    }
                  }
                } else {
                  // Up event for this id → drop the ring, emit a fading UP pulse at the final
                  // position. Using `change.previousPressed` (which we'd need to check via the
                  // change history) isn't necessary: the wire-level dispatch always pairs press +
                  // release per id, so a non-pressed event for a tracked id is unambiguously the
                  // up.
                  if (activePointers.remove(id) != null) {
                    // The up marker's kind (ordinary UP vs UNHANDLED) depends on whether anything
                    // consumed this pointer, which is only settled after the Final pass — so defer
                    // the pulse and resolve it below.
                    released[id] = change.position to eventMs
                    // Final trail sample at the lift point so the whoosh reaches the release, then
                    // ages out like the rest. Added BEFORE the fling calc so the release point is
                    // the freshest sample feeding the velocity estimate.
                    trail.add(TrailSample(id, change.position, eventMs))
                    // Fling: estimate release velocity from this pointer's recent trail samples
                    // (last ~FLING_WINDOW_MS). Fast enough → emit a velocity vector marker.
                    flingVelocity(trail, id)?.let { v ->
                      flings.add(Fling(change.position, v, eventMs))
                    }
                    presses.remove(id)
                    // Any lift changes the active set, so the latched two-finger baseline (span /
                    // centroid / angle) is stale — clear it. The next move with ≥ 2 fingers
                    // re-latches against the pointers that actually remain, so a 3→2 transition
                    // (e.g. A+B+C then lift A) measures the new B+C pair instead of A+B's baseline.
                    pinchBaselineSpan = 0f
                    pinchBaselineCentroid = Offset.Zero
                    pinchBaselineAngle = 0f
                  }
                }
              }
              // Final pass (after children): record whether each change was consumed. We never
              // consume ourselves — this is read-only. Any gesture detector in the content
              // (clickable / draggable / transformable / …) that claimed the event has set
              // `isConsumed` by now.
              val finalEvent = awaitPointerEvent(PointerEventPass.Final)
              for (change in finalEvent.changes) {
                if (change.isConsumed) consumedEver[change.id.value] = true
              }
              // Resolve deferred releases: a pointer the composition consumed at any point gets the
              // ordinary UP pulse; one nothing ever collected gets the distinct UNHANDLED marker.
              for ((id, posMs) in released) {
                val kind = if (consumedEver[id] == true) PulseKind.UP else PulseKind.UNHANDLED
                pulses.add(Pulse(posMs.first, posMs.second, kind))
                consumedEver.remove(id)
              }
            }
          }
        }
    ) {
      content()
      Canvas(modifier = Modifier.fillMaxSize()) {
        // Motion "whoosh" trail, drawn first so it sits under the rings/pulses. Per-pointer comet:
        // consecutive samples joined by a line whose width + alpha taper with the newer endpoint's
        // age, so the head (most recent) is thick and bright and the tail fades to nothing.
        for ((_, samples) in trail.groupBy { it.id }) {
          if (samples.size < 2) continue
          val ordered = samples.sortedBy { it.emittedAtMs }
          for (i in 0 until ordered.size - 1) {
            val to = ordered[i + 1]
            val age = (nowMs - to.emittedAtMs).coerceAtLeast(0L)
            val freshness = (1f - age.toFloat() / TRAIL_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
            if (freshness <= 0f) continue
            drawLine(
              color = TRAIL_COLOR.copy(alpha = freshness * TRAIL_MAX_ALPHA),
              start = ordered[i].position,
              end = to.position,
              strokeWidth =
                TRAIL_MIN_WIDTH_PX + (TRAIL_MAX_WIDTH_PX - TRAIL_MIN_WIDTH_PX) * freshness,
              cap = StrokeCap.Round,
            )
          }
        }
        // Long-press progress: a single stationary finger held past LONG_PRESS_ARC_DELAY_MS grows a
        // radial arc that completes at LONG_PRESS_MS (when the confirm pulse fires). Distinguishes
        // a
        // deliberate hold from a tap, which lifts before the arc starts.
        if (activePointers.size == 1) {
          val (id, pos) = activePointers.entries.first()
          val press = presses[id]
          if (press != null && !press.disqualified) {
            val held = nowMs - press.downAtMs
            if (held in LONG_PRESS_ARC_DELAY_MS until LONG_PRESS_MS) {
              val progress =
                (held - LONG_PRESS_ARC_DELAY_MS).toFloat() /
                  (LONG_PRESS_MS - LONG_PRESS_ARC_DELAY_MS).toFloat()
              val r = ACTIVE_RADIUS_PX + LONG_PRESS_ARC_GAP_PX
              drawArc(
                color = LONG_PRESS_COLOR,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(pos.x - r, pos.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 3f, cap = StrokeCap.Round),
              )
            }
          }
        }
        // Persistent rings for every active pointer. Two layers (translucent fill + saturated
        // stroke) plus a crosshair so the dispatch point inside the ring is exact.
        for ((_, pos) in activePointers) {
          drawCircle(color = ACTIVE_FILL, radius = ACTIVE_RADIUS_PX, center = pos)
          drawCircle(
            color = ACTIVE_STROKE,
            radius = ACTIVE_RADIUS_PX,
            center = pos,
            style = Stroke(width = 2f),
          )
          val crossArm = ACTIVE_RADIUS_PX * 0.6f
          drawLine(
            color = CROSSHAIR,
            start = Offset(pos.x - crossArm, pos.y),
            end = Offset(pos.x + crossArm, pos.y),
            strokeWidth = 1.5f,
          )
          drawLine(
            color = CROSSHAIR,
            start = Offset(pos.x, pos.y - crossArm),
            end = Offset(pos.x, pos.y + crossArm),
            strokeWidth = 1.5f,
          )
        }
        // Multi-finger overlays, drawn over the rings. Two fingers → the pinch caliper (zoom
        // magnitude + rotation arc + pan arrow); three or more → a convex-hull outline (rotation /
        // pan are ill-defined past a pair). A small dot badge reports the live pointer count.
        val activePts = activePointers.values.toList()
        if (activePts.size == 2) {
          val span = (activePts[0] - activePts[1]).getDistance()
          val ratio = if (pinchBaselineSpan > 0f) span / pinchBaselineSpan else 1f
          drawPinchCaliper(
            activePts[0],
            activePts[1],
            ratio,
            pinchBaselineCentroid,
            pinchBaselineAngle,
          )
        } else if (activePts.size >= 3) {
          drawMultiTouchHull(activePts)
        }
        if (activePts.size >= 2) drawPointerCountBadge(activePts)
        // Fling vectors: a fading arrow from each fast release, length ∝ speed (clamped). Reads as
        // "the finger was flicked, not placed" — the bit a still drag-release frame can't show.
        for (fling in flings) {
          val age = (nowMs - fling.emittedAtMs).coerceAtLeast(0L)
          val alpha = (1f - age.toFloat() / FLING_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
          if (alpha <= 0f) continue
          drawFling(fling, alpha)
        }
        // Expanding fading pulses, one per recent down / up event. The age-driven radius lerp +
        // alpha fade is what gives the overlay its "tap flash" character — short enough that
        // consecutive clicks don't smear, long enough that a single click is unambiguous across
        // ~12 frames at 30 fps.
        for (pulse in pulses) {
          val age = (nowMs - pulse.emittedAtMs).coerceAtLeast(0L)
          val progress = (age.toFloat() / PULSE_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
          val radius =
            PULSE_START_RADIUS_PX + (PULSE_END_RADIUS_PX - PULSE_START_RADIUS_PX) * progress
          if (pulse.kind == PulseKind.UNHANDLED) {
            // The exception: a release nothing in the composition consumed. A dashed red ring + an
            // ✕ at the lift point so "this gesture fell through" stands out next to the ordinary
            // (consumed) up flashes. Consumed releases draw the normal solid up pulse below.
            val color = UNHANDLED_COLOR.copy(alpha = 1f - progress)
            drawCircle(
              color = color,
              radius = radius,
              center = pulse.position,
              style =
                Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))),
            )
            val arm = UNHANDLED_X_ARM_PX
            drawLine(
              color = color,
              start = Offset(pulse.position.x - arm, pulse.position.y - arm),
              end = Offset(pulse.position.x + arm, pulse.position.y + arm),
              strokeWidth = 2.5f,
              cap = StrokeCap.Round,
            )
            drawLine(
              color = color,
              start = Offset(pulse.position.x - arm, pulse.position.y + arm),
              end = Offset(pulse.position.x + arm, pulse.position.y - arm),
              strokeWidth = 2.5f,
              cap = StrokeCap.Round,
            )
            continue
          }
          val baseColor =
            when (pulse.kind) {
              PulseKind.DOWN -> PULSE_DOWN
              PulseKind.UP -> PULSE_UP
              PulseKind.LONG_PRESS -> LONG_PRESS_COLOR
              PulseKind.UNHANDLED -> UNHANDLED_COLOR // handled above; keeps the `when` exhaustive
            }
          // Translucent filled disc that fades as it expands — the "alpha tap circle". Gives a tap
          // (down + up with no travel, hence no ring and no trail) a clear flash on its own.
          drawCircle(
            color = baseColor.copy(alpha = (1f - progress) * PULSE_FILL_ALPHA),
            radius = radius,
            center = pulse.position,
          )
          drawCircle(
            color = baseColor.copy(alpha = (1f - progress) * baseColor.alpha),
            radius = radius,
            center = pulse.position,
            style = Stroke(width = 2f),
          )
        }
      }
    }
  }

  /**
   * Draws the pinch "caliper" between two pointers: a dashed rubber-band with perpendicular
   * measurement ticks at each end, a centroid magnitude ring sized by [ratio] against a faint
   * reference ring at ratio = 1, and a pair of chevrons that point outward while the fingers spread
   * (`ratio ≥ 1`, zooming in) and inward while they close (zooming out). Deliberately a measurement
   * metaphor over the gesture itself rather than a floating zoom badge over the content.
   */
  private fun DrawScope.drawPinchCaliper(
    p0: Offset,
    p1: Offset,
    ratio: Float,
    baselineCentroid: Offset,
    baselineAngle: Float,
  ) {
    val axis = p1 - p0
    val len = axis.getDistance()
    if (len < 1f) return
    val ux = axis.x / len
    val uy = axis.y / len
    val perpX = -uy
    val perpY = ux
    val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)

    // Rubber-band between the fingers.
    drawLine(
      color = PINCH_COLOR.copy(alpha = 0.9f),
      start = p0,
      end = p1,
      strokeWidth = 2f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
      cap = StrokeCap.Round,
    )

    // Caliper jaws — short perpendicular ticks at each dispatch point.
    for (p in listOf(p0, p1)) {
      drawLine(
        color = PINCH_COLOR,
        start = Offset(p.x - perpX * PINCH_TICK_PX, p.y - perpY * PINCH_TICK_PX),
        end = Offset(p.x + perpX * PINCH_TICK_PX, p.y + perpY * PINCH_TICK_PX),
        strokeWidth = 2f,
      )
    }

    // Centroid magnitude: faint reference ring at the start span, saturated live ring scaled by the
    // current spread ratio, so growing past the reference reads as "zooming in".
    drawCircle(
      color = PINCH_COLOR.copy(alpha = 0.25f),
      radius = PINCH_BASE_RADIUS_PX,
      center = centroid,
      style = Stroke(width = 1.5f),
    )
    val liveRadius =
      (PINCH_BASE_RADIUS_PX * ratio).coerceIn(PINCH_MIN_RADIUS_PX, PINCH_MAX_RADIUS_PX)
    drawCircle(
      color = PINCH_COLOR,
      radius = liveRadius,
      center = centroid,
      style = Stroke(width = 2.5f),
    )

    // Directional chevrons just beyond the live ring, one toward each pointer. Sign of travel along
    // the axis flips with zoom direction so both chevrons fan outward (spread) or inward (close).
    val zoomDir = if (ratio >= 1f) 1f else -1f
    val base = liveRadius + 8f
    for (side in listOf(-1f, 1f)) {
      val anchor = Offset(centroid.x + ux * side * base, centroid.y + uy * side * base)
      val dir = side * zoomDir
      val tip =
        Offset(anchor.x + ux * dir * PINCH_CHEVRON_PX, anchor.y + uy * dir * PINCH_CHEVRON_PX)
      val wingA = Offset(anchor.x + perpX * PINCH_CHEVRON_PX, anchor.y + perpY * PINCH_CHEVRON_PX)
      val wingB = Offset(anchor.x - perpX * PINCH_CHEVRON_PX, anchor.y - perpY * PINCH_CHEVRON_PX)
      drawLine(PINCH_COLOR, wingA, tip, strokeWidth = 2f, cap = StrokeCap.Round)
      drawLine(PINCH_COLOR, wingB, tip, strokeWidth = 2f, cap = StrokeCap.Round)
    }

    // Pan: an arrow from where the two-finger centroid started to where it is now, shown once it
    // has travelled past a small threshold.
    if (
      baselineCentroid != Offset.Zero && (centroid - baselineCentroid).getDistance() > PAN_MIN_PX
    ) {
      drawArrow(baselineCentroid, centroid, PINCH_COLOR.copy(alpha = 0.8f))
    }

    // Rotation: an arc at the centroid spanning the angle the finger pair has turned through since
    // the gesture began, with an arrowhead for direction.
    val currentAngle = kotlin.math.atan2(p1.y - p0.y, p1.x - p0.x)
    var deltaDeg = Math.toDegrees((currentAngle - baselineAngle).toDouble()).toFloat()
    deltaDeg = ((deltaDeg + 180f).mod(360f)) - 180f // normalise to (-180, 180]
    if (kotlin.math.abs(deltaDeg) > PINCH_ROT_MIN_DEG) {
      val startDeg = Math.toDegrees(baselineAngle.toDouble()).toFloat()
      val r = PINCH_ROT_RADIUS_PX
      drawArc(
        color = PINCH_COLOR,
        startAngle = startDeg,
        sweepAngle = deltaDeg,
        useCenter = false,
        topLeft = Offset(centroid.x - r, centroid.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round),
      )
      val endRad = Math.toRadians((startDeg + deltaDeg).toDouble())
      val end =
        Offset(
          centroid.x + r * kotlin.math.cos(endRad).toFloat(),
          centroid.y + r * kotlin.math.sin(endRad).toFloat(),
        )
      val sign = if (deltaDeg >= 0f) 1f else -1f
      val tangent =
        Offset(-kotlin.math.sin(endRad).toFloat() * sign, kotlin.math.cos(endRad).toFloat() * sign)
      drawArrowHead(end, tangent, PINCH_COLOR)
    }
  }

  /** Convex-hull outline + centroid dot for a 3+ finger gesture. */
  private fun DrawScope.drawMultiTouchHull(points: List<Offset>) {
    val hull = convexHull(points)
    if (hull.size >= 2) {
      for (i in hull.indices) {
        drawLine(
          color = PINCH_COLOR.copy(alpha = 0.9f),
          start = hull[i],
          end = hull[(i + 1) % hull.size],
          strokeWidth = 2f,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
          cap = StrokeCap.Round,
        )
      }
    }
    drawCircle(PINCH_COLOR, radius = 3f, center = centroidOf(points))
  }

  /** A small row of dots under the centroid reporting how many fingers are down. */
  private fun DrawScope.drawPointerCountBadge(points: List<Offset>) {
    val c = centroidOf(points)
    val n = points.size
    val totalWidth = (n - 1) * COUNT_DOT_GAP_PX
    val y = c.y + COUNT_BADGE_OFFSET_PX
    for (i in 0 until n) {
      val x = c.x - totalWidth / 2f + i * COUNT_DOT_GAP_PX
      drawCircle(PINCH_COLOR, radius = COUNT_DOT_RADIUS_PX, center = Offset(x, y))
    }
  }

  private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    drawLine(color, from, to, strokeWidth = 2.5f, cap = StrokeCap.Round)
    val v = to - from
    val len = v.getDistance()
    if (len >= 1f) drawArrowHead(to, Offset(v.x / len, v.y / len), color)
  }

  private fun DrawScope.drawArrowHead(tip: Offset, dir: Offset, color: Color) {
    val perpX = -dir.y
    val perpY = dir.x
    val a = ARROW_HEAD_PX
    val w1 = Offset(tip.x - dir.x * a + perpX * a * 0.6f, tip.y - dir.y * a + perpY * a * 0.6f)
    val w2 = Offset(tip.x - dir.x * a - perpX * a * 0.6f, tip.y - dir.y * a - perpY * a * 0.6f)
    drawLine(color, tip, w1, strokeWidth = 2.5f, cap = StrokeCap.Round)
    drawLine(color, tip, w2, strokeWidth = 2.5f, cap = StrokeCap.Round)
  }

  private fun centroidOf(points: List<Offset>): Offset {
    var sx = 0f
    var sy = 0f
    for (p in points) {
      sx += p.x
      sy += p.y
    }
    return Offset(sx / points.size, sy / points.size)
  }

  /**
   * Andrew's monotone-chain convex hull, counter-clockwise. Returns the input as-is for < 3 pts.
   */
  private fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val pts = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset): Float =
      (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in pts) {
      while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
        lower.removeAt(lower.size - 1)
      }
      lower.add(p)
    }
    val upper = ArrayList<Offset>()
    for (p in pts.asReversed()) {
      while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
        upper.removeAt(upper.size - 1)
      }
      upper.add(p)
    }
    return lower.dropLast(1) + upper.dropLast(1)
  }

  /**
   * Estimates release velocity (px/ms) for [id] from its recent [trail] samples — the displacement
   * over the oldest sample still within [FLING_WINDOW_MS] of the latest. Returns null when there
   * aren't two usable samples or the speed is below [FLING_MIN_SPEED_PX_PER_MS] (i.e. a placed
   * drag, not a flick).
   */
  private fun flingVelocity(trail: List<TrailSample>, id: Long): Offset? {
    val samples = trail.filter { it.id == id }.sortedBy { it.emittedAtMs }
    if (samples.size < 2) return null
    val latest = samples.last()
    val ref = samples.firstOrNull { latest.emittedAtMs - it.emittedAtMs <= FLING_WINDOW_MS }
    if (ref == null || ref === latest) return null
    val dt = (latest.emittedAtMs - ref.emittedAtMs).coerceAtLeast(1L)
    val velocity = (latest.position - ref.position) / dt.toFloat()
    return if (velocity.getDistance() >= FLING_MIN_SPEED_PX_PER_MS) velocity else null
  }

  /** Draws a fling as a fading arrow from the release point along the velocity, length ∝ speed. */
  private fun DrawScope.drawFling(fling: Fling, alpha: Float) {
    val speed = fling.velocity.getDistance()
    if (speed <= 0f) return
    val len = (speed * FLING_VECTOR_MS).coerceIn(FLING_MIN_LEN_PX, FLING_MAX_LEN_PX)
    val ux = fling.velocity.x / speed
    val uy = fling.velocity.y / speed
    val tail = fling.position
    val head = Offset(tail.x + ux * len, tail.y + uy * len)
    val color = FLING_COLOR.copy(alpha = alpha)
    drawLine(color, tail, head, strokeWidth = 3f, cap = StrokeCap.Round)
    // Arrowhead: two short wings angled back from the head.
    val perpX = -uy
    val perpY = ux
    val a = FLING_ARROW_PX
    val wingA = Offset(head.x - ux * a + perpX * a * 0.6f, head.y - uy * a + perpY * a * 0.6f)
    val wingB = Offset(head.x - ux * a - perpX * a * 0.6f, head.y - uy * a - perpY * a * 0.6f)
    drawLine(color, head, wingA, strokeWidth = 3f, cap = StrokeCap.Round)
    drawLine(color, head, wingB, strokeWidth = 3f, cap = StrokeCap.Round)
  }

  private data class TrailSample(val id: Long, val position: Offset, val emittedAtMs: Long)

  private data class Fling(val position: Offset, val velocity: Offset, val emittedAtMs: Long)

  /**
   * Mutable per-pointer press bookkeeping. [disqualified] flips true once the press can no longer
   * be a long-press — either it travelled past slop, or the gesture became multi-touch (a
   * long-press is a lone stationary finger). [longPressFired] is the one-shot latch for its confirm
   * pulse.
   */
  private class PressState(val downAt: Offset, val downAtMs: Long) {
    var disqualified: Boolean = false
    var longPressFired: Boolean = false
  }

  private data class Pulse(val position: Offset, val emittedAtMs: Long, val kind: PulseKind)

  private enum class PulseKind {
    DOWN,
    UP,
    LONG_PRESS,
    /** A release nothing in the composition consumed — the "fell through" exception. */
    UNHANDLED,
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("touch-overlay")

    // Radii are in compose-px (1 unit = 1 dp at density 1.0). The values match the MotionEvent
    // debug overlay built into Android's `View System UI` toggle — recognisable to anyone who's
    // ever flipped that on.
    private const val ACTIVE_RADIUS_PX: Float = 18f
    private const val PULSE_START_RADIUS_PX: Float = 16f
    private const val PULSE_END_RADIUS_PX: Float = 36f
    private const val PULSE_LIFETIME_MS: Long = 400L
    // Peak alpha of the filled tap disc at age 0; fades linearly to 0 over PULSE_LIFETIME_MS.
    private const val PULSE_FILL_ALPHA: Float = 0.30f

    // Motion-trail tuning. Short lifetime so a quick drag reads as a streak, not a smear; width +
    // alpha taper from head to tail give it the "whoosh" comet look.
    private const val TRAIL_LIFETIME_MS: Long = 300L
    private const val TRAIL_MAX_WIDTH_PX: Float = 7f
    private const val TRAIL_MIN_WIDTH_PX: Float = 1.5f
    private const val TRAIL_MAX_ALPHA: Float = 0.55f

    // Pinch caliper tuning. The base radius is the magnitude ring at ratio = 1; the live ring is
    // clamped so a wild spread doesn't fill the frame.
    private const val PINCH_TICK_PX: Float = 7f
    private const val PINCH_CHEVRON_PX: Float = 6f
    private const val PINCH_BASE_RADIUS_PX: Float = 14f
    private const val PINCH_MIN_RADIUS_PX: Float = 7f
    private const val PINCH_MAX_RADIUS_PX: Float = 40f
    // Rotation arc + pan/rotation arrowheads, and the pointer-count dot badge.
    private const val PINCH_ROT_RADIUS_PX: Float = 24f
    private const val PINCH_ROT_MIN_DEG: Float = 6f
    private const val PAN_MIN_PX: Float = 8f
    private const val ARROW_HEAD_PX: Float = 7f
    private const val COUNT_DOT_RADIUS_PX: Float = 2.5f
    private const val COUNT_DOT_GAP_PX: Float = 7f
    private const val COUNT_BADGE_OFFSET_PX: Float = 30f

    // Fling: min release speed (px/ms) to draw a vector, the projection horizon mapping speed →
    // arrow length, the clamped length range, arrowhead size, and how long the marker lingers.
    private const val FLING_WINDOW_MS: Long = 80L
    private const val FLING_MIN_SPEED_PX_PER_MS: Float = 0.6f
    private const val FLING_VECTOR_MS: Float = 80f
    private const val FLING_MIN_LEN_PX: Float = 20f
    private const val FLING_MAX_LEN_PX: Float = 72f
    private const val FLING_ARROW_PX: Float = 8f
    private const val FLING_LIFETIME_MS: Long = 500L

    // Long-press: hold timeout, the delay before the progress arc appears (so quick taps draw
    // nothing), and the gap between the active ring and the arc.
    private const val LONG_PRESS_MS: Long = 500L
    private const val LONG_PRESS_ARC_DELAY_MS: Long = 150L
    private const val LONG_PRESS_ARC_GAP_PX: Float = 6f

    // Half-length of the ✕ arms drawn at an unconsumed (fell-through) release.
    private const val UNHANDLED_X_ARM_PX: Float = 7f

    private val ACTIVE_FILL: Color = Color(0x4000BCD4)
    private val ACTIVE_STROKE: Color = Color(0xFF00BCD4)
    private val TRAIL_COLOR: Color = Color(0xFF00BCD4)
    // Deep purple — intentionally distinct from the cyan rings/trail and the amber down/up pulses
    // so a pinch reads as its own thing at a glance.
    private val PINCH_COLOR: Color = Color(0xFF7C4DFF)
    private val CROSSHAIR: Color = Color.White
    private val PULSE_DOWN: Color = Color(0xFFFF9800) // orange — drag start
    private val PULSE_UP: Color = Color(0xFFFFC107) // amber — drag end
    private val FLING_COLOR: Color = Color(0xFF00C853) // green — fast release
    private val LONG_PRESS_COLOR: Color = Color(0xFFFF5722) // deep orange — press-and-hold
    private val UNHANDLED_COLOR: Color = Color(0xFFF44336) // red — release nothing consumed
  }
}

/**
 * Planner that maps [PreviewOverrides.touchOverlay] to a [TouchOverlayExtension] — no-op when the
 * field is null or false. Registered via `DaemonMain.previewOverrideExtensions` so any one-shot
 * render whose overrides ask for the overlay (`renderNow.overrides.touchOverlay = true`) opts in,
 * and `DesktopHost.acquireRecordingSession` flips the field on live recordings before threading
 * them through [RenderEngine.setUp].
 */
class TouchOverlayPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = TouchOverlayExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    if (request.touchOverlay == true) TouchOverlayExtension() else null
}
