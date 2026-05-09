package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRenderStrategyPathologicalTest {

    private class DefaultArgPreviewHost {
        @Suppress("unused")
        @Composable
        fun PreviewWithDefaultSuffix(prefix: String, suffix: String = "default") {
            // no-op: test only verifies reflective method resolution shape
            val ignored = prefix + suffix
            check(ignored.isNotEmpty())
        }

        @Suppress("unused")
        @Composable
        fun PreviewWithDefaultSuffix(prefix: Int) {
            check(prefix >= 0)
        }
    }

    @Test
    fun `findComposableMethodWithArgs resolves overload that leaves trailing default param unsupplied`() {
        val method = findComposableMethodWithArgs(
            clazz = DefaultArgPreviewHost::class.java,
            name = "PreviewWithDefaultSuffix",
            previewArgs = listOf("prefix"),
        )

        assertEquals("PreviewWithDefaultSuffix", method.asMethod().name)
        assertArrayEquals(arrayOf(String::class.java), method.parameterTypes)
    }
}
