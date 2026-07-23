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

  // A FIXED server version for the goldens: the about box surfaces the running build, but pinning a
  // constant here (rather than the real BUNDLE_VERSION) keeps the committed HTML stable across
  // releases — production passes BUNDLE_VERSION, the fixtures pass this.
  private val version = "0.0.0-fixture"

  // Catalog provenance for the public compose-m3 landing golden — captures the provenance strip
  // (delivery branch, generation date, tool versions, regenerate link) the visual-diff bot diffs.
  private val provenance =
    ServeWeb.CatalogProvenance(
      repo = "yschimke/compose-ai-tools",
      branch = "design-artifacts/compose-m3",
      generatedAt = "2026-07-17T09:30:00.000Z",
      toolVersion = "0.16.54",
      designParityVersion = "0.1.25",
    )

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

  // A catalog whose components carry baked non-default STATES (checkbox checked/unchecked, radio
  // selected/unselected), each in light + dark, tagged via the `state`/`theme` metadata the
  // `previews/variants.json` manifest carries. The landing folds each component to ONE (default)
  // card; the viewer grows the `<nav class="cp-states">` switcher to the component's other
  // same-theme states. Captured so the visual-diff bot covers the state toggle end-to-end.
  private val statefulPreviews =
    listOf(
      ServePreview(
        "checkbox__ideal__default__light",
        "Checkbox · Checked (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "checkbox__ideal__default__dark",
        "Checkbox · Checked (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "checkbox__ideal__unchecked__light",
        "Checkbox · Unchecked (light)",
        state = "unchecked",
        theme = "light",
      ),
      ServePreview(
        "checkbox__ideal__unchecked__dark",
        "Checkbox · Unchecked (dark)",
        state = "unchecked",
        theme = "dark",
      ),
      ServePreview(
        "radiobutton__ideal__default__light",
        "Radio · Selected (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "radiobutton__ideal__default__dark",
        "Radio · Selected (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "radiobutton__ideal__unselected__light",
        "Radio · Unselected (light)",
        state = "unselected",
        theme = "light",
      ),
      ServePreview(
        "radiobutton__ideal__unselected__dark",
        "Radio · Unselected (dark)",
        state = "unselected",
        theme = "dark",
      ),
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

  // An app catalog whose previews carry a `section` (the tab) + `group` (the sub-heading) + an
  // authored `catalogOrder` — the tabbed-landing structure meshcore-mobile publishes. Three
  // sections
  // (Themes / Components / Screens) with sub-groups inside, and the group name "Device" reused
  // across
  // two sections (scoped per tab) so the fixture exercises that. Ordered by catalogOrder so the
  // tabs
  // read Themes → Components → Screens as authored, not id-sorted.
  private val sectionedPreviews =
    listOf(
      ServePreview(
        "theme-meshcore-light__ideal__default__compact",
        "Theme · MeshCore (light)",
        section = "Themes",
        group = "Foundation",
        catalogOrder = 0,
      ),
      ServePreview(
        "theme-material3-light__ideal__default__compact",
        "Theme · Material 3 (light)",
        section = "Themes",
        group = "Foundation",
        catalogOrder = 1,
      ),
      ServePreview(
        "devicesummarycard-populated__ideal__default__compact",
        "Device summary · Populated",
        section = "Components",
        group = "Device",
        catalogOrder = 2,
      ),
      ServePreview(
        "devicesummarycard-loading__ideal__default__compact",
        "Device summary · Loading",
        section = "Components",
        group = "Device",
        catalogOrder = 3,
      ),
      ServePreview(
        "contactrow-variants__ideal__default__compact",
        "Contact row · Variants",
        section = "Components",
        group = "Contacts",
        catalogOrder = 4,
      ),
      ServePreview(
        "contactlist-many__ideal__default__compact",
        "Contact list · Many",
        section = "Components",
        group = "Contacts",
        catalogOrder = 5,
      ),
      ServePreview(
        "scanner-savedpopulated__ideal__default__compact",
        "Scanner · Saved populated",
        section = "Screens",
        group = "Scanner",
        catalogOrder = 6,
      ),
      ServePreview(
        "scanner-blemany__ideal__default__compact",
        "Scanner · BLE many",
        section = "Screens",
        group = "Scanner",
        catalogOrder = 7,
      ),
      ServePreview(
        "device-manycontacts__ideal__default__compact",
        "Device · Many contacts",
        section = "Screens",
        group = "Device",
        catalogOrder = 8,
      ),
    )

  // A component (Button/Filled) whose default render carries baked PROPS-axis variants — an RTL
  // render, an ar-XB pseudo-locale, and a 2× font-scale — each in light + dark, tagged via the
  // `props` metadata the `previews/variants.json` manifest now carries (the i18n/a11y axes the
  // compose-m3 catalog folds via `variants`). The landing folds each component to ONE (default)
  // card; the viewer grows a second `<nav class="cp-states" aria-label="Component variant">`
  // switcher to the component's other same-theme variants. Captured so the visual-diff bot covers
  // the variant fold + switcher end-to-end (the fix for the "duplicate RTL/locale tiles" the
  // imported M3 tabs showed).
  private val variantPreviews =
    listOf(
      ServePreview(
        "button-filled__ideal__default__light",
        "Button · Filled (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "button-filled__ideal__default__dark",
        "Button · Filled (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "button-filled__ideal__default__light__direction-rtl",
        "Button · Filled · RTL (light)",
        state = "default",
        theme = "light",
        props = mapOf("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__direction-rtl",
        "Button · Filled · RTL (dark)",
        state = "default",
        theme = "dark",
        props = mapOf("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__locale-ar-xb",
        "Button · Filled · ar-XB (light)",
        state = "default",
        theme = "light",
        props = mapOf("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__locale-ar-xb",
        "Button · Filled · ar-XB (dark)",
        state = "default",
        theme = "dark",
        props = mapOf("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__fontscale-2.0",
        "Button · Filled · 2× font (light)",
        state = "default",
        theme = "light",
        props = mapOf("fontScale" to "2.0"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__fontscale-2.0",
        "Button · Filled · 2× font (dark)",
        state = "default",
        theme = "dark",
        props = mapOf("fontScale" to "2.0"),
      ),
    )

  // A section-LESS catalog whose components fall into families (button ×3, card ×2, plus singleton
  // fab / badge). Authors no `section` metadata, so the landing can't tab it — instead ServeWeb
  // *synthesizes* family sub-group dividers (Button / Card / FAB / Badge) so a large flat catalog
  // reads as grouped clusters. Each component carries a light+dark pair, so the golden also
  // exercises the sticky theme toggle inside the synthesized groups. Captured so the visual-diff
  // bot covers the synthesized-grouping layout.
  private val groupedPreviews =
    listOf("button-filled", "button-outlined", "button-tonal", "card-elevated", "card-filled")
      .flatMap { slug ->
        val name = slug.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
        listOf("light", "dark").map { theme ->
          ServePreview("${slug}__ideal__default__$theme", "$name ($theme)", theme = theme)
        }
      } +
      listOf(
        ServePreview("fab__ideal__default__light", "FAB (light)", theme = "light"),
        ServePreview("fab__ideal__default__dark", "FAB (dark)", theme = "dark"),
        ServePreview("badge__ideal__default__light", "Badge (light)", theme = "light"),
        ServePreview("badge__ideal__default__dark", "Badge (dark)", theme = "dark"),
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
        hasHomeIndex = true,
        version = version,
        provenance = provenance,
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
            // Wear is dark-first: the hero backs on the dark stage, not the default white.
            darkStage = true,
          ),
          ServeWeb.HomeSystem(
            system = "remote-m3",
            title = "Remote Compose Material 3",
            subtitle = "androidx.wear.compose.remote:remote-material3",
            previewCount = 6,
            trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
            heroPreviewId = "Button-Filled__ideal__default__light",
          ),
          // App systems published UNLISTED from their own repos but promoted to the LISTED set
          // (`--catalogs`), so they show on the front door alongside the design systems.
          ServeWeb.HomeSystem(
            system = "meshcore-mobile",
            title = "MeshCore",
            subtitle = "ee.schimke.meshcore",
            previewCount = 33,
            trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
            heroPreviewId = "device-manycontacts__ideal__default__compact",
          ),
          ServeWeb.HomeSystem(
            system = "homeassistant-remotecompose",
            title = "HomeAssistant RemoteCompose",
            subtitle = "ee.schimke.homeassistant",
            previewCount = 9,
            trust =
              "branch:yschimke/homeassistant-remotecompose@design-artifacts/homeassistant-remotecompose",
            heroPreviewId = null,
          ),
          // A Wear app (Confetti): dark-first stage, and its hero is a conference SCREEN — the most
          // representative view of the app — rather than a single component.
          ServeWeb.HomeSystem(
            system = "confetti-wear",
            title = "Confetti (Wear)",
            subtitle = "dev.johnoreilly.confetti",
            previewCount = 12,
            trust = "branch:joreilly/Confetti@design-artifacts/confetti-wear",
            heroPreviewId = "conference-screen__ideal__default__dark",
            darkStage = true,
          ),
        ),
        token,
        isPublic = true,
        version = version,
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
        hasHomeIndex = true,
        basePath = "/meshcore-mobile",
        version = version,
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
        hasHomeIndex = true,
        version = version,
      )
    // A catalog whose components carry baked non-default states: the landing folds each to ONE card
    // (the default), the non-default states reachable via the viewer switcher.
    val landingStates =
      ServeWeb.landingPage(
        "compose-m3",
        statefulPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // An app catalog served under its path (/meshcore-mobile/) whose previews carry sections: the
    // landing renders a TAB BAR (Themes / Components / Screens) over per-section panels, each with
    // its `group` sub-headings. Captured so the visual-diff bot covers the tabbed structure.
    val landingSections =
      ServeWeb.landingPage(
        "meshcore-mobile",
        sectionedPreviews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/meshcore-mobile",
        version = version,
      )
    // The default-state viewer for that catalog: renders the `<nav class="cp-states">` switcher of
    // links to the component's other same-theme states, the current (Default) state marked active.
    val viewerStates =
      ServeWeb.viewerPage(
        statefulPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = statefulPreviews,
      )
    // A catalog whose component carries baked PROPS-axis variants (RTL / pseudo-locale / large
    // font): the landing folds the eight renders to ONE (default) card, the variants reachable via
    // the viewer's variant switcher.
    val landingVariants =
      ServeWeb.landingPage(
        "compose-m3",
        variantPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // The default-render viewer for that catalog: renders the `<nav aria-label="Component
    // variant">`
    // switcher of links to the component's other same-theme variants, the current (Default) marked
    // active.
    val viewerVariants =
      ServeWeb.viewerPage(
        variantPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = variantPreviews,
      )
    // A section-less catalog rendered with SYNTHESIZED family sub-groups (Button / Card / FAB /
    // Badge dividers over the flat grid) — the fix for a large ungrouped catalog reading as one
    // undivided wall. Captured so the visual-diff bot covers the synthesized-grouping layout.
    val landingGrouped =
      ServeWeb.landingPage(
        "compose-m3",
        groupedPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // A viewer whose sibling list spans several components each with many baked variants (a
    // button-filled with RTL/locale/font variants, plus checkbox/radiobutton states). The component
    // nav COLLAPSES to one entry per component (button-filled once, not ~8 times), mirroring the
    // grid. Captured so the visual-diff bot covers the de-duplicated nav drawer.
    val viewerNavCollapsed =
      ServeWeb.viewerPage(
        variantPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = variantPreviews + statefulPreviews,
      )
    // The styled 404 a browser gets when it follows a dead link to a catalog or preview page —
    // the site's own chrome with a "back to design systems" link, not a bare text/plain dead-end.
    val notFound =
      ServeWeb.notFoundPage("That preview does not exist in this catalog.", token, isPublic = true)

    // The server STATUS page (GET /status): a snapshot of the running host — published catalogs +
    // their trust/liveness, the render daemons up now, the effective config, and recent daemon
    // startup failures. A representative spread (a live+running catalog, a degraded baked one, an
    // unlisted one, a running desktop daemon, and one recent failure so the amber "degraded" badge
    // +
    // failure table are captured) with fixed figures so the golden stays stable across runs.
    val serveStatus =
      ServeWeb.statusPage(
        token = token,
        view =
          ServeWeb.StatusView(
            version = version,
            public = true,
            overallOk = false,
            summary =
              listOf(
                ServeWeb.Stat("Catalogs", "3"),
                ServeWeb.Stat("Live daemons running", "1"),
                ServeWeb.Stat("Active streams", "2"),
                ServeWeb.Stat("Live seats", "3 free / 5"),
                ServeWeb.Stat("Known sessions", "4"),
                ServeWeb.Stat("Uptime", "3d 4h"),
              ),
            config =
              listOf(
                ServeWeb.Stat("Access", "public (open)"),
                ServeWeb.Stat("Bind", "0.0.0.0:8080"),
                ServeWeb.Stat("Trusted re-render", "on"),
                ServeWeb.Stat("Trust store", "configured"),
                ServeWeb.Stat("Catalog refresh", "600s"),
                ServeWeb.Stat("Live seats", "5"),
                ServeWeb.Stat("Render slots", "4"),
                ServeWeb.Stat("Accept uploads", "off"),
              ),
            catalogs =
              listOf(
                ServeWeb.StatusCatalog(
                  id = "compose-m3",
                  title = "Compose Material 3",
                  listed = true,
                  trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
                  previews = 42,
                  live = true,
                  running = true,
                  degradation = null,
                  provenance =
                    "yschimke/compose-ai-tools@design-artifacts/compose-m3 · 2026-07-17T09:30:00.000Z",
                ),
                ServeWeb.StatusCatalog(
                  id = "remote-m3",
                  title = "Remote Compose Material 3",
                  listed = true,
                  trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
                  previews = 6,
                  live = false,
                  running = false,
                  degradation = "this delivery branch publishes no live bundle this server can run",
                  provenance =
                    "yschimke/compose-ai-tools@design-artifacts/remote-m3 · 2026-07-17T09:30:00.000Z",
                ),
                ServeWeb.StatusCatalog(
                  id = "cadence",
                  title = "Cadence",
                  listed = false,
                  trust = "unverified",
                  previews = 11,
                  live = true,
                  running = false,
                  degradation = null,
                  provenance = null,
                ),
              ),
            servers =
              listOf(
                ServeWeb.StatusServer(
                  id = "compose-m3",
                  label = "compose-m3 (live bundle)",
                  backend = "desktop",
                  activeStreams = 2,
                  upForText = "12m 5s",
                )
              ),
            failures =
              listOf(
                ServeWeb.StatusFailure(
                  whenText = "2026-07-17 09:41 UTC",
                  session = "wear-m3",
                  reason = "daemon launch timed out after 300s",
                )
              ),
          ),
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
      File(pagesDir, "serve-landing-states.html").writeText(landingStates)
      File(pagesDir, "serve-landing-sections.html").writeText(landingSections)
      File(pagesDir, "serve-viewer-states.html").writeText(viewerStates)
      File(pagesDir, "serve-status.html").writeText(serveStatus)
      File(pagesDir, "serve-landing-variants.html").writeText(landingVariants)
      File(pagesDir, "serve-viewer-variants.html").writeText(viewerVariants)
      File(pagesDir, "serve-landing-grouped.html").writeText(landingGrouped)
      File(pagesDir, "serve-viewer-nav-collapsed.html").writeText(viewerNavCollapsed)
      File(pagesDir, "serve-notfound.html").writeText(notFound)
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
    assertGolden(File(pagesDir, "serve-landing-states.html"), landingStates)
    assertGolden(File(pagesDir, "serve-landing-sections.html"), landingSections)
    assertGolden(File(pagesDir, "serve-viewer-states.html"), viewerStates)
    assertGolden(File(pagesDir, "serve-status.html"), serveStatus)
    // The status page leads with the header health badge, links to the machine-readable JSON, and
    // renders the catalog / running-daemon / failure tables. A recent failure ⇒ the amber
    // "degraded" badge; a live+running catalog reads "live · running"; a baked one shows its
    // reason.
    assertTrue(
      serveStatus.contains("Server status") && serveStatus.contains("href=\"/status.json\""),
      "status page headers the status and links its JSON form",
    )
    assertTrue(
      serveStatus.contains("recent daemon failure(s)") &&
        serveStatus.contains("daemon launch timed out after 300s"),
      "a recent failure surfaces the degraded badge and the failure row",
    )
    assertTrue(
      serveStatus.contains("live · running") && serveStatus.contains("baked PNG"),
      "the catalog table distinguishes a running live catalog from a baked one",
    )
    assertGolden(File(pagesDir, "serve-landing-variants.html"), landingVariants)
    assertGolden(File(pagesDir, "serve-viewer-variants.html"), viewerVariants)
    // The variant landing folds the component's props-axis renders out: eight renders yield ONE
    // (default) swap card, and no RTL / locale / fontscale variant is emitted as its own card.
    assertEquals(
      1,
      Regex("class=\"cp-card\"").findAll(landingVariants).count(),
      "the component folds to a single default card despite its props variants",
    )
    assertFalse(
      landingVariants.contains("direction-rtl") ||
        landingVariants.contains("locale-ar-xb") ||
        landingVariants.contains("fontscale-2.0"),
      "props variants are folded out of the variant landing grid",
    )
    // The default-render viewer renders the variant switcher, marking Default active and linking
    // the
    // same-theme RTL sibling, never the dark render.
    val variantNav =
      viewerVariants.substringAfter("aria-label=\"Component variant\"").substringBefore("</nav>")
    assertTrue(
      variantNav.contains("aria-current=\"page\">Default</a>") &&
        variantNav.contains("/p/button-filled__ideal__default__light__direction-rtl") &&
        variantNav.contains(">RTL</a>"),
      "the viewer variant switcher marks Default active and links the same-theme RTL variant",
    )
    assertFalse(
      variantNav.contains("__dark__direction-rtl"),
      "the variant switcher stays within the current theme",
    )
    // A sectioned catalog renders a tab bar (role=tablist) with one tab per section, in authored
    // order (Themes → Components → Screens), each carrying its card count; a flat catalog shows
    // none.
    assertTrue(
      landingSections.contains("class=\"cp-tabs\"") && landingSections.contains("role=\"tablist\""),
      "a sectioned catalog renders the tab bar",
    )
    val tabOrder =
      Regex("data-tab=\"([a-z0-9-]+)\"").findAll(landingSections).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf("themes", "components", "screens"),
      tabOrder,
      "tabs are ordered by authored catalogOrder, not id-sorted",
    )
    // Each section is a role=tabpanel keyed by its slug, and the first tab opens selected.
    assertTrue(
      landingSections.contains("id=\"cp-panel-themes\" role=\"tabpanel\"") &&
        landingSections.contains("id=\"cp-panel-components\" role=\"tabpanel\"") &&
        landingSections.contains("id=\"cp-panel-screens\" role=\"tabpanel\""),
      "each section renders a tabpanel",
    )
    assertTrue(
      landingSections.contains(
        "id=\"cp-tab-themes\" href=\"#cp-panel-themes\" data-tab=\"themes\"" +
          " aria-controls=\"cp-panel-themes\" aria-selected=\"true\""
      ),
      "the first tab is selected and its anchor targets its panel",
    )
    // The `group` renders as a sub-heading inside a tab — including the same "Device" group name
    // reused across the Components and Screens sections (scoped per tab, not merged).
    assertTrue(
      landingSections.contains("<h3 class=\"cp-group-head\">Foundation</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Contacts</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Scanner</h3>"),
      "component groups render as sub-headings within their section tab",
    )
    assertEquals(
      2,
      Regex("<h3 class=\"cp-group-head\">Device</h3>").findAll(landingSections).count(),
      "a group name reused across sections stays scoped per tab (one sub-heading each)",
    )
    // The tab JS is wired (adds cp-js, drives the tabs); a flat catalog's script omits all of it.
    assertTrue(
      landingSections.contains("classList.add(\"cp-js\")") &&
        landingSections.contains("querySelectorAll(\".cp-tab\")"),
      "the sectioned landing wires the tab-switching script",
    )
    // `role="tablist"` (the tab bar) and `classList.add("cp-js")` (the tab script) appear ONLY when
    // tabs are rendered — the shared stylesheet's `.cp-tabs` / `html.cp-js` rules are on every
    // page,
    // so this checks the markup/script, not the CSS.
    assertFalse(
      landingThemed.contains("role=\"tablist\"") ||
        landingThemed.contains("classList.add(\"cp-js\")"),
      "a flat (section-less) catalog renders no tab bar and no tab script",
    )
    // The state landing folds each component's non-default states out: checkbox + radio yield ONE
    // card each (two total), and no `unchecked`/`unselected` card is emitted.
    assertEquals(
      2,
      Regex("class=\"cp-card\"").findAll(landingStates).count(),
      "each component folds to a single default card",
    )
    assertFalse(
      landingStates.contains("unchecked") || landingStates.contains("unselected"),
      "non-default states are folded out of the state landing grid",
    )
    // The default-state viewer renders the state switcher, marking Default active and linking the
    // same-theme unchecked sibling.
    val statesNav = viewerStates.substringAfter("class=\"cp-states\"").substringBefore("</nav>")
    assertTrue(
      statesNav.contains("aria-current=\"page\">Default</a>") &&
        statesNav.contains("/p/checkbox__ideal__unchecked__light"),
      "the viewer state switcher marks Default active and links the same-theme sibling",
    )
    assertGolden(File(pagesDir, "serve-landing-grouped.html"), landingGrouped)
    // A section-less catalog gains SYNTHESIZED family sub-group dividers (as <h2 cp-group-head>)
    // over
    // a flat grid — no tab bar — so a large ungrouped catalog reads as clustered families.
    assertTrue(
      landingGrouped.contains("class=\"cp-grid-groups\"") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">Button</h2>") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">Card</h2>") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">FAB</h2>"),
      "a section-less catalog renders synthesized family sub-group dividers",
    )
    assertFalse(
      landingGrouped.contains("role=\"tablist\""),
      "synthesized family grouping renders no tab bar (it is a flat grouped grid, not tabs)",
    )
    assertGolden(File(pagesDir, "serve-viewer-nav-collapsed.html"), viewerNavCollapsed)
    // The component nav collapses to ONE entry per component: button-filled's ~8 baked variants +
    // checkbox/radiobutton states yield exactly three nav items, button-filled listed once.
    val collapsedNav =
      viewerNavCollapsed.substringAfter("id=\"cp-nav-list\"").substringBefore("</ul>")
    assertEquals(
      3,
      Regex("class=\"cp-nav-item\"").findAll(collapsedNav).count(),
      "the component nav lists one entry per component, not per baked variant",
    )
    assertEquals(
      1,
      Regex("href=\"[^\"]*button-filled").findAll(collapsedNav).count(),
      "the multi-variant component appears exactly once in the nav",
    )
    assertGolden(File(pagesDir, "serve-notfound.html"), notFound)
    // The 404 is a full styled document with a heading, the message, and a link back home — not a
    // bare text/plain dead-end.
    assertTrue(
      notFound.contains("<!doctype html>") &&
        notFound.contains("<h1 class=\"cp-head\">Not found</h1>") &&
        notFound.contains("That preview does not exist in this catalog.") &&
        notFound.contains("class=\"cp-back\""),
      "the 404 page is styled chrome with a back-home link",
    )
    // Every page wraps its content in a single <main> landmark and leads with an <h1>.
    for ((name, html) in
      listOf("home" to homeIndex, "landing" to landingPublic, "viewer" to viewer)) {
      assertEquals(
        1,
        Regex("<main class=\"cp-main\">").findAll(html).count(),
        "the $name page has exactly one <main> landmark",
      )
      assertTrue(html.contains("<h1 class=\"cp-head\""), "the $name page leads with an <h1>")
    }
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
    // meshcore-mobile + homeassistant-remotecompose are LISTED (`--catalogs`), so they show on the
    // front door in the "Design systems" grid — served from their own repos.
    assertTrue(
      homeIndex.contains("href=\"/meshcore-mobile/\"") &&
        homeIndex.contains("href=\"/homeassistant-remotecompose/\""),
      "listed app systems appear on the front door with their /<system>/ links",
    )
    assertTrue(
      homeIndex.contains("MeshCore"),
      "a listed app shows its human title on the front door",
    )
    // An UNLISTED catalog (cadence) is served at /<system>/ but kept OFF the front door: the home
    // index carries no separate "Apps" section, so publishing it doesn't advertise it on the
    // landing.
    assertFalse(
      homeIndex.contains("<p class=\"cp-head\">Apps</p>"),
      "the front door has no Apps section — unlisted catalogs are not indexed",
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
    // When the catalog carries screens (an app, not a component library), a Screens-section preview
    // is the hero — the most representative view — beating any single component, even a filled
    // button.
    assertEquals(
      "conference-screen__ideal__default__dark",
      ServeWeb.representativePreviewId(
        listOf(
          ServePreview("button-filled__ideal__default__light", "Filled default"),
          ServePreview(
            "conference-screen__ideal__default__dark",
            "Conference",
            section = "Screens",
          ),
          ServePreview("bookmarks-screen__ideal__default__dark", "Bookmarks", section = "Screens"),
        )
      ),
      "a catalog with screens fronts a screen, not a component",
    )
    // The dark stage is DECLARED by the catalog (display.surface) first; the system-name heuristic
    // is only the fallback, so nothing is hardcoded per app.
    assertTrue(
      ServeWeb.SystemDisplay.resolveDarkFirst("anything", "dark"),
      "a declared dark surface wins regardless of the system name",
    )
    assertFalse(
      ServeWeb.SystemDisplay.resolveDarkFirst("wear-m3", "light"),
      "a declared light surface overrides the wear-name dark-first heuristic",
    )
    assertTrue(
      ServeWeb.SystemDisplay.resolveDarkFirst("confetti-wear", null),
      "fallback: a Wear/watch system id is dark-first when nothing is declared",
    )
    assertFalse(
      ServeWeb.SystemDisplay.resolveDarkFirst("compose-m3", null),
      "fallback: a non-Wear system stays on the light stage",
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
    // The badge now prefixes a lane icon (▶ live / ▪ static) — the visible signal the Static⇄Live
    // toggle flipped — but still hard-codes the CMP-WASM tier label for the in-browser app.
    assertTrue(
      wasmViewer.contains("\"▶ CMP-WASM\""),
      "badge hard-codes the wasm tier label with the live icon",
    )
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
    // The visible mode control is now a single Static⇄Live toggle; the transport radios (png / live
    // / wasm) live hidden behind it for the transition JS to drive. The wasm radio is present only
    // when a wasm app backs the session.
    assertTrue(
      withWasm.contains("id=\"cp-live-toggle\""),
      "expected the single Static⇄Live preview toggle",
    )
    assertTrue(
      withWasm.contains("name=\"cp-mode\" value=\"png\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"live\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"wasm\""),
      "expected the hidden png / live / wasm transport radios",
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
    // The old "Component only (no background)" wasm checkbox was removed — it was confusing (it
    // three-way-cycled the background), so the in-browser app now always renders its themed
    // background.
    assertFalse(withWasm.contains("id=\"cp-wasm-bg\""), "the Component-only toggle is gone")
    assertFalse(
      withWasm.contains("Component only"),
      "no Component-only option (the app renders its own background)",
    )
    assertFalse(
      withWasm.contains("\"background=off\""),
      "no background=off forwarded to the app anymore",
    )

    // No wasmSrc → snapshot viewer has no Wasm mode: png + live mode inputs only, no Wasm
    // input/iframe.
    val plain = ServeWeb.viewerPage(card, token)
    assertTrue(!plain.contains("name=\"cp-mode\" value=\"wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm-bg\""))
    assertTrue(plain.contains("name=\"cp-mode\" value=\"png\""), "png mode input always present")
  }

  @Test
  fun `a dedicated In-browser Wasm toggle shows only when a wasm app and a daemon lane are both present`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // Case C — daemon live lane + wasm app: "Live preview" would prefer the daemon (bestLiveMode)
    // and hide the wasm lane, so a distinct "In-browser (Wasm)" toggle is added beside it.
    val both =
      ServeWeb.viewerPage(
        card,
        token,
        sessionId = "compose-m3",
        hasLiveStream = true,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      both.contains("id=\"cp-wasm-btn\"") && both.contains("In-browser (Wasm)"),
      "with both a daemon lane and a wasm app, the viewer adds the In-browser (Wasm) toggle",
    )
    assertTrue(
      both.contains("id=\"cp-live-toggle\""),
      "the daemon 'Live preview' toggle stays alongside it",
    )
    // While the in-browser lane is active, the daemon-only controls (size/device/orientation/
    // background + the app-theme selector) can't be honoured by the iframe, so syncServerControls
    // disables them on the wasm lane rather than leaving dead-but-enabled knobs.
    assertTrue(
      both.contains("var onWasm = wasmActive();") &&
        both.contains("!onWasm && (!staticSnapshot || canRenderOverrides"),
      "server-only controls are gated off while the Wasm lane is active",
    )
    assertTrue(
      both.contains("themeProviderEl.disabled = onWasm ||"),
      "the app-theme selector is disabled while the Wasm lane is active",
    )

    // Case B — wasm app but NO daemon lane: the single Static⇄Live toggle already drops into wasm
    // as
    // its only interactive lane, so a separate button would be redundant.
    val wasmOnly = ServeWeb.viewerPage(card, token, wasmSrc = "/wasm/compose-m3/?id=card-filled")
    assertFalse(
      wasmOnly.contains("id=\"cp-wasm-btn\""),
      "a wasm-only session keeps the single toggle (no redundant In-browser button)",
    )

    // Case A — daemon lane but no wasm app: nothing to add.
    val daemonOnly = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertFalse(
      daemonOnly.contains("id=\"cp-wasm-btn\""),
      "a daemon-only session shows no In-browser (Wasm) toggle",
    )
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
  fun `SVG is an on-screen format toggle and an export format when the session can export SVG`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // SVG isn't part of the awkward PNG/live radio group any more, but it's still an on-screen
    // format: a dedicated toggle beside the Live toggle swaps the static snapshot between the
    // raster
    // PNG and the vector SVG. Offered only when the session can export SVG (hasSvgExport).
    val svgView = ServeWeb.viewerPage(card, token, hasSvgExport = true)
    assertTrue(
      svgView.contains("id=\"cp-svg-toggle\"") && svgView.contains("class=\"cp-fmt-toggle\""),
      "an SVG-exporting session offers the on-screen SVG format toggle",
    )
    // The SVG lane reuses the snapshot <img> but swaps the render extension; the viewer JS carries
    // the snapshotExt seam and stamps the backend badge with SVG.
    assertTrue(
      svgView.contains("var snapshotExt = \".png\";") && svgView.contains("? \".svg\" : \".png\""),
      "the snapshot lane flips its render extension between PNG and SVG",
    )
    assertTrue(
      svgView.contains("if (mode === \"svg\") return \"▪ SVG\";"),
      "the backend badge names the SVG lane",
    )
    // The SVG export also surfaces as a copyable/downloadable URL row plus the "Full page (scroll)"
    // toggle.
    assertTrue(
      svgView.contains("id=\"cp-url-svg\"") && svgView.contains("id=\"cp-scroll-long\""),
      "an SVG-exporting session offers the SVG download row and its Full-page toggle",
    )

    // No SVG export → no SVG toggle, no SVG URL row, and no scroll toggle.
    val plain = ServeWeb.viewerPage(card, token)
    assertFalse(plain.contains("id=\"cp-svg-toggle\""), "no SVG toggle without SVG export")
    assertFalse(plain.contains("id=\"cp-url-svg\""), "no SVG export row without SVG support")
    assertFalse(plain.contains("id=\"cp-scroll-long\""), "no Full-page toggle without SVG export")
  }

  @Test
  fun `pages are mobile-responsive with a viewport meta and a narrow breakpoint`() {
    // Every page carries the viewport meta (so mobile browsers don't zoom out to a desktop width)
    // and the shared stylesheet includes the narrow breakpoint that collapses the viewer's
    // stage + overrides row into a single stacked column and drops the flex items' min-width so
    // nothing overflows a ~320px screen.
    // A representative viewer with siblings (so the component nav drawer is present too).
    val viewer = ServeWeb.viewerPage(previews.first(), token, siblings = previews)
    assertTrue(
      viewer.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"),
      "the page declares a mobile viewport",
    )
    assertTrue(
      viewer.contains("@media (max-width: 640px) {"),
      "the stylesheet has a narrow-viewport breakpoint",
    )
    assertTrue(
      viewer.contains(".cp-stage, .cp-controls, .cp-nav { flex: 1 1 100%; min-width: 0; }"),
      "stage/overrides/nav stack full-width and drop their min-width on a phone",
    )
    // Usability: on mobile the two drawers become bottom sheets reachable from a sticky toggle bar
    // (so overrides + the component list are one tap away, not a long scroll below a tall preview),
    // with a scrim behind the open sheet.
    assertTrue(
      viewer.contains(".cp-viewer-bar { position: sticky; top: 0;"),
      "the drawer toggle bar is sticky on mobile",
    )
    assertTrue(
      viewer.contains(".cp-viewer.cp-controls-open .cp-controls,") &&
        viewer.contains("position: fixed; left: 0; right: 0; bottom: 0;"),
      "open drawers render as fixed bottom sheets on mobile",
    )
    assertTrue(
      viewer.contains("id=\"cp-scrim\"") && viewer.contains(".cp-scrim.cp-scrim-on"),
      "a dismiss scrim backs the open bottom sheet",
    )
    // The overrides drawer collapses on load on a phone so the preview leads (JS-driven; the
    // server markup still defaults it open for desktop).
    assertTrue(
      viewer.contains("if (isMobile()) setOpen(\"cp-controls-open\", false);"),
      "the overrides drawer starts collapsed on a phone",
    )
    // The breakpoint ships on the landing pages too (shared stylesheet).
    val landing = ServeWeb.landingPage(moduleLabel, previews, token)
    assertTrue(landing.contains("@media (max-width: 640px) {"), "landing is responsive too")
  }

  @Test
  fun `a catalog landing shows a back-to-home button instead of a sideways catalog nav`() {
    // A server that publishes a home index (hasHomeIndex) replaces the old design-systems nav row
    // with a single back button that links HOME (the front-door index at /), token-gated here. The
    // flag — not a catalog list — gates it, so an app-only server (--catalogs-unlisted, no listed
    // catalogs) whose landings still have a home index keeps a way back.
    val front = ServeWeb.landingPage(moduleLabel, previews, token, hasHomeIndex = true)
    assertFalse(front.contains("class=\"cp-systems\""), "the sideways design-systems nav is gone")
    assertTrue(
      front.contains("class=\"cp-back\" href=\"/?token=$token\""),
      "a landing with a home index links back to it",
    )
    // No sideways links to the other catalogs any more.
    assertFalse(
      front.contains("href=\"/wear-m3/?token=$token\""),
      "no sideways link to sibling catalogs",
    )

    // Public mode: the back button is token-free (every route is open).
    val public =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, hasHomeIndex = true)
    assertTrue(
      public.contains("class=\"cp-back\" href=\"/\""),
      "the public back button is token-free",
    )

    // No home index → no back button (a plain, single-module `serve` with nothing to go back to).
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-back\""),
      "a plain module landing shows no back button",
    )
  }

  @Test
  fun `a catalog landing shows the provenance strip with branch, date, versions and regenerate`() {
    val landing =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        provenance =
          ServeWeb.CatalogProvenance(
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
            generatedAt = "2026-07-17T09:30:00.000Z",
            toolVersion = "0.16.54",
            designParityVersion = "0.1.25",
          ),
      )
    assertTrue(landing.contains("class=\"cp-prov\""), "the provenance strip renders")
    // Links to the delivery branch and the regenerating workflow.
    assertTrue(
      landing.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/tree/design-artifacts/compose-m3\""
      ),
      "the strip links the delivery branch on GitHub",
    )
    assertTrue(
      landing.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/actions/workflows/design-artifacts.yml\""
      ),
      "the strip links the regenerating workflow",
    )
    // Friendly generation date + both tool versions.
    assertTrue(landing.contains("2026-07-17 09:30 UTC"), "the generation date is shown")
    assertTrue(
      landing.contains("compose-ai-tools <code>0.16.54</code>") &&
        landing.contains("design-parity <code>0.1.25</code>"),
      "both generating tool versions are shown",
    )
    // No provenance passed → no strip (a plain bundle / non-catalog module).
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true)
        .contains("class=\"cp-prov\""),
      "a landing without provenance shows no strip",
    )
  }

  @Test
  fun `the about box surfaces the server version with a GitHub icon`() {
    val home =
      ServeWeb.homeIndexPage(
        listOf(
          ServeWeb.HomeSystem(
            system = "compose-m3",
            title = "Compose Material 3",
            subtitle = null,
            previewCount = 1,
            trust = null,
            heroPreviewId = null,
          )
        ),
        token,
        isPublic = true,
        version = "1.2.3",
      )
    assertTrue(home.contains(">server v1.2.3<"), "the running server version is shown")
    assertTrue(home.contains("class=\"cp-gh\""), "the source link carries the GitHub icon")
    // A null version simply omits the pill (no dangling separator crash).
    val noVer = ServeWeb.homeIndexPage(emptyList(), token, isPublic = true)
    assertFalse(noVer.contains("class=\"cp-about-ver\""), "no version pill when version is null")
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
    assertTrue(
      staticView.contains("id=\"cp-live\" tabindex=\"-1\" disabled"),
      "live transport radio disabled",
    )
    // With no live lane at all, the single Static⇄Live toggle is itself disabled.
    assertTrue(
      staticView.contains("id=\"cp-live-toggle\"") &&
        staticView.contains("aria-pressed=\"false\" disabled"),
      "the Static⇄Live toggle is disabled on a pure static bundle",
    )
    assertTrue(
      staticView.contains("id=\"cp-uiMode\" disabled"),
      "Day/Night disabled without a Wasm app",
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
    assertTrue(wasmView.contains("id=\"cp-uiMode\">"), "Day/Night enabled with a Wasm app")
    assertTrue(
      wasmView.contains("step=\"0.1\" value=\"1.0\">"),
      "font scale enabled with a Wasm app",
    )
    assertTrue(wasmView.contains("autocomplete=\"off\">"), "locale enabled with a Wasm app")
    // Locale is a datalist-backed input, not a fixed <select>: presets drop down, but any BCP-47
    // tag the server accepts can still be typed (the reviewer's arbitrary-locale case, e.g. en-GB).
    assertTrue(
      wasmView.contains("id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\"") &&
        wasmView.contains("<datalist id=\"cp-localeTag-list\">") &&
        wasmView.contains("value=\"en-GB\""),
      "locale keeps free BCP-47 entry (datalist input with presets)",
    )
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
      wasmView.contains("if (wasmToggle) { setMode(\"wasm\"); return; }"),
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
      liveCatalogWasm.contains("id=\"cp-live\" tabindex=\"-1\">"),
      "live catalog leaves the live transport radio enabled (not disabled)",
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
    // A live catalog's carried daemon re-renders an override on demand (canRenderOverrides), so the
    // display controls (Size / Device / Locale / Orientation / Day-Night / …) render ENABLED right
    // in the static snapshot — editing one re-points /render, which the daemon serves freshly. This
    // is the fix for "most override modes disabled for CMP": they no longer sit greyed out until a
    // live stream is opened.
    assertTrue(
      catalogKnobs.contains("function syncServerControls()"),
      "the viewer has a syncServerControls() that keeps the display controls in sync",
    )
    assertTrue(
      catalogKnobs.contains("syncServerControls();"),
      "syncServerControls() is invoked on every mode transition",
    )
    assertTrue(
      catalogKnobs.contains("!staticSnapshot || canRenderOverrides || !!(live && live.checked)"),
      "display controls are live whenever the server can render an override (on-demand or streaming)",
    )
    // The server-render controls render ENABLED in the baked markup (canRenderOverrides), not
    // disabled-until-live: device, size, orientation, locale all take effect immediately via
    // on-demand /render.
    assertTrue(
      catalogKnobs.contains("id=\"cp-device\">") &&
        catalogKnobs.contains("id=\"cp-sizeMode\">") &&
        !catalogKnobs.contains(
          "id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\" placeholder=\"e.g. en-GB, zh-Hant-TW\" autocomplete=\"off\" disabled"
        ),
      "display controls (size/device/locale) render enabled on an on-demand-render catalog",
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
  fun `degrade banner explains why a session is snapshot-only and is absent when live`() {
    val degraded = listOf(ServeDegradation.catalogBakedOnly())

    // The catalog-level reason renders as a banner under the header on BOTH the landing and viewer
    // (checked on the rendered `class="cp-degrade"` section, since the CSS always defines the
    // class).
    val landing = ServeWeb.landingPage(moduleLabel, previews, token, degradations = degraded)
    assertTrue(landing.contains("class=\"cp-degrade\""), "expected a degradation banner")
    assertTrue(landing.contains("publishes no live bundle"), "expected the baked-only reason text")

    val viewer = ServeWeb.viewerPage(previews.first(), token, degradations = degraded)
    assertTrue(viewer.contains("class=\"cp-degrade\""), "expected the banner on the viewer too")
    assertTrue(viewer.contains("publishes no live bundle"))

    // A fully-live session (no degradations, the default) renders no banner section.
    assertTrue(
      !ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-degrade\""),
      "a live/undegraded session must not render a banner",
    )
    assertTrue(!ServeWeb.viewerPage(previews.first(), token).contains("class=\"cp-degrade\""))
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
