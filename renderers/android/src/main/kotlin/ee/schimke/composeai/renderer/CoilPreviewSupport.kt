package ee.schimke.composeai.renderer

import android.content.Context
import androidx.compose.runtime.ProvidedValue

/**
 * Makes coil-backed images (`AsyncImage`, `rememberAsyncImagePainter`, `SubcomposeAsyncImage`)
 * actually resolve during an off-device render.
 *
 * ## The problem
 *
 * A preview whose content goes through coil captures as a blank/black PNG (issue #2952). The
 * preview is discovered, sized and named correctly — only the pixels are missing, and the knock-on
 * effect is worse than a hole where the artwork should be: with no result, `AsyncImage` reports **no
 * intrinsic size**, so under `ContentScale.FillWidth` it expands to its parent's full height and
 * pushes sibling content out of frame. A screen that should show artwork *plus* a caption captures
 * as solid black — neither element visible.
 *
 * There are **two** independent reasons, and both have to be fixed or the preview stays blank:
 * 1. **coil never starts the load.** `rememberAsyncImagePainter` copies `LocalInspectionMode.current`
 *    onto the painter, and when it is set coil short-circuits to the request's *placeholder* and
 *    returns without loading anything. The renderer composes with `LocalInspectionMode = true` on
 *    purpose (AS parity, issue #1584), so this branch is taken on every static preview render. With
 *    no placeholder configured — the normal case — that leaves `State.Loading(null)`: nothing to
 *    draw and no intrinsic size.
 * 2. **Even once it starts, nothing finishes it.** coil's default `ImageLoader` fetches and decodes
 *    on `Dispatchers.IO`, a real thread pool that neither Compose's paused `mainClock` nor
 *    Robolectric's main looper drives, so the painter is still empty at capture time.
 *
 * ## The fix
 *
 * This object owns (2); (1) is handled per coil major — [ShadowAsyncImagePainter] for coil 2, whose
 * inspection branch has no hook, and `LocalAsyncImagePreviewHandler` for coil 3, which does (see
 * [previewHandlerProvidedValue]).
 *
 * For (2): swap the singleton `ImageLoader` for one **derived from the consumer's own**
 * (`newBuilder()`, so their fetchers, decoders, interceptors, keyers and disk/memory caches are all
 * preserved) with every dispatcher rebound to `Dispatchers.Main.immediate`. On the render thread
 * that makes `imageLoader.execute(request)` run *inline*: the fetch and decode happen inside the
 * same `onRemembered` that started them, so by the time the first frame is drawn the painter holds
 * a real result and reports a real intrinsic size. Crossfade and hardware bitmaps are turned off
 * for the same reason the renderer pauses the clock — a render must be deterministic, and
 * Robolectric has no GPU to back a `Bitmap.Config.HARDWARE`.
 *
 * That covers every consumer on the default singleton loader — including one whose `Application`
 * implements `ImageLoaderFactory` — with **zero change to production code**. The alternative the
 * issue calls out (restructuring production composables to accept an injectable `Painter` /
 * `ImageLoader` purely so they can be previewed) is the tail wagging the dog.
 *
 * Explicitly passing `AsyncImage(imageLoader = …)` still bypasses this, since the composable then
 * never consults the singleton. Nothing can be done about that from out here; such a preview is
 * already supplying its own loader and can make it inline itself.
 *
 * ## What is *not* fixed
 *
 * **Remote models.** An `http(s)://` URL is not fetched. Running coil inline is what makes local
 * models resolve, but it also puts the fetch on the render thread, where Android's main-thread
 * network guard rejects it — and that is the behaviour to keep: a preview whose pixels depend on
 * live egress isn't reproducible, which is exactly why the downloadable-font path resolves through
 * a warmed cache rather than a per-render download. Preview with local bytes, an `R.drawable`, or a
 * warm coil disk cache.
 *
 * What changes for those models is that they no longer fail *silently*: [CoilLoadDiagnostics]
 * records the failed / still-pending request and the render loop writes it into the
 * `<png>.warnings.json` sidecar, so the blank sticker is diagnosable rather than mysterious.
 *
 * ## Version handling
 *
 * coil 2 (`coil.*`) and coil 3 (`coil3.*`) are separate, non-overlapping package trees and a
 * consumer may have either (or, during a migration, both). Each is handled by its own installer
 * behind [CoilPreviewInstaller]; both are `compileOnly` here and reached through `Class.forName` so
 * the overwhelmingly common case — a consumer with no coil at all — loads none of it and pays
 * nothing.
 */
// Public (not `internal`) so BOTH Android render paths can drive it: the gradle-plugin's
// `RobolectricRenderTest` (same module) and the CLI `bundle pack` / serve daemon's
// `:daemon:android` `RenderEngine`, which lives in a different module. Same rationale as
// `FontResolutionDiagnostics`.
object CoilPreviewSupport {

  /**
   * Set `-Dcomposeai.coil.previewLoader=false` to leave the consumer's `ImageLoader` untouched.
   * Escape hatch for a consumer whose loader misbehaves when its dispatchers are rebound (a custom
   * `Fetcher` that hard-asserts it isn't on the main thread, say) — the previews go back to
   * capturing blank, but the render doesn't break.
   */
  const val ENABLED_PROPERTY: String = "composeai.coil.previewLoader"

  /**
   * One installer per coil major present on the classpath — usually zero or one, but **both** when
   * a consumer is mid-migration and still has coil 2 on the graph while new screens use coil 3.
   * Each major owns its own singleton, so installing only the first one found would leave the
   * other's `AsyncImage`s capturing blank.
   */
  private val installers: List<CoilPreviewInstaller> by lazy { detectInstallers() }

  private var installed = false

  /** Whether at least one coil major was detected and its preview loader is in force. */
  val active: Boolean
    get() = installed

  /** Read per call so a test / daemon can flip [ENABLED_PROPERTY] between renders. */
  internal val enabled: Boolean
    get() = System.getProperty(ENABLED_PROPERTY)?.toBooleanStrictOrNull() ?: true

  /**
   * Install the preview `ImageLoader` for **every** coil major on the classpath. Idempotent and
   * cheap to call before every preview — the swap happens once per process and later calls are a
   * boolean check.
   *
   * Failures are per-major and best-effort: an unexpected coil version whose builder lost a method,
   * or a consumer loader that throws from `newBuilder()`, degrades that major to "renders the way
   * it did before" and prints one stderr line, without failing the preview or blocking the other
   * major from installing.
   */
  fun installIfPresent(context: Context) {
    if (installed || !enabled) return
    if (installers.isEmpty()) return
    installers.forEach { target ->
      try {
        target.install(context)
        installed = true
        System.err.println(
          "ComposeAiCoil: installed a synchronous preview ImageLoader (${target.description}) so " +
            "coil-backed images resolve before capture"
        )
      } catch (failure: Throwable) {
        System.err.println(
          "ComposeAiCoil: could not install the preview ImageLoader (${target.description}): " +
            "${failure.message}; coil-backed images may capture blank"
        )
      }
    }
  }

  /**
   * A composition-local entry the render must provide for coil to resolve images while
   * `LocalInspectionMode` is `true` (the renderer's AS-parity default), or null when the installed
   * coil major doesn't need one.
   *
   * Only **coil 3** returns a value: it routes its inspection-mode branch through
   * `LocalAsyncImagePreviewHandler`, a public hook designed for exactly this, and the renderer
   * hands it a handler that runs the real request. Coil 2 has no such hook and is handled at the
   * bytecode level instead — see [ShadowAsyncImagePainter]. So on a mid-migration classpath with
   * both majors installed, this still yields exactly one entry (coil 3's) and coil 2 is covered by
   * the shadow, which is why taking the first non-null is correct rather than lossy.
   *
   * Mirrors [OfflineXrSession.providedValue]'s shape so the call site stays a one-liner.
   */
  fun previewHandlerProvidedValue(): ProvidedValue<*>? =
    if (!enabled) null
    else
      installers.firstNotNullOfOrNull { installer ->
        runCatching { installer.previewHandlerProvidedValue() }.getOrNull()
      }

  /** Restore each consumer's original singleton loader. Tests and the daemon's teardown path. */
  fun restore() {
    if (!installed) return
    installed = false
    installers.forEach { runCatching { it.restore() } }
  }

  /**
   * One installer per coil major actually present — both when a consumer is mid-migration and has
   * coil 2 and coil 3 on the graph at once. Marker classes are the singleton holders rather than
   * `AsyncImage`, because a consumer can depend on `coil-base` / `coil3-core` without the Compose
   * artifact and still hit this through their own painter.
   */
  private fun detectInstallers(): List<CoilPreviewInstaller> =
    listOfNotNull(
      loadInstaller("coil.Coil", "ee.schimke.composeai.renderer.Coil2PreviewInstaller"),
      loadInstaller(
        "coil3.SingletonImageLoader",
        "ee.schimke.composeai.renderer.Coil3PreviewInstaller",
      ),
    )

  /**
   * Reflectively instantiate [installerClass] only once [markerClass] proves the matching coil
   * major is present. Going through `Class.forName` (rather than naming the installer directly)
   * keeps the coil-typed bytecode off the verification path entirely for the no-coil case, which is
   * the only way to be certain a consumer without coil never sees a `NoClassDefFoundError`.
   */
  private fun loadInstaller(markerClass: String, installerClass: String): CoilPreviewInstaller? {
    val loader = CoilPreviewSupport::class.java.classLoader ?: return null
    return try {
      Class.forName(markerClass, false, loader)
      Class.forName(installerClass, true, loader).getDeclaredConstructor().newInstance()
        as CoilPreviewInstaller
    } catch (absent: Throwable) {
      null
    }
  }
}

/**
 * Per-coil-major strategy for swapping the singleton `ImageLoader`. Implementations live in their
 * own class files so the coil-typed bytecode is never loaded for a consumer on the other major (or
 * on no coil at all).
 */
internal interface CoilPreviewInstaller {
  /** Human-readable major, for the stderr line. */
  val description: String

  /** Swap in the derived, inline-dispatcher loader. */
  fun install(context: Context)

  /**
   * The composition local this coil major needs to bypass its inspection-mode branch, or null when
   * it has no such hook (coil 2 — see [ShadowAsyncImagePainter]).
   */
  fun previewHandlerProvidedValue(): ProvidedValue<*>? = null

  /** Put the consumer's loader back. */
  fun restore()
}

/**
 * Render-time surfacing for coil loads that did **not** resolve, the sibling of
 * [FontResolutionDiagnostics].
 *
 * With the preview loader installed, a local model (a `ByteArray`, a `Drawable`, a `Bitmap`, an
 * `R.drawable` / `content://` / `file://` URI) resolves inline and never lands here. What does land
 * here is the case the renderer can't fix: a request that **failed** (a remote URL under a closed
 * network policy, a decode error) or that was still **pending** when the capture ran. Either way
 * the PNG has a hole in it, and the point of recording it is that the hole comes with a reason
 * attached instead of being a silently blank sticker.
 *
 * Unlike a font fallback this is never fatal. A blank image is a legitimate thing to capture — a
 * preview may deliberately render an empty/offline state — so the PNG is always kept and the
 * unresolved loads ride alongside it in `<png>.warnings.json`.
 *
 * Collection is per-preview: the render loop calls [beginPreview] before a render and
 * [drainPreview] after. A one-line stderr note is emitted once per distinct model per process, so
 * a catalog render that asks for the same unreachable URL on 200 stickers says so once.
 */
object CoilLoadDiagnostics {

  /** How a request ended up unresolved at capture time. */
  enum class Outcome {
    /** coil reported an error — network failure, decode failure, no fetcher for the model. */
    FAILED,
    /** The request was still in flight when the capture ran. */
    PENDING,
  }

  /** One coil request that did not produce pixels for the preview being captured. */
  data class UnresolvedLoad(val model: String, val outcome: Outcome, val detail: String?)

  private val warnedThisProcess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlight = java.util.Collections.synchronizedMap(LinkedHashMap<Any, String>())
  private val failures = java.util.Collections.synchronizedList(mutableListOf<UnresolvedLoad>())

  /** Reset the per-preview buffers. Called by the render loop before each preview's render. */
  fun beginPreview() {
    inFlight.clear()
    synchronized(failures) { failures.clear() }
  }

  /**
   * Snapshot and clear the loads that didn't resolve during the just-finished preview render:
   * everything coil reported an error for, plus everything still in flight at capture time.
   */
  fun drainPreview(): List<UnresolvedLoad> {
    val pending =
      synchronized(inFlight) { inFlight.values.map { UnresolvedLoad(it, Outcome.PENDING, null) } }
    val failed = synchronized(failures) { failures.toList() }
    inFlight.clear()
    synchronized(failures) { failures.clear() }
    val all = failed + pending
    all.forEach { if (warnedThisProcess.add(it.model + '\u0000' + it.outcome)) warn(it) }
    return all
  }

  /** Record that a request for [model] started. [key] identifies it for the terminal callback. */
  internal fun onStart(key: Any, model: String) {
    inFlight[key] = model
  }

  /** Record that the request for [key] produced a bitmap — nothing to warn about. */
  internal fun onSuccess(key: Any) {
    inFlight.remove(key)
  }

  /** Record that the request for [key] failed with [detail]. */
  internal fun onFailure(key: Any, detail: String?) {
    val model = inFlight.remove(key) ?: return
    synchronized(failures) { failures.add(UnresolvedLoad(model, Outcome.FAILED, detail)) }
  }

  /** The human-readable line for [load], used for stderr and the sidecar. */
  fun describe(load: UnresolvedLoad): String =
    when (load.outcome) {
      // A remote model is the common case here and deserves its own wording: the render runs coil
      // inline on the render thread (that's what makes local models resolve), so an HTTP fetch
      // trips Android's main-thread network guard and fails immediately rather than hanging. That
      // is deliberate — a preview that silently depends on live egress isn't reproducible — but
      // "NetworkOnMainThreadException" on its own reads like a bug, so say what it means.
      Outcome.FAILED ->
        if (load.detail?.contains("NetworkOnMainThreadException") == true) {
          "ComposeAiCoil: image request for \"${load.model}\" was not fetched — preview renders " +
            "don't hit the network, so remote models capture blank and may collapse the layout " +
            "around them. Preview with local bytes / an R.drawable, or warm coil's disk cache."
        } else {
          "ComposeAiCoil: image request for \"${load.model}\" failed" +
            (load.detail?.let { " — $it" } ?: "") +
            "; it captures blank and may collapse the layout around it"
        }
      Outcome.PENDING ->
        "ComposeAiCoil: image request for \"${load.model}\" had not completed when the preview " +
          "was captured; it captures blank and may collapse the layout around it"
    }

  private fun warn(load: UnresolvedLoad) = System.err.println(describe(load))

  /** Reset process-wide dedupe + the per-preview buffers. Tests only. */
  internal fun resetForTest() {
    warnedThisProcess.clear()
    beginPreview()
  }
}
