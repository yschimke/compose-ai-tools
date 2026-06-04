package ee.schimke.composeai.plugin.tooling

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

/**
 * One library's declared `minSdkVersion`, read from its AAR `AndroidManifest.xml`. Feeds
 * [CompatRules.checkLibraryMinSdk], which fires when a transitive library on the unit-test
 * classpath demands a higher minSdk than the consumer module declares — the conflict AGP's manifest
 * merger rejects at `process<Variant>UnitTestManifest`, surfaced when compose-preview forces the
 * unit-test manifest merge (its renderer runs as a Robolectric unit test).
 *
 * [packageName] is the AAR manifest's `package` attribute — exactly the value AGP's
 * `tools:overrideLibrary` escape hatch wants, so the remediation can name it concretely.
 */
internal data class LibraryMinSdk(
  val coordinate: String,
  val packageName: String?,
  val minSdk: Int,
) : java.io.Serializable

/**
 * Parses the two fields [CompatRules] needs out of an AAR `AndroidManifest.xml`: the `package`
 * attribute and `uses-sdk`'s `android:minSdkVersion`. Namespace-unaware DOM parse with external
 * entity/DTD loading disabled (the manifests come from third-party artifacts). Any parse failure or
 * a non-integer codename minSdk (`"VanillaIceCream"`) yields a null field rather than throwing —
 * the rule simply skips libraries it can't read a numeric floor from.
 */
internal object AarManifestReader {

  data class Parsed(val packageName: String?, val minSdk: Int?)

  fun parse(manifest: File): Parsed =
    runCatching { manifest.inputStream().use { parseDocument(it) } }
      .getOrElse { Parsed(null, null) }

  /** String overload for tests; production reads from the resolved manifest file. */
  internal fun parse(xml: String): Parsed =
    runCatching { xml.byteInputStream().use { parseDocument(it) } }.getOrElse { Parsed(null, null) }

  private fun parseDocument(input: java.io.InputStream): Parsed {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setHardenedFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setHardenedFeature("http://xml.org/sax/features/external-general-entities", false)
        setHardenedFeature("http://xml.org/sax/features/external-parameter-entities", false)
      }
    val doc = factory.newDocumentBuilder().parse(input)
    val root = doc.documentElement
    val packageName = root?.getAttribute("package")?.takeIf { it.isNotBlank() }
    val usesSdk = doc.getElementsByTagName("uses-sdk")
    val minSdk =
      (0 until usesSdk.length)
        .asSequence()
        .map { usesSdk.item(it) as? org.w3c.dom.Element }
        .filterNotNull()
        .mapNotNull { it.getAttribute("android:minSdkVersion").takeIf { v -> v.isNotBlank() } }
        .firstOrNull()
        ?.toIntOrNull()
    return Parsed(packageName, minSdk)
  }

  private fun DocumentBuilderFactory.setHardenedFeature(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
  }
}

/** Reads [LibraryMinSdk] entries from a set of resolved `android-manifest` artifacts. */
internal object LibraryMinSdkCollector {
  fun collect(artifacts: Iterable<ResolvedArtifactResult>): List<LibraryMinSdk> {
    val out = LinkedHashMap<String, LibraryMinSdk>()
    for (artifact in artifacts) {
      // Module deps only — project (`:foo`) deps resolve their own minSdk from defaultConfig and
      // can't conflict with the consuming module in a way `tools:overrideLibrary` addresses.
      val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
      val coordinate = "${id.group}:${id.module}"
      if (out.containsKey(coordinate)) continue
      val file = artifact.file
      if (!file.isFile) continue
      val parsed = AarManifestReader.parse(file)
      val min = parsed.minSdk ?: continue
      out[coordinate] = LibraryMinSdk(coordinate, parsed.packageName, min)
    }
    return out.values.toList()
  }
}
