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

package ee.schimke.composeai.cli.serve

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pool of long-lived `RcJvmRenderWorkerMain` processes, so a cmp-jvm render costs a frame on a
 * warm JVM (~85 ms) instead of a fresh Compose Desktop + Skiko boot (~2.3 s).
 *
 * A worker holds nothing project-derived — a `.rc` document is self-describing, which is why the
 * browser's JS player draws the same bytes with no knowledge of any catalog — so **any** worker can
 * serve **any** document, from any module or repository, in any order. There is deliberately no
 * affinity, no per-catalog warmup and no keying: that is the whole difference from the `@Preview`
 * daemon, which must stay per-module because it holds the consumer's classloader.
 *
 * ## Why this is allowed to exist
 *
 * `rc-compare` gates the PR on pixel parity, so a render that depended on how many documents a
 * worker had already drawn would convert a correctness gate into a flake source.
 * `RcJvmHotWorkerDeterminismTest` is the standing proof that it does not: it renders a corpus cold,
 * churns the process with dozens of renders at varied sizes, densities, seeds and formats, then
 * re-renders and asserts byte identity. If that test ever fails, the correct response is to disable
 * this pool ([SYS_PROP_ENABLED]`=off`), not to relax the assertion.
 *
 * ## Failure posture
 *
 * Every failure mode degrades to the pre-existing one-shot subprocess rather than to a broken
 * render:
 * * a sidecar too old to speak the protocol, or a worker that cannot be spawned, reports
 *   [PoolResult.Unusable] and the caller falls back;
 * * after [MAX_START_FAILURES] consecutive spawn/handshake failures the pool disables itself for
 *   the life of the process, so a systematically broken pool costs one failed spawn, not one per
 *   render;
 * * a worker that wedges is destroyed by the watchdog and never returned to the idle set;
 * * a document the player genuinely cannot draw reports [PoolResult.Failed] — that is a real answer
 *   and is **not** retried on the one-shot path, which would double the cost of every bad document.
 *
 * Workers are recycled after [maxRendersPerWorker] renders or [maxWorkerAgeMillis], bounding any
 * native leak without giving up the amortisation.
 *
 * Thread-safe: [maxWorkers] permits gate admission, and a worker is only ever checked out to one
 * thread at a time, so a worker's streams are never touched concurrently.
 */
internal class RcJvmWorkerPool(
  private val classpath: List<File>,
  private val javaBin: String,
  private val extraJvmArgs: List<String>,
  private val maxWorkers: Int,
  private val maxRendersPerWorker: Int,
  private val maxWorkerAgeMillis: Long,
  private val renderTimeoutSeconds: Long,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * The worker entry point to spawn. Overridden only by tests, which point it at a stub that speaks
   * the same frames without Compose or Skiko — so the protocol, the retire policy and every failure
   * path are covered on a machine with no native render stack.
   */
  private val workerMainClass: String = WORKER_MAIN_CLASS,
) : AutoCloseable {

  sealed interface PoolResult {
    data class Ok(val bytes: ByteArray) : PoolResult

    /** The player answered, and the answer is "I cannot draw this". Do not fall back. */
    data class Failed(val reason: String) : PoolResult

    /** The pool could not serve this at all. The caller should use the one-shot path. */
    data class Unusable(val reason: String) : PoolResult
  }

  private val permits = Semaphore(maxWorkers, /* fair= */ true)
  private val idle = ArrayDeque<Worker>()

  /**
   * Every worker this pool has started and not yet discarded — **including** the ones currently
   * checked out to a render thread.
   *
   * [idle] alone is not enough to shut down: a worker that is mid-render is absent from it, so
   * closing only the parked ones would leave its child JVM alive and its caller blocked on a pipe
   * read that nothing will ever complete (shutting the watchdog down drops the scheduled kill that
   * would otherwise free it).
   */
  private val liveWorkers = LinkedHashSet<Worker>()
  private val lock = Any()
  private val requestIds = AtomicInteger(0)
  private var startFailures = 0
  private var disabledReason: String? = null
  private var closed = false

  private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "rcjvm-pool-watchdog").apply { isDaemon = true }
  }

  fun render(
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seedsText: String,
    format: RcJvmServerRenderer.Format,
    /**
     * The `ColorTheme` branch to select — see `RcJvmServerRenderer.render`. Light by default,
     * matching that entry point: a headless render has no host theme to follow, and one that varied
     * with the machine's desktop setting would not be reproducible.
     */
    theme: RcJvmServerRenderer.RenderTheme = RcJvmServerRenderer.RenderTheme.LIGHT,
    /**
     * Budget for this render, so the caller can bound pool-attempt + fallback together rather than
     * letting each lane spend the full timeout in turn. Defaults to the pool's own timeout.
     */
    timeoutSeconds: Long = renderTimeoutSeconds,
  ): PoolResult {
    synchronized(lock) {
      disabledReason?.let {
        return PoolResult.Unusable(it)
      }
      if (closed) return PoolResult.Unusable("cmp-jvm worker pool is closed")
    }

    permits.acquire()
    var worker: Worker? = null
    try {
      worker =
        takeIdle()
          ?: when (val started = startWorker(timeoutSeconds)) {
            is StartOutcome.Started -> started.worker
            is StartOutcome.Failed -> return PoolResult.Unusable(started.reason)
          }

      val result =
        worker.render(
          docBytes,
          spec,
          seedsText,
          format,
          theme,
          requestIds.incrementAndGet(),
          timeoutSeconds,
        )
      if (result is PoolResult.Unusable) {
        // The worker broke mid-request (wedged, died, desynchronised). It is already destroyed;
        // dropping it here means the next caller spawns a fresh one.
        discard(worker)
        worker = null
      }
      return result
    } finally {
      val finished = worker
      if (finished != null) {
        if (finished.shouldRetire(clock(), maxRendersPerWorker, maxWorkerAgeMillis)) {
          discard(finished)
        } else {
          returnIdle(finished)
        }
      }
      permits.release()
    }
  }

  /**
   * Hand out a parked worker, discarding any that died while idle (an OOM-killer, a stray `pkill
   * java`) or that aged out while parked — handing back a corpse would surface its EOF as a render
   * failure on a document that is perfectly fine.
   */
  private fun takeIdle(): Worker? {
    val doomed = ArrayList<Worker>()
    val chosen =
      synchronized(lock) {
        var picked: Worker? = null
        while (picked == null) {
          val candidate = idle.pollFirst() ?: break
          if (
            candidate.isAlive() &&
              !candidate.shouldRetire(clock(), maxRendersPerWorker, maxWorkerAgeMillis)
          ) {
            picked = candidate
          } else {
            doomed += candidate
          }
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

  /**
   * Forget a worker and destroy its process. Idempotent, so the races that can double-discard one —
   * [close] running while a render thread is finishing with it, say — are harmless.
   */
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

  private fun startWorker(timeoutSeconds: Long): StartOutcome {
    val command = buildList {
      add(javaBin)
      addAll(extraJvmArgs)
      add("-cp")
      add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
      add(workerMainClass)
    }
    return try {
      val worker = Worker(command, watchdog, clock())
      worker.handshake(minOf(HANDSHAKE_TIMEOUT_SECONDS, timeoutSeconds.coerceAtLeast(1)))
      val registered =
        synchronized(lock) {
          startFailures = 0
          // `close()` may have run while this worker was booting. Registering it now would leak a
          // child JVM that nothing will ever reap.
          if (closed) false else liveWorkers.add(worker)
        }
      if (!registered) {
        worker.close()
        StartOutcome.Failed("cmp-jvm worker pool is closed")
      } else {
        StartOutcome.Started(worker)
      }
    } catch (e: Exception) {
      val reason = "cmp-jvm worker pool could not start a worker: ${e.message}"
      synchronized(lock) {
        startFailures++
        if (startFailures >= MAX_START_FAILURES) {
          disabledReason =
            "$reason (disabled after $startFailures consecutive failures; " +
              "falling back to one-shot renders)"
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
    // Every worker, not just the parked ones. Destroying a checked-out worker's process is what
    // unblocks the render thread waiting on its pipe — and it has to happen *before* the watchdog
    // stops, because `shutdownNow()` drops the scheduled kill that is the only other way out of
    // that read.
    doomed.forEach { it.close() }
    watchdog.shutdownNow()
  }

  /**
   * One worker process plus the frame streams and the bookkeeping that decides when to retire it.
   */
  private class Worker(
    command: List<String>,
    private val watchdog: java.util.concurrent.ScheduledExecutorService,
    private val bornAtMillis: Long,
  ) {
    private val process = ProcessBuilder(command).redirectErrorStream(false).start()
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
          }
        } catch (_: IOException) {
          // The process went away; nothing to drain.
        }
      }
        .apply {
          name = "rcjvm-worker-stderr"
          isDaemon = true
          start()
        }
    }

    fun isAlive(): Boolean = process.isAlive

    fun shouldRetire(now: Long, maxRenders: Int, maxAgeMillis: Long): Boolean =
      renders >= maxRenders || (now - bornAtMillis) >= maxAgeMillis

    /**
     * Read the worker's hello frame, under a watchdog: a JVM that starts but never speaks (a broken
     * classpath that hangs, a native loader stuck on a lock) must not block a render thread
     * forever.
     */
    fun handshake(timeoutSeconds: Long) {
      val guard = armWatchdog(timeoutSeconds)
      try {
        val magic = fromWorker.readInt()
        if (magic != MAGIC_HELLO) {
          throw IOException("unexpected hello magic $magic (is lib-rcjvm stale?)")
        }
        val version = fromWorker.readInt()
        if (version != PROTOCOL_VERSION) {
          throw IOException(
            "worker speaks protocol $version, this cli speaks $PROTOCOL_VERSION " +
              "(is lib-rcjvm stale?)"
          )
        }
      } catch (e: Exception) {
        close()
        throw IOException("${e.message.orEmpty()}${stderrSuffix()}", e)
      } finally {
        guard.disarm()
      }
    }

    fun render(
      docBytes: ByteArray,
      spec: RcJvmRenderSpec,
      seedsText: String,
      format: RcJvmServerRenderer.Format,
      theme: RcJvmServerRenderer.RenderTheme,
      requestId: Int = 0,
      renderTimeoutSeconds: Long,
    ): PoolResult {
      val guard = armWatchdog(renderTimeoutSeconds)
      var timedOut = false
      try {
        val seeds = seedsText.toByteArray(Charsets.UTF_8)
        toWorker.writeInt(MAGIC_REQUEST)
        toWorker.writeInt(requestId)
        toWorker.writeInt(spec.widthPx)
        toWorker.writeInt(spec.heightPx)
        toWorker.writeInt(spec.density.toRawBits())
        toWorker.writeInt(
          if (format == RcJvmServerRenderer.Format.SVG) WIRE_FORMAT_SVG else WIRE_FORMAT_PNG
        )
        // After `format`, matching `RcJvmRenderWorkerMain`. Both ends ship from the same build, so
        // the frame is versioned by the build rather than negotiated.
        toWorker.writeInt(theme.frame)
        toWorker.writeInt(seeds.size)
        toWorker.write(seeds)
        toWorker.writeInt(docBytes.size)
        toWorker.write(docBytes)
        toWorker.flush()

        val magic = fromWorker.readInt()
        if (magic != MAGIC_RESPONSE) {
          throw IOException("unexpected response magic $magic")
        }
        fromWorker.readInt() // requestId — single in-flight request per worker, so informational.
        val status = fromWorker.readInt()
        val payloadLen = fromWorker.readInt()
        if (payloadLen < 0) throw IOException("negative payload length $payloadLen")
        val payload = ByteArray(payloadLen).also { fromWorker.readFully(it) }

        renders++
        return if (status == STATUS_OK) {
          PoolResult.Ok(payload)
        } else {
          PoolResult.Failed("cmp-jvm render failed: ${String(payload, Charsets.UTF_8).take(300)}")
        }
      } catch (e: Exception) {
        timedOut = guard.fired()
        close()
        val reason =
          if (timedOut) {
            "cmp-jvm render timed out after ${renderTimeoutSeconds}s"
          } else {
            "cmp-jvm pooled worker failed: ${e.message}${stderrSuffix()}"
          }
        // Unusable, not Failed: the *worker* broke, so nothing was learned about the document and
        // the caller is entitled to try the one-shot path.
        return PoolResult.Unusable(reason)
      } finally {
        guard.disarm()
      }
    }

    /**
     * Arm the kill-switch that bounds a blocking frame read.
     *
     * A pipe read has no timeout, so destroying the process is the only thing that can unblock the
     * render thread. The returned guard records whether it actually *fired*: inferring that from
     * `!process.isAlive` instead would race, because `destroyForcibly` returns before the OS has
     * reaped the process, and a timeout would then be reported as a generic worker failure.
     */
    private fun armWatchdog(timeoutSeconds: Long): Guard {
      val fired = java.util.concurrent.atomic.AtomicBoolean(false)
      val future =
        watchdog.schedule(
          {
            fired.set(true)
            process.destroyForcibly()
          },
          timeoutSeconds,
          TimeUnit.SECONDS,
        )
      return Guard(fired, future)
    }

    class Guard(
      private val fired: java.util.concurrent.atomic.AtomicBoolean,
      private val future: java.util.concurrent.ScheduledFuture<*>,
    ) {
      fun fired(): Boolean = fired.get()

      fun disarm() {
        future.cancel(false)
      }
    }

    private fun stderrSuffix(): String {
      val tail = synchronized(stderrTail) { stderrTail.lastOrNull() }
      return tail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()
    }

    fun close() {
      runCatching { toWorker.close() }
      runCatching { fromWorker.close() }
      runCatching { process.destroyForcibly() }
    }
  }

  internal companion object {
    const val WORKER_MAIN_CLASS = "ee.schimke.composeai.rcembedded.jvm.RcJvmRenderWorkerMainKt"

    // Mirrors `RcJvmRenderWorkerMain.kt`. The cli cannot depend on the player module (its Skiko
    // natives are deliberately kept off the cli classpath — that is why the render is a subprocess
    // at all), so the wire constants are duplicated here on purpose. The version check in
    // [Worker.handshake] is what keeps the duplication honest: a sidecar that disagrees is refused
    // and the caller falls back, rather than the two sides silently misreading each other.
    const val MAGIC_HELLO = 0x52435731
    const val MAGIC_REQUEST = 0x52435131
    const val MAGIC_RESPONSE = 0x52435231
    // 2 adds the per-request `theme` field; see `RcJvmRenderWorkerMain`'s frame layout.
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 0
    const val STATUS_FAILED = 1
    const val WIRE_THEME_LIGHT = 0
    const val WIRE_THEME_DARK = 1
    const val WIRE_FORMAT_PNG = 0
    const val WIRE_FORMAT_SVG = 1

    const val HANDSHAKE_TIMEOUT_SECONDS = 60L
    const val MAX_START_FAILURES = 3
    const val STDERR_TAIL_LINES = 40

    const val SYS_PROP_ENABLED = "composeai.rcjvm.pool"
    const val SYS_PROP_WORKERS = "composeai.rcjvm.pool.workers"
    const val SYS_PROP_MAX_RENDERS = "composeai.rcjvm.pool.maxRenders"
    const val SYS_PROP_MAX_AGE_MINUTES = "composeai.rcjvm.pool.maxAgeMinutes"

    /**
     * Default worker count. Each worker is a full Compose Desktop JVM, so this is deliberately
     * small and independent of core count past a point — serve's own render semaphore already
     * bounds concurrency, and more workers buy resident memory rather than throughput.
     */
    fun defaultWorkers(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

    fun isEnabled(): Boolean =
      !System.getProperty(SYS_PROP_ENABLED).equals("off", ignoreCase = true)

    fun configuredWorkers(): Int =
      System.getProperty(SYS_PROP_WORKERS)?.toIntOrNull()?.coerceIn(1, 16) ?: defaultWorkers()

    fun configuredMaxRenders(): Int =
      System.getProperty(SYS_PROP_MAX_RENDERS)?.toIntOrNull()?.coerceAtLeast(1) ?: 200

    fun configuredMaxAgeMillis(): Long =
      (System.getProperty(SYS_PROP_MAX_AGE_MINUTES)?.toLongOrNull()?.coerceAtLeast(1) ?: 30L) *
        60_000L
  }
}
