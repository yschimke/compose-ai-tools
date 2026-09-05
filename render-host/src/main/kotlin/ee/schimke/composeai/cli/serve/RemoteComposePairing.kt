package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Keeps a served bundle's Remote Compose artifacts on **one line** — and says which line, when the
 * bundle and the daemon sidecar each hold half of one.
 *
 * ## The failure this exists to name
 *
 * `androidx.compose.remote:*` is a family whose members are compiled against each other:
 * `remote-player-core`'s `RemoteDocument` reads `RemoteClock.SYSTEM` out of `remote-core`, the
 * `remote-creation*` artifacts write the documents the players read. Two of them from two builds on
 * one classpath is a linkage error waiting for the first render — exactly the shape
 * [SkikoNativePairing] guards for Skiko's bindings/native pair.
 *
 * The serve path mixes lines structurally. A bundle records its own Remote Compose coordinates and
 * [ServeBundleDaemon.shouldPrecedeDaemonSidecar] promotes them ahead of the daemon sidecar (they
 * are `androidx.`), so the artifacts the bundle **carries** win. The sidecar keeps its own pin for
 * everything else — and the sidecar's IR replay connector
 * ([ee.schimke.composeai.daemon.RemoteComposeIrReplay]) links against whatever answers. So a bundle
 * that carries part of the family leaves the rest at the sidecar's version, and the two halves meet
 * inside the first IR replay:
 * ```
 * java.lang.NoSuchFieldError: Class androidx.compose.remote.core.RemoteClock
 *   does not have member field 'androidx.compose.remote.core.RemoteClock SYSTEM'
 *     at androidx.compose.remote.player.core.RemoteDocument.<clinit>(RemoteDocument.java:44)
 * ```
 *
 * — `meshcore-mobile`, whose bundle pins the family at `1.0.0-SNAPSHOT` from an androidx.dev build
 * against a sidecar on released alphas. The [RenderCircuitBreaker] classifies that as fatal (a
 * linkage error no retry can clear, correctly), latches the whole catalog's live lane behind it,
 * and shows the reason as-is: a field name and no cause at all (compose-preview-server#187).
 *
 * ## What this adds
 *
 * [BundleClasspathGaps] explains a coordinate that went **missing** or came back as the **wrong
 * bytes**. This is the third shape: everything resolved, every hash matched, and the classpath
 * still carries two Remote Compose lines because the bundle and the sidecar each supplied part of
 * the family. Nothing in the resolution record can see that — it is a property of the two lists
 * side by side, which is where [skew] reads it.
 *
 * Deliberately narrow, matching [SkikoNativePairing.classpathSkew]'s "leading half-pair" rule. A
 * bundle that carries **every** family artifact the sidecar has shadows the sidecar's copies whole
 * and is coherent whatever its version; a bundle that carries none of them runs entirely on the
 * sidecar's line and is coherent too. Only a bundle carrying *some* of the family at a version that
 * is not the sidecar's mixes the two, and only that is reported.
 *
 * ## Which side wins
 *
 * In that one state, and **only on a bundle that carries IR** ([ServeBundleDaemon] passes `hasIr`),
 * the bundle's copies stop being promoted ahead of the sidecar ([isFamilyMember] drops them out of
 * the parent overlay). The gate matters: what makes the sidecar authoritative is that
 * `RemoteComposeIrReplay` is **sidecar** code and a replayed document has no consumer class of its
 * own to keep a version priority for. A bundle with no IR has the opposite property — its previews
 * are consumer bytecode compiled against the versions the bundle records — so
 * [ServeBundleDaemon.shouldPrecedeDaemonSidecar]'s "the catalog's framework versions win" rule
 * stands there untouched, and a split family is reported without being rearranged.
 *
 * On an IR bundle the exception costs nothing that rule was protecting: a half-promoted family wins
 * nothing — it links its own half against the sidecar's other half and every render dies. A mixed
 * bundle (IR previews *and* class-backed ones) is the one place the trade is real, and it goes the
 * same way: the class-backed previews may lose their own Remote Compose versions, but the
 * alternative is the fatal breaker latching the lane for **every** preview in the catalog, IR or
 * not. The worst case after demotion is a document the sidecar's player is too old to replay, which
 * fails that render and leaves the lane, the rest of the catalog and the background optimizer pass
 * alive.
 */
internal object RemoteComposePairing {

  /**
   * What every Remote Compose group and package has in common — `androidx.compose.remote`,
   * `androidx.wear.compose.remote` — so one marker identifies both a coordinate's group and the
   * package a linkage error names (the internal `androidx/compose/remote/…` form is dotted first).
   */
  private const val FAMILY_MARKER = "compose.remote"

  /**
   * The family's main line, and the group a sidecar jar belongs to unless its artifact says Wear.
   */
  const val BASE_GROUP: String = "androidx.compose.remote"

  /** The Wear line, versioned independently of [BASE_GROUP]. */
  private const val WEAR_GROUP = "androidx.wear.compose.remote"

  /**
   * Artifacts published by [WEAR_GROUP] rather than [BASE_GROUP]. Needed only for the sidecar side,
   * where the group is not in the path — a `:cli:installDist` `lib/` is flat, so `remote-material3`
   * is all there is to go on. The bundle side carries its real group on the coordinate.
   */
  private val WEAR_ARTIFACTS = setOf("remote-material3")

  /** File name written beside the launch descriptor, mirroring [BundleClasspathGaps]. */
  private const val FILE_NAME = "remote-compose-line.json"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * One Remote Compose artifact, the version the classpath will load it at, and the line it belongs
   * to.
   *
   * [group] is what keeps the **base** family (`androidx.compose.remote`) and the **Wear** one
   * (`androidx.wear.compose.remote`) from being compared against each other. They version
   * independently and legitimately — this server's own sidecar ships `remote-core` at
   * `1.0.0-alpha18` beside `remote-material3` at `1.0.0-alpha10` — so a single "one version across
   * the whole family" rule would call every such bundle skewed. Defaulted to the base group so a
   * record written before this field existed still reads back as what it was.
   */
  @Serializable
  data class Member(
    val artifact: String,
    val version: String,
    val group: String = BASE_GROUP,
  )

  /**
   * What each side of the daemon `-cp` contributes to the family: the artifacts the bundle carries
   * (promoted ahead of the sidecar) and the ones the sidecar ships. Persisted so a trip can be
   * diagnosed without re-deriving a classpath the daemon assembled at materialization.
   */
  @Serializable
  data class Line(
    val bundle: List<Member> = emptyList(),
    val sidecar: List<Member> = emptyList(),
    /**
     * Whether [ServeBundleDaemon] demoted the bundle's copies behind the sidecar to keep the family
     * on one line. Defaulted so a record written before the demotion existed still reads back.
     */
    val demoted: Boolean = false,
  )

  /** Whether [coordinate] belongs to the Remote Compose family at all. */
  fun isFamilyMember(coordinate: BundleReader.ClasspathEntry.Maven): Boolean =
    coordinate.group.contains(FAMILY_MARKER)

  /**
   * Whether [coordinate] is in one of the [skewedGroups] this bundle must stop promoting — the
   * group's own supply is split, so its version priority is worth nothing. A group the bundle
   * covers coherently keeps its priority even when a sibling group next to it does not.
   */
  fun isDemoted(coordinate: BundleReader.ClasspathEntry.Maven, skewedGroups: Set<String>): Boolean =
    isFamilyMember(coordinate) && coordinate.group in skewedGroups

  /** The family members among the bundle's **resolved** coordinates. */
  fun bundleMembers(coords: List<BundleReader.ClasspathEntry.Maven>): List<Member> =
    coords
      .filter { isFamilyMember(it) }
      .map { Member(it.artifact, it.version, it.group) }
      .distinct()

  /**
   * The family members on the daemon sidecar's own classpath, read off jar filenames.
   *
   * The sidecar is a `:cli:installDist` layout — `lib/<artifact>-<version>.jar` — so the artifact
   * and version are both in the name. Bundle-side artifacts are NOT read this way: a resolved
   * `.aar` reaches the classpath as `extracted/<sha256>/classes.jar`, which carries neither, which
   * is why [bundleMembers] reads the coordinates instead.
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
   * The sentence for a classpath that will load two Remote Compose lines, or null when the family
   * is coherent — one side supplies all of it, or both sides agree on the version.
   *
   * A version both sides agree on is taken at face value here even when it is a `-SNAPSHOT` that
   * names no single build: acting on that suspicion at materialization would demote a family that
   * is probably fine. [mutableVersionSuspicion] carries it to the one place it is evidence.
   *
   * "Coherent" is judged per artifact and not per version count: the bundle's copies precede the
   * sidecar's, so a family artifact the bundle carries is the bundle's whatever else is behind it,
   * and only an artifact the bundle does **not** carry falls through to the sidecar's version.
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
   * The Remote Compose groups whose supply is split between the bundle and the sidecar at differing
   * versions — the groups [ServeBundleDaemon] must stop promoting on an IR bundle, and empty when
   * the classpath is coherent.
   *
   * Judged **per group**, never across the family as a whole. `androidx.compose.remote` and
   * `androidx.wear.compose.remote` version independently (alpha18 beside alpha10 in this server's
   * own sidecar), so a bundle carrying both lines is normal and comparing one against the other
   * would report a skew that is not there — and then demote a base family the bundle carries
   * coherently, which is the failure this whole file exists to prevent.
   *
   * Within a group it is judged per artifact and not per version count: the bundle's copies precede
   * the sidecar's, so an artifact the bundle carries is the bundle's whatever else is behind it,
   * and only an artifact it does **not** carry falls through to the sidecar's version.
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
   * Persist the two sides beside the launch descriptor in [destDir] and log the skew, if any.
   *
   * The record is written whenever the bundle names the family at all — a healthy catalog costs one
   * small file and lets a later trip say "the line was coherent" by finding nothing to report,
   * rather than by finding nothing at all.
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
   * The split a **mutable** version hides: the two sides read the same `-SNAPSHOT`, which names no
   * single build, so version equality proves nothing about whether they came from one. Null when
   * the family is not split at all, or when the versions that agree are released ones.
   *
   * Not enough to act on at materialization — demoting a family that is probably coherent would
   * cost every catalog on a snapshot line its own versions for no evidence. It IS enough once a
   * Remote Compose linkage error has actually happened, which is the only place it is read: at that
   * point the classpath has demonstrated the split that the version strings could not rule out.
   * This is the shape that killed `meshcore-mobile` before content-keyed extraction landed
   * (compose-ai-tools#5015) — both sides said `1.0.0-SNAPSHOT` and a stale extraction served one of
   * them from another build.
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
   * One sentence attributing a **fatal** linkage [reason] inside the Remote Compose packages to a
   * classpath that mixes two of its lines — or null when the failure is not Remote Compose's, the
   * record is unreadable, or the family was coherent and the failure has some other cause.
   *
   * Read at trip time from [descriptorPath]'s directory, mirroring
   * [SkikoNativePairing.linkageDiagnosis] and [BundleClasspathGaps.linkageDiagnosis]: the open
   * breaker's reason is the only report anyone outside the box reads.
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
   * `remote-core-1.0.0-alpha18.jar`, `remote-player-view-1.0.0-SNAPSHOT.jar`,
   * `remote-material3-1.0.0-alpha10.jar` — the family's published artifacts, named from a flat
   * `lib/` directory where the group is not in the path.
   *
   * The second segment is enumerated rather than left open (`remote-\w+`) so an unrelated
   * `remote-…` jar from some other group cannot be read as a Remote Compose member and reported as
   * a skew that isn't one. Every published family artifact starts with one of these: `remote-core`,
   * `remote-player-*`, `remote-creation-*`, `remote-tooling-preview`, `remote-foundation`,
   * `remote-material3`.
   */
  private val REMOTE_ARTIFACT =
    Regex(
      """^(remote-(?:core|player|creation|tooling|foundation|material3)(?:-[a-z0-9]+)*)-""" +
        """(\d[\w.\-]*)\.jar$"""
    )

  /**
   * Linkage markers a mixed family explains. The same set [BundleClasspathGaps] uses and for the
   * same reason: a `VerifyError` or an `UnsatisfiedLinkError` is a different fault, and two Remote
   * Compose lines say nothing useful about it.
   */
  private val LINKAGE_MARKERS =
    listOf(
      "NoSuchFieldError",
      "NoSuchMethodError",
      "NoClassDefFoundError",
      "ClassNotFoundException",
    )
}
