package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * The design-parity activity feed a producer publishes with its catalog: recent code commits, Figma
 * file versions and comments, and producer-side mapping gaps.
 *
 * Published rather than queried live because the serve host holds no Figma credential and never
 * talks to Figma (see [ServeFigmaSpec] and `docs/public-preview-server.md`), and has no checkout
 * for `git log`; the publish pipeline snapshots it into `activity.json`, which also makes it
 * reproducible and diffable.
 *
 * Fail-soft like [ServeDesignReferenceStore]: bad records (or the whole feed) are dropped, never
 * the catalog. Free text from other people is untrusted: [ServeWeb] escapes it, and outbound links
 * ([CodeEvent.url], Figma deep links) are reassembled from validated parts against literal origins.
 */
@Serializable
public data class ParityActivity(
  val schema: String = SCHEMA,
  /** ISO-8601 instant the feed was snapshotted. Shown as the feed's "as of". */
  val generatedAt: String? = null,
  /** How far back the producer looked, in days. Informational; drives the header wording only. */
  val windowDays: Int? = null,
  val code: CodeLane? = null,
  val figma: FigmaLane? = null,
  /** Gaps only the producer can see — the server derives preview-side coverage itself. */
  val gaps: List<MappingGap> = emptyList(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-activity/v1"
    public const val DIRECTORY: String = "parity"
    public const val FILE: String = "activity.json"
  }
}

/** Recent commits to the code side of the parity pair. */
@Serializable
public data class CodeLane(
  /** `owner/name` of the source repo, used to rebuild commit URLs. */
  val repo: String? = null,
  /** Branch or ref the commits were read from. */
  val ref: String? = null,
  val events: List<CodeEvent> = emptyList(),
)

/** One commit that touched a file backing at least one catalog preview. */
@Serializable
public data class CodeEvent(
  val sha: String,
  val subject: String,
  /** ISO-8601 author date. */
  val at: String,
  val author: String? = null,
  /** Preview ids this commit touched, resolved by the producer; empty is legal (shared files). */
  val previewIds: List<String> = emptyList(),
  /** Catalog component ids (`Button/Filled`) the commit touched, for display. */
  val components: List<String> = emptyList(),
)

/** Recent activity on the Figma file the catalog is specified by. */
@Serializable
public data class FigmaLane(
  /** Figma file key. Validated before any deep link is built from it. */
  val fileKey: String? = null,
  val fileName: String? = null,
  val versions: List<FigmaVersionEvent> = emptyList(),
  val comments: List<FigmaCommentEvent> = emptyList(),
)

/** One named version / autosave checkpoint in the Figma file's history. */
@Serializable
public data class FigmaVersionEvent(
  val id: String,
  /** ISO-8601. */
  val at: String,
  val label: String? = null,
  val description: String? = null,
  val author: String? = null,
)

/**
 * One Figma comment, anchored to a node when pinned. [previewIds] links it, via `design-map.json`,
 * to the previews that node specifies.
 */
@Serializable
public data class FigmaCommentEvent(
  val id: String,
  /** ISO-8601. */
  val at: String,
  val message: String,
  val author: String? = null,
  val resolved: Boolean = false,
  /** `51592:4768` — the pinned node, when the comment is anchored. */
  val nodeId: String? = null,
  val previewIds: List<String> = emptyList(),
  val components: List<String> = emptyList(),
)

/**
 * A mapping gap the producer found. Preview-side coverage gaps are derived live by the server, so
 * they are not a kind here (a stale file could contradict the catalog).
 */
@Serializable
public data class MappingGap(
  /** One of [Kind]; an unknown token drops the record rather than rendering as a mystery row. */
  val kind: String,
  /** Human summary — what is missing, in the producer's own words. */
  val detail: String,
  /** The design-map `code` locator (`path/Foo.kt#Bar`), when the gap has a code side. */
  val code: String? = null,
  /** The design-map `ref` (`figma:<key>/<node>`), when the gap has a design side. */
  val ref: String? = null,
  /** The preview id the gap concerns, when it names one. */
  val previewId: String? = null,
  val component: String? = null,
) {
  /** The gap kinds the dashboard groups and explains. */
  public object Kind {
    /** `design-map.json` names a preview id the published catalog does not contain. */
    public const val DANGLING_MAPPING: String = "dangling-mapping"

    /** A mapped Figma node exists but its reference raster could not be published. */
    public const val UNRENDERED_REFERENCE: String = "unrendered-reference"

    /** A component published in the Figma file that no code entry maps to. */
    public const val UNMAPPED_DESIGN_NODE: String = "unmapped-design-node"

    public val ALL: Set<String> =
      setOf(DANGLING_MAPPING, UNRENDERED_REFERENCE, UNMAPPED_DESIGN_NODE)
  }
}

/**
 * Validated, read-only view of a catalog's `parity/activity.json`. Permissive about absence, strict
 * about shape: undated events are dropped (the feed is time-ordered), over-long text is truncated,
 * and unknown [MappingGap.Kind]s are dropped.
 */
public object ServeParityActivityStore {

  /** Feed rows kept per lane. A catalog cannot make a page arbitrarily large. */
  private const val MAX_EVENTS = 100

  /** Gap rows kept. Same reasoning as [MAX_EVENTS]. */
  private const val MAX_GAPS = 200

  /** Free text is display-only; longer than this is truncated with an ellipsis. */
  private const val MAX_TEXT = 400

  /** Figma file keys are URL-safe alphanumerics — same rule [ServeFigmaSpec] links by. */
  private val FILE_KEY = Regex("[A-Za-z0-9_-]{1,64}")

  /** `73:6` (API/handle form) or `73-6` (URL form). */
  private val NODE_ID = Regex("[0-9]+[:-][0-9]+")

  /** A full commit sha; short forms are rejected because the URL is rebuilt from it. */
  private val SHA = Regex("[a-f0-9]{7,40}")

  /** `owner/name`, the only shape a github.com commit URL is assembled from. */
  private val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")

  private val JSON = Json { ignoreUnknownKeys = true }

  /**
   * The catalog's activity feed, or null when it publishes none (the common case) or publishes one
   * that does not parse. Never throws.
   */
  public fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ParityActivity? {
    val path = bundleDir.toOkioPath() / ParityActivity.DIRECTORY.toPath() / ParityActivity.FILE
    val raw =
      runCatching {
        if (!fileSystem.exists(path)) return@runCatching null
        JSON.decodeFromString<ParityActivity>(fileSystem.read(path) { readUtf8() })
      }
        .getOrNull() ?: return null
    return sanitize(raw)
  }

  /**
   * Drop every record that can't be rendered honestly and clamp the rest. Public because it is the
   * whole trust boundary and what the tests exercise.
   */
  public fun sanitize(raw: ParityActivity): ParityActivity? {
    if (raw.schema != ParityActivity.SCHEMA) return null
    val code =
      raw.code?.let { lane ->
        CodeLane(
          repo = lane.repo?.trim()?.takeIf { REPO.matches(it) },
          ref = lane.ref?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 120) },
          events =
            lane.events
              .filter { SHA.matches(it.sha.trim().lowercase()) && isTimestamp(it.at) }
              .map { event ->
                CodeEvent(
                  sha = event.sha.trim().lowercase(),
                  subject = clamp(event.subject.trim(), MAX_TEXT),
                  at = event.at.trim(),
                  author = event.author?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 120) },
                  previewIds = event.previewIds.filter { it.isNotBlank() }.distinct().take(50),
                  components = event.components.filter { it.isNotBlank() }.distinct().take(50),
                )
              }
              .sortedByDescending { it.at }
              .take(MAX_EVENTS),
        )
      }
    val figma =
      raw.figma?.let { lane ->
        FigmaLane(
          fileKey = lane.fileKey?.trim()?.takeIf { FILE_KEY.matches(it) },
          fileName = lane.fileName?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 160) },
          versions =
            lane.versions
              .filter { it.id.isNotBlank() && isTimestamp(it.at) }
              .map { version ->
                FigmaVersionEvent(
                  id = clamp(version.id.trim(), 80),
                  at = version.at.trim(),
                  label = version.label?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 160) },
                  description =
                    version.description
                      ?.trim()
                      ?.takeIf { it.isNotEmpty() }
                      ?.let { clamp(it, MAX_TEXT) },
                  author =
                    version.author?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 120) },
                )
              }
              .sortedByDescending { it.at }
              .take(MAX_EVENTS),
          comments =
            lane.comments
              .filter { it.id.isNotBlank() && isTimestamp(it.at) && it.message.isNotBlank() }
              .map { comment ->
                FigmaCommentEvent(
                  id = clamp(comment.id.trim(), 80),
                  at = comment.at.trim(),
                  message = clamp(comment.message.trim(), MAX_TEXT),
                  author =
                    comment.author?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 120) },
                  resolved = comment.resolved,
                  nodeId = comment.nodeId?.trim()?.takeIf { NODE_ID.matches(it) },
                  previewIds = comment.previewIds.filter { it.isNotBlank() }.distinct().take(50),
                  components = comment.components.filter { it.isNotBlank() }.distinct().take(50),
                )
              }
              .sortedByDescending { it.at }
              .take(MAX_EVENTS),
        )
      }
    val gaps =
      raw.gaps
        .filter { it.kind in MappingGap.Kind.ALL && it.detail.isNotBlank() }
        .map { gap ->
          MappingGap(
            kind = gap.kind,
            detail = clamp(gap.detail.trim(), MAX_TEXT),
            code = gap.code?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 300) },
            ref = gap.ref?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 300) },
            previewId = gap.previewId?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 300) },
            component = gap.component?.trim()?.takeIf { it.isNotEmpty() }?.let { clamp(it, 160) },
          )
        }
        .take(MAX_GAPS)
    val sanitized =
      ParityActivity(
        generatedAt = raw.generatedAt?.trim()?.takeIf { isTimestamp(it) },
        windowDays = raw.windowDays?.takeIf { it in 1..3650 },
        code = code?.takeIf { it.events.isNotEmpty() },
        figma = figma?.takeIf { it.versions.isNotEmpty() || it.comments.isNotEmpty() },
        gaps = gaps,
      )
    // An empty feed is treated as no feed, so no empty tab is offered.
    val empty = sanitized.code == null && sanitized.figma == null && sanitized.gaps.isEmpty()
    return sanitized.takeIf { !empty }
  }

  /**
   * The github.com commit URL for [sha] in [repo], or null when either is not the exact shape the
   * URL is built from. Assembled from a literal origin, never taken from the catalog.
   */
  public fun commitUrl(repo: String?, sha: String): String? {
    val owner = repo?.trim()?.takeIf { REPO.matches(it) } ?: return null
    val id = sha.trim().lowercase().takeIf { SHA.matches(it) } ?: return null
    return "https://github.com/$owner/commit/$id"
  }

  /**
   * The figma.com deep link for [nodeId] in [fileKey], or null when either is malformed. Figma's
   * URL form spells a node id `73-6` where the API and the design map use `73:6`.
   */
  public fun nodeUrl(fileKey: String?, nodeId: String?): String? {
    val key = fileKey?.trim()?.takeIf { FILE_KEY.matches(it) } ?: return null
    val node = nodeId?.trim()?.takeIf { NODE_ID.matches(it) } ?: return null
    return "https://www.figma.com/design/$key?node-id=${node.replace(':', '-')}"
  }

  /** The figma.com file URL for [fileKey], or null when it is not a key. */
  public fun fileUrl(fileKey: String?): String? {
    val key = fileKey?.trim()?.takeIf { FILE_KEY.matches(it) } ?: return null
    return "https://www.figma.com/design/$key"
  }

  /**
   * Whether [value] is an ISO-8601 instant, by shape: the feed sorts as text and displays through
   * `prettyDate`, so a matching shape is safe for both.
   */
  private fun isTimestamp(value: String?): Boolean =
    value != null &&
      Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?""").containsMatchIn(value.trim())

  private fun clamp(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"
}
