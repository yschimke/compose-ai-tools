package ee.schimke.composeai.discovery

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult

/**
 * Detects `@Preview` functions that install a `MaterialTheme` in their own body in a module that
 * declares `@ThemeCatalog` / `@WearThemeCatalog` providers. A theme catalog wraps the preview
 * (`Wrap(content)`, see `InvokeWithOptionalWrapper` in the daemon), so a theme installed inside the
 * body shadows it and every entry in the viewer's Theme select renders identically — as happened in
 * the Confetti catalogs.
 *
 * **A warning, never an error:** pinning a preview to one theme can be intentional.
 *
 * Bounded walk of the preview's bytecode: direct calls, then module-local methods (previews usually
 * call an app theme wrapper). Library code other than the theme entry points isn't followed.
 */
internal object PreviewThemeShadowing {

  /**
   * Owners of the `MaterialTheme(…)` composable (the `…Kt` facade). Not `MaterialTheme.colorScheme`
   * etc., which only read the theme.
   */
  private val THEME_INSTALL_OWNERS =
    setOf(
      "androidx.compose.material3.MaterialThemeKt",
      "androidx.compose.material.MaterialThemeKt",
      "androidx.wear.compose.material3.MaterialThemeKt",
      "androidx.wear.compose.material.MaterialThemeKt",
    )

  private const val THEME_INSTALL_METHOD = "MaterialTheme"

  /** Module-local hops to follow; real chains are 2–3 deep. Caps pathological call graphs. */
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
   * @param previewMethods the `@Preview` methods that produced previews, as (declaring class,
   *   method).
   * @param projectClassFqns FQNs from the module's own sources; the walk only recurses into these.
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
        // Advisory check: unreadable bytecode is skipped, as in PreviewTargetInference.
        null
      }
    chain?.let { Finding(classInfo.name, method.name, it) }
  }

  /** The call chain from [method] to a theme install, or `null` within [MAX_DEPTH] hops. */
  private fun walk(
    classInfo: ClassInfo,
    method: MethodInfo,
    scanResult: ScanResult,
    projectClassFqns: Set<String>,
    depth: Int,
    visited: MutableSet<String>,
  ): List<String>? {
    if (!visited.add("${classInfo.name}#${method.name}${method.typeDescriptorStr}")) return null
    // The inference walker already follows lambda bodies, so `AppTheme { … }` is seen from the
    // root.
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
   * Defaulted parameters make most call sites compile to `MaterialTheme$default`, so match that
   * too.
   */
  internal fun isThemeInstall(call: PreviewTargetInference.Invocation): Boolean =
    call.ownerFqn in THEME_INSTALL_OWNERS &&
      (call.methodName == THEME_INSTALL_METHOD ||
        call.methodName == "$THEME_INSTALL_METHOD\$default")

  /**
   * The warning for [findings], or `null`; [themeCount] explains why it matters for this module.
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
