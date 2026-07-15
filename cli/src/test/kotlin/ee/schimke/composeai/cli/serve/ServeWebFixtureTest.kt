package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden generator + drift guard for the `serve` web surfaces captured by the preview-harness.
 *
 * `ServeWeb`'s landing + viewer pages are a *visual* surface, so — per the repo rule about wiring
 * new visual surfaces into the preview workflow — they're rendered to committed HTML fixtures under
 * `vscode-extension/preview-harness/fixtures/pages/`. The harness's `pages-snapshot.spec.mjs`
 * screenshots those per theme into `out/<fixture>.<theme>.png`, which the existing generic
 * `vscode-preview-diff.py` bot diffs + comments on every PR — no panel/`scenario.html` plumbing.
 *
 * This test re-renders the pages from the *current* `ServeWeb` and asserts the committed fixtures
 * match, so any change to the serve UI fails here until the fixtures are refreshed. Regenerate
 * with:
 * ```
 * UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
 * ```
 *
 * (An env var rather than a `-D` system property, since Gradle forwards the environment to the
 * forked test JVM but not arbitrary system properties.)
 */
class ServeWebFixtureTest {

  private val token = "demo-token-fixture"
  private val moduleLabel = ":samples:cmp"

  // A representative spread: a few snapshot-only previews plus two that also advertise the future
  // `live` (CMP→JS) mode, so the captured chrome exercises the mode seam.
  private val previews =
    listOf(
      ServePreview("com.example.ButtonPreview", "Button"),
      ServePreview(
        "com.example.CardPreview",
        "Card",
        listOf(PreviewMode.SNAPSHOT, PreviewMode.LIVE),
      ),
      ServePreview("com.example.DialogPreview", "Dialog"),
      ServePreview("com.example.ListScreenPreview", "List screen"),
      ServePreview(
        "com.example.ProfileScreenPreview",
        "Profile screen",
        listOf(PreviewMode.SNAPSHOT, PreviewMode.LIVE),
      ),
      ServePreview("com.example.SettingsScreenPreview", "Settings screen"),
    )

  // A design-catalog spread whose flattened ids carry a per-theme axis (`…__light` / `…__dark`),
  // plus one theme-less component — so the captured landing exercises the sticky light/dark toggle
  // and its card filtering (a component-preview module without theme variants shows no toggle).
  private val themedPreviews =
    listOf(
      ServePreview("button-filled__ideal__default__light", "Button · Filled (light)"),
      ServePreview("button-filled__ideal__default__dark", "Button · Filled (dark)"),
      ServePreview("switch-on__ideal__default__light", "Switch · On (light)"),
      ServePreview("switch-on__ideal__default__dark", "Switch · On (dark)"),
      ServePreview("badge", "Badge"),
    )

  // A trusted-catalog preview that declares author knobs (a `label` string + an accent `color`) —
  // the `compose/overrides` payload PR #2281 added across the M3 catalog. On a live catalog session
  // (ServeCatalogLiveHost) these render as LIVE controls that re-render via `/render` on edit.
  private val knobPreview =
    ServePreview(
      "button-filled__ideal__default__light",
      "Button · Filled (light)",
      overrides =
        listOf(
          PreviewOverrideDeclaration(
            key = "label",
            type = PreviewOverrideType.STRING,
            default = PreviewOverrideValue.StringValue("Filled"),
          ),
          PreviewOverrideDeclaration(
            key = "iconColor",
            type = PreviewOverrideType.COLOR,
            default = PreviewOverrideValue.ColorValue("#FF6750A4"),
          ),
          // A font knob (`catalogOverrideFont` / `previewOverrideFont`): a string knob a viewer
          // renders as an autocompleting combobox seeded with the declared `@TypographyCatalog`
          // names. The real catalog knob sets `googleFonts = true` (splicing the full
          // fonts.google.com list); the fixture keeps it off so the committed golden isn't ~1900
          // `<option>` lines — the full-list splice is covered by a dedicated behavioural test.
          PreviewOverrideDeclaration(
            key = "theme.font",
            type = PreviewOverrideType.STRING,
            default = PreviewOverrideValue.StringValue("Roboto Flex"),
            suggestions = listOf("Roboto Flex", "Google Sans Flex", "Lobster Two"),
          ),
        ),
    )

  @Test
  fun `serve web fixtures are in sync with ServeWeb`() {
    val pagesDir = File(repoRoot(), "vscode-extension/preview-harness/fixtures/pages")
    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"

    // Render the fixtures with a producer-trust badge so the visual-diff harness captures it: a
    // trusted (signature) landing and an unverified viewer exercise both badge styles.
    val landing =
      ServeWeb.landingPage(moduleLabel, previews, token, trust = "signature:compose-ai-tools-ci")
    // The public preview server's landing carries the "about" intro that explains the host + its
    // trust model (preview.coo.ee). Captured so the visual-diff harness covers that surface too.
    val landingPublic =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        catalogs = listOf("compose-m3", "wear-m3"),
      )
    // The public preview server's FRONT DOOR: an index of the published design systems, each a card
    // with a meaningful hero preview, its title + library, trust badge, and a link to /<system>/.
    // This is what `/` serves now (instead of an arbitrary default module's grid), so the harness
    // captures it per theme.
    val homeIndex =
      ServeWeb.homeIndexPage(
        listOf(
          ServeWeb.HomeSystem(
            system = "compose-m3",
            title = "Compose Material 3",
            subtitle = "androidx.compose.material3:material3",
            previewCount = 42,
            trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
            heroPreviewId = "button-filled__ideal__default__light",
          ),
          ServeWeb.HomeSystem(
            system = "wear-m3",
            title = "Wear Compose Material 3",
            subtitle = "androidx.wear.compose:compose-material3",
            previewCount = 18,
            trust = "branch:yschimke/compose-ai-tools@design-artifacts/wear-m3",
            heroPreviewId = "button-filled__ideal__default__light",
          ),
          ServeWeb.HomeSystem(
            system = "remote-m3",
            title = "Remote Compose Material 3",
            subtitle = "androidx.wear.compose.remote:remote-material3",
            previewCount = 6,
            trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
            heroPreviewId = "Button-Filled__ideal__default__light",
          ),
        ),
        token,
        isPublic = true,
        // The app catalogs (`--catalogs-unlisted`): served like the design systems but grouped
        // under
        // a separate "Apps" section on the front door instead of the "Design systems" nav.
        apps =
          listOf(
            ServeWeb.HomeSystem(
              system = "meshcore-mobile",
              title = "MeshCore",
              subtitle = "ee.schimke.meshcore",
              previewCount = 33,
              trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
              heroPreviewId = "device-manycontacts__ideal__default__compact",
            ),
            ServeWeb.HomeSystem(
              system = "cadence",
              title = "Cadence",
              subtitle = "ee.schimke.cadence",
              previewCount = 12,
              trust = "branch:yschimke/cadence@design-artifacts/cadence",
              heroPreviewId = null,
            ),
          ),
      )
    val viewer =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        trust = "unverified",
        // The full preview list feeds the left-hand component nav drawer (default closed) so the
        // harness captures its chrome alongside the default-open overrides drawer.
        siblings = previews,
      )
    // A second viewer carrying the in-browser Wasm tier, so the harness captures the "Run in
    // browser (Wasm)" toggle + iframe seam a CMP catalog session shows.
    val wasmViewer =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    // A trusted catalog served LIVE (ServeCatalogLiveHost): static baked snapshots
    // (canApplyOverrides=false) yet the "Live (stream)" toggle is enabled (hasLiveStream=true), and
    // it also carries the in-browser Wasm tier. Captures the chrome where Live is on AND Wasm is
    // available AND snapshots stay static — the case the `staticSnapshot` (not `live.disabled`)
    // wasm auto-enable signal exists for.
    val wasmViewerLive =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    // A trusted catalog served LIVE (ServeCatalogLiveHost) whose preview declares author knobs:
    // snapshots stay baked (canApplyOverrides=false) but the carried daemon CAN re-render an
    // override on demand (canRenderOverrides=true), so the declared knob controls render ENABLED
    // and
    // an edit re-renders via /render. This is the surface PR #2281's overrides feed into — captured
    // so the visual-diff bot covers the knob panel.
    val viewerCatalogKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasSvgExport = true,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=button-filled",
        wasmSameOrigin = true,
        // A trusted-catalog live session now also carries the app's declared @ThemeCatalog themes
        // (read from the live bundle's previews.json), so the App theme selector renders enabled
        // and
        // re-renders via the carried daemon — the surface this PR wires up end-to-end.
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
          ),
      )
    // A catalog served under its canonical path (/meshcore-mobile/) rather than ?session=: same
    // pages, but links stay on the path (basePath) and drop the &session= param. Captures the
    // path-mounted landing + viewer the public server now serves these design systems at.
    val landingPath =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        catalogs = listOf("compose-m3", "wear-m3"),
        basePath = "/meshcore-mobile",
      )
    val viewerPath =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        basePath = "/meshcore-mobile",
        siblings = previews,
      )
    // A daemon-backed viewer whose module declares `@ThemeCatalog` themes: the viewer adds an "App
    // theme" selector (grouped by `@ThemeCatalog(group=…)`) so a preview can be re-rendered under a
    // chosen theme via the `themeProvider` override. Captured so the visual-diff bot covers the
    // selector.
    val viewerThemes =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
            ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
          ),
      )
    // A daemon-backed viewer for a preview detected to support keyboard focus (`@FocusedPreview`):
    // the "Detected features" group with the "Keyboard focus" control appears, gated to daemon
    // sessions. Captured so the visual-diff bot covers the detected-feature control.
    val viewerFocus =
      ServeWeb.viewerPage(
        ServePreview("com.example.FocusRingPreview", "Focus ring", supportsFocus = true),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
      )
    // A daemon-backed viewer for a preview detected to support one-handed gesture hints
    // (`@GestureHintPreview`) on an Android-backed session (`gesturesRenderable = true`): the
    // "Detected features" group shows the "Show gesture hints" control. Captured so the visual-diff
    // bot covers the Android-gated detected-feature control.
    val viewerGestures =
      ServeWeb.viewerPage(
        ServePreview("com.example.OneHandedPreview", "One-handed", supportsGestures = true),
        token,
        sessionId = "wear-m3",
        canApplyOverrides = true,
        gesturesRenderable = true,
      )
    // The SAME gesture-supporting preview on a desktop-backed session (`gesturesRenderable =
    // false`,
    // the default): the desktop daemon ignores the override, so the row is omitted rather than
    // shown
    // dead — no "Detected features" group at all.
    val viewerGesturesDesktop =
      ServeWeb.viewerPage(
        ServePreview("com.example.OneHandedPreview", "One-handed", supportsGestures = true),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
      )
    // A catalog whose previews carry per-theme variants, so the landing shows the sticky light/dark
    // toggle and tags each card with its baked theme for client-side filtering.
    val landingThemed =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        catalogs = listOf("compose-m3", "wear-m3"),
      )

    if (update) {
      pagesDir.mkdirs()
      File(pagesDir, "serve-landing.html").writeText(landing)
      File(pagesDir, "serve-landing-public.html").writeText(landingPublic)
      File(pagesDir, "serve-home-index.html").writeText(homeIndex)
      File(pagesDir, "serve-viewer.html").writeText(viewer)
      File(pagesDir, "serve-viewer-wasm.html").writeText(wasmViewer)
      File(pagesDir, "serve-viewer-wasm-live.html").writeText(wasmViewerLive)
      File(pagesDir, "serve-viewer-catalog-knobs.html").writeText(viewerCatalogKnobs)
      File(pagesDir, "serve-viewer-themes.html").writeText(viewerThemes)
      File(pagesDir, "serve-viewer-focus.html").writeText(viewerFocus)
      File(pagesDir, "serve-viewer-gestures.html").writeText(viewerGestures)
      File(pagesDir, "serve-landing-path.html").writeText(landingPath)
      File(pagesDir, "serve-viewer-path.html").writeText(viewerPath)
      File(pagesDir, "serve-landing-themed.html").writeText(landingThemed)
      writePlaceholderPng(File(pagesDir, "_render-placeholder.png"))
      return
    }

    assertGolden(File(pagesDir, "serve-landing.html"), landing)
    assertGolden(File(pagesDir, "serve-landing-public.html"), landingPublic)
    assertGolden(File(pagesDir, "serve-home-index.html"), homeIndex)
    assertGolden(File(pagesDir, "serve-viewer.html"), viewer)
    // The overrides drawer defaults OPEN (`cp-controls-open` on the viewer, its toggle expanded)…
    assertTrue(
      viewer.contains("class=\"cp-viewer cp-controls-open\"") &&
        viewer.contains("id=\"cp-controls-toggle\" aria-expanded=\"true\""),
      "the overrides drawer defaults open",
    )
    // …and the component nav drawer defaults CLOSED (present, but its toggle collapsed and the
    // viewer element itself carries no `cp-nav-open` class), while still linking each sibling to
    // its
    // own viewer page. The absence check is scoped to the viewer element's class attribute — the
    // bare token `cp-nav-open` also appears in the stylesheet (`:not(.cp-nav-open)`) and drawer
    // script, so a whole-document `contains` would always match.
    assertTrue(
      viewer.contains("id=\"cp-nav\"") &&
        viewer.contains("id=\"cp-nav-toggle\" aria-expanded=\"false\"") &&
        viewer.contains("class=\"cp-viewer cp-controls-open\"") &&
        !viewer.contains("class=\"cp-viewer cp-controls-open cp-nav-open\""),
      "the component nav drawer defaults closed",
    )
    assertTrue(
      viewer.contains("class=\"cp-nav-item\" href=\"/p/com.example.ButtonPreview?token="),
      "the nav drawer links each sibling to its viewer page",
    )
    // A single-preview session shows neither the nav drawer nor its toggle — both when no siblings
    // are passed AND when the caller passes the whole preview list whose only entry is the current
    // preview (the `renderHost.previews` shape a one-preview module produces): there is nothing to
    // navigate *to*, so `navDrawerHtml` suppresses the drawer rather than emitting a self-link.
    for (solo in
      listOf(
        ServeWeb.viewerPage(previews.first(), token),
        ServeWeb.viewerPage(previews.first(), token, siblings = listOf(previews.first())),
      )) {
      assertFalse(
        solo.contains("id=\"cp-nav\"") || solo.contains("id=\"cp-nav-toggle\""),
        "a single-preview session shows no component nav drawer",
      )
    }
    assertGolden(File(pagesDir, "serve-viewer-wasm.html"), wasmViewer)
    assertGolden(File(pagesDir, "serve-viewer-wasm-live.html"), wasmViewerLive)
    assertGolden(File(pagesDir, "serve-viewer-catalog-knobs.html"), viewerCatalogKnobs)
    assertGolden(File(pagesDir, "serve-viewer-themes.html"), viewerThemes)
    assertGolden(File(pagesDir, "serve-viewer-focus.html"), viewerFocus)
    // The detected-feature control shows for a focus-supporting preview…
    assertTrue(
      viewerFocus.contains("id=\"cp-focus\"") && viewerFocus.contains("Keyboard focus"),
      "a @FocusedPreview preview shows the Keyboard focus control",
    )
    // …and NOT for an ordinary preview (no dead control).
    assertFalse(
      viewerThemes.contains("id=\"cp-focus\""),
      "a preview without @FocusedPreview shows no Keyboard focus control",
    )
    assertGolden(File(pagesDir, "serve-viewer-gestures.html"), viewerGestures)
    // The gesture control shows for a gesture-supporting preview on an Android-backed session…
    assertTrue(
      viewerGestures.contains("id=\"cp-gestures\"") &&
        viewerGestures.contains("Show gesture hints"),
      "a @GestureHintPreview preview shows the Show gesture hints control on an Android session",
    )
    // …but NOT on a desktop-backed session (gesturesRenderable = false) — the row is omitted, not
    // shown dead, since the desktop daemon ignores the override.
    assertFalse(
      viewerGesturesDesktop.contains("id=\"cp-gestures\""),
      "a gesture-supporting preview shows no gesture control on a desktop session",
    )
    assertGolden(File(pagesDir, "serve-landing-path.html"), landingPath)
    assertGolden(File(pagesDir, "serve-viewer-path.html"), viewerPath)
    assertGolden(File(pagesDir, "serve-landing-themed.html"), landingThemed)
    assertTrue(
      File(pagesDir, "_render-placeholder.png").isFile,
      "missing _render-placeholder.png — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )

    // The home index lists every published system as a card linking to its /<system>/ catalog —
    // including remote-m3 — each carrying a hero preview img from that system's /render endpoint.
    assertTrue(homeIndex.contains("Design systems"), "home index is headed 'Design systems'")
    assertTrue(
      homeIndex.contains("href=\"/compose-m3/\"") &&
        homeIndex.contains("href=\"/wear-m3/\"") &&
        homeIndex.contains("href=\"/remote-m3/\""),
      "home index cards link to each system's canonical /<system>/ path",
    )
    assertTrue(
      homeIndex.contains("Remote Compose Material 3"),
      "remote-m3 appears in the index with its human title",
    )
    assertTrue(
      homeIndex.contains("src=\"/remote-m3/render/Button-Filled__ideal__default__light.png\""),
      "each system card renders a meaningful hero preview from its /render endpoint",
    )
    assertTrue(
      homeIndex.contains("cp-badge--trusted"),
      "the index badges each system's producer trust",
    )
    // The app catalogs (`--catalogs-unlisted`) show under a separate "Apps" section, each linking
    // to
    // its canonical /<system>/ path — so meshcore-mobile / cadence surface on the front door too,
    // while staying off the "Design systems" nav.
    assertTrue(
      homeIndex.contains("<p class=\"cp-head\">Apps</p>"),
      "home index has an Apps section",
    )
    assertTrue(
      homeIndex.contains("href=\"/meshcore-mobile/\"") && homeIndex.contains("href=\"/cadence/\""),
      "app cards link to each app catalog's canonical /<system>/ path",
    )
    assertTrue(
      homeIndex.contains("MeshCore") && homeIndex.contains("Cadence"),
      "each app appears in the Apps section with its human title",
    )
    // Public mode opens every route, so server-rendered links carry NO ?token param.
    assertFalse(homeIndex.contains("token="), "public home index links are token-free")
    assertFalse(landingPublic.contains("token="), "public landing drops the token from its links")
    // A token-gated (non-public) landing keeps the token as the only gate.
    assertTrue(
      landing.contains("?token=$token"),
      "a token-gated landing keeps the token in its links",
    )
    // The public viewer's back-link is token-free, and its request-building JS only sends a token
    // when the page URL itself carried one (so a public page stays token-free end to end).
    val publicViewer =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        isPublic = true,
      )
    assertFalse(publicViewer.contains("?token="), "public viewer back-link carries no token")
    assertTrue(
      publicViewer.contains("if (token) parts.push(\"token=\""),
      "viewer JS only appends a token when the page URL carried one",
    )
    // The representative pick prefers a default-state light hero over dark / disabled edge cases.
    assertEquals(
      "button-filled__ideal__default__light",
      ServeWeb.representativePreviewId(
        listOf(
          ServePreview("badge__ideal__default__dark", "Badge dark"),
          ServePreview("button-filled__ideal__disabled__light", "Filled disabled"),
          ServePreview("button-filled__ideal__default__light", "Filled default"),
          ServePreview("button-filled__ideal__default__dark", "Filled dark"),
        )
      ),
      "the hero pick prefers a default-state, light, filled-button render",
    )

    // The sticky theme toggle appears only for a catalog with light/dark pairs, and each paired
    // component collapses into ONE swap card carrying both themes' baked render; a plain component
    // module shows no toggle.
    assertTrue(
      landingThemed.contains("class=\"cp-theme\""),
      "themed catalog shows the theme toggle",
    )
    assertTrue(
      landingThemed.contains("class=\"cp-card\" data-swap=\"1\"") &&
        landingThemed.contains("data-l-src=") &&
        landingThemed.contains("data-d-src="),
      "a paired component renders one swap card carrying both themes' baked render",
    )
    // The swap collapses the two variants into one card: the button-filled light+dark pair is a
    // single card, not two, so the dark variant's id no longer appears as its own card id line.
    assertFalse(
      landingThemed.contains(">button-filled__ideal__default__dark</div>"),
      "the dark variant is folded into the swap card, not a separate card",
    )
    assertTrue(
      landingThemed.contains("localStorage.setItem(\"cp-theme\""),
      "toggle persists the choice to the shared cp-theme key",
    )
    // The swap re-points the image + viewer link + id + label to the chosen theme's baked render.
    assertTrue(
      landingThemed.contains("img.src = src;") &&
        landingThemed.contains(
          "c.setAttribute(\"href\", c.getAttribute(\"data-\" + k + \"-href\"))"
        ),
      "the toggle swaps the card's render and viewer link in place (not a filter)",
    )
    // Dark-first system (Wear): a preview with no explicit __light/__dark token still tags the
    // viewer stage dark (data-bg-theme, the background axis — separate from the data-card-theme
    // filter axis), so a light-on-transparent Wear render stays readable — while a non-dark-first
    // viewer with no theme token leaves the stage on its default (light).
    assertTrue(
      viewerGestures.contains("cp-controls-open\" data-bg-theme=\"dark\""),
      "a Wear (dark-first) viewer tags the stage dark even without a __dark token",
    )
    assertFalse(
      viewerFocus.contains("cp-controls-open\" data-bg-theme="),
      "a non-dark-first viewer with no theme token leaves the stage default (light)",
    )
    // The stage only follows the Theme choice when the control can actually re-render: on a static
    // bundle the select is disabled (but may carry a seeded localStorage value), so syncBg must
    // gate
    // on !el.disabled or it would tint the stage under an unchanged baked PNG.
    assertTrue(
      viewerGestures.contains("!el.disabled &&"),
      "syncBg only honors the Theme choice when the control is usable (not a disabled static select)",
    )
    assertFalse(
      landing.contains("class=\"cp-theme\""),
      "a module without theme variants shows no toggle",
    )
    // The search box filters the grid and appears for every non-empty module — including the
    // plain, theme-less one that shows no theme toggle. The grid carries the id the input targets.
    assertTrue(landing.contains("id=\"cp-search\""), "landing carries the search box")
    assertTrue(
      landing.contains("id=\"cp-grid\""),
      "the grid is labelled for the search box to target",
    )
    assertTrue(
      landingThemed.contains("id=\"cp-search\""),
      "the search box shows alongside the theme toggle on a themed catalog",
    )
    // The combined filter composes search with theme: on a themed catalog the script still persists
    // the theme choice, so search didn't displace the theme half.
    assertTrue(
      landingThemed.contains("localStorage.setItem(\"cp-theme\"") &&
        landingThemed.contains("getElementById(\"cp-search\")"),
      "the themed landing's filter script drives both the theme toggle and the search box",
    )
    // The viewer both seeds its Theme select from the shared cp-theme on load (so a theme-less
    // preview inherits the catalog choice) and writes it back on change — the sticky round-trip.
    assertTrue(
      viewer.contains("localStorage.getItem(\"cp-theme\""),
      "viewer seeds its Theme select from the shared cp-theme on load",
    )
    assertTrue(
      viewer.contains("localStorage.setItem(\"cp-theme\""),
      "viewer Theme change writes the shared cp-theme key",
    )

    // The backend-provenance badge names the active tier. The Wasm tier is always CMP-WASM; the
    // live + snapshot labels come from server metadata (a live daemon can be Android, not just
    // JVM),
    // defaulting to generic Live / Snapshot.
    assertTrue(viewer.contains("id=\"cp-backend\""), "viewer stage carries the backend badge")
    assertTrue(wasmViewer.contains("\"CMP-WASM\""), "badge hard-codes only the wasm tier label")
    assertTrue(
      viewer.contains("data-live-backend=\"Live\"") &&
        viewer.contains("data-snapshot-backend=\"Snapshot\""),
      "live + snapshot labels default to generic, server-settable values",
    )
    // Both labels are server-settable (design catalogs render Android; a desktop daemon streams
    // JVM).
    val labelled =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ButtonPreview") },
        token,
        snapshotBackend = "Android",
        liveBackend = "CMP-JVM",
      )
    assertTrue(
      labelled.contains("data-snapshot-backend=\"Android\"") &&
        labelled.contains("data-live-backend=\"CMP-JVM\""),
      "snapshotBackend + liveBackend flow to the badge",
    )
  }

  @Test
  fun `viewer mounts the Wasm tier only when a wasm app backs the session`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    val withWasm = ServeWeb.viewerPage(card, token, wasmSrc = "/wasm/compose-m3/?id=card-filled")
    // The render mode is a radio group: PNG (default) / Live Compose / Wasm — the last only when a
    // wasm app backs the session.
    assertTrue(
      withWasm.contains("name=\"cp-mode\" value=\"png\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"live\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"wasm\""),
      "expected the PNG / Live Compose / Wasm radio group",
    )
    assertTrue(withWasm.contains("id=\"cp-wasm\""), "expected the Wasm iframe")
    assertTrue(withWasm.contains("data-wasm-src=\"/wasm/compose-m3/?id=card-filled\""))
    // Default (no wasmSameOrigin ⇒ untrusted / unknown): the iframe stays opaque-origin, so an
    // unverified catalog's `/wasm/` app can't reach the parent viewer's tokened URLs / DOM.
    // Match the exact attribute, not a bare "allow-same-origin" substring — the viewer-script
    // comments mention the phrase, so a substring check would be polluted.
    assertTrue(
      withWasm.contains("sandbox=\"allow-scripts\"") &&
        !withWasm.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "untrusted Wasm stays opaque-origin (allow-scripts only)",
    )
    // A TRUSTED catalog's app (wasmSameOrigin=true) gets its real origin, so its storage/history
    // APIs (window.caches via supportsCacheApi, history.pushState) stop throwing SecurityError in
    // an
    // opaque origin. Still no allow-forms / allow-popups / allow-top-navigation.
    val trustedWasm =
      ServeWeb.viewerPage(
        card,
        token,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      trustedWasm.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "trusted Wasm gets same-origin",
    )
    // Flash-free switch: the snapshot stays on-stage until the app's first-frame signal, and the
    // iframe is overlaid on the snapshot's exact box (pixel parity with the baked PNG).
    assertTrue(
      withWasm.contains("\"cp-wasm-ready\""),
      "viewer listens for the app's first-frame signal",
    )
    assertTrue(
      withWasm.contains("function positionWasmFrame()"),
      "iframe is positioned over the snapshot's rendered box",
    )
    assertTrue(withWasm.contains("loading Wasm…"), "load state keeps the snapshot with a status")
    // Guard against re-adding a page-side font preload: the real prefetch lives in the app's own
    // index.html (in flight before the iframe navigates, and the app consumes the promises), so a
    // page-side preload is redundant.
    assertFalse(
      withWasm.contains("preloadWasmFonts"),
      "no page-side font preload (the app's index.html owns the prefetch)",
    )
    // The in-browser tier can drop the sticker background (component only on the checkerboard).
    assertTrue(
      withWasm.contains("id=\"cp-wasm-bg\"") && withWasm.contains("Component only (no background)"),
      "expected the background toggle",
    )
    assertTrue(withWasm.contains("\"background=off\""), "background knob forwarded to the app")

    // No wasmSrc → snapshot viewer has no Wasm mode: PNG + Live radios only, no Wasm radio/iframe.
    val plain = ServeWeb.viewerPage(card, token)
    assertTrue(!plain.contains("name=\"cp-mode\" value=\"wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm-bg\""))
    assertTrue(plain.contains("name=\"cp-mode\" value=\"png\""), "PNG radio always present")
  }

  @Test
  fun `live canvas fits the daemon frame aspect-preserved inside the snapshot box`() {
    // The live lane is pinned to the baked snapshot's box (so a differently-sized frame doesn't
    // resize the stage), but a <canvas> stretches its buffer to its CSS box — filling that box
    // squished a frame whose aspect differed from the snapshot. The viewer fits the frame (contain)
    // and centres it, letterboxing within the snapshot footprint instead.
    val liveView =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        canApplyOverrides = true,
      )
    assertTrue(
      liveView.contains("function fitLiveCanvas()"),
      "the live canvas has a dedicated aspect-preserving fit function",
    )
    // Contain-fit math: scale by the smaller of the two box/buffer ratios, then centre.
    assertTrue(
      liveView.contains("Math.min(boxW / liveW, boxH / liveH)"),
      "the frame is scaled to contain (the smaller box/buffer ratio), not stretched to fill",
    )
    assertTrue(
      liveView.contains("(boxW - w) / 2") && liveView.contains("(boxH - h) / 2"),
      "the fitted frame is centred within the snapshot box",
    )
    // drawFrame caches the buffer dims and re-fits on each frame; a resize re-fits too.
    assertTrue(
      liveView.contains("liveW = im.naturalWidth;") && liveView.contains("fitLiveCanvas();"),
      "each frame caches its dims and re-fits",
    )
    assertTrue(
      liveView.contains("if (live && live.checked && !canvas.hidden) fitLiveCanvas();"),
      "a window resize re-fits the live canvas (not a plain box fill)",
    )
  }

  @Test
  fun `viewer offers the SVG render mode only when the session can export SVG`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // A session that can export SVG (a catalog / daemon) adds an SVG radio to the render-mode
    // group,
    // gated on the same hasSvgExport flag as the SVG direct-link row.
    val svgView = ServeWeb.viewerPage(card, token, hasSvgExport = true)
    assertTrue(
      svgView.contains("name=\"cp-mode\" value=\"svg\"") && svgView.contains("id=\"cp-mode-svg\""),
      "an SVG-exporting session offers the SVG render-mode radio",
    )
    // The SVG lane reuses the snapshot <img> but swaps the render extension; the viewer JS carries
    // the snapshotExt seam and stamps the backend badge with SVG.
    assertTrue(
      svgView.contains("var snapshotExt = \".png\";") &&
        svgView.contains("snapshotExt = \".svg\";"),
      "the snapshot lane flips its render extension between PNG and SVG",
    )
    assertTrue(
      svgView.contains("if (mode === \"svg\") return \"SVG\";"),
      "the backend badge names the SVG lane",
    )

    // No SVG export → no SVG radio, and the snapshot lane never leaves the raster PNG.
    val plain = ServeWeb.viewerPage(card, token)
    assertFalse(
      plain.contains("name=\"cp-mode\" value=\"svg\""),
      "a session without SVG export shows no SVG radio",
    )
    assertFalse(plain.contains("id=\"cp-mode-svg\""), "no SVG radio id without SVG export")
  }

  @Test
  fun `landing lists served catalogs as session nav links, marking the current one`() {
    // Default session (sessionId null): every catalog is a link to its canonical /<system>/ path.
    val front =
      ServeWeb.landingPage(moduleLabel, previews, token, catalogs = listOf("compose-m3", "wear-m3"))
    assertTrue(front.contains("class=\"cp-systems\""), "expected the design-systems nav")
    assertTrue(
      front.contains("href=\"/compose-m3/?token=$token\""),
      "expected a compose-m3 path link",
    )
    assertTrue(front.contains("href=\"/wear-m3/?token=$token\""), "expected a wear-m3 path link")

    // On a catalog session, that system is the current (non-link) pill, the other stays a link.
    val onCompose =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        sessionId = "compose-m3",
        catalogs = listOf("compose-m3", "wear-m3"),
      )
    assertTrue(
      onCompose.contains("aria-current=\"page\">compose-m3</span>"),
      "current catalog is marked",
    )
    assertTrue(onCompose.contains("href=\"/wear-m3/?token=$token\""), "other catalog stays a link")

    // No catalogs → no nav row.
    assertTrue(!ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-systems\""))
  }

  @Test
  fun `path-mounted pages keep links on the path and drop the session query param`() {
    // Served under /meshcore-mobile/: card, render and zip links carry the /meshcore-mobile prefix
    // and are token-only (the path, not &session=, carries the session).
    val landing =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        basePath = "/meshcore-mobile",
      )
    assertTrue(
      landing.contains("href=\"/meshcore-mobile/p/com.example.ButtonPreview?token=$token\""),
      "card link stays on the path",
    )
    assertTrue(
      landing.contains(
        "src=\"/meshcore-mobile/render/com.example.ButtonPreview.png?token=$token\""
      ),
      "render link stays on the path",
    )
    assertTrue(landing.contains("href=\"/meshcore-mobile/bundle.zip?token=$token\""), "zip on path")
    assertTrue(!landing.contains("&session="), "no &session= param in path mode")

    val viewer =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        sessionId = "meshcore-mobile",
        basePath = "/meshcore-mobile",
      )
    assertTrue(
      viewer.contains("href=\"/meshcore-mobile/?token=$token\""),
      "back link stays on path",
    )
    // No same-session link carries &session= (the viewer JS still contains the literal "&session="
    // for the legacy query lane, so match the link pattern, not the bare substring).
    assertTrue(
      !viewer.contains("&session=meshcore-mobile"),
      "no &session= link param in path-mode viewer",
    )
    // The viewer JS recovers the base from the path so /render + /ws hit the same session.
    assertTrue(
      viewer.contains("location.pathname.replace"),
      "viewer derives its request base from the path",
    )
  }

  @Test
  fun `public landing shows the about intro, default landing does not`() {
    val public = ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true)
    assertTrue(public.contains("class=\"cp-about\""), "expected the about section")
    assertTrue(public.contains("public preview server"), "expected the about title")
    assertTrue(public.contains("href=\"/version\""), "expected a link to /version")

    val default = ServeWeb.landingPage(moduleLabel, previews, token)
    assertTrue(
      !default.contains("class=\"cp-about\""),
      "non-public landing must omit the about box",
    )
  }

  @Test
  fun `static snapshot viewer disables server-render controls but a live session keeps them`() {
    // Catalog/bundle (canApplyOverrides defaults false), no Wasm: the controls that rebuild /render
    // can't take effect on a baked PNG, so they're disabled and a note explains why.
    val staticView = ServeWeb.viewerPage(previews.first(), token)
    assertTrue(staticView.contains("Pre-rendered snapshot"), "expected the static-snapshot note")
    // The note links out to how a viewer can enable the live overrides — run their own serve.
    assertTrue(
      staticView.contains("public-preview-server.md#running-one\">Enable a local preview server."),
      "snapshot note links to local preview server instructions",
    )
    assertTrue(staticView.contains("value=\"1.0\" disabled"), "font scale disabled")
    assertTrue(staticView.contains("id=\"cp-device\" disabled"), "device disabled")
    assertTrue(staticView.contains("id=\"cp-orientation\" disabled"), "orientation disabled")
    assertTrue(staticView.contains("id=\"cp-live\" disabled"), "Live Compose radio disabled")
    assertTrue(
      staticView.contains("id=\"cp-uiMode\" disabled"),
      "theme disabled without a Wasm app",
    )
    assertFalse(
      staticView.contains("id=\"cp-talkBack\""),
      "no live stream ⇒ the live-only overlay toggles are omitted entirely, not left dead",
    )
    assertFalse(
      staticView.contains("id=\"cp-themeProvider\""),
      "no declared themes ⇒ the App theme selector is omitted entirely",
    )

    // Static + Wasm: theme, font scale, and locale go LIVE (the in-browser app honours them) — only
    // the server-render-only controls (device/orientation/live stream) stay disabled.
    val wasmView =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
      )
    assertTrue(wasmView.contains("id=\"cp-uiMode\">"), "theme enabled with a Wasm app")
    assertTrue(
      wasmView.contains("step=\"0.1\" value=\"1.0\">"),
      "font scale enabled with a Wasm app",
    )
    assertTrue(wasmView.contains("autocomplete=\"off\">"), "locale enabled with a Wasm app")
    assertTrue(
      wasmView.contains("public-preview-server.md#running-one\">Enable a local preview server."),
      "wasm-snapshot note also links to local preview server instructions",
    )
    assertTrue(wasmView.contains("id=\"cp-device\" disabled"), "device stays server-only")
    assertTrue(wasmView.contains("id=\"cp-orientation\" disabled"), "orientation stays server-only")
    // The Wasm override-patch builder forwards the honoured params (theme/font scale/locale) to the
    // running app (via postMessage / the initial `#…` fragment), not the iframe query.
    assertTrue(wasmView.contains("\"fontScale=\""), "font scale forwarded to Wasm")
    assertTrue(wasmView.contains("\"localeTag=\""), "locale forwarded to Wasm")
    // On a static snapshot, a wasm-honoured control change auto-enables the Wasm tier (rather than
    // firing a /render the published catalog can't serve), so the control actually takes effect.
    // The
    // signal is the explicit `staticSnapshot` flag, NOT `live.disabled` — a live catalog serves
    // static snapshots yet leaves the Live toggle enabled.
    assertTrue(
      wasmView.contains("data-static-snapshot=\"true\""),
      "static-snapshot flag on the viewer",
    )
    assertTrue(
      wasmView.contains("else if (staticSnapshot && wasmToggle) {"),
      "static-snapshot wasm controls auto-enable the in-browser tier",
    )

    // Trusted catalog served LIVE + Wasm (ServeCatalogLiveHost): snapshots stay static (so the wasm
    // auto-enable + note still apply) but the Live toggle is ENABLED — the exact case
    // `live.disabled`
    // could no longer stand in for `staticSnapshot`.
    val liveCatalogWasm =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        canApplyOverrides = false,
        hasLiveStream = true,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
      )
    assertTrue(
      liveCatalogWasm.contains("id=\"cp-live\"> Live Compose"),
      "live catalog leaves the Live Compose radio enabled (not disabled)",
    )
    assertTrue(
      liveCatalogWasm.contains("data-static-snapshot=\"true\""),
      "live catalog still marks its snapshot lane static",
    )
    assertTrue(
      liveCatalogWasm.contains("Pre-rendered snapshot"),
      "live catalog keeps the static note",
    )
    assertTrue(
      liveCatalogWasm.contains("id=\"cp-device\" disabled"),
      "server-render-only controls stay disabled on a live catalog's static snapshot",
    )
    // A live-stream session offers the overlay toggles (talkBack / touch), rendered disabled until
    // the Live Compose mode is actually entered (the mode-transition JS flips them on).
    assertTrue(
      liveCatalogWasm.contains("cp-overlays") &&
        liveCatalogWasm.contains("id=\"cp-talkBack\" type=\"checkbox\" disabled") &&
        liveCatalogWasm.contains("id=\"cp-touchOverlay\" type=\"checkbox\" disabled"),
      "live stream offers the overlay toggles, disabled until Live Compose is active",
    )
    // The stream replays the full liveOverrides() on open so an overlay checked while the socket
    // was
    // still connecting (its change event dropped by the readyState guard) still reaches the daemon.
    assertTrue(
      liveCatalogWasm.contains("ws.onopen = function () {"),
      "the live stream seeds the daemon with the current overrides once the socket opens",
    )

    // Trusted catalog served LIVE whose preview declares author knobs (ServeCatalogLiveHost with
    // canRenderOverrides): snapshots stay baked, but the carried daemon re-renders a knob edit on
    // demand, so the declared knob controls render ENABLED (not the disabled, informational form a
    // plain static bundle shows) and route knob edits to /render.
    val catalogKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasSvgExport = true,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
      )
    assertTrue(catalogKnobs.contains("cp-knobs"), "declared knobs render as a control list")
    assertTrue(
      catalogKnobs.contains("data-knob-key=\"label\"") &&
        catalogKnobs.contains("data-knob-key=\"iconColor\""),
      "each declared knob gets a labelled control",
    )
    assertTrue(
      catalogKnobs.contains("data-can-render-overrides=\"true\""),
      "the viewer is flagged as override-renderable",
    )
    // The knobs are ENABLED — a live control, not the disabled/informational form. The `label` knob
    // is a text input; assert it renders enabled (no trailing ` disabled`).
    assertTrue(
      catalogKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "value=\"Filled\">"
      ),
      "declared knobs are enabled on an override-renderable session",
    )
    assertTrue(
      catalogKnobs.contains("edit a value to re-render"),
      "the knob note invites editing rather than saying values are baked in",
    )
    // A knob edit has a dedicated handler (onKnobEdited) that drives whichever transport is live —
    // here the carried daemon via /render (canRenderOverrides). The Wasm tier also honours named
    // knobs now, so the handler picks the iframe when Wasm is active (see the wasm-only case
    // below).
    assertTrue(
      catalogKnobs.contains("function onKnobEdited()"),
      "knob edits have a dedicated, transport-aware handler",
    )
    // During an active Live (stream), the override map sent over the WebSocket must carry the knob
    // values too (as knob.<key> entries), not just the display fields — otherwise the daemon resets
    // an edited knob to its default. The setOverrides sends use liveOverrides(), which folds them
    // in.
    assertTrue(
      catalogKnobs.contains("function liveOverrides()") &&
        catalogKnobs.contains("o[\"knob.\" + key]"),
      "the live-stream override map includes the declared knob values",
    )
    assertFalse(
      catalogKnobs.contains("setOverrides\", overrides: overrides()"),
      "live-stream setOverrides sends liveOverrides() (knobs included), not the display-only map",
    )
    // A plain static bundle (no daemon) still shows the knobs as DISABLED, informational controls.
    val staticKnobs = ServeWeb.viewerPage(knobPreview, token)
    assertTrue(
      staticKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "value=\"Filled\" disabled"
      ),
      "a plain static bundle leaves declared knobs disabled",
    )
    assertTrue(
      staticKnobs.contains("static bundle, values are baked in"),
      "a plain static bundle keeps the baked-in note",
    )

    // A published static catalog whose ONLY interactive lane is the in-browser Wasm app (no daemon
    // re-render: canApplyOverrides/canRenderOverrides both false, wasmSrc present). The wasm tier
    // now
    // seeds its `catalogOverride*` from the `knob.<key>` patch, so the declared knob controls
    // render
    // ENABLED and a knob edit drives the Wasm iframe — this is the preview.coo.ee case where
    // `?knob.label=…` did nothing in Wasm mode before.
    val wasmKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = false,
        wasmSrc = "/wasm/compose-m3/?id=button-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      wasmKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "value=\"Filled\">"
      ),
      "a wasm-backed published catalog enables the declared knob controls (no trailing disabled)",
    )
    assertTrue(
      wasmKnobs.contains("apply it in the browser (Wasm)"),
      "the knob note invites in-browser editing when only the Wasm lane is available",
    )
    // The `.cp-knob` edit handler drives whichever transport is live — for a wasm-only session it
    // posts the override patch (with the knob) to the iframe, or auto-enables Wasm from the
    // snapshot.
    assertTrue(
      wasmKnobs.contains("function onKnobEdited()") &&
        wasmKnobs.contains("wasmFrame.contentWindow.postMessage(wasmOverridePatch()"),
      "a knob edit routes to the Wasm iframe when that tier is active",
    )
    // wasmOverridePatch() carries the changed knob into the iframe fragment / postMessage,
    // alongside
    // the display axes — without this the app never sees the edit.
    assertTrue(
      wasmKnobs.contains("function wasmOverridePatch()") &&
        wasmKnobs.contains("parts.push(\"knob.\" + encodeURIComponent(key)"),
      "the wasm override patch includes the author-declared knob values",
    )
    // Deep-link parity: the knob controls hydrate from the page URL's `knob.<key>` params on load,
    // so opening `/p/…?knob.label=Hello` (or a copied direct link) renders the override immediately
    // in every transport — including the Wasm iframe, whose patch is built purely from control
    // state
    // — rather than the author default until the user edits the control.
    assertTrue(
      wasmKnobs.contains("q.get(\"knob.\" + key)"),
      "the viewer hydrates declared knob controls from the URL's knob.<key> params",
    )

    // Copyable direct links: every viewer offers a PNG URL row (copy + download); a session that
    // can
    // export SVG (a catalog / daemon) also offers an SVG row. The URLs are built client-side from
    // location.origin with the current overrides so a copied link reproduces the on-screen render.
    assertTrue(catalogKnobs.contains("class=\"cp-links\""), "the direct-links panel is shown")
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-png\"") && catalogKnobs.contains("id=\"cp-dl-png\""),
      "the PNG URL row has a copyable field and a download link",
    )
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-svg\"") && catalogKnobs.contains("id=\"cp-dl-svg\""),
      "an SVG-exporting session also offers an SVG URL row",
    )
    // Next to Download, a one-click "Copy PNG"/"Copy SVG" button that copies the rendered artefact
    // itself as clipboard text (PNG as a base64 data: URI, SVG markup verbatim) via .cp-copyimg.
    assertTrue(
      catalogKnobs.contains("class=\"cp-copyimg\"") &&
        catalogKnobs.contains("data-copyimg-ext=\".png\"") &&
        catalogKnobs.contains("data-copyimg-ext=\".svg\""),
      "each URL row has a Copy PNG / Copy SVG button that copies the artefact as text",
    )
    assertTrue(
      catalogKnobs.contains("readAsDataURL") &&
        catalogKnobs.contains("navigator.clipboard.writeText"),
      "the Copy PNG/SVG handler fetches the render and writes it to the clipboard as text",
    )
    // The URL itself is copied by clicking the field (no separate "Copy URL" button) — the handler
    // binds to .cp-url and flashes .cp-url-copied.
    assertFalse(
      catalogKnobs.contains("class=\"cp-copy\"") || catalogKnobs.contains(">Copy URL<"),
      "the separate Copy URL button is gone — the field is click-to-copy",
    )
    assertTrue(
      catalogKnobs.contains("querySelectorAll(\".cp-url\")") &&
        catalogKnobs.contains("cp-url-copied"),
      "clicking the URL field copies the URL and flashes the field",
    )
    assertTrue(
      catalogKnobs.contains("function refreshLinks()") && catalogKnobs.contains("location.origin"),
      "the links are rebuilt from location.origin as the controls change",
    )
    // A plain static bundle can't export SVG, so it shows the PNG row but not the SVG one.
    assertTrue(staticKnobs.contains("id=\"cp-url-png\""), "PNG URL row shows on any viewer")
    assertFalse(
      staticKnobs.contains("id=\"cp-url-svg\""),
      "no SVG URL row when the session can't export SVG",
    )

    // Live daemon session (canApplyOverrides = true): everything enabled, no note.
    val liveView = ServeWeb.viewerPage(previews.first(), token, canApplyOverrides = true)
    assertTrue(!liveView.contains("Pre-rendered snapshot"), "no static note on a live session")
    assertTrue(!liveView.contains("value=\"1.0\" disabled"), "font scale enabled on a live session")
    assertTrue(!liveView.contains("id=\"cp-device\" disabled"), "device enabled on a live session")
    assertTrue(liveView.contains("data-static-snapshot=\"false\""), "live session is not static")
  }

  @Test
  fun `declared themes render an App theme selector routed through the daemon`() {
    val themes =
      listOf(
        ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
        ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
        ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
      )
    val view =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        canApplyOverrides = true,
        declaredThemes = themes,
      )
    // The selector exists and carries each provider FQN as an option value with the human name.
    assertTrue(
      view.contains("id=\"cp-themeProvider\""),
      "declared themes render an App theme select",
    )
    assertTrue(
      view.contains("<option value=\"com.example.BrandLightThemeCatalog\">Brand Light</option>"),
      "each declared theme is an option keyed by its provider FQN",
    )
    // `@ThemeCatalog(group=…)` buckets themes into <optgroup>s; an ungrouped theme stays flat.
    assertTrue(view.contains("<optgroup label=\"Brand\">"), "grouped themes get an <optgroup>")
    assertTrue(
      view.contains(
        "<option value=\"com.example.HighContrastThemeCatalog\">High Contrast</option>"
      ),
      "an ungrouped theme is a flat option",
    )
    // Enabled on a daemon host and routed like a knob (the daemon path, never the wasm
    // auto-enable).
    assertFalse(
      view.contains("id=\"cp-themeProvider\" class=\"cp-knob-theme\" disabled"),
      "the theme selector is enabled on a daemon-backed host",
    )
    assertTrue(
      view.contains("themeSel.addEventListener(\"change\", onKnobChanged)"),
      "the theme selector routes its change through the daemon (knob) path",
    )
    assertTrue(
      view.contains("parts.push(\"themeProvider=\""),
      "a chosen theme is appended to the /render URL as themeProvider",
    )

    // A static bundle can't load a provider, so the selector renders disabled (informational).
    val staticThemed = ServeWeb.viewerPage(previews.first(), token, declaredThemes = themes)
    assertTrue(
      staticThemed.contains("id=\"cp-themeProvider\" class=\"cp-knob-theme\" disabled"),
      "the theme selector is disabled on a static bundle (no daemon to apply it)",
    )
  }

  @Test
  fun `trust badge renders trusted and unverified variants and is absent for a live module`() {
    val trusted = ServeWeb.landingPage(moduleLabel, previews, token, trust = "branch:repo@b")
    assertTrue(trusted.contains("cp-badge--trusted"), "expected a trusted badge")
    assertTrue(trusted.contains("✓ branch:repo@b"))

    val unverified = ServeWeb.viewerPage(previews.first(), token, trust = "unverified")
    assertTrue(unverified.contains("cp-badge--unverified"), "expected an unverified badge")

    // A live daemon-backed module carries no trust verdict → no badge element (the CSS still
    // defines `.cp-badge`, so check for the rendered `class="cp-badge…` span, not the bare string).
    assertTrue(!ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-badge"))
  }

  @Test
  fun `theme toggle shows only when the grid has light-dark pairs to swap`() {
    // A theme-PAIRED catalog: each component is baked in both __light and __dark, so those two
    // previews collapse into ONE swap card and the toggle re-points it between them — the toggle
    // shows, and the grid has one card per component (not two).
    val paired =
      listOf(
        ServePreview("button__ideal__default__light", "Button (light)"),
        ServePreview("button__ideal__default__dark", "Button (dark)"),
        ServePreview("switch__ideal__default__light", "Switch (light)"),
        ServePreview("switch__ideal__default__dark", "Switch (dark)"),
      )
    val pairedHtml = ServeWeb.landingPage("compose-m3", paired, token)
    assertTrue(
      pairedHtml.contains("class=\"cp-theme\""),
      "a theme-paired catalog shows the Light/Dark toggle",
    )
    // Two components × two themes → two swap cards, each carrying both themes' render.
    assertEquals(
      2,
      Regex("class=\"cp-card\" data-swap=\"1\"").findAll(pairedHtml).count(),
      "each paired component is one swap card (two components → two cards, not four)",
    )
    assertTrue(
      pairedHtml.contains("data-l-src=") && pairedHtml.contains("data-d-src="),
      "a swap card carries both the light and dark baked render",
    )

    // An APP catalog (meshcore-mobile shape): theme-neutral app screens plus two theme-showcase
    // previews that are DISTINCT components (theme-meshcore-light vs theme-meshcore-dark), so
    // nothing
    // pairs into a swap card. No pair → no toggle. This is the behaviour uniformly across every app
    // catalog: it keys off whether any component is baked in both themes, never the system name.
    val appCatalog =
      listOf(
        ServePreview("theme-meshcore-light__ideal__default__light__compact", "MeshCore light"),
        ServePreview("theme-meshcore-dark__ideal__default__dark__compact", "MeshCore dark"),
        ServePreview("contactlist-many__ideal__default__compact", "Contacts"),
        ServePreview("scanner-blefew__ideal__default__compact", "Scanner"),
        ServePreview("device-lowbattery__ideal__default__compact", "Device"),
        ServePreview("tcpconnectpanel-idle__ideal__default__compact", "TCP connect"),
      )
    assertFalse(
      ServeWeb.landingPage("meshcore-mobile", appCatalog, token, basePath = "/meshcore-mobile")
        .contains("class=\"cp-theme\""),
      "an app catalog with no light/dark pairs shows no Light/Dark toggle",
    )

    // A one-sided themed catalog (dark variants only, no light pair) also shows no toggle — there
    // is
    // nothing to swap to.
    val darkOnly =
      listOf(
        ServePreview("a__ideal__default__dark", "A"),
        ServePreview("b__ideal__default__dark", "B"),
      )
    assertFalse(
      ServeWeb.landingPage("x", darkOnly, token).contains("class=\"cp-theme\""),
      "a catalog with only one theme side shows no toggle",
    )
  }

  @Test
  fun `grouping strips only the theme segment, keeping a non-theme light-dark state segment`() {
    // A flattened id can carry a non-theme `light`/`dark` STATE segment before the theme segment
    // (the `toggle__<state>__default__<theme>` shape the catalog routing already documents). Only
    // the LAST light/dark (the theme, per cardTheme) may be stripped for the grouping key — else
    // the
    // dark-state and light-state toggles collapse onto one card and a state disappears.
    val stateful =
      listOf(
        ServePreview("toggle__dark__default__light", "Toggle · dark state (light)"),
        ServePreview("toggle__dark__default__dark", "Toggle · dark state (dark)"),
        ServePreview("toggle__light__default__light", "Toggle · light state (light)"),
        ServePreview("toggle__light__default__dark", "Toggle · light state (dark)"),
      )
    val html = ServeWeb.landingPage("compose-m3", stateful, token)
    // Two distinct components (dark-state, light-state), each a swap pair → two swap cards, not
    // one.
    assertEquals(
      2,
      Regex("class=\"cp-card\" data-swap=\"1\"").findAll(html).count(),
      "the dark-state and light-state toggles stay separate swap cards",
    )
    // Both states survive: each state's light+dark ids appear as swap-card data (none dropped).
    for (id in
      listOf(
        "toggle__dark__default__light",
        "toggle__dark__default__dark",
        "toggle__light__default__light",
        "toggle__light__default__dark",
      )) {
      assertTrue(html.contains(id), "the $id variant must survive grouping, not be dropped")
    }
  }

  @Test
  fun `a font knob renders an autocompleting combobox, catalog names first then Google Fonts`() {
    val fontPreview =
      ServePreview(
        "button-filled__ideal__default__light",
        "Button · Filled (light)",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "theme.font",
              type = PreviewOverrideType.STRING,
              default = PreviewOverrideValue.StringValue("Roboto Flex"),
              suggestions = listOf("Roboto Flex", "Google Sans Flex", "Lobster Two"),
              googleFonts = true,
            )
          ),
      )
    val view =
      ServeWeb.viewerPage(
        fontPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
      )
    // A font knob is a free-text `<input list>` bound to a `<datalist>` — a combobox, not a plain
    // text input — so any family is selectable while the field stays editable.
    assertTrue(
      view.contains("data-knob-key=\"theme.font\"") && view.contains("list=\"cp-dl-theme-font\""),
      "the font knob renders as an <input list> combobox",
    )
    assertTrue(
      view.contains("<datalist id=\"cp-dl-theme-font\">"),
      "the font knob emits a matching <datalist>",
    )
    val datalist =
      view.substringAfter("<datalist id=\"cp-dl-theme-font\">").substringBefore("</datalist>")
    val robotoIdx = datalist.indexOf("<option value=\"Roboto Flex\">")
    val lobsterIdx = datalist.indexOf("<option value=\"Lobster Two\">")
    val interIdx = datalist.indexOf("<option value=\"Inter\">")
    // The declared @TypographyCatalog names come first, in order ("by default show the typography
    // catalog")…
    assertTrue(
      robotoIdx in 0 until lobsterIdx,
      "the declared suggestions render first, in declaration order",
    )
    // …then `googleFonts = true` splices the full fonts.google.com list after them, so an arbitrary
    // family (Inter) is offered — de-duplicated, so Roboto Flex / Lobster Two aren't repeated.
    assertTrue(
      interIdx > lobsterIdx,
      "the Google Fonts list follows the declared suggestions (an arbitrary family is offered)",
    )
    assertEquals(
      1,
      Regex("<option value=\"Roboto Flex\">").findAll(datalist).count(),
      "a declared name that's also a Google family isn't duplicated",
    )
  }

  private fun assertGolden(file: File, rendered: String) {
    assertTrue(
      file.isFile,
      "missing fixture ${file.name} — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )
    assertEquals(
      file.readText(),
      rendered,
      "${file.name} is stale vs ServeWeb — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )
  }

  /** Walk up from the test working dir (the `:cli` project dir) to the repo root. */
  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
  }

  /**
   * A fixed, phone-shaped placeholder the harness serves for the daemon's `/render/<id>.png`
   * endpoint (which has no backend in CI). Gives every preview tile a realistic size so the
   * captured layout doesn't collapse on broken images. Deterministic so it never churns the visual
   * diff.
   */
  private fun writePlaceholderPng(file: File) {
    val w = 200
    val h = 420
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint =
      GradientPaint(0f, 0f, Color(0xCF, 0xD8, 0xFF), 0f, h.toFloat(), Color(0x9A, 0xA7, 0xE6))
    g.fillRect(0, 0, w, h)
    g.color = Color(0x33, 0x33, 0x3A)
    g.font = Font("SansSerif", Font.BOLD, 18)
    val label = "preview"
    val fm = g.fontMetrics
    g.drawString(label, (w - fm.stringWidth(label)) / 2, h / 2)
    g.dispose()
    ImageIO.write(img, "png", file)
  }
}
