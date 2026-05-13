package ee.schimke.composeai.data.fonts

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Path-backed producer for `fonts/used`, written by backend render loops in default mode. */
object FontsUsedDataProducer {
  const val KIND: String = "fonts/used"
  // Bumped to 2 in #1057 (Cluster D) for the optional `provider` field on
  // `FontUsedEntry`. The field is open-ended (`"google"`, `"asset"`,
  // `"system"`, omitted) and lets the VS Code Text/i18n bundle decide
  // whether to render a Google Fonts external-link affordance without
  // re-doing the bundled allowlist match for every paint.
  const val SCHEMA_VERSION: Int = 2
  const val FILE: String = "fonts-used.json"

  val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  fun readPayload(rootDir: File, previewId: String): FontsUsedPayload? {
    val file = rootDir.resolve(previewId).resolve(FILE)
    if (!file.exists()) return null
    return json.decodeFromString(FontsUsedPayload.serializer(), file.readText())
  }

  fun writeArtifacts(rootDir: File, previewId: String, payload: FontsUsedPayload) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    previewDir.resolve(FILE).writeText(json.encodeToString(FontsUsedPayload.serializer(), payload))
  }
}

@Serializable data class FontsUsedPayload(val fonts: List<FontUsedEntry>)

@Serializable
data class FontUsedEntry(
  val requestedFamily: String,
  val resolvedFamily: String,
  val weight: Int,
  val style: String,
  val sourceFile: String? = null,
  val fellBackFrom: List<String>? = null,
  val consumerNodeIds: List<String> = emptyList(),
  /**
   * Open-ended provenance tag for the resolved font. Today the renderer leaves this null; the
   * Text/i18n VS Code bundle falls back to a bundled Google Fonts allowlist when this is unset.
   * Expected values are `"google"`, `"asset"`, `"system"`. Schema-version-gated so older renderers
   * serialise compatible payloads.
   */
  val provider: String? = null,
)
