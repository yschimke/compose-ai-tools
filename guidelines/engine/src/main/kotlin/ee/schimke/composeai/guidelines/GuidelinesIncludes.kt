package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared rule packs a catalog's `ui-builder.guidelines.json` pulls in via `includes`: `https` URLs
 * to `catalog-guidelines/v1` files pinned by sha256. Merged like the Gradle plugin's
 * `UiBuilderGuidelinesFile.flatten`:
 * - a pack rule naming `platforms` is carried only into a catalog whose `platform` it lists;
 * - packs are layers in include order, the catalog's own rules last; later rules replace same-id
 *   ones, so a form-factor pack can override a general one and the catalog can override either;
 * - `exclude`d ids are left out of that include;
 * - an include's `profiles` narrows carried rules that name none;
 * - pack frames are added unless the catalog already asks for the same one;
 * - no nesting, and each pack is at most [MAX_PACK_BYTES].
 *
 * Reads the raw text rather than `CatalogGuidelinesV1.includes` for older contracts releases; once
 * that field exists, the merged result must clear it so a resolved file never looks unresolved.
 */
public object GuidelinesIncludes {
  /** The largest pack read, so a pin cannot make a reader buffer an arbitrary download. */
  public const val MAX_PACK_BYTES: Int = 1024 * 1024

  /** At most this many includes per file. */
  public const val MAX_INCLUDES: Int = 8

  @Serializable
  internal data class Include(
    val url: String,
    val sha256: String,
    val profiles: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
  )

  @Serializable private data class Declaring(val includes: List<Include> = emptyList())

  /** The includes [text] declares, in order; empty for a flat file. */
  internal fun declared(text: String): List<Include> =
    GUIDELINES_JSON.decodeFromString(Declaring.serializer(), text).includes

  private val SHA256 = Regex("[0-9a-f]{64}")

  /** What is wrong with [include] before anything is fetched, or null. */
  internal fun shapeProblem(include: Include): String? =
    when {
      !include.url.startsWith("https://") -> "include `${include.url}` is not an https URL"
      !SHA256.matches(include.sha256) ->
        "include `${include.url}` has no sha256 (64 lowercase hex digits)"
      else -> null
    }

  /**
   * [own] with every pack in [includes] merged in, or no guidelines and the reason the first that
   * failed did.
   */
  internal fun resolve(
    own: CatalogGuidelinesV1,
    includes: List<Include>,
    fetch: (String) -> ByteArray,
  ): CatalogGuidelinesLoader.Loaded {
    if (includes.size > MAX_INCLUDES) {
      return CatalogGuidelinesLoader.Loaded(null, "more than $MAX_INCLUDES includes")
    }
    val packs = mutableListOf<Pair<Include, CatalogGuidelinesV1>>()
    for (include in includes) {
      shapeProblem(include)?.let {
        return CatalogGuidelinesLoader.Loaded(null, it)
      }
      val bytes =
        try {
          fetch(include.url)
        } catch (e: Exception) {
          return CatalogGuidelinesLoader.Loaded(
            null,
            "include `${include.url}` could not be read: ${e.message ?: e.javaClass.simpleName}",
          )
        }
      if (bytes.size > MAX_PACK_BYTES) {
        return CatalogGuidelinesLoader.Loaded(
          null,
          "include `${include.url}` is larger than $MAX_PACK_BYTES bytes",
        )
      }
      val actual = sha256(bytes)
      if (actual != include.sha256) {
        return CatalogGuidelinesLoader.Loaded(
          null,
          "include `${include.url}` does not match its pin (sha256 $actual)",
        )
      }
      val text = bytes.toString(Charsets.UTF_8)
      val pack =
        CatalogGuidelinesLoader.parseOwn(text).guidelines
          ?: return CatalogGuidelinesLoader.Loaded(
            null,
            "include `${include.url}` is not a guidelines pack: " +
              CatalogGuidelinesLoader.parseOwn(text).problem,
          )
      val nested = runCatching { declared(text) }.getOrDefault(emptyList())
      if (nested.isNotEmpty()) {
        return CatalogGuidelinesLoader.Loaded(
          null,
          "include `${include.url}` includes others; packs may not nest",
        )
      }
      packs += include to pack
    }
    return CatalogGuidelinesLoader.Loaded(merge(own, packs))
  }

  /** [own] with [packs] merged in by the rules above. */
  internal fun merge(
    own: CatalogGuidelinesV1,
    packs: List<Pair<Include, CatalogGuidelinesV1>>,
  ): CatalogGuidelinesV1 {
    // Layered: each pack in include order, then the catalog's own rules. A later layer's rule
    // replaces an earlier one of the same id where it stood; a new id is appended.
    val rules = LinkedHashMap<String, GuidelineRuleV1>()
    val frames = own.frames.toMutableList()
    for ((include, pack) in packs) {
      for (rule in pack.rules) {
        if (rule.id in include.exclude) continue
        if (rule.platforms.isNotEmpty() && own.platform !in rule.platforms) continue
        rules[rule.id] =
          if (include.profiles.isEmpty() || rule.profiles.isNotEmpty()) rule
          else rule.newBuilder().also { it.profiles = include.profiles }.build()
      }
      pack.frames.filterNot { it in frames }.forEach { frames += it }
    }
    own.rules.forEach { rules[it.id] = it }
    return own
      .newBuilder()
      .also {
        it.rules = rules.values.toList()
        it.frames = frames.toList()
      }
      .build()
  }

  /** A fetcher reading `https` URLs through [http], refusing anything larger than a pack may be. */
  public fun httpFetcher(http: OkHttpClient): (String) -> ByteArray = { url ->
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

  internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
