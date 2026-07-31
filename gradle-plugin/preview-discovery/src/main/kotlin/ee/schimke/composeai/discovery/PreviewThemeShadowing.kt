package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult

/**
 * Detects `@Preview` functions that install a `MaterialTheme` in their **own body** in a module
 * that also declares `@ThemeCatalog` / `@WearThemeCatalog` providers — the combination that
 * silently kills the preview server's theme switcher.
 *
 * The two features disagree about composition order. A theme catalog is applied by *wrapping* the
 * preview function: the renderer resolves the provider and composes `Wrap(content)` around the
 * preview body ([`InvokeWithOptionalWrapper`] in the daemon). A theme installed *inside* the
 * preview body therefore composes within that wrapper and shadows it, so every entry in the
 * viewer's **Theme** select renders byte-identical pixels. The catalog looks like it has a live
 * theme axis and doesn't.
 *
 * This is not hypothetical: it is exactly what shipped in `confetti-wear` and `confetti-mobile`,
 * where all five conference themes rendered the same PNG because every catalog preview opened with
 * `ConfettiThemeFixed { … }`. Nothing in the pipeline noticed, because the synthetic per-theme
 * specimen sheets ([PreviewKind.THEME_CATALOG]) wrap a *canned* grid rather than an app preview and
 * so kept differing correctly.
 *
 * **Warning, never an error.** Pinning a preview to one theme is legitimate — a per-identity "theme
 * foundation" sticker documents exactly one theme on purpose, and should stay pinned. The check
 * can't tell that apart from the bug, so it reports and lets the author decide.
 *
 * Detection is a bounded walk of the preview's bytecode: direct calls first, then into the module's
 * own methods (a preview almost never calls `MaterialTheme` directly — it calls the app's theme
 * wrapper, which calls it). Library code other than the theme entry points themselves is not
 * followed, so the walk stays cheap and can't wander into Compose internals.
 */
internal object PreviewThemeShadowing {

  /**
   * Owners of the `MaterialTheme(…)` *composable* — the call that installs a theme. Kotlin compiles
   * a top-level composable into a `…Kt` facade, so `MaterialTheme { }` in `material3` becomes
   * `androidx.compose.material3.MaterialThemeKt.MaterialTheme(…)`.
   *
   * Deliberately NOT matched: `MaterialTheme.colorScheme` and friends, which *read* the ambient
   * theme and compile to `androidx.compose.material3.MaterialTheme.getColorScheme(…)` — owner
   * without the `Kt` facade suffix, and a different method name. Reading the theme is what a
   * well-behaved preview body does; only installing one shadows the wrapper.
   */
  private val THEME_INSTALL_OWNERS =
    setOf(
      "androidx.compose.material3.MaterialThemeKt",
      "androidx.compose.material.MaterialThemeKt",
      "androidx.wear.compose.material3.MaterialThemeKt",
      "androidx.wear.compose.material.MaterialThemeKt",
    )

  private const val THEME_INSTALL_METHOD = "MaterialTheme"

  /**
   * How many module-local hops to follow from the preview body. Real chains are short —
   * `SessionCardPopulatedPreview → ConfettiThemeFixed → MaterialTheme` is two, and the deepest in
   * Confetti (`…Preview → ConfettiPreviewScaffold → ConfettiThemeFixed → MaterialTheme`) is three.
   * The cap keeps a pathological call graph from turning discovery into a whole-program analysis.
   */
  private const val MAX_DEPTH = 6

  /** One preview whose body installs a theme, with the call chain that gets there. */
  internal data class Finding(
    val classFqn: String,
    val methodName: String,
    /** Human-readable hops from the preview to the `MaterialTheme` call, nearest first. */
    val chain: List<String>,
  ) {
    /** `com.example.FooKt.BarPreview (via AppTheme → MaterialTheme)` */
    fun describe(): String = "$classFqn.$methodName (via ${chain.joinToString(" → ")})"
  }

  /**
   * @param previewMethods the `@Preview` methods discovery actually produced previews from, as
   *   (declaring class, method) pairs.
   * @param projectClassFqns FQNs compiled from the module's own sources; the walk only recurses
   *   into these, never into dependency JARs.
   */
  internal fun detect(
    previewMethods: List<Pair<ClassInfo, MethodInfo>>,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
  ): List<Finding> = previewMethods.mapNotNull { (classInfo, method) ->
    val chain =
      try {
        walk(classInfo, method, scanResult, projectClassFqns, depth = 0, visited = mutableSetOf())
      } catch (_: Throwable) {
        // Bytecode we can't read is not worth failing discovery over — the whole check is
        // advisory. Same posture as PreviewTargetInference's own extractCalls guard.
        null
      }
    chain?.let { Finding(classInfo.name, method.name, it) }
  }

  /**
   * Returns the chain of call names from [method] down to a theme install, or `null` if this method
   * doesn't reach one within [MAX_DEPTH] module-local hops.
   */
  private fun walk(
    classInfo: ClassInfo,
    method: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
    depth: Int,
    visited: MutableSet<String>,
  ): List<String>? {
    if (!visited.add("${classInfo.name}#${method.name}${method.typeDescriptorStr}")) return null
    // Reuses the inference walker: it already follows the method's own lambda bodies, so a theme
    // installed as `AppTheme { … }` inside the preview is seen from the preview's own root method.
    val calls = PreviewTargetInference.extractCalls(classInfo, method)

    if (calls.any(::isThemeInstall)) return listOf(THEME_INSTALL_METHOD)
    if (depth >= MAX_DEPTH) return null

    for (call in calls) {
      if (call.ownerFqn !in projectClassFqns) continue
      val targetClass = scanResult.getClassInfo(call.ownerFqn) ?: continue
      val targetMethod =
        targetClass.methodInfo.firstOrNull {
          it.name == call.methodName && it.typeDescriptorStr == call.descriptor
        } ?: continue
      val deeper = walk(targetClass, targetMethod, scanResult, projectClassFqns, depth + 1, visited)
      if (deeper != null) return listOf(call.methodName) + deeper
    }
    return null
  }

  /**
   * `MaterialTheme` takes defaulted parameters (`colorScheme`, `typography`, `shapes`), so all but
   * the fully-specified call site compiles to the synthetic `MaterialTheme$default` bridge rather
   * than `MaterialTheme` itself — and `AppTheme { … }` passing only `content` is by far the common
   * shape. Matching the bare name alone would miss nearly every real occurrence.
   */
  internal fun isThemeInstall(call: PreviewTargetInference.Invocation): Boolean =
    call.ownerFqn in THEME_INSTALL_OWNERS &&
      (call.methodName == THEME_INSTALL_METHOD ||
        call.methodName == "$THEME_INSTALL_METHOD\$default")

  /**
   * The discovery warning for [findings], or `null` when there is nothing to say. [themeCount] is
   * how many theme providers the module declares — quoted back so the message explains *why* this
   * matters for this module specifically.
   */
  internal fun warningOrNull(findings: List<Finding>, themeCount: Int): String? {
    if (findings.isEmpty() || themeCount == 0) return null
    return buildString {
      append("composePreviewDiscover: ")
      append(findings.size)
      append(" @Preview function(s) install a theme in their own body, while this module declares ")
      append(themeCount)
      append(" @ThemeCatalog/@WearThemeCatalog provider(s).\n")
      append(
        "  A theme catalog is applied by WRAPPING the preview, so a theme installed inside the " +
          "preview body composes within that wrapper and shadows it — the viewer's Theme select " +
          "will render identical pixels for these previews:\n"
      )
      findings.take(10).forEach { append("    - ").append(it.describe()).append('\n') }
      if (findings.size > 10) append("    (+").append(findings.size - 10).append(" more)\n")
      append(
        "  Fix: declare the theme with `@PreviewWrapper(SomeThemeCatalog::class)` on the preview " +
          "instead of calling it in the body, or have the app's theme stand down when an override " +
          "is already installed. Pinning a preview to one theme on purpose (a per-theme specimen " +
          "sheet) is fine — this is a warning, not an error."
      )
    }
  }
}
