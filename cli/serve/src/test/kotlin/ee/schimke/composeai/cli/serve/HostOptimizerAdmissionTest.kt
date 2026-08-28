package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostOptimizerAdmissionTest {
  @Test
  fun `file coordinator caps the whole host and excludes duplicate systems`() {
    val directory = Files.createTempDirectory("optimizer-host-locks").toFile()
    try {
      val firstReplica = FileOptimizerHostCoordinator(directory, lanes = 2)
      val secondReplica = FileOptimizerHostCoordinator(directory, lanes = 2)
      val first = assertNotNull(firstReplica.tryAcquire("catalog-a"))
      assertNull(
        secondReplica.tryAcquire("catalog-a"),
        "the same catalog must not warm in two replicas",
      )
      assertNull(
        secondReplica.tryAcquire("catalog-b"),
        "only the elected replica may warm, so its cache index stays coherent",
      )
      val second = assertNotNull(firstReplica.tryAcquire("catalog-b"))
      assertNull(firstReplica.tryAcquire("catalog-c"), "the two lanes belong to the whole host")

      first.close()
      assertNotNull(firstReplica.tryAcquire("catalog-c")).close()
      second.close()
      firstReplica.close()
      assertNotNull(secondReplica.tryAcquire("catalog-a"), "leadership fails over on exit").close()
      secondReplica.close()
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun `pressure stops immediately and resumes only after a quiet recovery window`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.2, cpuUtilization = 0.2, memoryAvailableFraction = 0.8)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(resumeQuietMillis = 1_000, sampleIntervalMillis = 0),
        clock = { now },
      )
    assertFalse(gate.snapshot().constrained)

    sample = sample.copy(cpuUtilization = 0.9)
    assertTrue(gate.snapshot().constrained)
    assertTrue(gate.snapshot().reason.orEmpty().contains("CPU"))

    now = 100
    sample = sample.copy(cpuUtilization = 0.2)
    assertTrue(gate.snapshot().constrained, "one safe sample must not flap the optimizer back on")
    assertTrue(gate.snapshot().reason.orEmpty().contains("recovering"))
    now = 1_099
    assertTrue(gate.snapshot().constrained)
    now = 1_100
    assertFalse(gate.snapshot().constrained)
  }

  @Test
  fun `a reading parked in the dead band cannot hold the gate forever`() {
    // The production stall: memory settles at 18% available, which is neither at-or-below the 15%
    // stop side (so nothing re-trips) nor at-or-above the 25% resume side (so the quiet window
    // never starts). Before maxRecoveryMillis this held admission for eight hours.
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.02, cpuUtilization = 0.02, memoryAvailableFraction = 0.8)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            resumeQuietMillis = 1_000,
            sampleIntervalMillis = 0,
            maxRecoveryMillis = 10_000,
          ),
        clock = { now },
      )
    assertFalse(gate.snapshot().constrained)

    sample = sample.copy(memoryAvailableFraction = 0.10)
    assertTrue(gate.snapshot().constrained)

    sample = sample.copy(memoryAvailableFraction = 0.18)
    now = 1_000
    assertTrue(gate.snapshot().constrained, "the dead band must still hold the gate at first")
    val reason = gate.snapshot().reason.orEmpty()
    assertTrue(reason.contains("18%"), "reason should name the reading: $reason")
    assertTrue(reason.contains("25%"), "reason should name the bar it must clear: $reason")

    now = 10_999
    assertTrue(gate.snapshot().constrained)
    now = 11_000
    assertFalse(gate.snapshot().constrained, "the hold must expire once no stop threshold is met")
  }

  @Test
  fun `a dead band hold does not expire while a stop threshold is still met`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.02, cpuUtilization = 0.02, memoryAvailableFraction = 0.10)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            resumeQuietMillis = 1_000,
            sampleIntervalMillis = 0,
            maxRecoveryMillis = 10_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)

    // Still under the stop threshold well past maxRecoveryMillis: the cap must not admit into a
    // host that is genuinely out of memory.
    now = 60_000
    assertTrue(gate.snapshot().constrained)
    assertTrue(gate.snapshot().reason.orEmpty().contains("memory available 10%"))

    sample = sample.copy(memoryAvailableFraction = 0.30)
    now = 60_001
    assertTrue(gate.snapshot().constrained, "recovery is still gated on the quiet window")
    now = 61_001
    assertFalse(gate.snapshot().constrained)
  }

  @Test
  fun `a hold whose stop threshold keeps re-tripping duty-cycles instead of latching forever`() {
    var now = 0L
    // The preview.coo.ee steady state: an idle box whose 17 resident daemons keep MemAvailable a
    // hair under the stop side, so every sample re-trips and nothing ever "recovers".
    val sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.03, memoryAvailableFraction = 0.14)
    val thresholds =
      OptimizerPressureThresholds(
        sampleIntervalMillis = 0,
        starvationCapMillis = 10_000,
        dutyCycleMillis = 1_000,
      )
    val gate = OptimizerPressureGate(sample = { sample }, thresholds = thresholds, clock = { now })
    assertTrue(gate.snapshot().constrained)

    now = 9_999
    assertTrue(gate.snapshot().constrained, "the cap must not open early")
    assertEquals(9_999, gate.snapshot().heldMillis)

    now = 10_000
    val open = gate.snapshot()
    assertFalse(open.constrained, "a bounded duty cycle beats never optimizing at all")
    assertEquals(1, open.dutyCycles)
    assertEquals(11_000, open.dutyCycleUntilEpochMillis)
    assertTrue(
      open.reason.orEmpty().contains("memory available 14%"),
      "the reading is still what is holding, so keep naming it: $open",
    )

    now = 11_000
    assertTrue(gate.snapshot().constrained, "the window is bounded too")
    now = 20_999
    assertTrue(gate.snapshot().constrained)
    now = 21_000
    assertFalse(gate.snapshot().constrained, "the cap re-arms from the end of the window")
    assertEquals(2, gate.snapshot().dutyCycles)
  }

  @Test
  fun `the default duty cycle outlives a cold daemon warm`() {
    var now = 0L
    val gate =
      OptimizerPressureGate(
        sample = {
          HostResourceSample(
            loadPerCpu = 0.17,
            cpuUtilization = 0.03,
            memoryAvailableFraction = 0.14,
          )
        },
        thresholds = OptimizerPressureThresholds(sampleIntervalMillis = 0),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)

    now = 30 * 60_000L
    assertFalse(gate.snapshot().constrained, "the starvation cap opens a bounded work window")

    // The deployed Android lanes take up to about 68 seconds merely to warm. The old 60-second
    // default had already closed here, so a pass reached its first render gate with no work done.
    now += 70_000L
    val afterWarm = gate.snapshot()
    assertFalse(afterWarm.constrained, "the concession must leave time to render after warming")
    assertNotNull(afterWarm.dutyCycleUntilEpochMillis)

    now = 35 * 60_000L
    assertTrue(gate.snapshot().constrained, "the longer concession remains bounded")
  }

  @Test
  fun `an expired duty cycle closes even when sampling has stopped working`() {
    var now = 0L
    var readable = true
    val gate =
      OptimizerPressureGate(
        sample = {
          if (readable) {
            HostResourceSample(
              loadPerCpu = 0.17,
              cpuUtilization = 0.03,
              memoryAvailableFraction = 0.14,
            )
          } else {
            null
          }
        },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)

    now = 10_000
    assertFalse(gate.snapshot().constrained)

    // `/proc` stops answering mid-window. The cached snapshot says "open"; without expiring it on
    // the clock the concession becomes permanent, under pressure nothing can observe any more.
    readable = false
    now = 10_500
    assertFalse(gate.snapshot().constrained, "the window itself is still running")
    now = 11_000
    val closed = gate.snapshot()
    assertTrue(closed.constrained, "an elapsed window must close without a fresh reading")
    assertNull(closed.dutyCycleUntilEpochMillis)
    now = 60_000
    assertTrue(gate.snapshot().constrained)
  }

  @Test
  fun `the duty cycle never opens on a host that is genuinely out of memory`() {
    var now = 0L
    val sample =
      HostResourceSample(loadPerCpu = 0.1, cpuUtilization = 0.1, memoryAvailableFraction = 0.02)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 100_000
    val held = gate.snapshot()
    assertTrue(held.constrained, "below the floor the latch is the correct failure mode")
    assertEquals(0, held.dutyCycles)
  }

  @Test
  fun `a newly tripped signal closes an open duty cycle`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.03, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10_000
    assertFalse(gate.snapshot().constrained)

    // The memory reading that earned the concession may stay over its threshold — that is the
    // point — but CPU crossing its own stop side is new pressure the window never answered for,
    // and "a high reading stops admission immediately" outranks it.
    sample = sample.copy(cpuUtilization = 0.95)
    now = 10_500
    val stopped = gate.snapshot()
    assertTrue(stopped.constrained, "a new signal must cut the window short")
    assertNull(stopped.dutyCycleUntilEpochMillis)
    assertTrue(stopped.reason.orEmpty().contains("CPU 95%"), "and name itself: $stopped")
  }

  @Test
  fun `a signal that tripped earlier in the hold still closes a later window`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.95, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    // CPU trips at the start of the hold and then goes quiet; memory keeps the hold alive, so
    // `trippedBy` still remembers CPU for the rest of it. A second CPU spike is new pressure all
    // the same, and must not be filtered out as "already tripped".
    assertTrue(gate.snapshot().constrained)
    now = 1_000
    sample = sample.copy(cpuUtilization = 0.03)
    assertTrue(gate.snapshot().constrained)

    now = 11_000
    assertFalse(gate.snapshot().constrained, "the cap opens a window")
    now = 11_500
    sample = sample.copy(cpuUtilization = 0.95)
    val stopped = gate.snapshot()
    assertTrue(stopped.constrained, "the CPU spike is new pressure, whenever it last happened")
    assertNull(stopped.dutyCycleUntilEpochMillis)
  }

  @Test
  fun `pressure after a window has elapsed does not re-arm the cap again`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.03, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10_000
    assertFalse(gate.snapshot().constrained, "window open, scheduled to end at 11s")
    now = 11_000
    assertTrue(gate.snapshot().constrained, "and elapsed, having been served in full")

    // A CPU trip four seconds later is cutting nothing short — the window is long over — so it must
    // not push the next concession out from 21s to 25s.
    now = 15_000
    sample = sample.copy(cpuUtilization = 0.95)
    assertTrue(gate.snapshot().constrained)
    sample = sample.copy(cpuUtilization = 0.03)

    now = 20_999
    assertTrue(gate.snapshot().constrained)
    now = 21_000
    assertFalse(gate.snapshot().constrained, "still due 10s after the window that was served")
  }

  @Test
  fun `an expiry noticed late still re-arms from the scheduled window end`() {
    var now = 0L
    var readable = true
    val gate =
      OptimizerPressureGate(
        sample = {
          if (readable) {
            HostResourceSample(
              loadPerCpu = 0.17,
              cpuUtilization = 0.03,
              memoryAvailableFraction = 0.14,
            )
          } else {
            null
          }
        },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10_000
    assertFalse(gate.snapshot().constrained, "the window opens, scheduled to end at 11s")

    // The sampler goes blind across the expiry and stays blind until 20s. The concession was
    // served in full, so the next one is due 10s after the scheduled end — charging a fresh cap
    // from the moment someone happened to look would make blindness cost the host admission.
    readable = false
    now = 20_000
    assertTrue(gate.snapshot().constrained)
    readable = true
    now = 20_999
    assertTrue(gate.snapshot().constrained)
    now = 21_000
    assertFalse(gate.snapshot().constrained, "due at 21s, not 30s")
  }

  @Test
  fun `a window cut short re-arms the cap from when it actually closed`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.03, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10_000
    assertFalse(gate.snapshot().constrained)

    // Cut short 100ms in. The next concession is owed 10s from *here*, not 10s from the end the
    // window never reached — otherwise the cap silently becomes cap + window.
    now = 10_100
    sample = sample.copy(cpuUtilization = 0.95)
    assertTrue(gate.snapshot().constrained)
    sample = sample.copy(cpuUtilization = 0.03)

    now = 20_099
    assertTrue(gate.snapshot().constrained)
    now = 20_100
    assertFalse(gate.snapshot().constrained, "the cap runs from the early close")
  }

  @Test
  fun `a hold closed while sampling is broken keeps reporting how long it has held`() {
    var now = 0L
    var readable = true
    val gate =
      OptimizerPressureGate(
        sample = {
          if (readable) {
            HostResourceSample(
              loadPerCpu = 0.17,
              cpuUtilization = 0.03,
              memoryAvailableFraction = 0.14,
            )
          } else {
            null
          }
        },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    assertEquals(0, gate.snapshot().heldMillis)
    now = 10_000
    assertFalse(gate.snapshot().constrained)
    // The hold is still on during the window, and it started at t=0 — the cap bookkeeping moving
    // its own anchor to the window end must not make the reported duration collapse to zero.
    assertEquals(10_000, gate.snapshot().heldMillis)

    readable = false
    now = 3_600_000
    val stale = gate.snapshot()
    assertTrue(stale.constrained)
    assertEquals(3_600_000, stale.heldMillis, "an hour held must not report as zero: $stale")
  }

  @Test
  fun `a zero-length window is not counted as a duty cycle`() {
    var now = 0L
    val sample =
      HostResourceSample(loadPerCpu = 0.17, cpuUtilization = 0.03, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 0,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10_000
    assertTrue(gate.snapshot().constrained, "a window that admits nothing is not a concession")
    now = 100_000
    val held = gate.snapshot()
    assertTrue(held.constrained)
    assertEquals(0, held.dutyCycles, "and must not be reported as one: $held")
  }

  @Test
  fun `the duty cycle stays shut while memory cannot be read at all`() {
    var now = 0L
    // `/proc/meminfo` unreadable while load and CPU are fine: the sampler reports a partial sample
    // rather than none, and an unverified OOM floor must not buy a concession.
    val sample =
      HostResourceSample(loadPerCpu = 0.90, cpuUtilization = 0.10, memoryAvailableFraction = null)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 100_000
    val held = gate.snapshot()
    assertTrue(held.constrained, "an unknown memory reading is not a safe one")
    assertEquals(0, held.dutyCycles)
  }

  @Test
  fun `a zero starvation cap keeps the hold permanent`() {
    var now = 0L
    val sample =
      HostResourceSample(loadPerCpu = 0.1, cpuUtilization = 0.1, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds = OptimizerPressureThresholds(sampleIntervalMillis = 0, starvationCapMillis = 0),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)
    now = 10 * 60_000
    assertTrue(gate.snapshot().constrained)
    assertEquals(0, gate.snapshot().dutyCycles)
  }

  @Test
  fun `recovery clears the starvation clock`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.1, cpuUtilization = 0.1, memoryAvailableFraction = 0.14)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(
            resumeQuietMillis = 0,
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
          ),
        clock = { now },
      )
    assertTrue(gate.snapshot().constrained)

    now = 5_000
    sample = sample.copy(memoryAvailableFraction = 0.80)
    val recovered = gate.snapshot()
    assertFalse(recovered.constrained)
    assertNull(recovered.heldMillis)

    // A new hold starts its own cap rather than inheriting the previous one's 5s of credit.
    now = 6_000
    sample = sample.copy(memoryAvailableFraction = 0.14)
    assertTrue(gate.snapshot().constrained)
    now = 15_999
    assertTrue(gate.snapshot().constrained)
    now = 16_000
    assertFalse(gate.snapshot().constrained)
  }

  @Test
  fun `only the signal that tripped the hold has to recover`() {
    var now = 0L
    var sample =
      HostResourceSample(loadPerCpu = 0.2, cpuUtilization = 0.2, memoryAvailableFraction = 0.8)
    val gate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds =
          OptimizerPressureThresholds(resumeQuietMillis = 1_000, sampleIntervalMillis = 0),
        clock = { now },
      )
    assertFalse(gate.snapshot().constrained)

    sample = sample.copy(memoryAvailableFraction = 0.10)
    assertTrue(gate.snapshot().constrained)

    // Memory is back above its resume side. CPU sits between its resume (0.70) and stop (0.85)
    // thresholds — uncomfortable, but it never stopped anything, so it must not block resumption.
    sample = sample.copy(memoryAvailableFraction = 0.40, cpuUtilization = 0.75)
    now = 1
    assertTrue(gate.snapshot().constrained, "one safe sample must not flap the optimizer back on")
    now = 1_001
    assertFalse(gate.snapshot().constrained)
  }

  /**
   * Load average per CPU is a queue depth, not a percentage.
   *
   * Both load thresholds were parsed as fractions and silently discarded every value over 1.0 —
   * which is every value that matters on a host rendering flat out. Measured on preview.coo.ee
   * while the optimizer worked: 1.03, 1.12 and 1.74 per CPU. The stop side therefore could not be
   * lifted off its 0.85 default, so the gate tripped on the optimizer's own load and the operator's
   * override vanished without a word.
   */
  @Test
  fun `a load threshold above one is a setting, not a typo`() {
    val stop = "composeai.serve.optimizerStopLoadPerCpu"
    val resume = "composeai.serve.optimizerResumeLoadPerCpu"
    val previousStop = System.getProperty(stop)
    val previousResume = System.getProperty(resume)
    try {
      // The case the box actually needs: hold only once load passes two runnable tasks per core,
      // and resume at one and a half. Neither is expressible as a fraction.
      System.setProperty(stop, "2.5")
      System.setProperty(resume, "1.5")
      val tuned = OptimizerPressureThresholds.fromSystemProperties()
      assertEquals(2.5, tuned.stopLoadPerCpu)
      assertEquals(1.5, tuned.resumeLoadPerCpu)

      // Still bounded — a typo is still a typo, just not at a ceiling every busy box exceeds.
      System.setProperty(stop, "1000")
      assertEquals(
        OptimizerPressureThresholds().stopLoadPerCpu,
        OptimizerPressureThresholds.fromSystemProperties().stopLoadPerCpu,
      )

      // Negative load is meaningless, so it falls back too.
      System.setProperty(stop, "-1")
      assertEquals(
        OptimizerPressureThresholds().stopLoadPerCpu,
        OptimizerPressureThresholds.fromSystemProperties().stopLoadPerCpu,
      )
    } finally {
      if (previousStop == null) System.clearProperty(stop)
      else System.setProperty(stop, previousStop)
      if (previousResume == null) System.clearProperty(resume)
      else System.setProperty(resume, previousResume)
    }
  }

  /**
   * The limbs that ARE fractions must stay fractions — the fix above must not widen CPU or memory,
   * where a value over 1.0 really is a percentage someone forgot to divide.
   */
  @Test
  fun `a cpu or memory threshold above one is still refused`() {
    val cpu = "composeai.serve.optimizerStopCpuUtilization"
    val previous = System.getProperty(cpu)
    try {
      System.setProperty(cpu, "96")
      assertEquals(
        OptimizerPressureThresholds().stopCpuUtilization,
        OptimizerPressureThresholds.fromSystemProperties().stopCpuUtilization,
        "96 means 96%, and obeying it as a ratio would disable the CPU limb entirely",
      )
      System.setProperty(cpu, "0.96")
      assertEquals(0.96, OptimizerPressureThresholds.fromSystemProperties().stopCpuUtilization)
    } finally {
      if (previous == null) System.clearProperty(cpu) else System.setProperty(cpu, previous)
    }
  }

  @Test
  fun `thresholds fall back to defaults when system properties are absent or unparseable`() {
    val key = "composeai.serve.optimizerStopMemoryAvailableFraction"
    val quiet = "composeai.serve.optimizerResumeQuietMillis"
    val previous = System.getProperty(key)
    val previousQuiet = System.getProperty(quiet)
    try {
      System.clearProperty(key)
      System.clearProperty(quiet)
      assertEquals(
        OptimizerPressureThresholds(),
        OptimizerPressureThresholds.fromSystemProperties(),
      )

      System.setProperty(key, "0.35")
      System.setProperty(quiet, "5000")
      val tuned = OptimizerPressureThresholds.fromSystemProperties()
      assertEquals(0.35, tuned.stopMemoryAvailableFraction)
      assertEquals(5_000L, tuned.resumeQuietMillis)

      // A ratio outside 0..1 and a negative duration are typos, not instructions.
      System.setProperty(key, "35")
      System.setProperty(quiet, "-1")
      val rejected = OptimizerPressureThresholds.fromSystemProperties()
      assertEquals(
        OptimizerPressureThresholds().stopMemoryAvailableFraction,
        rejected.stopMemoryAvailableFraction,
      )
      assertEquals(OptimizerPressureThresholds().resumeQuietMillis, rejected.resumeQuietMillis)
    } finally {
      if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
      if (previousQuiet == null) System.clearProperty(quiet)
      else System.setProperty(quiet, previousQuiet)
    }
  }

  @Test
  fun `load and memory independently constrain optimization`() {
    var sample =
      HostResourceSample(loadPerCpu = 0.9, cpuUtilization = 0.1, memoryAvailableFraction = 0.8)
    val loadGate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds = OptimizerPressureThresholds(sampleIntervalMillis = 0),
      )
    assertTrue(loadGate.snapshot().constrained)
    assertTrue(loadGate.snapshot().reason.orEmpty().contains("load"))

    sample =
      HostResourceSample(loadPerCpu = 0.1, cpuUtilization = 0.1, memoryAvailableFraction = 0.1)
    val memoryGate =
      OptimizerPressureGate(
        sample = { sample },
        thresholds = OptimizerPressureThresholds(sampleIntervalMillis = 0),
      )
    assertTrue(memoryGate.snapshot().constrained)
    assertTrue(memoryGate.snapshot().reason.orEmpty().contains("memory"))
  }

  @Test
  fun `background work reports automatic pressure as a pause`() {
    val gate =
      OptimizerPressureGate(
        sample = {
          HostResourceSample(
            loadPerCpu = 1.2,
            cpuUtilization = 0.95,
            memoryAvailableFraction = 0.05,
          )
        },
        thresholds = OptimizerPressureThresholds(sampleIntervalMillis = 0),
      )
    val work = ServeBackgroundWork(pressureGate = gate)

    assertNull(work.withOptimizerSlot("catalog", waitMillis = 0) { true })
    val snapshot = work.optimizerAdmissionSnapshot()
    assertTrue(snapshot.paused)
    assertTrue(snapshot.pressure?.constrained == true)
    assertTrue(snapshot.pauseReason.orEmpty().contains("load"))
  }

  @Test
  fun `background work admits a slice once the starvation cap opens the gate`() {
    var now = 0L
    val gate =
      OptimizerPressureGate(
        sample = {
          HostResourceSample(
            loadPerCpu = 0.17,
            cpuUtilization = 0.03,
            memoryAvailableFraction = 0.14,
          )
        },
        thresholds =
          OptimizerPressureThresholds(
            sampleIntervalMillis = 0,
            starvationCapMillis = 10_000,
            dutyCycleMillis = 1_000,
          ),
        clock = { now },
      )
    val work = ServeBackgroundWork(clock = { now }, pressureGate = gate)

    assertNull(work.withOptimizerSlot("catalog", waitMillis = 0) { true })
    assertTrue(work.optimizersPaused())

    now = 10_000
    assertEquals(true, work.withOptimizerSlot("catalog", waitMillis = 0) { true })
    val snapshot = work.optimizerAdmissionSnapshot()
    assertFalse(snapshot.paused, "the duty cycle has to reach the admission path, not just /status")
    assertEquals(1, snapshot.pressure?.dutyCycles)
  }

  /**
   * `/proc/meminfo` inside a container reports the HOST's memory. The deployed profiles cap preview
   * at 3 GiB and 6 GiB on far larger hosts, so a replica already near its own OOM limit read as
   * having plenty of headroom and the gate kept admitting optimizer work.
   */
  @Test
  fun `memory headroom is the smaller of the host and the cgroup`() {
    val root = Files.createTempDirectory("host-sampler").toFile()
    val proc = File(root, "proc").apply { mkdirs() }
    // 32 GiB host, 24 GiB of it available: 75%, comfortably unconstrained on its own.
    File(proc, "meminfo").writeText("MemTotal:       33554432 kB\nMemAvailable:   25165824 kB\n")

    val hostOnly = LinuxHostResourceSampler(proc, File(root, "absent")).sample()
    assertEquals(0.75, assertNotNull(hostOnly?.memoryAvailableFraction), 0.001)

    // Same host, but this process is in a 3 GiB cgroup with 2.9 GiB charged to it.
    val cgroupV2 = File(root, "cgroup2").apply { mkdirs() }
    File(cgroupV2, "memory.max").writeText("${3L * 1024 * 1024 * 1024}\n")
    File(cgroupV2, "memory.current").writeText("${(2.9 * 1024 * 1024 * 1024).toLong()}\n")

    val constrained = LinuxHostResourceSampler(proc, cgroupV2).sample()
    assertTrue(
      assertNotNull(constrained?.memoryAvailableFraction) < 0.05,
      "the container's own ceiling has to win over the host's spare memory",
    )
  }

  @Test
  fun `page cache charged to the cgroup is not counted as used`() {
    val root = Files.createTempDirectory("host-sampler-cache").toFile()
    val proc = File(root, "proc").apply { mkdirs() }
    File(proc, "meminfo").writeText("MemTotal:       33554432 kB\nMemAvailable:   25165824 kB\n")
    val cgroup = File(root, "cgroup2").apply { mkdirs() }
    val limit = 4L * 1024 * 1024 * 1024
    File(cgroup, "memory.max").writeText("$limit\n")
    File(cgroup, "memory.current").writeText("${limit / 2}\n")
    // Half of what is charged is reclaimable page cache, so the real headroom is 75%, not 50%.
    File(cgroup, "memory.stat").writeText("anon 1\ninactive_file ${limit / 4}\nslab 2\n")

    val sample = LinuxHostResourceSampler(proc, cgroup).sample()

    assertEquals(0.75, assertNotNull(sample?.memoryAvailableFraction), 0.001)
  }

  @Test
  fun `an unlimited cgroup leaves the host reading untouched`() {
    val root = Files.createTempDirectory("host-sampler-unlimited").toFile()
    val proc = File(root, "proc").apply { mkdirs() }
    File(proc, "meminfo").writeText("MemTotal:       33554432 kB\nMemAvailable:   25165824 kB\n")

    // cgroup v2 spells it as a word...
    val v2 = File(root, "cgroup2").apply { mkdirs() }
    File(v2, "memory.max").writeText("max\n")
    File(v2, "memory.current").writeText("1024\n")
    assertEquals(
      0.75,
      assertNotNull(LinuxHostResourceSampler(proc, v2).sample()?.memoryAvailableFraction),
      0.001,
    )

    // ...cgroup v1 as a sentinel near Long.MAX_VALUE, which must not be read as a real ceiling.
    val v1 = File(root, "cgroup1").apply { mkdirs() }
    File(v1, "memory").apply {
      mkdirs()
      File(this, "memory.limit_in_bytes").writeText("9223372036854771712\n")
      File(this, "memory.usage_in_bytes").writeText("1024\n")
    }
    assertEquals(
      0.75,
      assertNotNull(LinuxHostResourceSampler(proc, v1).sample()?.memoryAvailableFraction),
      0.001,
    )
  }

  @Test
  fun `a cgroup v1 limit is honoured`() {
    val root = Files.createTempDirectory("host-sampler-v1").toFile()
    val proc = File(root, "proc").apply { mkdirs() }
    File(proc, "meminfo").writeText("MemTotal:       33554432 kB\nMemAvailable:   25165824 kB\n")
    val cgroup = File(root, "cgroup1").apply { mkdirs() }
    File(cgroup, "memory").apply {
      mkdirs()
      val limit = 6L * 1024 * 1024 * 1024
      File(this, "memory.limit_in_bytes").writeText("$limit\n")
      File(this, "memory.usage_in_bytes").writeText("${limit - limit / 10}\n")
      File(this, "memory.stat").writeText("total_inactive_file 0\n")
    }

    val sample = LinuxHostResourceSampler(proc, cgroup).sample()

    assertEquals(0.1, assertNotNull(sample?.memoryAvailableFraction), 0.001)
  }
}
