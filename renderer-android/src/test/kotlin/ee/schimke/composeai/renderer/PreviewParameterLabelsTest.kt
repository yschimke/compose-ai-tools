package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewParameterLabelsTest {

    @Test
    fun `pair uses first as label`() {
        val suffixes = PreviewParameterLabels.suffixesFor(
            listOf("on" to Any(), "off" to Any(), "unavailable" to Any()),
        )
        assertEquals(listOf("_on", "_off", "_unavailable"), suffixes)
    }

    data class Named(val name: String, val value: Int)

    @Test
    fun `data class with name property derives label`() {
        val suffixes = PreviewParameterLabels.suffixesFor(
            listOf(Named("alpha", 1), Named("beta", 2)),
        )
        assertEquals(listOf("_alpha", "_beta"), suffixes)
    }

    data class Labeled(val label: String)

    @Test
    fun `label property is recognised`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf(Labeled("red"), Labeled("blue")))
        assertEquals(listOf("_red", "_blue"), suffixes)
    }

    data class WithId(val id: String)

    @Test
    fun `id property is recognised when name and label absent`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf(WithId("a1"), WithId("b2")))
        assertEquals(listOf("_a1", "_b2"), suffixes)
    }

    @Test
    fun `toString fallback is used when no property matches`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf("hello", "world"))
        assertEquals(listOf("_hello", "_world"), suffixes)
    }

    @Test
    fun `default object toString falls back to PARAM_idx`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf(Any(), Any()))
        assertEquals(listOf("_PARAM_0", "_PARAM_1"), suffixes)
    }

    @Test
    fun `null values fall back to PARAM_idx`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf<Any?>(null, "ok"))
        assertEquals(listOf("_PARAM_0", "_ok"), suffixes)
    }

    @Test
    fun `spaces and parens are sanitized`() {
        val suffixes = PreviewParameterLabels.suffixesFor(
            listOf("tile light (light)" to Any(), "tile dark (dark)" to Any()),
        )
        assertEquals(listOf("_tile_light_light", "_tile_dark_dark"), suffixes)
    }

    @Test
    fun `shell-hostile characters are replaced with underscore`() {
        val suffixes = PreviewParameterLabels.suffixesFor(listOf("a&b/c", "x|y*z"))
        assertEquals(listOf("_a_b_c", "_x_y_z"), suffixes)
    }

    @Test
    fun `colliding labels fall back to PARAM_idx across the whole fan-out`() {
        val suffixes = PreviewParameterLabels.suffixesFor(
            listOf("dup" to Any(), "dup" to Any(), "unique" to Any()),
        )
        assertEquals(listOf("_PARAM_0", "_PARAM_1", "_PARAM_2"), suffixes)
    }

    @Test
    fun `long labels are truncated`() {
        val long = "x".repeat(64)
        val suffixes = PreviewParameterLabels.suffixesFor(listOf(long to Any()))
        val expected = "_" + "x".repeat(32)
        assertEquals(listOf(expected), suffixes)
    }
}
