package ee.schimke.composeai.plugin

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Detects the same module sitting on one render JVM classpath at more than one version.
 *
 * The render / daemon classpaths are assembled by *concatenating* several separately-resolved
 * dependency graphs (the renderer config, AGP's unit-test runtime classpath, the screenshotTest
 * runtime config, the daemon config). Gradle runs conflict resolution *within* a graph, never
 * across a `FileCollection` concatenation — so two graphs that each resolved `foo:bar` to a
 * different version put BOTH jars in front of the same classloader. Whichever entry comes first
 * wins per-class, and a class from the winning jar can end up calling into a sibling class that
 * only the losing jar's version defines. The symptom is always a link error far from the cause:
 * ```
 * java.lang.NoSuchFieldError: Class org.bouncycastle.asn1.iana.IANAObjectIdentifiers
 *   does not have member field '…id_MLKEM768_RSA2048_SHA3_256'
 *   at …compositekem.KeyFactorySpi.<clinit>
 * ```
 *
 * — two `bcprov-jdk18on` jars (1.84 from the consumer's own test deps via mockserver, 1.85 from
 * Robolectric via `renderer-android`), reported downstream as `homeassistant-remotecompose#495`.
 * The same shape has previously surfaced as Hamcrest `NoSuchMethodError` at `Espresso.<clinit>` and
 * as `NoSuchFieldError` on `androidx.lifecycle.ReportFragment.Companion`.
 *
 * [ComposePreviewPlugin] no longer concatenates the consumer's separately-resolved unit-test graph
 * (see `AndroidPreviewClasspath.buildAgpClasspathExtras`), so the common case is fixed at source.
 * This detector is the backstop for the paths that still stack graphs — the screenshotTest runtime
 * config, the daemon config — and for any future regression. It runs as a `doFirst` on the render
 * tasks and reports rather than guesses: the message names the module, every version present, and
 * the jar that will actually win.
 *
 * It inspects the *final* `FileCollection` the JVM is handed, so it validates reality rather than
 * re-deriving what resolution intended — a config-level comparison would go green the moment the
 * plugin stopped re-adding a graph, even if some other code path put the jars back. Each entry is
 * then resolved to its exact `group:name:version` through the map from
 * [AndroidPreviewClasspath.buildArtifactCoordinates]; filename parsing is only the fallback for
 * entries that map doesn't cover, because on-disk names carry no group and several unrelated
 * modules are called `core`.
 */
internal object RenderClasspathDuplicates {

  /** One classpath entry that parsed to a recognisable `<artifact>` @ `<version>`. */
  data class Entry(val group: String?, val artifact: String, val version: String, val path: String)

  /** One module present at more than one version, in classpath order (first entry wins). */
  data class Duplicate(val coordinate: String, val entries: List<Entry>) {
    val versions: List<String>
      get() = entries.map { it.version }.distinct()

    /** The jar that actually wins class lookup — the earliest on the classpath. */
    val winner: Entry
      get() = entries.first()
  }

  /**
   * Gradle's module cache layout:
   * `…/modules-2/files-2.1/<group>/<name>/<version>/<sha1>/<name>-<version>.jar`. The only shape
   * that yields a *group*, so it anchors the group backfill in [find].
   */
  private val MODULE_CACHE =
    Regex("/files-2\\.1/([^/]+)/([^/]+)/([^/]+)/[0-9a-f]{20,}/", RegexOption.IGNORE_CASE)

  /**
   * Artifact-transform outputs:
   * `…/.transforms/<hash>/transformed/<name>-<version>/jars/classes.jar` (AAR → classes.jar) or
   * `…/transformed/<name>-<version>.jar` (jetified jars). The directory segment carries
   * `<name>-<version>` but never the group.
   */
  private val TRANSFORMED = Regex("/transformed/([^/]+?)(?:\\.jar)?/")

  /**
   * Gradle variant suffixes AGP appends to a transform output's directory name. `glance-1.2.0-rc01`
   * (the AAR's `classes.jar`) and `glance-1.2.0-rc01-runtime` (the runtime-variant jar) are the
   * SAME module at the SAME version — the render classpath deliberately pulls both the `jar` and
   * `android-classes` artifact views, so seeing the pair is normal and harmless. Without stripping
   * these, every AAR on the classpath reads as a version conflict and the report is pure noise (77
   * false positives on `samples/android` alone).
   */
  private val VARIANT_SUFFIXES = listOf("-runtime", "-api")

  /**
   * Splits `<name>-<version>` at the first `-` followed by a digit. That boundary is what makes
   * `bcprov-jdk18on-1.85` → `bcprov-jdk18on` @ `1.85` (and not `bcprov` @ `jdk18on-1.85`), while
   * still keeping multi-segment versions whole: `robolectric-4.17-beta-2` → `4.17-beta-2`,
   * `listenablefuture-9999.0-empty-to-avoid-conflict-with-guava` → the full sentinel version.
   *
   * The parsed version has any [VARIANT_SUFFIXES] marker removed, so the `jar` and
   * `android-classes` views of one artifact compare equal.
   */
  internal fun splitNameVersion(stem: String): Pair<String, String>? {
    val match = Regex("-(?=\\d)").find(stem) ?: return null
    val name = stem.substring(0, match.range.first)
    var version = stem.substring(match.range.first + 1)
    VARIANT_SUFFIXES.firstOrNull { version.endsWith(it) }
      ?.let { version = version.removeSuffix(it) }
    if (name.isEmpty() || version.isEmpty()) return null
    return name to version
  }

  /**
   * Parses one classpath entry into a module coordinate, or null when it isn't a versioned module
   * artifact (a class directory, `R.jar`, the unit-test config dir, `android.jar`, a generated
   * shard-classes dir — all of which are legitimately unversioned and must never be flagged).
   *
   * Tries the module cache first (it supplies the group), then the transform output directory, then
   * the bare filename.
   */
  internal fun coordinateOf(path: String): Entry? {
    val normalized = path.replace('\\', '/')
    if (!normalized.endsWith(".jar", ignoreCase = true)) return null

    MODULE_CACHE.find(normalized)?.let { m ->
      return Entry(
        group = m.groupValues[1],
        artifact = m.groupValues[2],
        version = m.groupValues[3],
        path = path,
      )
    }

    TRANSFORMED.find(normalized)?.let { m ->
      // `jetified-` is AGP's own prefix on transformed artifacts; strip it so the jetified and
      // non-jetified copies of one module compare equal.
      val stem = m.groupValues[1].removePrefix("jetified-")
      splitNameVersion(stem)?.let { (name, version) ->
        return Entry(group = null, artifact = name, version = version, path = path)
      }
    }

    val filename = normalized.substringAfterLast('/').removeSuffix(".jar").removeSuffix(".JAR")
    splitNameVersion(filename.removePrefix("jetified-"))?.let { (name, version) ->
      return Entry(group = null, artifact = name, version = version, path = path)
    }
    return null
  }

  /**
   * Returns every module on [paths] that appears at more than one version, in classpath order.
   *
   * [coordinates] maps an absolute classpath path to the exact `group:name:version` Gradle resolved
   * it to (see [AndroidPreviewClasspath.buildArtifactCoordinates]). Preferring it over path parsing
   * is what makes the report trustworthy: transform outputs are named `<artifact>-<version>` with
   * no group, so three unrelated modules that happen to be called `core` — `androidx.core:core`,
   * `androidx.test:core`, `ee.schimke.composeai:core` — look identical on disk and get reported as
   * one module at three versions. Asking Gradle removes the guesswork entirely.
   *
   * Paths absent from [coordinates] (class dirs, `R.jar`, `android.jar`, generated shard classes)
   * fall back to path parsing, and are then attributed to a known module ONLY when exactly one
   * known group uses that artifact name. Ambiguous or unattributable entries are skipped: a missed
   * duplicate is a far cheaper failure than a false one, which trains people to ignore the warning.
   */
  fun find(
    paths: Iterable<String>,
    coordinates: Map<String, String> = emptyMap(),
  ): List<Duplicate> {
    val buckets = LinkedHashMap<String, MutableList<Entry>>()
    val unattributed = mutableListOf<Entry>()

    for (path in paths) {
      val exact = coordinates[path]
      if (exact != null) {
        val key = exact.substringBeforeLast(':')
        val version = exact.substringAfterLast(':')
        val artifact = key.substringAfterLast(':')
        buckets
          .getOrPut(key) { mutableListOf() }
          .add(Entry(key.substringBeforeLast(':'), artifact, version, path))
      } else {
        coordinateOf(path)?.let { unattributed.add(it) }
      }
    }

    // Artifact name → the single group that owns it, or null when several do. Only unambiguous
    // names can absorb an unattributed entry.
    val groupsByArtifact = LinkedHashMap<String, MutableSet<String>>()
    buckets.values.flatten().forEach { entry ->
      groupsByArtifact.getOrPut(entry.artifact) { linkedSetOf() }.add(entry.group ?: "")
    }
    for (entry in unattributed) {
      val groups = groupsByArtifact[entry.artifact] ?: continue
      val group = groups.singleOrNull() ?: continue
      buckets["$group:${entry.artifact}"]?.add(entry)
    }

    return buckets
      .filterValues { bucket -> bucket.map { it.version }.distinct().size > 1 }
      .map { (key, bucket) -> Duplicate(key, bucket) }
  }

  /** Convenience overload for a resolved [org.gradle.api.file.FileCollection]'s files. */
  fun findInFiles(files: Iterable<File>, coordinates: Map<String, String> = emptyMap()) =
    find(files.map { it.absolutePath }, coordinates)

  /**
   * Human-readable report. Lists each module, the versions present, and which jar wins classload —
   * enough for the reader to decide whether to align the dependency or exclude it, without
   * re-running anything.
   */
  fun report(duplicates: List<Duplicate>, taskPath: String): String = buildString {
    appendLine(
      "compose-preview: ${duplicates.size} module(s) are on the $taskPath JVM classpath at more " +
        "than one version. Java loads the FIRST match per class, so a class from the winning jar " +
        "can link against a sibling that only the other version defines (NoSuchFieldError / " +
        "NoSuchMethodError at an unrelated <clinit>)."
    )
    duplicates.forEach { duplicate ->
      appendLine("  ${duplicate.coordinate}  ${duplicate.versions.joinToString(", ")}")
      appendLine("    wins: ${duplicate.winner.path}")
      duplicate.entries.drop(1).forEach { appendLine("    also: ${it.path}") }
    }
    appendLine(
      "Align the module in your build (a version force, a `belongsTo` alignment rule for a " +
        "split family such as org.bouncycastle:bcprov/bcutil/bcpkix, or an exclude on whichever " +
        "graph shouldn't carry it). Set -PcomposePreview.classpathDuplicates=fail to make this " +
        "an error, or =off to silence it."
    )
  }

  /** Valid values for `-PcomposePreview.classpathDuplicates`. */
  const val MODE_WARN = "warn"
  const val MODE_FAIL = "fail"
  const val MODE_OFF = "off"

  /**
   * Runs the check for [task] and reports according to [mode].
   *
   * Called from a `doFirst` on each render task, alongside
   * [AndroidPreviewClasspath.validateApplicationOnClasspath] — by then `classpath.files` is fully
   * resolved, and a `doFirst` touches only the task (never `project.*`), so it stays
   * configuration-cache safe.
   *
   * Warns by default rather than failing. A duplicate is a strong smell but not always fatal —
   * plenty of consumers have carried one for months without a visible symptom because the classes
   * that differ are never loaded. Failing every such build on upgrade would be worse than the
   * disease; `-PcomposePreview.classpathDuplicates=fail` opts into the strict behaviour for CI.
   */
  fun check(
    task: Task,
    files: Iterable<File>,
    mode: String,
    coordinates: Map<String, String> = emptyMap(),
  ) {
    if (mode == MODE_OFF) return
    val duplicates = findInFiles(files, coordinates)
    if (duplicates.isEmpty()) return
    val message = report(duplicates, task.path)
    if (mode == MODE_FAIL) throw GradleException(message)
    task.logger.warn(message)
  }
}
