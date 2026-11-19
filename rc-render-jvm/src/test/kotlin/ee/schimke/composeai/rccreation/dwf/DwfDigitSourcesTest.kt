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

/**
 * The WFF v2 digit sources (`HOUR_TENS_DIGIT` … `SECOND_UNITS_DIGIT`) a digital face draws one
 * image a digit with. Each was an unknown source evaluating to 0, so a face's hour stuck at "00".
 */
class DwfDigitSourcesTest {
  private val sources =
    listOf(
      "HOUR_TENS_DIGIT",
      "HOUR_UNITS_DIGIT",
      "MINUTE_TENS_DIGIT",
      "MINUTE_UNITS_DIGIT",
      "SECOND_TENS_DIGIT",
      "SECOND_UNITS_DIGIT",
    )

  /**
   * One 40 px square a source, at x = 20 + 60·i; for each candidate digit d, a condition shows the
   * square in grey level 20·(d+1) when the source equals d. The colour read back is the digit.
   */
  private val face: String = buildString {
    append("""<WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">""")
    sources.forEachIndexed { i, source ->
      for (d in 0..9) {
        val grey = "%02X".format(20 * (d + 1))
        append(
          """<Condition><Expressions><Expression name="e$i$d">[$source] == $d</Expression>""" +
            """</Expressions><Compare expression="e$i$d"><PartDraw x="0" y="0" width="450" """ +
            """height="450"><Rectangle x="${20 + 60 * i}" y="200" width="40" height="40">""" +
            """<Fill color="#FF$grey$grey$grey"/></Rectangle></PartDraw></Compare></Condition>""",
        )
      }
    }
    append("</Scene></WatchFace>")
  }

  private fun digits(hour: Int, minute: Int, second: Int, is24Hour: Boolean): List<Int> {
    val bitmap = render(hour, minute, second, is24Hour)
    return sources.indices.map { i ->
      val grey = bitmap.getColor(40 + 60 * i, 220) and 0xFF
      grey / 20 - 1
    }
  }

  @Test
  fun everyDigitSourceIsKnown() {
    val diagnostics = DwfDiagnostics()
    runBlocking {
      captureDwfRemoteDocumentBytes(
        xml = face,
        options = DwfRenderOptions(diagnostics = diagnostics),
      )
    }
    assertEquals(emptyList(), diagnostics.entries.toList())
  }

  @Test
  fun digitsFollowTheClock() {
    assertEquals(listOf(1, 2, 0, 3, 4, 7), digits(12, 3, 47, is24Hour = true))
    assertEquals(listOf(1, 2, 0, 3, 4, 7), digits(12, 3, 47, is24Hour = false))
    assertEquals(listOf(0, 0, 5, 9, 0, 5), digits(0, 59, 5, is24Hour = true))
  }

  @Test
  fun hourDigitsFollowThe24HourSetting() {
    // 21:08 is "21" on a 24-hour clock and "09" on a 12-hour one; midnight is "00" or "12".
    assertEquals(listOf(2, 1), digits(21, 8, 0, is24Hour = true).take(2))
    assertEquals(listOf(0, 9), digits(21, 8, 0, is24Hour = false).take(2))
    assertEquals(listOf(1, 2), digits(0, 8, 0, is24Hour = false).take(2))
  }

  private fun render(hour: Int, minute: Int, second: Int, is24Hour: Boolean): Bitmap = runBlocking {
    val bytes = captureDwfRemoteDocumentBytes(xml = face)
    val time =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_000_000_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = 2025,
            month = 11,
            dayOfMonth = 5,
            dayOfYear = 309,
            hour = hour,
            minute = minute,
            second = second,
            isoDayOfWeek = 3,
            offsetSeconds = 0,
          )
      }
    val scene = ImageComposeScene(width = 450, height = 450, density = Density(1f))
    try {
      val state =
        mutableStateMapOf<String, RcNamedValue>(
          DwfVariables.IS_24_HOUR to RcNamedValue.BooleanValue(is24Hour),
        )
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
}
