package ee.schimke.composeai.renderer

import android.os.Build
import android.view.View
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import org.robolectric.shadows.ShadowBuild

/**
 * Minimal ATF integration for the Robolectric renderer.
 *
 * ATF's [AccessibilityHierarchyAndroid] bails out when
 * `Build.FINGERPRINT == "robolectric"`
 * ([AccessibilityHierarchyAndroid.java:667](https://github.com/google/Accessibility-Test-Framework-for-Android/blob/c65cab02b2a845c29c3da100d6adefd345a144e3/src/main/java/com/google/android/apps/common/testing/accessibility/framework/uielement/AccessibilityHierarchyAndroid.java#L667)).
 * The workaround is to swap the fingerprint to something ATF doesn't recognise
 * for the duration of the check — we mirror what roborazzi's own
 * `whileAvoidingRobolectricFingerprint` helper does.
 *
 * Kept standalone (not using `SemanticsNodeInteraction.checkRoboAccessibility`)
 * because the renderer uses the lightweight `captureRoboImage { @Composable }`
 * overload, which doesn't expose a `SemanticsNodeInteraction`. Running ATF
 * directly against the view we snapshot through [LocalView] avoids paying the
 * `createComposeRule()` tax on every render.
 */
internal object AccessibilityChecker {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Output of a single a11y pass over the rendered View tree. Both
     * fields are populated unconditionally (so the Paparazzi-style overlay
     * can render even when nothing's wrong), but consumers usually treat
     * empty [findings] as "no ATF problems" and empty [nodes] as "a11y
     * was disabled or the View has no labelled content".
     */
    data class Result(
        val findings: List<AccessibilityFinding>,
        val nodes: List<AccessibilityNode>,
    )

    fun analyze(previewId: String, root: View): Result {
        val originalFingerprint = Build.FINGERPRINT
        val needsSwap = originalFingerprint == "robolectric"
        if (needsSwap) {
            ShadowBuild.setFingerprint("roborazzi")
        }
        try {
            val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
            val checks = AccessibilityCheckPreset
                .getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
            val allResults = mutableListOf<AccessibilityHierarchyCheckResult>()
            for (check in checks) {
                allResults += check.runCheckOnHierarchy(hierarchy)
            }
            if (DEBUG) {
                val typeCounts = allResults.groupingBy { it.type?.name ?: "null" }.eachCount()
                println("[compose-a11y] $previewId: ran ${checks.size} check(s), got ${allResults.size} raw result(s) on ${root.javaClass.simpleName} (attached=${root.isAttachedToWindow}) types=$typeCounts")
            }
            val findings = allResults.mapNotNull { it.toFinding() }
            val nodes = extractNodes(hierarchy)
            return Result(findings = findings, nodes = nodes)
        } finally {
            if (needsSwap) {
                ShadowBuild.setFingerprint(originalFingerprint)
            }
        }
    }

    /** Back-compat shim — most call sites only care about findings. */
    fun check(previewId: String, root: View): List<AccessibilityFinding> =
        analyze(previewId, root).findings

    private fun extractNodes(hierarchy: AccessibilityHierarchyAndroid): List<AccessibilityNode> {
        val views = hierarchy.activeWindow.allViews
        val out = mutableListOf<AccessibilityNode>()
        for (v in views) {
            val bounds = v.boundsInScreen
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) continue
            // Prefer contentDescription (what TalkBack actually announces);
            // fall back to visible text. Both are SpannableStrings — toString()
            // strips ATF's spans so we get plain UI copy.
            val label = (v.contentDescription?.toString()?.trim().orEmpty())
                .ifEmpty { v.text?.toString()?.trim().orEmpty() }
            val role = simplifyRole(v.accessibilityClassName?.toString() ?: v.className?.toString())
            val states = buildStates(
                isCheckable = v.isCheckable == true,
                isChecked = v.isChecked == true,
                isClickable = v.isClickable,
                isLongClickable = v.isLongClickable,
                isScrollable = v.isScrollable == true,
                isEditable = v.isEditable == true,
                isEnabled = v.isEnabled,
                stateDescription = v.stateDescription?.toString()?.trim().orEmpty(),
                hintText = v.hintText?.toString()?.trim().orEmpty(),
            )
            val merged = isMergedSemanticsRoot(v)
            // Drop nodes that wouldn't carry weight in the legend: a label,
            // a role beyond plain View, an actionable state, or clickability
            // is enough to keep them. Clickable-but-empty containers (a card
            // with text inside it that's already its own node) drop out.
            val keep = label.isNotEmpty() ||
                states.isNotEmpty() ||
                (role != null && (v.isClickable || v.isLongClickable))
            if (!keep) continue
            // The label is what reviewers scan for first; an unlabelled
            // clickable element with a known role still surfaces, but with
            // an empty `label` field — the overlay caller decides how to
            // render that (we draw the role on its own line in the legend).
            out += AccessibilityNode(
                label = label,
                role = role,
                states = states,
                merged = merged,
                boundsInScreen = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            )
        }
        return out
    }

    /**
     * Pure projection from the booleans / strings ATF exposes on a single
     * [com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement]
     * to the legend chips the overlay renders. Factored out of [extractNodes]
     * so the chip selection rules can be unit-tested without standing up an
     * ATF hierarchy (which needs a real `View` graph).
     *
     * Order of chips is intentional and stable: structural state first
     * (`checked` / `unchecked`), then behaviour (`clickable`,
     * `long-clickable`, `scrollable`, `editable`), then disability /
     * descriptive overrides (`disabled`, the verbatim
     * `stateDescription`, the `hint: …` line). Reviewers scan top-to-bottom
     * for what they care about.
     *
     * Heading / selected aren't here: ATF's `ViewHierarchyElement` doesn't
     * expose Compose-side `Modifier.semantics { heading() }` /
     * `selected = true` cleanly enough to gate a chip on.
     */
    internal fun buildStates(
        isCheckable: Boolean,
        isChecked: Boolean,
        isClickable: Boolean,
        isLongClickable: Boolean,
        isScrollable: Boolean,
        isEditable: Boolean,
        isEnabled: Boolean,
        stateDescription: String,
        hintText: String,
    ): List<String> = buildList {
        if (isCheckable) add(if (isChecked) "checked" else "unchecked")
        if (isClickable) add("clickable")
        if (isLongClickable) add("long-clickable")
        if (isScrollable) add("scrollable")
        if (isEditable) add("editable")
        if (!isEnabled) add("disabled")
        if (stateDescription.isNotEmpty()) add(stateDescription)
        if (hintText.isNotEmpty()) add("hint: $hintText")
    }

    /**
     * `true` when [v] is its own TalkBack focus stop — either ATF marks it
     * `isScreenReaderFocusable()` or it has no screen-reader-focusable
     * ancestor (so it's a standalone node, not a child of a merged
     * container). `false` for the inner `Text` of a `Button` whose
     * semantics are merged into the button: TalkBack would announce the
     * button as one stop, so the inner text is "part of" something
     * already shown.
     *
     * The overlay renders unmerged descendants with a dashed border + `↳ `
     * legend prefix so reviewers can see structure without mistaking the
     * extra rows for additional TalkBack stops.
     */
    internal fun isMergedSemanticsRoot(v: ViewHierarchyElement): Boolean {
        if (v.isScreenReaderFocusable) return true
        var p = v.parentView
        while (p != null) {
            if (p.isScreenReaderFocusable) return false
            p = p.parentView
        }
        return true
    }

    /**
     * Strip the package off TalkBack's class name and drop the generic
     * `android.view.View` so the legend doesn't fill up with `View` chips.
     */
    private fun simplifyRole(fqn: String?): String? {
        if (fqn.isNullOrEmpty()) return null
        val short = fqn.substringAfterLast('.')
        if (short.isEmpty() || short == "View" || short == "ViewGroup") return null
        return short
    }

    private val DEBUG = System.getProperty("composeai.a11y.debug") == "true"

    /**
     * Writes the per-preview report. The plugin's `verifyAccessibility` task
     * collects all of these into a single `accessibility.json`; writing
     * per-preview avoids concurrent-writer issues when previews are sharded
     * across JVMs.
     *
     * Always writes the JSON entry, even when both lists are empty (so
     * `accessibility.json` covers every preview). The annotated PNG is
     * generated whenever there's at least one finding OR one ANI node and
     * a screenshot is available — that way the Paparazzi-style "what
     * TalkBack sees" overlay shows up on clean previews too.
     */
    fun writePerPreviewReport(
        outputDir: File,
        previewId: String,
        findings: List<AccessibilityFinding>,
        nodes: List<AccessibilityNode>,
        screenshot: File? = null,
    ) {
        outputDir.mkdirs()
        val hasContent = findings.isNotEmpty() || nodes.isNotEmpty()
        val annotated = if (screenshot != null && hasContent) {
            AccessibilityOverlay.generate(screenshot, findings, nodes)
        } else null
        if (hasContent && annotated == null) {
            // Content present but no overlay landed — log the precondition
            // that wasn't met so the cause is visible in CI logs without
            // having to enable the verbose `composeai.a11y.debug` flag.
            // [AccessibilityOverlay.generate] logs the more specific reason
            // when it gets to run; this branch covers the case where it
            // wasn't called at all (annotation disabled or no screenshot).
            val reason = when {
                screenshot == null -> "screenshot=null (annotation disabled or capture not wired)"
                else -> "AccessibilityOverlay.generate returned null (see preceding stderr)"
            }
            System.err.println(
                "[compose-a11y] $previewId: ${findings.size} finding(s) / " +
                    "${nodes.size} node(s) but no overlay — $reason",
            )
        }

        // Path in the entry is stored relative to the aggregate
        // `accessibility.json` (which lives in the plugin output root next
        // to `previews.json`). That way downstream tools can resolve it the
        // same way they resolve `renderOutput` — by joining with the
        // manifest's parent directory.
        val relative = annotated?.let { file ->
            val root = outputDir.parentFile ?: return@let file.name
            file.relativeTo(root).path
        }

        val entry = AccessibilityEntry(
            previewId = previewId,
            findings = findings,
            nodes = nodes,
            annotatedPath = relative,
        )
        outputDir.resolve("$previewId.json").writeText(
            json.encodeToString(AccessibilityEntry.serializer(), entry),
        )
    }

    private fun AccessibilityHierarchyCheckResult.toFinding(): AccessibilityFinding? {
        // NOT_RUN is noise in output — the check couldn't evaluate this element.
        // We still surface INFO so consumers can see what ATF considered.
        val level = when (type) {
            AccessibilityCheckResultType.ERROR -> "ERROR"
            AccessibilityCheckResultType.WARNING -> "WARNING"
            AccessibilityCheckResultType.INFO -> "INFO"
            else -> return null // NOT_RUN / SUPPRESSED / RESOLVED / null — noise
        }
        val ruleType = sourceCheckClass?.simpleName ?: "UnknownCheck"
        val message = try {
            getMessage(Locale.ENGLISH).toString()
        } catch (e: Throwable) {
            e.message ?: "no message"
        }
        val element = element
        val bounds = element?.boundsInScreen?.let { "${it.left},${it.top},${it.right},${it.bottom}" }
        val viewDesc = element?.let { el ->
            buildString {
                el.className?.let { append(it).append(' ') }
                el.resourceName?.let { append('#').append(it.substringAfterLast('/')) }
                el.contentDescription?.let { append(" desc=\"").append(it).append('"') }
            }.trim().takeIf { it.isNotEmpty() }
        }
        return AccessibilityFinding(
            level = level,
            type = ruleType,
            message = message,
            viewDescription = viewDesc,
            boundsInScreen = bounds,
        )
    }
}
