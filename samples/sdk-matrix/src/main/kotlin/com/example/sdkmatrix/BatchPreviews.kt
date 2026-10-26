package com.example.sdkmatrix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

/**
 * Three consecutive previews in the same render JVM, each filled with its own colour, so the
 * matrix's pixel check (`scripts/sdk_matrix_pixel_check.py`) can tell that every preview after the
 * first draws its own frame. On SDK 37 a frame clock that went backwards between tests left every
 * preview after the first a uniform `#FAFAFA` frame with no error (#6229); this is the regression
 * cell for it. The colours are pure primaries so the check can match them exactly.
 */
@Composable
private fun BatchCell(color: Color, label: String) {
  Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
    Text(text = label, color = Color.White, fontSize = 32.sp)
  }
}

@Preview(name = "BatchRed", widthDp = 120, heightDp = 120)
@Composable
fun BatchRedPreview() = BatchCell(Color(0xFFFF0000), "A")

@Preview(name = "BatchBlue", widthDp = 120, heightDp = 120)
@Composable
fun BatchBluePreview() = BatchCell(Color(0xFF0000FF), "B")

@Preview(name = "BatchGreen", widthDp = 120, heightDp = 120)
@Composable
fun BatchGreenPreview() = BatchCell(Color(0xFF00FF00), "C")
