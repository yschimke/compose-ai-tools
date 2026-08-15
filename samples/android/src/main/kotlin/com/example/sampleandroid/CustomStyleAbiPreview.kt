package com.example.sampleandroid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CustomStyle
import androidx.compose.foundation.style.ExperimentalFoundationStyleApi
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.apply
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationStyleApi::class)
private val abiCustomStyle = CustomStyle<StyleScope> { background(Color.Magenta) }

@OptIn(ExperimentalFoundationStyleApi::class)
@Preview(name = "CustomStyle ABI", widthDp = 80, heightDp = 80)
@Composable
fun CustomStyleAbiPreview() {
  val style = Style { apply(abiCustomStyle) }
  Box(Modifier.size(80.dp).styleable(style = style))
}
