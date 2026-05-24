@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.CreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import kotlinx.coroutines.runBlocking

/**
 * `PreviewWrapperProvider` that bridges `renderNow.overrides.remoteCompose.namedValues` into the
 * running `RemoteComposePlayer`'s `StateUpdater`. Applied as `@PreviewWrapper(
 * RemoteOverridablePreviewWrapper::class)` on a `@Preview`-annotated composable so the body stays
 * authoring-shaped (`Container { MyRemoteComponent() }`) — no `RemoteOverridablePreview(...)` /
 * `RemotePreview(...)` call inside the body. This is the canonical shape: preview authors swap
 * one annotation, not the function body.
 *
 * Hard-codes [RcPlatformProfiles.ANDROIDX] for symmetry with the local `RemotePreviewWrapper` the
 * sample shipped before this connector existed. Consumers that want a different profile subclass
 * and override [profile] — the tooling instantiates the wrapper via its no-arg ctor, so per-call
 * overrides aren't a thing the wrapper API supports today.
 *
 * See [RemoteOverridablePreview] for the underlying composable; the wrapper just forwards. See
 * [applyConnectorOverrides] for the leaf that resolves each [RemoteNamedValue] to the matching
 * `StateUpdater.setUserLocal*` setter.
 */
open class RemoteOverridablePreviewWrapper : PreviewWrapperProvider {
  /** Remote-compose platform profile to capture the document against. Defaults to ANDROIDX. */
  protected open val profile: Profile = RcPlatformProfiles.ANDROIDX

  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    RemoteOverridablePreview(profile = profile, content = content)
  }
}

/**
 * Composable that captures [content] via `captureSingleRemoteDocument`, hands the document to
 * `RemoteDocumentPlayer`, and installs an `init` callback that applies
 * [RemoteComposeController.namedValues] through the player's [StateUpdater]. Prefer the
 * annotation-only path through [RemoteOverridablePreviewWrapper]; this composable exists for
 * tooling/host code that needs to drive the bridge manually.
 *
 * The "USER:" domain prefix that `rememberNamedRemoteString` (and the rest of the `rememberNamed*`
 * family) uses on the writer side is the same prefix `StateUpdater.setUserLocal*` consumes, so a
 * binding declared with `rememberNamedRemoteString("label", "Tap me")` is reachable by passing
 * the bare `"label"` (no manual prefix) into the override map.
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

  val displayMetrics = context.resources.displayMetrics
  val displayInfo =
    CreationDisplayInfo(
      displayMetrics.widthPixels,
      displayMetrics.heightPixels,
      displayMetrics.densityDpi,
    )
  // Same capture pattern as upstream `RemotePreview` — `runBlocking` inside `remember` so the
  // document materialises once per (profile, content) pair without re-capturing across
  // recompositions.
  val remoteDocument =
    remember(profile, content) {
      runBlocking {
        RemoteDocument(captureSingleRemoteDocument(context, displayInfo, profile, content).bytes)
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
 * for the `RemoteNamedValue` variant. Internal but visible for tests; ordinary callers reach this
 * via [RemoteOverridablePreview] / [RemoteOverridablePreviewWrapper].
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
      is RemoteNamedValue.BooleanValue -> updater.setUserLocalInt(name, if (value.value) 1 else 0)
      is RemoteNamedValue.ColorValue -> {
        // Wire model accepts an arbitrary string for argb (a typo in a panel value
        // would otherwise crash the render path). Skip invalid hex instead of throwing.
        val hex = value.argb.removePrefix("#")
        val argb = hex.toLongOrNull(16)?.toInt()
        if (argb != null) updater.setUserLocalColor(name, argb)
      }
    }
  }
}

