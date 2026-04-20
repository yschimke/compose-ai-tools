package com.example.samplelibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun LibraryGreeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Library: $name", modifier = modifier)
}

@Preview(name = "Library Greeting", showBackground = true)
@Composable
fun LibraryGreetingPreview() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                LibraryGreeting("Hello")
            }
        }
    }
}

@Preview(name = "Library Box", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun LibraryBoxPreview() {
    Box(
        modifier = Modifier.size(120.dp).background(Color(0xFF336699)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Library", color = Color.White)
    }
}
