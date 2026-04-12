package com.example.sampleandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(name = "Red Box", showBackground = true, backgroundColor = 0xFFFF0000)
@Composable
fun RedBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Red),
        contentAlignment = Alignment.Center,
    ) {
        Text("Red", color = Color.White)
    }
}

@Preview(name = "Blue Box", showBackground = true, backgroundColor = 0xFF0000FF)
@Composable
fun BlueBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Blue),
        contentAlignment = Alignment.Center,
    ) {
        Text("Blue", color = Color.White)
    }
}

@Preview(name = "Green Box", showBackground = true, backgroundColor = 0xFF00FF00)
@Composable
fun GreenBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Green),
        contentAlignment = Alignment.Center,
    ) {
        Text("Green", color = Color.Black)
    }
}

@Preview(name = "Default", showBackground = true)
@Composable
fun GreetingPreview() {
    MaterialTheme {
        Greeting("Preview")
    }
}

@Preview(name = "Loading Spinner", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun LoadingPreview() {
    MaterialTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Loading...")
        }
    }
}
