package com.example.wearwidget

import androidx.compose.remote.creation.compose.text.RemoteTextAlign

// What these widgets write differently on the pinned Remote Compose release than on androidx-main,
// for the default `release` lane. `src/remoteSnapshot` has androidx-main's spelling for
// `-Pcomposeai.remoteCompose=snapshot`; `build.gradle.kts` compiles one or the other. The two
// currently agree: remote-material3 alpha16 took `RemoteTextAlign` as androidx-main had.

/** Wear `RemoteText`'s centred alignment. */
internal val CenterTextAlign: RemoteTextAlign = RemoteTextAlign.Center
