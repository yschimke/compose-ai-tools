package ee.schimke.composeai.daemon

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for compose-ai-tools#1360 finding #1: the synthetic IME dispatch in
 * [KeyboardOverrideExtension] must seed the builder from the view's existing root insets so non-IME
 * inset types (status bar, navigation bar, system gestures, …) survive the dispatch.
 *
 * `WindowInsetsCompat.Builder().setInsets(Type.ime(), …).build()` starts from an empty inset set:
 * before this fix, every keyboard visibility toggle zeroed status / navigation / safe-drawing
 * insets in `WindowInsetsHolder`, and consumer modifiers like `Modifier.systemBarsPadding()` or
 * `WindowInsets.safeDrawing.asPaddingValues()` briefly collapsed their padding on each transition.
 *
 * Robolectric-backed because `WindowInsetsCompat.Builder.setInsets(...)` wraps the framework
 * `WindowInsets` class internally; against the Android-jar stubs `setInsets` returns the empty set
 * and the merge is unobservable. Robolectric supplies the real framework impl.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardInsetsMergeTest {

  @Test
  fun `buildKeyboardInsets preserves non-IME insets from the existing window insets`() {
    // Simulate the host view already carrying system-bar / navigation-bar insets — exactly the
    // shape `ViewCompat.getRootWindowInsets(view)` returns on a real device once the system has
    // laid out the decor view.
    val statusBars = Insets.of(0, 60, 0, 0)
    val navigationBars = Insets.of(0, 0, 0, 80)
    val systemGestures = Insets.of(16, 0, 16, 0)
    val existing =
      WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), statusBars)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBars)
        .setInsets(WindowInsetsCompat.Type.systemGestures(), systemGestures)
        .build()

    val imeInsets = Insets.of(0, 0, 0, 240)
    val merged = buildKeyboardInsets(existing, imeInsets)

    // The IME inset we just dispatched should be visible to consumers reading `WindowInsets.ime`.
    assertEquals(
      "IME inset must reflect the band height dispatched by the around-composable",
      imeInsets,
      merged.getInsets(WindowInsetsCompat.Type.ime()),
    )
    // The non-IME inset types must survive the dispatch — that's the bug from #1360.
    assertEquals(
      "status-bar inset must survive synthetic IME dispatch (otherwise systemBars padding " +
        "collapses on every keyboard visibility change)",
      statusBars,
      merged.getInsets(WindowInsetsCompat.Type.statusBars()),
    )
    assertEquals(
      "navigation-bar inset must survive synthetic IME dispatch (otherwise safeDrawing padding " +
        "collapses on every keyboard visibility change)",
      navigationBars,
      merged.getInsets(WindowInsetsCompat.Type.navigationBars()),
    )
    assertEquals(
      "system-gestures inset must survive synthetic IME dispatch",
      systemGestures,
      merged.getInsets(WindowInsetsCompat.Type.systemGestures()),
    )
  }

  @Test
  fun `buildKeyboardInsets handles null root insets by emitting an IME-only payload`() {
    // `ViewCompat.getRootWindowInsets(view)` can return null before the view is attached. The
    // helper falls back to a fresh builder; the dispatch still carries the IME inset so the
    // band's height reaches consumer modifiers immediately.
    val imeInsets = Insets.of(0, 0, 0, 240)
    val merged = buildKeyboardInsets(existing = null, imeInsets = imeInsets)

    assertEquals(imeInsets, merged.getInsets(WindowInsetsCompat.Type.ime()))
    // Non-IME insets default to Insets.NONE under a fresh builder — that's expected and matches
    // what a real un-attached view would produce.
    assertEquals(Insets.NONE, merged.getInsets(WindowInsetsCompat.Type.statusBars()))
  }
}
