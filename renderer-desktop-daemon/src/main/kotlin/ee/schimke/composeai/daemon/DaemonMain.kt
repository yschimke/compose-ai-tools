@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

/**
 * Entry point for the desktop preview daemon JVM — see docs/daemon/DESIGN.md § 4
 * ("Renderer-agnostic surface"). Mirrors `:renderer-android-daemon`'s [DaemonMain][
 * ee.schimke.composeai.daemon.DaemonMain] (B1.5) so a future `composePreviewDaemonStart` task that
 * picks the right `mainClass` per target doesn't have to special-case anything.
 *
 * Lifecycle (B-desktop.1.5):
 * 1. **Stdout reroute.** Stdout is the JSON-RPC channel — every byte is a framed message. Some
 *    libraries we don't fully control (kotlinx-coroutines bootstrap, Skiko native init, occasional
 *    `println` left over in third-party code) will write to `System.out` by default and corrupt the
 *    wire. Capture the real stdout into a local before swapping `System.out` to `System.err`, then
 *    hand the captured stream to [JsonRpcServer]. After this point any `System.out.println` lands
 *    on stderr (free-form log per [PROTOCOL.md § 1](../../../../../../docs/daemon/PROTOCOL.md)).
 * 2. **Print a hello banner to stderr** so `runDaemonMain` debugging ("did the JVM boot?") is
 *    obvious without sending bytes down the wire.
 * 3. Build a [DesktopHost] (B-desktop.1.3 + B-desktop.1.4 — holds the warm render thread + Compose
 *    runtime open across renders). Implements the renderer-agnostic [RenderHost] from
 *    `:renderer-daemon-core`.
 * 4. Build a [JsonRpcServer] (B1.5 — JSON-RPC 2.0 over stdio with LSP-style Content-Length
 *    framing). Lives in `:renderer-daemon-core`; binds to any [RenderHost] implementation.
 * 5. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit` or stdin closes; it
 *    calls `System.exit` itself.
 * 6. Defensive `host.shutdown(...)` in `finally` — `JsonRpcServer.run` already calls
 *    `host.shutdown()` on its `cleanShutdown` path, but if `run()` itself throws (e.g. an
 *    unrecoverable IO error) before reaching that, the host's render thread is still alive and a
 *    bare `System.exit` would skip its `try/finally` discipline. Calling `shutdown(timeoutMs =
 *    30_000)` here is idempotent and matches the renderer-android side.
 *
 * `args` is currently unused; future flags (e.g. `--detect-leaks=heavy`, `--foreground`) will be
 * parsed here.
 */
fun main(args: Array<String>) {
  // Capture the real stdout *before* swapping. Whatever uses `System.out` after this line lands on
  // stderr; the JSON-RPC channel is the captured `realOut`.
  val realOut = System.out
  System.setOut(System.err)

  System.err.println("compose-ai-tools desktop daemon: hello (args=${args.toList()})")

  val host: RenderHost = DesktopHost()
  val server = JsonRpcServer(input = System.`in`, output = realOut, host = host)
  try {
    server.run() // blocks until the client closes the wire
  } finally {
    // Idempotent — JsonRpcServer.cleanShutdown already calls this on the happy path.
    try {
      host.shutdown(timeoutMs = 30_000)
    } catch (t: Throwable) {
      System.err.println("compose-ai-tools desktop daemon: host.shutdown failed: ${t.message}")
    }
  }
}
