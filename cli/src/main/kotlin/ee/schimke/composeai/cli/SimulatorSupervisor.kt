package ee.schimke.composeai.cli

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One simulated device's JVM, kept behind `launch`'s control channel so it can be replaced without
 * ending the session: `restart` starts the same app again in a fresh JVM, `update` starts another
 * APK (a rebuilt one) in its place, and live reload ([LiveReload]) starts it with freshly compiled
 * classes ahead of the converted ones. Each keeps the app's data unless asked not to, as `adb
 * install -r` does: the old simulator copies its data directory and external storage
 * (`snapshotData`) and the new one restores them before its `Application` is created.
 *
 * Commands it does not handle itself go to the running simulator as they are; its replies and
 * events come back as they are. Replies to commands that were in flight when the simulator was
 * replaced fail rather than never arriving. A simulator that fails to start leaves the session up,
 * so a fix can be followed by another `reload` or `update`.
 *
 * [device] names the simulator in a `--pair` session: its JSON lines carry it as `"device"` and its
 * log lines start `[phone] ` / `[watch] `.
 */
internal class SimulatorSupervisor(
  /** `phone` / `watch` in a paired session; null for a lone simulator. */
  private val device: String?,
  initial: Launch,
  /** The simulator JVM's command line for a launch. */
  private val command: (Launch) -> List<String>,
  /** A line for this process's stdout. */
  private val emit: (String) -> Unit,
  /** A line for this process's stderr. */
  private val log: (String) -> Unit,
  /** With the control channel; otherwise the simulator's stdout is passed through as log lines. */
  private val control: Boolean,
  /** `update`, `reload` and other commands the CLI handles; null for any it does not. */
  private val extraCommand: ((SimulatorSupervisor, String, JsonObject) -> JsonObject?)? = null,
  /** Whether the session outlives an app that exits on its own (live reload: fix and reload). */
  private val outliveExits: Boolean = false,
) {

  /** What a simulator JVM runs: the APK, its classes (overlays first), and extra properties. */
  data class Launch(
    val apk: File,
    val classes: List<File>,
    val properties: Map<String, String> = emptyMap(),
  )

  @Volatile
  var launch: Launch = initial
    private set

  @Volatile private var current: Child? = null
  private val worker = Executors.newSingleThreadExecutor { r ->
    Thread(r, "simulator-${device ?: "control"}").apply { isDaemon = true }
  }
  private val internalIds = AtomicLong(1)
  private val finished = CompletableFuture<Int>()
  private val stderrTail = ArrayDeque<String>()

  /** Starts the first simulator. Its `ready` goes out as the session's. */
  fun start() {
    current = spawn(launch, first = true)
  }

  /** An event of the CLI's own on the control channel, such as the APK's `conversion`. */
  fun event(json: JsonObject) = emitJson(json)

  /** Runs [line], a control-channel command, after every command before it. */
  fun submit(line: String) {
    worker.execute {
      try {
        dispatch(line)
      } catch (e: Exception) {
        reply(idOf(line), error = "$e")
      }
    }
  }

  /** The exit code once the session is over: its simulator exited, or [close] was called. */
  fun awaitExit(): Int = finished.get()

  /** Ends the session: the simulator is asked to exit (stdin closed), then ended. */
  fun close() {
    worker.execute {
      current?.stop()
      finished.complete(current?.process?.exitValueOrNull() ?: 0)
    }
    worker.shutdown()
  }

  /** Ends the simulator immediately (the launcher is going away). */
  fun destroy() {
    current?.let { destroyTree(it.process) }
  }

  // ---------------------------------------------------------------------------------------------

  private fun dispatch(line: String) {
    val json = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    val cmd = json?.string("cmd")
    if (json == null || cmd == null) {
      forward(line, json)
      return
    }
    val id = json["id"]
    val result =
      try {
        when (cmd) {
          "restart" ->
            restart(
              launch,
              keepData = json.boolean("keepData") ?: (json.boolean("clearData") != true),
              reason = "restart",
            )
          else -> extraCommand?.invoke(this, cmd, json)
        }
      } catch (e: Exception) {
        reply(id, error = e.message ?: "$e")
        return
      }
    if (result == null) forward(line, json) else reply(id, result = result)
  }

  private fun forward(line: String, json: JsonObject?) {
    val child = current
    if (child == null || !child.process.isAlive) {
      reply(
        json?.get("id"),
        error =
          "the app is not running" +
            (lastFailure?.let { ": $it" } ?: "") +
            "; restart, update or reload it",
      )
      return
    }
    json?.get("id")?.let { child.pending += it.toString() }
    child.write(line)
  }

  @Volatile private var lastFailure: String? = null

  /**
   * Replaces the running simulator with one running [next]: snapshots the app's data first when
   * [keepData] (and the old simulator is still there to ask), stops the old JVM, starts the new
   * one, and waits for its `ready`. Returns the timings and the new simulator's `ready` info.
   */
  fun restart(next: Launch, keepData: Boolean, reason: String): JsonObject {
    val start = System.nanoTime()
    val old = current
    var snapshot: File? = null
    var snapshotMs = 0L
    var kept: JsonElement = JsonPrimitive(false)
    if (keepData && old != null && old.process.isAlive) {
      val dir = Files.createTempDirectory("compose-preview-data").toFile()
      val t = System.nanoTime()
      val saved =
        old.request(
          buildJsonObject {
            put("cmd", "snapshotData")
            put("to", dir.path)
          },
          SNAPSHOT_TIMEOUT_MS,
        )
      snapshotMs = elapsed(t)
      if (saved is Reply.Ok) {
        snapshot = dir
        kept = saved.result["files"] ?: JsonPrimitive(true)
      } else {
        dir.deleteRecursively()
        log(prefix("compose-preview launch: could not keep the app's data: $saved"))
      }
    }
    val stopStart = System.nanoTime()
    if (old != null) {
      old.retired = true
      old.stop()
      old.failPending("the simulator was replaced ($reason)")
    }
    val stopMs = elapsed(stopStart)
    val withData =
      next.copy(
        properties =
          next.properties + (snapshot?.let { mapOf(RESTORE_PROPERTY to it.path) } ?: emptyMap())
      )
    val spawnStart = System.nanoTime()
    val child = spawn(withData, first = false)
    current = child
    val ready =
      try {
        child.ready.get(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      } catch (e: Exception) {
        val cause = (e.cause ?: e).message ?: "$e"
        lastFailure = "the $reason did not start: $cause"
        child.retired = true
        destroyTree(child.process)
        current = null
        snapshot?.deleteRecursively()
        throw IllegalStateException("${lastFailure}\n${tail()}")
      }
    lastFailure = null
    snapshot?.deleteRecursively()
    launch = next
    val startMs = elapsed(spawnStart)
    val result = buildJsonObject {
      put("reason", reason)
      put("keptData", kept)
      put("snapshotMs", snapshotMs)
      put("stopMs", stopMs)
      put("startMs", startMs)
      put("totalMs", elapsed(start))
      ready["startup"]?.let { put("startup", it) }
      put("info", JsonObject(ready.filterKeys { it != "startup" }))
    }
    emitJson(
      buildJsonObject {
        put("event", "restarted")
        put("reason", reason)
        put("totalMs", elapsed(start))
      }
    )
    return result
  }

  // ---------------------------------------------------------------------------------------------

  private fun spawn(launch: Launch, first: Boolean): Child {
    val stamped =
      launch.copy(
        properties = launch.properties + (LAUNCHED_PROPERTY to "${System.currentTimeMillis()}")
      )
    val builder = ProcessBuilder(command(stamped))
    if (!control) builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
    val child = Child(builder.start(), first)
    child.pump()
    return child
  }

  private inner class Child(val process: Process, private val first: Boolean) {
    val ready = CompletableFuture<JsonObject>()
    val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val internal = ConcurrentHashMap<String, CompletableFuture<Reply>>()
    private val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    @Volatile var retired = false

    fun pump() {
      daemon("simulator-out-${device ?: "app"}") {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach(::onLine)
        }
        ready.completeExceptionally(IllegalStateException("the simulator exited (${exit()})"))
        internal.values.forEach { it.complete(Reply.Failed("the simulator exited")) }
      }
      daemon("simulator-err-${device ?: "app"}") {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach { line ->
            synchronized(stderrTail) {
              stderrTail.addLast(line)
              while (stderrTail.size > TAIL_LINES) stderrTail.removeFirst()
            }
            log(prefix(line))
          }
        }
      }
      process.onExit().thenRun {
        if (retired) return@thenRun
        failPending("the simulator exited (${exit()})")
        if (outliveExits && current === this) {
          lastFailure = "it exited (${exit()})"
          emitJson(
            buildJsonObject {
              put("event", "exited")
              put("code", process.exitValue())
            }
          )
        } else {
          finished.complete(process.exitValue())
        }
      }
    }

    private fun onLine(line: String) {
      if (!control) {
        emit(prefix(line))
        return
      }
      val json = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
      if (json == null) {
        emitRaw(line)
        return
      }
      val id = json["id"]?.toString()
      if (id != null) {
        internal.remove(id)?.let { future ->
          future.complete(
            if (json.boolean("ok") == true)
              Reply.Ok(json["result"] as? JsonObject ?: JsonObject(emptyMap()))
            else Reply.Failed(json.string("error") ?: "failed")
          )
          return
        }
        pending.remove(id)
      }
      if (json.string("event") == "ready") {
        ready.complete(JsonObject(json.filterKeys { it != "event" }))
        // A replacement's ready is folded into the restart's reply and `restarted` event.
        if (!first) return
      }
      emitRaw(line)
    }

    fun write(line: String) {
      synchronized(stdin) {
        stdin.write(line)
        stdin.newLine()
        stdin.flush()
      }
    }

    fun request(command: JsonObject, timeoutMs: Long): Reply {
      val id = "\"cli-${internalIds.getAndIncrement()}\""
      val future = CompletableFuture<Reply>()
      internal[id] = future
      return try {
        write(JsonObject(command + ("id" to Json.parseToJsonElement(id))).toString())
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: Exception) {
        Reply.Failed("$e")
      } finally {
        internal.remove(id)
      }
    }

    fun failPending(message: String) {
      for (id in pending.toList()) {
        pending.remove(id)
        emitJson(
          buildJsonObject {
            put("id", Json.parseToJsonElement(id))
            put("ok", false)
            put("error", message)
          }
        )
      }
    }

    /** Closing stdin ends the simulator (its control channel exits on EOF); then it is ended. */
    fun stop() {
      runCatching { synchronized(stdin) { stdin.close() } }
      if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) destroyTree(process)
    }

    private fun exit(): String =
      if (process.isAlive) "still running" else "exit ${process.exitValue()}"
  }

  private sealed interface Reply {
    data class Ok(val result: JsonObject) : Reply

    data class Failed(val message: String) : Reply
  }

  private fun reply(id: JsonElement?, result: JsonObject? = null, error: String? = null) {
    if (!control) {
      error?.let { log(prefix("compose-preview launch: $it")) }
      return
    }
    emitJson(
      buildJsonObject {
        put("id", id ?: kotlinx.serialization.json.JsonNull)
        put("ok", error == null)
        if (error == null) put("result", result ?: JsonObject(emptyMap())) else put("error", error)
      }
    )
  }

  private fun emitJson(json: JsonObject) {
    if (control) emitRaw(json.toString())
  }

  private fun emitRaw(line: String) {
    emit(if (device == null) line else PairedLaunch.tag(line, device))
  }

  private fun prefix(line: String): String =
    if (device == null) line else PairedLaunch.prefix(line, device)

  private fun tail(): String =
    synchronized(stderrTail) { stderrTail.toList() }.takeLast(TAIL_REPORTED).joinToString("\n")

  private fun idOf(line: String): JsonElement? = runCatching {
    (Json.parseToJsonElement(line) as? JsonObject)?.get("id")
  }
    .getOrNull()

  companion object {
    /** `:simulator-host`'s `AppFiles.RESTORE_PROPERTY`. */
    const val RESTORE_PROPERTY = "composeai.simulator.restoreData"
    /** `:simulator-host`'s `Startup.CLI_PROPERTY`. */
    const val LAUNCHED_PROPERTY = "composeai.simulator.t.launched"

    private const val SNAPSHOT_TIMEOUT_MS = 60_000L
    private const val READY_TIMEOUT_MS = 300_000L
    private const val STOP_GRACE_MS = 5_000L
    private const val TAIL_LINES = 200
    private const val TAIL_REPORTED = 30

    private fun elapsed(startNanos: Long): Long =
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun JsonObject.string(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
      (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun Process.exitValueOrNull(): Int? = if (isAlive) null else exitValue()

    private fun daemon(name: String, body: () -> Unit) =
      Thread(body, name).apply {
        isDaemon = true
        start()
      }
  }
}
