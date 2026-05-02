package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage of [AccessibilityChecker.buildStates] — the projection
 * from ATF's per-element booleans / strings to the legend chip list. ATF
 * itself can't be exercised here without a real `View` graph, so we test
 * the chip selection rules (and their order) directly.
 */
class AccessibilityCheckerStatesTest {

    @Test
    fun `default node emits no chips`() {
        assertEquals(
            emptyList<String>(),
            AccessibilityChecker.buildStates(
                isCheckable = false,
                isChecked = false,
                isClickable = false,
                isLongClickable = false,
                isScrollable = false,
                isEditable = false,
                isEnabled = true,
                stateDescription = "",
                hintText = "",
            ),
        )
    }

    @Test
    fun `checkable surfaces checked or unchecked depending on state`() {
        val checked = AccessibilityChecker.buildStates(
            isCheckable = true,
            isChecked = true,
            isClickable = false,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            isEnabled = true,
            stateDescription = "",
            hintText = "",
        )
        val unchecked = AccessibilityChecker.buildStates(
            isCheckable = true,
            isChecked = false,
            isClickable = false,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            isEnabled = true,
            stateDescription = "",
            hintText = "",
        )
        assertEquals(listOf("checked"), checked)
        assertEquals(listOf("unchecked"), unchecked)
    }

    @Test
    fun `non-checkable suppresses checked chip even if isChecked is true`() {
        // ATF returns non-null isChecked even when the View isn't a
        // checkable widget; gate strictly on isCheckable so a stray Boolean
        // doesn't surface a meaningless "unchecked" on every label.
        assertEquals(
            emptyList<String>(),
            AccessibilityChecker.buildStates(
                isCheckable = false,
                isChecked = true,
                isClickable = false,
                isLongClickable = false,
                isScrollable = false,
                isEditable = false,
                isEnabled = true,
                stateDescription = "",
                hintText = "",
            ),
        )
    }

    @Test
    fun `clickable button surfaces clickable chip`() {
        assertEquals(
            listOf("clickable"),
            AccessibilityChecker.buildStates(
                isCheckable = false,
                isChecked = false,
                isClickable = true,
                isLongClickable = false,
                isScrollable = false,
                isEditable = false,
                isEnabled = true,
                stateDescription = "",
                hintText = "",
            ),
        )
    }

    @Test
    fun `long-clickable scrollable editable disabled all surface independently`() {
        assertEquals(
            listOf("long-clickable", "scrollable", "editable", "disabled"),
            AccessibilityChecker.buildStates(
                isCheckable = false,
                isChecked = false,
                isClickable = false,
                isLongClickable = true,
                isScrollable = true,
                isEditable = true,
                isEnabled = false,
                stateDescription = "",
                hintText = "",
            ),
        )
    }

    @Test
    fun `stateDescription and hintText round-trip into the chip list`() {
        assertEquals(
            listOf("expanded", "hint: Search"),
            AccessibilityChecker.buildStates(
                isCheckable = false,
                isChecked = false,
                isClickable = false,
                isLongClickable = false,
                isScrollable = false,
                isEditable = false,
                isEnabled = true,
                stateDescription = "expanded",
                hintText = "Search",
            ),
        )
    }

    @Test
    fun `chip order is stable - structural state, behaviour, disability, descriptive`() {
        assertEquals(
            listOf(
                "checked",
                "clickable",
                "long-clickable",
                "scrollable",
                "editable",
                "disabled",
                "selected",
                "hint: Tap",
            ),
            AccessibilityChecker.buildStates(
                isCheckable = true,
                isChecked = true,
                isClickable = true,
                isLongClickable = true,
                isScrollable = true,
                isEditable = true,
                isEnabled = false,
                stateDescription = "selected",
                hintText = "Tap",
            ),
        )
    }
}
