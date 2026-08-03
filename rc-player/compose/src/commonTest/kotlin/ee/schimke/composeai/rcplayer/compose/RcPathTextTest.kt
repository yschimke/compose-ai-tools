package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class RcPathTextTest {
  @Test
  fun placesGlyphCenterOnPathAndUsesTangentAngle() {
    val placement =
      computePathTextPlacement(
        position = Offset(40f, 60f),
        tangent = Offset(0f, 1f),
        advance = 12f,
        verticalOffset = -3f,
        firstBaseline = 8f,
      )

    assertEquals(Offset(34f, 49f), placement.topLeft)
    assertEquals(90f, placement.angleDegrees, 0.0001f)
  }
}
