package ee.schimke.composeai.daemon.config

import java.io.File

/**
 * The one place every `composeai.daemon.*` system property is declared.
 *
 * Before this registry each knob was a bare `System.getProperty("composeai.daemon.…")` at its point
 * of use, with its default and its parsing inline. That made the set an *unversioned public API
 * surface* nobody could enumerate: the names were string literals invisible to the ABI validator,
 * and a rename was a silent behaviour change rather than a compile error.
 *
 * Three things follow from declaring them here instead:
 * - **Enumerable.** [DaemonProperties.ALL] is the complete list, and `docs/daemon/TUNABLES.md` is
 *   generated from it (see `DaemonPropertiesDocTest`) rather than hand-maintained.
 * - **Typed.** Each entry states its type, its default and how a raw string becomes a value, so
 *   `daemon.idleTimeoutMs` parses the same way everywhere it is read.
 * - **Renameable.** Call sites reference `DaemonProperties.Names.…`, so a rename is a compile
 *   error.
 *
 * `DaemonPropertyRegistryTest` pins the invariant that no `"composeai.daemon.…"` literal is left in
 * `daemon/` outside this file.
 *
 * ### Producer-only entries
 *
 * Some entries are written by the Gradle plugin's daemon launch descriptor and read outside
 * `daemon/` (the `bta.*` compile inputs, the descriptor's `maxHeapMb` / `warmSpare` echo, the data
 * connectors' `resDirs` / `defaultLocale`). They are declared here too — the point of the registry
 * is that the *name space* is enumerable, not just the half this module happens to read.
 */
public sealed class DaemonProperty<T>(
  /** The system-property name, e.g. `composeai.daemon.idleTimeoutMs`. */
  public val name: String,
  /** The value used when the property is unset or unparseable. */
  public val defaultValue: T,
  /** One-line description, rendered into the generated tunables table. */
  public val doc: String,
  /** Which area of the daemon this knob belongs to; groups the generated table. */
  public val group: String,
) {
  /** Type name for the generated table (`Boolean`, `Long`, `path list`, …). */
  public abstract val typeLabel: String

  /** Parses a raw property value; `null` (unset) and unparseable input both yield the default. */
  public abstract fun parse(raw: String?): T

  /** Reads and parses this property. [lookup] is a seam for tests. */
  public fun read(lookup: (String) -> String? = System::getProperty): T = parse(lookup(name))

  /** How the default renders in the generated table. */
  public open val defaultLabel: String
    get() = defaultValue?.let { "`$it`" } ?: "unset"

  override fun toString(): String = "$typeLabel $name (default $defaultValue)"
}

/** A free-form string knob. `null` default means "unset is meaningful". */
public class StringProperty(
  name: String,
  default: String? = null,
  doc: String,
  group: String,
) : DaemonProperty<String?>(name, default, doc, group) {
  override val typeLabel: String = "String"

  override fun parse(raw: String?): String? = raw?.takeIf { it.isNotBlank() } ?: defaultValue
}

/** An absolute filesystem path carried as a string. Same parsing as [StringProperty]. */
public class PathProperty(name: String, doc: String, group: String) :
  DaemonProperty<String?>(name, null, doc, group) {
  override val typeLabel: String = "path"

  override fun parse(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
}

/**
 * A `File.pathSeparator`-delimited list of paths. Empty when unset — every consumer of these treats
 * "unset" and "empty" alike.
 */
public class PathListProperty(name: String, doc: String, group: String) :
  DaemonProperty<List<String>>(name, emptyList(), doc, group) {
  override val typeLabel: String = "path list"

  override val defaultLabel: String = "empty"

  override fun parse(raw: String?): List<String> =
    raw?.split(File.pathSeparator)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

/** A comma/semicolon-separated list. Empty when unset. */
public class CsvListProperty(name: String, doc: String, group: String) :
  DaemonProperty<List<String>>(name, emptyList(), doc, group) {
  override val typeLabel: String = "list"

  override val defaultLabel: String = "empty"

  override fun parse(raw: String?): List<String> =
    raw?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

/**
 * A boolean knob. `true` / `false` are matched case-insensitively; anything else — including an
 * unset property — yields [defaultValue], so a typo never silently flips a default.
 */
public class BooleanProperty(name: String, default: Boolean, doc: String, group: String) :
  DaemonProperty<Boolean>(name, default, doc, group) {
  override val typeLabel: String = "Boolean"

  override fun parse(raw: String?): Boolean =
    when {
      raw == null -> defaultValue
      raw.trim().equals("true", ignoreCase = true) -> true
      raw.trim().equals("false", ignoreCase = true) -> false
      else -> defaultValue
    }
}

/** An integer knob, optionally floored at [min] the way the reading site already coerced it. */
public class IntProperty(
  name: String,
  default: Int,
  doc: String,
  group: String,
  private val min: Int? = null,
) : DaemonProperty<Int>(name, default, doc, group) {
  override val typeLabel: String = "Int"

  override fun parse(raw: String?): Int {
    val parsed = raw?.trim()?.toIntOrNull() ?: defaultValue
    return min?.let { parsed.coerceAtLeast(it) } ?: parsed
  }
}

/** A millisecond / byte-count knob, optionally floored at [min]. */
public class LongProperty(
  name: String,
  default: Long,
  doc: String,
  group: String,
  private val min: Long? = null,
) : DaemonProperty<Long>(name, default, doc, group) {
  override val typeLabel: String = "Long"

  override fun parse(raw: String?): Long {
    val parsed = raw?.trim()?.toLongOrNull() ?: defaultValue
    return min?.let { parsed.coerceAtLeast(it) } ?: parsed
  }
}

/**
 * The registry itself. [Names] holds the raw names as `const val` so existing published `const val
 * …_PROP` declarations can delegate to them without changing their ABI (a `const val` initialised
 * from another `const val` still compiles to a `ConstantValue` field); the typed entries below
 * carry the default, the parse and the doc.
 */
public object DaemonProperties {

  /** Group labels — also the section headings of the generated tunables table. */
  private const val G_LIFECYCLE = "Lifecycle and timeouts"
  private const val G_CLASSPATH = "Classpath and discovery"
  private const val G_HISTORY = "History"
  private const val G_SANDBOX = "Sandbox pool"
  private const val G_TRACING = "Tracing and diagnostics"
  private const val G_BUNDLE = "Bundle IR replay"
  private const val G_DATA = "Data products"
  private const val G_DESCRIPTOR = "Launch descriptor (set by the Gradle plugin)"
  private const val G_BTA = "In-process compile (BTA)"

  /**
   * Property names as compile-time constants.
   *
   * Referenced from the published `const val …_PROP` declarations that predate this registry, which
   * must stay `const` to keep their `ConstantValue` ABI.
   */
  public object Names {
    public const val IDLE_TIMEOUT_MS: String = "composeai.daemon.idleTimeoutMs"
    public const val RENDER_TIMEOUT_MS: String = "composeai.daemon.renderTimeoutMs"
    public const val CLASSPATH_DIRTY_GRACE_MS: String = "composeai.daemon.classpathDirtyGraceMs"
    public const val DATA_FETCH_RERENDER_BUDGET_MS: String =
      "composeai.daemon.dataFetchRerenderBudgetMs"
    public const val DISCOVERY_WATCHDOG_MS: String = "composeai.daemon.discoveryWatchdogMs"
    public const val INTERACTIVE_IDLE_LEASE_MS: String = "composeai.daemon.interactive.idleLeaseMs"
    public const val SANDBOX_BOOT_TIMEOUT_MS: String = "composeai.daemon.sandboxBootTimeoutMs"

    public const val USER_CLASS_DIRS: String = "composeai.daemon.userClassDirs"
    public const val USER_CLASS_PACKAGES: String = "composeai.daemon.userClassPackages"
    public const val CHEAP_SIGNAL_FILES: String = "composeai.daemon.cheapSignalFiles"
    public const val PREVIEWS_JSON_PATH: String = "composeai.daemon.previewsJsonPath"

    public const val HISTORY_DIR: String = "composeai.daemon.historyDir"
    public const val WORKSPACE_ROOT: String = "composeai.daemon.workspaceRoot"
    public const val MODULE_ID: String = "composeai.daemon.moduleId"
    public const val GIT_REF_HISTORY: String = "composeai.daemon.gitRefHistory"
    public const val GIT_REF_HISTORY_SYNC_MODE: String = "composeai.daemon.gitRefHistorySyncMode"
    public const val GIT_REF_HISTORY_DEBOUNCE_MS: String =
      "composeai.daemon.gitRefHistoryDebounceMs"
    public const val GIT_REF_HISTORY_PUBLISH_POLICY: String =
      "composeai.daemon.gitRefHistoryPublishPolicy"
    public const val HISTORY_MAX_ENTRIES_PER_PREVIEW: String =
      "composeai.daemon.history.maxEntriesPerPreview"
    public const val HISTORY_MAX_AGE_DAYS: String = "composeai.daemon.history.maxAgeDays"
    public const val HISTORY_MAX_TOTAL_SIZE_BYTES: String =
      "composeai.daemon.history.maxTotalSizeBytes"
    public const val HISTORY_AUTO_PRUNE_INTERVAL_MS: String =
      "composeai.daemon.history.autoPruneIntervalMs"

    public const val SANDBOX_COUNT: String = "composeai.daemon.sandboxCount"
    public const val WARM_SPARE: String = "composeai.daemon.warmSpare"
    public const val BACKGROUND_SANDBOX_BOOT: String = "composeai.daemon.backgroundSandboxBoot"
    public const val WARM_RENDER_ON_BOOT: String = "composeai.daemon.warmRenderOnBoot"
    public const val USE_CONSUMER_APPLICATION: String = "composeai.daemon.useConsumerApplication"
    public const val MAX_RENDERS_PER_SANDBOX: String = "composeai.daemon.maxRendersPerSandbox"
    public const val MAX_HEAP_MB: String = "composeai.daemon.maxHeapMb"
    public const val SANDBOX_WORKER_PORT: String = "composeai.daemon.sandboxWorker.port"
    public const val SANDBOX_WORKER_SLOT: String = "composeai.daemon.sandboxWorker.slot"

    public const val STARTUP_QUIET: String = "composeai.daemon.startupQuiet"
    public const val ATRACE: String = "composeai.daemon.atrace"
    public const val PERFETTO_TRACE: String = "composeai.daemon.perfettoTrace"
    public const val COMPOSITION_TRACE: String = "composeai.daemon.compositionTrace"
    public const val RECORDINGS_DIR: String = "composeai.daemon.recordingsDir"

    public const val BUNDLE_MANIFEST_PATH: String = "composeai.daemon.bundleManifestPath"
    public const val IR_DIR: String = "composeai.daemon.irDir"

    public const val RES_DIRS: String = "composeai.daemon.resDirs"
    public const val DEFAULT_LOCALE: String = "composeai.daemon.defaultLocale"

    public const val PROTOCOL_VERSION: String = "composeai.daemon.protocolVersion"
    public const val MODULE_PATH: String = "composeai.daemon.modulePath"
    public const val MODULE_PROJECT_DIR: String = "composeai.daemon.moduleProjectDir"

    public const val BTA_IMPL_CLASSPATH: String = "composeai.daemon.bta.implClasspath"
    public const val BTA_COMPILE_CLASSPATH: String = "composeai.daemon.bta.compileClasspath"
    public const val BTA_COMPILER_PLUGINS: String = "composeai.daemon.bta.compilerPlugins"
    public const val BTA_MODULE_NAME: String = "composeai.daemon.bta.moduleName"
    public const val BTA_OUTPUT_DIR: String = "composeai.daemon.bta.outputDir"
    public const val BTA_IC_WORKING_DIR: String = "composeai.daemon.bta.icWorkingDir"
    public const val BTA_INELIGIBILITY_REASON: String = "composeai.daemon.bta.ineligibilityReason"
  }

  // ---- Lifecycle and timeouts ---------------------------------------------------------------

  public val idleTimeoutMs: LongProperty =
    LongProperty(
      Names.IDLE_TIMEOUT_MS,
      5_000L,
      "How long the daemon waits after its last client disconnects before exiting.",
      G_LIFECYCLE,
    )

  public val renderTimeoutMs: LongProperty =
    LongProperty(
      Names.RENDER_TIMEOUT_MS,
      5 * 60_000L,
      "Initial per-render `host.submit` timeout, before `initialize.options.maxRenderMs` lands. " +
        "Non-positive values keep the default.",
      G_LIFECYCLE,
    )

  public val classpathDirtyGraceMs: LongProperty =
    LongProperty(
      Names.CLASSPATH_DIRTY_GRACE_MS,
      2_000L,
      "Grace window after a classpath-dirty signal before the daemon acts on it. " +
        "See PROTOCOL.md § 6.",
      G_LIFECYCLE,
    )

  public val dataFetchRerenderBudgetMs: LongProperty =
    LongProperty(
      Names.DATA_FETCH_RERENDER_BUDGET_MS,
      30_000L,
      "Budget for the re-render a `data/fetch` may trigger. See DATA-PRODUCTS.md § Re-render " +
        "semantics.",
      G_LIFECYCLE,
    )

  public val discoveryWatchdogMs: LongProperty =
    LongProperty(
      Names.DISCOVERY_WATCHDOG_MS,
      1_500L,
      "Window between `fileChanged({kind:source})` and the deferred discovery scan.",
      G_LIFECYCLE,
    )

  public val interactiveIdleLeaseMs: LongProperty =
    LongProperty(
      Names.INTERACTIVE_IDLE_LEASE_MS,
      60_000L,
      "Idle lease before a held interactive session auto-closes.",
      G_LIFECYCLE,
    )

  public val sandboxBootTimeoutMs: LongProperty =
    LongProperty(
      Names.SANDBOX_BOOT_TIMEOUT_MS,
      10L * 60L * 1000L,
      "Deadline for a Robolectric sandbox bootstrap. The default covers a cold " +
        "`android-all-instrumented` download; warm boots are 5–15s.",
      G_LIFECYCLE,
    )

  // ---- Classpath and discovery --------------------------------------------------------------

  public val userClassDirs: PathListProperty =
    PathListProperty(
      Names.USER_CLASS_DIRS,
      "`File.pathSeparator`-delimited user-class directories the child-first loader serves.",
      G_CLASSPATH,
    )

  public val userClassPackages: PathListProperty =
    PathListProperty(
      Names.USER_CLASS_PACKAGES,
      "Packages excluded from Robolectric instrumentation and served from the user classloader.",
      G_CLASSPATH,
    )

  public val cheapSignalFiles: PathListProperty =
    PathListProperty(
      Names.CHEAP_SIGNAL_FILES,
      "Files whose mtime/size form the cheap classpath-dirty signal.",
      G_CLASSPATH,
    )

  public val previewsJsonPath: PathProperty =
    PathProperty(
      Names.PREVIEWS_JSON_PATH,
      "Absolute path to the `previews.json` manifest. Unset boots with an empty preview index.",
      G_CLASSPATH,
    )

  // ---- History ------------------------------------------------------------------------------

  public val historyDir: PathProperty =
    PathProperty(
      Names.HISTORY_DIR,
      "Render-history archive directory. Unset disables history entirely.",
      G_HISTORY,
    )

  public val workspaceRoot: PathProperty =
    PathProperty(
      Names.WORKSPACE_ROOT,
      "Repository root used for git provenance. Defaults to the daemon JVM's working directory.",
      G_HISTORY,
    )

  public val moduleId: StringProperty =
    StringProperty(
      Names.MODULE_ID,
      null,
      "Gradle project path stamped into every history entry's `module` field.",
      G_HISTORY,
    )

  public val gitRefHistory: CsvListProperty =
    CsvListProperty(
      Names.GIT_REF_HISTORY,
      "Reporting refs wired as read-only history sources, e.g. `refs/heads/preview/main`.",
      G_HISTORY,
    )

  public val gitRefHistorySyncMode: StringProperty =
    StringProperty(
      Names.GIT_REF_HISTORY_SYNC_MODE,
      "READ_ONLY",
      "Sync mode for the reporting refs: `READ_ONLY`, `WRITE_LOCAL` or `WRITE_PUSH`.",
      G_HISTORY,
    )

  public val gitRefHistoryDebounceMs: LongProperty =
    LongProperty(
      Names.GIT_REF_HISTORY_DEBOUNCE_MS,
      1_000L,
      "Debounce window coalescing a render burst into one reporting-branch commit. `0` disables.",
      G_HISTORY,
      min = 0L,
    )

  public val gitRefHistoryPublishPolicy: StringProperty =
    StringProperty(
      Names.GIT_REF_HISTORY_PUBLISH_POLICY,
      "cleanOnBranch",
      "Which renders reach the reporting branch: `all`, or `cleanOnBranch` (the default).",
      G_HISTORY,
    )

  public val historyMaxEntriesPerPreview: IntProperty =
    IntProperty(
      Names.HISTORY_MAX_ENTRIES_PER_PREVIEW,
      50,
      "Prune knob — entries retained per preview. `0` or negative disables this knob.",
      G_HISTORY,
    )

  public val historyMaxAgeDays: IntProperty =
    IntProperty(
      Names.HISTORY_MAX_AGE_DAYS,
      14,
      "Prune knob — maximum entry age in days. `0` or negative disables this knob.",
      G_HISTORY,
    )

  public val historyMaxTotalSizeBytes: LongProperty =
    LongProperty(
      Names.HISTORY_MAX_TOTAL_SIZE_BYTES,
      500_000_000L,
      "Prune knob — total archive size ceiling in bytes. `0` or negative disables this knob.",
      G_HISTORY,
    )

  public val historyAutoPruneIntervalMs: LongProperty =
    LongProperty(
      Names.HISTORY_AUTO_PRUNE_INTERVAL_MS,
      60L * 60L * 1000L,
      "Prune knob — auto-prune scheduler period. `0` or negative disables the scheduler.",
      G_HISTORY,
    )

  // ---- Sandbox pool -------------------------------------------------------------------------

  public val sandboxCount: IntProperty =
    IntProperty(
      Names.SANDBOX_COUNT,
      1,
      "In-JVM Robolectric sandbox slots. Set by the supervisor to `1 + replicasPerDaemon`. The " +
        "Android daemon main derives a larger default (5) when `warmSpare` is on; this default " +
        "applies to every other reader.",
      G_SANDBOX,
      min = 1,
    )

  public val warmSpare: BooleanProperty =
    BooleanProperty(
      Names.WARM_SPARE,
      true,
      "Whether the pool keeps a warm spare sandbox so recycle is an atomic swap.",
      G_SANDBOX,
    )

  public val backgroundSandboxBoot: BooleanProperty =
    BooleanProperty(
      Names.BACKGROUND_SANDBOX_BOOT,
      false,
      "Whether `initialize` may return once the first sandbox is ready, the rest booting behind " +
        "it. The Gradle-plugin descriptor sets this `true`; the raw sysprop default is off.",
      G_SANDBOX,
    )

  public val warmRenderOnBoot: BooleanProperty =
    BooleanProperty(
      Names.WARM_RENDER_ON_BOOT,
      true,
      "Whether each background-booted slot performs a boot-time warm render.",
      G_SANDBOX,
    )

  public val useConsumerApplication: BooleanProperty =
    BooleanProperty(
      Names.USE_CONSUMER_APPLICATION,
      false,
      "Whether Robolectric instantiates the consumer manifest's `Application` instead of the " +
        "pinned `android.app.Application` stub.",
      G_SANDBOX,
    )

  public val maxRendersPerSandbox: IntProperty =
    IntProperty(
      Names.MAX_RENDERS_PER_SANDBOX,
      1_000,
      "Renders a sandbox handles before it is recycled regardless of drift signals.",
      G_SANDBOX,
      min = 1,
    )

  public val maxHeapMb: IntProperty =
    IntProperty(
      Names.MAX_HEAP_MB,
      1_024,
      "Post-GC heap ceiling for the daemon JVM, in MiB. Also becomes `-Xmx`.",
      G_SANDBOX,
    )

  public val sandboxWorkerPort: IntProperty =
    IntProperty(
      Names.SANDBOX_WORKER_PORT,
      0,
      "Loopback port a spawned sandbox worker connects back on. Set by `SandboxProcessPool`; " +
        "`SandboxWorkerMain` fails fast when unset.",
      G_SANDBOX,
    )

  public val sandboxWorkerSlot: IntProperty =
    IntProperty(
      Names.SANDBOX_WORKER_SLOT,
      0,
      "Pool slot index a spawned sandbox worker owns. Set by `SandboxProcessPool`.",
      G_SANDBOX,
    )

  // ---- Tracing and diagnostics --------------------------------------------------------------

  public val startupQuiet: BooleanProperty =
    BooleanProperty(
      Names.STARTUP_QUIET,
      false,
      "Suppress startup-timing marks on stderr. Marks are still buffered for the summary.",
      G_TRACING,
    )

  public val atrace: BooleanProperty =
    BooleanProperty(
      Names.ATRACE,
      false,
      "Mirror render trace sections into `android.os.Trace`.",
      G_TRACING,
    )

  public val perfettoTrace: BooleanProperty =
    BooleanProperty(
      Names.PERFETTO_TRACE,
      false,
      "Emit a Perfetto trace for each render. Set from the Gradle plugin's daemon DSL.",
      G_TRACING,
    )

  public val compositionTrace: BooleanProperty =
    BooleanProperty(
      Names.COMPOSITION_TRACE,
      false,
      "Enable Compose composition tracing in the render extension.",
      G_TRACING,
    )

  public val recordingsDir: PathProperty =
    PathProperty(
      Names.RECORDINGS_DIR,
      "Where interactive recordings are written. Unset falls back to a sibling of the render " +
        "output directory.",
      G_TRACING,
    )

  // ---- Bundle IR replay ---------------------------------------------------------------------

  public val bundleManifestPath: PathProperty =
    PathProperty(
      Names.BUNDLE_MANIFEST_PATH,
      "Portable-bundle manifest backing IR replay. Both this and `irDir` must be set.",
      G_BUNDLE,
    )

  public val irDir: PathProperty =
    PathProperty(Names.IR_DIR, "Directory holding the bundle's serialised preview IR.", G_BUNDLE)

  // ---- Data products ------------------------------------------------------------------------

  public val resDirs: PathListProperty =
    PathListProperty(
      Names.RES_DIRS,
      "Android resource directories the resource / i18n data products read.",
      G_DATA,
    )

  public val defaultLocale: StringProperty =
    StringProperty(
      Names.DEFAULT_LOCALE,
      null,
      "Locale treated as the source language by the i18n translations data product.",
      G_DATA,
    )

  // ---- Launch descriptor --------------------------------------------------------------------

  public val protocolVersion: StringProperty =
    StringProperty(
      Names.PROTOCOL_VERSION,
      null,
      "Daemon protocol version stamped by the Gradle plugin's launch descriptor.",
      G_DESCRIPTOR,
    )

  public val modulePath: StringProperty =
    StringProperty(
      Names.MODULE_PATH,
      null,
      "Gradle project path of the module the daemon serves.",
      G_DESCRIPTOR,
    )

  public val moduleProjectDir: PathProperty =
    PathProperty(
      Names.MODULE_PROJECT_DIR,
      "Absolute project directory of the module the daemon serves.",
      G_DESCRIPTOR,
    )

  // ---- In-process compile (BTA) -------------------------------------------------------------

  public val btaImplClasspath: PathListProperty =
    PathListProperty(
      Names.BTA_IMPL_CLASSPATH,
      "Build Tools API implementation JARs loaded into BTA's isolated classloader.",
      G_BTA,
    )

  public val btaCompileClasspath: PathListProperty =
    PathListProperty(Names.BTA_COMPILE_CLASSPATH, "Compile classpath for stage-2 compiles.", G_BTA)

  public val btaCompilerPlugins: PathListProperty =
    PathListProperty(
      Names.BTA_COMPILER_PLUGINS,
      "Compiler-plugin JARs (Compose, serialization, …) passed to the in-process compile.",
      G_BTA,
    )

  public val btaModuleName: StringProperty =
    StringProperty(Names.BTA_MODULE_NAME, null, "Kotlin module name for stage-2 compiles.", G_BTA)

  public val btaOutputDir: PathProperty =
    PathProperty(Names.BTA_OUTPUT_DIR, "Class output directory for stage-2 compiles.", G_BTA)

  public val btaIcWorkingDir: PathProperty =
    PathProperty(
      Names.BTA_IC_WORKING_DIR,
      "Incremental-compilation working directory for stage-2 compiles.",
      G_BTA,
    )

  public val btaIneligibilityReason: StringProperty =
    StringProperty(
      Names.BTA_INELIGIBILITY_REASON,
      null,
      "Why the Gradle plugin ruled this module out of in-process compile; surfaced in diagnostics.",
      G_BTA,
    )

  /**
   * Every declared property, in the order the generated table renders them.
   *
   * `DaemonPropertyRegistryTest` asserts this is complete against the `composeai.daemon.*` literals
   * in the tree, and that the names are unique.
   */
  public val ALL: List<DaemonProperty<*>> =
    listOf(
      idleTimeoutMs,
      renderTimeoutMs,
      classpathDirtyGraceMs,
      dataFetchRerenderBudgetMs,
      discoveryWatchdogMs,
      interactiveIdleLeaseMs,
      sandboxBootTimeoutMs,
      userClassDirs,
      userClassPackages,
      cheapSignalFiles,
      previewsJsonPath,
      historyDir,
      workspaceRoot,
      moduleId,
      gitRefHistory,
      gitRefHistorySyncMode,
      gitRefHistoryDebounceMs,
      gitRefHistoryPublishPolicy,
      historyMaxEntriesPerPreview,
      historyMaxAgeDays,
      historyMaxTotalSizeBytes,
      historyAutoPruneIntervalMs,
      sandboxCount,
      warmSpare,
      backgroundSandboxBoot,
      warmRenderOnBoot,
      useConsumerApplication,
      maxRendersPerSandbox,
      maxHeapMb,
      sandboxWorkerPort,
      sandboxWorkerSlot,
      startupQuiet,
      atrace,
      perfettoTrace,
      compositionTrace,
      recordingsDir,
      bundleManifestPath,
      irDir,
      resDirs,
      defaultLocale,
      protocolVersion,
      modulePath,
      moduleProjectDir,
      btaImplClasspath,
      btaCompileClasspath,
      btaCompilerPlugins,
      btaModuleName,
      btaOutputDir,
      btaIcWorkingDir,
      btaIneligibilityReason,
    )

  /** [ALL] keyed by property name. */
  public val BY_NAME: Map<String, DaemonProperty<*>> = ALL.associateBy { it.name }

  /** Section order of the generated table. */
  public val GROUPS: List<String> =
    listOf(
      G_LIFECYCLE,
      G_CLASSPATH,
      G_HISTORY,
      G_SANDBOX,
      G_TRACING,
      G_BUNDLE,
      G_DATA,
      G_DESCRIPTOR,
      G_BTA,
    )

  /**
   * Renders the tunables table written to `docs/daemon/TUNABLES.md`. Kept next to the declarations
   * so a new property shows up in the docs the moment it is declared — `DaemonPropertiesDocTest`
   * fails the build when the checked-in file drifts.
   */
  public fun renderMarkdown(): String = buildString {
    appendLine("<!--")
    appendLine("  GENERATED FILE — do not edit by hand.")
    appendLine()
    appendLine("  Source of truth: DaemonProperties in")
    appendLine(
      "  daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/config/DaemonProperties.kt"
    )
    appendLine(
      "  Regenerate:      ./gradlew :daemon:core:test --tests '*DaemonPropertiesDocTest*' " +
        "-Pcomposeai.docs.regenerate=true"
    )
    appendLine("-->")
    appendLine()
    appendLine("# Preview daemon — tunables")
    appendLine()
    appendLine(
      "Every `composeai.daemon.*` system property the daemon and its launch descriptor understand,"
    )
    appendLine(
      "generated from the typed registry so the list cannot drift from the code that reads it."
    )
    appendLine()
    appendLine(
      "Pass one with `-D<name>=<value>` on the daemon JVM. The Gradle-facing knobs " +
        "(`maxHeapMb`, `warmSpare`,"
    )
    appendLine(
      "`maxRendersPerSandbox`, `backgroundSandboxBoot`) are normally set through the " +
        "`composePreview.daemon { … }`"
    )
    appendLine("DSL rather than by hand — see [CONFIG.md](CONFIG.md).")
    appendLine()
    appendLine(
      "\"Default\" is the value used when the property is unset **or** unparseable: a typo never " +
        "silently"
    )
    appendLine("flips a knob.")
    for (group in GROUPS) {
      val entries = ALL.filter { it.group == group }
      if (entries.isEmpty()) continue
      appendLine()
      appendLine("## $group")
      appendLine()
      appendLine("| Property | Type | Default | Effect |")
      appendLine("| --- | --- | --- | --- |")
      for (property in entries) {
        appendLine(
          "| `${property.name}` | ${property.typeLabel} | ${property.defaultLabel} | ${property.doc} |"
        )
      }
    }
  }
}
