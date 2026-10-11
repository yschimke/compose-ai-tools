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
 * A catalog's `ui-builder.guidelines.json` (compose-ui-builder's `catalog-guidelines/v1` format),
 * authored beside its policy and published beside `ui-builder.json`. This repo only finds it,
 * checks the essential fields, and carries it. Found in the same directory as the chosen policy,
 * never independently, for the same reason as [DiscoverPreviewsTask]'s authored pair.
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

  /** At most this many includes per file; the same bound as the `:design-guidelines` engine's. */
  const val MAX_INCLUDES: Int = 8

  /** The outcome of [flatten]: the text to publish, and why it could not be flattened, if not. */
  data class Flattened(val text: String, val problem: String? = null)

  /**
   * [text] with its `includes` packs merged in (via the `:design-guidelines` engine's
   * `GuidelinesIncludes`), so readers see every rule without fetching. Packs layer in include
   * order, the catalog's own rules last, later rules replacing same-id earlier ones in place;
   * `platforms`, `exclude` and `profiles` filter what's carried; frames are added where not already
   * requested. At most [MAX_INCLUDES]; pack rules pass [problems]. No includes → unchanged bytes.
   *
   * If a pack can't be read or doesn't match its pin, [text] is returned as written with the
   * reason; the publish workflow fails on the same pin.
   */
  fun flatten(text: String, fetch: (String) -> ByteArray = ::fetchPack): Flattened {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val includes = root?.get("includes") as? JsonArray ?: return Flattened(text)
    if (includes.isEmpty()) return Flattened(text)
    if (includes.size > MAX_INCLUDES) return Flattened(text, "more than $MAX_INCLUDES includes")
    val platform = root.string("platform")
    val own = (root["rules"] as? JsonArray).orEmpty()
    // Layered: each pack in include order, then the catalog's own rules; a later layer's rule
    // replaces an earlier one of the same id where it stood.
    val layered = LinkedHashMap<String, JsonElement>()
    val frames = (root["frames"] as? JsonArray).orEmpty().toMutableList()
    for ((index, element) in includes.withIndex()) {
      val include = element as? JsonObject
      includeProblem(include, index)?.let {
        return Flattened(text, it)
      }
      val url = include!!.string("url")!!
      val pin = include.string("sha256")!!
      val bytes =
        packs["$url $pin"]
          ?: try {
            fetch(url)
          } catch (e: Exception) {
            return Flattened(text, "$url could not be read: ${e.message ?: e.javaClass.simpleName}")
          }
      val actual = sha256(bytes)
      if (actual != pin) {
        return Flattened(text, "$url does not match its pin (sha256 $actual)")
      }
      packs["$url $pin"] = bytes
      val pack =
        runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
          .getOrNull() ?: return Flattened(text, "$url is not a JSON object")
      if (pack.string("schema") != SCHEMA) return Flattened(text, "$url is not a guidelines pack")
      if ((pack["includes"] as? JsonArray)?.isNotEmpty() == true) {
        return Flattened(text, "$url includes others; packs may not nest")
      }
      // The loader refuses a merged file with any malformed rule, so check pack rules first.
      problems(bytes.toString(Charsets.UTF_8), pack.string("catalog").orEmpty())
        .firstOrNull()
        ?.let {
          return Flattened(text, "$url: $it")
        }
      val exclude = include.strings("exclude")
      val profiles = include.strings("profiles")
      for (rule in (pack["rules"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
        val id = rule.string("id") ?: continue
        if (id in exclude) continue
        val platforms = rule.strings("platforms")
        if (platforms.isNotEmpty() && platform !in platforms) continue
        layered[id] =
          if (profiles.isEmpty() || rule.strings("profiles").isNotEmpty()) rule
          else JsonObject(rule + ("profiles" to JsonArray(profiles.map(::JsonPrimitive))))
      }
      (pack["frames"] as? JsonArray).orEmpty().filterNot { it in frames }.forEach { frames += it }
    }
    own.forEachIndexed { index, rule ->
      layered[(rule as? JsonObject)?.string("id") ?: "#own-$index"] = rule
    }
    val rules = layered.values.toList()
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

  /** Verified packs cached by URL + pin, so a changed pin never reuses old bytes. */
  private val packs = ConcurrentHashMap<String, ByteArray>()

  private val http: OkHttpClient by lazy {
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
  }

  private fun fetchPack(url: String): ByteArray {
    require(url.startsWith("https://")) { "not an https URL" }
    return http.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (!response.isSuccessful) throw IOException("answered ${response.code}")
      val source = response.body.source()
      if (source.request(MAX_PACK_BYTES + 1L)) {
        throw IOException("larger than $MAX_PACK_BYTES bytes")
      }
      source.buffer.readByteArray()
    }
  }

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
