/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.plugin

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pool of long-lived `DesktopRendererWorkerMain` processes for one module's render, so a capture
 * costs a frame on a warm JVM instead of a whole fork.
 *
 * `RenderPreviewsTask` forked `:renderer-desktop` once per capture, paying JVM + Compose Desktop +
 * Skiko boot every time — 2.15 s/preview measured end-to-end on m3-catalog (~43 min for its 1095
 * previews) against ~36 ms warm.
 *
 * **Per module, and per task execution.** Unlike `RcJvmWorkerPool` — whose workers take a
 * self-describing `.rc` document from any project — a renderer worker is bound to the consumer's
 * classpath, so it can only serve the module that spawned it. That costs nothing here: every
 * capture in one `composePreviewRender` execution shares that module, which is exactly the run the
 * amortisation applies to. The pool is created and closed inside the task action, so no process
 * outlives the build and nothing process-shaped is held on a task field (configuration cache).
 *
 * **Why this is sound.** The worker calls the renderer's own `main()`, so a pooled capture runs the
 * identical code a forked one did. `DesktopRendererReentrancyTest` pins the two properties that
 * makes safe: repeated in-process renders draw identical pixels, and a capture does not inherit the
 * `@OverrideVariant` seed of the one before it.
 *
 * **What reuse gives up.** A fork gave each capture a fresh JVM, so a preview that mutates
 * top-level or `object` state during composition could not affect any other. A warm worker loads
 * consumer classes once, so that isolation is gone: an order-dependent preview can now influence a
 * later capture. This is the trade the preview daemon has always made — it renders many previews
 * per JVM behind a persistent classloader — so the batch lane is not held to a stricter standard
 * than the interactive one. Bounded rather than defended against: [maxRendersPerWorker] recycles
 * workers, the default of one worker keeps ordering deterministic rather than racy, and
 * [SYS_PROP_ENABLED]`=off` restores per-capture forks exactly. A preview whose pixels depend on
 * what rendered before it is not reproducible for the catalogs either, so the fix in that case is
 * the preview, not the pool.
 *
 * **Failure posture**, mirroring the cmp-jvm pool and the two defects review found there:
 * * anything the pool cannot serve reports [WorkerResult.Unusable] and the caller forks that
 *   capture instead, so the worst case is the cost that was already being paid;
 * * a render the *renderer* rejects reports [WorkerResult.Failed] — a real answer about that
 *   capture, not retried on a fork, which would double the cost of every broken preview;
 * * [close] destroys **checked-out** workers too, before stopping the watchdog: a worker mid-render
 *   is absent from [idle], and `shutdownNow()` drops the scheduled kill that is the only other way
 *   out of a blocked pipe read;
 * * after [MAX_START_FAILURES] consecutive spawn failures the pool disables itself for the rest of
 *   the run, so a systematically broken pool costs one failed spawn rather than one per capture.
 */
internal class DesktopRenderWorkerPool(
  private val classpath: List<File>,
  private val javaExecutable: String,
  private val jvmArgs: List<String>,
  private val maxWorkers: Int,
  private val maxRendersPerWorker: Int,
  private val renderTimeoutSeconds: Long,
  /**
   * Working directory for every worker, which must be the **task project's** directory:
   * `ExecOperations.javaexec` defaulted to it, so a preview reading a relative path resolved it
   * against the subproject. A bare `ProcessBuilder` would inherit the Gradle daemon's directory
   * instead and quietly resolve the same path somewhere else — a difference between the warm and
   * forked lanes, which is exactly what this pool must never introduce.
   */
  private val workingDir: File,
  /**
   * Where a worker's stderr goes. The forked lane let the renderer's own diagnostics through to the
   * build log — missing `@PreviewParameter` providers, device-frame and display-filter failures,
   * the `Render failed …` line that accompanies an error sidecar. Those arrive on *successful*
   * requests (the renderer handles them and returns normally), so a pool that only kept stderr for
   * its own failure messages would silently swallow them.
   */
  private val stderrSink: (String) -> Unit,
  /**
   * What the worker's `LD_LIBRARY_PATH` should be, relative to the daemon's own. Defaults to
   * "inherit", which is what a worker got before [RenderNativeEnv] existed and what it still gets
   * everywhere but a hybrid store/system sandbox.
   */
  private val nativeEnv: RenderNativeEnv.Decision = RenderNativeEnv.Decision.Inherit,
  private val workerMainClass: String = WORKER_MAIN_CLASS,
) : AutoCloseable {

  sealed interface WorkerResult {
    object Ok : WorkerResult

    /** The renderer answered "I could not draw this". Do not fork a retry. */
    data class Failed(val reason: String) : WorkerResult

    /** The pool could not serve this at all; the caller should fork this capture. */
    data class Unusable(val reason: String) : WorkerResult
  }

  private val permits = Semaphore(maxWorkers, /* fair= */ true)
  private val idle = ArrayDeque<Worker>()

  /** Every live worker, **including** checked-out ones — see [close]. */
  private val liveWorkers = LinkedHashSet<Worker>()
  private val lock = Any()
  private val requestIds = AtomicInteger(0)
  private var startFailures = 0
  private var disabledReason: String? = null
  private var closed = false

  /** Captures served warm, for the task's one-line summary. */
  val servedWarm = AtomicInteger(0)

  private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "compose-preview-render-worker-watchdog").apply { isDaemon = true }
  }

  fun render(args: List<String>, overridesSeed: String?): WorkerResult {
    synchronized(lock) {
      disabledReason?.let {
        return WorkerResult.Unusable(it)
      }
      if (closed) return WorkerResult.Unusable("render worker pool is closed")
    }

    permits.acquire()
    var worker: Worker? = null
    try {
      worker =
        takeIdle()
          ?: when (val started = startWorker()) {
            is StartOutcome.Started -> started.worker
            is StartOutcome.Failed -> return WorkerResult.Unusable(started.reason)
          }

      val result = worker.render(args, overridesSeed.orEmpty(), requestIds.incrementAndGet())
      if (result is WorkerResult.Unusable) {
        discard(worker)
        worker = null
      } else {
        servedWarm.incrementAndGet()
      }
      return result
    } finally {
      val finished = worker
      if (finished != null) {
        if (finished.shouldRetire(maxRendersPerWorker)) discard(finished) else returnIdle(finished)
      }
      permits.release()
    }
  }

  private fun takeIdle(): Worker? {
    val doomed = ArrayList<Worker>()
    val chosen =
      synchronized(lock) {
        var picked: Worker? = null
        while (picked == null) {
          val candidate = idle.pollFirst() ?: break
          if (candidate.isAlive() && !candidate.shouldRetire(maxRendersPerWorker))
            picked = candidate
          else doomed += candidate
        }
        picked
      }
    doomed.forEach { discard(it) }
    return chosen
  }

  private fun returnIdle(worker: Worker) {
    val parked =
      synchronized(lock) {
        if (closed || !worker.isAlive()) false
        else {
          idle.addLast(worker)
          true
        }
      }
    if (!parked) discard(worker)
  }

  /** Forget a worker and destroy its process. Idempotent, so a double-discard on a race is safe. */
  private fun discard(worker: Worker) {
    synchronized(lock) {
      liveWorkers.remove(worker)
      idle.remove(worker)
    }
    worker.close()
  }

  private sealed interface StartOutcome {
    class Started(val worker: Worker) : StartOutcome

    class Failed(val reason: String) : StartOutcome
  }

  private fun startWorker(): StartOutcome {
    val command = buildList {
      add(javaExecutable)
      addAll(jvmArgs)
      add("-cp")
      add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
      add(workerMainClass)
    }
    return try {
      // Registered BEFORE the handshake, not after. A worker waiting for its hello frame is a live
      // child process; if `close()` ran while it booted it would be invisible to shutdown, and
      // `watchdog.shutdownNow()` would then drop the handshake kill-switch — leaving the read
      // blocked forever with the JVM still up. Registering first means `close()` can always reap
      // it, and a pool that closed underneath us is detected right after.
      val worker = Worker(command, watchdog, workingDir, stderrSink, nativeEnv)
      val tracked = synchronized(lock) { if (closed) false else liveWorkers.add(worker) }
      if (!tracked) {
        worker.close()
        return StartOutcome.Failed("render worker pool is closed")
      }
      worker.handshake(HANDSHAKE_TIMEOUT_SECONDS)
      synchronized(lock) { startFailures = 0 }
      if (synchronized(lock) { closed }) {
        discard(worker)
        StartOutcome.Failed("render worker pool is closed")
      } else {
        StartOutcome.Started(worker)
      }
    } catch (e: Exception) {
      val reason = "could not start a render worker: ${e.message}"
      synchronized(lock) {
        startFailures++
        if (startFailures >= MAX_START_FAILURES) {
          disabledReason =
            "$reason (disabled after $startFailures consecutive failures; " +
              "forking each capture instead)"
        }
      }
      StartOutcome.Failed(reason)
    }
  }

  override fun close() {
    val doomed =
      synchronized(lock) {
        closed = true
        idle.clear()
        liveWorkers.toList().also { liveWorkers.clear() }
      }
    // Every worker, not just the parked ones, and before the watchdog stops: destroying a
    // checked-out worker's process is what unblocks the thread waiting on its pipe, and
    // `shutdownNow()` would otherwise drop the scheduled kill that is the only other way out.
    doomed.forEach { it.close() }
    watchdog.shutdownNow()
  }

  private class Worker(
    command: List<String>,
    private val watchdog: ScheduledExecutorService,
    workingDir: File,
    private val stderrSink: (String) -> Unit,
    nativeEnv: RenderNativeEnv.Decision,
  ) {
    private val process =
      ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(false)
        .also { RenderNativeEnv.apply(nativeEnv, it.environment()) }
        .start()
    private val toWorker = DataOutputStream(process.outputStream.buffered())
    private val fromWorker = DataInputStream(process.inputStream.buffered())
    private val stderrTail = ArrayDeque<String>()
    private var renders = 0

    init {
      Thread {
        try {
          process.errorStream.bufferedReader().forEachLine { line ->
            synchronized(stderrTail) {
              stderrTail.addLast(line)
              while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.pollFirst()
            }
            // Forwarded as well as buffered: the tail exists for the pool's own failure
            // messages, but most renderer diagnostics ride a *successful* request and would
            // otherwise never be seen.
            stderrSink(line)
          }
        } catch (_: IOException) {
          // Process went away; nothing to drain.
        }
      }
        .apply {
          name = "compose-preview-render-worker-stderr"
          isDaemon = true
          start()
        }
    }

    fun isAlive(): Boolean = process.isAlive

    fun shouldRetire(maxRenders: Int): Boolean = renders >= maxRenders

    fun handshake(timeoutSeconds: Long) {
      val guard = armWatchdog(timeoutSeconds)
      try {
        val magic = fromWorker.readInt()
        if (magic != MAGIC_HELLO) throw IOException("unexpected hello magic $magic")
        val version = fromWorker.readInt()
        if (version != WORKER_PROTOCOL_VERSION) {
          throw IOException(
            "worker speaks protocol $version, this plugin speaks $WORKER_PROTOCOL_VERSION"
          )
        }
      } catch (e: Exception) {
        close()
        throw IOException("${e.message.orEmpty()}${stderrSuffix()}", e)
      } finally {
        guard.disarm()
      }
    }

    fun render(args: List<String>, seed: String, requestId: Int): WorkerResult {
      val guard = armWatchdog(RENDER_GUARD_SECONDS)
      try {
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        toWorker.writeInt(MAGIC_REQUEST)
        toWorker.writeInt(requestId)
        toWorker.writeInt(seedBytes.size)
        toWorker.write(seedBytes)
        toWorker.writeInt(args.size)
        args.forEach {
          val b = it.toByteArray(Charsets.UTF_8)
          toWorker.writeInt(b.size)
          toWorker.write(b)
        }
        toWorker.flush()

        val magic = fromWorker.readInt()
        if (magic != MAGIC_RESPONSE) throw IOException("unexpected response magic $magic")
        fromWorker.readInt() // requestId — one in-flight request per worker, so informational.
        val status = fromWorker.readInt()
        val len = fromWorker.readInt()
        if (len < 0) throw IOException("negative message length $len")
        val message = String(ByteArray(len).also { fromWorker.readFully(it) }, Charsets.UTF_8)

        renders++
        return if (status == STATUS_OK) WorkerResult.Ok else WorkerResult.Failed(message.take(300))
      } catch (e: Exception) {
        val timedOut = guard.fired()
        close()
        // Unusable rather than Failed: the *worker* broke, so nothing was learned about this
        // capture and the caller is entitled to fork it.
        return WorkerResult.Unusable(
          if (timedOut) "render worker timed out after ${RENDER_GUARD_SECONDS}s"
          else "render worker failed: ${e.message}${stderrSuffix()}"
        )
      } finally {
        guard.disarm()
      }
    }

    private fun armWatchdog(timeoutSeconds: Long): Guard {
      val fired = AtomicBoolean(false)
      val future =
        watchdog.schedule(
          {
            fired.set(true)
            // A pipe read has no timeout; destroying the process is the only way to unblock it.
            process.destroyForcibly()
          },
          timeoutSeconds,
          TimeUnit.SECONDS,
        )
      return Guard(fired, future)
    }

    class Guard(private val fired: AtomicBoolean, private val future: ScheduledFuture<*>) {
      /** Whether the kill actually ran — inferring it from process liveness would race. */
      fun fired(): Boolean = fired.get()

      fun disarm() {
        future.cancel(false)
      }
    }

    private fun stderrSuffix(): String =
      synchronized(stderrTail) { stderrTail.lastOrNull() }
        ?.takeIf { it.isNotBlank() }
        ?.let { ": ${it.take(300)}" }
        .orEmpty()

    fun close() {
      runCatching { toWorker.close() }
      runCatching { fromWorker.close() }
      runCatching { process.destroyForcibly() }
    }
  }

  internal companion object {
    const val WORKER_MAIN_CLASS = "ee.schimke.composeai.renderer.DesktopRendererWorkerMainKt"

    // Mirrors `DesktopRendererWorkerMain.kt`. The plugin cannot depend on the renderer module (it
    // is resolved into the consumer's dependency graph), so the wire constants are duplicated on
    // purpose; the version check in [Worker.handshake] is what keeps that honest.
    const val MAGIC_HELLO = 0x43505731
    const val MAGIC_REQUEST = 0x43505131
    const val MAGIC_RESPONSE = 0x43505231
    const val WORKER_PROTOCOL_VERSION = 1
    const val STATUS_OK = 0

    const val HANDSHAKE_TIMEOUT_SECONDS = 120L
    const val MAX_START_FAILURES = 3
    const val STDERR_TAIL_LINES = 40

    /**
     * Per-capture kill-switch. Generous because a single heavy capture (a long scroll, a GIF
     * window) legitimately takes a while, and killing a worker mid-render costs the whole warm JVM.
     */
    const val RENDER_GUARD_SECONDS = 600L

    const val SYS_PROP_ENABLED = "composeai.render.workerPool"
    const val SYS_PROP_WORKERS = "composeai.render.workerPool.workers"
    const val SYS_PROP_MAX_RENDERS = "composeai.render.workerPool.maxRenders"

    fun isEnabled(): Boolean =
      !System.getProperty(SYS_PROP_ENABLED).equals("off", ignoreCase = true)

    /**
     * One worker by default. Captures are already driven serially by the task, and the render
     * itself is Skiko-bound; extra workers buy resident memory rather than throughput. Sharding
     * across Gradle workers remains the way to use more cores.
     */
    fun configuredWorkers(): Int =
      System.getProperty(SYS_PROP_WORKERS)?.toIntOrNull()?.coerceIn(1, 8) ?: 1

    fun configuredMaxRenders(): Int =
      System.getProperty(SYS_PROP_MAX_RENDERS)?.toIntOrNull()?.coerceAtLeast(1) ?: 500
  }
}
