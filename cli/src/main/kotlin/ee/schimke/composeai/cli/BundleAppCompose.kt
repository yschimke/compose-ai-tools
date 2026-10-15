package ee.schimke.composeai.cli

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * Whose Compose a one-shot Android `bundle render` runs: the daemon lane's decision
 * (`AppComposeSelection` in compose-preview-daemon's `:daemon:core`, compose-preview-daemon#288),
 * asked of the renderer's own jars so the two lanes cannot disagree.
 *
 * The daemon lane keeps the app's jars on a child loader that sends every `androidx.` lookup to the
 * daemon's, and, when the decision prefers the app (`auto`: the app's Compose is newer), installs a
 * parent that serves `androidx/` bytecode from the app's jars. The one-shot lane has one flat
 * classpath instead, on which the first copy of a class wins, so the same decision is applied as
 * the order of that classpath: the app's dependencies before the renderer's when the app's Compose
 * runs, after them when the daemon's does. Before this the app's always came first, so an app on an
 * older Compose than the renderer (Compose 1.7.5 against the daemon's 1.12.1) ran the renderer,
 * compiled against the newer one, on the older runtime: `NoSuchMethodError` / `IllegalAccessError`.
 *
 * The mode is the daemon's `composeai.daemon.appCompose` (`auto`, `app`, `daemon`; default `auto`),
 * or `bundle render --app-compose`.
 */
internal object BundleAppCompose {
  const val MODE_PROPERTY: String = "composeai.daemon.appCompose"
  const val DEFAULT_MODE: String = "auto"

  private const val SELECTION = "ee.schimke.composeai.daemon.AppComposeSelection"

  /** [preferApp] decides the classpath order; [logLine] is the daemon's own one-line account. */
  data class Decision(val preferApp: Boolean, val logLine: String)

  /**
   * The decision for an app classpath of [appEntries] against the renderer in [rendererJars]. When
   * those jars carry no `AppComposeSelection` (daemon < 3.18.0, or a `lib-renderer-android`
   * override without `:daemon:core`), a forced `app` or `daemon` mode is still applied as given and
   * `auto` returns null, which keeps the old order.
   */
  fun decide(
    rendererJars: List<File>,
    appEntries: List<File>,
    mode: String = System.getProperty(MODE_PROPERTY) ?: DEFAULT_MODE,
    selectionClass: String = SELECTION,
  ): Decision? {
    val jars = rendererJars.filter { it.isFile }
    if (jars.isEmpty()) return forced(mode)
    return URLClassLoader(
        jars.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
      .use { loader ->
        val type =
          try {
            loader.loadClass(selectionClass)
          } catch (_: ClassNotFoundException) {
            return forced(mode)
          }
        val decision =
          try {
            type
              .getMethod("decide", List::class.java, ClassLoader::class.java, String::class.java)
              .invoke(
                type.getField("INSTANCE").get(null),
                appEntries.map { it.toURI().toURL() },
                loader,
                mode,
              )
          } catch (e: InvocationTargetException) {
            throw e.targetException
          }
        Decision(
          preferApp = decision.javaClass.getMethod("getPreferApp").invoke(decision) as Boolean,
          logLine = decision.javaClass.getMethod("logLine").invoke(decision) as String,
        )
      }
  }

  /** A forced [mode] applied without the renderer's selection; null for `auto`. */
  private fun forced(mode: String): Decision? =
    when (mode.trim().lowercase()) {
      "app" -> Decision(preferApp = true, logLine = "[appCompose] mode=app runs=app reason=forced")
      "daemon" ->
        Decision(preferApp = false, logLine = "[appCompose] mode=daemon runs=daemon reason=forced")
      else -> null
    }

  /**
   * The flat render classpath: [leading] (launcher, config, resources, app classes) first and
   * [trailing] (`android.jar`) last; between them the app's dependency jars and the renderer's, in
   * the order [decision] picks. No decision keeps the app's first, the order before this existed.
   */
  fun classpath(
    decision: Decision?,
    leading: List<File>,
    appDependencies: List<File>,
    rendererJars: List<File>,
    trailing: List<File>,
  ): List<File> =
    leading +
      (if (decision == null || decision.preferApp) appDependencies + rendererJars
      else rendererJars + appDependencies) +
      trailing
}
