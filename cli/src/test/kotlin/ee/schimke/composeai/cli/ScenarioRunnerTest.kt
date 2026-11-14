package ee.schimke.composeai.cli

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ScenarioRunnerTest {

  @Test
  fun `parses YAML scenario and evaluates status color tolerance and pixel frame expectations`() {
    val reportDir = Files.createTempDirectory("scenario-runner-test-").toFile()
    try {
      val yaml =
        """
        # End-to-end watch face scenario
        name: simple-digital-health-and-ambient
        steps:
          - clock: { time: "2026-06-15T10:08:30Z", frozen: true, is24Hour: true }
          - health: { heartRate: 132, steps: 8420 }
          - weather:
              available: true
              condition: RAIN
              temp: 16.5
          - snapshot: interactive-1008
          - expect:
              status.colors.primary: "#7B61FF±16"
              status.complications.10.text: "132 bpm"
              frame.litPixelRatio: "> 0.10"
          - ambient: { enabled: true, burnIn: true }
          - snapshot: ambient-1008
          - expect:
              frame.changedFrom: interactive-1008
              frame.litPixelRatio: "< 0.15"
              events.contains: watchfaceColors
        """
          .trimIndent()

      val spec = ScenarioRunner.parseScenario(yaml, jsonFormat = false)
      assertEquals("simple-digital-health-and-ambient", spec.name)
      assertEquals(8, spec.steps.size)

      var ambientMode = false
      val sentCommands = mutableListOf<JsonObject>()
      val fakeTransport =
        object : ScenarioRunner.Transport {
          override fun awaitReady(expectedDevices: Int, timeoutMs: Long) {}

          override fun send(command: JsonObject, timeoutMs: Long): JsonObject {
            sentCommands += command
            val cmd = command["cmd"]?.jsonPrimitive?.contentOrNull
            return when (cmd) {
              "ambient" -> {
                ambientMode = command["value"]?.jsonPrimitive?.contentOrNull == "true"
                assertEquals("true", command["burnIn"]?.jsonPrimitive?.contentOrNull)
                buildJsonObject { put("ok", true) }
              }
              "screenshot" -> {
                val outPath = File(command.getValue("path").jsonPrimitive.content)
                writeSyntheticWatchFrame(outPath, ambient = ambientMode)
                buildJsonObject {
                  put("ok", true)
                  put("path", outPath.absolutePath)
                }
              }
              "watchface" ->
                buildJsonObject {
                  put("ok", true)
                  put(
                    "result",
                    buildJsonObject {
                      put("active", "com.example/.SimpleDigital")
                      put(
                        "colors",
                        buildJsonObject {
                          put("primary", "#785EF8")
                          put("secondary", "#625B71")
                          put("tertiary", "#7D5260")
                          put("source", "config")
                        },
                      )
                      put(
                        "complications",
                        buildJsonArray {
                          add(
                            buildJsonObject {
                              put("id", 10)
                              put("text", "132 bpm")
                            },
                          )
                        },
                      )
                    },
                  )
                }
              else -> buildJsonObject { put("ok", true) }
            }
          }

          override fun events(): List<JsonObject> =
            listOf(
              buildJsonObject {
                put("event", "watchfaceColors")
                put("primary", "#785EF8")
              },
            )

          override fun close() {}
        }

      val report = ScenarioRunner.execute(spec, reportDir, fakeTransport)
      assertTrue(report.passed, "expected scenario to pass: ${report.toJson()}")
      assertEquals(2, report.snapshots.size)
      assertTrue(File(reportDir, "interactive-1008.png").isFile)
      assertTrue(File(reportDir, "ambient-1008.png").isFile)
      assertTrue(report.reportFile.isFile)
      assertTrue(sentCommands.any { it["cmd"] == JsonPrimitive("clock") })
      assertTrue(sentCommands.any { it["cmd"] == JsonPrimitive("weather") })
    } finally {
      reportDir.deleteRecursively()
    }
  }

  @Test
  fun `colorSpecToleranceRejectsOutOfBoundsChannelDifference`() {
    assertTrue(ScenarioRunner.matchColorSpec("#785EF8", "#7B61FF±12"))
    assertFalse(ScenarioRunner.matchColorSpec("#00C853", "#7B61FF±12"))
  }

  @Test
  fun `parses a JSON Lines scenario file one step per line, skipping comments`() {
    val file = Files.createTempFile("wear-scenario-", ".jsonl").toFile()
    try {
      file.writeText(
        """
        # pin the clock, then capture
        {"clock": {"time": "10:08:30", "frozen": true}}

        // a command step with a snapshot and expectation
        {"cmd": "watchface", "op": "status", "snapshot": "s1", "expect": {"last.kind": "dwf"}}
        """
          .trimIndent(),
      )
      val spec = ScenarioRunner.parseScenarioFile(file)
      assertEquals(file.nameWithoutExtension, spec.name)
      assertEquals(2, spec.steps.size)
      assertEquals("s1", spec.steps[1]["snapshot"]?.jsonPrimitive?.contentOrNull)
    } finally {
      file.delete()
    }
  }

  private fun writeSyntheticWatchFrame(file: File, ambient: Boolean) {
    val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.color = Color.BLACK
      g.fillRect(0, 0, 100, 100)
      g.color = if (ambient) Color(180, 180, 180) else Color(120, 94, 248)
      if (ambient) {
        // Sparse outline (< 15% lit pixels).
        g.fillRect(35, 45, 30, 10)
      } else {
        // Rich interactive dial (> 10% lit pixels).
        g.fillOval(15, 15, 70, 70)
      }
    } finally {
      g.dispose()
    }
    file.parentFile?.mkdirs()
    ImageIO.write(img, "PNG", file)
  }
}
