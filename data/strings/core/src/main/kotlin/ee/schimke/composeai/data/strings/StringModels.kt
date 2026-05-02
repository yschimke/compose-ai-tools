package ee.schimke.composeai.data.strings

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import org.w3c.dom.Element

object TextStringsProduct {
  const val KIND: String = "text/strings"
  const val SCHEMA_VERSION: Int = 1
}

object I18nTranslationsProduct {
  const val KIND: String = "i18n/translations"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "i18n-translations.json"
}

@Serializable data class TextStringsPayload(val texts: List<TextStringEntry>)

@Serializable
data class TextStringEntry(
  val text: String? = null,
  val textSource: String? = null,
  val semanticsText: String? = null,
  val semanticsLabel: String? = null,
  val fontSize: String? = null,
  val foregroundColor: String? = null,
  val backgroundColor: String? = null,
  val editableText: String? = null,
  val inputText: String? = null,
  val nodeId: String,
  val boundsInScreen: String,
  val localeTag: String,
  val fontScale: Float,
)

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

data class AndroidStringCatalog(
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
      val nonTranslatable = linkedSetOf<String>()
      val locales = linkedSetOf(defaultLocale)
      resDirs
        .filter { it.isDirectory }
        .forEach { resDir ->
          resDir
            .listFiles()
            ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            ?.sortedBy { it.absolutePath }
            ?.forEach { valuesDir ->
              val locale = localeFromValuesDir(valuesDir.name, defaultLocale) ?: return@forEach
              locales.add(locale)
              valuesDir
                .listFiles { file -> file.isFile && file.extension == "xml" }
                ?.sortedBy { it.name }
                ?.forEach { xml ->
                  parseStringsXml(xml, locale, defaultLocale, entries, nonTranslatable)
                }
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
      nonTranslatable: MutableSet<String>,
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
        if (element.getAttribute("translatable") == "false") {
          nonTranslatable.add(name)
          entries.remove(name)
          continue
        }
        if (name in nonTranslatable) continue
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
      val languageIndex = tokens.indexOfFirst {
        (it.length == 2 || it.length == 3) && it.all { ch -> ch.isLowerCase() && ch.isLetter() }
      }
      if (languageIndex == -1) return null
      val language = tokens[languageIndex]
      val region =
        tokens.drop(languageIndex + 1).firstOrNull {
          it.length == 3 &&
            it[0] == 'r' &&
            it.drop(1).all { ch -> ch.isUpperCase() && ch.isLetter() }
        }
      return if (region != null) "$language-${region.drop(1)}" else language
    }
  }
}

data class AndroidStringEntry(
  val name: String,
  var defaultValue: String? = null,
  var sourceFile: File? = null,
  val translations: MutableMap<String, String> = linkedMapOf(),
)

data class ResolvedString(
  val resourceName: String,
  val sourceFile: String?,
  val translations: Map<String, String>,
  val untranslatedLocales: List<String>,
)
