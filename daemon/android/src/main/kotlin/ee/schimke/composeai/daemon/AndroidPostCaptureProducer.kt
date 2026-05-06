package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import java.io.File
/**
 * Producer that runs after Compose's `captureRoboImage` finishes, given access to the
 * still-attached test rule and the per-render data directory. Lets per-feature data-product
 * extraction live outside the [RenderEngine] body so adding a feature is "register a producer in
 * `DaemonMain`" rather than "edit `RenderEngine.kt`."
 *
 * Producers are registered as a list on [RenderEngine]; the engine iterates over them after
 * capture, calling [shouldRun] (default true) and then [write] inside a `try/catch` and a
 * `trace.section(id)` so a misbehaving producer can't strand the PNG. The original hardcoded
 * blocks lived inline in `RenderEngine.kt:316–449`; this contract collapses the
 * `if (dataDir != null) { try { ... } catch(...) { stderr.println(...) } }` × 6 pattern down to
 * one drive loop.
 *
 * **In-composition state.** Producers that need recorders during composition (e.g. fonts /
 * resources, which wrap `LocalFontFamilyResolver` / `LocalContext`) read those recorders from
 * the [AndroidPostCaptureContext]; the recorders themselves are still owned by [RenderEngine]
 * because they have to be wired into `setContent` before the producer ever runs. This keeps
 * post-capture write logic with the producer while leaving the per-render bootstrapping with
 * the engine.
 */
interface AndroidPostCaptureProducer {

  /**
   * Stable identifier used for trace sections (`trace.section(id)`) and stderr error reporting.
   * Format: `<namespace>:<feature>` or `<feature>:dataProduct`, mirroring what the old inline
   * blocks logged so existing trace consumers don't lose continuity.
   */
  val id: String

  /**
   * Called once the engine has built [AndroidPostCaptureContext] but before [write]. Default
   * runs on every render; override to gate (e.g. a11y producer skips when
   * `runAccessibility=false`).
   */
  fun shouldRun(context: AndroidPostCaptureContext): Boolean = true

  /**
   * Writes any sidecar artifacts the producer owns into `context.dataDir`. Throws are caught by
   * the engine and reported on stderr — the PNG already lives, so a sidecar failure is logged
   * and skipped, not propagated.
   */
  fun write(context: AndroidPostCaptureContext)
}

/**
 * Carrier for everything a producer might read from the just-finished render. Built by
 * [RenderEngine] inside its render loop and passed to each registered [AndroidPostCaptureProducer]
 * once per render.
 *
 * **Lazy fields.** [semanticsRoot] is computed on first access — three of the six in-tree
 * producers read it (`compose/semantics`, `layout/inspector`, `i18n/translations`); rebuilding
 * the lookup three times would cost nothing functionally but pads the trace. The shared lazy
 * keeps the trace honest about who paid the cost.
 */
class AndroidPostCaptureContext(
  val rule: AndroidComposeTestRule<*, ComponentActivity>,
  val activity: ComponentActivity,
  val spec: RenderSpec,
  val outputFile: File,
  val dataDir: File,
  val isRound: Boolean,
  val runAccessibility: Boolean,
  val slotTables: List<Any>,
  val fontRecorder: FontResolverRecorder,
  val resourceRecorder: RecordingResources,
  val imageProcessors: List<ImageProcessor>,
)
