package ee.schimke.composeai.plugin

import org.gradle.util.GradleVersion

/**
 * Fail-fast guard so `compose-preview` produces a clear error on too-old Gradle instead of letting
 * the build limp on and surface an opaque `NoSuchMethodError` from a Gradle API we depend on.
 *
 * Floor is the lowest Gradle line our integration matrix routinely exercises (Gradle 9.0.x). Most
 * Android consumers are gated more strictly by AGP's own minimum — AGP 9.0.x needs Gradle 9.1.0+,
 * AGP 9.1.x needs 9.3.1+ — so on Android builds AGP rejects older Gradle before our `apply()` runs
 * anyway. The check still earns its keep on **CMP Desktop** consumers (no AGP gate) and as
 * documentation any time something slips past AGP's check.
 *
 * Pure on purpose so the unit test can drive every branch without spinning up a `Project`.
 */
internal object GradleVersionCheck {
  internal val MIN: GradleVersion = GradleVersion.version("9.0.0")

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
