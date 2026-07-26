package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Reads `androidx.wear.compose.material3.MaterialTheme` by reflection, so the renderer can draw a
 * `@WearThemeCatalog` specimen without a compile-time dependency on
 * `androidx.wear.compose:compose-material3` (which it only has as `testImplementation`).
 *
 * The reflection isn't just about the build dependency — it's about *identity*. The daemon loads
 * the user's module on a child classloader ([UserClassLoaderHolder]); a specimen compiled against
 * the renderer's own wear-compose would read a different `CompositionLocal` instance than the one
 * the app's `MaterialTheme` provided, and get defaults back with no error. Resolving through the
 * provider's own loader is what makes the read see the theme that's actually installed. Same
 * reasoning, and the same shape, as [WearReduceMotionLocal].
 *
 * Two details of the Wear API make this workable:
 * - `MaterialTheme.getColorScheme(Composer, int)` / `getTypography(Composer, int)` are **public**
 *   methods taking the composer directly, so they can be invoked with `currentComposer` the same
 *   way [PreviewRenderStrategy]'s default strategy invokes a consumer `@Composable`. No
 *   `getDeclaredComposableMethod` lookup needed.
 * - The values cross the loader boundary cleanly. `Color` is a `@JvmInline value class`, so its
 *   getters are name-mangled (`getPrimary-0d7_KjU()`) and return a raw `long` we re-wrap into
 *   *our* [Color]. `TextStyle` lives in `androidx.compose.ui.text`, shared via the parent loader,
 *   so those getters return a directly usable instance.
 *
 * Every lookup is best-effort: a module without wear-compose on its classpath (or a future release
 * that renames these members) yields null, and the caller falls back rather than failing the
 * render.
 */
internal object WearMaterialTheme {

  private const val MATERIAL_THEME = "androidx.wear.compose.material3.MaterialTheme"

  /** The Wear `ColorScheme` currently in composition, as an opaque handle, or null. */
  @Composable
  fun colorSchemeOrNull(loader: ClassLoader?): Any? = readTheme(loader, "getColorScheme")

  /** The Wear `Typography` currently in composition, as an opaque handle, or null. */
  @Composable
  fun typographyOrNull(loader: ClassLoader?): Any? = readTheme(loader, "getTypography")

  /**
   * Invoke `MaterialTheme.<getter>(composer, 0)` reflectively. The `0` is the composable calling
   * convention's `$changed` mask — these getters are read-only `LocalX.current` reads, so no
   * change bits apply.
   */
  @Composable
  private fun readTheme(loader: ClassLoader?, getter: String): Any? {
    // Read the composer in composable scope; `runCatching`'s lambda is not itself @Composable, so
    // `currentComposer` can't be touched inside it.
    val composer = currentComposer
    return runCatching {
        val clazz =
          Class.forName(MATERIAL_THEME, false, loader ?: WearMaterialTheme::class.java.classLoader)
        val instance = clazz.getField("INSTANCE").get(null)
        val method =
          clazz.getMethod(
            getter,
            Class.forName(
              "androidx.compose.runtime.Composer",
              false,
              WearMaterialTheme::class.java.classLoader,
            ),
            Int::class.javaPrimitiveType,
          )
        method.invoke(instance, composer, 0)
      }
      .getOrNull()
  }

  /**
   * Read a colour role off a Wear `ColorScheme` handle. Kotlin mangles a value-class-returning
   * getter's JVM name with a stable-per-signature hash (`getPrimary-0d7_KjU`), so match on the
   * `get<Role>` prefix rather than an exact name — the suffix is an implementation detail we must
   * not hard-code. Returns null when the role doesn't exist in this wear-compose version.
   */
  fun role(scheme: Any?, name: String): Color? {
    if (scheme == null) return null
    val getter = "get${name.replaceFirstChar { it.uppercase() }}"
    return runCatching {
        val method =
          scheme.javaClass.methods.firstOrNull {
            (it.name == getter || it.name.startsWith("$getter-")) &&
              it.parameterCount == 0 &&
              it.returnType == Long::class.javaPrimitiveType
          } ?: return null
        Color((method.invoke(scheme) as Long).toULong())
      }
      .getOrNull()
  }

  /** Read a type-scale style off a Wear `Typography` handle. `TextStyle` is parent-loader shared. */
  fun style(typography: Any?, name: String): TextStyle? {
    if (typography == null) return null
    val getter = "get${name.replaceFirstChar { it.uppercase() }}"
    return runCatching { typography.javaClass.getMethod(getter).invoke(typography) as? TextStyle }
      .getOrNull()
  }
}
