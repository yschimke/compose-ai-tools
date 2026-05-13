package ee.schimke.composeai.cli

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `compose-preview profile <path.json>` — runs a saved [Profile] by translating its fields into the
 * equivalent CLI flag set and delegating to [ReportCommand]. Thin on purpose: profiles are a
 * "captured flag combination," not a programming model. Anything richer (predicate filters,
 * per-result hooks, multi-renderer orchestration) is tracked on issue #1084 (Kotlin scripting).
 *
 * Flags passed alongside the profile path are appended **after** the synthesised args from the
 * profile, so ad-hoc tweaks like `compose-preview profile auth-a11y.json --json --changed-only`
 * override / extend the profile rather than being overridden by it. Same left-to-right semantics as
 * every other CLI command — the last `--flag value` wins.
 */
class ProfileCommand(private val rawArgs: List<String>) {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  fun run() {
    val path = rawArgs.firstOrNull { !it.startsWith("-") }
    if (path == null) {
      System.err.println("Usage: compose-preview profile <path.json> [flags…]")
      System.err.println(
        "Run `compose-preview extensions list` to see available extension ids " +
          "for the `extensions` / `failOn` / `report` fields."
      )
      exitProcess(1)
    }

    val profile = readProfile(File(path))
    val extraArgs = rawArgs.filter { it != path }

    val reportExtensionId = resolveReportExtension(profile)
    val syntheticArgs = synthesiseArgs(profile, reportExtensionId, extraArgs)

    // Delegate to the existing renderer pipeline. `ReportCommand` is open (declared `open
    // class` for exactly this kind of binding) and its `implicitExtensions()` already wires the
    // chosen extension; the profile's `extensions` list flows in via `--with-extension`.
    ReportCommand(syntheticArgs, reportExtensionId).run()
  }

  private fun readProfile(file: File): Profile {
    if (!file.exists()) {
      System.err.println("Profile not found: ${file.path}")
      exitProcess(1)
    }
    val parsed =
      try {
        json.decodeFromString(Profile.serializer(), file.readText())
      } catch (e: SerializationException) {
        System.err.println("Could not parse profile ${file.path}: ${e.message}")
        exitProcess(1)
      }
    if (parsed.schema !in ACCEPTED_PROFILE_SCHEMAS) {
      System.err.println(
        "Unsupported profile schema '${parsed.schema}' in ${file.path}. " +
          "Expected one of: ${ACCEPTED_PROFILE_SCHEMAS.joinToString(", ")}."
      )
      exitProcess(1)
    }
    return parsed
  }

  /**
   * Returns the renderer id `ReportCommand` will use. Defaults to [Profile.report] if set,
   * otherwise the first entry of [Profile.extensions]. Exits 1 if the chosen id has no registered
   * renderer — the profile is asking us to "print the canned report for X" and we don't know how to
   * print one for X.
   */
  internal fun resolveReportExtension(profile: Profile): String {
    val known = builtInExtensionReporters().keys
    val candidate = profile.report ?: profile.extensions.firstOrNull()
    if (candidate == null) {
      System.err.println(
        "Profile must set `report` or include at least one entry in `extensions` " +
          "so the CLI knows which canned report to render. " +
          "Known extensions: ${known.sorted().joinToString(", ")}."
      )
      exitProcess(1)
    }
    if (candidate !in known) {
      System.err.println(
        "Profile references unknown extension '$candidate'. " +
          "Known extensions: ${known.sorted().joinToString(", ")}."
      )
      exitProcess(1)
    }
    return candidate
  }

  /**
   * Synthesises the flag list `ReportCommand` will parse. Mirrors what a user would have typed:
   * `--with-extension <id>` per profile extension (except the chosen report extension, which
   * `ReportCommand.implicitExtensions()` adds automatically), `--module` / `--filter` / `--id` /
   * `--changed-only` per filter axis, and `--fail-on <level>` for the report extension's threshold
   * if set. Trailing [extraArgs] preserve user overrides — last write wins, same as on the real
   * CLI.
   */
  internal fun synthesiseArgs(
    profile: Profile,
    reportExtensionId: String,
    extraArgs: List<String>,
  ): List<String> = buildList {
    // Don't double-add the report extension — `ReportCommand.implicitExtensions()` already
    // ensures it's enabled. Adding it via `--with-extension` would be deduplicated by
    // `extensionGradleArgs()` anyway, but we keep the synthesised args clean for diagnostic
    // logging.
    for (ext in profile.extensions) {
      if (ext == reportExtensionId) continue
      add("--with-extension")
      add(ext)
    }
    profile.filter.module?.let {
      add("--module")
      add(it)
    }
    profile.filter.idSubstring?.let {
      add("--filter")
      add(it)
    }
    profile.filter.id?.let {
      add("--id")
      add(it)
    }
    if (profile.filter.changedOnly) add("--changed-only")
    profile.failOn[reportExtensionId]?.let {
      add("--fail-on")
      add(it)
    }
    addAll(extraArgs)
  }
}
