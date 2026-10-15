package ee.schimke.composeai.renderer

/**
 * A stand-in for the Android daemon sidecar's one-shot renderer main, for
 * `AndroidRendererLauncherTest`: like the real one under Robolectric, it returns while a non-daemon
 * thread (the sandbox's main thread) is still alive, or fails when asked to.
 */
fun main(args: Array<String>) {
  if ("--fail" in args) error("render failed")
  Thread { Thread.sleep(600_000) }.apply { isDaemon = false }.start()
  println("rendered")
}
