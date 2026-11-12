package ee.schimke.composeai.rccreation.dwf

import ee.schimke.composeai.rcplayer.protocol.RcConditionalOperations
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextAnchored
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextOnCircle
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DwfParserAndCaptureTest {
  private val sampleWatchFaceXml =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <Metadata key="CLOCK_TYPE" value="ANALOG" />
      <Metadata key="PREVIEW_TIME" value="10:10:30" />

      <UserConfigurations>
        <ColorConfiguration id="theme" displayName="Theme" defaultValue="cyber">
          <ColorOption id="cyber" displayName="Cyber" colors="#FF00E5FF #FF7C4DFF" />
          <ColorOption id="ember" displayName="Ember" colors="#FFFF6D00 #FFFF1744" />
        </ColorConfiguration>
        <ListConfiguration id="dial_style" displayName="Dial" defaultValue="sport">
          <ListOption id="sport" displayName="Sport" />
          <ListOption id="minimal" displayName="Minimal" />
        </ListConfiguration>
        <BooleanConfiguration id="show_ticks" displayName="Ticks" defaultValue="true" />
      </UserConfigurations>

      <Scene backgroundColor="#FF0D1117">
        <Variant mode="AMBIENT" target="backgroundColor" value="#FF000000" />

        <!-- Progress ring driven by [STEP_PERCENT] -->
        <PartDraw x="25" y="25" width="400" height="400">
          <Variant mode="AMBIENT" target="alpha" value="110" />
          <Arc centerX="200" centerY="200" width="380" height="380" startAngle="0" endAngle="360">
            <Transform target="endAngle" value="clamp([STEP_PERCENT] * 3.6, 0, 360)" />
            <Stroke color="[CONFIGURATION.theme.0]" thickness="14" cap="ROUND" />
          </Arc>
        </PartDraw>

        <!-- Curved top label -->
        <PartText x="0" y="0" width="450" height="450">
          <TextCircular centerX="225" centerY="225" width="330" height="330"
              startAngle="320" endAngle="40" align="CENTER" direction="CLOCKWISE">
            <Font family="SYNC_TO_DEVICE" size="18" weight="MEDIUM" color="#FFB0BEC5">
              <Upper><Template>%s %d<Parameter expression="[DAY_OF_WEEK_S]" /><Parameter expression="[DAY]" /></Template></Upper>
            </Font>
          </TextCircular>
        </PartText>

        <!-- Digital sub-readout -->
        <DigitalClock x="125" y="95" width="200" height="48">
          <TimeText format="HH:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER">
            <Font family="SYNC_TO_DEVICE" size="34" weight="BOLD" color="#FFFFFFFF" />
          </TimeText>
        </DigitalClock>

        <!-- Complication slot -->
        <ComplicationSlot x="155" y="285" width="140" height="72" slotId="1"
            supportedTypes="SHORT_TEXT RANGED_VALUE EMPTY" defaultSystemProvider="WATCH_BATTERY"
            defaultSystemProviderType="SHORT_TEXT">
          <BoundingOval x="0" y="0" width="140" height="72" />
          <Complication type="SHORT_TEXT">
            <PartDraw x="0" y="0" width="140" height="72">
              <RoundRectangle x="0" y="0" width="140" height="72" cornerRadiusX="36" cornerRadiusY="36">
                <Fill color="#22FFFFFF" />
              </RoundRectangle>
            </PartDraw>
            <PartText x="10" y="14" width="120" height="44">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="22" weight="SEMI_BOLD" color="[CONFIGURATION.theme.1]">
                  <Parameter expression="[COMPLICATION.TEXT]" />
                </Font>
              </Text>
            </PartText>
          </Complication>
        </ComplicationSlot>

        <!-- Conditional low-battery badge -->
        <Condition>
          <Expressions>
            <Expression name="low_batt">[BATTERY_IS_LOW]</Expression>
          </Expressions>
          <Compare expression="low_batt">
            <PartText x="175" y="368" width="100" height="28">
              <Text align="CENTER">
                <Font size="16" color="#FFFF5252">LOW</Font>
              </Text>
            </PartText>
          </Compare>
          <Default>
            <PartImage x="209" y="366" width="32" height="32" resource="@drawable/ic_bolt" tintColor="[CONFIGURATION.theme.0]" />
          </Default>
        </Condition>

        <!-- Analog hands -->
        <AnalogClock x="0" y="0" width="450" height="450">
          <HourHand x="217" y="105" width="16" height="140" pivotX="0.5" pivotY="0.86" />
          <MinuteHand x="219" y="65" width="12" height="180" pivotX="0.5" pivotY="0.89" />
          <SecondHand x="222" y="45" width="6" height="210" pivotX="0.5" pivotY="0.86">
            <Sweep frequency="15" />
          </SecondHand>
        </AnalogClock>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun detectsWatchFaceRootAndRejectsOtherXml() {
    assertTrue(DwfParser.isDwfXml(sampleWatchFaceXml))
    assertTrue(
      !DwfParser.isDwfXml("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"),
    )
    assertNull(DwfParser.parseOrNull("<resources><string name=\"app\">Watch</string></resources>"))
  }

  @Test
  fun parsesWatchFaceAstMetadataConfigsAndSceneNodes() {
    val doc = DwfParser.parse(sampleWatchFaceXml)
    assertEquals(450, doc.width)
    assertEquals(450, doc.height)
    assertEquals(DwfClipShape.CIRCLE, doc.clipShape)
    assertEquals("10:10:30", doc.previewTime)
    assertEquals("ANALOG", doc.clockType)
    assertEquals(3, doc.userConfigurations.size)
    val themeCfg = doc.userConfigurations[0] as DwfUserConfiguration.ColorConfiguration
    assertEquals("theme", themeCfg.id)
    assertEquals(2, themeCfg.options.size)
    assertEquals(0xFF00E5FF.toInt(), themeCfg.options[0].colors[0])

    assertEquals(6, doc.scene.children.size)
    val analog = doc.scene.children.filterIsInstance<DwfSceneNode.AnalogClock>().single()
    assertEquals(3, analog.hands.size)
    assertEquals(
      15,
      analog.hands.first { it.kind == DwfClockHand.HandKind.SECOND }.sweepFrequencyHz,
    )
  }

  @Test
  fun parsesVectorDrawableAndCapturesRemoteComposeDocument() = runTest {
    val boltVector =
      DwfVectorDrawable.parseOrNull(
        """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp"
            android:height="24dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
          <path
              android:fillColor="#FFFFFFFF"
              android:pathData="M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66L13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51L11,21z" />
        </vector>
        """
          .trimIndent(),
      )
    assertNotNull(boltVector)
    val resolver = DwfMapResourceResolver(vectors = mapOf("ic_bolt" to boltVector))

    val rcDoc = captureDwfRemoteDocument(xml = sampleWatchFaceXml, resources = resolver)
    assertEquals(450, rcDoc.header.width)
    assertEquals(450, rcDoc.header.height)

    val allOps = rcDoc.operations
    val namedVars = allOps.filterIsInstance<RcNamedVariable>().map { it.name }.toSet()
    assertTrue(
      "USER:${DwfVariables.IS_AMBIENT}" in namedVars || DwfVariables.IS_AMBIENT in namedVars,
    )
    assertTrue(namedVars.any { it.endsWith(DwfVariables.STEP_COUNT) })
    assertTrue(namedVars.any { it.endsWith(DwfVariables.colorThemeKey("theme", 0)) })
    assertTrue(namedVars.any { it.endsWith(DwfVariables.complicationTextKey(1)) })

    assertTrue(
      allOps.any { it.opcode == RcOpcodes.DRAW_ARC },
      "Expected step progress arc in captured RcDocument",
    )
    assertTrue(
      allOps.any { it is RcDrawTextAnchored },
      "Expected anchored text in captured RcDocument",
    )
    assertTrue(
      allOps.any { it is RcDrawTextOnCircle },
      "Expected circular text in captured RcDocument",
    )
    assertTrue(
      allOps.any { it is RcConditionalOperations },
      "Expected conditional branches in captured RcDocument",
    )
  }

  @Test
  fun parsesFlavorsWeightedStrokesAndAppliesFakeComplicationAndWeatherSeeds() = runTest {
    val xml =
      """
      <WatchFace width="450" height="450" clipShape="CIRCLE">
        <UserConfigurations>
          <ColorConfiguration id="themeColor" displayName="Theme" defaultValue="1">
            <ColorOption id="1" displayName="Mint" colors="#FF00BCD4 #FF80DEEA" />
            <ColorOption id="2" displayName="Rose" colors="#FFD65780 #FFEE9480" />
          </ColorConfiguration>
          <BooleanConfiguration id="showHr" displayName="HR" defaultValue="FALSE" />
          <Flavors defaultValue="flavorOne">
            <Flavor id="flavorOne" displayName="One">
              <Configuration id="themeColor" optionId="1" />
              <Configuration id="showHr" optionId="FALSE" />
            </Flavor>
            <Flavor id="flavorTwo" displayName="Two">
              <Configuration id="themeColor" optionId="2" />
              <Configuration id="showHr" optionId="TRUE" />
              <ComplicationSlot slotId="0">
                <DefaultProviderPolicy defaultSystemProvider="STEP_COUNT" defaultSystemProviderType="SHORT_TEXT" />
              </ComplicationSlot>
            </Flavor>
          </Flavors>
        </UserConfigurations>
        <Scene backgroundColor="#FF000000">
          <PartText x="125" y="40" width="200" height="40">
            <Text align="CENTER">
              <Font family="SYNC_TO_DEVICE" size="24" weight="MEDIUM" color="[CONFIGURATION.themeColor.0]">
                <Parameter expression="[HEART_RATE] != null ? [HEART_RATE] : &quot;--&quot;" />
              </Font>
            </Text>
          </PartText>
          <PartText x="80" y="90" width="290" height="40">
            <Text align="CENTER">
              <Font family="SYNC_TO_DEVICE" size="20" color="#FFFFFFFF">
                <Parameter expression="icuText(&quot;HH:mm&quot;, [WEATHER.LAST_UPDATED])" />
              </Font>
            </Text>
          </PartText>
          <ComplicationSlot x="169" y="150" width="113" height="113" slotId="0"
              supportedTypes="RANGED_VALUE SHORT_TEXT WEIGHTED_ELEMENTS LONG_TEXT">
            <BoundingOval x="0" y="0" width="113" height="113" />
            <Complication type="RANGED_VALUE">
              <PartDraw x="0" y="0" width="113" height="113">
                <Arc centerX="56.5" centerY="56.5" width="100" height="100" startAngle="180" endAngle="420">
                  <WeightedStroke colors="[COMPLICATION.RANGED_VALUE_COLORS]" thickness="8"
                      interpolate="[COMPLICATION.RANGED_VALUE_COLORS_INTERPOLATE]" cap="ROUND" />
                </Arc>
              </PartDraw>
            </Complication>
            <Complication type="WEIGHTED_ELEMENTS">
              <PartDraw x="0" y="0" width="113" height="113">
                <Arc centerX="56.5" centerY="56.5" width="100" height="100" startAngle="0" endAngle="360">
                  <WeightedStroke colors="[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]"
                      weights="[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]" discreteGap="6" thickness="8" />
                </Arc>
              </PartDraw>
              <PartImage x="40" y="40" width="32" height="32">
                <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
              </PartImage>
            </Complication>
            <Complication type="LONG_TEXT">
              <PartText x="0" y="40" width="162" height="32">
                <Text align="START" ellipsis="TRUE">
                  <Font family="SYNC_TO_DEVICE" size="20" color="#FFFFFFFF">
                    <Parameter expression="[COMPLICATION.TEXT]" />
                  </Font>
                </Text>
              </PartText>
            </Complication>
          </ComplicationSlot>
        </Scene>
      </WatchFace>
      """
        .trimIndent()

    val parsed = DwfParser.parse(xml)
    assertEquals("flavorOne", parsed.defaultFlavorId)
    assertEquals(2, parsed.flavors.size)
    assertEquals("flavorTwo", parsed.flavors[1].id)
    assertEquals("2", parsed.flavors[1].configurations["themeColor"])
    assertEquals("STEP_COUNT", parsed.flavors[1].complications[0]?.defaultSystemProvider)

    val compSlot = parsed.scene.children.filterIsInstance<DwfSceneNode.ComplicationSlot>().single()
    val rangedDraw =
      compSlot.complications
        .first { it.type == "RANGED_VALUE" }
        .children
        .filterIsInstance<DwfSceneNode.PartDraw>()
        .single()
    val rangedShape = rangedDraw.shapes.single()
    assertTrue(rangedShape.primitive is DwfDrawPrimitive.Arc)
    assertEquals(1, rangedShape.weightedStrokes.size)
    assertEquals("[COMPLICATION.RANGED_VALUE_COLORS]", rangedShape.weightedStrokes.single().colors)

    val rcDoc =
      captureDwfRemoteDocument(
        document = parsed,
        options =
          DwfRenderOptions(
            flavorDefault = "flavorTwo",
            heartRateDefault = -1f,
            weatherDefault = DwfWeatherSeed(lastUpdatedText = "14:28"),
            complicationDefaults =
              mapOf(
                0 to
                  DwfComplicationSeed(
                    type = "WEIGHTED_ELEMENTS",
                    text = "Text w/ Icon & Title",
                    monochromaticImage = "builtin:battery",
                    rangedValueColors = listOf(0xFF00FFFF.toInt(), 0xFF00FF00.toInt()),
                    rangedValueColorsInterpolate = true,
                    weightedElementsColors =
                      listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
                    weightedElementsWeights = listOf(3f, 3f, 2f),
                  ),
              ),
          ),
      )

    val textOps =
      rcDoc.operations
        .filterIsInstance<ee.schimke.composeai.rcplayer.protocol.RcTextData>()
        .map { it.text }
        .toSet()
    assertTrue("14:28" in textOps, "Expected seeded weather lastUpdatedText in RcDocument")
    assertTrue("--" in textOps, "Expected fallback '--' literal for unavailable heart rate")
  }
}
