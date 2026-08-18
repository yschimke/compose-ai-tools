package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The one place ARGB `Int`s become [Color]s and back.
 *
 * The player's runtime keeps colours as packed ARGB `Int`s — `:rc-player-runtime` has no Compose UI
 * dependency, and there those really are wire values. The compose module is the boundary where a
 * host meets the player, and a host should be handing over a [Color]. That makes the conversion a
 * boundary concern rather than a runtime one, and it belongs in exactly one file so the two places
 * that need it — the `systemColors` host hook and `RcNamedValue.Color` — cannot disagree about
 * channel order or about what `null` means.
 *
 * `Color.toArgb()` rather than hand-packing the channels: it is the conversion Compose itself uses,
 * so a wide-gamut colour is converted the same way the rest of the framework would convert it.
 */
internal fun Color.toRcArgb(): Int = toArgb()

/**
 * Inverse of [toRcArgb]. `Color(Int)` reads the value as packed ARGB, which is what the wire is.
 */
internal fun rcColorFromArgb(argb: Int): Color = Color(argb)
