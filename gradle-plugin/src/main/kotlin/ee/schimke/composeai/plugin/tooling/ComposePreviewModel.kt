package ee.schimke.composeai.plugin.tooling

/**
 * Tooling API model exposing plugin state to the CLI, VS Code and agents. One model that grows with
 * the plugin.
 *
 * Use only [Map] / [List] / [String] / [Boolean] return types (Tooling-API marshalable), and keep
 * in lockstep with the CLI-side copy at the same FQN: the reflective proxies silently return `null`
 * on drift. Retrieved with `connection.model(ComposePreviewModel::class.java).get()` from any
 * project.
 */
interface ComposePreviewModel {
  /** Plugin version recorded at build time. */
  val pluginVersion: String

  /**
   * Every project with the plugin applied, keyed by Gradle path (`:app`). Empty prompts the CLI's
   * "apply the plugin" remediation.
   */
  val modules: Map<String, ModuleInfo>
}

/** Per-module state; the extension point for new getters (update the CLI copy in lockstep). */
interface ModuleInfo {
  /** The configured variant, typically `"debug"`. */
  val variant: String

  /**
   * Resolved `${variant}RuntimeClasspath` (`group:name` → version), which drives AGP's merged
   * resource APK. Empty when unresolvable.
   */
  val mainRuntimeDependencies: Map<String, String>

  /** Resolved `${variant}UnitTestRuntimeClasspath`, what the renderer runs against. */
  val testRuntimeDependencies: Map<String, String>

  /**
   * Compat findings against the maps above (see `docs/RENDERER_COMPATIBILITY.md`), shared by the
   * CLI and VS Code.
   */
  val findings: List<ModuleFinding>

  /** AGP version, or `null`; for bug triage. */
  val agpVersion: String?

  /** KGP version, or `null`. */
  val kotlinVersion: String?

  /**
   * Snapshot of the render Test task's JVM setup (launcher, classpaths, copied JVM args), or `null`
   * when not registered; makes JDK-fallback bugs like #142 visible.
   */
  val renderPreviewsTask: RenderPreviewsTaskInfo?
}

/** Taken at model-build time, reflecting what Gradle would use if the task ran now. */
interface RenderPreviewsTaskInfo {
  /**
   * Whether a toolchain launcher is explicitly wired; `false` means fallback to the daemon JVM or
   * even PATH `java` (#142).
   */
  val javaLauncherPinned: Boolean

  /** Effective Java major version the test worker will fork with. */
  val javaLauncherVersion: String?

  /** Effective JVM vendor (e.g. "Temurin", "Google Inc.") — useful for triage. */
  val javaLauncherVendor: String?

  /** Effective `java.home` the forked worker will use. */
  val javaLauncherPath: String?

  /** Number of entries on the task's `classpath`. */
  val classpathSize: Int

  /**
   * `bootstrapClasspath` size: AGP's unit-test task injects `mockable-android.jar`,
   * `composePreviewRender` doesn't.
   */
  val bootstrapClasspathSize: Int

  /** JVM args applied to the forked worker (post-`jvmArgs(...)` copies). */
  val jvmArgs: List<String>
}

/**
 * One compat-check result, as `compose-preview doctor` prints it and VS Code's Problems view
 * consumes it. Must match the CLI-side copy method for method.
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
