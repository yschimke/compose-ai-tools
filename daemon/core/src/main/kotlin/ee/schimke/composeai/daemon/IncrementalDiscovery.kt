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
    val scanRoots = if (target != null) listOf(target) else classpath
    return try {
      ClassGraph()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(scanRoots.map { it.toAbsolutePath().toString() })
        .ignoreParentClassLoaders()
        .scan()
        .use { scanResult -> collectPreviews(scanResult, file) }
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: IncrementalDiscovery.scanForFile($file) failed " +
          "(${t.javaClass.simpleName}: ${t.message}); returning empty"
      )
      emptySet()
    }
  }

  private fun collectPreviews(scanResult: ScanResult, file: Path): Set<PreviewInfoDto> {
    val sourceKey = file.toString()
    val basename = file.fileName?.toString().orEmpty()
    val results = LinkedHashSet<PreviewInfoDto>()
    for (classInfo in scanResult.allClasses) {
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
        if (direct.isNotEmpty()) {
          for (ann in direct) {
            results.add(toDto(classInfo, method, ann, sourceKey))
          }
          continue
        }
        for (ann in annotations) {
          val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
          for (resolvedAnn in resolved) {
            results.add(toDto(classInfo, method, resolvedAnn, sourceKey))
          }
        }
      }
    }
    return results
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
