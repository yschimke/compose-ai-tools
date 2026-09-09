package ee.schimke.composeai.plugin

import java.io.File

/**
 * Resolving the template designs a `ui-builder.policy.json` names, for both tasks that publish
 * them.
 *
 * ONE implementation on purpose. [DiscoverPreviewsTask] copies these designs beside the catalog it
 * writes and [BundlePreviewTask] packs them into the bundle, and the two carried
 * character-identical copies of this lookup — so every fix had to be made twice and, twice, was
 * not: the precedence rule landed in both, a containment check and a doc repair in only one. Two
 * lanes publishing the same policy's templates from two copies of the same rules is a disagreement
 * waiting for whichever fix gets forgotten next.
 */
internal object UiBuilderTemplateLookup {
  /** The one directory a `templates` path may live under, and the tree declared as a task input. */
  const val UI_BUILDER_DIR: String = "ui-builder"

  /**
   * The designs [paths] names, resolved to real files under [roots].
   *
   * Paths a policy cannot supply a file for are simply absent from the map; the caller reports them
   * rather than failing, because a catalog naming a template it does not ship is a mistake to tell
   * somebody about and not a reason to publish no catalog.
   *
   * [moduleOwnsPolicy] follows the location `authoredPair()` chose, and is not decoration. A nested
   * module with neither authored file falls back to the repository root's policy — and that
   * policy's `templates` name designs authored beside IT. Searching the module first would let a
   * module-local `ui-builder/designs/…`, kept for some other catalog, shadow the design the
   * selected policy actually owns, so the publish would carry bytes that policy never named, under
   * the name it did.
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
      // Only under `ui-builder/`, which is exactly the tree declared as these tasks' input. A path
      // outside it resolves to a real file Gradle is not watching, so editing that file would
      // invalidate nothing and an up-to-date or cached build would keep publishing stale bytes —
      // the input declaration and the lookup have to describe the same set or neither means
      // anything. Anything else is reported by the caller as unresolvable rather than read from
      // somewhere untracked.
      // Strictly beneath, and the directory itself is not a design: `ui-builder` as a path could
      // never resolve here anyway (the check below requires a file), so accepting it upstream only
      // produced a catalog naming a template nothing carries.
      .filter { it.startsWith("$UI_BUILDER_DIR/") && it != "$UI_BUILDER_DIR/" }
      .mapNotNull { path ->
        ordered.firstNotNullOfOrNull { root -> inside(root, path) }?.let { path to it }
      }
      .toMap()
  }

  /**
   * Publish nothing: remove the catalog at [out] AND the designs it advertised in [templateDir].
   *
   * Deleting only `ui-builder.json` was half of it. The designs live in a declared output directory
   * that Gradle does not empty between runs, so removing a policy left the copies behind in
   * `build/compose-previews/ui-builder` — where `compose-preview-server ui` reads them as ordinary
   * local design-library entries, owned by no policy and explained by nothing. The catalog and its
   * templates are published together and have to be withdrawn together.
   *
   * A plain function over two files so this is provable without a Gradle run; the task path around
   * it had no test at all, which is how the half of it that was missing stayed missing.
   */
  fun withdraw(out: File, templateDir: File) {
    if (out.exists()) out.delete()
    if (templateDir.exists()) templateDir.deleteRecursively()
    templateDir.mkdirs()
  }

  /**
   * [path] under `[root]/ui-builder`, or null when it resolves anywhere else.
   *
   * The prefix filter above is TEXTUAL, and `ui-builder/../catalog.spec.json` passes it — so
   * `File(root, path)` landed on a real file outside the declared input tree. That is the same
   * escape the bundle publisher's own path handling was fixed for one commit earlier, arriving here
   * through the checkout instead of through a bundle: untracked bytes copied into an output,
   * invalidating nothing when they change, and in the bundle lane written as a zip entry whose name
   * still says `ui-builder/…`. The prefix says what a path may look like; only canonicalising says
   * where it ends up.
   */
  private fun inside(root: File, path: String): File? {
    val base = File(root, UI_BUILDER_DIR).canonicalFile
    val target = File(root, path).canonicalFile
    val contained = target.path.startsWith(base.path + File.separator)
    return target.takeIf { contained && it.isFile }
  }
}
