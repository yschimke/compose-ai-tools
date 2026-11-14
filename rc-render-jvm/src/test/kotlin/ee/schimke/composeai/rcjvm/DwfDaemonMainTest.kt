package ee.schimke.composeai.rcjvm

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeProduct
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DwfDaemonMainTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  private val sampleWatchFaceXml =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <Metadata key="PREVIEW_TIME" value="10:08:30" />
      <UserConfigurations>
        <BooleanConfiguration id="show_seconds" displayName="Show Seconds" defaultValue="true" />
      </UserConfigurations>
      <Scene backgroundColor="#FF0D1117">
        <DigitalClock x="125" y="30" width="200" height="60">
          <TimeText format="hh:mm" x="0" y="0" width="200" height="60" size="38" align="CENTER" color="#FFFFFFFF" />
        </DigitalClock>

        <!-- Slot 1: supports SHORT_TEXT, RANGED_VALUE, GOAL_PROGRESS, WEIGHTED_ELEMENTS, MONOCHROMATIC_IMAGE, SMALL_IMAGE -->
        <ComplicationSlot
            slotId="1"
            x="45"
            y="140"
            width="160"
            height="140"
            supportedTypes="SHORT_TEXT RANGED_VALUE GOAL_PROGRESS WEIGHTED_ELEMENTS MONOCHROMATIC_IMAGE SMALL_IMAGE"
            defaultSystemProvider="WATCH_BATTERY"
            defaultSystemProviderType="RANGED_VALUE">
          <BoundingOval x="0" y="0" width="160" height="140" />
          <Complication type="SHORT_TEXT">
            <Condition>
              <Expressions>
                <Expression name="has_mono">[COMPLICATION.MONOCHROMATIC_IMAGE] != null</Expression>
                <Expression name="has_title">[COMPLICATION.TITLE] != null &amp;&amp; [COMPLICATION.TITLE] != ""</Expression>
              </Expressions>
              <Compare expression="has_mono">
                <PartImage x="60" y="12" width="40" height="40" resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
              </Compare>
            </Condition>
            <PartText x="10" y="56" width="140" height="34">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="20" color="#FF7EE787">
                  <Parameter expression="[COMPLICATION.TITLE]" />
                </Font>
              </Text>
            </PartText>
            <PartText x="10" y="92" width="140" height="40">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="26" weight="BOLD" color="#FFFFFFFF">
                  <Parameter expression="[COMPLICATION.TEXT]" />
                </Font>
              </Text>
            </PartText>
          </Complication>
          <Complication type="RANGED_VALUE">
            <PartDraw x="10" y="10" width="140" height="120">
              <Arc centerX="70" centerY="60" width="110" height="110"
                   startAngle="210"
                   endAngle="210 + 300 * clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)">
                <WeightedStroke colors="[COMPLICATION.RANGED_VALUE_COLORS]" thickness="12" cap="ROUND" interpolate="[COMPLICATION.RANGED_VALUE_COLORS_INTERPOLATE]" />
              </Arc>
            </PartDraw>
            <PartText x="20" y="50" width="120" height="40">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="22" color="#FFFFFFFF">
                  <Parameter expression="[COMPLICATION.TEXT]" />
                </Font>
              </Text>
            </PartText>
          </Complication>
          <Complication type="GOAL_PROGRESS">
            <PartDraw x="10" y="10" width="140" height="120">
              <Arc centerX="70" centerY="60" width="110" height="110"
                   startAngle="180"
                   endAngle="180 + 320 * clamp([COMPLICATION.GOAL_PROGRESS_VALUE] / [COMPLICATION.GOAL_PROGRESS_TARGET_VALUE], 0, 1)">
                <WeightedStroke colors="[COMPLICATION.GOAL_PROGRESS_COLORS]" thickness="12" cap="ROUND" interpolate="[COMPLICATION.GOAL_PROGRESS_COLORS_INTERPOLATE]" />
              </Arc>
            </PartDraw>
          </Complication>
          <Complication type="WEIGHTED_ELEMENTS">
            <PartDraw x="10" y="10" width="140" height="120">
              <Arc centerX="70" centerY="60" width="110" height="110" startAngle="0" endAngle="360">
                <WeightedStroke colors="[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]" weights="[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]" thickness="14" cap="BUTT" />
              </Arc>
            </PartDraw>
          </Complication>
          <Complication type="MONOCHROMATIC_IMAGE">
            <PartImage x="40" y="30" width="80" height="80" resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
          </Complication>
          <Complication type="SMALL_IMAGE">
            <PartImage x="40" y="30" width="80" height="80" resource="[COMPLICATION.SMALL_IMAGE]" />
          </Complication>
        </ComplicationSlot>

        <!-- Slot 2: PHOTO_IMAGE / LONG_TEXT -->
        <ComplicationSlot
            slotId="2"
            x="230"
            y="140"
            width="175"
            height="140"
            supportedTypes="LONG_TEXT PHOTO_IMAGE">
          <BoundingBox x="0" y="0" width="175" height="140" />
          <Complication type="LONG_TEXT">
            <PartText x="5" y="30" width="165" height="40">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="18" color="#FF9CDCFE">
                  <Parameter expression="[COMPLICATION.TITLE]" />
                </Font>
              </Text>
            </PartText>
            <PartText x="5" y="72" width="165" height="44">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="22" color="#FFFFFFFF">
                  <Parameter expression="[COMPLICATION.TEXT]" />
                </Font>
              </Text>
            </PartText>
          </Complication>
          <Complication type="PHOTO_IMAGE">
            <PartImage x="10" y="10" width="155" height="120" resource="[COMPLICATION.PHOTO_IMAGE]" />
          </Complication>
        </ComplicationSlot>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun daemonSupportsComplicationDiscoveryOverridesAndSemantics() {
    val root = Files.createTempDirectory("dwf-daemon-test-").toFile()
    try {
      val resDir = File(root, "src/main/res").apply { mkdirs() }
      val rawDir = File(resDir, "raw").apply { mkdirs() }
      File(rawDir, "watchface.xml").writeText(sampleWatchFaceXml)

      val previewsDir = File(root, "build/compose-previews").apply { mkdirs() }
      val outputDir = File(previewsDir, "dwf-renders").apply { mkdirs() }
      val previewsJson = File(previewsDir, "previews.json")
      previewsJson.writeText(
        """
        {
          "previews": [
            {
              "id": "dwf__raw_watchface",
              "functionName": "watchface",
              "params": {
                "name": "SampleWatchFace",
                "widthDp": 450,
                "heightDp": 450,
                "density": 1.0,
                "dwfAssetPath": "raw/watchface.xml"
              },
              "captures": [
                { "renderOutput": "dwf-renders/dwf__raw_watchface_0.png" }
              ]
            }
          ]
        }
        """
          .trimIndent(),
      )

      val harness =
        DaemonHarness(
          DwfDaemonConfig(
            previewsJson = previewsJson,
            outputDir = outputDir,
            resourceDirs = listOf(resDir),
            moduleProjectDir = root,
          ),
        )
      try {
        val initRes =
          json.decodeFromJsonElement(
            InitializeResult.serializer(),
            harness.request("initialize", buildJsonObject { put("protocolVersion", 2) }),
          )
        assertThat(initRes.manifest.previewCount).isEqualTo(1)
        assertThat(initRes.capabilities.dataProducts.map { it.kind })
          .containsAtLeast(
            RemoteComposeProduct.KIND,
            ComposeSemanticsProduct.KIND,
            WatchFaceComplicationsProduct.KIND,
          )
        assertThat(initRes.capabilities.supportedOverrides)
          .containsAtLeast("ambient", "remoteCompose", "widthPx", "heightPx")

        // 1. Default render + fetch watchface/complications & compose/semantics
        val defaultHash = harness.renderAndHash("dwf__raw_watchface", null)
        val defaultCompPayload = harness.fetchComplications("dwf__raw_watchface")
        assertThat(defaultCompPayload.slots).hasSize(2)
        assertThat(defaultCompPayload.slots[0].slotId).isEqualTo(1)
        assertThat(defaultCompPayload.slots[0].supportedTypes)
          .containsExactly(
            "SHORT_TEXT",
            "RANGED_VALUE",
            "GOAL_PROGRESS",
            "WEIGHTED_ELEMENTS",
            "MONOCHROMATIC_IMAGE",
            "SMALL_IMAGE",
          )
        assertThat(defaultCompPayload.slots[1].slotId).isEqualTo(2)

        val defaultSemantics = harness.fetchSemantics("dwf__raw_watchface")
        val slotTags = collectTestTags(defaultSemantics.root)
        assertThat(slotTags)
          .containsAtLeast("watchface_root", "complication_slot_1", "complication_slot_2")

        // 2. Switch slot 1 to SHORT_TEXT (text-only vs icon+title+text)
        val shortTextOverrides =
          namedOverrides(
            DwfVariables.complicationTypeKey(1) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_SHORT_TEXT),
            DwfVariables.complicationTitleKey(1) to RemoteNamedValue.StringValue(""),
            DwfVariables.complicationTextKey(1) to RemoteNamedValue.StringValue("72 BPM"),
            DwfVariables.complicationHasMonoImageKey(1) to RemoteNamedValue.BooleanValue(false),
          )
        val shortTextHash = harness.renderAndHash("dwf__raw_watchface", shortTextOverrides)
        assertThat(shortTextHash).isNotEqualTo(defaultHash)

        val shortTextWithIconOverrides =
          namedOverrides(
            DwfVariables.complicationTypeKey(1) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_SHORT_TEXT),
            DwfVariables.complicationTitleKey(1) to RemoteNamedValue.StringValue("HEART"),
            DwfVariables.complicationTextKey(1) to RemoteNamedValue.StringValue("138 BPM"),
            DwfVariables.complicationHasMonoImageKey(1) to RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationImageColorKey(1) to RemoteNamedValue.ColorValue("#FFFF5252"),
          )
        val shortTextWithIconHash =
          harness.renderAndHash("dwf__raw_watchface", shortTextWithIconOverrides)
        assertThat(shortTextWithIconHash).isNotEqualTo(shortTextHash)

        val updatedComp = harness.fetchComplications("dwf__raw_watchface")
        assertThat(updatedComp.slots[0].activeState.type).isEqualTo("SHORT_TEXT")
        assertThat(updatedComp.slots[0].activeState.title).isEqualTo("HEART")
        assertThat(updatedComp.slots[0].activeState.text).isEqualTo("138 BPM")
        assertThat(updatedComp.slots[0].activeState.hasMonochromaticImage).isTrue()

        val updatedSem = harness.fetchSemantics("dwf__raw_watchface")
        val slot1Node = findByTestTag(updatedSem.root, "complication_slot_1")
        assertThat(slot1Node).isNotNull()
        assertThat(slot1Node!!.label).contains("138 BPM")

        // 3. Switch slot 1 to RANGED_VALUE with custom color ramp (discrete vs interpolated)
        val rangedOverrides =
          namedOverrides(
            DwfVariables.complicationTypeKey(1) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_RANGED_VALUE),
            DwfVariables.complicationValueKey(1) to RemoteNamedValue.FloatValue(82f),
            DwfVariables.complicationMinKey(1) to RemoteNamedValue.FloatValue(0f),
            DwfVariables.complicationMaxKey(1) to RemoteNamedValue.FloatValue(100f),
            DwfVariables.complicationTextKey(1) to RemoteNamedValue.StringValue("82%"),
            DwfVariables.complicationHasRangedColorsKey(1) to RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationRangedInterpolateKey(1) to RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationColorCountKey(1) to RemoteNamedValue.IntValue(2),
            DwfVariables.complicationColorStopKey(1, 0) to RemoteNamedValue.ColorValue("#FF00E676"),
            DwfVariables.complicationColorStopKey(1, 1) to RemoteNamedValue.ColorValue("#FFFFEA00"),
          )
        val rangedHash = harness.renderAndHash("dwf__raw_watchface", rangedOverrides)
        assertThat(rangedHash).isNotEqualTo(shortTextWithIconHash)

        // 4. Switch slot 1 to WEIGHTED_ELEMENTS with custom weights + colors
        val weightedOverrides =
          namedOverrides(
            DwfVariables.complicationTypeKey(1) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_WEIGHTED_ELEMENTS),
            DwfVariables.complicationHasWeightedElementsKey(1) to
              RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationColorCountKey(1) to RemoteNamedValue.IntValue(3),
            DwfVariables.complicationColorStopKey(1, 0) to RemoteNamedValue.ColorValue("#FF2979FF"),
            DwfVariables.complicationColorStopKey(1, 1) to RemoteNamedValue.ColorValue("#FF00E5FF"),
            DwfVariables.complicationColorStopKey(1, 2) to RemoteNamedValue.ColorValue("#FFFF4081"),
            DwfVariables.complicationWeightKey(1, 0) to RemoteNamedValue.FloatValue(60f),
            DwfVariables.complicationWeightKey(1, 1) to RemoteNamedValue.FloatValue(25f),
            DwfVariables.complicationWeightKey(1, 2) to RemoteNamedValue.FloatValue(15f),
            DwfVariables.complicationWeightKey(1, 3) to RemoteNamedValue.FloatValue(0f),
          )
        val weightedHash = harness.renderAndHash("dwf__raw_watchface", weightedOverrides)
        assertThat(weightedHash).isNotEqualTo(rangedHash)

        // 5. Switch slot 1 to SMALL_IMAGE and slot 2 to PHOTO_IMAGE
        val imageOverrides =
          namedOverrides(
            DwfVariables.complicationTypeKey(1) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_SMALL_IMAGE),
            DwfVariables.complicationHasSmallImageKey(1) to RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationAccentColorKey(1) to RemoteNamedValue.ColorValue("#FF7C4DFF"),
            DwfVariables.complicationImageColorKey(1) to RemoteNamedValue.ColorValue("#FFFFFFFF"),
            DwfVariables.complicationTypeKey(2) to
              RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_PHOTO_IMAGE),
            DwfVariables.complicationHasPhotoImageKey(2) to RemoteNamedValue.BooleanValue(true),
            DwfVariables.complicationAccentColorKey(2) to RemoteNamedValue.ColorValue("#FFFF6D00"),
            DwfVariables.complicationImageColorKey(2) to RemoteNamedValue.ColorValue("#FFFFD180"),
          )
        val imageHash = harness.renderAndHash("dwf__raw_watchface", imageOverrides)
        assertThat(imageHash).isNotEqualTo(weightedHash)

        // 6. Switch both slots to EMPTY + ambient mode
        val emptyAmbientOverrides =
          PreviewOverrides(
            ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT),
            remoteCompose =
              RemoteComposeOverride.Builder()
                .apply {
                  namedValues =
                    mapOf(
                      DwfVariables.complicationTypeKey(1) to
                        RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_EMPTY),
                      DwfVariables.complicationTypeKey(2) to
                        RemoteNamedValue.IntValue(DwfVariables.COMPLICATION_TYPE_EMPTY),
                    )
                }
                .build(),
          )
        val emptyHash = harness.renderAndHash("dwf__raw_watchface", emptyAmbientOverrides)
        assertThat(emptyHash).isNotEqualTo(imageHash)
        val emptyComp = harness.fetchComplications("dwf__raw_watchface")
        assertThat(emptyComp.isAmbient).isTrue()
        assertThat(emptyComp.slots[0].activeState.type).isEqualTo("EMPTY")
        assertThat(emptyComp.slots[1].activeState.type).isEqualTo("EMPTY")

        // 7. Fetch compose/remotecompose live knob state
        val rcData = harness.fetchRemoteCompose("dwf__raw_watchface")
        assertThat(rcData.declarations).isNotEmpty()
        assertThat(rcData.namedValues).containsKey(DwfVariables.complicationTypeKey(1))
      } finally {
        harness.close()
      }
    } finally {
      root.deleteRecursively()
    }
  }

  private fun namedOverrides(vararg pairs: Pair<String, RemoteNamedValue>): PreviewOverrides =
    PreviewOverrides(
      remoteCompose = RemoteComposeOverride.Builder().apply { namedValues = mapOf(*pairs) }.build(),
    )

  private fun collectTestTags(node: ComposeSemanticsNode): Set<String> {
    val out = LinkedHashSet<String>()
    fun walk(n: ComposeSemanticsNode) {
      n.testTag?.let { out.add(it) }
      n.children.forEach(::walk)
    }
    walk(node)
    return out
  }

  private fun findByTestTag(node: ComposeSemanticsNode, tag: String): ComposeSemanticsNode? {
    if (node.testTag == tag) return node
    for (child in node.children) {
      findByTestTag(child, tag)?.let {
        return it
      }
    }
    return null
  }

  private inner class DaemonHarness(config: DwfDaemonConfig) {
    private val clientToDaemon = PipedOutputStream()
    private val daemonIn = PipedInputStream(clientToDaemon, 256 * 1024)
    private val daemonToClient = PipedOutputStream()
    private val clientIn = PipedInputStream(daemonToClient, 256 * 1024)
    private var nextId = 1L

    private val responses = LinkedBlockingQueue<Pair<Long, JsonObject>>()
    private val notifications = LinkedBlockingQueue<Pair<String, JsonObject>>()

    private val daemonThread =
      Thread({ runDwfDaemon(daemonIn, daemonToClient, config) }, "test-dwf-daemon").apply {
        isDaemon = true
        start()
      }

    private val readerThread =
      Thread(
          {
            while (true) {
              val frame = readFrame(clientIn) ?: break
              val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
              val id = obj["id"]?.jsonPrimitive?.longOrNull
              val method = obj["method"]?.jsonPrimitive?.contentOrNull
              if (id != null) {
                responses.offer(id to obj)
              } else if (method != null) {
                notifications.offer(method to (obj["params"] as? JsonObject ?: buildJsonObject {}))
              }
            }
          },
          "test-dwf-client-reader",
        )
        .apply {
          isDaemon = true
          start()
        }

    fun request(method: String, params: JsonObject): JsonElement {
      val id = nextId++
      val req = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
      }
      sendFrame(req.toString())
      val (respId, respObj) =
        responses.poll(30, TimeUnit.SECONDS) ?: error("Timed out waiting for response to $method")
      assertThat(respId).isEqualTo(id)
      respObj["error"]?.let { err -> error("RPC $method failed: $err") }
      return respObj["result"] ?: error("Missing result in $respObj")
    }

    fun renderAndHash(previewId: String, overrides: PreviewOverrides?): String {
      notifications.clear()
      val params = buildJsonObject {
        putJsonArray("previews") { add(JsonPrimitive(previewId)) }
        if (overrides != null) {
          put("overrides", json.encodeToJsonElement(PreviewOverrides.serializer(), overrides))
        }
      }
      val res =
        json.decodeFromJsonElement(RenderNowResult.serializer(), request("renderNow", params))
      assertThat(res.queued).containsExactly(previewId)
      val (notifMethod, notifParams) =
        notifications.poll(30, TimeUnit.SECONDS)
          ?: error("Timed out waiting for renderFinished for $previewId")
      assertThat(notifMethod).isEqualTo("renderFinished")
      val pngPath = notifParams["pngPath"]?.jsonPrimitive?.contentOrNull ?: error("Missing pngPath")
      val pngFile = File(pngPath)
      assertThat(pngFile.isFile).isTrue()
      val bytes = pngFile.readBytes()
      assertThat(bytes.size).isGreaterThan(100)
      return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
      }
    }

    fun fetchComplications(previewId: String): WatchFaceComplicationsPayload {
      val res =
        request(
            "data/fetch",
            buildJsonObject {
              put("previewId", previewId)
              put("kind", WatchFaceComplicationsProduct.KIND)
              put("inline", true)
            },
          )
          .jsonObject
      return json.decodeFromJsonElement(
        WatchFaceComplicationsPayload.serializer(),
        res["payload"] ?: error("Missing payload in $res"),
      )
    }

    fun fetchSemantics(previewId: String): ComposeSemanticsPayload {
      val res =
        request(
            "data/fetch",
            buildJsonObject {
              put("previewId", previewId)
              put("kind", ComposeSemanticsProduct.KIND)
              put("inline", true)
            },
          )
          .jsonObject
      return json.decodeFromJsonElement(
        ComposeSemanticsPayload.serializer(),
        res["payload"] ?: error("Missing payload in $res"),
      )
    }

    fun fetchRemoteCompose(previewId: String): RemoteComposePayload {
      val res =
        request(
            "data/fetch",
            buildJsonObject {
              put("previewId", previewId)
              put("kind", RemoteComposeProduct.KIND)
              put("inline", true)
            },
          )
          .jsonObject
      return json.decodeFromJsonElement(
        RemoteComposePayload.serializer(),
        res["payload"] ?: error("Missing payload in $res"),
      )
    }

    fun close() {
      runCatching {
        sendFrame(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "exit")
          }
            .toString(),
        )
      }
      runCatching { clientToDaemon.close() }
      daemonThread.join(5_000)
      readerThread.join(5_000)
    }

    private fun sendFrame(text: String) {
      val bytes = text.toByteArray(Charsets.UTF_8)
      val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
      clientToDaemon.write(header)
      clientToDaemon.write(bytes)
      clientToDaemon.flush()
    }

    private fun readFrame(stream: InputStream): ByteArray? {
      var contentLength = -1
      val buf = ByteArrayOutputStream(64)
      while (true) {
        val line = readHeaderLine(stream, buf) ?: return null
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
          contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
        }
      }
      if (contentLength < 0) return null
      val payload = ByteArray(contentLength)
      var off = 0
      while (off < contentLength) {
        val n = stream.read(payload, off, contentLength - off)
        if (n < 0) return null
        off += n
      }
      return payload
    }

    private fun readHeaderLine(stream: InputStream, buf: ByteArrayOutputStream): String? {
      buf.reset()
      while (true) {
        val b = stream.read()
        if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
        if (b == '\n'.code) {
          val bytes = buf.toByteArray()
          val end =
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
            else bytes.size
          return String(bytes, 0, end, Charsets.US_ASCII)
        }
        buf.write(b)
      }
    }
  }
}
