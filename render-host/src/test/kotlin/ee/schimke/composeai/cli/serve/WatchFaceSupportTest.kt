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
    DwfApkWatchFaceRendererBackend.resetBridge()
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
      listOf(WatchFaceRendererIds.DWF_APK, WatchFaceRendererIds.DWF_RC),
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
              optionColors =
                mapOf(
                  "ocean" to listOf("#FF00BCD4", "#FF006064"),
                  "ember" to listOf("#FFFF5722", "#FFBF360C"),
                ),
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
    assertEquals(
      listOf(WatchFaceRendererIds.DWF_APK, WatchFaceRendererIds.DWF_RC),
      renderers.map { it.id },
    )
    assertTrue(renderers.first { it.id == WatchFaceRendererIds.DWF_RC }.isDefault)
    assertTrue(!renderers.first { it.id == WatchFaceRendererIds.DWF_APK }.available)
    assertEquals(
      WatchFaceRendererIds.DWF_RC,
      WatchFaceRendererRegistry.resolveFor(fakeHost, preview, null)?.id,
    )

    // Even when an APK file path is configured on disk, the built-in backend stays unavailable
    // when sidecars / live watch-face bundle are absent on the host.
    val tempApk = Files.createTempFile("fake-dwf-renderer", ".apk").toFile()
    try {
      System.setProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP, tempApk.absolutePath)
      val apkInfo = DwfApkWatchFaceRendererBackend.infoFor(fakeHost, preview)
      assertTrue(!apkInfo.available)
      assertTrue(!apkInfo.supportsLiveOverrides)
      assertIs<RenderOutcome.Failed>(
        DwfApkWatchFaceRendererBackend.render(fakeHost, preview, PreviewOverrides()),
      )
      assertIs<WatchFaceComplicationsOutcome.Unavailable>(
        DwfApkWatchFaceRendererBackend.complications(fakeHost, preview, PreviewOverrides()),
      )
    } finally {
      System.clearProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP)
      tempApk.delete()
    }

    // Portable `namedOverrides` (without `remoteCompose.namedValues`) translate into RC seeds and
    // complication projections for static fallback lanes, while explicit RC seeds still win.
    val portableOnly =
      PreviewOverrides(
        namedOverrides =
          mapOf(
            "wf.batteryPercent" to PreviewOverrideValue.FloatValue(12f),
            "wf.config.color_style" to PreviewOverrideValue.StringValue("ember"),
            "wf.slot.1.type" to PreviewOverrideValue.StringValue("SHORT_TEXT"),
            "wf.slot.1.text" to PreviewOverrideValue.StringValue("95 BPM"),
          ),
      )
    val translatedSeeds = WatchFaceOverrides.effectiveRcNamedValues(portableOnly, payload)
    assertEquals(RemoteNamedValue.FloatValue(12f), translatedSeeds["dwf.battery_percent"])
    assertEquals(RemoteNamedValue.BooleanValue(true), translatedSeeds["dwf.battery_is_low"])
    assertEquals(RemoteNamedValue.IntValue(1), translatedSeeds["dwf.config.color_style"])
    assertEquals(
      RemoteNamedValue.ColorValue("#FFFF5722"),
      translatedSeeds["dwf.color.color_style.0"],
    )
    assertEquals(
      RemoteNamedValue.StringValue("95 BPM"),
      translatedSeeds["dwf.complication.1.text"],
    )
    val projectedPortable = WatchFaceOverrides.projectComplicationsPayload(payload, portableOnly)
    assertEquals("95 BPM", projectedPortable.slots.single().activeState.text)
    assertEquals("ember", projectedPortable.userConfigurations.single().activeValue)

    // Register a custom launch-backed `dwf-apk` test double and verify it becomes the default
    // watch-face renderer whenever available.
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

    val renderersWithApk = WatchFaceRendererRegistry.renderersFor(fakeHost, preview)
    assertTrue(renderersWithApk.first { it.id == WatchFaceRendererIds.DWF_APK }.isDefault)
    assertTrue(!renderersWithApk.first { it.id == WatchFaceRendererIds.DWF_RC }.isDefault)
    assertEquals(WatchFaceRendererIds.DWF_APK, WatchFaceRendererRegistry.resolve(null)?.id)
    assertEquals(
      WatchFaceRendererIds.DWF_APK,
      WatchFaceRendererRegistry.resolveFor(fakeHost, preview, null)?.id,
    )

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

  @Test
  fun `DwfApkWatchFaceRendererBackend delegates to DwfApkSessionBridge and resolves configured APK`() {
    val preview =
      ServePreview(
        id = "dwf__bridge_test",
        label = "bridge_test",
        watchFace =
          WatchFaceComplicationsPayload(
            previewId = "dwf__bridge_test",
            dwfAssetPath = "raw/watchface.xml",
            widthPx = 450,
            heightPx = 450,
            clipShape = "CIRCLE",
            previewTime = "10:10:30",
            isAmbient = false,
            slots = emptyList(),
          ),
        dwfAssetPath = "raw/watchface.xml",
      )
    val fakeHost =
      object : ServeHost {
        override val previews: List<ServePreview> = listOf(preview)
        override val label: String = "test"

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.NotFound

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

    // Without a runtime APK or custom bridge, infoFor reports unavailable with an actionable
    // reason.
    val missingInfo = DwfApkWatchFaceRendererBackend.infoFor(fakeHost, preview)
    assertEquals(WatchFaceRendererIds.DWF_APK, missingInfo.id)
    assertTrue(!missingInfo.available || DwfApkWatchFaceRendererBackend.configuredApkFile() != null)

    // configuredApkFile() resolves a real file passed via APK_PATH_SYS_PROP.
    val tmpApk = Files.createTempFile("dwf-runtime-", ".apk").toFile()
    try {
      System.setProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP, tmpApk.absolutePath)
      assertEquals(tmpApk, DwfApkWatchFaceRendererBackend.configuredApkFile())
    } finally {
      System.clearProperty(DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP)
      tmpApk.delete()
    }
    assertNull(
      File("/nonexistent/dwf-runtime.apk").takeIf(File::isFile),
    )

    // Custom DwfApkSessionBridge receives infoFor, render, and complications calls, and becomes the
    // default watch-face backend when available.
    DwfApkWatchFaceRendererBackend.setBridge(
      object : DwfApkSessionBridge {
        override fun isAvailable(host: ServeHost, preview: ServePreview): Boolean = true

        override fun unavailableReason(host: ServeHost, preview: ServePreview): String? = null

        override fun render(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): RenderOutcome = RenderOutcome.Ok(byteArrayOf(7, 8, 9), RenderOutcome.Generation.DAEMON)

        override fun complications(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): WatchFaceComplicationsOutcome =
          WatchFaceOverrides.projectComplicationsOutcome(
            preview.watchFace!!,
            overrides,
            WatchFaceRendererIds.DWF_APK,
          )
      },
    )
    val bridgeInfo = DwfApkWatchFaceRendererBackend.infoFor(fakeHost, preview)
    assertTrue(bridgeInfo.available)
    assertTrue(bridgeInfo.isDefault)
    assertNull(bridgeInfo.unavailableReason)
    assertEquals(WatchFaceRendererIds.DWF_APK, WatchFaceRendererRegistry.resolve(null)?.id)
    assertEquals(
      WatchFaceRendererIds.DWF_APK,
      WatchFaceRendererRegistry.resolveFor(fakeHost, preview, null)?.id,
    )
    val bridgeRender =
      assertIs<RenderOutcome.Ok>(
        DwfApkWatchFaceRendererBackend.render(fakeHost, preview, PreviewOverrides()),
      )
    assertEquals(listOf<Byte>(7, 8, 9), bridgeRender.png.toList())
    val bridgeComps =
      assertIs<WatchFaceComplicationsOutcome.Ok>(
        DwfApkWatchFaceRendererBackend.complications(fakeHost, preview, PreviewOverrides()),
      )
    assertEquals(WatchFaceRendererIds.DWF_APK, bridgeComps.rendererId)
  }

  @Test
  fun `WatchFaceOverrides preserves complication image URLs and flavor overrides`() {
    val photoUrl = "https://compose-preview.local/dwf/photo-image.png"
    val monoDataUrl = "data:image/png;base64,iVBORw0KGgo="
    val parsed =
      assertIs<WatchFaceOverrides.WfParamParse.Ok>(
        WatchFaceOverrides.parseWfParams(
          mapOf(
            "wf.flavor" to "autumn",
            "wf.slot.1.type" to "PHOTO_IMAGE",
            "wf.slot.1.photoImage" to photoUrl,
            "wf.slot.2.type" to "SHORT_TEXT",
            "wf.slot.2.monochromaticImage" to monoDataUrl,
          ),
        ),
      )
    assertEquals(PreviewOverrideValue.StringValue("autumn"), parsed.namedOverrides["wf.flavor"])
    assertEquals(RemoteNamedValue.StringValue("autumn"), parsed.rcNamedValues["dwf.flavor"])
    assertEquals(
      PreviewOverrideValue.StringValue(photoUrl),
      parsed.namedOverrides["wf.slot.1.photoImage"],
    )
    assertEquals(
      RemoteNamedValue.BooleanValue(true),
      parsed.rcNamedValues["dwf.complication.1.has_photo_image"],
    )
    assertEquals(
      RemoteNamedValue.StringValue(photoUrl),
      parsed.rcNamedValues["dwf.complication.1.photo_image"],
    )
    assertEquals(
      PreviewOverrideValue.StringValue(monoDataUrl),
      parsed.namedOverrides["wf.slot.2.monochromaticImage"],
    )
  }

  @Test
  fun `DwfApkDaemonBridge emits watchface config op with flavor and clears stale slot overrides`() {
    val preview =
      ServePreview(
        id = "dwf__flavors_test",
        label = "flavors_test",
        watchFace =
          WatchFaceComplicationsPayload(
            previewId = "dwf__flavors_test",
            dwfAssetPath = "raw/watchface.xml",
            widthPx = 450,
            heightPx = 450,
            clipShape = "CIRCLE",
            previewTime = "10:10:30",
            isAmbient = false,
            slots = emptyList(),
          ),
        dwfAssetPath = "raw/watchface.xml",
      )
    val parsed =
      assertIs<WatchFaceOverrides.WfParamParse.Ok>(
        WatchFaceOverrides.parseWfParams(
          mapOf(
            "wf.flavor" to "autumn",
            "wf.config.clockStyle" to "digital",
            "wf.slot.1.type" to "SHORT_TEXT",
            "wf.slot.1.text" to "84",
          ),
        ),
      )
    val overrides =
      PreviewOverrides(
        namedOverrides = parsed.namedOverrides,
        remoteCompose =
          ee.schimke.composeai.daemon.protocol.RemoteComposeOverride.Builder()
            .also { it.namedValues = parsed.rcNamedValues }
            .build(),
      )
    val (commands, overriddenSlots) =
      DwfApkDaemonBridge.buildOverrideCommands(
        preview = preview,
        overrides = overrides,
        previouslyOverriddenSlots = setOf(1, 2),
      )
    assertEquals(setOf(1), overriddenSlots)

    val wfCommands = commands.filter { it.first == "watchface" }.map { it.second }
    assertTrue(wfCommands.none { it["op"]?.toString()?.contains("flavor") == true })

    val configIdx = wfCommands.indexOfFirst { it["op"]?.toString() == "\"config\"" }
    val uncompIdx = wfCommands.indexOfFirst { it["op"]?.toString() == "\"uncomplication\"" }
    assertTrue(uncompIdx >= 0 && configIdx >= 0 && uncompIdx < configIdx)

    val configCmd = wfCommands[configIdx]
    assertEquals("\"autumn\"", configCmd["flavor"]?.toString())
    assertEquals("true", configCmd["reset"]?.toString())
    assertTrue(configCmd["values"]?.toString()?.contains("\"clockStyle\":\"digital\"") == true)

    val uncompCmd = wfCommands[uncompIdx]
    assertEquals("2", uncompCmd["slot"]?.toString())
  }

  @Test
  fun `dwf-apk bridge decodes PNG+ZIP carriers and extracts Android resources from classes app jar`() {
    val pngCarrier =
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUB" +
            "AScY42YAAAAASUVORK5CYII=",
        )
    val appJarBytes =
      java.io.ByteArrayOutputStream().let { baos ->
        java.util.zip.ZipOutputStream(baos).use { zout ->
          zout.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
          zout.write("Manifest-Version: 1.0\n".toByteArray())
          zout.closeEntry()
          zout.putNextEntry(java.util.zip.ZipEntry("com/example/Dummy.class"))
          zout.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
          zout.closeEntry()
          zout.putNextEntry(java.util.zip.ZipEntry("raw/watchface.xml"))
          zout.write("<WatchFace width=\"450\" height=\"450\"/>".toByteArray())
          zout.closeEntry()
          zout.putNextEntry(java.util.zip.ZipEntry("values-round/strings.xml"))
          zout.write("<resources/>".toByteArray())
          zout.closeEntry()
        }
        baos.toByteArray()
      }
    val outerZipBytes =
      java.io.ByteArrayOutputStream().let { baos ->
        java.util.zip.ZipOutputStream(baos).use { zout ->
          zout.putNextEntry(java.util.zip.ZipEntry("classes/app.jar"))
          zout.write(appJarBytes)
          zout.closeEntry()
          zout.putNextEntry(java.util.zip.ZipEntry("android/resources.ap_"))
          zout.write("PRELINKED".toByteArray())
          zout.closeEntry()
        }
        baos.toByteArray()
      }
    val polyglot = pngCarrier + outerZipBytes
    val decoded = DwfApkDaemonBridge.decodeBundleZipBytes(polyglot)
    assertEquals(
      "PRELINKED",
      ee.schimke.composeai.bundle.AndroidBundleResources.extractResourceApkBytes(decoded)
        ?.decodeToString(),
    )

    val outDir = java.nio.file.Files.createTempDirectory("dwf-jar-res").toFile()
    try {
      assertTrue(DwfApkDaemonBridge.extractJarAndroidResources(appJarBytes, outDir))
      assertTrue(java.io.File(outDir, "raw/watchface.xml").isFile)
      assertTrue(java.io.File(outDir, "values-round/strings.xml").isFile)
      assertTrue(!java.io.File(outDir, "META-INF/MANIFEST.MF").exists())
      assertTrue(!java.io.File(outDir, "com/example/Dummy.class").exists())
    } finally {
      outDir.deleteRecursively()
    }
  }
}
