package ee.schimke.composeai.cli

import ee.schimke.composeai.discovery.PreviewInfo
import java.io.File

/**
 * Where `--output` puts each of [previews], keyed by preview id (#6115). One target's previews;
 * [bazelOutputPlan] allocates across several.
 */
internal fun bazelOutputFiles(
  previews: List<PreviewInfo>,
  output: File?,
  /**
   * The `--output` argument as typed: `File` drops the trailing separator that marks a directory.
   */
  typed: String? = output?.path,
): Map<String, File> =
  bazelOutputPlan(previews.map { "" to it }, output, typed).mapKeys { it.key.second }

/**
 * Where `--output` puts each of [previews], keyed by (target, preview id), every name allocated
 * once across all the selected targets so no render overwrites another:
 * - A directory (one that exists, or a path ending in a separator): every preview, under its
 *   capture's own file name; a name already taken, by a preview of another target, gets a numeric
 *   suffix.
 * - A file with one preview: that file.
 * - A file with several (a multipreview function, a `--filter` matching more than one, or several
 *   targets): one file per preview beside it, `<stem>_<variant>.<ext>`, the variant being what the
 *   preview's id adds to its function's name, or the next unused position when that is empty or
 *   taken.
 */
internal fun bazelOutputPlan(
  previews: List<Pair<String, PreviewInfo>>,
  output: File?,
  typed: String? = output?.path,
): Map<Pair<String, String>, File> {
  if (output == null || previews.isEmpty()) return emptyMap()
  val used = HashSet<String>()
  fun claim(preferred: String?, positional: (Int) -> String, from: Int): String {
    if (!preferred.isNullOrEmpty() && used.add(preferred)) return preferred
    var position = from
    while (true) positional(position++).let { if (used.add(it)) return it }
  }
  val namesDirectory = typed != null && (typed.endsWith("/") || typed.endsWith(File.separator))
  if (output.isDirectory || namesDirectory) {
    return previews.associate { (target, preview) ->
      val name =
        preview.captures
          .firstOrNull()
          ?.renderOutput
          ?.takeIf { it.isNotEmpty() }
          ?.let { File(it).name } ?: "${outputSafe(preview.id)}.png"
      val stem = name.substringBeforeLast('.')
      val extension = name.substringAfterLast('.', "png")
      (target to preview.id) to output.resolve(claim(name, { "${stem}_$it.$extension" }, from = 2))
    }
  }
  if (previews.size == 1) return mapOf(previews.single().let { (t, p) -> (t to p.id) to output })
  val extension = output.extension.ifEmpty { "png" }
  val stem = output.nameWithoutExtension
  return previews.withIndex().associate { (index, entry) ->
    val (target, preview) = entry
    val variant =
      claim(
        outputSafe(preview.id.removePrefix("${preview.className}.${preview.functionName}")),
        { it.toString() },
        from = index + 1,
      )
    (target to preview.id) to output.resolveSibling("${stem}_$variant.$extension")
  }
}

/** [text] reduced to characters every filesystem accepts, without leading or trailing `_`. */
internal fun outputSafe(text: String): String =
  text.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
