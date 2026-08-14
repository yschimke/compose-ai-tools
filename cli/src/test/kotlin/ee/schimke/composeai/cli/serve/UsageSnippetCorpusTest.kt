package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Generates usage snippets from **real catalog checkouts** and writes them where a compiler can be
 * pointed at them.
 *
 * ### Why a corpus and not more fixtures
 *
 * Every other test of the cleaner feeds it source this repository controls. That is the wrong shape
 * for the question "does the Source panel work across the catalogs": a fixture proves the rules I
 * wrote match the source I wrote. This walks a checkout, samples previews the way a visitor
 * browsing would land on them, and emits whatever comes out — including the failures.
 *
 * ### Opt-in, and silent without checkouts
 *
 * Driven by `-Dcomposeai.usageCorpus.<system>=<path>`, so it is a no-op in a normal build and on
 * any machine without the catalogs. `scripts/usage-corpus.sh` supplies the paths and then compiles
 * what this writes; see `docs/design/USAGE_SNIPPET_CORPUS.md` for the whole loop.
 *
 * The catalogs are deliberately unalike, which is the point of testing both:
 * - **m3-catalog** is annotation-first (`@CatalogComponent` / `@CatalogVariant`) and ships a
 *   `compose-usage.json`, so its snippets are expected to come out as usage code.
 * - **meshcore-mobile** is spec-driven (`catalog.spec.json` names plain `@Preview` functions) and
 *   ships no rules at all, so it exercises the generic path — annotation stripping only.
 */
class UsageSnippetCorpusTest {

  private data class Sample(val system: String, val kind: String, val function: String)

  private fun repo(system: String): File? =
    System.getProperty("composeai.usageCorpus.$system")?.takeIf { it.isNotBlank() }?.let(::File)

  private val outDir =
    File(System.getProperty("composeai.usageCorpus.out") ?: "build/usage-corpus").also {
      it.mkdirs()
    }

  /** Every `.kt` under a checkout, excluding build output and tests. */
  private fun sources(root: File): List<File> =
    root
      .walkTopDown()
      .onEnter { it.name !in setOf("build", ".git", ".gradle") }
      .filter { it.isFile && it.extension == "kt" }
      .filterNot { it.path.contains("/test/") || it.path.contains("/androidTest/") }
      .toList()

  /**
   * A 1-based line inside [function]'s declaration — the anchor the cleaner walks outwards from.
   *
   * Discovery normally supplies this from the classfile line table. Reading it off the source is
   * what lets the corpus run without building the catalog, and it lands in the same place: the
   * cleaner only needs *a* line inside the declaration.
   */
  private fun anchorOf(text: String, function: String): Int? {
    val lines = text.lines()
    val at = lines.indexOfFirst { line ->
      Regex("""^\s*(?:@\w+\s+)*(?:private\s+|internal\s+)?fun\s+$function\s*\(""")
        .containsMatchIn(line)
    }
    if (at < 0) return null
    // The line after the signature is inside the body for both block and expression forms; fall
    // back to the signature line itself, which the slice also accepts.
    return if (at + 1 <= lines.lastIndex && lines[at + 1].isNotBlank()) at + 2 else at + 1
  }

  private fun findFunction(files: List<File>, function: String): Pair<File, Int>? =
    files.firstNotNullOfOrNull { file ->
      val text = runCatching { file.readText() }.getOrNull() ?: return@firstNotNullOfOrNull null
      if (!text.contains("fun $function")) return@firstNotNullOfOrNull null
      anchorOf(text, function)?.let { file to it }
    }

  /** The catalog's declared rules, or [UsageRules.GENERIC] when it ships none. */
  private fun rulesFor(root: File): Pair<UsageRules, Boolean> {
    val file = File(root, PlaygroundSeedResolver.USAGE_RULES_FILE)
    if (!file.isFile) return UsageRules.GENERIC to false
    return (UsageRules.parse(file.readText()) ?: UsageRules.GENERIC) to true
  }

  /** English string resources, so `stringResource(Res.string.x)` inlines as the rendered label. */
  private fun stringsFor(root: File, rules: UsageRules): Map<String, String> {
    val path = rules.stringsPath ?: return emptyMap()
    val file =
      root.walkTopDown().firstOrNull { it.isFile && it.path.endsWith(path.replace('\\', '/')) }
        ?: return emptyMap()
    return Regex(
        """<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL,
      )
      .findAll(file.readText())
      .associate {
        it.groupValues[1] to PlaygroundSeedResolver.unescapeAndroidString(it.groupValues[2])
      }
  }

  /** m3-catalog: annotation-first, so the samples come from the annotations themselves. */
  private fun m3Samples(root: File, perKind: Int): List<Sample> {
    val out = mutableListOf<Sample>()
    for (file in sources(root).sortedBy { it.path }) {
      val lines = runCatching { file.readText().lines() }.getOrNull() ?: continue
      for ((i, line) in lines.withIndex()) {
        val kind =
          when {
            line.trimStart().startsWith("@CatalogComponent") -> "component"
            line.trimStart().startsWith("@CatalogVariant") -> "variant"
            else -> continue
          }
        // The declaration's own `fun` line is below the annotation stack.
        val fn =
          lines.drop(i).firstNotNullOfOrNull {
            Regex("""^fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""").find(it)?.groupValues?.get(1)
          } ?: continue
        if (out.none { it.function == fn }) out += Sample("m3-catalog", kind, fn)
      }
    }
    // Spread across the alphabet rather than taking the first N, which would be one section file.
    return listOf("component", "variant").flatMap { kind ->
      out
        .filter { it.kind == kind }
        .let { all ->
          if (all.size <= perKind) all else (0 until perKind).map { all[it * all.size / perKind] }
        }
    }
  }

  /** meshcore-mobile: spec-driven, so the samples come from `catalog.spec.json`. */
  private fun meshcoreSamples(root: File, perKind: Int): List<Sample> {
    val spec = File(root, "catalog.spec.json").takeIf { it.isFile } ?: return emptyList()
    val text = spec.readText()
    // Deliberately regex rather than a JSON parser: this test has no JSON dependency and the shape
    // it needs (preview names, and the nested ones under "variants") is unambiguous in this file.
    val variantBlocks = Regex(""""variants"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    val previewName = Regex(""""preview"\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"""")
    val variants =
      variantBlocks
        .findAll(text)
        .flatMap { previewName.findAll(it.groupValues[1]) }
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    val components =
      previewName
        .findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .filterNot { it in variants }
        .toList()
    fun spread(all: List<String>, kind: String) =
      (if (all.size <= perKind) all else (0 until perKind).map { all[it * all.size / perKind] })
        .map { Sample("meshcore-mobile", kind, it) }
    return spread(components, "component") + spread(variants, "variant")
  }

  private val perKind =
    System.getProperty("composeai.usageCorpus.samples")?.toIntOrNull()?.coerceAtLeast(1) ?: 5

  @Test
  fun `generate usage snippets from the catalog checkouts`() {
    val m3 = repo("m3-catalog")
    val meshcore = repo("meshcore-mobile")
    if (m3 == null && meshcore == null) return // no checkouts wired: nothing to do

    val report = StringBuilder()
    var written = 0

    for (root in listOfNotNull(m3, meshcore)) {
      val system = if (root == m3) "m3-catalog" else "meshcore-mobile"
      val files = sources(root)
      val (rules, declared) = rulesFor(root)
      val strings = stringsFor(root, rules)
      val samples =
        if (system == "m3-catalog") m3Samples(root, perKind) else meshcoreSamples(root, perKind)
      report.appendLine(
        "## $system — ${samples.size} samples, rules: ${if (declared) "declared (${rules.scaffolds.size} scaffolds)" else "GENERIC (none declared)"}"
      )
      for (sample in samples) {
        val found = findFunction(files, sample.function)
        if (found == null) {
          report.appendLine("- ${sample.kind}/${sample.function}: SOURCE NOT FOUND")
          continue
        }
        val (file, anchor) = found
        val cleaned =
          runCatching { PlaygroundSourceCleaner.clean(file.readText(), anchor, rules, strings) }
            .getOrElse { e ->
              report.appendLine("- ${sample.kind}/${sample.function}: THREW ${e::class.simpleName}")
              null
            }
        if (cleaned == null) {
          report.appendLine("- ${sample.kind}/${sample.function}: DECLINED (would seed verbatim)")
          continue
        }
        val dir = File(outDir, system).also { it.mkdirs() }
        File(dir, "${sample.kind}_${sample.function}.kt").writeText(cleaned.text)
        written++
        val residue = if (cleaned.residue.isEmpty()) "clean" else "residue=${cleaned.residue}"
        report.appendLine("- ${sample.kind}/${sample.function}: $residue (${file.name})")
      }
      report.appendLine()
    }

    File(outDir, "REPORT.md").writeText(report.toString())
    println(report)
    assertTrue(written > 0, "no snippets were generated from the supplied checkouts")
  }
}
