package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Red Box", backgroundColor = 0xFFFF0000, showBackground = true)
@Composable
fun RedBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Red),
        contentAlignment = Alignment.Center,
    ) {
        Text("Red", color = Color.White)
    }
}

@Preview(name = "Blue Box", backgroundColor = 0xFF0000FF, showBackground = true)
@Composable
fun BlueBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Blue),
        contentAlignment = Alignment.Center,
    ) {
        Text("Blue", color = Color.White)
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}
