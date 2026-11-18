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

/** WFF v4 `<Reference>`: attribute binding and `[REFERENCE.name]`. */
class DwfReferenceTest {
  private fun render(
    xml: String,
    hour: Int = 15,
    minute: Int = 8,
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

  private fun ring(reference: String) =
    """
    <WatchFace width="450" height="450">
      <UserConfigurations>
        <ColorConfiguration id="theme" defaultValue="1" displayName="t" screenReaderText="t">
          <ColorOption id="0" displayName="red" colors="#FFFF0000"/>
          <ColorOption id="1" displayName="green" colors="#FF00FF00"/>
        </ColorConfiguration>
      </UserConfigurations>
      <Scene backgroundColor="#FF000000">
        <PartDraw x="0" y="0" width="450" height="450">
          <Ellipse x="75" y="75" width="300" height="300">
            <Stroke thickness="20" color="#FF888888">$reference</Stroke>
          </Ellipse>
        </PartDraw>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun referenceBindsTheParentAttributeToItsSource() {
    val xml =
      ring(
        """<Reference name="color" source="[CONFIGURATION.theme.0]" defaultValue="#FF888888"/>""",
      )
    // The selected option (green) wins over the literal grey.
    assertEquals(0x00FF00, render(xml).rgb(225, 77))
    // ...and seeding the configuration colour recolours it.
    val red =
      render(xml, overrides = mapOf("dwf.color.theme.0" to RcNamedValue.Color(0xFFFF0000.toInt())))
    assertEquals(0xFF0000, red.rgb(225, 77))
  }

  @Test
  fun referenceToAnUndeclaredConfigurationUsesItsDefaultValue() {
    val xml =
      ring("""<Reference name="color" source="[CONFIGURATION.nope.0]" defaultValue="#FF2244AA"/>""")
    assertEquals(0x2244AA, render(xml).rgb(225, 77))
  }

  @Test
  fun referenceNameIsReadableAsAnExpressionSource() {
    val xml =
      """
      <WatchFace width="450" height="450"><Scene backgroundColor="#FF000000">
        <Reference name="h" source="[HOUR_0_23]" defaultValue="0"/>
        <Condition><Expressions><Expression name="is15">[REFERENCE.h] == 15</Expression></Expressions>
          <Compare expression="is15"><PartDraw x="0" y="0" width="450" height="450"><Rectangle x="150" y="150" width="100" height="100"><Fill color="#FFFFFFFF"/></Rectangle></PartDraw></Compare></Condition>
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
    assertEquals(0xFFFFFF, render(xml, hour = 15).rgb(200, 200))
    assertEquals(0x000000, render(xml, hour = 9).rgb(200, 200))
  }

  @Test
  fun unknownReferenceNameIsADiagnostic() {
    val xml =
      """<WatchFace width="450" height="450"><Scene><PartText x="0" y="0" width="450" height="50"><Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="30" color="#FFFFFFFF"><Template>%d<Parameter expression="[REFERENCE.zz]"/></Template></Font></Text></PartText></Scene></WatchFace>"""
    val diagnostics = DwfDiagnostics()
    runBlocking {
      captureDwfRemoteDocumentBytes(
        xml = xml,
        options = DwfRenderOptions(diagnostics = diagnostics),
      )
    }
    assertEquals(DwfDiagnostics.UNKNOWN_SOURCE, diagnostics.entries.single().first)
  }

  @Test
  fun malformedReferenceIsStillReported() {
    val xml = ring("""<Reference source="[CONFIGURATION.theme.0]"/>""")
    val diagnostics = DwfDiagnostics()
    runBlocking {
      captureDwfRemoteDocumentBytes(
        xml = xml,
        options = DwfRenderOptions(diagnostics = diagnostics),
      )
    }
    assertEquals(DwfDiagnostics.UNSUPPORTED_ELEMENT, diagnostics.entries.single().first)
  }
}
