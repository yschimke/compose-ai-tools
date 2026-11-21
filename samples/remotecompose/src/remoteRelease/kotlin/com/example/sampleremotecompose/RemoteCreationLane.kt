@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.action.RemoteAction
import androidx.compose.remote.creation.compose.state.MutableRemoteState
import androidx.compose.remote.creation.compose.state.RemotePaintingStyle
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteStrokeJoin
import androidx.compose.remote.creation.compose.text.RemoteFontWeight

// The names these samples need that previously differed between the pinned Remote Compose release
// and androidx-main (`src/remoteSnapshot`). Both lanes now share the Remote* types and
// `valueChangeAction`.

/** A `RemotePaint`'s stroke style. */
internal val StrokePaintingStyle: RemotePaintingStyle = RemotePaintingStyle.Stroke

/** A `RemotePaint`'s round stroke join. */
internal val RoundStrokeJoin: RemoteStrokeJoin = RemoteStrokeJoin.Round

/** `RemoteText`'s font weights. */
internal val MediumFontWeight: RemoteFontWeight = RemoteFontWeight.Medium
internal val SemiBoldFontWeight: RemoteFontWeight = RemoteFontWeight.SemiBold

/** androidx-main's `valueChangeAction`, which this package resolves before an import. */
internal fun <T> valueChangeAction(
  remoteState: MutableRemoteState<T>,
  updatedValue: RemoteState<T>,
): RemoteAction =
  androidx.compose.remote.creation.compose.action.valueChangeAction(remoteState, updatedValue)
