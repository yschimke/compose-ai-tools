package com.example.sampleremotecompose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * Declared themes for this sample — the half that was missing when a RemoteCompose catalog first
 * met a theme selector in the wild.
 *
 * ## Why this module in particular
 *
 * This is the only module in the repo that renders Remote Compose through **approach 2**:
 * `@PreviewWrapper(RemotePreviewWrapper::class)` on an ordinary `@Preview`, so the wrapper — not
 * the body — installs the capture. `:samples:design-catalog-remote-m3` pairs Remote Compose with
 * `@WearThemeCatalog` already, but through **approach 1** (`RemoteSticker { … }` inside the body),
 * where a theme provider composes outside the capture by construction and nothing can go wrong.
 *
 * Approach 2 plus a declared theme is the combination that had no home here, and it is exactly the
 * one `yschimke/meshcore-mobile` shipped. A `themeProvider` override used to REPLACE a preview's
 * declared `@PreviewWrapper`, which for these previews deletes the capture the body needs: every
 * `RemoteBox` / `RemoteColumn` / `RemoteRow` then lands on the plain UI applier and the render dies
 * with `IllegalStateException: Invalid applier` before drawing a pixel. Every widget preview in
 * that catalog failed the moment the serve theme optimiser began pre-rendering each preview under
 * each declared theme. The renderer now nests a theme override outside a structural wrapper instead
 * (`isStructuralPreviewWrapper` in `:data-render-core`); these providers exist so the shape that
 * broke is present upstream rather than only downstream.
 *
 * ## What a theme here does and does not reach
 *
 * It does not repaint the widgets, and that is not a bug to fix. `RemotePreviewWrapper` captures
 * its content with `captureSingleRemoteDocument` inside a `remember`, which is a **separate
 * composition** — no `CompositionContext` is threaded from the enclosing one, so composition locals
 * (a `MaterialTheme` among them) do not cross into the recorded document. A theme selected against
 * one of these previews therefore renders the same pixels it renders untinted. What must be true is
 * that it *renders at all*.
 *
 * Where the theme is live is the synthetic specimen sheet `@WearThemeCatalog` makes the renderer
 * emit per provider: a canned Wear M3 role + type-scale grid composed inside `Wrap`, which reads
 * the scheme below and differs per theme. Re-theming a recorded document is a different mechanism
 * entirely — named-value overrides on the replay path — and `:samples:design-catalog-remote-m3`'s
 * `RemoteThemeCatalogs.kt` is the worked example of it.
 *
 * ## Coverage this does and does not add — measured, not assumed
 *
 * It does **not** reproduce the bug, and it is worth being precise about why, because the obvious
 * assumption is wrong.
 *
 * No offline lane applies a `themeProvider` at all: `composePreviewRenderAll` wraps only the canned
 * specimen grid in a provider, never an ordinary preview, so these sheets keep rendering either
 * way. The interesting case is a live `serve` session, and that was tried: pack this module
 * (`bundle pack --module samples:remotecompose`), serve it on a Robolectric daemon, and request
 * `render/<RemoteButtonWithBorderPreview>.png?themeProvider=<RemoteSampleCoralThemeCatalog>`. The
 * provider is accepted (a bogus one 400s, so the declared set is enforced) and the render returns
 * 200 — **on a daemon with the nesting fix and on one with it reverted, byte for byte identical.**
 *
 * The reason is that `bundle pack` records `ir/<id>.rc` for exactly these `@PreviewWrapper`
 * previews, and a bundle-backed session replays those bytes rather than recomposing the body. No
 * wrapper is resolved on a replay, so there is nothing for a theme override to drop. A recorded
 * document is immune to this bug by construction.
 *
 * `yschimke/meshcore-mobile`'s catalog recomposes the same shape instead, which is why it broke:
 * `GET /meshcore-mobile/render/app-deviceinfowidgetemptypreview__…png?themeProvider=<its app
 * theme>` answered `500 render failed: IllegalStateException: Invalid applier` against the unfixed
 * server while the un-themed render of the same preview answered 200.
 *
 * So the automatic guard remains `themeProviderOverrideNestsAroundStructuralWrapper` in
 * `:daemon:android` and `:daemon:desktop`, which drives the decision directly. What these providers
 * add is narrower and honest: the approach-2 + declared-theme *shape* now exists upstream, on a
 * module carrying the real `RemotePreviewWrapper` that neither daemon's test classpath has, ready
 * for a lane that renders it through recomposition. Anyone wiring that lane should assert against a
 * recomposed render — an IR replay will pass no matter what the wrapper logic does.
 *
 * Wear M3 rather than phone M3 because `androidx.compose.material3` reaches this module only at
 * runtime (via `wear-compose-remote-material3`), while `androidx.wear.compose:compose-material3` is
 * on the compile classpath — and this module deliberately pins versions instead of taking the
 * Compose BOM (see its `build.gradle.kts`), so adding a phone-M3 compile dependency to write two
 * theme providers would be a poor trade.
 *
 * Each provider declares its own `Wrap`: the renderer resolves the method reflectively **on the
 * concrete class**, so an implementation inherited from a shared base is a `NoSuchMethodException`
 * and the sheet fails to render.
 */
@WearThemeCatalog(name = "Remote Default", group = "Remote")
class RemoteSampleDefaultThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = MaterialTheme { content() }
}

/** Warm coral primary — the palette the specimen sheet is read against the default with. */
@WearThemeCatalog(name = "Remote Coral", group = "Remote")
class RemoteSampleCoralThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(
      colorScheme = ColorScheme(primary = Color(0xFFFF6F61), secondary = Color(0xFFFFB4A9))
    ) {
      content()
    }
}
