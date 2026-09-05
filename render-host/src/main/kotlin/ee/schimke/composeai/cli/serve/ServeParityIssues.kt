package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/** Source-compatible names for the wire types now owned by compose-preview-contracts. */
typealias ParityIssues = ee.schimke.composeai.parityissues.protocol.ParityIssues

typealias ParityIssue = ee.schimke.composeai.parityissues.protocol.ParityIssue

/** Fail-soft trust boundary for `parity/issues.json`. */
object ServeParityIssuesStore {
  const val MAX_ISSUES = 2000
  private const val MAX_TEXT = 300
  private val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
  private val ID = Regex("[^\\p{Cc}]{1,300}")
  private val AREAS = setOf("spec", "component", "preview", "renderer", "comparison")
  /**
   * The `parity:` vocabulary this index accepts, kept in step with `parity-issues.mjs`'s own set —
   * a value only one end knows is a classification the round trip drops without saying so.
   * `upstream` and `catalog` are the two answers the report form's "Where does it belong?" control
   * offers beside the `verification-needed` it already had.
   */
  private val PARITY =
    setOf("regression", "known-difference", "verification-needed", "upstream", "catalog")
  private val JSON = Json { ignoreUnknownKeys = true }

  fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ParityIssues? {
    val path = bundleDir.toOkioPath() / ParityIssues.DIRECTORY.toPath() / ParityIssues.FILE
    val raw =
      runCatching {
        if (!fileSystem.exists(path)) return@runCatching null
        JSON.decodeFromString<ParityIssues>(fileSystem.read(path) { readUtf8() })
      }
        .getOrNull() ?: return null
    return sanitize(raw)
  }

  fun sanitize(raw: ParityIssues): ParityIssues? {
    if (raw.schema != ParityIssues.SCHEMA || raw.issues.size > MAX_ISSUES) return null
    val generatedAt = raw.generatedAt?.trim()?.takeIf(::isTimestamp)
    // Identity is repository + number + **component**, not repository + number. One issue may name
    // several components — an umbrella report covering three Elevated stickers files one issue and
    // the producer emits a row each — and deduping by issue alone collapsed those to an arbitrary
    // one of them at load time, so the report reached one component page and silently missed the
    // rest. Two rows that agree on all three are still a duplicate and still collapse.
    val issues =
      raw.issues.mapNotNull(::sanitizeIssue).distinctBy {
        Triple(it.repository, it.number, it.component)
      }
    if (issues.isEmpty()) return null
    return ParityIssues(generatedAt = generatedAt, issues = issues)
  }

  private fun sanitizeIssue(raw: ParityIssue): ParityIssue? {
    val repository = raw.repository.trim().lowercase().takeIf(REPOSITORY::matches) ?: return null
    if (raw.number < 1) return null
    val expected = "https://github.com/$repository/issues/${raw.number}"
    val claimed = canonicalIssueUrl(raw.url) ?: return null
    if (claimed != expected) return null
    val state =
      raw.state.trim().lowercase().takeIf { it == "open" || it == "closed" } ?: return null
    val title =
      raw.title.trim().takeIf { it.isNotEmpty() }?.let { clamp(it, MAX_TEXT) } ?: return null
    val scope =
      raw.scope.trim().lowercase().takeIf { it == "component" || it == "variant" } ?: return null
    return ParityIssue(
      repository = repository,
      number = raw.number,
      title = title,
      url = expected,
      state = state,
      area = raw.area?.removePrefix("area:")?.lowercase()?.takeIf(AREAS::contains),
      parity = raw.parity?.removePrefix("parity:")?.lowercase()?.takeIf(PARITY::contains),
      system = raw.system.cleanId(),
      component = raw.component.cleanId(),
      scope = scope,
      previewIds = raw.previewIds.mapNotNull { it.cleanId() }.distinct().take(100),
      referenceIds = raw.referenceIds.mapNotNull { it.cleanId() }.distinct().take(100),
      acceptanceId = raw.acceptanceId.cleanId(),
    )
  }

  private fun String?.cleanId(): String? =
    this?.trim()?.takeIf { ID.matches(it) }?.let { clamp(it, MAX_TEXT) }

  private fun canonicalIssueUrl(value: String): String? {
    val match =
      Regex(
          "https://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/issues/([0-9]+)/?",
          RegexOption.IGNORE_CASE,
        )
        .matchEntire(value.trim()) ?: return null
    val repository = "${match.groupValues[1]}/${match.groupValues[2]}".lowercase()
    if (!REPOSITORY.matches(repository)) return null
    val number = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return "https://github.com/$repository/issues/$number"
  }

  private fun isTimestamp(value: String): Boolean = runCatching {
    Instant.parse(value)
    true
  }
    .getOrDefault(false)

  private fun clamp(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max - 1) + "…"
}
