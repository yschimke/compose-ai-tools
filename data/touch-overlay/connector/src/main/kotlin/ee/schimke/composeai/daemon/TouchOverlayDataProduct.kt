package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * on top of a held-scene preview — a translucent ring at every currently-pressed pointer plus a
 * short-lived expanding pulse wherever a touch went down or came up. Activated for live recording
 * sessions (and any one-shot render that flips `renderNow.overrides.touchOverlay`) so the agent
 * reviewing the captured APNG / mp4 can see exactly where each finger landed, when, and what
 * happened next.
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
 * **State model.** Two pieces of [remember]-scoped state:
 * - `activePointers: MutableMap<Long, Offset>` — per-id `PointerId.value` → current natural-px
 *   position. `Press` / `Move` add/update entries, `Release` removes them.
 * - `pulses: MutableList<Pulse>` — short-lived dispatch markers. Each pulse carries the position,
 *   the frame-clock ms at which it was emitted, and its kind (DOWN vs UP) for colour. The
 *   `LaunchedEffect` loop also prunes expired pulses so the buffer stays bounded across long
 *   recordings.
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
    val pulses = remember { mutableStateListOf<Pulse>() }
    var nowMs by remember { mutableLongStateOf(0L) }

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
        }
      }
    }

    Box(
      modifier =
        Modifier.fillMaxSize().pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              // Initial pass so we see events BEFORE the inner content. We never call `consume()`
              // so the inner composition (the user's @Preview, with its own
              // `Modifier.transformable` / `Modifier.clickable` / etc.) still receives the full
              // event sequence — this layer is a pure observer.
              val event = awaitPointerEvent(PointerEventPass.Initial)
              for (change in event.changes) {
                val id = change.id.value
                if (change.pressed) {
                  // First press for this id → emit a DOWN pulse so the first frame after the down
                  // shows a bright ring even if no `Move` arrives. Subsequent same-id events just
                  // update the position.
                  if (!activePointers.containsKey(id)) {
                    pulses.add(Pulse(change.position, nowMs, PulseKind.DOWN))
                  }
                  activePointers[id] = change.position
                } else {
                  // Up event for this id → drop the ring, emit a fading UP pulse at the final
                  // position. Using `change.previousPressed` (which we'd need to check via the
                  // change history) isn't necessary: the wire-level dispatch always pairs press +
                  // release per id, so a non-pressed event for a tracked id is unambiguously the
                  // up.
                  if (activePointers.remove(id) != null) {
                    pulses.add(Pulse(change.position, nowMs, PulseKind.UP))
                  }
                }
              }
            }
          }
        }
    ) {
      content()
      Canvas(modifier = Modifier.fillMaxSize()) {
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
        // Expanding fading pulses, one per recent down / up event. The age-driven radius lerp +
        // alpha fade is what gives the overlay its "tap flash" character — short enough that
        // consecutive clicks don't smear, long enough that a single click is unambiguous across
        // ~12 frames at 30 fps.
        for (pulse in pulses) {
          val age = (nowMs - pulse.emittedAtMs).coerceAtLeast(0L)
          val progress = (age.toFloat() / PULSE_LIFETIME_MS.toFloat()).coerceIn(0f, 1f)
          val radius =
            PULSE_START_RADIUS_PX + (PULSE_END_RADIUS_PX - PULSE_START_RADIUS_PX) * progress
          val baseColor =
            when (pulse.kind) {
              PulseKind.DOWN -> PULSE_DOWN
              PulseKind.UP -> PULSE_UP
            }
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

  private data class Pulse(val position: Offset, val emittedAtMs: Long, val kind: PulseKind)

  private enum class PulseKind {
    DOWN,
    UP,
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

    private val ACTIVE_FILL: Color = Color(0x4000BCD4)
    private val ACTIVE_STROKE: Color = Color(0xFF00BCD4)
    private val CROSSHAIR: Color = Color.White
    private val PULSE_DOWN: Color = Color(0xFFFF9800) // orange — drag start
    private val PULSE_UP: Color = Color(0xFFFFC107) // amber — drag end
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
