package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath

/**
 * `compose-preview record` — turn a session script into a repeatable recording (GIF / APNG / MP4 /
 * WebM) of an already-compiled `@Preview`, with one command and zero MCP / daemon / protocol
 * knowledge required.
 *
 * The command is a thin author-facing wrapper over the existing scripted-recording machinery: it
 * discovers the target module (so the classpath + preview spec come for free from the gradle
 * plugin), opens a short-lived [RenderSession] against the module's daemon, then drives the
 * standard start → script → stop → encode sequence and copies the artifact to `--out`. The daemon
 * is spawned, driven, and shut down inside this one invocation — the user never manages a server.
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
 * The script file is a JSON array of `RecordingScriptEvent` — the same vocabulary agents already
 * emit through MCP `record_preview` (`input.pointerDown` / `pointerMove` / `pointerUp` / `click`,
 * `input.keyDown` / `keyUp`, `recording.probe`, …). Recordings tick on a virtual clock keyed to
 * `fps`, so the same script reproduces the same frames every run.
 *
 * **Assertions.** The script can also carry Maestro-style `assert.visible` / `assert.notVisible`
 * events, each with a `target` (ref / testTag / role+text). They resolve against the live semantics
 * tree at their `tMs`; if any assertion isn't met the command still writes the recording (the
 * frames show why it failed) but exits non-zero (code 2), turning a recording into a check CI can
 * gate on:
 * ```json
 * { "tMs": 1500, "kind": "assert.visible", "target": { "text": "Submit" } }
 * ```
 *
 * **Accessibility gate.** `--fail-on a11y[=errors|warnings]` (issue #1966) fetches the preview's
 * `a11y/atf` findings through the same session after recording and exits non-zero if the threshold
 * is tripped — the Espresso/ATF "fail on a11y violations" model, bundled into the record command.
 * ATF findings are Android-produced; on a desktop preview the findings are empty and the gate is a
 * no-op (with a clear note).
 */
class RecordPreviewCommand(args: List<String>) : Command(args) {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val previewRef: String? = exactId ?: args.flagValue("--preview") ?: filter
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
   * `--fail-on a11y[=errors|warnings]` (issue #1966) — after recording, fetch the preview's
   * `a11y/atf` findings through the open session and exit non-zero if the chosen threshold is
   * tripped. `null` (the default) leaves a11y gating off. ATF findings are Android-produced; on a
   * desktop preview the findings are empty (documented in [RecordA11yGate]).
   */
  private val a11yThreshold: A11yThreshold? = parseFailOnA11y(args.flagValue("--fail-on"))

  private fun parseFailOnA11y(raw: String?): A11yThreshold? {
    val v = raw?.trim()?.lowercase()?.ifEmpty { null } ?: return null
    if (v != "a11y" && !v.startsWith("a11y=")) {
      fail("unsupported --fail-on '$raw'; expected a11y, a11y=errors, or a11y=warnings")
    }
    val token = v.substringAfter('=', "errors")
    return A11yThreshold.parse(token)
      ?: fail("unsupported --fail-on a11y level '$token'; use errors or warnings")
  }

  override fun run() {
    val previewRef = requireFlag(previewRef, "--preview", "a preview reference")
    val scriptPath = requireFlag(scriptPath, "--script", "a session-script JSON file")
    val outPath = requireFlag(outPath, "--out", "an output file path")

    val scriptFile = File(scriptPath)
    if (!scriptFile.isFile) {
      fail("script file not found: ${scriptFile.absolutePath}")
    }
    val events = parseScript(scriptFile)
    if (events.isEmpty()) {
      System.err.println(
        "compose-preview record: warning — script '$scriptPath' contained no events; " +
          "the recording will be a single static frame."
      )
    }

    val format = resolveFormat(formatFlag, outPath)
    val overrides = parseOverrides(overridePairs)

    // Phase 1: discover the module + its preview spec and refresh the daemon descriptor. Runs
    // inside
    // the gradle connection; we capture everything the daemon-driven phase needs and let the
    // connection close before spawning the daemon (the spawn reads the on-disk descriptor, not the
    // tooling-api model).
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
      // composePreviewRenderAll doesn't write daemon-launch.json; composePreviewDaemonStart does,
      // and
      // both depend on composePreviewDiscover so previews.json is fresh for the preview lookup.
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
      // a11y gate (issue #1966): fetch the preview's ATF findings through the still-open session.
      val a11yGate = a11yThreshold?.let { fetchA11yGate(live, target.previewId, it, overrides) }
      RecordingOutcome(scriptEvents = stopped.scriptEvents, encoded = encoded, a11yGate = a11yGate)
    }

    val outFile = copyArtifact(outcome.encoded.videoPath, outPath)
    println(
      "Recorded ${target.previewId} → ${outFile.path} " +
        "(${format.name.lowercase()}, ${outcome.encoded.sizeBytes} bytes)"
    )

    // Gate on assertions last, after the artifact is on disk — a failing recording is still worth
    // keeping (the captured frames show *why* the assertion failed). A non-zero exit lets CI /
    // agents
    // treat a recording as a check, the way Maestro's `assertVisible` fails a flow.
    //
    // Two failure shapes count: a FAILED assertion (the condition was evaluated and not met), and
    // an
    // `assert.*` event that came back anything other than APPLIED — most commonly UNSUPPORTED on a
    // backend that doesn't advertise assertions (e.g. Android today). An assertion that never ran
    // is
    // NOT a pass; treating it as one would let a CI recording exit 0 while silently skipping the
    // check it was written to enforce.
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
    }
    var a11yFailed = false
    when (val gate = outcome.a11yGate) {
      null -> {} // gating not requested
      is A11yGateOutcome.NotApplicable -> {
        // Backend can't produce ATF findings (desktop overlay-only a11y, or kind not advertised).
        // Don't trip — there's legitimately nothing to check — but say so rather than silently
        // pass.
        System.err.println(
          "compose-preview record: --fail-on a11y had nothing to check for ${target.previewId} " +
            "(${gate.reason})."
        )
      }
      is A11yGateOutcome.EvaluationFailed -> {
        // The a11y producer errored (fetch/budget failure). The user asked to gate on a11y and the
        // check couldn't run — fail closed rather than exit 0 on an unevaluated check.
        System.err.println(
          "compose-preview record: a11y check could not be evaluated for ${target.previewId} " +
            "(${gate.reason}); failing closed."
        )
        a11yFailed = true
      }
      is A11yGateOutcome.Evaluated -> {
        if (gate.result.tripped) {
          System.err.println(
            "compose-preview record: a11y check failed for ${target.previewId} — " +
              gate.result.summary()
          )
          a11yFailed = true
        } else if (verbose) {
          System.err.println("[record] a11y check passed — ${gate.result.summary()}")
        }
      }
    }
    if (failures.isNotEmpty() || a11yFailed) {
      exitProcess(2)
    }
  }

  /**
   * Fetch the preview's `a11y/atf` findings through the open session and evaluate the gate. Reuses
   * the daemon's existing a11y data product (the same one `compose-preview a11y` drives).
   *
   * Distinguishes three outcomes so the caller can fail *closed*: [A11yGateOutcome.Evaluated] (the
   * verdict), [A11yGateOutcome.NotApplicable] (backend produces no ATF — desktop, or kind not
   * advertised — don't trip), and [A11yGateOutcome.EvaluationFailed] (the producer errored — the
   * check the user requested couldn't run, so the command must fail rather than silently pass).
   */
  private fun fetchA11yGate(
    session: RenderSession,
    previewId: String,
    threshold: A11yThreshold,
    overrides: PreviewOverrides?,
  ): A11yGateOutcome {
    // The daemon registers `a11y` as inactive metadata; enable it so `data/fetch` for `a11y/atf`
    // resolves instead of returning "kind not advertised". Non-fatal — we still try the fetch.
    runCatching { session.enableExtensions(listOf("a11y")) }
      .onFailure {
        if (verbose) System.err.println("[record] extensions/enable a11y: ${it.message}")
      }
    // Force a fresh render in the recorded configuration before fetching, so the findings aren't
    // served from a stale `a11y-atf.json` left by an earlier run and reflect the same `--overrides`
    // the recording used (Codex review on #1980). Best-effort: if the a11y producer re-renders
    // independently of this call it falls back to the discovery-time spec — see the limitation
    // noted
    // in `docs/daemon/RECORDING-ASSERTIONS.md` (true recorded-state a11y is the future
    // assert.a11y).
    runCatching {
        session.renderNow(listOf(previewId), reason = "a11y-gate", overrides = overrides)
      }
      .onFailure { if (verbose) System.err.println("[record] a11y-gate pre-render: ${it.message}") }
    val payload =
      try {
        session.fetchData(previewId, ATF_KIND, inline = true, timeout = 120.seconds).payload
      } catch (e: DataProductException) {
        // NOT_AVAILABLE / UNKNOWN = the daemon doesn't produce ATF here (desktop overlay-only path,
        // or the kind isn't advertised) → nothing to check. FETCH_FAILED / BUDGET_EXCEEDED = the
        // producer tried and errored → the check couldn't run, so fail closed.
        return when (e.code) {
          DataProductException.NOT_AVAILABLE,
          DataProductException.UNKNOWN ->
            A11yGateOutcome.NotApplicable(
              "no ATF data for this backend — ATF findings come from the Android backend; a desktop " +
                "preview is overlay-only (${e.wireMessage})"
            )
          else -> A11yGateOutcome.EvaluationFailed("a11y producer error: ${e.wireMessage}")
        }
      } catch (e: RenderSessionException) {
        return A11yGateOutcome.EvaluationFailed(
          "a11y fetch transport error: ${e.message ?: e.javaClass.simpleName}"
        )
      }
    val findings = payload?.let(::parseA11yFindings).orEmpty()
    return A11yGateOutcome.Evaluated(evaluateA11yGate(findings, threshold))
  }

  private fun parseA11yFindings(payload: JsonElement): List<AccessibilityFinding> {
    val obj = payload as? JsonObject ?: return emptyList()
    val findings = obj["findings"] ?: return emptyList()
    return try {
      json.decodeFromJsonElement(ListSerializer(AccessibilityFinding.serializer()), findings)
    } catch (_: Exception) {
      emptyList()
    }
  }

  private data class RecordingOutcome(
    val scriptEvents: List<RecordingScriptEvidence>,
    val encoded: RecordingEncodeResult,
    val a11yGate: A11yGateOutcome?,
  )

  private companion object {
    private const val ATF_KIND = "a11y/atf"
  }

  private data class ResolvedModule(val projectDir: File, val previewId: String)

  // ---------------------------------------------------------------------------
  // Script + argument parsing.
  // ---------------------------------------------------------------------------

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
   * Pick the encoder. An explicit `--format` always wins; otherwise infer from the `--out`
   * extension (`.gif` / `.apng` / `.mp4` / `.webm`); falling back to APNG (the always-available
   * default) when neither pins it.
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
   * Resolve `--preview` against the discovered manifest. Accepts (in priority order): an exact
   * preview `id`, the `<className>.<functionName>` form the issue's UX uses, a bare `functionName`,
   * or a unique case-insensitive substring of an id. Fails with the candidate list when nothing
   * matches or more than one does.
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
   * Build a [PreviewOverrides] from `key=value` pairs. Supports the knobs that matter for recording
   * an existing preview — most notably `touchOverlay` (paints rings under dispatched pointers). The
   * full override surface lives on MCP `record_preview`; this is the friendly CLI subset.
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
          else ->
            fail(
              "unsupported --overrides key '$key'. Supported: touchOverlay, inspectionMode, " +
                "device, localeTag, fontScale, density, widthPx, heightPx"
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

  // ---------------------------------------------------------------------------
  // Output.
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

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
