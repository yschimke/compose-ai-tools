package ee.schimke.composeai.plugin

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Reads AGP's resource-merge blame file (`merger.xml`) to answer one question for
 * [AndroidResourcePruner]: which merged file resources are **first-party** — authored somewhere in
 * this Gradle build — as opposed to merged in from a third-party AAR.
 *
 * ## Why this exists
 *
 * The pruner's job is to drop the merged-**dependency** file resources a Compose render never
 * inflates, and it needs a retain-set to protect everything else. That retain-set used to be "the
 * rendering module's own `src/<sourceSet>/res`" (now only the fallback half of
 * `BundlePreviewTask.firstPartyFileResourceKeys`), which is right only for a single-module catalog.
 * A real app's catalog module renders composables whose icons live in **sibling project modules** —
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
 * treated as first-party: this is a retain-set, and the failure mode of retaining too much is a
 * slightly larger bundle, while retaining too little is a dead render.
 */
internal object MergedResourceOwnership {

  /**
   * Resource identities (`"<typeBase>/<name>"`, e.g. `"drawable/ic_play"`) contributed by
   * first-party data sets across every `merger.xml` under [moduleBuildDir], in the shape
   * [AndroidResourcePruner.prune] expects. Returns null when no usable blame file is present (a
   * build that never merged resources, a malformed file, or a future AGP that stops writing them),
   * so the caller can distinguish unavailable ownership metadata from a valid result containing no
   * first-party file resources.
   */
  fun firstPartyFileResourceKeys(moduleBuildDir: File): Set<String>? {
    val keys = mutableSetOf<String>()
    var usableBlameFound = false
    for (blame in blameFiles(moduleBuildDir)) {
      // Do not retain keys partially parsed from a malformed file. A second valid blame file can
      // still make the overall result usable.
      val fileKeys = mutableSetOf<String>()
      runCatching { collectInto(fileKeys, blame) }
        .onSuccess {
          usableBlameFound = true
          keys += fileKeys
        }
    }
    return keys.takeIf { usableBlameFound }
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
   * Stream [blame], adding a key for every `<file path="…"/>` that sits inside a first-party
   * `<dataSet>`. Streaming (StAX) rather than DOM on purpose: a merger.xml for an app-sized module
   * is megabytes of inlined `values` XML, and we only care about the element names and one
   * attribute.
   */
  private fun collectInto(keys: MutableSet<String>, blame: File) {
    val factory =
      XMLInputFactory.newInstance().apply {
        // Blame files are build output, not untrusted input, but a resource-merge XML never needs
        // external entities or DTDs — refuse them rather than letting a malformed one reach out.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
      }
    blame.inputStream().buffered().use { input ->
      val reader = factory.createXMLStreamReader(input)
      // Data sets don't nest, so a single "are we inside a first-party one" flag is enough; it is
      // set on every <dataSet> open and consulted by the <file> entries that follow.
      var firstParty = false
      try {
        while (reader.hasNext()) {
          if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
          when (reader.localName) {
            "dataSet" -> firstParty = isFirstParty(reader.getAttributeValue(null, "config"))
            "file" ->
              if (firstParty) {
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
                key?.let(keys::add)
              }
          }
        }
      } finally {
        reader.close()
      }
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
