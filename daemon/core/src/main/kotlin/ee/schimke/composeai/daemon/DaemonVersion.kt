package ee.schimke.composeai.daemon

import java.util.Properties

/**
 * Daemon version baked into the jar by `generateDaemonVersionResource` in
 * [daemon/core/build.gradle.kts]. Surfaced via the `initialize` response's `daemonVersion` field so
 * VS Code's `[daemon] ready ...` line and remote-bug-report dumps record the actual release. Falls
 * back to `0.0.0-dev` when the resource is missing (e.g. running tests with a partial classpath);
 * production [DaemonMain] entry points pass [value] explicitly to [JsonRpcServer]'s constructor.
 *
 * Mirrors `gradle-plugin`'s [PluginVersion] / `cli`'s `BUNDLE_VERSION` — the version the build
 * baked in is the same one downstream artifacts publish, so embedders can reason about
 * compatibility without a separate compile-time literal.
 */
object DaemonVersion {
  val value: String by lazy {
    val stream =
      DaemonVersion::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/daemon/daemon-version.properties")
        ?: return@lazy "0.0.0-dev"
    val props = Properties()
    stream.use { props.load(it) }
    props.getProperty("version") ?: "0.0.0-dev"
  }
}
