package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Shared Gradle [BuildService] bounding how many `SimulatorManagedDevice` test tasks can execute
 * simulator JVMs concurrently across a multi-module build.
 *
 * Each simulator process boots Robolectric + Android framework classes, so running unconstrained
 * across every module in parallel can exhaust host RAM/CPU. Tune with
 * `-PcomposePreview.simulatorMaxParallel=<N>` (default: half of host CPUs, clamped to `1..4`).
 */
abstract class SimulatorConcurrencyService : BuildService<BuildServiceParameters.None> {

  internal companion object {
    const val SERVICE_NAME: String = "composePreviewSimulatorConcurrency"
    const val MAX_PARALLEL_PROPERTY: String = "composePreview.simulatorMaxParallel"

    internal fun defaultMaxParallel(availableProcessors: Int): Int =
      (availableProcessors / 2).coerceIn(1, 4)

    internal fun resolveMaxParallel(
      propertyValue: String?,
      availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    ): Int {
      val parsed = propertyValue?.trim()?.toIntOrNull()
      return parsed?.coerceAtLeast(1) ?: defaultMaxParallel(availableProcessors)
    }

    /**
     * Registers (or reuses) the build-scoped [SimulatorConcurrencyService] with `maxParallelUsages`
     * set from `-PcomposePreview.simulatorMaxParallel`.
     */
    internal fun registerIfAbsent(project: Project): Provider<SimulatorConcurrencyService> {
      val configured = project.providers.gradleProperty(MAX_PARALLEL_PROPERTY).orNull
      val maxParallel = resolveMaxParallel(configured)
      return project.gradle.sharedServices.registerIfAbsent(
        SERVICE_NAME,
        SimulatorConcurrencyService::class.java,
      ) {
        maxParallelUsages.set(maxParallel)
      }
    }

    /**
     * True when [taskName] is an AGP managed-device test task for [deviceName], i.e.
     * `<deviceName><Variant>AndroidTest` (for example `simulatorDebugAndroidTest`).
     */
    internal fun isManagedDeviceTestTaskName(taskName: String, deviceName: String): Boolean {
      if (deviceName.isEmpty()) return false
      val suffix = "AndroidTest"
      if (taskName.length <= deviceName.length + suffix.length) return false
      if (!taskName.startsWith(deviceName) || !taskName.endsWith(suffix)) return false
      return taskName[deviceName.length].isUpperCase()
    }
  }
}
