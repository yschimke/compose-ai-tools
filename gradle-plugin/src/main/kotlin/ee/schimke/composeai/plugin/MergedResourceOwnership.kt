package ee.schimke.composeai.plugin

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Reads AGP's resource-merge blame file (`merger.xml`) to answer one question for
 * [AndroidResourcePruner]: which merged file resources came from a third-party AAR, as opposed to
 * being authored somewhere in this Gradle build.
 *
 * ## Why this exists
 *
 * The pruner drops the merged-**dependency** file resources a Compose render never inflates, and it
 * needs provenance to know which those are. It used to work the other way round, from a retain-set
 * of "the rendering module's own `src/<sourceSet>/res`" (now only a safety floor in
 * `BundlePreviewTask.prunableFileResourceKeys`), which is right only for a single-module catalog. A
 * real app's catalog module renders composables whose icons live in **sibling project modules** —
 * Pocket Casts renders from `:modules:services:compose` while `ic_play` / `ic_filters_play` live in
 * `:modules:services:ui` — so every one of those was pruned, `resources.arsc` kept its now-dangling
 * entry, and the live daemon's first `painterResource(...)` threw `NotFoundException: File
 * res/drawable/ic_play.xml from xml type xml resource ID #0x7f080346` instead of rendering
 * (issue #3260). The same over-prune took out `pocketcasts-wear`'s `splash_background`.
 *
 * Enumerating sibling projects from the plugin is not an option: this build keeps its plugin code
 * Isolated-Projects-clean (see `gradle.properties`), and `rootProject.allprojects` is exactly the
 * cross-project configuration IP rejects. AGP has already done the work for us, though — the
 * resource merger writes every contributing data set, with provenance, into `merger.xml` under the
 * **rendering module's own** build dir, so reading it involves no cross-project access at all.
 *
 * ## The discriminator
 *
 * Each `<dataSet>` carries a `config` attribute naming where it came from:
 * - a bare **source-set name** — `main`, `debug`, `test` (and its `$Generated` twin) — for the
 *   rendering module's own `src/<sourceSet>/res`: first-party;
 * - a **Gradle project path** — `:modules:services:ui` — for every project module it depends on:
 *   first-party;
 * - a **Maven coordinate** — `androidx.cardview:cardview:1.0.0` — for an AAR: third-party.
 *
 * Only the last of those carries a `:` with something before it, so "contains a `:` that isn't the
 * first character" identifies an AAR and nothing else. Everything we can't classify that way is
 * treated as first-party — and, because [AndroidResourcePruner] drops from a *positive* set of
 * AAR-attributed keys rather than keeping a retain-set, an unclassifiable resource is retained by
 * omission as well: it never reaches [Ownership.prunable] in the first place.
 */
internal object MergedResourceOwnership {

  /**
   * Which merged file resources came from where, each identity in the `"<typeBase>/<name>"` shape
   * (e.g. `"drawable/ic_play"`) [AndroidResourcePruner.prune] expects.
   */
  data class Ownership(val firstParty: Set<String>, val thirdParty: Set<String>) {
    /**
     * The keys safe to drop: attributed to an AAR and to **no** project in this build. A resource
     * contributed by both — an app overriding an AppCompat drawable, or two data sets sharing a
     * name — resolves to the first-party file at runtime, so it stays.
     */
    val prunable: Set<String>
      get() = thirdParty - firstParty
  }

  /**
   * File-resource ownership across every `merger.xml` under [moduleBuildDir]. Returns null when no
   * usable blame file is present (a build that never merged resources, a malformed file, or a
   * future AGP that stops writing them), so the caller can distinguish unavailable ownership
   * metadata from a valid result that attributes nothing to a dependency.
   *
   * "Usable" means the parse both completed *and* recognised the schema — see [collectInto]. Well-
   * formed XML we can't read is the case to be careful with: it yields zero keys without throwing.
   * That is now merely useless rather than destructive (an empty [Ownership.prunable] drops
   * nothing), but the distinction still belongs in the result, so a caller that wants to warn about
   * absent metadata can tell it apart from a build with no AAR file payload.
   */
  fun fileResourceOwnership(moduleBuildDir: File): Ownership? {
    val firstParty = mutableSetOf<String>()
    val thirdParty = mutableSetOf<String>()
    var usableBlameFound = false
    for (blame in blameFiles(moduleBuildDir)) {
      // Do not keep keys partially parsed from a malformed file: a half-read blame file can name an
      // AAR data set's resources while missing the project data set that also contributes them,
      // which would mark a live first-party resource prunable. A second valid blame file can still
      // make the overall result usable.
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
   * The merge-blame files AGP wrote for this module, at
   * `build/intermediates/incremental/<variant>/merge<Variant>Resources/merger.xml` (and the
   * `…UnitTest…` variant the render actually runs against). The exact path is variant- and
   * AGP-version-dependent, so walk for the filename rather than reconstructing it; there are a
   * handful per module and each is read once.
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
   * Stream [blame], routing every `<file path="…"/>` into [firstParty] or [thirdParty] by the
   * `<dataSet>` it sits in. Streaming (StAX) rather than DOM on purpose: a merger.xml for an
   * app-sized module is megabytes of inlined `values` XML, and we only care about the element names
   * and one attribute.
   *
   * Returns whether the schema was recognised, i.e. whether the file carried at least one
   * `<dataSet>`. AGP always writes one for the module's own source set, so a merger.xml with none
   * is a file we don't understand — a future AGP that restructures it while keeping the name parses
   * cleanly here and yields nothing, and the caller must not read that as a complete answer.
   * Recognised-but-empty (data sets present, no file resources in them) stays a valid result.
   */
  private fun collectInto(
    firstParty: MutableSet<String>,
    thirdParty: MutableSet<String>,
    blame: File,
  ): Boolean {
    val factory =
      XMLInputFactory.newInstance().apply {
        // Blame files are build output, not untrusted input, but a resource-merge XML never needs
        // external entities or DTDs — refuse them rather than letting a malformed one reach out.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
      }
    return blame.inputStream().buffered().use { input ->
      val reader = factory.createXMLStreamReader(input)
      // Data sets don't nest, so a single "which bucket are we filling" reference is enough; it is
      // set on every <dataSet> open and consulted by the <file> entries that follow.
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
              // A single-file resource is written with its identity spelled out —
              // `<file name="ic_play" path="…/res/drawable/ic_play.xml" type="drawable"/>` — so
              // prefer those attributes and fall back to reading the path's type directory. A
              // `values` file carries neither (its contents are the child elements), and is
              // skipped either way: those compile into `resources.arsc`, which the pruner never
              // touches.
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
   * Whether a `<dataSet config="…">` is authored in this build — everything except a Maven
   * coordinate, which is the only config shape carrying a `:` with a group in front of it. That
   * admits both a bare source-set name (`test`, the module's own `src/test/res`) and a Gradle
   * project path (`:modules:services:ui`, a sibling module). An absent/blank config is first-party
   * for the retain-set reason in the class kdoc. AGP appends `$Generated` to the config of a data
   * set's generated twin, which changes none of this.
   */
  private fun isFirstParty(config: String?): Boolean {
    val value = config?.trim().orEmpty()
    return value.isEmpty() || value.startsWith(":") || !value.contains(':')
  }

  /**
   * `"<typeBase>/<name>"` from a `<file>`'s own `type` / `name` attributes, or null when it doesn't
   * carry them (a `values` file) — the qualifier-free type is what [AndroidResourcePruner.prune]'s
   * retain-set is keyed by, so `type="drawable"` needs no normalising.
   */
  private fun declaredResourceKey(type: String?, name: String?): String? {
    val typeBase = type?.trim()?.substringBefore('-').orEmpty()
    val resourceName = name?.trim().orEmpty()
    if (typeBase.isEmpty() || resourceName.isEmpty() || typeBase == "values") return null
    return "$typeBase/$resourceName"
  }

  /**
   * `"<typeBase>/<name>"` for a merged resource file path, or null when it isn't one we can key — a
   * `values` file (those compile into `resources.arsc`, which the pruner never touches) or a path
   * with no resource-type parent directory.
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
   * `intermediates/incremental/<variant>/merge<Variant>Resources/merger.xml` is three levels below
   * the walk root; a couple of levels of headroom absorbs AGP layout changes without turning this
   * into a walk of the whole build directory.
   */
  private const val BLAME_WALK_DEPTH = 5
}
