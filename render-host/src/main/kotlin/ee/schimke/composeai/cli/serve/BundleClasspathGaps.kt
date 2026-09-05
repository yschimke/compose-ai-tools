package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The coordinates a catalog's bundle recorded that this server could not resolve — written beside
 * the daemon launch descriptor at materialization time, read back when a render dies of a linkage
 * error so the failure can name its cause.
 *
 * ## Why
 *
 * [CoordinateResolver][ee.schimke.composeai.cli.CoordinateResolver] warns and drops a coordinate it
 * can't find rather than failing, and that is right: most misses are harmless (a dependency no
 * render path touches), and a slightly-thin classpath still beats no catalog. What it cost was
 * attribution. `remote-m3` publishes a `coordinates` bundle whose whole Remote Compose runtime is
 * an androidx.dev snapshot build; the server resolved none of it, logged thirteen separate warnings
 * at startup, stood the daemon up, and every render then died with `NoClassDefFoundError:
 * androidx/compose/remote/player/view/RemoteComposePlayer`. That is the text the circuit breaker
 * latched and the viewer showed, and it names a class, not a cause — so issues #4259 and #4265 were
 * both filed against the symptom.
 *
 * ## The other shape: resolved, but not the recorded bytes
 *
 * A coordinate can also come back **wrong** rather than missing.
 * [CoordinateResolver][ee.schimke.composeai.cli.CoordinateResolver] compares a resolved artifact
 * against the `sha256` the bundle recorded and, on a mismatch, warns and returns it anyway
 * ("almost-compatible beats nothing") — which is right for a leaf dependency and fatal for a family
 * whose artifacts must move together. That is what killed `meshcore-mobile`: its whole Remote
 * Compose runtime is pinned at `1.0.0-SNAPSHOT` from one androidx.dev build, a stale extraction
 * served `remote-player-core` from a *different* build, and the resulting classpath carried two
 * Remote Compose lines. Every IR replay died on `NoSuchFieldError: class
 * androidx.compose.remote.core.RemoteClock does not have member field … SYSTEM`
 * (compose-preview-server#187; the resolver-side fix is content-keyed extraction).
 *
 * Nothing was *unresolved* there, so the record above stayed silent and the breaker reason named a
 * class and no cause — the same hole issues #4259 / #4265 opened, reached through a different door.
 * A version-level skew check would not have found it either: both sides read `1.0.0-SNAPSHOT`. Only
 * the hash separates them, and the resolver already computed it, so [record] keeps the mismatches
 * too.
 *
 * ## What
 *
 * [record] persists the gap as `classpath-gaps.json` next to `daemon-launch.json`.
 * [linkageDiagnosis] reads it at trip time and returns one sentence for [RenderCircuitBreaker] to
 * append to the open breaker's reason — the only diagnosis anyone outside the box ever sees. When
 * the missing class's package matches one of the unresolved coordinates, the sentence names that
 * artifact specifically; otherwise it reports the gap and lets the reader draw the line. A
 * mismatched coordinate that explains the failure is reported ahead of an unresolved one: "these
 * exact bytes are not the ones the bundle recorded" is a cause, where "something is missing" is a
 * direction.
 *
 * Read at trip time rather than held in memory, mirroring [SkikoNativePairing.linkageDiagnosis]: a
 * diagnosis nobody needs must not cost anything on the healthy path.
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
     * Coordinates that resolved to bytes whose sha256 is not the one the bundle recorded. Defaulted
     * so a `classpath-gaps.json` written before this field existed still reads back.
     */
    val mismatched: List<Gap> = emptyList(),
    /** How many Maven coordinates the bundle recorded in total, for proportion. */
    val total: Int = 0,
  )

  /**
   * Persist [unresolved] and [mismatched] beside the launch descriptor in [destDir] and log the
   * aggregate. A no-op (and no file) when every coordinate resolved to the bytes the bundle
   * recorded, so the diagnosis can never fire on a healthy catalog.
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
   * One sentence attributing a **fatal** linkage [reason] to this catalog's unresolved coordinates,
   * or null when the classpath was complete, the record is unreadable, or the failure isn't the
   * kind an absent artifact explains.
   *
   * [descriptorPath] is the daemon's `daemon-launch.json`; the record sits beside it.
   */
  fun linkageDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? =
    attributedDiagnosis(reason, descriptorPath, fileSystem)
      ?: unattributedDiagnosis(reason, descriptorPath, fileSystem)

  /**
   * The half of [linkageDiagnosis] that names the artifact the failing type actually lives in: a
   * mismatched coordinate, or an unresolved one whose group and artifact tokens appear in the
   * failure. Null when this record can point at nothing in particular.
   *
   * Split from [unattributedDiagnosis] so a caller with a **more specific** diagnosis of its own
   * can sit between the two. The generic half fires on any unresolved coordinate, related or not —
   * a catalog missing one optional dependency would otherwise answer every linkage failure in the
   * process with "something is missing", including the ones
   * [RemoteComposePairing][RemoteComposePairing.linkageDiagnosis] can explain exactly. Attribution
   * earns the first word; a bare gap does not.
   */
  fun attributedDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? {
    val gaps = readGaps(reason, descriptorPath, fileSystem) ?: return null
    val dotted = reason.replace('/', '.')
    // A mismatched coordinate the failing type points at is reported first and alone: it names the
    // artifact AND says what is wrong with it, which is strictly more than the unresolved list can
    // say. Unattributed mismatches fall through — "some artifact is the wrong build" without a name
    // is weaker than an unresolved list that does name one.
    mismatchDiagnosis(gaps, dotted)?.let {
      return it
    }
    val culprit = gaps.unresolved.bestExplanationOf(dotted) ?: return null
    return unresolvedSentence(
      gaps,
      " — including ${culprit.coordinate}, which is where the missing type lives",
    )
  }

  /**
   * The half that reports the gap without naming a culprit — "these ${'$'}n coordinates are missing
   * and one of them is probably it". A direction rather than a cause, and correspondingly the last
   * diagnosis to be tried.
   */
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
   * The sentence for a linkage failure inside a coordinate whose bytes are not the ones the bundle
   * recorded, or null when no mismatch explains this failure.
   *
   * Attribution is required here, unlike the unresolved case. A `NoSuchFieldError` in a package no
   * mismatched artifact ships is not this record's to claim — some other dependency is at fault and
   * saying "one of these is probably wrong" would send the reader down the wrong path. The whole
   * mismatch list still rides along once one of them does match, because these artifacts travel in
   * families and the reader needs to see the rest of the family.
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
   * How well [gap] explains the type named in [dottedReason] (the failure text with `/` rewritten
   * to `.`, since a `NoClassDefFoundError` prints the internal form). Zero means "says nothing".
   *
   * The group has to appear at all — the naming convention every AndroidX / JetBrains / Square
   * artifact follows — and then each hyphen-separated token of the artifact id that also appears
   * breaks the tie between siblings in one group. So for
   * `androidx/compose/remote/player/view/RemoteComposePlayer` the group `androidx.compose.remote`
   * matches five unresolved artifacts, and `remote-player-view` (whose `player` and `view` tokens
   * are both in the package) outscores `remote-core`. When nothing scores, the diagnosis stays
   * general rather than naming an artifact it can't stand behind.
   */
  private fun attributionScore(dottedReason: String, gap: Gap): Int {
    if (!dottedReason.contains(gap.group)) return 0
    val tokenHits = gap.artifact.split('-').count { it.length > 2 && dottedReason.contains(it) }
    return 1 + tokenHits
  }

  /**
   * Linkage markers an absent artifact explains. Deliberately narrower than
   * [RenderFailureClassifier]'s fatal set: a `VerifyError` or an `UnsatisfiedLinkError` is a
   * different fault (bad bytecode, a native pairing — see [SkikoNativePairing]) and an unresolved
   * coordinate says nothing useful about it.
   */
  private val MISSING_TYPE_MARKERS =
    listOf(
      "NoClassDefFoundError",
      "ClassNotFoundException",
      "NoSuchMethodError",
      "NoSuchFieldError",
    )
}
