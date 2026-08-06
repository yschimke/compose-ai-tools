package ee.schimke.composeai.cli.serve

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `history.json` — the precomputed render history that ships alongside the renders on a delivery
 * branch, so a viewer can show a preview's timeline without a git checkout.
 *
 * This exists because the hosted viewer has no repository. `serve` only has git in project mode
 * (`--revisions`, rooted at the local project repo), so a direct [PreviewHistory] walk can never
 * reach `preview.coo.ee`. CI already has the branch checked out when it publishes, so it computes
 * the history once and commits the answer.
 *
 * **Keyed by preview id, not render path.** [PreviewHistory] works in branch paths
 * (`renders/<module>/<basename>`) because that is what git reports, but every consumer — the
 * viewer, the diff bot, `baselines.json` itself — addresses previews by id
 * (`<module>/<fqName>_<label>`). The join happens here, once, so no consumer has to reconstruct a
 * path convention.
 *
 * ### Relationship to `daemon.history`
 *
 * Not the same feature as the daemon's history archive (`HistorySource`, `GitRefHistorySource`,
 * `compose-preview history list|read|diff`), despite the shared word. That one reads the daemon's
 * *reporting branch*, whose layout is one directory per preview with `entry.json` sidecars carrying
 * semantics / a11y / theme snapshots. This reads the *baseline delivery branches*, written by CI in
 * a flat `renders/<module>/` layout with no sidecars at all.
 *
 * They are kept separate deliberately: a [HistoryEntry][ee.schimke.composeai.daemon.history]-shaped
 * record would be almost entirely null for delivery-branch data, and this side's job is to be a
 * small static file a viewer fetches rather than a live source API. If the two ever converge, this
 * is the file to fold in.
 */
@OptIn(ExperimentalSerializationApi::class)
object PreviewHistoryManifest {

  /**
   * Bumped when the shape changes incompatibly, so a viewer can refuse a manifest it can't read.
   */
  const val FORMAT_VERSION: String = "compose-preview-history/v1"

  /** The file's name on the delivery branch, beside `baselines.json`. */
  const val FILE_NAME: String = "history.json"

  @Serializable
  data class Manifest(
    /**
     * [EncodeDefault] is load-bearing, not decoration: [JSON] sets `encodeDefaults = false` so the
     * redundant fields below stay off the wire, and without this the version discriminator would be
     * dropped by exactly the same rule — leaving every published manifest with nothing for a viewer
     * to version-check against. Kept as a *default* rather than a required parameter so a manifest
     * that somehow lacks it still decodes.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("formatVersion")
    val formatVersion: String = FORMAT_VERSION,
    /**
     * Delivery-branch commit this was computed from. A viewer can compare it against the branch tip
     * to tell whether the manifest is current; it is expected to lag by exactly the commit that
     * carries the manifest itself.
     */
    @SerialName("generatedFrom") val generatedFrom: String,
    /** Keyed by preview id, the same keys `baselines.json` uses. */
    @SerialName("previews") val previews: Map<String, PreviewTimeline>,
  )

  @Serializable
  data class PreviewTimeline(
    /** Render path on the branch, so a viewer can fetch any version's bytes. */
    @SerialName("path") val path: String,
    /** Newest first. Trimmed when [unstable] — see [PreviewHistory.Timeline.displayVersions]. */
    @SerialName("versions") val versions: List<ManifestVersion>,
    /** Raw commits touching this render, before any collapsing or trimming. */
    @SerialName("observations") val observations: Int,
    /**
     * True when this render keeps reverting to bytes it had already moved away from. Present so a
     * viewer can label the timeline as unreliable rather than silently showing a trimmed list that
     * doesn't add up to [observations].
     */
    @SerialName("unstable") val unstable: Boolean,
    /** Returns to previously-seen bytes. Non-zero with `unstable: false` means a lone revert. */
    @SerialName("flapCount") val flapCount: Int,
  )

  @Serializable
  data class ManifestVersion(
    /** Content sha of the render — stable across commits, so a viewer can cache by it. */
    @SerialName("blob") val blob: String,
    /** Newest delivery-branch commit carrying these bytes. */
    @SerialName("commit") val commit: String,
    /** Author date of [commit], ISO-8601. */
    @SerialName("date") val date: String,
    /** Source commit these bytes were rendered from, when the publish subject recorded one. */
    @SerialName("sourceSha") val sourceSha: String? = null,
    /**
     * Delivery-branch commit that introduced these bytes. Omitted when it equals [commit], which is
     * every single-publish version — the common case, and 40 redundant hex characters each. Readers
     * should fall back to [commit]; [introducedBy] does that.
     */
    @SerialName("sinceCommit") val sinceCommit: String? = null,
    /** Publishes carrying these bytes. */
    @SerialName("commits") val commits: Int,
    /**
     * Separate runs that had these bytes. Omitted when 1 (the overwhelming majority), so the file
     * doesn't carry a redundant field on every stable entry.
     */
    @SerialName("occurrences") val occurrences: Int? = null,
  ) {
    /** The commit that introduced these bytes, resolving the omitted-when-equal [sinceCommit]. */
    val introducedBy: String
      get() = sinceCommit ?: commit
  }

  /**
   * Map render path → preview id, parsed from a `baselines.json` payload.
   *
   * The delivery branch stores `renders/<module>/<renderBasename>` and `baselines.json` records
   * `module` + `renderBasename` per preview, so this reverses that into the lookup [build] needs.
   * Entries missing either field are skipped rather than guessed at — a preview whose path can't be
   * reconstructed is better absent from the manifest than present under a wrong key.
   */
  fun renderPathsToPreviewIds(baselinesJson: String): Map<String, String> {
    val root =
      runCatching { Json.parseToJsonElement(baselinesJson).jsonObject }.getOrNull()
        ?: return emptyMap()
    val byPath = LinkedHashMap<String, String>()
    for ((previewId, entry) in root) {
      val obj = entry as? JsonObject ?: continue
      val module = obj["module"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
      val basename = obj["renderBasename"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
      if (module.isEmpty() || basename.isEmpty()) continue
      byPath["renders/$module/$basename"] = previewId
    }
    return byPath
  }

  /**
   * Build the manifest from extracted [timelines], keyed by preview id via [pathToPreviewId].
   *
   * A render path with no matching preview id is dropped. That is the normal fate of history for a
   * preview that has since been deleted or renamed: it still has commits on the branch, but nothing
   * in `baselines.json` points at it, so no viewer could address it anyway.
   *
   * Previews are emitted in sorted key order so regenerating an unchanged branch produces a
   * byte-identical file — otherwise every publish would show a spurious `history.json` diff.
   */
  fun build(
    timelines: Map<String, PreviewHistory.Timeline>,
    pathToPreviewId: Map<String, String>,
    generatedFrom: String,
  ): Manifest {
    val previews = sortedMapOf<String, PreviewTimeline>()
    for ((path, timeline) in timelines) {
      val previewId = pathToPreviewId[path] ?: continue
      if (timeline.versions.isEmpty()) continue
      previews[previewId] =
        PreviewTimeline(
          path = path,
          versions = timeline.displayVersions.map { it.toManifestVersion() },
          observations = timeline.observations,
          unstable = timeline.unstable,
          flapCount = timeline.flapCount,
        )
    }
    return Manifest(generatedFrom = generatedFrom, previews = previews)
  }

  private fun PreviewHistory.Version.toManifestVersion() =
    ManifestVersion(
      blob = blob,
      commit = until.commit,
      date = until.date,
      sourceSha = until.sourceSha,
      sinceCommit = since.commit.takeIf { it != until.commit },
      commits = commits,
      occurrences = occurrences.takeIf { it > 1 },
    )

  /**
   * Serialize for committing. Pretty-printed because this file lands on a branch that humans and
   * the diff bot read: a one-line JSON blob would make every regeneration an unreviewable diff,
   * whereas one field per line keeps a changed timeline legible in a PR.
   */
  fun encode(manifest: Manifest): String = JSON.encodeToString(manifest) + "\n"

  /** Lenient on read so a manifest written by a newer CLI (extra fields) still loads. */
  fun decode(text: String): Manifest? =
    runCatching { JSON.decodeFromString<Manifest>(text) }.getOrNull()

  private val JSON = Json {
    prettyPrint = true
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
  }

  /** `jsonPrimitive.contentOrNull` throws on a non-string primitive; this never does. */
  private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null
}
