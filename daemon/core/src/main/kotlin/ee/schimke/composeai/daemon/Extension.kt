package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor

/**
 * A daemon extension. Bundles every contribution one feature makes to the daemon's wire surface so
 * a single id can flip the whole thing on or off at runtime.
 *
 * Daemons start with an [ExtensionRegistry] holding every registered extension in the **inactive**
 * state — registered only as metadata. Until a client (today, the MCP supervisor) calls
 * `extensions/enable`, none of the contributions below appear in JSON-RPC responses or run during
 * renders. This is how the daemon defaults to "almost no extensions" without losing the descriptive
 * surface clients need to discover what's available.
 *
 * `dataProductRegistry` and `previewOverrideExtensions` are the **executing** halves: they snapshot
 * render results, observe interactive sessions, and wrap previews. The descriptor lists are pure
 * metadata: clients consult them via `initialize.capabilities` and `extensions/list` but the daemon
 * doesn't act on them by itself.
 *
 * **Dependencies** name other extensions whose execution side this one relies on. When extension A
 * is publicly enabled and declares `dependencies = listOf("data/theme")`, the registry brings the
 * theme extension online so its `onRender` can run and feed A — but theme's data-product kinds and
 * descriptors stay invisible to clients (they don't show up in `extensions/list`'s public set, and
 * `data/fetch` against them returns `Unknown`). This matches the rule "dependencies don't
 * contribute to responses directly, just via the extension that depends on them."
 *
 * IDs are free-form short strings — `data/theme`, `data/recomposition`, `override/wallpaper`. Match
 * the `kind` namespace where a single registry advertises a single kind (so the id reads naturally
 * in `extensions/enable` calls); use a feature-shaped id when one extension carries several kinds
 * or pure metadata.
 */
data class Extension(
  val id: String,
  val displayName: String = id,
  val dependencies: List<String> = emptyList(),
  val dataProductRegistry: DataProductRegistry? = null,
  val dataExtensionDescriptors: List<DataExtensionDescriptor> = emptyList(),
  val previewExtensionDescriptors: List<PreviewExtensionDescriptor> = emptyList(),
  val previewOverrideExtensions: List<PreviewOverrideExtension> = emptyList(),
) {
  init {
    require(id.isNotBlank()) { "Extension id must not be blank" }
    require(id !in dependencies) { "Extension '$id' must not depend on itself" }
  }

  /** Capabilities this extension advertises when active. Empty for descriptor-only extensions. */
  val dataProductCapabilities: List<DataProductCapability>
    get() = dataProductRegistry?.capabilities ?: emptyList()
}
