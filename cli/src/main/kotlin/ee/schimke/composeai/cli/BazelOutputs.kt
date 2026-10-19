package ee.schimke.composeai.cli

import ee.schimke.composeai.discovery.PreviewInfo
import java.io.File

/**
 * Where `--output` puts each of [previews], keyed by preview id (#6115).
 * - A directory (one that exists, or a path ending in a separator): every preview, under its
 *   capture's own file name, which is unique within a target.
 * - A file with one preview: that file.
 * - A file with several (a multipreview function, or a `--filter` matching more than one): one file
 *   per preview beside it, `<stem>_<variant>.<ext>`, the variant being what the preview's id adds
 *   to its function's name, or its position when that is not enough.
 */
internal fun bazelOutputFiles(
  previews: List<PreviewInfo>,
  output: File?,
  /**
   * The `--output` argument as typed: `File` drops the trailing separator that marks a directory.
   */
  typed: String? = output?.path,
): Map<String, File> {
  if (output == null || previews.isEmpty()) return emptyMap()
  val namesDirectory = typed != null && (typed.endsWith("/") || typed.endsWith(File.separator))
  if (output.isDirectory || namesDirectory) {
    return previews.associate { preview ->
      val name =
        preview.captures
          .firstOrNull()
          ?.renderOutput
          ?.takeIf { it.isNotEmpty() }
          ?.let { File(it).name } ?: "${outputSafe(preview.id)}.png"
      preview.id to output.resolve(name)
    }
  }
  if (previews.size == 1) return mapOf(previews.single().id to output)
  val extension = output.extension.ifEmpty { "png" }
  val stem = output.nameWithoutExtension
  val used = HashSet<String>()
  return previews.withIndex().associate { (index, preview) ->
    val variant =
      outputSafe(preview.id.removePrefix("${preview.className}.${preview.functionName}")).takeIf {
        it.isNotEmpty() && it !in used
      } ?: (index + 1).toString()
    used += variant
    preview.id to output.resolveSibling("${stem}_$variant.$extension")
  }
}

/** [text] reduced to characters every filesystem accepts, without leading or trailing `_`. */
internal fun outputSafe(text: String): String =
  text.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
