package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * `compose-preview record` — turn a session script into a repeatable recording (GIF / APNG / MP4 /
 * WebM) of an already-compiled `@Preview`. Discovers the module, opens a short-lived
 * [RenderSession] against its daemon, runs start → script → stop → encode, and copies the artifact
 * to `--out`.
 *
 * ```
 * compose-preview record \
 *   --module :samples:cmp \
 *   --preview com.example.samplecmp.MultiTouchDrawingPreviewKt.MultiTouchDrawingPreview \
 *   --script demos/multi-touch-drawing/session.json \
 *   --overrides touchOverlay=true \
 *   --out demos/multi-touch-drawing/drawing-canvas-gestures.gif
 * ```
 *
 * The script is a JSON array of `RecordingScriptEvent` (the MCP `record_preview` vocabulary),
 * ticked on a virtual clock keyed to `fps` so it reproduces exactly.
 *
 * Scripts may carry `assert.visible` / `assert.notVisible` events with a `target`, resolved against
 * the live semantics tree at their `tMs`. If any fails, the recording is still written but the
 * command exits 2, so CI can gate on it:
 * ```json
 * { "tMs": 1500, "kind": "assert.visible", "target": { "text": "Submit" } }
 * ```
 */
class RecordPreviewCommand(args: List<String>) : Command(args) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * The preview to record: `--preview`, or `--id` / `--filter` as aliases. Exactly one is needed,
   * so it goes through [resolvePreviewId]'s staged resolver and ambiguity is an error.
   */
  private val recordedPreviewRef: String? = exactId ?: previewRef ?: filter
  private val scriptPath: String? = args.flagValue("--script")
  private val outPath: String? = args.flagValue("--out") ?: args.flagValue("--output")
  private val formatFlag: String? =
    args.flagValue("--format")?.trim()?.lowercase()?.ifEmpty { null }
  private val fps: Int? = args.flagValue("--fps")?.toIntOrNull()
  private val scale: Float? = args.flagValue("--scale")?.toFloatOrNull()
  private val overridePairs: List<String> =
    args
      .flagValuesAll("--overrides")
      .flatMap { it.split(',', ';') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  /**
   * Directory for `assert.pixels` baseline PNGs; relative `inputText` paths resolve against it
   * (default: the current directory).
   */
  private val baselineDir: String? = args.flagValue("--baseline-dir")

  override fun run() {
    val previewRef = requireFlag(recordedPreviewRef, "--preview", "a preview reference")
    val scriptPath = requireFlag(scriptPath, "--script", "a session-script JSON file")
    val outPath = requireFlag(outPath, "--out", "an output file path")

    val scriptFile = File(scriptPath)
    if (!scriptFile.isFile) {
      fail("script file not found: ${scriptFile.absolutePath}")
    }
    val events = resolveBaselines(parseScript(scriptFile))
    if (events.isEmpty()) {
      System.err.println(
        "compose-preview record: warning — script '$scriptPath' contained no events; " +
          "the recording will be a single static frame."
      )
    }

    val format = resolveFormat(formatFlag, outPath)
    // `--overrides` beats the shared settings file, key by key (the MCP server's precedence).
    val overrides = previewSettings.fillOverrides(parseOverrides(overridePairs))

    // Phase 1: discover the module and preview spec and refresh the daemon descriptor, then close
    // the Gradle connection before spawning the daemon (which reads the on-disk descriptor).
    var resolved: ResolvedModule? = null
    withGradle(silenceStdout = false) { gradle ->
      val modules = resolveModules(gradle)
      val module =
        when {
          modules.size == 1 -> modules.single()
          explicitModule != null -> modules.single() // resolveModules already narrowed to one
          else ->
            fail(
              "multiple preview modules found (${modules.joinToString(", ") { it.gradlePath }}); " +
                "pass --module to pick one"
            )
        }
      // `composePreviewDaemonStart` writes daemon-launch.json; both it and the render depend on
      // discovery.
      val ok =
        runGradle(
          gradle,
          ":${module.gradlePath}:composePreviewDiscover",
          ":${module.gradlePath}:composePreviewDaemonStart",
          arguments = gradleArgsWithForce(),
        )
      if (!ok) {
        fail("gradle discovery / daemon bootstrap failed for ${module.gradlePath}")
      }
      val manifests = readAllManifests(listOf(module))
      val previewId = resolvePreviewId(previewRef, manifests)
      resolved = ResolvedModule(projectDir = module.projectDir, previewId = previewId)
    }
    val target = resolved ?: fail("could not resolve preview module")

    // Phase 2: drive the recording against the module's daemon.
    val descriptorFile = File(target.projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptorFile.isFile) {
      fail(
        "daemon descriptor missing at ${descriptorFile.absolutePath}; " +
          "composePreviewDaemonStart should have written it — re-run with --verbose"
      )
    }
    val config =
      RenderSessionConfig(
        descriptorPath = descriptorFile,
        workspaceRoot = target.projectDir.absoluteFile,
        workspaceName = target.projectDir.name.ifBlank { "workspace" },
        logSink = { if (verbose) System.err.println("[record] $it") },
      )

    val session: RenderSession =
      try {
        SubprocessRenderSessions.open(config)
      } catch (e: RenderSessionException) {
        fail("failed to open render session: ${e.message ?: e.javaClass.simpleName}")
      }

    val outcome = session.use { live ->
      val started =
        live.recordingStart(target.previewId, fps = fps, scale = scale, overrides = overrides)
      if (events.isNotEmpty()) {
        live.recordingScript(started.recordingId, events)
      }
      val stopped = live.recordingStop(started.recordingId)
      if (verbose) {
        System.err.println(
          "[record] captured ${stopped.frameCount} frame(s) covering ${stopped.durationMs}ms " +
            "(${stopped.frameWidthPx}x${stopped.frameHeightPx}px)"
        )
      }
      val encoded =
        try {
          live.recordingEncode(started.recordingId, format = format)
        } catch (e: RenderSessionException) {
          fail(encodeFailureMessage(format, e))
        }
      RecordingOutcome(scriptEvents = stopped.scriptEvents, encoded = encoded)
    }

    val outFile = copyArtifact(outcome.encoded.videoPath, outPath)
    println(
      "Recorded ${target.previewId} → ${outFile.path} " +
        "(${format.name.lowercase()}, ${outcome.encoded.sizeBytes} bytes)"
    )

    // Gate on assertions after the artifact is written (the frames show why). A FAILED assertion
    // and an `assert.*` that wasn't APPLIED (e.g. UNSUPPORTED on Android) both fail: an assertion
    // that never ran is not a pass.
    val failures =
      outcome.scriptEvents.filter {
        it.status == RecordingScriptEventStatus.FAILED ||
          (it.kind.startsWith("assert.") && it.status != RecordingScriptEventStatus.APPLIED)
      }
    if (failures.isNotEmpty()) {
      System.err.println(
        "compose-preview record: ${failures.size} assertion(s) failed for ${target.previewId}:"
      )
      for (f in failures) {
        val statusNote =
          if (f.status == RecordingScriptEventStatus.UNSUPPORTED) " (unsupported by this backend)"
          else ""
        System.err.println(
          "  - [t=${f.tMs}ms] ${f.kind}$statusNote: ${f.message ?: "assertion not met"}"
        )
      }
      exitProcess(2)
    }
  }

  private data class RecordingOutcome(
    val scriptEvents: List<RecordingScriptEvidence>,
    val encoded: RecordingEncodeResult,
  )

  private data class ResolvedModule(val projectDir: File, val previewId: String)

  private fun parseScript(scriptFile: File): List<RecordingScriptEvent> {
    val text = fileSystem.read(scriptFile.path.toPath()) { readUtf8() }
    return try {
      json.decodeFromString(ListSerializer(RecordingScriptEvent.serializer()), text)
    } catch (e: Exception) {
      fail(
        "could not parse script '${scriptFile.path}' as a JSON array of RecordingScriptEvent: " +
          (e.message ?: e.javaClass.simpleName)
      )
    }
  }

  /**
   * Make `assert.pixels` baseline paths (carried in `inputText`) absolute against `--baseline-dir`,
   * so they don't depend on the daemon's working directory. `"assert.pixels"` mirrors
   * `RecordingScriptDataExtensions.ASSERT_PIXELS_EVENT` without the `:data-render-core` dependency.
   */
  private fun resolveBaselines(events: List<RecordingScriptEvent>): List<RecordingScriptEvent> {
    val base = File(baselineDir ?: ".")
    return events.map { e ->
      val path = e.inputText
      if (e.kind != "assert.pixels" || path.isNullOrBlank() || File(path).isAbsolute) e
      else e.copy(inputText = File(base, path).absolutePath)
    }
  }

  /**
   * Pick the encoder: `--format`, else the `--out` extension (`.gif` / `.apng` / `.mp4` / `.webm`),
   * else APNG.
   */
  private fun resolveFormat(formatFlag: String?, outPath: String): RecordingFormat {
    if (formatFlag != null) {
      return formatFlag.toRecordingFormatOrNull()
        ?: fail("unsupported --format '$formatFlag'; expected one of: apng, gif, mp4, webm")
    }
    val ext = outPath.substringAfterLast('.', "").lowercase()
    return ext.toRecordingFormatOrNull()
      ?: run {
        System.err.println(
          "compose-preview record: no --format and unrecognised --out extension '.$ext'; " +
            "defaulting to apng. Pass --format gif|apng|mp4|webm to choose explicitly."
        )
        RecordingFormat.APNG
      }
  }

  private fun String.toRecordingFormatOrNull(): RecordingFormat? =
    when (this) {
      "apng" -> RecordingFormat.APNG
      "gif" -> RecordingFormat.GIF
      "mp4" -> RecordingFormat.MP4
      "webm" -> RecordingFormat.WEBM
      else -> null
    }

  /**
   * Resolve `--preview` against the manifest, in priority order: exact id,
   * `<className>.<functionName>`, bare `functionName`, or a unique case-insensitive id substring.
   * Fails with the candidates when nothing or several match.
   */
  private fun resolvePreviewId(
    previewRef: String,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
  ): String {
    val previews = manifests.flatMap { (_, manifest) -> manifest.previews }
    if (previews.isEmpty()) {
      fail("no previews discovered in the target module — nothing to record")
    }
    val byId = previews.firstOrNull { it.id == previewRef }
    if (byId != null) return byId.id
    val byFqn = previews.firstOrNull { "${it.className}.${it.functionName}" == previewRef }
    if (byFqn != null) return byFqn.id
    val byFn = previews.filter { it.functionName == previewRef }
    if (byFn.size == 1) return byFn.single().id
    val bySubstring = previews.filter { it.id.contains(previewRef, ignoreCase = true) }
    if (bySubstring.size == 1) return bySubstring.single().id

    val candidates = previews.joinToString("\n") { "  - ${it.id}" }
    if (bySubstring.size > 1 || byFn.size > 1) {
      fail("preview reference '$previewRef' is ambiguous. Candidates:\n$candidates")
    }
    fail("preview '$previewRef' not found. Known previews:\n$candidates")
  }

  /**
   * Build [PreviewOverrides] from `key=value` pairs: the recording-relevant subset (e.g.
   * `touchOverlay`) of MCP `record_preview`'s overrides.
   */
  private fun parseOverrides(pairs: List<String>): PreviewOverrides? {
    if (pairs.isEmpty()) return null
    var overrides = PreviewOverrides()
    for (pair in pairs) {
      val key = pair.substringBefore('=').trim()
      val value = pair.substringAfter('=', "").trim()
      if (!pair.contains('=') || key.isEmpty()) {
        fail("invalid --overrides entry '$pair'; expected key=value")
      }
      overrides =
        when (key) {
          "touchOverlay" -> overrides.copy(touchOverlay = value.toBooleanFlag(key))
          "talkBack" -> overrides.copy(talkBack = value.toBooleanFlag(key))
          "inspectionMode" -> overrides.copy(inspectionMode = value.toBooleanFlag(key))
          "device" -> overrides.copy(device = value)
          "localeTag" -> overrides.copy(localeTag = value)
          "fontScale" -> overrides.copy(fontScale = value.toFloatOrFail(key))
          "density" -> overrides.copy(density = value.toFloatOrFail(key))
          "widthPx" -> overrides.copy(widthPx = value.toIntOrFail(key))
          "heightPx" -> overrides.copy(heightPx = value.toIntOrFail(key))
          // Pin the preview's wall clock for deterministic timestamps; the preview must read
          // `LocalClock`.
          "clockEpochMillis" -> overrides.copy(clockEpochMillis = value.toLongOrFail(key))
          else ->
            fail(
              "unsupported --overrides key '$key'. Supported: touchOverlay, inspectionMode, " +
                "device, localeTag, fontScale, density, widthPx, heightPx, clockEpochMillis"
            )
        }
    }
    return overrides
  }

  private fun String.toBooleanFlag(key: String): Boolean =
    when (lowercase()) {
      "true",
      "1",
      "yes",
      "on" -> true
      "false",
      "0",
      "no",
      "off" -> false
      else -> fail("--overrides $key expects a boolean; got '$this'")
    }

  private fun String.toFloatOrFail(key: String): Float =
    toFloatOrNull() ?: fail("--overrides $key expects a number; got '$this'")

  private fun String.toIntOrFail(key: String): Int =
    toIntOrNull() ?: fail("--overrides $key expects an integer; got '$this'")

  private fun String.toLongOrFail(key: String): Long =
    toLongOrNull() ?: fail("--overrides $key expects an integer; got '$this'")

  private fun copyArtifact(videoPath: String, outPath: String): File {
    val src = videoPath.toPath()
    val dst = outPath.toPath()
    val bytes =
      try {
        fileSystem.read(src) { readByteArray() }
      } catch (e: Exception) {
        fail("encoded recording missing at $videoPath: ${e.message ?: e.javaClass.simpleName}")
      }
    dst.parent?.let { fileSystem.createDirectories(it) }
    fileSystem.write(dst) { write(bytes) }
    return File(outPath)
  }

  private fun encodeFailureMessage(format: RecordingFormat, e: RenderSessionException): String {
    val base = "encode to ${format.name.lowercase()} failed: ${e.message ?: e.javaClass.simpleName}"
    return if (format == RecordingFormat.MP4 || format == RecordingFormat.WEBM) {
      "$base\nHint: mp4/webm require an `ffmpeg` binary on PATH. Use --format gif (or apng) for a " +
        "pure-JVM recording with no native dependency."
    } else {
      base
    }
  }

  private fun requireFlag(value: String?, flag: String, what: String): String {
    if (value.isNullOrBlank()) {
      fail("$flag is required ($what)")
    }
    return value
  }

  private fun fail(message: String): Nothing {
    System.err.println("compose-preview record: $message")
    exitProcess(1)
  }
}
