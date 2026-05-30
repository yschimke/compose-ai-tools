package ee.schimke.composeai.plugin.tooling

/**
 * Driver-side copy of the plugin's Tooling API model. MUST stay at the same fully-qualified name
 * and method signatures as the plugin-side interface — Gradle's Tooling API uses reflective proxies
 * to bridge the two across the daemon boundary, so drift silently produces `null` returns. See the
 * plugin-side source for the canonical docstrings:
 * `gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/tooling/ComposePreviewModel.kt`.
 *
 * Lives in `:gradle-preview-driver` (not `:cli`) so the driver's
 * [ee.schimke.composeai.cli .DiscoverPreviewModulesAction] can reference it directly; `:cli` keeps
 * seeing it through its transitive `api` dependency on this module, so existing
 * `ee.schimke.composeai.plugin.tooling` imports in `:cli` resolve unchanged.
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
  val agpVersion: String?
  val kotlinVersion: String?
  val renderPreviewsTask: RenderPreviewsTaskInfo?
}

interface RenderPreviewsTaskInfo {
  val javaLauncherPinned: Boolean
  val javaLauncherVersion: String?
  val javaLauncherVendor: String?
  val javaLauncherPath: String?
  val classpathSize: Int
  val bootstrapClasspathSize: Int
  val jvmArgs: List<String>
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
