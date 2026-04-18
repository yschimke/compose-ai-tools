package ee.schimke.composeai.plugin.tooling

/**
 * Plugin-side compat-check rules. Single source of truth used by both
 * [ComposePreviewModelBuilder] (Tooling API path, consumed by the CLI) and
 * [ComposePreviewDoctorTask] (Gradle-task path, consumed by the VS Code
 * extension and anything else that drives Gradle tasks but can't run
 * `BuildAction`s).
 *
 * Bump the thresholds here — and only here — when a new AndroidX AAR adds
 * an R.id field that older transitives don't have. Keep the catalogue in
 * `docs/RENDERER_COMPATIBILITY.md` in sync.
 */
internal object CompatRules {

    /**
     * activity 1.11+ transitively brought `androidx.navigationevent:1.0.0`.
     * Consumers whose main variant has older activity end up with the
     * `ComponentActivity` classes expecting `R.id.view_tree_…` resources
     * that aren't in the merged APK — crashes Robolectric with
     * `NoClassDefFoundError: androidx/navigationevent/R$id`.
     */
    private val NAVIGATIONEVENT_REQUIRES_ACTIVITY = Semver(1, 11, 0)

    /**
     * compose-ui 1.10.0 added a call site that references
     * `androidx.core.R.id.tag_compat_insets_dispatch`, added in
     * `androidx.core:core:1.16.0`.
     */
    private val COMPOSE_UI_NEEDS_CORE_1_16 = Semver(1, 10, 0)
    private val CORE_1_16 = Semver(1, 16, 0)

    /**
     * Generic "you're well behind head" minimums for libraries whose
     * version commonly shapes renderer behaviour. Threshold policy:
     * roughly the second-to-last known stable at time of writing —
     * recent enough to flag genuinely stale stacks, lenient enough that
     * a consumer one release behind head doesn't get nagged. Bump when
     * the paired upstream line ships a new stable minor.
     *
     * The specific compat rules above (navigationevent, core-vs-compose)
     * still fire on their own when they apply — this list is the
     * catch-all for "nothing's actively broken, but you're old".
     */
    private val OLD_DEP_MINIMUMS: List<Pair<String, Semver>> = listOf(
        "androidx.compose.ui:ui" to Semver(1, 9, 0),
        "androidx.activity:activity" to Semver(1, 10, 0),
        "androidx.core:core" to Semver(1, 15, 0),
        "androidx.lifecycle:lifecycle-runtime" to Semver(2, 8, 0),
    )

    /**
     * Runs every rule against the given dep snapshot and returns findings
     * ordered by severity. Pure — safe to call from tests and from the
     * serialisation path.
     */
    fun evaluate(main: Map<String, String>, test: Map<String, String>): List<ModuleFindingData> {
        val findings = mutableListOf<ModuleFindingData>()
        val mainV = parseVersions(main)
        val testV = parseVersions(test)
        checkUiTestManifest(testV)?.let(findings::add)
        checkNavigationEvent(mainV, testV, main)?.let(findings::add)
        checkComposeUiVsCore(mainV, testV, main)?.let(findings::add)
        checkComposeBom(main)?.let(findings::add)
        findings += checkOldDeps(mainV, testV, main, test)
        return findings
    }

    private fun checkUiTestManifest(test: Map<String, Semver>): ModuleFindingData? {
        if (test.containsKey("androidx.compose.ui:ui-test-manifest")) return null
        return ModuleFindingData(
            id = "ui-test-manifest-missing",
            severity = "error",
            message = "androidx.compose.ui:ui-test-manifest missing from test classpath",
            detail = "ComposeTestRule-backed paths (a11y checks, future renderer features) need the <activity> entry merged into the test AndroidManifest. Without it, Robolectric can't launch the host Activity at render time.",
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
            detail = "Test classpath has androidx.navigationevent:$navEvent pulled in via a newer activity. " +
                "AGP builds the merged test resource APK from the main variant " +
                "(activity:${rawMain["androidx.activity:activity"] ?: "(absent)"}), so " +
                "`androidx.navigationevent.R.id.*` resources aren't merged. Expect " +
                "`NoClassDefFoundError: androidx/navigationevent/R\$id` at render time.",
            remediationSummary = "Upgrade main-variant activity-compose to >= $NAVIGATIONEVENT_REQUIRES_ACTIVITY, or avoid transitively pulling navigationevent into tests.",
            remediationCommands = listOf("implementation(\"androidx.activity:activity-compose:$NAVIGATIONEVENT_REQUIRES_ACTIVITY\")"),
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
            detail = "Test classpath has compose-ui:$composeUi which calls `ViewCompat.setOnApplyWindowInsetsListener`, reading `R.id.tag_compat_insets_dispatch` (added in androidx.core:1.16.0). Main variant has core:${rawMain["androidx.core:core"] ?: "(absent)"}, so that resource isn't in the merged APK. Expect `NoSuchFieldError: … tag_compat_insets_dispatch`.",
            remediationSummary = "Bump Compose BOM (or androidx.core directly) on the main variant so it resolves to >= $CORE_1_16.",
            remediationCommands = emptyList(),
            docsUrl = DOCS_COMPOSE_UI_VS_CORE,
        )
    }

    /**
     * Emits one warning per [OLD_DEP_MINIMUMS] entry whose highest resolved
     * version (main or test classpath) is below the minimum. Libraries
     * absent from both classpaths are silently skipped — the consumer
     * isn't using them, so there's nothing to warn about.
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
            out += ModuleFindingData(
                id = "old-dep-$artifact",
                severity = "warning",
                message = "$artifact is on $rawVersion; recommended >= $minVersion",
                detail = "Resolved $artifact:$rawVersion is several releases behind. Renderer compat issues tend to cluster around older Compose / Activity / Core / Lifecycle versions; upgrading clears a class of bugs before they hit.",
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
            detail = "Without a BOM, compose-ui / compose-runtime / compose-foundation can end up on non-lockstep versions. Most consumer failure reports we see start here.",
            remediationSummary = "Declare a Compose BOM to align all compose-* artifact versions.",
            remediationCommands = listOf("implementation(platform(\"androidx.compose:compose-bom:2024.09.00\"))"),
            docsUrl = null,
        )
    }

    private fun parseVersions(raw: Map<String, String>): Map<String, Semver> {
        val out = LinkedHashMap<String, Semver>(raw.size)
        for ((key, version) in raw) {
            Semver.parseOrNull(version)?.let { out[key] = it }
        }
        return out
    }

    private const val DOCS_ROOT = "https://github.com/yschimke/compose-ai-tools/blob/main/docs/RENDERER_COMPATIBILITY.md"
    private const val DOCS_UI_TEST_MANIFEST = "$DOCS_ROOT#consumer-scope-ui-test-manifest-injection"
    private const val DOCS_NAVIGATIONEVENT = "$DOCS_ROOT#activity-compose-111-on-a-consumer-with-an-older-activity"
    private const val DOCS_COMPOSE_UI_VS_CORE = "$DOCS_ROOT#compose-ui-110-on-a-consumer-with-older-androidxcore"
}

/**
 * Minimal semver used by compat rules. Lives plugin-side so the rules can
 * work against the resolved-version strings Gradle produces, without a
 * dependency on any external semver library.
 */
internal data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val extra: String = "",
) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        val base = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (base != 0) return base
        return when {
            extra == other.extra -> 0
            extra.isEmpty() -> 1   // release beats pre-release
            other.extra.isEmpty() -> -1
            else -> extra.compareTo(other.extra)
        }
    }

    override fun toString(): String = buildString {
        append(major); append('.'); append(minor); append('.'); append(patch)
        if (extra.isNotEmpty()) { append('-'); append(extra) }
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

/**
 * [ModuleFinding] impl used by both the model builder and the task. Named
 * `Data` not `Impl` to match the existing ModuleInfoData / ComposePreviewModelData.
 */
internal data class ModuleFindingData(
    override val id: String,
    override val severity: String,
    override val message: String,
    override val detail: String?,
    override val remediationSummary: String?,
    override val remediationCommands: List<String>,
    override val docsUrl: String?,
) : ModuleFinding, java.io.Serializable
