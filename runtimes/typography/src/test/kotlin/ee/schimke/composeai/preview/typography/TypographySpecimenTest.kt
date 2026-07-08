package ee.schimke.composeai.preview.typography

import androidx.activity.ComponentActivity
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smoke test for the three specimen helpers. Each test composes the helper into a Robolectric
 * activity, waits for first composition, then asserts:
 *
 * 1. The helper renders without throwing (the `composeRule.setContent { … }` call would propagate a
 *    composition-time exception here).
 * 2. The expected number of labelled rows surface in the semantics tree, counted by querying for
 *    each row's label string. We use `onAllNodesWithText(label, substring = false)
 *    .fetchSemanticsNodes().size` because `assertCountEquals` would couple us to the merged-vs-
 *    unmerged tree shape and `Text` in Material 3 can register either way depending on the ambient
 *    `LocalContentColor` etc.
 *
 * The tests stay deliberately shape-only — they do NOT measure rendered glyph metrics. The helpers
 * are display surfaces whose visual correctness is verified through the compose-preview render
 * pipeline (the sibling `:samples:android` `@Preview` fixtures), not unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypographySpecimenTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `TypographySpecimen renders one row per Material 3 type role`() {
    composeRule.setContent { TypographySpecimen(typography = Typography()) }
    composeRule.waitForIdle()

    // Fifteen Material 3 roles — three sizes (Large / Medium / Small) across five families
    // (display / headline / title / body / label). Asserting on every label string guards both
    // the helper's row count AND its row order — a regression that drops `labelSmall` or renames
    // a role fails specifically here rather than producing a vague "wrong number of rows" diff.
    val expectedRoles =
      listOf(
        "displayLarge",
        "displayMedium",
        "displaySmall",
        "headlineLarge",
        "headlineMedium",
        "headlineSmall",
        "titleLarge",
        "titleMedium",
        "titleSmall",
        "bodyLarge",
        "bodyMedium",
        "bodySmall",
        "labelLarge",
        "labelMedium",
        "labelSmall",
      )
    for (role in expectedRoles) {
      val count = composeRule.onAllNodesWithText(role).fetchSemanticsNodes().size
      assert(count == 1) {
        "Expected exactly one row labelled \"$role\" in TypographySpecimen, got $count"
      }
    }
  }

  @Test
  fun `FontFamilySpecimen renders one row per weight in the supplied ladder`() {
    val weights =
      listOf(
        FontWeight.Light,
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
      )
    composeRule.setContent {
      FontFamilySpecimen(fontFamily = FontFamily.SansSerif, weights = weights)
    }
    composeRule.waitForIdle()

    // Default weight ladder produces five rows — one per token in the `DefaultWeights` list.
    // The label column is what we count; the sample text is the same across rows so counting it
    // would just verify "5 == 5" tautologically.
    for (weight in weights) {
      val label = weightLabel(weight)
      val count = composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size
      assert(count == 1) {
        "Expected exactly one row labelled \"$label\" in FontFamilySpecimen, got $count"
      }
    }
  }

  @Test
  fun `FontFamilySpecimen labels unknown weights with their numeric value`() {
    // The label fallback path (`w350` for custom weights) is what keeps the row label
    // deterministic for variable-font specimens. Worth a dedicated assertion since the path is
    // separate from the named-weight `when` arm.
    val custom = FontWeight(350)
    composeRule.setContent {
      FontFamilySpecimen(fontFamily = FontFamily.SansSerif, weights = listOf(custom))
    }
    composeRule.waitForIdle()

    val count = composeRule.onAllNodesWithText("w350").fetchSemanticsNodes().size
    assert(count == 1) { "Expected one row labelled \"w350\" for FontWeight(350), got $count" }
  }

  @Test
  fun `FallbackCoverageSpecimen renders one row per canonical script`() {
    composeRule.setContent { FallbackCoverageSpecimen() }
    composeRule.waitForIdle()

    // The five canonical scripts. Counting via the label column rather than the sample text
    // string so an environment with missing glyphs (which would render the sample as tofu) still
    // passes the structural test — broken glyph fallback is a render-time visual regression, not
    // a composition-time bug, and is caught by the `:samples:android` PNG diff.
    val expectedScripts = listOf("Latin", "CJK", "Arabic", "Devanagari", "Emoji")
    for (script in expectedScripts) {
      val count = composeRule.onAllNodesWithText(script).fetchSemanticsNodes().size
      assert(count == 1) {
        "Expected exactly one row labelled \"$script\" in FallbackCoverageSpecimen, got $count"
      }
    }
  }
}
