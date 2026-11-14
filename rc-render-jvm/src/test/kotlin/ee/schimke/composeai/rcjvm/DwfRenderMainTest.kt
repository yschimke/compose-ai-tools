package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import java.io.File
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DwfRenderMainTest {
  @get:Rule val temp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun rendersDwfPreviewWithResourcesAndEmitsRcAndKnobSidecars() {
    val root = temp.newFolder("dwf-preview")
    val resDir = File(root, "res").apply { mkdirs() }
    File(resDir, "values").mkdirs()
    File(resDir, "values/strings.xml")
      .writeText(
        """
        <resources>
          <string name="wf_subtitle">CHRONO</string>
        </resources>
        """
          .trimIndent(),
      )
    File(resDir, "values/colors.xml")
      .writeText(
        """
        <resources>
          <color name="dial_bg">#FF0D1420</color>
        </resources>
        """
          .trimIndent(),
      )
    File(resDir, "drawable").mkdirs()
    File(resDir, "drawable/ic_bolt.xml")
      .writeText(
        """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp"
            android:height="24dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
          <path android:fillColor="#FFFFC107" android:pathData="M7,2 L17,2 L13,10 L18,10 L9,22 L11,13 L6,13 Z" />
        </vector>
        """
          .trimIndent(),
      )
    File(resDir, "raw").mkdirs()
    File(resDir, "raw/watchface.xml")
      .writeText(
        """
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Metadata key="PREVIEW_TIME" value="10:10:30" />
          <UserConfigurations>
            <ColorConfiguration id="accent" displayName="Accent" defaultValue="mint">
              <ColorOption id="mint" displayName="Mint" colors="#FF00E676" />
            </ColorConfiguration>
            <BooleanConfiguration id="show_seconds" displayName="Show Seconds" defaultValue="true" />
          </UserConfigurations>
          <Scene backgroundColor="@color/dial_bg">
            <Variant mode="AMBIENT" target="backgroundColor" value="#FF000000" />
            <PartDraw x="16" y="16" width="418" height="418">
              <Arc centerX="209" centerY="209" width="390" height="390" startAngle="0" endAngle="220">
                <Transform target="endAngle" value="clamp([STEP_PERCENT] * 3.6, 0, 360)" />
                <Stroke color="[CONFIGURATION.accent.0]" thickness="14" cap="ROUND" />
              </Arc>
            </PartDraw>
            <PartImage x="209" y="56" width="32" height="32" resource="@drawable/ic_bolt" />
            <PartText x="125" y="96" width="200" height="36">
              <Text align="CENTER">
                <Font size="20" weight="BOLD" color="#FFB0BEC5">@string/wf_subtitle</Font>
              </Text>
            </PartText>
            <DigitalClock x="105" y="140" width="240" height="60">
              <TimeText format="HH:mm" hourFormat="TWENTY_FOUR" align="CENTER">
                <Font size="44" weight="BOLD" color="#FFFFFFFF" />
              </TimeText>
            </DigitalClock>
            <ComplicationSlot x="145" y="295" width="160" height="64" slotId="1"
                supportedTypes="SHORT_TEXT RANGED_VALUE" defaultSystemProvider="STEP_COUNT"
                defaultSystemProviderType="SHORT_TEXT">
              <Complication type="SHORT_TEXT">
                <PartText x="0" y="0" width="160" height="64">
                  <Text align="CENTER">
                    <Font size="24" color="#FF80CBC4">
                      <Parameter expression="[COMPLICATION.TEXT]" />
                    </Font>
                  </Text>
                </PartText>
              </Complication>
            </ComplicationSlot>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val previewsRoot = File(root, "compose-previews").apply { mkdirs() }
    val previewsJson = File(previewsRoot, "previews.json")
    previewsJson.writeText(
      """
      {
        "previews": [
          {
            "id": "dwf__raw_watchface",
            "functionName": "watchface",
            "params": {
              "name": "watchface",
              "widthDp": 450,
              "heightDp": 450,
              "density": 1.0,
              "dwfAssetPath": "raw/watchface.xml"
            },
            "captures": [
              { "renderOutput": "dwf-renders/dwf__raw_watchface.png" }
            ]
          }
        ]
      }
      """
        .trimIndent(),
    )

    val outDir = File(previewsRoot, "dwf-renders")
    val count = renderDwfPreviews(previewsJson, outDir, listOf(resDir))
    assertThat(count).isEqualTo(1)

    val pngFile = File(outDir, "dwf__raw_watchface.png")
    val rcFile = File(outDir, "dwf__raw_watchface.rc")
    val knobsFile = File(outDir, "dwf__raw_watchface.remotecompose.json")
    assertThat(pngFile.isFile).isTrue()
    assertThat(rcFile.isFile).isTrue()
    assertThat(knobsFile.isFile).isTrue()

    val decodedImg = Image.makeFromEncoded(pngFile.readBytes())
    assertThat(decodedImg.width).isEqualTo(450)
    assertThat(decodedImg.height).isEqualTo(450)

    val payload = json.decodeFromString<RemoteComposeDeclarationsPayload>(knobsFile.readText())
    assertThat(payload.capturePlayer).isEqualTo("cmp-jvm")
    val byName = payload.declarations.associateBy { it.name }
    assertThat(byName[DwfVariables.IS_AMBIENT]?.default)
      .isEqualTo(RemoteNamedValue.BooleanValue(false))
    assertThat(byName[DwfVariables.colorThemeKey("accent", 0)]?.default)
      .isEqualTo(RemoteNamedValue.ColorValue("#FF00E676"))
    assertThat(byName[DwfVariables.STEP_COUNT]?.default)
      .isInstanceOf(RemoteNamedValue.IntValue::class.java)
    assertThat(byName[DwfVariables.complicationTextKey(1)]?.default)
      .isInstanceOf(RemoteNamedValue.StringValue::class.java)

    // Re-rendering the emitted .rc with a different theme color changes the pixels.
    val recoloredPng =
      renderDwfDocumentToPng(
        bytes = rcFile.readBytes(),
        widthPx = 450,
        heightPx = 450,
        seeds =
          mapOf(
            DwfVariables.colorThemeKey("accent", 0) to RcNamedValue.Color(0xFFFF1744.toInt()),
          ),
      )
    val bmpDefault = Bitmap.makeFromImage(decodedImg)
    val bmpRecolored = Bitmap.makeFromImage(Image.makeFromEncoded(recoloredPng))
    assertThat(pixelHash(bmpRecolored)).isNotEqualTo(pixelHash(bmpDefault))
  }

  @Test
  fun compilesApkDwfDirectoryToRcAndManifest() {
    val workDir = temp.newFolder("apk-dwf")
    val wfDir = File(workDir, "watchfaces").apply { mkdirs() }
    File(wfDir, "chrono.xml")
      .writeText(
        """
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Scene backgroundColor="#FF000000">
            <ComplicationSlot x="140" y="280" width="170" height="60" slotId="10"
                supportedTypes="SHORT_TEXT RANGED_VALUE" defaultSystemProvider="WATCH_BATTERY"
                defaultSystemProviderType="RANGED_VALUE">
              <Complication type="RANGED_VALUE">
                <PartText x="0" y="0" width="170" height="60">
                  <Text><Font size="22" color="#FFFFFFFF"><Parameter expression="[COMPLICATION.TEXT]" /></Font></Text>
                </PartText>
              </Complication>
            </ComplicationSlot>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )
    File(workDir, "watchfaces-input.json")
      .writeText(
        """[{"id":"com.example.wf/raw/chrono","label":"Chrono DWF","xmlFile":"watchfaces/chrono.xml"}]""",
      )

    val manifest = compileApkWatchFaces(workDir)
    assertThat(manifest.watchFaces).hasSize(1)
    val entry = manifest.watchFaces.single()
    assertThat(entry.id).isEqualTo("com.example.wf/raw/chrono")
    assertThat(entry.label).isEqualTo("Chrono DWF")
    assertThat(File(workDir, entry.rcFile).isFile).isTrue()
    assertThat(entry.slots).hasSize(1)
    assertThat(entry.slots.single().id).isEqualTo(10)
    assertThat(entry.slots.single().supportedTypes).containsExactly(3, 5).inOrder()
  }

  @Test
  fun compilesApkDwfWithOverridableForecastWeatherVariables() {
    val workDir = temp.newFolder("apk-dwf-weather")
    val wfDir = File(workDir, "watchfaces").apply { mkdirs() }
    File(wfDir, "weather_face.xml")
      .writeText(
        """
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Scene backgroundColor="#FF000000">
            <PartText x="40" y="140" width="370" height="70">
              <Text>
                <Font size="44" color="#FFFFFFFF">
                  <Template>%d / %d<Parameter expression="[WEATHER.HOURS.1.TEMPERATURE]" /><Parameter expression="[WEATHER.DAYS.1.TEMPERATURE_HIGH]" /></Template>
                </Font>
              </Text>
            </PartText>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      )

    val manifest = compileApkWatchFaces(workDir)
    val rcBytes = File(workDir, manifest.watchFaces.single().rcFile).readBytes()
    val defaultPng = renderDwfDocumentToPng(bytes = rcBytes, widthPx = 450, heightPx = 450)
    val overriddenPng =
      renderDwfDocumentToPng(
        bytes = rcBytes,
        widthPx = 450,
        heightPx = 450,
        seeds =
          mapOf(
            DwfVariables.weatherHourTemperatureKey(1) to RcNamedValue.FloatValue(5f),
            DwfVariables.weatherDayTempHighKey(1) to RcNamedValue.FloatValue(34f),
          ),
      )
    val bmpDefault = Bitmap.makeFromImage(Image.makeFromEncoded(defaultPng))
    val bmpOverridden = Bitmap.makeFromImage(Image.makeFromEncoded(overriddenPng))
    assertThat(pixelHash(bmpOverridden)).isNotEqualTo(pixelHash(bmpDefault))
  }

  private fun pixelHash(bitmap: Bitmap): Long {
    var h = 1125899906842597L
    for (y in 0 until bitmap.height step 3) {
      for (x in 0 until bitmap.width step 3) {
        h = 31L * h + bitmap.getColor(x, y).toLong()
      }
    }
    return h
  }
}
