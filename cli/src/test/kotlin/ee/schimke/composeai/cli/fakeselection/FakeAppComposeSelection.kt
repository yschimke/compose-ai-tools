package ee.schimke.composeai.cli.fakeselection

import java.net.URL

/**
 * Stands in for compose-preview-daemon's `AppComposeSelection` in `BundleAppComposeTest`, with the
 * same JVM shape `BundleAppCompose` calls: `INSTANCE.decide(List<URL>, ClassLoader, String)`
 * returning an object with `getPreferApp()` and `logLine()`. Prefers the app when the mode is
 * `app`, or when the first app entry's name contains "newer".
 */
object FakeAppComposeSelection {
  class Decision(val preferApp: Boolean, private val line: String) {
    fun logLine(): String = line
  }

  fun decide(userUrls: List<URL>, daemonLoader: ClassLoader, mode: String): Decision {
    val app = mode == "app" || userUrls.firstOrNull()?.path?.contains("newer") == true
    return Decision(app, "fake mode=$mode runs=${if (app) "app" else "daemon"}")
  }
}
