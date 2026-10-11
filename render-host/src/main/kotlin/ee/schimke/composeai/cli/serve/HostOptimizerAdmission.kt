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
public data class HostResourceSample(
  val loadPerCpu: Double?,
  val cpuUtilization: Double?,
  /** The governing headroom: the smaller of [memoryHost] and [memoryCgroup]. */
  val memoryAvailableFraction: Double?,
  /**
   * The two ceilings [memoryAvailableFraction] is the minimum of, so `/status.json` can say which
   * governs. Null when absent or unreadable (an unlimited cgroup leaves [memoryCgroup] null).
   */
  val memoryHost: Double? = null,
  val memoryCgroup: Double? = null,
)

/**
 * Thresholds have separate stop/resume sides so a busy host cannot flap. Serializable because they
 * are published on `/status.json`: a reading is only diagnosable next to the (possibly overridden)
 * threshold it was judged against.
 */
@Serializable
public data class OptimizerPressureThresholds(
  val stopLoadPerCpu: Double = 0.85,
  val resumeLoadPerCpu: Double = 0.60,
  val stopCpuUtilization: Double = 0.85,
  val resumeCpuUtilization: Double = 0.70,
  val stopMemoryAvailableFraction: Double = 0.15,
  val resumeMemoryAvailableFraction: Double = 0.25,
  val resumeQuietMillis: Long = 30_000L,
  val sampleIntervalMillis: Long = 2_000L,
  /**
   * Longest the gate may hold while no reading is over a stop threshold. A host can sit
   * indefinitely in the dead band between stop and resume sides; hysteresis should delay
   * resumption, not prevent it, so the hold is capped.
   */
  val maxRecoveryMillis: Long = 10 * 60_000L,
  /**
   * Longest a hold whose stop threshold keeps re-tripping may last before the gate opens for
   * [dutyCycleMillis]; `0` restores the permanent latch. A host whose baseline sits on the stop
   * side (e.g. steady 14% available memory) would otherwise never run optimization. Genuine
   * emergencies stay latched ([dutyCycleFloorMemoryAvailableFraction]).
   */
  val starvationCapMillis: Long = 30 * 60_000L,
  /**
   * How long the gate stays open once [starvationCapMillis] is exhausted; `0` disables the cap.
   * Must comfortably exceed a cold Android daemon warm (34-68s) so the window does useful work.
   */
  val dutyCycleMillis: Long = 5 * 60_000L,
  /**
   * Memory headroom below which the duty cycle never opens: the next allocation may OOM-kill the
   * replica.
   */
  val dutyCycleFloorMemoryAvailableFraction: Double = 0.05,
) {
  public companion object {
    /**
     * Thresholds overridden by `composeai.serve.optimizer*` system properties, since what counts as
     * constrained depends on the host's baseline.
     */
    public fun fromSystemProperties(): OptimizerPressureThresholds {
      val defaults = OptimizerPressureThresholds()
      return OptimizerPressureThresholds(
        // `ratio`, not `fraction`: load per CPU is a queue depth and can exceed 1.0.
        stopLoadPerCpu = ratio("optimizerStopLoadPerCpu") ?: defaults.stopLoadPerCpu,
        resumeLoadPerCpu = ratio("optimizerResumeLoadPerCpu") ?: defaults.resumeLoadPerCpu,
        stopCpuUtilization = fraction("optimizerStopCpuUtilization") ?: defaults.stopCpuUtilization,
        resumeCpuUtilization =
          fraction("optimizerResumeCpuUtilization") ?: defaults.resumeCpuUtilization,
        stopMemoryAvailableFraction =
          fraction("optimizerStopMemoryAvailableFraction") ?: defaults.stopMemoryAvailableFraction,
        resumeMemoryAvailableFraction =
          fraction("optimizerResumeMemoryAvailableFraction")
            ?: defaults.resumeMemoryAvailableFraction,
        resumeQuietMillis = millis("optimizerResumeQuietMillis") ?: defaults.resumeQuietMillis,
        sampleIntervalMillis =
          millis("optimizerSampleIntervalMillis") ?: defaults.sampleIntervalMillis,
        maxRecoveryMillis = millis("optimizerMaxRecoveryMillis") ?: defaults.maxRecoveryMillis,
        starvationCapMillis =
          millis("optimizerStarvationCapMillis") ?: defaults.starvationCapMillis,
        dutyCycleMillis = millis("optimizerDutyCycleMillis") ?: defaults.dutyCycleMillis,
        dutyCycleFloorMemoryAvailableFraction =
          fraction("optimizerDutyCycleFloorMemoryAvailableFraction")
            ?: defaults.dutyCycleFloorMemoryAvailableFraction,
      )
    }

    /** A ratio in `0.0..1.0`; anything outside that is a typo, so ignore it rather than obey it. */
    private fun fraction(name: String): Double? =
      System.getProperty("composeai.serve.$name")?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }

    /**
     * Parse a load-per-CPU threshold, which is a queue depth, not a fraction: a busy host sits
     * above 1.0, so parsing it as a fraction would silently drop legitimate overrides. Bounded at
     * [MAX_LOAD_PER_CPU] to still reject typos; above it the default stands.
     */
    private fun ratio(name: String): Double? =
      System.getProperty("composeai.serve.$name")?.toDoubleOrNull()?.takeIf {
        it in 0.0..MAX_LOAD_PER_CPU
      }

    /**
     * Ceiling for a load-per-CPU threshold; a host at 64x its core count has already fallen over.
     */
    private const val MAX_LOAD_PER_CPU = 64.0

    /** Zero is meaningful (sample every call, never hold), so only negatives are rejected. */
    private fun millis(name: String): Long? =
      System.getProperty("composeai.serve.$name")?.toLongOrNull()?.takeIf { it >= 0L }
  }
}

/** The individual readings [OptimizerPressureGate] can trip on, so resumption can be per-signal. */
private enum class PressureSignal {
  LOAD,
  CPU,
  MEMORY;

  /** Whether this signal is back on the safe side of its **resume** threshold. */
  fun recovered(sample: HostResourceSample, thresholds: OptimizerPressureThresholds): Boolean =
    when (this) {
      LOAD -> sample.loadPerCpu?.let { it <= thresholds.resumeLoadPerCpu } != false
      CPU -> sample.cpuUtilization?.let { it <= thresholds.resumeCpuUtilization } != false
      MEMORY ->
        sample.memoryAvailableFraction?.let { it >= thresholds.resumeMemoryAvailableFraction } !=
          false
    }
}

/** Host-pressure state published on `/status.json` with the optimizer admission counters. */
@Serializable
public data class OptimizerPressureSnapshot(
  val constrained: Boolean,
  val reason: String? = null,
  val loadPerCpu: Double? = null,
  val cpuUtilization: Double? = null,
  val memoryAvailableFraction: Double? = null,
  val sampledAtEpochMillis: Long? = null,
  /** How long the current uninterrupted hold has withheld admission, or null when open. */
  val heldMillis: Long? = null,
  /** Set while the starvation cap has opened the gate on a host that is still over a threshold. */
  val dutyCycleUntilEpochMillis: Long? = null,
  /** How many times the starvation cap has had to open this gate since the server started. */
  val dutyCycles: Int = 0,
  /**
   * The effective thresholds [constrained] was judged against (after overrides). Null only when
   * built without a gate.
   */
  val thresholds: OptimizerPressureThresholds? = null,
  /**
   * The two memory ceilings behind [memoryAvailableFraction] (the smaller of them), kept apart
   * because a full cgroup on a roomy host needs a cap change, not a bigger box.
   */
  val memoryHostAvailableFraction: Double? = null,
  val memoryCgroupAvailableFraction: Double? = null,
)

/**
 * Hysteretic host-resource gate for best-effort optimization.
 *
 * A high reading stops admission immediately. Resumption needs every reading that tripped the hold
 * back on its resume side for [OptimizerPressureThresholds.resumeQuietMillis].
 * [OptimizerPressureThresholds.maxRecoveryMillis] bounds a hold stuck in the dead band, and
 * [OptimizerPressureThresholds.starvationCapMillis] bounds one whose reading stays on the stop side
 * by opening a bounded window. Only a host under
 * [OptimizerPressureThresholds.dutyCycleFloorMemoryAvailableFraction] keeps the latch.
 */
public class OptimizerPressureGate(
  private val sample: () -> HostResourceSample?,
  private val thresholds: OptimizerPressureThresholds = OptimizerPressureThresholds(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var cached = OptimizerPressureSnapshot(constrained = false)
  private var nextSampleAt = Long.MIN_VALUE
  private var safeSince = Long.MIN_VALUE

  /** Signals that tripped this hold; only these have to recover to clear it. Empty when open. */
  private var trippedBy = emptySet<PressureSignal>()

  /** When the current hold last saw every stop threshold clear — the [maxRecoveryMillis] anchor. */
  private var recoveringSince = Long.MIN_VALUE

  /** Whether the pressure logic itself wants to hold, before the starvation cap has its say. */
  private var held = false

  /**
   * When the next concession is measured from: the end of each window, or the moment one is cut
   * short.
   */
  private var capAnchor = Long.MIN_VALUE

  /** When the current uninterrupted hold began. Reporting only — `heldMillis` on `/status.json`. */
  private var holdStartedAt = Long.MIN_VALUE

  /** End of the window the starvation cap has opened, or [Long.MIN_VALUE] when not duty-cycling. */
  private var dutyCycleUntil = Long.MIN_VALUE

  private var dutyCycles = 0

  /** What was over a stop threshold on the previous sample — the [newlyTripped] baseline. */
  private var lastTripped = emptySet<PressureSignal>()

  public fun snapshot(): OptimizerPressureSnapshot =
    synchronized(lock) {
      val now = clock()
      if (now < nextSampleAt) return@synchronized cachedClosingExpiredDutyCycle(now)
      nextSampleAt = now + thresholds.sampleIntervalMillis.coerceAtLeast(0L)
      val current =
        runCatching(sample).getOrNull() ?: return@synchronized cachedClosingExpiredDutyCycle(now)
      val tripped = buildList {
        current.loadPerCpu
          ?.takeIf { it >= thresholds.stopLoadPerCpu }
          ?.let { add(PressureSignal.LOAD to "load ${formatRatio(it)} per CPU") }
        current.cpuUtilization
          ?.takeIf { it >= thresholds.stopCpuUtilization }
          ?.let { add(PressureSignal.CPU to "CPU ${formatPercent(it)}") }
        current.memoryAvailableFraction
          ?.takeIf { it <= thresholds.stopMemoryAvailableFraction }
          ?.let { add(PressureSignal.MEMORY to "memory available ${formatPercent(it)}") }
      }
      // Only the signals that tripped the hold must recover.
      val safe = trippedBy.all { it.recovered(current, thresholds) }
      // Signals over a stop threshold now vs. on the previous sample; newly tripped ones cut a duty
      // cycle short. `trippedBy` accumulates over the hold, so it can't answer this.
      val activeSignals = tripped.map { it.first }.toSet()
      val newlyTripped = activeSignals - lastTripped
      lastTripped = activeSignals
      val holding =
        if (tripped.isNotEmpty()) {
          trippedBy = trippedBy + tripped.map { it.first }
          safeSince = Long.MIN_VALUE
          recoveringSince = Long.MIN_VALUE
          true
        } else if (!held) {
          // `held`, not the published `constrained`, which reads open during a duty cycle.
          false
        } else {
          // Nothing is over a stop threshold: the hold ends after `resumeQuietMillis` on the resume
          // side, or after `maxRecoveryMillis` in the dead band.
          if (recoveringSince == Long.MIN_VALUE) recoveringSince = now
          val recoveryExhausted = now - recoveringSince >= thresholds.maxRecoveryMillis
          val quiet =
            if (!safe) {
              safeSince = Long.MIN_VALUE
              false
            } else {
              if (safeSince == Long.MIN_VALUE) safeSince = now
              now - safeSince >= thresholds.resumeQuietMillis
            }
          !quiet && !recoveryExhausted
        }
      if (holding) {
        if (!held) {
          holdStartedAt = now
          capAnchor = now
        }
      } else {
        trippedBy = emptySet()
        safeSince = Long.MIN_VALUE
        recoveringSince = Long.MIN_VALUE
        holdStartedAt = Long.MIN_VALUE
        capAnchor = Long.MIN_VALUE
        dutyCycleUntil = Long.MIN_VALUE
      }
      held = holding
      val constrained = holding && !dutyCycling(current, now, newlyTripped)
      cached =
        OptimizerPressureSnapshot(
          constrained = constrained,
          reason =
            when {
              !holding -> null
              tripped.isNotEmpty() -> tripped.joinToString(", ") { it.second }
              else -> recoveringReason(current)
            },
          loadPerCpu = current.loadPerCpu,
          cpuUtilization = current.cpuUtilization,
          memoryAvailableFraction = current.memoryAvailableFraction,
          sampledAtEpochMillis = now,
          heldMillis = heldMillis(now, holding),
          dutyCycleUntilEpochMillis = dutyCycleUntil.takeIf { it > now },
          dutyCycles = dutyCycles,
          thresholds = thresholds,
          memoryHostAvailableFraction = current.memoryHost,
          memoryCgroupAvailableFraction = current.memoryCgroup,
        )
      cached
    }

  /**
   * The cached snapshot, with an elapsed duty-cycle window closed first; otherwise a sampler that
   * stops working would publish an open gate forever.
   */
  private fun cachedClosingExpiredDutyCycle(now: Long): OptimizerPressureSnapshot {
    if (!held || dutyCycleUntil == Long.MIN_VALUE || now < dutyCycleUntil) return cached
    closeWindow(at = dutyCycleUntil)
    // Recompute `heldMillis` rather than republishing the stale value.
    cached =
      cached.copy(
        constrained = true,
        dutyCycleUntilEpochMillis = null,
        heldMillis = heldMillis(now, holding = true),
      )
    return cached
  }

  /** How long the current hold has withheld admission, or null when the gate is not holding. */
  private fun heldMillis(now: Long, holding: Boolean): Long? =
    if (holding && holdStartedAt != Long.MIN_VALUE) (now - holdStartedAt).coerceAtLeast(0L)
    else null

  /**
   * End the open window, if any, and re-arm the cap from [at]. A window cut short closes at `now`;
   * one that elapsed anchors at its scheduled end, even if the expiry was noticed later.
   */
  private fun closeWindow(at: Long) {
    if (dutyCycleUntil == Long.MIN_VALUE) return
    dutyCycleUntil = Long.MIN_VALUE
    capAnchor = at
  }

  /**
   * Whether the starvation cap lets this held gate through right now: after
   * [OptimizerPressureThresholds.starvationCapMillis] of holding, open a
   * [OptimizerPressureThresholds.dutyCycleMillis] window, then re-arm. Never opened (and closed
   * early) while memory is under, or can't be read against,
   * [OptimizerPressureThresholds.dutyCycleFloorMemoryAvailableFraction]; closed early when a new
   * signal trips. The reason keeps naming the holding reading: a duty cycle is progress despite
   * pressure.
   */
  private fun dutyCycling(
    sample: HostResourceSample,
    now: Long,
    newlyTripped: Set<PressureSignal>,
  ): Boolean {
    // A zero-length window admits nothing, so either knob at 0 disables the cap.
    if (thresholds.starvationCapMillis <= 0L || thresholds.dutyCycleMillis <= 0L) return false
    // Retire an elapsed window immediately, so the early closes below don't re-anchor it.
    if (dutyCycleUntil != Long.MIN_VALUE && now >= dutyCycleUntil) closeWindow(at = dutyCycleUntil)
    // An unknown memory reading is not safe: keep the latch.
    val headroom = sample.memoryAvailableFraction
    if (headroom == null || headroom < thresholds.dutyCycleFloorMemoryAvailableFraction) {
      closeWindow(at = now)
      return false
    }
    // A new signal crossing its stop threshold closes the window immediately.
    if (newlyTripped.isNotEmpty()) {
      closeWindow(at = now)
      return false
    }
    if (now < dutyCycleUntil) return true
    if (capAnchor == Long.MIN_VALUE) return false
    if (now - capAnchor < thresholds.starvationCapMillis) return false
    dutyCycleUntil = now + thresholds.dutyCycleMillis
    capAnchor = dutyCycleUntil
    dutyCycles++
    return true
  }

  /**
   * Why a hold with no reading over a stop threshold is still held: names the signal and the bar it
   * must clear, so a quiet-window countdown is distinguishable from a dead band.
   */
  private fun recoveringReason(sample: HostResourceSample): String {
    val waiting =
      trippedBy
        .filterNot { it.recovered(sample, thresholds) }
        .sorted()
        .map { signal ->
          when (signal) {
            PressureSignal.LOAD ->
              "load per CPU ${formatRatio(sample.loadPerCpu ?: 0.0)}" +
                " above resume ${formatRatio(thresholds.resumeLoadPerCpu)}"
            PressureSignal.CPU ->
              "CPU ${formatPercent(sample.cpuUtilization ?: 0.0)}" +
                " above resume ${formatPercent(thresholds.resumeCpuUtilization)}"
            PressureSignal.MEMORY ->
              "memory available ${formatPercent(sample.memoryAvailableFraction ?: 0.0)}" +
                " below resume ${formatPercent(thresholds.resumeMemoryAvailableFraction)}"
          }
        }
    return if (waiting.isEmpty()) "host recovering"
    else "host recovering: ${waiting.joinToString(", ")}"
  }

  private fun formatRatio(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

  private fun formatPercent(value: Double): String =
    "%.0f%%".format(java.util.Locale.ROOT, value * 100.0)
}

/**
 * Reads Linux load, CPU time and available memory from `/proc`, and from the cgroup when this
 * process is memory-limited: inside a container `/proc/meminfo` reports the host's memory, not the
 * container's limit. The reported fraction is the smaller headroom.
 */
public class LinuxHostResourceSampler(
  private val procRoot: File = File("/proc"),
  private val cgroupRoot: File = File("/sys/fs/cgroup"),
) {
  private val previousCpu = AtomicReference<CpuTimes?>()

  public fun sample(): HostResourceSample? {
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
    // The nearer ceiling governs; an unlimited or unreadable cgroup contributes nothing.
    val cgroup = cgroupMemoryAvailableFraction()
    val constrained = listOfNotNull(memory, cgroup).minOrNull()
    if (load == null && cpu == null && constrained == null) return null
    return HostResourceSample(load, cpu, constrained, memoryHost = memory, memoryCgroup = cgroup)
  }

  /**
   * Fraction of this process's cgroup memory allowance still available, or null when unlimited.
   * Reads cgroup v2 (`memory.max`/`memory.current`) or v1 (`memory.limit_in_bytes`); a v1 limit at
   * or above `MemTotal` means no limit. `inactive_file` (reclaimable page cache) is subtracted from
   * usage, or a container that read many files would look permanently full.
   */
  private fun cgroupMemoryAvailableFraction(): Double? = runCatching {
    readCgroupV2Memory() ?: readCgroupV1Memory()
  }
    .getOrNull()

  private fun readCgroupV2Memory(): Double? {
    val limit =
      File(cgroupRoot, "memory.max").takeIf { it.isFile }?.readText()?.trim() ?: return null
    if (limit == "max") return null
    val max = limit.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val current =
      File(cgroupRoot, "memory.current").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        ?: return null
    val reclaimable = cgroupStatValue(File(cgroupRoot, "memory.stat"), "inactive_file")
    return availableFraction(max, current, reclaimable)
  }

  private fun readCgroupV1Memory(): Double? {
    val directory = File(cgroupRoot, "memory")
    val max =
      File(directory, "memory.limit_in_bytes")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 } ?: return null
    // v1 spells "unlimited" as a sentinel near Long.MAX_VALUE rather than a word.
    if (max >= UNLIMITED_CGROUP_V1_LIMIT) return null
    val current =
      File(directory, "memory.usage_in_bytes")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.toLongOrNull() ?: return null
    val reclaimable = cgroupStatValue(File(directory, "memory.stat"), "total_inactive_file")
    return availableFraction(max, current, reclaimable)
  }

  private fun availableFraction(max: Long, current: Long, reclaimable: Long): Double {
    val used = (current - reclaimable).coerceAtLeast(0L)
    return ((max - used).toDouble() / max).coerceIn(0.0, 1.0)
  }

  private fun cgroupStatValue(file: File, key: String): Long =
    runCatching {
      file
        .useLines { lines ->
          lines.firstOrNull { it.startsWith("$key ") }?.substringAfter(' ')?.trim()?.toLongOrNull()
        }
        .orZero()
    }
      .getOrNull() ?: 0L

  private fun Long?.orZero(): Long = this ?: 0L

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

  private companion object {
    // cgroup v1's "no limit" sentinel (`PAGE_COUNTER_MAX * PAGE_SIZE`); at or above this is
    // unlimited.
    const val UNLIMITED_CGROUP_V1_LIMIT = 0x7FFFFFFFFFFFF000L
  }
}

/** A host-wide optimizer lease. Closing it releases both the catalog and lane locks. */
public fun interface OptimizerHostLease : AutoCloseable

/** Cross-process admission used by every preview replica sharing one coordination directory. */
public fun interface OptimizerHostCoordinator {
  public fun tryAcquire(system: String): OptimizerHostLease?

  public companion object {
    public val NONE: OptimizerHostCoordinator = OptimizerHostCoordinator { OptimizerHostLease {} }
  }
}

/**
 * Coordinates optimizer work across server replicas with advisory file locks: a per-system lock
 * stops two replicas warming the same generation, and a lane lock caps passes per physical host.
 * The kernel releases locks if a replica exits or is OOM-killed.
 */
public class FileOptimizerHostCoordinator(
  private val directory: File,
  private val lanes: Int,
) : OptimizerHostCoordinator, AutoCloseable {
  @Volatile private var leaderLock: HeldFileLock? = null

  init {
    Runtime.getRuntime().addShutdownHook(Thread({ close() }, "optimizer-host-lock-release"))
  }

  override fun tryAcquire(system: String): OptimizerHostLease? {
    if (!(directory.isDirectory || directory.mkdirs())) return null
    // One replica owns optimization for its lifetime so its in-memory cache view stays coherent;
    // the kernel hands leadership to a survivor if it dies.
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
