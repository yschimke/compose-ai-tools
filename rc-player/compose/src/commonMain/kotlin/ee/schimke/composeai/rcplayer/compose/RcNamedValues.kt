package ee.schimke.composeai.rcplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue

/**
 * A caller-owned, snapshot-backed holder for the named values driving a document.
 *
 * **Why a holder rather than a plain map.** The player used to key its `remember` on the map, so
 * any change to it — including a parent recomposition rebuilding an equal map — constructed a fresh
 * `RcPlayerState` and threw away running animation timelines, mid-drag touch state, marquee
 * position, and every variable a document action had already changed. The one API a host uses to
 * drive a live document was also the one that reset it: a slider bound to a named float restarted
 * every animation in the document on each frame of the drag.
 *
 * A `SnapshotStateMap` fixes both halves at once. The player keys on the document alone, and
 * mutations are observed through the snapshot system and applied *incrementally* through
 * `RcPlayerState.setNamedValue` — the mechanism that already existed and had no caller on the
 * public path.
 *
 * Ownership is the caller's on purpose: a host usually wants to read a value back (a document
 * action can change one) and to hold the same map across document swaps.
 */
@Composable
public fun rememberRcNamedValues(
  vararg initial: Pair<String, RcNamedValue>
): SnapshotStateMap<String, RcNamedValue> = remember { mutableStateMapOf(*initial) }

/**
 * [RcNamedValue.Color] from a Compose [Color].
 *
 * `RcNamedValue.Color` carries packed ARGB because it lives in `:rc-player-runtime`, which has no
 * Compose UI dependency and where ARGB really is the wire value. This is the same boundary
 * conversion `systemColors` makes, through the same helper, so the two cannot disagree about
 * channel order.
 */
public fun rcNamedColor(color: Color): RcNamedValue.Color = RcNamedValue.Color(color.toRcArgb())

/** The [Color] an [RcNamedValue.Color] holds. Inverse of [rcNamedColor]. */
public val RcNamedValue.Color.color: Color
  get() = rcColorFromArgb(argb)
