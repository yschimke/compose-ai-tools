package ee.schimke.composeai.renderer

import android.app.Activity
import android.content.Context

/**
 * The Android theme the preview host activity runs under.
 *
 * Compose doesn't read the platform theme — it resolves colour and type from `MaterialTheme`. A
 * platform `View` does, and that is what makes this matter: a `@Preview` whose body is an
 * `AndroidView` inflates (or styles) a real `View`, and a `View` resolves its default style through
 * *theme attributes*. When the attribute is app-owned — `?attr/primary_text_01` in a
 * `TextAppearance` style, say — and the host activity's theme has never heard of it, the resolve
 * fails hard:
 * ```
 * UnsupportedOperationException: Failed to resolve attribute at index 3: TypedValue{t=0x2/d=0x7f0405b0 …}
 * ```
 *
 * (`t=0x2` is TYPE_ATTRIBUTE — an attribute reference nothing resolved; `0x7f…` is the app's own
 * resource package.) That exception escapes composition, so the render aborts, no
 * `previews/<id>.png` is written, and the design-artifacts export drops the component from the
 * candidate join as "no static PNG" — issues #2946 / #2957.
 *
 * ### Resolution order
 * 1. **`composeai.render.hostTheme`** — an explicitly named theme, e.g. `@style/Theme.Foo`,
 *    `com.example:style/Theme.Foo`, or a bare `Theme.Foo`. Set it through the Gradle plugin
 *    (`-PcomposePreview.hostTheme=…` / `-Dcomposeai.render.hostTheme=…`), which forwards it to both
 *    the batch render JVM and the daemon.
 * 2. **`<application android:theme>`** from the merged manifest.
 * 3. Nothing — the activity keeps the platform default.
 *
 * Step 2 is what Robolectric already gives an **application** module for free, and it's why an app
 * module's `AndroidView` previews generally work today. Step 1 exists for the case that doesn't: a
 * **library** module. A library's merged manifest has no `<application android:theme>` at all, so
 * there is no app theme to inherit — yet its previews routinely style platform views with
 * attributes that only the app's theme defines. The renderer cannot guess which of a design
 * system's themes to use, so the consumer names one. That mirrors Android Studio's preview pane,
 * where the theme picker exists for exactly this reason.
 */
object PreviewHostTheme {

  /**
   * System property naming the host theme. Forwarded into the render / daemon JVM by the Gradle
   * plugin (`AndroidPreviewClasspath.buildSystemProperties`), so setting it on the Gradle
   * invocation reaches the JVM that reads it.
   */
  const val HOST_THEME_PROPERTY: String = "composeai.render.hostTheme"

  /**
   * The theme resource id to host previews under, or `0` when neither the property nor the manifest
   * names one.
   *
   * A configured name that doesn't resolve returns `0` (and the manifest theme is *not* consulted):
   * silently falling back would hide the typo that made a whole module's `AndroidView` previews
   * blow up, which is the failure mode this exists to end. [describeUnresolved] gives the caller a
   * message to surface instead.
   */
  fun resolveThemeResId(context: Context): Int {
    val configured = configuredThemeName()
    if (configured != null) return resolveByName(context, configured)
    return runCatching { context.applicationInfo?.theme ?: 0 }.getOrDefault(0)
  }

  /**
   * A one-line explanation when a configured [HOST_THEME_PROPERTY] didn't resolve, else `null`.
   * Callers log this once per render so a mistyped theme name is visible rather than silently
   * inert.
   */
  fun describeUnresolved(context: Context): String? {
    val configured = configuredThemeName() ?: return null
    if (resolveByName(context, configured) != 0) return null
    return "compose-ai: $HOST_THEME_PROPERTY=\"$configured\" did not resolve to a style in " +
      "${context.packageName}'s resources — previews keep the platform default theme, so an " +
      "AndroidView styled through an app-owned ?attr/… may still fail to render."
  }

  /**
   * Applies [resolveThemeResId] to [activity] and returns the resource id that was applied, or `0`
   * when there was nothing to apply.
   *
   * Called after the activity is already resumed (the render rule owns its lifecycle), which is
   * fine: `Activity.setTheme` re-applies onto the live `Resources.Theme` with `force = true`, so
   * every view created afterwards — the `ComposeView` the rule installs and any `AndroidView` child
   * inside it — resolves against the app's attributes. The window background is repainted from the
   * preview's own background colour immediately after, so a theme's `android:windowBackground`
   * can't leak into the capture.
   */
  fun applyTo(activity: Activity): Int {
    val themeResId = resolveThemeResId(activity)
    if (themeResId == 0) {
      // Once per JVM: a mistyped theme name would otherwise be silently inert, and the only
      // symptom would be the render failure it was configured to prevent.
      if (!warnedUnresolved) {
        describeUnresolved(activity)?.let {
          warnedUnresolved = true
          System.err.println(it)
        }
      }
      return 0
    }
    return runCatching {
        activity.setTheme(themeResId)
        themeResId
      }
      .getOrDefault(0)
  }

  /** Guards the one-shot unresolved-theme warning in [applyTo]. Reset by tests. */
  @Volatile internal var warnedUnresolved: Boolean = false

  /** The raw configured theme name, trimmed, or `null` when unset / blank. */
  private fun configuredThemeName(): String? =
    System.getProperty(HOST_THEME_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }

  /**
   * Resolves `@style/Foo`, `pkg:style/Foo`, `@pkg:style/Foo` or a bare `Foo` against [context]'s
   * resource table, returning `0` when it names nothing.
   *
   * `Resources.getIdentifier` is the only lookup available here — the renderer never sees the
   * consumer's generated `R` class — and a bare name is resolved in the app's own package, which is
   * where a consumer's themes live.
   */
  private fun resolveByName(context: Context, name: String): Int {
    val cleaned = name.removePrefix("@")
    val (pkg, entry) =
      if (cleaned.contains(':')) cleaned.substringBefore(':') to cleaned.substringAfter(':')
      else context.packageName to cleaned
    val (type, styleName) =
      if (entry.contains('/')) entry.substringBefore('/') to entry.substringAfter('/')
      else "style" to entry
    return runCatching { context.resources.getIdentifier(styleName, type, pkg) }.getOrDefault(0)
  }
}
