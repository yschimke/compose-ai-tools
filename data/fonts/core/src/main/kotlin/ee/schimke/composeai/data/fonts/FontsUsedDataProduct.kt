package ee.schimke.composeai.data.fonts

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/** Path-backed producer for `fonts/used`, written by backend render loops in default mode. */
object FontsUsedDataProducer {
  const val KIND: String = "fonts/used"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "fonts-used.json"

  val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  fun readPayload(
    rootDir: File,
    previewId: String,
    fileSystem: FileSystem = SystemFileSystem,
  ): FontsUsedPayload? {
    val file = rootDir.resolve(previewId).resolve(FILE)
    if (!file.exists()) return null
    return json.decodeFromString(
      FontsUsedPayload.serializer(),
      fileSystem.read(file.path.toPath()) { readUtf8() },
    )
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    payload: FontsUsedPayload,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(FontsUsedPayload.serializer(), payload))
    }
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
)
