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
 * A pool of long-lived `RcJvmRenderWorkerMain` processes, so a cmp-jvm render costs ~85 ms on a
 * warm JVM instead of a ~2.3 s Compose Desktop + Skiko boot. `.rc` documents are self-describing,
 * so any worker serves any document: no affinity or keying (unlike the per-module `@Preview`
 * daemon).
 *
 * Rendering must not depend on worker history, since `rc-compare` gates PRs on pixel parity;
 * `RcJvmHotWorkerDeterminismTest` asserts byte identity after churn. If it fails, disable the pool
 * ([SYS_PROP_ENABLED]`=off`) rather than relax the test.
 *
 * Failures degrade to the one-shot subprocess:
 * * an old sidecar or failed spawn reports [PoolResult.Unusable] and the caller falls back;
 * * after [MAX_START_FAILURES] consecutive spawn/handshake failures the pool disables itself;
 * * a wedged worker is destroyed by the watchdog and never reused;
 * * an undrawable document reports [PoolResult.Failed], which is not retried one-shot.
 *
 * Workers recycle after [maxRendersPerWorker] renders or [maxWorkerAgeMillis] to bound native
 * leaks. Thread-safe: [maxWorkers] permits gate admission and a worker is checked out to one thread
 * at a time.
 */
// Public because `:server` call sites live in another module; not a widened API by intent.
public class RcJvmWorkerPool(
  private val classpath: List<File>,
  private val javaBin: String,
  private val extraJvmArgs: List<String>,
  private val maxWorkers: Int,
  private val maxRendersPerWorker: Int,
  private val maxWorkerAgeMillis: Long,
  private val renderTimeoutSeconds: Long,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * The worker entry point; tests point it at a stub speaking the same frames without
   * Compose/Skiko.
   */
  private val workerMainClass: String = WORKER_MAIN_CLASS,
) : AutoCloseable {

  public sealed interface PoolResult {
    public data class Ok(val bytes: ByteArray) : PoolResult

    /** The player answered, and the answer is "I cannot draw this". Do not fall back. */
    public data class Failed(val reason: String) : PoolResult

    /** The pool could not serve this at all. The caller should use the one-shot path. */
    public data class Unusable(val reason: String) : PoolResult
  }

  private val permits = Semaphore(maxWorkers, /* fair= */ true)
  private val idle = ArrayDeque<Worker>()

  /**
   * Every started, undiscarded worker, including checked-out ones: [close] must kill those too, or
   * their callers stay blocked on a pipe read forever.
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

  public fun render(
    docBytes: ByteArray,
    spec: RcJvmRenderSpec,
    seedsText: String,
    format: RcJvmServerRenderer.Format,
    /** The `ColorTheme` branch; light by default for reproducible headless renders. */
    theme: RcJvmServerRenderer.RenderTheme = RcJvmServerRenderer.RenderTheme.LIGHT,
    /** Budget for this render, so pool attempt + fallback share one timeout. */
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
        // The worker broke mid-request and is already destroyed; the next caller spawns a fresh
        // one.
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
   * Hand out a parked worker, discarding any that died or aged out while idle so a corpse's EOF
   * isn't reported as a document failure.
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
   * Forget a worker and destroy its process. Idempotent, so racing double-discards are harmless.
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
          // [close] may have run while this worker booted; registering it would leak its JVM.
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
    // Kill every worker, including checked-out ones, before the watchdog stops — `shutdownNow()`
    // drops the scheduled kills that would otherwise unblock their pipe reads.
    doomed.forEach { it.close() }
    watchdog.shutdownNow()
  }

  /** One worker process with its frame streams and retirement bookkeeping. */
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
     * Read the hello frame under a watchdog, so a JVM that never speaks can't block a render
     * thread.
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
        // After `format`, matching `RcJvmRenderWorkerMain`; both ship from one build, so no
        // negotiation.
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
        // Unusable, not Failed: the worker broke, so the caller may try the one-shot path.
        return PoolResult.Unusable(reason)
      } finally {
        guard.disarm()
      }
    }

    /**
     * Arm the kill switch that bounds a blocking pipe read (which has no timeout). The guard
     * records whether it fired, since `!process.isAlive` races with `destroyForcibly`.
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

  // Public because `:server` call sites live in another module; not a widened API by intent.
  public companion object {
    public const val WORKER_MAIN_CLASS: String =
      "ee.schimke.composeai.rcjvm.RcJvmRenderWorkerMainKt"

    // Mirrors `RcJvmRenderWorkerMain.kt` in `:rc-render-jvm`, which the CLI can't depend on (its
    // Skiko natives stay off this classpath). The handshake's version check refuses a disagreeing
    // sidecar.
    public const val MAGIC_HELLO: Int = 0x52435731
    public const val MAGIC_REQUEST: Int = 0x52435131
    public const val MAGIC_RESPONSE: Int = 0x52435231
    // 2 adds the per-request `theme` field; see `RcJvmRenderWorkerMain`'s frame layout.
    public const val PROTOCOL_VERSION: Int = 2
    public const val STATUS_OK: Int = 0
    public const val STATUS_FAILED: Int = 1
    public const val WIRE_THEME_LIGHT: Int = 0
    public const val WIRE_THEME_DARK: Int = 1
    public const val WIRE_FORMAT_PNG: Int = 0
    public const val WIRE_FORMAT_SVG: Int = 1

    public const val HANDSHAKE_TIMEOUT_SECONDS: Long = 60L
    public const val MAX_START_FAILURES: Int = 3
    public const val STDERR_TAIL_LINES: Int = 40

    public const val SYS_PROP_ENABLED: String = "composeai.rcjvm.pool"
    public const val SYS_PROP_WORKERS: String = "composeai.rcjvm.pool.workers"
    public const val SYS_PROP_MAX_RENDERS: String = "composeai.rcjvm.pool.maxRenders"
    public const val SYS_PROP_MAX_AGE_MINUTES: String = "composeai.rcjvm.pool.maxAgeMinutes"

    /**
     * Default worker count: deliberately small, since each is a full Compose Desktop JVM and
     * serve's render semaphore already bounds concurrency.
     */
    public fun defaultWorkers(): Int =
      (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

    public fun isEnabled(): Boolean =
      !System.getProperty(SYS_PROP_ENABLED).equals("off", ignoreCase = true)

    public fun configuredWorkers(): Int =
      System.getProperty(SYS_PROP_WORKERS)?.toIntOrNull()?.coerceIn(1, 16) ?: defaultWorkers()

    public fun configuredMaxRenders(): Int =
      System.getProperty(SYS_PROP_MAX_RENDERS)?.toIntOrNull()?.coerceAtLeast(1) ?: 200

    public fun configuredMaxAgeMillis(): Long =
      (System.getProperty(SYS_PROP_MAX_AGE_MINUTES)?.toLongOrNull()?.coerceAtLeast(1) ?: 30L) *
        60_000L
  }
}
