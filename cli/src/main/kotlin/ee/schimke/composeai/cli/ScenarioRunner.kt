package ee.schimke.composeai.cli

import java.awt.image.BufferedImage
import java.io.File
import java.io.PipedReader
import java.io.PipedWriter
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Declarative scenario runner for `compose-preview launch --scenario <file> [--report-dir <dir>]`.
 *
 * Parses JSON or YAML scenario documents, drives the simulator's JSON-lines control channel across
 * platform inputs (`clock`, `health`, `battery`, `weather`, `accel`, `ambient`, `watchface`, `tap`,
 * `tiles`, `notification`, etc.), captures named PNG snapshots, evaluates structured state, color
 * tolerance (`#RRGGBB±tol`), and pixel-level frame assertions (`frame.changedFrom`,
 * `frame.unchangedFrom`, `frame.litPixelRatio`, `frame.regionColor`), and writes `report.json`.
 */
internal object ScenarioRunner {

  const val REPORT_SCHEMA = "compose-preview-scenario-report/v1"
  private const val DEFAULT_READY_TIMEOUT_MS = 120_000L
  private const val DEFAULT_COMMAND_TIMEOUT_MS = 45_000L

  private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  internal interface Transport : AutoCloseable {
    fun awaitReady(expectedDevices: Int = 1, timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS)

    fun send(command: JsonObject, timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS): JsonObject

    fun events(): List<JsonObject>
  }

  internal data class ScenarioSpec(
    val name: String,
    val steps: List<JsonObject>,
  )

  internal data class SnapshotRecord(
    val name: String,
    val file: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val litPixelRatio: Double,
    val image: BufferedImage,
    val status: JsonObject?,
  )

  internal data class AssertionOutcome(
    val key: String,
    val passed: Boolean,
    val expected: JsonElement,
    val actual: String,
    val detail: String? = null,
  )

  internal data class StepOutcome(
    val index: Int,
    val action: String,
    val passed: Boolean,
    val durationMs: Long,
    val error: String? = null,
    val snapshot: String? = null,
    val assertions: List<AssertionOutcome> = emptyList(),
  )

  internal data class ScenarioReport(
    val name: String,
    val passed: Boolean,
    val durationMs: Long,
    val steps: List<StepOutcome>,
    val snapshots: Map<String, SnapshotRecord>,
    val reportFile: File,
  ) {
    fun toJson(): JsonObject = buildJsonObject {
      put("schema", REPORT_SCHEMA)
      put("name", name)
      put("ok", passed)
      put("durationMs", durationMs)
      put(
        "snapshots",
        buildJsonArray {
          for (snap in snapshots.values) {
            add(
              buildJsonObject {
                put("name", snap.name)
                put("path", snap.file.absolutePath)
                put("sha256", snap.sha256)
                put("width", snap.width)
                put("height", snap.height)
                put("litPixelRatio", snap.litPixelRatio)
              },
            )
          }
        },
      )
      put(
        "steps",
        buildJsonArray {
          for (step in steps) {
            add(
              buildJsonObject {
                put("index", step.index)
                put("action", step.action)
                put("ok", step.passed)
                put("durationMs", step.durationMs)
                step.snapshot?.let { put("snapshot", it) }
                step.error?.let { put("error", it) }
                if (step.assertions.isNotEmpty()) {
                  put(
                    "assertions",
                    buildJsonArray {
                      for (a in step.assertions) {
                        add(
                          buildJsonObject {
                            put("key", a.key)
                            put("ok", a.passed)
                            put("expected", a.expected)
                            put("actual", a.actual)
                            a.detail?.let { put("detail", it) }
                          },
                        )
                      }
                    },
                  )
                }
              },
            )
          }
        },
      )
    }
  }

  /**
   * Runs [scenarioFile] against a live [PairedLaunch] factory, returning `0` when every step and
   * assertion succeeds or `2` when any step or assertion fails.
   */
  fun runWithPairedSession(
    scenarioFile: File,
    reportDir: File,
    expectedDevices: Int = 1,
    verbose: Boolean = false,
    launchRunner: (input: java.io.Reader, output: (String) -> Unit) -> Int,
  ): Int {
    val spec = parseScenarioFile(scenarioFile)
    reportDir.mkdirs()
    val writer = PipedWriter()
    val reader = PipedReader(writer, 65_536)
    val outboundLines = LinkedBlockingQueue<String>()
    val sessionExit = CompletableFuture.supplyAsync {
      launchRunner(reader) { line ->
        if (verbose) System.err.println("compose-preview scenario <- $line")
        outboundLines.put(line)
      }
    }

    val transport = PipedSessionTransport(writer, outboundLines, sessionExit, verbose)
    val report =
      try {
        execute(
          spec = spec,
          reportDir = reportDir,
          transport = transport,
          expectedDevices = expectedDevices,
        )
      } finally {
        runCatching { transport.close() }
        runCatching { sessionExit.get(15, TimeUnit.SECONDS) }
      }

    printSummary(report)
    return if (report.passed) 0 else 2
  }

  internal fun execute(
    spec: ScenarioSpec,
    reportDir: File,
    transport: Transport,
    expectedDevices: Int = 1,
  ): ScenarioReport {
    reportDir.mkdirs()
    val startNs = System.nanoTime()
    val snapshots = LinkedHashMap<String, SnapshotRecord>()
    val stepOutcomes = mutableListOf<StepOutcome>()
    var lastCommandResult: JsonObject? = null
    var latestFrame: SnapshotRecord? = null

    try {
      transport.awaitReady(expectedDevices)
    } catch (e: Exception) {
      stepOutcomes +=
        StepOutcome(
          index = 0,
          action = "boot",
          passed = false,
          durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
          error = e.message ?: "$e",
        )
      val reportFile = File(reportDir, "report.json")
      val failedReport =
        ScenarioReport(
          name = spec.name,
          passed = false,
          durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
          steps = stepOutcomes,
          snapshots = snapshots,
          reportFile = reportFile,
        )
      reportFile.writeText(prettyJson.encodeToString(failedReport.toJson()))
      return failedReport
    }

    for ((idx, step) in spec.steps.withIndex()) {
      val stepStart = System.nanoTime()
      val device = (step["device"] as? JsonPrimitive)?.contentOrNull
      val actionLabel = describeStepAction(step)
      var stepError: String? = null
      var snapshotNameRecorded: String? = null
      val assertions = mutableListOf<AssertionOutcome>()

      try {
        // 1. Dispatch any command part of the step (a step can also carry snapshot + expect).
        val commands = buildCommandsForStep(step, device)
        for (cmd in commands) {
          val reply = transport.send(cmd)
          if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull == false) {
            val err = (reply["error"] as? JsonPrimitive)?.contentOrNull ?: reply.toString()
            throw IllegalStateException(
              "command '${cmd["cmd"]?.jsonPrimitive?.contentOrNull}' failed: $err",
            )
          }
          lastCommandResult = (reply["result"] as? JsonObject) ?: reply
        }

        // Optional host wait (`wait: 250` or `wait: { ms: 250 }`).
        val waitEl = step["wait"]
        if (waitEl != null) {
          val ms =
            when (waitEl) {
              is JsonPrimitive -> waitEl.longOrNull ?: 0L
              is JsonObject -> (waitEl["ms"] as? JsonPrimitive)?.longOrNull ?: 0L
              else -> 0L
            }
          if (ms > 0L) {
            val waitCmd = buildJsonObject {
              put("cmd", "wait")
              put("ms", ms)
              device?.let { put("device", it) }
            }
            val reply = transport.send(waitCmd)
            lastCommandResult = (reply["result"] as? JsonObject) ?: reply
          }
        }

        // 2. Capture named snapshot if requested.
        val snapName = (step["snapshot"] as? JsonPrimitive)?.contentOrNull
        if (!snapName.isNullOrBlank()) {
          val snap = captureSnapshot(snapName, reportDir, device, transport)
          snapshots[snapName] = snap
          latestFrame = snap
          snapshotNameRecorded = snapName
        }

        // 3. Evaluate `expect` block if present.
        val expectObj = step["expect"] as? JsonObject
        if (expectObj != null) {
          val needsFrame = expectObj.keys.any { it == "frame" || it.startsWith("frame.") }
          val frameForExpect =
            if (needsFrame) {
              if (snapName != null && latestFrame != null) latestFrame
              else
                captureSnapshot("_step_${idx + 1}", reportDir, device, transport).also {
                  latestFrame = it
                }
            } else {
              latestFrame
            }
          val statusReply =
            transport.send(
              buildJsonObject {
                put("cmd", "watchface")
                put("op", "status")
                device?.let { put("device", it) }
              },
            )
          val statusObj =
            (statusReply["result"] as? JsonObject)
              ?: statusReply.takeIf { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }
              ?: buildJsonObject {}
          assertions +=
            evaluateExpectations(
              expectObj = expectObj,
              status = statusObj,
              last = lastCommandResult ?: buildJsonObject {},
              events = transport.events(),
              currentFrame = frameForExpect,
              snapshots = snapshots,
            )
        }
      } catch (e: Exception) {
        stepError = e.message ?: "$e"
      }

      val passed = stepError == null && assertions.all { it.passed }
      stepOutcomes +=
        StepOutcome(
          index = idx + 1,
          action = actionLabel,
          passed = passed,
          durationMs =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).let {
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStart)
            },
          error = stepError,
          snapshot = snapshotNameRecorded,
          assertions = assertions,
        )
    }

    val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
    val allPassed = stepOutcomes.all { it.passed }
    val reportFile = File(reportDir, "report.json")
    val report =
      ScenarioReport(
        name = spec.name,
        passed = allPassed,
        durationMs = totalMs,
        steps = stepOutcomes,
        snapshots = snapshots,
        reportFile = reportFile,
      )
    reportFile.writeText(prettyJson.encodeToString(report.toJson()))
    return report
  }

  private fun captureSnapshot(
    name: String,
    reportDir: File,
    device: String?,
    transport: Transport,
  ): SnapshotRecord {
    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outFile = File(reportDir, "$safeName.png").absoluteFile
    outFile.parentFile?.mkdirs()
    val shotReply =
      transport.send(
        buildJsonObject {
          put("cmd", "screenshot")
          put("path", outFile.absolutePath)
          device?.let { put("device", it) }
        },
      )
    if ((shotReply["ok"] as? JsonPrimitive)?.booleanOrNull == false) {
      throw IllegalStateException(
        "screenshot '$name' failed: ${(shotReply["error"] as? JsonPrimitive)?.contentOrNull ?: shotReply}",
      )
    }
    if (!outFile.isFile) {
      throw IllegalStateException("screenshot '$name' did not write $outFile")
    }
    val img =
      ImageIO.read(outFile)
        ?: throw IllegalStateException("screenshot '$name' at $outFile is not a readable PNG")
    val statusReply = runCatching {
      transport.send(
        buildJsonObject {
          put("cmd", "watchface")
          put("op", "status")
          device?.let { put("device", it) }
        },
      )
    }
      .getOrNull()
    val statusObj = (statusReply?.get("result") as? JsonObject) ?: statusReply
    return SnapshotRecord(
      name = name,
      file = outFile,
      sha256 = sha256(outFile),
      width = img.width,
      height = img.height,
      litPixelRatio = computeLitPixelRatio(img),
      image = img,
      status = statusObj,
    )
  }

  private fun buildCommandsForStep(step: JsonObject, device: String?): List<JsonObject> {
    val commands = mutableListOf<JsonObject>()
    fun withDevice(base: Map<String, JsonElement>): JsonObject {
      val merged = LinkedHashMap(base)
      if (device != null && "device" !in merged) {
        merged["device"] = JsonPrimitive(device)
      }
      return JsonObject(merged)
    }

    if ("cmd" in step) {
      val filtered = step.filterKeys { it != "snapshot" && it != "expect" && it != "wait" }
      commands += withDevice(filtered)
      return commands
    }

    val directCommands =
      listOf(
        "clock" to "clock",
        "health" to "health",
        "battery" to "battery",
        "weather" to "weather",
        "accel" to "accel",
        "watchface" to "watchface",
        "display" to "display",
        "screen" to "screen",
        "tiles" to "tiles",
        "notification" to "notification",
        "notifications" to "notification",
        "broadcast" to "broadcast",
      )
    for ((stepKey, cmdName) in directCommands) {
      val payload = step[stepKey] ?: continue
      if (payload is JsonObject) {
        val map = LinkedHashMap<String, JsonElement>()
        map["cmd"] = JsonPrimitive(cmdName)
        map.putAll(payload)
        commands += withDevice(map)
      }
    }

    val ambientEl = step["ambient"]
    if (ambientEl != null) {
      // The simulator's `ambient` command takes `value`, plus `burnIn` / `lowBit` for the next
      // `onEnterAmbient`; without `value` it toggles, so the shorthand always sends one.
      when (ambientEl) {
        is JsonPrimitive -> {
          val enabled = ambientEl.booleanOrNull ?: (ambientEl.contentOrNull == "true")
          commands +=
            withDevice(mapOf("cmd" to JsonPrimitive("ambient"), "value" to JsonPrimitive(enabled)))
        }
        is JsonObject -> {
          val enabled =
            (ambientEl["enabled"] as? JsonPrimitive)?.booleanOrNull
              ?: (ambientEl["value"] as? JsonPrimitive)?.booleanOrNull
              ?: true
          val amb = LinkedHashMap<String, JsonElement>()
          amb["cmd"] = JsonPrimitive("ambient")
          amb["value"] = JsonPrimitive(enabled)
          ambientEl["burnIn"]?.let { amb["burnIn"] = it }
          ambientEl["lowBit"]?.let { amb["lowBit"] = it }
          commands += withDevice(amb)
        }
        else -> {}
      }
    }

    val tapEl = step["tap"]
    if (tapEl is JsonObject) {
      if ("slot" in tapEl) {
        val wfTap = LinkedHashMap<String, JsonElement>()
        wfTap["cmd"] = JsonPrimitive("watchface")
        wfTap["op"] = JsonPrimitive("tap")
        wfTap.putAll(tapEl)
        commands += withDevice(wfTap)
      } else {
        val rawTap = LinkedHashMap<String, JsonElement>()
        rawTap["cmd"] = JsonPrimitive("tap")
        rawTap.putAll(tapEl)
        commands += withDevice(rawTap)
      }
    }

    return commands
  }

  private fun evaluateExpectations(
    expectObj: JsonObject,
    status: JsonObject,
    last: JsonObject,
    events: List<JsonObject>,
    currentFrame: SnapshotRecord?,
    snapshots: Map<String, SnapshotRecord>,
  ): List<AssertionOutcome> {
    val outcomes = mutableListOf<AssertionOutcome>()
    val root = buildJsonObject {
      put("status", status)
      put("last", last)
      put("events", JsonArray(events))
    }

    // Flatten nested `frame: { ... }` if written as an object instead of `frame.changedFrom`.
    val flattened = LinkedHashMap<String, JsonElement>()
    for ((k, v) in expectObj) {
      if (k == "frame" && v is JsonObject) {
        for ((fk, fv) in v) {
          flattened["frame.$fk"] = fv
        }
      } else {
        flattened[k] = v
      }
    }

    for ((key, expected) in flattened) {
      when (key) {
        "frame.changedFrom" -> {
          val refName = (expected as? JsonPrimitive)?.contentOrNull.orEmpty()
          val ref = snapshots[refName]
          if (currentFrame == null || ref == null) {
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = false,
                expected = expected,
                actual = if (ref == null) "missing snapshot '$refName'" else "no current frame",
              )
          } else {
            val diffPixels = countDifferentPixels(currentFrame.image, ref.image)
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = diffPixels > 0,
                expected = expected,
                actual = "diffPixels=$diffPixels",
                detail =
                  if (diffPixels > 0) "$diffPixels pixels changed vs '$refName'"
                  else "frame is identical to '$refName'",
              )
          }
        }
        "frame.unchangedFrom" -> {
          val refName = (expected as? JsonPrimitive)?.contentOrNull.orEmpty()
          val ref = snapshots[refName]
          if (currentFrame == null || ref == null) {
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = false,
                expected = expected,
                actual = if (ref == null) "missing snapshot '$refName'" else "no current frame",
              )
          } else {
            val diffPixels = countDifferentPixels(currentFrame.image, ref.image)
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = diffPixels == 0,
                expected = expected,
                actual = "diffPixels=$diffPixels",
              )
          }
        }
        "frame.litPixelRatio" -> {
          if (currentFrame == null) {
            outcomes += AssertionOutcome(key, false, expected, "no current frame")
          } else {
            val ratio = currentFrame.litPixelRatio
            val ok = matchesValue(JsonPrimitive(ratio), expected)
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = ok,
                expected = expected,
                actual = "%.4f".format(java.util.Locale.ROOT, ratio),
              )
          }
        }
        "frame.regionColor" -> {
          if (currentFrame == null || expected !is JsonObject) {
            outcomes += AssertionOutcome(key, false, expected, "missing frame or region spec")
          } else {
            val x = (expected["x"] as? JsonPrimitive)?.intOrNull ?: (currentFrame.width / 2)
            val y = (expected["y"] as? JsonPrimitive)?.intOrNull ?: (currentFrame.height / 2)
            val radius = (expected["radius"] as? JsonPrimitive)?.intOrNull ?: 4
            val colorSpec = (expected["color"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val avgHex = sampleRegionHex(currentFrame.image, x, y, radius)
            val ok = matchColorSpec(avgHex, colorSpec)
            outcomes +=
              AssertionOutcome(
                key = key,
                passed = ok,
                expected = expected,
                actual = avgHex,
                detail = "region ($x,$y r=$radius) avg=$avgHex",
              )
          }
        }
        "events.contains" -> {
          val matched =
            if (expected is JsonObject) {
              events.any { ev -> jsonSubsetMatches(ev, expected) }
            } else {
              val eventName = (expected as? JsonPrimitive)?.contentOrNull
              eventName != null &&
                events.any { (it["event"] as? JsonPrimitive)?.contentOrNull == eventName }
            }
          outcomes +=
            AssertionOutcome(
              key = key,
              passed = matched,
              expected = expected,
              actual = "events=${events.size}",
            )
        }
        else -> {
          val resolved =
            resolvePath(root, key)
              ?: (if (!key.startsWith("status.") && !key.startsWith("last."))
                resolvePath(status, key)
              else null)
          val ok = matchesValue(resolved, expected)
          outcomes +=
            AssertionOutcome(
              key = key,
              passed = ok,
              expected = expected,
              actual = resolved?.toString() ?: "null",
            )
        }
      }
    }
    return outcomes
  }

  private fun resolvePath(root: JsonElement, dottedPath: String): JsonElement? {
    var cur: JsonElement? = root
    for (segment in dottedPath.split('.')) {
      if (cur == null || cur is JsonNull) return null
      cur =
        when (cur) {
          is JsonObject -> cur[segment]
          is JsonArray -> {
            val idx = segment.toIntOrNull()
            if (idx != null && idx in cur.indices) cur[idx]
            else {
              // Also allow matching array element by `id` or `slotId` (`complications.10`).
              cur.firstOrNull { item ->
                item is JsonObject &&
                  ((item["id"] as? JsonPrimitive)?.contentOrNull == segment ||
                    (item["slotId"] as? JsonPrimitive)?.contentOrNull == segment)
              }
            }
          }
          else -> null
        }
    }
    return cur
  }

  private fun matchesValue(actual: JsonElement?, expected: JsonElement): Boolean {
    if (expected is JsonNull) return actual == null || actual is JsonNull
    if (expected is JsonObject) {
      if ("exists" in expected) {
        val wantExists = (expected["exists"] as? JsonPrimitive)?.booleanOrNull ?: true
        val exists = actual != null && actual !is JsonNull
        if (exists != wantExists) return false
      }
      if ("contains" in expected) {
        val needle = (expected["contains"] as? JsonPrimitive)?.contentOrNull ?: return false
        val hay = (actual as? JsonPrimitive)?.contentOrNull ?: actual?.toString() ?: return false
        if (!hay.contains(needle)) return false
      }
      if ("notEquals" in expected) {
        if (matchesValue(actual, expected.getValue("notEquals"))) return false
      }
      if ("nearColor" in expected) {
        val target = (expected["nearColor"] as? JsonPrimitive)?.contentOrNull ?: return false
        val tol = (expected["tolerance"] as? JsonPrimitive)?.intOrNull ?: 24
        val actualStr = (actual as? JsonPrimitive)?.contentOrNull ?: return false
        if (!matchColorSpec(actualStr, "$target±$tol")) return false
      }
      val specialKeys = setOf("exists", "contains", "notEquals", "nearColor", "tolerance")
      val subsetKeys = expected.filterKeys { it !in specialKeys }
      if (subsetKeys.isNotEmpty()) {
        if (actual !is JsonObject) return false
        return jsonSubsetMatches(actual, JsonObject(subsetKeys))
      }
      return true
    }
    if (actual == null || actual is JsonNull) return false

    if (expected is JsonPrimitive && actual is JsonPrimitive) {
      val expStr = expected.contentOrNull ?: return actual == expected
      val actStr = actual.contentOrNull ?: return false

      // Hex color with optional tolerance: `#4F378B` or `#4F378B±24` or `#4F378B+-24`.
      if (expStr.startsWith("#") && actStr.startsWith("#")) {
        return matchColorSpec(actStr, expStr)
      }

      // Numeric comparison expression: `< 0.15`, `<= 10`, `> 0`, `>= 72`.
      val cmpMatch = NUMERIC_COMPARISON_REGEX.matchEntire(expStr.trim())
      if (cmpMatch != null) {
        val op = cmpMatch.groupValues[1]
        val rhs = cmpMatch.groupValues[2].toDoubleOrNull()
        val lhs = actual.doubleOrNull ?: actStr.toDoubleOrNull()
        if (lhs != null && rhs != null) {
          return when (op) {
            "<" -> lhs < rhs
            "<=" -> lhs <= rhs
            ">" -> lhs > rhs
            ">=" -> lhs >= rhs
            "==" -> abs(lhs - rhs) < 1e-6
            "!=" -> abs(lhs - rhs) >= 1e-6
            else -> false
          }
        }
      }

      val expNum = expected.doubleOrNull
      val actNum = actual.doubleOrNull
      if (!expected.isString && expNum != null && actNum != null) {
        return abs(expNum - actNum) < 1e-6
      }
      val expBool = expected.booleanOrNull
      val actBool = actual.booleanOrNull
      if (!expected.isString && expBool != null && actBool != null) {
        return expBool == actBool
      }
      return actStr == expStr
    }
    return actual == expected
  }

  private fun jsonSubsetMatches(actual: JsonObject, subset: JsonObject): Boolean {
    for ((k, v) in subset) {
      if (!matchesValue(actual[k], v)) return false
    }
    return true
  }

  internal fun matchColorSpec(actualHex: String, spec: String): Boolean {
    val actualRgb = parseHexRgb(actualHex) ?: return false
    val normalized = spec.replace("+-", "±")
    val targetPart = normalized.substringBefore('±').trim()
    val tol = if ('±' in normalized) normalized.substringAfter('±').trim().toIntOrNull() ?: 0 else 0
    val targetRgb = parseHexRgb(targetPart) ?: return false
    return abs(actualRgb[0] - targetRgb[0]) <= tol &&
      abs(actualRgb[1] - targetRgb[1]) <= tol &&
      abs(actualRgb[2] - targetRgb[2]) <= tol
  }

  private fun parseHexRgb(hex: String): IntArray? {
    val clean = hex.trim().removePrefix("#")
    val rgb =
      when (clean.length) {
        6 -> clean.toLongOrNull(16)?.toInt() ?: return null
        8 -> (clean.toLongOrNull(16) ?: return null).toInt()
        else -> return null
      }
    return intArrayOf((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF)
  }

  internal fun computeLitPixelRatio(img: BufferedImage): Double {
    val total = img.width.toLong() * img.height.toLong()
    if (total <= 0L) return 0.0
    var lit = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val argb = img.getRGB(x, y)
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        if (a > 16 && (r > 16 || g > 16 || b > 16)) {
          lit++
        }
      }
    }
    return lit.toDouble() / total.toDouble()
  }

  internal fun countDifferentPixels(a: BufferedImage, b: BufferedImage): Int {
    if (a.width != b.width || a.height != b.height) {
      return max(a.width * a.height, b.width * b.height)
    }
    var diff = 0
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) diff++
      }
    }
    return diff
  }

  private fun sampleRegionHex(img: BufferedImage, cx: Int, cy: Int, radius: Int): String {
    val x0 = max(0, cx - radius)
    val x1 = min(img.width - 1, cx + radius)
    val y0 = max(0, cy - radius)
    val y1 = min(img.height - 1, cy + radius)
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    for (y in y0..y1) {
      for (x in x0..x1) {
        val argb = img.getRGB(x, y)
        rSum += (argb ushr 16) and 0xFF
        gSum += (argb ushr 8) and 0xFF
        bSum += argb and 0xFF
        count++
      }
    }
    if (count == 0L) return "#000000"
    return "#%02X%02X%02X"
      .format((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
  }

  private fun describeStepAction(step: JsonObject): String {
    val keys = step.keys.filter { it != "device" }
    return keys.joinToString("+").ifEmpty { "noop" }
  }

  private fun printSummary(report: ScenarioReport) {
    val statusWord = if (report.passed) "PASSED" else "FAILED"
    println(
      "Scenario '${report.name}': $statusWord (${report.steps.count { it.passed }}/${report.steps.size} steps, " +
        "${report.snapshots.size} snapshots, ${report.durationMs} ms)",
    )
    for (step in report.steps) {
      val mark = if (step.passed) "PASS" else "FAIL"
      val snapSuffix = step.snapshot?.let { " [snapshot: $it]" }.orEmpty()
      println("  [$mark] step ${step.index}: ${step.action}$snapSuffix (${step.durationMs} ms)")
      if (step.error != null) {
        println("         error: ${step.error}")
      }
      for (a in step.assertions.filter { !it.passed }) {
        println("         expect ${a.key}: expected ${a.expected}, got ${a.actual}")
      }
    }
    println("  Report: ${report.reportFile.absolutePath}")
  }

  /**
   * Parses a `.jsonl` (one step object per line), `.json` or `.yaml`/`.yml` scenario file into
   * [ScenarioSpec].
   */
  internal fun parseScenarioFile(file: File): ScenarioSpec {
    require(file.isFile) { "no such scenario file: $file" }
    val text = file.readText()
    if (file.extension.equals("jsonl", ignoreCase = true)) {
      return parseJsonLines(text, defaultName = file.nameWithoutExtension)
    }
    val isJson =
      file.extension.equals("json", ignoreCase = true) || text.trimStart().startsWith("{")
    return parseScenario(text, jsonFormat = isJson, defaultName = file.nameWithoutExtension)
  }

  /**
   * JSON Lines: each non-blank line that is not a `#` or `//` comment is one step object, in the
   * same shape as an entry of a JSON or YAML scenario's `steps` list.
   */
  internal fun parseJsonLines(text: String, defaultName: String = "scenario"): ScenarioSpec {
    val steps = mutableListOf<JsonObject>()
    for ((lineNo, raw) in text.lines().withIndex()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
      val el =
        try {
          Json.parseToJsonElement(line)
        } catch (e: IllegalArgumentException) {
          throw IllegalArgumentException(
            "scenario '$defaultName' line ${lineNo + 1} is not valid JSON: ${e.message}",
            e,
          )
        }
      steps +=
        el as? JsonObject
          ?: throw IllegalArgumentException(
            "scenario '$defaultName' line ${lineNo + 1} must be a JSON object",
          )
    }
    return ScenarioSpec(name = defaultName, steps = steps)
  }

  internal fun parseScenario(
    text: String,
    jsonFormat: Boolean = text.trimStart().startsWith("{"),
    defaultName: String = "scenario",
  ): ScenarioSpec {
    val root =
      if (jsonFormat) {
        Json.parseToJsonElement(text).jsonObject
      } else {
        MiniYamlParser.parseObject(text)
      }
    val name = (root["name"] as? JsonPrimitive)?.contentOrNull ?: defaultName
    val stepsEl =
      root["steps"] as? JsonArray
        ?: throw IllegalArgumentException("scenario '$name' must contain a 'steps' list")
    val steps = stepsEl.mapIndexed { idx, el ->
      el as? JsonObject
        ?: throw IllegalArgumentException("scenario '$name' step ${idx + 1} must be an object")
    }
    return ScenarioSpec(name = name, steps = steps)
  }

  private val NUMERIC_COMPARISON_REGEX = Regex("""^(<=|>=|<|>|==|!=)\s*(-?\d+(?:\.\d+)?)$""")

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(8192)
      while (true) {
        val r = input.read(buf)
        if (r <= 0) break
        md.update(buf, 0, r)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Drives a running [PairedLaunch] over piped streams, correlating replies by `"id"` while
   * buffering asynchronous `"event"` JSON objects for `events.contains` assertions.
   */
  private class PipedSessionTransport(
    private val writer: PipedWriter,
    private val outboundLines: LinkedBlockingQueue<String>,
    private val sessionExit: CompletableFuture<Int>,
    private val verbose: Boolean,
  ) : Transport {
    private val nextId = AtomicInteger(1)
    private val collectedEvents = mutableListOf<JsonObject>()
    private var readySeen = 0

    override fun awaitReady(expectedDevices: Int, timeoutMs: Long) {
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
      while (readySeen < expectedDevices) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs <= 0L) {
          throw IllegalStateException(
            "timed out waiting for simulator ready ($readySeen/$expectedDevices ready)",
          )
        }
        if (sessionExit.isDone && outboundLines.isEmpty()) {
          throw IllegalStateException(
            "simulator exited (${sessionExit.getNow(-1)}) before ready ($readySeen/$expectedDevices)",
          )
        }
        val line = outboundLines.poll(min(remainingMs, 500L), TimeUnit.MILLISECONDS) ?: continue
        val obj = parseJsonObjectOrNull(line) ?: continue
        if ("event" in obj) {
          collectedEvents += obj
          if ((obj["event"] as? JsonPrimitive)?.contentOrNull == "ready") {
            readySeen++
          }
        }
      }
    }

    override fun send(command: JsonObject, timeoutMs: Long): JsonObject {
      val id = "sc-${nextId.getAndIncrement()}"
      val withId = JsonObject(mapOf("id" to JsonPrimitive(id)) + command)
      val serialized = withId.toString()
      if (verbose) System.err.println("compose-preview scenario -> $serialized")
      writer.write(serialized)
      writer.write("\n")
      writer.flush()

      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
      while (true) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMs <= 0L) {
          throw IllegalStateException("timed out waiting for reply to $serialized")
        }
        if (sessionExit.isDone && outboundLines.isEmpty()) {
          throw IllegalStateException("simulator exited while awaiting reply to $serialized")
        }
        val line = outboundLines.poll(min(remainingMs, 500L), TimeUnit.MILLISECONDS) ?: continue
        val obj = parseJsonObjectOrNull(line) ?: continue
        if ("event" in obj && "id" !in obj) {
          collectedEvents += obj
          if ((obj["event"] as? JsonPrimitive)?.contentOrNull == "ready") {
            readySeen++
          }
          continue
        }
        val replyId = (obj["id"] as? JsonPrimitive)?.contentOrNull
        if (replyId == id || (replyId == null && "ok" in obj)) {
          return obj
        }
      }
    }

    override fun events(): List<JsonObject> = ArrayList(collectedEvents)

    override fun close() {
      runCatching { writer.close() }
    }

    private fun parseJsonObjectOrNull(line: String): JsonObject? {
      val trimmed = line.trim()
      if (!trimmed.startsWith("{")) return null
      return runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    }
  }

  /**
   * Compact indentation-aware YAML subset parser for scenario files (supports block mappings, `-`
   * sequences, inline JSON `{...}` / `[...]`, quoted/unquoted scalars, numbers, booleans, and `#`
   * comments without pulling an external YAML library onto `:cli`'s classpath).
   */
  internal object MiniYamlParser {
    private data class Line(val indent: Int, val text: String, val lineNumber: Int)

    fun parseObject(yaml: String): JsonObject {
      val lines = preprocess(yaml)
      if (lines.isEmpty()) return buildJsonObject {}
      val (el, _) = parseBlock(lines, 0, lines[0].indent)
      return el as? JsonObject ?: throw IllegalArgumentException("YAML root must be a mapping")
    }

    private fun preprocess(yaml: String): List<Line> {
      val out = mutableListOf<Line>()
      yaml.lines().forEachIndexed { idx, raw ->
        val stripped = stripComment(raw)
        if (stripped.isBlank() || stripped.trim() == "---" || stripped.trim() == "...") {
          return@forEachIndexed
        }
        val indent = stripped.indexOfFirst { !it.isWhitespace() }
        out +=
          Line(indent = indent, text = stripped.substring(indent).trimEnd(), lineNumber = idx + 1)
      }
      return out
    }

    private fun stripComment(line: String): String {
      var inSingle = false
      var inDouble = false
      var i = 0
      while (i < line.length) {
        val c = line[i]
        if (c == '\\' && inDouble && i + 1 < line.length) {
          i += 2
          continue
        }
        if (c == '\'' && !inDouble) inSingle = !inSingle
        else if (c == '"' && !inSingle) inDouble = !inDouble
        else if (c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace())) {
          return line.substring(0, i)
        }
        i++
      }
      return line
    }

    private fun parseBlock(lines: List<Line>, start: Int, baseIndent: Int): Pair<JsonElement, Int> {
      val first = lines[start]
      return if (first.text == "-" || first.text.startsWith("- ")) {
        parseSequence(lines, start, baseIndent)
      } else {
        parseMapping(lines, start, baseIndent)
      }
    }

    private fun parseSequence(lines: List<Line>, start: Int, seqIndent: Int): Pair<JsonArray, Int> {
      val items = mutableListOf<JsonElement>()
      var idx = start
      while (idx < lines.size) {
        val line = lines[idx]
        if (line.indent < seqIndent) break
        if (line.indent != seqIndent || !(line.text == "-" || line.text.startsWith("- "))) break

        val afterDash = if (line.text == "-") "" else line.text.substring(2).trimStart()
        if (afterDash.isEmpty()) {
          idx++
          if (idx < lines.size && lines[idx].indent > seqIndent) {
            val (child, nextIdx) = parseBlock(lines, idx, lines[idx].indent)
            items += child
            idx = nextIdx
          } else {
            items += JsonNull
          }
        } else if (isInlineJson(afterDash)) {
          items += parseScalarOrInlineJson(afterDash)
          idx++
        } else if (splitKeyValue(afterDash) != null) {
          // Inline mapping item under `- key: value`, possibly followed by more indented keys!
          val itemIndent = line.indent + 2
          val synthetic = mutableListOf(Line(itemIndent, afterDash, line.lineNumber))
          idx++
          while (idx < lines.size && lines[idx].indent >= itemIndent) {
            synthetic += lines[idx]
            idx++
          }
          val (obj, _) = parseMapping(synthetic, 0, itemIndent)
          items += obj
        } else {
          items += parseScalarOrInlineJson(afterDash)
          idx++
        }
      }
      return JsonArray(items) to idx
    }

    private fun parseMapping(lines: List<Line>, start: Int, mapIndent: Int): Pair<JsonObject, Int> {
      val map = LinkedHashMap<String, JsonElement>()
      var idx = start
      while (idx < lines.size) {
        val line = lines[idx]
        if (line.indent < mapIndent) break
        if (line.indent > mapIndent) {
          throw IllegalArgumentException(
            "unexpected indentation at YAML line ${line.lineNumber}: '${line.text}'",
          )
        }
        if (line.text == "-" || line.text.startsWith("- ")) break
        val kv =
          splitKeyValue(line.text)
            ?: throw IllegalArgumentException(
              "expected 'key: value' at YAML line ${line.lineNumber}: '${line.text}'",
            )
        val key = unquoteKey(kv.first)
        val rawVal = kv.second
        idx++
        if (rawVal.isEmpty()) {
          if (idx < lines.size && lines[idx].indent > mapIndent) {
            val (child, nextIdx) = parseBlock(lines, idx, lines[idx].indent)
            map[key] = child
            idx = nextIdx
          } else {
            map[key] = JsonNull
          }
        } else {
          map[key] = parseScalarOrInlineJson(rawVal)
        }
      }
      return JsonObject(map) to idx
    }

    private fun splitKeyValue(text: String): Pair<String, String>? {
      var inSingle = false
      var inDouble = false
      var braceDepth = 0
      var bracketDepth = 0
      var i = 0
      while (i < text.length) {
        val c = text[i]
        if (c == '\\' && inDouble && i + 1 < text.length) {
          i += 2
          continue
        }
        when {
          c == '\'' && !inDouble -> inSingle = !inSingle
          c == '"' && !inSingle -> inDouble = !inDouble
          !inSingle && !inDouble && c == '{' -> braceDepth++
          !inSingle && !inDouble && c == '}' -> braceDepth = max(0, braceDepth - 1)
          !inSingle && !inDouble && c == '[' -> bracketDepth++
          !inSingle && !inDouble && c == ']' -> bracketDepth = max(0, bracketDepth - 1)
          !inSingle && !inDouble && braceDepth == 0 && bracketDepth == 0 && c == ':' -> {
            if (i == text.length - 1 || text[i + 1].isWhitespace()) {
              val key = text.substring(0, i).trim()
              val value = text.substring(i + 1).trim()
              if (key.isNotEmpty()) return key to value
            }
          }
        }
        i++
      }
      return null
    }

    private fun unquoteKey(raw: String): String {
      if (
        raw.length >= 2 &&
          ((raw.startsWith('"') && raw.endsWith('"')) ||
            (raw.startsWith('\'') && raw.endsWith('\'')))
      ) {
        return raw.substring(1, raw.length - 1)
      }
      return raw
    }

    private fun isInlineJson(s: String): Boolean {
      val t = s.trim()
      return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }

    private fun parseScalarOrInlineJson(raw: String): JsonElement {
      val t = raw.trim()
      if (isInlineJson(t)) {
        runCatching {
          return Json.parseToJsonElement(t)
        }
        return parseFlowYaml(t)
      }
      if (t == "null" || t == "~") return JsonNull
      if (t == "true") return JsonPrimitive(true)
      if (t == "false") return JsonPrimitive(false)
      if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
        return runCatching { Json.parseToJsonElement(t) as JsonPrimitive }
          .getOrElse { JsonPrimitive(t.substring(1, t.length - 1)) }
      }
      if (t.length >= 2 && t.startsWith('\'') && t.endsWith('\'')) {
        return JsonPrimitive(t.substring(1, t.length - 1).replace("''", "'"))
      }
      t.toLongOrNull()?.let {
        return JsonPrimitive(it)
      }
      t.toDoubleOrNull()?.let {
        return JsonPrimitive(it)
      }
      return JsonPrimitive(t)
    }

    /**
     * Handles YAML flow objects/lists with unquoted keys like `{ time: "10:08", frozen: true }`.
     */
    private fun parseFlowYaml(flow: String): JsonElement {
      val t = flow.trim()
      if (t.startsWith("{") && t.endsWith("}")) {
        val body = t.substring(1, t.length - 1).trim()
        if (body.isEmpty()) return buildJsonObject {}
        val map = LinkedHashMap<String, JsonElement>()
        for (part in splitFlowTopLevel(body)) {
          val kv = splitKeyValue(part.trim()) ?: continue
          map[unquoteKey(kv.first)] = parseScalarOrInlineJson(kv.second)
        }
        return JsonObject(map)
      }
      if (t.startsWith("[") && t.endsWith("]")) {
        val body = t.substring(1, t.length - 1).trim()
        if (body.isEmpty()) return buildJsonArray {}
        return JsonArray(splitFlowTopLevel(body).map { parseScalarOrInlineJson(it) })
      }
      return JsonPrimitive(t)
    }

    private fun splitFlowTopLevel(body: String): List<String> {
      val parts = mutableListOf<String>()
      var start = 0
      var inSingle = false
      var inDouble = false
      var depth = 0
      var i = 0
      while (i < body.length) {
        val c = body[i]
        if (c == '\\' && inDouble && i + 1 < body.length) {
          i += 2
          continue
        }
        when {
          c == '\'' && !inDouble -> inSingle = !inSingle
          c == '"' && !inSingle -> inDouble = !inDouble
          !inSingle && !inDouble && (c == '{' || c == '[') -> depth++
          !inSingle && !inDouble && (c == '}' || c == ']') -> depth = max(0, depth - 1)
          !inSingle && !inDouble && depth == 0 && c == ',' -> {
            parts += body.substring(start, i)
            start = i + 1
          }
        }
        i++
      }
      val tail = body.substring(start).trim()
      if (tail.isNotEmpty()) parts += tail
      return parts
    }
  }
}
