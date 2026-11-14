package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BUNDLE_WATCHFACE_SUFFIX
import ee.schimke.composeai.bundle.WatchFaceActiveComplicationDto
import ee.schimke.composeai.bundle.WatchFaceComplicationSlotDto
import ee.schimke.composeai.bundle.WatchFaceComplicationsPayload
import ee.schimke.composeai.bundle.WatchFaceComplicationsProduct
import ee.schimke.composeai.bundle.WatchFaceSlotBoundsDto
import ee.schimke.composeai.bundle.WatchFaceUserConfigDto
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.io.SystemFileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WatchFaceSupportTest {

  private val json = Json { encodeDefaults = true }

  private fun sampleActiveState(
    type: String = "SHORT_TEXT",
    typeCode: Int = 1,
    text: String = "72 BPM",
    value: Float = 0f,
  ): WatchFaceActiveComplicationDto =
    WatchFaceActiveComplicationDto(
      type = type,
      typeCode = typeCode,
      text = text,
      title = "",
      value = value,
      min = 0f,
      max = 100f,
      hasMonochromaticImage = false,
      hasSmallImage = false,
      hasPhotoImage = false,
      imageColor = "#FFFFFFFF",
      accentColor = "#FF00E676",
      hasRangedValueColors = false,
      rangedValueColorsInterpolate = false,
      hasGoalProgressColors = false,
      goalProgressColorsInterpolate = false,
      hasWeightedElements = false,
      colorCount = 1,
    )

  @AfterTest
  fun resetRegistry() {
    WatchFaceRendererRegistry.resetDefaults()
    System.clearProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP)
  }

  @Test
  fun `parse translates ambient, clockEpochMillis, wfRenderer, and wf slot overrides`() {
    val parsed =
      ServeOverrides.parse(
        mapOf(
          "ambient" to "true",
          "ambientBurnIn" to "true",
          "clockEpochMillis" to "10:15:30",
          "wfRenderer" to "dwf-rc",
          "wf.batteryPercent" to "42",
          "wf.config.theme" to "mint",
          "wf.slot.1.type" to "RANGED_VALUE",
          "wf.slot.1.value" to "75",
          "wf.slot.1.min" to "0",
          "wf.slot.1.max" to "100",
          "wf.slot.1.text" to "75%",
          "wf.slot.1.colors" to "#FF00E676,#FFFFEA00",
        ),
      )
    val ok = assertIs<OverrideParse.Ok>(parsed)
    val ov = ok.overrides
    assertEquals(AmbientStateOverride.AMBIENT, ov.ambient?.state)
    assertEquals(true, ov.ambient?.burnInProtectionRequired)
    assertNotNull(ov.clockEpochMillis)
    assertEquals(
      PreviewOverrideValue.FloatValue(42f),
      ov.namedOverrides?.get("wf.batteryPercent"),
    )
    assertEquals(
      PreviewOverrideValue.StringValue("mint"),
      ov.namedOverrides?.get("wf.config.theme"),
    )
    assertEquals(
      PreviewOverrideValue.StringValue("RANGED_VALUE"),
      ov.namedOverrides?.get("wf.slot.1.type"),
    )
    val rcValues = ov.remoteCompose?.namedValues.orEmpty()
    assertEquals(RemoteNamedValue.FloatValue(75f), rcValues["dwf.complication.1.value"])
    assertEquals(RemoteNamedValue.StringValue("75%"), rcValues["dwf.complication.1.text"])
    assertEquals(RemoteNamedValue.IntValue(2), rcValues["dwf.complication.1.color_count"])
    assertEquals(RemoteNamedValue.ColorValue("#FF00E676"), rcValues["dwf.complication.1.color.0"])
    assertEquals(RemoteNamedValue.ColorValue("#FFFFEA00"), rcValues["dwf.complication.1.color.1"])

    val keyA = ServeOverrides.cacheKey("watchface_1", ov)
    val keyB = ServeOverrides.cacheKey("watchface_1", ov.copy(clockEpochMillis = 123456L))
    assertTrue(keyA != keyB, "clockEpochMillis must participate in ServeOverrides.cacheKey")
  }

  @Test
  fun `WatchFaceRendererRegistry exposes dwf-rc and dwf-apk and allows custom backend registration`() {
    assertEquals(
      listOf(WatchFaceRendererIds.DWF_RC, WatchFaceRendererIds.DWF_APK),
      WatchFaceRendererRegistry.all().map { it.id },
    )
    assertEquals(WatchFaceRendererIds.DWF_RC, WatchFaceRendererRegistry.resolve(null)?.id)
    assertEquals(WatchFaceRendererIds.DWF_APK, WatchFaceRendererRegistry.resolve("dwf-apk")?.id)
    assertNull(WatchFaceRendererRegistry.resolve("unknown-renderer"))

    val payload =
      WatchFaceComplicationsPayload(
        previewId = "wf_1",
        dwfAssetPath = "raw/watchface.xml",
        widthPx = 450,
        heightPx = 450,
        clipShape = "CIRCLE",
        previewTime = "10:10:30",
        isAmbient = false,
        userConfigurations =
          listOf(
            WatchFaceUserConfigDto(
              id = "color_style",
              kind = "COLOR",
              displayName = "Color Style",
              defaultValue = "ocean",
              activeValue = "ocean",
              options = listOf("ocean", "ember"),
            ),
          ),
        slots =
          listOf(
            WatchFaceComplicationSlotDto(
              slotId = 1,
              bounds = WatchFaceSlotBoundsDto(100f, 100f, 90f, 90f),
              supportedTypes = listOf("SHORT_TEXT", "RANGED_VALUE"),
              authoredTypes = listOf("SHORT_TEXT", "RANGED_VALUE"),
              activeState = sampleActiveState(text = "72 BPM"),
            ),
          ),
      )
    val preview =
      ServePreview(
        id = "wf_1",
        label = "watchface",
        watchFace = payload,
        dwfAssetPath = "raw/watchface.xml",
      )
    val fakeHost =
      object : ServeHost {
        override val previews: List<ServePreview> = listOf(preview)
        override val label: String = "test"

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Ok(byteArrayOf(1, 2, 3), RenderOutcome.Generation.DAEMON)

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: ee.schimke.composeai.daemon.protocol.StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (ee.schimke.composeai.daemon.protocol.StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun activeStreamCount(): Int = 0

        override fun close() {}
      }

    val renderers = WatchFaceRendererRegistry.renderersFor(fakeHost, preview)
    assertEquals(2, renderers.size)
    assertTrue(renderers.first { it.id == WatchFaceRendererIds.DWF_RC }.isDefault)
    assertTrue(!renderers.first { it.id == WatchFaceRendererIds.DWF_APK }.available)

    // Register a custom launch-backed `dwf-apk` test double and verify dispatch + projection.
    val customApkBackend =
      object : WatchFaceRendererBackend {
        override val id: String = WatchFaceRendererIds.DWF_APK
        override val label: String = "Custom DWF APK Simulator"
        override val badgeLabel: String = "dwf · apk-sim"

        override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo =
          WatchFaceRendererInfo(
            id = id,
            label = label,
            badgeLabel = badgeLabel,
            available = true,
            supportsLiveOverrides = true,
          )

        override fun render(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): RenderOutcome = RenderOutcome.Ok(byteArrayOf(9, 9, 9), RenderOutcome.Generation.DAEMON)

        override fun complications(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): WatchFaceComplicationsOutcome =
          WatchFaceOverrides.projectComplicationsOutcome(preview.watchFace!!, overrides, id)
      }
    WatchFaceRendererRegistry.register(customApkBackend)

    val resolvedApk = assertNotNull(WatchFaceRendererRegistry.resolve(WatchFaceRendererIds.DWF_APK))
    val renderRes =
      assertIs<RenderOutcome.Ok>(resolvedApk.render(fakeHost, preview, PreviewOverrides()))
    assertEquals(listOf<Byte>(9, 9, 9), renderRes.png.toList())

    val parsed =
      assertIs<OverrideParse.Ok>(
        ServeOverrides.parse(
          mapOf(
            "ambient" to "true",
            "wf.slot.1.type" to "RANGED_VALUE",
            "wf.slot.1.text" to "88%",
            "wf.slot.1.value" to "88",
            "wf.slot.1.colors" to "#FF00E676,#FFFFEA00",
          ),
        ),
      )
    val compRes =
      assertIs<WatchFaceComplicationsOutcome.Ok>(
        resolvedApk.complications(fakeHost, preview, parsed.overrides),
      )
    assertEquals(WatchFaceRendererIds.DWF_APK, compRes.rendererId)
    assertTrue(compRes.payload.isAmbient)
    assertEquals("RANGED_VALUE", compRes.payload.slots.single().activeState.type)
    assertEquals("88%", compRes.payload.slots.single().activeState.text)
    assertEquals(88f, compRes.payload.slots.single().activeState.value)
    assertEquals(
      listOf("#FF00E676", "#FFFFEA00"),
      compRes.payload.slots.single().activeState.colors,
    )
  }

  @Test
  fun `ServeBundleDaemon extracts watchface sidecars onto ServePreview`() {
    val dir = Files.createTempDirectory("serve-wf-sidecar").toFile()
    try {
      val previewsDir = File(dir, "previews").apply { mkdirs() }
      val wfId = "dwf__raw_watchface"
      val previewsJson = File(dir, "previews.json")
      previewsJson.writeText(
        """
        {
          "module": ":watchface",
          "variant": "debug",
          "previews": [
            {
              "id": "$wfId",
              "functionName": "watchface",
              "className": "",
              "params": {
                "name": "watchface",
                "dwfAssetPath": "raw/watchface.xml"
              }
            }
          ]
        }
        """
          .trimIndent(),
      )

      val payload =
        WatchFaceComplicationsPayload(
          previewId = wfId,
          dwfAssetPath = "raw/watchface.xml",
          widthPx = 450,
          heightPx = 450,
          clipShape = "CIRCLE",
          previewTime = "10:10:30",
          isAmbient = false,
          slots =
            listOf(
              WatchFaceComplicationSlotDto(
                slotId = 1,
                bounds = WatchFaceSlotBoundsDto(100f, 100f, 90f, 90f),
                supportedTypes = listOf("SHORT_TEXT"),
                authoredTypes = listOf("SHORT_TEXT"),
                activeState = sampleActiveState(text = "42"),
              ),
            ),
        )
      val bos = ByteArrayOutputStream()
      ZipOutputStream(bos).use { zos ->
        zos.putNextEntry(ZipEntry("previews/$wfId$BUNDLE_WATCHFACE_SUFFIX"))
        zos.write(
          json.encodeToString(WatchFaceComplicationsPayload.serializer(), payload).toByteArray(),
        )
        zos.closeEntry()
      }

      ServeBundleDaemon.extractKnobSidecars(bos.toByteArray(), previewsDir, SystemFileSystem)
      val previews = ServeBundleDaemon.readPreviews(previewsJson, previewsDir, SystemFileSystem)
      assertEquals(1, previews.size)
      val loaded = previews.single()
      assertTrue(loaded.isWatchFace)
      assertEquals("raw/watchface.xml", loaded.dwfAssetPath)
      assertTrue(WatchFaceComplicationsProduct.KIND in loaded.dataProductKinds)
      assertEquals("42", loaded.watchFace?.slots?.single()?.activeState?.text)
    } finally {
      dir.deleteRecursively()
    }
  }
}
