package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

// The web-embed *view* of a packed bundle. This is the one bundle reader that did not move to
// `:bundle-format` in the #3824 split: its result is shaped by [WebEmbed], the HTML generator,
// which reaches into `serve`'s escaping helpers and so belongs to the CLI. `:bundle-format` stays
// free of it — a bundle reader should not need an HTML generator on its classpath.

/**
 * The previews needed to build a web embed, read out of a packed bundle in one pass: the manifest
 * (for ordering + cover) plus each selected preview's baked PNG and a display label.
 *
 * Previews without a baked `previews/<id>.png` (e.g. a `--no-render` pack, or one that failed to
 * render) are dropped — there's nothing to show on a web page. Returned in `previewIds` order with
 * the cover first, matching how the polyglot lays them out.
 */
internal data class BundleWebEmbedData(
  val manifest: BundleReader.Manifest,
  val previews: List<WebEmbed.Preview>,
)

private val bundleJson = Json {
  ignoreUnknownKeys = true
  classDiscriminator = "kind"
}

private val bundlePreviewsJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

/**
 * Read everything a [WebEmbed] needs from a bundle file: the manifest, `previews.json` (for
 * human-readable labels), and every baked `previews/<id>.png`. The PNGs are the single source of
 * what's shown, so previews with no baked image are omitted.
 */
internal fun readBundleWebEmbedData(file: File): BundleWebEmbedData {
  val zipBytes = BundleReader.extractZipBytes(file)
  var manifest: BundleReader.Manifest? = null
  val labels = HashMap<String, String>()
  val pngs = HashMap<String, ByteArray>()
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      val name = entry.name
      when {
        name == "bundle.json" ->
          manifest =
            bundleJson.decodeFromString(
              BundleReader.Manifest.serializer(),
              zin.readBytes().toString(Charsets.UTF_8),
            )
        name == "previews.json" -> {
          // Best-effort: labels are a nicety. A malformed/foreign previews.json must not sink the
          // embed — we still have ids from bundle.json to fall back on.
          runCatching {
            bundlePreviewsJson.decodeFromString(
              PreviewManifest.serializer(),
              zin.readBytes().toString(Charsets.UTF_8),
            )
          }
            .getOrNull()
            ?.previews
            ?.forEach { labels[it.id] = it.functionName.ifBlank { it.id } }
        }
        name.startsWith("previews/") && name.endsWith(".png") -> {
          val id = name.removePrefix("previews/").removeSuffix(".png")
          pngs[id] = zin.readBytes()
        }
      }
      zin.closeEntry()
    }
  }
  val m = manifest ?: throw IllegalArgumentException("bundle.json missing in ${file.path}")
  val previews =
    m.previewIds.mapNotNull { id ->
      val png = pngs[id] ?: return@mapNotNull null
      WebEmbed.Preview(
        id = id,
        label = labels[id] ?: id,
        pngBytes = png,
        isCover = id == m.coverPreviewId,
      )
    }
  return BundleWebEmbedData(manifest = m, previews = previews)
}
