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
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

/** `[MOON_PHASE_*]` come from the real date (mean synodic month), or from `dwf.moon.phase`. */
class DwfMoonPhaseTest {
  private fun render(
    xml: String,
    hour: Int = 12,
    minute: Int = 0,
    year: Int = 2025,
    month: Int = 11,
    day: Int = 5,
    overrides: Map<String, RcNamedValue> = emptyMap(),
  ): Bitmap = runBlocking {
    val bytes = captureDwfRemoteDocumentBytes(xml = xml)
    val time =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_000_000_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = year,
            month = month,
            dayOfMonth = day,
            dayOfYear = java.time.LocalDate.of(year, month, day).dayOfYear,
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

  /** Cell i (x = 10 + 50 i) is lit when `[MOON_PHASE_TYPE] == i`. */
  private val typeFace =
    "<WatchFace width=\"450\" height=\"450\"><Scene backgroundColor=\"#FF000000\">" +
      (0..7).joinToString("") { i ->
        """<Condition><Expressions><Expression name="t$i">[MOON_PHASE_TYPE] == $i</Expression></Expressions>
          <Compare expression="t$i"><PartDraw x="0" y="0" width="450" height="450"><Rectangle x="${10 + 50 * i}" y="100" width="40" height="40"><Fill color="#FFFFFFFF"/></Rectangle></PartDraw></Compare></Condition>"""
      } +
      "</Scene></WatchFace>"

  private fun typeOf(frame: Bitmap): Int {
    val lit = (0..7).filter { frame.rgb(30 + 50 * it, 120) == 0xFFFFFF }
    assertEquals(1, lit.size, "exactly one phase type is lit, was $lit")
    return lit.single()
  }

  private fun typeOn(y: Int, m: Int, d: Int, hour: Int = 12) =
    typeOf(render(typeFace, hour = hour, year = y, month = m, day = d))

  @Test
  fun fullMoonDates() {
    assertEquals(4, typeOn(2025, 11, 5)) // full moon 2025-11-05 13:19 UTC
    assertEquals(4, typeOn(2026, 11, 24)) // full moon 2026-11-24
  }

  @Test
  fun newMoonDates() {
    assertEquals(0, typeOn(2025, 11, 20)) // new moon 2025-11-20 06:47 UTC
    assertEquals(0, typeOn(2000, 1, 6, hour = 18)) // the reference new moon
  }

  @Test
  fun quarterDates() {
    assertEquals(6, typeOn(2025, 11, 12)) // last quarter 2025-11-12
    assertEquals(2, typeOn(2025, 11, 28)) // first quarter 2025-11-28
  }

  @Test
  fun waxingAndWaningInBetween() {
    assertEquals(1, typeOn(2025, 11, 23)) // waxing crescent
    assertEquals(3, typeOn(2025, 11, 1)) // waxing gibbous, ~day 11
    assertEquals(7, typeOn(2025, 11, 17)) // waning crescent
  }

  @Test
  fun namedValueDrivesThePhase() {
    for ((phase, expected) in listOf(0f to 0, 0.25f to 2, 0.5f to 4, 0.75f to 6, 0.9f to 7)) {
      val f =
        render(
          typeFace,
          overrides = mapOf(DwfVariables.MOON_PHASE to RcNamedValue.FloatValue(phase)),
        )
      assertEquals(expected, typeOf(f), "dwf.moon.phase=$phase")
    }
  }

  @Test
  fun positionIsInteger0To27() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <Condition><Expressions><Expression name="p">[MOON_PHASE_POSITION] == 14</Expression></Expressions>
          <Compare expression="p"><PartDraw x="0" y="0" width="450" height="450"><Rectangle x="150" y="150" width="100" height="100"><Fill color="#FFFFFFFF"/></Rectangle></PartDraw></Compare></Condition>
      </Scene></WatchFace>
      """
        .trimIndent()
    val f = render(xml, overrides = mapOf(DwfVariables.MOON_PHASE to RcNamedValue.FloatValue(0.5f)))
    assertEquals(0xFFFFFF, f.rgb(200, 200))
  }

  @Test
  fun compilesWithoutDiagnosticsForTheDocumentedSources() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <PartText x="0" y="0" width="450" height="50"><Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="30" color="#FFFFFFFF"><Template>%s %d %d<Parameter expression="[MOON_PHASE_TYPE_STRING]"/><Parameter expression="[MOON_PHASE_TYPE]"/><Parameter expression="[MOON_PHASE_POSITION]"/></Template></Font></Text></PartText>
      </Scene></WatchFace>
      """
        .trimIndent()
    val diagnostics = DwfDiagnostics()
    runBlocking {
      captureDwfRemoteDocumentBytes(
        xml = xml,
        options = DwfRenderOptions(diagnostics = diagnostics),
      )
    }
    assertEquals(emptyList(), diagnostics.entries.toList())
  }
}
