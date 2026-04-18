package ee.schimke.composeai.renderer

import android.os.Build
import android.view.View
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
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

    fun check(previewId: String, root: View): List<AccessibilityFinding> {
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
            return allResults.mapNotNull { it.toFinding() }
        } finally {
            if (needsSwap) {
                ShadowBuild.setFingerprint(originalFingerprint)
            }
        }
    }

    private val DEBUG = System.getProperty("composeai.a11y.debug") == "true"

    /**
     * Writes the per-preview report. The plugin's `verifyAccessibility` task
     * collects all of these into a single `accessibility.json`; writing
     * per-preview avoids concurrent-writer issues when previews are sharded
     * across JVMs.
     */
    fun writePerPreviewReport(outputDir: File, previewId: String, findings: List<AccessibilityFinding>) {
        outputDir.mkdirs()
        val entry = AccessibilityEntry(previewId = previewId, findings = findings)
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
