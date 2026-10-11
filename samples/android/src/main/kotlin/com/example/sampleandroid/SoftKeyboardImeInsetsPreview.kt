@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.sampleandroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
 * Confirms the soft-keyboard extension publishes real `WindowInsetsCompat.Type.ime()` insets, not
 * just a painted band: a `LazyColumn` with `Modifier.imePadding()` shrinks so its last row sits
 * above the keyboard. Diff against [ImeAwareListHiddenPreview].
 *
 * The connector dispatches synthetic IME insets to the host view when
 * `KeyboardController.softInputVisible` flips, so `WindowInsets.ime`, `imePadding()`,
 * `consumeWindowInsets` and `asPaddingValues()` all behave as on a device.
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
 * Companion with the IME hidden: the list runs to the bottom of the canvas.
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
 * 30 rows: with the keyboard up the 240dp band removes ~4-5 visible rows, which proves the inset
 * flows through `WindowInsets.ime`.
 */
private val LIST_ROWS: List<ListEntry> = (1..30).map { ListEntry(index = it, title = "Item $it") }
