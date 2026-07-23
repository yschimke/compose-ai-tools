@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteColor
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration

/**
 * Declaring drop-in replacements for the upstream `rememberNamedRemote*` family: they behave exactly
 * like the alpha `androidx.compose.remote.creation.compose.state.rememberNamedRemote*` (bind a
 * `USER:`-domain named value, seedable via `renderNow.overrides.remoteCompose` / the serve
 * `rc.<name>=` param), and additionally **record the binding as an editable knob declaration** so a
 * consumer can present a control for it.
 *
 * **Why a declaring wrapper rather than auto-enumeration.** The named value a `rememberNamedRemote*`
 * call binds is resolved into the captured `RemoteDocument` (its top-level ops are just a header +
 * the root layout — the value is baked into the tree, not exposed as an enumerable named-variable
 * op), and the runtime table that *would* list it (`RemoteComposePlayer.getNamedStrings()` etc.)
 * only populates when the player view actually paints — which the Robolectric bundle-render path
 * doesn't do. So the author naming the binding here is the only point that reliably knows the
 * `(name, kind, default)` triple; this is the bootstrap that tells the panel which names exist
 * before it can seed any of them.
 *
 * The declaration is recorded from a `SideEffect` (not during composition) so it lands after
 * `RemoteComposeOverrideExtension`'s render-start `clearDeclarations`, mirroring the plain-Compose
 * `previewOverride*` discipline. Outside a daemon/render the record is a harmless no-op write to the
 * process-static controller.
 */
@Composable
fun rememberOverridableRemoteString(name: String, default: String): RemoteString {
  SideEffect {
    RemoteComposeController.recordDeclaration(
      RemoteComposeKnobDeclaration(name, RemoteNamedValue.StringValue(default))
    )
  }
  return rememberNamedRemoteString(name, default)
}

/** [rememberOverridableRemoteString] for a color knob; the default is recorded as `#AARRGGBB`. */
@Composable
fun rememberOverridableRemoteColor(name: String, default: Color): RemoteColor {
  SideEffect {
    RemoteComposeController.recordDeclaration(
      RemoteComposeKnobDeclaration(name, RemoteNamedValue.ColorValue(default.toArgbHex()))
    )
  }
  return rememberNamedRemoteColor(name, default)
}

/** `#AARRGGBB`, matching the form `RemoteNamedValue.ColorValue` carries and the connector parses. */
private fun Color.toArgbHex(): String = "#%08X".format(toArgb())
