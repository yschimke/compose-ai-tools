@file:JvmName("DwfDaemonMain")
@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.rcjvm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.ExtensionInfoDto
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDocumentProduct
import ee.schimke.composeai.data.remotecompose.RemoteComposePayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeProduct
import ee.schimke.composeai.rccreation.dwf.DwfDocument
import ee.schimke.composeai.rccreation.dwf.DwfParser
import ee.schimke.composeai.rccreation.dwf.DwfRenderOptions
import ee.schimke.composeai.rccreation.dwf.DwfSceneNode
import ee.schimke.composeai.rccreation.dwf.DwfUserConfiguration
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rccreation.dwf.captureDwfRemoteDocumentBytes
import ee.schimke.composeai.rccreation.dwf.parseDwfColorArgb
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.skia.EncodedImageFormat

/**
 * Data product kind advertising Declarative Watch Face (`<WatchFace>`) complication slot metadata
 * and live slot state (`get_preview_data(kind = "watchface/complications")`).
 */
public object WatchFaceComplicationsProduct {
  public const val KIND: String = "watchface/complications"
  public const val SCHEMA_VERSION: Int = 1
}

/** Configuration for [runDwfDaemon]. */
public data class DwfDaemonConfig(
  val previewsJson: File?,
  val outputDir: File,
  val resourceDirs: List<File>,
  val moduleProjectDir: File?,
  val daemonVersion: String = "dwf-rcjvm",
) {
  public companion object {
    public fun fromSystemProperties(): DwfDaemonConfig {
      val previewsJsonPath =
        System.getProperty("composeai.daemon.previewsJsonPath")?.takeIf { it.isNotBlank() }
          ?: System.getProperty("composeai.render.manifest")?.takeIf { it.isNotBlank() }
      val previewsJson = previewsJsonPath?.let(::File)
      val moduleProjectDir =
        System.getProperty("composeai.daemon.moduleProjectDir")
          ?.takeIf { it.isNotBlank() }
          ?.let(::File)
      val outputDirPath =
        System.getProperty("composeai.render.outputDir")?.takeIf { it.isNotBlank() }
      val outputDir =
        when {
          outputDirPath != null -> File(outputDirPath)
          previewsJson != null -> File(previewsJson.parentFile ?: File("."), "dwf-renders")
          moduleProjectDir != null -> File(moduleProjectDir, "build/compose-previews/dwf-renders")
          else -> File(System.getProperty("java.io.tmpdir"), "compose-preview-dwf-renders")
        }
      val rawResDirs =
        System.getProperty("composeai.daemon.resDirs")
          .orEmpty()
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map(::File)
      val fallbackResDir = moduleProjectDir?.let { File(it, "src/main/res") }
      val resourceDirs = buildList {
        addAll(rawResDirs)
        if (fallbackResDir != null && fallbackResDir.isDirectory && fallbackResDir !in this) {
          add(fallbackResDir)
        }
      }
        .distinct()
      return DwfDaemonConfig(
        previewsJson = previewsJson,
        outputDir = outputDir,
        resourceDirs = resourceDirs,
        moduleProjectDir = moduleProjectDir,
      )
    }
  }
}

@Serializable
public data class WatchFaceComplicationsPayload(
  val previewId: String,
  val dwfAssetPath: String,
  val widthPx: Int,
  val heightPx: Int,
  val clipShape: String,
  val previewTime: String,
  val isAmbient: Boolean,
  val userConfigurations: List<WatchFaceUserConfigDto> = emptyList(),
  val slots: List<WatchFaceComplicationSlotDto> = emptyList(),
)

@Serializable
public data class WatchFaceUserConfigDto(
  val id: String,
  val kind: String,
  val displayName: String,
  val defaultValue: String,
  val activeValue: String,
  val options: List<String> = emptyList(),
)

@Serializable
public data class WatchFaceSlotBoundsDto(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
)

@Serializable
public data class WatchFaceComplicationSlotDto(
  val slotId: Int,
  val bounds: WatchFaceSlotBoundsDto,
  val boundingShape: String? = null,
  val isCustomizable: Boolean = true,
  val supportedTypes: List<String> = emptyList(),
  val authoredTypes: List<String> = emptyList(),
  val defaultSystemProvider: String? = null,
  val defaultSystemProviderType: String? = null,
  val activeState: WatchFaceActiveComplicationDto,
)

@Serializable
public data class WatchFaceActiveComplicationDto(
  val type: String,
  val typeCode: Int,
  val text: String,
  val title: String,
  val value: Float,
  val min: Float,
  val max: Float,
  val hasMonochromaticImage: Boolean,
  val hasSmallImage: Boolean,
  val hasPhotoImage: Boolean,
  val imageColor: String,
  val accentColor: String,
  val hasRangedValueColors: Boolean,
  val rangedValueColorsInterpolate: Boolean,
  val hasGoalProgressColors: Boolean,
  val goalProgressColorsInterpolate: Boolean,
  val hasWeightedElements: Boolean,
  val colorCount: Int,
  val colors: List<String> = emptyList(),
  val weights: List<Float> = emptyList(),
) {
  internal fun semanticText(): String =
    when (type) {
      "EMPTY" -> ""
      "MONOCHROMATIC_IMAGE",
      "SMALL_IMAGE",
      "PHOTO_IMAGE" -> listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
      "RANGED_VALUE",
      "GOAL_PROGRESS" -> {
        val label = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (label.isNotEmpty()) label else "${formatNumber(value)}/${formatNumber(max)}"
      }
      else -> listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
    }

  internal fun accessibilitySummary(slotId: Int): String =
    when (type) {
      "EMPTY" -> "Complication slot $slotId: EMPTY"
      "RANGED_VALUE",
      "GOAL_PROGRESS" -> {
        val parts = buildList {
          add("Complication slot $slotId ($type)")
          if (title.isNotBlank()) add(title)
          if (text.isNotBlank()) add(text)
          add("value=${formatNumber(value)} [${formatNumber(min)}..${formatNumber(max)}]")
        }
        parts.joinToString(": ")
      }
      "WEIGHTED_ELEMENTS" -> {
        val parts = buildList {
          add("Complication slot $slotId (WEIGHTED_ELEMENTS)")
          if (title.isNotBlank()) add(title)
          if (text.isNotBlank()) add(text)
          if (weights.isNotEmpty()) {
            add("weights=${weights.joinToString(",") { formatNumber(it) }}")
          }
        }
        parts.joinToString(": ")
      }
      else -> {
        val content = listOf(title, text).filter { it.isNotBlank() }.joinToString(" - ")
        if (content.isNotEmpty()) {
          "Complication slot $slotId ($type): $content"
        } else {
          "Complication slot $slotId ($type)"
        }
      }
    }
}

private fun formatNumber(v: Float): String {
  val asInt = v.toInt()
  return if (kotlin.math.abs(v - asInt.toFloat()) < 0.001f) asInt.toString()
  else "%.1f".format(java.util.Locale.ROOT, v)
}

/**
 * Entrypoint for `composePreviewDaemonStart` on Declarative Watch Face (`<WatchFace>`) modules.
 * Speaks LSP `Content-Length`-framed JSON-RPC 2.0 over `stdin`/`stdout`, keeping `System.out`
 * redirected to `System.err` so library diagnostics never corrupt wire frames.
 */
public fun main(args: Array<String>) {
  val rpcIn = System.`in`
  val rpcOut = System.out
  System.setOut(System.err)
  val config = DwfDaemonConfig.fromSystemProperties()
  runDwfDaemon(rpcIn, rpcOut, config)
}

public fun runDwfDaemon(input: InputStream, output: OutputStream, config: DwfDaemonConfig) {
  DwfDaemonSession(input, output, config).loop()
}

private class DwfDaemonSession(
  private val input: InputStream,
  private val output: OutputStream,
  private val config: DwfDaemonConfig,
) {
  private val running = AtomicBoolean(true)
  private val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }
  private val payloadJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val advertisedDataProducts =
    listOf(
      DataProductCapability(
        kind = RemoteComposeProduct.KIND,
        schemaVersion = RemoteComposeProduct.SCHEMA_VERSION,
        transport = DataProductTransport.BOTH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      ),
      DataProductCapability(
        kind = RemoteComposeDocumentProduct.KIND,
        schemaVersion = RemoteComposeDocumentProduct.SCHEMA_VERSION,
        transport = DataProductTransport.BOTH,
        attachable = false,
        fetchable = true,
        requiresRerender = false,
      ),
      DataProductCapability(
        kind = ComposeSemanticsProduct.KIND,
        schemaVersion = ComposeSemanticsProduct.SCHEMA_VERSION,
        transport = DataProductTransport.BOTH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      ),
      DataProductCapability(
        kind = WatchFaceComplicationsProduct.KIND,
        schemaVersion = WatchFaceComplicationsProduct.SCHEMA_VERSION,
        transport = DataProductTransport.BOTH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      ),
    )

  private val supportedOverrides =
    listOf("widthPx", "heightPx", "density", "device", "ambient", "remoteCompose")

  private val knownExtensionIds =
    setOf(
      "compose/semantics",
      "compose-semantics",
      "compose/remotecompose",
      "remotecompose",
      "watchface/complications",
      "watchface-complications",
      "ambient",
    )

  private val enabledExtensions =
    ConcurrentHashMap.newKeySet<String>().apply { addAll(knownExtensionIds) }
  private val globalAttachProducts = ConcurrentHashMap.newKeySet<String>()
  private val subscribedProducts = ConcurrentHashMap.newKeySet<Pair<String, String>>()

  private data class RawSlotMeta(
    val slotId: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val boundingShape: String?,
    val isCustomizable: Boolean,
    val supportedTypes: List<String>,
    val authoredTypes: List<String>,
    val defaultSystemProvider: String?,
    val defaultSystemProviderType: String?,
  )

  private data class CompiledWatchFace(
    val entry: DwfPreviewEntryDto,
    val xmlFile: File,
    val xmlLastModified: Long,
    val fakes: DwfFakesConfig?,
    val fakesLastModified: Long,
    val doc: DwfDocument,
    val rcBytes: ByteArray,
    val declarationsPayload: RemoteComposeDeclarationsPayload,
    val defaultNamedValues: Map<String, RemoteNamedValue>,
    val slots: List<RawSlotMeta>,
  )

  private data class RenderedWatchFaceState(
    val pngFile: File,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveNamedValues: Map<String, RemoteNamedValue>,
    val unusedSeedKeys: List<String>,
    val semanticsPayload: ComposeSemanticsPayload,
    val complicationsPayload: WatchFaceComplicationsPayload,
    val remoteComposePayload: RemoteComposePayload,
  )

  private val compiledCache = ConcurrentHashMap<String, CompiledWatchFace>()
  private val latestRenders = ConcurrentHashMap<String, RenderedWatchFaceState>()
  @Volatile private var manifestLastModified: Long = -1L
  private val manifestEntries = ConcurrentHashMap<String, DwfPreviewEntryDto>()

  fun loop() {
    try {
      while (running.get()) {
        val frame = readFrame(input) ?: break
        val obj =
          runCatching { wireJson.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: continue
        val responseId = obj["id"]?.jsonPrimitive?.longOrNull
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val params = obj["params"] as? JsonObject
        if (responseId != null && method != null) {
          handleRequest(responseId, method, params)
        } else if (method != null) {
          handleNotification(method, params)
        }
      }
    } catch (_: IOException) {
      // Stream closed by supervisor.
    }
  }

  private fun refreshManifestIfNeeded(force: Boolean = false): Int {
    val file = config.previewsJson ?: return manifestEntries.size
    if (!file.isFile) return 0
    val mtime = file.lastModified()
    if (!force && mtime == manifestLastModified && manifestEntries.isNotEmpty()) {
      return manifestEntries.size
    }
    val parsed =
      runCatching { dwfJson.decodeFromString<DwfPreviewManifestDto>(file.readText()) }.getOrNull()
        ?: return manifestEntries.size
    val dwfPreviews = parsed.previews.filter { it.params.dwfAssetPath != null }
    manifestEntries.clear()
    for (entry in dwfPreviews) {
      manifestEntries[entry.id] = entry
    }
    manifestLastModified = mtime
    return dwfPreviews.size
  }

  private fun compileOrGet(previewId: String): CompiledWatchFace? {
    refreshManifestIfNeeded()
    val entry = manifestEntries[previewId] ?: return null
    val relPath = entry.params.dwfAssetPath ?: return null
    val previewsRoot =
      config.outputDir.parentFile ?: config.previewsJson?.parentFile ?: config.outputDir
    val xmlFile = resolveDwfXmlFile(relPath, config.resourceDirs, previewsRoot) ?: return null
    val sidecarFile =
      File(xmlFile.parentFile ?: File("."), "${xmlFile.nameWithoutExtension}.fakes.json")
    val xmlMtime = xmlFile.lastModified()
    val fakesMtime = if (sidecarFile.isFile) sidecarFile.lastModified() else 0L

    val existing = compiledCache[previewId]
    if (
      existing != null &&
        existing.xmlFile == xmlFile &&
        existing.xmlLastModified == xmlMtime &&
        existing.fakesLastModified == fakesMtime
    ) {
      return existing
    }

    val resolver = DwfResourceDirectoryResolver(config.resourceDirs)
    val fakes = findSidecarFakes(xmlFile)
    val doc = DwfParser.parse(xmlFile.readText(), resolver)
    val options = fakes?.toRenderOptions() ?: DwfRenderOptions()
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, options) }
    val declarations = extractDwfDeclarationsPayload(doc, rcBytes)
    val defaultMap = LinkedHashMap<String, RemoteNamedValue>()
    for (decl in declarations.declarations) {
      defaultMap[decl.name] = decl.default
    }
    val slots = collectRawSlots(doc.scene.children)
    val compiled =
      CompiledWatchFace(
        entry = entry,
        xmlFile = xmlFile,
        xmlLastModified = xmlMtime,
        fakes = fakes,
        fakesLastModified = fakesMtime,
        doc = doc,
        rcBytes = rcBytes,
        declarationsPayload = declarations,
        defaultNamedValues = defaultMap,
        slots = slots,
      )
    compiledCache[previewId] = compiled
    return compiled
  }

  private fun collectRawSlots(rootNodes: List<DwfSceneNode>): List<RawSlotMeta> {
    val found = LinkedHashMap<Int, RawSlotMeta>()
    fun walk(nodes: List<DwfSceneNode>, offsetX: Float, offsetY: Float) {
      for (node in nodes) {
        when (node) {
          is DwfSceneNode.ComplicationSlot -> {
            found.putIfAbsent(
              node.slotId,
              RawSlotMeta(
                slotId = node.slotId,
                x = offsetX + node.geometry.x,
                y = offsetY + node.geometry.y,
                width = node.geometry.width,
                height = node.geometry.height,
                boundingShape = node.boundingShape?.kind,
                isCustomizable = node.isCustomizable,
                supportedTypes = node.supportedTypes,
                authoredTypes = node.complications.map { it.type }.distinct(),
                defaultSystemProvider = node.defaultSystemProvider,
                defaultSystemProviderType = node.defaultSystemProviderType,
              ),
            )
          }
          is DwfSceneNode.Group ->
            walk(node.children, offsetX + node.geometry.x, offsetY + node.geometry.y)
          is DwfSceneNode.AnalogClock ->
            walk(node.children, offsetX + node.geometry.x, offsetY + node.geometry.y)
          is DwfSceneNode.Condition -> node.branches.forEach { walk(it.children, offsetX, offsetY) }
          is DwfSceneNode.BooleanConfigurationSwitch -> {
            walk(node.trueChildren, offsetX, offsetY)
            walk(node.falseChildren, offsetX, offsetY)
          }
          is DwfSceneNode.ListConfigurationSwitch ->
            node.options.forEach { walk(it.children, offsetX, offsetY) }
          else -> {}
        }
      }
    }
    walk(rootNodes, 0f, 0f)
    return found.values.sortedBy { it.slotId }
  }

  private fun renderWatchFace(
    compiled: CompiledWatchFace,
    overrides: PreviewOverrides?,
  ): RenderedWatchFaceState {
    val nativeW = compiled.doc.width.coerceAtLeast(1)
    val nativeH = compiled.doc.height.coerceAtLeast(1)
    val (deviceW, deviceH) = parseDeviceSpecPx(overrides?.device)
    val widthPx = (overrides?.widthPx ?: deviceW ?: nativeW).coerceAtLeast(1)
    val heightPx = (overrides?.heightPx ?: deviceH ?: nativeH).coerceAtLeast(1)

    val effectiveNamed = LinkedHashMap<String, RemoteNamedValue>(compiled.defaultNamedValues)
    if (overrides?.ambient != null) {
      val amb = overrides.ambient?.state == AmbientStateOverride.AMBIENT
      effectiveNamed[DwfVariables.IS_AMBIENT] = RemoteNamedValue.BooleanValue(amb)
    }
    val declaredNames = compiled.defaultNamedValues.keys
    val unusedSeeds = mutableListOf<String>()
    for ((rawKey, value) in overrides?.remoteCompose?.namedValues.orEmpty()) {
      val key = rawKey.removePrefix("USER:")
      effectiveNamed[key] = value
      if (
        key !in declaredNames &&
          !key.startsWith("dwf.") // DWF slots/configs that a simpler watch face didn't reference
      ) {
        unusedSeeds += key
      }
    }

    val rcSeeds = LinkedHashMap<String, RcNamedValue>()
    for ((k, v) in effectiveNamed) {
      toRcNamedValue(v)?.let { rcSeeds[k] = it }
    }

    val complicationsPayload =
      buildComplicationsPayload(compiled, widthPx, heightPx, effectiveNamed)
    val remotePayload =
      RemoteComposePayload(
          namedValues = effectiveNamed,
          hostActions = emptyList(),
          profile = overrides?.remoteCompose?.profile,
          declarations = compiled.declarationsPayload.declarations,
        )
        .apply {
          if (unusedSeeds.isNotEmpty()) {
            warnings =
              listOf("Unused RemoteCompose namedValues not declared by watch face: $unusedSeeds")
          }
        }

    val (hour, minute, second) =
      parsePreviewHms(compiled.fakes?.previewTime ?: compiled.doc.previewTime)
    val year = compiled.fakes?.year ?: 2026
    val month = compiled.fakes?.month ?: 11
    val dayOfMonth = compiled.fakes?.dayOfMonth ?: 18
    val date = runCatching {
      LocalDate.of(year, month, dayOfMonth)
    }
      .getOrElse { LocalDate.of(2026, 11, 18) }
    val fixedTime =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = 1_763_460_630_000L

        override fun snapshot(epochMillis: Long): RcTimeSnapshot =
          RcTimeSnapshot(
            epochMillis = epochMillis,
            year = date.year,
            month = date.monthValue,
            dayOfMonth = date.dayOfMonth,
            dayOfYear = date.dayOfYear,
            hour = hour,
            minute = minute,
            second = second,
            isoDayOfWeek = date.dayOfWeek.value,
            offsetSeconds = 0,
          )
      }

    val sx = widthPx.toFloat() / nativeW.toFloat()
    val sy = heightPx.toFloat() / nativeH.toFloat()
    val typefaces = dwfTypefaces()
    val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(1f))
    val pngBytes: ByteArray
    val semanticsPayload: ComposeSemanticsPayload
    try {
      val stateMap = mutableStateMapOf<String, RcNamedValue>().apply { putAll(rcSeeds) }
      scene.setContent {
        CompositionLocalProvider(LocalRcTimeSource provides fixedTime) {
          Box(
            Modifier.fillMaxSize().semantics {
              testTag = "watchface_root"
              contentDescription =
                "WatchFace ${compiled.entry.params.name ?: compiled.entry.id} (${widthPx}x${heightPx})"
            },
          ) {
            if (typefaces == null) {
              RcComposePlayer(
                bytes = compiled.rcBytes,
                modifier = Modifier.fillMaxSize(),
                namedValues = stateMap,
              )
            } else {
              RcComposePlayer(
                bytes = compiled.rcBytes,
                modifier = Modifier.fillMaxSize(),
                namedValues = stateMap,
                typefaces = typefaces,
              )
            }
            for (slotDto in complicationsPayload.slots) {
              val b = slotDto.bounds
              Box(
                Modifier.offset((b.x * sx).dp, (b.y * sy).dp)
                  .size((b.width * sx).coerceAtLeast(1f).dp, (b.height * sy).coerceAtLeast(1f).dp)
                  .semantics(mergeDescendants = true) {
                    testTag = "complication_slot_${slotDto.slotId}"
                    contentDescription = slotDto.activeState.accessibilitySummary(slotDto.slotId)
                    val displayStr = slotDto.activeState.semanticText()
                    if (displayStr.isNotEmpty()) {
                      text = AnnotatedString(displayStr)
                    }
                  },
              )
            }
          }
        }
      }
      val image = scene.render(0L)
      val encoded =
        image.encodeToData(EncodedImageFormat.PNG)
          ?: error("skiko could not encode rendered DWF watch face to PNG")
      pngBytes = encoded.bytes
      val semanticsRoot =
        scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
          ?: error("DwfDaemonMain found no Compose semantics owner")
      semanticsPayload = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)
    } finally {
      scene.close()
    }

    config.outputDir.mkdirs()
    val pngFile = resolveOutputPngFile(compiled.entry, overrides)
    pngFile.parentFile?.mkdirs()
    pngFile.writeBytes(pngBytes)

    if (overrides == null) {
      val stem = pngFile.nameWithoutExtension
      val dir = pngFile.parentFile ?: config.outputDir
      File(dir, "$stem.rc").writeBytes(compiled.rcBytes)
      File(dir, "$stem.remotecompose.json")
        .writeText(
          payloadJson.encodeToString(
            RemoteComposeDeclarationsPayload.serializer(),
            compiled.declarationsPayload,
          ),
        )
    }

    val state =
      RenderedWatchFaceState(
        pngFile = pngFile,
        widthPx = widthPx,
        heightPx = heightPx,
        effectiveNamedValues = effectiveNamed,
        unusedSeedKeys = unusedSeeds,
        semanticsPayload = semanticsPayload,
        complicationsPayload = complicationsPayload,
        remoteComposePayload = remotePayload,
      )
    latestRenders[compiled.entry.id] = state
    return state
  }

  private fun resolveOutputPngFile(
    entry: DwfPreviewEntryDto,
    overrides: PreviewOverrides?,
  ): File {
    if (overrides == null) {
      val relOut = entry.captures.firstOrNull()?.renderOutput?.trim().orEmpty()
      if (relOut.isNotEmpty()) {
        val relInOutputDir =
          if (relOut.startsWith("${config.outputDir.name}/")) {
            relOut.removePrefix("${config.outputDir.name}/")
          } else {
            File(relOut).name
          }
        return File(config.outputDir, relInOutputDir)
      }
      return File(config.outputDir, "${entry.id}.png")
    }
    val serializedOverrides =
      wireJson.encodeToString(PreviewOverrides.serializer(), overrides).toByteArray(Charsets.UTF_8)
    val digest =
      MessageDigest.getInstance("SHA-256").digest(serializedOverrides).take(6).joinToString("") {
        "%02x".format(it)
      }
    return File(config.outputDir, "${entry.id}-$digest.png")
  }

  private fun buildComplicationsPayload(
    compiled: CompiledWatchFace,
    widthPx: Int,
    heightPx: Int,
    namedValues: Map<String, RemoteNamedValue>,
  ): WatchFaceComplicationsPayload {
    fun strVal(key: String, fallback: String = ""): String =
      (namedValues[key] as? RemoteNamedValue.StringValue)?.value ?: fallback

    fun floatVal(key: String, fallback: Float = 0f): Float =
      when (val v = namedValues[key]) {
        is RemoteNamedValue.FloatValue -> v.value
        is RemoteNamedValue.IntValue -> v.value.toFloat()
        is RemoteNamedValue.DpValue -> v.value
        else -> fallback
      }

    fun intVal(key: String, fallback: Int = 0): Int =
      when (val v = namedValues[key]) {
        is RemoteNamedValue.IntValue -> v.value
        is RemoteNamedValue.FloatValue -> v.value.toInt()
        is RemoteNamedValue.BooleanValue -> if (v.value) 1 else 0
        else -> fallback
      }

    fun boolVal(key: String, fallback: Boolean = false): Boolean =
      when (val v = namedValues[key]) {
        is RemoteNamedValue.BooleanValue -> v.value
        is RemoteNamedValue.IntValue -> v.value != 0
        else -> fallback
      }

    fun colorVal(key: String, fallback: String): String =
      (namedValues[key] as? RemoteNamedValue.ColorValue)?.argb ?: fallback

    val isAmbient = boolVal(DwfVariables.IS_AMBIENT, compiled.fakes?.isAmbient ?: false)

    val configs =
      compiled.doc.userConfigurations.map { cfg ->
        when (cfg) {
          is DwfUserConfiguration.BooleanConfiguration -> {
            val active = boolVal(DwfVariables.configKey(cfg.id), cfg.defaultValue)
            WatchFaceUserConfigDto(
              id = cfg.id,
              kind = "BOOLEAN",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.defaultValue.toString(),
              activeValue = active.toString(),
            )
          }
          is DwfUserConfiguration.ListConfiguration -> {
            val defaultOpt = cfg.defaultValue ?: cfg.options.firstOrNull()?.id.orEmpty()
            val defaultIdx =
              cfg.options.indexOfFirst { it.id == defaultOpt }.takeIf { it >= 0 } ?: 0
            val activeIdx = intVal(DwfVariables.configKey(cfg.id), defaultIdx)
            val activeId = cfg.options.getOrNull(activeIdx)?.id ?: activeIdx.toString()
            WatchFaceUserConfigDto(
              id = cfg.id,
              kind = "LIST",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = defaultOpt,
              activeValue = activeId,
              options = cfg.options.map { it.id },
            )
          }
          is DwfUserConfiguration.ColorConfiguration -> {
            val defaultOpt = cfg.defaultValue ?: cfg.options.firstOrNull()?.id.orEmpty()
            val defaultIdx =
              cfg.options.indexOfFirst { it.id == defaultOpt }.takeIf { it >= 0 } ?: 0
            val activeIdx = intVal(DwfVariables.configKey(cfg.id), defaultIdx)
            val activeId = cfg.options.getOrNull(activeIdx)?.id ?: activeIdx.toString()
            WatchFaceUserConfigDto(
              id = cfg.id,
              kind = "COLOR",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = defaultOpt,
              activeValue = activeId,
              options = cfg.options.map { it.id },
            )
          }
          is DwfUserConfiguration.PhotosConfiguration ->
            WatchFaceUserConfigDto(
              id = cfg.id,
              kind = "PHOTOS",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.configType,
              activeValue = cfg.configType,
            )
        }
      }

    val slotDtos =
      compiled.slots.map { slot ->
        val sid = slot.slotId
        val seed = compiled.fakes?.complications?.get(sid)?.toSeed()
        val fallbackTypeStr =
          seed?.type
            ?: slot.defaultSystemProviderType
            ?: slot.supportedTypes.firstOrNull()
            ?: "SHORT_TEXT"
        val fallbackTypeCode = DwfVariables.complicationTypeCode(fallbackTypeStr)
        val typeCode = intVal(DwfVariables.complicationTypeKey(sid), fallbackTypeCode)
        val typeName = DwfVariables.complicationTypeName(typeCode)
        val colorCount =
          intVal(DwfVariables.complicationColorCountKey(sid), 3)
            .coerceIn(1, DwfVariables.MAX_COMPLICATION_COLOR_STOPS)
        val hasRangedColors = boolVal(DwfVariables.complicationHasRangedColorsKey(sid), false)
        val hasGoalColors = boolVal(DwfVariables.complicationHasGoalColorsKey(sid), false)
        val hasWeighted = boolVal(DwfVariables.complicationHasWeightedElementsKey(sid), false)
        val stopDefaults = listOf("#FF4285F4", "#FF34A853", "#FFFBBC05", "#FFEA4335")
        val weightDefaults = listOf(45f, 35f, 20f, 0f)
        val colors =
          (0 until colorCount).map { idx ->
            colorVal(
              DwfVariables.complicationColorStopKey(sid, idx),
              stopDefaults.getOrElse(idx) { "#FFFFFFFF" },
            )
          }
        val weights =
          (0 until colorCount).map { idx ->
            floatVal(
              DwfVariables.complicationWeightKey(sid, idx),
              weightDefaults.getOrElse(idx) { 0f },
            )
          }

        val active =
          WatchFaceActiveComplicationDto(
            type = typeName,
            typeCode = typeCode,
            text = strVal(DwfVariables.complicationTextKey(sid), seed?.text.orEmpty()),
            title = strVal(DwfVariables.complicationTitleKey(sid), seed?.title.orEmpty()),
            value = floatVal(DwfVariables.complicationValueKey(sid), seed?.value ?: 0f),
            min = floatVal(DwfVariables.complicationMinKey(sid), seed?.min ?: 0f),
            max = floatVal(DwfVariables.complicationMaxKey(sid), seed?.max ?: 100f),
            hasMonochromaticImage =
              boolVal(
                DwfVariables.complicationHasMonoImageKey(sid),
                !seed?.monochromaticImage.isNullOrBlank(),
              ),
            hasSmallImage =
              boolVal(
                DwfVariables.complicationHasSmallImageKey(sid),
                !seed?.smallImage.isNullOrBlank(),
              ),
            hasPhotoImage =
              boolVal(
                DwfVariables.complicationHasPhotoImageKey(sid),
                !seed?.photoImage.isNullOrBlank(),
              ),
            imageColor = colorVal(DwfVariables.complicationImageColorKey(sid), "#FFFFFFFF"),
            accentColor = colorVal(DwfVariables.complicationAccentColorKey(sid), "#FF1976D2"),
            hasRangedValueColors = hasRangedColors,
            rangedValueColorsInterpolate =
              boolVal(DwfVariables.complicationRangedInterpolateKey(sid), false),
            hasGoalProgressColors = hasGoalColors,
            goalProgressColorsInterpolate =
              boolVal(DwfVariables.complicationGoalInterpolateKey(sid), false),
            hasWeightedElements = hasWeighted,
            colorCount = colorCount,
            colors = if (hasRangedColors || hasGoalColors || hasWeighted) colors else emptyList(),
            weights = if (hasWeighted) weights else emptyList(),
          )

        WatchFaceComplicationSlotDto(
          slotId = sid,
          bounds = WatchFaceSlotBoundsDto(slot.x, slot.y, slot.width, slot.height),
          boundingShape = slot.boundingShape,
          isCustomizable = slot.isCustomizable,
          supportedTypes = slot.supportedTypes,
          authoredTypes = slot.authoredTypes,
          defaultSystemProvider = slot.defaultSystemProvider,
          defaultSystemProviderType = slot.defaultSystemProviderType,
          activeState = active,
        )
      }

    return WatchFaceComplicationsPayload(
      previewId = compiled.entry.id,
      dwfAssetPath = compiled.entry.params.dwfAssetPath.orEmpty(),
      widthPx = widthPx,
      heightPx = heightPx,
      clipShape = compiled.doc.clipShape.name,
      previewTime = (compiled.fakes?.previewTime ?: compiled.doc.previewTime) ?: "10:10:30",
      isAmbient = isAmbient,
      userConfigurations = configs,
      slots = slotDtos,
    )
  }

  private fun parseDeviceSpecPx(device: String?): Pair<Int?, Int?> {
    if (device.isNullOrBlank() || !device.startsWith("spec:")) return null to null
    val tokens =
      device
        .removePrefix("spec:")
        .split(',')
        .mapNotNull { part ->
          val eq = part.indexOf('=')
          if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }
        .toMap()
    val dpi = tokens["dpi"]?.toFloatOrNull() ?: 160f
    val scale = (dpi / 160f).coerceAtLeast(0.5f)
    fun dimPx(raw: String?): Int? {
      if (raw == null) return null
      return when {
        raw.endsWith("px") -> raw.removeSuffix("px").toFloatOrNull()?.toInt()
        raw.endsWith("dp") -> (raw.removeSuffix("dp").toFloatOrNull()?.times(scale))?.toInt()
        else -> raw.toFloatOrNull()?.toInt()
      }
    }
    return dimPx(tokens["width"]) to dimPx(tokens["height"])
  }

  private fun toRcNamedValue(value: RemoteNamedValue): RcNamedValue? =
    when (value) {
      is RemoteNamedValue.StringValue -> RcNamedValue.Text(value.value)
      is RemoteNamedValue.FloatValue -> RcNamedValue.FloatValue(value.value)
      is RemoteNamedValue.IntValue -> RcNamedValue.Integer(value.value)
      is RemoteNamedValue.BooleanValue -> RcNamedValue.Integer(if (value.value) 1 else 0)
      is RemoteNamedValue.DpValue -> RcNamedValue.FloatValue(value.value)
      is RemoteNamedValue.ColorValue ->
        parseDwfColorArgb(value.argb)?.let { RcNamedValue.Color(it) }
    }

  private fun handleRequest(id: Long, method: String, params: JsonObject?) {
    when (method) {
      "initialize" -> {
        val optionsObj = params?.get("options") as? JsonObject
        val attachArr = optionsObj?.get("attachDataProducts") as? JsonArray
        attachArr
          ?.mapNotNull { it.jsonPrimitive.contentOrNull }
          ?.let { kinds -> globalAttachProducts.addAll(kinds) }
        val count = refreshManifestIfNeeded(force = true)
        val result =
          InitializeResult(
            protocolVersion = 2,
            daemonVersion = config.daemonVersion,
            pid = ProcessHandle.current().pid(),
            capabilities =
              ServerCapabilities(
                incrementalDiscovery = true,
                sandboxRecycle = false,
                leakDetection = emptyList(),
                dataProducts = advertisedDataProducts,
                supportedOverrides = supportedOverrides,
              ),
            classpathFingerprint = "dwf-rcjvm",
            manifest =
              Manifest(
                path = config.previewsJson?.absolutePath.orEmpty(),
                previewCount = count,
              ),
          )
        sendResponse(id, wireJson.encodeToJsonElement(InitializeResult.serializer(), result))
      }
      "extensions/list" -> {
        val listResult =
          ExtensionsListResult(
            extensions =
              listOf(
                ExtensionInfoDto(
                  id = "compose/semantics",
                  displayName = "Compose Semantics",
                  publiclyEnabled = true,
                  active = true,
                  dataProductKinds = listOf(ComposeSemanticsProduct.KIND),
                ),
                ExtensionInfoDto(
                  id = "compose/remotecompose",
                  displayName = "Remote Compose",
                  publiclyEnabled = true,
                  active = true,
                  dataProductKinds =
                    listOf(RemoteComposeProduct.KIND, RemoteComposeDocumentProduct.KIND),
                ),
                ExtensionInfoDto(
                  id = "watchface/complications",
                  displayName = "Watch Face Complications",
                  publiclyEnabled = true,
                  active = true,
                  dataProductKinds = listOf(WatchFaceComplicationsProduct.KIND),
                ),
              ),
          )
        sendResponse(
          id,
          wireJson.encodeToJsonElement(ExtensionsListResult.serializer(), listResult),
        )
      }
      "extensions/enable" -> {
        val requested =
          (params?.get("ids") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val newly = mutableListOf<String>()
        val already = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (extId in requested) {
          if (extId in knownExtensionIds) {
            if (enabledExtensions.add(extId)) newly += extId else already += extId
          } else {
            unknown += extId
          }
        }
        val result =
          ExtensionsEnableResult.Builder()
            .apply {
              newlyEnabled = newly
              alreadyEnabled = already
              this.unknown = unknown
              dataProducts = advertisedDataProducts
            }
            .build()
        sendResponse(id, wireJson.encodeToJsonElement(ExtensionsEnableResult.serializer(), result))
      }
      "renderNow" -> {
        val previews =
          (params?.get("previews") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val overrides =
          params
            ?.get("overrides")
            ?.takeUnless { it is JsonNull }
            ?.let {
              runCatching { wireJson.decodeFromJsonElement(PreviewOverrides.serializer(), it) }
                .getOrElse { err ->
                  sendError(id, -32602, "Invalid PreviewOverrides: ${err.message}")
                  return
                }
            }
        refreshManifestIfNeeded()
        val queued = mutableListOf<String>()
        val rejected = mutableListOf<RejectedRender>()
        for (pid in previews) {
          if (manifestEntries.containsKey(pid)) {
            queued += pid
          } else {
            rejected += RejectedRender(pid, "no manifest entry for '$pid'")
          }
        }
        val response = RenderNowResult(queued = queued, rejected = rejected)
        sendResponse(id, wireJson.encodeToJsonElement(RenderNowResult.serializer(), response))

        for (pid in queued) {
          val startNs = System.nanoTime()
          runCatching {
            val compiled =
              compileOrGet(pid) ?: error("DWF XML asset could not be resolved for '$pid'")
            val rendered = renderWatchFace(compiled, overrides)
            val tookMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val attachKinds =
              advertisedDataProducts
                .filter { it.attachable }
                .map { it.kind }
                .filter { kind ->
                  kind in globalAttachProducts || (pid to kind) in subscribedProducts
                }
            val attachments = attachKinds.mapNotNull { kind ->
              buildAttachmentJson(compiled, rendered, kind)
            }
            val notif = buildJsonObject {
              put("id", pid)
              put("pngPath", rendered.pngFile.absolutePath)
              put("tookMs", tookMs)
              if (attachments.isNotEmpty()) {
                putJsonArray("dataProducts") { attachments.forEach { add(it) } }
              }
            }
            sendNotification("renderFinished", notif)
          }
            .onFailure { err ->
              val tookMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
              val failNotif = buildJsonObject {
                put("id", pid)
                put("message", err.message ?: err::class.simpleName.orEmpty())
                put("tookMs", tookMs)
              }
              sendNotification("renderFailed", failNotif)
            }
        }
      }
      "data/fetch" -> handleDataFetch(id, params)
      "data/subscribe",
      "data/unsubscribe" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull.orEmpty()
        val cap = advertisedDataProducts.firstOrNull { it.kind == kind }
        if (cap == null || !cap.attachable) {
          sendError(
            id,
            -32020,
            if (cap == null) "DataProductUnknown: kind not advertised: $kind"
            else "DataProductUnknown: kind '$kind' is not attachable",
          )
        } else {
          if (method == "data/subscribe") subscribedProducts.add(previewId to kind)
          else subscribedProducts.remove(previewId to kind)
          sendResponse(id, buildJsonObject { put("ok", true) })
        }
      }
      "shutdown" -> {
        sendResponse(id, JsonNull)
      }
      else -> {
        sendError(id, -32601, "method not found: $method")
      }
    }
  }

  private fun handleDataFetch(id: Long, params: JsonObject?) {
    val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull.orEmpty()
    val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull.orEmpty()
    val cap = advertisedDataProducts.firstOrNull { it.kind == kind }
    if (cap == null) {
      sendError(id, -32020, "DataProductUnknown: kind not advertised: $kind")
      return
    }
    val compiled = compileOrGet(previewId)
    if (compiled == null) {
      sendError(id, -32021, "DataProductNotAvailable: unknown preview '$previewId'")
      return
    }
    val inline =
      when (val raw = params?.get("inline")?.jsonPrimitive?.contentOrNull) {
        null -> false
        else -> raw == "true"
      }
    val perKindParams = params?.get("params") as? JsonObject
    val explicitOverrides =
      perKindParams
        ?.get(DataFetchParams.PARAM_OVERRIDES)
        ?.takeUnless { it is JsonNull }
        ?.let {
          runCatching { wireJson.decodeFromJsonElement(PreviewOverrides.serializer(), it) }
            .getOrNull()
        }
    val forceRerender =
      perKindParams?.get(DataFetchParams.PARAM_FORCE_RERENDER)?.jsonPrimitive?.contentOrNull ==
        "true"

    val rendered = runCatching {
      if (explicitOverrides != null || forceRerender) {
        renderWatchFace(compiled, explicitOverrides)
      } else {
        latestRenders[previewId] ?: renderWatchFace(compiled, null)
      }
    }
      .getOrElse { err ->
        sendError(id, -32022, "DataProductFetchFailed: ${err.message}")
        return
      }

    val payloadElement: JsonElement =
      when (kind) {
        RemoteComposeProduct.KIND ->
          payloadJson.encodeToJsonElement(
            RemoteComposePayload.serializer(),
            rendered.remoteComposePayload,
          )
        RemoteComposeDocumentProduct.KIND ->
          payloadJson.encodeToJsonElement(
            RemoteComposeDocumentPayload.serializer(),
            RemoteComposeDocumentPayload(Base64.getEncoder().encodeToString(compiled.rcBytes)),
          )
        ComposeSemanticsProduct.KIND ->
          payloadJson.encodeToJsonElement(
            ComposeSemanticsPayload.serializer(),
            rendered.semanticsPayload,
          )
        WatchFaceComplicationsProduct.KIND ->
          payloadJson.encodeToJsonElement(
            WatchFaceComplicationsPayload.serializer(),
            rendered.complicationsPayload,
          )
        else -> {
          sendError(id, -32020, "DataProductUnknown: $kind")
          return
        }
      }

    val sidecarFile =
      File(config.outputDir, "$previewId.${kind.replace('/', '_')}.json").also { file ->
        file.parentFile?.mkdirs()
        file.writeText(payloadElement.toString())
      }

    val response = buildJsonObject {
      put("kind", cap.kind)
      put("schemaVersion", cap.schemaVersion)
      if (inline) {
        put("payload", payloadElement)
      }
      put("path", sidecarFile.absolutePath)
    }
    sendResponse(id, response)
  }

  private fun buildAttachmentJson(
    compiled: CompiledWatchFace,
    rendered: RenderedWatchFaceState,
    kind: String,
  ): JsonObject? {
    val cap = advertisedDataProducts.firstOrNull { it.kind == kind && it.attachable } ?: return null
    val payloadElement: JsonElement =
      when (kind) {
        RemoteComposeProduct.KIND ->
          payloadJson.encodeToJsonElement(
            RemoteComposePayload.serializer(),
            rendered.remoteComposePayload,
          )
        ComposeSemanticsProduct.KIND ->
          payloadJson.encodeToJsonElement(
            ComposeSemanticsPayload.serializer(),
            rendered.semanticsPayload,
          )
        WatchFaceComplicationsProduct.KIND ->
          payloadJson.encodeToJsonElement(
            WatchFaceComplicationsPayload.serializer(),
            rendered.complicationsPayload,
          )
        else -> return null
      }
    return buildJsonObject {
      put("kind", cap.kind)
      put("schemaVersion", cap.schemaVersion)
      put("payload", payloadElement)
    }
  }

  private fun handleNotification(method: String, params: JsonObject?) {
    when (method) {
      "initialized" -> {}
      "fileChanged" -> {
        refreshManifestIfNeeded(force = true)
        compiledCache.clear()
        val rawEntries =
          config.previewsJson
            ?.takeIf(File::isFile)
            ?.let { file ->
              runCatching {
                (Json.parseToJsonElement(file.readText()) as? JsonObject)?.get("previews")
                  as? JsonArray
              }
                .getOrNull()
            }
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (rawEntries.isNotEmpty()) {
          val notif = buildJsonObject {
            put("added", JsonArray(emptyList()))
            put("removed", JsonArray(emptyList()))
            put("changed", JsonArray(rawEntries))
            put("totalPreviews", JsonPrimitive(rawEntries.size))
          }
          sendNotification("discoveryUpdated", notif)
        }
      }
      "setVisible",
      "setFocus" -> {}
      "exit" -> running.set(false)
      else -> {}
    }
  }

  private fun sendResponse(id: Long, result: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    sendFrame(payload.toString())
  }

  private fun sendError(id: Long, code: Int, message: String) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendNotification(method: String, params: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    }
    sendFrame(payload.toString())
  }

  private fun sendFrame(jsonText: String) {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(output) {
      output.write(header)
      output.write(bytes)
      output.flush()
    }
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
