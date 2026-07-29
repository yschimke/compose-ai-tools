package ee.schimke.composeai.renderer

import android.content.Context
import androidx.compose.runtime.ProvidedValue
import coil3.EventListener
import coil3.ColorImage
import coil3.Image
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePreviewHandler

import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers

/**
 * [CoilPreviewInstaller] for **coil 3** (`io.coil-kt.coil3:coil*`, package `coil3.*`).
 *
 * Same shape and same rationale as [Coil2PreviewInstaller] — see that file for why each override is
 * there. coil 3 renamed the knobs (`*Dispatcher` → `*CoroutineContext`, `Coil` →
 * `SingletonImageLoader`) and dropped the ones that no longer apply (there is no network observer
 * or `respectCacheHeaders` on the loader; the network layer is a pluggable component), so the
 * override list is shorter, but the load-bearing part is identical: rebind the dispatchers to the
 * immediate main dispatcher so `execute()` runs inline before the capture.
 *
 * The second half is [previewHandlerProvidedValue]. The renderer composes with
 * `LocalInspectionMode = true` by default (AS parity, issue #1584), and coil short-circuits into a
 * placeholder-only branch when that's set. coil 3 makes that branch overridable through
 * `LocalAsyncImagePreviewHandler` — so the renderer supplies a handler that runs the real request,
 * and coil 3 needs no bytecode-level help. coil 2 has no equivalent hook, which is why it needs
 * [ShadowAsyncImagePainter] instead.
 *
 * Never referenced by name from [CoilPreviewSupport]; instantiated through `Class.forName` behind a
 * `coil3.SingletonImageLoader` probe.
 */
internal class Coil3PreviewInstaller : CoilPreviewInstaller {

  override val description: String = "coil 3"

  private var original: ImageLoader? = null

  override fun install(context: Context) {
    val consumerLoader = SingletonImageLoader.get(context)
    original = consumerLoader
    // `setUnsafe` (not `setSafe`): `setSafe` is a no-op once a loader has been resolved, and
    // resolving it is exactly what the line above just did.
    SingletonImageLoader.setUnsafe(
      consumerLoader
        .newBuilder()
        .interceptorCoroutineContext(Dispatchers.Main.immediate)
        .fetcherCoroutineContext(Dispatchers.Main.immediate)
        .decoderCoroutineContext(Dispatchers.Main.immediate)
        .eventListener(DiagnosticEventListener)
        .build()
    )
  }

  override fun restore() {
    original?.let(SingletonImageLoader::setUnsafe)
    original = null
  }

  /**
   * `LocalAsyncImagePreviewHandler` bound to a handler that actually executes the request, instead
   * of coil's `Default` (which resolves the request's placeholder and stops there).
   *
   * The loader it reaches for is the singleton — i.e. the inline-dispatcher one [install] just put
   * there — so the `execute` completes on the render thread before the capture, exactly like the
   * non-inspection path.
   */
  override fun previewHandlerProvidedValue(): ProvidedValue<*> =
    LocalAsyncImagePreviewHandler provides previewHandler

  /**
   * The handler's lambda must return a non-null `Image`, but a request the sandbox can't fetch
   * yields an `ErrorResult` whose `image` is null. Substituting a zero-sized transparent
   * [ColorImage] keeps that case on the same path as a normal coil error — nothing drawn — instead
   * of throwing out of the handler and taking the whole composition down with it.
   * [CoilLoadDiagnostics] is what turns the miss into an explanation in the warnings sidecar.
   */
  private val previewHandler: AsyncImagePreviewHandler = AsyncImagePreviewHandler {
    request: ImageRequest ->
    val loaded: Image? = SingletonImageLoader.get(request.context).execute(request).image
    loaded ?: EMPTY_IMAGE
  }

  private companion object {
    /** Transparent and zero-sized, so a failed load contributes no pixels and no intrinsic size. */
    val EMPTY_IMAGE: Image = ColorImage(color = 0, width = 0, height = 0)
  }

  /** coil 3 twin of [Coil2PreviewInstaller]'s listener; same trade-off on replacing the consumer's. */
  private object DiagnosticEventListener : EventListener() {
    override fun onStart(request: ImageRequest) {
      CoilLoadDiagnostics.onStart(request, describeModel(request.data))
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
      CoilLoadDiagnostics.onSuccess(request)
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
      CoilLoadDiagnostics.onFailure(request, result.throwable.toString())
    }
  }
}
