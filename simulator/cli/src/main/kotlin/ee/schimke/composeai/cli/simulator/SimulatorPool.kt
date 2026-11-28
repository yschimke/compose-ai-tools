package ee.schimke.composeai.cli.simulator

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `--instrument --pool`: simulators kept started between runs, so a run skips the JVM's start,
 * Robolectric's sandbox and the framework's setup of the app's resources, display and
 * configuration.
 *
 * A pool is one background process per user, listening on a Unix socket under
 * `<cache>/simulator/pool/`, started by the first run that asks for it and gone after
 * [IDLE_MINUTES] without a run. It keeps [standbys][Standby]: simulator JVMs started with
 * `composeai.simulator.standby`, which stop just before the app's `AppComponentFactory` and wait
 * for a go line (`:simulator-host`'s `Standby`). A run sends its whole simulator command; the pool
 * takes a standby started with the same command less the properties a test run varies ([PER_RUN]:
 * which tests, which shard, where the reports go), hands those over in the go line, and relays the
 * simulator's output and exit code back. Only once that run has ended does it start the next
 * standby for the same command, so the boot does not compete with the tests for CPU.
 *
 * A rebuilt APK converts to new jars, so its command differs and finds no standby: that run boots
 * as without a pool, and leaves a standby for the next. The pool keeps standbys for the
 * [MAX_COMMANDS] most recent commands, [MAX_STANDBYS] in all; each is an idle simulator JVM of a
 * few hundred megabytes.
 */
internal object SimulatorPool {

  const val IDLE_MINUTES = 30L
  const val MAX_COMMANDS = 2
  const val MAX_STANDBYS = 4

  /** How often a quiet run's client is checked for, by a heartbeat line. */
  private const val HEARTBEAT_SECONDS = 1L

  /** `:simulator-host`'s `Standby.PROPERTY`. */
  private const val STANDBY_PROPERTY = "composeai.simulator.standby"

  /** The properties one run of a suite sets and the next changes, handed over in the go line. */
  val PER_RUN =
    setOf(
      "composeai.simulator.instrument.tests",
      "composeai.simulator.instrument.report",
      "composeai.simulator.instrument.json",
      "composeai.simulator.instrument.shard",
    )

  private val STANDBY_LINE = """{"event":"standby"}"""

  fun dir(): File = File(composeAiCacheDir("simulator"), "pool")

  /**
   * The environment variables a simulator reads, or a JVM or the native libraries it loads do: part
   * of a standby's identity, with the working directory, so a run is never handed one started for
   * another context (another `PATH` finds another ffmpeg). The rest of a client's environment
   * varies from build to build (Gradle's own variables among it) without changing what the
   * simulator does.
   */
  private val CONTEXT_ENV =
    setOf(
      "PATH",
      "HOME",
      "TMPDIR",
      "TZ",
      "LANG",
      "LC_ALL",
      "JAVA_HOME",
      "JAVA_TOOL_OPTIONS",
      "_JAVA_OPTIONS",
      "JDK_JAVA_OPTIONS",
      "LD_LIBRARY_PATH",
      "DYLD_LIBRARY_PATH",
      "ANDROID_HOME",
      "ANDROID_SDK_ROOT",
    )

  /** [command] with the context it runs in: what a standby must match to be handed a run. */
  fun identity(command: List<String>, cwd: File?, env: Map<String, String>): List<String> =
    command +
      "cwd=${cwd?.absolutePath}" +
      env
        .filterKeys { it in CONTEXT_ENV || it.startsWith("COMPOSE_PREVIEW_") }
        .toSortedMap()
        .map { (k, v) -> "env:$k=$v" }

  private fun socketFile(dir: File) = File(dir, "pool.sock")

  /**
   * [command] split into the standby's command (no [PER_RUN] property) and those properties, which
   * the go line sets.
   */
  fun split(command: List<String>): Pair<List<String>, Map<String, String>> {
    val go = linkedMapOf<String, String>()
    val base = command.filter { arg ->
      val key = arg.takeIf { it.startsWith("-D") }?.substringAfter("-D")?.substringBefore('=')
      if (key != null && key in PER_RUN) {
        go[key] = arg.substringAfter('=')
        false
      } else true
    }
    return base to go
  }

  // --- The client: one run. ---

  /**
   * Runs [command] in a standby of the pool (starting the pool when none answers), each line of its
   * output passed to [out]. Returns the simulator's exit code; [keep] is how many standbys of this
   * command the pool should hold for the next run (a sharded run's shard count).
   */
  fun run(command: List<String>, keep: Int = 1, out: (String) -> Unit): Int {
    val (base, go) = split(command)
    val channel = connect() ?: throw IOException("the simulator pool did not start; see ${log()}")
    channel.use {
      val writer = PrintStream(Channels.newOutputStream(channel), true, Charsets.UTF_8)
      val request = buildJsonObject {
        put("command", JsonArray(base.map(::JsonPrimitive)))
        put("go", JsonObject(go.mapValues { JsonPrimitive(it.value) }))
        put("keep", keep)
        put("cwd", System.getProperty("user.dir"))
        put("env", JsonObject(System.getenv().mapValues { JsonPrimitive(it.value) }))
      }
      writer.println(request.toString())
      Channels.newInputStream(channel).bufferedReader(Charsets.UTF_8).useLines { lines ->
        for (line in lines) {
          val json =
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
          json["line"]?.jsonPrimitive?.contentOrNull?.let(out)
          json["exit"]?.jsonPrimitive?.intOrNull?.let {
            return it
          }
          json["error"]?.jsonPrimitive?.contentOrNull?.let {
            throw IOException("simulator pool: $it")
          }
        }
      }
    }
    throw IOException("the simulator pool closed the run without an exit code; see ${log()}")
  }

  /** The pool's log: what it started, handed over and stopped. */
  fun log(): File = File(dir(), "pool.log")

  private fun connect(): SocketChannel? {
    tryConnect()?.let {
      return it
    }
    startServer()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
      tryConnect()?.let {
        return it
      }
      Thread.sleep(100)
    }
    return null
  }

  private fun tryConnect(): SocketChannel? {
    val socket = socketFile(dir())
    if (!socket.exists()) return null
    return runCatching {
      SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        connect(UnixDomainSocketAddress.of(socket.toPath()))
      }
    }
      .getOrNull()
  }

  /** Starts the pool in a JVM of its own, from this CLI's class path, outliving this run. */
  private fun startServer() {
    val dir = dir().apply { mkdirs() }
    val java = ProcessHandle.current().info().command().orElse("java")
    ProcessBuilder(
        java,
        "-Xmx128m",
        "-cp",
        System.getProperty("java.class.path"),
        Server::class.java.name,
        dir.absolutePath,
      )
      .redirectOutput(ProcessBuilder.Redirect.appendTo(log()))
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows()) "NUL" else "/dev/null")))
      .start()
  }

  private fun isWindows() = System.getProperty("os.name").startsWith("Windows")

  /** `stop-pool`: ends the pool and its standbys. Returns whether one was running. */
  fun stop(): Boolean {
    val channel = tryConnect() ?: return false
    channel.use {
      PrintStream(Channels.newOutputStream(it), true, Charsets.UTF_8).println("""{"stop":true}""")
      Channels.newInputStream(it).read()
    }
    return true
  }

  // --- The server. ---

  /** A simulator started ahead of a run, waiting for its go line. */
  class Standby(val key: List<String>, val process: Process) {
    @Volatile var ready = false
    val lines = java.util.concurrent.LinkedBlockingQueue<String>()

    init {
      thread(name = "standby-${process.pid()}", isDaemon = true) {
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { all ->
          for (line in all) {
            if (!ready && line == STANDBY_LINE) {
              ready = true
              continue
            }
            lines.put(line)
          }
        }
        lines.put(EOF)
      }
    }

    companion object {
      const val EOF = "\u0000eof"
    }
  }

  class Server(private val dir: File) {
    private val standbys = mutableListOf<Standby>()
    /** Set once the pool is stopping: no standby starts after it. */
    @Volatile private var stopping = false
    /** The standbys handed to a run and still running it. */
    private val active = mutableSetOf<Standby>()
    private val recent = ArrayDeque<List<String>>()
    @Volatile private var lastRun = System.nanoTime()
    @Volatile private var running = 0
    private val lock = Object()

    fun serve() {
      dir.mkdirs()
      // One pool per cache: a second one started by a racing run finds the lock held and leaves.
      val lockChannel =
        FileChannel.open(
          File(dir, "pool.lock").toPath(),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
        )
      val held = lockChannel.tryLock() ?: return
      ignoreTerminalSignals()
      val socket = socketFile(dir)
      socket.delete()
      val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      server.bind(UnixDomainSocketAddress.of(socket.toPath()))
      log("pool ${ProcessHandle.current().pid()} listening on $socket")
      Runtime.getRuntime().addShutdownHook(Thread { shutdown(socket) })
      thread(name = "pool-idle", isDaemon = true) {
        while (true) {
          Thread.sleep(TimeUnit.SECONDS.toMillis(30))
          val idle = TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - lastRun)
          if (running == 0 && idle >= IDLE_MINUTES) {
            log("idle for $idle min: stopping")
            shutdown(socket)
            Runtime.getRuntime().halt(0)
          }
        }
      }
      while (true) {
        val client = server.accept()
        thread(name = "pool-client", isDaemon = true) {
          try {
            handle(client)
          } catch (e: Exception) {
            log("run failed: $e")
          } finally {
            runCatching { client.close() }
          }
        }
      }
      @Suppress("UNREACHABLE_CODE") held.release()
    }

    private fun handle(client: SocketChannel) {
      val reader = Channels.newInputStream(client).bufferedReader(Charsets.UTF_8)
      val writer = PrintStream(Channels.newOutputStream(client), true, Charsets.UTF_8)
      val request = Json.parseToJsonElement(reader.readLine() ?: return).jsonObject
      if (request["stop"] != null) {
        log("asked to stop")
        shutdown(socketFile(dir))
        writer.println("{}")
        Runtime.getRuntime().halt(0)
      }
      val command = request["command"]!!.jsonArray.map { it.jsonPrimitive.content }
      val go = request["go"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
      val keep = (request["keep"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, MAX_STANDBYS)
      val cwd = request["cwd"]?.jsonPrimitive?.contentOrNull?.let(::File)
      val env = request["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
      val key = identity(command, cwd, env)
      val start = System.nanoTime()
      val (standby, warm) =
        synchronized(lock) {
          running++
          lastRun = System.nanoTime()
          remember(key)
          val taken =
            standbys.firstOrNull { it.key == key && it.process.isAlive }?.also { standbys -= it }
          ((taken ?: spawn(command, key, cwd, env)) to (taken != null)).also { active += it.first }
        }
      log(
        "run ${standby.process.pid()}: ${if (warm) "standby ${if (standby.ready) "ready" else "starting"}" else "no standby, booting"}",
      )
      writer.println(
        buildJsonObject {
          put(
            "line",
            "simulator pool: ${if (warm) "a started simulator" else "no started simulator yet; booting one"}",
          )
        },
      )
      val stdin = standby.process.outputStream
      try {
        stdin.write(
          ("""{"go":${JsonObject(go.mapValues { JsonPrimitive(it.value) })}}""" + "\n").toByteArray(
            Charsets.UTF_8,
          ),
        )
        stdin.flush()
        stdin.close()
        while (true) {
          val line = standby.lines.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
          if (line == Standby.EOF) break
          // A quiet second gets a heartbeat, which the client ignores, so a client that went away
          // is noticed while the tests print nothing.
          writer.println(
            if (line == null) "{}" else buildJsonObject { put("line", line) }.toString(),
          )
          // The run's client went away (Ctrl-C, a killed Gradle build): so does its simulator. A
          // reader can't watch for that: a channel's streams share its blocking lock, so a read
          // waiting there would hold up every write.
          if (writer.checkError() && standby.process.isAlive) {
            log("run ${standby.process.pid()}: the client went away; stopping it")
            destroyTree(standby.process)
          }
        }
        val exit = standby.process.waitFor()
        log(
          "run ${standby.process.pid()} exited $exit after " +
            "${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)} ms",
        )
        writer.println(buildJsonObject { put("exit", exit) })
      } finally {
        synchronized(lock) {
          running--
          lastRun = System.nanoTime()
          // Only now, with the run's tests done, are the next standbys started.
          active -= standby
          if (running == 0) refill(command, key, keep, cwd, env)
        }
      }
    }

    /** Standbys of [key] up to [keep], once nothing is running. */
    private fun refill(
      command: List<String>,
      key: List<String>,
      keep: Int,
      cwd: File?,
      env: Map<String, String>,
    ) {
      if (stopping) return
      standbys.removeAll { !it.process.isAlive }
      var have = standbys.count { it.key == key }
      while (have < keep && standbys.size < MAX_STANDBYS) {
        standbys += spawn(command, key, cwd, env)
        have++
      }
    }

    /** Keeps [key] among the [MAX_COMMANDS] most recent, ending standbys of any older one. */
    private fun remember(key: List<String>) {
      recent.remove(key)
      recent.addFirst(key)
      while (recent.size > MAX_COMMANDS) {
        val old = recent.removeLast()
        standbys
          .filter { it.key == old }
          .forEach {
            log("dropping standby ${it.process.pid()}: an older command")
            destroyTree(it.process)
            standbys -= it
          }
      }
    }

    private fun spawn(
      run: List<String>,
      key: List<String>,
      cwd: File?,
      env: Map<String, String>,
    ): Standby {
      val command = run.take(1) + "-D$STANDBY_PROPERTY=true" + run.drop(1)
      val builder = ProcessBuilder(command).redirectErrorStream(true)
      if (env.isNotEmpty()) {
        builder.environment().clear()
        builder.environment().putAll(env)
      }
      cwd?.takeIf { it.isDirectory }?.let(builder::directory)
      val standby = Standby(key, builder.start())
      log("started standby ${standby.process.pid()}")
      return standby
    }

    private fun shutdown(socket: File) {
      synchronized(lock) {
        stopping = true
        // The runs under way too: with the pool gone, nothing would watch for their clients
        // leaving.
        (standbys + active).forEach { destroyTree(it.process) }
        standbys.clear()
        active.clear()
      }
      socket.delete()
    }

    private fun log(message: String) {
      println("${java.time.Instant.now()} $message")
      System.out.flush()
    }

    /**
     * Ctrl-C in the terminal of the run that started the pool signals its whole process group, the
     * pool included; the pool is meant to outlive that run.
     */
    private fun ignoreTerminalSignals() {
      if (System.getProperty("os.name").startsWith("Windows")) return
      for (name in listOf("INT", "HUP")) {
        runCatching {
          sun.misc.Signal.handle(sun.misc.Signal(name), sun.misc.SignalHandler.SIG_IGN)
        }
      }
    }

    companion object {
      @JvmStatic
      fun main(args: Array<String>) {
        Server(File(args.single())).serve()
      }
    }
  }
}
