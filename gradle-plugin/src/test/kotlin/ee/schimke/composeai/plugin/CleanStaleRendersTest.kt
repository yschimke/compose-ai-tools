package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CleanStaleRendersTest {

    private val expected = setOf(
        "Foo.png",
        "sub/Bar.png",
        "Baz_TIME_500ms.png",
    )

    @Test
    fun `keeps a11y sibling of registered render`() {
        assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.a11y.png", expected)).isTrue()
        assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("sub/Bar.a11y.png", expected)).isTrue()
    }

    @Test
    fun `keeps a11y sibling of fan-out capture`() {
        // Multi-capture previews encode dimensions in the basename
        // (`_TIME_500ms`); the a11y overlay sits next to the same basename.
        assertThat(
            ComposePreviewTasks.isA11ySiblingOfExpected("Baz_TIME_500ms.a11y.png", expected),
        ).isTrue()
    }

    @Test
    fun `drops a11y png with no clean sibling`() {
        assertThat(
            ComposePreviewTasks.isA11ySiblingOfExpected("Removed.a11y.png", expected),
        ).isFalse()
    }

    @Test
    fun `ignores non-a11y png entirely`() {
        // Plain renders are handled by the exact-match branch in
        // cleanStaleRenders; this helper should pass them through (false)
        // so the caller decides.
        assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.png", expected)).isFalse()
        assertThat(ComposePreviewTasks.isA11ySiblingOfExpected("Foo.gif", expected)).isFalse()
    }
}
