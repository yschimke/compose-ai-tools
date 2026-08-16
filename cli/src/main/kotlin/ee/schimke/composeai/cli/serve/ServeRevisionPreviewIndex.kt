package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compact, generation-time inventory used to remove nonexistent historic versions from menus. */
@Serializable
data class ServeRevisionPreviewIndex(
  val schema: String = "",
  val current: List<String> = emptyList(),
  val revisions: List<Entry> = emptyList(),
) {
  @Serializable data class Entry(val commit: String = "", val previews: List<String> = emptyList())

  /** Null means this branch predates the index, so callers must fail open. */
  fun previewsByCommit(): Map<String, Set<String>>? {
    if (schema != SCHEMA) return null
    return revisions
      .mapNotNull { entry ->
        ServeCatalogRevision.normalize(entry.commit)?.let { it to entry.previews.toSet() }
      }
      .toMap()
  }

  companion object {
    const val FILE_NAME: String = "preview-index.json"
    const val SCHEMA: String = "compose-preview-revision-index/v1"
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray?): ServeRevisionPreviewIndex? = bytes?.let {
      runCatching { JSON.decodeFromString(serializer(), it.toString(Charsets.UTF_8)) }.getOrNull()
    }
  }
}
