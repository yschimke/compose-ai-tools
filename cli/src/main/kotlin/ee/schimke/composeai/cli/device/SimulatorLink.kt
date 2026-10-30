package ee.schimke.composeai.cli.device

import ee.schimke.composeai.cli.destroyTree
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One simulator JVM, driven over its `--control stdio` channel by this process rather than by a
 * person: the virtual device's adb, console and gRPC requests become control commands with ids of
 * their own ([ID_PREFIX]), and their replies come back here. Every other line — events, and the
 * replies to commands a `--control stdio` client sent through [forward] — goes to [onLine].
 */
internal class SimulatorLink(command: List<String>, private val onLine: (String) -> Unit) {

  private val process: Process =
    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
  private val writer =
    BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
  private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
  private val ids = AtomicLong()

  /** The `ready` event's `info`, once the app (or the system UI) is up. */
  val ready = CompletableFuture<JsonObject>()

  init {
    Thread(
        {
          process.inputStream.bufferedReader().useLines { lines -> for (line in lines) route(line) }
          val gone = IllegalStateException("the simulator exited (${process.waitFor()})")
          ready.completeExceptionally(gone)
          pending.values.forEach { it.completeExceptionally(gone) }
        },
        "simulator-link",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  val alive: Boolean
    get() = process.isAlive

  fun onExit(): CompletableFuture<Process> = process.onExit()

  private fun route(line: String) {
    val json = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    val id = (json?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (json != null && id != null && id.startsWith(ID_PREFIX)) {
      pending.remove(id)?.complete(json)
      return
    }
    if (json?.get("event")?.jsonPrimitive?.contentOrNull == "ready") {
      ready.complete(json["info"] as? JsonObject ?: JsonObject(emptyMap()))
    }
    onLine(line)
  }

  /** A control client's line, sent on as it is. */
  fun forward(line: String) = write(line)

  /**
   * Sends [command] and waits for its reply: the `result` when `ok`, else [SimulatorError] with the
   * simulator's message.
   */
  fun request(command: JsonObject, timeoutMs: Long = 60_000): JsonObject {
    val id = ID_PREFIX + ids.incrementAndGet()
    val reply = CompletableFuture<JsonObject>()
    pending[id] = reply
    write(
      Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(command + ("id" to JsonPrimitive(id))),
      )
    )
    val answer =
      try {
        reply.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: java.util.concurrent.ExecutionException) {
        throw SimulatorError(e.cause?.message ?: "the simulator is gone")
      } catch (e: java.util.concurrent.TimeoutException) {
        pending.remove(id)
        throw SimulatorError("the simulator did not answer ${command["cmd"]} in ${timeoutMs}ms")
      }
    if ((answer["ok"] as? JsonPrimitive)?.content == "true") {
      return answer["result"] as? JsonObject ?: JsonObject(emptyMap())
    }
    throw SimulatorError((answer["error"] as? JsonPrimitive)?.content ?: "failed")
  }

  fun request(cmd: String, vararg args: Pair<String, JsonElement>): JsonObject =
    request(
      buildJsonObject {
        put("cmd", JsonPrimitive(cmd))
        for ((k, v) in args) put(k, v)
      }
    )

  private fun write(line: String) {
    synchronized(writer) {
      writer.write(line)
      writer.newLine()
      writer.flush()
    }
  }

  /** Asks the simulator to quit, then makes sure it has. */
  fun stop() {
    if (process.isAlive) {
      runCatching { write("""{"cmd":"quit"}""") }
      if (!process.waitFor(5, TimeUnit.SECONDS)) destroyTree(process)
    }
  }

  class SimulatorError(message: String) : RuntimeException(message)

  companion object {
    const val ID_PREFIX = "device-"
  }
}
