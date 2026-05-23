@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.CreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import kotlinx.coroutines.runBlocking

/**
 * Drop-in replacement for upstream `androidx.compose.remote.tooling.preview.RemotePreview` that
 * wires `renderNow.overrides.remoteCompose.namedValues` into the running `RemoteComposePlayer`'s
 * `StateUpdater`. Preview authors swap `RemotePreview` for [RemoteOverridablePreview] when they
 * want panel-side / daemon-side overrides to flow through to a `rememberNamedRemoteString` (or
 * `rememberNamedRemoteFloat`, `rememberNamedRemoteColor`, `rememberNamedRemoteInt`) binding.
 *
 * The bridge runs in two halves:
 *
 *  1. The document is captured the same way upstream `RemotePreview` does — `runBlocking {
 *     captureSingleRemoteDocument(...) }` over [content], producing a [RemoteDocument]. The
 *     [Profile] passed in still drives `RcPlatformProfiles` resolution.
 *  2. The captured document feeds [RemoteDocumentPlayer], and we install an `init` callback that
 *     reads [RemoteComposeController.namedValues] (the connector-side store seeded by
 *     `RemoteComposeOverrideExtension`) and applies each entry through the player's
 *     [StateUpdater.setUserLocalString] / `setUserLocalFloat` / `setUserLocalInt` /
 *     `setUserLocalColor` family. The "USER:" domain prefix that `rememberNamedRemote*` uses on
 *     the writer side is the same prefix [StateUpdater.setUserLocal] consumes, so a binding
 *     declared with `rememberNamedRemoteString("label", "Tap me")` is reachable by passing
 *     `"label"` (no manual prefix) to `setUserLocalString`.
 *
 * `RemoteNamedValue.BooleanValue` has no `setUserLocalBoolean` counterpart in alpha010; we
 * collapse it to `setUserLocalInt(name, 0 | 1)` so consumer code that bound the same name to a
 * `rememberNamedRemoteInt` sees the toggled value. `RemoteNamedValue.DpValue` maps to
 * `setUserLocalFloat` (dp units are densitised float values once they reach the player).
 *
 * When the connector has no seeded overrides (the default in a vanilla `composePreviewRenderAll`
 * run) the loop is a no-op and the preview renders with each `rememberNamedRemote*`'s declared
 * default — same output as plain `RemotePreview`.
 */
@Composable
fun RemoteOverridablePreview(
  profile: Profile,
  modifier: Modifier = Modifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current

  // Same capture pattern as upstream `RemotePreview` — `runBlocking` inside `remember` so the
  // document materialises once per (profile, content) pair without re-capturing across
  // recompositions. The display info is left null (upstream default) so capture sizes against
  // the active configuration without us second-guessing the conversion.
  val displayMetrics = context.resources.displayMetrics
  val displayInfo =
    CreationDisplayInfo(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
  val remoteDocument =
    remember(profile, content) {
      runBlocking {
        RemoteDocument(
          captureSingleRemoteDocument(context, displayInfo, profile, content).bytes
        )
      }
    }

  // Snapshot the seeded overrides at composition time. The map is from `RemoteComposeController`
  // (process-static state), seeded by `RemoteComposeOverrideExtension` from
  // `renderNow.overrides.remoteCompose`. Reading `.value` here makes recomposition observe the
  // controller's `MutableState`, so a follow-up render with a new override re-runs the bridge.
  val seededOverrides = RemoteComposeController.namedValues.value

  RemoteDocumentPlayer(
    document = remoteDocument.document,
    documentWidth = displayInfo.width,
    documentHeight = displayInfo.height,
    modifier = modifier,
    init = { player -> applyConnectorOverrides(player.stateUpdater, seededOverrides) },
  )
}

/**
 * Pushes every entry of [overrides] through [updater] using the matching `setUserLocal*` setter
 * for the `RemoteNamedValue` variant. Public for tests; ordinary callers reach this via
 * [RemoteOverridablePreview].
 */
internal fun applyConnectorOverrides(
  updater: StateUpdater,
  overrides: Map<String, RemoteNamedValue>,
) {
  for ((name, value) in overrides) {
    when (value) {
      is RemoteNamedValue.StringValue -> updater.setUserLocalString(name, value.value)
      is RemoteNamedValue.FloatValue -> updater.setUserLocalFloat(name, value.value)
      is RemoteNamedValue.IntValue -> updater.setUserLocalInt(name, value.value)
      is RemoteNamedValue.DpValue -> updater.setUserLocalFloat(name, value.value)
      is RemoteNamedValue.BooleanValue ->
        updater.setUserLocalInt(name, if (value.value) 1 else 0)
      is RemoteNamedValue.ColorValue -> {
        val argb = value.argb.removePrefix("#").toLong(16).toInt()
        updater.setUserLocalColor(name, argb)
      }
    }
  }
}
