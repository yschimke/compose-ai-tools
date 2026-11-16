@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.action.valueChange
import androidx.compose.remote.creation.compose.state.MutableRemoteState
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontWeight

// The names these samples need that the pinned Remote Compose release (1.0.0-alpha23) spells
// differently from androidx-main, for the default `release` lane. `src/remoteSnapshot` has the same
// names for `-Pcomposeai.remoteCompose=snapshot`; `build.gradle.kts` compiles one or the other.
// Everything in `src/main` is written in a form both lanes accept. Delete both files, and use
// androidx-main's names directly, once the pinned release has them.

/** A `RemotePaint`'s stroke style: Compose's `PaintingStyle` in alpha23. */
internal val StrokePaintingStyle: PaintingStyle = PaintingStyle.Stroke

/** A `RemotePaint`'s round stroke join: Compose's `StrokeJoin` in alpha23. */
internal val RoundStrokeJoin: StrokeJoin = StrokeJoin.Round

/** `RemoteText`'s font weights: Compose's `FontWeight` in alpha23. */
internal val MediumFontWeight: FontWeight = FontWeight.Medium
internal val SemiBoldFontWeight: FontWeight = FontWeight.SemiBold

/** androidx-main's `valueChangeAction`: alpha23's `valueChange`, renamed. */
internal fun <T> valueChangeAction(
  remoteState: MutableRemoteState<T>,
  updatedValue: RemoteState<T>,
): RemoteAction = valueChange(remoteState, updatedValue)
