package com.example.cmpwasmcatalog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.designcatalogm3.shared.ScreenDocumentRender
import ee.schimke.composeai.discovery.ScreenDocument

/**
 * Where a [ScreenDocument] is composed for the builder's preview pane.
 *
 * `internal`, like the rest of this app's helpers. Not a style choice: a **public** interface here
 * is an exported wasm-klib declaration, and the exporting checker
 * (`WasmKlibExportingDeclaration.collectDeclarations`) runs out of heap walking it during klib
 * serialization. This is an application, nothing outside consumes the seam, and `internal` keeps it
 * out of the exported set entirely.
 *
 * ### Why this is an interface and not a direct call to the renderer
 *
 * The browser can only compose the catalogs that reach `wasmJs`, which is M3 and not Wear:
 * `androidx.wear.compose` is Android-only, so an in-process pane is a mobile-M3 pane by
 * construction. Reaching an **Android** catalog means the composition runs somewhere that can host
 * it — the Robolectric daemon — and the browser shows streamed frames and sends pointer events
 * back, the way `wear-m3` already runs live on `preview.coo.ee`.
 *
 * Both sides take the **same document**. That is the whole point of the seam: the builder edits a
 * `ScreenDocument` — the same type `ScreenGenerator` generates from — and where it gets composed is
 * a deployment question, not a modelling one. Hard-wiring the in-process renderer would have made
 * the browser-only path structural, and unpicking it later means touching every part of the builder
 * rather than one binding.
 *
 * See [docs/design/UI_BUILDER_COMBINED.md](../../../../../../../docs/design/UI_BUILDER_COMBINED.md)
 * for how a streamed host reaches an assembled screen at all — it has no discovered `@Preview` for
 * a daemon to render, so it must first become code.
 */
internal interface ScreenPreviewHost {
  /** Compose (or display) [document] in the builder's preview pane. */
  @Composable fun Preview(document: ScreenDocument, modifier: Modifier)

  /**
   * What this host can draw, for the builder to say so rather than render a confusing blank.
   *
   * A host that cannot reach a catalog should be visibly unable to, not silently empty — the
   * failure a user hits is "I added a Wear component and nothing appeared", and that reads as a bug
   * in the component.
   */
  val label: String
}

/**
 * The in-process host: the M3 catalog composed directly in the browser.
 *
 * No daemon, no server, no round-trip — an edit is a recomposition. This is the fast path and the
 * default, and it stays correct with no network at all, which is why the builder must keep working
 * when no host is configured.
 */
internal object WasmCatalogPreviewHost : ScreenPreviewHost {
  override val label: String = "in-process (M3, wasm)"

  @Composable
  override fun Preview(document: ScreenDocument, modifier: Modifier) {
    ScreenDocumentRender(document, modifier)
  }
}
