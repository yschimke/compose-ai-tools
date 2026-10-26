@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.wear.core.WearWidgetParams
import ee.schimke.composeai.daemon.RemoteComposeController
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.NamedValueSeedTarget
import ee.schimke.composeai.data.remotecompose.reseed
import ee.schimke.composeai.rcembedded.player.RcPlayer
import ee.schimke.composeai.rcembedded.player.RcPlayerState
import ee.schimke.composeai.rcembedded.player.RemoteImageSupport

// Everything in this file touches `:data-remotecompose-connector`, which is `compileOnly` here: it
// is on a render's classpath only when the consumer or the daemon puts it there. So nothing outside
// this file names a connector type, and every call into it is behind
// [remoteComposeConnectorAvailable]
// — this file's class is then never loaded without the connector beside it.

/**
 * The connector's controller class. A daemon render seeds `renderNow.overrides.remoteCompose
 * .namedValues` into it; a Glance Wear widget builds its own player, so before #401 that seed never
 * reached a widget preview and the PNG came back unchanged.
 */
internal const val CONNECTOR_CONTROLLER: String =
  "ee.schimke.composeai.daemon.RemoteComposeController"

/**
 * Whether a connector that can take a widget's consumption back is on [classLoader]: the controller
 * with `markNamedValuesConsumed` (compose-preview-daemon 3.26.0, #406) and the shared seed mapping.
 * An older connector seeds nothing this lane could report, so it counts as absent.
 */
internal fun connectorControllerPresent(classLoader: ClassLoader?): Boolean = runCatching {
  val controller = Class.forName(CONNECTOR_CONTROLLER, false, classLoader)
  controller.getMethod("markNamedValuesConsumed")
  controller.getMethod("getNamedValues")
  Class.forName(
    "ee.schimke.composeai.data.remotecompose.RemoteComposeSeedingKt",
    false,
    classLoader,
  )
  true
}
  .getOrDefault(false)

/** [connectorControllerPresent] for this runtime's own classloader, resolved once. */
internal val remoteComposeConnectorAvailable: Boolean by lazy {
  connectorControllerPresent(WearWidgetPreviewPlayer::class.java.classLoader)
}

/**
 * The named values the connector holds for this render. Call only when the connector is present.
 */
@Composable
internal fun connectorNamedValues(): Map<String, RemoteNamedValue> =
  RemoteComposeController.namedValues.value

/**
 * The embedded lane with [namedValues] seeded over the document's authored defaults before the
 * first frame, then reported to the connector as consumed so the daemon does not warn that the seed
 * was dropped.
 *
 * Draws through [RcPlayer] with an [RcPlayerState] (the state-taking overload the connector's own
 * embedded backend uses) rather than `ExperimentalRemoteDocumentPlayer`, which has no seeding hook;
 * the size is the same as [CmpWearWidgetPlayer]'s, so a seed changes values, not the frame.
 */
@Composable
internal fun SeededCmpWearWidgetPlayer(
  bytes: ByteArray,
  params: WearWidgetParams,
  namedValues: Map<String, RemoteNamedValue>,
) {
  val state =
    remember(bytes) {
      // Before the parse, as in CmpWearWidgetPlayer.
      RemoteImageSupport.enableEncodedImageReferences()
      RcPlayerState(RemoteDocument(bytes).document)
    }
  val target = remember(state) { RcPlayerStateSeedTarget(state) }
  val seeded = remember(state) { mutableSetOf<String>() }
  // During composition, not in a SideEffect: the player reads these on its first frame, and the
  // typed-state writes are snapshot-backed. A name dropped from the seed returns to its default.
  remember(target, namedValues) {
    val applied = target.reseed(previous = seeded, overrides = namedValues)
    seeded.apply {
      clear()
      addAll(applied)
    }
  }
  // After the connector's render-start reset, like its own players (#401).
  SideEffect { RemoteComposeController.markNamedValuesConsumed() }
  RcPlayer(
    state = state,
    modifier =
      Modifier.width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
        .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
  )
}

/** The embedded player's typed named-value states as a [NamedValueSeedTarget]. */
private class RcPlayerStateSeedTarget(private val state: RcPlayerState) : NamedValueSeedTarget {
  override fun setString(name: String, value: String) {
    state.stringState(name).value = value
  }

  override fun setFloat(name: String, value: Float) {
    state.floatState(name).value = value
  }

  override fun setInt(name: String, value: Int) {
    state.intState(name).value = value
  }

  override fun setBoolean(name: String, value: Boolean) {
    state.booleanState(name).value = value
  }

  override fun setColor(name: String, argb: Int) {
    state.colorState(name).value = Color(argb)
  }

  override fun clear(name: String) = state.clearOverride(name)
}
