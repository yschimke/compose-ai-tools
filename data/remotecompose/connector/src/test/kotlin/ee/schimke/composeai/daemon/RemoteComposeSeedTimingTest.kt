package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **When** `renderNow.overrides.remoteCompose` reaches the controller, relative to the composition
 * pass that reads it.
 *
 * [RemoteComposeOverrideExtension] used to seed from a `DisposableEffect(seed)`, whose block runs
 * *after* the pass that registered it — so the first composition composed unseeded and the seed
 * only landed as a recomposition. A named value read straight into the composition survives that
 * (it re-reads on the recomposition), which is why the defect stayed invisible; the two shapes
 * below do not, and they are the same two that broke the plain-Compose sibling as #4209 / #4210.
 *
 * It bites on the live serve lane: `ServeThemeReplay.expand` turns a `?themeProvider=` request into
 * `rc.<role>=color:…` named seeds for replayed previews, and those arrive through this extension.
 *
 * These are composition-level rather than pixel-level (the shape [OverrideIntegrationTest]'s
 * `namedOverrideReachesAKeylessRememberOnTheFirstComposition` uses in `:daemon:android`) because
 * the daemon's render lanes only register this extension when the alpha `compose-remote` artifacts
 * are on the consumer's classpath — `isRemoteComposeAvailable` is false on every in-repo render
 * test JVM. What is under test is the seeding *phase*, and the composition is where that is
 * observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class RemoteComposeSeedTimingTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  /** What the daemon seeds. */
  private val seededBlue = "#FF42A5F5"

  /** What the preview's author wrote as the fallback. */
  private val authorRed = "#FFEF5350"

  private fun seedOf(name: String, argb: String): RemoteComposeOverride =
    RemoteComposeOverride(namedValues = mapOf(name to RemoteNamedValue.ColorValue(argb)))

  /** Compose [content] the way a render does — wrapped in the extension's around-composable. */
  @Composable
  private fun Seeded(seed: RemoteComposeOverride?, content: @Composable () -> Unit) {
    val extension = remember(seed) { RemoteComposeOverrideExtension(seed) }
    extension.Around(
      ExtensionComposeContext(
        extensionId = RemoteComposeOverrideExtension.ID,
        previewId = "seed-timing",
        renderMode = null,
      )
    ) {
      content()
    }
  }

  private fun String.toComposeColor(): Color = Color(removePrefix("#").toLong(16).toInt())

  /**
   * Regression for the Remote Compose twin of #4210 — the seed has to be in the controller **before
   * the first composition pass**, not one pass late.
   *
   * A knob read straight into the composition cannot catch this: a seed applied after the first
   * pass still lands, via the recomposition the snapshot write triggers. A knob captured by a
   * **keyless `remember`** has no second chance — the initializer runs once, on the first pass, and
   * keeps what it saw. Every androidx `remember*State` factory is that shape, so a preview binding
   * a daemon-seeded colour into one quietly published its unseeded fallback.
   */
  @Test
  fun `a named seed reaches a keyless remember on the first composition`() {
    var captured: String? = null
    rule.setContent {
      Seeded(seedOf("fill", seededBlue)) {
        val fill = LocalRemoteComposeHost.current.namedColor("fill", default = authorRed)
        // Keyless on purpose — the shape every `remember*State` factory has. Do not add `fill` as
        // a key: that would re-run the initializer on the late-seed recomposition and hide the bug
        // this test exists to catch.
        captured = remember { fill }
      }
    }
    rule.waitForIdle()

    assertEquals(
      "a seed captured by a keyless remember must be the daemon's value, not the author default" +
        " — the seed landed a composition pass too late",
      seededBlue,
      captured,
    )
  }

  /**
   * Regression for the Remote Compose twin of #4209 — the sibling symptom of the same late seed.
   *
   * An animation captures its initial value on the first composition pass. Seeding after that pass
   * doesn't set the value, it *retargets* an animation already sitting on the unseeded one — and a
   * render captures the first frame or two, so the PNG shows the value the seed was supposed to
   * replace. Asserted on the *first* value the composition observed, which is what a render's early
   * frame draws.
   */
  @Test
  fun `a named seed settles an animated value without animating from the default`() {
    val observed = mutableListOf<Color>()
    rule.setContent {
      Seeded(seedOf("fill", seededBlue)) {
        val fill = LocalRemoteComposeHost.current.namedColor("fill", default = authorRed)
        val animated by animateColorAsState(targetValue = fill.toComposeColor())
        observed += animated
      }
    }
    rule.waitForIdle()

    assertEquals(
      "an animated value must start settled on the seed, not animate to it from the author" +
        " default; observed $observed",
      seededBlue.toComposeColor(),
      observed.first(),
    )
    assertEquals(
      "with the seed applied before the first pass there is nothing to animate; observed $observed",
      listOf(seededBlue.toComposeColor()),
      observed.distinct(),
    )
  }

  /**
   * The reconciliation the phase move needed: the seed shares the named-value map with
   * [RemoteComposeController.setNamedValue], which user code calls from inside a `RemotePreview`
   * block and `interactive/setRemoteCompose` uses for single-value live edits.
   *
   * Applying the seed during composition means it is on the recomposition path, so a naive
   * "re-apply every pass" would drop a live edit the moment anything else recomposed — the whole
   * point of `interactive/setRemoteCompose` being merge-don't-replace. The extension applies the
   * seed once per override identity instead, so an unchanged override recomposes without touching
   * what was written back.
   */
  @Test
  fun `a live edit survives a recomposition under an unchanged seed`() {
    val tick = mutableStateOf(0)
    var seenTick = -1
    rule.setContent {
      Seeded(seedOf("fill", seededBlue)) {
        seenTick = tick.value
        LocalRemoteComposeHost.current.namedColor("fill", default = authorRed)
      }
    }
    rule.waitForIdle()

    // The panel pushes a single-value edit at the live session, the way `AndroidInteractiveSession`
    // does for `interactive/setRemoteCompose`.
    RemoteComposeController.setNamedValue("live", RemoteNamedValue.IntValue(7))
    tick.value = 1
    rule.waitForIdle()

    assertEquals("the composition should have recomposed", 1, seenTick)
    assertEquals(
      "a recomposition under an unchanged seed must not replace the named-value map",
      RemoteNamedValue.IntValue(7),
      RemoteComposeController.valueOf("live"),
    )
    assertEquals(
      "the seeded value must still be applied",
      RemoteNamedValue.ColorValue(seededBlue),
      RemoteComposeController.valueOf("fill"),
    )
  }

  /**
   * A fresh override still replaces the map wholesale, and the *old* seed's on-dispose clear must
   * not take the new one down with it.
   *
   * That ordering is new: the seed is applied during composition, but the `DisposableEffect` that
   * owns the clear disposes in the apply phase — i.e. *after* the pass that installed the
   * replacement. A blind `set(null)` there would leave the render unseeded;
   * [RemoteComposeController.clearSeed] compares identities so the stale clear is a no-op.
   */
  @Test
  fun `a replacement seed survives the outgoing seed's dispose`() {
    val seed = mutableStateOf(seedOf("fill", authorRed))
    var observedFill: String? = null
    rule.setContent {
      Seeded(seed.value) {
        observedFill = LocalRemoteComposeHost.current.namedColor("fill", default = "#FF000000")
      }
    }
    rule.waitForIdle()
    assertEquals(authorRed, observedFill)

    seed.value = seedOf("fill", seededBlue)
    rule.waitForIdle()

    assertEquals("the replacement seed must be the one applied", seededBlue, observedFill)
    assertEquals(
      "the outgoing seed's dispose must not clear the replacement",
      RemoteNamedValue.ColorValue(seededBlue),
      RemoteComposeController.valueOf("fill"),
    )
  }
}
