/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.remote.player.compose.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextMeasureBehaviorTest {
    private val measured =
        TextMeasureBounds(
            left = 3f,
            top = -37f,
            right = 537f,
            bottom = 11f,
            fontTop = -45f,
            fontBottom = 12f,
            advance = 539f,
        )

    @Test
    fun selectorsReadTheAndroidXInkRectangle() {
        assertEquals(
            listOf(534f, 48f, 3f, 537f, -37f, 11f),
            (0..5).map { selectTextMeasureResult(it, measured) },
        )
    }

    @Test
    fun flagsApplyInAndroidPaintContextOrder() {
        val monospace = 0x100
        val fontHeight = 0x200
        val advance = 0x400

        assertEquals(3f, selectTextMeasureResult(monospace or 2, measured))
        assertEquals(536f, selectTextMeasureResult(monospace or 3, measured))
        assertEquals(-45f, selectTextMeasureResult(fontHeight or 4, measured))
        assertEquals(12f, selectTextMeasureResult(fontHeight or 5, measured))
        assertEquals(539f, selectTextMeasureResult(advance or 0, measured))
        assertEquals(57f, selectTextMeasureResult(advance or fontHeight or 1, measured))
    }

    @Test
    fun unknownSelectorLeavesTheDestinationUntouched() {
        assertNull(selectTextMeasureResult(6, measured))
        assertNull(selectTextMeasureResult(255, measured))
    }
}
