package com.example.sampleandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Issue #1203 demo — Android counterpart to `samples/cmp/.../KeyboardDemoPreview.kt`. Click the
 * keyboard-icon Controls button on the live card, focus the surface, and type: the daemon
 * dispatches `KEY_DOWN` / `KEY_UP` through the held rule's `performKeyInput { keyDown(Key(...)) }`
 * and the `BasicTextField` updates in real time.
 */
@Preview(name = "Keyboard Demo", showBackground = true)
@Composable
fun KeyboardDemoPreview() {
  var text by remember { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }
  // Claim focus on first composition so the very first keystroke after toggling Controls on
  // lands on the field — without this the user would need to click into the surface first.
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  Surface(color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(text = "Type with Controls on:", style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (text.isEmpty()) "(empty)" else text,
        style = TextStyle(fontSize = 18.sp),
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
      )
      BasicTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
      )
    }
  }
}
