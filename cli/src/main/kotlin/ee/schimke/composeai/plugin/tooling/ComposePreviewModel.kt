package ee.schimke.composeai.plugin.tooling

/**
 * CLI-side copy of the plugin's Tooling API model. MUST stay at the same
 * fully-qualified name and method signatures as the plugin-side interface —
 * Gradle's Tooling API uses reflective proxies to bridge the two across the
 * daemon boundary, so drift silently produces `null` returns. See the
 * plugin-side source for the canonical docstrings:
 * `gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/tooling/ComposePreviewModel.kt`.
 */
interface ComposePreviewModel {
    val pluginVersion: String
    val modules: Map<String, ModuleInfo>
}

interface ModuleInfo {
    val variant: String
    val mainRuntimeDependencies: Map<String, String>
    val testRuntimeDependencies: Map<String, String>
    val findings: List<ModuleFinding>
}

interface ModuleFinding {
    val id: String
    val severity: String
    val message: String
    val detail: String?
    val remediationSummary: String?
    val remediationCommands: List<String>
    val docsUrl: String?
}
