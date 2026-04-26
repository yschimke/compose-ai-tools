@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

/**
 * Entry point for the preview daemon JVM — see docs/daemon/DESIGN.md § 4.
 *
 * The Gradle plugin's `composePreviewDaemonStart` task points its launch
 * descriptor at `ee.schimke.composeai.daemon.DaemonMain` (see
 * [`AndroidPreviewSupport.kt:974`](../../../../../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt#L974)),
 * which the JVM resolves via the file-level [JvmName] annotation above.
 *
 * Lifecycle:
 *
 * 1. Print a hello banner to stderr (free-form log per PROTOCOL.md § 1).
 * 2. Build a [DaemonHost] (B1.3 — holds the Robolectric sandbox open across
 *    renders).
 * 3. Build a [JsonRpcServer] (B1.5 — JSON-RPC 2.0 over stdio with LSP-style
 *    Content-Length framing).
 * 4. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit`
 *    or stdin closes; it calls `System.exit` itself.
 *
 * `args` is currently unused; future flags (e.g. `--detect-leaks=heavy`,
 * `--foreground`) will be parsed here.
 */
fun main(args: Array<String>) {
  System.err.println("compose-ai-tools daemon: hello (args=${args.toList()})")
  val host = DaemonHost()
  val server = JsonRpcServer(input = System.`in`, output = System.out, host = host)
  server.run()
}
