package ee.schimke.composeai.cli

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
  private val devices: List<Pair<String, List<String>>>,
  private val control: Boolean,
) {

  fun run(): Int {
    val processes = devices.map { (device, command) ->
      val builder = ProcessBuilder(command)
      if (!control) builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
      device to builder.start()
    }
    val hook = Thread { processes.forEach { (_, process) -> destroyTree(process) } }
    Runtime.getRuntime().addShutdownHook(hook)
    val pumps = mutableListOf<Thread>()
    for ((device, process) in processes) {
      pumps +=
        daemon("paired-err-$device") {
          process.errorStream.bufferedReader().useLines { lines ->
            for (line in lines) {
              val prefixed = prefix(line, device)
              synchronized(System.err) { System.err.println(prefixed) }
            }
          }
        }
    }
    if (!control) {
      for ((device, process) in processes) {
        pumps +=
          daemon("paired-out-$device") {
            process.inputStream.bufferedReader().useLines { lines ->
              for (line in lines) {
                val prefixed = prefix(line, device)
                synchronized(System.out) {
                  println(prefixed)
                  System.out.flush()
                }
              }
            }
          }
      }
    }

    if (control) {
      val inputs = processes.associate { (device, process) ->
        device to BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
      }
      val primary = devices.first().first
      for ((device, process) in processes) {
        pumps +=
          daemon("paired-out-$device") {
            process.inputStream.bufferedReader().useLines { lines ->
              for (line in lines) {
                val tagged = tag(line, device)
                synchronized(System.out) {
                  println(tagged)
                  System.out.flush()
                }
              }
            }
          }
      }
      daemon("paired-in") {
        System.`in`.bufferedReader().useLines { lines ->
          for (line in lines) {
            if (line.isBlank()) continue
            val target = inputs[deviceOf(line) ?: primary]
            if (target == null) {
              synchronized(System.out) {
                println(
                  """{"ok":false,"error":"no device ${deviceOf(line)}; ${inputs.keys} are paired"}"""
                )
              }
              continue
            }
            runCatching {
              target.write(line)
              target.newLine()
              target.flush()
            }
          }
        }
        inputs.values.forEach { runCatching { it.close() } }
      }
    }

    // The first simulator to end ends the pair.
    val exit =
      java.util.concurrent.CompletableFuture.anyOf(
          *processes.map { (_, p) -> p.onExit() }.toTypedArray()
        )
        .get() as Process
    processes.forEach { (_, p) -> if (p !== exit) destroyTree(p) }
    // The last lines either simulator printed, its exit's stack trace among them.
    pumps.forEach { it.join(PUMP_DRAIN_MS) }
    try {
      Runtime.getRuntime().removeShutdownHook(hook)
    } catch (_: IllegalStateException) {
      // Shutdown already began.
    }
    return exit.exitValue()
  }

  companion object {
    /** The `device` a control command names, or null. */
    internal fun deviceOf(line: String): String? = runCatching {
      (Json.parseToJsonElement(line) as? JsonObject)?.get("device")?.jsonPrimitive?.content
    }
      .getOrNull()

    private const val PUMP_DRAIN_MS = 2_000L

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
