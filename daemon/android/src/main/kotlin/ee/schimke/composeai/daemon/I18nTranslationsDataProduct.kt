package ee.schimke.composeai.daemon

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element

/**
 * Producer for `i18n/translations`, backed by Android string resources plus the visible
 * text carried by the Compose semantics tree for the rendered preview.
 */
object I18nTranslationsDataProducer {
  const val KIND: String = "i18n/translations"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "i18n-translations.json"
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

@Serializable
data class I18nTranslationsPayload(
  val supportedLocales: List<String>,
  val renderedLocale: String,
  val defaultLocale: String,
  val strings: List<I18nVisibleString>,
)

@Serializable
data class I18nVisibleString(
  val nodeId: String? = null,
  val boundsInScreen: String,
  val resourceName: String? = null,
  val sourceFile: String? = null,
  val rendered: String,
  val translations: Map<String, String> = emptyMap(),
  val untranslatedLocales: List<String>? = null,
)

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

internal data class AndroidStringCatalog(
  val defaultLocale: String,
  val supportedLocales: List<String>,
  val strings: Map<String, AndroidStringEntry>,
) {
  fun match(rendered: String, renderedLocale: String?): ResolvedString? {
    val locale = renderedLocale?.takeIf { it.isNotBlank() } ?: defaultLocale
    val entry =
      strings.values.firstOrNull { it.resolveFor(locale, defaultLocale) == rendered }
        ?: strings.values.firstOrNull { it.translations.values.any { value -> value == rendered } }
        ?: return null
    val translations = entry.translations.toSortedMap()
    val untranslated =
      supportedLocales
        .filterNot { it in translations.keys }
        .filterNot { it == defaultLocale && entry.defaultValue != null }
    return ResolvedString(
      resourceName = "R.string.${entry.name}",
      sourceFile = entry.sourceFile?.absolutePath,
      translations = translations,
      untranslatedLocales = untranslated,
    )
  }

  private fun AndroidStringEntry.resolveFor(locale: String, defaultLocale: String): String? =
    translations[locale]
      ?: languageOnly(locale)?.let { translations[it] }
      ?: translations[defaultLocale]
      ?: defaultValue

  private fun languageOnly(tag: String): String? =
    tag.split('-', '_').firstOrNull()?.takeIf { it.isNotBlank() && it != tag }

  companion object {
    fun load(resDirs: List<File>, defaultLocale: String): AndroidStringCatalog {
      val entries = linkedMapOf<String, AndroidStringEntry>()
      val locales = linkedSetOf(defaultLocale)
      resDirs.filter { it.isDirectory }.forEach { resDir ->
        resDir.listFiles()
          ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
          ?.sortedBy { it.absolutePath }
          ?.forEach { valuesDir ->
            val locale = localeFromValuesDir(valuesDir.name, defaultLocale) ?: return@forEach
            locales.add(locale)
            valuesDir.listFiles { file -> file.isFile && file.extension == "xml" }
              ?.sortedBy { it.name }
              ?.forEach { xml -> parseStringsXml(xml, locale, defaultLocale, entries) }
          }
      }
      return AndroidStringCatalog(
        defaultLocale = defaultLocale,
        supportedLocales = locales.toList(),
        strings = entries,
      )
    }

    private fun parseStringsXml(
      file: File,
      locale: String,
      defaultLocale: String,
      entries: MutableMap<String, AndroidStringEntry>,
    ) {
      val doc =
        runCatching {
            DocumentBuilderFactory.newInstance()
              .apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
              }
              .newDocumentBuilder()
              .parse(file)
          }
          .getOrNull() ?: return
      val nodes = doc.getElementsByTagName("string")
      for (i in 0 until nodes.length) {
        val element = nodes.item(i) as? Element ?: continue
        val name = element.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
        if (element.getAttribute("translatable") == "false" && locale != defaultLocale) continue
        val value = element.textContent?.trim()?.takeIf { it.isNotBlank() } ?: continue
        val entry = entries.getOrPut(name) { AndroidStringEntry(name = name) }
        if (locale == defaultLocale) {
          entry.defaultValue = value
          entry.sourceFile = entry.sourceFile ?: file
        }
        entry.translations[locale] = value
        if (entry.sourceFile == null) entry.sourceFile = file
      }
    }

    private fun localeFromValuesDir(name: String, defaultLocale: String): String? {
      if (name == "values") return defaultLocale
      val tokens = name.removePrefix("values-").split('-').filter { it.isNotBlank() }
      val bcp = tokens.firstOrNull { it.startsWith("b+") }
      if (bcp != null) return bcp.removePrefix("b+").split('+').joinToString("-")
      val languageIndex =
        tokens.indexOfFirst {
          (it.length == 2 || it.length == 3) && it.all { ch -> ch.isLowerCase() && ch.isLetter() }
        }
      if (languageIndex == -1) return null
      val language = tokens[languageIndex]
      val region =
        tokens.drop(languageIndex + 1).firstOrNull {
          it.length == 3 && it[0] == 'r' && it.drop(1).all { ch -> ch.isUpperCase() && ch.isLetter() }
        }
      return if (region != null) "$language-${region.drop(1)}" else language
    }
  }
}

internal data class AndroidStringEntry(
  val name: String,
  var defaultValue: String? = null,
  var sourceFile: File? = null,
  val translations: MutableMap<String, String> = linkedMapOf(),
)

internal data class ResolvedString(
  val resourceName: String,
  val sourceFile: String?,
  val translations: Map<String, String>,
  val untranslatedLocales: List<String>,
)
