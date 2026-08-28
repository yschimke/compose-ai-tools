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
  /**
   * Longest the gate may withhold admission while **no** reading is over a stop threshold.
   *
   * The gap between a stop and its resume side is a dead band, and a host can sit in one
   * indefinitely: memory available 18% is neither `<= 0.15` (so nothing re-trips, and the reason
   * degrades to the bare "host recovering") nor `>= 0.25` (so [OptimizerPressureGate] never starts
   * counting [resumeQuietMillis]). Observed in production as a gate held for eight hours while the
   * host sat at 2% CPU, with theme optimization stalled at 0.35% of its entries.
   *
   * Hysteresis exists to delay resumption, not to prevent it, so cap the hold. The stop thresholds
   * are the real danger line; once the reading is back on their safe side, a bounded duty cycle —
   * admit, possibly trip again, wait again — is the correct failure mode for best-effort work,
   * where a permanent latch is not.
   */
  val maxRecoveryMillis: Long = 10 * 60_000L,
  /**
   * Longest a hold whose stop threshold keeps **re-tripping** may withhold admission before the
   * gate opens for [dutyCycleMillis]. `0` restores the old permanent latch.
   *
   * [maxRecoveryMillis] bounds the dead band; this bounds the other permanent latch, and it is the
   * one production actually sat in. preview.coo.ee runs 17 resident render daemons on an 8 GB box,
   * so `MemAvailable` there is a steady 14-15% — under the 15% stop side on every sample. Nothing
   * is recovering, so the dead-band cap never engages, and theme optimization simply never ran:
   * `/status.json` reported `paused · memory available 15%` with `wear-m3` warmed to 5 of its 170
   * entries across a 15-hour uptime.
   *
   * A steady-state reading is the host's baseline, not an emergency, and best-effort work that
   * never runs is indistinguishable from work that was never scheduled. So the same reasoning
   * [maxRecoveryMillis] records applies: bound the hold, admit a slice, let it trip again. What
   * stays permanent is a genuine emergency — see [dutyCycleFloorMemoryAvailableFraction].
   */
  val starvationCapMillis: Long = 30 * 60_000L,
  /**
   * How long the gate stays open once [starvationCapMillis] is exhausted. `0` disables the cap.
   *
   * This must be comfortably longer than one cold Android daemon warm (34-68s in production). The
   * old one-minute window was commonly spent entirely in that warm: the next per-batch gate check
   * saw the window closed and yielded with zero cache entries written. Five minutes matches the
   * optimizer's normal lane slice, so a concession can pay for the warm and still do useful work
   * without turning the pressure backstop into an unbounded admission.
   */
  val dutyCycleMillis: Long = 5 * 60_000L,
  /**
   * Memory headroom below which the duty cycle never opens, whatever the hold has cost.
   *
   * The stop side (15%) is "back off"; this is "the next allocation may be the one that OOM-kills
   * the replica". A host there keeps the permanent latch, because slow progress is not worth a
   * killed server.
   */
  val dutyCycleFloorMemoryAvailableFraction: Double = 0.05,
) {
  companion object {
    /**
     * Thresholds overridden by `composeai.serve.optimizer*` system properties.
     *
     * Deployments differ in what "constrained" means — a box whose steady state is 18% available
     * memory (17 resident render daemons will do that) has a resume threshold set *below* its own
     * baseline, so the gate is guaranteed to latch on the first transient dip. That is a property
     * of the host, not of the code, and it needs to be settable without a rebuild.
     */
    fun fromSystemProperties(): OptimizerPressureThresholds {
      val defaults = OptimizerPressureThresholds()
      return OptimizerPressureThresholds(
        stopLoadPerCpu = fraction("optimizerStopLoadPerCpu") ?: defaults.stopLoadPerCpu,
        resumeLoadPerCpu = fraction("optimizerResumeLoadPerCpu") ?: defaults.resumeLoadPerCpu,
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
data class OptimizerPressureSnapshot(
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
)

/**
 * Hysteretic host-resource gate for best-effort optimization.
 *
 * A high reading stops admission immediately. Resumption requires every reading that tripped the
 * hold to be back on its resume side for [OptimizerPressureThresholds.resumeQuietMillis], so a
 * render finishing does not instantly admit another cold daemon while the host is still recovering.
 *
 * Because the stop and resume sides differ, a reading can settle between them and satisfy neither.
 * [OptimizerPressureThresholds.maxRecoveryMillis] bounds how long that costs: hysteresis delays
 * resumption, and this is what stops it preventing resumption outright.
 *
 * A reading that stays on the *stop* side is the other way a hold becomes permanent, and it is the
 * one a busy host reaches by simply being busy. [OptimizerPressureThresholds.starvationCapMillis]
 * bounds that one the same way: after the cap the gate opens for a bounded window, then holds
 * again. Only a host under [OptimizerPressureThresholds.dutyCycleFloorMemoryAvailableFraction]
 * keeps the latch.
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

  /** Signals that tripped this hold; only these have to recover to clear it. Empty when open. */
  private var trippedBy = emptySet<PressureSignal>()

  /** When the current hold last saw every stop threshold clear — the [maxRecoveryMillis] anchor. */
  private var recoveringSince = Long.MIN_VALUE

  /** Whether the pressure logic itself wants to hold, before the starvation cap has its say. */
  private var held = false

  /**
   * When the next concession is measured from — moved to the end of each window the cap opens, and
   * to the moment a window is cut short, so the cap always means "at most this long without one".
   */
  private var capAnchor = Long.MIN_VALUE

  /** When the current uninterrupted hold began. Reporting only — `heldMillis` on `/status.json`. */
  private var holdStartedAt = Long.MIN_VALUE

  /** End of the window the starvation cap has opened, or [Long.MIN_VALUE] when not duty-cycling. */
  private var dutyCycleUntil = Long.MIN_VALUE

  private var dutyCycles = 0

  /** What was over a stop threshold on the previous sample — the [newlyTripped] baseline. */
  private var lastTripped = emptySet<PressureSignal>()

  fun snapshot(): OptimizerPressureSnapshot =
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
      // Only what actually stopped us has to come back: a hold taken for memory should not also
      // wait on a CPU reading that never crossed its own stop threshold.
      val safe = trippedBy.all { it.recovered(current, thresholds) }
      // What is over a stop threshold *right now*, and what was over one on the previous sample.
      // The difference is what has to cut a duty cycle short. Comparing against `trippedBy` would
      // not do: that set accumulates for the life of the hold, so a signal that tripped once, went
      // quiet, and spiked again while memory kept the hold alive would read as "already tripped"
      // and buy the rest of the window.
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
          // `held`, not the published `constrained`: a starvation duty cycle publishes an open gate
          // while the hold is still on, and reading that back would end the hold without the quiet
          // window it is owed.
          false
        } else {
          // Nothing is over a stop threshold any more, so the hold is now bounded either way:
          // it ends when the resume side is held for `resumeQuietMillis`, or when the dead band
          // between the two sides has withheld admission for `maxRecoveryMillis`.
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
        )
      cached
    }

  /**
   * The cached snapshot, with an elapsed duty-cycle window closed first.
   *
   * Both paths that reuse [cached] — inside the sample interval, and when sampling fails outright —
   * would otherwise publish an open gate for as long as they last. Inside the sample interval that
   * is at most one interval; when `/proc` stops being readable it is forever, and the 60-second
   * window becomes a permanent admission of optimizer work under pressure nobody can see any more.
   * The window is a *bounded* concession, so it expires on the clock rather than on the next
   * successful reading.
   */
  private fun cachedClosingExpiredDutyCycle(now: Long): OptimizerPressureSnapshot {
    if (!held || dutyCycleUntil == Long.MIN_VALUE || now < dutyCycleUntil) return cached
    closeWindow(at = dutyCycleUntil)
    // `heldMillis` is recomputed rather than carried over: the cached value was taken while the
    // window was open, and republishing it would report a gate that has been shut for hours as
    // having withheld admission for however long it had at the moment the sampler died.
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
   * Ends the open window, if any, and re-arms the cap from [at].
   *
   * A window cut short — by new pressure, or by memory dropping under the floor — must not leave
   * the next concession measured from the end it never reached: that would withhold admission for
   * up to `starvationCapMillis + dutyCycleMillis`, which is not what the cap says it does. So a
   * live window closes at `now`.
   *
   * A window that simply *elapsed* is the other case, and it anchors at its scheduled end even when
   * nothing observed the expiry until later: the concession was served in full, and a sampler that
   * went blind for the next nine seconds is not a reason to charge the host another cap for them.
   */
  private fun closeWindow(at: Long) {
    if (dutyCycleUntil == Long.MIN_VALUE) return
    dutyCycleUntil = Long.MIN_VALUE
    capAnchor = at
  }

  /**
   * Whether the starvation cap should let this held gate through right now.
   *
   * Opens a [OptimizerPressureThresholds.dutyCycleMillis] window once a hold has withheld admission
   * for [OptimizerPressureThresholds.starvationCapMillis], then re-arms: hold, admit a slice, hold
   * again. The window is closed early — and never opened — while memory sits under (or cannot be
   * read against) [OptimizerPressureThresholds.dutyCycleFloorMemoryAvailableFraction], so the one
   * case that can actually kill the server keeps the permanent latch; and closed early when a
   * signal that was not already holding crosses its stop threshold, so the concession never covers
   * pressure it did not answer for.
   *
   * The reason string keeps naming the reading that is holding, because it still is: a duty cycle
   * is progress *despite* the pressure, not an all-clear. `dutyCycles` on `/status.json` is what
   * says the host has been running on it.
   */
  private fun dutyCycling(
    sample: HostResourceSample,
    now: Long,
    newlyTripped: Set<PressureSignal>,
  ): Boolean {
    // A zero-length window admits nothing, so it is not a duty cycle — counting one would leave
    // `/status.json` reporting concessions the gate never made. Both knobs disable the cap.
    if (thresholds.starvationCapMillis <= 0L || thresholds.dutyCycleMillis <= 0L) return false
    // `dutyCycleUntil` means "a window is live", and it has to be retired the moment one elapses:
    // the two early closes below anchor at `now` because they are cutting a live window short, and
    // a stale marker makes them do that to a window that already ran its course — re-anchoring a
    // cap that was correctly anchored at the window's end when it opened.
    if (dutyCycleUntil != Long.MIN_VALUE && now >= dutyCycleUntil) closeWindow(at = dutyCycleUntil)
    // An unknown memory reading is not a safe one. `LinuxHostResourceSampler` returns a partial
    // sample when `/proc/meminfo` is unreadable but load and CPU are not, and a load hold would
    // then earn a concession with the OOM floor unverified. A host that never reports memory keeps
    // the permanent latch, which is the conservative half of that trade.
    val headroom = sample.memoryAvailableFraction
    if (headroom == null || headroom < thresholds.dutyCycleFloorMemoryAvailableFraction) {
      closeWindow(at = now)
      return false
    }
    // "A high reading stops admission immediately" is the gate's contract, and an open window must
    // not buy a *different* signal up to `dutyCycleMillis` of grace. The signal that earned the
    // concession may stay over its threshold — that is the whole point — but a new one closes it.
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
   * Why a hold with no reading over a stop threshold is still held.
   *
   * The bare "host recovering" this replaces was actively misleading during the eight-hour stall
   * [OptimizerPressureThresholds.maxRecoveryMillis] documents: it reads as "nearly there" whether
   * the gate is counting down its quiet window or parked in a dead band it cannot leave. Naming the
   * signal and the bar it has to clear makes the two distinguishable from `/status.json` alone.
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
 * Reads Linux load, CPU time and available memory through the proc filesystem — and, when this
 * process is inside a memory-limited cgroup, through that cgroup as well.
 *
 * **Why the cgroup half exists.** `/proc/meminfo` inside a container reports the HOST's memory, not
 * the container's limit. The deployed profiles cap preview at 3 GiB (`deploy/vps`) and 6 GiB
 * (`deploy/oracle`) on hosts with far more than that, so a replica sitting a hair under its own OOM
 * limit still saw plenty of "available" memory host-wide — and the admission gate kept letting
 * optimizer work in at precisely the moment it needed to stop. The reported fraction is the SMALLER
 * of the two headrooms, so whichever ceiling is nearer is the one that governs.
 */
class LinuxHostResourceSampler(
  private val procRoot: File = File("/proc"),
  private val cgroupRoot: File = File("/sys/fs/cgroup"),
) {
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
    // Whichever ceiling is nearer governs. An unlimited or unreadable cgroup contributes nothing,
    // which is what keeps a bare-metal host reading exactly as it did before.
    val constrained = listOfNotNull(memory, cgroupMemoryAvailableFraction()).minOrNull()
    if (load == null && cpu == null && constrained == null) return null
    return HostResourceSample(load, cpu, constrained)
  }

  /**
   * The fraction of this process's cgroup memory allowance still available, or null when there is
   * no limit to speak of.
   *
   * Both cgroup generations are read because the deployment targets do not agree: v2 exposes
   * `memory.max`/`memory.current` at the root, v1 the `memory/memory.limit_in_bytes` pair. A v1
   * limit is reported as a huge sentinel rather than a word, so anything at or above the host's own
   * `MemTotal` is treated as "no limit" rather than compared against.
   *
   * `inactive_file` is subtracted from usage because it is page cache the kernel reclaims under
   * pressure rather than memory this process cannot give back. Counting it would make a container
   * that has merely read a lot of files look permanently full, and the gate would then refuse
   * optimizer work forever — the opposite failure, and a quieter one.
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
    // cgroup v1 writes `PAGE_COUNTER_MAX * PAGE_SIZE` when there is no limit; on 64-bit that is a
    // number in the exabytes. Anything at or above this is "unlimited", not a ceiling to measure.
    const val UNLIMITED_CGROUP_V1_LIMIT = 0x7FFFFFFFFFFFF000L
  }
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
