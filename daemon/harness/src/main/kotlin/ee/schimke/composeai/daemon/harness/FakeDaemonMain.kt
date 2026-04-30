@file:JvmName("FakeDaemonMain")

package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.IncrementalDiscovery
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.PreviewIndex
import ee.schimke.composeai.daemon.PreviewInfoDto
import java.io.File
import java.nio.file.Path

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
 *
 * **B2.2 phase 2.** The fake daemon also seeds the daemon-side [PreviewIndex] from the same fixture
 * manifest [FakeHost] reads, then wires an [IncrementalDiscovery] so `fileChanged({kind: source})`
 * actually exercises the discovery cascade end-to-end (cheap pre-filter → scoped scan → diff → emit
 * `discoveryUpdated`). The harness's classpath has no compiled `@Preview` classes, so `scanForFile`
 * returns empty and the diff carries `removed` for any preview whose `sourceFile` matches the saved
 * path — that's exactly what the S3 scenario test asserts.
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

  // B2.2 phase 2 — build a daemon-side PreviewIndex from the same fixture manifest. The fake-mode
  // `previews.json` shape is `[{...}]` rather than the plugin's `{previews: [...]}` envelope, so we
  // can't re-use [PreviewIndex.loadFromFile] directly — instead we wrap each [FakePreviewSpec] in a
  // minimal [PreviewInfoDto]. `sourceFile` is taken from the per-spec field when set so the diff
  // path's source-file scoping has something to anchor to.
  val previewIndex: PreviewIndex =
    if (manifest.isNotEmpty()) {
      val byId = LinkedHashMap<String, PreviewInfoDto>(manifest.size)
      for ((id, spec) in manifest) {
        byId[id] =
          PreviewInfoDto(
            id = id,
            className = spec.className.ifEmpty { "fake.${id.replaceFirstChar { it.uppercase() }}" },
            methodName = spec.functionName.ifEmpty { "Preview" },
            sourceFile = spec.sourceFile,
            displayName = spec.displayName,
            group = spec.group,
          )
      }
      System.err.println(
        "compose-ai-daemon harness: PreviewIndex seeded (previewCount=${byId.size})"
      )
      PreviewIndex.fromMap(path = manifestFile.toPath(), byId = byId)
    } else {
      PreviewIndex.empty()
    }

  val incrementalDiscovery: IncrementalDiscovery? =
    if (previewIndex.size > 0) {
      val classpath =
        (System.getProperty("java.class.path") ?: "")
          .split(File.pathSeparator)
          .filter { it.isNotBlank() }
          .map { Path.of(it) }
      System.err.println(
        "compose-ai-daemon harness: IncrementalDiscovery active " +
          "(classpath=${classpath.size}, previewCount=${previewIndex.size})"
      )
      IncrementalDiscovery(classpath = classpath)
    } else null

  val server =
    JsonRpcServer(
      input = System.`in`,
      output = System.out,
      host = host,
      daemonVersion = "harness-fake",
      previewIndex = previewIndex,
      incrementalDiscovery = incrementalDiscovery,
    )
  server.run()
}
