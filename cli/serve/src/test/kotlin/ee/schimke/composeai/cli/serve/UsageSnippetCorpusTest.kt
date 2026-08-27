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
 * Driven by `-Dcomposeai.usageCorpus.repos=<name>=<path>,…`, so it is a no-op in a normal build and
 * on any machine without the catalogs. `scripts/usage-corpus.sh` supplies the paths and then
 * compiles what this writes; see `docs/design/USAGE_SNIPPET_CORPUS.md` for the whole loop.
 *
 * The catalogs are deliberately unalike, which is the point of testing both, and which sampler runs
 * is decided by the checkout's *shape* rather than its name:
 * - **annotation-first** (m3-catalog): `@CatalogComponent` / `@CatalogVariant`, plus a
 *   `compose-usage.json`, so its snippets are expected to come out as usage code.
 * - **spec-driven** (meshcore-mobile): a `catalog.spec.json` naming plain `@Preview` functions, and
 *   no rules at all, so it exercises the generic path — annotation stripping only.
 */
class UsageSnippetCorpusTest {

  private data class Sample(val system: String, val kind: String, val function: String)

  /**
   * The checkouts to sample, as `name=path,name=path` in `composeai.usageCorpus.repos`.
   *
   * One property rather than one per catalog on purpose: a fixed set of forwarded keys silently
   * ignores any checkout whose name is not in it, which would have produced an empty corpus and a
   * passing run for a catalog nobody sampled.
   */
  private fun repos(): List<Pair<String, File>> {
    // Absent means "no checkouts wired", and is the normal build. *Present but malformed* is a typo
    // in the documented property, and must not read as the same thing: silently dropping the entry
    // turns a wrong invocation into a successful no-op, which is the failure this whole loop is
    // built to stop reporting as a pass.
    val spec = System.getProperty("composeai.usageCorpus.repos") ?: return emptyList()
    // `-Dcomposeai.usageCorpus.repos=` — present and empty — is a wrong invocation too, and
    // dropping through to the empty list would make it the same silent no-op as not setting it.
    require(spec.isNotBlank() && spec.any { it != ',' && !it.isWhitespace() }) {
      "composeai.usageCorpus.repos is set but empty; omit it, or pass <name>=<path>,…"
    }
    return spec
      .split(',')
      .filter { it.isNotBlank() }
      .map { entry ->
        val at = entry.indexOf('=')
        val name = if (at > 0) entry.substring(0, at).trim() else ""
        val path = if (at > 0) entry.substring(at + 1).trim() else ""
        require(name.isNotEmpty() && path.isNotEmpty()) {
          "composeai.usageCorpus.repos entry is not <name>=<path>: '$entry'"
        }
        name to File(path)
      }
  }

  private val outDir =
    File(System.getProperty("composeai.usageCorpus.out") ?: "build/usage-corpus").also {
      it.mkdirs()
    }

  /**
   * Every `.kt` under a checkout, excluding build output and test sources.
   *
   * The source-set names matter here: `/test/` alone misses every Kotlin Multiplatform layout —
   * `src/commonTest`, `src/jvmTest`, `src/desktopTest`, `src/androidUnitTest` — and both catalogs
   * sampled are multiplatform. A test fixture picked up as a production preview would quietly move
   * the reported ratio.
   *
   * Matched at the source-set name's **boundary**, not as a substring: `src/latestMain` and
   * `src/contestMain` contain `test` and are production, and excluding them would drop real
   * previews just as quietly in the other direction. A test source set's name ends in `Test`
   * (`commonTest`, `jvmTest`, `androidUnitTest`) or `TestFixtures` (`commonTestFixtures`,
   * `androidTestFixtures`), or is `test` / `testFixtures` outright — optionally with an Android
   * build-variant suffix (`testDebug`, `androidTestDebug`, `screenshotTestDebug`). The suffix must
   * start with a capital, which is what keeps `latestMain` out of it.
   */
  private fun sources(root: File): List<File> {
    val testSourceSet =
      Regex(
        """/src/(test|testFixtures|[A-Za-z0-9]*Test|[A-Za-z0-9]*TestFixtures)([A-Z][A-Za-z0-9]*)?/"""
      )
    return root
      .walkTopDown()
      .onEnter { it.name !in setOf("build", ".git", ".gradle") }
      .filter { it.isFile && it.extension == "kt" }
      // `invariantSeparatorsPath`, not `path`: on Windows the latter is backslash-separated, the
      // regex below only knows `/src/…/`, and every fixture would then read as production — which
      // would fail this class's own source-set test on a Windows checkout.
      .filterNot { testSourceSet.containsMatchIn(it.invariantSeparatorsPath) }
      .toList()
  }

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
      root.walkTopDown().firstOrNull {
        it.isFile && it.invariantSeparatorsPath.endsWith(path.replace('\\', '/'))
      } ?: return emptyMap()
    return Regex(
        """<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL,
      )
      .findAll(file.readText())
      .associate {
        it.groupValues[1] to PlaygroundSeedResolver.unescapeAndroidString(it.groupValues[2])
      }
  }

  /**
   * The catalog's declared scaffold sources, read repo-root-relative out of the checkout — what the
   * server fetches from GitHub for the same rules file. Without them a delegating catalog's samples
   * would be cleaned differently here than in production, which is the one thing this corpus must
   * not do.
   */
  private fun helperSourcesFor(root: File, rules: UsageRules): List<String> =
    rules.scaffoldSources
      .map { it.trim().removePrefix("/") }
      .filter { it.isNotEmpty() }
      .take(PlaygroundSeedResolver.MAX_SCAFFOLD_SOURCES)
      .mapNotNull { path ->
        File(root, path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
      }

  /** Annotation-first (m3-catalog): the samples come from the annotations themselves. */
  private fun annotationSamples(system: String, root: File, perKind: Int): List<Sample> {
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
        if (out.none { it.function == fn }) out += Sample(system, kind, fn)
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

  /** Spec-driven (meshcore-mobile): the samples come from `catalog.spec.json`. */
  private fun specSamples(system: String, root: File, perKind: Int): List<Sample> {
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
        .map { Sample(system, kind, it) }
    return spread(components, "component") + spread(variants, "variant")
  }

  /**
   * Give each snippet its own package, so the corpus compiles as N independent pastes rather than
   * as one source set.
   *
   * Without this the snippets share the default package, and the compile answers a different
   * question than the one asked in both directions: two previews from the same file that each close
   * over the same same-file helper collide as redeclarations (a failure the developer pasting *one*
   * of them would never see), and a catalog symbol one snippet leaks can resolve against a
   * declaration another snippet happened to copy (a pass the developer would never get). Distinct
   * packages remove both — nothing here imports anything else here.
   *
   * The package goes after any file annotations and before the imports, which is where Kotlin wants
   * it.
   */
  private fun inOwnPackage(text: String, system: String, name: String): String {
    fun part(raw: String) = raw.replace(Regex("[^A-Za-z0-9]"), "_").lowercase()
    val decl = "package usagecorpus.${part(system)}.${part(name)}"
    val lines = text.lines()
    val at = lines.indexOfFirst { it.trimStart().startsWith("import ") }
    val insertAt =
      if (at >= 0) at else lines.indexOfLast { it.trimStart().startsWith("@file:") } + 1
    return (lines.take(insertAt) + listOf(decl, "") + lines.drop(insertAt)).joinToString("\n")
  }

  private val perKind =
    System.getProperty("composeai.usageCorpus.samples")?.toIntOrNull()?.coerceAtLeast(1) ?: 5

  /**
   * The source-set filter has now been wrong in both directions — leaking KMP test sources when it
   * only looked for `/test/`, then excluding production `latestMain` when it matched `test` as a
   * substring, then dropping `commonTestFixtures` when it was narrowed to boundaries. Each of those
   * moves the reported ratio silently, which is the one thing this corpus must not do, so the rule
   * gets pinned rather than re-derived.
   */
  @Test
  fun `test source sets are excluded and production ones are not`() {
    val root = File(outDir, "source-set-fixture").also { it.deleteRecursively() }
    val excluded =
      listOf(
        "test", // single-platform
        "testFixtures",
        "commonTest", // Kotlin Multiplatform
        "jvmTest",
        "androidUnitTest",
        "commonTestFixtures",
        "androidTestFixtures",
        "testDebug", // Android build-variant suffixes
        "androidTestDebug",
        "screenshotTestDebug",
      )
    val kept = listOf("main", "commonMain", "androidMain", "latestMain", "contestMain")
    for (name in excluded + kept) {
      File(root, "module/src/$name/kotlin").mkdirs()
      File(root, "module/src/$name/kotlin/Previews.kt").writeText("// $name\n")
    }
    val found = sources(root).map { it.parentFile.parentFile.name }.toSet()
    assertTrue(found == kept.toSet(), "kept ${found.sorted()}, wanted ${kept.sorted()}")
  }

  @Test
  fun `generate usage snippets from the catalog checkouts`() {
    val repos = repos()
    if (repos.isEmpty()) return // no checkouts wired: nothing to do

    val report = StringBuilder()
    var written = 0

    for ((system, root) in repos) {
      val files = sources(root)
      val (rules, declared) = rulesFor(root)
      val strings = stringsFor(root, rules)
      val helperSources = helperSourcesFor(root, rules)
      // Which sampler by the catalog's *shape*, not its name — keying on the name would silently
      // mis-sample the next catalog somebody points this at. Annotations first, spec as the
      // fallback, rather than branching on the spec file's presence: m3-catalog ships **both**, so
      // "has a spec ⇒ spec-driven" sampled it as the wrong shape and produced nothing.
      val samples =
        annotationSamples(system, root, perKind).ifEmpty { specSamples(system, root, perKind) }
      report.appendLine(
        // The catalog's *own* scaffolds, not the merged map — counting the inherited generic rules
        // would credit every catalog with rules it never wrote. Compared by entry, so a catalog
        // that declared only its own reading of a generic knob still counts.
        "## $system — ${samples.size} samples, rules: ${if (declared) "declared (${with(UsageRules.Companion) { rules.catalogScaffolds() }.size} scaffolds)" else "GENERIC (none declared)"}"
      )
      for (sample in samples) {
        val found = findFunction(files, sample.function)
        if (found == null) {
          report.appendLine("- ${sample.kind}/${sample.function}: SOURCE NOT FOUND")
          continue
        }
        val (file, anchor) = found
        val cleaned = runCatching {
          PlaygroundSourceCleaner.clean(
            source = file.readText(),
            bodyLine = anchor,
            rules = rules,
            strings = strings,
            helperSources = helperSources,
          )
        }
          .getOrElse { e ->
            report.appendLine("- ${sample.kind}/${sample.function}: THREW ${e::class.simpleName}")
            null
          }
        if (cleaned == null) {
          report.appendLine("- ${sample.kind}/${sample.function}: DECLINED (would seed verbatim)")
          continue
        }
        val dir = File(outDir, system).also { it.mkdirs() }
        val name = "${sample.kind}_${sample.function}"
        File(dir, "$name.kt").writeText(inOwnPackage(cleaned.text, system, name))
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
