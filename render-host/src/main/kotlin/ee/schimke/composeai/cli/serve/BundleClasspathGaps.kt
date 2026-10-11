package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The coordinates a catalog's bundle recorded that this server could not resolve, or resolved to
 * different bytes than recorded — written beside the daemon launch descriptor at materialization
 * and read back when a render dies of a linkage error, so the failure can name its cause.
 *
 * [CoordinateResolver][ee.schimke.composeai.cli.CoordinateResolver] warns and continues on a miss
 * or a sha256 mismatch, which is right for leaf deps but leaves the eventual `NoClassDefFoundError`
 * or `NoSuchFieldError` naming a class rather than a cause. Mismatches matter for families that
 * must move together (two builds of one `1.0.0-SNAPSHOT` look identical by version; only the hash
 * differs).
 *
 * [record] writes `classpath-gaps.json` next to `daemon-launch.json`. [linkageDiagnosis] reads it
 * at trip time and returns one sentence for [RenderCircuitBreaker] to append: a mismatched
 * coordinate that explains the failure first, then an unresolved one matching the missing type's
 * package, else the bare gap. Read at trip time (like [SkikoNativePairing.linkageDiagnosis]) so the
 * healthy path pays nothing.
 */
internal object BundleClasspathGaps {

  /** File name written beside the launch descriptor. */
  private const val FILE_NAME = "classpath-gaps.json"

  private val json = Json { ignoreUnknownKeys = true }

  /** One unresolved coordinate, flattened to what a diagnosis needs. */
  @Serializable
  data class Gap(
    /** `group:artifact:version`, as the bundle recorded it. */
    val coordinate: String,
    /** Kept apart from [coordinate] so a missing class can be matched back to its artifact. */
    val group: String,
    val artifact: String,
  )

  @Serializable
  data class Gaps(
    val unresolved: List<Gap> = emptyList(),
    /**
     * Coordinates that resolved to bytes with an unexpected sha256. Defaulted so older files still
     * read.
     */
    val mismatched: List<Gap> = emptyList(),
    /** How many Maven coordinates the bundle recorded in total, for proportion. */
    val total: Int = 0,
  )

  /**
   * Persist [unresolved] and [mismatched] beside the descriptor in [destDir] and log the aggregate.
   * No file when everything resolved as recorded, so the diagnosis can't fire on a healthy catalog.
   */
  fun record(
    destDir: File,
    unresolved: List<BundleReader.ClasspathEntry.Maven>,
    total: Int,
    system: String,
    onLog: (String) -> Unit,
    mismatched: List<BundleReader.ClasspathEntry.Maven> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (unresolved.isEmpty() && mismatched.isEmpty()) return
    if (unresolved.isNotEmpty()) {
      onLog(
        "catalog $system: ${unresolved.size} of $total classpath coordinate(s) did not resolve — " +
          "the live daemon starts with an incomplete classpath and any render that needs one of " +
          "them will fail with a linkage error. Unresolved: " +
          unresolved.joinToString { "${it.group}:${it.artifact}:${it.version}" }
      )
    }
    if (mismatched.isNotEmpty()) {
      onLog(
        "catalog $system: ${mismatched.size} of $total classpath coordinate(s) resolved to bytes " +
          "that are not the ones the bundle recorded — the version string matches but the artifact " +
          "does not, so the daemon links code from two builds of the same library and a render " +
          "that crosses the seam fails with NoSuchMethodError / NoSuchFieldError. Mismatched: " +
          mismatched.joinToString { "${it.group}:${it.artifact}:${it.version}" }
      )
    }
    val gaps =
      Gaps(
        unresolved = unresolved.map { it.toGap() },
        mismatched = mismatched.map { it.toGap() },
        total = total,
      )
    runCatching {
      fileSystem.write(File(destDir, FILE_NAME).path.toPath()) {
        writeUtf8(json.encodeToString(Gaps.serializer(), gaps))
      }
    }
  }

  /**
   * One sentence attributing a fatal linkage [reason] to this catalog's classpath gaps, or null
   * when the classpath was complete, the record is unreadable, or the failure isn't one a missing
   * artifact explains. [descriptorPath] is the `daemon-launch.json` the record sits beside.
   */
  fun linkageDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? =
    attributedDiagnosis(reason, descriptorPath, fileSystem)
      ?: unattributedDiagnosis(reason, descriptorPath, fileSystem)

  /**
   * The half of [linkageDiagnosis] that names the artifact the failing type lives in (mismatched,
   * or unresolved with matching group/artifact tokens), or null. Split from [unattributedDiagnosis]
   * so a more specific diagnosis (e.g.
   * [RemoteComposePairing][RemoteComposePairing.linkageDiagnosis]) can run in between: the generic
   * half fires on any gap, related or not.
   */
  fun attributedDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? {
    val gaps = readGaps(reason, descriptorPath, fileSystem) ?: return null
    val dotted = reason.replace('/', '.')
    // An attributed mismatch is reported first and alone: it names the artifact and what's wrong.
    // Unattributed mismatches fall through.
    mismatchDiagnosis(gaps, dotted)?.let {
      return it
    }
    val culprit = gaps.unresolved.bestExplanationOf(dotted) ?: return null
    return unresolvedSentence(
      gaps,
      " — including ${culprit.coordinate}, which is where the missing type lives",
    )
  }

  /** The half that reports the gap without naming a culprit — a direction, tried last. */
  fun unattributedDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? {
    val gaps = readGaps(reason, descriptorPath, fileSystem) ?: return null
    if (gaps.unresolved.isEmpty()) return null
    return unresolvedSentence(gaps, " — one of them is likely where the missing type lives")
  }

  private fun unresolvedSentence(gaps: Gaps, attribution: String): String =
    "This catalog's bundle records ${gaps.total} Maven coordinate(s) and this server could not " +
      "resolve ${gaps.unresolved.size} of them, so the daemon is running on an incomplete " +
      "classpath$attribution. Unresolved: ${gaps.unresolved.joinToString { it.coordinate }}. " +
      "Republish the catalog from a build whose repositories the bundle records, or give this " +
      "server access to them (--extra-maven-repos)."

  /** The record beside [descriptorPath], or null when [reason] is not a linkage failure at all. */
  private fun readGaps(reason: String, descriptorPath: File, fileSystem: FileSystem): Gaps? {
    if (MISSING_TYPE_MARKERS.none { it in reason }) return null
    val file = File(descriptorPath.parentFile ?: return null, FILE_NAME)
    return runCatching {
      fileSystem
        .read(file.path.toPath()) { readUtf8() }
        .let { json.decodeFromString(Gaps.serializer(), it) }
    }
      .getOrNull()
  }

  /**
   * The sentence for a linkage failure inside a mismatched coordinate, or null. Requires
   * attribution (a failure elsewhere isn't this record's to claim); once one matches, the whole
   * mismatch list is included since these artifacts travel in families.
   */
  private fun mismatchDiagnosis(gaps: Gaps, dottedReason: String): String? {
    val culprit = gaps.mismatched.bestExplanationOf(dottedReason) ?: return null
    return "This catalog's bundle records a sha256 for each of its ${gaps.total} Maven " +
      "coordinate(s), and ${gaps.mismatched.size} of them resolved to different bytes — including " +
      "${culprit.coordinate}, which is where the missing member lives. The version string matched, " +
      "so the daemon linked two builds of one library together; that is a linkage error no retry " +
      "can clear. Mismatched: ${gaps.mismatched.joinToString { it.coordinate }}. This is expected " +
      "to be a stale cache when the coordinate is a `-SNAPSHOT` (its version names no single " +
      "build); republishing the catalog against released versions removes the ambiguity."
  }

  /** The gap that best explains the type in [dottedReason], or null when none says anything. */
  private fun List<Gap>.bestExplanationOf(dottedReason: String): Gap? = maxByOrNull {
    attributionScore(dottedReason, it)
  }
    ?.takeIf { attributionScore(dottedReason, it) > 0 }

  private fun BundleReader.ClasspathEntry.Maven.toGap() =
    Gap(coordinate = "$group:$artifact:$version", group = group, artifact = artifact)

  /**
   * How well [gap] explains the type in [dottedReason] (failure text with `/` → `.`); zero means
   * not at all. The group must appear, then each artifact-id token that also appears breaks ties
   * between siblings (e.g. `remote-player-view` beats `remote-core` for `…/remote/player/view/…`).
   */
  private fun attributionScore(dottedReason: String, gap: Gap): Int {
    if (!dottedReason.contains(gap.group)) return 0
    val tokenHits = gap.artifact.split('-').count { it.length > 2 && dottedReason.contains(it) }
    return 1 + tokenHits
  }

  /**
   * Linkage markers a missing artifact explains; narrower than [RenderFailureClassifier]'s fatal
   * set (`VerifyError` / `UnsatisfiedLinkError` are different faults, see [SkikoNativePairing]).
   */
  private val MISSING_TYPE_MARKERS =
    listOf(
      "NoClassDefFoundError",
      "ClassNotFoundException",
      "NoSuchMethodError",
      "NoSuchFieldError",
    )
}
