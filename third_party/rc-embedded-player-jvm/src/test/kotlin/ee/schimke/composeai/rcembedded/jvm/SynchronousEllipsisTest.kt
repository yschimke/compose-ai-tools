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

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.remote.player.compose.embedded.synchronousEllipsis
import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.assertEquals
import org.junit.Test

class SynchronousEllipsisTest {
  private val monospaceWidth: (String) -> Int = { value -> value.codePointCount(0, value.length) }

  @Test
  fun `text that fits is unchanged`() {
    assertEquals(
      "Compose",
      synchronousEllipsis("Compose", TextOverflow.StartEllipsis, 7, monospaceWidth),
    )
  }

  @Test
  fun `start ellipsis keeps the largest fitting suffix`() {
    assertEquals(
      "…efghij",
      synchronousEllipsis("abcdefghij", TextOverflow.StartEllipsis, 7, monospaceWidth),
    )
  }

  @Test
  fun `middle ellipsis divides the remaining width between both ends`() {
    assertEquals(
      "abc…hij",
      synchronousEllipsis("abcdefghij", TextOverflow.MiddleEllipsis, 7, monospaceWidth),
    )
  }

  @Test
  fun `ellipsis never splits a surrogate pair`() {
    assertEquals(
      "…cdef",
      synchronousEllipsis("ab😀cdef", TextOverflow.StartEllipsis, 5, monospaceWidth),
    )
  }
}
