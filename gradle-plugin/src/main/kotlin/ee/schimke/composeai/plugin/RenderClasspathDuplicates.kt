package ee.schimke.composeai.plugin

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Detects the same module on one render JVM classpath at more than one version.
 *
 * Render / daemon classpaths concatenate separately-resolved graphs, and Gradle only resolves
 * conflicts within a graph, so both jars can reach one classloader. The first wins per class and
 * the symptom is a distant link error, e.g. a `NoSuchFieldError` from two `bcprov-jdk18on` jars
 * (homeassistant-remotecompose#495).
 *
 * A backstop for paths that still stack graphs, run as a render task `doFirst`. It inspects the
 * final `FileCollection` (reality, not intent) and names each module, its versions and the winning
 * jar. Exact coordinates come from [AndroidPreviewClasspath.buildArtifactCoordinates]; filename
 * parsing is only a fallback, since on-disk names carry no group.
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
   * `…/modules-2/files-2.1/<group>/<name>/<version>/<sha1>/<name>-<version>.jar`: the only layout
   * that yields a group.
   */
  private val MODULE_CACHE =
    Regex("/files-2\\.1/([^/]+)/([^/]+)/([^/]+)/[0-9a-f]{20,}/", RegexOption.IGNORE_CASE)

  /** Artifact-transform outputs (`…/transformed/<name>-<version>[.jar]/…`), with no group. */
  private val TRANSFORMED = Regex("/transformed/([^/]+?)(?:\\.jar)?/")

  /**
   * Variant suffixes AGP appends to transform outputs. The `jar` and `android-classes` views of one
   * AAR (`glance-1.2.0-rc01` and `glance-1.2.0-rc01-runtime`) must compare equal, or every AAR
   * reads as a conflict.
   */
  private val VARIANT_SUFFIXES = listOf("-runtime", "-api")

  private val NAME_VERSION_BOUNDARY = Regex("-(?=\\d)")

  /**
   * Splits `<name>-<version>` at the first `-` followed by a digit (`bcprov-jdk18on-1.85` →
   * `bcprov-jdk18on` @ `1.85`, `robolectric-4.17-beta-2` → `4.17-beta-2`), dropping any
   * [VARIANT_SUFFIXES].
   */
  internal fun splitNameVersion(stem: String): Pair<String, String>? {
    val match = NAME_VERSION_BOUNDARY.find(stem) ?: return null
    val name = stem.substring(0, match.range.first)
    var version = stem.substring(match.range.first + 1)
    VARIANT_SUFFIXES.firstOrNull { version.endsWith(it) }
      ?.let { version = version.removeSuffix(it) }
    if (name.isEmpty() || version.isEmpty()) return null
    return name to version
  }

  /**
   * Parses one classpath entry into a module coordinate, or null for unversioned entries (class
   * dirs, `R.jar`, `android.jar`, …) which must never be flagged. Tries the module cache (which has
   * the group), then the transform directory, then the filename.
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
      // Strip AGP's `jetified-` prefix so both copies of one module compare equal.
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
   * Every module on [paths] at more than one version, in classpath order.
   *
   * [coordinates] (absolute path → exact `group:name:version`) is preferred over path parsing,
   * which can't tell `androidx.core:core` from `androidx.test:core`. Unmapped paths are attributed
   * to a module only when exactly one known group uses that artifact name; ambiguous ones are
   * skipped, since a false report trains people to ignore the warning.
   */
  fun find(
    paths: Iterable<String>,
    coordinates: Map<String, String> = emptyMap(),
  ): List<Duplicate> =
    bucketByModule(paths, coordinates)
      .filterValues { bucket -> bucket.map { it.version }.distinct().size > 1 }
      .map { (key, bucket) -> Duplicate(key, bucket) }

  /**
   * Groups [paths] into `group:artifact` buckets in classpath order, shared by [find] and
   * [findFamilySkew].
   */
  private fun bucketByModule(
    paths: Iterable<String>,
    coordinates: Map<String, String>,
  ): LinkedHashMap<String, MutableList<Entry>> {
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

    // Only an artifact name owned by a single group can absorb an unattributed entry.
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
  }

  /** Convenience overload for a resolved [org.gradle.api.file.FileCollection]'s files. */
  fun findInFiles(files: Iterable<File>, coordinates: Map<String, String> = emptyMap()) =
    find(files.map { it.absolutePath }, coordinates)

  /**
   * Coordinates that are one release train under separate module names, with no BOM, so Gradle can
   * resolve each to a different version while [find] sees nothing wrong. Only added for failures
   * someone actually hit.
   *
   * @param artifact the train's members within [group]; not the whole group, since e.g. the
   *   BouncyCastle FIPS line versions independently.
   * @param remediation the fix to print, which differs per family (align up vs pin down).
   */
  data class SplitFamily(
    val group: String,
    val why: String,
    val artifact: Regex,
    val remediation: String,
  )

  /**
   * Align upward on a virtual platform: BouncyCastle members move together, and a hardcoded force
   * goes stale into a downgrade.
   */
  private val BOUNCYCASTLE_REMEDIATION =
    """
    |    abstract class BouncyCastleAlignmentRule : ComponentMetadataRule {
    |      override fun execute(context: ComponentMetadataContext) {
    |        val id = context.details.id
    |        if (id.group == "org.bouncycastle") {
    |          context.details.belongsTo("org.bouncycastle:bouncycastle-virtual-platform:${'$'}{id.version}")
    |        }
    |      }
    |    }
    |    dependencies { components.all(BouncyCastleAlignmentRule::class.java) }
    """
      .trimMargin()

  /**
   * Pin down to 1.3: Espresso calls the 2-arg `AllOf.allOf` that 2.x removed, so aligning up just
   * guarantees a `NoSuchMethodError`. Matches `applyRenderGraphResolutionRules`.
   */
  private val HAMCREST_REMEDIATION =
    """
    |    configurations.all {
    |      resolutionStrategy.eachDependency {
    |        if (requested.group == "org.hamcrest" && requested.name == "hamcrest") {
    |          useTarget("org.hamcrest:hamcrest-core:1.3")
    |          because("Espresso needs 1.3's AllOf.allOf(Matcher, Matcher); 2.x removed it")
    |        }
    |      }
    |    }
    """
      .trimMargin()

  private val SPLIT_FAMILIES =
    listOf(
      // homeassistant-remotecompose#495: mixed bcprov/bcutil fail static init. Only the `-jdkXX`
      // line moves together; FIPS artifacts must not match.
      SplitFamily(
        group = "org.bouncycastle",
        why = "bcprov/bcutil/bcpkix ship as one release train, no BOM",
        // `-jdk18on`, `-jdk15on` and legacy `-jdk15to18`; `bc-fips` has no `-jdk` segment.
        artifact = Regex("^bc[a-z]*-jdk\\d+(?:on|to\\d+)$"),
        remediation = BOUNCYCASTLE_REMEDIATION,
      ),
      // Merged `hamcrest` 2.x and split 1.3 jars are different coordinates that overlap by class;
      // catches what `applyRenderGraphResolutionRules` misses.
      SplitFamily(
        group = "org.hamcrest",
        why = "the merged 2.x jar and the split 1.3 jars overlap by class",
        artifact = Regex("^hamcrest(?:-core|-library|-integration|-all)?$"),
        remediation = HAMCREST_REMEDIATION,
      ),
    )

  /** One split family whose members resolved to more than one version. */
  data class FamilySkew(val family: SplitFamily, val members: List<Entry>) {
    val group: String
      get() = family.group

    val why: String
      get() = family.why

    val versions: List<String>
      get() = members.map { it.version }.distinct()

    /** Distinct `artifact:version`, in classpath order — what the report prints. */
    val coordinates: List<String>
      get() = members.map { "${it.artifact}:${it.version}" }.distinct()
  }

  /**
   * Every [SPLIT_FAMILIES] entry whose members are at more than one version: the skew [find] can't
   * see. Needs two distinct artifacts (one artifact at two versions is [find]'s job) and only uses
   * entries with a known group.
   */
  fun findFamilySkew(
    paths: Iterable<String>,
    coordinates: Map<String, String> = emptyMap(),
  ): List<FamilySkew> {
    val entries = bucketByModule(paths, coordinates).values.flatten().filter { it.group != null }
    return SPLIT_FAMILIES.mapNotNull { family ->
      val members = entries.filter {
        it.group == family.group && family.artifact.matches(it.artifact)
      }
      val distinctArtifacts = members.map { it.artifact }.distinct()
      val distinctVersions = members.map { it.version }.distinct()
      if (distinctArtifacts.size > 1 && distinctVersions.size > 1) {
        FamilySkew(family, members)
      } else null
    }
  }

  /** Human-readable report: each module, its versions, and which jar wins class loading. */
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
      "Align the module in your build (a version force, or an exclude on whichever graph " +
        "shouldn't carry it). Set -PcomposePreview.classpathDuplicates=fail to make this an " +
        "error, or =off to silence it."
    )
  }

  /**
   * Human-readable report for [findFamilySkew] with each family's remediation. The plugin never
   * applies it: those rules act on every configuration, including what the app ships.
   */
  fun reportFamilySkew(skews: List<FamilySkew>, taskPath: String): String = buildString {
    appendLine(
      "compose-preview: ${skews.size} dependency family/families on the $taskPath JVM classpath " +
        "resolved to more than one version. Each coordinate is at a single version, so ordinary " +
        "conflict resolution sees nothing wrong — but these coordinates ship as one release train " +
        "and their classes reference each other, so mixing versions link-errors at runtime."
    )
    skews.forEach { skew ->
      appendLine("  ${skew.group}  (${skew.why})")
      skew.coordinates.forEach { appendLine("    $it") }
      appendLine("    fix:")
      appendLine(skew.family.remediation)
    }
    appendLine(
      "Applying that is your call — these rules act on EVERY configuration in the module, not " +
        "just the render classpath, so check it doesn't move a version your app ships. Set " +
        "-PcomposePreview.classpathDuplicates=off to silence this."
    )
  }

  /** Valid values for `-PcomposePreview.classpathDuplicates`. */
  const val MODE_WARN = "warn"
  const val MODE_FAIL = "fail"
  const val MODE_OFF = "off"

  /**
   * Runs the check for [task] from a render task `doFirst` (configuration-cache safe) and reports
   * per [mode]. Warns by default: many duplicates never load a differing class.
   */
  fun check(
    task: Task,
    files: Iterable<File>,
    mode: String,
    coordinates: Map<String, String> = emptyMap(),
  ) {
    if (mode == MODE_OFF) return
    val paths = files.map { it.absolutePath }
    // Both checks are reported before failing, so a slow render isn't paid twice to learn both.
    val skews = findFamilySkew(paths, coordinates)
    val duplicates = find(paths, coordinates)
    val messages = buildList {
      if (skews.isNotEmpty()) add(reportFamilySkew(skews, task.path))
      if (duplicates.isNotEmpty()) add(report(duplicates, task.path))
    }
    if (messages.isEmpty()) return
    val message = messages.joinToString("\n")
    if (mode == MODE_FAIL) throw GradleException(message)
    task.logger.warn(message)
  }
}
