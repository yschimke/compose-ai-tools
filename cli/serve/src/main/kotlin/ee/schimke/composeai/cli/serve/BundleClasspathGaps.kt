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
 * ## What
 *
 * [record] persists the gap as `classpath-gaps.json` next to `daemon-launch.json`.
 * [linkageDiagnosis] reads it at trip time and returns one sentence for [RenderCircuitBreaker] to
 * append to the open breaker's reason — the only diagnosis anyone outside the box ever sees. When
 * the missing class's package matches one of the unresolved coordinates, the sentence names that
 * artifact specifically; otherwise it reports the gap and lets the reader draw the line.
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
    /** How many Maven coordinates the bundle recorded in total, for proportion. */
    val total: Int = 0,
  )

  /**
   * Persist [unresolved] beside the launch descriptor in [destDir] and log the aggregate. A no-op
   * (and no file) when everything resolved, so the diagnosis can never fire on a healthy catalog.
   */
  fun record(
    destDir: File,
    unresolved: List<BundleReader.ClasspathEntry.Maven>,
    total: Int,
    system: String,
    onLog: (String) -> Unit,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (unresolved.isEmpty()) return
    onLog(
      "catalog $system: ${unresolved.size} of $total classpath coordinate(s) did not resolve — " +
        "the live daemon starts with an incomplete classpath and any render that needs one of " +
        "them will fail with a linkage error. Unresolved: " +
        unresolved.joinToString { "${it.group}:${it.artifact}:${it.version}" }
    )
    val gaps =
      Gaps(
        unresolved =
          unresolved.map {
            Gap(
              coordinate = "${it.group}:${it.artifact}:${it.version}",
              group = it.group,
              artifact = it.artifact,
            )
          },
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
  ): String? {
    if (MISSING_TYPE_MARKERS.none { it in reason }) return null
    val file = File(descriptorPath.parentFile ?: return null, FILE_NAME)
    val gaps =
      runCatching {
        fileSystem
          .read(file.path.toPath()) { readUtf8() }
          .let { json.decodeFromString(Gaps.serializer(), it) }
      }
        .getOrNull() ?: return null
    if (gaps.unresolved.isEmpty()) return null
    val dotted = reason.replace('/', '.')
    val culprit =
      gaps.unresolved
        .maxByOrNull { attributionScore(dotted, it) }
        ?.takeIf { attributionScore(dotted, it) > 0 }
    val head =
      "This catalog's bundle records ${gaps.total} Maven coordinate(s) and this server could not " +
        "resolve ${gaps.unresolved.size} of them, so the daemon is running on an incomplete " +
        "classpath"
    val attribution =
      culprit?.let { " — including ${it.coordinate}, which is where the missing type lives" }
        ?: " — one of them is likely where the missing type lives"
    return "$head$attribution. Unresolved: ${gaps.unresolved.joinToString { it.coordinate }}. " +
      "Republish the catalog from a build whose repositories the bundle records, or give this " +
      "server access to them (--extra-maven-repos)."
  }

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
