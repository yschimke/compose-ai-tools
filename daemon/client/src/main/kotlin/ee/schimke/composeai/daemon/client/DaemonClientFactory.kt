package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.DaemonLaunchDescriptor
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * Pluggable spawn — production [SubprocessDaemonClientFactory] forks a JVM via [ProcessBuilder];
 * tests inject an in-memory factory that wires the [DaemonClient] to a fake daemon over piped
 * streams. The factory returns a [DaemonSpawn] that owns the underlying resource (subprocess or
 * coroutine).
 *
 * [workspaceId] identifies which workspace the daemon serves; with
 * [DaemonLaunchDescriptor.modulePath] it names the daemon in logs and keys test doubles. It is
 * deliberately *not* `:mcp`'s `RegisteredProject`: that type carries the MCP supervisor's own
 * registry (a mutable module list and a map of supervised daemons), and naming it here would put
 * the supervision model in the signature of every consumer that only wants to start a daemon —
 * including the render-session library, which built a throwaway one purely to satisfy this
 * parameter. Spawning needs the identity, not the registry. Narrowing it is what made this module
 * extractable at all (#4528).
 */
fun interface DaemonClientFactory {
  fun spawn(workspaceId: WorkspaceId, descriptor: DaemonLaunchDescriptor): DaemonSpawn
}

/**
 * Owns the resources behind a single live [DaemonClient]: the subprocess (in production) or the
 * fake daemon side of a piped pair (in tests). The owner calls [client] once after spawn to wire
 * the notification + close handlers.
 */
interface DaemonSpawn {
  val client: DaemonClient

  /**
   * Wires the owner's notification + close handlers onto the underlying [client]. Called exactly
   * once by `DaemonSupervisor.spawn` before any traffic flows. Implementations typically delay
   * creating the [client] until this call so the handlers are baked in from the first frame.
   */
  fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient

  fun shutdown()

  /**
   * Shuts down within [timeout] when the owner has a stricter lifecycle budget. Implementations
   * that do not own a subprocess retain their ordinary shutdown behaviour.
   */
  fun shutdown(timeout: Duration) = shutdown()
}
