package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.Test
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
}
