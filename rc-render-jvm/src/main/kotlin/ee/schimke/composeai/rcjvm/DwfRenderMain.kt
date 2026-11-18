@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.rcjvm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.dwf.WffValue
import ee.schimke.composeai.dwf.extractNamedValueSpecs
import ee.schimke.composeai.rccreation.dwf.DwfComplicationSeed
import ee.schimke.composeai.rccreation.dwf.DwfDocument
import ee.schimke.composeai.rccreation.dwf.DwfParser
import ee.schimke.composeai.rccreation.dwf.DwfRenderOptions
import ee.schimke.composeai.rccreation.dwf.DwfSceneNode
import ee.schimke.composeai.rccreation.dwf.DwfUserConfiguration
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rccreation.dwf.DwfWeatherSeed
import ee.schimke.composeai.rccreation.dwf.captureDwfRemoteDocumentBytes
import ee.schimke.composeai.rccreation.dwf.parseDwfColorArgb
import ee.schimke.composeai.rcplayer.compose.LocalRcTimeSource
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcManifestTypefaceLoader
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcTimeSnapshot
import ee.schimke.composeai.rcplayer.runtime.RcTimeSource
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat

internal val dwfJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

@Serializable
internal data class DwfPreviewManifestDto(val previews: List<DwfPreviewEntryDto> = emptyList())

@Serializable
internal data class DwfPreviewEntryDto(
  val id: String,
  val functionName: String = "",
  val params: DwfPreviewParamsDto = DwfPreviewParamsDto(),
  val captures: List<DwfCaptureDto> = listOf(DwfCaptureDto()),
)

@Serializable
internal data class DwfPreviewParamsDto(
  val name: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val dwfAssetPath: String? = null,
)

@Serializable internal data class DwfCaptureDto(val renderOutput: String = "")

/**
 * Deterministic fake host data for DWF preview rendering (`--fakes-json <file>` or
 * `<watchface>.fakes.json` beside the watch face XML). Removes complication, sensor, flavor, clock,
 * and weather discrepancies during preview and reference validation.
 */
@Serializable
public data class DwfFakesConfig(
  val flavor: String? = null,
  val previewTime: String? = null,
  val year: Int? = null,
  val month: Int? = null,
  val dayOfMonth: Int? = null,
  val isAmbient: Boolean? = null,
  val is24Hour: Boolean? = null,
  val isDst: Boolean? = null,
  val batteryPercent: Float? = null,
  val batteryCharging: Boolean? = null,
  val unreadNotifications: Int? = null,
  val stepCount: Int? = null,
  val stepGoal: Float? = null,
  val heartRate: Float? = null,
  val calories: Float? = null,
  val distance: Float? = null,
  val floors: Float? = null,
  val configurations: Map<String, String> = emptyMap(),
  val complications: Map<Int, DwfFakeComplication> = emptyMap(),
  val weather: DwfFakeWeather? = null,
) {
  public fun toRenderOptions(base: DwfRenderOptions = DwfRenderOptions()): DwfRenderOptions =
    base.copy(
      isAmbientDefault = isAmbient ?: base.isAmbientDefault,
      is24HourDefault = is24Hour ?: base.is24HourDefault,
      isDstDefault = isDst ?: base.isDstDefault,
      batteryPercentDefault = batteryPercent ?: base.batteryPercentDefault,
      batteryChargingDefault = batteryCharging ?: base.batteryChargingDefault,
      unreadNotificationsDefault = unreadNotifications ?: base.unreadNotificationsDefault,
      stepCountDefault = stepCount ?: base.stepCountDefault,
      stepGoalDefault = stepGoal ?: base.stepGoalDefault,
      heartRateDefault = heartRate ?: base.heartRateDefault,
      caloriesDefault = calories ?: base.caloriesDefault,
      distanceDefault = distance ?: base.distanceDefault,
      floorsDefault = floors ?: base.floorsDefault,
      flavorDefault = flavor ?: base.flavorDefault,
      configurationDefaults = base.configurationDefaults + configurations,
      complicationDefaults =
        base.complicationDefaults + complications.mapValues { (_, c) -> c.toSeed() },
      weatherDefault = weather?.toSeed() ?: base.weatherDefault,
    )
}

@Serializable
public data class DwfFakeComplication(
  val type: String = "SHORT_TEXT",
  val text: String = "",
  val title: String = "",
  val value: Float = 0f,
  val min: Float = 0f,
  val max: Float = 100f,
  val monochromaticImage: String? = null,
  val smallImage: String? = null,
  val photoImage: String? = null,
  val rangedValueColors: List<String> = emptyList(),
  val rangedValueColorsInterpolate: Boolean = false,
  val goalProgressColors: List<String> = emptyList(),
  val goalProgressColorsInterpolate: Boolean = false,
  val weightedElementsColors: List<String> = emptyList(),
  val weightedElementsWeights: List<Float> = emptyList(),
) {
  public fun toSeed(): DwfComplicationSeed =
    DwfComplicationSeed(
      type = type,
      text = text,
      title = title,
      value = value,
      min = min,
      max = max,
      monochromaticImage = monochromaticImage,
      smallImage = smallImage,
      photoImage = photoImage,
      rangedValueColors = rangedValueColors.mapNotNull { parseDwfColorArgb(it) },
      rangedValueColorsInterpolate = rangedValueColorsInterpolate,
      goalProgressColors = goalProgressColors.mapNotNull { parseDwfColorArgb(it) },
      goalProgressColorsInterpolate = goalProgressColorsInterpolate,
      weightedElementsColors = weightedElementsColors.mapNotNull { parseDwfColorArgb(it) },
      weightedElementsWeights = weightedElementsWeights,
    )
}

@Serializable
public data class DwfFakeWeather(
  val isAvailable: Boolean = true,
  val isDay: Boolean = true,
  val condition: Int = 14,
  val conditionName: String = "Partly Cloudy",
  val temperature: Float = 22f,
  val temperatureLow: Float? = null,
  val temperatureHigh: Float? = null,
  val temperatureUnit: Int = 1,
  val chanceOfPrecipitation: Int = 15,
  val uvIndex: Int = 5,
  val lastUpdatedText: String = "",
  val hours: Map<Int, DwfFakeWeatherHour> = emptyMap(),
  val days: Map<Int, DwfFakeWeatherDay> = emptyMap(),
) {
  public fun toSeed(): DwfWeatherSeed =
    DwfWeatherSeed(
      isAvailable = isAvailable,
      isDay = isDay,
      condition = condition,
      conditionName = conditionName,
      temperature = temperature,
      temperatureLow = temperatureLow,
      temperatureHigh = temperatureHigh,
      temperatureUnit = temperatureUnit,
      chanceOfPrecipitation = chanceOfPrecipitation,
      uvIndex = uvIndex,
      lastUpdatedText = lastUpdatedText,
      hours =
        hours.mapValues { (_, h) ->
          DwfWeatherSeed.Hour(
            isAvailable = h.isAvailable,
            isDay = h.isDay,
            condition = h.condition,
            temperature = h.temperature,
            uvIndex = h.uvIndex,
            chanceOfPrecipitation = h.chanceOfPrecipitation,
          )
        },
      days =
        days.mapValues { (_, d) ->
          DwfWeatherSeed.Day(
            isAvailable = d.isAvailable,
            conditionDay = d.conditionDay,
            conditionNight = d.conditionNight,
            temperatureHigh = d.temperatureHigh,
            temperatureLow = d.temperatureLow,
            chanceOfPrecipitation = d.chanceOfPrecipitation,
            chanceOfPrecipitationNight = d.chanceOfPrecipitationNight,
          )
        },
    )
}

@Serializable
public data class DwfFakeWeatherHour(
  val isAvailable: Boolean = true,
  val isDay: Boolean = true,
  val condition: Int = 14,
  val temperature: Float = 22f,
  val uvIndex: Int = 4,
  val chanceOfPrecipitation: Int = 10,
)

@Serializable
public data class DwfFakeWeatherDay(
  val isAvailable: Boolean = true,
  val conditionDay: Int = 8,
  val conditionNight: Int = 1,
  val temperatureHigh: Float = 25f,
  val temperatureLow: Float = 16f,
  val chanceOfPrecipitation: Int = 15,
  val chanceOfPrecipitationNight: Int = 10,
)

/** Input entry written by `ApkDwfExtractor` for `--compile-apk-dwf-dir`. */
@Serializable
public data class ApkDwfWatchFaceInput(
  val id: String,
  val label: String,
  val xmlFile: String,
)

/** Output manifest written to `<dir>/compiled-manifest.json` for `:simulator-host`. */
@Serializable
public data class CompiledApkDwfManifest(
  val watchFaces: List<CompiledApkDwfEntry> = emptyList(),
)

@Serializable
public data class CompiledApkDwfEntry(
  val id: String,
  val label: String,
  val rcFile: String,
  val width: Int,
  val height: Int,
  val clipShape: String,
  val previewHour: Int,
  val previewMinute: Int,
  val previewSecond: Int,
  val slots: List<CompiledApkDwfSlot> = emptyList(),
  val configurations: List<CompiledApkDwfConfig> = emptyList(),
  val defaultFlavorId: String? = null,
  val flavors: List<CompiledApkDwfFlavor> = emptyList(),
)

@Serializable
public data class CompiledApkDwfFlavor(
  val id: String,
  val displayName: String,
  val configurations: Map<String, String> = emptyMap(),
  val complications: Map<Int, CompiledApkDwfFlavorComplication> = emptyMap(),
)

@Serializable
public data class CompiledApkDwfFlavorComplication(
  val defaultSystemProvider: String? = null,
  val defaultSystemProviderType: String? = null,
)

@Serializable
public data class CompiledApkDwfSlot(
  val id: Int,
  val supportedTypes: List<Int>,
  val defaultSystemProvider: String? = null,
  val defaultType: Int = DwfVariables.COMPLICATION_TYPE_SHORT_TEXT,
  val enabled: Boolean = true,
  val x: Float = 0f,
  val y: Float = 0f,
  val width: Float = 0f,
  val height: Float = 0f,
)

@Serializable
public data class CompiledApkDwfConfig(
  val id: String,
  val kind: String,
  val displayName: String,
  val defaultValue: String,
  val options: List<CompiledApkDwfConfigOption> = emptyList(),
)

@Serializable
public data class CompiledApkDwfConfigOption(
  val id: String,
  val displayName: String,
  val colors: List<Int> = emptyList(),
)

/**
 * CLI entrypoint for two DWF workflows:
 * 1. **Preview mode** (`--previews-json <file> --output-dir <dir> [--resource-dir <dir>]...
 *    [--fakes-json <file>]`): compiles discovered `<WatchFace>` XML assets to `<stem>.rc`, writes
 *    `<stem>.remotecompose.json` knob declarations (`capturePlayer = "cmp-jvm"`), and renders
 *    deterministic `<stem>.png` stills using Wear OS `Roboto Flex` typography.
 * 2. **Single face mode** (`--watchface <watchface.xml> --png <out.png> [--resource-dir <res>]...
 *    [--set name=value]... [--time HH:MM[:SS]] [--date YYYY-MM-DD] [--fakes-json <file>]`): renders
 *    one face to one PNG with named-value overrides, for example `--set dwf.is_ambient=true`,
 *    `--set metrics.heart_rate=120`, `--set dwf.complication.1.text=Hi`, `--set dwf.config.x=2`.
 *    Run once with an unknown name to list the names the face declares. Translator warnings (an
 *    unknown data source, a malformed expression) go to stderr.
 * 3. **APK simulator compile mode** (`--compile-apk-dwf-dir <dir>`): compiles extracted APK watch
 *    face XMLs against `<dir>/res` into `.rc` documents + `compiled-manifest.json` for
 *    `compose-preview launch`.
 */
public fun main(args: Array<String>) {
  var previewsJsonPath: String? = null
  var outputDirPath: String? = null
  var compileApkDirPath: String? = null
  var fakesJsonPath: String? = null
  var watchFacePath: String? = null
  var outputPngPath: String? = null
  var timeArg: String? = null
  var dateArg: String? = null
  val setArgs = mutableListOf<String>()
  val resourceDirs = mutableListOf<File>()
  val filters = mutableListOf<String>()
  val idFilters = mutableListOf<String>()
  val idExcludes = mutableListOf<String>()

  var i = 0
  while (i < args.size) {
    when (val arg = args[i]) {
      "--previews-json" -> previewsJsonPath = args[++i]
      "--output-dir" -> outputDirPath = args[++i]
      "--compile-apk-dwf-dir" -> compileApkDirPath = args[++i]
      "--fakes-json" -> fakesJsonPath = args[++i]
      "--watchface" -> watchFacePath = args[++i]
      "--png" -> outputPngPath = args[++i]
      "--set" -> setArgs += args[++i]
      "--time" -> timeArg = args[++i]
      "--date" -> dateArg = args[++i]
      "--resource-dir" -> resourceDirs += File(args[++i])
      "--filter" -> filters += args[++i]
      "--id-filter" -> idFilters += args[++i]
      "--id-exclude" -> idExcludes += args[++i]
      else -> {
        System.err.println("DwfRenderMain: unknown option '$arg'")
        exitProcess(2)
      }
    }
    i++
  }

  try {
    if (watchFacePath != null) {
      val png = outputPngPath?.let(::File) ?: error("DwfRenderMain: --png is required")
      val overrides = parseDwfSetArgs(setArgs)
      val result =
        renderDwfFaceFile(
          xmlFile = File(watchFacePath),
          resourceDirs = resourceDirs,
          overrides = overrides,
          time = timeArg,
          date = dateArg,
          fakesJson = fakesJsonPath?.let(::File),
        )
      png.absoluteFile.parentFile?.mkdirs()
      png.writeBytes(result.png)
      for ((code, message) in result.diagnostics) System.err.println("warning $code: $message")
      println("wrote ${png.path}")
      return
    }
    if (compileApkDirPath != null) {
      compileApkWatchFaces(File(compileApkDirPath), fakesJsonPath?.let(::File))
      return
    }

    val previewsJson =
      previewsJsonPath?.let(::File) ?: error("DwfRenderMain: --previews-json is required")
    val outputDir = outputDirPath?.let(::File) ?: error("DwfRenderMain: --output-dir is required")
    renderDwfPreviews(
      previewsJson = previewsJson,
      outputDir = outputDir,
      resourceDirs = resourceDirs,
      filters = filters,
      idFilters = idFilters,
      idExcludes = idExcludes,
      fakesJson = fakesJsonPath?.let(::File),
    )
  } catch (t: Throwable) {
    System.err.println("DwfRenderMain failed: ${t.message}")
    t.printStackTrace(System.err)
    exitProcess(1)
  }
}

/** A parsed `--set name=value` argument; the value is typed against the face's declarations. */
internal data class DwfSetArg(val name: String, val raw: String)

internal fun parseDwfSetArgs(args: List<String>): List<DwfSetArg> = args.map { arg ->
  val eq = arg.indexOf('=')
  require(eq > 0) { "DwfRenderMain: --set expects name=value, got '$arg'" }
  DwfSetArg(arg.substring(0, eq).trim(), arg.substring(eq + 1))
}

internal class DwfFaceRender(val png: ByteArray, val diagnostics: List<Pair<String, String>>)

/**
 * Renders one watch face file to PNG with [overrides] seeded as named values. Each override is
 * typed by the face's own declaration of that name, so `--set dwf.is_ambient=true`, `--set
 * metrics.heart_rate=120` and `--set dwf.complication.1.text=Hi` need no type syntax. A name the
 * face does not declare is an error that lists the ones it does.
 */
internal fun renderDwfFaceFile(
  xmlFile: File,
  resourceDirs: List<File>,
  overrides: List<DwfSetArg>,
  time: String? = null,
  date: String? = null,
  fakesJson: File? = null,
): DwfFaceRender {
  require(xmlFile.isFile) { "DwfRenderMain: watch face '$xmlFile' does not exist" }
  val resolver = DwfResourceDirectoryResolver(resourceDirs)
  val doc = DwfParser.parse(xmlFile.readText(), resolver)
  val fakes =
    fakesJson?.takeIf(File::isFile)?.let { dwfJson.decodeFromString<DwfFakesConfig>(it.readText()) }
      ?: findSidecarFakes(xmlFile)
  val diagnostics = ee.schimke.composeai.rccreation.dwf.DwfDiagnostics()
  val options = (fakes?.toRenderOptions() ?: DwfRenderOptions()).copy(diagnostics = diagnostics)
  val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, options) }
  val specs =
    extractNamedValueSpecs(
        rcBytes,
        doc.userConfigurations
          .filterIsInstance<DwfUserConfiguration.BooleanConfiguration>()
          .map { it.id }
          .toSet(),
      )
      .associateBy { it.name }
  val seeds = LinkedHashMap<String, RcNamedValue>()
  for (o in overrides) {
    val spec =
      specs[o.name]
        ?: error(
          "DwfRenderMain: the face does not declare '${o.name}'. Declared names: " +
            specs.keys.sorted().joinToString(", "),
        )
    seeds[o.name] = typedDwfSeed(o, spec.default)
  }
  val (hour, minute, second) = parsePreviewHms(time ?: fakes?.previewTime ?: doc.previewTime)
  val day =
    date?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
      ?: LocalDate.of(fakes?.year ?: 2026, fakes?.month ?: 11, fakes?.dayOfMonth ?: 18)
  val png =
    renderDwfDocumentToPng(
      bytes = rcBytes,
      widthPx = doc.width.coerceAtLeast(1),
      heightPx = doc.height.coerceAtLeast(1),
      hour = hour,
      minute = minute,
      second = second,
      seeds = seeds,
      year = day.year,
      month = day.monthValue,
      dayOfMonth = day.dayOfMonth,
      typefaces = dwfTypefaces(resourceDirs),
    )
  return DwfFaceRender(png, diagnostics.entries)
}

private fun typedDwfSeed(arg: DwfSetArg, declared: WffValue): RcNamedValue {
  val raw = arg.raw.trim()
  fun bad(kind: String): Nothing =
    error("DwfRenderMain: --set ${arg.name}=${arg.raw}: expected $kind")
  return when (declared) {
    is WffValue.Text -> RcNamedValue.Text(arg.raw)
    is WffValue.Float -> RcNamedValue.FloatValue(raw.toFloatOrNull() ?: bad("a number"))
    is WffValue.Int -> RcNamedValue.Integer(raw.toIntOrNull() ?: bad("an integer"))
    is WffValue.Bool ->
      RcNamedValue.BooleanValue(
        when (raw.lowercase()) {
          "true",
          "1" -> true
          "false",
          "0" -> false
          else -> bad("true, false, 1 or 0")
        },
      )
    is WffValue.Color ->
      RcNamedValue.Color(parseDwfColorArgb(raw) ?: bad("a colour such as #FF00AA33"))
  }
}

public fun renderDwfPreviews(
  previewsJson: File,
  outputDir: File,
  resourceDirs: List<File>,
  filters: List<String> = emptyList(),
  idFilters: List<String> = emptyList(),
  idExcludes: List<String> = emptyList(),
  fakesJson: File? = null,
): Int {
  if (!previewsJson.isFile) return 0
  val manifest = dwfJson.decodeFromString<DwfPreviewManifestDto>(previewsJson.readText())
  val selected =
    manifest.previews
      .filter { entry -> entry.params.dwfAssetPath != null }
      .filter { entry ->
        filters.isEmpty() ||
          filters.any { pat ->
            matchesFilter(entry.id, pat) ||
              matchesFilter(entry.functionName, pat) ||
              (entry.params.name != null && matchesFilter(entry.params.name, pat))
          }
      }
      .filter { entry -> idFilters.isEmpty() || entry.id in idFilters }
      .filterNot { entry -> idExcludes.any { pat -> matchesFilter(entry.id, pat) } }

  if (selected.isEmpty()) return 0

  outputDir.mkdirs()
  val previewsRoot = outputDir.parentFile ?: previewsJson.parentFile ?: outputDir
  val resolver = DwfResourceDirectoryResolver(resourceDirs)
  val explicitFakes =
    fakesJson?.takeIf(File::isFile)?.let { dwfJson.decodeFromString<DwfFakesConfig>(it.readText()) }

  var rendered = 0
  for (entry in selected) {
    val relPath = entry.params.dwfAssetPath ?: continue
    val xmlFile =
      resolveDwfXmlFile(relPath, resourceDirs, previewsRoot)
        ?: error("DwfRenderMain: could not locate DWF XML '$relPath' in $resourceDirs")
    val sidecarFakes = findSidecarFakes(xmlFile)
    val activeFakes = explicitFakes ?: sidecarFakes
    val xml = xmlFile.readText()
    val doc = DwfParser.parse(xml, resolver)
    val options = activeFakes?.toRenderOptions() ?: DwfRenderOptions()
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, options) }
    val declarationsPayload = extractDwfDeclarationsPayload(doc, rcBytes)
    val defaultNamedValues = LinkedHashMap<String, RemoteNamedValue>()
    for (decl in declarationsPayload.declarations) {
      defaultNamedValues[decl.name] = decl.default
    }
    val slots = collectDwfRawSlots(doc.scene.children)
    val widthPx = doc.width.coerceAtLeast(1)
    val heightPx = doc.height.coerceAtLeast(1)
    val watchFacePayload =
      buildDwfComplicationsPayload(
        previewId = entry.id,
        dwfAssetPath = relPath,
        doc = doc,
        fakes = activeFakes,
        slots = slots,
        widthPx = widthPx,
        heightPx = heightPx,
        namedValues = defaultNamedValues,
      )
    val (hour, minute, second) = parsePreviewHms(activeFakes?.previewTime ?: doc.previewTime)
    val pngBytes =
      renderDwfDocumentToPng(
        bytes = rcBytes,
        widthPx = widthPx,
        heightPx = heightPx,
        hour = hour,
        minute = minute,
        second = second,
        year = activeFakes?.year ?: 2026,
        month = activeFakes?.month ?: 11,
        dayOfMonth = activeFakes?.dayOfMonth ?: 18,
        typefaces = dwfTypefaces(resourceDirs),
      )

    for (capture in entry.captures.ifEmpty { listOf(DwfCaptureDto()) }) {
      val relOut = capture.renderOutput.trim()
      val pngFile =
        if (relOut.isNotEmpty()) {
          val relInOutputDir =
            if (relOut.startsWith("${outputDir.name}/")) {
              relOut.removePrefix("${outputDir.name}/")
            } else {
              File(relOut).name
            }
          File(outputDir, relInOutputDir)
        } else {
          File(outputDir, "${entry.id}.png")
        }
      pngFile.parentFile?.mkdirs()
      pngFile.writeBytes(pngBytes)
      val stem = pngFile.nameWithoutExtension
      val dir = pngFile.parentFile ?: outputDir
      File(dir, "$stem.rc").writeBytes(rcBytes)
      File(dir, "$stem.remotecompose.json")
        .writeText(
          dwfJson.encodeToString(
            RemoteComposeDeclarationsPayload.serializer(),
            declarationsPayload,
          ),
        )
      File(dir, "$stem.watchface.json")
        .writeText(
          dwfJson.encodeToString(
            WatchFaceComplicationsPayload.serializer(),
            watchFacePayload,
          ),
        )
      rendered++
    }
  }
  return rendered
}

public fun compileApkWatchFaces(
  workDir: File,
  fakesJson: File? = null,
): CompiledApkDwfManifest {
  val resDir = File(workDir, "res")
  val resolver = DwfResourceDirectoryResolver(listOfNotNull(resDir.takeIf(File::isDirectory)))
  val explicitFakes =
    (fakesJson ?: File(workDir, "fakes.json")).takeIf(File::isFile)?.let {
      dwfJson.decodeFromString<DwfFakesConfig>(it.readText())
    }
  val inputFile = File(workDir, "watchfaces-input.json")
  val inputs: List<ApkDwfWatchFaceInput> =
    if (inputFile.isFile) {
      dwfJson.decodeFromString(inputFile.readText())
    } else {
      val wfDir = File(workDir, "watchfaces")
      wfDir
        .listFiles()
        ?.filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?.map { xml ->
          ApkDwfWatchFaceInput(
            id = xml.nameWithoutExtension,
            label = xml.nameWithoutExtension,
            xmlFile = "watchfaces/${xml.name}",
          )
        }
        .orEmpty()
    }

  val compiledEntries = mutableListOf<CompiledApkDwfEntry>()
  for (item in inputs) {
    val xmlFile = File(workDir, item.xmlFile)
    if (!xmlFile.isFile) continue
    val xml = xmlFile.readText()
    if (!DwfParser.isDwfXml(xml)) continue
    val doc = DwfParser.parse(xml, resolver)
    val activeFakes = explicitFakes ?: findSidecarFakes(xmlFile)
    val baseOptions = DwfRenderOptions(defaultUnboundComplicationsToEmpty = true)
    val options = activeFakes?.toRenderOptions(baseOptions) ?: baseOptions
    val rcBytes = runBlocking { captureDwfRemoteDocumentBytes(doc, resolver, options) }
    val safeId = item.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val rcFileName = "$safeId.rc"
    File(workDir, rcFileName).writeBytes(rcBytes)

    val (hour, minute, second) = parsePreviewHms(activeFakes?.previewTime ?: doc.previewTime)
    val slots = collectComplicationSlots(doc.scene.children)
    val configs =
      doc.userConfigurations.map { cfg ->
        when (cfg) {
          is DwfUserConfiguration.BooleanConfiguration ->
            CompiledApkDwfConfig(
              id = cfg.id,
              kind = "BOOLEAN",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.defaultValue.toString(),
            )
          is DwfUserConfiguration.ListConfiguration ->
            CompiledApkDwfConfig(
              id = cfg.id,
              kind = "LIST",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.defaultValue ?: cfg.options.firstOrNull()?.id.orEmpty(),
              options =
                cfg.options.map { opt ->
                  CompiledApkDwfConfigOption(
                    id = opt.id,
                    displayName = opt.displayName ?: opt.id,
                  )
                },
            )
          is DwfUserConfiguration.ColorConfiguration ->
            CompiledApkDwfConfig(
              id = cfg.id,
              kind = "COLOR",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.defaultValue ?: cfg.options.firstOrNull()?.id.orEmpty(),
              options =
                cfg.options.map { opt ->
                  CompiledApkDwfConfigOption(
                    id = opt.id,
                    displayName = opt.displayName ?: opt.id,
                    colors = opt.colors,
                  )
                },
            )
          is DwfUserConfiguration.PhotosConfiguration ->
            CompiledApkDwfConfig(
              id = cfg.id,
              kind = "PHOTOS",
              displayName = cfg.displayName ?: cfg.id,
              defaultValue = cfg.configType,
            )
        }
      }

    val flavors =
      doc.flavors.map { flavor ->
        CompiledApkDwfFlavor(
          id = flavor.id,
          displayName = flavor.displayName ?: flavor.id,
          configurations = flavor.configurations,
          complications =
            flavor.complications.mapValues { (_, pol) ->
              CompiledApkDwfFlavorComplication(
                defaultSystemProvider = pol.defaultSystemProvider,
                defaultSystemProviderType = pol.defaultSystemProviderType,
              )
            },
        )
      }

    compiledEntries +=
      CompiledApkDwfEntry(
        id = item.id,
        label = item.label,
        rcFile = rcFileName,
        width = doc.width.coerceAtLeast(1),
        height = doc.height.coerceAtLeast(1),
        clipShape = doc.clipShape.name,
        previewHour = hour,
        previewMinute = minute,
        previewSecond = second,
        slots = slots,
        configurations = configs,
        defaultFlavorId = doc.defaultFlavorId,
        flavors = flavors,
      )
  }

  val outManifest = CompiledApkDwfManifest(watchFaces = compiledEntries)
  File(workDir, "compiled-manifest.json")
    .writeText(dwfJson.encodeToString(CompiledApkDwfManifest.serializer(), outManifest))
  return outManifest
}

public fun renderDwfDocumentToPng(
  bytes: ByteArray,
  widthPx: Int,
  heightPx: Int,
  hour: Int = 10,
  minute: Int = 10,
  second: Int = 30,
  seeds: Map<String, RcNamedValue> = emptyMap(),
  year: Int = 2026,
  month: Int = 11,
  dayOfMonth: Int = 18,
  typefaces: RcTypefaceLoader? = dwfTypefaces(),
  imageLoader: ee.schimke.composeai.rcplayer.compose.RcImageLoader = defaultDwfImageLoader(),
): ByteArray {
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
  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(1f))
  try {
    val stateMap = mutableStateMapOf<String, RcNamedValue>().apply { putAll(seeds) }
    scene.setContent {
      CompositionLocalProvider(
        LocalRcTimeSource provides fixedTime,
        ee.schimke.composeai.rcplayer.compose.LocalRcImageLoader provides imageLoader,
      ) {
        if (typefaces == null) {
          RcComposePlayer(bytes = bytes, modifier = Modifier.fillMaxSize(), namedValues = stateMap)
        } else {
          RcComposePlayer(
            bytes = bytes,
            modifier = Modifier.fillMaxSize(),
            namedValues = stateMap,
            typefaces = typefaces,
          )
        }
      }
    }
    val image = scene.render(0L)
    val data =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: error("skiko could not encode the rendered DWF watch face to PNG")
    return data.bytes
  } finally {
    scene.close()
  }
}

/**
 * Resolves the typeface loader for DWF renders: explicit `-Dcomposeai.rcjvm.fontsDir` when set,
 * otherwise the bundled `Roboto Flex` variable family (`dwf-fonts/fonts.json`) matching Wear OS's
 * `SYNC_TO_DEVICE` system font across all weights `100..900`.
 */
internal fun dwfTypefaces(): RcTypefaceLoader? = rcTypefaces() ?: bundledDwfTypefaces

private val bundledDwfTypefaces: RcTypefaceLoader? by lazy {
  val classLoader = DwfPreviewManifestDto::class.java.classLoader
  runCatching {
    runBlocking {
      RcManifestTypefaceLoader { path ->
          checkNotNull(classLoader.getResourceAsStream(path)) { "Missing DWF font resource: $path" }
            .use { it.readAllBytes() }
        }
        .load("dwf-fonts")
    }
  }
    .getOrNull()
}

internal fun findSidecarFakes(xmlFile: File): DwfFakesConfig? {
  val dir = xmlFile.parentFile ?: return null
  val stem = xmlFile.nameWithoutExtension
  val candidate = File(dir, "$stem.fakes.json")
  if (!candidate.isFile) return null
  return runCatching { dwfJson.decodeFromString<DwfFakesConfig>(candidate.readText()) }.getOrNull()
}

internal fun extractDwfDeclarationsPayload(
  doc: DwfDocument,
  rcBytes: ByteArray,
): RemoteComposeDeclarationsPayload {
  val declarations =
    extractNamedValueSpecs(
        rcBytes,
        doc.userConfigurations
          .filterIsInstance<DwfUserConfiguration.BooleanConfiguration>()
          .map { it.id }
          .toSet(),
      )
      .map { spec ->
        val default: RemoteNamedValue =
          when (val v = spec.default) {
            is WffValue.Text -> RemoteNamedValue.StringValue(v.value)
            is WffValue.Float -> RemoteNamedValue.FloatValue(v.value)
            is WffValue.Int -> RemoteNamedValue.IntValue(v.value)
            is WffValue.Bool -> RemoteNamedValue.BooleanValue(v.value)
            is WffValue.Color -> RemoteNamedValue.ColorValue("#%08X".format(v.argb))
          }
        RemoteComposeKnobDeclaration(spec.name, default)
      }
  return RemoteComposeDeclarationsPayload(declarations).apply { capturePlayer = "cmp-jvm" }
}

private fun collectComplicationSlots(nodes: List<DwfSceneNode>): List<CompiledApkDwfSlot> {
  val out = LinkedHashMap<Int, CompiledApkDwfSlot>()
  fun visit(list: List<DwfSceneNode>) {
    for (node in list) {
      when (node) {
        is DwfSceneNode.ComplicationSlot -> {
          val types =
            node.supportedTypes
              .map { WearComplicationTypeNumbers.fromDwfName(it) }
              .filter { it > 0 }
              .distinct()
              .ifEmpty { listOf(WearComplicationTypeNumbers.SHORT_TEXT) }
          val defaultType =
            node.defaultSystemProviderType?.let(WearComplicationTypeNumbers::fromDwfName)?.takeIf {
              it > 0
            } ?: types.first()
          out.putIfAbsent(
            node.slotId,
            CompiledApkDwfSlot(
              id = node.slotId,
              supportedTypes = types,
              defaultSystemProvider = node.defaultSystemProvider,
              defaultType = defaultType,
              enabled = true,
              x = node.geometry.x,
              y = node.geometry.y,
              width = node.geometry.width,
              height = node.geometry.height,
            ),
          )
        }
        is DwfSceneNode.Group -> visit(node.children)
        is DwfSceneNode.Condition -> node.branches.forEach { visit(it.children) }
        is DwfSceneNode.BooleanConfigurationSwitch -> {
          visit(node.trueChildren)
          visit(node.falseChildren)
        }
        is DwfSceneNode.ListConfigurationSwitch -> node.options.forEach { visit(it.children) }
        else -> {}
      }
    }
  }
  visit(nodes)
  return out.values.toList()
}

/**
 * AndroidX `ComplicationType` wire numbers (`WatchFaceHost` / `WatchFaceClient` in
 * `:simulator-host`), distinct from `DwfVariables.COMPLICATION_TYPE_*` internal RC enum codes.
 */
private object WearComplicationTypeNumbers {
  const val SHORT_TEXT: Int = 3
  const val LONG_TEXT: Int = 4
  const val RANGED_VALUE: Int = 5
  const val MONOCHROMATIC_IMAGE: Int = 6
  const val SMALL_IMAGE: Int = 7
  const val PHOTO_IMAGE: Int = 8
  const val NO_PERMISSION: Int = 9
  const val NO_DATA: Int = 10
  const val GOAL_PROGRESS: Int = 13
  const val WEIGHTED_ELEMENTS: Int = 14

  fun fromDwfName(raw: String): Int =
    when (raw.trim().uppercase().removePrefix("COMPLICATION_TYPE_")) {
      "SHORT_TEXT" -> SHORT_TEXT
      "LONG_TEXT" -> LONG_TEXT
      "RANGED_VALUE" -> RANGED_VALUE
      "MONOCHROMATIC_IMAGE",
      "ICON" -> MONOCHROMATIC_IMAGE
      "SMALL_IMAGE" -> SMALL_IMAGE
      "PHOTO_IMAGE",
      "LARGE_IMAGE" -> PHOTO_IMAGE
      "GOAL_PROGRESS" -> GOAL_PROGRESS
      "WEIGHTED_ELEMENTS" -> WEIGHTED_ELEMENTS
      "NO_DATA" -> NO_DATA
      "NO_PERMISSION" -> NO_PERMISSION
      else -> 0
    }
}

internal fun parsePreviewHms(previewTime: String?): Triple<Int, Int, Int> {
  val raw = previewTime?.trim().orEmpty()
  if (raw.isEmpty()) return Triple(10, 10, 30)
  val parts = raw.split(':')
  if (parts.size >= 2) {
    val h = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: 10
    val m = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: 10
    val s = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return Triple(h, m, s)
  }
  return Triple(10, 10, 30)
}

internal fun resolveDwfXmlFile(
  relPath: String,
  resourceDirs: List<File>,
  previewsRoot: File,
): File? {
  val direct = File(relPath)
  if (direct.isAbsolute && direct.isFile) return direct
  for (resDir in resourceDirs) {
    val candidate = File(resDir, relPath)
    if (candidate.isFile) return candidate
  }
  val fromRoot = File(previewsRoot, relPath)
  if (fromRoot.isFile) return fromRoot
  return null
}

private fun matchesFilter(candidate: String, pattern: String): Boolean {
  if ('*' !in pattern) return candidate.contains(pattern, ignoreCase = true)
  val regex =
    Regex(
      "^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$",
      RegexOption.IGNORE_CASE,
    )
  return regex.matches(candidate)
}
