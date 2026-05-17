package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.strings.I18nTranslationsProduct
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Producer for `i18n/translations`, backed by Android string resources plus the visible text
 * carried by the Compose semantics tree for the rendered preview.
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
    defaultLocale: String =
      System.getProperty(DEFAULT_LOCALE_PROP)?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCALE,
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
      .ifEmpty { listOf(File("src/main/res"), File("src/debug/res")) }

  private fun SemanticsNode.visibleStrings(): List<VisibleString> {
    val cfg = config
    val text =
      cfg
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
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

/** Registry for `i18n/translations`. Path-transport; base class handles the inline upgrade. */
class I18nTranslationsDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
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
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == I18nTranslationsDataProducer.KIND)
      rootDir.resolve(previewId).resolve(I18nTranslationsDataProducer.FILE)
    else null
}
