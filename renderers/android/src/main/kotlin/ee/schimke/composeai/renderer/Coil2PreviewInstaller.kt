package ee.schimke.composeai.renderer

import android.content.Context
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers

/**
 * [CoilPreviewInstaller] for **coil 2** (`io.coil-kt:coil*`, package `coil.*`).
 *
 * Never referenced by name from [CoilPreviewSupport] — it is instantiated through `Class.forName`
 * behind a `coil.Coil` probe, so a consumer without coil 2 never loads this class and never sees a
 * `NoClassDefFoundError` for the `coil.*` types in its signature.
 *
 * The swap derives from the consumer's *own* loader (`newBuilder()`), so their `ComponentRegistry`
 * — custom fetchers, decoders, keyers, mappers, interceptors — plus their memory/disk caches all
 * survive. Only the parts that make a render non-deterministic are overridden:
 *
 * - **the four dispatchers** → `Dispatchers.Main.immediate`. This is the whole fix. coil defaults
 *   these to `Dispatchers.IO`, a real thread pool that neither Compose's paused `mainClock` nor
 *   Robolectric's main looper drives; rebinding them to the immediate main dispatcher makes
 *   `execute()` run *inline* on the render thread, so a local model (`ByteArray`, `Drawable`,
 *   `Bitmap`, an `R.drawable` / `file://` / `content://` URI) is fetched and decoded inside the
 *   same `onRemembered` that started it. By the time `setContent` returns the painter holds a real
 *   result — and therefore a real intrinsic size, which is what stops `ContentScale.FillWidth` from
 *   collapsing the layout around it.
 * - **crossfade off** — a transition is a time-based animation, and the renderer's whole capture
 *   contract is a paused clock at a known virtual time. A crossfading image would capture at
 *   whatever alpha the clock happened to land on.
 * - **hardware bitmaps off** — `Bitmap.Config.HARDWARE` has no backing GPU allocation under
 *   Robolectric, and a hardware bitmap can't be read back by the PNG capture even when it does
 *   allocate.
 * - **network observer off** — it registers a `ConnectivityManager` callback and defaults the
 *   loader to "offline" when the sandbox reports no network, which would fail requests that the
 *   disk cache could have served.
 * - **cache headers not respected** — a warm disk cache should serve a render regardless of how
 *   long ago the response said it expires; the render wants determinism, not freshness.
 */
internal class Coil2PreviewInstaller : CoilPreviewInstaller {

  override val description: String = "coil 2"

  private var original: ImageLoader? = null

  override fun install(context: Context) {
    // Resolves the consumer's loader: an `Application` implementing `ImageLoaderFactory`, an
    // earlier `Coil.setImageLoader(...)`, or coil's own default built from this context.
    val consumerLoader = Coil.imageLoader(context)
    original = consumerLoader
    Coil.setImageLoader(
      consumerLoader
        .newBuilder()
        .interceptorDispatcher(Dispatchers.Main.immediate)
        .fetcherDispatcher(Dispatchers.Main.immediate)
        .decoderDispatcher(Dispatchers.Main.immediate)
        .transformationDispatcher(Dispatchers.Main.immediate)
        .crossfade(false)
        .allowHardware(false)
        .networkObserverEnabled(false)
        .respectCacheHeaders(false)
        .components { add(PlaceholderFallbackInterceptor) }
        .eventListener(DiagnosticEventListener)
        .build()
    )
  }

  override fun restore() {
    original?.let(Coil::setImageLoader)
    original = null
  }

  /**
   * Feeds [CoilLoadDiagnostics] so a request that *can't* resolve here — a remote URL under a
   * closed network policy, a decode failure — surfaces as a warning instead of an unexplained blank
   * sticker.
   *
   * This replaces any `eventListener` the consumer set on their loader (coil exposes no way to read
   * the existing one back off a builder, so chaining isn't possible). That's a deliberate trade: an
   * app-side analytics listener firing during an off-device preview render has no audience, whereas
   * "why is this preview blank" is the exact question this whole file exists to answer.
   */
  private object DiagnosticEventListener : EventListener {
    override fun onStart(request: ImageRequest) {
      CoilLoadDiagnostics.onStart(request, describeModel(request.data))
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
      CoilLoadDiagnostics.onSuccess(request)
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
      CoilLoadDiagnostics.onFailure(request, result.throwable.toString())
    }

    override fun onCancel(request: ImageRequest) {
      // A cancelled request is indistinguishable from one that never finished as far as the
      // captured pixels go, so leave it in the in-flight map and let the drain report it PENDING.
    }
  }

  private object PlaceholderFallbackInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
      val result = chain.proceed(chain.request)
      return coil2ResultWithPlaceholderFallback(result)
    }
  }
}

internal fun coil2ResultWithPlaceholderFallback(result: ImageResult): ImageResult {
  val placeholder = result.request.placeholder
  return if (result is ErrorResult && result.drawable == null && placeholder != null) {
    ErrorResult(placeholder, result.request, result.throwable)
  } else {
    result
  }
}

/**
 * A short, stable label for a coil model, for warning text. `toString()` alone would print
 * `[B@4f3a2b1c` for the `ByteArray` case that motivated issue #2952 — useless in a warning, and
 * different on every run, which would make the sidecar non-reproducible.
 */
internal fun describeModel(model: Any?): String =
  when (model) {
    null -> "null"
    is ByteArray -> "ByteArray(${model.size} bytes)"
    is CharSequence -> model.toString()
    is Int -> "resource 0x${model.toString(16)}"
    else -> {
      val rendered = model.toString()
      // Anything relying on the identity `toString()` default (`pkg.Type@1a2b3c`) is reduced to
      // its type so the label is reproducible run to run.
      if (rendered.matches(IDENTITY_TO_STRING)) model.javaClass.simpleName else rendered
    }
  }

private val IDENTITY_TO_STRING = Regex("""^\S+@[0-9a-f]+$""")
