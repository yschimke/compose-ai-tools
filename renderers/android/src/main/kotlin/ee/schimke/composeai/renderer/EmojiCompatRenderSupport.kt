package ee.schimke.composeai.renderer

import android.content.Context

/**
 * Optionally initialises `androidx.emoji2` [EmojiCompat] before a preview composes, so the rendered
 * PNG reflects the **same** emoji font the consumer app actually ships — not the platform fallback.
 *
 * ## Why this exists
 *
 * Compose `Text` routes emoji through EmojiCompat when the process has initialised it (see
 * `PlatformParagraphStyle.emojiSupportMatch`, default `EmojiSupportMatch.Default`). A consumer that
 * depends on `androidx.emoji2:emoji2-bundled` and calls `EmojiCompat.init(BundledEmojiCompatConfig)`
 * renders emoji on-device from that **bundled, version-pinned** NotoColorEmoji. The renderer never
 * runs the consumer's `Application.onCreate()` (see `RobolectricRenderTestBase` — the default
 * `android.app.Application` is used), so EmojiCompat is normally *un*-initialised in the render JVM
 * and Compose falls back to the platform emoji font (the AOSP `NotoColorEmoji.ttf` bundled in
 * Robolectric's `nativeruntime-dist-compat`). That's a preview↔device fidelity gap: newer or
 * differently-styled emoji look different in the preview than in the shipped app.
 *
 * When the consumer ships `emoji2-bundled`, [ensureInitialized] closes that gap by initialising
 * EmojiCompat with the bundled config in the render process — so previews draw the app's own emoji
 * font.
 *
 * ## Gating & dependency posture
 *
 * Everything here is reflective: the renderer takes **no** compile dependency on emoji2. The whole
 * path is a no-op unless `androidx.emoji2.bundled.BundledEmojiCompatConfig` is on the *runtime*
 * classpath — i.e. only when the consumer itself depends on `emoji2-bundled`. A consumer that
 * doesn't use emoji2 renders exactly as before (platform fallback), so this is fully additive.
 *
 * ## Robolectric specifics
 *
 * The bundled metadata is loaded **synchronously** on the calling thread (a direct executor) under
 * `LOAD_STRATEGY_MANUAL`, then the load state is polled with the main looper idled between checks —
 * Robolectric's looper is `PAUSED`, so nothing advances on its own. `replaceAll` is left at its
 * default (`false`), so only emoji the platform font can't render fall through to the bundled font —
 * matching what a default emoji2 consumer draws on-device rather than force-restyling every glyph.
 */
internal object EmojiCompatRenderSupport {

  /** Process-level guard: init is attempted once per render JVM, mirroring [PixelSystemFontAliases]. */
  @Volatile private var attempted = false

  /**
   * Initialise EmojiCompat with the consumer's bundled config, once per process. No-op when
   * `emoji2-bundled` isn't on the classpath, when EmojiCompat is already configured, or on any
   * reflective failure — the render must never fail because of an optional fidelity nicety.
   */
  fun ensureInitialized(context: Context) {
    if (attempted) return
    attempted = true
    val bundledConfigClass =
      runCatching { Class.forName("androidx.emoji2.bundled.BundledEmojiCompatConfig") }.getOrNull()
        ?: return
    runCatching { initBundled(context, bundledConfigClass) }
      .onFailure { System.err.println("EmojiCompat preview init skipped: ${it.message}") }
  }

  private fun initBundled(context: Context, bundledConfigClass: Class<*>) {
    val emojiCompatClass = Class.forName("androidx.emoji2.text.EmojiCompat")
    val configClass = Class.forName("androidx.emoji2.text.EmojiCompat\$Config")

    // Already initialised in this process (e.g. the consumer's own Application ran): leave it be.
    if (emojiCompatClass.getMethod("isConfigured").invoke(null) as Boolean) return

    // Direct executor so `load()` resolves the bundled metadata inline rather than on a background
    // thread Robolectric would never pump. The (Context, Executor) ctor landed in emoji2 1.3.0;
    // fall back to the (Context) ctor on older lines.
    val directExecutor = java.util.concurrent.Executor { it.run() }
    val config =
      runCatching {
          bundledConfigClass
            .getConstructor(Context::class.java, java.util.concurrent.Executor::class.java)
            .newInstance(context, directExecutor)
        }
        .getOrElse { bundledConfigClass.getConstructor(Context::class.java).newInstance(context) }

    val manual = emojiCompatClass.getField("LOAD_STRATEGY_MANUAL").getInt(null)
    configClass
      .getMethod("setMetadataLoadStrategy", Int::class.javaPrimitiveType)
      .invoke(config, manual)
    // Leave `replaceAll` at its default (false): EmojiCompat replaces only emoji the platform font
    // can't render, matching what a default emoji2 consumer draws on-device. Forcing replaceAll=true
    // would push glyphs the platform already covers through the bundled font too, making the preview
    // diverge from the app in the opposite direction — and the renderer can't know the consumer
    // opted into replaceAll. Gap-fill still fixes the case that matters: newer / ZWJ emoji the frozen
    // preview font lacks get the bundled glyph, while system-covered emoji stay on the platform font.

    val instance = emojiCompatClass.getMethod("init", configClass).invoke(null, config)
    emojiCompatClass.getMethod("load").invoke(instance)

    val succeeded = emojiCompatClass.getField("LOAD_STATE_SUCCEEDED").getInt(null)
    val getLoadState = emojiCompatClass.getMethod("getLoadState")
    // With a direct executor `load()` normally settles synchronously; poll + idle as a safety net.
    repeat(100) {
      if (getLoadState.invoke(instance) as Int == succeeded) return
      idleMainLooper()
    }
  }

  private fun idleMainLooper() {
    runCatching {
      val looper = android.os.Looper.getMainLooper()
      org.robolectric.Shadows.shadowOf(looper).idle()
    }
  }

  /**
   * Clears the once-per-process [attempted] guard so a unit test can drive [ensureInitialized] from
   * a clean slate. Test-only — production code relies on the guard firing exactly once per render
   * JVM.
   */
  internal fun resetForTest() {
    attempted = false
  }
}
