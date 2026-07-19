package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeConsumerCaptureShapeTest {
  private fun elements(modifier: Modifier): List<Any> =
    modifier.foldIn(mutableListOf<Modifier.Element>()) { acc, element ->
      acc.add(element)
      acc
    }

  @Test
  fun `reads the shape from a background(color, shape) modifier`() {
    val shape = RoundedCornerShape(12.dp)
    assertEquals(
      shape.toString(),
      ThemeConsumerCapture.shapeStringOf(elements(Modifier.background(Color.Red, shape))),
    )
  }

  @Test
  fun `reads the shape from a clip(shape) modifier`() {
    val shape = RoundedCornerShape(8.dp)
    assertEquals(
      shape.toString(),
      ThemeConsumerCapture.shapeStringOf(elements(Modifier.clip(shape))),
    )
  }

  @Test
  fun `a plain rectangular background carries no theme shape`() {
    // `background(color)` defaults to RectangleShape — not a theme shape role.
    assertNull(ThemeConsumerCapture.shapeStringOf(elements(Modifier.background(Color.Red))))
  }

  @Test
  fun `no shape-bearing modifier yields null`() {
    assertNull(ThemeConsumerCapture.shapeStringOf(elements(Modifier.padding(4.dp))))
  }

  // A Wear scaling card fills through `Modifier.paint(BackgroundPainter)`, whose shape rides on the
  // painter, not the modifier — matched reflectively by simple-name + a `painter`/`shape` field.
  // These fakes reproduce that field shape without a Wear-compose dependency on the test classpath.
  private class BackgroundPainter(@JvmField val shape: Shape)

  private class PaintModifier(@JvmField val painter: Any)

  @Test
  fun `reads the shape from a BackgroundPainter behind a paint modifier`() {
    val shape = RoundedCornerShape(20.dp)
    assertEquals(
      shape.toString(),
      ThemeConsumerCapture.shapeStringOf(listOf(PaintModifier(BackgroundPainter(shape)))),
    )
  }

  @Test
  fun `a non-BackgroundPainter behind a paint modifier yields null`() {
    class OtherPainter(@JvmField val shape: Shape)
    assertNull(
      ThemeConsumerCapture.shapeStringOf(
        listOf(PaintModifier(OtherPainter(RoundedCornerShape(20.dp))))
      )
    )
  }
}
