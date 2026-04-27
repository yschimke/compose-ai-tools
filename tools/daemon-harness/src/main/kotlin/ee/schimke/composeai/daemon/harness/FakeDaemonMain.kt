@file:JvmName("FakeDaemonMain")

package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.JsonRpcServer
import java.io.File

/**
 * Tiny entry point for fake-mode harness daemons — see
 * [TEST-HARNESS § 8a](../../../docs/daemon/TEST-HARNESS.md#8a-the-fakehost-test-fixture).
 *
 * Reads its fixture directory from the `composeai.harness.fixtureDir` system property, loads
 * `previews.json`, wires `JsonRpcServer` onto a [FakeHost], and starts pumping JSON-RPC over
 * stdin/stdout — matching the wire shape VS Code drives in production. The harness's
 * `HarnessClient` spawns this `main` via `ProcessBuilder` and talks to it over
 * `Content-Length`-framed stdio.
 *
 * **Why a separate entry point** rather than embedding `JsonRpcServer` in-process? The whole point
 * of the harness is to exercise the protocol over a real subprocess (TEST-HARNESS § 1, goals): we
 * want stdio framing, OS-level lifecycle, exit codes, and stderr-buffering to be
 * *production-shaped*, not piped streams in the same JVM (that's [`JsonRpcServerIntegrationTest`]'s
 * job in core).
 */
fun main(args: Array<String>) {
  val fixtureProp =
    System.getProperty("composeai.harness.fixtureDir")
      ?: error(
        "FakeDaemonMain: -Dcomposeai.harness.fixtureDir=<path> is required (the directory " +
          "containing previews.json + per-preview PNG fixtures)"
      )
  val fixtureDir = File(fixtureProp)
  require(fixtureDir.isDirectory) {
    "FakeDaemonMain: fixture dir '$fixtureProp' does not exist or is not a directory"
  }
  val manifestFile = File(fixtureDir, "previews.json")
  val manifest = FakeHost.loadManifest(manifestFile)
  val host = FakeHost(fixtureDir = fixtureDir, manifest = manifest)
  val server =
    JsonRpcServer(
      input = System.`in`,
      output = System.out,
      host = host,
      daemonVersion = "harness-fake",
    )
  server.run()
}
