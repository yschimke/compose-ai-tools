package ee.schimke.composeai.mcp

/**
 * Source-compatibility shim for the move of `DaemonLaunchDescriptor` to `:daemon:core`
 * (`ee.schimke.composeai.daemon`), so an external consumer that imports the old name keeps
 * compiling across the upgrade. See [ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor]
 * for why it moved.
 *
 * **What this does not preserve: binary compatibility.** A typealias resolves to the new class at
 * compile time, so `DaemonClientFactory.spawn`'s JVM signature now names
 * `ee/schimke/composeai/daemon/DaemonLaunchDescriptor`. A consumer *recompiled* against this
 * release is fine; an already-compiled implementation of `DaemonClientFactory` linked against the
 * old signature is not, and no shim in this package can change that — only keeping two live classes
 * could, which would reintroduce exactly the duplicate-schema problem the move exists to end.
 *
 * That trade is deliberate rather than careless. Per [docs/API_STABILITY.md](
 * ../../../../../../../docs/API_STABILITY.md) § 1, the published surface of `:mcp` is the MCP tool
 * names and input schemas (surface 8) — its Kotlin types are internal and may move. The wire format
 * this class parses, `daemon-launch.json`, is unchanged: same fields, same `schemaVersion`, same
 * tolerant `parse`. Consumers of the *format* see nothing at all.
 *
 * Remove in the next major.
 */
@Deprecated(
  "Moved to the published daemon-core contract; import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor.",
  ReplaceWith(
    "DaemonLaunchDescriptor",
    "ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor",
  ),
)
typealias DaemonLaunchDescriptor = ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
