package ee.schimke.composeai.plugin

import java.io.File

/**
 * Resolves the template designs a `ui-builder.policy.json` names, shared by [DiscoverPreviewsTask]
 * (copies them beside the catalog) and [BundlePreviewTask] (packs them), so fixes can't land in
 * only one.
 */
internal object UiBuilderTemplateLookup {
  /** The one directory a `templates` path may live under, and the tree declared as a task input. */
  const val UI_BUILDER_DIR: String = "ui-builder"

  /**
   * The designs [paths] names, resolved under [roots]. Unresolvable paths are absent; the caller
   * reports them rather than failing.
   *
   * [moduleOwnsPolicy] follows `authoredPair()`'s choice: a root policy's templates live beside it,
   * so searching the module first could let an unrelated module-local design shadow the one the
   * policy names.
   */
  fun resolve(
    roots: Iterable<File>,
    paths: List<String>,
    moduleOwnsPolicy: Boolean,
  ): Map<String, File> {
    if (paths.isEmpty()) return emptyMap()
    val ordered =
      roots.filter { it.isDirectory }.let { if (moduleOwnsPolicy) it else it.reversed() }
    return paths
      .distinct()
      // Only strictly under `ui-builder/`, the tree declared as the tasks' input; anything else
      // would be untracked bytes that never invalidate the build. The directory itself isn't a
      // design.
      .filter { it.startsWith("$UI_BUILDER_DIR/") && it != "$UI_BUILDER_DIR/" }
      .mapNotNull { path ->
        ordered.firstNotNullOfOrNull { root -> inside(root, path) }?.let { path to it }
      }
      .toMap()
  }

  /**
   * Publish nothing: remove the catalog at [out] and its designs in [templateDir]. Gradle doesn't
   * empty the declared output dir, so leftover designs would show up as orphaned local library
   * entries in `compose-preview-server ui`. A plain function so it's testable without Gradle.
   */
  fun withdraw(out: File, templateDir: File) {
    if (out.exists()) out.delete()
    if (templateDir.exists()) templateDir.deleteRecursively()
    templateDir.mkdirs()
  }

  /**
   * [path] under `[root]/ui-builder`, or null if it resolves elsewhere. The prefix filter is
   * textual (`ui-builder/../catalog.spec.json` passes it); only canonicalising shows where a path
   * really ends up.
   */
  private fun inside(root: File, path: String): File? {
    val base = File(root, UI_BUILDER_DIR).canonicalFile
    val target = File(root, path).canonicalFile
    val contained = target.path.startsWith(base.path + File.separator)
    return target.takeIf { contained && it.isFile }
  }
}
