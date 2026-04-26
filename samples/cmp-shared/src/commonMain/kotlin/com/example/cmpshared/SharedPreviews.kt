package com.example.cmpshared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview
@Composable
fun SharedRedBoxPreview() {
  Box(modifier = Modifier.size(120.dp).background(Color.Red)) { Text("shared red") }
}

@Preview(name = "Blue", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun SharedBlueBoxPreview() {
  Box(modifier = Modifier.size(120.dp).background(Color.Blue)) { Text("shared blue") }
}
