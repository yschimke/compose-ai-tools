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
  ): List<Duplicate> =
    bucketByModule(paths, coordinates)
      .filterValues { bucket -> bucket.map { it.version }.distinct().size > 1 }
      .map { (key, bucket) -> Duplicate(key, bucket) }

  /**
   * Groups [paths] into `group:artifact` buckets, in classpath order. Shared by [find] and
   * [findFamilySkew] so both see exactly the same view of the classpath.
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
  }

  /** Convenience overload for a resolved [org.gradle.api.file.FileCollection]'s files. */
  fun findInFiles(files: Iterable<File>, coordinates: Map<String, String> = emptyMap()) =
    find(files.map { it.absolutePath }, coordinates)

  /**
   * A set of coordinates that are really one release train, published under separate module names.
   *
   * Gradle aligns *a module* against itself, and nothing more. A library shipped as several
   * coordinates with no BOM or platform gets each coordinate resolved independently, so the graph
   * can settle on `bcprov-jdk18on:1.85` while leaving `bcutil-jdk18on:1.84` — one version each, so
   * [find] sees nothing wrong, and the classes still don't agree at runtime.
   *
   * The bar for adding an entry here is a **failure someone actually hit**, not a hunch that a
   * group looks splittable. A false report is expensive: it trains people to ignore the warning,
   * which is the same reasoning that keeps [find] from guessing at ambiguous artifact names.
   */
  /**
   * @param artifact which artifacts *in* [group] belong to the train. Deliberately not "everything
   *   in the group": `org.bouncycastle` also publishes the FIPS line (`bc-fips`, `bcpkix-fips`,
   *   `bctls-fips`), whose versions advance **independently** — `bc-fips:2.1.0` alongside
   *   `bcpkix-fips:2.1.9` is a correct, healthy classpath. Matching the whole group would report
   *   that as skew and, under `classpathDuplicates=fail`, break a build that has nothing wrong with
   *   it.
   * @param remediation the fix to print. Per-family because the right answer differs: BouncyCastle
   *   wants aligning *up* to the highest member, Hamcrest wants pinning *down* to 1.3.
   */
  data class SplitFamily(
    val group: String,
    val why: String,
    val artifact: Regex,
    val remediation: String,
  )

  /**
   * Align the family *upward* on a virtual platform. Right for BouncyCastle: the members really do
   * move together, every member has a release at the highest version, and a hardcoded force would
   * go stale — silently becoming a downgrade as soon as one member's floor moves, which is what
   * happened when Robolectric went to 1.85 against a pinned 1.84.
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
   * Pin the family *downward* to 1.3. The opposite of the BouncyCastle fix, and the reason
   * remediation is per-family rather than one generic snippet.
   *
   * `hamcrest-core:2.2` / `hamcrest-library:2.2` do exist — as deprecated shims that just depend on
   * the merged `hamcrest:2.2` — so a virtual platform *would* resolve. It would resolve to the
   * wrong place: Espresso's compiled bytecode calls the 2-arg `AllOf.allOf(Matcher, Matcher)` that
   * 2.x deleted, so aligning up to 2.x trades a silent mismatch for a guaranteed
   * `NoSuchMethodError`. Substituting back to 1.3 is what `applyRenderGraphResolutionRules` already
   * does on the render graph.
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
      // homeassistant-remotecompose#495. BC 1.84+'s post-quantum `compositekem.KeyFactorySpi`
      // references `IANAObjectIdentifiers` fields added in 1.81; pair it with an older asn1 jar
      // from a sibling coordinate and its static init throws NoSuchFieldError, failing every a11y
      // preview. BouncyCastle publishes no BOM.
      //
      // Scoped to the `-jdkXXon` / `-jdkXXtoYYon` line, which is the one that moves in lockstep.
      // The FIPS artifacts share the group but not the release train, so they must not match.
      SplitFamily(
        group = "org.bouncycastle",
        why = "bcprov/bcutil/bcpkix ship as one release train, no BOM",
        // `bcprov-jdk18on`, `bcprov-jdk15on`, and the legacy `bcprov-jdk15to18` (which ends in the
        // JDK number, not `on`) — all real published coordinates. `bc-fips` / `bcpkix-fips` have no
        // `-jdk` segment at all, which is what keeps them out.
        artifact = Regex("^bc[a-z]*-jdk\\d+(?:on|to\\d+)$"),
        remediation = BOUNCYCASTLE_REMEDIATION,
      ),
      // Espresso's bytecode calls Hamcrest 1.3's 2-arg `AllOf.allOf`, which 2.x replaced with
      // varargs. The merged `hamcrest` 2.x artifact and the legacy split `hamcrest-core` /
      // `hamcrest-library` 1.3 jars are different coordinates, so Gradle never dedups them and
      // `Espresso.<clinit>` throws NoSuchMethodError. See `applyRenderGraphResolutionRules`, which
      // substitutes the render graph back to 1.3 — this catches the case where that rule misses.
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
   * Returns every [SPLIT_FAMILIES] entry whose members are on the classpath at more than one
   * version.
   *
   * This is the gap [find] can't see: it compares a module against itself, so `bcprov 1.85` next to
   * `bcutil 1.84` reads as two healthy modules. Reporting it needs the prior knowledge that those
   * coordinates move together, which is what [SPLIT_FAMILIES] encodes.
   *
   * Requires at least two distinct *artifacts* in the family — a single coordinate at two versions
   * is already [find]'s job, and reporting it twice would just add noise.
   *
   * Only entries with a known group participate: an unattributed jar can't be assigned to a family
   * without guessing, and guessing is what makes a warning ignorable.
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
      "Align the module in your build (a version force, or an exclude on whichever graph " +
        "shouldn't carry it). Set -PcomposePreview.classpathDuplicates=fail to make this an " +
        "error, or =off to silence it."
    )
  }

  /**
   * Human-readable report for [findFamilySkew], with the family's own remediation to paste.
   *
   * The fix is spelled out rather than described because it isn't obvious *and* it isn't uniform:
   * BouncyCastle wants aligning up to the highest member, Hamcrest wants pinning down to 1.3
   * (aligning it up is what breaks Espresso). Emitting one generic snippet for both would hand half
   * of readers a fix that makes their build worse, so the text lives on [SplitFamily].
   *
   * The plugin deliberately does NOT apply any of it. `ComponentMetadataRule`s register on
   * `DependencyHandler` and so apply to *every* configuration in the project, including the
   * consumer's `releaseRuntimeClasspath`. Changing a dependency version project-wide is
   * emphatically not the preview plugin's call to make for a shipped app — so this reports and
   * hands the decision back.
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
    val paths = files.map { it.absolutePath }
    // Two independent faults: `find` catches one module at two versions, `findFamilySkew` catches
    // sibling coordinates of one library disagreeing — which `find` cannot see, because each
    // coordinate is internally consistent. A classpath can have one, the other, or both.
    //
    // BOTH are computed and reported before anything throws. Failing on the first would hide the
    // second until the reader fixed it and re-ran, which for a slow render task means paying the
    // full build twice to learn something we already knew on the first pass.
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
