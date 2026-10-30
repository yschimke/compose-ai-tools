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
 * ending the session: `restart` starts the same app again in a fresh JVM, and `update` starts
 * another APK (a rebuilt one) in its place. Each keeps the app's data unless asked not to, as `adb
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
  /** Whether the session outlives an app that exits on its own, to be restarted or updated. */
  private val outliveExits: Boolean = false,
  /**
   * Keep a standby simulator JVM prepared up to the app's own code (`:simulator-host`'s `Standby`),
   * so a `restart` or class `reload` skips the JVM, Robolectric's sandbox and the framework's
   * setup. It costs a second JVM's memory while idle.
   */
  private val standby: Boolean = false,
) {

  /**
   * What a simulator JVM runs: the APK, its classes (overlay directories first, the APK's converted
   * jar last), and extra properties.
   */
  data class Launch(
    val apk: File,
    val classes: List<File>,
    val properties: Map<String, String> = emptyMap(),
  )

  @Volatile
  var launch: Launch = initial
    private set

  @Volatile private var current: Child? = null
  /** The prepared standby, its launch and the overlay directory its class path names. */
  @Volatile private var spare: Spare? = null

  private class Spare(val child: Child, val launch: Launch, val slot: File)

  private val worker = Executors.newSingleThreadExecutor { r ->
    Thread(r, "simulator-${device ?: "control"}").apply { isDaemon = true }
  }
  private val internalIds = AtomicLong(1)
  private val finished = CompletableFuture<Int>()
  private val stderrTail = ArrayDeque<String>()

  /** Starts the first simulator. Its `ready` goes out as the session's. */
  fun start() {
    val first = spawn(launch, first = true)
    current = first
    if (standby) first.ready.thenRun { worker.execute { prepareSpare() } }
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
      dropSpare()
      current?.stop()
      finished.complete(current?.process?.exitValueOrNull() ?: 0)
    }
    worker.shutdown()
  }

  /** Ends the simulator immediately (the launcher is going away). */
  fun destroy() {
    current?.let { destroyTree(it.process) }
    dropSpare()
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
  /** The data a replacement that failed to start was to restore. */
  @Volatile private var orphanedData: File? = null

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
    if (keepData && (old == null || !old.process.isAlive)) {
      // The last attempt stopped the old simulator and then failed to start: its data is here.
      orphanedData?.let { dir ->
        snapshot = dir
        kept = JsonPrimitive(true)
      }
    }
    orphanedData?.takeIf { it != snapshot }?.deleteRecursively()
    orphanedData = null
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
    val warm = takeSpare(next)
    val child =
      if (warm != null) {
        warm.child.promote(
          withData.properties.filterKeys { it == RESTORE_PROPERTY } +
            (ASKED_PROPERTY to "${System.currentTimeMillis()}")
        )
        warm.child
      } else spawn(withData, first = false)
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
        // Kept for the next restart, update or reload: the app it came from is gone.
        orphanedData = snapshot
        throw IllegalStateException("${lastFailure}\n${tail()}")
      }
    lastFailure = null
    snapshot?.deleteRecursively()
    launch = next
    val startMs = elapsed(spawnStart)
    prepareSpare()
    val result = buildJsonObject {
      put("reason", reason)
      put("standby", warm != null)
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

  /**
   * Starts a standby for the next restart of [launch], unless one is waiting: the same APK and
   * converted classes, with an empty overlay directory of its own in front, which a reload fills
   * just before telling it to go.
   */
  private fun prepareSpare() {
    if (!standby || spare?.child?.process?.isAlive == true) return
    val slot = Files.createTempDirectory("compose-preview-standby").toFile()
    val spareLaunch =
      launch.copy(
        classes = listOf(slot, launch.classes.last()),
        properties = launch.properties - RESTORE_PROPERTY + (STANDBY_PROPERTY to "true"),
      )
    spare = Spare(spawn(spareLaunch, first = false, standby = true), spareLaunch, slot)
  }

  /**
   * The standby, if it can run [next]: the same APK and converted jar, and only directories in
   * front of the jar, which are copied into its overlay directory. Otherwise it is ended.
   */
  private fun takeSpare(next: Launch): Spare? {
    val candidate = spare ?: return null
    spare = null
    val overlays = next.classes.dropLast(1)
    val fits =
      candidate.child.process.isAlive &&
        candidate.launch.apk == next.apk &&
        candidate.launch.classes.last() == next.classes.last() &&
        overlays.all { it.isDirectory } &&
        candidate.launch.properties - STANDBY_PROPERTY == next.properties - RESTORE_PROPERTY
    if (!fits) {
      candidate.retire()
      return null
    }
    // A standby still starting is taken all the same: it is further along than a new JVM.
    val prepared = runCatching {
      candidate.child.prepared.get(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    if (prepared.isFailure) {
      candidate.retire()
      return null
    }
    for (overlay in overlays) overlay.copyRecursively(candidate.slot, overwrite = true)
    return candidate
  }

  private fun Spare.retire() {
    child.retired = true
    destroyTree(child.process)
    slot.deleteRecursively()
  }

  private fun dropSpare() {
    spare?.retire()
    spare = null
  }

  private fun spawn(launch: Launch, first: Boolean, standby: Boolean = false): Child {
    val stamped =
      launch.copy(
        properties = launch.properties + (LAUNCHED_PROPERTY to "${System.currentTimeMillis()}")
      )
    val builder = ProcessBuilder(command(stamped))
    if (!control) builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
    val child = Child(builder.start(), first, standby)
    child.pump()
    return child
  }

  private inner class Child(
    val process: Process,
    private val first: Boolean,
    /** Waiting to be told to go; its log is held back until then. */
    @Volatile private var standby: Boolean = false,
  ) {
    val ready = CompletableFuture<JsonObject>()
    /** A standby's `standby` event: prepared, and waiting for [promote]. */
    val prepared = CompletableFuture<Unit>()
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
        prepared.completeExceptionally(IllegalStateException("the standby exited (${exit()})"))
        internal.values.forEach { it.complete(Reply.Failed("the simulator exited")) }
      }
      daemon("simulator-err-${device ?: "app"}") {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach { line ->
            synchronized(stderrTail) {
              stderrTail.addLast(line)
              while (stderrTail.size > TAIL_LINES) stderrTail.removeFirst()
            }
            if (!standby) log(prefix(line))
          }
        }
      }
      process.onExit().thenRun {
        // A standby, or a simulator already replaced, ends without ending the session.
        if (retired || (!first && current !== this)) return@thenRun
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
      if (json.string("event") == "standby") {
        prepared.complete(Unit)
        return
      }
      if (json.string("event") == "ready") {
        ready.complete(JsonObject(json.filterKeys { it != "event" }))
        // A replacement's ready is folded into the restart's reply and `restarted` event.
        if (!first) return
      }
      emitRaw(line)
    }

    /** Tells a standby to go on, with [properties] (the data to restore, when it was asked). */
    fun promote(properties: Map<String, String>) {
      standby = false
      write(
        JsonObject(
            mapOf("go" to JsonObject(properties.mapValues { (_, value) -> JsonPrimitive(value) }))
          )
          .toString()
      )
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
    /** When a standby was asked to take over: its `startup.totalMs` counts from here. */
    const val ASKED_PROPERTY = "composeai.simulator.t.asked"
    /** `:simulator-host`'s `Standby.PROPERTY`. */
    const val STANDBY_PROPERTY = "composeai.simulator.standby"

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
