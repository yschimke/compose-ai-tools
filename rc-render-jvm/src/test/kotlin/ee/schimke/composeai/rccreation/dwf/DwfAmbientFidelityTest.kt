package ee.schimke.composeai.rccreation.dwf

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcjvm.defaultDwfImageLoader
import ee.schimke.composeai.rcplayer.compose.LocalRcImageLoader
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/**
 * Pixel regressions for ambient `Variant`s and the expression sources a face reads (dogfood triage
 * items 4, 7 and 8).
 */
class DwfAmbientFidelityTest {
  private fun render(
    xml: String,
    hour: Int = 15,
    minute: Int = 8,
    overrides: Map<String, RcNamedValue> = emptyMap(),
  ): Bitmap = runBlocking {
    val bytes = captureDwfRemoteDocumentBytes(xml = xml)
    val time =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_000_000_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = 2026,
            month = 11,
            dayOfMonth = 18,
            dayOfYear = 322,
            hour = hour,
            minute = minute,
            second = 0,
            isoDayOfWeek = 3,
            offsetSeconds = 0,
          )
      }
    val scene = ImageComposeScene(width = 450, height = 450, density = Density(1f))
    try {
      val state = mutableStateMapOf<String, RcNamedValue>().apply { putAll(overrides) }
      scene.setContent {
        CompositionLocalProvider(
          LocalRcTimeSource provides time,
          LocalRcImageLoader provides defaultDwfImageLoader(),
        ) {
          RcComposePlayer(
            bytes = bytes,
            modifier = Modifier.size(450.dp, 450.dp),
            namedValues = state,
          )
        }
      }
      Bitmap.makeFromImage(scene.render(0L))
    } finally {
      scene.close()
    }
  }

  private fun Bitmap.rgb(x: Int, y: Int): Int = getColor(x, y) and 0xFFFFFF

  private val weatherRepro =
    """
    <WatchFace width="450" height="450">
      <Scene backgroundColor="#FF000000">
        <Group x="0" y="0" width="450" height="450">
          <Variant mode="AMBIENT" target="alpha" value="120"/>
          <Condition>
            <Expressions>
              <Expression name="is_one">[WEATHER.CONDITION] == 1</Expression>
              <Expression name="is_two">[WEATHER.CONDITION] == 2</Expression>
            </Expressions>
            <Compare expression="is_one">
              <PartDraw x="75" y="75" width="300" height="300"><RoundRectangle x="0" y="0" width="300" height="200" cornerRadiusX="40" cornerRadiusY="40"><Fill color="#FFB8C4D0"/></RoundRectangle></PartDraw>
            </Compare>
            <Compare expression="is_two">
              <PartDraw x="75" y="75" width="300" height="300"><Ellipse x="0" y="0" width="300" height="200"><Fill color="#FFB8C4D0"/></Ellipse><Line startX="50" startY="220" endX="40" endY="290"><Stroke color="#FF4FA8FF" thickness="12" cap="ROUND"/></Line></PartDraw>
            </Compare>
          </Condition>
        </Group>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun fillInTakenCompareBranchDrawsWhenAnAncestorGroupHasAnAmbientVariant() {
    val frame =
      render(weatherRepro, overrides = mapOf("dwf.weather.condition" to RcNamedValue.Integer(2)))
    // Ellipse centre (75+150, 75+100). Fill must be the full B8C4D0.
    assertEquals(0xB8C4D0, frame.rgb(225, 175), "the taken branch's Fill must draw")
    // The stroke still draws too. Sampled at the stroke's centre (x 114..124 is solid at y 330):
    // x=125 is its antialiased edge pixel, whose coverage shifts by a few percent through the
    // group's alpha layer.
    assertEquals(0x4FA8FF, frame.rgb(120, 75 + 255), "the Line stroke must draw")
    val ambient =
      render(
        weatherRepro,
        overrides =
          mapOf(
            "dwf.weather.condition" to RcNamedValue.Integer(2),
            DwfVariables.IS_AMBIENT to RcNamedValue.BooleanValue(true),
          ),
      )
    // 120/255 of B8C4D0 (~0x57) then the 15% ambient scrim (#6586) gives ~0x4A.
    val r = (ambient.rgb(225, 175) shr 16) and 0xFF
    assertTrue(
      r in 0x46..0x4E,
      "ambient fill should be ~0x4A (alpha 120 plus scrim), was 0x${r.toString(16)}",
    )
  }

  private val arcRepro =
    """
    <WatchFace width="450" height="450">
      <UserConfigurations>
        <ColorConfiguration id="accent" displayName="a" defaultValue="0">
          <ColorOption id="0" displayName="l" colors="#ff9be15d"/>
          <ColorOption id="1" displayName="c" colors="#ff4fd1ff"/>
        </ColorConfiguration>
      </UserConfigurations>
      <Scene backgroundColor="#ff000000">
        <PartDraw x="0" y="0" width="450" height="450">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Arc centerX="225" centerY="225" width="420" height="420" startAngle="120" endAngle="240" direction="CLOCKWISE"><Stroke thickness="14" color="[CONFIGURATION.accent.0]" cap="ROUND"/></Arc>
        </PartDraw>
        <PartDraw x="0" y="0" width="450" height="450">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Arc centerX="225" centerY="225" width="300" height="300" startAngle="120" endAngle="240" direction="CLOCKWISE"><Stroke thickness="14" color="#ff4fd1ff" cap="ROUND"/></Arc>
        </PartDraw>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun literalStrokeColourEqualToAColorOptionStillDrawsWithAmbientVariants() {
    val frame = render(arcRepro)
    // Arcs run through the left side (WFF angles start at 12 o clock: 120..240 is the bottom, 180 =
    // 6 o clock).
    assertEquals(0x9BE15D, frame.rgb(225, 435), "outer config-coloured arc")
    assertEquals(0x4FD1FF, frame.rgb(225, 375), "inner literal-coloured arc")
  }

  @Test
  fun ambientDimsDrawsWithNoAmbientVariantLikeTheRealRuntime() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <PartDraw x="0" y="0" width="450" height="450"><Rectangle x="100" y="100" width="100" height="100"><Fill color="#FFFFFFFF"/></Rectangle></PartDraw>
      </Scene></WatchFace>
      """
        .trimIndent()
    val ambient =
      render(xml, overrides = mapOf(DwfVariables.IS_AMBIENT to RcNamedValue.BooleanValue(true)))
    // #6586 measured the 0x26000000 scrim against the real DeclarativeWatchFaceRuntime with
    // dwf-diff: ambient similarity rose on all six faces (e.g. wff-complications 0.924 to 0.977),
    // so a draw with no ambient variant reads ~217/255 in ambient. Do not remove it.
    val v = ambient.rgb(150, 150) and 0xFF
    assertTrue(v in 214..220, "ambient scrim should dim white to ~217, was $v")
    assertEquals(0xFFFFFF, render(xml).rgb(150, 150), "interactive stays full white")
  }

  @Test
  fun dayOfMonthAndAmPmStateReadTheClock() {
    // Fill colours are not expressions in WFF, so drive the colour through Condition instead.
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <Condition><Expressions>
          <Expression name="dom">[DAY_OF_MONTH] == 18</Expression>
          <Expression name="pm">[AMPM_STATE] == 1</Expression>
        </Expressions>
          <Compare expression="dom"><PartDraw x="0" y="0" width="450" height="450"><Rectangle x="150" y="100" width="50" height="50"><Fill color="#FF00FF00"/></Rectangle></PartDraw></Compare>
        </Condition>
        <Condition><Expressions><Expression name="pm">[AMPM_STATE] == 1</Expression></Expressions>
          <Compare expression="pm"><PartDraw x="0" y="0" width="450" height="450"><Rectangle x="250" y="100" width="50" height="50"><Fill color="#FF0000FF"/></Rectangle></PartDraw></Compare>
        </Condition>
      </Scene></WatchFace>
      """
        .trimIndent()
    val pm = render(xml, hour = 15)
    assertEquals(0x00FF00, pm.rgb(170, 120), "[DAY_OF_MONTH] is 18")
    assertEquals(0x0000FF, pm.rgb(270, 120), "[AMPM_STATE] is 1 at 15:08")
    val am = render(xml, hour = 9)
    assertEquals(0x000000, am.rgb(270, 120), "[AMPM_STATE] is 0 at 09:08")
  }

  private fun clockFace(hourFormat: String) =
    """
    <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
      <DigitalClock x="0" y="0" width="450" height="450"><TimeText format="hh:mm" hourFormat="$hourFormat" align="CENTER" x="0" y="150" width="450" height="60"><Font family="SYNC_TO_DEVICE" size="40" color="#FFFFFFFF"/></TimeText></DigitalClock>
    </Scene></WatchFace>
    """
      .trimIndent()

  private fun Bitmap.digest(): Long {
    var h = 1125899906842597L
    for (y in 0 until height step 2) for (x in 0 until width step 2) h = 31L * h + getColor(x, y)
    return h
  }

  @Test
  fun syncToDeviceHourFormatFollowsTheIs24HourNamedValue() {
    fun frame(format: String, is24: Boolean) =
      render(
        clockFace(format),
        hour = 15,
        overrides = mapOf(DwfVariables.IS_24_HOUR to RcNamedValue.BooleanValue(is24)),
      )
    assertEquals(frame("TWENTY_FOUR", true).digest(), frame("SYNC_TO_DEVICE", true).digest())
    assertEquals(frame("TWELVE", false).digest(), frame("SYNC_TO_DEVICE", false).digest())
    assertNotEquals(frame("SYNC_TO_DEVICE", true).digest(), frame("SYNC_TO_DEVICE", false).digest())
  }
}
