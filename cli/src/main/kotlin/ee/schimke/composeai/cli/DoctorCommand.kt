package ee.schimke.composeai.cli

import ee.schimke.composeai.plugin.tooling.ModuleInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 *
 * **Project** (runs when a `settings.gradle[.kts]` is found at `--project` or cwd):
 * - Plugin applied to at least one module
 * - Consumer's test-runtime classpath vs main-variant classpath satisfies the AAR/R.id
 *   version-alignment rules for preview rendering. Checks:
 *     - `deps.<module>.ui-test-manifest` — ui-test-manifest on test classpath
 *     - `deps.<module>.activity-vs-navigationevent` — navigationevent on test, older activity on
 *       main
 *     - `deps.<module>.compose-ui-vs-core` — compose-ui 1.10+ on test, older androidx.core on main
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
 * Exits 0 when no errors (warnings OK), 1 when any check reports ERROR.
 */
class DoctorCommand(args: List<String>) {
  private val jsonOut = "--json" in args
  private val reportOut = "--report" in args
  private val explain = "--explain" in args
  private val verbose = "--verbose" in args || "-v" in args
  private val projectDirArg = args.flagValue("--project")

  /**
   * Version the CLI suggests in remediation messages ("install version X"). Comes from
   * `--plugin-version` or the CLI's compiled-in default. Distinct from [appliedPluginVersion],
   * which is what the project actually has on its classpath.
   */
  private val recommendedPluginVersion =
    args.flagValue("--plugin-version") ?: DEFAULT_PLUGIN_VERSION

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
      checkNetworkReach()
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
   * the forked `renderPreviews` test worker picked up the system default, because the Test task's
   * `javaLauncher` wasn't pinned to the project toolchain. Separating the CLI JVM from the PATH JVM
   * makes that delta visible in the first line of output.
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
                "curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh | bash"
              ),
            docs =
              "https://github.com/$REPO/blob/main/skills/compose-preview/design/CLAUDE_CLOUD.md",
          ),
      )
    )
  }

  // --- Project checks -----------------------------------------------------

  private var daemonGradleVersion: String? = null
  private var daemonJavaHome: String? = null
  private var daemonJavaMajor: Int? = null

  private fun runProjectChecks(projectDir: File) {
    val model =
      try {
        GradleConnection(projectDir, verbose = verbose).use { gc ->
          // Daemon-JVM + Gradle-version snapshot. Runs first so other
          // project-scope checks can compare against the daemon's JDK
          // (e.g. flagging test worker mismatch in #142).
          checkGradleDaemon(gc)
          gc.runBuildAction(GatherComposePreviewModelAction())
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

    if (model == null || model.modules.isEmpty()) {
      addCheck(
        DoctorCheck(
          id = "project.plugin-applied",
          category = "project",
          status = "error",
          message = "no modules have the compose-preview plugin applied",
          remediation =
            DoctorRemediation(
              summary = "Apply the plugin in your module's `plugins { }` block.",
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
      val skew = applied != recommendedPluginVersion
      addCheck(
        DoctorCheck(
          id = "project.plugin-version",
          category = "project",
          status = "ok",
          message = "compose-preview plugin v$applied",
          detail =
            if (skew) "CLI is on $recommendedPluginVersion — bump the plugin to align" else null,
        )
      )
    }

    for ((modulePath, info) in model.modules) {
      checkModuleVersions(modulePath, info)
      checkRenderPreviewsTask(modulePath, info)
      checkModuleCompat(modulePath, info)
      checkErrorSignatures(projectDir, modulePath)
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
            "$modulePath — renderPreviews will fork JDK $launcherMajor, Gradle daemon runs JDK $daemonJavaMajor",
          detail =
            "$detail; symptom on mismatch: `ClassNotFoundException: android.app.Application` during JUnit discovery (see issue #142)",
          remediation =
            DoctorRemediation(
              summary = "Pin the renderPreviews Test task to the project's Java toolchain.",
              commands =
                listOf(
                  "kotlin { jvmToolchain(${daemonJavaMajor ?: 21}) }",
                  "// or: tasks.named(\"renderPreviews\", Test::class) { javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(${daemonJavaMajor ?: 21})) }) }",
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
          message = "$modulePath — renderPreviews launcher JDK ${launcherMajor ?: "?"}",
          detail = detail,
        )
      )
    }
  }

  /**
   * Scans HTML reports under `build/reports/tests/renderPreviews/` for known error signatures and
   * emits a per-module hint when it spots one. Purely pattern-based — we only match signatures
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
    val reportDir = File(moduleDir, "build/reports/tests/renderPreviews")
    if (!reportDir.isDirectory) return
    val htmls =
      reportDir.walkTopDown().maxDepth(4).filter { it.isFile && it.extension == "html" }.toList()
    if (htmls.isEmpty()) return

    val haystack =
      htmls
        .asSequence()
        .mapNotNull {
          try {
            it.readText()
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
          message = "$modulePath — last renderPreviews run failed with a known signature",
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
   * classpath. Renderer-android is compiled with compose- compiler 2.2.21, which emits calls to
   * `ComposeUiNode.setCompositeKeyHash` — first shipped in compose-ui 1.9 (compose-bom 2025.01.00).
   * Older consumers hit `NoSuchMethodError` the moment `renderPreviews` starts.
   */
  private fun checkComposeBomVersion() {
    val workspace = File(projectDirArg ?: ".").canonicalFile
    val versions = findComposeBomDeclarations(workspace)
    if (versions.isEmpty()) return // No declarations → nothing to assert on.

    val tooOld = versions.filter { (_, v) -> v.isOlderThan(MIN_BOM_YEAR, MIN_BOM_MONTH) }
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
            "Older BOMs lack `ComposeUiNode.setCompositeKeyHash`; `renderPreviews` will fail with NoSuchMethodError.",
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
          file.readText()
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

  private fun headPlain(url: String): Pair<Int, Map<String, String>> {
    val conn =
      (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = 3_000
        readTimeout = 3_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "compose-preview-doctor")
      }
    return try {
      val code = conn.responseCode
      val headers =
        conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
      code to headers
    } catch (e: Exception) {
      -1 to mapOf("error" to (e.message ?: e.javaClass.simpleName))
    } finally {
      conn.disconnect()
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
      val stdout = process.inputStream.bufferedReader().use { it.readText() }
      val stderr = process.errorStream.bufferedReader().use { it.readText() }
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      CommandResult(process.exitValue(), stdout, stderr)
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
        release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }
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
   * One known `renderPreviews` failure signature. Pattern is a plain substring we look for in the
   * HTML test report; [hint] is the human explanation; [remediation] is the same action structure
   * the rest of doctor emits, so agents get concrete commands out of this too.
   */
  private data class ErrorSignature(
    val pattern: String,
    val hint: String,
    val remediation: DoctorRemediation?,
  )

  companion object {
    private const val REPO = "yschimke/compose-ai-tools"
    private const val DEFAULT_PLUGIN_VERSION = "0.8.8" // x-release-please-version

    /**
     * Minimum supported Compose BOM — 2025.01.00 → compose-ui 1.9.0. That's the first BOM where
     * `ComposeUiNode.setCompositeKeyHash` (emitted by compose-compiler 2.2.21) exists on the
     * runtime side.
     */
    private const val MIN_BOM_YEAR = 2025
    private const val MIN_BOM_MONTH = 1

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
     * Failure signatures the CLI recognises from `renderPreviews` HTML reports. Order matters — the
     * first match wins. Keep the list curated: only patterns we've traced to a specific, actionable
     * root cause belong here. Patterns that overlap benign test output produce false positives.
     */
    private val KNOWN_ERROR_SIGNATURES =
      listOf(
        ErrorSignature(
          pattern = "ClassNotFoundException: android.app.Application",
          hint = "likely test-worker JVM mismatch (see issue #142)",
          remediation =
            DoctorRemediation(
              summary = "Pin the renderPreviews Test task's javaLauncher to the project toolchain.",
              commands = listOf("kotlin { jvmToolchain(21) }"),
              docs = "https://github.com/$REPO/issues/142",
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
          pattern = "NoSuchMethodError: androidx.compose.runtime.ComposeUiNode",
          hint =
            "compose-bom too old — renderer-compiled calls postdate the runtime on the consumer's classpath",
          remediation = DoctorRemediation(summary = "Bump compose-bom to at least 2025.01.00."),
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
