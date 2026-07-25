@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.daemon

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.RemoteDensityBehavior
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
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.render.IrSidecarChannel
import kotlinx.coroutines.runBlocking

/**
 * `PreviewWrapperProvider` that bridges `renderNow.overrides.remoteCompose.namedValues` into the
 * running `RemoteComposePlayer`'s `StateUpdater`. Applied as `@PreviewWrapper(
 * RemoteOverridablePreviewWrapper::class)` on a `@Preview`-annotated composable so the body stays
 * authoring-shaped (`Container { MyRemoteComponent() }`) — no `RemoteOverridablePreview(...)` /
 * `RemotePreview(...)` call inside the body. This is the canonical shape: preview authors swap one
 * annotation, not the function body.
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
 * binding declared with `rememberNamedRemoteString("label", "Tap me")` is reachable by passing the
 * bare `"label"` (no manual prefix) into the override map.
 *
 * `RemoteNamedValue.BooleanValue` has no `setUserLocalBoolean` counterpart in alpha010; we collapse
 * it to `setUserLocalInt(name, 0 | 1)` so consumer code that bound the same name to a
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
  // Capture in `Dp` density behavior, not the 3-arg default (`Legacy`). Legacy serialises the
  // dp-typed size modifiers (`size(dp)`, `heightIn(dp)` / `buttonSizeModifier`) inconsistently, so a
  // player can't reproduce the generation-density render — Material3 button/card fills and the
  // circular-progress indicator come out ~1/density too small. `Dp` keeps those dimensions in dp so
  // a player can scale them by the generation density. That density is written into the header
  // below (the alpha writer records DOC_WIDTH/HEIGHT in px and the density *behavior*, but not the
  // density value itself), which the rc-player consumes to scale the dp modifiers back to px.
  val displayInfo =
    RemoteCreationDisplayInfo(
      displayMetrics.widthPixels,
      displayMetrics.heightPixels,
      displayMetrics.densityDpi,
      densityBehavior = RemoteDensityBehavior.Dp,
    )
  // Same capture pattern as upstream `RemotePreview` — `runBlocking` inside `remember` so the
  // document materialises once per (profile, content) pair without re-capturing across
  // recompositions. Collect the knobs the content declares during the capture (via the
  // `rememberOverridable*` wrappers) so they can be re-recorded on every render below.
  val captured =
    remember(profile, content) {
      RemoteComposeController.collectingDeclarations {
        runBlocking {
          val bytes =
            captureSingleRemoteDocument(
                context,
                displayInfo,
                RemoteDensity.from(displayInfo),
                LayoutDirection.Ltr,
                profile = profile,
                content = content,
              )
              .bytes
          // The `Dp` capture keeps size modifiers in dp but the alpha writer doesn't record the
          // generation density *value* (only DOC_WIDTH/HEIGHT in px and the density behavior). Stamp
          // DOC_DENSITY_AT_GENERATION into the header so the rc-player can scale the dp modifiers
          // back to px; without it the fills/indicator render ~1/density too small. Best-effort and
          // idempotent — never fail the render over it.
          val stamped =
            runCatching { stampGenerationDensity(bytes, displayMetrics.density) }.getOrDefault(bytes)
          // Offer the captured RC doc so a bundle can carry + replay it without this composable's
          // bytecode; the render harness drains it into the `renders/<stem>.rc` sidecar that
          // `BundlePreviewTask.resolvePreviewIr` packs. No-op outside a daemon/test render (no
          // current preview id). Best-effort — never fail the render over IR capture. See
          // IrSidecarChannel.
          runCatching { IrSidecarChannel.offer(IrSidecarChannel.FORMAT_REMOTECOMPOSE, stamped) }
          RemoteDocument(bytes)
        }
      }
    }
  val remoteDocument = captured.first
  val declaredKnobs = captured.second

  // Re-record the captured knobs on EVERY render. The memoized capture above records them only once
  // (during the outer composition phase); on the daemon path `RemoteComposeOverrideExtension`'s
  // render-start `clearDeclarations()` runs afterwards (from a `DisposableEffect`, the apply phase),
  // so without this a `renderNow` / `data/fetch` render would surface no knobs. A `SideEffect` lands
  // in the apply phase after that clear (Compose runs every `RememberObserver` before any
  // `SideEffect`). Idempotent, so the standalone Gradle path (which clears before rendering) is
  // unaffected.
  androidx.compose.runtime.SideEffect {
    declaredKnobs.forEach { RemoteComposeController.recordDeclaration(it) }
  }

  // Snapshot the seeded overrides at composition time. The map is from `RemoteComposeController`
  // (process-static state), seeded by `RemoteComposeOverrideExtension` from
  // `renderNow.overrides.remoteCompose`. Reading `.value` here makes recomposition observe the
  // controller's `MutableState`, so a follow-up render with a new override re-runs the bridge.
  val seededOverrides = RemoteComposeController.namedValues.value

  RemoteDocumentPlayer(
    document = remoteDocument.document,
    documentWidth = displayMetrics.widthPixels,
    documentHeight = displayMetrics.heightPixels,
    modifier = modifier,
    init = { player -> applyConnectorOverrides(player.stateUpdater, seededOverrides) },
  )
}

// Remote Compose modern-header wire constants (big-endian). The header op is:
//   [op:1][major|MAGIC:4][minor:4][patch:4][propCount:4][ (tag:2)(len:2)(payload:len) ... ]
// where tag = (dataType << 10) | key, dataType FLOAT = 1, key 7 = DOC_DENSITY_AT_GENERATION.
private const val RC_HEADER_MAGIC = 0x048C0000.toInt()
private const val RC_PROP_DENSITY_AT_GENERATION = 7
private const val RC_DATATYPE_FLOAT = 1

/**
 * Insert `DOC_DENSITY_AT_GENERATION = density` into a captured RemoteDocument's header, so a player
 * can scale the dp-typed size modifiers back to generation pixels. Returns the input unchanged if
 * the density is unusable, the header isn't the modern property-table format, or the property is
 * already present (idempotent). Pure byte-surgery on the header — the wire format is exercised by
 * the rc-player parity harness.
 */
internal fun stampGenerationDensity(bytes: ByteArray, density: Float): ByteArray {
  if (!density.isFinite() || density <= 0f) return bytes
  if (bytes.size < 17) return bytes
  fun beInt(o: Int): Int =
    ((bytes[o].toInt() and 0xFF) shl 24) or
      ((bytes[o + 1].toInt() and 0xFF) shl 16) or
      ((bytes[o + 2].toInt() and 0xFF) shl 8) or
      (bytes[o + 3].toInt() and 0xFF)
  fun beShort(o: Int): Int = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)

  if ((beInt(1) and 0xFFFF0000.toInt()) != RC_HEADER_MAGIC) return bytes
  val propCount = beInt(13)
  if (propCount < 0) return bytes
  // Walk the existing property table; bail (leave unchanged) if density is already recorded or the
  // table is malformed.
  var off = 17
  repeat(propCount) {
    if (off + 4 > bytes.size) return bytes
    if ((beShort(off) and 0x3FF) == RC_PROP_DENSITY_AT_GENERATION) return bytes
    off += 4 + beShort(off + 2)
  }

  val tag = (RC_DATATYPE_FLOAT shl 10) or RC_PROP_DENSITY_AT_GENERATION
  val densBits = java.lang.Float.floatToIntBits(density)
  val out = ByteArray(bytes.size + 8)
  System.arraycopy(bytes, 0, out, 0, 17)
  val newCount = propCount + 1
  out[13] = (newCount ushr 24).toByte()
  out[14] = (newCount ushr 16).toByte()
  out[15] = (newCount ushr 8).toByte()
  out[16] = newCount.toByte()
  out[17] = (tag ushr 8).toByte()
  out[18] = tag.toByte()
  out[19] = 0
  out[20] = 4
  out[21] = (densBits ushr 24).toByte()
  out[22] = (densBits ushr 16).toByte()
  out[23] = (densBits ushr 8).toByte()
  out[24] = densBits.toByte()
  System.arraycopy(bytes, 17, out, 25, bytes.size - 17)
  return out
}

/**
 * Pushes every entry of [overrides] through [updater] using the matching `setUserLocal*` setter for
 * the `RemoteNamedValue` variant. Internal but visible for tests; ordinary callers reach this via
 * [RemoteOverridablePreview] / [RemoteOverridablePreviewWrapper].
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
