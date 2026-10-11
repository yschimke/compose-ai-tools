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
 * `history.json`: the precomputed render history shipped on a delivery branch, so the hosted viewer
 * (which has no git checkout) can show a preview's timeline. CI computes it at publish time.
 *
 * Keyed by preview id (`<module>/<fqName>_<label>`), not render path: every consumer addresses
 * previews by id, so the join happens once here.
 *
 * Unrelated to the daemon's history archive (`compose-preview history list|read|diff`), which reads
 * a reporting branch with per-preview sidecars; this is a small static file over flat baseline
 * branches.
 */
@OptIn(ExperimentalSerializationApi::class)
public object PreviewHistoryManifest {

  /** Bumped on incompatible changes so a viewer can refuse what it can't read. */
  public const val FORMAT_VERSION: String = "compose-preview-history/v1"

  /** The file's name on the delivery branch, beside `baselines.json`. */
  public const val FILE_NAME: String = "history.json"

  /**
   * The render-path → preview-id join source ([renderPathsToPreviewIds]), named once for all
   * readers.
   */
  public const val BASELINES_FILE_NAME: String = "baselines.json"

  @Serializable
  public data class Manifest(
    /**
     * Required because [JSON] uses `encodeDefaults = false`, which would otherwise drop the
     * version. A default rather than required so a manifest lacking it still decodes.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("formatVersion")
    val formatVersion: String = FORMAT_VERSION,
    /**
     * The newest delivery-branch commit touching renders that this covers — not the branch tip,
     * which moves with every manifest commit and would make regeneration never converge.
     */
    @SerialName("generatedFrom") val generatedFrom: String,
    /** Keyed by preview id, the same keys `baselines.json` uses. */
    @SerialName("previews") val previews: Map<String, PreviewTimeline>,
  )

  @Serializable
  public data class PreviewTimeline(
    /** Render path on the branch, so a viewer can fetch any version's bytes. */
    @SerialName("path") val path: String,
    /** Newest first. Trimmed when [unstable] — see [PreviewHistory.Timeline.displayVersions]. */
    @SerialName("versions") val versions: List<ManifestVersion>,
    /** Raw commits touching this render, before any collapsing or trimming. */
    @SerialName("observations") val observations: Int,
    /**
     * True when this render keeps reverting to earlier bytes, so a viewer can flag the timeline.
     */
    @SerialName("unstable") val unstable: Boolean,
    /** Returns to previously-seen bytes. Non-zero with `unstable: false` means a lone revert. */
    @SerialName("flapCount") val flapCount: Int,
  )

  @Serializable
  public data class ManifestVersion(
    /** Content sha of the render — stable across commits, so a viewer can cache by it. */
    @SerialName("blob") val blob: String,
    /** Newest delivery-branch commit carrying these bytes. */
    @SerialName("commit") val commit: String,
    /** Author date of [commit], ISO-8601. */
    @SerialName("date") val date: String,
    /** Source commit these bytes were rendered from, when the publish subject recorded one. */
    @SerialName("sourceSha") val sourceSha: String? = null,
    /**
     * Commit that introduced these bytes; omitted when equal to [commit] (fall back via
     * [introducedBy]).
     */
    @SerialName("sinceCommit") val sinceCommit: String? = null,
    /** Publishes carrying these bytes. */
    @SerialName("commits") val commits: Int,
    /** Separate runs that had these bytes; omitted when 1. */
    @SerialName("occurrences") val occurrences: Int? = null,
  ) {
    /** The commit that introduced these bytes, resolving the omitted-when-equal [sinceCommit]. */
    val introducedBy: String
      get() = sinceCommit ?: commit
  }

  /**
   * The two delivery-branch layouts. A baseline branch stores `renders/<module>/<basename>` and
   * needs `baselines.json` to map files to previews; a design catalog branch stores
   * `images/<slug>/<variant>.png`, whose flattened path is the id
   * ([CatalogImagePaths.previewIdFor], reused so the keys match the serve routes).
   */
  public enum class Layout(public val dir: String) {
    /** `renders/<module>/<basename>`, joined through `baselines.json`. */
    RENDERS("renders"),
    /** `images/<slug>/<variant>.png`, joined by flattening the path. */
    IMAGES(CatalogImagePaths.IMAGES_DIR);

    public companion object {
      /** Parse a `--layout` value, or null when it names neither layout. */
      public fun of(value: String?): Layout? = entries.firstOrNull { it.name.equals(value, true) }
    }
  }

  /**
   * Image path → preview id for a [Layout.IMAGES] branch, flattened as the serve routes do. Paths
   * outside `images/` are dropped rather than given a bogus id.
   */
  public fun imagePathsToPreviewIds(paths: Iterable<String>): Map<String, String> {
    val byPath = LinkedHashMap<String, String>()
    for (path in paths) {
      if (!path.startsWith("${CatalogImagePaths.IMAGES_DIR}/") || !path.endsWith(".png")) continue
      if (".." in path.split("/")) continue
      byPath[path] = CatalogImagePaths.previewIdFor(path)
    }
    return byPath
  }

  /**
   * Render path → preview id from a `baselines.json` payload (`module` + `renderBasename`). Entries
   * missing either field are skipped rather than guessed.
   */
  public fun renderPathsToPreviewIds(baselinesJson: String): Map<String, String> {
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
   * Build the manifest from [timelines], keyed via [pathToPreviewId]. Paths with no preview id
   * (deleted or renamed previews) are dropped. Sorted keys keep regeneration byte-identical.
   */
  public fun build(
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

  /** Pretty-printed so regenerations produce reviewable diffs on the branch. */
  public fun encode(manifest: Manifest): String = JSON.encodeToString(manifest) + "\n"

  /** Lenient on read so a manifest written by a newer CLI (extra fields) still loads. */
  public fun decode(text: String): Manifest? = runCatching {
    JSON.decodeFromString<Manifest>(text)
  }
    .getOrNull()

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
