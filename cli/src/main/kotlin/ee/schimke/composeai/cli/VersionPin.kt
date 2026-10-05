package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.io.StringReader
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The project version pin: one place a consumer names the compose-preview version, honoured by
 * every entrypoint so the CLI, the VS Code extension and CI don't render against different releases
 * (issue #3738).
 *
 * Precedence: `--plugin-version`, then `COMPOSE_PREVIEW_VERSION`, then `gradle.properties`
 * `composePreview.version` (what `compose-preview pin` writes), then `gradle/libs.versions.toml`
 * `[versions] composePreviewCli`. Nothing found means the caller's bundled version.
 *
 * The pin only governs auto-inject ([autoInjectInitScriptArgs]); a module declaring the plugin
 * itself keeps its own version, and nothing here rewrites build scripts. Kept in lockstep with the
 * extension's `versionPin.ts` and the actions' `resolve-version.py`.
 */
internal const val VERSION_PIN_PROPERTY = "composePreview.version"

/** Environment override, read after `--plugin-version` and before anything on disk. */
internal const val VERSION_PIN_ENV = "COMPOSE_PREVIEW_VERSION"

/** Default version-catalog path scanned for [VERSION_PIN_CATALOG_KEY]. */
internal const val VERSION_PIN_CATALOG_PATH = "gradle/libs.versions.toml"

/**
 * `[versions]` key read from the catalog; the `install` / `apply` actions' `catalog-key` default.
 */
internal const val VERSION_PIN_CATALOG_KEY = "composePreviewCli"

/**
 * The build root for [start]: the nearest ancestor (inclusive) holding a settings file, else the
 * nearest holding a Gradle wrapper. Shared by [Command.findProjectRoot] and [PinCommand].
 *
 * Settings first because a nested build may borrow its parent's wrapper (issue #5031); the wrapper
 * still supplies the distribution, see [findGradleWrapperRoot].
 */
internal fun findGradleProjectRoot(start: File = File(".").absoluteFile): File? {
  var dir: File? = start
  while (dir != null) {
    if (hasSettingsFile(dir)) return dir
    dir = dir.parentFile
  }
  return findGradleWrapperRoot(start)
}

/** True when [dir] holds a Gradle settings file in either DSL — i.e. [dir] is a build root. */
internal fun hasSettingsFile(dir: File): Boolean =
  File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile

/**
 * The nearest ancestor of [start] holding a `gradlew`: whose Gradle distribution applies, not which
 * build this is ([findGradleProjectRoot]).
 */
internal fun findGradleWrapperRoot(start: File = File(".").absoluteFile): File? {
  var dir: File? = start
  while (dir != null) {
    if (File(dir, "gradlew").exists()) return dir
    dir = dir.parentFile
  }
  return null
}

/**
 * The nearest ancestor (inclusive) holding a `.git` file or directory, or null outside a checkout.
 *
 * Distinct from the build root for trust: [confirmProjectServeHost] bounds "what may a pull request
 * have written" by the checkout, so a nested build can't confirm its own `composePreview.serveUrl`
 * from a committed `.gradle/gradle.properties` one level up.
 */
internal fun findVcsCheckoutRoot(start: File = File(".").absoluteFile): File? {
  var dir: File? = start.absoluteFile
  while (dir != null) {
    if (File(dir, ".git").exists()) return dir
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
 * Resolves the project's version pin, or `null` when nothing pins a version. [projectRoot] may be
 * null before a project is located. Unreadable or malformed files fall through to the next source:
 * a broken pin must never be worse than no pin.
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

/** Trims a raw pin and drops a leading `v`, as `resolve-version.py` does; blank is absent. */
private fun String.normalizedPin(): String? = trim().removePrefix("v").takeIf { it.isNotEmpty() }

/** Reads `composePreview.version` from [projectRoot]`/gradle.properties`. */
internal fun readGradlePropertiesPin(
  projectRoot: File,
  fileSystem: FileSystem = SystemFileSystem,
): String? = readGradleProperty(projectRoot, VERSION_PIN_PROPERTY, fileSystem)?.normalizedPin()

/**
 * One trimmed value out of [projectRoot]`/gradle.properties`, or null when the file, parse or key
 * is missing. Parsed with [Properties] since `key : value`, continuations and escapes are all
 * legal.
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
 * Reads the `[versions]` entry named [key] out of [projectRoot]`/`[catalogPath]. A regex scan
 * bounded to the `[versions]` table, since the CLI has no TOML dependency.
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
 * returning the file. Line-based so the user's comments and order survive (`Properties.store` would
 * drop them). Later duplicate assignments are removed, since `Properties.load` takes the last.
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
 * Removes every `composePreview.version` assignment (a later duplicate would otherwise keep the
 * project pinned) and the comment block [writeGradlePropertiesPin] added. True when anything was
 * removed.
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
 * True for a non-comment line assigning [VERSION_PIN_PROPERTY] with any properties-file separator
 * (`=`, `:` or whitespace), matching what [Properties] reads.
 */
private fun String.isPinAssignment(): Boolean {
  val trimmed = trimStart()
  if (trimmed.startsWith("#") || trimmed.startsWith("!")) return false
  return PIN_ASSIGNMENT_RE.containsMatchIn(trimmed)
}

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
 * Warns when the pin names a version other than the running CLI, whose bundled daemon and renderer
 * stay at [BUNDLE_VERSION]. Stronger wording across a major (wire format changes,
 * docs/VERSIONING.md § 3). Silent with no pin, a match, or any `-SNAPSHOT`.
 */
internal fun warnOnCliSkew(
  pin: ResolvedVersionPin?,
  cliVersion: String = BUNDLE_VERSION,
  stderr: (String) -> Unit = System.err::println,
  once: AtomicBoolean = cliSkewWarned,
  // The re-pin remedy must name a version that was published to Central.
  mavenLineVersion: String = MAVEN_LINE_VERSION,
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
      "`compose-preview pin $mavenLineVersion`."
  )
}

/**
 * The plugin version to inject: the project's pin, else [fallback]. Emits [warnOnCliSkew] as a side
 * effect.
 */
internal fun resolvePluginVersion(
  projectRoot: File?,
  args: List<String> = emptyList(),
  env: (String) -> String? = System::getenv,
  fileSystem: FileSystem = SystemFileSystem,
  // Becomes a Gradle coordinate, so it must exist on Maven Central; see MAVEN_LINE_VERSION.
  fallback: String = MAVEN_LINE_VERSION,
  stderr: (String) -> Unit = System.err::println,
): String {
  val pin = resolveVersionPin(projectRoot, args, env, fileSystem)
  // An explicit `--plugin-version` needs no warning.
  if (pin?.source != VersionPinSource.FLAG) warnOnCliSkew(pin, fallback, stderr)
  return pin?.version ?: fallback
}
