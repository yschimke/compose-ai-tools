package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.recomposition.RecompositionProduct
import kotlinx.serialization.json.JsonElement

/**
 * Android-side stub for the `compose/recomposition` kind.
 *
 * **Why a stub?** The real producer ([data-recomposition-connector]'s
 * `RecompositionDataProductRegistry`) is desktop-only today — it installs a
 * `CompositionObserver` reflectively against the held `ImageComposeScene`'s
 * `Recomposer`, and the Compose Multiplatform `compose.ui` dep that brings in
 * `ImageComposeScene` conflicts with the Android daemon's `androidx.compose.ui`
 * graph. On Android the equivalent observer install needs to run **inside** the
 * Robolectric sandbox classloader and bridge counters back to the host through
 * a primitive-only seam — a substantial cross-cutting change to the
 * `daemon.bridge` surface that we are staging separately.
 *
 * **What this stub does.** Advertises the same `compose/recomposition`
 * capability the real producer does so:
 *
 * - `initialize.capabilities.dataProducts` includes the kind on Android.
 * - `data/subscribe` no longer fails with `-32020 kind not advertised`.
 * - The panel's Performance bundle stops surfacing the subscribe-failed log
 *   line every time the user toggles the chip on a Wear / Android preview.
 *
 * Fetches resolve to [DataProductRegistry.Outcome.NotAvailable] and the
 * attachments list is always empty: the panel sees an honest "advertised but
 * no data yet" state until the in-sandbox observer lands. Snapshot mode does
 * **not** trigger a re-render here (would be wasted work — the next render
 * would attach nothing anyway), which is why `requiresRerender` is `false`
 * for the stub even though the real producer advertises `true`.
 */
class AndroidRecompositionStubRegistry : DataProductRegistry {

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = RecompositionProduct.KIND,
        schemaVersion = RecompositionProduct.SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Recomposition counts (stub)",
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != RecompositionProduct.KIND) return DataProductRegistry.Outcome.Unknown
    return DataProductRegistry.Outcome.NotAvailable
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> =
    emptyList()
}
