package com.example.wearwidget

import androidx.compose.ui.text.style.TextAlign

// What these widgets write differently on the pinned Remote Compose release than on androidx-main,
// for the default `release` lane. `src/remoteSnapshot` has androidx-main's spelling for
// `-Pcomposeai.remoteCompose=snapshot`; `build.gradle.kts` compiles one or the other. Delete both,
// and inline androidx-main's, once the pinned release has it.

/** Wear `RemoteText`'s centred alignment: Compose's `TextAlign` in remote-material3 alpha15. */
internal val CenterTextAlign: TextAlign = TextAlign.Center
