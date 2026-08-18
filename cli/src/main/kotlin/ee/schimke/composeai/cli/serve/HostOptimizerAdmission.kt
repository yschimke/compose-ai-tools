package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

/** One observation of the host resources background optimization must yield to. */
data class HostResourceSample(
  val loadPerCpu: Double?,
  val cpuUtilization: Double?,
  val memoryAvailableFraction: Double?,
)

/** Thresholds deliberately have separate stop/resume sides so a busy host cannot flap. */
data class OptimizerPressureThresholds(
  val stopLoadPerCpu: Double = 0.85,
  val resumeLoadPerCpu: Double = 0.60,
  val stopCpuUtilization: Double = 0.85,
  val resumeCpuUtilization: Double = 0.70,
  val stopMemoryAvailableFraction: Double = 0.15,
  val resumeMemoryAvailableFraction: Double = 0.25,
  val resumeQuietMillis: Long = 30_000L,
  val sampleIntervalMillis: Long = 2_000L,
)

/** Host-pressure state published on `/status.json` with the optimizer admission counters. */
@Serializable
data class OptimizerPressureSnapshot(
  val constrained: Boolean,
  val reason: String? = null,
  val loadPerCpu: Double? = null,
  val cpuUtilization: Double? = null,
  val memoryAvailableFraction: Double? = null,
  val sampledAtEpochMillis: Long? = null,
)

/**
 * Hysteretic host-resource gate for best-effort optimization.
 *
 * A high reading stops admission immediately. Resumption requires every available reading to be on
 * the safe side for [OptimizerPressureThresholds.resumeQuietMillis], so a render finishing does not
 * instantly admit another cold daemon while the host is still recovering.
 */
class OptimizerPressureGate(
  private val sample: () -> HostResourceSample?,
  private val thresholds: OptimizerPressureThresholds = OptimizerPressureThresholds(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var cached = OptimizerPressureSnapshot(constrained = false)
  private var nextSampleAt = Long.MIN_VALUE
  private var safeSince = Long.MIN_VALUE

  fun snapshot(): OptimizerPressureSnapshot =
    synchronized(lock) {
      val now = clock()
      if (now < nextSampleAt) return@synchronized cached
      nextSampleAt = now + thresholds.sampleIntervalMillis.coerceAtLeast(0L)
      val current = runCatching(sample).getOrNull() ?: return@synchronized cached
      val reasons = buildList {
        current.loadPerCpu
          ?.takeIf { it >= thresholds.stopLoadPerCpu }
          ?.let { add("load ${formatRatio(it)} per CPU") }
        current.cpuUtilization
          ?.takeIf { it >= thresholds.stopCpuUtilization }
          ?.let { add("CPU ${formatPercent(it)}") }
        current.memoryAvailableFraction
          ?.takeIf { it <= thresholds.stopMemoryAvailableFraction }
          ?.let { add("memory available ${formatPercent(it)}") }
      }
      val safe =
        current.loadPerCpu?.let { it <= thresholds.resumeLoadPerCpu } != false &&
          current.cpuUtilization?.let { it <= thresholds.resumeCpuUtilization } != false &&
          current.memoryAvailableFraction?.let { it >= thresholds.resumeMemoryAvailableFraction } !=
            false
      val constrained =
        if (reasons.isNotEmpty()) {
          safeSince = Long.MIN_VALUE
          true
        } else if (!cached.constrained) {
          false
        } else if (!safe) {
          safeSince = Long.MIN_VALUE
          true
        } else {
          if (safeSince == Long.MIN_VALUE) safeSince = now
          now - safeSince < thresholds.resumeQuietMillis
        }
      cached =
        OptimizerPressureSnapshot(
          constrained = constrained,
          reason =
            when {
              reasons.isNotEmpty() -> reasons.joinToString(", ")
              constrained -> "host recovering"
              else -> null
            },
          loadPerCpu = current.loadPerCpu,
          cpuUtilization = current.cpuUtilization,
          memoryAvailableFraction = current.memoryAvailableFraction,
          sampledAtEpochMillis = now,
        )
      cached
    }

  private fun formatRatio(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

  private fun formatPercent(value: Double): String =
    "%.0f%%".format(java.util.Locale.ROOT, value * 100.0)
}

/** Reads Linux host load, CPU time and available memory through the proc filesystem. */
class LinuxHostResourceSampler(private val procRoot: File = File("/proc")) {
  private val previousCpu = AtomicReference<CpuTimes?>()

  fun sample(): HostResourceSample? {
    if (!procRoot.isDirectory) return null
    val statLines = runCatching { File(procRoot, "stat").readLines() }.getOrNull().orEmpty()
    val cpuCount =
      statLines
        .count { it.startsWith("cpu") && it.length > 3 && it[3].isDigit() }
        .coerceAtLeast(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    val load = runCatching {
      File(procRoot, "loadavg").readText().trim().substringBefore(' ').toDouble() / cpuCount
    }
      .getOrNull()
    val cpu = statLines.firstOrNull()?.takeIf { it.startsWith("cpu ") }?.let(::cpuUtilization)
    val memory = runCatching {
      val values =
        File(procRoot, "meminfo").useLines { lines ->
          lines
            .mapNotNull { line ->
              val key = line.substringBefore(':', missingDelimiterValue = "")
              val value = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
              value?.let { key to it }
            }
            .toMap()
        }
      val total = values["MemTotal"]?.takeIf { it > 0 } ?: return@runCatching null
      values["MemAvailable"]?.toDouble()?.div(total)
    }
      .getOrNull()
      ?.let { it }
    if (load == null && cpu == null && memory == null) return null
    return HostResourceSample(load, cpu, memory)
  }

  private fun cpuUtilization(line: String): Double? {
    val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
    if (values.size < 4) return null
    val idle = values[3] + values.getOrElse(4) { 0L }
    val total = values.sum()
    val now = CpuTimes(total, idle)
    val before = previousCpu.getAndSet(now) ?: return null
    val totalDelta = now.total - before.total
    if (totalDelta <= 0) return null
    return (1.0 - (now.idle - before.idle).toDouble() / totalDelta).coerceIn(0.0, 1.0)
  }

  private data class CpuTimes(val total: Long, val idle: Long)
}

/** A host-wide optimizer lease. Closing it releases both the catalog and lane locks. */
fun interface OptimizerHostLease : AutoCloseable

/** Cross-process admission used by every preview replica sharing one coordination directory. */
fun interface OptimizerHostCoordinator {
  fun tryAcquire(system: String): OptimizerHostLease?

  companion object {
    val NONE = OptimizerHostCoordinator { OptimizerHostLease {} }
  }
}

/**
 * Coordinates optimizer work across server replicas with advisory file locks.
 *
 * A per-system lock prevents two replicas warming the same generation concurrently. A lane lock
 * caps all optimizer passes on the physical host, rather than multiplying the configured lane count
 * by the number of replicas. Locks are released by the kernel if a replica exits or is OOM-killed.
 */
class FileOptimizerHostCoordinator(
  private val directory: File,
  private val lanes: Int,
) : OptimizerHostCoordinator, AutoCloseable {
  @Volatile private var leaderLock: HeldFileLock? = null

  init {
    Runtime.getRuntime().addShutdownHook(Thread({ close() }, "optimizer-host-lock-release"))
  }

  override fun tryAcquire(system: String): OptimizerHostLease? {
    if (!(directory.isDirectory || directory.mkdirs())) return null
    // One replica owns optimization for its lifetime. A pass-sized system lock alone prevents
    // simultaneous work, but the next slice could move to a replica whose in-memory view predates
    // the first replica's writes and redundantly warm the same generation. Leadership keeps the
    // cache/index view coherent; the kernel hands it to a survivor if the owner exits or is killed.
    if (!ensureLeader()) return null
    val systemLock = tryLock(File(directory, "system-${digest(system)}.lock")) ?: return null
    for (lane in 0 until lanes.coerceAtLeast(1)) {
      val laneLock = tryLock(File(directory, "lane-$lane.lock"))
      if (laneLock != null) {
        return OptimizerHostLease {
          laneLock.close()
          systemLock.close()
        }
      }
    }
    systemLock.close()
    return null
  }

  @Synchronized
  private fun ensureLeader(): Boolean {
    if (leaderLock != null) return true
    leaderLock = tryLock(File(directory, "leader.lock"))
    return leaderLock != null
  }

  @Synchronized
  override fun close() {
    leaderLock?.close()
    leaderLock = null
  }

  private fun tryLock(file: File): HeldFileLock? {
    val randomAccess = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: return null
    val channel = randomAccess.channel
    val lock =
      try {
        channel.tryLock()
      } catch (_: OverlappingFileLockException) {
        null
      } catch (_: Exception) {
        null
      }
    if (lock == null) {
      runCatching { channel.close() }
      runCatching { randomAccess.close() }
      return null
    }
    return HeldFileLock(randomAccess, channel, lock)
  }

  private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .take(12)
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private class HeldFileLock(
    private val randomAccess: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
  ) : AutoCloseable {
    override fun close() {
      runCatching { lock.release() }
      runCatching { channel.close() }
      runCatching { randomAccess.close() }
    }
  }
}
