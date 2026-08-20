package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.plugin.tooling.ModuleInfo
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
 * `compose-preview doctor`
 *
 * Two layers of checks:
 *
 * **Environment** (always runs, safe outside a Gradle project):
 * - Java 17+ on PATH
 * - HEAD probes of Google-controlled hosts required by Android / downloadable-font render paths
 *   (`maven.google.com`, `dl.google.com`, `fonts.googleapis.com`, `fonts.gstatic.com`). Warnings
 *   only; set `COMPOSE_PREVIEW_DOCTOR_SKIP_NETWORK=1` to skip.
 * - `env.desktop-natives` — only when the project has a CMP Desktop module: resolves skiko's four
 *   native dependencies the way the *render JVM's* loader would, catching the
 *   `UnsatisfiedLinkError: libGL.so.1: cannot open shared object file` class of failure before a
 *   render burns a full build. See [DesktopNativesCheck] for why `ldd` isn't a substitute.
 *
 * **Project** (runs when a `settings.gradle[.kts]` is found at `--project` or cwd):
 * - Plugin applied to at least one module
 * - Consumer's test-runtime classpath vs main-variant classpath satisfies the AAR/R.id
 *   version-alignment rules for preview rendering. Checks:
 *     - `deps.<module>.ui-test-manifest` — ui-test-manifest on test classpath
 *     - `deps.<module>.activity-vs-navigationevent` — navigationevent on test, older activity on
 *       main
 *     - `deps.<module>.compose-ui-vs-core` — compose-ui 1.10+ on test, older androidx.core on main
 *     - `deps.<module>.hamcrest-skew` — `org.hamcrest:hamcrest:2.x` and the legacy split
 *       `:hamcrest-library` / `:hamcrest-core` 1.3 jars both on the test classpath; mixed `AllOf` /
 *       `Matchers` classes break Espresso's `<clinit>` with `NoSuchMethodError`
 *     - `deps.<module>.compose-bom` (warning) — no Compose BOM declared
 *
 * Output modes:
 * - Default: human-friendly ANSI with ✓ / ! / ✗ / ∙ markers, per-check remediation.
 * - `--json`: machine-readable [DoctorReport] (schema `compose-preview-doctor/v1`). Agents should
 *   prefer this — remediations come with concrete `commands[]` they can apply directly.
 * - `--explain`: prints extended rationale for each non-ok check, including the specific exception
 *   class an unfixed misconfig will surface at render time. Useful for humans first hitting a
 *   failure; noisy for agents.
 *
 * Opt-in slow checks (off by default to keep the default invocation cheap):
 * - `--daemon` (alias `--with-daemon`): also spawns each module's preview daemon JVM, completes the
 *   `initialize` handshake, and tears it down. Catches descriptor / classpath / launcher
 *   regressions that only surface at runtime. Costs ~600ms (Desktop) or 3-10s (Robolectric) per
 *   module. Emits one `project.<module>.daemon-smoke` check per module.
 *
 * Exits 0 when no errors (warnings OK), 1 when any check reports ERROR.
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
   * Opt-in: when set, [runProjectChecks] also spawns each module's daemon JVM, completes the
   * `initialize` round-trip, and tears it down. Slow (~600ms desktop, 3-10s Robolectric per module)
   * so it's a separate flag rather than part of the default battery. `--with-daemon` is the same
   * flag — kept as an alias because both spellings turned up in early dogfooding.
   */
  private val checkDaemon = "--daemon" in args || "--with-daemon" in args

  /**
   * Version the CLI suggests in remediation messages ("install version X"). Comes from
   * `--plugin-version`, else the project's version pin once [checkVersionPin] has read it, else the
   * CLI's compiled-in default. Distinct from [appliedPluginVersion], which is what the project
   * actually has on its classpath — a project can be pinned to one version and still have another
   * applied, which is exactly the state the pin check exists to surface.
   */
  private var recommendedPluginVersion = args.flagValue("--plugin-version") ?: BUNDLE_VERSION

  /**
   * `--variant <name>` forwarded as `-PcomposePreview.variant=<name>` on the Gradle connection,
   * mirroring [BaseCommand.variantOverride]. Without this the model query (and any subsequent
   * daemon spawn through `--with-daemon`) defaults to whatever the plugin's
   * `composePreview.variant` convention picks, so `doctor --variant prodRelease` would silently
   * report on `debug` instead.
   */
  private val variantOverride: String? =
    args.flagValue("--variant")?.trim()?.takeIf { it.isNotEmpty() }

  private fun variantGradleArgs(): List<String> {
    val v = variantOverride ?: return emptyList()
    return listOf("-PcomposePreview.variant=$v")
  }

  /**
   * Plugin version actually applied to the project, read from the Tooling model after
   * [runProjectChecks] fetches it. Null when no project was detected or no module applies the
   * plugin. Surfaced in the report header and as an explicit `project.plugin-version` check so
   * agents debugging "my bump didn't take" can see exactly what's on the classpath.
   */
  private var appliedPluginVersion: String? = null

  /** Plugin version to use in headers and the JSON `pluginVersion` field — applied if known. */
  private val reportPluginVersion: String
    get() = appliedPluginVersion ?: recommendedPluginVersion

  private val checks = mutableListOf<DoctorCheck>()

  /**
   * Claude Code cloud sandbox detection. Same signal `scripts/install.sh` uses (see `CLAUDE_CLOUD`
   * auto-detection). When true, network-reach remediations call out Claude Code's Custom network
   * mode directly and `checkClaudeCloud` emits a top-line `env.claude-cloud` check so the rest of
   * the report reads in that context.
   */
  private val inClaudeCloud: Boolean =
    !System.getenv("CLAUDE_CODE_SESSION_ID").isNullOrBlank() ||
      !System.getenv("CLAUDE_ENV_FILE").isNullOrBlank()

  fun run() {
    // Environment checks always run.
    checkOs()
    checkJava()
    checkPathJava()
    checkClaudeCloud()

    val projectDir = resolveProjectDir()

    checkComposeBomVersion()
    if (System.getenv("COMPOSE_PREVIEW_DOCTOR_SKIP_NETWORK") != "1") {
      checkBundleVersion()
      checkNetworkReach()
    } else {
      // Still surface the installed version offline — paste-friendly for bug reports and the
      // single most useful line in the report when the user is asking "what am I running?".
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

  /**
   * OS fingerprint. Cheap to collect and has saved several issue rounds — e.g. #142 was specific to
   * a Linux kernel build not visible from `os.name` alone. We emit name/version/arch verbatim;
   * doctor never branches on these.
   */
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

  private fun checkJava() {
    val version = System.getProperty("java.specification.version")
    val major = version?.substringBefore('.')?.toIntOrNull()
    // Fingerprint the CLI's own JVM so bug reports carry vendor (often
    // differentiates the Linux-distro / Google-internal JDK that caused
    // #142) and java.home (pinpoints SDKMAN vs system-provided installs).
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
   * Separate check for the `java` on `PATH`. Motivated by #142: the reporter's Gradle launcher was
   * pinned to JDK 21 via `JAVA_HOME`, but their system default (`java` on PATH) was JDK 25 — and
   * the forked `composePreviewRender` test worker picked up the system default, because the Test
   * task's `javaLauncher` wasn't pinned to the project toolchain. Separating the CLI JVM from the
   * PATH JVM makes that delta visible in the first line of output.
   *
   * Skipped on Windows for now — `java -version` prints to stderr, the parsing is the same, but
   * nobody is reporting Windows-specific bugs yet and `sh -c` isn't available there. Add when a
   * Windows-only bug report needs it.
   */
  private fun checkPathJava() {
    val sameAsCli =
      System.getProperty("java.home")?.let { home ->
        // If PATH's `java` resolves to the same install as java.home,
        // emitting a second check is noise — the cli check already
        // covers it.
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
    // `java -version` prints 3 lines to stderr: the version, the
    // runtime build, and the VM build. We keep the first two, which
    // carry vendor tagging (e.g. `+-google-release-868188172`).
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
   * Surfaces Claude Code cloud sandbox detection as an info-style check so agents and humans
   * reading the report know the four `env.network.*` probes below are load-bearing (Google hosts
   * aren't on the Trusted allowlist — they only resolve in Custom mode) and that
   * `scripts/install.sh` is the intended bootstrap path. Suppressed when no Claude cloud env vars
   * are set.
   */
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
    // Cheap, disk-only, and independent of the Gradle model — so it runs first and still reports
    // when the model query below fails. A wrong pin is one of the reasons a query fails.
    checkVersionPin(projectDir)
    checkPreviewServer(projectDir)
    val injectArgs = autoInjectInitScriptArgs(args, projectRoot = projectDir)
    val model =
      try {
        GradleConnection(
            projectDir,
            verbose = verbose,
            extraArguments = injectArgs + variantGradleArgs(),
          )
          .use { gc ->
            // Daemon-JVM + Gradle-version snapshot. Runs first so other
            // project-scope checks can compare against the daemon's JDK
            // (e.g. flagging test worker mismatch in #142).
            checkGradleDaemon(gc)
            gc.runBuildAction(GatherComposePreviewModelAction()).also {
              gradleAccessFailure = gc.lastModelAccessFailure
            }
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
        addCheck(
          DoctorCheck(
            id = "project.gradle-access",
            category = "project",
            status = "error",
            message = "could not query Gradle project model",
            detail =
              "Gradle ${it.operation} failed: ${it.message}" +
                (it.detail?.let { d -> " Caused by: $d" } ?: ""),
            remediation =
              DoctorRemediation(
                summary =
                  "Ensure the CLI can access the Gradle wrapper, distribution cache, and lock files, then rerun doctor.",
                commands = listOf("./gradlew help"),
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
      // Per-project model-build failures are the usual reason discovery comes back empty while the
      // render task works (issue #3) — surface them so the user isn't left guessing.
      val failureDetail =
        model.failures
          .takeIf { it.isNotEmpty() }
          ?.let { fs ->
            "${fs.size} project(s) failed to configure during discovery and were skipped: " +
              fs.take(10).joinToString("; ") { "${it.path}: ${it.message}" } +
              if (fs.size > 10) " (… and ${fs.size - 10} more)" else ""
          }
      addCheck(
        DoctorCheck(
          id = "project.plugin-applied",
          category = "project",
          status = "error",
          message = "no modules have the compose-preview plugin applied",
          detail = failureDetail,
          remediation =
            DoctorRemediation(
              summary =
                if (failureDetail != null)
                  "Projects failed to configure during discovery — rerun with --verbose for full " +
                    "Gradle output. If the plugin is applied via a convention plugin, the CLI now " +
                    "skips auto-inject automatically; otherwise apply it in your module's " +
                    "`plugins { }` block."
                else "Apply the plugin in your module's `plugins { }` block.",
              commands =
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
      // The CLI ships the daemon + renderer at its own BUNDLE_VERSION; the applied Gradle plugin is
      // independent. A *major* mismatch is the real "mixing incompatible versions" hazard — major
      // releases change the render/daemon wire format and the published okio.Path / suspend APIs —
      // so surface that as a warning rather than the soft align hint used for minor/patch skew.
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
                    "compose-preview init-script --plugin-version $BUNDLE_VERSION",
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
   * Opt-in spawn smoke test. For each [modulePaths] entry, locate the
   * `build/compose-previews/daemon-launch.json` descriptor, fork the daemon JVM, run the
   * `initialize` round-trip, and tear it down. Each module emits one
   * `project.<module>.daemon-smoke` check; the per-module results are independent so a stale
   * descriptor in one module doesn't suppress a clean spawn in another.
   */
  private fun checkDaemonLiveness(projectDir: File, modulePaths: Set<String>) {
    if (modulePaths.isEmpty()) return
    for (modulePath in modulePaths) {
      val outcome = runDaemonSmokeTest(projectDir = projectDir, modulePath = modulePath)
      addCheck(interpretDaemonSmoke(modulePath, outcome))
    }
  }

  /**
   * Emits the daemon's Gradle and JVM fingerprint as two `env` checks. We stash the JDK major and
   * path on the class so per-module checks can compare against them (see
   * [checkRenderPreviewsTask]).
   *
   * Runs inside the project block because fetching the model requires a live [GradleConnection].
   * It's logically an env concern though, so the check id lives under `env.*`.
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
    // Derive JDK major from the release file — more reliable than
    // guessing from `javaHome` path naming. Falls back to null if the
    // file isn't there or doesn't parse (e.g. a non-standard install).
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
   * Report the project's compose-preview **version pin** — the one place a project names the
   * version every entrypoint should use (issue #3738; see [resolveVersionPin]).
   *
   * Three outcomes, and none of them is an error:
   * - **no pin** — `ok`, with the "how to pin" remediation. The zero-config path is legitimate:
   *   each entrypoint uses its own bundled version, which is fine for a single-machine project.
   * - **pin matches this CLI** — `ok`, the happy state.
   * - **pin differs from this CLI** — `warning`. The pinned plugin is what gets injected, but the
   *   daemon and renderer this CLI ships are stuck at [BUNDLE_VERSION], so across a major (where
   *   the render/daemon wire format changes — docs/VERSIONING.md § 3) they can genuinely disagree.
   *   A `-SNAPSHOT` on either side stays `ok`: a local snapshot build driving a pinned project is a
   *   deliberate development flow, not a misconfiguration.
   */
  /**
   * Report the project's **preview server** — the host `share-preview` uploads rendered evidence to
   * when the project names one (see [resolveProjectServeUrl]).
   *
   * Reported because it changes what a command does without appearing on its command line: a
   * project with this set makes `share-preview` upload by default where it would otherwise create a
   * gist, and "why did my render end up on a website" should be answerable by `doctor` rather than
   * by reading the source. Never an error — not naming a host is the normal state.
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
    // The same validation the upload performs, so `doctor` can't hand out a clean bill of health
    // for a configuration the command will refuse — which is precisely the configuration it exists
    // to diagnose.
    ServeImageUploader.rejectUnsafeUrl(configured.url)?.let { refusal ->
      addCheck(
        DoctorCheck(
          id = "project.preview-server",
          category = "project",
          status = "error",
          // Redacted: a URL is refused *because* it carries credentials, and this message is where
          // that URL would otherwise reach a terminal, a CI log and --json output.
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
        // Same identity `share-preview` will use, so the two cannot disagree about whether a
        // repo-scoped confirmation applies.
        originRepo = gitOriginRepo(projectDir),
        fileSystem = fileSystem,
      )
    if (trust is ServeUrlTrust.NeedsConfirmation) {
      addCheck(
        DoctorCheck(
          id = "project.preview-server",
          category = "project",
          // A warning, not an error: nothing is broken, and refusing to act on an unconfirmed
          // host is the safe behaviour working as designed. The operator just isn't getting the
          // upload they may be expecting.
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
    // Remediation snippets elsewhere in the report ("apply the plugin with version X") should name
    // the version the project has actually chosen, not this CLI's build.
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
   * Warn when the Gradle daemon's JVM is past [AGP_JDK_CEILING] and any module on this project
   * applies AGP. Motivated by issue #1544: AGP's `JdkImageTransform` invokes the daemon JDK's
   * `jlink` to materialise `android.jar`'s system modules, and on JDK 26 that has been reported
   * failing on `core-for-system-modules.jar`. The same JDK + configuration-cache combination also
   * can't serialise `JdkImageInput.generatedModuleFile` (a `TransformBackedProvider`). Neither
   * failure mode is compose-preview-specific — they reproduce with plain AGP tasks too — but doctor
   * is where consumers come when their first `compose-preview` invocation blows up, so we flag the
   * env mismatch here.
   *
   * Threshold is the last AGP-blessed LTS at time of writing. Bump in one place ([AGP_JDK_CEILING])
   * once AGP officially supports a newer LTS. Skipped on CMP Desktop-only projects — the
   * JdkImageTransform path only runs under AGP.
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
   * Emits `env.desktop-natives` when any module renders through the CMP Desktop (skiko) path.
   *
   * Gated on [rendersThroughSkiko] rather than run unconditionally: an Android-only project renders
   * under Robolectric and never touches `libskiko`, so the check would be pure noise there. On a
   * project that *does* include CMP, this is the check that turns the otherwise-opaque
   * `UnsatisfiedLinkError: libGL.so.1: cannot open shared object file` into a named, fixable
   * environment problem — before the user spends a full render cycle discovering it.
   *
   * The JVM we evaluate against is the Gradle daemon's ([daemonJavaHome], captured by
   * [checkGradleDaemon]), because that's what the desktop render path forks from. `LD_LIBRARY_PATH`
   * comes from this process's *environment*, which is the same value the daemon and the render
   * subprocess inherit — so a variable that was set but never exported reads as unset here, exactly
   * as it does at render time.
   */
  private fun checkDesktopNatives(modules: Map<String, ModuleInfo>) {
    val desktopModules = modules.filterValues { rendersThroughSkiko(it) }
    if (desktopModules.isEmpty()) return

    // Evaluate against every JVM a render could actually fork into, not just the daemon's. A module
    // whose render task carries its own launcher (a raised render JDK, or an explicit
    // `composePreview.renderJavaVersion`) is the case that matters most here: store libraries on
    // the path of a *system* JDK is the mixed-glibc trap, and reading the daemon's `java.home`
    // instead would report a Nix daemon as healthy while the render dies (issue #3690).
    // Per module: its own launcher when the model reports one, the daemon only as *that module's*
    // fallback. Adding the daemon unconditionally would judge a JVM nothing renders on — and since
    // the worst verdict wins below, an unused daemon that cannot resolve a library would fail
    // doctor for a project whose every render is fine.
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
        // Resolved through symlinks so a store lib dir reached via a link farm
        // (`~/.cache/coo-ee/desktop-gl/lib`, `~/.nix-profile/lib`) is still recognised as one.
        canonicalize = canonicalize,
      )
    }
    // The worst verdict wins: one render JVM that cannot load skiko breaks that module's previews
    // regardless of how healthy the others look. Ranked by severity rather than by "first not-ok",
    // because the candidates are ordered by where they came from — so an earlier candidate's
    // warning would otherwise hide a later one whose renders cannot work at all, and warnings exit
    // 0. Same order [DesktopNativesCheck.interpret] uses, so the selected result and the status it
    // is reported under agree.
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
              // The desktop render task is not a `Test`, so the Tooling model does not report its
              // launcher: a CMP module that pins `composePreview.renderJavaVersion` is invisible
              // here. Only worth saying where it could change the answer — a store daemon with
              // store libraries on the path, which is exactly the healthy-looking configuration
              // that a pinned system render JDK turns into the #3690 failure.
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
   * Whether [info]'s previews render through skiko (CMP Desktop) rather than Robolectric (Android).
   *
   * Primary signal is skiko itself on a resolved classpath — that's the artifact that carries the
   * native `.so`, so its presence is precisely the condition under which the native deps matter.
   * The `agpVersion == null` fallback covers a module whose classpath didn't resolve (doctor treats
   * empty dep maps as "not checkable" elsewhere too): no AGP means no Robolectric path, so Desktop
   * is the only renderer left.
   */
  private fun rendersThroughSkiko(info: ModuleInfo): Boolean {
    val deps = info.mainRuntimeDependencies.keys + info.testRuntimeDependencies.keys
    if (deps.any { it.startsWith("org.jetbrains.skiko:") }) return true
    return deps.isEmpty() && info.agpVersion == null
  }

  /**
   * Emits per-module version info — AGP, Kotlin, Robolectric, Compose runtime.
   * Robolectric/Compose-runtime are read from the resolved test classpath; AGP/Kotlin are
   * plugin-side reflective reads. All four are surfaced as an `info`-style ok-status check so
   * `--report` has one pasteable block with everything a triager needs to see.
   */
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
   * The check motivated by issue #142. If the test worker's forked JDK is a different major than
   * the Gradle daemon's, emit a `warning` with the specific error signature the mismatch typically
   * produces, plus a remediation pointing at the `javaLauncher` toolchain wiring.
   *
   * When the JDK majors match (or when we can't tell), this degrades to a pure info line — still
   * useful in bug reports because it fingerprints the launcher vendor and path.
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
   * Scans HTML reports under `build/reports/tests/composePreviewRender/` for known error signatures
   * and emits a per-module hint when it spots one. Purely pattern-based — we only match signatures
   * we've seen in field reports, so false positives are rare and actionable. Best-effort: if
   * there's no report on disk (first run, clean checkout) we silently skip.
   */
  private fun checkErrorSignatures(projectDir: File, modulePath: String) {
    // Gradle path → filesystem path. `:auth:composables` → `auth/composables`
    // for standard layouts (issue #157). Custom `project.projectDir`
    // overrides aren't covered here — this is a best-effort triage path;
    // when the directory doesn't exist we silently skip the signature
    // scan rather than emit a false "no prior failure" signal.
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
   * Render findings produced plugin-side (see [CompatRules] in gradle-plugin) as doctor checks. CLI
   * doesn't run compat logic of its own — one source of truth, consumed by both CLI and VS Code.
   * Rule thresholds and remediation phrasing live in the plugin.
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
      // Short-form detail is the default; `--explain` also surfaces
      // the long-form detail from the plugin (the "why this breaks at
      // render time" rationale).
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
   * Grep-based "is your compose-bom recent enough" pre-flight. Runs BEFORE any Gradle call, so it
   * works even outside a Gradle project or before the plugin is applied — complements the
   * plugin-side `CompatRules` findings, which only fire once Gradle has resolved the test
   * classpath. The renderer's own `MeasuredWrapBox` links against
   * `ComposeUiNode.Companion.getApplyOnDeactivatedNodeAssertion`, first shipped in compose-ui
   * 1.10.0 (compose-bom 2025.12.00). This wrapper is used by the standalone `composePreviewRender`
   * task as well as daemon-backed rendering, so even a simple preview cannot stay on a 1.9.x render
   * classpath.
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
   * Returns `(fileWhereFound, parsedVersion)` for every `androidx.compose:compose-bom` version
   * literal we find under [root]. Scans `gradle/libs.versions.toml` and every `build.gradle[.kts]`,
   * early-exiting at depth 4 so we don't wander through `build/`.
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

  /**
   * Compose BOM version in `YYYY.MM.NN` form. Enough precision for "is this older than 2025.01"; we
   * never need to differentiate patches.
   */
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
   * Compact, paste-friendly fingerprint block intended for GitHub issue reports. Prints everything
   * a triager needs upfront so the reporter doesn't have to re-run `gradlew --version`, `java
   * -version`, etc. across multiple follow-up comments. Structure is flat key-value so grep-parsing
   * from an agent is cheap, and the schema string anchors the v1 contract the same way
   * [DoctorReport.schema] does.
   *
   * Each block (env / modules / errors) only prints when we have data — e.g. module versions are
   * omitted when no project was detected.
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
   * Compares the installed CLI's [BUNDLE_VERSION] against the latest GitHub release tag. Mirrors
   * the resolution trick in `scripts/install.sh` — a HEAD against the public `releases/latest`
   * redirect, not `api.github.com`, because the API rate-limits unauthenticated callers on shared
   * sandbox IPs (Claude Code cloud, GitHub Actions free tier, etc.) and would 403.
   *
   * Failure modes are non-fatal:
   * - Unreachable network → `skipped` check, single-line note. Same env-var opt-out
   *   (`COMPOSE_PREVIEW_DOCTOR_SKIP_NETWORK=1`) as the rest of network probes.
   * - Latest tag unparseable → `skipped`. Conservative: never call something "out of date" we can't
   *   verify.
   * - Installed == latest → `ok`.
   * - Installed older → `warning` with a `compose-preview update` remediation. Newer-than-latest
   *   (SNAPSHOT or pre-release) is treated as `ok` — these come from local builds and shouldn't
   *   nag.
   */
  private fun checkBundleVersion() {
    val latestUrl = "https://github.com/$REPO/releases/latest"
    val resolved =
      try {
        httpProbeClient().use { client ->
          runBlocking {
            // HEAD with redirects followed (Ktor follows for HEAD by default); the final request
            // URL is the `…/releases/tag/v<version>` the `releases/latest` 302 lands on.
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
   * Probe Google-controlled hosts that the Android render path and Compose's downloadable-fonts
   * integration depend on at build + render time. Each host becomes one `env.network.<id>` check;
   * an unreachable host is a warning (it only matters for specific consumers) and points at the
   * Claude Code on-the-web Custom-allowlist docs, since this is the most common place the checks
   * fail.
   */
  private fun checkNetworkReach() {
    NETWORK_HOSTS.forEach { probe ->
      val (code, headers) = headPlain(probe.url)
      val id = "env.network.${probe.id}"
      val check =
        if (code > 0) {
          DoctorCheck(
            id = id,
            category = "env",
            status = "ok",
            message = "${probe.host} reachable (HTTP $code)",
          )
        } else {
          val summary =
            if (inClaudeCloud) {
              "Claude Code cloud session detected — switch the session's network level from Trusted to **Custom**, keep 'include Trusted defaults' on, and add `${probe.host}` (plus the other three Google hosts probed here) to the allowlist."
            } else {
              "Allow ${probe.host} in your sandbox / proxy configuration. In Claude Code cloud sessions this means switching network access to Custom and adding the host (keep 'include Trusted defaults' on)."
            }
          DoctorCheck(
            id = id,
            category = "env",
            status = "warning",
            message = "${probe.host} unreachable",
            detail = "${probe.purpose}. Error: ${headers["error"] ?: "unknown"}.",
            remediation =
              DoctorRemediation(
                summary = summary,
                docs = "https://code.claude.com/docs/en/claude-code-on-the-web#network-access",
              ),
          )
        }
      addCheck(check)
    }
  }

  /**
   * HEAD [url] and return its status code + response headers, or `-1 to {error}` when the host is
   * unreachable (never throws). A non-2xx is still a real response — its code comes back unchanged
   * so [checkNetworkReach] reports "reachable (HTTP 404)" rather than a failure.
   */
  internal fun headPlain(url: String): Pair<Int, Map<String, String>> =
    try {
      httpProbeClient().use { client ->
        runBlocking {
          val response = client.head(url) { header("User-Agent", USER_AGENT) }
          val headers = response.headers.entries().associate { (k, v) -> k to v.joinToString(", ") }
          response.status.value to headers
        }
      }
    } catch (e: Exception) {
      -1 to mapOf("error" to (e.message ?: e.javaClass.simpleName))
    }

  /**
   * A Ktor/OkHttp client tuned for doctor's one-shot HEAD probes: 3s connect + request timeouts,
   * redirects followed (the engine default for HEAD). One client per probe — doctor isn't a hot
   * path, and `use {}` tears the connection pool down immediately. Mirrors the Ktor/OkHttp client
   * the rest of the CLI already uses (see [BundleSource]).
   */
  private fun httpProbeClient(): HttpClient =
    HttpClient(OkHttp) {
      install(HttpTimeout) {
        connectTimeoutMillis = 3_000
        requestTimeoutMillis = 3_000
      }
    }

  /**
   * Sanitise a module path (e.g. `:app` → `app`, `:samples:wear` → `samples:wear`) for use in check
   * ids.
   */
  private fun idSafe(modulePath: String): String = modulePath.removePrefix(":").ifEmpty { "root" }

  /**
   * Exec a short-lived command and capture its stdout/stderr. Returns `null` if the executable
   * wasn't found, the process failed to start, or it didn't finish within 5s — doctor folds all of
   * those into "skip the check" rather than erroring on what's essentially optional fingerprinting.
   */
  private fun runCommand(cmd: List<String>): CommandResult? {
    return try {
      val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
      // Drain stderr concurrently: reading stdout to EOF before touching stderr deadlocks if the
      // child fills the stderr pipe buffer first, and the 5s `waitFor` guard below can never fire
      // while we're blocked on that read. (`java -version` prints to stderr, so this path matters.)
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
    /**
     * `java -version` prints to stderr on most JDKs; fall back to stdout for the occasional one
     * that doesn't.
     */
    fun stderrOrStdout(): String = stderr.ifBlank { stdout }
  }

  /**
   * Reads the JDK major version from `$javaHome/release`. Most JDK distributions ship this file
   * with a `JAVA_VERSION=...` line — more reliable than guessing from the install path, which
   * varies wildly (`/usr/lib/jvm/temurin-21-jdk-amd64` vs `/opt/homebrew/opt/openjdk@21` vs
   * `~/.sdkman/candidates/java/21.0.11-tem`). Returns `null` when the file is missing or malformed.
   */
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
   * One known `composePreviewRender` failure signature. Pattern is a plain substring we look for in
   * the HTML test report; [hint] is the human explanation; [remediation] is the same action
   * structure the rest of doctor emits, so agents get concrete commands out of this too.
   */
  private data class ErrorSignature(
    val pattern: String,
    val hint: String,
    val remediation: DoctorRemediation?,
  )

  companion object {
    /**
     * Minimum supported Compose BOM — 2025.12.00 → compose-ui 1.10.0. That's the first BOM whose
     * runtime exposes `ComposeUiNode.Companion.getApplyOnDeactivatedNodeAssertion`, linked by the
     * renderer's `MeasuredWrapBox` on both standalone and daemon-backed Android renders.
     */
    private const val MIN_BOM_YEAR = 2025
    private const val MIN_BOM_MONTH = 12

    internal fun isComposeBomBelowRenderFloor(raw: String): Boolean? =
      ComposeVersion.parse(raw)?.isOlderThan(MIN_BOM_YEAR, MIN_BOM_MONTH)

    /** User-Agent for doctor's HEAD probes — matches the string `scripts/install.sh` sends. */
    private const val USER_AGENT = "compose-preview-doctor"

    /**
     * Highest JDK we trust to drive AGP without surfacing the issue-1544-class footguns
     * (`JdkImageTransform` / configuration-cache serialisation of `TransformBackedProvider`). JDK
     * 21 is the last AGP-blessed LTS at time of writing. Bump once AGP officially supports a newer
     * LTS — `checkDaemonJdkForAgp` keys off this value.
     */
    private const val AGP_JDK_CEILING = 21

    private val SKIP_DIRS = setOf("build", "node_modules", "out", "dist", ".gradle")

    private data class NetworkHost(
      val id: String,
      val host: String,
      val url: String,
      val purpose: String,
    )

    /**
     * Google-controlled hosts required by the Android/Compose render paths. None are on Claude
     * Code's default Trusted allowlist — they only resolve in Custom mode or in environments with
     * broader egress.
     */
    private val NETWORK_HOSTS =
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
          url = "https://fonts.googleapis.com/",
          purpose =
            "Google Fonts API — used by androidx.compose.ui:ui-text-google-fonts at render time",
        ),
        NetworkHost(
          id = "fonts-gstatic",
          host = "fonts.gstatic.com",
          url = "https://fonts.gstatic.com/",
          purpose = "Google Fonts static asset host — downloadable-font binaries",
        ),
      )

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * Failure signatures the CLI recognises from `composePreviewRender` HTML reports. Order matters
     * — the first match wins. Keep the list curated: only patterns we've traced to a specific,
     * actionable root cause belong here. Patterns that overlap benign test output produce false
     * positives.
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

// --- Report schema ---------------------------------------------------------
// Stable public contract for external consumers — keep backwards-compatible
// within a major schema version. Schema version lives in [DoctorReport.schema].

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
