package com.example.sampleandroidscreenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Regular top-level @Preview — same shape as every preview in `:sample-android`.
 * Confirms that applying the Google screenshot plugin hasn't broken our
 * ordinary discovery path (shared `sourceClassDirs` still picks up main).
 */
@Preview(name = "main-square", showBackground = true)
@Composable
fun MainSquarePreview() {
    MaterialTheme {
        Surface(color = Color(0xFF4285F4)) {
            Box(Modifier.size(96.dp).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                Text("main")
            }
        }
    }
}
