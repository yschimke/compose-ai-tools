package ee.schimke.composeai.renderer

import android.content.Context
import androidx.emoji2.text.EmojiCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

/**
 * Verifies [EmojiCompatRenderSupport] against the real `androidx.emoji2` API (on the test classpath
 * only). The reflective init is the risky part — method/field names have to match emoji2 — so these
 * tests exercise that wiring end-to-end rather than mocking it. The end-to-end *visual* effect
 * (bundled emoji reaching a rendered PNG) is covered by
 * `:samples:android`'s `EmojiCompatComparisonPreview`.
 *
 * `sdk = [35]` reuses the `android-all` artifact the render pipeline already resolves, so the test
 * needs no extra SDK download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// EmojiCompat init touches no TLS; disable Conscrypt so the test doesn't load its native lib
// (mirrors the render pipeline's `robolectric.conscryptMode=OFF`).
@ConscryptMode(ConscryptMode.Mode.OFF)
class EmojiCompatRenderSupportTest {

  @Before
  fun reset() {
    EmojiCompatRenderSupport.resetForTest()
    resetEmojiCompatSingleton()
  }

  @After
  fun tearDown() {
    resetEmojiCompatSingleton()
  }

  @Test
  fun `ensureInitialized configures EmojiCompat via the reflective bundled-config path`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    EmojiCompatRenderSupport.ensureInitialized(context)

    assertTrue(
      "ensureInitialized should have initialised EmojiCompat from emoji2-bundled",
      EmojiCompat.isConfigured(),
    )
  }

  @Test
  fun `ensureInitialized is safe to call more than once`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Second call hits the once-per-process guard; neither call may throw.
    EmojiCompatRenderSupport.ensureInitialized(context)
    EmojiCompatRenderSupport.ensureInitialized(context)

    assertTrue(EmojiCompat.isConfigured())
  }

  /**
   * Clears the `EmojiCompat` process singleton between tests. Reflective so the test compiles
   * without depending on the `@VisibleForTesting` reset overload's exact visibility; best-effort, so
   * a signature change just weakens isolation rather than breaking the suite.
   */
  private fun resetEmojiCompatSingleton() {
    runCatching {
      val reset = EmojiCompat::class.java.getDeclaredMethod("reset")
      reset.isAccessible = true
      reset.invoke(null)
    }
  }
}
