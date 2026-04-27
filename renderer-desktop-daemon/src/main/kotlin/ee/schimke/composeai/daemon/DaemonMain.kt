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
 * 5. **Install a SIGTERM shutdown hook** (B-desktop.1.6) that closes stdin to nudge the read loop
 *    out of `read()` and calls `host.shutdown(timeoutMs)` so the in-flight render drains before the
 *    JVM exits. Mirrors the no-mid-render-cancellation enforcement listed in
 *    [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement).
 * 6. [JsonRpcServer.run] blocks until the client sends `shutdown` + `exit` or stdin closes; it
 *    calls `System.exit` itself.
 * 7. Defensive `host.shutdown(...)` in `finally` — `JsonRpcServer.run` already calls
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

  installSigtermShutdownHook(host, originalStdin = System.`in`)

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

/**
 * Installs the SIGTERM shutdown hook that enforces the no-mid-render-cancellation invariant from
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)
 * (B-desktop.1.6).
 *
 * **What the hook does, in order**, when SIGTERM arrives (or any other JVM-shutdown trigger fires:
 * `System.exit`, last non-daemon thread exiting, `Ctrl-C` on a foreground process, etc.):
 *
 * 1. Closes [originalStdin]. The [JsonRpcServer.run] read loop is blocked in
 *    `InputStream.read(...)`; closing the stream surfaces an `IOException` / EOF and the loop
 *    breaks. This is option (a) from the B-desktop.1.6 task brief — "close `System.in` from the
 *    shutdown hook so the loop sees EOF" — chosen over adding a `requestStop()` API to
 *    `:renderer-daemon-core` because it doesn't widen the core surface. The trade-off is the read
 *    loop still walks through its own EOF→idle-timeout path before reaching `cleanShutdown`; the
 *    drain we care about (host render thread) is handled by step 2 below, independently.
 * 2. Calls [RenderHost.shutdown] with the timeout from `composeai.daemon.idleTimeoutMs` (capped at
 *    the JVM's default 30s shutdown-hook grace window — JVMs kill non-daemon hooks that exceed
 *    this). [DesktopHost.shutdown] enqueues a poison pill on the render queue and joins the worker
 *    thread, so an in-flight `RenderEngine.render` finishes (including its `try/finally`
 *    `scene.close()` from B-desktop.1.4) before the JVM proceeds with exit.
 *
 * **The crucial difference from the JSON-RPC `shutdown` request path.** `JsonRpcServer.shutdown`
 * already drains the in-flight queue before resolving (PROTOCOL.md § 3). That handler runs on the
 * read thread. The SIGTERM hook runs on a JVM-owned shutdown thread *concurrently with* the read
 * thread — so the host gets two `shutdown()` calls (one from the hook, one from `cleanShutdown` on
 * the read thread's EOF path). [DesktopHost.shutdown] is idempotent, so this is fine; the second
 * call observes the worker already gone and returns immediately.
 *
 * **What we cannot defend against.** SIGKILL (`kill -9`) bypasses shutdown hooks entirely — the
 * kernel kills the JVM mid-syscall. An in-flight `ImageComposeScene` will leak its Skia native
 * `Surface` (and the JVM's classloader graph), but the daemon process is also gone, so the leak
 * doesn't span renders. There is nothing we can do about this in user code; the only mitigation is
 * the gradle plugin / VS Code client preferring SIGTERM over SIGKILL for routine daemon disposal.
 *
 * **Manual smoke test.** Run `./gradlew :renderer-desktop-daemon:runDaemonMain` in one terminal,
 * note the PID printed in the hello banner, and `kill -TERM <pid>` from another terminal. The
 * hook's "draining…" line lands on stderr, [DesktopHost.shutdown] returns once the worker is gone,
 * and the JVM exits within ~1s for an idle daemon (the time taken by `host.shutdown()` plus the
 * read loop's EOF→idleTimeout-sleep walk; the latter is bounded by the
 * `composeai.daemon.idleTimeoutMs` system property, default 5s).
 */
private fun installSigtermShutdownHook(host: RenderHost, originalStdin: java.io.InputStream) {
  // Same property the JsonRpcServer reads, so a single sysprop tunes both timeouts coherently.
  // Default 30s — matches the existing `finally`-block defensive shutdown above and most JVMs'
  // shutdown-hook grace window.
  val timeoutMs =
    System.getProperty(JsonRpcServer.IDLE_TIMEOUT_PROP)?.toLongOrNull()?.coerceAtMost(30_000L)
      ?: 30_000L

  Runtime.getRuntime()
    .addShutdownHook(
      Thread(
        {
          System.err.println(
            "compose-ai-tools desktop daemon: SIGTERM received, draining in-flight renders " +
              "(timeoutMs=$timeoutMs)"
          )
          // Close stdin to break the read loop out of its blocking read() — same effect as the
          // client closing the wire, which JsonRpcServer.readLoop already handles. Best-effort:
          // if the stream is already closed (or we're being called twice), swallow.
          try {
            originalStdin.close()
          } catch (_: Throwable) {
            // ignore — we just want the read loop to stop reading new requests.
          }
          try {
            host.shutdown(timeoutMs = timeoutMs)
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-tools desktop daemon: SIGTERM hook host.shutdown failed: ${t.message}"
            )
          }
          System.err.println("compose-ai-tools desktop daemon: drain complete, JVM exiting")
        },
        "compose-ai-daemon-sigterm-hook",
      )
    )
}
