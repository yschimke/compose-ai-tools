package ee.schimke.composeai.plugin

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class PreviewExtension @Inject constructor(private val objects: ObjectFactory) {
    val variant: Property<String> = objects.property(String::class.java).convention("debug")
    val sdkVersion: Property<Int> = objects.property(Int::class.java).convention(35)
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * When true, each `renderAllPreviews` run archives every rendered PNG whose
     * content differs from the most recent entry into [historyDir]. Default: false.
     */
    val historyEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Root directory for preview history snapshots. Each preview gets a
     * subfolder named after its id; inside, filenames are timestamps.
     * Lives outside `build/` by default so `./gradlew clean` doesn't wipe it.
     */
    val historyDir: DirectoryProperty = objects.directoryProperty()

    /**
     * Number of parallel JVM forks used to render previews. Default 1 (no sharding).
     *
     * Special values:
     *  - `1` (default): no sharding; a single JVM renders every preview.
     *  - `0`: auto — the plugin picks a shard count based on the discovered
     *    preview count and available CPU cores, using [ShardTuning]'s cost
     *    model. Falls back to 1 if previews.json hasn't been generated yet.
     *  - `≥2`: explicit shard count.
     *
     * Each shard runs a generated `RobolectricRenderTest_ShardN` subclass with its
     * own slice of the manifest (round-robin partition). Within a shard, the
     * Robolectric sandbox is reused across that shard's previews; across shards
     * each JVM pays its own ~3–4s cold-start cost, so sharding is a net win only
     * when the module has enough previews to amortise that overhead.
     */
    val shards: Property<Int> = objects.property(Int::class.java).convention(1)

    /**
     * ATF (Accessibility Test Framework) checks, run against each rendered
     * preview. Off by default — turning it on surfaces findings in the CLI,
     * VSCode diagnostics, and `accessibility.json`, but does NOT break the
     * build. Set `failOnErrors = true` / `failOnWarnings = true` to gate
     * builds on findings.
     *
     *     composePreview {
     *         accessibilityChecks {
     *             enabled = true
     *             failOnErrors = true  // opt in to build gating
     *         }
     *     }
     */
    val accessibilityChecks: AccessibilityChecksExtension =
        objects.newInstance(AccessibilityChecksExtension::class.java)

    fun accessibilityChecks(action: Action<AccessibilityChecksExtension>) {
        action.execute(accessibilityChecks)
    }
}

abstract class AccessibilityChecksExtension @Inject constructor(objects: ObjectFactory) {
    /** Default: `false`. Enabling turns on ATF checks and the `verifyAccessibility` task. */
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Fail the build if any ERROR-level ATF finding is reported. Default:
     * `false` — findings are reported (logged, written to the JSON report,
     * surfaced as CLI/VSCode diagnostics) but do not fail the build unless
     * the consumer explicitly opts in. That way turning on `enabled` is a
     * safe, purely additive change.
     */
    val failOnErrors: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Fail the build if any WARNING-level ATF finding is reported. Default: `false` (same rationale as [failOnErrors]). */
    val failOnWarnings: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
