package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WatchFaceComplicationsPayload
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pluggable session bridge used by [DwfApkWatchFaceRendererBackend] (`dwf-apk`) to render
 * Declarative Watch Faces (`<WatchFace>`) through the Wear OS DWF Renderer APK
 * (`DeclarativeWatchFaceRuntime.apk`) running inside a `:simulator-host` daemon.
 */
public interface DwfApkSessionBridge {
  public fun isAvailable(host: ServeHost, preview: ServePreview): Boolean

  public fun unavailableReason(host: ServeHost, preview: ServePreview): String?

  public fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome

  public fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome
}

/**
 * Default [DwfApkSessionBridge] backed by a persistent `:simulator-host` (`LaunchMain`) daemon
 * hosting `DeclarativeWatchFaceRuntime.apk` in Robolectric (`SimulatorSandbox`).
 *
 * Packages a preview's bundle resources into a cached resource-only WFF APK via `aapt2`, pushes it
 * into the warm runtime daemon via `watchface { op: "push" }`, applies renderer-neutral
 * [WatchFaceOverrides] (ambient, clock, sensors, user configs, complication slots), and captures
 * the rendered frame via `screenshot`.
 */
internal object DwfApkDaemonBridge : DwfApkSessionBridge {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()
  private var activeDaemon: SimulatorDaemonProcess? = null

  private data class Environment(
    val runtimeApk: File,
    val simulatorJars: List<File>,
    val androidDaemonJars: List<File>,
    val dex2jarJars: List<File>,
    val androidJar: File,
    val aapt2: File?,
  )

  internal fun isEnvironmentReady(): Boolean =
    resolveEnvironment(requireAapt2 = false).first != null

  private fun resolveEnvironment(requireAapt2: Boolean = true): Pair<Environment?, String?> {
    val runtimeApk =
      DwfApkWatchFaceRendererBackend.configuredApkFile()
        ?: return null to
          "wfRenderer '${WatchFaceRendererIds.DWF_APK}' is unavailable: no Wear OS DWF renderer APK " +
            "configured (set -D${DwfApkWatchFaceRendererBackend.APK_PATH_SYS_PROP} or " +
            "\$${DwfApkWatchFaceRendererBackend.APK_PATH_ENV}, or run `compose-preview dwf-runtime pull`)"

    val simulatorJars = locateBundleSidecarJars("lib-simulator")
    if (simulatorJars.isEmpty()) {
      return null to
        "wfRenderer '${WatchFaceRendererIds.DWF_APK}' needs `lib-simulator/` (looked in " +
          "${bundleSidecarSearchDescription("lib-simulator")})"
    }

    val androidDaemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (androidDaemonJars.isEmpty()) {
      return null to
        "wfRenderer '${WatchFaceRendererIds.DWF_APK}' needs `lib-daemon-android/` (looked in " +
          "${bundleSidecarSearchDescription("lib-daemon-android")})"
    }

    val dex2jarJars = locateBundleSidecarJars("lib-dex2jar")
    val androidJar =
      locateAndroidJar()
        ?: return null to
          "wfRenderer '${WatchFaceRendererIds.DWF_APK}' needs an Android SDK `android.jar` " +
            "(set ANDROID_HOME or ANDROID_SDK_ROOT)"

    val aapt2 = locateAapt2()
    if (requireAapt2 && aapt2 == null) {
      return null to
        "wfRenderer '${WatchFaceRendererIds.DWF_APK}' needs Android SDK `build-tools/*/aapt2` " +
          "to package watch-face bundle resources"
    }

    return Environment(
      runtimeApk = runtimeApk,
      simulatorJars = simulatorJars,
      androidDaemonJars = androidDaemonJars,
      dex2jarJars = dex2jarJars,
      androidJar = androidJar,
      aapt2 = aapt2,
    ) to null
  }

  override fun isAvailable(host: ServeHost, preview: ServePreview): Boolean =
    unavailableReason(host, preview) == null

  override fun unavailableReason(host: ServeHost, preview: ServePreview): String? {
    val hasPrelinkedApk = host.hasWatchFaceResourceApk(preview.id)
    val (_, envError) = resolveEnvironment(requireAapt2 = !hasPrelinkedApk)
    if (envError != null) return envError
    if (!hasPrelinkedApk && !host.canDownloadExecutableBundle(preview.id)) {
      return "wfRenderer '${WatchFaceRendererIds.DWF_APK}' requires a live watch-face bundle " +
        "on this host (static snapshot catalogs without a bundle cannot run in the APK daemon)"
    }
    return null
  }

  override fun render(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): RenderOutcome {
    val prelinkedApk = host.watchFaceResourceApk(preview.id)
    val (env, envError) = resolveEnvironment(requireAapt2 = prelinkedApk == null)
    if (env == null) return RenderOutcome.Failed(envError ?: "DWF APK daemon unavailable")
    val bundleBytes =
      if (prelinkedApk != null) null
      else
        host.executableBundle(preview.id)
          ?: return RenderOutcome.Failed(
            "wfRenderer '${WatchFaceRendererIds.DWF_APK}': watch-face bundle is unavailable for '${preview.id}'",
          )

    return runCatching {
      synchronized(lock) {
        val wffApk = ensureWffApk(env, preview, prelinkedApk, bundleBytes)
        val runtimeClasses = ensureRuntimeClasses(env)
        val daemon = ensureDaemon(env, runtimeClasses, wffApk)
        daemon.pushWatchFace(wffApk)
        applyOverrides(daemon, preview, overrides)
        val shotFile = Files.createTempFile("dwf-apk-shot-", ".png").toFile()
        try {
          daemon.command(
            "screenshot",
            buildJsonObject {
              put("path", shotFile.absolutePath)
              put("square", false)
            },
          )
          val bytes = Files.readAllBytes(shotFile.toPath())
          if (bytes.isEmpty()) {
            RenderOutcome.Failed("DWF APK daemon produced an empty screenshot")
          } else {
            RenderOutcome.Ok(bytes, RenderOutcome.Generation.DAEMON)
          }
        } finally {
          shotFile.delete()
        }
      }
    }
      .getOrElse { err ->
        synchronized(lock) {
          activeDaemon?.close()
          activeDaemon = null
        }
        RenderOutcome.Failed("DWF APK daemon render failed: ${err.message ?: err}")
      }
  }

  override fun complications(
    host: ServeHost,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsOutcome {
    val (_, envError) = resolveEnvironment(requireAapt2 = !host.hasWatchFaceResourceApk(preview.id))
    if (envError != null) return WatchFaceComplicationsOutcome.Unavailable(envError)
    val base: WatchFaceComplicationsPayload =
      preview.watchFace ?: return WatchFaceComplicationsOutcome.NotFound
    return WatchFaceOverrides.projectComplicationsOutcome(
      base,
      overrides,
      WatchFaceRendererIds.DWF_APK,
    )
  }

  private fun applyOverrides(
    daemon: SimulatorDaemonProcess,
    preview: ServePreview,
    overrides: PreviewOverrides,
  ) {
    val (commands, newOverriddenSlots) =
      buildOverrideCommands(preview, overrides, daemon.overriddenSlots())
    for ((cmd, payload) in commands) {
      daemon.command(cmd, payload)
    }
    daemon.setOverriddenSlots(newOverriddenSlots)
  }

  internal fun buildOverrideCommands(
    preview: ServePreview,
    overrides: PreviewOverrides,
    previouslyOverriddenSlots: Set<Int> = emptySet(),
  ): Pair<List<Pair<String, JsonObject>>, Set<Int>> {
    val commands = ArrayList<Pair<String, JsonObject>>()
    val rc = WatchFaceOverrides.effectiveRcNamedValues(overrides, preview.watchFace)
    val named = overrides.namedOverrides.orEmpty()

    val ambientState = overrides.ambient
    if (ambientState != null) {
      val isAmbient = ambientState.state == AmbientStateOverride.AMBIENT
      commands +=
        "ambient" to
          buildJsonObject {
            put("state", if (isAmbient) "AMBIENT" else "INTERACTIVE")
            ambientState.burnInProtectionRequired?.let { put("burnIn", it) }
            ambientState.deviceHasLowBitAmbient?.let { put("lowBit", it) }
          }
    } else {
      val rcAmb = (rc["dwf.is_ambient"] as? RemoteNamedValue.BooleanValue)?.value
      commands +=
        "ambient" to
          buildJsonObject { put("state", if (rcAmb == true) "AMBIENT" else "INTERACTIVE") }
    }

    overrides.clockEpochMillis?.let { epochMillis ->
      commands += "clock" to buildJsonObject { put("timeMillis", epochMillis) }
    }

    val heartRate =
      (named["wf.heartRate"] as? PreviewOverrideValue.FloatValue)?.value?.toDouble()
        ?: (named["wf.heartRate"] as? PreviewOverrideValue.IntValue)?.value?.toDouble()
        ?: (rc["metrics.heart_rate"] as? RemoteNamedValue.FloatValue)?.value?.toDouble()
    val stepCount =
      (named["wf.stepCount"] as? PreviewOverrideValue.IntValue)?.value
        ?: (rc["metrics.day.steps"] as? RemoteNamedValue.IntValue)?.value
    if (heartRate != null || stepCount != null) {
      commands +=
        "health" to
          buildJsonObject {
            heartRate?.let { put("heartRate", it) }
            stepCount?.let { put("steps", it) }
          }
    }

    val batteryPercent =
      (named["wf.batteryPercent"] as? PreviewOverrideValue.FloatValue)?.value?.toInt()
        ?: (named["wf.batteryPercent"] as? PreviewOverrideValue.IntValue)?.value
        ?: (rc["dwf.battery_percent"] as? RemoteNamedValue.FloatValue)?.value?.toInt()
    if (batteryPercent != null) {
      commands += "battery" to buildJsonObject { put("level", batteryPercent) }
    }

    val flavorId =
      (named["wf.flavor"] as? PreviewOverrideValue.StringValue)?.value?.takeIf { it.isNotBlank() }
        ?: (rc["dwf.flavor"] as? RemoteNamedValue.StringValue)?.value?.takeIf { it.isNotBlank() }

    val configValues = LinkedHashMap<String, String>()
    for ((k, v) in named) {
      if (!k.startsWith("wf.config.")) continue
      val cfgId = k.removePrefix("wf.config.")
      if (cfgId.isBlank()) continue
      val strVal =
        when (v) {
          is PreviewOverrideValue.StringValue -> v.value
          is PreviewOverrideValue.BooleanValue -> v.value.toString()
          is PreviewOverrideValue.IntValue -> v.value.toString()
          is PreviewOverrideValue.FloatValue -> v.value.toString()
          else -> null
        } ?: continue
      configValues[cfgId] = strVal
    }
    val overriddenSlotIds = buildSet {
      for (k in rc.keys) {
        if (k.startsWith("dwf.complication.")) {
          k.removePrefix("dwf.complication.").substringBefore('.').toIntOrNull()?.let(::add)
        }
      }
      for (k in named.keys) {
        if (k.startsWith("wf.slot.")) {
          k.removePrefix("wf.slot.").substringBefore('.').toIntOrNull()?.let(::add)
        }
      }
    }
    for (staleSlot in previouslyOverriddenSlots - overriddenSlotIds) {
      commands +=
        "watchface" to
          buildJsonObject {
            put("op", "uncomplication")
            put("slot", staleSlot)
          }
    }

    commands +=
      "watchface" to
        buildJsonObject {
          put("op", "config")
          put("reset", true)
          if (flavorId != null) {
            put("flavor", flavorId)
          }
          if (configValues.isNotEmpty()) {
            put("values", buildJsonObject { configValues.forEach { (k, v) -> put(k, v) } })
          }
        }
    if (overriddenSlotIds.isNotEmpty()) {
      val projectedBySlot =
        preview.watchFace
          ?.let { WatchFaceOverrides.projectComplicationsPayload(it, overrides) }
          ?.slots
          ?.associateBy { it.slotId }
          .orEmpty()
      for (slotId in overriddenSlotIds) {
        val active = projectedBySlot[slotId]?.activeState
        val p = "dwf.complication.$slotId"
        val monoImage =
          (named["wf.slot.$slotId.monochromaticImage"] as? PreviewOverrideValue.StringValue)
            ?.value
            ?.takeIf { isImageReference(it) }
            ?: (rc["$p.monochromatic_image"] as? RemoteNamedValue.StringValue)?.value?.takeIf {
              isImageReference(it)
            }
        val smallImage =
          (named["wf.slot.$slotId.smallImage"] as? PreviewOverrideValue.StringValue)
            ?.value
            ?.takeIf { isImageReference(it) }
            ?: (rc["$p.small_image"] as? RemoteNamedValue.StringValue)?.value?.takeIf {
              isImageReference(it)
            }
        val photoImage =
          (named["wf.slot.$slotId.photoImage"] as? PreviewOverrideValue.StringValue)
            ?.value
            ?.takeIf { isImageReference(it) }
            ?: (rc["$p.photo_image"] as? RemoteNamedValue.StringValue)?.value?.takeIf {
              isImageReference(it)
            }
        if (active != null) {
          commands +=
            buildSlotComplicationCommand(
              slotId = slotId,
              typeName = active.type,
              text = active.text,
              title = active.title,
              value = active.value,
              min = active.min,
              max = active.max,
              colors = active.colors,
              weights = active.weights,
              monochromaticImage = monoImage,
              smallImage = smallImage,
              photoImage = photoImage,
            )
        } else {
          val typeCode = (rc["$p.type"] as? RemoteNamedValue.IntValue)?.value
          val typeName =
            typeCode?.let { WatchFaceOverrides.complicationTypeName(it) } ?: "SHORT_TEXT"
          commands +=
            buildSlotComplicationCommand(
              slotId = slotId,
              typeName = typeName,
              text = (rc["$p.text"] as? RemoteNamedValue.StringValue)?.value,
              title = (rc["$p.title"] as? RemoteNamedValue.StringValue)?.value,
              value = (rc["$p.value"] as? RemoteNamedValue.FloatValue)?.value,
              min = (rc["$p.min"] as? RemoteNamedValue.FloatValue)?.value,
              max = (rc["$p.max"] as? RemoteNamedValue.FloatValue)?.value,
              colors = emptyList(),
              weights = emptyList(),
              monochromaticImage = monoImage,
              smallImage = smallImage,
              photoImage = photoImage,
            )
        }
      }
    }
    return commands to overriddenSlotIds
  }

  private fun isImageReference(raw: String): Boolean {
    val v = raw.trim()
    return v.startsWith("http://", ignoreCase = true) ||
      v.startsWith("https://", ignoreCase = true) ||
      v.startsWith("file:", ignoreCase = true) ||
      v.startsWith("data:image/", ignoreCase = true)
  }

  private fun buildSlotComplicationCommand(
    slotId: Int,
    typeName: String?,
    text: String?,
    title: String?,
    value: Float?,
    min: Float?,
    max: Float?,
    colors: List<String>,
    weights: List<Float>,
    monochromaticImage: String? = null,
    smallImage: String? = null,
    photoImage: String? = null,
  ): Pair<String, JsonObject> {
    val resolvedType = typeName?.trim()?.uppercase(Locale.ROOT) ?: "SHORT_TEXT"
    if (resolvedType == "EMPTY") {
      return "watchface" to
        buildJsonObject {
          put("op", "uncomplication")
          put("slot", slotId)
        }
    }
    return "watchface" to
      buildJsonObject {
        put("op", "complication")
        put("slot", slotId)
        put("type", resolvedType)
        text?.let { put("text", it) }
        title?.let { put("title", it) }
        value?.let { put("value", it.toDouble()) }
        min?.let { put("min", it.toDouble()) }
        max?.let { put("max", it.toDouble()) }
        monochromaticImage?.let { put("monochromaticImage", it) }
        smallImage?.let { put("smallImage", it) }
        photoImage?.let { put("photoImage", it) }
        if (colors.isNotEmpty()) {
          put("colors", buildJsonArray { colors.forEach { add(JsonPrimitive(it)) } })
        }
        if (weights.isNotEmpty()) {
          put("weights", buildJsonArray { weights.forEach { add(JsonPrimitive(it.toDouble())) } })
        }
      }
  }

  private fun ensureDaemon(
    env: Environment,
    runtimeClasses: File,
    initialWffApk: File,
  ): SimulatorDaemonProcess {
    val existing = activeDaemon
    if (existing != null && existing.isAlive() && existing.runtimeApk == env.runtimeApk) {
      return existing
    }
    existing?.close()
    val spawned = SimulatorDaemonProcess.start(env, runtimeClasses, initialWffApk)
    activeDaemon = spawned
    return spawned
  }

  private fun ensureRuntimeClasses(env: Environment): File {
    val sha = sha256(env.runtimeApk)
    val cacheDir = composeAiCacheDir("dwf-apk-daemon").apply { mkdirs() }
    val outJar = File(cacheDir, "runtime-v2-$sha.jar")
    if (outJar.isFile && outJar.length() > 0L) return outJar

    val simulatorHostJar =
      env.simulatorJars.firstOrNull { it.name.startsWith("simulator-host") }
        ?: error("lib-simulator has no simulator-host jar")
    val cp =
      (listOf(simulatorHostJar) + env.dex2jarJars + env.simulatorJars + env.androidDaemonJars)
        .distinctBy { it.absolutePath }
        .joinToString(File.pathSeparator) { it.absolutePath }
    val tmpJar = File(cacheDir, "runtime-$sha.jar.tmp")
    tmpJar.delete()
    val proc =
      ProcessBuilder(
          javaBinary(),
          "-cp",
          cp,
          "ee.schimke.composeai.simulator.ApkClassConverter",
          env.runtimeApk.absolutePath,
          tmpJar.absolutePath,
          env.androidJar.absolutePath,
        )
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val finished = proc.waitFor(120, TimeUnit.SECONDS)
    if (!finished || proc.exitValue() != 0 || !tmpJar.isFile) {
      tmpJar.delete()
      error("ApkClassConverter failed for ${env.runtimeApk.name}: $output")
    }
    Files.move(tmpJar.toPath(), outJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    return outJar
  }

  init {
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          synchronized(lock) {
            activeDaemon?.close()
            activeDaemon = null
          }
        },
      )
  }

  private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
  private val ANDROID_RES_TYPE_PREFIXES =
    listOf(
      "anim",
      "animator",
      "color",
      "drawable",
      "font",
      "interpolator",
      "layout",
      "menu",
      "mipmap",
      "navigation",
      "raw",
      "transition",
      "values",
      "xml",
    )

  internal fun decodeBundleZipBytes(bytes: ByteArray): ByteArray {
    if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    if (bytes.size >= PNG_SIG.size && PNG_SIG.indices.all { bytes[it] == PNG_SIG[it] }) {
      var offset = PNG_SIG.size
      while (offset + 12 <= bytes.size) {
        val length =
          ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        if (length < 0 || offset.toLong() + 12L + length.toLong() > bytes.size.toLong()) break
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        offset += 12 + length
        if (type == "IEND") {
          return if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)
        }
      }
    }
    return bytes
  }

  internal fun extractJarAndroidResources(jarBytes: ByteArray, resOutDir: File): Boolean {
    var extractedAny = false
    val canonicalRoot = resOutDir.canonicalPath + File.separator
    ZipInputStream(ByteArrayInputStream(jarBytes)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val normalized = entry.name.removePrefix("/").removePrefix("res/")
          val slash = normalized.indexOf('/')
          if (slash > 0) {
            val topDir = normalized.substring(0, slash)
            val baseType = topDir.substringBefore('-')
            if (baseType in ANDROID_RES_TYPE_PREFIXES) {
              val target = File(resOutDir, normalized)
              if (target.canonicalPath.startsWith(canonicalRoot)) {
                target.parentFile?.mkdirs()
                Files.copy(zin, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                extractedAny = true
              }
            }
          }
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
    return extractedAny
  }

  internal fun resolveRobolectricDepsDir(): File? {
    val explicit =
      System.getProperty("robolectric.dependency.dir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("COMPOSE_PREVIEW_ROBOLECTRIC_DEPS_DIR")?.takeIf { it.isNotBlank() }
    if (explicit != null) {
      return File(explicit).absoluteFile.takeIf(File::isDirectory)
    }
    val cached = composeAiCacheDir("robolectric-deps")
    return cached.takeIf { dir ->
      dir.isDirectory &&
        dir.listFiles()?.any { f ->
          f.isFile && f.name.startsWith("android-all-instrumented-") && f.name.endsWith(".jar")
        } == true
    }
  }

  private fun ensureWffApk(
    env: Environment,
    preview: ServePreview,
    prelinkedResourceApk: ByteArray?,
    bundleBytes: ByteArray?,
  ): File {
    val decodedBundleZip = bundleBytes?.let(::decodeBundleZipBytes)
    val carriedResourceApk =
      prelinkedResourceApk
        ?: decodedBundleZip?.let {
          ee.schimke.composeai.bundle.AndroidBundleResources.extractResourceApkBytes(it)
        }
    val sourceBytes =
      carriedResourceApk
        ?: decodedBundleZip
        ?: error("no watch-face bundle or resource APK available")
    val digest =
      sha256Bytes(sourceBytes) + "_" + (preview.dwfAssetPath ?: preview.id).hashCode().toString(16)
    val cacheDir = File(composeAiCacheDir("dwf-apk-daemon"), "wff-$digest")
    val apkFile = File(cacheDir, "watchface.apk")
    if (apkFile.isFile && apkFile.length() > 0L) return apkFile

    cacheDir.mkdirs()
    if (carriedResourceApk != null) {
      val tmpApk = File(cacheDir, "watchface.apk.tmp")
      Files.write(tmpApk.toPath(), carriedResourceApk)
      Files.move(
        tmpApk.toPath(),
        apkFile.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
      return apkFile
    }

    val rawBundleZip = decodedBundleZip ?: error("watch-face bundle bytes missing")
    val aapt2 =
      env.aapt2
        ?: error(
          "wfRenderer '${WatchFaceRendererIds.DWF_APK}' needs Android SDK `build-tools/*/aapt2` " +
            "when bundle does not carry pre-linked `android/resources.ap_`",
        )
    val extractDir = File(cacheDir, "extracted").apply { mkdirs() }
    unpackZipInto(rawBundleZip, extractDir)
    val appJar = File(extractDir, "classes/app.jar")
    val jarResDir = File(extractDir, "jar-res")
    if (
      appJar.isFile &&
        !File(extractDir, "classes/res").isDirectory &&
        !File(extractDir, "res").isDirectory
    ) {
      extractJarAndroidResources(Files.readAllBytes(appJar.toPath()), jarResDir)
    }

    val resDir =
      listOf(File(extractDir, "classes/res"), File(extractDir, "res"), jarResDir).firstOrNull {
        it.isDirectory && !it.list().isNullOrEmpty()
      } ?: error("bundle has no res/ directory for watch face '${preview.id}'")

    val xmlDir = File(resDir, "xml").apply { mkdirs() }
    val infoXml = File(xmlDir, "watch_face_info.xml")
    if (!infoXml.isFile) {
      infoXml.writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
    <Preview value="@drawable/preview" />
</WatchFaceInfo>
""",
      )
    }

    val safePkg =
      "ee.schimke.composeai.wff." +
        preview.id.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_").take(40)
    val manifestFile = File(cacheDir, "AndroidManifest.xml")
    manifestFile.writeText(
      """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$safePkg"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="35" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:hasCode="false" android:label="${preview.label.replace("\"", "")}">
        <property
            android:name="com.google.wear.watchface.format.version"
            android:value="2" />
    </application>
</manifest>
""",
    )

    val compiledZip = File(cacheDir, "compiled-res.zip")
    runTool(
      aapt2.absolutePath,
      "compile",
      "--dir",
      resDir.absolutePath,
      "-o",
      compiledZip.absolutePath,
    )
    val tmpApk = File(cacheDir, "watchface.apk.tmp")
    runTool(
      aapt2.absolutePath,
      "link",
      "-I",
      env.androidJar.absolutePath,
      "--manifest",
      manifestFile.absolutePath,
      "-o",
      tmpApk.absolutePath,
      compiledZip.absolutePath,
    )
    Files.move(tmpApk.toPath(), apkFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    return apkFile
  }

  private fun unpackZipInto(zipBytes: ByteArray, destDir: File) {
    destDir.mkdirs()
    val canonicalRoot = destDir.canonicalPath + File.separator
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val target = File(destDir, entry.name)
          if (target.canonicalPath.startsWith(canonicalRoot)) {
            target.parentFile?.mkdirs()
            Files.copy(zin, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
  }

  private fun runTool(vararg cmd: String) {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    val ok = proc.waitFor(60, TimeUnit.SECONDS)
    if (!ok || proc.exitValue() != 0) {
      error("${cmd.first()} failed (${if (ok) proc.exitValue() else "timeout"}): $out")
    }
  }

  private fun locateAndroidJar(): File? {
    val roots =
      listOfNotNull(
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() },
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() },
        System.getProperty("composeai.androidHome")?.takeIf { it.isNotBlank() },
      )
    for (root in roots) {
      val platforms = File(root, "platforms")
      val jars =
        platforms
          .listFiles()
          ?.filter { it.isDirectory }
          ?.sortedByDescending { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
          ?.map { File(it, "android.jar") }
          ?.filter { it.isFile }
          .orEmpty()
      if (jars.isNotEmpty()) return jars.first()
    }
    return null
  }

  private fun locateAapt2(): File? {
    val roots =
      listOfNotNull(
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() },
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() },
      )
    for (root in roots) {
      val buildTools = File(root, "build-tools")
      val bins =
        buildTools
          .listFiles()
          ?.filter { it.isDirectory }
          ?.sortedByDescending { it.name }
          ?.map { File(it, "aapt2") }
          ?.filter { it.isFile && it.canExecute() }
          .orEmpty()
      if (bins.isNotEmpty()) return bins.first()
    }
    return null
  }

  private fun javaBinary(): String =
    File(System.getProperty("java.home"), "bin/java").takeIf { it.isFile }?.absolutePath ?: "java"

  private fun sha256(file: File): String = sha256Bytes(Files.readAllBytes(file.toPath()))

  private fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private class SimulatorDaemonProcess(
    val runtimeApk: File,
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader,
  ) : AutoCloseable {
    private val nextId = AtomicInteger(1)
    private var lastPushedApk: String? = null
    private var overriddenSlotsOnDaemon: Set<Int> = emptySet()

    fun isAlive(): Boolean = process.isAlive

    fun overriddenSlots(): Set<Int> = overriddenSlotsOnDaemon

    fun setOverriddenSlots(slots: Set<Int>) {
      overriddenSlotsOnDaemon = slots
    }

    fun pushWatchFace(wffApk: File) {
      val path = wffApk.canonicalPath
      if (lastPushedApk == path) return
      command(
        "watchface",
        buildJsonObject {
          put("op", "push")
          put("apk", path)
          put("activate", true)
        },
      )
      lastPushedApk = path
      overriddenSlotsOnDaemon = emptySet()
    }

    fun command(cmd: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement? {
      val id = nextId.getAndIncrement()
      val msg = buildJsonObject {
        put("id", id)
        put("cmd", cmd)
        payload.forEach { (k, v) -> put(k, v) }
      }
      writer.write(msg.toString())
      writer.newLine()
      writer.flush()

      while (true) {
        val line = reader.readLine() ?: error("DWF APK daemon closed stdout unexpectedly")
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) continue
        val obj =
          runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: continue
        val respId = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
        if (respId != id) continue
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
          val errMsg = obj["error"]?.jsonPrimitive?.content ?: "unknown error"
          error("$cmd failed: $errMsg")
        }
        return obj["result"]
      }
    }

    override fun close() {
      runCatching {
        writer.close()
        reader.close()
      }
      process.destroyForcibly()
    }

    companion object {
      fun start(
        env: Environment,
        runtimeClasses: File,
        initialWffApk: File,
      ): SimulatorDaemonProcess {
        val simulatorHostJar =
          env.simulatorJars.firstOrNull { it.name.startsWith("simulator-host") }
            ?: error("lib-simulator has no simulator-host jar")
        val cp =
          (env.simulatorJars + env.androidDaemonJars + listOf(env.androidJar))
            .distinctBy { it.absolutePath }
            .joinToString(File.pathSeparator) { it.absolutePath }
        val robolectricDepsDir = resolveRobolectricDepsDir()
        val cmd = buildList {
          add(javaBinary())
          add("-XX:G1PeriodicGCInterval=5000")
          add("--enable-native-access=ALL-UNNAMED")
          add("--add-opens=java.base/java.net=ALL-UNNAMED")
          add("--add-opens=java.base/java.io=ALL-UNNAMED")
          add("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
          add("-Djava.awt.headless=true")
          add("-javaagent:${simulatorHostJar.absolutePath}")
          if (robolectricDepsDir != null) {
            add("-Drobolectric.offline=true")
            add("-Drobolectric.dependency.dir=${robolectricDepsDir.absolutePath}")
          }
          add("-Drobolectric.createActivityContexts=true")
          add("-Drobolectric.useEmbeddedViewRoot=true")
          add("-Drobolectric.deviceconfig.sdk=35")
          add("-Dcomposeai.simulator.control=stdio")
          add("-Dcomposeai.simulator.formFactor=wear")
          add("-Dcomposeai.simulator.launcher=true")
          add("-Dcomposeai.simulator.noLaunch=true")
          add("-Dcomposeai.simulator.wffApks=${initialWffApk.absolutePath}")
          add("-Dcomposeai.simulator.qualifiers=w227dp-h227dp-round-watch-xhdpi")
          add("-cp")
          add(cp)
          add("ee.schimke.composeai.simulator.LaunchMain")
          add(env.runtimeApk.absolutePath)
          add(runtimeClasses.absolutePath)
        }
        val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val daemon = SimulatorDaemonProcess(env.runtimeApk, proc, writer, reader)
        // Wait for initial `watchface status` round-trip so we know the sandbox is ready.
        daemon.command("watchface", buildJsonObject { put("op", "status") })
        return daemon
      }
    }
  }
}
