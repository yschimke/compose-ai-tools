package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Real application-side text editor used by both previews below.
 *
 * The sample owns only ordinary Compose state, focus, and the public software-keyboard controller.
 * A scripted capture sends `input.keyDown` / `input.keyUp` events through the daemon's public input
 * path; those events update this field and independently drive the keyboard connector's pressed-key
 * overlay. Keeping those two effects on the same input path means a capture cannot appear to type
 * when focus or text dispatch is broken.
 */
@Composable
private fun SoftKeyboardMessageEditor() {
  var value by remember { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
    keyboardController?.show()
  }
  DisposableEffect(keyboardController) { onDispose { keyboardController?.hide() } }

  Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F3F4)) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
      Text(text = "Compose", style = MaterialTheme.typography.titleMedium)
      Box(
        modifier =
          Modifier.padding(top = 16.dp)
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        BasicTextField(
          value = value,
          onValueChange = { value = it },
          modifier =
            Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("message-editor"),
          textStyle =
            TextStyle(color = Color(0xFF1F1F1F), fontSize = 20.sp, fontWeight = FontWeight.Medium),
          cursorBrush = SolidColor(Color(0xFF1F1F1F)),
          singleLine = true,
        )
      }
    }
  }
}

/**
 * Recording target for `compose-previews/recordings/soft-keyboard-typing.json`. The plain preview
 * is the deterministic empty-field frame; replaying the committed script produces the animated
 * capture through real text input.
 */
@Preview(name = "Soft Keyboard — typing", widthDp = 360, heightDp = 640)
@Composable
fun SoftKeyboardAnimatedPreview() = SoftKeyboardMessageEditor()

/** Static baseline for the same public focus and IME-visibility path. */
@Preview(name = "Soft Keyboard — idle", widthDp = 360, heightDp = 640)
@Composable
fun SoftKeyboardIdlePreview() = SoftKeyboardMessageEditor()
