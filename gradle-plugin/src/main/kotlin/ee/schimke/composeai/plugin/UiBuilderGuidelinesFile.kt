package ee.schimke.composeai.plugin

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A catalog's own design guidance, `ui-builder.guidelines.json`, authored beside its
 * `ui-builder.policy.json` and published beside the `ui-builder.json` that policy produces.
 *
 * The format is compose-ui-builder's (`compose-ui-builder/catalog-guidelines/v1`: the catalog's
 * rules and the pictures its guidelines check is shown); this repository only finds the file,
 * checks the fields a reader cannot do without, and carries it verbatim. compose-preview-server
 * fetches it from the catalog's delivery branch next to `ui-builder.json`.
 *
 * It is found in the SAME directory as the policy that was chosen, never resolved on its own: a
 * module that owns its policy and a repository root with guidelines for another catalog must not
 * produce a hybrid, for the reason [DiscoverPreviewsTask]'s authored pair is resolved together.
 */
internal object UiBuilderGuidelinesFile {
  const val FILE_NAME: String = "ui-builder.guidelines.json"
  const val SCHEMA: String = "compose-ui-builder/catalog-guidelines/v1"

  /** The guidelines file beside [policy], or null when the catalog publishes none. */
  fun besidePolicy(policy: File): File? = File(policy.parentFile, FILE_NAME).takeIf { it.isFile }

  /**
   * What is wrong with [text] as the guidelines of catalog [catalogId], or nothing. Only what a
   * reader cannot work around: the schema, the catalog it is for, an integer version, and the six
   * fields every rule must carry.
   */
  fun problems(text: String, catalogId: String): List<String> {
    val root =
      runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return listOf("is not a JSON object")
    return buildList {
      if (root.string("schema") != SCHEMA) add("`schema` is not `$SCHEMA`")
      val catalog = root.string("catalog")
      if (catalog != catalogId) add("`catalog` is `${catalog ?: "missing"}`, not `$catalogId`")
      if ((root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == null)
        add("`version` is not an integer")
      val rules = root["rules"] as? JsonArray
      if (rules == null) add("`rules` is not a list")
      rules?.forEachIndexed { index, element ->
        val rule = element as? JsonObject
        val name = rule?.string("id") ?: "#$index"
        if (rule == null) {
          add("rule $name is not an object")
          return@forEachIndexed
        }
        RULE_FIELDS.filter { rule.string(it).isNullOrBlank() }
          .forEach { add("rule $name has no `$it`") }
        rule.string("check")?.let { if ('?' !in it) add("rule $name's `check` is not a question") }
        rule.string("source")?.let {
          if (!it.startsWith("https://")) add("rule $name's `source` is not an https URL")
        }
      }
      when (val includes = root["includes"]) {
        null -> Unit
        !is JsonArray -> add("`includes` is not a list")
        else ->
          includes.forEachIndexed { index, element ->
            includeProblem(element as? JsonObject, index)?.let(::add)
          }
      }
    }
  }

  private val SHA256 = Regex("[0-9a-f]{64}")

  private fun includeProblem(include: JsonObject?, index: Int): String? {
    val url = include?.string("url")
    return when {
      include == null -> "include #$index is not an object"
      url == null || !url.startsWith("https://") -> "include #$index's `url` is not an https URL"
      include.string("sha256")?.let(SHA256::matches) != true ->
        "include $url has no `sha256` (64 lowercase hex digits)"
      else -> null
    }
  }

  /** The largest rule pack read; the same bound as the `:design-guidelines` engine's. */
  const val MAX_PACK_BYTES: Int = 1024 * 1024

  /** The outcome of [flatten]: the text to publish, and why it could not be flattened, if not. */
  data class Flattened(val text: String, val problem: String? = null)

  /**
   * [text] with the rule packs its `includes` name merged in and the `includes` removed, so the
   * published file is flat and every reader — compose-preview-server, the browser editor — sees the
   * whole rule set without fetching anything. The merge is the `:design-guidelines` engine's
   * (`GuidelinesIncludes`): a pack rule naming `platforms` is carried only into a catalog whose
   * `platform` it lists, an `exclude`d id or one the catalog defines itself is left out, an
   * include's `profiles` narrows the carried rules naming none, and a pack's frames are added where
   * the catalog does not already ask for the same one. A file with no includes comes back
   * byte-for-byte.
   *
   * When a pack cannot be read or does not match its pin, [text] comes back as written, includes
   * and all, with the reason: a reader that resolves includes (the CLI) still can, and the publish
   * workflow fails loudly on the same pin before it renders.
   */
  fun flatten(text: String, fetch: (String) -> ByteArray = ::fetchPack): Flattened {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val includes = root?.get("includes") as? JsonArray ?: return Flattened(text)
    if (includes.isEmpty()) return Flattened(text)
    val platform = root.string("platform")
    val rules = (root["rules"] as? JsonArray).orEmpty().toMutableList()
    val ids = rules.mapNotNullTo(mutableSetOf()) { (it as? JsonObject)?.string("id") }
    val frames = (root["frames"] as? JsonArray).orEmpty().toMutableList()
    for ((index, element) in includes.withIndex()) {
      val include = element as? JsonObject
      includeProblem(include, index)?.let {
        return Flattened(text, it)
      }
      val url = include!!.string("url")!!
      val bytes =
        try {
          fetch(url)
        } catch (e: Exception) {
          return Flattened(text, "$url could not be read: ${e.message ?: e.javaClass.simpleName}")
        }
      val actual = sha256(bytes)
      if (actual != include.string("sha256")) {
        return Flattened(text, "$url does not match its pin (sha256 $actual)")
      }
      val pack =
        runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
          .getOrNull() ?: return Flattened(text, "$url is not a JSON object")
      if (pack.string("schema") != SCHEMA) return Flattened(text, "$url is not a guidelines pack")
      if ((pack["includes"] as? JsonArray)?.isNotEmpty() == true) {
        return Flattened(text, "$url includes others; packs may not nest")
      }
      val exclude = include.strings("exclude")
      val profiles = include.strings("profiles")
      for (rule in (pack["rules"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
        val id = rule.string("id") ?: continue
        if (id in ids || id in exclude) continue
        val platforms = rule.strings("platforms")
        if (platforms.isNotEmpty() && platform !in platforms) continue
        ids += id
        rules +=
          if (profiles.isEmpty() || rule.strings("profiles").isNotEmpty()) rule
          else JsonObject(rule + ("profiles" to JsonArray(profiles.map(::JsonPrimitive))))
      }
      (pack["frames"] as? JsonArray).orEmpty().filterNot { it in frames }.forEach { frames += it }
    }
    val flat =
      buildMap<String, JsonElement> {
        root.forEach { (key, value) ->
          when (key) {
            "includes" -> Unit
            "rules" -> put(key, JsonArray(rules))
            "frames" -> put(key, JsonArray(frames))
            else -> put(key, value)
          }
        }
        if ("rules" !in root) put("rules", JsonArray(rules))
        if ("frames" !in root && frames.isNotEmpty()) put("frames", JsonArray(frames))
      }
    return Flattened(PRETTY.encodeToString(JsonObject.serializer(), JsonObject(flat)) + "\n")
  }

  private val PRETTY = Json { prettyPrint = true }

  /** Packs already read in this daemon, by URL and pin, so discovery and bundling fetch once. */
  private val packs = ConcurrentHashMap<String, ByteArray>()

  private val http: OkHttpClient by lazy {
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
  }

  private fun fetchPack(url: String): ByteArray =
    packs[url]
      ?: run {
        require(url.startsWith("https://")) { "not an https URL" }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) throw IOException("answered ${response.code}")
          val source = response.body.source()
          if (source.request(MAX_PACK_BYTES + 1L)) {
            throw IOException("larger than $MAX_PACK_BYTES bytes")
          }
          source.buffer.readByteArray()
        }
      }
        .also { packs[url] = it }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull {
      (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }

  private val RULE_FIELDS = listOf("id", "kind", "severity", "guidance", "check", "source")

  private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
