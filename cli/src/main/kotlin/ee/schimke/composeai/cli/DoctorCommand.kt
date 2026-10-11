package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.plugin.tooling.ModuleInfo
import ee.schimke.composeai.previewdriver.GradleAccessFailure
import ee.schimke.composeai.previewdriver.GradleConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `compose-preview doctor`.
 *
 * **Environment** checks (always run, safe outside a Gradle project): Java 17+, OS, HEAD probes of
 * the Google hosts the Android / downloadable-font render paths need (warnings only; skip with
 * `COMPOSE_PREVIEW_DOCTOR_SKIP_NETWORK=1`), which `compose-preview-server` `serve` would exec
 * (never fetched here), and `env.desktop-natives` for CMP Desktop projects ([DesktopNativesCheck]).
 *
 * **Project** checks (when a `settings.gradle[.kts]` is found): plugin applied, plus dependency
 * alignment rules between the test-runtime and main classpaths (`deps.<module>.*`).
 *
 * Output: ANSI by default; `--json` for a [DoctorReport] (`compose-preview-doctor/v1`, preferred by
 * agents); `--explain` for extended rationale. `--daemon` also smoke-tests each module's daemon.
 * Exits 0 when there are no errors, 1 otherwise.
 */
class DoctorCommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val jsonOut = "--json" in args
  private val reportOut = "--report" in args
  private val explain = "--explain" in args
  private val verbose = "--verbose" in args || "-v" in args
  private val projectDirArg = args.flagValue("--project")

  /**
   * `--timeout <seconds>` for the discovery model query, as [Command.timeoutSeconds]; cold
   * configuration of a large build can take minutes.
   */
  private val timeoutSeconds: Long =
    args.flagValue("--timeout")?.toLongOrNull() ?: GradleConnection.DEFAULT_TIMEOUT_SECONDS

  /**
   * Opt-in daemon spawn + `initialize` smoke test per module (slow). `--with-daemon` is an alias.
   */
  private val checkDaemon = "--daemon" in args || "--with-daemon" in args

  /**
   * Version suggested in remediation messages: `--plugin-version`, else the project's pin (once
   * [checkVersionPin] reads it), else the CLI's Maven-line default. Distinct from
   * [appliedPluginVersion], which is what the classpath actually has.
   */
  // Not [BUNDLE_VERSION]: this is pasted into builds, so it must be resolvable.
  private var recommendedPluginVersion = args.flagValue("--plugin-version") ?: MAVEN_LINE_VERSION

  /**
   * `--variant <name>`, forwarded as `-PcomposePreview.variant=<name>` like
   * [BaseCommand.variantOverride].
   */
  private val variantOverride: String? =
    args.flagValue("--variant")?.trim()?.takeIf { it.isNotEmpty() }

  private fun variantGradleArgs(): List<String> {
    val v = variantOverride ?: return emptyList()
    return listOf("-PcomposePreview.variant=$v")
  }

  /**
   * Plugin version actually applied, from the Tooling model; null when no project or plugin was
   * found.
   */
  private var appliedPluginVersion: String? = null

  /** Plugin version to use in headers and the JSON `pluginVersion` field — applied if known. */
  private val reportPluginVersion: String
    get() = appliedPluginVersion ?: recommendedPluginVersion

  private val checks = mutableListOf<DoctorCheck>()

  /**
   * Claude Code cloud sandbox detection (same signal as `scripts/install.sh`); tailors network
   * remediations and adds an `env.claude-cloud` check.
   */
  private val inClaudeCloud: Boolean =
    !System.getenv("CLAUDE_CODE_SESSION_ID").isNullOrBlank() ||
      !System.getenv("CLAUDE_ENV_FILE").isNullOrBlank()

  fun run() {
    // Environment checks always run.
    checkOs()
    checkJava()
    checkPathJava()
    checkArgEncoding()
    checkClaudeCloud()
    checkServerBinary()

    val projectDir = resolveProjectDir()

    checkComposeBomVersion()
    if (System.getenv("COMPOSE_PREVIEW_DOCTOR_SKIP_NETWORK") != "1") {
      checkBundleVersion()
      checkNetworkReach()
    } else {
      // Still report the installed version offline: the most useful line in a bug report.
      addCheck(
        DoctorCheck(
          id = "env.bundle-version",
          category = "env",
          status = "ok",
          message = "compose-preview $BUNDLE_VERSION (update check skipped)",
        )
      )
    }

    // Project checks: only when a Gradle project is reachable.
    if (projectDir != null) {
      runProjectChecks(projectDir)
    } else {
      addCheck(
        DoctorCheck(
          id = "project.detected",
          category = "project",
          status = "skipped",
          message = "no Gradle project at ${projectDirArg ?: "."}",
          detail = "project-scope compatibility checks were skipped",
          remediation =
            DoctorRemediation(
              summary = "run doctor from a Gradle project root, or pass `--project <dir>`"
            ),
        )
      )
    }

    emit()
  }

  private fun resolveProjectDir(): File? {
    val dir = File(projectDirArg ?: ".").absoluteFile
    if (!dir.exists() || !dir.isDirectory) return null
    return if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) {
      dir
    } else null
  }

  // --- Env checks ---------------------------------------------------------

  /** OS fingerprint, emitted verbatim for bug reports; doctor never branches on it. */
  private fun checkOs() {
    val name = System.getProperty("os.name") ?: "unknown"
    val version = System.getProperty("os.version") ?: ""
    val arch = System.getProperty("os.arch") ?: ""
    addCheck(
      DoctorCheck(
        id = "env.os",
        category = "env",
        status = "ok",
        message = listOf(name, version, arch).filter { it.isNotBlank() }.joinToString(" "),
      )
    )
  }

  /**
   * Which `compose-preview-server` `serve` and `browse` would exec, and where it came from. It is
   * fetched on first use ([ServerDistributionProvision]), so "none yet" is normal. Never fetches:
   * doctor must stay cheap and offline.
   */
  private fun checkServerBinary() {
    // The cache only: "which release is newest" is a network question `serve` asks itself.
    val requested = ServerDistributionProvision.requestedVersion()
    val cached = ServerDistributionProvision.cachedVersions().firstOrNull()
    val tracking =
      when {
        requested != null ->
          "pinned to server $requested by ${ServerDistributionProvision.VERSION_ENV}"
        cached != null -> "tracking the newest server release; $cached is cached"
        else -> "tracking the newest server release"
      }
    val choice = ServerBinaryDiscovery.choose(emptyList())
    if (choice != null) {
      val pin =
        if (choice.source == ServerBinaryDiscovery.CACHE) "This CLI is $tracking"
        else "This CLI is $tracking, and fetches one when it finds none"
      // Finding a binary isn't being able to run it: its start script resolves its own `java`. Null
      // when fine or unknown.
      val unrunnable = ServerJavaPreflight.failure(choice, ReleasedDistribution.SERVER)
      addCheck(
        DoctorCheck(
          id = "env.preview-server",
          category = "env",
          status = if (unrunnable == null) "ok" else "warning",
          message =
            if (unrunnable == null) "preview server ${choice.binary} (from ${choice.source})"
            else "preview server ${choice.binary} is present but this machine cannot run it",
          detail =
            unrunnable
              ?: "`serve` and `browse` exec this; every other command needs no server. $pin",
          remediation =
            unrunnable?.let {
              DoctorRemediation(
                summary = "point JAVA_HOME at a JDK the distribution supports, then re-run"
              )
            },
        )
      )
      return
    }
    val offline =
      System.getProperty("composeai.bundle.offline").toBoolean() ||
        System.getenv("COMPOSE_PREVIEW_OFFLINE") == "1"
    addCheck(
      DoctorCheck(
        id = "env.preview-server",
        category = "env",
        status = if (offline) "warning" else "ok",
        message =
          if (offline) "no preview server, and offline mode is set"
          else if (requested != null)
            "no preview server yet; `serve` fetches $requested on first use"
          else "no preview server yet; `serve` fetches the newest release on first use",
        // With no release resolved, the cache root is the honest "where did you look".
        detail =
          "checked ${ServerBinaryDiscovery.FLAG}, ${ServerBinaryDiscovery.ENV}, PATH and " +
            "${ServerDistributionProvision.defaultCacheRoot().absolutePath}",
        remediation =
          if (!offline) null
          else
            DoctorRemediation(
              summary =
                "run `compose-preview serve` once with network access to cache the server, or " +
                  "unpack the distribution yourself and set ${ServerBinaryDiscovery.ENV}",
              commands =
                listOf(
                  "curl -L -o server.tar.gz ${ServerDistributionProvision.assetUrl(requested ?: "<version>")}"
                ),
            ),
      )
    )
  }

  private fun checkJava() {
    val version = System.getProperty("java.specification.version")
    val major = version?.substringBefore('.')?.toIntOrNull()
    // Vendor and java.home help identify distro/vendor JDKs and SDKMAN vs system installs.
    val vendor = System.getProperty("java.vendor") ?: "unknown"
    val runtime =
      System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: "unknown"
    val home = System.getProperty("java.home") ?: "unknown"
    val detail = "vendor: $vendor; runtime: $runtime; java.home: $home"
    if (major != null && major >= 17) {
      addCheck(
        DoctorCheck(
          id = "env.java-17",
          category = "env",
          status = "ok",
          message = "CLI JVM Java $version",
          detail = detail,
        )
      )
    } else {
      addCheck(
        DoctorCheck(
          id = "env.java-17",
          category = "env",
          status = "error",
          message = "Java 17+ required, got ${version ?: "unknown"}",
          detail = detail,
          remediation =
            DoctorRemediation(
              summary =
                "Install a JDK 17 or newer and put it on PATH, or set JAVA_HOME. " +
                  "The CLI and renderer target JDK 17 bytecode, so any newer JDK (21, 25, …) works.",
              commands = listOf("sdk install java 17.0.11-tem"),
            ),
        )
      )
    }
  }

  /**
   * Separate check for the `java` on `PATH`, which can differ from the CLI's JVM and is what forked
   * test workers may pick up. Skipped on Windows (no `sh -c`).
   */
  private fun checkPathJava() {
    val sameAsCli =
      System.getProperty("java.home")?.let { home ->
        // Same install as java.home: a second check would be noise.
        val probe = runCommand(listOf("sh", "-c", "command -v java"))
        probe?.stdout?.trim()?.startsWith(home) == true
      } ?: false
    if (sameAsCli) return

    val which = runCommand(listOf("sh", "-c", "command -v java")) ?: return
    val path =
      which.stdout.trim().ifBlank {
        return
      }
    val versionOut = runCommand(listOf(path, "-version"))?.stderrOrStdout()?.trim().orEmpty()
    // Keep the version and runtime-build lines (they carry vendor tags).
    val summary = versionOut.lines().take(2).joinToString(" | ").ifBlank { "unreachable" }
    addCheck(
      DoctorCheck(
        id = "env.path-jvm",
        category = "env",
        status = "ok",
        message = "`java` on PATH → $path",
        detail = summary,
      )
    )
  }

  /**
   * Info check for Claude Code cloud: the Google hosts below only resolve in Custom network mode,
   * and `scripts/install.sh` is the intended bootstrap. Suppressed outside Claude cloud.
   */
  /**
   * Whether this JVM can pass a non-ASCII preview id across a process boundary. `sun.jnu.encoding`
   * follows the process locale (often `ANSI_X3.4-1968` in containers), which turns non-ASCII
   * argument characters into `?`. The CLI routes such ids through a file, but the Gradle daemon
   * inherits the same locale, so it is still worth a warning.
   */
  private fun checkArgEncoding() {
    val encoding = System.getProperty("sun.jnu.encoding") ?: System.getProperty("file.encoding")
    val utf8 = encoding != null && runCatching { charset(encoding) }.getOrNull() == Charsets.UTF_8
    addCheck(
      DoctorCheck(
        id = "env.arg-encoding",
        category = "env",
        status = if (utf8) "ok" else "warning",
        message = "process argument encoding: ${encoding ?: "unknown"}",
        detail =
          if (utf8) null
          else
            "sun.jnu.encoding is not UTF-8, so non-ASCII characters (an em dash in a @Preview " +
              "name, say) are replaced by '?' when they cross a process boundary. Renders still " +
              "work; a Gradle daemon started under this locale can mangle preview ids it is " +
              "asked for by name.",
        remediation =
          if (utf8) null
          else
            DoctorRemediation(
              summary = "run with a UTF-8 locale (any UTF-8 locale the image has will do)",
              commands = listOf("export LC_ALL=C.UTF-8 LANG=C.UTF-8", "compose-preview doctor"),
            ),
      )
    )
  }

  private fun checkClaudeCloud() {
    if (!inClaudeCloud) return
    val sessionId = System.getenv("CLAUDE_CODE_SESSION_ID").orEmpty()
    val envFile = System.getenv("CLAUDE_ENV_FILE").orEmpty()
    addCheck(
      DoctorCheck(
        id = "env.claude-cloud",
        category = "env",
        status = "ok",
        message = "Claude Code cloud sandbox detected",
        detail =
          buildString {
            append("session=${sessionId.ifBlank { "(unset)" }}")
            append("; env-file=${envFile.ifBlank { "(unset)" }}")
            append(". Cloud renders need network level = Custom with ")
            append(NETWORK_HOSTS.joinToString(", ") { it.host })
            append(" allowlisted (keep 'include Trusted defaults' on). ")
            append("`scripts/install.sh` reuses the pre-installed JDK (21 on current ")
            append("Claude Cloud images) and installs the skill + CLI bundle. It only ")
            append("falls back to apt-installing JDK 17 when no JDK 17+ is available.")
          },
        remediation =
          DoctorRemediation(
            summary =
              "Bootstrap the CLI + skill bundle and write JAVA_HOME/PATH to \$CLAUDE_ENV_FILE.",
            commands =
              listOf(
                "curl -fsSL https://raw.githubusercontent.com/$SKILLS_REPO/main/scripts/install.sh | bash"
              ),
            docs =
              "https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md",
          ),
      )
    )
  }

  // --- Project checks -----------------------------------------------------

  private var daemonGradleVersion: String? = null
  private var daemonJavaHome: String? = null
  private var daemonJavaMajor: Int? = null

  private fun runProjectChecks(projectDir: File) {
    var gradleAccessFailure: GradleAccessFailure? = null
    // Lets a failed model query be explained as an unpublished pinned plugin version.
    val pin = resolveVersionPin(projectDir, args, fileSystem = fileSystem)
    // Not [BUNDLE_VERSION]: the guidance diagnoses unpublished versions, so it needs one that is.
    val pluginVersion = pin?.version ?: MAVEN_LINE_VERSION
    val pluginVersionSource = pin?.source?.display
    val diagnosePublicationRace = { text: String ->
      pluginResolutionGuidance(text, pluginVersion, pluginVersionSource)
    }
    // Disk-only and independent of the model, so it still reports when the query below fails.
    checkVersionPin(projectDir)
    checkPreviewServer(projectDir)
    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = projectDir)
    val model =
      try {
        GradleConnection(
            projectDir,
            verbose = verbose,
            extraArguments = injectArgs + variantGradleArgs() + gradleWriteLocksArgs(),
          )
          .apply { failureAdvice = diagnosePublicationRace }
          .use { gc ->
            // First, so later checks can compare against the daemon's JDK.
            checkGradleDaemon(gc)
            // The caller's `--timeout`, not the 60s default: cold configuration of a large build
            // takes longer.
            gc
              .runBuildAction(GatherComposePreviewModelAction(), timeoutSeconds = timeoutSeconds)
              .also { gradleAccessFailure = gc.lastModelAccessFailure }
          }
      } catch (e: Exception) {
        addCheck(
          DoctorCheck(
            id = "project.model",
            category = "project",
            status = "error",
            message = "could not fetch plugin Tooling model",
            detail = e.message,
            remediation =
              DoctorRemediation(
                summary = "Ensure the project builds (`./gradlew help`) and the plugin is applied."
              ),
          )
        )
        return
      }

    if (model == null) {
      gradleAccessFailure?.let {
        val raceGuidance =
          diagnosePublicationRace(listOfNotNull(it.message, it.detail).joinToString("\n"))
        addCheck(
          DoctorCheck(
            id = "project.gradle-access",
            category = "project",
            status = "error",
            message =
              if (raceGuidance != null)
                "could not query Gradle project model — the compose-preview plugin " +
                  "$pluginVersion is not resolvable"
              else "could not query Gradle project model",
            detail =
              "Gradle ${it.operation} failed: ${it.message}" +
                (it.detail?.let { d -> " Caused by: $d" } ?: ""),
            remediation =
              DoctorRemediation(
                summary =
                  raceGuidance
                    ?: "Ensure the CLI can access the Gradle wrapper, distribution cache, and lock files, then rerun doctor.",
                commands =
                  if (raceGuidance != null) listOf("compose-preview pin <previous-version>")
                  else listOf("./gradlew help"),
              ),
          )
        )
        addCheck(
          DoctorCheck(
            id = "project.plugin-applied",
            category = "project",
            status = "skipped",
            message = "plugin application check skipped because Gradle model access failed",
          )
        )
        return
      }
      addCheck(
        DoctorCheck(
          id = "project.model",
          category = "project",
          status = "error",
          message = "could not fetch plugin Tooling model",
          remediation =
            DoctorRemediation(
              summary = "Ensure the project builds (`./gradlew help`) and the plugin is applied."
            ),
        )
      )
      return
    }

    if (model.modules.isEmpty()) {
      // Per-project model failures are the usual reason discovery is empty while rendering works.
      val failureDetail =
        model.failures
          .takeIf { it.isNotEmpty() }
          ?.let { fs ->
            "${fs.size} project(s) failed to configure during discovery and were skipped: " +
              fs.take(10).joinToString("; ") { "${it.path}: ${it.message}" } +
              if (fs.size > 10) " (… and ${fs.size - 10} more)" else ""
          }
      val raceGuidance = model.failures.firstNotNullOfOrNull { diagnosePublicationRace(it.message) }
      // A cancelled project was never evaluated, so this is a timeout, not "plugin not applied".
      val cancelled = model.failures.count { isDiscoveryCancellationFailure(it.message) }
      if (cancelled > 0 && raceGuidance == null) {
        addCheck(
          DoctorCheck(
            id = "project.discovery-timeout",
            category = "project",
            status = "error",
            message =
              "discovery timed out after ${timeoutSeconds}s while Gradle configured the build",
            detail =
              "$cancelled of ${model.failures.size} project(s) were cancelled mid-configuration " +
                "(\"Build cancelled.\") rather than failing to configure. A build with no " +
                "configuration cache entry pays full cold configuration on the first pass, which " +
                "on a large multi-module build can take minutes." +
                (failureDetail?.let { " $it" } ?: ""),
            remediation =
              DoctorRemediation(
                summary =
                  "Re-run the same command — the second pass reuses the cached configuration — or " +
                    "raise the budget with --timeout <seconds>.",
                commands =
                  listOf(
                    "compose-preview doctor",
                    "compose-preview doctor --timeout ${timeoutSeconds * 2}",
                  ),
              ),
          )
        )
        addCheck(
          DoctorCheck(
            id = "project.plugin-applied",
            category = "project",
            status = "skipped",
            message = "plugin application check skipped because discovery did not complete",
          )
        )
        return
      }
      // If projects were skipped, discovery is incomplete; withhold the `plugins { }` snippet.
      val incomplete = raceGuidance == null && model.failures.isNotEmpty()
      addCheck(
        DoctorCheck(
          id = "project.plugin-applied",
          category = "project",
          status = "error",
          message =
            if (incomplete)
              "discovery did not complete — ${model.failures.size} project(s) failed to configure, " +
                "so no modules could be inspected"
            else "no modules have the compose-preview plugin applied",
          detail = failureDetail,
          remediation =
            DoctorRemediation(
              summary =
                if (raceGuidance != null) raceGuidance
                else if (incomplete)
                  "Fix the configuration failures above — rerun with --verbose for full Gradle " +
                    "output. Until discovery completes the CLI cannot tell whether the plugin is " +
                    "applied. If it is applied via a convention plugin, the CLI skips auto-inject " +
                    "automatically."
                else "Apply the plugin in your module's `plugins { }` block.",
              commands =
                if (incomplete) listOf("compose-preview doctor --verbose")
                else
                  listOf(
                    "id(\"ee.schimke.composeai.preview\") version \"$recommendedPluginVersion\""
                  ),
              docs = "https://github.com/$REPO#usage",
            ),
        )
      )
      return
    }

    appliedPluginVersion = model.pluginVersion.takeIf { it.isNotEmpty() }

    addCheck(
      DoctorCheck(
        id = "project.plugin-applied",
        category = "project",
        status = "ok",
        message = "plugin applied in ${model.modules.size} module(s)",
        detail = model.modules.keys.joinToString(", "),
      )
    )

    appliedPluginVersion?.let { applied ->
      // The CLI's daemon/renderer are at BUNDLE_VERSION; a major mismatch with the applied plugin
      // changes wire formats and APIs, so warn rather than hint.
      val incompatible = versionsIncompatible(applied, BUNDLE_VERSION)
      val skew = applied != recommendedPluginVersion
      addCheck(
        DoctorCheck(
          id = "project.plugin-version",
          category = "project",
          status = if (incompatible) "warning" else "ok",
          message =
            if (incompatible)
              "compose-preview plugin v$applied is incompatible with CLI v$BUNDLE_VERSION"
            else "compose-preview plugin v$applied",
          detail =
            when {
              incompatible ->
                "The applied Gradle plugin (v$applied) and this CLI (v$BUNDLE_VERSION) are on " +
                  "different major versions. A major release changes the render/daemon wire format " +
                  "and the published APIs, so mixing them can fail to render or behave unexpectedly " +
                  "— align both to the same major version."
              skew -> "CLI is on $recommendedPluginVersion — bump the plugin to align"
              else -> null
            },
          remediation =
            if (incompatible)
              DoctorRemediation(
                summary =
                  "Align the compose-preview Gradle plugin and CLI to the same major version",
                commands =
                  listOf(
                    "# bump the plugin to match the CLI:",
                    "compose-preview init-script --plugin-version $MAVEN_LINE_VERSION",
                    "# …or update the CLI to match the plugin (see install.sh):",
                    "compose-preview update",
                  ),
                docs = "https://github.com/$REPO/blob/main/docs/RELEASING.md",
              )
            else null,
        )
      )
    }

    checkDaemonJdkForAgp(model.modules)
    checkDesktopNatives(model.modules)

    for ((modulePath, info) in model.modules) {
      checkModuleVersions(modulePath, info)
      checkRenderPreviewsTask(modulePath, info)
      checkModuleCompat(modulePath, info)
      checkErrorSignatures(projectDir, modulePath)
    }

    if (checkDaemon) {
      checkDaemonLiveness(projectDir, model.modules.keys)
    } else if (model.modules.isNotEmpty()) {
      addCheck(
        DoctorCheck(
          id = "project.daemon-smoke",
          category = "project",
          status = "skipped",
          message = "daemon spawn check not run — pass `--daemon` to test it (slow)",
          detail =
            "spawns each module's daemon JVM and confirms `initialize` succeeds. " +
              "Adds ~600ms (Desktop) or 3-10s (Android/Robolectric) per module.",
        )
      )
    }
  }

  /**
   * Opt-in spawn smoke test: per module, read `daemon-launch.json`, fork the daemon, run
   * `initialize`, and tear it down. Results are independent per module.
   */
  private fun checkDaemonLiveness(projectDir: File, modulePaths: Set<String>) {
    if (modulePaths.isEmpty()) return
    for (modulePath in modulePaths) {
      val outcome = runDaemonSmokeTest(projectDir = projectDir, modulePath = modulePath)
      addCheck(interpretDaemonSmoke(modulePath, outcome))
    }
  }

  /**
   * Emit the daemon's Gradle and JVM fingerprint as `env` checks, stashing the JDK for later
   * per-module comparison ([checkRenderPreviewsTask]). Needs a live [GradleConnection].
   */
  private fun checkGradleDaemon(gc: GradleConnection) {
    val env =
      gc.buildEnvironment()
        ?: run {
          addCheck(
            DoctorCheck(
              id = "env.gradle-daemon",
              category = "env",
              status = "warning",
              message = "could not fetch BuildEnvironment from Gradle daemon",
            )
          )
          return
        }
    daemonGradleVersion = env.gradle.gradleVersion
    val javaHome = env.java.javaHome
    daemonJavaHome = javaHome.absolutePath
    // From the `release` file, not the path; null when unavailable.
    daemonJavaMajor = readJdkMajor(javaHome)
    val majorStr = daemonJavaMajor?.let { "JDK $it" } ?: "unknown JDK"
    addCheck(
      DoctorCheck(
        id = "env.gradle-daemon",
        category = "env",
        status = "ok",
        message = "Gradle ${daemonGradleVersion} on $majorStr",
        detail = "daemon java.home: ${daemonJavaHome}",
      )
    )
  }

  /**
   * Report the project's compose-preview version pin ([resolveVersionPin]). Never an error:
   * - no pin — `ok`, with how to pin;
   * - pin matches this CLI — `ok`;
   * - pin differs — `warning`: the pinned plugin is injected, but this CLI's daemon and renderer
   *   stay
   *   at [BUNDLE_VERSION] and can disagree across a major. `-SNAPSHOT` on either side stays `ok`.
   */
  /**
   * Report the project's preview server ([resolveProjectServeUrl]), which makes `share-preview`
   * upload there instead of creating a gist. Never an error.
   */
  private fun checkPreviewServer(projectDir: File) {
    val configured = resolveProjectServeUrl(projectDir, args, fileSystem = fileSystem)
    if (configured == null) {
      addCheck(
        DoctorCheck(
          id = "project.preview-server",
          category = "project",
          status = "ok",
          message = "no preview server configured",
          detail =
            "Set $SERVE_URL_PROPERTY in gradle.properties to point share-preview at a " +
              "`compose-preview serve --accept-images` host, so rendered evidence gets an " +
              "embeddable URL without `gh` or push rights. Uploading needs a GitHub token with " +
              "access to that host's configured repository; the token is never read from a file " +
              "you commit.",
        )
      )
      return
    }
    // Same validation as the upload, so doctor can't approve a URL the command will refuse.
    ServeImageUploader.rejectUnsafeUrl(configured.url)?.let { refusal ->
      addCheck(
        DoctorCheck(
          id = "project.preview-server",
          category = "project",
          status = "error",
          // Redacted: the URL may be refused for carrying credentials.
          message =
            "preview server URL is unusable: ${ServeImageUploader.redactedUrl(configured.url)}",
          detail = "Source: ${configured.source.display}. $refusal",
        )
      )
      return
    }
    val trust =
      confirmProjectServeHost(
        configured,
        projectRoot = projectDir,
        // Same identity `share-preview` uses for repo-scoped confirmation.
        originRepo = gitOriginRepo(projectDir),
        fileSystem = fileSystem,
      )
    if (trust is ServeUrlTrust.NeedsConfirmation) {
      addCheck(
        DoctorCheck(
          id = "project.preview-server",
          category = "project",
          // A warning: refusing an unconfirmed host is working as designed.
          status = "warning",
          message =
            "this project names ${ServeImageUploader.redactedUrl(configured.url)}, unconfirmed " +
              "— share-preview won't use it",
          detail = trust.how,
        )
      )
      return
    }
    addCheck(
      DoctorCheck(
        id = "project.preview-server",
        category = "project",
        status = "ok",
        message = "share-preview uploads to ${ServeImageUploader.redactedUrl(configured.url)}",
        detail =
          "Source: ${configured.source.display}. This is what `share-preview` uses unless " +
            "--mechanism says otherwise. An uploaded image is readable by anyone holding its " +
            "link, so a project whose renders shouldn't leave the building should not name a " +
            "public host here.",
      )
    )
  }

  private fun checkVersionPin(projectDir: File) {
    val pin = resolveVersionPin(projectDir, args, fileSystem = fileSystem)
    if (pin == null) {
      addCheck(
        DoctorCheck(
          id = "project.version-pin",
          category = "project",
          status = "ok",
          message = "no version pin (each entrypoint uses its own bundled version)",
          detail =
            "This CLI is $BUNDLE_VERSION. Pin the project so the CLI, the VS Code extension and " +
              "the install / apply GitHub actions all drive the same release.",
          remediation =
            DoctorRemediation(
              summary = "Pin the compose-preview version for every entrypoint",
              commands = listOf("compose-preview pin --cli"),
              docs = "https://github.com/$REPO/blob/main/docs/VERSION_PIN.md",
            ),
        )
      )
      return
    }
    // Remediation snippets should name the version the project chose.
    recommendedPluginVersion = pin.version
    val snapshot = pin.version.endsWith("-SNAPSHOT") || BUNDLE_VERSION.endsWith("-SNAPSHOT")
    val skew = pin.version != BUNDLE_VERSION && !snapshot
    val incompatible = skew && versionsIncompatible(pin.version, BUNDLE_VERSION)
    addCheck(
      DoctorCheck(
        id = "project.version-pin",
        category = "project",
        status = if (skew) "warning" else "ok",
        message =
          if (skew) "pinned to ${pin.version}, but this CLI is $BUNDLE_VERSION"
          else "pinned to ${pin.version}",
        detail =
          buildString {
            append("Pin source: ${pin.source.display}. ")
            if (skew) {
              append(
                "The pinned plugin version is what gets injected, but the daemon and renderer " +
                  "this CLI ships are $BUNDLE_VERSION. "
              )
              if (incompatible) {
                append(
                  "Those are different major versions — the render/daemon wire format differs " +
                    "across a major, so they can fail to render or misbehave. "
                )
              }
              append("Align the CLI with the pin, or re-pin to this CLI.")
            } else {
              append("The CLI matches the pin.")
            }
          },
        remediation =
          if (skew)
            DoctorRemediation(
              summary = "Align the CLI and the project pin",
              commands =
                listOf(
                  "# move the CLI to the pinned version:",
                  "compose-preview update ${pin.version}",
                  "# …or re-pin the project to this CLI:",
                  "compose-preview pin --cli",
                ),
              docs = "https://github.com/$REPO/blob/main/docs/VERSION_PIN.md",
            )
          else null,
      )
    )
  }

  /**
   * Warn when the Gradle daemon's JVM is past [AGP_JDK_CEILING] and a module applies AGP: AGP's
   * `JdkImageTransform` (jlink) and configuration-cache serialisation have failed on newer JDKs.
   * Not compose-preview-specific, but doctor is where users look. Skipped for Desktop-only
   * projects.
   */
  private fun checkDaemonJdkForAgp(modules: Map<String, ModuleInfo>) {
    val major = daemonJavaMajor ?: return
    if (major <= AGP_JDK_CEILING) return
    val agpVersions = modules.values.mapNotNull { it.agpVersion }.distinct()
    if (agpVersions.isEmpty()) return
    addCheck(
      DoctorCheck(
        id = "env.daemon-jdk-agp",
        category = "env",
        status = "warning",
        message =
          "Gradle daemon on JDK $major — AGP is only officially supported up to JDK $AGP_JDK_CEILING",
        detail =
          "AGP ${agpVersions.joinToString(", ")} on this project. AGP's JdkImageTransform " +
            "invokes the daemon JDK's `jlink` to materialise android.jar's system modules; on " +
            "JDK 26 that has been reported failing on core-for-system-modules.jar (issue #1544). " +
            "The same JDK + configuration-cache combination also fails to serialise " +
            "`JdkImageInput.generatedModuleFile`. Reproduces with plain AGP tasks — not specific " +
            "to compose-preview.",
        remediation =
          DoctorRemediation(
            summary =
              "Pin the Gradle daemon to JDK $AGP_JDK_CEILING until AGP officially supports a newer LTS.",
            commands =
              listOf(
                "# gradle.properties:",
                "org.gradle.java.home=/path/to/jdk$AGP_JDK_CEILING",
                "# or per-invocation:",
                "JAVA_HOME=/path/to/jdk$AGP_JDK_CEILING ./gradlew …",
              ),
            docs = "https://github.com/$REPO/issues/1544",
          ),
      )
    )
  }

  /**
   * Emit `env.desktop-natives` when any module renders through CMP Desktop (skiko); Android-only
   * projects never load `libskiko`. Evaluated against the JVMs renders fork from, with this
   * process's `LD_LIBRARY_PATH` (the same value the daemon and render subprocess inherit).
   */
  private fun checkDesktopNatives(modules: Map<String, ModuleInfo>) {
    val desktopModules = modules.filterValues { rendersThroughSkiko(it) }
    if (desktopModules.isEmpty()) return

    // Evaluate every JVM a render could fork into: a module's own launcher when the model reports
    // one, otherwise the daemon as that module's fallback. An unused daemon must not fail doctor.
    val candidates =
      desktopModules.values
        .map { it.renderPreviewsTask?.javaLauncherPath ?: daemonJavaHome }
        .distinct()
        .ifEmpty { listOf(null) }
    val canonicalize = { path: String ->
      runCatching { File(path).canonicalPath }.getOrDefault(path)
    }
    val results = candidates.map { javaHome ->
      DesktopNativesCheck.evaluateDesktopNatives(
        osName = System.getProperty("os.name") ?: "",
        renderJavaHome = javaHome,
        ldLibraryPath = System.getenv("LD_LIBRARY_PATH"),
        exists = { path -> File(path).exists() },
        // Resolve symlinks so store lib dirs reached via link farms are still recognised.
        canonicalize = canonicalize,
      )
    }
    // Worst verdict wins, ranked by severity (same order as [DesktopNativesCheck.interpret]).
    val result =
      results.firstOrNull { it.missing.isNotEmpty() }
        ?: results.firstOrNull { !it.ok }
        ?: results.first()

    val check = DesktopNativesCheck.interpret(result, inClaudeCloud = inClaudeCloud)
    addCheck(
      check.copy(
        detail =
          listOfNotNull(
              check.detail,
              "evaluated against ${result.renderJavaHome ?: "an unknown JVM"}" +
                if (candidates.size > 1) " (of ${candidates.size} candidate render JVMs)" else "",
              // The desktop render task isn't a `Test`, so the model doesn't report its launcher.
              // Mentioned only where a pinned render JDK could change the answer.
              "a CMP module pinning composePreview.renderJavaVersion is not visible to this check; " +
                "the render task prunes store dirs for such a JVM itself"
                  .takeIf { !result.loaderReadsSystemCache && result.storeDirsOnPath.isNotEmpty() },
              "affects ${desktopModules.size} CMP/Desktop module(s): ${desktopModules.keys.joinToString(", ")}",
            )
            .joinToString(". ")
      )
    )
  }

  /**
   * Whether [info] renders through skiko (CMP Desktop) rather than Robolectric: skiko on a resolved
   * classpath, or no AGP when the classpath didn't resolve.
   */
  private fun rendersThroughSkiko(info: ModuleInfo): Boolean {
    val deps = info.mainRuntimeDependencies.keys + info.testRuntimeDependencies.keys
    if (deps.any { it.startsWith("org.jetbrains.skiko:") }) return true
    return deps.isEmpty() && info.agpVersion == null
  }

  /** Emit per-module versions (AGP, Kotlin, Robolectric, Compose runtime) as one info check. */
  private fun checkModuleVersions(modulePath: String, info: ModuleInfo) {
    val robolectric = info.testRuntimeDependencies["org.robolectric:robolectric"]
    val composeRuntime = info.testRuntimeDependencies["androidx.compose.runtime:runtime"]
    val parts = buildList {
      add("variant=${info.variant}")
      info.agpVersion?.let { add("agp=$it") }
      info.kotlinVersion?.let { add("kotlin=$it") }
      robolectric?.let { add("robolectric=$it") }
      composeRuntime?.let { add("compose-runtime=$it") }
    }
    addCheck(
      DoctorCheck(
        id = "project.${idSafe(modulePath)}.versions",
        category = "project",
        status = "ok",
        message = "$modulePath — ${parts.joinToString("  ")}",
      )
    )
  }

  /**
   * Warn when the test worker's forked JDK major differs from the Gradle daemon's, with the typical
   * error signature and the `javaLauncher` toolchain fix. Otherwise an info line.
   */
  private fun checkRenderPreviewsTask(modulePath: String, info: ModuleInfo) {
    val task = info.renderPreviewsTask ?: return
    val launcherMajor = task.javaLauncherVersion?.toIntOrNull()
    val launcherPath = task.javaLauncherPath ?: "(unknown)"
    val launcherVendor = task.javaLauncherVendor ?: "unknown"
    val mismatch =
      launcherMajor != null && daemonJavaMajor != null && launcherMajor != daemonJavaMajor
    val detail = buildString {
      append("launcher: JDK $launcherMajor ($launcherVendor) at $launcherPath")
      append("; classpath=${task.classpathSize}, bootstrap=${task.bootstrapClasspathSize}")
    }
    if (mismatch) {
      addCheck(
        DoctorCheck(
          id = "project.${idSafe(modulePath)}.render-previews-jvm",
          category = "project",
          status = "warning",
          message =
            "$modulePath — composePreviewRender will fork JDK $launcherMajor, Gradle daemon runs JDK $daemonJavaMajor",
          detail =
            "$detail; symptom on mismatch: `ClassNotFoundException: android.app.Application` during JUnit discovery (see issue #142)",
          remediation =
            DoctorRemediation(
              summary = "Pin the composePreviewRender Test task to the project's Java toolchain.",
              commands =
                listOf(
                  "kotlin { jvmToolchain(${daemonJavaMajor ?: 21}) }",
                  "// or: tasks.named(\"composePreviewRender\", Test::class) { javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${daemonJavaMajor ?: 21})) }) }",
                ),
              docs = "https://github.com/$REPO/issues/142",
            ),
        )
      )
    } else {
      addCheck(
        DoctorCheck(
          id = "project.${idSafe(modulePath)}.render-previews-jvm",
          category = "project",
          status = "ok",
          message = "$modulePath — composePreviewRender launcher JDK ${launcherMajor ?: "?"}",
          detail = detail,
        )
      )
    }
  }

  /**
   * Scan `build/reports/tests/composePreviewRender/` HTML reports for known error signatures and
   * emit a hint. Best-effort; skipped when there is no report.
   */
  private fun checkErrorSignatures(projectDir: File, modulePath: String) {
    // Gradle path → filesystem path for standard layouts; custom `projectDir`s are skipped
    // silently.
    val relative = idSafe(modulePath).replace(':', File.separatorChar)
    val moduleDir = File(projectDir, relative).takeIf { it.isDirectory } ?: return
    val reportDir = File(moduleDir, "build/reports/tests/composePreviewRender")
    if (!reportDir.isDirectory) return
    val htmls =
      reportDir.walkTopDown().maxDepth(4).filter { it.isFile && it.extension == "html" }.toList()
    if (htmls.isEmpty()) return

    val haystack =
      htmls
        .asSequence()
        .mapNotNull {
          try {
            fileSystem.read(it.path.toPath()) { readUtf8() }
          } catch (_: Exception) {
            null
          }
        }
        .joinToString("\n")

    val hint = KNOWN_ERROR_SIGNATURES.firstOrNull { haystack.contains(it.pattern) }
    if (hint != null) {
      addCheck(
        DoctorCheck(
          id = "project.${idSafe(modulePath)}.last-error",
          category = "project",
          status = "warning",
          message = "$modulePath — last composePreviewRender run failed with a known signature",
          detail = "${hint.pattern} — ${hint.hint}",
          remediation = hint.remediation,
        )
      )
    }
  }

  /**
   * Render plugin-side [CompatRules] findings as doctor checks; the plugin owns the rules and
   * wording.
   */
  private fun checkModuleCompat(modulePath: String, info: ModuleInfo) {
    val variant = info.variant

    if (info.mainRuntimeDependencies.isEmpty() && info.testRuntimeDependencies.isEmpty()) {
      addCheck(
        DoctorCheck(
          id = "deps.${idSafe(modulePath)}.resolve",
          category = "deps",
          status = "skipped",
          message = "$modulePath — dependency resolution returned empty for variant '$variant'",
          detail =
            "the plugin was applied but neither ${variant}RuntimeClasspath nor ${variant}UnitTestRuntimeClasspath resolved",
        )
      )
      return
    }

    if (info.findings.isEmpty()) {
      addCheck(
        DoctorCheck(
          id = "deps.${idSafe(modulePath)}.compat",
          category = "deps",
          status = "ok",
          message = "$modulePath — no compatibility issues found",
        )
      )
      return
    }

    for (finding in info.findings) {
      val status =
        when (finding.severity) {
          "error" -> "error"
          "warning" -> "warning"
          else -> "info"
        }
      // `--explain` adds the plugin's long-form rationale.
      val detail = if (explain) finding.detail else null
      val remediation =
        if (finding.remediationSummary != null) {
          DoctorRemediation(
            summary = finding.remediationSummary!!,
            commands = finding.remediationCommands,
            docs = finding.docsUrl,
          )
        } else null
      addCheck(
        DoctorCheck(
          id = "deps.${idSafe(modulePath)}.${finding.id}",
          category = "deps",
          status = status,
          message = "$modulePath — ${finding.message}",
          detail = detail,
          remediation = remediation,
        )
      )
    }
  }

  /**
   * Grep-based Compose BOM preflight that works before any Gradle call. The renderer's
   * `MeasuredWrapBox` needs compose-ui 1.10.0 (BOM 2025.12.00) on both standalone and daemon
   * renders.
   */
  private fun checkComposeBomVersion() {
    val workspace = File(projectDirArg ?: ".").canonicalFile
    val versions = findComposeBomDeclarations(workspace)
    if (versions.isEmpty()) return // No declarations → nothing to assert on.

    val tooOld = versions.filter { (_, v) -> isComposeBomBelowRenderFloor(v.raw) == true }
    if (tooOld.isEmpty()) {
      val summary = versions.joinToString(", ") { (_, v) -> v.raw }
      addCheck(
        DoctorCheck(
          id = "env.compose-bom-version",
          category = "env",
          status = "ok",
          message = "compose-bom version(s) look recent enough ($summary)",
        )
      )
      return
    }
    val floor = "$MIN_BOM_YEAR.${MIN_BOM_MONTH.toString().padStart(2, '0')}.00"
    for ((source, v) in tooOld) {
      addCheck(
        DoctorCheck(
          id = "env.compose-bom-version",
          category = "env",
          status = "warning",
          message =
            "compose-bom ${v.raw} declared in ${source.relativeTo(workspace).path} — renderer needs ≥$floor",
          detail =
            "Compose UI before 1.10.0 lacks `ComposeUiNode.Companion.getApplyOnDeactivatedNodeAssertion`. The standalone `composePreviewRender` path also uses the renderer-owned `MeasuredWrapBox`, so simple previews are not exempt. With dependency management enabled the plugin raises the render graph and matching resource pins together; with `composePreview.manageDependencies = false`, resolution fails with an actionable error instead.",
          remediation =
            DoctorRemediation(
              summary = "Bump the BOM.",
              commands = listOf("compose-bom = \"$floor\""),
            ),
        )
      )
    }
  }

  /**
   * `(file, version)` for every `androidx.compose:compose-bom` literal under [root], from
   * `gradle/libs.versions.toml` and `build.gradle[.kts]` files (max depth 4).
   */
  private fun findComposeBomDeclarations(root: File): List<Pair<File, ComposeVersion>> {
    val out = mutableListOf<Pair<File, ComposeVersion>>()
    val tomlRegex = Regex("""compose-bom\s*=\s*"([^"]+)"""")
    val bomInlineRegex = Regex("""["']androidx\.compose:compose-bom:([0-9][0-9A-Za-z.\-]+)["']""")

    fun scanTextFile(file: File) {
      val text =
        try {
          fileSystem.read(file.path.toPath()) { readUtf8() }
        } catch (_: Exception) {
          return
        }
      tomlRegex.findAll(text).forEach { m ->
        ComposeVersion.parse(m.groupValues[1])?.let { out += file to it }
      }
      bomInlineRegex.findAll(text).forEach { m ->
        ComposeVersion.parse(m.groupValues[1])?.let { out += file to it }
      }
    }

    fun walk(dir: File, depth: Int) {
      if (depth > 4 || dir.name.startsWith(".") || dir.name in SKIP_DIRS) return
      val children = dir.listFiles() ?: return
      for (f in children) {
        when {
          f.isDirectory -> walk(f, depth + 1)
          f.name == "libs.versions.toml" -> scanTextFile(f)
          f.name == "build.gradle.kts" || f.name == "build.gradle" -> scanTextFile(f)
        }
      }
    }
    walk(root, 0)
    return out
  }

  /** Compose BOM version in `YYYY.MM.NN` form; patch precision is never needed. */
  private data class ComposeVersion(val year: Int, val month: Int, val raw: String) {
    fun isOlderThan(minYear: Int, minMonth: Int): Boolean =
      year < minYear || (year == minYear && month < minMonth)

    companion object {
      private val pattern = Regex("""^(\d{4})\.(\d{2})\.\d+""")

      fun parse(s: String): ComposeVersion? {
        val m = pattern.find(s) ?: return null
        return ComposeVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), s)
      }
    }
  }

  // --- Output -------------------------------------------------------------

  private fun emit() {
    when {
      jsonOut -> emitJson()
      reportOut -> emitReport()
      else -> emitText()
    }
    val errors = checks.count { it.status == "error" }
    exitProcess(if (errors > 0) 1 else 0)
  }

  /**
   * Compact, flat key-value fingerprint block for GitHub issue reports, so triagers don't need
   * follow-up questions. Sections print only when there is data.
   */
  private fun emitReport() {
    println("compose-preview-doctor-report/v1")
    println()
    val env = checks.filter { it.category == "env" }
    val project = checks.filter { it.category == "project" }
    val deps = checks.filter { it.category == "deps" }

    println("plugin: $reportPluginVersion")
    for (c in env) {
      val tail = c.detail?.let { "  ($it)" } ?: ""
      println("${c.id}: ${c.message}$tail")
    }
    if (project.isNotEmpty()) {
      println()
      println("[project]")
      for (c in project) {
        println("${c.id} [${c.status}]: ${c.message}")
        c.detail?.let { println("    $it") }
      }
    }
    if (deps.isNotEmpty()) {
      println()
      println("[deps]")
      for (c in deps) {
        if (c.status == "ok") continue // compat-clean modules are noise here
        println("${c.id} [${c.status}]: ${c.message}")
        c.detail?.let { println("    $it") }
      }
    }

    val summary = summary()
    println()
    println(
      "summary: ok=${summary.ok} warning=${summary.warning} error=${summary.error} skipped=${summary.skipped}"
    )
  }

  private fun emitText() {
    println("compose-preview doctor")
    println()
    var currentCategory = ""
    for (check in checks) {
      if (check.category != currentCategory) {
        if (currentCategory.isNotEmpty()) println()
        println("  [${check.category}]")
        currentCategory = check.category
      }
      val marker =
        when (check.status) {
          "ok" -> "✓"
          "warning" -> "!"
          "error" -> "✗"
          "skipped" -> "∙"
          else -> "?"
        }
      println("  $marker ${check.message}")
      check.detail?.let { println("      $it") }
      check.remediation?.let { r ->
        println("      → ${r.summary}")
        for (cmd in r.commands) println("        \$ $cmd")
        r.docs?.let { println("        docs: $it") }
      }
    }
    println()

    val summary = summary()
    val headline =
      when {
        summary.error > 0 -> "✗ ${summary.error} error(s), ${summary.warning} warning(s)"
        summary.warning > 0 -> "✓ ok (${summary.warning} warning(s))"
        else -> "✓ all checks passed"
      }
    println(headline)
    if (summary.skipped > 0) println("  ${summary.skipped} check(s) skipped")
  }

  private fun emitJson() {
    val report =
      DoctorReport(
        pluginVersion = reportPluginVersion,
        overall =
          when {
            checks.any { it.status == "error" } -> "error"
            checks.any { it.status == "warning" } -> "warning"
            else -> "ok"
          },
        checks = checks.toList(),
        summary = summary(),
      )
    println(JSON.encodeToString(DoctorReport.serializer(), report))
  }

  private fun summary() =
    DoctorSummary(
      ok = checks.count { it.status == "ok" },
      warning = checks.count { it.status == "warning" },
      error = checks.count { it.status == "error" },
      skipped = checks.count { it.status == "skipped" },
    )

  // --- Helpers ------------------------------------------------------------

  private fun addCheck(check: DoctorCheck) {
    checks += check
  }

  /**
   * Compare [BUNDLE_VERSION] with the latest GitHub release via a HEAD on the `releases/latest`
   * redirect (not the rate-limited API). Unreachable or unparseable → `skipped`; older → `warning`
   * with `compose-preview update`; equal or newer (SNAPSHOT) → `ok`.
   */
  private fun checkBundleVersion() {
    val latestUrl = "https://github.com/$REPO/releases/latest"
    val resolved =
      try {
        httpProbeClient().use { client ->
          runBlocking {
            // The final redirected URL is `…/releases/tag/v<version>`.
            val response = client.head(latestUrl) { header("User-Agent", USER_AGENT) }
            response.call.request.url.toString()
          }
        }
      } catch (e: Exception) {
        addCheck(
          DoctorCheck(
            id = "env.bundle-version",
            category = "env",
            status = "skipped",
            message = "compose-preview $BUNDLE_VERSION (could not check for updates)",
            detail = "GET $latestUrl: ${e.message ?: e.javaClass.simpleName}",
          )
        )
        return
      }

    // Public redirect is `…/releases/tag/v<version>`. Strip everything up to the last `/v`.
    val latest = resolved.substringAfterLast("/v", missingDelimiterValue = "")
    if (latest.isBlank()) {
      addCheck(
        DoctorCheck(
          id = "env.bundle-version",
          category = "env",
          status = "skipped",
          message = "compose-preview $BUNDLE_VERSION (could not parse latest release tag)",
          detail = "redirect target: $resolved",
        )
      )
      return
    }

    val cmp = compareSemver(BUNDLE_VERSION, latest)
    when {
      cmp >= 0 ->
        addCheck(
          DoctorCheck(
            id = "env.bundle-version",
            category = "env",
            status = "ok",
            message = "compose-preview $BUNDLE_VERSION (latest)",
            detail = if (cmp > 0) "ahead of published latest v$latest" else null,
          )
        )
      else ->
        addCheck(
          DoctorCheck(
            id = "env.bundle-version",
            category = "env",
            status = "warning",
            message = "compose-preview $BUNDLE_VERSION is behind latest v$latest",
            detail = "see https://github.com/$REPO/releases/tag/v$latest for changes",
            remediation =
              DoctorRemediation(
                summary = "Update the compose-preview skill bundle and CLI to v$latest.",
                commands = listOf("compose-preview update"),
                docs = "https://github.com/$REPO/releases/latest",
              ),
          )
        )
    }
  }

  /**
   * Probe the Google hosts the Android render path and downloadable fonts need; one
   * `env.network.<id>` check each, classified by [networkCheck].
   */
  private fun checkNetworkReach() {
    NETWORK_HOSTS.forEach { probe ->
      val (code, headers) = headPlain(probe.url)
      addCheck(
        networkCheck(
          probe,
          code,
          headers["error"],
          inClaudeCloud,
          // Header lookup is case-insensitive at the source; Ktor lowercases nothing, so try both.
          redirectTarget = headers["Location"] ?: headers["location"],
        )
      )
    }
  }

  /**
   * HEAD [url] and return status + headers, or `-1 to {error}` when unreachable (never throws).
   * Non-2xx codes are returned as-is for [networkCheck] to judge.
   */
  internal fun headPlain(url: String): Pair<Int, Map<String, String>> =
    try {
      // Don't follow redirects: a captive portal redirecting to a 200 login page must not read as
      // healthy.
      httpProbeClient(followRedirects = false).use { client ->
        runBlocking {
          val response = client.head(url) { header("User-Agent", USER_AGENT) }
          val headers = response.headers.entries().associate { (k, v) -> k to v.joinToString(", ") }
          response.status.value to headers
        }
      }
    } catch (e: Exception) {
      -1 to mapOf("error" to (e.message ?: e.javaClass.simpleName))
    }

  /** Client for doctor's one-shot HEAD probes: 3s timeouts, one client per probe. */
  private fun httpProbeClient(followRedirects: Boolean = true): HttpClient =
    HttpClient(OkHttp) {
      // Off for reachability probes, on for the version check; set on both Ktor config and engine.
      this.followRedirects = followRedirects
      engine {
        config {
          followRedirects(followRedirects)
          followSslRedirects(followRedirects)
        }
      }
      install(HttpTimeout) {
        connectTimeoutMillis = 3_000
        requestTimeoutMillis = 3_000
      }
    }

  /** Module path without the leading `:` (`:samples:wear` → `samples:wear`) for check ids. */
  private fun idSafe(modulePath: String): String = modulePath.removePrefix(":").ifEmpty { "root" }

  /**
   * Run a short command and capture output; null if it can't start or exceeds 5s (the check is
   * skipped).
   */
  private fun runCommand(cmd: List<String>): CommandResult? {
    return try {
      val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
      // Drain stderr concurrently, or a full stderr pipe deadlocks the stdout read.
      val stderrHolder = arrayOfNulls<String>(1)
      val stderrThread = Thread {
        stderrHolder[0] = process.errorStream.bufferedReader().use { it.readText() }
      }
        .apply {
          isDaemon = true
          start()
        }
      val stdout = process.inputStream.bufferedReader().use { it.readText() }
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      stderrThread.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5))
      CommandResult(process.exitValue(), stdout, stderrHolder[0] ?: "")
    } catch (_: Exception) {
      null
    }
  }

  private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    /** `java -version` usually prints to stderr. */
    fun stderrOrStdout(): String = stderr.ifBlank { stdout }
  }

  /** JDK major from `$javaHome/release` (`JAVA_VERSION=...`), or null when missing or malformed. */
  private fun readJdkMajor(javaHome: File): Int? {
    val release = File(javaHome, "release").takeIf { it.isFile } ?: return null
    val line =
      try {
        fileSystem
          .read(release.path.toPath()) { readUtf8() }
          .lineSequence()
          .firstOrNull { it.startsWith("JAVA_VERSION=") }
      } catch (_: Exception) {
        return null
      } ?: return null
    // Format: JAVA_VERSION="21.0.11"  OR  JAVA_VERSION="1.8.0_402"
    val raw = line.substringAfter("=").trim().trim('"')
    val major = raw.substringBefore('.').toIntOrNull() ?: return null
    // Legacy JDK 8 reports as "1.8.x" — normalize to 8.
    return if (major == 1) raw.split('.').getOrNull(1)?.toIntOrNull() else major
  }

  /**
   * One known `composePreviewRender` failure signature: a substring to find in the HTML report, a
   * human [hint], and a [remediation] in doctor's standard shape.
   */
  private data class ErrorSignature(
    val pattern: String,
    val hint: String,
    val remediation: DoctorRemediation?,
  )

  companion object {
    /**
     * Minimum Compose BOM, 2025.12.00 (compose-ui 1.10.0), required by the renderer's
     * `MeasuredWrapBox`.
     */
    private const val MIN_BOM_YEAR = 2025
    private const val MIN_BOM_MONTH = 12

    internal fun isComposeBomBelowRenderFloor(raw: String): Boolean? =
      ComposeVersion.parse(raw)?.isOlderThan(MIN_BOM_YEAR, MIN_BOM_MONTH)

    /** User-Agent for doctor's HEAD probes — matches the string `scripts/install.sh` sends. */
    private const val USER_AGENT = "compose-preview-doctor"

    /**
     * Highest JDK trusted to drive AGP (last AGP-blessed LTS); `checkDaemonJdkForAgp` keys off it.
     */
    private const val AGP_JDK_CEILING = 21

    private val SKIP_DIRS = setOf("build", "node_modules", "out", "dist", ".gradle")

    internal data class NetworkHost(
      val id: String,
      val host: String,
      val url: String,
      val purpose: String,
      /**
       * Non-2xx statuses this URL returns with healthy egress, with the reason. Only
       * `fonts.gstatic.com` needs one (404 on `/`); anything else is treated as interception.
       */
      val expected: Map<Int, String> = emptyMap(),
    )

    /**
     * Google hosts the Android/Compose render paths need; none are on Claude Code's Trusted
     * allowlist.
     */
    internal val NETWORK_HOSTS =
      listOf(
        NetworkHost(
          id = "maven-google",
          host = "maven.google.com",
          url = "https://maven.google.com/web/index.html",
          purpose = "Google Maven — resolves AGP and AndroidX for Android-consumer renders",
        ),
        NetworkHost(
          id = "dl-google",
          host = "dl.google.com",
          url = "https://dl.google.com/",
          purpose = "Android SDK cmdline-tools / platform downloads",
        ),
        NetworkHost(
          id = "fonts-googleapis",
          host = "fonts.googleapis.com",
          // A real CSS2 query, as the render path requests; `/` 404s even with full egress.
          url = "https://fonts.googleapis.com/css2?family=Roboto:wght@400&display=swap",
          purpose =
            "Google Fonts API — used by androidx.compose.ui:ui-text-google-fonts at render time",
        ),
        NetworkHost(
          id = "fonts-gstatic",
          host = "fonts.gstatic.com",
          url = "https://fonts.gstatic.com/",
          purpose = "Google Fonts static asset host — downloadable-font binaries",
          // No stable 200 path (font URLs are versioned), but `/` reliably 404s from the real host.
          expected =
            mapOf(
              404 to "the host only serves versioned font paths, so `/` 404s when egress works"
            ),
        ),
      )

    /**
     * Turn one probe result into its `env.network.<id>` check:
     * - 2xx, or a status [NetworkHost.expected] documents for this URL → `ok` (with the reason).
     * - Any other status → `warning`: a proxy or portal answering for the host.
     * - No response ([code] <= 0) → `warning` with the transport error.
     *
     * Both warnings share the allowlist remediation.
     */
    internal fun networkCheck(
      probe: NetworkHost,
      code: Int,
      error: String?,
      inClaudeCloud: Boolean,
      /** The `Location` a 3xx pointed at, when there was one — named in the detail. */
      redirectTarget: String? = null,
    ): DoctorCheck {
      val id = "env.network.${probe.id}"
      val expectedNote = probe.expected[code]
      if (code in 200..299 || expectedNote != null) {
        val why = expectedNote?.let { " — $it" }.orEmpty()
        return DoctorCheck(
          id = id,
          category = "env",
          status = "ok",
          message = "${probe.host} reachable (HTTP $code$why)",
        )
      }
      val remediation =
        DoctorRemediation(
          summary =
            if (inClaudeCloud) {
              "Claude Code cloud session detected — switch the session's network level from Trusted to **Custom**, keep 'include Trusted defaults' on, and add `${probe.host}` (plus the other three Google hosts probed here) to the allowlist."
            } else {
              "Allow ${probe.host} in your sandbox / proxy configuration. In Claude Code cloud sessions this means switching network access to Custom and adding the host (keep 'include Trusted defaults' on)."
            },
          docs = "https://code.claude.com/docs/en/claude-code-on-the-web#network-access",
        )
      if (code <= 0) {
        return DoctorCheck(
          id = id,
          category = "env",
          status = "warning",
          message = "${probe.host} unreachable",
          detail = "${probe.purpose}. Error: ${error ?: "unknown"}.",
          remediation = remediation,
        )
      }
      // Describe the healthy answer from the probe's own config (gstatic expects a 404).
      val healthy =
        (listOf("2xx") + probe.expected.keys.sorted().map { "HTTP $it" }).let {
          if (it.size == 1) it.single() else it.dropLast(1).joinToString(", ") + " or " + it.last()
        }
      // A redirect is its own diagnosis: probably a captive portal or filtering proxy.
      if (code in 300..399) {
        val target = redirectTarget?.takeIf { it.isNotBlank() }
        return DoctorCheck(
          id = id,
          category = "env",
          status = "warning",
          message = "${probe.host} redirected (HTTP $code) instead of answering",
          detail =
            "${probe.purpose}. ${probe.url} answers $healthy when egress is healthy; a redirect" +
              (target?.let { " to $it" } ?: "") +
              " is a captive portal or filtering proxy answering on the host's behalf. The probe" +
              " does not follow it, because the page at the other end can be a 200 that proves" +
              " nothing about ${probe.host}.",
          remediation = remediation,
        )
      }
      return DoctorCheck(
        id = id,
        category = "env",
        status = "warning",
        message = "${probe.host} answered HTTP $code, not a success",
        detail =
          "${probe.purpose}. ${probe.url} answers $healthy when egress is healthy; a $code usually means a proxy or sandbox answered instead of the host.",
        remediation = remediation,
      )
    }

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * Failure signatures recognised in `composePreviewRender` HTML reports; first match wins. Only
     * patterns traced to a specific, actionable root cause belong here.
     */
    private val KNOWN_ERROR_SIGNATURES =
      listOf(
        ErrorSignature(
          pattern = "ClassNotFoundException: android.app.Application",
          hint = "likely test-worker JVM mismatch (see issue #142)",
          remediation =
            DoctorRemediation(
              summary =
                "Pin the composePreviewRender Test task's javaLauncher to the project toolchain.",
              commands = listOf("kotlin { jvmToolchain(21) }"),
              docs = "https://github.com/$REPO/issues/142",
            ),
        ),
        ErrorSignature(
          pattern = "cannot open shared object file",
          hint =
            "skiko's native deps aren't resolvable from the render JVM — see the env.desktop-natives check",
          remediation =
            DoctorRemediation(
              summary =
                "Install libGL/libX11/libfontconfig/libstdc++ and export LD_LIBRARY_PATH to the " +
                  "Gradle daemon, then force a re-render (a failed render is a cached task output).",
              commands =
                listOf(
                  "apt-get install -y libgl1 libx11-6 libfontconfig1 libstdc++6",
                  "./gradlew --stop",
                  "./gradlew :<module>:composePreviewRender --rerun",
                ),
              docs = "https://github.com/$REPO/blob/main/docs/DESKTOP_NATIVE_DEPS.md",
            ),
        ),
        ErrorSignature(
          pattern = "RuntimeException: Stub!",
          hint =
            "android.jar on bootstrap classpath is shadowing Robolectric's instrumented android-all",
          remediation =
            DoctorRemediation(
              summary =
                "Don't inject android.jar into bootstrapClasspath — keep it on the outer classpath only.",
              docs =
                "https://github.com/$REPO/blob/main/gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt",
            ),
        ),
        ErrorSignature(
          pattern = "getApplyOnDeactivatedNodeAssertion",
          hint =
            "compose-ui below 1.10.0 — even standalone simple previews pass through the renderer's MeasuredWrapBox",
          remediation =
            DoctorRemediation(
              summary =
                "Bump compose-bom to at least 2025.12.00, or enable composePreview.manageDependencies so the plugin raises the render graph and resource pins together."
            ),
        ),
        ErrorSignature(
          pattern = "NoSuchMethodError: androidx.compose.runtime.ComposeUiNode",
          hint =
            "compose-bom too old — renderer-compiled calls postdate the runtime on the consumer's classpath",
          remediation = DoctorRemediation(summary = "Bump compose-bom to at least 2025.12.00."),
        ),
      )
  }
}

// Report schema: a stable public contract, backwards-compatible within a major
// ([DoctorReport.schema]).

@Serializable
data class DoctorReport(
  val schema: String = "compose-preview-doctor/v1",
  val pluginVersion: String,
  val overall: String, // "ok" | "warning" | "error"
  val checks: List<DoctorCheck>,
  val summary: DoctorSummary,
)

@Serializable
data class DoctorCheck(
  /** Stable dotted id — safe to grep / branch on. */
  val id: String,
  /** "env" | "project" | "deps". */
  val category: String,
  /** "ok" | "warning" | "error" | "skipped". */
  val status: String,
  /** Single-line human-readable summary. */
  val message: String,
  /** Multi-line follow-up (optional). Agents can surface to users. */
  val detail: String? = null,
  /** Concrete action to unblock (optional). */
  val remediation: DoctorRemediation? = null,
)

@Serializable
data class DoctorRemediation(
  val summary: String,
  val commands: List<String> = emptyList(),
  val docs: String? = null,
)

@Serializable
data class DoctorSummary(val ok: Int, val warning: Int, val error: Int, val skipped: Int)
