package com.example.wearwidget

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * A superellipse ("squircle") — the ideal outline Wear OS frames a squircle widget in: fuller in the
 * corners than a rounded rectangle, flatter on the sides than an ellipse. Built by sampling the
 * superellipse curve with exponent [n] (n = 4 is the canonical squircle) into a closed [Path].
 * Non-square bounds stretch it to the widget's aspect ratio, matching how the platform squircle
 * scales. Used by [SquircleWidgetWrapper] to clip a widget preview to its ideal shape.
 */
class SquircleShape(private val n: Double = 4.0, private val samples: Int = 96) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val a = size.width / 2f
    val b = size.height / 2f
    val exp = 2.0 / n
    val path = Path()
    for (i in 0 until samples) {
      val t = (i.toDouble() / samples) * 2.0 * Math.PI
      val ct = cos(t)
      val st = sin(t)
      val x = a + a * (ct.sign * ct.absoluteValue.pow(exp)).toFloat()
      val y = b + b * (st.sign * st.absoluteValue.pow(exp)).toFloat()
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return Outline.Generic(path)
  }
}
