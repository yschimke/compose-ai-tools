@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider

/**
 * Replays a Remote Compose preview from a bundle's captured IR (schema v5): the serialized
 * `RemoteDocument` bytes ([IrSidecarChannel.FORMAT_REMOTECOMPOSE], `ir/<id>.rcdoc`) that
 * [RemoteOverridablePreview]'s capture path emitted. Reconstructs the document with
 * `RemoteDocument(bytes)` and hands it to the same `RemoteDocumentPlayer` the live path uses — so a
 * bundle renders with **no** reference to the `@RemoteComposable` body that produced it (its class
 * was dropped at pack time).
 *
 * The daemon reaches this reflectively (it can't compile against the alpha player), so [Replay] is
 * an instance method with a fixed `(ByteArray)` signature and the class has a no-arg constructor —
 * matching what `RenderEngine` resolves via `getDeclaredComposableMethod`, the same shape as
 * `PreviewWrapperProvider.Wrap`. Seeded `renderNow.overrides.remoteCompose` named values are
 * applied through the player's `StateUpdater` via [applyConnectorOverrides], so replay honours
 * overrides exactly like the live path (a no-op when none are seeded).
 */
class RemoteComposeIrReplay {
  @Composable
  fun Replay(bytes: ByteArray) {
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val remoteDocument = remember(bytes) { RemoteDocument(bytes) }
    val seededOverrides = RemoteComposeController.namedValues.value
    RemoteDocumentPlayer(
      document = remoteDocument.document,
      documentWidth = displayMetrics.widthPixels,
      documentHeight = displayMetrics.heightPixels,
      init = { player -> applyConnectorOverrides(player.stateUpdater, seededOverrides) },
    )
  }
}

/** Registers [RemoteComposeIrReplay] as the replay composable for `remotecompose` IR. */
class RemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = IrSidecarChannel.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = RemoteComposeIrReplay::class.java
}
