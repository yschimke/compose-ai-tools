@file:JvmName("AndroidRendererLauncher")

package ee.schimke.composeai.cli.androidrender

import kotlin.system.exitProcess

/**
 * The main class of `bundle render`'s Android subprocess: runs the one-shot renderer's own main
 * (`AndroidRendererMainKt`, from the Android daemon sidecar), then exits.
 *
 * That main returns without exiting when every preview rendered, and Robolectric's sandbox main
 * thread (`SDK <n> Main Thread`) is not a daemon thread, so the JVM would idle until
 * [ee.schimke.composeai.cli.BundleRenderer]'s timeout and every preview would be reported failed.
 * The renderer's own non-zero `exitProcess` on a failure still wins. Daemon 3.18.1 exits on its own
 * (compose-preview-daemon#292); this stays for older renderers, including a
 * `-Dcomposeai.cli.libRendererAndroidDir` override.
 *
 * Runs on the renderer's classpath, not the CLI's: [ee.schimke.composeai.cli.BundleRenderer] copies
 * this one class file into the subprocess's classpath. So it may use only the JDK and the Kotlin
 * standard library the renderer already carries, and nothing else from the CLI.
 */
fun main(args: Array<String>) {
  val status =
    try {
      Class.forName(RENDERER_MAIN)
        .getMethod("main", Array<String>::class.java)
        .invoke(null, args as Any)
      0
    } catch (e: java.lang.reflect.InvocationTargetException) {
      e.targetException.printStackTrace()
      1
    } catch (e: Throwable) {
      e.printStackTrace()
      1
    }
  System.out.flush()
  System.err.flush()
  exitProcess(status)
}

private const val RENDERER_MAIN = "ee.schimke.composeai.renderer.AndroidRendererMainKt"
