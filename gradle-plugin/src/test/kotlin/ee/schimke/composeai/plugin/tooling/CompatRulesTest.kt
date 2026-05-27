package ee.schimke.composeai.plugin.tooling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule gets two test cases — one that triggers it, one that doesn't. Rules are pure so no
 * mocking or Project fixture required; we hand-roll the dependency maps.
 */
class CompatRulesTest {

  @Test
  fun `ui-test-manifest missing fires error`() {
    val findings = CompatRules.evaluate(mainWithBom(), testWithoutUiTestManifest())
    val f = findings.single { it.id == "ui-test-manifest-missing" }
    assertEquals("error", f.severity)
    assertTrue(f.remediationCommands.any { "ui-test-manifest" in it })
  }

  @Test
  fun `ui-test-manifest present is quiet`() {
    val test = testWithoutUiTestManifest() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    assertNull(findings.firstOrNull { it.id == "ui-test-manifest-missing" })
  }

  @Test
  fun `navigationevent with old main activity fires error`() {
    val main = mainWithBom() + ("androidx.activity:activity" to "1.8.0")
    val test =
      testWithoutUiTestManifest() +
        ("androidx.navigationevent:navigationevent" to "1.0.0") +
        ("androidx.activity:activity" to "1.13.0")
    val findings = CompatRules.evaluate(main, test)
    val f = findings.single { it.id == "activity-vs-navigationevent" }
    assertEquals("error", f.severity)
    assertTrue("1.8.0" in (f.detail ?: ""))
  }

  @Test
  fun `navigationevent with matching main activity is quiet`() {
    val main = mainWithBom() + ("androidx.activity:activity" to "1.13.0")
    val test =
      main +
        ("androidx.navigationevent:navigationevent" to "1.0.0") +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    assertNull(findings.firstOrNull { it.id == "activity-vs-navigationevent" })
  }

  @Test
  fun `compose-ui 1_10 with old core fires error`() {
    val main = mainWithBom() + ("androidx.core:core" to "1.15.0")
    val test =
      main +
        ("androidx.compose.ui:ui" to "1.10.6") +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    val f = findings.single { it.id == "compose-ui-vs-core" }
    assertEquals("error", f.severity)
  }

  @Test
  fun `compose-ui 1_9 is below threshold (no finding even with old core)`() {
    val main = mainWithBom() + ("androidx.core:core" to "1.15.0")
    val test =
      main +
        ("androidx.compose.ui:ui" to "1.9.4") +
        ("androidx.compose.ui:ui-test-manifest" to "1.9.4")
    val findings = CompatRules.evaluate(main, test)
    assertNull(findings.firstOrNull { it.id == "compose-ui-vs-core" })
  }

  @Test
  fun `no compose BOM yields warning`() {
    val main = mapOf("androidx.core:core" to "1.16.0")
    val test = main + ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    val f = findings.single { it.id == "compose-bom-missing" }
    assertEquals("warning", f.severity)
  }

  @Test
  fun `with compose BOM, bom finding is absent`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
      )
    assertNull(findings.firstOrNull { it.id == "compose-bom-missing" })
  }

  @Test
  fun `all findings populated have non-empty docs or commands`() {
    // Stacked scenario: everything wrong — exercises every rule at once.
    val main = mapOf("androidx.activity:activity" to "1.8.0", "androidx.core:core" to "1.15.0")
    val test =
      mapOf(
        "androidx.navigationevent:navigationevent" to "1.0.0",
        "androidx.compose.ui:ui" to "1.10.6",
      )
    val findings = CompatRules.evaluate(main, test)
    assertTrue(findings.size >= 3)
    // Every finding should carry at least one actionable hint.
    for (f in findings) {
      val actionable = f.remediationCommands.isNotEmpty() || f.docsUrl != null
      assertTrue(
        "finding ${f.id} has no actionable remediation",
        actionable || f.remediationSummary != null,
      )
    }
  }

  @Test
  fun `old activity fires old-dep warning`() {
    val main = mainWithBom() + ("androidx.activity:activity" to "1.8.0")
    val test = main + ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    val f = findings.single { it.id == "old-dep-androidx.activity:activity" }
    assertEquals("warning", f.severity)
    assertTrue("1.8.0" in f.message)
    assertTrue(f.remediationCommands.any { "androidx.activity:activity" in it })
  }

  @Test
  fun `current activity is quiet`() {
    val main = mainWithBom() + ("androidx.activity:activity" to "1.13.0")
    val test = main + ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    assertNull(findings.firstOrNull { it.id.startsWith("old-dep-androidx.activity") })
  }

  @Test
  fun `absent library emits no old-dep finding`() {
    // lifecycle-runtime isn't on the classpath at all — silently skipped.
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
      )
    assertNull(findings.firstOrNull { it.id.startsWith("old-dep-androidx.lifecycle") })
  }

  @Test
  fun `test-classpath higher than main is taken as resolved`() {
    // Main has old activity, test classpath has newer — we take the max.
    val main = mainWithBom() + ("androidx.activity:activity" to "1.8.0")
    val test =
      main +
        ("androidx.activity:activity" to "1.13.0") +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6")
    val findings = CompatRules.evaluate(main, test)
    assertNull(findings.firstOrNull { it.id.startsWith("old-dep-androidx.activity") })
  }

  @Test
  fun `old gradle fires error`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
        gradleVersion = "8.10",
      )
    val f = findings.single { it.id == "gradle-too-old" }
    assertEquals("error", f.severity)
    assertTrue("8.10" in f.message)
    assertTrue(f.remediationCommands.any { "gradle-version" in it })
  }

  @Test
  fun `minimum gradle is quiet`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
        gradleVersion = CompatRules.GRADLE_MIN.toString(),
      )
    assertNull(findings.firstOrNull { it.id == "gradle-too-old" })
  }

  @Test
  fun `newer gradle is quiet`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
        gradleVersion = "9.9.0",
      )
    assertNull(findings.firstOrNull { it.id == "gradle-too-old" })
  }

  @Test
  fun `absent gradle version emits no finding`() {
    // Back-compat: callers that don't plumb the running Gradle version
    // still get the dependency checks without a bogus "unknown version"
    // finding.
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        mainWithBom() + ("androidx.compose.ui:ui-test-manifest" to "1.10.6"),
      )
    assertNull(findings.firstOrNull { it.id == "gradle-too-old" })
  }

  @Test
  fun `hamcrest 2x with legacy 1_3 fires error`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("org.hamcrest:hamcrest" to "2.2") +
        ("org.hamcrest:hamcrest-library" to "1.3") +
        ("org.hamcrest:hamcrest-core" to "1.3")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    val f = findings.single { it.id == "hamcrest-skew" }
    assertEquals("error", f.severity)
    assertTrue("hamcrest:2.2" in f.message)
    assertTrue("hamcrest-library:1.3" in f.message)
    assertTrue(f.remediationCommands.any { "useTarget" in it })
  }

  @Test
  fun `hamcrest 2x alone is quiet`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("org.hamcrest:hamcrest" to "2.2")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    assertNull(findings.firstOrNull { it.id == "hamcrest-skew" })
  }

  @Test
  fun `hamcrest 1_3 alone is quiet`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("org.hamcrest:hamcrest-library" to "1.3") +
        ("org.hamcrest:hamcrest-core" to "1.3")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    assertNull(findings.firstOrNull { it.id == "hamcrest-skew" })
  }

  @Test
  fun `kmp desktop sibling on test classpath fires error`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("androidx.lifecycle:lifecycle-viewmodel-desktop" to "2.8.7") +
        ("org.jetbrains.compose.ui:ui-desktop" to "1.10.0")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    val f = findings.single { it.id == "kmp-android-sibling-mismatch" }
    assertEquals("error", f.severity)
    assertTrue("lifecycle-viewmodel-desktop:2.8.7" in f.message)
    assertTrue("org.jetbrains.compose.ui:ui-desktop:1.10.0" in f.message)
    assertTrue(f.remediationCommands.any { "removeSuffix" in it })
  }

  @Test
  fun `kmp jvmstubs sibling on test classpath fires error`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("androidx.savedstate:savedstate-jvmstubs" to "1.3.0")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    val f = findings.single { it.id == "kmp-android-sibling-mismatch" }
    assertEquals("error", f.severity)
    assertTrue("androidx.savedstate:savedstate-jvmstubs:1.3.0" in f.message)
  }

  @Test
  fun `kmp android sibling and unscoped desktop artifacts are quiet`() {
    val test =
      mainWithBom() +
        ("androidx.compose.ui:ui-test-manifest" to "1.10.6") +
        ("androidx.lifecycle:lifecycle-viewmodel-android" to "2.8.7") +
        ("org.jetbrains.kotlinx:kotlinx-coroutines-swing-desktop" to "1.10.2")
    val findings = CompatRules.evaluate(mainWithBom(), test)
    assertNull(findings.firstOrNull { it.id == "kmp-android-sibling-mismatch" })
  }

  @Test
  fun `semver parses and compares`() {
    assertEquals(Semver(1, 16, 0), Semver.parseOrNull("1.16.0"))
    assertEquals(Semver(1, 16, 0), Semver.parseOrNull("1.16"))
    assertNull(Semver.parseOrNull("foo"))
    assertTrue(Semver(1, 16, 0) > Semver(1, 15, 0))
    assertTrue(Semver(1, 13, 0) > Semver(1, 13, 0, "alpha01"))
    assertNotNull(Semver.parseOrNull("1.13.0-alpha01"))
  }

  @Test
  fun `undeclared preview tooling with escape hatch on fires warning`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        testWithoutUiTestManifest(),
        previewToolingDeclared = false,
        enforcePreviewToolingDependency = false,
      )
    val f = findings.single { it.id == "module.preview-tooling-not-declared" }
    assertEquals("warning", f.severity)
    assertTrue(f.remediationCommands.any { "ui-tooling-preview" in it })
  }

  @Test
  fun `declared preview tooling stays quiet even with escape hatch on`() {
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        testWithoutUiTestManifest(),
        previewToolingDeclared = true,
        enforcePreviewToolingDependency = false,
      )
    assertNull(findings.firstOrNull { it.id == "module.preview-tooling-not-declared" })
  }

  @Test
  fun `enforce-on path does not fire the escape-hatch finding`() {
    // When the gate is enforced and tooling is declared, the check is a no-op. (When the gate is
    // enforced and tooling is NOT declared, the plugin's `onVariants` filter skips registration
    // entirely so doctor never runs — but `CompatRules.evaluate` itself stays silent on the
    // input, which is the contract pinned here.)
    val findings =
      CompatRules.evaluate(
        mainWithBom(),
        testWithoutUiTestManifest(),
        previewToolingDeclared = false,
        enforcePreviewToolingDependency = true,
      )
    assertNull(findings.firstOrNull { it.id == "module.preview-tooling-not-declared" })
  }

  @Test
  fun `null signals skip the check entirely (CMP Desktop path)`() {
    // CMP / Desktop callers pass null/null because the per-module Android signals aren't
    // meaningful for them. Asserts the check stays silent rather than emitting a spurious warning
    // on non-Android modules.
    val findings = CompatRules.evaluate(mainWithBom(), testWithoutUiTestManifest())
    assertNull(findings.firstOrNull { it.id == "module.preview-tooling-not-declared" })
  }

  // --- Fixtures ----------------------------------------------------------

  private fun mainWithBom(): Map<String, String> =
    mapOf(
      "androidx.compose:compose-bom" to "2025.03.00",
      "androidx.activity:activity" to "1.13.0",
      "androidx.core:core" to "1.18.0",
    )

  private fun testWithoutUiTestManifest(): Map<String, String> =
    mapOf("androidx.activity:activity" to "1.13.0")
}
