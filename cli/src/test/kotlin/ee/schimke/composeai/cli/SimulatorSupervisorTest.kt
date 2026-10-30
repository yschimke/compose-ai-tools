package ee.schimke.composeai.cli

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [SimulatorSupervisor] over a stand-in simulator: a shell script that sends `ready` (naming its
 * APK and the data it was asked to restore), answers `snapshotData`, and echoes other commands.
 */
class SimulatorSupervisorTest {

  private val fake =
    """
    echo "simulator for $1" >&2
    restore="$2"
    warm=false
    if [ "$3" = "true" ]; then
      echo '{"event":"standby"}'
      IFS= read -r go || exit 0
      restore=$(printf '%s' "${'$'}go" | sed -n 's/.*"composeai.simulator.restoreData":"\([^"]*\)".*/\1/p')
      warm=true
    fi
    startup='{"totalMs":7}'
    if [ "$4" = "fail" ] && [ -n "${'$'}restore" ]; then startup='{"totalMs":7,"restoreError":"disk full"}'; fi
    echo "{\"event\":\"ready\",\"apk\":\"$1\",\"restore\":\"${'$'}restore\",\"warm\":${'$'}warm,\"startup\":${'$'}startup}"
    while IFS= read -r line; do
      id=$(printf '%s' "${'$'}line" | sed -n 's/.*"id":\("[^"]*"\|[0-9]*\).*/\1/p')
      case "${'$'}line" in
        *snapshotData*) echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"files\":3}}" ;;
        *hang*) ;;
        *) echo "{\"id\":${'$'}id,\"ok\":true,\"result\":{\"apk\":\"$1\"}}" ;;
      esac
    done
    """
      .trimIndent()

  private val out = LinkedBlockingQueue<JsonObject>()

  private fun supervisor(
    device: String? = null,
    extra: ((SimulatorSupervisor, String, JsonObject) -> JsonObject?)? = null,
    standby: Boolean = false,
    failRestore: () -> Boolean = { false },
  ) =
    SimulatorSupervisor(
      device = device,
      initial = SimulatorSupervisor.Launch(File("first.apk"), listOf(File("classes.jar"))),
      command = { launch ->
        listOf(
          "bash",
          "-c",
          fake,
          "fake",
          launch.apk.path,
          launch.properties[SimulatorSupervisor.RESTORE_PROPERTY].orEmpty(),
          launch.properties[SimulatorSupervisor.STANDBY_PROPERTY].orEmpty(),
          if (failRestore()) "fail" else "",
        )
      },
      emit = { line -> out.add(Json.parseToJsonElement(line).jsonObject) },
      log = {},
      control = true,
      extraCommand = extra,
      standby = standby,
    )

  private fun next(): JsonObject = out.poll(20, TimeUnit.SECONDS) ?: error("no output")

  private fun nextWith(key: String, value: String): JsonObject {
    while (true) {
      val line = next()
      if (line[key]?.jsonPrimitive?.content == value) return line
    }
  }

  @Test
  fun `restart replaces the simulator, keeping its data, and answers with the new one's ready`() {
    val supervisor = supervisor()
    supervisor.start()
    assertEquals("first.apk", nextWith("event", "ready")["apk"]!!.jsonPrimitive.content)

    supervisor.submit("""{"id":1,"cmd":"restart"}""")
    val reply = nextWith("id", "1")
    assertEquals("true", reply["ok"]!!.jsonPrimitive.content)
    val result = reply["result"]!!.jsonObject
    assertEquals("3", result["keptData"]!!.jsonPrimitive.content)
    val info = result["info"]!!.jsonObject
    assertTrue(info["restore"]!!.jsonPrimitive.content.contains("compose-preview-data"))
    assertEquals("7", result["startup"]!!.jsonObject["totalMs"]!!.jsonPrimitive.content)

    supervisor.submit("""{"id":2,"cmd":"restart","keepData":false}""")
    val clean = nextWith("id", "2")["result"]!!.jsonObject
    assertEquals("false", clean["keptData"]!!.jsonPrimitive.content)
    assertEquals("", clean["info"]!!.jsonObject["restore"]!!.jsonPrimitive.content)

    supervisor.submit("""{"id":3,"cmd":"info"}""")
    assertEquals("true", nextWith("id", "3")["ok"]!!.jsonPrimitive.content)
    supervisor.close()
    assertEquals(0, supervisor.awaitExit())
  }

  @Test
  fun `a command in flight when the simulator is replaced fails instead of never answering`() {
    val supervisor = supervisor(device = "watch")
    supervisor.start()
    nextWith("event", "ready")
    supervisor.submit("""{"id":5,"cmd":"hang"}""")
    supervisor.submit("""{"id":6,"cmd":"restart","keepData":false}""")
    val failed = nextWith("id", "5")
    assertEquals("false", failed["ok"]!!.jsonPrimitive.content)
    assertEquals("watch", failed["device"]!!.jsonPrimitive.content)
    assertEquals("true", nextWith("id", "6")["ok"]!!.jsonPrimitive.content)
    supervisor.close()
  }

  @Test
  fun `the cli's own commands restart onto another launch`() {
    val supervisor =
      supervisor(
        extra = { s, cmd, _ ->
          if (cmd == "update") {
            s.restart(SimulatorSupervisor.Launch(File("second.apk"), emptyList()), false, "update")
          } else null
        }
      )
    supervisor.start()
    nextWith("event", "ready")
    supervisor.submit("""{"id":"u","cmd":"update"}""")
    val reply = nextWith("id", "u")["result"]!!.jsonObject
    assertEquals("update", reply["reason"]!!.jsonPrimitive.content)
    assertEquals("second.apk", reply["info"]!!.jsonObject["apk"]!!.jsonPrimitive.content)
    assertEquals("second.apk", supervisor.launch.apk.path)
    supervisor.submit("""{"id":7,"cmd":"info"}""")
    assertEquals(
      "second.apk",
      nextWith("id", "7")["result"]!!.jsonObject["apk"]!!.jsonPrimitive.content,
    )
    supervisor.close()
  }

  @Test
  fun `a standby takes over a restart, with the data, and another is prepared`() {
    val supervisor = supervisor(standby = true)
    supervisor.start()
    nextWith("event", "ready")

    supervisor.submit("""{"id":1,"cmd":"restart"}""")
    val first = nextWith("id", "1")["result"]!!.jsonObject
    assertEquals("true", first["standby"]!!.jsonPrimitive.content)
    val info = first["info"]!!.jsonObject
    assertEquals("true", info["warm"]!!.jsonPrimitive.content)
    assertTrue(info["restore"]!!.jsonPrimitive.content.contains("compose-preview-data"))

    supervisor.submit("""{"id":2,"cmd":"restart","keepData":false}""")
    val second = nextWith("id", "2")["result"]!!.jsonObject
    assertEquals("true", second["standby"]!!.jsonPrimitive.content)
    assertEquals("", second["info"]!!.jsonObject["restore"]!!.jsonPrimitive.content)
    supervisor.close()
    assertEquals(0, supervisor.awaitExit())
  }

  @Test
  fun `a restore that fails keeps the snapshot for the next restart`() {
    var fail = true
    val supervisor = supervisor(failRestore = { fail })
    supervisor.start()
    nextWith("event", "ready")

    supervisor.submit("""{"id":1,"cmd":"restart"}""")
    val failed = nextWith("id", "1")["result"]!!.jsonObject
    assertEquals("false", failed["keptData"]!!.jsonPrimitive.content)
    assertEquals("disk full", failed["restoreError"]!!.jsonPrimitive.content)
    val snapshot = File(failed["snapshot"]!!.jsonPrimitive.content)
    assertTrue(snapshot.isDirectory, "the snapshot is kept, not deleted")

    fail = false
    supervisor.submit("""{"id":2,"cmd":"restart"}""")
    val retried = nextWith("id", "2")["result"]!!.jsonObject
    assertEquals(
      snapshot.path,
      retried["info"]!!.jsonObject["restore"]!!.jsonPrimitive.content,
      "the next restart restores the kept snapshot, not the empty app's",
    )
    supervisor.close()
  }
}
