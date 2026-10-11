package ee.schimke.composeai.preview.color

import androidx.activity.ComponentActivity
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Shape-only smoke test: each helper composes without throwing and surfaces the expected labels
 * (counted via `onAllNodesWithText`, as in the typography runtime). Swatch pixels are verified by
 * the `:samples:android` preview renders instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorSpecimenTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `ColorSchemeSpecimen renders one row per Material 3 colour role`() {
    composeRule.setContent { ColorSchemeSpecimen(colorScheme = lightColorScheme()) }
    composeRule.waitForIdle()

    // A representative spread across the accent families, surfaces, and utility roles. Asserting on
    // the label strings guards both that the role is present and that its name didn't drift — a
    // regression that renames or drops a role fails specifically here.
    val expectedRoles =
      listOf(
        "primary",
        "onPrimary",
        "primaryContainer",
        "secondary",
        "tertiaryContainer",
        "background",
        "surface",
        "surfaceVariant",
        "inverseSurface",
        "error",
        "outline",
        "outlineVariant",
        "scrim",
        "surfaceContainerHighest",
      )
    for (role in expectedRoles) {
      val count = composeRule.onAllNodesWithText(role).fetchSemanticsNodes().size
      assert(count == 1) {
        "Expected exactly one row labelled \"$role\" in ColorSchemeSpecimen, got $count"
      }
    }
  }

  @Test
  fun `ColorSpecimen renders one row per supplied named colour`() {
    val palette =
      listOf(
        "brand/coral" to Color(0xFFFF6F61),
        "brand/teal" to Color(0xFF008080),
        "brand/gold" to Color(0xFFFFD700),
      )
    composeRule.setContent { ColorSpecimen(colors = palette) }
    composeRule.waitForIdle()

    for ((label, _) in palette) {
      val count = composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size
      assert(count == 1) {
        "Expected exactly one row labelled \"$label\" in ColorSpecimen, got $count"
      }
    }
  }

  @Test
  fun `ColorSpecimen labels each swatch with its full AARRGGBB hex`() {
    // The hex column is the machine-readable half of the swatch — worth a dedicated assertion since
    // the alpha byte is what distinguishes a semi-transparent role from its opaque sibling.
    val opaque = Color(0xFF3366CC)
    val translucent = Color(0x80000000)
    composeRule.setContent {
      ColorSpecimen(colors = listOf("opaque" to opaque, "translucent" to translucent))
    }
    composeRule.waitForIdle()

    for (expected in listOf("#FF3366CC", "#80000000")) {
      val count = composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().size
      assert(count == 1) { "Expected one swatch labelled \"$expected\", got $count" }
    }
  }
}
