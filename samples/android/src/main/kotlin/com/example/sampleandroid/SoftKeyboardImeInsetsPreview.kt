@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.sampleandroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Confirms the soft-keyboard data extension publishes real `WindowInsetsCompat.Type.ime()` insets
 * — not just a painted-on overlay. A `LazyColumn` with `Modifier.imePadding()` should shrink so its
 * last row sits **above** the band, not behind it. Pair-rendered against [ImeAwareListHiddenPreview]
 * as a same-content/different-IME-state diff: both show a 30-row list under the same heading; only
 * this one raises the IME (and thus shrinks the list to the band-relative viewport).
 *
 * Compose's IME inset story:
 *
 * - `WindowInsetsCompat.Type.ime()` is the canonical Android IME inset type.
 * - Compose's `WindowInsets.ime` (in `androidx.compose.foundation.layout`) surfaces that type to
 *   composable code via `WindowInsetsHolder`, which subscribes to the host view's
 *   `dispatchApplyWindowInsets` callback.
 * - `Modifier.imePadding()` is shorthand for `Modifier.windowInsetsPadding(WindowInsets.ime)`;
 *   `Modifier.consumeWindowInsets(WindowInsets.ime)` lets a parent that already padded for the IME
 *   tell its children to ignore it. `WindowInsets.ime.asPaddingValues()` plugs the inset into a
 *   `LazyColumn`'s `contentPadding` instead of stealing layout space.
 *
 * The connector dispatches synthetic `WindowInsetsCompat.Type.ime()` to the renderer's host view
 * whenever `KeyboardController.softInputVisible` flips, so all three of those code paths "just
 * work" inside the preview — same as on a real Android device with a real IME up.
 */
@Preview(name = "IME-aware list — keyboard up", widthDp = 360, heightDp = 640)
@Composable
fun ImeAwareListShownPreview() {
  val keyboardController = LocalSoftwareKeyboardController.current
  DisposableEffect(Unit) {
    keyboardController?.show()
    onDispose { keyboardController?.hide() }
  }
  ImeAwareList(showLabel = "keyboard up — list capped above the band")
}

/**
 * Companion preview with the IME hidden. Same list, same scroll state, no `keyboardController.show()`
 * — the band stays down and the list runs to the bottom of the canvas. Diffing this against
 * [ImeAwareListShownPreview] makes the inset-driven viewport adaptation visible at a glance.
 */
@Preview(name = "IME-aware list — keyboard hidden", widthDp = 360, heightDp = 640)
@Composable
fun ImeAwareListHiddenPreview() {
  ImeAwareList(showLabel = "keyboard hidden — list fills the canvas")
}

@Composable
private fun ImeAwareList(showLabel: String) {
  Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F3F4)) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        modifier =
          Modifier.fillMaxSize()
            // `WindowInsets.ime` is what the connector publishes on visibility. `imePadding()`
            // applies it as a bottom padding so the LazyColumn's viewport shrinks to fit above
            // the band — the canonical Compose pattern for adjustResize-style behaviour.
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      ) {
        item {
          Text(
            text = showLabel,
            style =
              MaterialTheme.typography.labelMedium.copy(
                color = Color(0xFF5F6368),
                fontWeight = FontWeight.SemiBold,
              ),
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        items(LIST_ROWS) { row ->
          ListRow(row)
          HorizontalDivider(color = Color(0xFFE0E0E0))
        }
      }
      // Footer pinned just above the band via `WindowInsets.ime`, demonstrating
      // `Modifier.windowInsetsPadding(WindowInsets.ime)` working alongside `imePadding()` on the
      // list — same inset, two consumers.
      Text(
        text = "ime-aware footer",
        style =
          MaterialTheme.typography.labelSmall.copy(
            color = Color(0xFF5F6368),
            fontWeight = FontWeight.Medium,
          ),
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.ime)
            .padding(8.dp),
      )
    }
  }
}

@Composable
private fun ListRow(row: ListEntry) {
  Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
    Text(
      text = "${row.index}. ${row.title}",
      style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF1F1F1F)),
    )
  }
}

private data class ListEntry(val index: Int, val title: String)

/**
 * 30 rows. The hidden-keyboard preview runs the canvas full-bleed so the last visible row hits
 * around row 12-14 (depending on font metrics); the keyboard-up preview shrinks the viewport by
 * the band's 240dp, which knocks the visible window down by ~4-5 rows. The diff is the proof the
 * inset is actually flowing through `WindowInsets.ime` and not just a painted-on overlay.
 */
private val LIST_ROWS: List<ListEntry> =
  (1..30).map { ListEntry(index = it, title = "Item $it") }
