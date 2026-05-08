package com.example.sampleandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun PseudoSampleBody() {
  Surface(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = stringResource(R.string.pseudo_greeting),
        style = MaterialTheme.typography.headlineSmall,
      )
      Text(
        text = stringResource(R.string.pseudo_settings_title),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(R.string.pseudo_settings_subtitle),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Preview(name = "default", showBackground = true, widthDp = 320, heightDp = 180)
@Composable
fun PseudoSampleDefault() {
  PseudoSampleBody()
}

@Preview(name = "accent", locale = "en-XA", showBackground = true, widthDp = 320, heightDp = 180)
@Composable
fun PseudoSampleAccent() {
  PseudoSampleBody()
}

@Preview(name = "bidi", locale = "ar-XB", showBackground = true, widthDp = 320, heightDp = 180)
@Composable
fun PseudoSampleBidi() {
  PseudoSampleBody()
}
