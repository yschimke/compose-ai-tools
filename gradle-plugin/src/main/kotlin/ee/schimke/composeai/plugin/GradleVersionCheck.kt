package ee.schimke.composeai.plugin

import org.gradle.util.GradleVersion

/**
 * Fail-fast guard so `compose-preview` produces a clear error on too-old Gradle instead of letting
 * the build limp on and surface an opaque `NoSuchMethodError` from a Gradle API we depend on.
 *
 * Floor matches `CompatRules.GRADLE_MIN` — the binding constraint is AGP's own minimum (9.1.x →
 * Gradle 9.3.1), so on Android builds AGP rejects older Gradle before our `apply()` runs anyway.
 * The check still earns its keep on **CMP Desktop** consumers (no AGP gate) and as documentation
 * any time something slips past AGP's check.
 *
 * Pure on purpose so the unit test can drive every branch without spinning up a `Project`.
 */
internal object GradleVersionCheck {
  internal val MIN: GradleVersion = GradleVersion.version("9.3.1")

  /**
   * @return `null` when [current] satisfies the floor, otherwise a remediation message suitable
   *   for [org.gradle.api.GradleException].
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
