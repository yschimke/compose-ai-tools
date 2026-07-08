package ee.schimke.composeai.preview.slots

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the current render requested a **cleared background** — the "crisp outline" toggle. The
 * renderer paints its harness background transparent whenever the effective spec resolves to a
 * transparent fill (see each backend's `RenderEngine`); this local lets a composable that draws its
 * *own* opaque fill (a Material 3 `Surface`, a `Box(Modifier.background(...))`) drop that fill to
 * match, so the captured PNG is a component silhouette on transparency instead of a solid card.
 *
 * `false` (the default) preserves the composable's normal appearance — an unset render, or one that
 * shows a background, reads exactly as before. The live `renderNow.overrides.clearBackground` flag
 * (`compose-preview serve`'s `?background=clear`, the VS Code panel chip) provides `true` around
 * the rendered preview, mirroring how `LocalSlotMode` is provided for the slot map — so
 * `/render/<id>.png` and `/render/<id>.png?background=clear` come from one preview with no source
 * edit.
 *
 * A consumer opts in by reading it where it would otherwise hard-code a fill, e.g. `Surface(color =
 * if (LocalPreviewBackgroundCleared.current) Color.Transparent else colorScheme.surface)`.
 * Composables that never draw their own background ignore it and stay transparent-by-default.
 */
val LocalPreviewBackgroundCleared: ProvidableCompositionLocal<Boolean> = compositionLocalOf {
  false
}
