package ee.schimke.composeai.renderer

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/**
 * Robolectric shadow for `androidx.core.provider.FontsContractCompat.requestFont`
 * that short-circuits the GMS Fonts provider lookup and delegates to
 * [GoogleFontCacheAccess] instead.
 *
 * Why shadow here rather than wrap `FontFamily.Resolver`: in Compose 1.9+
 * `FontFamily.Resolver` is a `sealed interface`, so external modules can't
 * implement it. The TypefaceLoader path is the lowest-level hook the public
 * AndroidX surface exposes — `FontsContractCompat.requestFont` is what
 * Compose's internal `DefaultFontsContractCompatLoader` calls, so a shadow
 * covers every GoogleFont usage no matter which FontFamily the consumer
 * builds.
 *
 * Registered globally via the generated `robolectric.properties` on the
 * renderer's test classpath (see
 * [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask]).
 *
 * **Callback dispatch.** The real `requestFont` posts the callback through
 * the supplied [Handler]. Under Robolectric's PAUSED looper mode the post
 * never runs during `renderDefault`'s `advanceTimeBy(32ms)` pump — that pump
 * drives Compose's `MonotonicFrameClock`, not the main Looper's pending-task
 * queue — so the awaiting coroutine stays suspended, Compose's async font
 * loader never transitions, and Text keeps rendering in the platform
 * fallback. We invoke the callback synchronously instead: the caller is
 * `GoogleFontTypefaceLoader.awaitLoad` inside a coroutine, and
 * `onTypefaceRetrieved` merely resumes that continuation — no re-entrance
 * hazard, and the Typeface lands before the first frame fires.
 */
@Implements(FontsContractCompat::class)
class ShadowFontsContractCompat {
    companion object {
        /**
         * Matches the 7-arg `FontsContractCompat.requestFont` overload that
         * AndroidX exposes in `core:1.7+`:
         * ```java
         * public static void requestFont(
         *     Context, FontRequest, int style, boolean isBlockingFetch,
         *     int timeout, Handler, FontRequestCallback)
         * ```
         * This is the entrypoint Compose's `DefaultFontsContractCompatLoader`
         * calls. Older / newer 5-arg variants (deprecated but still on the
         * class in some BOMs) fall through to the real implementation — they
         * aren't on the Compose path.
         */
        @JvmStatic
        @Implementation
        fun requestFont(
            @Suppress("UNUSED_PARAMETER") context: Context,
            request: FontRequest,
            @Suppress("UNUSED_PARAMETER") style: Int,
            @Suppress("UNUSED_PARAMETER") isBlockingFetch: Boolean,
            @Suppress("UNUSED_PARAMETER") timeout: Int,
            @Suppress("UNUSED_PARAMETER") handler: Handler,
            callback: FontsContractCompat.FontRequestCallback,
        ) {
            val key = parseFontRequestQuery(request.query)
            val file: File? = key?.let {
                GoogleFontCacheAccess.load(it.name, it.weight.weight, it.italic)
            }
            if (file == null) {
                callback.onTypefaceRequestFailed(
                    FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND,
                )
                return
            }
            val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
            if (typeface == null) {
                callback.onTypefaceRequestFailed(
                    FontsContractCompat.FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR,
                )
                return
            }
            callback.onTypefaceRetrieved(typeface)
        }
    }
}
