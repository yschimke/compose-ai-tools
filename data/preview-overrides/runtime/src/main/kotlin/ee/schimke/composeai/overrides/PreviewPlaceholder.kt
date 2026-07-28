package ee.schimke.composeai.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Forced content-loading placeholder state for a preview (issue #2646), or null to leave the
 * preview's own state alone.
 *
 * A Wear/M3 placeholder is driven by a `PlaceholderState` the app owns — typically
 * `rememberPlaceholderState { contentReady }` — so a renderer can't flip it from the outside. This
 * local is the opt-in seam that lets it: preview content asks [placeholderActive] whether the
 * daemon pinned a state and feeds the answer into its own `PlaceholderState`, so **one** preview
 * renders deterministically in both the loaded and loading states. Same opt-in model as
 * [LocalClock] / `PreviewSlot`: unset (the default, and production) leaves behaviour untouched, so
 * an unforced render is byte-identical.
 *
 * The daemon provides it from `renderNow.overrides.placeholderActive`
 * (`PlaceholderStateOverrideExtension` in `:data-preview-overrides-connector`), so
 * `/render/<id>.png?placeholderActive=true` shows the loading state of the same preview.
 */
val LocalPlaceholderActive: ProvidableCompositionLocal<Boolean?> = compositionLocalOf { null }

/**
 * The forced placeholder state for this render, or [default] when none was forced.
 *
 * Read it where the preview builds its `PlaceholderState`:
 * ```
 * val loading = placeholderActive(default = false)
 * val state = rememberPlaceholderState { !loading }
 * ```
 */
@Composable
fun placeholderActive(default: Boolean = false): Boolean = LocalPlaceholderActive.current ?: default
