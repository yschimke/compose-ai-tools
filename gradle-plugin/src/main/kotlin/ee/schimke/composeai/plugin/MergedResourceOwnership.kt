package ee.schimke.composeai.plugin

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Reads AGP's resource-merge blame file (`merger.xml`) to tell [AndroidResourcePruner] which merged
 * file resources came from third-party AARs rather than this build.
 *
 * ## Why
 *
 * A retain-set of only the rendering module's own `src/<sourceSet>/res` (now just a floor in
 * `BundlePreviewTask.prunableFileResourceKeys`) pruned icons from sibling project modules, leaving
 * dangling `resources.arsc` entries that crashed `painterResource` in the live daemon (#3260).
 * Enumerating sibling projects isn't Isolated-Projects-safe, but AGP already records every
 * contributing data set in `merger.xml` under the rendering module's own build dir.
 *
 * ## The discriminator
 *
 * Each `<dataSet config="…">` is one of:
 * - a **source-set name** (`main`, `debug`, `test`, plus `$Generated` twins): first-party;
 * - a **Gradle project path** (`:modules:services:ui`): first-party;
 * - a **Maven coordinate** (`androidx.cardview:cardview:1.0.0`): third-party.
 *
 * Only a coordinate has a `:` after its first character. Anything unclassifiable counts as
 * first-party, and since the pruner drops from a positive AAR set, it is retained by omission.
 */
internal object MergedResourceOwnership {

  /** Merged file resources by origin, as `"<typeBase>/<name>"` keys (e.g. `"drawable/ic_play"`). */
  data class Ownership(val firstParty: Set<String>, val thirdParty: Set<String>) {
    /**
     * Safe to drop: AAR-attributed and contributed by no project. A resource from both resolves to
     * the first-party file at runtime, so it stays.
     */
    val prunable: Set<String>
      get() = thirdParty - firstParty
  }

  /**
   * Ownership across every `merger.xml` under [moduleBuildDir], or null when none is usable (never
   * merged, malformed, or an unrecognised future layout), so callers can tell missing metadata from
   * "nothing attributed to a dependency". "Usable" means parsed and schema-recognised (see
   * [collectInto]).
   */
  fun fileResourceOwnership(moduleBuildDir: File): Ownership? {
    val firstParty = mutableSetOf<String>()
    val thirdParty = mutableSetOf<String>()
    var usableBlameFound = false
    for (blame in blameFiles(moduleBuildDir)) {
      // Discard keys from a malformed file: a half-read file could miss the project data set that
      // also contributes a resource, marking it prunable.
      val fileFirstParty = mutableSetOf<String>()
      val fileThirdParty = mutableSetOf<String>()
      runCatching { collectInto(fileFirstParty, fileThirdParty, blame) }
        .onSuccess { recognised ->
          if (recognised) {
            usableBlameFound = true
            firstParty += fileFirstParty
            thirdParty += fileThirdParty
          }
        }
    }
    return if (usableBlameFound) Ownership(firstParty, thirdParty) else null
  }

  /**
   * AGP's blame files
   * (`build/intermediates/incremental/<variant>/merge<Variant>Resources/merger.xml`, plus the
   * unit-test variant). Paths vary by AGP version, so walk for the filename.
   */
  private fun blameFiles(moduleBuildDir: File): List<File> {
    val incremental = File(moduleBuildDir, "intermediates/incremental")
    if (!incremental.isDirectory) return emptyList()
    return incremental
      .walkTopDown()
      .maxDepth(BLAME_WALK_DEPTH)
      .filter { it.isFile && it.name == BLAME_FILE_NAME }
      .toList()
  }

  /**
   * Streams [blame] (StAX; these files can be megabytes), routing each `<file path="…"/>` into
   * [firstParty] or [thirdParty] by its `<dataSet>`.
   *
   * Returns whether the schema was recognised (at least one `<dataSet>`, which AGP always writes);
   * a restructured file that parses cleanly but yields nothing must not read as complete. Data sets
   * with no file resources are still valid.
   */
  private fun collectInto(
    firstParty: MutableSet<String>,
    thirdParty: MutableSet<String>,
    blame: File,
  ): Boolean {
    val factory =
      XMLInputFactory.newInstance().apply {
        // Never needs external entities or DTDs; refuse them.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
      }
    return blame.inputStream().buffered().use { input ->
      val reader = factory.createXMLStreamReader(input)
      // Data sets don't nest, so one current bucket suffices.
      var bucket = firstParty
      var sawDataSet = false
      try {
        while (reader.hasNext()) {
          if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
          when (reader.localName) {
            "dataSet" -> {
              sawDataSet = true
              bucket =
                if (isFirstParty(reader.getAttributeValue(null, "config"))) firstParty
                else thirdParty
            }
            "file" -> {
              // Prefer a single-file resource's `type` / `name` attributes, else read the path's
              // type directory. `values` files have neither and are skipped (they compile into
              // `resources.arsc`).
              val declared =
                declaredResourceKey(
                  reader.getAttributeValue(null, "type"),
                  reader.getAttributeValue(null, "name"),
                )
              val key =
                declared ?: reader.getAttributeValue(null, "path")?.let { resourceKeyOf(it) }
              key?.let(bucket::add)
            }
          }
        }
      } finally {
        reader.close()
      }
      sawDataSet
    }
  }

  /**
   * Whether a data set config is authored in this build: anything but a Maven coordinate (a `:`
   * with a group before it). Blank is first-party; `$Generated` suffixes change nothing.
   */
  private fun isFirstParty(config: String?): Boolean {
    val value = config?.trim().orEmpty()
    return value.isEmpty() || value.startsWith(":") || !value.contains(':')
  }

  /** `"<typeBase>/<name>"` from a `<file>`'s `type` / `name`, or null (a `values` file). */
  private fun declaredResourceKey(type: String?, name: String?): String? {
    val typeBase = type?.trim()?.substringBefore('-').orEmpty()
    val resourceName = name?.trim().orEmpty()
    if (typeBase.isEmpty() || resourceName.isEmpty() || typeBase == "values") return null
    return "$typeBase/$resourceName"
  }

  /**
   * `"<typeBase>/<name>"` for a merged resource path, or null for `values` files or paths without a
   * type directory.
   */
  private fun resourceKeyOf(path: String): String? {
    val normalized = path.replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    val typeDir = normalized.substringBeforeLast('/', "").substringAfterLast('/')
    if (fileName.isEmpty() || typeDir.isEmpty()) return null
    val typeBase = typeDir.substringBefore('-')
    if (typeBase.isEmpty() || typeBase == "values") return null
    return "$typeBase/${AndroidResourcePruner.resourceNameOf(fileName)}"
  }

  private const val BLAME_FILE_NAME = "merger.xml"

  /**
   * The blame file is three levels below the walk root; two more absorb layout changes without
   * walking the whole build dir.
   */
  private const val BLAME_WALK_DEPTH = 5
}
