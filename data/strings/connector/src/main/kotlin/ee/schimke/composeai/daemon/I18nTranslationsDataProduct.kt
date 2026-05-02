package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.strings.I18nTranslationsProduct
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Producer for `i18n/translations`, backed by Android string resources plus the visible
 * text carried by the Compose semantics tree for the rendered preview.
 */
object I18nTranslationsDataProducer {
  const val KIND: String = I18nTranslationsProduct.KIND
  const val SCHEMA_VERSION: Int = I18nTranslationsProduct.SCHEMA_VERSION
  const val FILE: String = I18nTranslationsProduct.FILE
  const val RES_DIRS_PROP: String = "composeai.daemon.resDirs"
  private const val DEFAULT_LOCALE_PROP: String = "composeai.daemon.defaultLocale"
  private const val DEFAULT_LOCALE: String = "en"

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    root: SemanticsNode,
    renderedLocale: String?,
    resDirs: List<File> = resDirsFromSysprop(),
    defaultLocale: String = System.getProperty(DEFAULT_LOCALE_PROP)?.takeIf { it.isNotBlank() }
      ?: DEFAULT_LOCALE,
  ) {
    val catalog = AndroidStringCatalog.load(resDirs = resDirs, defaultLocale = defaultLocale)
    val payload =
      I18nTranslationsPayload(
        supportedLocales = catalog.supportedLocales,
        renderedLocale = renderedLocale?.takeIf { it.isNotBlank() } ?: defaultLocale,
        defaultLocale = defaultLocale,
        strings =
          root.visibleStrings().map { visible ->
            val resolved = catalog.match(visible.rendered, renderedLocale)
            I18nVisibleString(
              nodeId = visible.nodeId,
              boundsInScreen = visible.boundsInScreen,
              resourceName = resolved?.resourceName,
              sourceFile = resolved?.sourceFile,
              rendered = visible.rendered,
              translations = resolved?.translations.orEmpty(),
              untranslatedLocales =
                resolved?.untranslatedLocales.orEmpty().takeIf { it.isNotEmpty() },
            )
          },
      )
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    previewDir.resolve(FILE).writeText(json.encodeToString(payload))
  }

  internal fun resDirsFromSysprop(): List<File> =
    (System.getProperty(RES_DIRS_PROP) ?: "")
      .split(File.pathSeparator)
      .mapNotNull { it.takeIf(String::isNotBlank)?.let(::File) }
      .ifEmpty {
        listOf(
          File("src/main/res"),
          File("src/debug/res"),
        )
      }

  private fun SemanticsNode.visibleStrings(): List<VisibleString> {
    val cfg = config
    val text =
      cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
        ?.joinToString(" ") { it.text }
        ?.takeIf { it.isNotBlank() }
        ?: cfg
          .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
          ?.joinToString(" ")
          ?.takeIf { it.isNotBlank() }
    val here =
      text?.let {
        listOf(
          VisibleString(
            nodeId = id.toString(),
            boundsInScreen = boundsInRoot.toWireBounds(),
            rendered = it,
          )
        )
      } ?: emptyList()
    return here + children.flatMap { it.visibleStrings() }
  }

  private fun androidx.compose.ui.geometry.Rect.toWireBounds(): String =
    "${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}"

  private data class VisibleString(
    val nodeId: String,
    val boundsInScreen: String,
    val rendered: String,
  )
}

typealias I18nTranslationsPayload = ee.schimke.composeai.data.strings.I18nTranslationsPayload

typealias I18nVisibleString = ee.schimke.composeai.data.strings.I18nVisibleString

typealias AndroidStringCatalog = ee.schimke.composeai.data.strings.AndroidStringCatalog

typealias AndroidStringEntry = ee.schimke.composeai.data.strings.AndroidStringEntry

typealias ResolvedString = ee.schimke.composeai.data.strings.ResolvedString

/** Registry side for `i18n/translations`; reads the latest JSON artefact from disk. */
class I18nTranslationsDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = I18nTranslationsDataProducer.KIND,
        schemaVersion = I18nTranslationsDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != I18nTranslationsDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = I18nTranslationsDataProducer.SCHEMA_VERSION,
          path = file.absolutePath,
        )
      )
    }
    val payload: JsonObject =
      try {
        json.parseToJsonElement(file.readText()) as JsonObject
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = I18nTranslationsDataProducer.SCHEMA_VERSION,
        payload = payload,
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (I18nTranslationsDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = I18nTranslationsDataProducer.KIND,
        schemaVersion = I18nTranslationsDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(I18nTranslationsDataProducer.FILE)
}
