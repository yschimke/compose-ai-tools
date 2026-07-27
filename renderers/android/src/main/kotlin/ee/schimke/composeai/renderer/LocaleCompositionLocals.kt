package ee.schimke.composeai.renderer

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the locale from the preview's Robolectric [Configuration] into the locale composition
 * locals added in newer Compose UI releases.
 *
 * The renderer deliberately compiles against the Compose 1.9 compatibility BOM, while
 * `LocalLocaleList` and `LocalLocale` were added later. Resolve the providable backing local and
 * `LocaleList` reflectively so previews using a newer Compose UI can read both APIs without making
 * older consumers fail class loading. `LocalLocale` is computed from the first entry in
 * `LocalLocaleList`, so providing `LocalProvidableLocaleList` supports both public locals.
 *
 * Normally `AbstractComposeView` installs the platform locals through Compose UI's
 * `ProvideCommonCompositionLocals`. The preview renderer must also work when its 1.9-compiled
 * composition host runs preview bytecode built against 1.11, however. An audit of the Compose UI
 * platform locals added between those versions found this locale list as the only new
 * platform-derived public local. The other addition, runtime's `LocalRetainedValuesStore`, is
 * lifecycle state owned and installed by the composition host itself; synthesizing it here would
 * break retention semantics.
 *
 * Two failures are deliberately told apart, because they mean opposite things:
 * * **[Resolution.Absent]** — this classpath's Compose UI predates the locale locals. Expected on
 *   the compat BOM, so it stays silent, and it's cached: the miss then costs one lookup per class
 *   loader instead of a thrown `ClassNotFoundException` per composition.
 * * **anything else** — the symbols are there but couldn't be used (a renamed accessor, a changed
 *   `LocaleList` constructor, an unparseable language tag). That's a renderer bug or an upstream
 *   break, not a compatibility case, so it's reported on stderr rather than swallowed into a
 *   silently wrong default locale.
 */
object LocaleCompositionLocals {

  /** What one class loader's Compose UI offers, resolved once. See [resolve]. */
  private sealed interface Resolution {
    /** The locale locals exist here: the providable local, plus `LocaleList(String)`. */
    data class Bound(
      val local: ProvidableCompositionLocal<Any>,
      val localeListConstructor: Constructor<*>,
    ) : Resolution

    /** This Compose UI has no locale locals — the expected case on the 1.9 compat BOM. */
    data object Absent : Resolution
  }

  /**
   * Per-class-loader resolution cache. The daemon renders thousands of previews per process and
   * every composition asks for this, so neither the reflective lookup nor — in the far more common
   * [Resolution.Absent] case — the cost of *throwing* `ClassNotFoundException` should be paid per
   * render. Keyed by class loader because one daemon can host preview bytecode from several.
   */
  private val resolutions = ConcurrentHashMap<ClassLoader, Resolution>()

  /** Reported at most once per class loader, so a broken classpath says so without spamming. */
  private val reportedFailures = ConcurrentHashMap.newKeySet<ClassLoader>()

  private val defaultClassLoader: ClassLoader
    get() = LocaleCompositionLocals::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

  /**
   * `LocalProvidableLocaleList provides LocaleList(<tags>)` for [configuration], or null when this
   * Compose UI has no locale locals (or the bridge could not be built — see the class docs).
   */
  fun providedValue(
    configuration: Configuration,
    classLoader: ClassLoader = defaultClassLoader,
  ): ProvidedValue<*>? = providedValue(languageTags(configuration), classLoader)

  /**
   * The reflective half, split from the [Configuration] reading so a test can drive it directly.
   *
   * [findClass] is the seam a test substitutes to stand in a newer Compose UI: a stub *class
   * loader* can't do it, because `Class.forName` rejects a class whose name doesn't match the one
   * requested, and Robolectric's sandbox resolves `Class.forName` against its own loader anyway.
   * Production always uses the default, which is exactly what the daemon runs.
   */
  internal fun providedValue(
    languageTags: String,
    classLoader: ClassLoader,
    findClass: (String) -> Class<*> = { Class.forName(it, false, classLoader) },
  ): ProvidedValue<*>? {
    val bound =
      resolutions.computeIfAbsent(classLoader) { resolve(it, findClass) } as? Resolution.Bound
        ?: return null
    return try {
      bound.local provides bound.localeListConstructor.newInstance(languageTags)
    } catch (e: ReflectiveOperationException) {
      // The locals ARE present, so this is a real break rather than an old-Compose classpath.
      report(classLoader, "could not build LocaleList(\"$languageTags\")", e)
      null
    }
  }

  /**
   * Resolve in two steps, because the same exception means different things either side of the
   * line: **before** the local is found, a missing class or accessor is just an older Compose UI;
   * **after** it, the locale locals demonstrably exist, so anything that then fails to line up —
   * including a `LocaleList` without the `String` constructor — is a present-but-incompatible
   * classpath, which is exactly what must be reported rather than silently ignored.
   */
  private fun resolve(classLoader: ClassLoader, findClass: (String) -> Class<*>): Resolution {
    val local = findProvidableLocaleList(classLoader, findClass) ?: return Resolution.Absent
    return try {
      val localeListClass = findClass("androidx.compose.ui.text.intl.LocaleList")
      Resolution.Bound(local, localeListClass.getConstructor(String::class.java))
    } catch (e: ReflectiveOperationException) {
      report(classLoader, "LocalProvidableLocaleList found but LocaleList(String) is not usable", e)
      Resolution.Absent
    }
  }

  /** The providable local, or null when this Compose UI simply predates it (silent, expected). */
  @Suppress("UNCHECKED_CAST")
  private fun findProvidableLocaleList(
    classLoader: ClassLoader,
    findClass: (String) -> Class<*>,
  ): ProvidableCompositionLocal<Any>? =
    try {
      findClass("androidx.compose.ui.platform.CompositionLocalsKt")
        .getMethod("getLocalProvidableLocaleList")
        .invoke(null) as ProvidableCompositionLocal<Any>
    } catch (_: ClassNotFoundException) {
      // Compose UI older than the locale locals — the compat-BOM case. Nothing to say.
      null
    } catch (_: NoSuchMethodException) {
      // Same: the accessor arrived with the locals, so its absence means an older Compose UI.
      null
    } catch (e: ReflectiveOperationException) {
      // The class is there but the accessor won't yield a local — not a version gap.
      report(classLoader, "LocalProvidableLocaleList accessor is not usable", e)
      null
    }

  private fun report(classLoader: ClassLoader, what: String, e: Throwable) {
    if (!reportedFailures.add(classLoader)) return
    System.err.println(
      "Locale composition locals unavailable — previews render in the default locale ($what): " +
        "${e::class.java.simpleName}: ${e.message}"
    )
  }

  internal fun languageTags(configuration: Configuration): String {
    if (Build.VERSION.SDK_INT >= 24) {
      val locales =
        configuration.locales.takeUnless { it.isEmpty } ?: android.os.LocaleList.getDefault()
      return (0 until locales.size()).joinToString(",") { locales[it].toLanguageTag() }
    }
    @Suppress("DEPRECATION")
    return (configuration.locale ?: java.util.Locale.getDefault()).toLanguageTag()
  }
}
