@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.compose.remote.creation.compose.action.HostAction
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteText
import androidx.wear.compose.remote.material3.buttonSizeModifier

/**
 * Pure-remote composables — each one is the kind of component a real Remote
 * Compose screen is built from. Mirrors the upstream
 * `wear/compose/remote/remote-material3/samples` set, reduced to the three
 * button variants that don't need image-vector / bitmap fixtures.
 *
 * Exposed as the "unit of content" that the two preview approaches in
 * `Previews.kt` wrap differently:
 *   1. wrapper call inside the `@Preview`-annotated UI composable (see
 *      [RemoteButtonEnabledPreview]), and
 *   2. `@PreviewWrapper(RemotePreviewWrapper::class)` applied to a
 *      `@Preview`-annotated composable that only emits remote content (see
 *      [RemoteButtonWithBorderPreview]).
 */

// A shared action used by every sample button — [HostAction] is the Remote
// Compose equivalent of `onClick = { ... }`. The two arguments are a remote
// string payload and a remote-float handler id, both hoisted out so the
// per-button code stays focused on layout.
private val testAction = HostAction("testAction".rs, 1.rf)

@Composable
@RemoteComposable
fun RemoteButtonEnabled() {
    RemoteButton(
        onClick = testAction,
        modifier = RemoteModifier.buttonSizeModifier(),
        enabled = true.rb,
        content = { RemoteText("Enabled".rs) },
    )
}

@Composable
@RemoteComposable
fun RemoteButtonWithBorder() {
    RemoteButton(
        onClick = testAction,
        modifier = RemoteModifier.buttonSizeModifier(),
        border = 8.rdp,
        borderColor = RemoteColor(Color.Green),
    ) {
        RemoteText("Bordered".rs)
    }
}

@Composable
@RemoteComposable
fun RemoteButtonWithShape() {
    RemoteButton(
        onClick = testAction,
        modifier = RemoteModifier.buttonSizeModifier(),
        shape = RemoteRoundedCornerShape(4.rdp),
        content = { RemoteText("Custom shape".rs) },
    )
}

/**
 * Centers [content] inside a remote full-size box. Equivalent to the upstream
 * sample's `Container`; kept private to emphasise that its purpose is preview
 * framing, not production composition.
 */
@Composable
@RemoteComposable
fun Container(content: @Composable @RemoteComposable () -> Unit) {
    RemoteBox(
        modifier = RemoteModifier.fillMaxSize(),
        contentAlignment = RemoteAlignment.Center,
        content = content,
    )
}
