package ee.schimke.composeai.daemon

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.TypedValue
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Producer for `resources/used`, the Android resources resolved while rendering a preview. */
object ResourcesUsedDataProducer {
  const val KIND: String = "resources/used"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "resources-used.json"

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun recorder(base: Context): RecordingResources {
    val resources = base.resources
    return RecordingResources(
      base = resources,
      assets = resources.assets,
      displayMetrics = resources.displayMetrics,
      configuration = resources.configuration,
    )
  }

  fun context(base: Context, resources: RecordingResources): Context =
    object : ContextWrapper(base) {
      override fun getResources(): Resources = resources

      override fun getAssets(): AssetManager = resources.assets
    }

  fun writeArtifacts(rootDir: File, previewId: String, recorder: RecordingResources) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val payload = ResourcesUsedPayload(references = recorder.references())
    previewDir.resolve(FILE).writeText(
      json.encodeToString(ResourcesUsedPayload.serializer(), payload)
    )
  }
}

@Serializable
data class ResourcesUsedPayload(val references: List<ResourceUsedReference>)

@Serializable
data class ResourceUsedReference(
  val resourceType: String,
  val resourceName: String,
  val packageName: String,
  val resolvedValue: String? = null,
  val resolvedFile: String? = null,
  val consumers: List<ResourceUsedConsumer>,
)

@Serializable data class ResourceUsedConsumer(val nodeId: String)

class RecordingResources(
  private val base: Resources,
  assets: AssetManager,
  displayMetrics: android.util.DisplayMetrics,
  configuration: android.content.res.Configuration,
) : Resources(assets, displayMetrics, configuration) {
  private val references =
    Collections.synchronizedMap(linkedMapOf<String, ResourceUsedReference>())

  fun references(): List<ResourceUsedReference> =
    synchronized(references) { references.values.toList().sortedWith(compareBy({ it.resourceType }, { it.resourceName })) }

  override fun getText(id: Int): CharSequence =
    base.getText(id).also { record(id, "string", it.toString()) }

  override fun getString(id: Int): String =
    base.getString(id).also { record(id, "string", it) }

  override fun getString(id: Int, vararg formatArgs: Any?): String =
    base.getString(id, *formatArgs).also { record(id, "string", it) }

  override fun getQuantityText(id: Int, quantity: Int): CharSequence =
    base.getQuantityText(id, quantity).also { record(id, "plurals", it.toString()) }

  override fun getQuantityString(id: Int, quantity: Int): String =
    base.getQuantityString(id, quantity).also { record(id, "plurals", it) }

  override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
    base.getQuantityString(id, quantity, *formatArgs).also { record(id, "plurals", it) }

  override fun getTextArray(id: Int): Array<CharSequence> =
    base.getTextArray(id).also { record(id, "array", it.joinToString()) }

  override fun getStringArray(id: Int): Array<String> =
    base.getStringArray(id).also { record(id, "array", it.joinToString()) }

  override fun obtainTypedArray(id: Int): TypedArray =
    base.obtainTypedArray(id).also { record(id, "array", null) }

  override fun getDimension(id: Int): Float =
    base.getDimension(id).also { record(id, "dimen", dimensionValue(id)) }

  override fun getDimensionPixelOffset(id: Int): Int =
    base.getDimensionPixelOffset(id).also { record(id, "dimen", dimensionValue(id)) }

  override fun getDimensionPixelSize(id: Int): Int =
    base.getDimensionPixelSize(id).also { record(id, "dimen", dimensionValue(id)) }

  override fun getDrawable(id: Int): Drawable =
    base.getDrawable(id).also { record(id, "drawable", fileValue(id)) }

  override fun getDrawable(id: Int, theme: Theme?): Drawable =
    base.getDrawable(id, theme).also { record(id, "drawable", fileValue(id)) }

  override fun getDrawableForDensity(id: Int, density: Int): Drawable =
    base.getDrawableForDensity(id, density)!!.also { record(id, "drawable", fileValue(id)) }

  override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable =
    base.getDrawableForDensity(id, density, theme)!!.also { record(id, "drawable", fileValue(id)) }

  override fun getColor(id: Int): Int =
    base.getColor(id).also { record(id, "color", "#%08X".format(it)) }

  override fun getColor(id: Int, theme: Theme?): Int =
    base.getColor(id, theme).also { record(id, "color", "#%08X".format(it)) }

  override fun getColorStateList(id: Int): ColorStateList =
    base.getColorStateList(id).also { record(id, "color", colorStateListValue(it)) }

  override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
    base.getColorStateList(id, theme).also { record(id, "color", colorStateListValue(it)) }

  override fun getLayout(id: Int): XmlResourceParser =
    base.getLayout(id).also { record(id, "layout", fileValue(id)) }

  private fun record(id: Int, fallbackType: String, resolvedValue: String?) {
    val type = runCatching { base.getResourceTypeName(id) }.getOrNull() ?: fallbackType
    if (type !in SUPPORTED_TYPES) return
    val name = runCatching { base.getResourceEntryName(id) }.getOrNull() ?: return
    val packageName = runCatching { base.getResourcePackageName(id) }.getOrNull() ?: ""
    val file = resolvedFile(id) ?: ResourceSourceIndex.find(type = type, name = name)
    references["$packageName:$type/$name"] =
      ResourceUsedReference(
        resourceType = type,
        resourceName = name,
        packageName = packageName,
        resolvedValue = resolvedValue?.takeIf { it.isNotBlank() },
        resolvedFile = file,
        consumers = emptyList(),
      )
  }

  private fun resolvedFile(id: Int): String? {
    return fileValue(id)?.let(::normaliseResourcePath)
  }

  private fun fileValue(id: Int): String? {
    val value = TypedValue()
    return runCatching {
        base.getValue(id, value, true)
        value.string?.toString()
      }
      .getOrNull()
  }

  private fun dimensionValue(id: Int): String? {
    val value = TypedValue()
    return runCatching {
        base.getValue(id, value, true)
        value.coerceToString()?.toString()
      }
      .getOrNull()
  }

  private fun colorStateListValue(value: ColorStateList): String =
    "#%08X".format(value.defaultColor)

  private fun normaliseResourcePath(path: String): String? {
    val trimmed = path.trim().takeIf { it.isNotBlank() } ?: return null
    val resIndex = trimmed.indexOf("res/")
    val candidate = if (resIndex >= 0) trimmed.substring(resIndex) else trimmed
    if (!candidate.startsWith("res/") && !File(candidate).isAbsolute) return null
    if (File(candidate).isAbsolute) return candidate
    return ResourceSourceIndex.findRelative(candidate)
      ?: File(System.getProperty("user.dir"), candidate).absolutePath
  }

  private companion object {
    val SUPPORTED_TYPES = setOf("string", "drawable", "color", "dimen", "layout", "plurals", "array")
  }
}

private object ResourceSourceIndex {
  private val valuesEntry =
    Regex("""<\s*(string|color|dimen|plurals|string-array|integer-array|array)\b[^>]*\bname\s*=\s*["']([^"']+)["']""")

  private val indexed: Map<String, String> by lazy { buildIndex() }
  private val relativeFiles: Map<String, String> by lazy { buildRelativeIndex() }

  fun find(type: String, name: String): String? = indexed["$type/$name"]

  fun findRelative(relativePath: String): String? = relativeFiles[relativePath]

  private fun buildIndex(): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (resDir in resDirs()) {
      Files.walk(resDir.toPath()).use { paths ->
        paths
          .filter { Files.isRegularFile(it) }
          .forEach { path ->
            val parent = path.parent?.name ?: return@forEach
            val baseType = parent.substringBefore('-')
            val file = path.toFile()
            if (baseType == "values" && file.extension == "xml") {
              indexValuesFile(file, out)
            } else if (baseType in FILE_RESOURCE_TYPES) {
              out.putIfAbsent("$baseType/${file.nameWithoutExtension}", file.absolutePath)
            }
          }
      }
    }
    return out
  }

  private fun buildRelativeIndex(): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (resDir in resDirs()) {
      Files.walk(resDir.toPath()).use { paths ->
        paths
          .filter { Files.isRegularFile(it) }
          .forEach { path ->
            val relative = resDir.toPath().relativize(path).joinToString("/")
            out.putIfAbsent("res/$relative", path.toFile().absolutePath)
          }
      }
    }
    return out
  }

  private fun indexValuesFile(file: File, out: MutableMap<String, String>) {
    val text = runCatching { file.readText() }.getOrNull() ?: return
    valuesEntry.findAll(text).forEach { match ->
      val type =
        when (match.groupValues[1]) {
          "string-array", "integer-array" -> "array"
          else -> match.groupValues[1]
        }
      val name = match.groupValues[2]
      out.putIfAbsent("$type/$name", file.absolutePath)
    }
  }

  private fun resDirs(): List<File> {
    val roots =
      listOfNotNull(
        System.getProperty("composeai.daemon.moduleProjectDir"),
        System.getProperty("composeai.daemon.workspaceRoot"),
        System.getProperty("user.dir"),
      ).distinct()
    return roots.flatMap { root ->
      val rootFile = File(root)
      val src = rootFile.resolve("src")
      if (src.isDirectory) {
        src.listFiles()
          ?.map { it.resolve("res") }
          ?.filter { it.isDirectory }
          .orEmpty()
      } else {
        emptyList()
      }
    }
  }

  private fun java.nio.file.Path.joinToString(separator: String): String =
    iterator().asSequence().joinToString(separator) { it.name }

  private val FILE_RESOURCE_TYPES = setOf("drawable", "layout")
}

/** Registry side for `resources/used`; reads the latest JSON artefact from disk. */
class ResourcesUsedDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = ResourcesUsedDataProducer.KIND,
        schemaVersion = ResourcesUsedDataProducer.SCHEMA_VERSION,
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
    if (kind != ResourcesUsedDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = ResourcesUsedDataProducer.SCHEMA_VERSION,
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
        schemaVersion = ResourcesUsedDataProducer.SCHEMA_VERSION,
        payload = payload,
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (ResourcesUsedDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = ResourcesUsedDataProducer.KIND,
        schemaVersion = ResourcesUsedDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(ResourcesUsedDataProducer.FILE)
}
