package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/** A catalog-published snapshot of the GitHub issues joined to its previews. */
@Serializable
data class ParityIssues(
  val schema: String = SCHEMA,
  val generatedAt: String? = null,
  val issues: List<ParityIssue> = emptyList(),
) {
  companion object {
    const val SCHEMA = "compose-preview-issues/v1"
    const val DIRECTORY = "parity"
    const val FILE = "issues.json"
  }
}

@Serializable
data class ParityIssue(
  val repository: String,
  val number: Int,
  val title: String,
  /** Read from the wire only to validate the claimed identity; rebuilt before use. */
  val url: String,
  val state: String,
  val area: String? = null,
  val parity: String? = null,
  val system: String? = null,
  val component: String? = null,
  val previewIds: List<String> = emptyList(),
  val referenceIds: List<String> = emptyList(),
  val acceptanceId: String? = null,
)

/** Fail-soft trust boundary for `parity/issues.json`. */
object ServeParityIssuesStore {
  const val MAX_ISSUES = 2000
  private const val MAX_TEXT = 300
  private val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
  private val ID = Regex("[^\\p{Cc}]{1,300}")
  private val AREAS = setOf("spec", "component", "preview", "renderer", "comparison")
  private val PARITY = setOf("regression", "known-difference", "verification-needed")
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
