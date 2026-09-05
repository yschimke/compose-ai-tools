package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * The **design-parity activity feed** a producer publishes alongside its catalog — recent code
 * commits, recent Figma file versions, recent Figma comments, and the mapping gaps only the
 * producer can see.
 *
 * ## Why this is published data rather than a live query
 *
 * The serve host holds **no Figma credential and never talks to Figma** — the same rule that keeps
 * `source.uri` on a [DesignReference] informational (see [ServeFigmaSpec] and
 * `docs/public-preview-server.md`). A dashboard that called `GET /v1/files/:key/comments` at
 * request time would put a write-capable design-file token on a public box and make every page load
 * depend on Figma's rate limit. So the *pipeline* — which already holds `FIGMA_TOKEN` to rasterize
 * references (`emit-design-references.mjs`) — snapshots the activity at publish time into
 * `activity.json`, and the server only reads and renders it. That also makes the page reproducible:
 * the feed is a property of the published catalog, identical for every visitor, and diffable on the
 * delivery branch like everything else there.
 *
 * The same argument applies to the code lane: `git log` needs a checkout, which the publish job has
 * and the server does not.
 *
 * ## Failure posture
 *
 * Fail-soft, exactly like [ServeDesignReferenceStore]: a missing file, a wrong schema token, a
 * malformed record, or an out-of-range field drops that record (or the whole feed) and the catalog
 * serves normally. A parity dashboard is an enhancement; it must never cost a catalog its grid.
 *
 * ## Trust
 *
 * A catalog is third-party data and this file carries **free text written by other people** —
 * commit subjects and Figma comment bodies. Nothing here is trusted: every string is HTML-escaped
 * at render time by [ServeWeb], and the two outbound link shapes ([CodeEvent.url], Figma node deep
 * links) are *reassembled* from validated parts against literal origins rather than taken from the
 * file, so a catalog declaring `javascript:…` produces no link instead of an attacker-chosen href.
 */
@Serializable
data class ParityActivity(
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
  companion object {
    const val SCHEMA = "compose-preview-activity/v1"
    const val DIRECTORY = "parity"
    const val FILE = "activity.json"
  }
}

/** Recent commits to the code side of the parity pair. */
@Serializable
data class CodeLane(
  /** `owner/name` of the source repo, used to rebuild commit URLs. */
  val repo: String? = null,
  /** Branch or ref the commits were read from. */
  val ref: String? = null,
  val events: List<CodeEvent> = emptyList(),
)

/** One commit that touched a file backing at least one catalog preview. */
@Serializable
data class CodeEvent(
  val sha: String,
  val subject: String,
  /** ISO-8601 author date. */
  val at: String,
  val author: String? = null,
  /**
   * Preview ids this commit touched, resolved by the producer from the changed source files. Empty
   * is legal (a commit to a shared file) and simply renders with no inbound links.
   */
  val previewIds: List<String> = emptyList(),
  /** Catalog component ids (`Button/Filled`) the commit touched, for display. */
  val components: List<String> = emptyList(),
)

/** Recent activity on the Figma file the catalog is specified by. */
@Serializable
data class FigmaLane(
  /** Figma file key. Validated before any deep link is built from it. */
  val fileKey: String? = null,
  val fileName: String? = null,
  val versions: List<FigmaVersionEvent> = emptyList(),
  val comments: List<FigmaCommentEvent> = emptyList(),
)

/** One named version / autosave checkpoint in the Figma file's history. */
@Serializable
data class FigmaVersionEvent(
  val id: String,
  /** ISO-8601. */
  val at: String,
  val label: String? = null,
  val description: String? = null,
  val author: String? = null,
)

/**
 * One Figma comment, anchored to a node when the commenter pinned it to one.
 *
 * [previewIds] is the payoff: the producer resolves the pinned node back through `design-map.json`
 * to the previews it specifies, so "a designer commented on this" becomes a link straight to that
 * preview's reference-vs-render comparison.
 */
@Serializable
data class FigmaCommentEvent(
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
 * A mapping gap the **producer** found. Preview-side coverage ("this preview has no design
 * reference") is derived by the server from data it already has, so it is deliberately NOT a kind
 * here — publishing it would let a stale `activity.json` contradict the live catalog.
 */
@Serializable
data class MappingGap(
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
  object Kind {
    /** `design-map.json` names a preview id the published catalog does not contain. */
    const val DANGLING_MAPPING = "dangling-mapping"

    /** A mapped Figma node exists but its reference raster could not be published. */
    const val UNRENDERED_REFERENCE = "unrendered-reference"

    /** A component published in the Figma file that no code entry maps to. */
    const val UNMAPPED_DESIGN_NODE = "unmapped-design-node"

    val ALL = setOf(DANGLING_MAPPING, UNRENDERED_REFERENCE, UNMAPPED_DESIGN_NODE)
  }
}

/**
 * Validated, read-only view of a catalog's `parity/activity.json`.
 *
 * Validation is per-record and permissive about *absence* but strict about *shape*: an event with
 * no parseable timestamp is dropped (the feed is ordered by time, so an undated row has nowhere to
 * go), an over-long free-text field is truncated rather than dropped (the text is display-only),
 * and a gap with an unknown [MappingGap.Kind] is dropped.
 */
object ServeParityActivityStore {

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
  fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ParityActivity? {
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
   * Drop every record that cannot be rendered honestly and clamp the rest. Exposed (rather than
   * private to [load]) because it is the whole of the trust boundary and is what the unit tests
   * exercise — no filesystem needed.
   */
  fun sanitize(raw: ParityActivity): ParityActivity? {
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
    // An empty feed is indistinguishable from no feed, and rendering an empty dashboard is worse
    // than not offering the tab at all.
    val empty = sanitized.code == null && sanitized.figma == null && sanitized.gaps.isEmpty()
    return sanitized.takeIf { !empty }
  }

  /**
   * The github.com commit URL for [sha] in [repo], or null when either is not the exact shape the
   * URL is built from. Assembled from a literal origin, never taken from the catalog.
   */
  fun commitUrl(repo: String?, sha: String): String? {
    val owner = repo?.trim()?.takeIf { REPO.matches(it) } ?: return null
    val id = sha.trim().lowercase().takeIf { SHA.matches(it) } ?: return null
    return "https://github.com/$owner/commit/$id"
  }

  /**
   * The figma.com deep link for [nodeId] in [fileKey], or null when either is malformed. Figma's
   * URL form spells a node id `73-6` where the API and the design map use `73:6`.
   */
  fun nodeUrl(fileKey: String?, nodeId: String?): String? {
    val key = fileKey?.trim()?.takeIf { FILE_KEY.matches(it) } ?: return null
    val node = nodeId?.trim()?.takeIf { NODE_ID.matches(it) } ?: return null
    return "https://www.figma.com/design/$key?node-id=${node.replace(':', '-')}"
  }

  /** The figma.com file URL for [fileKey], or null when it is not a key. */
  fun fileUrl(fileKey: String?): String? {
    val key = fileKey?.trim()?.takeIf { FILE_KEY.matches(it) } ?: return null
    return "https://www.figma.com/design/$key"
  }

  /**
   * Whether [value] is an ISO-8601 instant we can both sort and display. Deliberately a shape check
   * rather than a full parse: the feed is sorted as text (ISO-8601 sorts chronologically) and
   * displayed through the same `prettyDate` the provenance strip uses, so anything that matches is
   * safe on both paths and anything that doesn't has nowhere sensible to go.
   */
  private fun isTimestamp(value: String?): Boolean =
    value != null &&
      Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?""").containsMatchIn(value.trim())

  private fun clamp(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"
}
