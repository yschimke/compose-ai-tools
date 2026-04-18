package ee.schimke.composeai.plugin.tooling

/**
 * Tooling API model exposing plugin-side state to external drivers — CLI
 * (`compose-preview doctor`, future commands), VS Code extension, agents.
 *
 * The goal is ONE model that grows alongside the plugin, rather than a new
 * model per feature. Keep return types to [Map] / [List] / [String] /
 * [Boolean] (what Gradle's Tooling API can marshal across the daemon
 * boundary), and keep interfaces in lockstep with the CLI-side copy at the
 * same FQN (`ee.schimke.composeai.plugin.tooling.ComposePreviewModel`).
 * Tooling API bridges them with reflective proxies — method signatures
 * drifting between sides silently produces `null`s.
 *
 * Registered once per build by
 * [ee.schimke.composeai.plugin.ComposePreviewPlugin.apply]. Retrieved from
 * the CLI with `connection.model(ComposePreviewModel::class.java).get()` —
 * works against any project in the build (the builder always returns the
 * build-wide snapshot).
 */
interface ComposePreviewModel {
    /**
     * Plugin version recorded at build time. Useful when the CLI needs to
     * report the plugin version without parsing the consumer's build files.
     */
    val pluginVersion: String

    /**
     * Every project where the compose-preview plugin was applied. Key is the
     * Gradle project path (starts with `:`, e.g. `:app`, `:sample-wear`).
     * Empty if no project applied the plugin — the CLI treats that case
     * specially (it surfaces an actionable "apply the plugin" remediation).
     */
    val modules: Map<String, ModuleInfo>
}

/**
 * Per-module state. Starts narrow — just dependency versions for doctor's
 * compat checks — but this interface is the extension point: add getters
 * here (e.g. discovered-preview counts, source roots, enabled features) as
 * the CLI grows. Any addition needs the CLI-side copy updated in lockstep.
 */
interface ModuleInfo {
    /**
     * The plugin's configured variant — typically `"debug"`. The CLI uses
     * this to phrase remediation hints and to pick the right configuration
     * names when resolving deps.
     */
    val variant: String

    /**
     * Resolved dependencies for the module's `${variant}RuntimeClasspath`
     * configuration (main app runtime — drives AGP's merged resource APK).
     * Map key is `group:name`; value is Gradle's resolved version string.
     *
     * Empty if the configuration didn't exist or didn't resolve — doctor
     * treats that as "not checkable" rather than erroring.
     */
    val mainRuntimeDependencies: Map<String, String>

    /**
     * Resolved dependencies for `${variant}UnitTestRuntimeClasspath` — what
     * the preview renderer actually runs against. Same shape as
     * [mainRuntimeDependencies].
     */
    val testRuntimeDependencies: Map<String, String>

    /**
     * Compat-check findings produced by the plugin's rules against the
     * dependency maps above. Each entry matches one of the known AAR/R.id
     * mismatches from `docs/RENDERER_COMPATIBILITY.md`. Rules live plugin-
     * side so the CLI (`compose-preview doctor`) and the VS Code extension
     * both consume the same list — no per-tool drift.
     */
    val findings: List<ModuleFinding>
}

/**
 * One compat-check result. Matches the shape the CLI prints under
 * `compose-preview doctor` and the shape the VS Code extension consumes for
 * the Problems view.
 *
 * Getters only return Tooling-API-marshalable types; the CLI-side duplicate
 * at `cli/src/main/kotlin/ee/schimke/composeai/plugin/tooling/ComposePreviewModel.kt`
 * must track these method-for-method.
 */
interface ModuleFinding {
    /** Stable dotted id, e.g. `ui-test-manifest-missing`. */
    val id: String

    /** `"error"` | `"warning"` | `"info"`. */
    val severity: String

    /** Single-line human-readable summary. */
    val message: String

    /** Longer rationale (optional). */
    val detail: String?

    /** One-line action the user can take (optional). */
    val remediationSummary: String?

    /** Concrete commands / snippets that implement the action. */
    val remediationCommands: List<String>

    /** Deep-link to project documentation (optional). */
    val docsUrl: String?
}
