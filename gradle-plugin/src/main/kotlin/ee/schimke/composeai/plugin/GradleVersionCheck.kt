package ee.schimke.composeai.plugin

import org.gradle.util.GradleVersion

/**
 * Fail-fast guard so `compose-preview` produces a clear error on too-old Gradle instead of letting
 * the build limp on and surface an opaque `NoSuchMethodError` from a Gradle API we depend on.
 *
 * Floor is the lowest Gradle line our integration matrix routinely exercises. The `agp8-min`
 * fixture pins the bottom edge to Gradle 8.13 (Signal-Android-class consumer); Android consumers on
 * AGP 9.x are gated more strictly by AGP itself (AGP 9.0.x → 9.1.0+, AGP 9.1.x → 9.3.1+) and AGP
 * rejects too-old Gradle before our `apply()` runs anyway. The check still earns its keep on **CMP
 * Desktop** consumers (no AGP gate) and on AGP 8.x consumers below the 8.13 line.
 *
 * Pure on purpose so the unit test can drive every branch without spinning up a `Project`.
 */
internal object GradleVersionCheck {
  internal val MIN: GradleVersion = GradleVersion.version("8.13")

  /**
   * @return `null` when [current] satisfies the floor, otherwise a remediation message suitable for
   *   [org.gradle.api.GradleException].
   */
  internal fun problem(current: GradleVersion): String? {
    if (current.baseVersion >= MIN) return null
    return buildString {
      append("compose-preview requires Gradle ${MIN.version} or newer; this build is on ")
      append(current.version)
      append(". Upgrade via `./gradlew wrapper --gradle-version ${MIN.version}`.")
    }
  }
}
