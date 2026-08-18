package ee.schimke.composeai.rcplayer.compose

/**
 * Builds an [RcTypefaceLoader] from a `fonts.json` manifest and the faces it names.
 *
 * **Why this is shared code rather than a host's fetch helper.** Everything it does is a *protocol*
 * fact — what a document means when it names no family, what a `google:`-prefixed name is, that
 * family keys are matched lowercased. Those lived in sixty lines inside the Wasm host, so an iOS
 * host rendering the same catalog got Compose's built-in face for all body text, and the
 * `google:Roboto Flex` name was simply unresolvable. That class of bug had already been hit and
 * fixed once, in the one host that had the rules (#4061).
 *
 * **The async/sync split is preserved.** [load] suspends — it fetches and decodes — and returns a
 * loader whose [RcTypefaceLoader.typeface] does not, because the player calls that during
 * composition and draw. Async work belongs in construction; see [RcTypefaceLoader].
 *
 * **The cache belongs to the instance, not to the process.** A host constructs one of these and
 * keeps it for the life of its page or screen, so a document swap reuses what the first load
 * fetched instead of re-downloading a whole catalog's fonts. Making it a global would mean font
 * bytes that no host can release.
 *
 * @param fetchBytes reads a URL. This is the only thing a host still has to supply; the manifest is
 *   fetched through it too and decoded as UTF-8.
 */
public class RcManifestTypefaceLoader(private val fetchBytes: suspend (url: String) -> ByteArray) {

  private var cached: Pair<String, RcTypefaceLoader>? = null

  /**
   * The loader for the manifest at `<base>fonts.json`, fetching every face it names.
   *
   * [base] is a directory: a trailing `/` is added if missing. It is kept in the cache key so a
   * cached result can never answer for a base it was not filled from — even though a host normally
   * has one for the life of a page.
   *
   * A manifest that cannot be fetched or parsed yields [RcTypefaceLoader.Empty] rather than
   * throwing, matching what the Wasm host did: a missing manifest is "this host supplies no fonts",
   * and it is `composeSupportReport`'s job to refuse a document that needs one. A *face* that fails
   * to fetch drops only its family, so one bad file does not cost a catalog its whole text.
   */
  public suspend fun load(base: String): RcTypefaceLoader {
    val directory = if (base.endsWith('/')) base else "$base/"
    cached?.let { (cachedBase, loader) -> if (cachedBase == directory) return loader }
    val entries = runCatching {
      parseFontManifest(fetchBytes(directory + MANIFEST_NAME).decodeToString())
    }
      .getOrElse { emptyList() }
    if (entries.isEmpty()) return RcTypefaceLoader.Empty
    val faces = buildFaces(directory, entries)
    val loader = RcBundledTypefaceLoader(faces)
    cached = directory to loader
    return loader
  }

  private suspend fun buildFaces(
    directory: String,
    entries: List<RcManifestFace>,
  ): Map<String, RcFontFaces> = buildMap {
    suspend fun facesFor(group: List<RcManifestFace>): RcFontFaces? = runCatching {
      RcFontFaces(
        group.map { entry ->
          RcFontFace(
            identity = entry.file,
            data = fetchBytes(directory + entry.file),
            weight = entry.weight,
            italic = entry.italic,
          )
        }
      )
    }
      .getOrNull()

    val byFamily = entries.groupBy { it.family }
    byFamily.forEach { (family, group) -> facesFor(group)?.let { put(family.lowercase(), it) } }

    // `role` names what a family is *for*. The default-role family answers to two keys: its own
    // name, registered above so `google:Roboto Flex` resolves, and the literal `"default"` a
    // document asks for when it names no family at all — which is what every CoreText in the
    // remote-m3 catalog does. Keying by name alone left `"default"` unresolvable and dropped all of
    // that text to Compose's built-in face; that is the bug this file exists to stop repeating per
    // host. See [rcResolveTypeface], which is the other half of the same contract.
    if (!containsKey(RC_DEFAULT_FAMILY)) {
      byFamily.entries
        .firstOrNull { (_, group) -> group.any { it.role == "default" } }
        ?.let { (family, group) ->
          (get(family.lowercase()) ?: facesFor(group))?.let { put(RC_DEFAULT_FAMILY, it) }
        }
    }
  }

  private companion object {
    const val MANIFEST_NAME = "fonts.json"
  }
}

/** One `fonts.json` row: a face file, and the family and role it was registered under. */
internal data class RcManifestFace(
  val role: String,
  val family: String,
  val file: String,
  val weight: Int,
  val italic: Boolean,
)

/**
 * Reads the `{"families":[{"name","role","fonts":[{"file","weight","style"}]}]}` shape.
 *
 * File names are rejected rather than sanitised when they escape the manifest's own directory — a
 * `..` segment, or anything carrying a scheme. The manifest is fetched from a host-controlled base,
 * but a catalog's manifest is often generated, and a generated path that walks out of its directory
 * is a bug wherever it came from. Rejecting one face loses one family; following it fetches from
 * somewhere the host did not name.
 */
internal fun parseFontManifest(json: String): List<RcManifestFace> =
  rcParseJson(json).array("families").flatMap { family ->
    val role = family.string("role") ?: "default"
    val name = family.string("name").orEmpty()
    family.array("fonts").mapNotNull { font ->
      val file = font.string("file")?.takeIf { it.isNotEmpty() && it.isSafeRelativePath() }
      file?.let {
        RcManifestFace(
          role = role,
          family = name,
          file = it,
          weight = (font.int("weight") ?: 400).coerceIn(1, 1000),
          italic = font.string("style") == "italic",
        )
      }
    }
  }

private fun String.isSafeRelativePath(): Boolean = ".." !in split('/') && !contains(':')
