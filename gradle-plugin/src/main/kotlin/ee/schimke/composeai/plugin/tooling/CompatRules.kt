package ee.schimke.composeai.plugin.tooling

import java.time.YearMonth

/**
 * Plugin-side compat rules, shared by [ComposePreviewModelBuilder] (Tooling API / CLI) and
 * [ComposePreviewDoctorTask] (task path / VS Code). Bump thresholds only here, and keep
 * `docs/RENDERER_COMPATIBILITY.md` in sync.
 */
internal object CompatRules {

  /**
   * Lowest Gradle version our integration matrix exercises (the `agp8-min` fixture). A coverage
   * statement, not an API requirement; AGP's own floor is usually stricter. Unrelated to the repo
   * wrapper version.
   */
  internal val GRADLE_MIN = Semver(8, 13, 0)

  /**
   * activity 1.11+ brings `androidx.navigationevent`; older activity on the main variant leaves
   * `ComponentActivity` expecting R ids absent from the merged APK (`NoClassDefFoundError:
   * androidx/navigationevent/R$id`).
   */
  private val NAVIGATIONEVENT_REQUIRES_ACTIVITY = Semver(1, 11, 0)

  /**
   * compose-ui 1.10.0 references `androidx.core.R.id.tag_compat_insets_dispatch`, added in core
   * 1.16.0.
   */
  private val COMPOSE_UI_NEEDS_CORE_1_16 = Semver(1, 10, 0)
  private val CORE_1_16 = Semver(1, 16, 0)

  /**
   * "Well behind head" minimums for libraries that shape renderer behaviour: roughly the
   * second-to-last stable. Bump when upstream ships a new stable minor. The specific rules above
   * still fire independently.
   */
  private val OLD_DEP_MINIMUMS: List<Pair<String, Semver>> =
    listOf(
      "androidx.compose.ui:ui" to Semver(1, 10, 0),
      "androidx.activity:activity" to Semver(1, 10, 0),
      "androidx.core:core" to Semver(1, 15, 0),
      "androidx.lifecycle:lifecycle-runtime" to Semver(2, 8, 0),
    )

  /**
   * Runs every rule against the dependency snapshot, returning findings by severity. Pure.
   *
   * Android-only signals (null for CMP / Desktop, which skips [checkUndeclaredPreviewTooling]):
   * - `previewToolingDeclared` — a preview-tooling coord is declared directly in this module.
   * - `transitivePreviewToolingDetected` — the resolved runtime graph reaches one (#1549), computed
   *   via [ee.schimke.composeai.plugin.ValidatePreviewToolingPresentTask.containsPreviewTooling].
   * - `enforcePreviewToolingDependency` — the `composePreview` escape hatch (#241).
   */
  fun evaluate(
    main: Map<String, String>,
    test: Map<String, String>,
    gradleVersion: String? = null,
    previewToolingDeclared: Boolean? = null,
    enforcePreviewToolingDependency: Boolean? = null,
    transitivePreviewToolingDetected: Boolean? = null,
    moduleMinSdk: Int? = null,
    libraryMinSdks: List<LibraryMinSdk> = emptyList(),
  ): List<ModuleFindingData> {
    val findings = mutableListOf<ModuleFindingData>()
    val mainV = parseVersions(main)
    val testV = parseVersions(test)
    checkGradleVersion(gradleVersion)?.let(findings::add)
    checkUiTestManifest(testV)?.let(findings::add)
    checkNavigationEvent(mainV, testV, main)?.let(findings::add)
    checkComposeUiVsCore(mainV, testV, main)?.let(findings::add)
    checkComposeBom(main)?.let(findings::add)
    checkHamcrestSkew(test)?.let(findings::add)
    checkKmpAndroidSiblingMismatch(test)?.let(findings::add)
    checkLibraryMinSdk(moduleMinSdk, libraryMinSdks)?.let(findings::add)
    checkUndeclaredPreviewTooling(
        previewToolingDeclared,
        enforcePreviewToolingDependency,
        transitivePreviewToolingDetected,
      )
      ?.let(findings::add)
    findings += checkOldDeps(mainV, testV, main, test)
    return findings
  }

  /**
   * Recommends declaring preview tooling directly when the gate passed only via the escape hatch or
   * transitive detection. Pinning it locally locks the version against sibling upgrades, exposes it
   * to doctor's version-skew checks, and documents intent. A warning, since relying on transitive
   * reachability is defensible. `null` when signals are unset, the dep is declared, or neither
   * fallback applied.
   */
  private fun checkUndeclaredPreviewTooling(
    previewToolingDeclared: Boolean?,
    enforcePreviewToolingDependency: Boolean?,
    transitivePreviewToolingDetected: Boolean?,
  ): ModuleFindingData? {
    if (previewToolingDeclared == null || enforcePreviewToolingDependency == null) return null
    if (previewToolingDeclared) return null
    val gatePassedViaEscapeHatch = !enforcePreviewToolingDependency
    val gatePassedViaTransitive = transitivePreviewToolingDetected == true
    if (!gatePassedViaEscapeHatch && !gatePassedViaTransitive) return null
    val cause =
      when {
        gatePassedViaTransitive ->
          "preview tooling reaches this module transitively via a declared `project(\":...\")` " +
            "dep (auto-detected by the IP-safe cross-project metadata service added in #1549)"
        else ->
          "composePreview.enforcePreviewToolingDependency = false bypasses the per-module " +
            "preview-tooling gate"
      }
    return ModuleFindingData(
      id = "module.preview-tooling-not-declared",
      severity = "warning",
      message = "$cause; no @Preview tooling coord is declared directly in this module",
      detail =
        "Declaring the dep here too pins the version in this module's view of the graph, " +
          "surfaces it to compose-preview doctor's version-skew checks, and documents intent for " +
          "future readers. Pick whichever tooling coord matches your stack: " +
          "androidx.compose.ui:ui-tooling-preview (AGP-only), or " +
          "org.jetbrains.compose.components:components-ui-tooling-preview (CMP / KMP).",
      remediationSummary =
        "Declare the preview-tooling dep directly so it's pinned and visible to doctor",
      remediationCommands =
        listOf(
          "dependencies {",
          "  implementation(\"androidx.compose.ui:ui-tooling-preview\")",
          "  // or, for Compose Multiplatform consumers:",
          "  // implementation(compose.components.uiToolingPreview)",
          "}",
        ),
      docsUrl = null,
    )
  }

  /**
   * Hamcrest 2.x removed the 2-arg `AllOf.allOf` that Espresso (built against 1.3) calls; with both
   * `hamcrest:2.x` and `hamcrest-library:1.3` on the classpath, `Espresso.<clinit>` fails with
   * `NoSuchMethodError`. The plugin already fixes its own render configuration; this surfaces the
   * skew for the consumer's own unit-test runs.
   */
  private fun checkHamcrestSkew(test: Map<String, String>): ModuleFindingData? {
    val merged = test["org.hamcrest:hamcrest"] ?: return null
    val library13 = test["org.hamcrest:hamcrest-library"]
    val core13 = test["org.hamcrest:hamcrest-core"]
    if (library13 == null && core13 == null) return null
    val mergedSemver = Semver.parseOrNull(merged) ?: return null
    if (mergedSemver < Semver(2, 0, 0)) return null
    val legacy =
      listOfNotNull(library13?.let { "hamcrest-library:$it" }, core13?.let { "hamcrest-core:$it" })
        .joinToString(", ")
    return ModuleFindingData(
      id = "hamcrest-skew",
      severity = "error",
      message = "mixed Hamcrest versions on the unit-test classpath (hamcrest:$merged + $legacy)",
      detail =
        "Espresso (transitively via androidx.compose.ui:ui-test-junit4) was compiled against " +
          "Hamcrest 1.3 and calls `org.hamcrest.core.AllOf.allOf(Matcher, Matcher)` — a 2-arg " +
          "overload removed in Hamcrest 2.x. With both `org.hamcrest:hamcrest:$merged` and the " +
          "legacy split `org.hamcrest:hamcrest-library` / `:hamcrest-core` jars on the classpath, " +
          "class lookup is order-dependent: when `Matchers` resolves to 1.3 but `AllOf` resolves " +
          "to 2.x, `Espresso.<clinit>` fails with `NoSuchMethodError` the first time " +
          "`RobolectricIdlingStrategy.runUntilIdle` walks through `EspressoLink`. The renderer's " +
          "own classpath has the merged jar substituted out, but AGP-driven test tasks still see " +
          "the skew.",
      remediationSummary =
        "Force-align hamcrest on the unit-test runtime classpath, or exclude `org.hamcrest:hamcrest` from whichever transitive pulls 2.x.",
      remediationCommands =
        listOf(
          "configurations.matching { it.name.endsWith(\"UnitTestRuntimeClasspath\") }.configureEach {",
          "  resolutionStrategy.eachDependency {",
          "    if (requested.group == \"org.hamcrest\" && requested.name == \"hamcrest\") {",
          "      useTarget(\"org.hamcrest:hamcrest-core:1.3\")",
          "    }",
          "  }",
          "}",
        ),
      docsUrl = DOCS_HAMCREST_SKEW,
    )
  }

  /**
   * KMP siblings (`-android`, `-desktop`, `-jvmstubs`) may resolve to the wrong platform when
   * Kotlin's platform-type rule isn't registered (AGP-only builds). The renderer config substitutes
   * them; this surfaces the skew for consumer-owned unit tests.
   */
  private fun checkKmpAndroidSiblingMismatch(test: Map<String, String>): ModuleFindingData? {
    val mismatches =
      test.keys
        .filter { coordinate ->
          val separator = coordinate.indexOf(':')
          if (separator <= 0) return@filter false
          val group = coordinate.substring(0, separator)
          val name = coordinate.substring(separator + 1)
          (group.startsWith("androidx.") || group.startsWith("org.jetbrains.compose.")) &&
            (name.endsWith("-desktop") || name.endsWith("-jvmstubs"))
        }
        .sorted()
    if (mismatches.isEmpty()) return null
    val rendered = mismatches.joinToString(", ") { "$it:${test.getValue(it)}" }
    return ModuleFindingData(
      id = "kmp-android-sibling-mismatch",
      severity = "error",
      message = "desktop/JVM-stub KMP sibling resolved on the unit-test classpath ($rendered)",
      detail =
        "AndroidX and Compose Multiplatform KMP modules publish Android and desktop/JVM-stub " +
          "siblings as separate coordinates. Android unit tests need the `-android` sibling so " +
          "AGP-flavoured bytecode links against the expected class shapes. If the Kotlin Android " +
          "plugin is absent, Gradle may resolve a desktop/JVM-stub sibling instead; the renderer " +
          "configuration substitutes it back to `-android`, but consumer-owned unit-test tasks " +
          "still see the mismatch.",
      remediationSummary =
        "Apply `org.jetbrains.kotlin.android`, or add a unit-test classpath substitution from `-desktop` / `-jvmstubs` to `-android`.",
      remediationCommands =
        listOf(
          "configurations.matching { it.name.endsWith(\"UnitTestRuntimeClasspath\") }.configureEach {",
          "  resolutionStrategy.eachDependency {",
          "    if ((requested.group.startsWith(\"androidx.\") || requested.group.startsWith(\"org.jetbrains.compose.\")) &&",
          "        (requested.name.endsWith(\"-desktop\") || requested.name.endsWith(\"-jvmstubs\"))) {",
          "      val suffix = if (requested.name.endsWith(\"-desktop\")) \"-desktop\" else \"-jvmstubs\"",
          "      useTarget(\"${'$'}{requested.group}:${'$'}{requested.name.removeSuffix(suffix)}-android:${'$'}{requested.version}\")",
          "    }",
          "  }",
          "}",
        ),
      docsUrl = null,
    )
  }

  /**
   * Fires when a library on the unit-test classpath declares a higher `minSdkVersion` than the
   * module: rendering triggers the unit-test manifest merge, which rejects it with an opaque AGP
   * error. Recommends the unit-test-only `tools:overrideLibrary` (naming the parsed packages), or
   * raising minSdk. `null` when module minSdk is unknown or nothing exceeds it.
   */
  private fun checkLibraryMinSdk(
    moduleMinSdk: Int?,
    libraryMinSdks: List<LibraryMinSdk>,
  ): ModuleFindingData? {
    if (moduleMinSdk == null) return null
    val offenders =
      libraryMinSdks.filter { it.minSdk > moduleMinSdk }.sortedByDescending { it.minSdk }
    if (offenders.isEmpty()) return null
    val highest = offenders.first().minSdk
    val packages = offenders.mapNotNull { it.packageName }.distinct()
    val overrideValue =
      if (packages.isNotEmpty()) packages.joinToString(", ") else "<library.package.name>"
    val offenderList = offenders.joinToString(", ") { "${it.coordinate} (minSdk ${it.minSdk})" }
    return ModuleFindingData(
      id = "library-minsdk-exceeds-module",
      severity = "error",
      message = "library minSdk exceeds module minSdk ($moduleMinSdk): $offenderList",
      detail =
        "compose-preview renders inside a Robolectric unit test, so AGP merges the unit-test " +
          "AndroidManifest (`process<Variant>UnitTestManifest`). That merge fails when a library " +
          "declares a higher `minSdkVersion` than this module ($moduleMinSdk) — here $offenderList. " +
          "minSdk has no meaning for a host-side unit test, so the `tools:overrideLibrary` escape " +
          "hatch clears the merge for the test variant without changing the minSdk you ship. Use " +
          "it unless you actually intend to raise this module's on-device minSdk to >= $highest.",
      remediationSummary =
        "Add tools:overrideLibrary to the unit-test AndroidManifest, or raise the module minSdk to >= $highest.",
      remediationCommands =
        listOf(
          "<!-- src/test/AndroidManifest.xml (or src/androidUnitTest/ for a KMP/CMP module) -->",
          "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"",
          "    xmlns:tools=\"http://schemas.android.com/tools\">",
          "    <uses-sdk tools:overrideLibrary=\"$overrideValue\" />",
          "</manifest>",
        ),
      docsUrl = DOCS_LIBRARY_MINSDK,
    )
  }

  /**
   * Flags Gradle below [GRADLE_MIN]; [gradleVersion] is `GradleVersion.current().version`, `null`
   * when not plumbed. AGP builds fail earlier, but CMP Desktop has no AGP gate, and a clear finding
   * beats the Tooling API's opaque error.
   */
  private fun checkGradleVersion(gradleVersion: String?): ModuleFindingData? {
    val raw = gradleVersion ?: return null
    val parsed = Semver.parseOrNull(raw) ?: return null
    if (parsed >= GRADLE_MIN) return null
    return ModuleFindingData(
      id = "gradle-too-old",
      severity = "error",
      message = "Gradle $raw is below the supported floor ($GRADLE_MIN)",
      detail =
        "compose-preview is integration-tested against Gradle $GRADLE_MIN and newer. The plugin " +
          "itself only uses APIs stable since the 8.x line, so older Gradle may happen to work, " +
          "but isn't covered by CI. On Android builds AGP's own check usually rejects too-old " +
          "Gradle before our `apply()` runs (AGP 9.0.x needs 9.1.0+, AGP 9.1.x needs 9.3.1+); " +
          "this finding is still emitted for CMP Desktop projects (no AGP gate) and to give a " +
          "clear remediation when the Tooling API wraps the real cause in a generic `Could not " +
          "execute build using connection to Gradle distribution …` message.",
      remediationSummary = "Upgrade the project's Gradle wrapper to >= $GRADLE_MIN.",
      remediationCommands = listOf("./gradlew wrapper --gradle-version $GRADLE_MIN"),
      docsUrl = null,
    )
  }

  private fun checkUiTestManifest(test: Map<String, Semver>): ModuleFindingData? {
    if (test.containsKey("androidx.compose.ui:ui-test-manifest")) return null
    return ModuleFindingData(
      id = "ui-test-manifest-missing",
      severity = "error",
      message = "androidx.compose.ui:ui-test-manifest missing from test classpath",
      detail =
        "ComposeTestRule-backed paths (a11y checks, future renderer features) need the <activity> entry merged into the test AndroidManifest. Without it, Robolectric can't launch the host Activity at render time.",
      remediationSummary = "Apply the plugin so it injects ui-test-manifest, or add it manually.",
      remediationCommands = listOf("testImplementation(\"androidx.compose.ui:ui-test-manifest\")"),
      docsUrl = DOCS_UI_TEST_MANIFEST,
    )
  }

  private fun checkNavigationEvent(
    main: Map<String, Semver>,
    test: Map<String, Semver>,
    rawMain: Map<String, String>,
  ): ModuleFindingData? {
    val navEvent = test["androidx.navigationevent:navigationevent"] ?: return null
    val mainActivity = main["androidx.activity:activity"]
    if (mainActivity != null && mainActivity >= NAVIGATIONEVENT_REQUIRES_ACTIVITY) return null
    return ModuleFindingData(
      id = "activity-vs-navigationevent",
      severity = "error",
      message = "navigationevent on test classpath but main variant activity is too old",
      detail =
        "Test classpath has androidx.navigationevent:$navEvent pulled in via a newer activity. " +
          "AGP builds the merged test resource APK from the main variant " +
          "(activity:${rawMain["androidx.activity:activity"] ?: "(absent)"}), so " +
          "`androidx.navigationevent.R.id.*` resources aren't merged. Expect " +
          "`NoClassDefFoundError: androidx/navigationevent/R\$id` at render time.",
      remediationSummary =
        "Upgrade main-variant activity-compose to >= $NAVIGATIONEVENT_REQUIRES_ACTIVITY, or avoid transitively pulling navigationevent into tests.",
      remediationCommands =
        listOf(
          "implementation(\"androidx.activity:activity-compose:$NAVIGATIONEVENT_REQUIRES_ACTIVITY\")"
        ),
      docsUrl = DOCS_NAVIGATIONEVENT,
    )
  }

  private fun checkComposeUiVsCore(
    main: Map<String, Semver>,
    test: Map<String, Semver>,
    rawMain: Map<String, String>,
  ): ModuleFindingData? {
    val composeUi = test["androidx.compose.ui:ui"] ?: return null
    if (composeUi < COMPOSE_UI_NEEDS_CORE_1_16) return null
    val mainCore = main["androidx.core:core"]
    if (mainCore != null && mainCore >= CORE_1_16) return null
    return ModuleFindingData(
      id = "compose-ui-vs-core",
      severity = "error",
      message = "compose-ui $composeUi expects androidx.core >= $CORE_1_16",
      detail =
        "Test classpath has compose-ui:$composeUi which calls `ViewCompat.setOnApplyWindowInsetsListener`, reading `R.id.tag_compat_insets_dispatch` (added in androidx.core:1.16.0). Main variant has core:${rawMain["androidx.core:core"] ?: "(absent)"}, so that resource isn't in the merged APK. Expect `NoSuchFieldError: … tag_compat_insets_dispatch`.",
      remediationSummary =
        "Bump Compose BOM (or androidx.core directly) on the main variant so it resolves to >= $CORE_1_16.",
      remediationCommands = emptyList(),
      docsUrl = DOCS_COMPOSE_UI_VS_CORE,
    )
  }

  /**
   * One warning per [OLD_DEP_MINIMUMS] entry whose highest resolved version is below the minimum;
   * absent libraries are skipped.
   */
  private fun checkOldDeps(
    main: Map<String, Semver>,
    test: Map<String, Semver>,
    rawMain: Map<String, String>,
    rawTest: Map<String, String>,
  ): List<ModuleFindingData> {
    val out = mutableListOf<ModuleFindingData>()
    for ((artifact, minVersion) in OLD_DEP_MINIMUMS) {
      val resolved = listOfNotNull(main[artifact], test[artifact]).maxOrNull() ?: continue
      if (resolved >= minVersion) continue
      val rawVersion = rawMain[artifact] ?: rawTest[artifact] ?: resolved.toString()
      out +=
        ModuleFindingData(
          id = "old-dep-$artifact",
          severity = "warning",
          message = "$artifact is on $rawVersion; recommended >= $minVersion",
          detail =
            "Resolved $artifact:$rawVersion is several releases behind. Renderer compat issues tend to cluster around older Compose / Activity / Core / Lifecycle versions; upgrading clears a class of bugs before they hit.",
          remediationSummary = "Upgrade $artifact to at least $minVersion.",
          remediationCommands = listOf("implementation(\"$artifact:$minVersion\")"),
          docsUrl = null,
        )
    }
    return out
  }

  private fun checkComposeBom(rawMain: Map<String, String>): ModuleFindingData? {
    if (rawMain.containsKey("androidx.compose:compose-bom")) return null
    return ModuleFindingData(
      id = "compose-bom-missing",
      severity = "warning",
      message = "no Compose BOM declared",
      detail =
        "Without a BOM, compose-ui / compose-runtime / compose-foundation can end up on non-lockstep versions. Most consumer failure reports we see start here.",
      remediationSummary = "Declare a Compose BOM to align all compose-* artifact versions.",
      remediationCommands =
        listOf(
          "implementation(platform(\"androidx.compose:compose-bom:${suggestedComposeBom()}\"))"
        ),
      docsUrl = null,
    )
  }

  /**
   * Previous month's Compose BOM (`YYYY.MM.00`), which always exists and never ages out of the
   * code.
   */
  internal fun suggestedComposeBom(today: YearMonth = YearMonth.now()): String {
    val ym = today.minusMonths(1)
    return "%04d.%02d.00".format(ym.year, ym.monthValue)
  }

  private fun parseVersions(raw: Map<String, String>): Map<String, Semver> {
    val out = LinkedHashMap<String, Semver>(raw.size)
    for ((key, version) in raw) {
      Semver.parseOrNull(version)?.let { out[key] = it }
    }
    return out
  }

  private const val DOCS_ROOT =
    "https://github.com/yschimke/compose-ai-tools/blob/main/docs/RENDERER_COMPATIBILITY.md"
  private const val DOCS_UI_TEST_MANIFEST = "$DOCS_ROOT#consumer-scope-ui-test-manifest-injection"
  private const val DOCS_NAVIGATIONEVENT =
    "$DOCS_ROOT#activity-compose-111-on-a-consumer-with-an-older-activity"
  private const val DOCS_COMPOSE_UI_VS_CORE =
    "$DOCS_ROOT#compose-ui-110-on-a-consumer-with-older-androidxcore"
  private const val DOCS_HAMCREST_SKEW = "$DOCS_ROOT#hamcrest-2x-on-the-unit-test-classpath"
  private const val DOCS_LIBRARY_MINSDK =
    "$DOCS_ROOT#a-library-declares-a-higher-minsdk-than-the-module"
}

/** Minimal semver for Gradle's resolved-version strings. */
internal data class Semver(val major: Int, val minor: Int, val patch: Int, val extra: String = "") :
  Comparable<Semver> {
  override fun compareTo(other: Semver): Int {
    val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    if (base != 0) return base
    return when {
      extra == other.extra -> 0
      extra.isEmpty() -> 1 // release beats pre-release
      other.extra.isEmpty() -> -1
      else -> extra.compareTo(other.extra)
    }
  }

  override fun toString(): String = buildString {
    append(major)
    append('.')
    append(minor)
    append('.')
    append(patch)
    if (extra.isNotEmpty()) {
      append('-')
      append(extra)
    }
  }

  companion object {
    fun parseOrNull(s: String): Semver? {
      val parts = s.split('-', limit = 2)
      val core = parts[0].split('.')
      if (core.size < 2) return null
      val major = core[0].toIntOrNull() ?: return null
      val minor = core[1].toIntOrNull() ?: return null
      val patch = core.getOrNull(2)?.toIntOrNull() ?: 0
      val extra = parts.getOrNull(1).orEmpty()
      return Semver(major, minor, patch, extra)
    }
  }
}

/** [ModuleFinding] impl shared by the model builder and the task. */
internal data class ModuleFindingData(
  override val id: String,
  override val severity: String,
  override val message: String,
  override val detail: String?,
  override val remediationSummary: String?,
  override val remediationCommands: List<String>,
  override val docsUrl: String?,
) : ModuleFinding, java.io.Serializable
