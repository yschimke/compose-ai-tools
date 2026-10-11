package com.example.cmpwasmcatalog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.designcatalogm3.shared.ScreenDocumentRender
import ee.schimke.composeai.discovery.ScreenDocument

/**
 * Where a [ScreenDocument] is composed for the builder's preview pane.
 *
 * `internal` because a public interface here is an exported wasm-klib declaration, and the
 * exporting checker runs out of heap walking it.
 *
 * An interface because the browser can only compose `wasmJs` catalogs (M3, not Wear); an Android
 * catalog must run on the Robolectric daemon with streamed frames. Both take the same document, so
 * where it's composed is a deployment question. See
 * [docs/design/UI_BUILDER_COMBINED.md](../../../../../../../docs/design/UI_BUILDER_COMBINED.md).
 */
internal interface ScreenPreviewHost {
  /** Compose (or display) [document] in the builder's preview pane. */
  @Composable fun Preview(document: ScreenDocument, modifier: Modifier)

  /**
   * What this host can draw, so the builder can say so instead of rendering a confusing blank.
   */
  val label: String
}

/**
 * The in-process host: the M3 catalog composed directly in the browser. An edit is a recomposition;
 * the default, and works with no network.
 */
internal object WasmCatalogPreviewHost : ScreenPreviewHost {
  override val label: String = "in-process (M3, wasm)"

  @Composable
  override fun Preview(document: ScreenDocument, modifier: Modifier) {
    ScreenDocumentRender(document, modifier)
  }
}
