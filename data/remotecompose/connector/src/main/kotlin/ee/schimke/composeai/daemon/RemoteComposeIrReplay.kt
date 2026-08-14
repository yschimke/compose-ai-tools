@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.collection.MutableObjectIntMap
import androidx.collection.ObjectIntMap
import androidx.collection.emptyObjectIntMap
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.render.IrSidecarChannel
import ee.schimke.composeai.data.render.extensions.IrReplayComposableProvider

/**
 * Replays a Remote Compose preview from a bundle's captured IR (schema v5): the serialized
 * `RemoteDocument` bytes ([IrSidecarChannel.FORMAT_REMOTECOMPOSE], `ir/<id>.rc`) that
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

    // Which player draws is read from the controller rather than passed in: the daemon resolves
    // this
    // composable reflectively against a fixed `(ByteArray)` signature (see the class doc), so there
    // is no parameter to thread a choice through. Null — the default — keeps the view-backed
    // player.
    val embedded =
      RemoteComposeController.player.value == RemoteComposePlayerKind.EMBEDDED &&
        isEmbeddedPlayerAvailable

    if (embedded) {
      // The embedded player owns its own `RemoteContext` and applies the document itself, so there
      // is no `init`/`StateUpdater` hook to seed through the way the view player has. Named colour
      // overrides are the one seeded facet it accepts up front.
      ExperimentalRemoteDocumentPlayer(
        document = remoteDocument,
        namedColorOverrides = seededOverrides.toNamedColorOverrides(),
      )
    } else {
      RemoteDocumentPlayer(
        document = remoteDocument.document,
        documentWidth = displayMetrics.widthPixels,
        documentHeight = displayMetrics.heightPixels,
        init = { player ->
          applyConnectorOverrides(player.stateUpdater, seededOverrides)
          installGoogleFontTypefaceResolver(player)
        },
      )
    }
  }
}

/**
 * Whether the vendored embedded player (`:third-party-rc-embedded-player`) is on the runtime
 * classpath. Resolved once per JVM. A consumer that doesn't ship it silently falls back to the view
 * player instead of dying with `NoClassDefFoundError` — the same classloader gate `:daemon:android`
 * uses before registering the Remote Compose extension at all.
 */
/**
 * The colour subset of the seeded named values, in the shape [ExperimentalRemoteDocumentPlayer]
 * takes (variable name -> ARGB int).
 *
 * This is deliberately *narrower* than [applyConnectorOverrides], which the view player gets: that
 * one also pushes string / float / int / dp / boolean seeds through the player's `StateUpdater`.
 * The embedded player exposes no equivalent seeding hook — it builds its own `RemoteContext` and
 * applies the document during composition — so non-colour overrides do **not** reach it. A render
 * that seeds them and then selects the embedded player will differ from the view player for that
 * reason alone, which is a property of the two players' APIs rather than a rendering divergence;
 * keep it in mind when reading a `rc-compare` row for a preview that carries knobs.
 *
 * Invalid hex is skipped rather than thrown, and a six-digit value is read as opaque — both through
 * the shared [rcColorToArgb], so the embedded player and the view player cannot disagree about what
 * the same seed means.
 */
internal fun Map<String, RemoteNamedValue>.toNamedColorOverrides(): ObjectIntMap<String> {
  val colors = entries.mapNotNull { (name, value) ->
    val color = value as? RemoteNamedValue.ColorValue ?: return@mapNotNull null
    val argb = rcColorToArgb(color.argb) ?: return@mapNotNull null
    name to argb
  }
  if (colors.isEmpty()) return emptyObjectIntMap()
  return MutableObjectIntMap<String>(colors.size).apply { colors.forEach { (n, v) -> put(n, v) } }
}

internal val isEmbeddedPlayerAvailable: Boolean by lazy {
  runCatching {
    Class.forName(
      "androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayerKt",
      false,
      RemoteComposeIrReplay::class.java.classLoader,
    )
  }
    .isSuccess
}

/** Registers [RemoteComposeIrReplay] as the replay composable for `remotecompose` IR. */
class RemoteComposeIrReplayProvider : IrReplayComposableProvider {
  override val format: String = IrSidecarChannel.FORMAT_REMOTECOMPOSE

  override fun replayClass(): Class<*> = RemoteComposeIrReplay::class.java
}
