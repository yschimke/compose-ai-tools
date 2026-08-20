package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.io.StringReader
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The **project version pin** — one place a consumer names the compose-preview version, honoured by
 * every entrypoint (issue #3738).
 *
 * Before this, each entrypoint picked a version on its own: the CLI auto-injected the plugin at its
 * own [BUNDLE_VERSION], the VS Code extension at its bundled `BUNDLED_PLUGIN_VERSION`, and the
 * `install` / `apply` composite actions at whatever their `version:` input resolved to (`latest` by
 * default). A project driven from more than one of those — the normal case: a developer's CLI, a
 * teammate's VS Code, and CI — silently rendered against three different releases, which is the
 * skew class issue #1920 documented from the CI side. The pin is the fix: **name the version once,
 * and every entrypoint reads it from the project.**
 *
 * # Sources, in precedence order
 * 1. `--plugin-version <v>` on the CLI invocation — a per-run override, nothing is read from disk.
 * 2. `COMPOSE_PREVIEW_VERSION` in the environment — the CI / container override.
 * 3. `gradle.properties` → `composePreview.version` — the canonical pin, and what
 *    [writeGradlePropertiesPin] (`compose-preview pin <version>`) writes. Universal: every Gradle
 *    project has this file, no version catalog required, and it sits in the same `composePreview.*`
 *    namespace as the plugin's other Gradle-property knobs.
 * 4. `gradle/libs.versions.toml` → `[versions] composePreviewCli` — the pre-existing, Renovate-
 *    friendly convention the `install` / `apply` actions already read via `version: catalog`. Kept
 *    as a source so consumers who already pin that way get the CLI and extension honouring it for
 *    free, with no new file to add.
 *
 * Nothing found → `null`, and the caller falls back to its own bundled version. That keeps the
 * zero-config path exactly as it was: a project with no pin behaves as it did before.
 *
 * # Scope: the pin governs auto-inject
 * The pin decides what **auto-inject** applies — the zero-config path ([autoInjectInitScriptArgs])
 * where the CLI and the extension inject the plugin via `--init-script`, which is how the large
 * majority of consumers run. A module that declares the plugin itself (`id("…") version "…"` or a
 * catalog alias) keeps its own version and the pin does not touch it. That is deliberate, not a
 * gap: [scanForComposeAiPreviewDeclaration] already skips injecting into such a module — Gradle's
 * `plugins {}` DSL rejects `id(…) version "…"` when the same plugin is also on the buildscript
 * classpath — so the build script's declaration is the only version in play there. Nothing here
 * ever rewrites a build script or a version catalog.
 *
 * # What a pin does *not* do
 * It cannot change the version of the binary already running. A CLI on 1.1.0 driving a project
 * pinned to 1.0.5 injects the **pinned** plugin (the pin is authoritative — that is the point) and
 * warns once, pointing at `compose-preview update`. [warnOnCliSkew] owns that message; `doctor`
 * reports the same state as a `project.version-pin` check.
 *
 * Kept in lockstep with the VS Code extension's `versionPin.ts` and the composite actions'
 * `resolve-version.py` (`version: pin`) — three implementations of one precedence list, each
 * covered by its own tests.
 */
internal const val VERSION_PIN_PROPERTY = "composePreview.version"

/** Environment override, read after `--plugin-version` and before anything on disk. */
internal const val VERSION_PIN_ENV = "COMPOSE_PREVIEW_VERSION"

/** Default version-catalog path scanned for [VERSION_PIN_CATALOG_KEY]. */
internal const val VERSION_PIN_CATALOG_PATH = "gradle/libs.versions.toml"

/**
 * `[versions]` key read from the catalog. Matches the `catalog-key` default the `install` and
 * `apply` composite actions already document, so a project pinned for CI is pinned for the CLI.
 */
internal const val VERSION_PIN_CATALOG_KEY = "composePreviewCli"

/**
 * Walks up from [start] looking for the Gradle wrapper, returning the first directory that has one
 * — the project root the pin is read from (and the directory `compose-preview pin <version>` writes
 * into). Null when there is no Gradle project above [start].
 *
 * Shared by [Command.findProjectRoot] and [PinCommand] so "which directory is the project" has
 * exactly one answer across the CLI.
 */
internal fun findGradleProjectRoot(start: File = File(".").absoluteFile): File? {
  var dir: File? = start
  while (dir != null) {
    if (File(dir, "gradlew").exists()) return dir
    dir = dir.parentFile
  }
  return null
}

/** Where a resolved pin came from. Ordered by precedence — first match wins. */
internal enum class VersionPinSource(val display: String) {
  FLAG("--plugin-version"),
  ENV(VERSION_PIN_ENV),
  GRADLE_PROPERTIES("gradle.properties ($VERSION_PIN_PROPERTY)"),
  VERSION_CATALOG("$VERSION_PIN_CATALOG_PATH ([versions] $VERSION_PIN_CATALOG_KEY)"),
}

/** A pin that was actually found, plus which source supplied it. */
internal data class ResolvedVersionPin(val version: String, val source: VersionPinSource)

/**
 * Resolves the project's version pin, or `null` when nothing pins a version.
 *
 * [projectRoot] is the Gradle root (the directory holding `gradlew`); pass `null` when no project
 * has been located yet — the flag and environment sources still apply. Every disk read goes through
 * [fileSystem] and is failure-tolerant: an unreadable or malformed `gradle.properties` / catalog
 * falls through to the next source rather than failing the run, because a broken pin must never be
 * worse than no pin.
 */
internal fun resolveVersionPin(
  projectRoot: File?,
  args: List<String> = emptyList(),
  env: (String) -> String? = System::getenv,
  fileSystem: FileSystem = SystemFileSystem,
): ResolvedVersionPin? {
  args.flagValue("--plugin-version")?.normalizedPin()?.let {
    return ResolvedVersionPin(it, VersionPinSource.FLAG)
  }
  env(VERSION_PIN_ENV)?.normalizedPin()?.let {
    return ResolvedVersionPin(it, VersionPinSource.ENV)
  }
  if (projectRoot == null) return null
  readGradlePropertiesPin(projectRoot, fileSystem)?.let {
    return ResolvedVersionPin(it, VersionPinSource.GRADLE_PROPERTIES)
  }
  readCatalogPin(projectRoot, fileSystem)?.let {
    return ResolvedVersionPin(it, VersionPinSource.VERSION_CATALOG)
  }
  return null
}

/**
 * Trims a raw pin value and drops a leading `v` (`v1.1.0` → `1.1.0`), matching what
 * `resolve-version.py` does with catalog and literal inputs. Blank values are treated as absent so
 * an empty `composePreview.version=` line doesn't pin the project to the empty string.
 */
private fun String.normalizedPin(): String? = trim().removePrefix("v").takeIf { it.isNotEmpty() }

/**
 * Reads `composePreview.version` from [projectRoot]`/gradle.properties`.
 *
 * Parsed with [Properties] (loaded from the Okio-read text, so the file access still goes through
 * the injected [fileSystem]) rather than a hand-rolled `split('=')`: `gradle.properties` is a Java
 * properties file, so `key : value`, continuation lines, and escapes are all legal and a naive
 * parser would silently misread them.
 */
internal fun readGradlePropertiesPin(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
): String? = readGradleProperty(projectRoot, VERSION_PIN_PROPERTY, fileSystem)?.normalizedPin()

/**
 * One `composePreview.*` value out of [projectRoot]`/gradle.properties`, trimmed, or null when the
 * file, the parse, or the key is missing. The raw value — a caller that needs it normalised (the
 * version pin strips a leading `v`) does that itself, since no other property wants it.
 *
 * Extracted from [readGradlePropertiesPin] when the preview-server URL became the second thing read
 * this way ([resolveProjectServeUrl]): both want the same failure-tolerant read of the same file,
 * and two copies would be two places to get properties-file escaping wrong.
 */
internal fun readGradleProperty(
  projectRoot: File,
  key: String,
  fileSystem: FileSystem = SystemFileSystem,
): String? {
  val file = File(projectRoot, "gradle.properties")
  val text =
    runCatching { fileSystem.read(file.path.toPath()) { readUtf8() } }.getOrNull() ?: return null
  val props = Properties()
  runCatching { props.load(StringReader(text)) }
    .getOrElse {
      return null
    }
  return props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Reads the `[versions]` entry named [key] out of [projectRoot]`/`[catalogPath].
 *
 * Deliberately a scoped regex scan rather than a TOML parse: the CLI has no TOML dependency, and
 * the same shape is already scanned by hand in [renderInitScript]'s catalog-accessor helper. We
 * bound the search to the `[versions]` table so an identically named key under `[libraries]` or
 * `[plugins]` can't be mistaken for the pin.
 */
internal fun readCatalogPin(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
  catalogPath: String = VERSION_PIN_CATALOG_PATH,
  key: String = VERSION_PIN_CATALOG_KEY,
): String? {
  val file = File(projectRoot, catalogPath)
  val text =
    runCatching { fileSystem.read(file.path.toPath()) { readUtf8() } }.getOrNull() ?: return null
  val versionsHeader = Regex("""(?m)^\s*\[versions]\s*$""").find(text) ?: return null
  val sectionStart = versionsHeader.range.last + 1
  val nextSection = Regex("""(?m)^\s*\[""").find(text, sectionStart)
  val section = text.substring(sectionStart, nextSection?.range?.first ?: text.length)
  val entry =
    Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*["']([^"']*)["']""").find(section) ?: return null
  return entry.groupValues[1].normalizedPin()
}

/**
 * Writes (or replaces) `composePreview.version=<version>` in [projectRoot]`/gradle.properties`,
 * returning the file it wrote.
 *
 * Line-based rather than [Properties]-based on purpose: `Properties.store` drops every comment and
 * reorders the file, and `gradle.properties` is a hand-maintained, comment-heavy file in most
 * projects. The first existing pin line is rewritten in place (keeping its position); otherwise the
 * pin is appended with a short comment explaining what reads it. A missing file is created.
 *
 * **Duplicate assignments are collapsed, not left behind.** A properties file may legally assign
 * the same key twice, and `Properties.load` resolves the *last* one — so rewriting only the first
 * would report a new pin while [readGradlePropertiesPin] kept resolving the old one. Every
 * assignment after the first is dropped so what we wrote is what the file then resolves to.
 */
internal fun writeGradlePropertiesPin(
  projectRoot: File,
  version: String,
  fileSystem: FileSystem = SystemFileSystem,
): File {
  val file = File(projectRoot, "gradle.properties")
  val path = file.path.toPath()
  val existing = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull()
  val line = "$VERSION_PIN_PROPERTY=$version"
  val updated =
    if (existing == null) {
      "${pinComment()}\n$line\n"
    } else {
      val lines = existing.lines()
      val idx = lines.indexOfFirst { it.isPinAssignment() }
      if (idx >= 0) {
        lines
          .filterIndexed { i, l -> i == idx || !l.isPinAssignment() }
          .mapIndexed { i, l -> if (i == idx) line else l }
          .joinToString("\n")
      } else {
        val body = existing.trimEnd('\n')
        val prefix = if (body.isEmpty()) "" else "$body\n\n"
        "$prefix${pinComment()}\n$line\n"
      }
    }
  fileSystem.write(path) { writeUtf8(updated) }
  return file
}

/**
 * Removes **every** `composePreview.version` assignment (and the comment block this file wrote
 * above the first one) from [projectRoot]`/gradle.properties`. Returns true when at least one pin
 * line was removed.
 *
 * All of them, not just the first: a properties file may legally assign the same key twice, and
 * `Properties.load` takes the last — so leaving a later duplicate behind would report the pin as
 * removed while the project stayed pinned.
 */
internal fun removeGradlePropertiesPin(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val file = File(projectRoot, "gradle.properties")
  val path = file.path.toPath()
  val existing = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return false
  val lines = existing.lines()
  val first = lines.indexOfFirst { it.isPinAssignment() }
  if (first < 0) return false
  val drop = lines.indices.filterTo(mutableSetOf()) { lines[it].isPinAssignment() }
  // Also drop the comment block we wrote above the pin, so a set/unset round-trip leaves the file
  // as it found it. Only our own marker lines — a user's own comment is left alone.
  var above = first - 1
  while (above >= 0 && lines[above].trimStart().startsWith("#") && lines[above] in PIN_COMMENT) {
    drop += above
    above--
  }
  val remaining = lines.filterIndexed { i, _ -> i !in drop }
  fileSystem.write(path) { writeUtf8(remaining.joinToString("\n").trimEnd('\n') + "\n") }
  return true
}

/**
 * True for a (non-comment) line assigning [VERSION_PIN_PROPERTY].
 *
 * Accepts all three separators a Java properties file allows — `key=v`, `key:v`, and bare `key v` —
 * because [readGradlePropertiesPin] reads the file through [Properties], which accepts all three. A
 * writer that recognised fewer forms than the reader would append a second assignment next to a
 * space-separated one it failed to see. The same grammar is mirrored in the extension's
 * `versionPin.ts` and both action scripts, which parse by regex rather than through [Properties].
 */
private fun String.isPinAssignment(): Boolean {
  val trimmed = trimStart()
  if (trimmed.startsWith("#") || trimmed.startsWith("!")) return false
  return PIN_ASSIGNMENT_RE.containsMatchIn(trimmed)
}

/**
 * `composePreview.version` followed by `=`, `:`, or whitespace — the properties-file separators.
 */
private val PIN_ASSIGNMENT_RE =
  Regex("""^${Regex.escape(VERSION_PIN_PROPERTY)}(?:[ \t]*[=:]|[ \t]|$)""")

private val PIN_COMMENT =
  listOf(
    "# compose-preview version pin — read by the CLI, the VS Code extension and the",
    "# install / apply GitHub actions so every entrypoint uses the same release.",
    "# Set with `compose-preview pin <version>`; remove with `compose-preview pin --remove`.",
  )

private fun pinComment(): String = PIN_COMMENT.joinToString("\n")

/** Emits [warnOnCliSkew] at most once per process, so a multi-invocation run isn't noisy. */
private val cliSkewWarned = AtomicBoolean(false)

/**
 * Warns when the project's pin names a version other than the CLI binary that is running.
 *
 * The pin still wins for the plugin we inject — a pin nobody honours is not a pin — but the daemon
 * and renderer the CLI *ships* are stuck at [BUNDLE_VERSION], so the two can genuinely disagree. A
 * cross-major disagreement is the sharp case (the render/daemon wire format changes across a major,
 * per docs/VERSIONING.md § 3), so it gets the stronger wording; within a major it's a nudge.
 *
 * Silent when no pin is set, when the pin equals [cliVersion], or when either version is a
 * `-SNAPSHOT` / unparseable string — a local snapshot build is deliberately allowed to drive a
 * pinned project without nagging.
 */
internal fun warnOnCliSkew(
  pin: ResolvedVersionPin?,
  cliVersion: String = BUNDLE_VERSION,
  stderr: (String) -> Unit = System.err::println,
  once: AtomicBoolean = cliSkewWarned,
) {
  if (pin == null || pin.version == cliVersion) return
  if (cliVersion.endsWith("-SNAPSHOT") || pin.version.endsWith("-SNAPSHOT")) return
  if (!once.compareAndSet(false, true)) return
  val incompatible = versionsIncompatible(pin.version, cliVersion)
  val severity = if (incompatible) "warning" else "note"
  stderr(
    "compose-preview $severity: this project pins compose-preview ${pin.version} " +
      "(${pin.source.display}) but the CLI on \$PATH is $cliVersion. " +
      (if (incompatible)
        "Those are different major versions — the render/daemon wire format differs across a " +
          "major, so the pinned plugin and this CLI's bundled renderer can disagree. "
      else "") +
      "Injecting the pinned plugin version. Align the CLI with " +
      "`compose-preview update ${pin.version}`, or re-pin with " +
      "`compose-preview pin $cliVersion`."
  )
}

/**
 * The plugin version every entrypoint-driven Gradle invocation should apply: the project's pin when
 * there is one, else [fallback] (the caller's own bundled version). Emits the skew note via
 * [warnOnCliSkew] as a side effect, so callers get the diagnostic without threading it themselves.
 */
internal fun resolvePluginVersion(
  projectRoot: File?,
  args: List<String> = emptyList(),
  env: (String) -> String? = System::getenv,
  fileSystem: FileSystem = SystemFileSystem,
  fallback: String = BUNDLE_VERSION,
  stderr: (String) -> Unit = System.err::println,
): String {
  val pin = resolveVersionPin(projectRoot, args, env, fileSystem)
  // A `--plugin-version` override is the user saying "this run, that version" — they already know,
  // so don't lecture them about it.
  if (pin?.source != VersionPinSource.FLAG) warnOnCliSkew(pin, fallback, stderr)
  return pin?.version ?: fallback
}
