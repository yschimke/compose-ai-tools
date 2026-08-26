package ee.schimke.composeai.daemon

import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * B2.2 phase 2 — daemon-side incremental rescan.
 *
 * **Layering** ([LAYERING.md](../../../../../../../docs/daemon/LAYERING.md)). `:daemon:core` does
 * NOT depend on `:gradle-plugin`. The plugin's `DiscoverPreviewsTask` is the authoritative
 * full-classpath discovery pass; this class is the daemon-side parallel impl scoped to one source
 * file. Both use ClassGraph and the same `@Preview` FQN list — kept in sync by manual mirroring,
 * not by code reuse.
 *
 * **Pipeline** (see [DESIGN.md § 8 Tier 2](../../../../../../../docs/daemon/DESIGN.md)).
 *
 * 1. **Cheap pre-filter** — [cheapPrefilter] regex-greps the saved `.kt` file's text for `@Preview`
 *    (or any registered multi-preview meta-annotation FQN already in the index). If no match AND
 *    the file isn't currently in the preview-bearing set, the file definitely doesn't contribute
 *    previews and we can skip the scan entirely. ~1ms per save. Fail-safe: returns `true` on any
 *    I/O exception so a transient read error doesn't drop a real edit.
 * 2. **Scoped scan** — [scanForFile] runs ClassGraph filtered to the smallest classpath element
 *    containing the changed file's compiled `.class` output. Returns the previews this file
 *    currently contributes. Fail-safe: returns `emptySet` on scan failure (logged via stderr —
 *    free-form per [PROTOCOL.md § 1](../../../../../../../docs/daemon/PROTOCOL.md)).
 *
 * **Limitations.** v1 uses a path-substring heuristic for source `.kt` → classpath dir mapping (see
 * [classpathElementForFile]). When the heuristic misses, the scan falls back to the full daemon
 * classpath — correct, just slower. v2 (a follow-up; not in this commit) would source the mapping
 * from the launch descriptor.
 *
 * **Multi-preview meta-annotations.** Users can define `@LightDarkPreviews` etc. that fan out to
 * multiple `@Preview`s. The plugin discovers these via `scanResult.getClassInfo(annName)` and
 * recurses; we mirror the same logic. Annotation names already known to the index (e.g. captured
 * during the plugin's full discovery pass) are passed in via [knownPreviewAnnotationFqns] so the
 * cheap pre-filter can also greplace them.
 */
class IncrementalDiscovery(
  /** The daemon's own classpath — typically `java.class.path` split on the path separator. */
  private val classpath: List<Path>,
  /**
   * `@Preview` FQNs to look for. Defaults to [DEFAULT_PREVIEW_ANNOTATION_FQNS]; multi-preview
   * meta-annotations the gradle plugin discovered can be appended on top so the cheap pre-filter
   * recognises a save that touches a class using one of them.
   */
  private val knownPreviewAnnotationFqns: Set<String> = DEFAULT_PREVIEW_ANNOTATION_FQNS,
) {

  /**
   * Compiled regex matching any of [knownPreviewAnnotationFqns] in source text. Built once at
   * construction. We match either the simple name (e.g. `@Preview`) OR the FQN (e.g.
   * `@androidx.compose.ui.tooling.preview.Preview`) so qualified imports also fire.
   */
  private val preFilterPattern: Regex by lazy {
    val alternatives = mutableSetOf<String>()
    for (fqn in knownPreviewAnnotationFqns) {
      // Simple name (after the last `.`).
      val simple = fqn.substringAfterLast('.')
      if (simple.isNotEmpty()) alternatives.add(Regex.escape(simple))
      alternatives.add(Regex.escape(fqn))
    }
    // `@(?:Preview|androidx\.compose\.ui\.tooling\.preview\.Preview|...)` with a word boundary
    // after to avoid `@PreviewWrapper` matching when only `@Preview` is in the set.
    val joined = alternatives.joinToString("|")
    Regex("""@(?:$joined)\b""")
  }

  /**
   * Returns `true` when [file] either contains `@Preview`-shaped annotation text OR is currently
   * known to contribute previews to [currentIndex]. Returning `true` means callers should escalate
   * to [scanForFile]; returning `false` means the save can be skipped entirely.
   *
   * Fail-safe on I/O errors — returns `true` so a transient read failure can't silently drop a real
   * edit.
   */
  fun cheapPrefilter(file: Path, currentIndex: PreviewIndex): Boolean {
    // Quick path: file currently contributes previews. The basename match guards against the
    // worst-case "absolute path" → "relative path" mismatch by also accepting suffix matches.
    val pathString = file.toString()
    val basename = file.fileName?.toString().orEmpty()
    val indexedSourceFiles =
      currentIndex.snapshot().values.mapNotNullTo(HashSet()) { it.sourceFile }
    val indexHit = indexedSourceFiles.any { sourceFile ->
      sourceFile == pathString ||
        (basename.isNotEmpty() && (sourceFile == basename || sourceFile.endsWith("/$basename")))
    }
    if (indexHit) return true

    return try {
      val text = Files.readString(file)
      preFilterPattern.containsMatchIn(text)
    } catch (_: IOException) {
      true
    } catch (_: Throwable) {
      true
    }
  }

  /**
   * Scoped ClassGraph scan: returns the previews [file] currently contributes. Filters the scan to
   * the smallest classpath element containing the file's compiled `.class` output (see
   * [classpathElementForFile]); falls back to the full classpath when the heuristic misses.
   *
   * The returned set's id field uses the same `<className>.<methodName>` form the gradle plugin
   * emits when no variant suffix is present, plus any `_<name>` / `_<group>` suffix needed to
   * disambiguate multi-preview expansions. We deliberately do NOT recompute the plugin's full
   * variant suffix (device/fontScale/uiMode); the diff path only uses tracked fields, and adding a
   * fresh preview variant via the daemon path is rare enough that picking up the new id on the next
   * plugin-side full discovery is acceptable v1 behaviour.
   *
   * Fail-safe: returns `emptySet` on any scan failure (and writes a stderr diagnostic).
   */
  fun scanForFile(file: Path): Set<PreviewInfoDto> {
    val target = classpathElementForFile(file)
    val scanRoots = scanRootsForTarget(target)
    return try {
      ClassGraph()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(scanRoots.map { it.toAbsolutePath().toString() })
        .ignoreParentClassLoaders()
        .scan()
        .use { scanResult -> collectPreviews(scanResult, file, target) }
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: IncrementalDiscovery.scanForFile($file) failed " +
          "(${t.javaClass.simpleName}: ${t.message}); returning empty"
      )
      emptySet()
    }
  }

  internal fun scanRootsForFile(file: Path): List<Path> =
    scanRootsForTarget(classpathElementForFile(file))

  private fun scanRootsForTarget(target: Path?): List<Path> {
    // Keep the changed module's class output scoped, but retain dependency JARs: a method can use a
    // dependency-provided multi-preview annotation whose meta-annotation carries @CaptureGutter.
    // Scanning only `target` makes that annotation class unresolvable and re-adds a combination the
    // authoritative pass rejected. Other module output directories stay excluded, preserving the
    // cheap one-file scan this path exists for.
    return if (target != null)
      buildList {
        add(target)
        classpath.filterTo(this) { root ->
          root != target && root.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true
        }
      }
    else classpath
  }

  private fun collectPreviews(
    scanResult: ScanResult,
    file: Path,
    target: Path?,
  ): Set<PreviewInfoDto> {
    val sourceKey = file.toString()
    val basename = file.fileName?.toString().orEmpty()
    val targetRoot = target?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() }
    val results = LinkedHashSet<PreviewInfoDto>()
    for (classInfo in scanResult.allClasses) {
      // The dependency JARs in the scan roots are there to resolve annotation metadata only (see
      // [scanRootsForFile]). A JAR class compiled from the same source basename as the edited file
      // (both modules having a `Previews.kt` is entirely normal) would otherwise satisfy the
      // basename match below and be emitted under this file's `sourceKey`, polluting the
      // incremental index with foreign previews. Preview candidates therefore have to originate
      // from the edited module's own class output.
      if (!originatesFrom(classInfo, targetRoot)) continue
      // The bytecode SourceFile attribute is just the basename; we accept either the absolute
      // path stored on the index (when sourceFile happens to be absolute) or the bytecode
      // basename match. This mirrors the index's diff-key heuristic in [PreviewIndex.diff].
      val classSource = classInfo.sourceFile
      val matchesFile =
        classSource != null && (classSource == basename || sourceKey.endsWith("/$classSource"))
      if (!matchesFile) continue
      for (method in classInfo.methodInfo) {
        val annotations = method.annotationInfo?.toList().orEmpty()
        val direct = collectDirectPreviews(annotations)
        val expansions =
          if (direct.isNotEmpty()) direct
          else annotations.flatMap { resolveMultiPreview(it, scanResult, mutableSetOf()) }
        if (expansions.isEmpty()) continue

        // Mirror the authoritative pass's rejection of `@CaptureGutter` + `@ScrollingPreview`
        // (PreviewDiscovery). Without it, a full pass correctly drops the combination from
        // `previews.json`, but the first source edit re-adds the function here — the diff sees a
        // previously-absent id and treats it as an addition — so the daemon would expose an
        // unguttered, unscrolled preview until the next full discovery. `@CaptureGutter` may be
        // hoisted onto a multi-preview annotation, so the check walks the meta-annotation closure.
        //
        // Best-effort, like the rest of this path: once a rejected function is out of the index, a
        // fix that removes one annotation may not re-trip `cheapPrefilter` (its regex carries only
        // the known preview FQNs), so recovery can wait for a full pass. That matches the
        // incremental path's standing v1 contract (see [scanForFile] / the class kdoc), where
        // identity is authoritative and details settle on the next full discovery.
        if (declaresGutterAndScrolling(annotations, scanResult)) {
          System.err.println(
            "compose-ai-daemon: IncrementalDiscovery skipping '${classInfo.name}.${method.name}'" +
              " — @CaptureGutter cannot be combined with @ScrollingPreview; remove one annotation."
          )
          continue
        }
        for (ann in expansions) {
          results.add(toDto(classInfo, method, ann, sourceKey))
        }
      }
    }
    return results
  }

  /**
   * Whether [classInfo] was loaded from [targetRoot] — the changed module's own class output.
   *
   * Fail-open: with no resolved target the scan is already the full classpath (no dependency-only
   * roots were added), and a classpath element ClassGraph can't expose as a file (a `jrt:` module,
   * say) can't be compared, so both cases keep the class as a candidate. Dropping a real preview is
   * worse than the pollution this guard exists to prevent, and neither case is the JAR-vs-module
   * ambiguity it targets.
   */
  private fun originatesFrom(classInfo: ClassInfo, targetRoot: Path?): Boolean {
    if (targetRoot == null) return true
    val element = runCatching { classInfo.classpathElementFile }.getOrNull() ?: return true
    val elementPath =
      runCatching { element.toPath().toAbsolutePath().normalize() }.getOrNull() ?: return true
    return elementPath == targetRoot
  }

  /**
   * True when a method declares `@ScrollingPreview` together with an **effective** `@CaptureGutter`
   * — the contradiction the authoritative pass rejects (see `PreviewDiscovery`).
   *
   * Must match that pass clause for clause, or a source save diverges from the full
   * `previews.json`: an all-zero gutter (`@CaptureGutter()`, or edges that all clamp to `0`) is
   * equivalent to no annotation there (`extractCaptureGutter` returns `null`), so such a function
   * is KEPT — checking the annotation's mere presence would wrongly drop it here and make the live
   * daemon shed a preview the full pass emitted.
   *
   * `@ScrollingPreview` targets FUNCTION only, so it is always a direct method annotation;
   * `@CaptureGutter` also targets ANNOTATION_CLASS, so it can be hoisted onto a multi-preview
   * annotation and is found by walking the meta-annotation closure.
   */
  private fun declaresGutterAndScrolling(
    annotations: List<AnnotationInfo>,
    scanResult: ScanResult,
  ): Boolean {
    var hasScrolling = false
    var gutter: AnnotationInfo? = null
    val visited = mutableSetOf<String>()
    fun walk(anns: List<AnnotationInfo>) {
      for (ann in anns) {
        if (ann.name == SCROLLING_PREVIEW_FQN) hasScrolling = true
        if (ann.name == CAPTURE_GUTTER_FQN && gutter == null) gutter = ann
        if (!visited.add(ann.name)) continue
        val annClass = scanResult.getClassInfo(ann.name) ?: continue
        walk(annClass.annotationInfo.toList())
      }
    }
    walk(annotations)
    return hasScrolling && gutter?.let { hasEffectiveGutter(it) } == true
  }

  /**
   * Whether a `@CaptureGutter` resolves to a non-zero gutter — mirrors `PreviewDiscovery`'s
   * `extractCaptureGutter` / `CaptureGutterDp.isEmpty()`: a per-edge `INHERIT_GUTTER` (`-1`) takes
   * `all`, negatives clamp to `0`, and an all-zero result is "no gutter".
   */
  private fun hasEffectiveGutter(ann: AnnotationInfo): Boolean {
    val pv = ann.parameterValues
    val all = (pv.getValue("all") as? Int) ?: 0
    fun edge(name: String): Int {
      val raw = (pv.getValue(name) as? Int) ?: INHERIT_GUTTER
      val resolved = if (raw == INHERIT_GUTTER) all else raw
      return resolved.coerceIn(0, MAX_CAPTURE_GUTTER_DP)
    }
    return edge("start") > 0 || edge("top") > 0 || edge("end") > 0 || edge("bottom") > 0
  }

  private fun collectDirectPreviews(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
    val result = mutableListOf<AnnotationInfo>()
    for (ann in annotations) {
      if (ann.name in knownPreviewAnnotationFqns) {
        result.add(ann)
      } else if (ann.name in CONTAINER_FQNS) {
        val value = ann.parameterValues.getValue("value")
        when (value) {
          is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { result.add(it) }
          is AnnotationInfo -> result.add(value)
          else -> {
            val len = runCatching { java.lang.reflect.Array.getLength(value) }.getOrNull() ?: 0
            for (i in 0 until len) {
              val elem = java.lang.reflect.Array.get(value, i)
              if (elem is AnnotationInfo) result.add(elem)
            }
          }
        }
      }
    }
    return result
  }

  private fun resolveMultiPreview(
    ann: AnnotationInfo,
    scanResult: ScanResult,
    visited: MutableSet<String>,
  ): List<AnnotationInfo> {
    if (ann.name in visited) return emptyList()
    if (ann.name in knownPreviewAnnotationFqns) return emptyList()
    if (ann.name in CONTAINER_FQNS) return emptyList()
    visited.add(ann.name)
    val annClassInfo = scanResult.getClassInfo(ann.name) ?: return emptyList()
    val direct = collectDirectPreviews(annClassInfo.annotationInfo.toList())
    if (direct.isNotEmpty()) return direct
    val result = mutableListOf<AnnotationInfo>()
    for (metaAnn in annClassInfo.annotationInfo) {
      result.addAll(resolveMultiPreview(metaAnn, scanResult, visited))
    }
    return result
  }

  private fun toDto(
    classInfo: ClassInfo,
    method: MethodInfo,
    ann: AnnotationInfo,
    sourceKey: String,
  ): PreviewInfoDto {
    val name = (ann.parameterValues.getValue("name") as? String)?.takeIf { it.isNotBlank() }
    val group = (ann.parameterValues.getValue("group") as? String)?.takeIf { it.isNotBlank() }
    val suffix =
      when {
        name != null -> "_$name"
        group != null -> "_$group"
        else -> ""
      }
    val id = "${classInfo.name}.${method.name}$suffix"
    return PreviewInfoDto(
      id = id,
      className = classInfo.name,
      methodName = method.name,
      sourceFile = sourceKey,
      displayName = name,
      group = group,
    )
  }

  /**
   * Resolves the saved source `.kt` file to the smallest classpath element that holds its compiled
   * `.class` output. Heuristic: walk classpath dirs (skipping JARs), pick the one whose absolute
   * path overlaps with the source file's path components after a recognised source-set prefix
   * (`src/main/kotlin/`, `src/<variant>/kotlin/`). Returns `null` when no dir overlaps.
   *
   * v2 follow-up: the gradle plugin's `composePreviewDaemonStart` could emit a source-set →
   * classpath-dir mapping in its launch descriptor, removing the heuristic. Until then this is
   * good-enough for the production layout (`build/tmp/kotlin-classes/<variant>/...` mirrors
   * `src/main/kotlin/...`'s package structure).
   */
  internal fun classpathElementForFile(file: Path): Path? {
    val pathString = file.toString().replace('\\', '/')
    // Pull off the "src/<sourceSet>/kotlin/<rel>" tail; the `<rel>` is the package path the
    // compiler outputs into.
    val idx =
      SOURCE_SET_PREFIXES.firstNotNullOfOrNull { prefix ->
        val match = Regex("""/$prefix/(?<rel>.+\.kt)$""").find(pathString)
        match?.groups?.get("rel")?.value
      } ?: return null

    val withoutKt = idx.removeSuffix(".kt")
    // `<package>/<filename>` — match against any classpath dir holding
    // `<package>/<filename>Kt.class`
    // OR `<package>/<file's class without Kt>.class`.
    val packagePath = withoutKt.substringBeforeLast('/', missingDelimiterValue = "")
    return classpath
      .filter { Files.isDirectory(it) }
      .firstOrNull { dir ->
        if (packagePath.isEmpty()) {
          // Top-level file — accept any class dir. Heuristic still gets us a smaller set than
          // `classpath`.
          true
        } else {
          Files.isDirectory(dir.resolve(packagePath))
        }
      }
  }

  companion object {
    /**
     * Daemon-side mirror of `:gradle-plugin`'s `DiscoverPreviewsTask.PREVIEW_FQNS`. Sharing would
     * pull `:gradle-plugin` onto the daemon classpath (a layering inversion); duplicating ~3 FQNs
     * is the cheap fix.
     */
    val DEFAULT_PREVIEW_ANNOTATION_FQNS: Set<String> =
      setOf(
        "androidx.compose.ui.tooling.preview.Preview",
        "androidx.compose.desktop.ui.tooling.preview.Preview",
        "androidx.wear.tiles.tooling.preview.Preview",
      )

    /**
     * `@CaptureGutter` / `@ScrollingPreview` FQNs, mirrored from `:gradle-plugin`'s
     * `PreviewDiscovery` for the same layering reason as [DEFAULT_PREVIEW_ANNOTATION_FQNS]: the two
     * cannot be combined, and this path must reject the pair to match the authoritative pass.
     */
    private const val CAPTURE_GUTTER_FQN = "ee.schimke.composeai.preview.CaptureGutter"
    private const val SCROLLING_PREVIEW_FQN = "ee.schimke.composeai.preview.ScrollingPreview"

    /**
     * Mirrors `preview.INHERIT_GUTTER` / `MAX_CAPTURE_GUTTER_DP` (a per-edge "take `all`" sentinel,
     * and the per-edge dp ceiling). Duplicated for the same layering reason as the FQNs above.
     */
    private const val INHERIT_GUTTER = -1
    private const val MAX_CAPTURE_GUTTER_DP = 64

    /** Synthesised `@Repeatable` containers; same FQN set as the gradle plugin. */
    private val CONTAINER_FQNS: Set<String> =
      setOf(
        "androidx.compose.ui.tooling.preview.Preview\$Container",
        "androidx.compose.ui.tooling.preview.Preview.Container",
        "androidx.wear.tiles.tooling.preview.Preview\$Container",
        "androidx.wear.tiles.tooling.preview.Preview.Container",
      )

    private val SOURCE_SET_PREFIXES: List<String> =
      listOf(
        // Standard Gradle source set layouts. Variants (`debug`, `release`, `androidTest`) are
        // matched implicitly by the second alternative.
        "src/main/kotlin",
        "src/main/java",
        "src/[A-Za-z0-9_]+/kotlin",
        "src/[A-Za-z0-9_]+/java",
      )
  }
}
