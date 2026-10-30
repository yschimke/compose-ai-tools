package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * `launch --pair`: the phone's and the watch's simulators as one session. Without the control
 * channel both share the terminal and show their windows. With it, this process owns both channels:
 * a stdin command goes to the simulator its `"device"` names (the first, the phone, when it names
 * none), and each JSON line a simulator prints comes out with its `"device"` added, so one client
 * drives the pair. Every other line either simulator prints — its stderr (the app's logcat,
 * Robolectric, stack traces) and, without the control channel, its stdout — comes out prefixed with
 * its device, `[phone] ` or `[watch] `, so two interleaved logs stay readable. Either simulator
 * ending ends the other; closing stdin ends both.
 */
internal class PairedLaunch(
  /** One supervisor per device, the first (the phone) taking commands that name none. */
  private val devices: List<Pair<String, SimulatorSupervisor>>,
  private val control: Boolean,
  /**
   * Other names a command's `device` may use: a device for its first app, a package for its app.
   */
  private val aliases: Map<String, String> = emptyMap(),
  /** Where a command naming no device goes: the first simulator, or a name [aliases] routes. */
  private val primary: String = devices.first().first,
) {

  fun run(): Int {
    devices.forEach { (_, supervisor) -> supervisor.start() }
    val hook = Thread { devices.forEach { (_, supervisor) -> supervisor.destroy() } }
    Runtime.getRuntime().addShutdownHook(hook)
    val supervisors = devices.toMap()
    if (control) {
      daemon("paired-in") {
        System.`in`.bufferedReader().useLines { lines ->
          for (line in lines) {
            if (line.isBlank()) continue
            // A lone simulator takes every command, whatever device it names.
            val target =
              if (devices.size == 1) devices.single().second
              else (deviceOf(line) ?: primary).let { supervisors[it] ?: supervisors[aliases[it]] }
            if (target == null) {
              emit(
                """{"ok":false,"error":"no device ${deviceOf(line)}; ${supervisors.keys} are running"}"""
              )
              continue
            }
            target.submit(line)
          }
        }
        devices.forEach { (_, supervisor) -> supervisor.close() }
      }
    }

    // The first simulator to end ends the pair.
    val exit =
      java.util.concurrent.CompletableFuture.anyOf(
          *devices
            .map { (_, supervisor) ->
              java.util.concurrent.CompletableFuture.supplyAsync { supervisor.awaitExit() }
            }
            .toTypedArray()
        )
        .get() as Int
    devices.forEach { (_, supervisor) -> supervisor.destroy() }
    try {
      Runtime.getRuntime().removeShutdownHook(hook)
    } catch (_: IllegalStateException) {
      // Shutdown already began.
    }
    // The last lines either simulator printed, its exit's stack trace among them.
    Thread.sleep(PUMP_DRAIN_MS)
    return exit
  }

  companion object {
    /** The `device` a control command names, or null. */
    internal fun deviceOf(line: String): String? = runCatching {
      (Json.parseToJsonElement(line) as? JsonObject)?.get("device")?.jsonPrimitive?.content
    }
      .getOrNull()

    private const val PUMP_DRAIN_MS = 500L

    /** A line for this process's stdout, whole even when both simulators print at once. */
    fun emit(line: String) {
      synchronized(System.out) {
        println(line)
        System.out.flush()
      }
    }

    /** A line for this process's stderr. */
    fun log(line: String) {
      synchronized(System.err) { System.err.println(line) }
    }

    /** A simulator's log line, prefixed with its device: `[watch] D/Tile: …`. */
    internal fun prefix(line: String, device: String): String = "[$device] $line"

    /**
     * [line] with `"device":<device>` added, when it is a JSON object; otherwise [prefix]ed: the
     * control channel's stdout carries only JSON, so anything else is stray output.
     */
    internal fun tag(line: String, device: String): String {
      val trimmed = line.trimStart()
      if (!trimmed.startsWith("{")) return prefix(line, device)
      val rest = trimmed.substring(1).trimStart()
      val separator = if (rest.startsWith("}")) "" else ","
      return "{\"device\":${JsonPrimitive(device)}$separator$rest"
    }

    private fun daemon(name: String, body: () -> Unit) =
      Thread(body, name).apply {
        isDaemon = true
        start()
      }
  }
}
