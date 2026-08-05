package ee.schimke.composeai.rcplayer.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.trace.RcTrace
import ee.schimke.composeai.rcplayer.trace.RcTraceRecorder
import ee.schimke.composeai.rcplayer.trace.RcTraceSectionStats
import org.jetbrains.skia.EncodedImageFormat

/** Everything one scenario produced: its per-section timings and the raw span timeline. */
public data class RcProfileResult(
  public val scenario: RcProfileScenario,
  public val documentBytes: Int,
  public val operations: Int,
  public val sections: List<RcTraceSectionStats>,
  public val chromeTraceJson: String,
)

/**
 * Runs the reference scenarios through the real desktop player and reports what the trace spans
 * measured.
 *
 * Density is deliberately 1: the documents are authored in the same units the scene is sized in, so
 * a tap at (160, 90) lands where the button is drawn without a conversion step in the middle of a
 * measurement.
 *
 * Each scenario is warmed before it is measured. Without that the first scenario in the list pays
 * for class loading, skiko's native init and JIT on behalf of all four, which is a real cost but
 * not the cost anyone reading a per-phase profile is asking about.
 */
public class RcProfileRunner(private val warmupLoads: Int = 3, private val warmupFrames: Int = 5) {
  public fun run(scenarios: List<RcProfileScenario>): List<RcProfileResult> =
    scenarios.map { scenario ->
      // Warm up with tracing off, so the recorder never sees a span that includes JIT time.
      RcTrace.recorder = null
      repeat(warmupLoads) { play(scenario, frames = warmupFrames, replayTaps = false) }

      val recorder = RcTraceRecorder()
      RcTrace.recorder = recorder
      try {
        repeat(scenario.loads) {
          play(scenario, frames = scenario.framesPerLoad, replayTaps = true)
        }
        RcProfileResult(
          scenario = scenario,
          documentBytes = scenario.bytes.size,
          operations = scenario.document.operations.size,
          sections = recorder.summary(),
          chromeTraceJson = recorder.toChromeTraceJson(processName = "rc-player/${scenario.id}"),
        )
      } finally {
        RcTrace.recorder = null
      }
    }

  /**
   * Render one scenario to PNG, tracing disabled.
   *
   * A profile of a document that silently failed to draw would still produce a full set of tidy
   * timings, so every run also emits the pixels it measured. The capture replays the scenario's
   * taps for the same reason: an interaction document's post-click state is the part worth looking
   * at.
   */
  public fun capture(scenario: RcProfileScenario): ByteArray {
    val previousRecorder = RcTrace.recorder
    RcTrace.recorder = null
    try {
      val scene = newScene(scenario)
      try {
        repeat(scenario.framesPerLoad) { frame ->
          advance(scene, scenario, frame, replayTaps = true)
        }
        val image = scene.render(scenario.framesPerLoad.toLong() * FRAME_INTERVAL_NANOS)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes
          ?: error("skiko could not encode ${scenario.id} to PNG")
      } finally {
        scene.close()
      }
    } finally {
      RcTrace.recorder = previousRecorder
    }
  }

  private fun play(scenario: RcProfileScenario, frames: Int, replayTaps: Boolean) {
    val scene = newScene(scenario)
    try {
      repeat(frames) { frame -> advance(scene, scenario, frame, replayTaps) }
    } finally {
      scene.close()
    }
  }

  private fun newScene(scenario: RcProfileScenario): ImageComposeScene =
    ImageComposeScene(
      width = RcProfileDocuments.WIDTH,
      height = RcProfileDocuments.HEIGHT,
      density = Density(1f),
    ) {
      // `Modifier.fillMaxSize()` is load-bearing, not decoration. The player's raw-document path
      // paints into a `Canvas` sized by the modifier the host supplies; with the default `Modifier`
      // that canvas measures 0×0, and while explicitly-positioned draw operations still land
      // (Compose
      // does not clip by default), anything sized *from* the `DrawScope` — canvas-drawn text, whose
      // layout constraints come from `size` — silently lays out into nothing. A profile taken
      // without
      // this would quietly under-report paint cost.
      RcComposePlayer(scenario.bytes, Modifier.fillMaxSize())
    }

  private fun advance(
    scene: ImageComposeScene,
    scenario: RcProfileScenario,
    frame: Int,
    replayTaps: Boolean,
  ) {
    if (replayTaps) {
      scenario.taps
        .filter { it.frameIndex == frame }
        .forEach { tap ->
          scene.sendPointerEvent(PointerEventType.Press, tap.position)
          scene.sendPointerEvent(PointerEventType.Release, tap.position)
        }
    }
    // A 60fps clock. `render(nanoTime)` is what drives `withFrameNanos`, so this is also what
    // advances the player's animation frame loop.
    scene.render(frame.toLong() * FRAME_INTERVAL_NANOS)
  }

  private companion object {
    const val FRAME_INTERVAL_NANOS = 16_666_667L
  }
}
