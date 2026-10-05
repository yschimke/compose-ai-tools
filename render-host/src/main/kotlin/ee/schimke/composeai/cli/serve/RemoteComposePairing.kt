package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Detects a served bundle whose Remote Compose artifacts are split across two lines: the bundle
 * carries part of the `androidx.compose.remote` family (promoted ahead of the sidecar) and the
 * daemon sidecar supplies the rest at another version. The members are compiled against each other,
 * so the first IR replay dies with a linkage error such as `NoSuchFieldError: ... RemoteClock
 * SYSTEM` (compose-preview-server#187), which the breaker latches as fatal for the whole catalog.
 *
 * Like [SkikoNativePairing.classpathSkew], only a half-pair is reported: a bundle carrying all or
 * none of a group is coherent. On an IR bundle ([ServeBundleDaemon] passes `hasIr`) the split
 * group's bundle copies are demoted behind the sidecar, since the replay connector is sidecar code;
 * the worst case is then one document that fails to replay rather than a dead lane. Bundles without
 * IR keep the "catalog's framework versions win" rule and are only reported.
 */
internal object RemoteComposePairing {

  /** Common to every Remote Compose group and (dotted) package name. */
  private const val FAMILY_MARKER = "compose.remote"

  /**
   * The family's main line, and the group a sidecar jar belongs to unless its artifact says Wear.
   */
  const val BASE_GROUP: String = "androidx.compose.remote"

  /** The Wear line, versioned independently of [BASE_GROUP]. */
  private const val WEAR_GROUP = "androidx.wear.compose.remote"

  /** [WEAR_GROUP] artifacts, needed for flat sidecar `lib/` jars whose path carries no group. */
  private val WEAR_ARTIFACTS = setOf("remote-material3")

  /** File name written beside the launch descriptor, mirroring [BundleClasspathGaps]. */
  private const val FILE_NAME = "remote-compose-line.json"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * One Remote Compose artifact and the version the classpath loads it at. [group] keeps the base
   * and Wear lines apart: they version independently. Defaulted so older records still read.
   */
  @Serializable
  data class Member(
    val artifact: String,
    val version: String,
    val group: String = BASE_GROUP,
  )

  /**
   * The family artifacts each side of the daemon `-cp` contributes, persisted so a later linkage
   * failure can be diagnosed without re-deriving the classpath.
   */
  @Serializable
  data class Line(
    val bundle: List<Member> = emptyList(),
    val sidecar: List<Member> = emptyList(),
    /** Whether [ServeBundleDaemon] demoted the bundle's copies behind the sidecar. */
    val demoted: Boolean = false,
  )

  /** Whether [coordinate] belongs to the Remote Compose family at all. */
  fun isFamilyMember(coordinate: BundleReader.ClasspathEntry.Maven): Boolean =
    coordinate.group.contains(FAMILY_MARKER)

  /** Whether [coordinate] is in one of the [skewedGroups] this bundle must stop promoting. */
  fun isDemoted(coordinate: BundleReader.ClasspathEntry.Maven, skewedGroups: Set<String>): Boolean =
    isFamilyMember(coordinate) && coordinate.group in skewedGroups

  /** The family members among the bundle's **resolved** coordinates. */
  fun bundleMembers(coords: List<BundleReader.ClasspathEntry.Maven>): List<Member> =
    coords
      .filter { isFamilyMember(it) }
      .map { Member(it.artifact, it.version, it.group) }
      .distinct()

  /**
   * The family members on the sidecar classpath, read off `lib/<artifact>-<version>.jar` names.
   * Bundle-side `.aar`s arrive as `extracted/<sha256>/classes.jar`, hence [bundleMembers].
   */
  fun sidecarMembers(sidecarClasspath: List<String>): List<Member> =
    sidecarClasspath
      .mapNotNull { path ->
        val filename = path.replace('\\', '/').substringAfterLast('/')
        REMOTE_ARTIFACT.matchEntire(filename)?.let {
          val artifact = it.groupValues[1]
          Member(
            artifact,
            it.groupValues[2],
            if (artifact in WEAR_ARTIFACTS) WEAR_GROUP else BASE_GROUP,
          )
        }
      }
      .distinct()

  /**
   * The sentence for a classpath that loads two Remote Compose lines, or null when coherent. An
   * agreed `-SNAPSHOT` is taken at face value here; see [mutableVersionSuspicion].
   */
  fun skew(line: Line): String? {
    val split = skewedGroups(line)
    if (split.isEmpty()) return null
    val bundle = line.bundle.filter { it.group in split }
    val fallthrough = line.fallthrough().filter { it.group in split }
    val bundleVersions = bundle.map { it.version }.distinct()
    val fallthroughVersions = fallthrough.map { it.version }.distinct()
    val remedy =
      if (line.demoted)
        "The server therefore let the sidecar's line win those artifacts: the bundle's copies " +
          "stay on the classpath but behind the sidecar's, so the daemon's own IR replay links " +
          "against one coherent set. A document authored against a newer player may still fail to " +
          "replay — as a per-render error, not a dead lane."
      else
        "These artifacts are compiled against each other, so a render that crosses the seam fails " +
          "with NoSuchFieldError / NoSuchMethodError and no retry can clear it."
    return "This catalog's bundle carries ${bundle.size} artifact(s) of ${split.joinToString()} " +
      "at ${bundleVersions.joinToString()}, but ${fallthrough.size} more that the render needs " +
      "are not in the bundle and come from the daemon sidecar instead, at " +
      "${fallthroughVersions.joinToString()} — ${fallthrough.joinToString { it.artifact }}. " +
      "$remedy Republish the catalog against the Remote Compose version this server ships, or " +
      "against one whose whole family the bundle records."
  }

  /**
   * The groups whose supply is split between bundle and sidecar at differing versions; empty when
   * coherent. Judged per group (base and Wear version independently) and per artifact: a carried
   * artifact is always the bundle's, only an uncarried one falls through to the sidecar.
   */
  fun skewedGroups(line: Line): Set<String> {
    if (line.bundle.isEmpty()) return emptySet()
    val fallthrough = line.fallthrough()
    return line.bundle
      .map { it.group }
      .distinct()
      .filterTo(mutableSetOf()) { group ->
        val carried = line.bundle.filter { it.group == group }.map { it.version }.distinct()
        val missing = fallthrough.filter { it.group == group }
        if (missing.isEmpty()) false
        else carried.size != 1 || carried != missing.map { it.version }.distinct()
      }
  }

  /** The family artifacts the render takes from the sidecar because the bundle records none. */
  private fun Line.fallthrough(): List<Member> {
    val carried = bundle.mapTo(mutableSetOf()) { it.group to it.artifact }
    return sidecar.filter { (it.group to it.artifact) !in carried }
  }

  /**
   * Persist the two sides beside the launch descriptor in [destDir] and log any skew. Written for
   * every bundle that names the family, so a later trip can tell "coherent" from "unrecorded".
   */
  fun record(
    destDir: File,
    bundle: List<Member>,
    sidecar: List<Member>,
    system: String,
    onLog: (String) -> Unit,
    demoted: Boolean = false,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (bundle.isEmpty()) return
    val line = Line(bundle = bundle, sidecar = sidecar, demoted = demoted)
    skew(line)?.let { onLog("catalog $system: $it") }
    runCatching {
      fileSystem.write(File(destDir, FILE_NAME).path.toPath()) {
        writeUtf8(json.encodeToString(Line.serializer(), line))
      }
    }
  }

  /**
   * The split a shared `-SNAPSHOT` version can hide, since it names no single build. Too weak to
   * act on at materialization; only read once a Remote Compose linkage error has happened
   * (compose-ai-tools#5015).
   */
  private fun mutableVersionSuspicion(line: Line): String? {
    if (line.bundle.isEmpty()) return null
    val fallthrough = line.fallthrough()
    for (group in line.bundle.map { it.group }.distinct()) {
      val missing = fallthrough.filter { it.group == group }
      if (missing.isEmpty()) continue
      val version =
        (line.bundle.filter { it.group == group } + missing)
          .map { it.version }
          .distinct()
          .singleOrNull() ?: continue
      if (!version.endsWith("-SNAPSHOT")) continue
      return "This catalog's bundle carries ${line.bundle.count { it.group == group }} artifact(s) " +
        "of $group and ${missing.size} more that the render needs come from the daemon sidecar " +
        "instead — ${missing.joinToString { it.artifact }}. Both sides read $version, which names " +
        "no single build, so that is not evidence they came from one; a linkage error inside these " +
        "packages is exactly what two builds of one snapshot look like. Republish the catalog " +
        "against released Remote Compose versions, which do name a build."
    }
    return null
  }

  /**
   * Attributes a fatal Remote Compose linkage [reason] to a mixed classpath, reading the record
   * beside [descriptorPath]; null when the failure isn't Remote Compose's or the line was coherent.
   * Mirrors [SkikoNativePairing.linkageDiagnosis] and [BundleClasspathGaps.linkageDiagnosis].
   */
  fun linkageDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? {
    if (LINKAGE_MARKERS.none { it in reason }) return null
    if (FAMILY_MARKER !in reason.replace('/', '.')) return null
    val file = File(descriptorPath.parentFile ?: return null, FILE_NAME)
    val line =
      runCatching {
        fileSystem
          .read(file.path.toPath()) { readUtf8() }
          .let { json.decodeFromString(Line.serializer(), it) }
      }
        .getOrNull() ?: return null
    return skew(line) ?: mutableVersionSuspicion(line)
  }

  /**
   * `remote-core-1.0.0-alpha18.jar` and friends. The second segment is enumerated so an unrelated
   * `remote-…` jar is never read as a family member.
   */
  private val REMOTE_ARTIFACT =
    Regex(
      """^(remote-(?:core|player|creation|tooling|foundation|material3)(?:-[a-z0-9]+)*)-""" +
        """(\d[\w.\-]*)\.jar$"""
    )

  /** Linkage markers a mixed family explains (same set as [BundleClasspathGaps]). */
  private val LINKAGE_MARKERS =
    listOf(
      "NoSuchFieldError",
      "NoSuchMethodError",
      "NoClassDefFoundError",
      "ClassNotFoundException",
    )
}
