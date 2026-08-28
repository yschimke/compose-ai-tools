package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageFrame
import ee.schimke.composeai.designpages.PageImage
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.designpages.PageNodeConfidence
import ee.schimke.composeai.designpages.PageNodeLink
import ee.schimke.composeai.web.WebEscaping
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Golden generator + drift guard for the `serve` web surfaces captured by the preview-harness.
 *
 * `ServeWeb`'s landing + viewer pages are a *visual* surface, so — per the repo rule about wiring
 * new visual surfaces into the preview workflow — they're rendered to committed HTML fixtures under
 * `preview-server/preview-harness/fixtures/pages/`. The harness's `pages-snapshot.spec.mjs`
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
 *
 * Two fields are deliberately held constant in the goldens so that a diff only ever shows markup:
 * the server version ([version]) and the cache-busting hash in every asset href (see
 * [stableAssetHrefs]). Both are volatile, neither is a surface anyone reviews, and left alone they
 * made this test fail for reasons no reviewer could act on.
 *
 * **What these goldens do NOT prove.** Every page here is built by calling `ServeWeb` directly with
 * arguments this test chooses — which is what lets it capture states a live server can't easily be
 * put into (a pinned revision, published parity issues, a degraded session). The cost is that a
 * fixture says only "the renderer draws this when handed these arguments"; it says nothing about
 * whether the HTTP handler ever hands them over. That gap is not hypothetical: the viewer's
 * per-preview "report an issue" affordance was passed `reportIssue = null` by the real handler for
 * weeks while these goldens went on rendering it from this test's own `fixtureReportIssue`, so the
 * harness screenshotted an affordance nobody could click. Anything that must actually be *wired*
 * belongs in a route test against an embedded server — see [ServeViewerIssueReportRouteTest] and
 * [ServeBugReportRouteTest] — with the golden covering only how it looks.
 */
class ServeWebFixtureTest {

  private fun jsonProps(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
  }

  private fun assetText(name: String): String = ServeWebAssets.load(name)!!.bytes.decodeToString()

  /**
   * One node of the design-page fixture.
   *
   * No geometry: the SVG below is the geometry, and the viewer measures the `data-node-id` element
   * rather than reading a rectangle out of the manifest.
   */
  private fun pageNode(
    nodeId: String,
    name: String,
    code: String? = null,
    previewId: String? = null,
    link: PageNodeLink = PageNodeLink.MANIFEST,
    confidence: PageNodeConfidence? = if (code == null) null else PageNodeConfidence.HIGH,
    depth: Int = 3,
    type: String? = null,
  ) =
    PageNode(
      nodeId = nodeId,
      name = name,
      depth = depth,
      ref = "figma:ocdacdEsnHipMJD3egzxKb/$nodeId",
      code = code,
      previewId = previewId,
      link = link,
      confidence = confidence,
      type = type,
    )

  private val token = "demo-token-fixture"
  private val moduleLabel = ":samples:cmp"

  // A FIXED server version for the goldens: the footer surfaces the running build, but pinning a
  // constant here (rather than the real SERVE_VERSION) keeps the committed HTML stable across
  // releases — production passes SERVE_VERSION, the fixtures pass this.
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

  // The viewer's prefilled "report an issue" link, built the way the server builds it: against the
  // catalog's SOURCE repo, carrying the preview's facts and a token-free deep link, with the
  // signed-in visitor's login named in the tooltip.
  // The Figma node a preview is specified by, resolved the way the server resolves it — from a
  // Figma-backed design reference the catalog published, not from a hand-written URL. Mirrors a
  // real
  // meshcore-mobile design-map entry (`figma:gYzowY4cQ7rNr2gYoco1M6/73:6`).
  private val fixtureDesignReference =
    DesignReference(
      id = "contact-chat-figma",
      previewId = "com.example.ProfileScreenPreview",
      label = "Contact chat",
      raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
      source = DesignReferenceSource(provider = "figma", uri = "figma:gYzowY4cQ7rNr2gYoco1M6/73:6"),
    )

  private val fixtureFigmaSpec = ServeFigmaSpec.of(fixtureDesignReference)

  /**
   * The comparison wall's **page-scoped** report — the launcher's catalog half on a page that shows
   * every component and singles out none (issue #4289). No preview, no render, no reference: the
   * golden pins the shape a report filed from the wall actually has.
   */
  private fun fixtureWallReportIssue(): ServeWeb.ReportIssue =
    fixturePageReportIssue(
      "https://preview.coo.ee/compose-m3/compare?format=reference",
      "these comparisons",
    )

  /**
   * The page-scoped catalog report every catalog surface that names no single preview now carries
   * (issue #4704) — the wall, the landing, the pages index, a design page, the motion browser.
   */
  private fun fixturePageReportIssue(pageUrl: String, subject: String): ServeWeb.ReportIssue {
    val context =
      ServeIssueReport.Context(
        repo = "yschimke/compose-ai-tools",
        system = "compose-m3",
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        toolVersion = provenance.toolVersion,
        pageUrl = pageUrl,
        publicRender = true,
      )
    return ServeWeb.ReportIssue(
      action = ServeIssueReport.action(context.repo),
      body = ServeIssueReport.body(context),
      bodyTemplate = ServeIssueReport.body(context, renderPlaceholder = true),
      repo = context.repo,
      login = "yschimke",
      subject = subject,
    )
  }

  private fun fixtureReportIssue(
    previewId: String,
    label: String,
    sourceFile: String,
    componentId: String? = null,
    referenceId: String? = null,
    variant: String = "",
    overrides: Map<String, String> = emptyMap(),
    // The focused comparison serves the template with the selection placeholder in it, because that
    // is the one page carrying a selector. The viewer's report has no element to name.
    selectionPlaceholder: Boolean = false,
  ) =
    ServeIssueReport.Context(
        repo = "yschimke/compose-ai-tools",
        previewId = previewId,
        previewLabel = label,
        system = "compose-m3",
        componentId = componentId,
        referenceId = referenceId,
        variant = variant,
        overrides = overrides,
        sourceUrl =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            sourceFile,
          ),
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        toolVersion = provenance.toolVersion,
        viewerUrl =
          if (referenceId == null) "https://preview.coo.ee/compose-m3/p/$previewId" else null,
        comparisonUrl =
          referenceId?.let { "https://preview.coo.ee/compose-m3/compare/$previewId?reference=$it" },
        renderUrl =
          "https://preview.coo.ee/compose-m3/render/$previewId.png" +
            overrides.entries
              .sortedBy { it.key }
              .joinToString("&", prefix = if (overrides.isEmpty()) "" else "?") { (key, value) ->
                "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
              },
        // The goldens stand in for preview.coo.ee, whose render lane is token-free — so they
        // capture the embedded-image form of the body.
        publicRender = true,
      )
      .let { ctx ->
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(ctx.repo),
          body = ServeIssueReport.body(ctx),
          bodyTemplate =
            ServeIssueReport.body(
              ctx,
              renderPlaceholder = true,
              selectionPlaceholder = selectionPlaceholder,
            ),
          repo = ctx.repo,
          login = "yschimke",
        )
      }

  // The colour half of two REAL published token files (`design-artifacts/<system>/tokens.dtcg.json`
  // — the DTCG projection of the catalog's resolved MaterialTheme), trimmed to the roles the web
  // projection reads. Kept verbatim so the themed fixtures below are the palettes a visitor to
  // preview.coo.ee actually gets, not invented colours.
  private val wearM3Tokens =
    """
    {"color":{
      "primary":{"${'$'}type":"color","${'$'}value":"#4dd0e1ff"},
      "primaryContainer":{"${'$'}type":"color","${'$'}value":"#4d3d76ff"},
      "onPrimary":{"${'$'}type":"color","${'$'}value":"#210f48ff"},
      "onPrimaryContainer":{"${'$'}type":"color","${'$'}value":"#f6edffff"},
      "surface":{"${'$'}type":"color","${'$'}value":"#202124ff"},
      "onSurface":{"${'$'}type":"color","${'$'}value":"#f6edffff"},
      "surfaceContainerLow":{"${'$'}type":"color","${'$'}value":"#272430ff"},
      "surfaceContainer":{"${'$'}type":"color","${'$'}value":"#332e3cff"}
    }}
    """
      .trimIndent()

  private val jetNewsTokens =
    """
    {"color":{
      "primary":{"${'$'}type":"color","${'$'}value":"#bf0031ff"},
      "onPrimary":{"${'$'}type":"color","${'$'}value":"#ffffffff"},
      "primaryContainer":{"${'$'}type":"color","${'$'}value":"#ffdad9ff"},
      "surface":{"${'$'}type":"color","${'$'}value":"#fffbffff"},
      "onSurface":{"${'$'}type":"color","${'$'}value":"#201a1aff"}
    }}
    """
      .trimIndent()

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

  /**
   * A published `rc-compare` manifest over [previews] — the shape [ServeCatalogStore] stages from a
   * catalog's delivery branch, hand-built here so the golden pins the *page*, not a fetch.
   *
   * Deliberately uneven, because the uneven cases are what the wall exists to show: the JS player
   * scores worse than the two Compose players (it is a separate implementation), and cmp-wasm
   * refuses one document outright, so the fixture captures a rendered column, a scored column and a
   * "player could not decode this" column side by side.
   */
  private fun rcCompareFixture(previews: List<ServePreview>): RcCompareManifest {
    val lanes =
      listOf(
        RcCompareLane("baked", "AndroidX Embedded · baked", "baked"),
        RcCompareLane("js", "RC · JS player", "js"),
        RcCompareLane("embedded", "AndroidX Embedded · vendored Android", "vendored"),
        RcCompareLane("androidx-embedded", "AndroidX Embedded · androidx.dev", "androidx.dev"),
        RcCompareLane("cmp-jvm", "RC · cmp-jvm player", "cmp-jvm"),
        RcCompareLane("cmp-wasm", "RC · cmp-wasm player", "cmp-wasm"),
      )
    fun cell(lane: String, slot: Int, pct: Double?, px: Long?, note: String = "") =
      if (pct == null && note.isNotEmpty()) RcCompareCell(rendered = false, note = note)
      else
        RcCompareCell(
          rendered = true,
          render = "$lane/$slot.png",
          diff = if (lane == "baked") "" else "$lane-diff/$slot.png",
          mismatchPct = pct,
          mismatchPx = px,
        )
    return RcCompareManifest(
      lanes = lanes,
      rows =
        previews.mapIndexed { slot, preview ->
          val wasmRefuses = slot == 1
          RcCompareRow(
            previewId = preview.id,
            width = 400,
            height = 400,
            lanes =
              mapOf(
                "baked" to cell("baked", slot, null, null),
                "js" to cell("js", slot, 2.97 - slot * 0.4, 9116L - slot * 900),
                "embedded" to cell("embedded", slot, 0.03 + slot * 0.01, 24L + slot),
                "androidx-embedded" to
                  cell("androidx-embedded", slot, 0.4 + slot * 0.1, 640L + slot * 10),
                "cmp-jvm" to cell("cmp-jvm", slot, 1.09 + slot * 0.2, 5151L + slot * 40),
                "cmp-wasm" to
                  if (wasmRefuses)
                    cell(
                      "cmp-wasm",
                      slot,
                      null,
                      null,
                      "Document is not renderable by the CMP player: CoreText requires DataFont",
                    )
                  else cell("cmp-wasm", slot, 3.4 - slot * 0.3, 12040L - slot * 900),
              ),
          )
        },
    )
  }

  // A catalog whose components carry baked non-default STATES (checkbox checked/unchecked, radio
  // selected/unselected), each in light + dark, tagged via the `state`/`theme` metadata the
  // `previews/variants.json` manifest carries. The landing folds each component to ONE (default)
  // card; the viewer grows its `.cp-axes-tree` subtree reaching the component's other
  // same-theme states. Captured so the visual-diff bot covers the state toggle end-to-end.
  /**
   * A Wear-shaped catalog that documents each component at the FIVE screen sizes its kit declares —
   * the shape wear-m3-catalog publishes. Every render carries the breakpoint it was captured at
   * (`ServePreview.size`), so the landing folds the non-primary sizes onto one card per component
   * and the viewer offers them as a size switcher; without the fold this is 10 cards wearing 2
   * names (wear-m3-catalog#41).
   *
   * `alertdialog` also varies its button arrangement, so the fixture pins the two axes *crossed*:
   * the state rows have to keep holding the size fixed, and the size rows the state.
   */
  private val breakpointPreviews =
    listOf("192dp", "204dp", "216dp", "225dp", "240dp").flatMapIndexed { index, size ->
      listOf(
        ServePreview(
          "alertdialog__ideal__default__$size",
          "Alert Dialog · $size",
          componentId = "AlertDialog",
          state = "default",
          size = size,
          section = "Containment",
          group = "Dialogs",
          catalogOrder = index * 2,
          // The authored one-liner every design catalog publishes and the browse surface now
          // prints under the component's name. Captured here so the visual-diff bot covers the
          // caption line on every future PR.
          caption = "A decision the app needs before it can go on.",
        ),
        ServePreview(
          "alertdialog__ideal__no-buttons__$size",
          "Alert Dialog · No buttons · $size",
          componentId = "AlertDialog",
          state = "no-buttons",
          size = size,
          section = "Containment",
          group = "Dialogs",
          catalogOrder = index * 2 + 1,
        ),
        ServePreview(
          "timetext__ideal__default__$size",
          "Time Text · $size",
          componentId = "TimeText",
          state = "default",
          size = size,
          section = "Text",
          group = "Time",
          catalogOrder = 100 + index,
        ),
      )
    }

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

  /**
   * A component with a WIDE state axis — the published m3-catalog's `iconbutton-outlined` bakes one
   * render per size × width × shape, which is what pushed the switcher past the point where showing
   * every chip was worth the fold it cost. Sized to that real shape (22 states) rather than a token
   * few, so the capture shows what the OPEN subtree actually costs on the catalog that motivated
   * this — the case a smaller fixture would have flattered. Past [ServeWeb]'s inline threshold the
   * rows arrive folded behind the title bar's `State · …` toggle, so this is the fixture that
   * captures the *collapsed* axes disclosure for the visual-diff bot; `serve-viewer-states.html`
   * (two states) keeps the expanded case.
   */
  private val wideStatePreviews =
    listOf(
        "default",
        "disabled",
        "xs",
        "xs-narrow",
        "xs-square",
        "xs-wide",
        "s",
        "s-narrow",
        "s-square",
        "s-wide",
        "m",
        "m-narrow",
        "m-square",
        "m-wide",
        "l",
        "l-narrow",
        "l-square",
        "l-wide",
        "xl",
        "xl-narrow",
        "xl-square",
        "xl-wide",
      )
      .flatMap { state ->
        listOf("light", "dark").map { theme ->
          ServePreview(
            "iconbutton-outlined__ideal__${state}__$theme",
            "Icon Button Outlined · ${state.replace('-', ' ')} ($theme)",
            state = state,
            theme = theme,
          )
        }
      }

  /**
   * A component baking state × props as a full CROSS-PRODUCT — every state also rendered RTL. This
   * is the shape whose subtree cannot be labelled one axis at a time: the row that resets the state
   * and the row that resets the props are both "Default" unless each names both coordinates. No
   * committed catalog had it, which is exactly why the ambiguity reached review unseen, so it gets
   * a fixture of its own and the visual-diff bot carries it from here.
   */
  private val crossProductPreviews =
    listOf("default", "pressed", "disabled").flatMap { state ->
      listOf<String?>(null, "rtl").map { direction ->
        ServePreview(
          "button-filled__ideal__${state}__light" + if (direction == null) "" else "__$direction",
          "Button · Filled",
          state = state,
          theme = "light",
          props = direction?.let { jsonProps("direction" to it) },
        )
      }
    }

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
      // The Remote Compose named-value knobs this preview declared (the `compose/remotecompose`
      // channel — the `rememberOverridableRemote*` wrappers). Rendered as a separate "Remote
      // Compose"
      // control group whose edits round-trip via `rc.<name>=<kind>:<value>`; captured alongside the
      // plain-Compose overrides so the visual-diff bot covers both panels.
      remoteComposeKnobs =
        listOf(
          RemoteComposeKnobDeclaration("label", RemoteNamedValue.StringValue("Filled")),
          RemoteComposeKnobDeclaration("shaderColor", RemoteNamedValue.ColorValue("#FF7DE2FF")),
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
  // card; the viewer's `.cp-axes-tree` subtree reaches them, listing the props axis beside the
  // state axis under one component row. Captured so the visual-diff bot covers the variant fold
  // end-to-end (the fix for the "duplicate RTL/locale tiles" the imported M3 tabs showed).
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
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__direction-rtl",
        "Button · Filled · RTL (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__locale-ar-xb",
        "Button · Filled · ar-XB (light)",
        state = "default",
        theme = "light",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__locale-ar-xb",
        "Button · Filled · ar-XB (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__fontscale-2.0",
        "Button · Filled · 2× font (light)",
        state = "default",
        theme = "light",
        props = jsonProps("fontScale" to "2.0"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__fontscale-2.0",
        "Button · Filled · 2× font (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("fontScale" to "2.0"),
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
    val pagesDir = File(repoRoot(), "preview-server/preview-harness/fixtures/pages")
    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    val parityIssues =
      listOf(
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 40,
          title = "Glyph colour is darker than the design token",
          url = "https://github.com/yschimke/m3-catalog/issues/40",
          state = "open",
          area = "component",
          parity = "known-difference",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 41,
          title = "Verify the disabled state after the token update",
          url = "https://github.com/yschimke/m3-catalog/issues/41",
          state = "closed",
          area = "preview",
          parity = "verification-needed",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
      )

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
        refreshUrl = "/compose-m3/refresh",
        // "try in playground" on the summary line — the catalog-level half of the handoff, captured
        // so its placement in that run of actions is diffed like any other pixel.
        playgroundHref = "/playground?catalog=compose-m3",
        // Published design pages on a catalog with NO navigation tree — the one shape that still
        // offers them as a header chip, because there is no tree to list them in. Captured so that
        // fallback keeps a baseline of its own, next to `landingGrouped`, which has a tree and so
        // lists these by name instead.
        designPages =
          listOf(
            // Sections on one page and none on the other, on purpose: the pane has to render both
            // a branch and a leaf, and a golden that only ever held one shape would not say so.
            ServeWeb.PageLink(
              "shape",
              "Shape",
              listOf(
                ServeWeb.PageSection("1:20", "Corner radius"),
                ServeWeb.PageSection("1:21", "Shape scale"),
              ),
            ),
            ServeWeb.PageLink("type", "Typography"),
          ),
        parityIssues = parityIssues,
      )
    // The public preview server's FRONT DOOR: an index of the published design systems, each a card
    // with a meaningful hero preview, its title + library, trust badge, and a link to /<system>/.
    // This is what `/` serves now (instead of an arbitrary default module's grid), so the harness
    // captures it per theme.
    // The front-page section the operator's `catalogs.json` publishes the built-in systems under —
    // a claim honoured only for catalogs whose bytes really came from that repo.
    val designSystemsGroup =
      ServeWeb.HomeGroup(
        heading = "Design Systems",
        noun = "design system(s)",
        repos = setOf("yschimke/compose-ai-tools"),
      )
    val homeSystems =
      listOf(
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "compose-m3",
          title = "Compose Material 3",
          subtitle = "androidx.compose.material3:material3",
          previewCount = 42,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "button-filled__ideal__default__light",
          // The normal path: a prebaked, content-hashed hero on the immutable `/hero/` lane, the
          // crop already in its pixels. Captured here so the golden pins the fast markup — eager
          // load, explicit box, no CSS clip window.
          heroImage =
            ServeWeb.HeroImage(
              path = "/hero/compose-m3/1f0c9a4b7d2e6503.png",
              width = 168,
              height = 68,
            ),
          // Publishes Figma-backed design references, so its card carries the "compare to Figma"
          // action. Set on two of the three design systems deliberately: the golden then holds a
          // row where one card has the action and its neighbour does not, which is the case the
          // `.cp-sys-cell` grid template exists for — the tiles still have to line their artwork
          // and their footers up.
          hasReferenceComparison = true,
          designToolLabel = "Figma",
        ),
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "wear-m3",
          title = "Wear Compose Material 3",
          subtitle = "androidx.wear.compose:compose-material3",
          previewCount = 18,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/wear-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "button-filled__ideal__default__light",
          heroImage =
            ServeWeb.HeroImage(
              path = "/hero/wear-m3/9b3d51ca08e7f264.png",
              width = 132,
              height = 132,
            ),
          // Wear is dark-first: the hero backs on the dark stage, not the default white.
          darkStage = true,
          hasReferenceComparison = true,
          designToolLabel = "Figma",
        ),
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "remote-m3",
          title = "Remote Compose Material 3",
          subtitle = "androidx.wear.compose.remote:remote-material3",
          previewCount = 6,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "Button-Filled__ideal__default__light",
          // Remote Compose draws the dark-first Wear scheme, so its catalog declares
          // `display.surface: "dark"` and the hero backs on the dark stage too.
          darkStage = true,
        ),
        // App systems published UNLISTED from their own repos but promoted to the LISTED set
        // (`--catalogs`), so they show on the front door alongside the design systems.
        ServeWeb.HomeSystem(
          system = "meshcore-mobile",
          title = "MeshCore",
          subtitle = "ee.schimke.meshcore",
          previewCount = 33,
          trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
          sourceRepo = "yschimke/meshcore-mobile",
          heroPreviewId = "device-manycontacts__ideal__default__compact",
        ),
        ServeWeb.HomeSystem(
          system = "homeassistant-remotecompose",
          title = "HomeAssistant RemoteCompose",
          subtitle = "ee.schimke.homeassistant",
          previewCount = 9,
          trust =
            "branch:yschimke/homeassistant-remotecompose@design-artifacts/homeassistant-remotecompose",
          sourceRepo = "yschimke/homeassistant-remotecompose",
          heroPreviewId = null,
          // Publishes design references whose provider names no design tool — checked-in PNGs. The
          // route works, so the card still offers the comparison; it just takes the neutral
          // wording. Captured here because gating the action on the vendor label rather than on
          // availability silently dropped it from every catalog in this shape (#4349), and a
          // golden is what stops that coming back.
          hasReferenceComparison = true,
        ),
        // A Wear app (Confetti): dark-first stage, and its hero is a conference SCREEN — the most
        // representative view of the app — rather than a single component.
        ServeWeb.HomeSystem(
          system = "confetti-wear",
          title = "Confetti (Wear)",
          subtitle = "dev.johnoreilly.confetti",
          previewCount = 12,
          trust = "branch:joreilly/Confetti@design-artifacts/confetti-wear",
          sourceRepo = "joreilly/Confetti",
          heroPreviewId = "conference-screen__ideal__default__dark",
          darkStage = true,
        ),
      )
    val homeIndex =
      ServeWeb.homeIndexPage(
        homeSystems,
        token,
        isPublic = true,
        version = version,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            login = "yschimke",
          ),
      )
    // The render-history timeline: a viewer served from a delivery branch, so it carries the
    // history.json URL + repo that `<cp-history-menu>` needs. Registered as its own page fixture so
    // the harness captures the strip on every future change, rather than only when someone
    // remembers to screenshot it.
    val viewerHistory =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        historyManifestUrl =
          ServeUrls.historyManifestUrl("yschimke/compose-ai-tools", "compose-preview/main"),
        historyRepo = "yschimke/compose-ai-tools",
        // Inlined so the harness renders the strip offline. Shaped like the real manifest: three
        // versions, one carried by several publishes, and flagged unstable so the capture covers
        // the badge as well as the chips.
        historyInlineJson =
          """
          {"formatVersion":"compose-preview-history/v1","generatedFrom":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80",
           "previews":{"${previews.first { it.id.endsWith("ProfileScreenPreview") }.id}":{
             "path":"renders/samples:compose-m3/ProfileScreenPreview.png","observations":7,
             "unstable":true,"flapCount":4,"versions":[
               {"blob":"a","commit":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80","date":"2026-05-22T11:08:37+00:00","sourceSha":"57ac24f3","commits":1},
               {"blob":"b","commit":"8b9f6f2bc953756edcb13963e09cd57c54866570","date":"2026-05-07T08:34:51+00:00","sourceSha":"cf69a4a0","commits":3},
               {"blob":"c","commit":"1f10ff93dcb1a0f5e6c7b8a9d0e1f2a3b4c5d6e7","date":"2026-04-19T09:12:00+00:00","sourceSha":"03ecb679","commits":1}]}}}
          """
            .trimIndent(),
      )
    // The same strip in PROJECT mode: no delivery branch to fetch from, so the timeline is computed
    // from the local repo ([ServeProjectHistory]) and its entries link at this server's own
    // content-addressed lane. Captured separately because it differs where it matters — the newest
    // entry is not "current" (the stage is rendered from the working tree), and the head carries
    // the "published baselines" scope label.
    val viewerHistoryLocal =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        historyLocalRenders = true,
        historyInlineJson =
          """
          {"formatVersion":"compose-preview-history/v1","generatedFrom":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80",
           "previews":{"${previews.first { it.id.endsWith("ProfileScreenPreview") }.id}":{
             "path":"renders/samples:compose-m3/ProfileScreenPreview.png","observations":5,
             "unstable":false,"flapCount":0,"versions":[
               {"blob":"1c9a3f6b2d4e5f708192a3b4c5d6e7f809a1b2c3","commit":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80","date":"2026-05-22T11:08:37+00:00","sourceSha":"57ac24f3","commits":1},
               {"blob":"2d8b4a7c3e5f60718293a4b5c6d7e8f90a1b2c3d","commit":"8b9f6f2bc953756edcb13963e09cd57c54866570","date":"2026-05-07T08:34:51+00:00","sourceSha":"cf69a4a0","commits":3},
               {"blob":"3e7c5b8d4f60718293a4b5c6d7e8f90a1b2c3d4e","commit":"1f10ff93dcb1a0f5e6c7b8a9d0e1f2a3b4c5d6e7","date":"2026-04-19T09:12:00+00:00","sourceSha":"03ecb679","commits":1}]}}}
          """
            .trimIndent(),
      )
    val viewer =
      ServeWeb.viewerPage(
        previews
          .first { it.id.endsWith("ProfileScreenPreview") }
          .copy(
            sourceFile = "src/main/kotlin/com/example/ProfileScreen.kt",
            section = "Screens",
            // Published motion captures, so the golden carries the Motion chip and the harness
            // diffs it on every future change to that row — the same reason every other affordance
            // on the provenance/renderer rows is exercised from a fixture rather than screenshotted
            // by hand. Two of them, so the per-capture picker's markup is covered too; it stays
            // `hidden` at rest, which is exactly the state the page is captured in.
            motion =
              listOf(
                ServeMotion(
                  id = "screens__profile__interaction",
                  kind = "interaction",
                  caption = "Tap the avatar",
                ),
                ServeMotion(
                  id = "screens__profile__anim",
                  kind = "animation",
                  caption = "Header collapse",
                ),
              ),
          ),
        token,
        trust = "unverified",
        // Every page ends with the minimal footer, so the goldens carry the fixed server version
        // on a representative page of each kind — not just the landings.
        version = version,
        // The full preview list feeds the left-hand component nav drawer (default closed) so the
        // harness captures its chrome alongside the default-open overrides drawer.
        siblings = previews,
        // A resolved GitHub source link (catalog source repo/ref/module + the preview's
        // sourceFile),
        // so the golden captures the per-preview "source" link under the title.
        sourceHref =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            "src/main/kotlin/com/example/ProfileScreen.kt",
          ),
        // …and the prefilled "report an issue" link beside it, so the golden captures the bug-
        // reporting affordance (and a signed-in visitor's "as @login" tooltip) next to source.
        reportIssue =
          fixtureReportIssue(
            "com.example.ProfileScreenPreview",
            "Profile screen",
            "src/main/kotlin/com/example/ProfileScreen.kt",
          ),
        // …and the "open in playground" handoff, so the golden captures the full provenance row a
        // host with the compile lane renders — the row is where every one of these affordances
        // lands, so a change to its rhythm shows up here.
        playgroundHref = "/playground?from=compose-m3/com.example.ProfileScreenPreview",
        parityIssues = parityIssues,
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
    // The signed-out view of a catalog whose live lane is behind GitHub auth — the state every
    // anonymous visitor to a public box sees, and until now the only viewer state with no fixture
    // at all. That gap is why a control that had been inert for its whole life (a `disabled` button
    // beside a login URL no script ever read) could not be seen to be inert. Captured so the
    // affordance is visually diffed on every future change to it.
    val viewerSignIn =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(loginHref = "/auth/github/start?return=%2Fp%2FCardPreview"),
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
        hasScrollExport = true,
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
        // The source + "report an issue" row. `serve-viewer` carries it too, but this is the viewer
        // fixture captured with the REAL stylesheet routed in (see STYLED_FIXTURES in
        // pages-snapshot.spec.mjs), so it is the shot where a change to how that row is *painted*
        // moves a baseline for the visual-diff bot rather than only changing the HTML.
        sourceHref =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            "src/main/kotlin/com/example/Button.kt",
          ),
        reportIssue =
          fixtureReportIssue(
            knobPreview.id,
            knobPreview.label,
            "src/main/kotlin/com/example/Button.kt",
          ),
        // …and the third link in the row: the Figma node this preview is specified by, which only a
        // catalog publishing Figma-backed references names.
        figmaSpec = fixtureFigmaSpec,
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
        // meshcore-mobile is the catalog that really publishes Figma-backed design references, so
        // it is the one whose landing offers both design actions — captured here so the visual-diff
        // bot covers the reference comparison (named after the design tool the references came
        // from) and the parity dashboard beside it.
        hasReferenceComparison = true,
        hasParityView = true,
        designToolLabel = "Figma",
        // The footer's Changelog entry — a published catalog has a change feed, so the path-mounted
        // landing is where that entry is diffed. Its prefixed href is half the point: the site
        // fixture below carries the rooted one.
        changelogHref = "/meshcore-mobile/feed.xml",
      )
    // …and the SAME catalog as a **top-level site** ([ServeSites]): rooted on a hostname of its
    // own, so it presents as the only thing on the server. Captured beside `landingPath` because
    // the difference between the two IS the feature and it is entirely visual — no back button (no
    // front door to return to on this hostname), and every link rooted rather than prefixed. One
    // fixture keeps the site chrome under the visual-diff bot from here on, so a later change to
    // the landing can't quietly regress the site presentation.
    val landingSite =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        // The two lines that make it a site: no home index to link back to, no path prefix, and
        // the session carried by the ORIGIN rather than a `?session=` on every href.
        hasHomeIndex = false,
        basePath = "",
        sessionInOrigin = true,
        version = version,
        hasReferenceComparison = true,
        hasParityView = true,
        designToolLabel = "Figma",
        // A site host's landing IS its front door, so it is the page that has to carry the sign-in
        // — there is no home index above it. Captured here so the control is visually diffed on the
        // one shape that depends on it (wear-m3-catalog#68).
        githubAuth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F"),
        changelogHref = "/feed.xml",
      )
    val viewerPath =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        basePath = "/meshcore-mobile",
        siblings = previews,
        // meshcore-mobile is the catalog that really publishes Figma-backed references, so its
        // golden is where the affordance is captured in context — both the provenance link and the
        // Spec lane chip that puts the imported reference on the stage beside the renderers.
        figmaSpec = fixtureFigmaSpec,
        designReference = fixtureDesignReference,
        hasDesignAnnotations = true,
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 28, width = 160, height = 42),
              label = "titleLarge 22sp/28sp",
              role = "Ada Lovelace",
              detail =
                mapOf(
                  "token" to "titleLarge",
                  "fontFamily" to "Roboto Flex",
                  "fontSize" to "22sp",
                  "fontWeight" to "400",
                  "lineHeight" to "28sp",
                  "fontVariationSettings" to "'opsz' 22, 'wdth' 100, 'wght' 400",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 92, width = 116, height = 18),
              label = "bodyMedium 14sp/20sp",
              role = "Analytical engine",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto Flex",
                  "fontSize" to "14sp",
                  "fontWeight" to "400",
                  "lineHeight" to "20sp",
                  "fontVariationSettings" to "'opsz' 14, 'wdth' 100, 'wght' 400",
                ),
            ),
          ),
        // The viewer carries the same footer entry as its landing — captured so the Changelog
        // link is diffed on the page a visitor is most often on when they want to know what moved.
        changelogHref = "/meshcore-mobile/feed.xml",
      )
    // The **default-value deep link** (#4218), captured because the bug it records is invisible
    // in the markup and lives entirely in what the page does with its own query string.
    //
    // Same catalog and same imported reference as [viewerPath], on a preview whose id NAMES its
    // theme (`…__light`) — which is what makes `?uiMode=light` a value that spells out the
    // default rather than an override. `pages-snapshot` navigates it at exactly the reported URL
    // (`?uiMode=light&mode=spec&specView=diff`), so the capture holds the state a visitor reaches
    // by toggling to dark and back: the spec lane up, the diff drawn, and — the part that
    // regressed — the live match on the chip and in the readout rather than the "baseline-only"
    // fallback that a pinned theme correctly produces. [viewerPath] keeps the untokened case, so
    // the pair covers both sides of the rule.
    val viewerSpecDefaultTheme =
      ServeWeb.viewerPage(
        ServePreview(
          "profile-screen__ideal__default__light",
          "Profile screen",
          section = "Screens",
          componentId = "ProfileScreen",
        ),
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        basePath = "/meshcore-mobile",
        siblings = previews,
        figmaSpec = fixtureFigmaSpec,
        designReference = fixtureDesignReference,
      )
    // A **Remote Compose** viewer, the shape preview.coo.ee serves for `remote-m3`: the same
    // captured `.rc` document is drawable by five different players, so this is the page the
    // renderer picker exists for. Captured because it is the ONLY fixture that carries the picker
    // at full width — the chip naming the current player ("Java"), the combo holding the
    // alternatives (with the unavailable `CMP JVM` listed as such), the "compare players →" step
    // out to the player wall, and the SVG toggle for whatever the chip is showing. Every other
    // viewer fixture has one or two lanes and so shoots a degenerate version of the row.
    // The delivery branch's publish history, as the store reads it off the branch's commit feed.
    // Shaped like the real thing: several regenerations a day, each stamping the source commit it
    // was rendered from, newest first.
    val catalogRevisions =
      listOf(
        ServeCatalogRevision.Revision(
          "46440dd86c24b2da6054ccab587e59fba4b15c7e",
          "2026-08-13T09:42:57Z",
          "0b0c2063",
        ),
        ServeCatalogRevision.Revision(
          "41c7a15fd21f52e7c6a959a0c441eb600ca46d4f",
          "2026-08-13T07:10:54Z",
          "b34eff53",
        ),
        ServeCatalogRevision.Revision(
          "421350e5cae04212a193cc8137be1c337a9d5396",
          "2026-08-12T15:42:44Z",
          "7b573ecc",
        ),
        ServeCatalogRevision.Revision(
          "4f7c1ae06b177603984734dc4fa3c0ea365e71e0",
          "2026-08-12T09:05:11Z",
          null,
        ),
      )
    val viewerRcPlayers =
      ServeWeb.viewerPage(
        ServePreview(
          "appcard__ideal__default__compact",
          "App card",
          section = "Cards",
          componentId = "AppCard",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasLiveStream = true,
        hasSvgExport = true,
        hasRemoteComposeDoc = true,
        // js + cmp-wasm play in the browser, java + cmp-android render through the daemon; cmp-jvm
        // needs sidecars this host doesn't carry, so it is the "(unavailable)" option.
        enabledRcPlayers = listOf("js", "cmp-wasm", "java", "cmp-android"),
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
      )
    // The same rich viewer, PINNED. Captured as the twin of [viewerRcPlayers] because that is the
    // page where the rule is visible: every lane above renders the catalog's current code, so a pin
    // must leave none of them on the page — no Live toggle, no renderer combo, no SVG toggle or
    // download, no inspection layers — while the stage keeps the publish the banner names.
    val viewerPinnedLanes =
      ServeWeb.viewerPage(
        ServePreview(
          "appcard__ideal__default__compact",
          "App card",
          section = "Cards",
          componentId = "AppCard",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasLiveStream = true,
        hasSvgExport = true,
        hasRemoteComposeDoc = true,
        hasA11yOverlay = true,
        hasDesignAnnotations = true,
        enabledRcPlayers = listOf("js", "cmp-wasm", "java", "cmp-android"),
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        revisions =
          ServeWeb.CatalogRevisions(
            pinned = catalogRevisions[1].commit,
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
      )
    // The Wear counterpart of [viewerPath]: a screen served under a Wear system path. Its Size
    // panel must offer the watch shapes (not Pixel phones / a foldable / a tablet) and drop the
    // Orientation control a watch can't honour — captured so the visual-diff bot covers the Wear
    // control panel, which no other page fixture reaches.
    val viewerWearScreen =
      ServeWeb.viewerPage(
        ServePreview(
          "settings-complication",
          "Settings complication",
          section = "Screens",
          componentId = "SettingsComplication",
        ),
        token,
        sessionId = "home-assistant-wear",
        canApplyOverrides = true,
        basePath = "/home-assistant-wear",
        trust = "branch:yschimke/home-assistant-wear@design-artifacts/home-assistant-wear",
      )
    // A daemon-backed viewer whose module declares `@ThemeCatalog` themes: the viewer adds an "App
    // theme" selector (grouped by `@ThemeCatalog(group=…)`) so a preview can be re-rendered under a
    // chosen theme via the `themeProvider` override. Captured so the visual-diff bot covers the
    // selector.
    val viewerThemes =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") }.copy(uiMode = 0x20),
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
    // The crowded-toolbar case: a viewer whose catalog declares the full Material 3 baseline +
    // contrast theme set, so the Theme axis alone offers eight chips beside the four fixed toolbar
    // controls. This is what the published `compose-m3` catalog actually looks like, and it is the
    // shape that used to wrap the viewer bar onto three lines and push the stage below the fold.
    // Committed so the single-row bar (chips shrinking, then scrolling within their own group) is
    // diffed by the visual-diff bot on every PR rather than only ever checked by hand.
    val viewerThemeOverflow =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") }.copy(uiMode = 0x20),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          listOf(
            ServeTheme("Baseline Dark", "com.example.BaselineDarkThemeCatalog"),
            ServeTheme("Baseline Light", "com.example.BaselineLightThemeCatalog"),
            ServeTheme("Dark High Contrast", "com.example.DarkHighContrastThemeCatalog"),
            ServeTheme("Dark Medium Contrast", "com.example.DarkMediumContrastThemeCatalog"),
            ServeTheme("Light High Contrast", "com.example.LightHighContrastThemeCatalog"),
            ServeTheme("Light Medium Contrast", "com.example.LightMediumContrastThemeCatalog"),
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
    // A daemon-backed viewer whose session can produce every inspection layer: the accessibility
    // focus map (`a11y/hierarchy` + ATF findings + touch targets) and the typography / theme
    // attributes derived from the render's own semantics tree. The harness drives this fixture
    // twice — once as served, once with the layers ticked (see `pages-snapshot.spec.mjs`'s
    // `serve-viewer-inspect` states) — so the visual-diff bot covers both the controls and the
    // drawn overlay + legend.
    val viewerInspect =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        hasA11yOverlay = true,
        hasDesignAnnotations = true,
      )
    // The **other lane behind the same Typography layer**: a published catalog with no daemon at
    // all, whose `annotations/index.json` carries typography measured over the baked frame this
    // page shows. `canApplyOverrides = false` and `hasDesignAnnotations = false` — so the Overrides
    // drawer is the static one and there is no Theme attributes row (nothing authors theme
    // attributes into a bundle; they are projected from a live semantics tree).
    //
    // Its own fixture rather than a flag on `serve-viewer-inspect`, because the claim is precisely
    // that a page WITHOUT the daemon controls still offers a working layer — which is invisible on
    // a fixture that has every control anyway. The harness ticks it in the
    // `serve-viewer-published-typography` `layers` state, so the boxes and the legend are diffed
    // per PR alongside the daemon lane's.
    val viewerPublishedTypography =
      ServeWeb.viewerPage(
        ServePreview("button-filled__ideal__default__light", "Filled button (light)"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasPublishedTypography = true,
      )
    // An SVG-exporting viewer opened straight into the **exploded 3D** view: the `3D` chip pressed
    // beside the SVG one, and the Exploded 3D group in the overrides drawer holding the camera
    // axes. Its stage is stubbed by the harness with the committed
    // `_render-placeholder-exploded.svg` — which `ExplodedSvgFixtureTest` generates from the
    // layered placeholder through the production renderer — so the PNG the visual-diff bot posts
    // shows the real projection, not a stand-in drawing.
    val viewerExploded =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        hasSvgExport = true,
        hasScrollExport = true,
      )
    // A viewer offering the **Source** chip: the usage code behind the card, on the stage in place
    // of the render. Its own fixture rather than a flag on `viewer` because the chip changes the
    // control row, and the `source-panel` state below — which is where the panel is actually drawn
    // — needs a page that carries the chip to press.
    val viewerSource =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        usageHref = "/usage/com.example.ProfileCardPreview",
      )
    // A viewer whose preview published motion captures, on a fixture of its OWN rather than as a
    // state of the main one. The harness's extra states run in order against the same page, and
    // this one leaves a lane OPEN — the still taken out of flow, a capture on the stage — so run
    // ahead of `serve-viewer`'s own `connecting` state it would have re-captured that baseline
    // showing the recording instead of the render. The Source panel is isolated for exactly this
    // reason, and this is the same shape of state.
    //
    // Two captures, because the per-capture menu only appears when there is a choice to make, and
    // an interacted shot is the only place its markup is ever visible.
    //
    // Their captions are the shape catalogs actually publish — a line of instruction followed by a
    // paragraph of what to watch for — rather than the two-word labels this fixture used to carry.
    // Those made the picker look fine at every width and hid the reason it is a menu at all: on the
    // old segmented group this pair is two paragraphs side by side, wider than the render they
    // introduce. A fixture that cannot show the problem cannot show it fixed either.
    val viewerMotion =
      ServeWeb.viewerPage(
        ServePreview(
          "com.example.SwitchPreview",
          "Switch",
          motion =
            listOf(
              ServeMotion(
                id = "switch-on__ideal__default__light",
                kind = "interaction",
                caption =
                  "Toggle on. The thumb travels the full width of the track and the container " +
                    "recolours to the checked state as it lands.",
              ),
              ServeMotion(
                id = "switch-on__ideal__default__light__anim",
                kind = "animation",
                caption =
                  "Thumb settle. Released mid-travel, the thumb overshoots and settles back " +
                    "through the theme's spatial spring rather than snapping to the stop.",
              ),
            ),
        ),
        token,
        sessionId = "compose-m3",
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
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        // Both comparison actions, so the golden captures the split summary line ("compare SVG ·
        // compare RC players") the visual-diff bot shoots.
        hasSvgComparison = true,
        hasRcComparison = true,
        version = version,
      )
    // The catalog-theme sync: a served system's pages are framed in ITS colours, not the built-in
    // indigo shell — the `:root` override `ServeThemeCss` projects from the delivery branch's
    // `tokens.dtcg.json`. Two fixtures so the bot diffs both directions of the mode match: a
    // dark-first catalog (wear-m3, cyan on near-black) on the landing, and a light-first one
    // (jetnews, crimson) on the viewer. The harness shoots each in light AND dark, which is exactly
    // where the "matching mode syncs surfaces, the other keeps built-in neutrals + the brand
    // accent" rule shows up.
    val landingCatalogPalette =
      ServeWeb.landingPage(
        "wear-m3",
        themedPreviews,
        token,
        sessionId = "wear-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/wear-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        declaredSurface = "dark",
        themeCss = ServeThemeCss.fromDtcg(wearM3Tokens)!!,
      )
    val viewerCatalogPalette =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "jetnews",
        trust = "branch:yschimke/compose-samples@design-artifacts/jetnews",
        siblings = previews,
        catalogTitle = "JetNews",
        themeCss = ServeThemeCss.fromDtcg(jetNewsTokens)!!,
      )
    // The format-comparison surface introduced for issue #3158. Both targets are advertised so the
    // visual fixture covers the format tabs; the light/dark pairs ensure the comparison theme
    // control and strict same-theme URL wiring are captured too.
    val formatComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        hasSvgFor = { it.startsWith("button-filled") || it.startsWith("switch-on") },
        hasRemoteComposeFor = { it.startsWith("button-filled") },
        referencesFor = { id ->
          if (id.startsWith("button-filled"))
            listOf(
              DesignReference(
                id = "design-$id",
                previewId = id,
                label = "Figma filled button",
                raster =
                  DesignReferenceRaster("references/design-$id.png", width = 320, height = 160),
                source = DesignReferenceSource(provider = "figma", revision = "fixture-42"),
                // The score the delivery branch bakes into `references/index.json`, which the wall
                // seeds its rows and its order from before the browser has measured anything. The
                // fixture carries one so the published-score marking is diffed like any other
                // pixel.
                match =
                  DesignReferenceMatch(
                    percent = 82.4,
                    changedPercent = 3.1,
                    scoreVersion = ServeDesignReferenceStore.SCORE_VERSION,
                  ),
              )
            )
          else emptyList()
        },
        reportIssue = fixtureWallReportIssue(),
        parityIssues = parityIssues,
      )
    // The Remote Compose PLAYER WALL: the same compare page in `?format=rc`, backed by a catalog's
    // published `rc-compare` manifest instead of by an in-browser render. Only the rc format is
    // advertised, so the page opens straight onto the wall — which is what the fixture is for.
    val rcLanesComparison =
      ServeWeb.comparisonPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        rcCompare = rcCompareFixture(themedPreviews),
      )
    val comparisonReferences =
      listOf(
        DesignReference(
          id = "design-button-filled-light",
          previewId = themedPreviews.first().id,
          label = "Figma filled button",
          raster = DesignReferenceRaster("references/design-button-filled-light.png", 320, 160),
          source =
            DesignReferenceSource(
              provider = "figma",
              uri = "https://www.figma.com/file/example",
              revision = "fixture-42",
              attributes = mapOf("nodeId" to "12:34"),
            ),
        ),
        DesignReference(
          id = "design-button-filled-review",
          previewId = themedPreviews.first().id,
          label = "Review revision",
          raster = DesignReferenceRaster("references/design-button-filled-review.png", 320, 160),
          source = DesignReferenceSource(provider = "penpot", revision = "fixture-43"),
        ),
      )
    // The design-parity dashboard. Built through the real [ServeParityDashboard] rather than by
    // hand-assembling a view model, so the golden also pins the *derivation* — coverage folding
    // light/dark onto one component, the two lanes merging by time, and a comment on a component
    // whose code didn't move landing in the "needs a look" band.
    val parityDashboard =
      ServeParityDashboard.build(
        previews = themedPreviews,
        // Button is mapped; Switch and Badge are not — a realistic, partly-covered catalog.
        hasReference = { it.startsWith("button-filled") },
        referenceIdFor = {
          if (it == "button-filled__ideal__default__light") "design-button-filled-light" else null
        },
        activity =
          ParityActivity(
            generatedAt = "2026-07-17T09:30:00.000Z",
            windowDays = 30,
            code =
              CodeLane(
                repo = "yschimke/compose-ai-tools",
                ref = "main",
                events =
                  listOf(
                    CodeEvent(
                      sha = "4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d",
                      subject = "fix(button): tighten the filled button's label padding to 16dp",
                      at = "2026-07-16T14:22:00.000Z",
                      author = "yschimke",
                      previewIds = listOf("button-filled__ideal__default__light"),
                      components = listOf("Button/Filled"),
                    ),
                    CodeEvent(
                      sha = "b842ee3c1d5f7a9b1c3d5e7f9a1b3c5d7e9f0a1b",
                      subject = "feat(badge): add the small/large size axis",
                      at = "2026-07-14T08:05:00.000Z",
                      author = "yschimke",
                      previewIds = listOf("badge"),
                      components = listOf("Badge"),
                    ),
                  ),
              ),
            figma =
              FigmaLane(
                fileKey = "ocdacdEsnHipMJD3egzxKb",
                fileName = "Material 3 Design Kit",
                versions =
                  listOf(
                    FigmaVersionEvent(
                      id = "3928471",
                      at = "2026-07-15T11:40:00.000Z",
                      label = "Buttons: 16dp label padding",
                      description = "Aligns the filled/tonal pair with the spec update.",
                      author = "Dana",
                    )
                  ),
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "9182",
                      at = "2026-07-16T09:02:00.000Z",
                      message = "The switch track reads 2dp short against the M3 spec sheet.",
                      author = "Dana",
                      nodeId = "51592:4768",
                      previewIds = listOf("switch-on__ideal__default__light"),
                      components = listOf("Switch/On"),
                    ),
                    FigmaCommentEvent(
                      id = "9165",
                      at = "2026-07-15T16:31:00.000Z",
                      message = "Padding change landed here too — matching the code side.",
                      author = "Dana",
                      resolved = true,
                      nodeId = "57994:2227",
                      previewIds = listOf("button-filled__ideal__default__light"),
                      components = listOf("Button/Filled"),
                    ),
                  ),
              ),
            gaps =
              listOf(
                MappingGap(
                  kind = MappingGap.Kind.UNMAPPED_DESIGN_NODE,
                  detail = "Published in the kit, but no design-map entry names it.",
                  ref = "figma:ocdacdEsnHipMJD3egzxKb/51827:5859",
                  component = "Bottom sheet / Modal",
                ),
                MappingGap(
                  kind = MappingGap.Kind.UNRENDERED_REFERENCE,
                  detail = "Figma render returned no image for this node; reference not published.",
                  ref = "figma:ocdacdEsnHipMJD3egzxKb/51159:5105",
                  component = "Bottom app bar",
                ),
              ),
          ),
      )
    val parity =
      ServeWeb.parityPage(
        moduleLabel = "compose-m3",
        dashboard = parityDashboard,
        token = token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        displayTitle = "Compose Material 3",
        hasReferenceFor = { it.startsWith("button-filled") },
        // The catalog names its design tool, so the page's way back out to the whole-catalog
        // comparison table is captured with the same wording as the link that leads here.
        designToolLabel = "Figma",
        parityIssues = parityIssues,
      )
    val referenceComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
            selectionPlaceholder = true,
          ),
        // The DERIVED semantics layers, which this page could not show until the inspection
        // machinery learned to mount over a host other than the viewer. They are what gives the
        // element selector something to point at on the render side of the comparison — the
        // authored redline below annotates the reference far more often than the render.
        derivedAnnotations = true,
        annotationsSelectable = true,
        // …and the tag index, which is the OTHER half, and the half an annotation-box-only design
        // misses: a uniquely tagged node carrying neither typography nor container tokens produces
        // no annotation at all, so nothing on this page draws a box for it.
        tagIndexAvailable = true,
        // A catalog that has accepted something, so the acceptance band and its payload are in a
        // golden. The band is filled by the engine at runtime — a fixture opened as a file has
        // nothing to fetch, so the screenshot shows it collapsed — which is the honest split: this
        // golden covers the markup and the payload the server writes, and the engine's own output
        // is covered by `cli/serve-web/test/acceptance.test.ts` end to end.
        //
        // The tag index is non-empty here because `tagIndexAvailable` is: an element-scoped
        // acceptance whose gate cannot run suppresses nothing, so the two must agree or the page
        // would offer a tag picker while telling the engine there are no tags.
        knownDifferences =
          KnownDifferenceScope(
            system = "compose-m3",
            component = ServeIssueReport.componentIdFor(themedPreviews.first()),
            previewId = themedPreviews.first().id,
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
            referenceSha256 = "b7d3f1".repeat(10) + "0123",
            tagIndex =
              mapOf(
                "button-filled-label" to
                  WireTagEntry(
                    count = 1,
                    bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                    space = ServeSemanticsTags.RENDER_PIXELS,
                  )
              ),
          ),
        // Both panels annotated, so the fixture covers the case the layers exist for: reading the
        // reference's spec against the actual's. The layout boxes agree here and the type styles
        // don't, which is what the page is meant to make obvious.
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.LAYOUT,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "pad 16dp · gap 8dp",
              role = "Button",
              detail = mapOf("padding" to "16", "gap" to "8", "cornerRadius" to "20"),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
              label = "labelLarge 14sp/20",
              role = "Label",
              detail =
                mapOf(
                  "token" to "labelLarge",
                  "fontFamily" to "Roboto",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 58, width = 128, height = 20),
              label = "labelLarge 14sp/20",
              role = "Secondary label",
              detail =
                mapOf(
                  "token" to "labelLarge",
                  "fontFamily" to "Roboto",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
          ),
        actualAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.LAYOUT,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "pad 16dp · gap 8dp",
              role = "Button",
              detail = mapOf("padding" to "16", "gap" to "8", "cornerRadius" to "20"),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
              label = "bodyMedium 14sp/20",
              role = "Label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 58, width = 128, height = 20),
              label = "bodyMedium 14sp/20",
              role = "Secondary label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 90, width = 128, height = 20),
              label = "bodyMedium 14sp/20 wght 700",
              role = "Emphasized label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "700",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            // The resolved-container layer. Before the THEME toggle existed this annotation loaded,
            // got a box and a legend row built for it, and was then hidden by CSS with no control
            // able to reveal it — so the fixture carries one to keep that reachable.
            DesignAnnotation(
              kind = AnnotationKind.THEME,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "fill #FF6750A4 · radius 20.0dp · border 1.0dp #FF79747E",
              role = "Button",
              detail =
                mapOf(
                  "background" to "#FF6750A4",
                  "cornerRadius" to "20.0dp",
                  "borderColor" to "#FF79747E",
                  "borderWidth" to "1.0dp",
                ),
            ),
          ),
        // The parity run's own verdict for this pair — the half that is prose rather than pixels,
        // and the reason this page can now say WHY two frames differ. One finding per category a
        // reader acts on, and the anchors deliberately reuse the same boxes the redline above
        // annotates: a golden in which the highlight and the spec box describe different regions
        // would hide exactly the drift the shared placement exists to prevent.
        parityFindings =
          listOf(
            ParityFindingSet(
              referenceId = comparisonReferences.first().id,
              status = "fail",
              reportUrl =
                "https://github.com/yschimke/compose-ai-tools/blob/design-parity/compose-m3/" +
                  "button-filled/report.html",
              findings =
                listOf(
                  ParityFinding(
                    kind = ParityFindingKind.TOKEN,
                    severity = ParityFindingSeverity.ERROR,
                    message = "spacing.padding: 24 vs spec 16 (Δ8)",
                    detail =
                      mapOf(
                        "token" to "spacing.padding",
                        "expected" to "16",
                        "actual" to "24",
                      ),
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
                          label = "Button",
                        )
                      ),
                  ),
                  ParityFinding(
                    kind = ParityFindingKind.I18N,
                    severity = ParityFindingSeverity.WARN,
                    message =
                      "\"Send\" risks truncation when localized: ≈154dp expanded vs 131dp " +
                        "available.",
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                          label = "Label",
                        )
                      ),
                  ),
                  ParityFinding(
                    kind = ParityFindingKind.LAYOUT,
                    severity = ParityFindingSeverity.WARN,
                    message = "layout \"Send\": offset (1, -12), size Δ(41, 3) vs reference",
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "reference",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                        ),
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                        ),
                      ),
                  ),
                  // The prose-only case, in the same golden: a finding with no geometry keeps its
                  // sentence and is not offered as a control.
                  ParityFinding(
                    kind = ParityFindingKind.CONTRAST,
                    severity = ParityFindingSeverity.INFO,
                    message =
                      "label on container: 4.9:1 — passes AA for 14sp text, below AAA (7:1).",
                    detail = mapOf("ratio" to "4.9", "required" to "4.5"),
                  ),
                ),
            )
          ),
        parityIssues = parityIssues,
      )
    // The same comparison, PINNED to an older publish (issue #3723) — the state a shared permalink
    // opens in. Captured because it is where the feature is visible: the banner naming the
    // revision and the way back to the live catalog, above a revision list opened on the publish
    // being shown.
    val referenceComparisonPinned =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        revisions =
          ServeWeb.CatalogRevisions(
            pinned = catalogRevisions[2].commit,
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            selectionPlaceholder = true,
          ),
        // No `tagIndexUrl`, and the reason said out loud. The pinned page is where the frame gate
        // is visible: the published index describes the CURRENT render, so a tag selection here
        // would record bounds measured on different pixels. The drag is unaffected — it is read off
        // the pixels on screen — so the page still offers a way to point at something.
        tagSelectionNote =
          "Tag selection is off on a pinned revision: the tag index describes the current " +
            "render, not this one. Drag a region instead.",
      )
    // The same page for a DARK-FIRST catalog, which is a materially different picture rather than a
    // recolour of the one above — and the case yschimke/wear-m3-catalog#56 was raised against.
    //
    // A dark-first system renders its stickers transparent on purpose (`showBackground = false`, so
    // one drops onto any Figma canvas), so the panels' ground is the *only* thing making its
    // white-on-transparent content visible. That made this page's missing stage invisible to every
    // existing fixture: the light-first twin above looks identical whether the stage resolves or
    // falls through, because its content is dark either way. Without this fixture the regression
    // could come back and no committed screenshot would move.
    val referenceComparisonDarkFirst =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "wear-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "wear-m3",
        // The catalog declaring its own stage, exactly as `catalog.json`'s `display.surface` does —
        // not the system-name heuristic, so the fixture pins the declared path.
        declaredSurface = "dark",
        isPublic = true,
        version = version,
      )
    assertTrue(
      referenceComparisonDarkFirst.contains("data-bg-theme=\"dark\""),
      "the dark-first comparison fixture must actually carry the dark stage",
    )
    // The same page for a ROUND device, which is the dark-first fixture's own blind spot. Giving
    // this page a ground fixed invisible stickers and introduced a quieter version of the same
    // fault: a Wear capture is a circle in a square PNG, so a ground painted across the panel draws
    // the watch as a rectangle. It is not merely untidy — Wear previews declare
    // `backgroundColor = 0xFF000000` against near-black screens, so on this repo's own
    // `PageIndicatorScaffoldTemplate` renders the stage was pixel-identical to the screen and the
    // device boundary disappeared. The dark-first fixture above cannot catch that: its preview
    // names no device, so it has no bezel to lose.
    val referenceComparisonRoundDevice =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "wear-m3",
        preview =
          themedPreviews
            .first()
            .copy(
              // Exactly what `samples/design-catalog-wear-m3` states — including the explicit dp
              // alongside the device id, which is the combination that used to resolve as square.
              deviceFrame = ServeDeviceFrame.from("id:wearos_large_round", 227, 227),
              showBackground = true,
              backgroundColor = 0xFF000000L,
            ),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "wear-m3",
        declaredSurface = "dark",
        isPublic = true,
        version = version,
      )
    assertTrue(
      referenceComparisonRoundDevice.contains("data-cp-stage-clip=\"1\"") &&
        referenceComparisonRoundDevice.contains("--cp-stage-clip: circle("),
      "the round-device fixture must actually carry the clip",
    )
    // The unpinned twin, on the viewer: the revision list folded away, which is all an ordinary
    // page view of a catalog with a publish history shows.
    val viewerRevisions =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        revisions =
          ServeWeb.CatalogRevisions(
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
      )
    // The same page with the revision menu popped open. The control is a `<details>` menu that
    // ships closed, so a screenshot of the page above only ever captures its trigger — and the list
    // of publishes, the part a change to this feature is most likely to break visually, would never
    // be diffed. Forcing the disclosure open is the whole difference between the two fixtures; the
    // markup inside it is the server's own.
    val viewerRevisionsOpen =
      viewerRevisions.replace(
        "<details class=\"cp-revisions\">",
        "<details class=\"cp-revisions\" open>",
      )
    assertFalse(
      viewerRevisionsOpen == viewerRevisions,
      "the revision menu's <details> tag changed shape — update this fixture's open-state rewrite",
    )
    // The same menu with `<cp-revision-runs>` answered: two distinct renders across the four
    // publishes, so the first and third rows carry a thumbnail and the two under them are indented
    // beneath the run they belong to.
    //
    // Registered as its own fixture because the markers are drawn CLIENT-SIDE from a lane the
    // harness cannot reach, so without an inlined payload the open-menu capture above would be
    // byte-identical whether the markers work or the feature is deleted. The four revisions split
    // 2 + 2, which is the smallest arrangement that exercises every visual state the feature has: a
    // first head (no rule above it), an indented follower, a second head (with the between-runs
    // rule), and a `×N` badge. The second run is `open` so the "at least N" wording is captured
    // too.
    val viewerRevisionRuns =
      ServeWeb.viewerPage(
          previews.first { it.id.endsWith("ProfileScreenPreview") },
          token,
          revisions =
            ServeWeb.CatalogRevisions(
              revisions = catalogRevisions,
              repo = "yschimke/compose-ai-tools",
            ),
          revisionRunsInlineJson =
            """
            {"schema":"compose-preview-render-runs/v1","revisions":4,"runs":[
              {"head":"46440dd86c24b2da6054ccab587e59fba4b15c7e","sourceSha":"0b0c2063","commits":2},
              {"head":"421350e5cae04212a193cc8137be1c337a9d5396","sourceSha":"7b573ecc","commits":2,
               "open":true}]}
            """
              .trimIndent(),
        )
        .replace("<details class=\"cp-revisions\">", "<details class=\"cp-revisions\" open>")
    assertTrue(
      viewerRevisionRuns.contains("id=\"cp-revision-runs-data\"") &&
        viewerRevisionRuns.contains("<cp-revision-runs "),
      "the runs fixture must carry both the element and the payload it draws from",
    )
    // The design page's inlined export. Run through the real [SvgSanitizer] rather than pasted in
    // whole, so the golden HTML is what the server would actually emit — including anything the
    // sanitizer strips.
    //
    // NESTED, like a real one, and that is load-bearing rather than decoration. A Figma export is a
    // tree — a page holds cards, a card holds slots, a slot holds the component — and
    // `<cp-page-zoom>` reads that tree as the levels a double-click drills through (see its zoom
    // section). While this fixture was FLAT, every node on it was a sibling of every other, so the
    // whole nested-zoom gesture was unreachable from the harness and a regression in it would have
    // moved no baseline. The two cards and their slots are also painted, for the same reason:
    // drilling resolves against the browser's hit test, so a level with nothing drawn in it can
    // only ever be found by the fallback bbox scan, which is not the path a reader takes.
    //
    // ONE NODE IS CLIPPED (`1:2`), and that is load-bearing too. A Figma export keeps an oversized
    // shape inside a component with a `clip-path` — the Wear kit does it for a placeholder's
    // shimmer sweep — and `getBoundingClientRect()` ignores clipping, so the node measured as the
    // sweep and the render fitted into that slot painted a grey blob across the page (issue #4323).
    // The sweep here is twice the square it is clipped to, so a regression to the unclipped
    // measurement publishes a render at twice its size in every design-page capture, rather than
    // nowhere at all.
    val designPageSvg =
      checkNotNull(
        SvgSanitizer.sanitize(
          """
          <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800" viewBox="0 0 1200 800" fill="none">
            <defs>
              <clipPath id="clipShimmer"><rect x="230" y="345" width="180" height="180" rx="36"/></clipPath>
            </defs>
            <rect width="1200" height="800" fill="#F7F2FA"/>
            <g data-node-id="1:0"><rect x="40" y="90" width="1140" height="690" fill="none"/></g>
            <g data-node-id="1:9"><rect x="40" y="20" width="560" height="50" rx="16" fill="#EADDFF"/></g>
            <g data-node-id="1:10"><rect x="620" y="20" width="560" height="50" rx="16" fill="#EADDFF"/></g>
            <g data-node-id="1:20">
              <rect x="40" y="90" width="560" height="690" rx="20" fill="#FFFFFF"/>
              <g data-node-id="1:30">
                <rect x="90" y="115" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:1"><circle cx="320" cy="215" r="90" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:31">
                <rect x="90" y="335" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:2">
                  <g clip-path="url(#clipShimmer)">
                    <rect x="230" y="345" width="180" height="180" rx="36" fill="#6750A4"/>
                    <path d="M470 300L560 390L280 670L190 580Z" fill="#EADDFF" fill-opacity="0.35"/>
                  </g>
                </g>
              </g>
              <g data-node-id="1:32">
                <rect x="90" y="555" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:4"><rect x="200" y="610" width="240" height="90" rx="45" fill="#6750A4"/></g>
              </g>
            </g>
            <g data-node-id="1:21">
              <rect x="620" y="90" width="560" height="690" rx="20" fill="#FFFFFF"/>
              <g data-node-id="1:33">
                <rect x="670" y="115" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:3"><path d="M900 125 L990 305 L810 305 Z" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:34">
                <rect x="670" y="335" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:5"><rect x="810" y="345" width="180" height="180" rx="90" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:35">
                <rect x="670" y="555" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:6"><rect x="810" y="565" width="180" height="180" fill="#6750A4"/></g>
              </g>
            </g>
          </svg>
          """
            .trimIndent()
        )
      )

    // A design page: one specimen sheet of the kit, inlined as SVG, with the node id of every
    // component on it. The mix is the point: two `manifest` links whose previews this catalog
    // publishes, one `convention` (low-confidence name match), one `manifest` link to a preview
    // that ISN'T published (outline, no render), one node the manifest names that the export does
    // not carry, and four `unlinked`: the component-set grid and BOTH spellings of the sheet header
    // (all structure), plus a specific shape no code implements, which is the finding the surface
    // exists to surface.
    //
    // Both spellings, because the kit uses both and only one of them used to be recognised. The
    // Shape page this fixture is drawn from names its header `.Header`, which the leading-dot rule
    // caught; every other page in the kit names it plain `Header`, which nothing caught — so the
    // header sat on 27 sheets outlined in red and clickable, and in the denominator. `Header` is
    // here so that regression has a golden of its own: neither header may appear in the markup.
    //
    // Each header gets a real box in the export above, one per column, so the capture is honest
    // about WHERE the mark used to land. A node with no box in the SVG has nowhere to draw and a
    // screenshot of the regression would show nothing — which is the one way this fixture could
    // pass while the bug it exists for was visible on the real sheet.
    val designPageFixture =
      DesignPage(
        id = "shape",
        name = "Shape",
        nodeId = "58548:7093",
        frame = PageFrame(width = 1200.0, height = 800.0),
        image = PageImage(uri = "shape.svg"),
        nodes =
          listOf(
            pageNode(
              "1:0",
              "Shape set",
              link = PageNodeLink.UNLINKED,
              depth = 1,
              type = "COMPONENT_SET",
            ),
            pageNode("1:9", ".Header", link = PageNodeLink.UNLINKED, depth = 2),
            pageNode("1:10", "Header", link = PageNodeLink.UNLINKED, depth = 2, type = "INSTANCE"),
            pageNode(
              "1:1",
              "Shape=Circle",
              code = "ui/Shapes.kt#CircleShape",
              previewId = "com.example.ProfileCardPreview",
            ),
            pageNode(
              "1:2",
              "Shape=Square",
              code = "ui/Shapes.kt#SquareShape",
              previewId = "com.example.ProfileCardPreview",
            ),
            pageNode(
              "1:3",
              "Shape=Triangle",
              code = "ui/Shapes.kt#TriangleShape",
              link = PageNodeLink.CONVENTION,
              confidence = PageNodeConfidence.LOW,
              previewId = "com.example.ProfileCardPreview",
            ),
            pageNode(
              "1:4",
              "Shape=Pill",
              code = "ui/Shapes.kt#PillShape",
              previewId = "com.example.NotInThisCatalog",
            ),
            pageNode("1:6", "Shape=Ghost-ish", link = PageNodeLink.UNLINKED),
            pageNode(
              "1:404",
              "Shape=Flattened",
              code = "ui/Shapes.kt#FlowerShape",
              previewId = "com.example.ProfileCardPreview",
            ),
          ),
      )

    val designPageHtml =
      ServeWeb.designPage(
        moduleLabel = "compose-m3",
        page = designPageFixture,
        svg = designPageSvg,
        fileKey = "ocdacdEsnHipMJD3egzxKb",
        // Everything except the pill, so the fixture covers a node the producer mapped but this
        // catalog cannot draw.
        renderablePreviewIds = setOf("com.example.ProfileCardPreview"),
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        reportIssue =
          fixturePageReportIssue(
            "https://preview.coo.ee/compose-m3/pages/foundations",
            "this design page",
          ),
      )

    // A node with code behind it is an ANCHOR, not a button or a bare div. That is what makes
    // clicking it navigate, a middle click open a tab, a modifier click do what the reader's
    // platform says, and the status bar preview the destination — none of which a `<button>` with a
    // click handler gives you, and all of which a screenshot passes without.
    //
    // Asserted here rather than in the harness because it is server-rendered markup: the capture
    // that used to hold it had to hover a node, wait for a tooltip and evaluate in a browser to
    // read two attributes off the emitted HTML.
    // Matched on the manifest-linked node itself (`1:1`), not on "some /p/ anchor exists": the
    // fixture carries several renderable nodes, so an existence check stays green while this
    // particular overlay regresses to a span or points somewhere else.
    val manifestNode =
      assertNotNull(
        Regex("""<(\w+) class="cp-page-node" [^>]*data-cp-node="1:1"[^>]*>""").find(designPageHtml),
        "the manifest-linked node 1:1 is emitted at all",
      )
    assertEquals(
      "a",
      manifestNode.groupValues[1],
      "a node with a renderable preview is emitted as an anchor",
    )
    // The whole final segment, not a prefix: `/p/com.example.ProfileCardPreviewLegacy` contains
    // the id we mean and navigates somewhere else.
    // `\shref=` and not `href=`: the latter also matches the tail of `data-href`, so swapping a
    // real
    // anchor for scripted navigation — precisely the inert-destination regression this is here to
    // catch — would keep it green.
    val hrefOf = { tag: String -> Regex("""\shref="([^"]*)"""").find(tag)?.groupValues?.get(1) }
    val manifestHref =
      assertNotNull(
        hrefOf(manifestNode.value),
        "the manifest node carries a real href attribute",
      )
    // Matched through the route delimiter. `substringAfterLast` returns the WHOLE string when the
    // delimiter is absent, so a bare `href="com.example.ProfileCardPreview"` — which resolves
    // relative to the current page and navigates nowhere near the preview — would have passed.
    assertEquals(
      "com.example.ProfileCardPreview",
      // The capture must be the FINAL segment. Unanchored, `/p/<id>/other` still yields `<id>` —
      // and the server registers only the exact `/p/{name}` routes, so that URL navigates nowhere.
      Regex("""/p/([^/?#]+)(?:[?#]|$)""").find(manifestHref)?.groupValues?.get(1),
      "…and its destination is THAT preview, on the preview route",
    )
    // …and one WITHOUT code is still a link, to the design file — the only destination it has. The
    // tag is the same; only the target differs, so a reader never meets a node that looks
    // navigable and is not.
    //
    // Matched on THAT node (`1:6`, the fixture's unlinked shape) rather than on "some anchor
    // exists and no span does": the renderable nodes satisfy a bare existence check on their own,
    // so this one could regress to a div and go unnoticed.
    val unlinkedNode =
      assertNotNull(
        Regex("""<(\w+) class="cp-page-node" [^>]*data-cp-node="1:6"[^>]*>""").find(designPageHtml),
        "the unlinked node 1:6 is emitted at all",
      )
    assertEquals(
      "a",
      unlinkedNode.groupValues[1],
      "an unlinked node stays an anchor rather than becoming inert",
    )
    // The WHOLE href, compared as one string. Checking parts independently loses whatever part is
    // not checked: a host check alone passes for the Figma homepage, and a key-plus-node check
    // alone passes for a relative URL or a different host carrying the same query.
    assertEquals(
      "https://www.figma.com/design/ocdacdEsnHipMJD3egzxKb?node-id=1-6",
      hrefOf(unlinkedNode.value),
      "…and its destination is THIS node in the catalog's design file",
    )

    // The catalog-wide MOTION BROWSER: every recording this catalog publishes, on one page.
    //
    // Captured because the page is the only place a reader can compare one component's transition
    // against its neighbour's, and because its resting state is load-bearing — every card opens on
    // its component's still, and nothing animates until someone presses it. A baseline of that
    // resting grid is what would catch the page starting to autoplay.
    //
    // Two sections and a component with TWO captures, deliberately: the section heads are the
    // page's only structure, and a component whose recordings differ only in their caption's tail
    // is exactly the case [MotionCaptureLabels] splits — one card per capture, distinguished in
    // the title, explained underneath.
    //
    // The Switch and the Icon Button each publish their captures on SEVERAL renders, which is the
    // production shape: a catalog hangs the same manifest entry off a component's default, its
    // states and both themes, and listed per render that turned `compose-m3` into 320 cards over
    // ten files. The fold is only visible in a baseline that carries the duplicate renders, so
    // these do — and the Icon Button's two recordings share one caption, so its block is also the
    // one that hoists that sentence above the cards instead of printing it under each.
    val switchCaptures =
      listOf(
        ServeMotion(
          id = "switch-on__ideal__default__light",
          kind = "interaction",
          caption =
            "Toggle on. The thumb travels the full width of the track and the container " +
              "recolours to the checked state as it lands.",
        ),
        ServeMotion(
          id = "switch-on__ideal__default__light__anim",
          kind = "animation",
          caption =
            "Thumb settle. Released mid-travel, the thumb overshoots and settles back " +
              "through the theme's spatial spring rather than snapping to the stop.",
        ),
      )
    val iconButtonCaptures =
      listOf("light", "dark").map { theme ->
        ServeMotion(
          id = "iconbutton-filled__ideal__default__$theme",
          kind = "interaction",
          caption =
            "Press and hold. Expressive animates the container into its pressed shape and " +
              "holds it there for the duration of the press; Baseline leaves it static.",
        )
      }
    val motionPreviews =
      listOf(
        ServePreview(
          "switch-on__ideal__default__light",
          "Switch · On",
          section = "Components",
          catalogOrder = 1,
          motion = switchCaptures,
        ),
        // The same two recordings again, on the switch's disabled render. One Switch block on the
        // page, not two.
        ServePreview(
          "switch-on__ideal__disabled__light",
          "Switch · On",
          section = "Components",
          catalogOrder = 1,
          state = "disabled",
          motion = switchCaptures,
        ),
        ServePreview(
          "card-filled__ideal__default__light",
          "Card · Filled",
          section = "Components",
          catalogOrder = 2,
          motion =
            listOf(
              ServeMotion(
                id = "card-filled__press",
                kind = "interaction",
                caption =
                  "Press and hold the card. The container lifts to its pressed elevation and " +
                    "the ripple expands from the contact point.",
              )
            ),
        ),
        ServePreview(
          "iconbutton-filled__ideal__default__light",
          "Icon Button · Filled",
          section = "Components",
          catalogOrder = 3,
          theme = "light",
          motion = iconButtonCaptures,
        ),
        ServePreview(
          "iconbutton-filled__ideal__default__dark",
          "Icon Button · Filled",
          section = "Components",
          catalogOrder = 3,
          theme = "dark",
          motion = iconButtonCaptures,
        ),
        // A capture with NO caption, which the annotation defaults produce and which the page has
        // to name honestly rather than leaving blank — the same fallback the viewer's picker uses.
        ServePreview(
          "profile__screen",
          "Profile screen",
          section = "Screens",
          catalogOrder = 4,
          motion = listOf(ServeMotion(id = "profile__scroll", kind = "interaction")),
        ),
        // …and a still-only component, which must NOT appear: the page is a list of recordings.
        ServePreview(
          "badge__ideal__default__light",
          "Badge",
          section = "Components",
          catalogOrder = 5,
        ),
      )
    val motionIndex =
      ServeWeb.motionIndexPage(
        moduleLabel = "compose-m3",
        previews = motionPreviews,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
      )

    val designPageIndex =
      ServeWeb.designPagesIndexPage(
        moduleLabel = "compose-m3",
        pages = listOf(designPageFixture),
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        reportIssue =
          fixturePageReportIssue("https://preview.coo.ee/compose-m3/pages", "these design pages"),
      )

    // The same themed catalog served LIVE by a session whose app declares `@ThemeCatalog` themes:
    // the header's Theme control lists every configured theme (issue #2881) — the baked Light/Dark
    // pair plus each declared theme — instead of only Light/Dark. Picking a declared theme
    // re-points each daemon-twinned card's thumbnail at a `?themeProvider=` render. Captured so the
    // visual-diff bot covers the widened control.
    val landingDeclaredThemes =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        // The motion browser's entry point, in the `⋯` menu with the catalog's other
        // destinations. This fixture is the one the harness OPENS that menu on (`actions-menu`),
        // so it is the only place the chip's pixels are ever captured.
        motionCaptureCount = 4,
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
            ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
          ),
        canRenderThemeFor = { true },
        themeRenderBurstCapacity = 5,
        // Carries the presence script, which is also what injects the render-server badge. Without
        // a fixture that emits it, neither the badge nor the themed-render swap it sits beside has
        // any visual-diff coverage — the harness shoots this page for both (see FIXTURE_STATES in
        // pages-snapshot.spec.mjs).
        presenceUrl = "/compose-m3/api/presence",
      )
    // The same catalog, IR-replayed: every card is redrawn by replaying a captured Remote Compose
    // document instead of re-running its composable, so a `themeProvider` render is refused 409 and
    // the declared chips must NOT be offered — picking one could only paint the grid with "This
    // preview can't render live". Captured beside [landingDeclaredThemes] so the diff is exactly
    // the widened control collapsing back to the baked Light/Dark pair, which is the claim: the
    // per-preview axes a replay CAN honour stay, only the composition-only one goes.
    val landingIrReplayThemes =
      ServeWeb.landingPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        declaredThemes =
          listOf(
            ServeTheme("Roboto Flex", "com.example.RobotoFlexThemeCatalog", group = "Typeface"),
            ServeTheme(
              "Google Sans Flex",
              "com.example.GoogleSansFlexThemeCatalog",
              group = "Typeface",
            ),
          ),
        // Fully live — a daemon twin for every card. Only the replay gate withholds the chips.
        canRenderThemeFor = { true },
        irReplayFor = { true },
        themeRenderBurstCapacity = 5,
      )
    // A live catalog: every card can be long-pressed to open a daemon session inside it. The
    // committed HTML holds the affordance's static half (the header note + the emitted config);
    // the gesture's two runtime states — the hover hint and a card actually streaming — are
    // captured as FIXTURE_STATES in pages-snapshot.spec.mjs, driven by the real script.
    val landingLive =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        canStreamLiveFor = { true },
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
    // A breakpoint-bearing catalog: one card per component at its first declared size, the other
    // four folded away. Captured so the visual-diff bot covers the size axis on every future PR.
    val landingBreakpoints =
      ServeWeb.landingPage(
        "wear-m3-catalog",
        breakpointPreviews,
        token,
        sessionId = "wear-m3-catalog",
        trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3-catalog",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/wear-m3-catalog",
        declaredSurface = "dark",
        version = version,
      )
    // …and its viewer, whose subtree lists the four folded breakpoints beside the state rows.
    val viewerBreakpoints =
      ServeWeb.viewerPage(
        breakpointPreviews.first(),
        token,
        sessionId = "wear-m3-catalog",
        catalogName = "M3 Wear OS Apps Design Kit",
        isPublic = true,
        basePath = "/wear-m3-catalog",
        siblings = breakpointPreviews,
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
    // A tabbed declared-theme catalog exercises initial queue priority before apply() has assigned
    // each card's hidden state (for a returning visitor whose saved tab is not the first one).
    //
    // Big enough, and with a burst capacity, to be the page the deferred-render contract runs on:
    // its cards outrun one viewport, so picking a theme leaves some of them held against the
    // scroll — the lane where a claim released by the on-screen batch used to strand them.
    val landingDeclaredTabbedThemes =
      ServeWeb.landingPage(
        "meshcore-mobile",
        sectionedPreviews,
        token,
        sessionId = "meshcore-mobile",
        declaredThemes = listOf(ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog")),
        canRenderThemeFor = { true },
        themeRenderBurstCapacity = 5,
      )
    // The default-state viewer for that catalog: renders the `.cp-axes-tree` subtree of
    // links to the component's other same-theme states, the current (Default) state marked active.
    val viewerStates =
      ServeWeb.viewerPage(
        statefulPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = statefulPreviews,
      )
    // Every disclosure at once, on the shape that motivated them: a state axis and a theme set both
    // wide enough to arrive FOLDED, plus sibling components so the nav toggle is there too. The
    // chips sit behind the title bar's `State · Default` / `Theme · Day` toggles, and the render
    // starts where three wrapped chip rows used to.
    val viewerAxesFolded =
      ServeWeb.viewerPage(
        wideStatePreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = wideStatePreviews + statefulPreviews,
        declaredThemes =
          listOf(
            ServeTheme("Baseline Light", "com.example.BaselineLightThemeCatalog"),
            ServeTheme("Baseline Dark", "com.example.BaselineDarkThemeCatalog"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog"),
          ),
      )
    // The cross-product viewer, entered on `pressed + RTL` — the render that has a non-default
    // value on BOTH axes, and so the only one from which a single-axis label is ambiguous. Its
    // subtree names both coordinates on every row and can walk either axis without leaving the
    // other behind.
    val viewerCrossProduct =
      ServeWeb.viewerPage(
        crossProductPreviews.first { it.state == "pressed" && it.props != null },
        token,
        sessionId = "compose-m3",
        siblings = crossProductPreviews,
      )
    // The tree at full depth. `synthesizeGroups` only divides a catalog with at least two families
    // and one family holding more than one card, and the variant/state fixtures above are each a
    // single component — so neither of them renders a tree at all, and the component and variant
    // rows would go uncaptured. This mixes them: a Button family of two cards (one carrying the
    // props axis), plus Checkbox and Radio button carrying the state axis.
    val treeDepthPreviews =
      variantPreviews +
        listOf(
          ServePreview(
            "button-outlined__ideal__default__light",
            "Button · Outlined (light)",
            state = "default",
            theme = "light",
          ),
          ServePreview(
            "button-outlined__ideal__default__dark",
            "Button · Outlined (dark)",
            state = "default",
            theme = "dark",
          ),
        ) +
        statefulPreviews
    val landingTreeDepth =
      ServeWeb.landingPage(
        "compose-m3",
        treeDepthPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
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
        // The design file's own pages, listed by name at the foot of the outline tree — the shape
        // m3-catalog is in, and the surface that replaced the header's "N pages" chip. Captured
        // here so the branch has a visual baseline of its own.
        designPages =
          listOf(
            // Sections on one page and none on the other, on purpose: the pane has to render both
            // a branch and a leaf, and a golden that only ever held one shape would not say so.
            ServeWeb.PageLink(
              "shape",
              "Shape",
              listOf(
                ServeWeb.PageSection("1:20", "Corner radius"),
                ServeWeb.PageSection("1:21", "Shape scale"),
              ),
            ),
            ServeWeb.PageLink("type", "Typography"),
          ),
      )
    // The streamlined Catalog mode, committed as first-class visual fixtures rather than only
    // structural assertions. These are also the PR evidence images for the feature: the catalog
    // inventory and a focused component page, rendered from the same production ServeWeb markup
    // and stylesheet the browser receives.
    val browserPreviews =
      listOf(
        ServePreview(
          "button-filled-default",
          "Filled button",
          componentId = "Button/Filled",
          state = "default",
          section = "Components",
          group = "Buttons",
        ),
        ServePreview(
          "button-filled-pressed",
          "Filled button pressed",
          componentId = "Button/Filled",
          state = "pressed",
          section = "Components",
          group = "Buttons",
          props = jsonProps("label" to "Continue"),
        ),
        ServePreview(
          "button-outlined-default",
          "Outlined button",
          componentId = "Button/Outlined",
          state = "default",
          section = "Components",
          group = "Buttons",
        ),
        ServePreview(
          "card-elevated-default",
          "Elevated card",
          componentId = "Card/Elevated",
          section = "Components",
          group = "Cards",
        ),
        ServePreview(
          "card-filled-default",
          "Filled card",
          componentId = "Card/Filled",
          section = "Components",
          group = "Cards",
        ),
        ServePreview(
          "navigation-bar-default",
          "Navigation bar",
          componentId = "Navigation/Navigation Bar",
          section = "Components",
          group = "Navigation",
        ),
        ServePreview(
          "profile-screen-default",
          "Profile screen",
          componentId = "Screens/Profile",
          section = "Screens",
          group = "Account",
        ),
      )
    val componentBrowserCatalog =
      ServeWeb.landingPage(
        "compose-m3",
        browserPreviews,
        token,
        sessionId = "compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/compose-m3",
        displayTitle = "Compose Material 3",
        declaredThemes = listOf(ServeTheme("Light", "com.example.LightThemeCatalog")),
        canRenderThemeFor = { true },
        componentBrowser = true,
        // Catalog mode keeps the catalog tracker: this is the presentation a design reviewer is
        // handed, and a reviewer is who a "this draws the wrong thing" report comes from (#4704).
        reportIssue = fixturePageReportIssue("https://preview.coo.ee/compose-m3/", "this catalog"),
      )
    val componentBrowserHome =
      ServeWeb.homeIndexPage(
          homeSystems,
          token,
          isPublic = true,
          version = version,
          componentBrowser = true,
        )
        .replace(Regex("[ \\t]+\\n"), "\n")
    val componentBrowserViewer =
      ServeWeb.viewerPage(
        browserPreviews.first { it.id == "button-filled-pressed" },
        token,
        sessionId = "compose-m3",
        catalogName = "Compose Material 3",
        catalogTitle = "Compose Material 3",
        basePath = "/compose-m3",
        isPublic = true,
        siblings = browserPreviews,
        canRenderOverrides = true,
        usageHref = "/compose-m3/usage/button-filled-pressed",
        hasSvgExport = true,
        // Carries the presence heartbeat — and with it the render-server poller — because Catalog
        // mode is where the badge has no header slot to land in. The Dev landing already captures
        // the badge's connected/idle states (`serve-landing-declared-themes`); this is the page
        // where the answer must be that NOTHING paints, and the harness can only hold that honest
        // if the poller is actually on the page it shoots.
        presenceUrl = "/compose-m3/api/presence",
        componentBrowser = true,
        // …and so does the component page, which is where a wrong render is actually noticed.
        reportIssue =
          fixtureReportIssue(
            "button-filled-pressed",
            "Filled button",
            "ui/buttons/FilledButton.kt",
          ),
      )
    // Catalog mode on a **Remote Compose** preview — the one page where the browser players are
    // still on offer there.
    //
    // Catalog mode used to strip the whole Remote Compose facet along with the rest of the dev
    // surface, which left a shared `?rcPlayer=…` link inert: no canvas, no chips, no switcher, and
    // no control owning the param, so it was quietly dropped from the URL and the page fell back to
    // the baked PNG. `js` and `cmp-wasm` replay published bytes in the visitor's own browser, so
    // none of the reasons the daemon-backed lanes are gated apply to them.
    //
    // Its own fixture because the plain Catalog viewer above carries no `.rc` document, so it
    // cannot show any of this — and without a fixture the surface would go back to being changed
    // without a picture. The claim it holds is a PAIR: the switcher is present and offers exactly
    // the two browser players, and the server-side ones are absent rather than greyed.
    val componentBrowserRemoteCompose =
      ServeWeb.viewerPage(
        browserPreviews.first { it.id == "button-filled-pressed" },
        token,
        sessionId = "compose-m3",
        catalogName = "Compose Material 3",
        catalogTitle = "Compose Material 3",
        basePath = "/compose-m3",
        isPublic = true,
        siblings = browserPreviews,
        canRenderOverrides = true,
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android", "cmp-jvm", "cmp-wasm"),
        componentBrowser = true,
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
    // The document lane (`--accept-docs`): the upload surface, and the expiring permalink page for
    // each known format. The permalink pages mount a vendored browser player, so the harness
    // captures the chrome around it (title, expiry pill, facts, download row) rather than the
    // played-back document itself.
    val docUpload =
      ServeWeb.docUploadPage(
        token,
        isPublic = true,
        ttlSeconds = 3600,
        urlUploadAllowed = true,
        version = version,
      )
    // The playground Stage-1 editor (`GET /playground`): the code box, mode selector, and result
    // pane. Always token-gated (the lane runs user code, refused under `--public`), so the fixture
    // renders the non-public form the server actually serves.
    // Rendered with the runtime catalog selector populated (`--playground`), because that is the
    // shape with the most moving parts — a pinned host renders the same page minus the Catalog
    // control, which the `playgroundPage omits the catalog control…` test pins separately.
    val playground =
      ServeWeb.playgroundPage(
        token,
        isPublic = false,
        version = version,
        editingLeaseEnabled = true,
        catalogs =
          listOf(
            PlaygroundCatalogInfo(
              id = "",
              label = "Server default",
              modes = PlaygroundMode.entries.toList(),
              resolved = true,
            ),
            PlaygroundCatalogInfo(
              id = "compose-m3",
              label = "compose-m3 (desktop)",
              backend = "desktop",
              modes = listOf(PlaygroundMode.CMP),
              resolved = true,
            ),
            PlaygroundCatalogInfo(
              id = "compose-wear",
              label = "compose-wear (android)",
              backend = "android",
              modes = listOf(PlaygroundMode.ANDROID, PlaygroundMode.REMOTE_COMPOSE),
              resolved = false,
            ),
          ),
      )
    // The handoff this host cannot honour: `/playground?from=horologist/…` on a server that browses
    // Android and Wear catalogs but runs only the desktop render backend, so no Android catalog is
    // a compile target. The link is withheld at the source now, so reaching this page means a
    // bookmark or a shared URL — and the page has to say so rather than silently retargeting the
    // buffer at whichever catalog happened to be first. Committed as its own golden because the
    // notice is a page state the ordinary playground fixture can never hold.
    val playgroundUncompilable =
      ServeWeb.playgroundPage(
        token,
        isPublic = false,
        version = version,
        catalogs =
          listOf(
            PlaygroundCatalogInfo(
              id = "compose-m3",
              label = "compose-m3 (desktop)",
              backend = "desktop",
              modes = listOf(PlaygroundMode.CMP),
              resolved = true,
            )
          ),
        catalogSelectorEnabled = true,
        seed =
          PlaygroundSeed(
            catalog = "horologist",
            previewId = "mediacontrolbuttonsplaying__ideal__default__compact",
            fileName = "MediaControlButtons.kt",
            text =
              """
              package com.google.android.horologist.media.ui.components

              @Preview
              @Composable
              fun MediaControlButtonsPlaying() {
                MediaControlButtons(onPlayButtonClick = {}, playing = true)
              }
              """
                .trimIndent(),
            blobUrl =
              "https://github.com/google/horologist/blob/main/media-ui/src/main/java/com/google/" +
                "android/horologist/media/ui/components/MediaControlButtons.kt",
            sliced = true,
          ),
      )
    val docLottie =
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = "0YFhq8Kb2s7cVv1nQpZs3A",
          name = "loading-spinner.json",
          formatId = ServeDocFormats.LOTTIE.id,
          formatLabel = ServeDocFormats.LOTTIE.label,
          playerPath = ServeDocFormats.LOTTIE.playerPath,
          rawPath = "/d/0YFhq8Kb2s7cVv1nQpZs3A/raw",
          facts =
            listOf(
              ServeDocFact("Name", "Loading spinner"),
              ServeDocFact("Bodymovin version", "5.7.4"),
              ServeDocFact("Size", "512 × 512"),
              ServeDocFact("Frames", "90 @ 30 fps"),
              ServeDocFact("Duration", "3s"),
              ServeDocFact("Layers", "6"),
            ),
          sizeText = "48 kB",
          expiresInText = "1h",
          expiresAtText = "2026-07-28T22:15:00Z",
          width = 512,
          height = 512,
        ),
        token,
        isPublic = true,
        version = version,
      )
    val docRemoteCompose =
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = "Tz3l9WcAq0Xj5RmB7dPuKw",
          name = "watchface.rc",
          formatId = ServeDocFormats.REMOTE_COMPOSE.id,
          formatLabel = ServeDocFormats.REMOTE_COMPOSE.label,
          playerPath = ServeDocFormats.REMOTE_COMPOSE.playerPath,
          rawPath = "/d/Tz3l9WcAq0Xj5RmB7dPuKw/raw",
          facts =
            listOf(
              ServeDocFact("Format version", "1.2.0"),
              ServeDocFact("Document size", "384 × 384"),
            ),
          sizeText = "12 kB",
          expiresInText = "58m",
          expiresAtText = "2026-07-28T22:13:00Z",
          width = 384,
          height = 384,
        ),
        token,
        isPublic = true,
        version = version,
      )

    // The styled 404 a browser gets when it follows a dead link to a catalog or preview page —
    // the site's own chrome with a "back to design systems" link, not a bare text/plain dead-end.
    // The agent access-grant CONSENT page (GET /agent-access/{id}). The one page on this server
    // whose job is to make a human suspicious of the link that brought them here, so the fixture
    // pins the parts that do that work: the verification code as the page's loudest element, the
    // agent-supplied purpose (escaped — the fixture's label carries markup on purpose), and one
    // scope the approver is shown but may not grant.
    val agentAccess =
      ServeWeb.agentGrantApprovalPage(
        requestId = "9c2Qk1pTf0Xb7hLm4nRzQA",
        userCode = "KX7M-9QD4",
        label = "fix wear-m3-catalog#68 <the focus ring>",
        client = "203.0.113.42",
        requestedScope = AgentGrantScope.PLAYGROUND,
        requestedTtlSeconds = 7200,
        expiresInSeconds = 540,
        approver = "@yschimke",
        selectableScopes = listOf(AgentGrantScope.PREVIEW, AgentGrantScope.LIVE),
        maxTtlSeconds = 8 * 3600,
        approveCsrf = "fixed-approve-seal",
        denyCsrf = "fixed-deny-seal",
        formAction = "/agent-access/9c2Qk1pTf0Xb7hLm4nRzQA",
        version = version,
        withheldScopes = listOf(AgentGrantScope.PLAYGROUND),
        withheldReason = "you do not hold it yourself on this server, so you cannot pass it on",
      )

    // The same page on a box that offers a CAPABILITY beside the scopes — the second fieldset, its
    // checkboxes unticked, and one capability the approver may not pass on. Its own fixture rather
    // than a variant of the one above, because the control that matters here (an independent
    // checkbox, where the scopes are a radio) only exists on a box whose operator opted in, and a
    // golden that never renders it would let that control change unseen.
    val agentAccessCapabilities =
      ServeWeb.agentGrantApprovalPage(
        requestId = "9c2Qk1pTf0Xb7hLm4nRzQA",
        userCode = "KX7M-9QD4",
        label = "embed the before/after in the PR body",
        client = "203.0.113.42",
        requestedScope = AgentGrantScope.LIVE,
        requestedTtlSeconds = 1800,
        expiresInSeconds = 540,
        approver = "@yschimke",
        selectableScopes = listOf(AgentGrantScope.PREVIEW, AgentGrantScope.LIVE),
        selectableCapabilities = listOf(AgentGrantCapability.IMAGES),
        maxTtlSeconds = 8 * 3600,
        approveCsrf = "fixed-approve-seal",
        denyCsrf = "fixed-deny-seal",
        formAction = "/agent-access/9c2Qk1pTf0Xb7hLm4nRzQA",
        version = version,
      )

    // What the approver lands on afterwards.
    val agentAccessGranted =
      ServeWeb.agentGrantNoticePage(
        heading = "Access granted",
        message =
          "The agent can now use this server for 2h. You can end it early from the server status " +
            "page at any time.",
        detail = "Scopes: preview, live · grant 4f2ab91c73de",
        version = version,
      )

    val notFound =
      ServeWeb.notFoundPage(
        "That preview does not exist in this catalog.",
        token,
        isPublic = true,
        version = version,
      )

    // The server STATUS page (GET /status): a snapshot of the running host — published catalogs +
    // their load/trust/liveness, the render daemons up now, the effective config, and recent daemon
    // startup failures. A representative spread (a live+running catalog, a degraded baked one, an
    // unlisted one, a running desktop daemon, and one recent failure so the amber "degraded" badge
    // +
    // failure table are captured) with fixed figures so the golden stays stable across runs.
    val serveStatus =
      ServeWeb.statusPage(
        token = token,
        version = version,
        view =
          ServeWeb.StatusView(
            version = version,
            public = true,
            // Exactly two hours after the fixed catalog generation time.
            nowMillis = 1_784_287_800_000,
            overallOk = false,
            healthReason = "1 daemon startup failure · 1 recent live render failure",
            healthHref = "#recent-daemon-failures",
            summary =
              listOf(
                // Both cards are derived from the catalog list by production
                // `ServeStatusSnapshot.toView()`, so they have to move with it: the `wear-m3` entry
                // below adds a fifth catalog and its 30 previews. A fixture whose summary disagrees
                // with its own table is a golden screenshot of a state the server cannot produce.
                ServeWeb.Stat(
                  "Catalogs",
                  "5/5 loaded",
                  ServeWeb.Meter(
                    total = 5,
                    segments = listOf(ServeWeb.MeterSegment("loaded", 5, "primary")),
                  ),
                ),
                ServeWeb.Stat(
                  "Published catalog renders",
                  "106 rendered · 1 failed · 0 deferred",
                  ServeWeb.Meter(
                    total = 107,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("rendered", 106, "primary"),
                        ServeWeb.MeterSegment("failed", 1, "warning"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Live daemons running", "1"),
                ServeWeb.Stat("Active streams", "2"),
                // What those two streams are achieving, in the shape `liveFrameText` prints: the
                // fps a viewer actually got, the median gap it came from, the painted/heartbeat
                // split, and the per-frame wire cost. "Active streams: 2" is a population; this is
                // the reading (#4281). Numbers taken from a real m3-catalog session so the row is
                // as long as it gets in practice.
                ServeWeb.Stat(
                  "Live frames",
                  "4.0 fps · p50 250ms · 1042 painted · 388 unchanged · 8 kB/frame",
                ),
                // Captured in the state that used to be invisible: a quiet gate held shut by a
                // session lease, which stands the theme optimizer down indefinitely while every
                // per-catalog row says only "paused". The fixture keeps the awkward case — the
                // longest of the four wordings, with a holder named — so the row's wrapping is
                // covered rather than the tidy "open · idle 90s" one.
                ServeWeb.Stat(
                  "Theme optimiser gate",
                  "closed · session lease held by compose-m3 · needs 60s quiet",
                ),
                ServeWeb.Stat(
                  "Live seats",
                  "3 free / 5",
                  ServeWeb.Meter(
                    total = 5,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("in use", 2, "secondary"),
                        ServeWeb.MeterSegment("free", 3, "primary"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Known sessions", "4"),
                ServeWeb.Stat("Uptime", "3d 4h"),
                ServeWeb.Stat(
                  "Live renders",
                  "1630 ok · 1 failed · 42 cached",
                  ServeWeb.Meter(
                    total = 1673,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("ok", 1630, "primary"),
                        ServeWeb.MeterSegment("failed", 1, "warning"),
                        ServeWeb.MeterSegment("cached", 42, "secondary"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Average render latency", "741ms"),
                ServeWeb.Stat("Worst first render", "13679ms"),
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
                  provenance = provenance,
                  themeOptimization =
                    ThemeOptimizationSnapshot(
                      state = "complete",
                      total = 168,
                      cached = 168,
                      remaining = 0,
                      failed = 0,
                      cachedBytes = 8_912_384,
                      fullyOptimized = true,
                      startedAtEpochMillis = 1_721_209_800_000,
                      completedAtEpochMillis = 1_721_209_920_000,
                    ),
                  renderCache =
                    CatalogRenderCacheSnapshot(
                      entries = 1448,
                      bytes = 13L * 1024 * 1024,
                      maxBytes = 128L * 1024 * 1024,
                      evictions = 0,
                    ),
                ),
                // A catalog part way through replacing another build's renders, with some of them
                // refusing to re-render. Every other catalog here is converged, so without this
                // entry the whole dirty/failed half of the optimization row — the wording, the
                // failure count and the meter's tone — was rendered by no fixture and therefore
                // diffed by nothing, which is how a status row can change unnoticed.
                ServeWeb.StatusCatalog(
                  id = "wear-m3",
                  title = "Wear Material 3",
                  listed = true,
                  trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                  previews = 30,
                  live = true,
                  running = true,
                  degradation = null,
                  provenance =
                    provenance.copy(
                      repo = "yschimke/wear-m3-catalog",
                      branch = "design-artifacts/wear-m3",
                    ),
                  themeOptimization =
                    ThemeOptimizationSnapshot(
                      state = "degraded",
                      total = 240,
                      cached = 232,
                      remaining = 8,
                      // Non-zero only because a *dirty* entry can now be counted: these are cached,
                      // so the old "not cached" rule reported this catalog as having no failures.
                      failed = 3,
                      cachedBytes = 11_403_264,
                      fullyOptimized = false,
                      dirty = 24,
                      startedAtEpochMillis = 1_721_209_800_000,
                    ),
                  renderCache =
                    CatalogRenderCacheSnapshot(
                      entries = 240,
                      bytes = 4L * 1024 * 1024,
                      maxBytes = 128L * 1024 * 1024,
                      evictions = 0,
                    ),
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
                    provenance.copy(
                      branch = "design-artifacts/remote-m3",
                      generatedAt = "2026-07-15T08:05:00.000Z",
                    ),
                ),
                // A trusted catalog whose daemon has gone idle: its facts are the last-known
                // snapshot, so the badge renders with a "last known" qualifier instead of the blank
                // cell that used to read as untrusted.
                ServeWeb.StatusCatalog(
                  id = "confetti-wear",
                  title = "Confetti Wear",
                  listed = true,
                  trust = "branch:joreilly/Confetti@design-artifacts/confetti-wear",
                  previews = 18,
                  live = true,
                  running = false,
                  degradation = null,
                  provenance =
                    provenance.copy(
                      repo = "joreilly/Confetti",
                      branch = "design-artifacts/confetti-wear",
                    ),
                  stale = true,
                ),
                ServeWeb.StatusCatalog(
                  id = "cadence",
                  title = "Cadence",
                  listed = false,
                  trust = "unverified",
                  previews = 11,
                  failedRenders = 1,
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
            renderFailures =
              listOf(
                ServeWeb.StatusRenderFailure(
                  whenText = "2026-07-17 09:43 UTC",
                  session = "compose-m3 (live bundle)",
                  durationText = "120000ms (timeout)",
                  reason = "timed out waiting for renderFinished",
                )
              ),
          ),
      )

    // The server's own bug-report page, captured from a *viewer* (the case that carries the most:
    // a resolved catalog, a preview, a render thumbnail) on a box that is not entirely healthy (a
    // catalog that failed to load and a render that timed out), because a report filed from a
    // perfectly healthy server is the one nobody sends. The render points at the harness's
    // placeholder lane, like every other fixture's stage.
    val bugReportServer =
      ServeBugReport.Server(
        version = version,
        public = true,
        uptimeSeconds = 3 * 86400 + 4 * 3600,
        java = "17.0.11 (Eclipse Adoptium)",
        os = "Linux 6.8.0-generic (amd64)",
        unhealthyCatalogs = listOf("`wear-m3`: failed — daemon launch timed out after 300s"),
        recentFailures =
          listOf(
            "2026-07-17 09:43 UTC  compose-m3 (live bundle): render failed after " +
              "120000ms (timeout) — timed out waiting for renderFinished"
          ),
      )
    val bugReportPageContext =
      ServeBugReport.Page(
        path = "/compose-m3/p/button-filled?uiMode=dark",
        url = "https://preview.coo.ee/compose-m3/p/button-filled?uiMode=dark",
        system = "compose-m3",
        previewId = "button-filled",
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        catalogToolVersion = "0.16.54",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        renderLane = "live daemon",
        renderUrl = "https://preview.coo.ee/compose-m3/render/button-filled.png?uiMode=dark",
        publicRender = true,
      )
    val bugReport =
      ServeWeb.bugReportPage(
        report =
          ServeWeb.BugReport(
            action = ServeBugReport.action(),
            body = ServeBugReport.body(bugReportServer, bugReportPageContext),
            bodyTemplate =
              ServeBugReport.body(
                bugReportServer,
                bugReportPageContext,
                clientPlaceholder = true,
              ),
            repo = ServeBugReport.REPO,
            renderUrl = "/compose-m3/render/button-filled.png",
            login = "yschimke",
          ),
        sections =
          listOf(
            ServeWeb.BugReportSection(
              "Server",
              listOf(
                "compose-preview" to version,
                "Mode" to "public (open)",
                "Uptime" to "3d 4h",
                "Server JVM" to "17.0.11 (Eclipse Adoptium)",
                "Server OS" to "Linux 6.8.0-generic (amd64)",
              ),
            ),
            ServeWeb.BugReportSection(
              "Page",
              listOf(
                "Page" to "/compose-m3/p/button-filled?uiMode=dark",
                "Design system" to "compose-m3",
                "Preview" to "button-filled",
                "Catalog" to "yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Catalog rendered by" to "compose-ai-tools 0.16.54",
                "Trust" to "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Render lane" to "live daemon",
              ),
            ),
            ServeWeb.BugReportSection(
              "Catalogs not loaded",
              listOf("" to "`wear-m3`: failed — daemon launch timed out after 300s"),
            ),
            ServeWeb.BugReportSection(
              "Recent failures",
              listOf(
                "" to
                  "2026-07-17 09:43 UTC  compose-m3 (live bundle): render failed after " +
                    "120000ms (timeout) — timed out waiting for renderFinished"
              ),
            ),
            ServeWeb.BugReportSection(
              "Browser",
              listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
            ),
          ),
        version = version,
      )
    // The same page as reached from a top-level SITE (issue #4319) — `wear.preview.coo.ee`, where
    // the hostname is one catalog and the reporter arrived from a design page with no preview on
    // it. Captured as its own golden because the paragraph that routes a *pixel* bug is different
    // here: it names the catalog and links its tracker instead of saying "go back to the preview",
    // and there is no render thumbnail to soften a page that is otherwise all prose.
    val bugReportSitePageContext =
      ServeBugReport.Page(
        path = "/pages/buttons",
        url = "https://wear.preview.coo.ee/pages/buttons",
        system = "wear-m3",
        catalog = "yschimke/wear-m3-catalog@design-artifacts/wear-m3",
        catalogToolVersion = "0.16.54",
        trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
        renderLane = "baked snapshots",
        publicRender = true,
      )
    val bugReportSite =
      ServeWeb.bugReportPage(
        report =
          ServeWeb.BugReport(
            action = ServeBugReport.action(),
            body = ServeBugReport.body(bugReportServer, bugReportSitePageContext),
            bodyTemplate =
              ServeBugReport.body(
                bugReportServer,
                bugReportSitePageContext,
                clientPlaceholder = true,
              ),
            repo = ServeBugReport.REPO,
            login = "yschimke",
            catalog =
              ServeWeb.BugReportCatalog(
                system = "wear-m3",
                title = "Wear Material 3",
                repo = "yschimke/wear-m3-catalog",
                issuesUrl = "https://github.com/yschimke/wear-m3-catalog/issues/new",
                site = true,
              ),
          ),
        sections =
          listOf(
            ServeWeb.BugReportSection(
              "Server",
              listOf(
                "compose-preview" to version,
                "Mode" to "public (open)",
                "Uptime" to "3d 4h",
                "Server JVM" to "17.0.11 (Eclipse Adoptium)",
                "Server OS" to "Linux 6.8.0-generic (amd64)",
              ),
            ),
            ServeWeb.BugReportSection(
              "Page",
              listOf(
                "Page" to "/pages/buttons",
                "Design system" to "wear-m3",
                "Catalog" to "yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                "Catalog rendered by" to "compose-ai-tools 0.16.54",
                "Trust" to "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                "Render lane" to "baked snapshots",
              ),
            ),
            ServeWeb.BugReportSection(
              "Browser",
              listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
            ),
          ),
        version = version,
        siteName = "Wear Material 3",
      )

    // The page goldens, named once: the same list backs both the `UPDATE_SERVE_WEB_FIXTURES=true`
    // regeneration below and the sync assertion further down, so a fixture can never be written
    // by one and forgotten by the other.
    // The card rasters the fixture page below frames. Drawn once and reused by both the golden and
    // the committed PNGs, so the two can't end up describing different pictures.
    val unfurlCards = socialCardFixtures()
    val renderedGoldens =
      listOf(
        "serve-landing.html" to landing,
        "serve-landing-public.html" to landingPublic,
        "serve-home-index.html" to homeIndex,
        "serve-viewer.html" to viewer,
        "serve-viewer-history.html" to viewerHistory,
        "serve-viewer-history-local.html" to viewerHistoryLocal,
        "serve-viewer-wasm.html" to wasmViewer,
        "serve-viewer-wasm-live.html" to wasmViewerLive,
        "serve-viewer-signin.html" to viewerSignIn,
        "serve-viewer-catalog-knobs.html" to viewerCatalogKnobs,
        "serve-viewer-themes.html" to viewerThemes,
        "serve-viewer-theme-overflow.html" to viewerThemeOverflow,
        "serve-viewer-focus.html" to viewerFocus,
        "serve-viewer-inspect.html" to viewerInspect,
        "serve-viewer-published-typography.html" to viewerPublishedTypography,
        "serve-viewer-exploded.html" to viewerExploded,
        "serve-viewer-gestures.html" to viewerGestures,
        "serve-viewer-source.html" to viewerSource,
        "serve-viewer-motion.html" to viewerMotion,
        "serve-landing-path.html" to landingPath,
        "serve-landing-site.html" to landingSite,
        "serve-viewer-path.html" to viewerPath,
        "serve-viewer-spec-default-theme.html" to viewerSpecDefaultTheme,
        "serve-viewer-rc-players.html" to viewerRcPlayers,
        "serve-viewer-wear-screen.html" to viewerWearScreen,
        "serve-landing-themed.html" to landingThemed,
        "serve-landing-catalog-palette.html" to landingCatalogPalette,
        "serve-viewer-catalog-palette.html" to viewerCatalogPalette,
        "serve-format-compare.html" to formatComparison,
        "serve-rc-lanes.html" to rcLanesComparison,
        "serve-reference-compare.html" to referenceComparison,
        "serve-reference-compare-pinned.html" to referenceComparisonPinned,
        "serve-reference-compare-dark-first.html" to referenceComparisonDarkFirst,
        "serve-reference-compare-round-device.html" to referenceComparisonRoundDevice,
        "serve-viewer-revisions.html" to viewerRevisions,
        "serve-viewer-revisions-open.html" to viewerRevisionsOpen,
        "serve-viewer-revision-runs.html" to viewerRevisionRuns,
        "serve-viewer-pinned-lanes.html" to viewerPinnedLanes,
        "serve-design-page.html" to designPageHtml,
        "serve-design-page-index.html" to designPageIndex,
        "serve-motion-index.html" to motionIndex,
        "serve-parity.html" to parity,
        "serve-landing-declared-themes.html" to landingDeclaredThemes,
        "serve-landing-declared-tabbed-themes.html" to landingDeclaredTabbedThemes,
        "serve-landing-ir-replay-themes.html" to landingIrReplayThemes,
        "serve-landing-live.html" to landingLive,
        "serve-landing-states.html" to landingStates,
        "serve-landing-sections.html" to landingSections,
        "serve-landing-breakpoints.html" to landingBreakpoints,
        "serve-viewer-breakpoints.html" to viewerBreakpoints,
        "serve-viewer-states.html" to viewerStates,
        "serve-viewer-axes-folded.html" to viewerAxesFolded,
        "serve-viewer-cross-product.html" to viewerCrossProduct,
        "serve-status.html" to serveStatus,
        "serve-report-bug.html" to bugReport,
        "serve-report-bug-site.html" to bugReportSite,
        "serve-landing-variants.html" to landingVariants,
        "serve-landing-tree-depth.html" to landingTreeDepth,
        "serve-viewer-variants.html" to viewerVariants,
        "serve-landing-grouped.html" to landingGrouped,
        "serve-component-browser-home.html" to componentBrowserHome,
        "serve-component-browser-catalog.html" to componentBrowserCatalog,
        "serve-component-browser-component.html" to componentBrowserViewer,
        "serve-component-browser-remote-compose.html" to componentBrowserRemoteCompose,
        "serve-viewer-nav-collapsed.html" to viewerNavCollapsed,
        "serve-notfound.html" to notFound,
        "serve-agent-access.html" to agentAccess,
        "serve-agent-access-capabilities.html" to agentAccessCapabilities,
        "serve-agent-access-granted.html" to agentAccessGranted,
        "serve-docs-upload.html" to docUpload,
        "serve-playground.html" to playground,
        "serve-playground-uncompilable.html" to playgroundUncompilable,
        "serve-doc-lottie.html" to docLottie,
        "serve-doc-remotecompose.html" to docRemoteCompose,
        // Not a served page: a frame around the drawn link-unfurl cards, so that raster surface is
        // screenshotted and diffed on every PR like the pages are. See [socialCardPage].
        "serve-social-card.html" to socialCardPage(unfurlCards),
      )

    // Normalise once, here, so the regeneration below and the sync assertion further down cannot
    // disagree about what a golden is supposed to contain.
    val goldens = renderedGoldens.map { (name, html) -> name to stableAssetHrefs(html) }

    if (update) {
      pagesDir.mkdirs()
      goldens.forEach { (name, html) -> File(pagesDir, name).writeText(html) }
      writePlaceholderPng(File(pagesDir, "_render-placeholder.png"))
      File(pagesDir, "_render-placeholder.svg").writeText(renderPlaceholderSvg())
      unfurlCards.forEach { (name, card) -> File(pagesDir, name).writeBytes(card.bytes) }
      return
    }

    assertGoldensInSync(pagesDir, goldens)
    assertUnfurlCardsInSync(pagesDir, unfurlCards)
    assertFalse(
      designPageHtml.contains(
        "class=\"cp-page-node\" data-link=\"unlinked\" data-cp-node=\"1:0\""
      ) ||
        designPageHtml.contains(
          "class=\"cp-page-row\" data-link=\"unlinked\" data-cp-node=\"1:0\""
        ),
      "a component-set grid is page structure, never a giant interactive hotspot or audit row",
    )
    assertFalse(
      designPageHtml.contains("data-cp-node=\"1:9\""),
      "private sheet furniture is not presented as missing component work",
    )
    assertTrue(
      designPageHtml.contains("data-cp-gap data-cp-node=\"1:6\""),
      "a concrete unimplemented component remains a focused gap hotspot",
    )
    // The parity page's load-bearing claims, asserted rather than left to the pixel diff. A
    // comment on Switch (whose code never moved in this window) is one-sided design movement, so
    // it must reach the "needs a look" band; Button moved on both sides and must NOT, because that
    // band exists to be short.
    assertTrue(
      parity.contains("Out-of-sync activity") &&
        parity.contains("Switch on") &&
        parity.contains("design only"),
      "one-sided design movement reaches the drift band",
    )
    assertFalse(
      parity
        .substringAfter("Out-of-sync activity")
        // The band that follows is `<cp-parity-scores>`, which renders its own "Visual
        // differences" heading client-side — so the tag, not the heading, is what bounds the drift
        // band in the served markup.
        .substringBefore("<cp-parity-scores>")
        .contains("Button"),
      "a component that moved on both sides is not drift",
    )
    assertTrue(
      parity.contains("All comparisons (3)") &&
        parity.contains("data-parity-comparison") &&
        parity.contains("<cp-parity-scores></cp-parity-scores>"),
      "the secondary inventory includes measured visual parity: $parity",
    )
    // Coverage is derived live from the previews + references, never from the published feed: 3
    // components (light/dark folded), one of them mapped.
    assertTrue(
      parity.contains("mapped</div>") && parity.contains(">1/3<"),
      "coverage tile: $parity",
    )
    // The unmapped chips link to the viewer; a mapped component's feed row links to its comparison.
    assertTrue(
      parity.contains("href=\"/compose-m3/p/switch-on__ideal__default__light\""),
      "an unmapped component opens its viewer",
    )
    assertTrue(
      parity.contains("href=\"/compose-m3/compare/button-filled__ideal__default__light\""),
      "a mapped component opens its reference comparison",
    )
    // A resolved comment is still shown (it is history) but visually stood down.
    assertTrue(parity.contains("cp-parity-entry--resolved"), "resolved comments render greyed")
    // The overrides drawer defaults closed so the preview leads.
    assertTrue(
      viewer.contains("class=\"cp-viewer\"") &&
        viewer.contains("id=\"cp-controls-toggle\" aria-expanded=\"false\""),
      "the overrides drawer defaults closed",
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
        viewer.contains("class=\"cp-viewer\"") &&
        !viewer.contains("class=\"cp-viewer cp-nav-open\""),
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
    // Project mode addresses an old render on THIS server (by content sha) rather than on
    // raw.githubusercontent.com, and must not advertise a manifest URL it has no repo to fetch.
    assertTrue(
      viewerHistoryLocal.contains("data-history-blob-url=\"/history/render/{blob}.png?token=") &&
        !viewerHistoryLocal.contains("data-history-url=") &&
        !viewerHistoryLocal.contains("data-history-repo="),
      "the project-mode timeline links at this server's own render lane",
    )
    // The declared Remote Compose knobs render as their own "Remote Compose" control group, one
    // `.cp-rc-knob` per knob carrying its name + wire kind (a `color` swatch value + a `string`),
    // separate from the plain-Compose Overrides panel.
    assertTrue(
      viewerCatalogKnobs.contains("data-cp-group=\"remotecompose\"") &&
        viewerCatalogKnobs.contains(">Remote Compose</summary>"),
      "the live catalog knob viewer shows the Remote Compose control group",
    )
    assertTrue(
      viewerCatalogKnobs.contains(
        "class=\"cp-rc-knob\" data-rc-name=\"shaderColor\" " + "data-rc-kind=\"color\""
      ),
      "the declared RC colour knob renders a control tagged with its name + kind",
    )
    assertTrue(
      viewerCatalogKnobs.contains("data-rc-name=\"label\" data-rc-kind=\"string\""),
      "the declared RC string knob renders a control tagged with its name + kind",
    )
    // A preview that declares no RC knobs shows no Remote Compose group (no dead panel).
    assertFalse(
      viewerThemes.contains("data-cp-group=\"remotecompose\""),
      "a preview without RC knobs shows no Remote Compose control group",
    )
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
    // The projected palette is inlined AFTER serve.css, so it wins at equal specificity — and it
    // carries both modes, so a dark-mode visitor to a light-first catalog still gets its brand.
    assertTrue(
      landingCatalogPalette.substringAfter("serve.css").startsWith("\">\n        <style>"),
      "the catalog palette is inlined directly after the stylesheet link",
    )
    assertTrue(
      // Both modes, as one `light-dark()` pair per property — the shape the page-theme setting
      // needs, since pinning `color-scheme` can only re-resolve a pair (see ServeThemeCssTest).
      viewerCatalogPalette.contains("--cp-accent: light-dark(#bf0031, ") &&
        !viewerCatalogPalette.contains("@media (prefers-color-scheme: dark)"),
      "the viewer carries the catalog's accent in both modes",
    )
    // A plain (non-catalog) session inlines nothing at all.
    assertFalse(landingThemed.contains("<style>"), "an unthemed page carries no inline palette")
    // Every page a visitor can reach *inside* a catalog carries the palette — walking grid →
    // compare formats → focused Reference/Diff/Actual must not drop back to the built-in chrome
    // partway through.
    val palette = ServeThemeCss.fromDtcg(jetNewsTokens)!!
    val inCatalogPages =
      mapOf(
        "landing" to ServeWeb.landingPage("jetnews", themedPreviews, token, themeCss = palette),
        "viewer" to ServeWeb.viewerPage(previews.first(), token, themeCss = palette),
        "format comparison" to
          ServeWeb.comparisonPage("jetnews", themedPreviews, token, themeCss = palette),
        "reference comparison" to
          ServeWeb.referenceComparisonPage(
            moduleLabel = "jetnews",
            preview = themedPreviews.first(),
            reference = comparisonReferences.first(),
            token = token,
            themeCss = palette,
          ),
      )
    for ((name, html) in inCatalogPages) {
      assertTrue(
        html.contains("--cp-accent: light-dark(#bf0031, "),
        "the $name page carries the palette",
      )
    }
    // One assist chip per comparable format, each deep-linking the format it names, rather than a
    // single "compare formats" text link that hid what this catalog can actually compare.
    assertTrue(
      landingThemed.contains(
        "<a class=\"cp-action-chip\" href=\"/compare?format=svg&amp;session=compose-m3\">" +
          "compare SVG</a>"
      ) &&
        landingThemed.contains(
          "<a class=\"cp-action-chip\" href=\"/compare?format=rc&amp;session=compose-m3\">" +
            "compare RC players</a>"
        ),
      "a catalog with alternate formats links each one separately: $landingThemed",
    )
    // …and the reference comparison is one of them, named after the tool it compares against and
    // deep-linking the same comparison page as its siblings — not the parity dashboard, which is a
    // different question and keeps its own name.
    assertTrue(
      landingPath.contains(
        "<a class=\"cp-action-chip\" href=\"/meshcore-mobile/compare?format=reference\">" +
          "compare to Figma</a>"
      ) &&
        landingPath.contains(
          "<a class=\"cp-action-chip\" href=\"/meshcore-mobile/parity\">design parity</a>"
        ),
      "a Figma-specified catalog compares against Figma and links the parity dashboard separately",
    )
    assertTrue(
      formatComparison.contains("data-compare-format=\"svg\"") &&
        formatComparison.contains("data-compare-format=\"rc\"") &&
        formatComparison.contains("data-compare-format=\"reference\"") &&
        formatComparison.contains("data-compare-theme=\"light\"") &&
        formatComparison.contains("data-compare-theme=\"dark\""),
      "the comparison page exposes only its available formats and its baked theme pair",
    )
    // The player wall's load-bearing claims, asserted rather than left to the pixel diff.
    assertTrue(
      rcLanesComparison.contains("id=\"cp-rc-lanes\"") &&
        rcLanesComparison.contains("data-rc-lanes=\"1\"") &&
        rcLanesComparison.contains(">Remote Compose players</button>") &&
        rcLanesComparison.contains("<cp-rc-lanes></cp-rc-lanes>"),
      "a catalog with a published rc-compare manifest gets the player wall, not the in-browser lane",
    )
    assertEquals(
      listOf(
        "AndroidX Embedded · baked",
        "RC · JS player",
        "AndroidX Embedded · vendored Android",
        "AndroidX Embedded · androidx.dev",
        "RC · cmp-jvm player",
        "RC · cmp-wasm player",
      ),
      Regex("<th>([^<]+)</th>")
        .findAll(rcLanesComparison.substringAfter("cp-rc-table").substringBefore("</thead>"))
        .map { it.groupValues[1] }
        .toList()
        .drop(1),
      "every player the run covered is its own column, in the published order",
    )
    assertEquals(
      listOf("none", "baked", "js", "embedded", "androidx-embedded", "cmp-jvm", "cmp-wasm"),
      Regex("data-rc-ref=\"([^\"]+)\"")
        .findAll(rcLanesComparison)
        .map { it.groupValues[1] }
        .toList(),
      "every column — including the baked reference — can itself be picked as the diff reference",
    )
    // Worst-match first on the **worst-scoring player**, not on any one lane — which is the point:
    // the second preview reorders ahead of the third on its cmp-wasm score even though its JS score
    // is better, so a preview only one player gets wrong still surfaces.
    assertEquals(
      listOf(
          "button-filled__ideal__default__light",
          "switch-on__ideal__default__light",
          "button-filled__ideal__default__dark",
          "switch-on__ideal__default__dark",
          "badge",
        )
        .map { "/p/$it" },
      Regex("<a href=\"(/p/[^\"?]+)")
        .findAll(rcLanesComparison.substringAfter("cp-rc-table"))
        .map { it.groupValues[1] }
        .toList(),
      "rows sort worst-match-first on the worst-scoring player",
    )
    assertTrue(
      rcLanesComparison.contains("/rc-compare/js/0.png?session=remote-m3") &&
        rcLanesComparison.contains("cp-rc-missing\">Document is not renderable by the CMP player"),
      "a rendered lane shows its published PNG; a lane that refused the document shows its reason",
    )
    val escapedComparison =
      ServeWeb.comparisonPage(
        moduleLabel = "compose-m3",
        previews = themedPreviews,
        token = token,
        displayTitle = "<script>alert('title')</script>",
        hasSvgFor = { true },
      )
    assertTrue(
      escapedComparison.contains("&lt;script&gt;alert(&#39;title&#39;)&lt;/script&gt;</a>") &&
        !escapedComparison.contains("<script>alert('title')</script></a>"),
      "the catalog-authored comparison breadcrumb title is HTML-escaped",
    )
    assertTrue(
      referenceComparison.contains(">Reference</h2>") &&
        referenceComparison.contains(">Diff</h2>") &&
        referenceComparison.contains(">Actual</h2>") &&
        referenceComparison.contains("Source:</strong> figma · revision fixture-42") &&
        referenceComparison.contains("aria-label=\"Design references\"") &&
        referenceComparison.contains(">Review revision</a>"),
      "the focused comparison presents the handoff triptych and provenance",
    )
    assertTrue(
      referenceComparison.contains("compose-parity-locator/v1") &&
        referenceComparison.contains(
          "overrides: {&quot;fontScale&quot;:&quot;1.5&quot;,&quot;knob.label&quot;:&quot;Send;now=x&quot;}"
        ) &&
        referenceComparison.contains("{{rawScores}}"),
      "the focused comparison pins the locator, canonical overrides and score placeholder",
    )
    // The selection placeholder rides in the TEMPLATE only. The server-rendered body is what a
    // visitor with JS off files, and it must not carry a token nothing will ever substitute.
    assertEquals(
      1,
      referenceComparison.split("{{selection}}").size - 1,
      "the selection placeholder appears once, in the template and nowhere else",
    )
    assertTrue(
      referenceComparison.substringAfter("data-report-template=\"").contains("{{selection}}"),
      "the selection placeholder rides in the report template",
    )
    assertTrue(
      referenceComparison.contains("id=\"cp-compare-actual\"") &&
        referenceComparison.contains("id=\"cp-render-inspect-layer\"") &&
        referenceComparison.contains("data-cp-host=\"#cp-compare-actual\"") &&
        // Opted in, so a box on THIS page is a target rather than only a reading aid — the brief's
        // first of two ways to choose. The viewer's mount carries no such attribute.
        referenceComparison.contains("data-cp-selectable=\"1\"") &&
        referenceComparison.contains("<cp-inspect-layers"),
      "the focused comparison mounts the derived semantics layers over its Actual panel",
    )
    assertTrue(
      referenceComparison.contains("<cp-element-selection>") &&
        referenceComparison.contains("class=\"cp-selection-tag\"") &&
        // Built through the page's own link rules, so it carries whatever credential the reader
        // presented — a hand-rolled query builder read only the request's query parameters and
        // dropped it entirely for a header- or bearer-authorized page, silently hiding the picker.
        referenceComparison.contains(
          "data-cp-tags=\"/tags/button-filled__ideal__default__light?session=compose-m3\""
        ) &&
        referenceComparison.contains("id=\"cp-selection-layer\""),
      "the focused comparison offers both a tag picker and a drag region",
    )
    // The pinned twin is the gate, and it is the load-bearing half of this feature: the published
    // index describes the CURRENT render, so offering a tag selection here would persist bounds
    // measured on different pixels into the acceptance's baseline — and later report an element
    // that never moved as moved. The drag stays, because it is read off the pixels on screen.
    assertTrue(
      !referenceComparisonPinned.contains("data-cp-tags=") &&
        referenceComparisonPinned.contains("class=\"cp-selection-drag\""),
      "a pinned comparison withholds tag selection and keeps the drag",
    )
    // …and withholds the OTHER separately-fetched source of bounds for the same reason. The layers
    // may still draw (a reading aid costs nothing out of date); clicking one records an
    // acceptance's
    // authoring-time baseline, and `.annotations` is a separate request from the PNG on screen.
    assertTrue(
      !referenceComparisonPinned.contains("data-cp-selectable="),
      "a pinned comparison withholds annotation-box selection too",
    )
    // A host whose PNG is baked but whose ANNOTATIONS come from a live daemon is the same mismatch
    // by another route — both live catalog wrappers are exactly that — so the page must not read
    // `annotationsSelectable` off the PNG lane's flags. The layers still draw; only the click goes.
    val liveAnnotationsComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = true,
        annotationsSelectable = false,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            selectionPlaceholder = true,
          ),
      )
    assertTrue(
      liveAnnotationsComparison.contains("id=\"cp-render-inspect-layer\"") &&
        !liveAnnotationsComparison.contains("data-cp-selectable=") &&
        liveAnnotationsComparison.contains("class=\"cp-selection-drag\""),
      "live annotations draw but cannot be selected; the drag stays",
    )
    assertTrue(
      liveAnnotationsComparison.contains("data-cp-inspect=\"theme\"") &&
        liveAnnotationsComparison.contains("data-cp-inspect=\"layout\""),
      "the semantics lane carries all three layers",
    )
    // The one host that CAN be selected is the static bundle: no daemon, so it answers
    // `.annotations` from what the catalog published over the very PNG it serves. It therefore has
    // no `hasDesignAnnotationsFor`, and gating the mount on that alone made the pick path
    // unreachable everywhere — the only selectable host was the only one with no mount, and every
    // host with a mount renders per request. The intersection was empty in production while both
    // halves looked individually correct, which is why this asserts the COMBINATION rather than
    // either flag.
    val publishedTypographyComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = false,
        publishedTypography = true,
        annotationsSelectable = true,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            selectionPlaceholder = true,
          ),
      )
    assertTrue(
      publishedTypographyComparison.contains("id=\"cp-render-inspect-layer\"") &&
        publishedTypographyComparison.contains("data-cp-selectable=\"1\""),
      "a published-typography host mounts the layers AND may select them",
    )
    // Typography only. Theme and Layout are projected from a semantics tree and nothing authors
    // them into a bundle, so offering their checkboxes here would be two controls whose fetch can
    // only come back with nothing to draw.
    assertTrue(
      publishedTypographyComparison.contains("data-cp-inspect=\"typography\"") &&
        !publishedTypographyComparison.contains("data-cp-inspect=\"theme\"") &&
        !publishedTypographyComparison.contains("data-cp-inspect=\"layout\""),
      "the published lane carries Typography and no dead controls",
    )
    // And a host with neither lane draws nothing at all rather than an empty layer div.
    val noAnnotationsComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = false,
        publishedTypography = false,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            selectionPlaceholder = true,
          ),
      )
    assertTrue(
      !noAnnotationsComparison.contains("id=\"cp-render-inspect-layer\"") &&
        !noAnnotationsComparison.contains("<cp-inspect-layers") &&
        noAnnotationsComparison.contains("class=\"cp-selection-drag\""),
      "no annotation lane means no mount, and the drag still stands alone",
    )
    // The substitution moved into `<cp-reference-compare>` with the rest of this page, so the
    // bundle is where it is now pinned. The property being held is the same one: the filled report
    // reaches an INPUT's `value` and nothing else — never an href or any other navigation sink.
    //
    // Both placeholders are matched by the shape the WRITER above emits them in, not as bare text:
    // the render one is a markdown link destination and the score one is a whole table row. A bare
    // substring replace rewrote the first occurrence anywhere in the body, so catalog-authored text
    // carrying either literal — a preview id, a variant derived from one — was edited instead while
    // the real link or row kept its placeholder. These strings are therefore the contract between
    // the two files, and a change on either side has to move both.
    assertTrue(
      assetText("serve-components.js").contains(".value=") &&
        assetText("serve-components.js").contains("](" + "{{render}})") &&
        assetText("serve-components.js").contains("| Raw comparison | `{{rawScores}}` |"),
      "the components bundle substitutes the report input value after comparison",
    )
    val referencedState =
      ServePreview(
        id = "button-filled__ideal__pressed__light",
        label = "Button · Filled pressed",
        state = "pressed",
        theme = "light",
      )
    assertTrue(
      ServeWeb.comparisonPage(
          moduleLabel = "compose-m3",
          previews = listOf(referencedState),
          token = token,
          referencesFor = { id ->
            listOf(
              DesignReference(
                id = "pressed-reference",
                previewId = id,
                raster = DesignReferenceRaster("references/pressed.png"),
              )
            )
          },
        )
        .contains("data-preview-ids=\"button-filled__ideal__pressed__light\""),
      "an exactly referenced non-default state remains a comparison row",
    )
    val buttonComparison =
      formatComparison.substringAfter("data-label=\"button-filled\"").substringBefore("</tr>")
    assertTrue(
      buttonComparison.contains(
        "data-png-light=\"/render/button-filled__ideal__default__light.png?session=compose-m3\""
      ) &&
        buttonComparison.contains(
          "data-svg-light=\"/render/button-filled__ideal__default__light.svg?session=compose-m3\""
        ) &&
        buttonComparison.contains(
          "data-png-dark=\"/render/button-filled__ideal__default__dark.png?session=compose-m3\""
        ) &&
        buttonComparison.contains(
          "data-svg-dark=\"/render/button-filled__ideal__default__dark.svg?session=compose-m3\""
        ),
      "each comparison row pairs PNG and SVG from the exact same baked theme variant",
    )
    val variantComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        variantPreviews,
        token,
        sessionId = "compose-m3",
        hasSvgFor = { true },
      )
    val variantComparisonIds =
      variantComparison.substringAfter("data-preview-ids=\"").substringBefore('"')
    assertTrue(
      variantComparisonIds.contains("button-filled__ideal__default__light__direction-rtl"),
      "a folded non-default variant deep-link aliases to its included component comparison row",
    )
    val sizedVariantPreviews =
      listOf("compact", "expanded").flatMap { size ->
        listOf(
          ServePreview(
            "button-filled__ideal__default__light__$size",
            "Button · Filled · $size",
            state = "default",
            theme = "light",
          ),
          ServePreview(
            "button-filled__ideal__pressed__light__$size",
            "Button · Filled · pressed · $size",
            state = "pressed",
            theme = "light",
          ),
          ServePreview(
            "button-filled__ideal__default__light__${size}__direction-rtl",
            "Button · Filled · RTL · $size",
            state = "default",
            theme = "light",
            props = jsonProps("direction" to "rtl"),
          ),
        )
      }
    val sizedVariantComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        sizedVariantPreviews,
        token,
        sessionId = "compose-m3",
        hasSvgFor = { true },
      )
    val sizedComparisonIds =
      Regex("data-preview-ids=\"([^\"]+)\"")
        .findAll(sizedVariantComparison)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(2, sizedComparisonIds.size)
    assertTrue(
      sizedComparisonIds[0].contains("__compact") &&
        sizedComparisonIds[0].contains("__pressed__light__compact") &&
        sizedComparisonIds[0].contains("__compact__direction-rtl") &&
        !sizedComparisonIds[0].contains("__expanded"),
      "compact aliases fold state and props without selecting the expanded comparison row",
    )
    assertTrue(
      sizedComparisonIds[1].contains("__expanded") &&
        sizedComparisonIds[1].contains("__pressed__light__expanded") &&
        sizedComparisonIds[1].contains("__expanded__direction-rtl") &&
        !sizedComparisonIds[1].contains("__compact"),
      "expanded aliases fold state and props without selecting the compact comparison row",
    )
    // Long-press a card and its preview streams from the daemon in place. The page carries the
    // gesture's configuration — each card's streamable ids, emitted in document order rather than
    // read back off the DOM — plus the header note that says the lane exists at all.
    assertTrue(
      landingLive.contains("window.cpCatalogLive = {base:\"\",query:\"session=compose-m3\"") &&
        landingLive.contains("cards:[{l:\"button-filled__ideal__default__light\"") &&
        landingLive.contains("hold a card for a live session"),
      "the live catalog page wires the long-press lane",
    )
    // Issue #2881: the header control lists every CONFIGURED theme, not just Light/Dark — the baked
    // pair plus one chip per declared `@ThemeCatalog` theme, each carrying its provider FQN.
    assertTrue(
      landingDeclaredThemes.contains("data-theme-choice=\"light\"") &&
        landingDeclaredThemes.contains("data-theme-choice=\"dark\"") &&
        landingDeclaredThemes.contains(
          "data-theme-choice=\"theme:com.example.BrandLightThemeCatalog\""
        ) &&
        landingDeclaredThemes.contains(
          "data-theme-choice=\"theme:com.example.HighContrastThemeCatalog\""
        ),
      "the catalog Theme control offers the baked pair plus every declared theme",
    )
    // …unless the cards are replayed from a captured document, which a theme provider has no
    // composition to wrap: the declared chips go, the baked pair (which a replay CAN honour, via
    // the player's own paint-time theme) stays. Both catalogs are equally live — the difference is
    // only whether the render re-runs the composable.
    assertTrue(
      landingIrReplayThemes.contains("data-theme-choice=\"light\"") &&
        landingIrReplayThemes.contains("data-theme-choice=\"dark\""),
      "an IR-replayed catalog keeps its baked light/dark axis",
    )
    assertFalse(
      landingIrReplayThemes.contains("data-theme-choice=\"theme:"),
      "…and offers no declared theme, whose render the server refuses 409",
    )
    assertFalse(
      landingIrReplayThemes.contains("var themeBase = ["),
      "…nor any themed-render URL for the script to fetch",
    )
    // Picking a declared theme re-renders through `themeProvider`. The per-card base URLs are
    // emitted by the SERVER (in the grid's document order) and never read back out of the DOM, so
    // no `<img src>` the script assigns originates as DOM text (CodeQL js/xss-through-dom).
    assertTrue(
      landingDeclaredThemes.contains(
        "var themeBase = [\"/render/button-filled__ideal__default__light.png?session=compose-m3\""
      ) && landingDeclaredThemes.contains("\"themeProvider=\" + encodeURIComponent(provider)"),
      "the server emits each card's themed-render URL for the script to use",
    )
    assertFalse(
      landingDeclaredThemes.contains("data-base-src"),
      "no render URL is round-tripped through a DOM attribute",
    )
    // A declared-theme selection asks the server for one short-lived page lease. A grant may run
    // five workers; denial/failure stays serial. Retries keep the same lease capability.
    assertTrue(
      landingDeclaredThemes.contains("var job = {") &&
        landingDeclaredThemes.contains(
          "var themeLeaseUrl = \"/api/theme-render-lease?session=compose-m3\""
        ) &&
        landingDeclaredThemes.contains("var themeRenderRetries = 3") &&
        landingDeclaredThemes.contains("function acquireThemeLease(gen, callback)") &&
        landingDeclaredThemes.contains("Math.max(1, Math.min(5, grant.concurrency))") &&
        landingDeclaredThemes.contains("function runThemeWorker(queue, gen, batch)") &&
        landingDeclaredThemes.contains(
          "runThemeQueue(themeQueue, themeQueueGen, lease, concurrency)"
        ) &&
        landingDeclaredThemes.contains("var workers = Math.min(concurrency, queue.length)") &&
        landingDeclaredThemes.contains("&_themeLease=\" + encodeURIComponent(lease)") &&
        landingDeclaredThemes.contains("job.src = job.baseSrc + \"&_retry=\" + job.retries") &&
        landingDeclaredThemes.contains("if (gen !== themeGen) return;") &&
        landingDeclaredThemes.contains(
          "queue.push(job);\n        runThemeWorker(queue, gen, batch)"
        ) &&
        landingDeclaredThemes.contains("1000 * Math.pow(2, job.retries)") &&
        landingDeclaredThemes.contains("releaseThemeLease(batch.lease, false)") &&
        landingDeclaredThemes.contains("navigator.sendBeacon(url, \"\")"),
      "themed renders use a leased worker burst with bounded backoff retries",
    )
    assertTrue(
      landingDeclaredThemes.contains("job.card.classList.add(\"cp-reloading\")") &&
        landingDeclaredThemes.contains("job.card.classList.remove(\"cp-reloading\")") &&
        landingDeclaredThemes.contains("job.card.setAttribute(\"aria-busy\", \"true\")"),
      "themed cards expose a busy treatment until each replacement thumbnail settles",
    )
    // Issue #3160: when the visitor is on a later tab, its visible cards must lead the serial
    // daemon queue. Otherwise every hidden Theme/Component card renders before the selected tab's
    // first image request, making the theme control appear to do nothing.
    //
    // The deferred half is no longer appended onto that queue once the visible cards are enqueued:
    // a large catalog is 80+ cards through a one-at-a-time daemon, so draining it spends a minute
    // rendering pixels nobody scrolled to. It now waits on the viewport instead — which serves
    // #3160's intent more strictly than the concat did, since a hidden tab's cards are not rendered
    // at all until that tab is opened, rather than merely rendered last.
    assertTrue(
      landingDeclaredTabbedThemes.contains("if (themeVisible) {") &&
        landingDeclaredTabbedThemes.contains("themeQueue.push(job)") &&
        landingDeclaredTabbedThemes.contains("themeDeferredQueue.push(job)") &&
        landingDeclaredTabbedThemes.contains(
          "themeVisible = current === \"all\" || " +
            "themeSection.getAttribute(\"data-section\") === current"
        ) &&
        landingDeclaredTabbedThemes.contains("deferTheme(themeDeferredQueue, themeQueueGen)"),
      "current-tab cards are rendered first, including before hidden state is initialized",
    )
    // Re-pointing runs only when the theme itself changed, so a search keystroke (which also calls
    // apply()) never restarts an in-flight themed-render queue.
    assertTrue(
      landingDeclaredThemes.contains("if (theme === appliedTheme) return;"),
      "the card re-point runs only on an actual theme change",
    )
    // A catalog with no declared themes carries none of the theme-render machinery at all.
    assertFalse(
      landingThemed.contains("themeBase") || landingThemed.contains("runThemeQueue"),
      "a baked-only catalog emits no themed-render script",
    )
    // A catalog with no declared themes keeps exactly the baked Light/Dark axis — no dead chips, no
    // themeProvider plumbing offered where nothing could apply it.
    assertFalse(
      landingThemed.contains("data-theme-choice=\"theme:"),
      "a catalog declaring no themes shows only the baked light/dark chips",
    )
    // …and declared themes are withheld from a session that cannot re-render them (a static bundle
    // replays baked PNGs, which would ignore the theme).
    assertFalse(
      ServeWeb.landingPage(
          "compose-m3",
          themedPreviews,
          token,
          declaredThemes = listOf(ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog")),
        )
        .contains("data-theme-choice=\"theme:"),
      "a static bundle offers no declared-theme chips it could not render",
    )
    // A theme-NEUTRAL module (no baked light/dark pair) whose session declares themes still gets
    // the control — a leading "Default" chip to return to the catalog's own renders, plus the
    // declared themes. Previously such a module showed no theme control at all.
    val neutralWithThemes =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        declaredThemes =
          listOf(ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand")),
        canRenderThemeFor = { true },
      )
    assertTrue(
      neutralWithThemes.contains("data-theme-choice=\"default\"") &&
        neutralWithThemes.contains(
          "data-theme-choice=\"theme:com.example.BrandLightThemeCatalog\""
        ),
      "a theme-neutral module with declared themes gets a Default chip plus the declared themes",
    )
    // The status page leads with the header health badge, links to the machine-readable JSON, and
    // renders the catalog / running-daemon / failure tables. A recent failure ⇒ the amber
    // "degraded" badge; a live+running catalog reads "live · running"; a baked one shows its
    // reason.
    assertTrue(
      serveStatus.contains("Server status") && serveStatus.contains("href=\"/status.json\""),
      "status page headers the status and links its JSON form",
    )
    assertTrue(
      serveStatus.contains("⚠ degraded") &&
        serveStatus.contains("daemon launch timed out after 300s"),
      "a recent failure surfaces the degraded badge and the failure row",
    )
    assertTrue(
      serveStatus.contains("live · running") && serveStatus.contains("baked PNG"),
      "the catalog table distinguishes a running live catalog from a baked one",
    )
    assertTrue(
      serveStatus.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/tree/design-artifacts/compose-m3\""
      ) &&
        serveStatus.contains("2 hours ago") &&
        serveStatus.contains("2 days ago") &&
        serveStatus.contains("compose-ai-tools <code>0.16.54</code>") &&
        serveStatus.contains("design-parity <code>0.1.25</code>"),
      "catalog status links its delivery branch and shows friendly build provenance",
    )
    // The consent page's whole job is the code and the honesty around it.
    assertTrue(
      agentAccess.contains("KX7M-9QD4") && agentAccess.contains("Verification code"),
      "the approval page shows the code the agent printed, labelled",
    )
    assertTrue(
      agentAccess.contains("fix wear-m3-catalog#68 &lt;the focus ring&gt;"),
      "the agent's label is escaped — it is attacker-controlled text on a page a human trusts",
    )
    assertTrue(
      agentAccess.contains("Not offered: playground"),
      "a scope the approver may not pass on is named rather than silently dropped",
    )
    assertTrue(
      agentAccess.contains("method=\"post\"") &&
        agentAccess.contains("name=\"csrf\" value=\"fixed-approve-seal\""),
      "approval is a sealed POST, never a link a prefetcher could follow",
    )
    assertFalse(
      agentAccess.contains("cpat_"),
      "no token is ever rendered on the consent page",
    )
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
    // The default-render viewer renders the component subtree, marking Default active and linking
    // the same-theme RTL sibling, never the dark render.
    val variantNav =
      viewerVariants.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      variantNav.contains("cp-tree-component cp-tree-link\" role=\"treeitem\"") &&
        variantNav.contains("aria-current=\"page\">Button") &&
        variantNav.contains("/p/button-filled__ideal__default__light__direction-rtl") &&
        variantNav.contains(">RTL</a>"),
      "the viewer subtree marks Default active and links the same-theme RTL variant",
    )
    assertFalse(
      variantNav.contains("__dark__direction-rtl"),
      "the subtree stays within the current theme",
    )
    // A sectioned catalog renders a navigation TREE (role=tree) with one row per section, in
    // authored order (Themes → Components → Screens), each carrying its card count; a flat catalog
    // shows none.
    assertTrue(
      landingSections.contains("class=\"cp-tree\"") && landingSections.contains("role=\"tree\""),
      "a sectioned catalog renders the navigation tree",
    )
    // Keyed on the row's OWN id rather than on `data-tab`, which the tree's group rows also carry
    // (they name the section they jump into) — matching those would report each section once per
    // group it holds.
    val tabOrder =
      Regex("id=\"cp-tab-([a-z0-9-]+)\"")
        .findAll(landingSections)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(
      listOf("all", "themes", "components", "screens"),
      tabOrder,
      "All leads, then the section rows in authored catalogOrder rather than id-sorted",
    )
    // Each section is a labelled region keyed by its slug.
    assertTrue(
      landingSections.contains("id=\"cp-panel-themes\" role=\"region\"") &&
        landingSections.contains("id=\"cp-panel-components\" role=\"region\"") &&
        landingSections.contains("id=\"cp-panel-screens\" role=\"region\""),
      "each section renders a labelled region",
    )
    assertTrue(
      landingSections.contains(
        "id=\"cp-tab-themes\" href=\"#cp-panel-themes\" data-tab=\"themes\"" +
          " aria-controls=\"cp-panel-themes\" aria-selected=\"false\""
      ),
      "a section row's anchor targets its own panel",
    )
    // The catalog lands on ALL — every section's panel showing, one scroll through the lot, and a
    // filter that spans the whole catalog because nothing is narrowing it. It counts the catalog,
    // and it controls the grid rather than any one panel, since that is what it shows.
    assertTrue(
      landingSections.contains(
        "<a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-all\" href=\"#cp-grid\"" +
          " data-tab=\"all\" aria-controls=\"cp-grid\" aria-selected=\"true\">" +
          "All<span class=\"cp-tab-count\">"
      ),
      "the tree leads with a selected All row controlling the whole grid",
    )
    val sectionTree = landingSections.substringAfter("id=\"cp-tabs\"").substringBefore("</nav>")
    assertEquals(
      1,
      Regex("aria-selected=\"true\"").findAll(sectionTree).count(),
      "All is the only selected row — a section under it is expanded, not selected",
    )
    // Under All every section is expanded: the tree stands beside a grid showing everything, so it
    // has to be the outline of everything rather than of one panel.
    assertTrue(
      Regex("id=\"cp-tab-themes\"[^>]* aria-selected=\"false\" aria-expanded=\"true\"")
        .containsMatchIn(landingSections) &&
        Regex("id=\"cp-tab-components\"[^>]* aria-selected=\"false\" aria-expanded=\"true\"")
          .containsMatchIn(landingSections),
      "All expands every section rather than leaving the tree closed over a full grid",
    )
    // What All actually does to the grid, the tree and the headings, in the script that owns each:
    // no card is filtered out by section; the per-section <h2>s that `cp-js` hides come back,
    // because the selected row no longer names the one section on screen; and a jump to a group
    // scrolls without narrowing the catalog down to that group's section.
    // The leading conjunct is "is a filter running", and it is matched loosely on purpose: it was
    // `q !== ""` alone until the Dev-mode `uses:` operator, and is `(q !== "" || usesActive())`
    // since — because a query of only `uses:Foo` leaves `q` empty, and a filter has to span every
    // section (see `ServeWeb.searchingExpr`). What this line is about is the rest of the
    // expression, which that change does not touch: while All is the selected row, the section a
    // card sits in must not hide it.
    assertTrue(
      Regex("""var tabOk = .+ \|\| !sec \|\| current === "all"""").containsMatchIn(landingSections),
      "under All a card is in the current tab whatever section holds it",
    )
    assertTrue(
      landingSections.contains("classList.toggle(") &&
        landingSections.contains("\"cp-multi-section\",") &&
        landingSections.contains("showingAll || searching") &&
        assetText("serve.css")
          .contains("html.cp-js.cp-multi-section .cp-section-head { display: block; }"),
      "the section headings come back whenever several sections are on screen at once",
    )
    assertTrue(
      landingSections.contains("function selectOwningTab(row) {") &&
        landingSections.contains("if (current === \"all\") return;"),
      "jumping to a group from All stays in All",
    )
    // …and a reload of the URL that click wrote lands on the same page. The fragment names where
    // to scroll, not which slice of the catalog to show, so neither the landing resolver nor the
    // Back/Forward one may narrow to the section that happens to hold it while All is selected.
    assertEquals(
      2,
      Regex("if \\(current === \"all\"\\) return;").findAll(landingSections).count(),
      "a #cp-group-… fragment scrolls within All rather than undoing it on load",
    )
    assertTrue(
      landingSections.contains("if (popped.row) markGroup(popped.row);"),
      "Back/Forward within All marks the row it lands on instead of switching section",
    )
    // The second level: each named group is a row under its section, pointing at the sub-group
    // divider's anchor — including the same "Device" group name reused across the Components and
    // Screens sections, which stays scoped per section (two distinct anchors, not one).
    assertTrue(
      landingSections.contains("data-group=\"cp-group-themes-foundation\"") &&
        landingSections.contains("data-group=\"cp-group-components-contacts\"") &&
        landingSections.contains("data-group=\"cp-group-screens-scanner\""),
      "each named group is a tree row pointing at its sub-group anchor",
    )
    assertTrue(
      landingSections.contains("<div class=\"cp-subgroup\" id=\"cp-group-components-device\"") &&
        landingSections.contains("<div class=\"cp-subgroup\" id=\"cp-group-screens-device\""),
      "a group name reused across sections gets one anchor per section, not a shared one",
    )
    // The `group` still renders as a sub-heading inside its section — the tree navigates to those
    // headings, it does not replace them.
    assertTrue(
      landingSections.contains("<h3 class=\"cp-group-head\">Foundation</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Contacts</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Scanner</h3>"),
      "component groups render as sub-headings within their section",
    )
    assertEquals(
      2,
      Regex("<h3 class=\"cp-group-head\">Device</h3>").findAll(landingSections).count(),
      "a group name reused across sections stays scoped per section (one sub-heading each)",
    )
    // The tree JS is wired (adds cp-js, drives the sections, wires the group rows and the
    // scroll-spy); a flat catalog's script omits all of it.
    assertTrue(
      landingSections.contains("classList.add(\"cp-js\")") &&
        landingSections.contains("querySelectorAll(\".cp-tab\")") &&
        landingSections.contains("querySelectorAll(\".cp-tree-group\")") &&
        landingSections.contains("new IntersectionObserver"),
      "the sectioned landing wires the tree script",
    )
    assertTrue(
      landingSections.contains("localStorage.getItem(\"cp-tab:meshcore-mobile\")") &&
        landingSections.contains("localStorage.setItem(\"cp-tab:meshcore-mobile\", current)"),
      "the selected section persists per catalog and is restored when returning from a preview",
    )
    // Three things the tree has to get right around the sticky toolbar and shared links, each of
    // which fails silently — the surface still looks correct while being unusable.
    assertTrue(
      landingSections.contains("setProperty(\"--cp-sticky-tools\"") &&
        assetText("serve.css").contains("scroll-margin-top: calc(var(--cp-sticky-tools, 64px)"),
      "the toolbar's measured height offsets the sticky tree and every scroll target",
    )
    assertTrue(
      landingSections.contains("if (!stop && firstShown) firstShown.tabIndex = 0;"),
      "a filter that hides the selected section moves the tree's tab stop to a visible branch",
    )
    assertTrue(
      landingSections.contains("function hashTarget()") &&
        landingSections.contains("decodeURIComponent(id)") &&
        landingSections.contains("initialTab = current;"),
      "a shared #cp-group-… link selects the section that holds it, non-ASCII slugs included",
    )
    // Back must resolve an entry the way loading it fresh would, so the same resolver runs on pop —
    // and it has to be registered AFTER the shared `?tab=` restore to get the last word.
    assertTrue(
      landingSections.contains("var popped = hashTarget();") &&
        landingSections.indexOf("var popped = hashTarget();") >
          landingSections.indexOf("var poppedTab = urlParam(\"tab\")"),
      "Back/Forward re-applies the fragment's precedence over ?tab=",
    )
    // No roving `tabindex` in the served markup: with no JS the arrow keys never bind, so baking
    // `-1` into every row but the first would strand the rest of the navigation for a keyboard.
    assertFalse(
      Regex("<a class=\"cp-tab\"[^>]*tabindex").containsMatchIn(landingSections) ||
        Regex("<a class=\"cp-tree-group\"[^>]*tabindex").containsMatchIn(landingSections),
      "the tree's tab stops are applied by script, not baked into the markup",
    )
    assertTrue(
      landingSections.contains("panel.scrollIntoView({ block: \"start\" })"),
      "selecting a section scrolls to its panel when the toolbar would hide it",
    )
    assertTrue(
      landingSections.contains("if (expanded !== \"true\") return;"),
      "Right does nothing on a tree leaf rather than acting as a second Down",
    )
    // The fragment has to travel with the selection: `cpUrlState` preserves whatever hash is
    // already on the URL, and the hash outranks `?tab=` on load, so a stale one silently sends the
    // next visitor to the wrong section.
    assertTrue(
      landingSections.contains("function setFragment(id)") &&
        landingSections.contains("setFragment(id);") &&
        landingSections.contains("setFragment(\"\");"),
      "navigating replaces the fragment, and choosing a section clears it",
    )
    // A `role="group"` has to hang off the treeitem whose `aria-expanded` governs it. The row is an
    // <a> (so it stays a real link) inside a `role="none"` <li>, so the tie is `aria-owns`.
    assertTrue(
      landingSections.contains("aria-owns=\"cp-tree-children-components\"") &&
        landingSections.contains(
          "<ul class=\"cp-tree-children\" id=\"cp-tree-children-components\""
        ),
      "a section row owns its group of sub-group rows",
    )
    // `role="tree"` (the nav) and `classList.add("cp-js")` (the section script) appear ONLY when
    // sections are rendered — the shared stylesheet's `.cp-tree` / `html.cp-js` rules are on every
    // page, so this checks the markup/script, not the CSS.
    assertFalse(
      landingThemed.contains("role=\"tree\"") || landingThemed.contains("classList.add(\"cp-js\")"),
      "a flat (section-less) catalog renders no navigation tree and no section script",
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
    // The default-state viewer renders the component subtree, marking Default active and linking
    // the same-theme unchecked sibling.
    val statesNav =
      viewerStates.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      statesNav.contains("aria-current=\"page\">Checkbox") &&
        statesNav.contains("/p/checkbox__ideal__unchecked__light"),
      "the viewer subtree marks Default active and links the same-theme sibling",
    )
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
    // A section-less catalog now gets an OUTLINE tree over the same flat grid: its synthesized
    // families are the top level, so the two levels of structure it does have (family, then
    // component) are navigable instead of invisible. No sections means no panels to switch, so
    // these rows carry no `data-tab` and the script emits none of the section machinery.
    assertTrue(
      landingGrouped.contains("role=\"tree\"") &&
        landingGrouped.contains("aria-label=\"Catalog contents\"") &&
        landingGrouped.contains("<div class=\"cp-subgroup\" id=\"cp-group-button\""),
      "a section-less catalog renders an outline tree over its synthesized families",
    )
    // Every sub-group carries its card count as `--cp-n`, which is what lets the sheet lay them
    // out as CLUSTERS — a one-card family asking for one column instead of a whole five-column
    // row with four of them painted blank (issue #4423). The count comes from the server because
    // CSS has no way to ask how many cards a group holds; get it wrong and the layout silently
    // reserves the wrong width, so it is pinned here rather than left to the pixel diff.
    assertTrue(
      landingGrouped.contains("id=\"cp-group-button\" style=\"--cp-n:3\"") &&
        landingGrouped.contains("id=\"cp-group-card\" style=\"--cp-n:2\"") &&
        landingGrouped.contains("id=\"cp-group-fab\" style=\"--cp-n:1\"") &&
        landingGrouped.contains("id=\"cp-group-badge\" style=\"--cp-n:1\""),
      "each sub-group declares how many cards wide it is",
    )
    // The id line is two spans so it can elide from the MIDDLE: clipped at the end, an id says
    // nothing the label above it hasn't, because what distinguishes one render from its siblings
    // is the suffix. Split at the last `__`, head shrinks, tail stays.
    assertTrue(
      landingGrouped.contains(
        "<div class=\"cp-id cp-id-elide\">" +
          "<span class=\"cp-id-head\">button-filled__ideal__default</span>" +
          "<span class=\"cp-id-tail\">__light</span></div>"
      ),
      "a card's id elides from the middle, keeping the mode and the scheme",
    )
    // The design file's pages are their own PANE beside Components, not a branch at the foot of the
    // tree and not a chip in the header row. The branch put them below every family, component and
    // variant the catalog has — past a hundred-odd rows on a real one — while answering a different
    // question from the inventory above them. A segmented switch says which of the two peers the
    // column is showing.
    assertTrue(
      landingGrouped.contains("<div class=\"cp-panes\" role=\"tablist\"") &&
        landingGrouped.contains("data-pane=\"components\" aria-controls=\"cp-pane-components\"") &&
        landingGrouped.contains("data-pane=\"pages\" aria-controls=\"cp-pane-pages\""),
      "a catalog with design pages switches its sidebar between Components and Pages",
    )
    // Each page is one row, named, carrying `data-search` so the one filter below the switch can
    // narrow them the way it narrows components — the pages used to be the only list in this
    // column the filter could not reach.
    // A page WITH sections is a branch — the row still leads to the whole sheet, and the sections
    // it is divided into hang under it, each landing on that node's anchor in the page view.
    assertTrue(
      landingGrouped.contains(
        "<a class=\"cp-tree-page cp-tree-link\" href=\"/pages/shape\" data-search=\"Shape\"" +
          " aria-expanded=\"true\" aria-controls=\"cp-page-sections-shape\">Shape"
      ) &&
        landingGrouped.contains(
          "<a class=\"cp-tree-variant cp-tree-link\" href=\"/pages/shape\"" +
            " data-search=\"Corner radius\">Corner radius</a>"
        ) &&
        landingGrouped.contains("data-search=\"Shape scale\">Shape scale</a>"),
      "a page's major sections hang under it, each linking to that node's anchor",
    )
    // …and a page with NO sections stays a leaf: named, filterable, and carrying neither a twisty
    // nor an empty child list. Both shapes in one golden, so neither can regress unnoticed.
    assertTrue(
      landingGrouped.contains(
        "<a class=\"cp-tree-page cp-tree-link\" href=\"/pages/type\"" +
          " data-search=\"Typography\">Typography</a>"
      ) && landingGrouped.contains("<a class=\"cp-pane-all\" href=\"/pages\">All pages</a>"),
      "a page with no sections stays a plain filterable row, and the index link survives",
    )
    // THE JOIN, asserted rather than assumed: every fragment a section row links to must exist as
    // an id on the page view it points at. These are two different goldens built by two different
    // functions, and the first version of this shipped links to `#cp-node-<setId>` — anchors that
    // the page never emits, because it draws one per COMPONENT and a component set is not one. The
    // links resolved to nothing and no test noticed, since each golden was self-consistent.
    val sectionFragments =
      Regex("""href="[^"]*#(cp-node-[^"]*)"""").findAll(landingGrouped).map { it.groupValues[1] }
    val pageAnchors =
      Regex("""id="(cp-node-[^"]*)"""").findAll(designPageHtml).map { it.groupValues[1] }
    val pageAnchorSet = pageAnchors.toSet()
    val dangling = sectionFragments.filterNot { it in pageAnchorSet }.toList()
    assertTrue(
      pageAnchorSet.isNotEmpty() && dangling.isEmpty(),
      "every section link lands on an anchor the page view actually emits; dangling: $dangling",
    )
    // Today that holds trivially, because a section links to the sheet and carries no fragment at
    // all — a set has nothing on the page to land on, and inferring one from depth is what the
    // `PageNode.container` contract forbids. Stated out loud so the guard above is not mistaken
    // for proof that targeting works: it proves only that nothing dangles, which is the property
    // that has to survive when the deep-link work adds real container anchors.
    assertTrue(
      sectionFragments.none(),
      "a section link carries no fragment until the page view anchors containers",
    )
    // The pane the switch reveals ships hidden, and the tree keeps the column when it is showing.
    assertTrue(
      landingGrouped.contains(
        "<div class=\"cp-pane cp-pane-pages\" id=\"cp-pane-pages\" role=\"tabpanel\""
      ) && landingGrouped.contains("aria-labelledby=\"cp-pane-tab-pages\" hidden>"),
      "the Pages pane starts hidden behind its tab",
    )
    // …and the branch it replaced is gone, so nothing lists the pages twice.
    assertFalse(
      landingGrouped.contains("cp-tree-pages-row"),
      "the pages branch does not survive alongside the pane that replaced it",
    )
    assertTrue(
      !landingGrouped.contains("class=\"cp-action-chip\" href=\"/pages\""),
      "a catalog with a tree offers its pages there, not as a header chip as well",
    )
    // …and the fallback: no tree to list them in (too few previews to synthesize families from)
    // means the chip is the only route, so it stays.
    assertTrue(
      !landingPublic.contains("cp-tree-pages") &&
        landingPublic.contains("class=\"cp-action-chip\" href=\"/pages\">2 pages</a>"),
      "a catalog with no tree keeps the header chip, or its pages would be unreachable",
    )
    // `reflectTree` walks every expandable row on every open/close; the Pages branch is expandable
    // and names no target, so without this guard it would be collapsed by the first component
    // click. Asserted on the emitted script because that is the only place it exists.
    assertTrue(
      landingGrouped.contains("if (!id) return;"),
      "the tree script leaves an always-open branch alone",
    )
    // The tree's two deepest levels. A component row jumps to its card (an in-page `data-group`);
    // a variant row has nowhere on the page to go — the grid folds those renders out — so it is a
    // plain link to the viewer, and carries no `data-group` at all.
    assertTrue(
      landingTreeDepth.contains(
        "<a class=\"cp-tree-component cp-tree-link\" role=\"treeitem\"" +
          " href=\"#cp-card-button-filled__ideal__default__light\""
      ),
      "each component is a row pointing at its own grid card",
    )
    val variantRows =
      Regex("<a class=\"cp-tree-variant cp-tree-link\"[^>]*>([^<]+)</a>")
        .findAll(landingTreeDepth)
        .map { it.groupValues[1] }
        .toList()
    assertTrue(
      variantRows.containsAll(listOf("Default", "RTL", "Locale ar-XB", "Font 2.0×", "Unchecked")),
      "primary-axis variants (props and state) each become a row: $variantRows",
    )
    assertTrue(
      landingTreeDepth.contains(
        "href=\"/p/button-filled__ideal__default__light__direction-rtl?session=compose-m3\""
      ),
      "a variant row links to the viewer rather than jumping within the page",
    )
    // Theme is SECONDARY — the card swaps it in place — so it never becomes a row, and neither the
    // dark twin of a component nor a `__dark` variant appears in the tree.
    assertFalse(
      Regex("<a class=\"cp-tree-(component|variant)[^>]*(Dark|__dark)")
        .containsMatchIn(landingTreeDepth),
      "theme stays a secondary axis and earns no tree row",
    )
    // A filter that hides a card must hide the row pointing at it, at EVERY level and in both tree
    // modes — otherwise a search leaves rows that scroll to nothing.
    assertTrue(
      landingTreeDepth.contains("treeComponents.forEach(function (c) {") &&
        landingGrouped.contains("treeComponents.forEach(function (c) {") &&
        landingGrouped.contains("treeGroups.forEach(function (g) {"),
      "the search filter follows the tree down to its component rows, outline trees included",
    )
    // A `role="tree"` is one tab stop. Nothing established that until the first arrow press, so
    // every visible row sat in the tab order until then.
    assertTrue(
      landingTreeDepth.contains("function syncTabStops()") &&
        landingTreeDepth.contains("cpTreeStops = syncTabStops;") &&
        landingTreeDepth.contains("if (cpTreeStops) cpTreeStops();"),
      "the tree keeps a single roving tab stop, re-synced whenever the filter moves rows",
    )
    // A `#cp-card-…` fragment can name a component in any group, not just the one the server
    // expanded — its own row has to open along with it.
    assertTrue(
      landingTreeDepth.contains("var owner = parentRow(row);"),
      "landing on a component's fragment opens the group that holds it",
    )
    // Cards are jump targets now, so they need the clearance sections and sub-groups already have.
    assertTrue(
      assetText("serve.css").contains(".cp-card { scroll-margin-top:"),
      "a card cleared the sticky toolbar when a component row jumps to it",
    )
    assertFalse(
      landingGrouped.contains("data-tab=") ||
        landingGrouped.contains("localStorage.getItem(\"cp-tab:"),
      "an outline tree switches no panels, so it emits no section machinery",
    )
    // The component nav collapses to ONE entry per component: button-filled's ~8 baked variants +
    // checkbox/radiobutton states yield exactly three nav items, button-filled listed once.
    val collapsedNav =
      viewerNavCollapsed.substringAfter("<aside class=\"cp-nav\"").substringBefore("</aside>")
    assertEquals(
      3,
      Regex("class=\"cp-nav-item\"|class=\"cp-tree-component cp-tree-link\"")
        .findAll(collapsedNav)
        .count(),
      "the component nav lists one entry per component, not per baked variant",
    )
    assertEquals(
      1,
      Regex(">Button · Filled[^<]*<").findAll(collapsedNav).count(),
      "the multi-variant component appears exactly once in the nav",
    )
    // Theme preservation: viewing a DARK preview, the collapsed nav links each OTHER component to
    // its DARK render (not the light default) — the same theme-preserving behaviour the state and
    // variant switchers already have, so navigating never snaps the visitor back to light.
    val darkNav =
      ServeWeb.viewerPage(
          statefulPreviews.first { it.id == "checkbox__ideal__default__dark" },
          token,
          sessionId = "compose-m3",
          siblings = statefulPreviews,
        )
        .substringAfter("id=\"cp-nav-list\"")
        .substringBefore("</ul>")
    assertTrue(
      darkNav.contains("/p/radiobutton__ideal__default__dark") &&
        !darkNav.contains("/p/radiobutton__ideal__default__light"),
      "the collapsed nav preserves the viewer's theme (a dark preview links dark siblings)",
    )
    // The editor page carries its three DOM hooks the run script + the Playwright e2e drive: the
    // source box, the mode selector with all three modes, and the Run button.
    assertTrue(
      playground.contains("id=\"pg-source\"") &&
        playground.contains("id=\"pg-run\"") &&
        playground.contains("value=\"compose-cmp\"") &&
        playground.contains("value=\"compose-android\"") &&
        playground.contains("value=\"remote-compose\""),
      "the playground page exposes the source box, Run button, and all three mode options",
    )
    // The run script targets the versioned compile route and follows either handoff field.
    assertTrue(
      playground.contains("/api/1/compiler/run") &&
        playground.contains("res.documentUrl || res.previewUrl"),
      "the playground script POSTs to the compile route and follows the /pg or /d handoff",
    )
    // A snippet is a list of files, not one buffer (#3017): the file strip is present, the run body
    // posts the whole list, and the response's previewId is surfaced so a snippet with several
    // @Previews says which one it drew.
    assertTrue(
      playground.contains("id=\"pg-files\"") &&
        playground.contains("id=\"pg-add-file\"") &&
        playground.contains("id=\"pg-remove-file\""),
      "the playground page exposes the multi-file strip",
    )
    assertTrue(
      playground.contains("files: files") && !playground.contains("files: [{ name: \"Snippet.kt\""),
      "the run body posts every open file, not just the active buffer",
    )
    assertTrue(
      playground.contains("res.previewId") &&
        playground.contains("res.previews") &&
        playground.contains("id=\"pg-preview-note\""),
      "the editor names the preview it rendered when a snippet declares several",
    )
    // A diagnostic names its file (and jumps to that tab): with several buffers open, a bare
    // "line 5" says nothing about which file to look at.
    assertTrue(
      playground.contains("d.file") && playground.contains("indexOfFile"),
      "diagnostics name their file and can jump to it",
    )
    assertTrue(
      playground.contains("editor.addLineWidget") &&
        playground.contains("editor.addLineClass") &&
        playground.contains("removeLineClass(entry.lineHandle") &&
        playground.contains("editor.markText") &&
        assetText("playground.css").contains(".cp-pg-inline-error"),
      "located compiler errors are shown inline and cleared through CodeMirror's moving line handle",
    )
    assertTrue(
      playground
        .substringAfter("removeFile.addEventListener")
        .substringBefore("renderFiles();\n      function setStatus")
        .contains("renderEditorDiags();"),
      "removing the active file repaints diagnostics for the buffer that replaces it",
    )
    // The terminal status stays exactly "Done." — the e2e polls on it, so the preview note lives
    // in its own element rather than being appended to the status text.
    assertTrue(
      playground.contains("setStatus(\"Done.\", false)"),
      "a successful run still ends on the terminal status the e2e keys on",
    )
    // The upload page names every format it accepts and states the expiry up front, so a visitor
    // knows what to drop and how long the resulting link lives before they share it.
    for (format in ServeDocFormats.ALL) {
      assertTrue(
        docUpload.contains(format.label) && docUpload.contains(format.extension),
        "the upload page lists ${format.id}",
      )
    }
    assertTrue(docUpload.contains("expires after 1h"), "the upload page states the link TTL")
    // A permalink page mounts its format's vendored player against the document's own bytes, and
    // never a different format's.
    assertTrue(
      docLottie.contains(ServeDocFormats.LOTTIE.playerPath) &&
        !docLottie.contains(ServeDocFormats.REMOTE_COMPOSE.playerPath),
      "the Lottie page loads only the Lottie player",
    )
    assertTrue(
      docRemoteCompose.contains(ServeDocFormats.REMOTE_COMPOSE.playerPath) &&
        docRemoteCompose.contains("width=\"384\" height=\"384\""),
      "the Remote Compose page loads the RC player onto a canvas sized from the document",
    )
    assertTrue(
      docLottie.contains("expires in 1h") && docLottie.contains("2026-07-28T22:15:00Z"),
      "the permalink page shows the time left and the exact expiry instant",
    )
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
      assertTrue(html.contains("<h1 class=\"cp-head"), "the $name page leads with an <h1>")
    }
    assertTrue(
      File(pagesDir, "_render-placeholder.png").isFile,
      "missing _render-placeholder.png — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )

    // The home index lists every published system as a card linking to its /<system>/ catalog —
    // including remote-m3 — each carrying a hero preview img.
    assertTrue(homeIndex.contains("Design Systems"), "home index is headed 'Design Systems'")
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
    // The normal card points at the PREBAKED hero: an immutable, content-hashed URL, loaded eagerly
    // with its box reserved up front — so the front door paints without touching the render lane.
    assertTrue(
      homeIndex.contains(
        "<img loading=\"eager\" decoding=\"async\" width=\"168\" height=\"68\"" +
          " alt=\"Compose Material 3 preview\" src=\"/hero/compose-m3/1f0c9a4b7d2e6503.png\">"
      ),
      "a system card shows its prebaked hero, sized and eager",
    )
    assertFalse(
      homeIndex.contains("src=\"/compose-m3/render/"),
      "a prebaked card puts no render request on the server",
    )
    // A catalog whose hero couldn't be prebaked still shows one — over the live /render lane.
    assertTrue(
      homeIndex.contains("src=\"/remote-m3/render/Button-Filled__ideal__default__light.png\""),
      "a card with no prebaked hero falls back to its /render endpoint",
    )
    assertTrue(
      !homeIndex.contains("cp-badge--trusted") && !homeIndex.contains("⚠ untrusted"),
      "the discovery index omits badges for trusted catalog cards",
    )
    val untrustedHomeIndex =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "unverified",
              title = "Unverified catalog",
              subtitle = null,
              previewCount = 1,
              trust = "unverified",
              heroPreviewId = null,
            )
          ),
        token = token,
        isPublic = true,
      )
    assertTrue(
      untrustedHomeIndex.contains("cp-badge--unverified") &&
        untrustedHomeIndex.contains("⚠ untrusted"),
      "the discovery index calls out a genuinely unverified catalog",
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
    assertTrue(
      homeIndex.contains("<h1 class=\"cp-head\">yschimke repositories</h1>"),
      "catalogs published by yschimke have their own section",
    )
    // A catalog that claims no configured group is sectioned by its source repo's OWNER — nothing
    // in the server knows `confetti-wear`, so Confetti gets a publisher-repository section.
    assertTrue(
      homeIndex.contains("<h1 class=\"cp-head\">joreilly repositories</h1>"),
      "an unconfigured publisher still gets its own section, derived from the source repo",
    )
    assertTrue(
      homeIndex.indexOf("href=\"/remote-m3/\"") <
        homeIndex.indexOf("<h1 class=\"cp-head\">yschimke repositories</h1>") &&
        homeIndex.indexOf("href=\"/homeassistant-remotecompose/\"") <
          homeIndex.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") &&
        homeIndex.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") <
          homeIndex.indexOf("href=\"/confetti-wear/\""),
      "cards are split between the design system, yschimke, and joreilly sections",
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
      viewerSource().contains("if (token) parts.push(\"token=\""),
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
    assertNull(
      ServeWeb.SystemDisplay.normalizeOverrideParams("confetti-wear", mapOf("uiMode" to "light"))[
          "uiMode"],
      "Wear ignores the generic light override",
    )
    assertEquals(
      "light",
      ServeWeb.SystemDisplay.normalizeOverrideParams("compose-m3", mapOf("uiMode" to "light"))[
          "uiMode"],
      "non-Wear systems retain day/night overrides",
    )
    assertTrue(
      landingThemed.contains("localStorage.getItem(\"cp-theme:compose-m3\")") &&
        landingThemed.contains("localStorage.setItem(\"cp-theme:compose-m3\", theme)"),
      "the catalog landing persists theme under its own catalog key",
    )
    assertTrue(
      viewerGestures.contains("localStorage.getItem(\"cp-theme:wear-m3\")") &&
        !viewerGestures.contains("cp-theme:compose-m3"),
      "a viewer reads only its own catalog's sticky theme",
    )
    assertEquals(
      mapOf("fontScale" to "1.5"),
      ServeWeb.SystemDisplay.normalizeOverrideParams(
        "confetti-wear",
        mapOf("uiMode" to "light", "fontScale" to "1.5"),
      ),
      "normalizing drops only uiMode — every other override survives",
    )

    // The sticky theme toggle appears only for a catalog with light/dark pairs, and each paired
    // component collapses into ONE swap card carrying both themes' baked render; a plain component
    // module shows no toggle.
    assertTrue(
      landingThemed.contains("id=\"cp-catalog-theme-bar\""),
      "themed catalog shows the theme toggle",
    )
    assertTrue(
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").containsMatchIn(landingThemed) &&
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
    // The swap re-points the image + viewer link + id + label to the chosen theme's baked render.
    assertTrue(
      landingThemed.contains(
        "if (img) { if (withSrc) setCardSrc(img, src); img.setAttribute(\"alt\", lbl); }"
      ) &&
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
      viewerGestures.contains("class=\"cp-viewer\" data-bg-theme=\"dark\""),
      "a Wear (dark-first) viewer tags the stage dark even without a __dark token",
    )
    assertFalse(
      viewerFocus.contains("class=\"cp-viewer\" data-bg-theme="),
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
      landing.contains("id=\"cp-catalog-theme-bar\""),
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
      "the search box shows on a themed catalog",
    )
    assertTrue(
      landingThemed
        .substringAfter("class=\"cp-catalog-menu\"")
        .substringBefore("</aside>")
        .contains("id=\"cp-search\""),
      "a catalog menu leads with its filter",
    )
    assertFalse(
      landingThemed.contains("id=\"cp-count\""),
      "the menu filter does not repeat the preview count",
    )
    // The combined filter composes search with theme: on a themed catalog the script still persists
    // the theme choice, so search didn't displace the theme half.
    assertTrue(
      landingThemed.contains("localStorage.setItem(\"cp-theme:compose-m3\"") &&
        landingThemed.contains("getElementById(\"cp-search\")"),
      "the themed landing's filter script drives both the theme toggle and the search box",
    )
    // The viewer seeds the unified Theme select from the catalog-scoped key and writes every
    // choice back. Explicit baked light/dark ids remain reproducible and ignore remembered themes.
    assertTrue(
      viewer.contains("localStorage.getItem(\"cp-theme:default\""),
      "viewer seeds its Theme select from the catalog-scoped theme key on load",
    )
    assertTrue(
      viewer.contains("localStorage.setItem(\"cp-theme:default\""),
      "viewer Theme change writes the catalog-scoped theme key",
    )
    assertTrue(
      viewerThemes.contains("stored.indexOf(\"theme:\") === 0") &&
        viewerThemes.contains("!urlOption && !themed && option") &&
        viewerThemes.contains("el.setAttribute(\"data-theme-active\", \"1\")"),
      "a remembered catalog theme is restored only when the preview path has no baked theme",
    )
    // The exclusivity rule itself moved to `cli/serve-web/src/viewer/themeChoice.ts`, where
    // `viewerThemeChoice.test.ts` drives it over every value instead of grepping for one spelling
    // of it. What is still worth holding HERE is the seam: `viewer.js` must ask the shared rules
    // rather than grow a second copy, because a second copy is how the select's values and their
    // consumption drift apart while both look right.
    assertTrue(
      viewerSource().contains("rules.chosenUiMode(") &&
        viewerSource().contains("rules.chosenThemeProvider("),
      "the unified Theme value is mapped by the shared rules, not restated in the viewer",
    )

    // The backend-provenance badge names the active tier. The Wasm tier is always CMP-WASM; the
    // live + snapshot labels come from server metadata (a live daemon can be Android, not just
    // JVM),
    // defaulting to generic Live / Snapshot.
    // The badge is `<cp-backend-badge>` (`cli/serve-web/src/components/BackendBadge.ts`), so what
    // the page owes it is the tag carrying the live region and the lane labels. Which icon and
    // label each mode produces — ▶ live / ▪ static, and the hard-coded CMP-WASM tier — is asserted
    // against the real element in `cli/serve-web/test/backendBadge.test.ts`, which a substring
    // match on a minified bundle could not do.
    assertTrue(
      viewer.contains("<cp-backend-badge ") && viewer.contains("id=\"cp-backend\""),
      "viewer stage carries the backend badge",
    )
    assertTrue(
      viewer.contains("role=\"status\"") && viewer.contains("aria-live=\"polite\""),
      "the badge is a server-rendered live region, so lane changes are announced",
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
  fun `a published capture is offered as a chip and never plays until it is asked for`() {
    val plain = previews.first { it.id.endsWith("CardPreview") }
    val withMotion =
      plain.copy(
        motion =
          listOf(
            ServeMotion(id = "card__filled__interaction", kind = "interaction", caption = "Press"),
            ServeMotion(
              id = "card__filled__anim",
              kind = "animation",
              caption = "Elevation settle",
              extension = ".gif",
            ),
          )
      )
    // Path-mounted, which is how a published catalog is actually served — so the assertions below
    // cover the session scoping of the route as well as its shape.
    val view =
      ServeWeb.viewerPage(withMotion, token, sessionId = "compose-m3", basePath = "/compose-m3")

    // The whole point of the axis: a still is what most readers came for, so motion is a control
    // beside it rather than the frame on the stage.
    assertTrue(view.contains("id=\"cp-motion-chip\""), "a preview with captures offers the chip")
    assertTrue(
      Regex("id=\"cp-motion-chip\"[^>]*aria-pressed=\"false\"").containsMatchIn(view),
      "the chip opens un-pressed — the page loads on the still",
    )
    assertTrue(
      Regex("<img id=\"cp-motion-img\"[^>]*hidden").containsMatchIn(view) &&
        !Regex("<img id=\"cp-motion-img\"[^>]*src=").containsMatchIn(view),
      "the capture is neither shown nor SRC-ed at page load — assigning src is what starts playback",
    )

    // The player: a canvas the decoded frames are painted on, and the transport that drives them.
    // Both start hidden — the bytes are still fetched on first entry and never at page load.
    assertTrue(
      Regex("<div class=\"cp-motion-player\" id=\"cp-motion-player\" hidden>")
        .containsMatchIn(view),
      "the player is rendered but withheld until the lane is entered",
    )
    assertTrue(view.contains("id=\"cp-motion-canvas\""), "the frames have a canvas to land on")
    // Hidden *separately* from the player, and that is load-bearing: the transport is revealed only
    // once a decode has actually succeeded, so a browser that falls back to the looping <img> is
    // never shown scrub and speed controls that cannot work.
    assertTrue(
      Regex("<div class=\"cp-motion-transport\" id=\"cp-motion-transport\" hidden>")
        .containsMatchIn(view),
      "the transport waits for a decode rather than promising control the page may not have",
    )
    assertTrue(
      view.contains("id=\"cp-motion-play\"") && view.contains("id=\"cp-motion-replay\""),
      "play/pause and play-again are both offered — a capture plays once, so replay is the way back",
    )
    assertTrue(
      Regex("<input type=\"range\" id=\"cp-motion-scrub\"[^>]*step=\"1\"").containsMatchIn(view),
      "the timeline is a real range input, so frame stepping and Home/End come from the platform",
    )
    assertTrue(
      view.contains("id=\"cp-motion-rate\"") && view.contains(">0.25×</option>"),
      "playback speed goes down to a quarter, which is where a 300ms spring becomes readable",
    )
    assertTrue(
      Regex("<option value=\"1\" selected>1×</option>").containsMatchIn(view),
      "…and opens at the rate the capture was recorded at",
    )

    // Each capture keeps its own extension end to end. An APNG served as a GIF renders one frame
    // and stops, so a picker that assumed one format would silently turn a recording into a still.
    assertTrue(
      view.contains("data-motion-src=\"/compose-m3/motion/card__filled__interaction.apng"),
      "the interaction capture is addressed as an APNG",
    )
    assertTrue(
      view.contains("data-motion-src=\"/compose-m3/motion/card__filled__anim.gif"),
      "the GIF capture keeps its own type rather than inheriting the first one's",
    )
    // The annotation's caption is what names the recording — without it a capture tells the reader
    // only that *something* moved.
    assertTrue(view.contains(">Press</option>"), "the declared caption labels its capture")
    assertTrue(view.contains(">Elevation settle</option>"), "…and so does the second one")
    // A caption short enough to BE a title carries no detail attribute: printing it in the menu and
    // again in the readout beside it is two controls stating one fact.
    assertFalse(
      view.contains("data-motion-detail=\"Press\""),
      "a caption the menu already shows in full is not repeated in the readout",
    )

    // A lane like every other: its own hidden mode radio, which is what buys `?mode=motion`,
    // restore-on-load and Back/Forward without a second mechanism.
    assertTrue(
      view.contains("name=\"cp-mode\" value=\"motion\" id=\"cp-motion-toggle\""),
      "motion joins the mode radio group rather than inventing its own state",
    )

    // …and a preview with nothing published is presented exactly as it was before.
    val without =
      ServeWeb.viewerPage(plain, token, sessionId = "compose-m3", basePath = "/compose-m3")
    assertFalse(without.contains("cp-motion-chip"), "no captures ⇒ no chip")
    assertFalse(without.contains("cp-motion-img"), "no captures ⇒ no stage image")
    assertFalse(without.contains("value=\"motion\""), "no captures ⇒ no mode radio")

    // Leaving the lane must DROP the src, not merely hide the image: a hidden capture with its src
    // still assigned keeps looping for the rest of the visit — invisible, and still decoding.
    assertTrue(
      viewerSource().contains("motionImg.removeAttribute(\"src\");"),
      "closing the lane stops the animation instead of hiding a still-playing one",
    )
    // Every other lane closes it, which is what stops a capture playing on behind the lane the
    // visitor actually moved to.
    assertTrue(
      viewerSource().contains("if (m !== \"motion\") closeMotion();"),
      "every transition out of motion closes the lane",
    )
    // The captions catalogs actually write: a line of instruction, then a paragraph of what to
    // watch for. On a button that was the whole paragraph across the control row; in the menu it is
    // the opening clause, and the rest rides to the readout on the option.
    val prose =
      ServeWeb.viewerPage(
        plain.copy(
          motion =
            listOf(
              ServeMotion(
                id = "card__filled__interaction",
                kind = "interaction",
                caption =
                  "Toggle repeatedly. The container morphs between its unchecked and checked " +
                    "shapes through the theme's spatial animation.",
              )
            )
        ),
        token,
        sessionId = "compose-m3",
      )
    assertTrue(
      prose.contains(">Toggle repeatedly</option>"),
      "the menu shows the caption's opening clause, not the paragraph behind it",
    )
    assertTrue(
      prose.contains(
        "data-motion-detail=\"Toggle repeatedly. The container morphs between its unchecked " +
          "and checked shapes through the theme&#39;s spatial animation.\""
      ),
      "…and the words themselves are kept, on the option the readout prints from",
    )

    // Two caption-less captures of the SAME kind — permitted by the manifest, and what the
    // annotation defaults produce. Both entries used to read "Interaction", leaving no way by eye
    // or by screen reader to tell which recording either one selects.
    val sameKind =
      ServeWeb.viewerPage(
        plain.copy(
          motion =
            listOf(
              ServeMotion(id = "card__a", kind = "interaction"),
              ServeMotion(id = "card__b", kind = "interaction"),
            )
        ),
        token,
        sessionId = "compose-m3",
      )
    assertTrue(
      sameKind.contains(">Interaction 1</option>") && sameKind.contains(">Interaction 2</option>"),
      "a repeated fallback label is numbered so the two entries can be told apart",
    )
    // …and a lone capture is NOT numbered: "Interaction 1" on a preview with one recording is a
    // count of something nobody was choosing between.
    assertTrue(
      view.contains(">Press</option>") && !view.contains(">Press 1</option>"),
      "a label that stands alone keeps its plain form",
    )

    // A pinned page is a permalink, and the rule it holds to is that a pinned request is never
    // answered with CURRENT bytes. `/motion/` reads the branch tip the session is holding and has
    // no revision to resolve against, so the axis is withdrawn there rather than playing today's
    // recording beside a render from another commit. Withdrawn whole — chip, stage image and mode
    // radio — so there is no half-present control to click.
    val pinnedView =
      ServeWeb.viewerPage(
        withMotion,
        token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        revisions = ServeWeb.CatalogRevisions(pinned = "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80"),
      )
    assertFalse(pinnedView.contains("cp-motion-chip"), "a pinned page offers no capture")
    assertFalse(pinnedView.contains("cp-motion-img"), "…and stages none")
    assertFalse(
      pinnedView.contains("value=\"motion\""),
      "…and carries no mode radio a URL could still name",
    )

    // The readout prints the caption in full for whichever capture is on the stage, and falls back
    // to standing IN FOR the menu on a single-capture preview — where the menu is hidden and
    // nothing else on the row names the recording.
    assertTrue(
      viewerSourceFlat()
        .contains("detail || (motionOptions.length > 1 || !option ? \"\" : option.text)"),
      "the readout shows the detail, or the title when no visible menu carries it",
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
      viewerSource().contains("\"cp-wasm-ready\""),
      "viewer listens for the app's first-frame signal",
    )
    assertTrue(
      viewerSource().contains("function positionWasmFrame()"),
      "iframe is positioned over the snapshot's rendered box",
    )
    assertTrue(
      viewerSource().contains("loading Wasm…"),
      "load state keeps the snapshot with a status",
    )
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
  fun `viewer offers sign-in in place of the live toggle when auth is what blocks the lane`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    val protectedLive =
      ServeWeb.viewerPage(
        card,
        token,
        canApplyOverrides = true,
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(
            loginHref = "/auth/github/start?return=%2Fp%2FCardPreview%3Ftoken%3Dabc"
          ),
      )

    // The lane is one click away, so offer the click. Previously this rendered a `disabled` button
    // whose only explanation was a `title` — invisible on touch, and never announced, since a
    // disabled button is not focusable.
    assertTrue(
      protectedLive.contains("id=\"cp-live-signin\"") &&
        protectedLive.contains(
          "href=\"/auth/github/start?return=%2Fp%2FCardPreview%3Ftoken%3Dabc\""
        ),
      "auth-blocked live preview offers a real sign-in link, not a dead control",
    )
    assertTrue(
      protectedLive.contains("Live preview — sign in"),
      "the reason is in the visible label, not only in a hover tooltip",
    )
    // The old markup put the login URL in `data-github-login` and no script ever read it, so the
    // control did nothing when clicked. An anchor cannot regress that way — following it is the
    // browser's job — but pin that the dead attribute is gone so it can't quietly come back.
    assertFalse(
      protectedLive.contains("data-github-login="),
      "the login URL is the anchor's href, not an attribute nothing reads",
    )
    // The live stream gates on being signed in, full stop — the repo check is the PLAYGROUND's.
    // Naming `--github-auth-repo` here told a visitor they needed access to a repo they have never
    // heard of, which is how wear-m3-catalog#68 ended with an outside contributor concluding the
    // preview was broken rather than that they were signed out.
    assertFalse(
      protectedLive.contains("data-github-repo="),
      "the Live sign-in must not carry the playground's gating repo",
    )
    assertTrue(
      protectedLive.contains(
        "title=\"Sign in with GitHub to enable Live preview. Any GitHub account works.\""
      ),
      "…and its tooltip states the real bar instead of naming that repo",
    )
    // …unless the operator narrowed sign-in itself. `--github-auth-users` makes the verifier refuse
    // every login outside the list, so "any GitHub account" would walk those visitors through OAuth
    // to a 403. The repo check never restricted sign-in this way; the allowlist does.
    val allowlisted =
      ServeWeb.viewerPage(
        card,
        token,
        canApplyOverrides = true,
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(
            loginHref = "/auth/github/start?return=%2Fp%2FCardPreview",
            restrictedToAllowedUsers = true,
          ),
      )
    assertTrue(
      allowlisted.contains(
        "title=\"Sign in with GitHub to enable Live preview. " +
          "This server allows named GitHub users only.\""
      ),
      "an allowlisted server does not promise that any account works",
    )
    // Must NOT be the toggle: `updateLiveToggle()` drives that element through `.disabled` and
    // `aria-pressed`, neither of which means anything on a link.
    assertFalse(
      protectedLive.contains("id=\"cp-live-toggle\""),
      "the sign-in link replaces the toggle rather than impersonating it",
    )
    assertTrue(
      protectedLive.contains(
        "name=\"cp-mode\" value=\"live\" id=\"cp-live\" tabindex=\"-1\" disabled"
      ),
      "the hidden live transport radio stays disabled — sign-in is offered, not granted",
    )

    // A bundle with no live lane at all keeps the honestly-disabled toggle: inviting a sign-in
    // that would unlock nothing is worse than a greyed chip.
    val noLane = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      noLane.contains("id=\"cp-live-toggle\"") && !noLane.contains("cp-live-signin"),
      "a session with nothing to unlock is not invited to sign in",
    )

    val openLive = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertTrue(
      openLive.contains(
        "id=\"cp-live-toggle\" class=\"cp-live-toggle\" aria-pressed=\"false\" " +
          "data-default-lane-label=\"Snapshot\" " +
          "title=\"Static snapshot — click for the live, interactive preview\">"
      ),
      "live preview remains an ordinary toggle when no GitHub sign-in prompt is required",
    )

    // The mode hint must not contradict the chip. The transport radio stays disabled while auth
    // blocks the lane, so the old hint read "no live lane" directly beside an offer to sign in for
    // one — the page asserting in the same breath that the thing is available and that it does not
    // exist. Caught only once the fixture was captured WITH the stylesheet.
    assertTrue(
      viewerSource().contains("static snapshot — sign in for live"),
      "an auth-blocked lane is reported as needing sign-in, not as absent",
    )
    assertTrue(
      viewerSource().contains("const liveSignIn = may<HTMLAnchorElement>(\"cp-live-signin\")"),
      "the hint keys off the sign-in link, which is the only marker of the auth-blocked state",
    )

    // The sign-in case gets NEITHER half of the invitation. The stage's click handler enters the
    // lane through `#cp-live-toggle`'s own state, which this page deliberately does not render — so
    // a hint here would advertise a gesture that lands on a sign-in the visitor has not done yet.
    assertFalse(
      protectedLive.contains("id=\"cp-stage-live-hint\""),
      "no click-for-live hint over a stage whose lane is still behind sign-in",
    )
    assertFalse(
      protectedLive.contains("id=\"cp-live-toggle-verb\""),
      "the sign-in link names its own destination; it does not carry the chip's verb",
    )
  }

  @Test
  fun `the viewer invites the live lane from the stage, not only from the toolbar chip`() {
    val card = previews.first { it.id.endsWith("CardPreview") }

    // #4287. Before this the ONLY route into the live lane was a chip in the toolbar: the stage
    // carried no click handler and said nothing about being interactive, so a visitor who never
    // hovered the toolbar never learned the preview could be made live. Two affordances fix it —
    // a hint badge ON the picture, and a click on the picture itself.
    val openLive = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertTrue(
      openLive.contains(
        "<span class=\"cp-live-hint cp-stage-live-hint\" id=\"cp-stage-live-hint\" " +
          "aria-hidden=\"true\">click for live</span>"
      ),
      "the stage carries the grid's own live-hint badge, worded for the gesture it offers here",
    )
    // Deliberately the SAME class the grid's cards use (`CatalogLive.ts`), so one badge style
    // means one thing across both surfaces rather than two lookalikes drifting apart.
    assertTrue(
      openLive.indexOf("id=\"cp-stage-live-hint\"") > openLive.indexOf("class=\"cp-stage\""),
      "the hint lives inside the stage, where the render it describes is",
    )
    // The chip reads as a switch rather than a caption: the label names the lane it is ON, the
    // verb names the lane a click goes TO.
    assertTrue(
      openLive.contains(
        "<span class=\"cp-live-toggle-verb\" id=\"cp-live-toggle-verb\" aria-hidden=\"true\">" +
          "▸ Live</span>"
      ),
      "the chip states its destination, so \"Java\" alone can't read as a readout",
    )
    // aria-hidden matters: the accessible name stays the lane's own name, and `aria-pressed` plus
    // the tooltip already carry the switch semantics. Without it the chip announces "Java ▸ Live".
    assertTrue(
      openLive.contains("id=\"cp-live-toggle\"") && openLive.contains("aria-pressed=\"false\""),
      "the verb is added beside the existing toggle semantics, not in place of them",
    )

    // A session with no live lane gets neither: a disabled chip must not promise a destination,
    // and a hint over a stage whose click is inert is worse than no hint at all.
    val noLane = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      Regex("id=\"cp-live-toggle\"[^>]* disabled>").containsMatchIn(noLane),
      "the fixture under test is the disabled-chip case",
    )
    assertFalse(
      noLane.contains("id=\"cp-stage-live-hint\"") || noLane.contains("id=\"cp-live-toggle-verb\""),
      "nothing invites a lane that does not exist",
    )

    // One predicate behind the chip's verb, the badge and the stage's click handler, so the three
    // cannot disagree about whether clicking the picture does anything.
    assertTrue(
      viewerSourceFlat().contains("function liveInvited() { return rules.liveInviteAvailable({"),
      "the invitation is derived, not duplicated per affordance",
    )
    // Single click, not double: a double-click requirement is exactly as undiscoverable as the
    // toolbar-only chip this replaces.
    assertTrue(
      viewerSource().contains("img.addEventListener(\"click\", function (event) {") &&
        !viewerSource().contains("img.addEventListener(\"dblclick\""),
      "the stage enters live on a single click",
    )
  }

  @Test
  fun `the in-browser Wasm lane is reachable from the renderer combo whenever a wasm app backs the session`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // Case C — daemon live lane + wasm app: the chip's own toggle prefers the daemon
    // (bestLiveMode), so without a second way in the Wasm tier would be registered and
    // unreachable. It is an option in the renderer combo rather than a chip of its own.
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
      both.contains("<option value=\"wasm\">In browser (Wasm)</option>"),
      "with a wasm app the viewer offers the in-browser lane in the renderer combo",
    )
    assertTrue(both.contains("id=\"cp-live-toggle\""), "the live toggle stays alongside it")
    // While the in-browser lane is active, the daemon-only controls (size/device/orientation/
    // background + the app-theme selector) can't be honoured by the iframe, so syncServerControls
    // disables them on the wasm lane rather than leaving dead-but-enabled knobs.
    assertTrue(
      viewerSource().contains("var onWasm = wasmActive();") &&
        viewerSourceFlat().contains("!onWasm && !onRc && !onFixedFrame &&"),
      "server-only controls are gated off while the Wasm lane is active",
    )
    // `onFixedFrame` is ONE predicate covering both lanes that put a frame on the stage the server
    // did not just render — the imported spec raster and a published capture. Asserted here because
    // it was briefly half-propagated: `canServerRender` used it while the theme select and the
    // Remote Compose knobs still gated on a spec-only flag, so overrides stayed live over a
    // recording while the code read as though the lane were gated.
    //
    // #4088 lifted the expression into `onFixedFrameLane()` so `syncServerControls` and
    // `activeThemeChoice` share one definition (and so it is callable during module init, before
    // the lane's own elements exist). The property under test is unchanged — one predicate, both
    // lanes, no spec-only flag beside it — so it is asserted against the helper and its use rather
    // than the inline expression that used to spell it.
    assertTrue(
      viewerSourceFlat().contains("""return mode === "spec" || mode === "motion";"""),
      "the fixed-frame predicate still covers both the spec and the motion lane",
    )
    assertTrue(
      viewerSource().contains("var onFixedFrame = onFixedFrameLane();") &&
        !viewerSource().contains("var onSpec = specActive();"),
      "one fixed-frame predicate gates the override families, not a spec-only flag beside it",
    )
    assertTrue(
      viewerSourceFlat().contains("hasDeclaredThemes && !onWasm"),
      "declared options in the unified Theme selector are disabled on the Wasm lane",
    )

    // Case B — wasm app but NO daemon lane: the chip already drops into wasm as its only
    // interactive lane, but the combo still names both so the visitor can read what they're on.
    val wasmOnly = ServeWeb.viewerPage(card, token, wasmSrc = "/wasm/compose-m3/?id=card-filled")
    assertTrue(
      wasmOnly.contains("<option value=\"png\">Snapshot</option>") &&
        wasmOnly.contains("<option value=\"wasm\">In browser (Wasm)</option>"),
      "a wasm-only session names both of its lanes",
    )

    // Case A — daemon lane but no wasm app: one lane, so no combo at all — the chip carries the
    // whole row, and with nothing to disambiguate against it names the STATE the stage is in and
    // lets its verb name the switch out of it.
    val daemonOnly = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertFalse(
      daemonOnly.contains("id=\"cp-lane-select\""),
      "a single-lane session grows no combo box",
    )
    assertTrue(
      daemonOnly.contains("<span id=\"cp-live-toggle-label\">Snapshot</span>") &&
        daemonOnly.contains(">▸ Live</span>"),
      "the chip reads \"Snapshot ▸ Live\": a state, and the switch out of it",
    )
    // The label must not carry the destination too. It did before the verb existed — and the pair
    // then read "Live preview ▸ Live", one chip naming the same lane twice.
    assertFalse(
      daemonOnly.contains("<span id=\"cp-live-toggle-label\">Live preview</span>"),
      "the destination is the verb's job, and only the verb's",
    )
    // With no lane to enter there is no verb to pair with, so the label keeps the plain (disabled)
    // invitation — "Snapshot" alone beside a dead dot would say nothing about what the chip is for.
    val noLaneAtAll = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      noLaneAtAll.contains("<span id=\"cp-live-toggle-label\">Live preview</span>"),
      "a chip with nothing to switch to still says what it is about",
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
      viewerSource().contains("function fitLiveCanvas()"),
      "the live canvas has a dedicated aspect-preserving fit function",
    )
    // Contain-fit math: scale by the smaller of the two box/buffer ratios, then centre.
    assertTrue(
      viewerSource().contains("Math.min(boxW / liveW, boxH / liveH)"),
      "the frame is scaled to contain (the smaller box/buffer ratio), not stretched to fill",
    )
    assertTrue(
      viewerSource().contains("(boxW - w) / 2") && viewerSource().contains("(boxH - h) / 2"),
      "the fitted frame is centred within the snapshot box",
    )
    // The painter caches the buffer dims and re-fits on every frame; a resize re-fits too. Reads
    // the decoded bitmap rather than an <img>'s natural size since #4285 moved the lane onto
    // `createImageBitmap` for the ordering guarantees.
    assertTrue(
      viewerSource().contains("liveW = bitmap.width;") &&
        viewerSource().contains("fitLiveCanvas();"),
      "each frame caches its dims and re-fits",
    )
    assertTrue(
      viewerSource().contains("if (live && live.checked && !canvas.hidden) fitLiveCanvas();"),
      "a window resize re-fits the live canvas (not a plain box fill)",
    )
  }

  @Test
  fun `the snapshot image records the render URL its pixels came from`() {
    // The visible <img> is painted from a fetched blob, so `src` is an opaque `blob:` UUID that
    // says nothing about which render produced it. `data-cp-src` carries the originating /render
    // URL — format, knobs and lane — and is the only handle on the *displayed* frame's identity.
    // The `#cp-url-png` / `#cp-url-svg` copy fields are not a substitute: they mirror the current
    // control state and update before (or without) any render landing.
    //
    // This is a unit-level guard for the serve-lanes e2e, which silently lost its teeth once the
    // blob swap landed and `src` stopped matching the render URL it was asserting on.
    val js = viewerSource()
    assertTrue(
      js.contains("img.setAttribute(\"data-cp-src\", url);"),
      "the fetched URL is recorded",
    )
    assertTrue(
      js.indexOf("img.setAttribute(\"data-cp-src\", url);") >
        js.indexOf("img.setAttribute(\"data-cp-blob\", objectUrl);"),
      "recorded in the same onload that swaps the frame in, so it never describes pixels that " +
        "haven't been painted yet",
    )
  }

  @Test
  fun `the viewer spends its vertical space on the render, not on chrome above it`() {
    val view =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        catalogTitle = "Material 3 Design Kit",
        sourceHref = "https://github.com/yschimke/compose-ai-tools/blob/main/Profile.kt",
        engagement = ServeWeb.PreviewEngagement(1),
      )

    // 1. The breadcrumb is navigation, so it rides in the navigation bar rather than spending the
    //    body's first row on it.
    assertFalse(
      view.substringAfter("<main class=\"cp-main\">").contains("cp-breadcrumb"),
      "the trail is out of the body",
    )
    assertTrue(
      view
        .substringBefore("<main class=\"cp-main\">")
        .contains(
          "<nav class=\"cp-breadcrumb\" aria-label=\"Breadcrumb\">" +
            "<a href=\"/?token=$token&amp;session=compose-m3\">Material 3 Design Kit</a>"
        ),
      "…and in the header's lead slot, still linking the catalog it came from",
    )

    // 2. Name, trust verdict, id and the view tally are ONE row — they answer one question.
    val head = view.substringAfter("<div class=\"cp-preview-head\">").substringBefore("</div>")
    assertTrue(head.contains("<h1 class=\"cp-head cp-preview-title\">"), head)
    assertTrue(head.contains("<code class=\"cp-preview-id\""), head)
    assertTrue(head.contains("<span class=\"cp-viewer-engage\">1 view</span>"), head)

    // 3. The provenance links are hand-off affordances, so they sit with the other hand-off
    //    affordances (the PNG / SVG export links) below the stage — not between the title and the
    //    render.
    assertTrue(
      view.indexOf("class=\"cp-preview-links\"") > view.indexOf("class=\"cp-viewer "),
      "the provenance row is below the stage",
    )
    assertTrue(
      view.indexOf("class=\"cp-preview-links\"") < view.indexOf("class=\"cp-export\""),
      "…and immediately above the export bar it now leads",
    )
  }

  @Test
  fun `theme choices use a dropdown and secondary actions stay in the renderer row`() {
    val css = assetText("serve.css")
    assertTrue(
      css.contains(".cp-theme-menu-panel { position: absolute;") &&
        css.contains(".cp-theme-menu-panel .cp-theme-bar { display: flex; flex-direction: column;"),
      "theme choices render in an anchored dropdown",
    )
    // Eight theme chips beside four fixed controls — the published compose-m3 shape, and what the
    // committed `serve-viewer-theme-overflow` golden captures for the visual-diff bot.
    val crowded =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          (1..6).map { ServeTheme("Declared theme $it", "com.example.Theme${'$'}it") },
      )
    assertEquals(
      1,
      crowded.split("class=\"cp-theme cp-theme-bar\"").size - 1,
      "the dropdown contains one theme choice group",
    )
    assertFalse(crowded.contains("class=\"cp-viewer-bar\""), "the old horizontal row is gone")
    val rendererRow =
      crowded.substringAfter("<div class=\"cp-preview-primary\"").substringBefore("</div>")
    assertTrue(rendererRow.contains("<cp-bg-toggle") && rendererRow.contains("Fit width"))
  }

  /**
   * The Theme dropdown's ROWS, which were unreadable on every dark page: on `/wear-m3/` — a
   * dark-first catalog whose declared themes all carry `data-theme-mode="dark"`, so every row
   * matched — the menu opened as six invisible labels on a near-black panel.
   *
   * Two independent faults, both of them cascade accidents rather than colour choices, so both are
   * held here rather than left to a screenshot:
   *
   * 1. `color-scheme: normal` on a menu row. `normal` is not "whatever the page is" — it is the UA
   *    default, light — so it re-resolved every `light-dark()` in the token layer for those rows
   *    alone and painted `on-surface`'s LIGHT value (#1d1b20) on the dark surface the panel is
   *    filled with. `inherit` is the value that means what the rule intended.
   * 2. The rows are `.cp-theme-btn` inside `.cp-theme-bar`, and `.cp-theme-bar .cp-theme-btn` — the
   *    horizontal scroller's rule, for the bar this menu REPLACED — is declared later in the sheet
   *    at equal specificity. So `.cp-theme-menu-panel .cp-theme-btn` lost `display` to the
   *    scroller's `inline-block`, and `width`/`height` do not apply to the inline box that leaves
   *    the swatch pseudo-element in: the 16px circle drew as a 2px sliver of its own border. Every
   *    menu-row rule therefore has to name `.cp-theme-bar` too, which is what this asserts.
   */
  @Test
  fun `theme menu rows keep the page's colour scheme and the menu's own box`() {
    val css = assetText("serve.css")
    assertFalse(
      css.contains("color-scheme: normal"),
      "no rule resets a subtree to the UA's light default; `inherit` is what follows the page",
    )
    assertTrue(
      css.contains(
        ".cp-theme-menu-panel .cp-theme-bar .cp-theme-btn { display: flex; min-width: 12em;"
      ),
      "menu rows out-specify the scroller's `inline-block`, so the swatch gets a real box",
    )
    assertTrue(
      css.contains(
        ".cp-theme-menu-panel .cp-theme-bar .cp-theme-btn[data-theme-mode] { color-scheme: inherit;"
      ),
      "…and resolve their label colour in the scheme the page is actually painted in",
    )
    // Every rule that dresses a menu row has to clear the scroller the same way, or the next one
    // added without `.cp-theme-bar` silently loses whatever the scroller also declares.
    val menuRules =
      css.lines().filter { it.trimStart().startsWith(".cp-theme-menu-panel .cp-theme-btn") }
    assertTrue(
      menuRules.isEmpty(),
      "unqualified menu-row rules lose to `.cp-theme-bar`: $menuRules",
    )
  }

  @Test
  fun `viewer defaults to fit screen and offers an explicit fit width mode`() {
    val view = ServeWeb.viewerPage(previews.first(), token)
    // One toggle, unpressed: screen fit is the default, and "Fit width" names the state the
    // button turns on rather than a second segment that re-selects what is already showing.
    assertTrue(
      view.contains("class=\"cp-bg-btn cp-zoom-toggle\" aria-pressed=\"false\"") &&
        view.contains(">Fit width</button>"),
      "the viewer offers width fit as an unpressed toggle over the default screen fit",
    )
    // The cap's arithmetic — the 320px floor, the slack under the stage, the rounding that keeps a
    // re-measure from churning — moved to `cli/serve-web/src/viewer/fit.ts`, where
    // `viewerFit.test.ts`
    // drives each rule instead of grepping for one spelling of the expression. What this still
    // holds
    // is what the SERVED asset must do: measure the stage's real position rather than guess, hand
    // that to the shared rule, and apply a cap before the first render.
    assertTrue(
      viewerSource().contains("stage.getBoundingClientRect().top") &&
        viewerSource().contains("rules.fitCap(top, window.innerHeight)") &&
        viewerSource().contains("applyZoom(\"fit\");"),
      "screen fit bounds tall previews to the space the viewport actually has left below the " +
        "chrome, measured before the initial render rather than guessed at 72vh",
    )
    assertTrue(
      viewerSource().contains("if (live && live.checked && !canvas.hidden) fitLiveCanvas();") &&
        viewerSource().contains("if (wasmActive()) positionWasmFrame();"),
      "changing zoom re-pins active live and Wasm overlays to the snapshot geometry",
    )
  }

  @Test
  fun `screen previews keep device controls in Size while components expose only constraints`() {
    val screen =
      ServeWeb.viewerPage(
        ServePreview("speaker-details", "Speaker details", section = "Screens"),
        token,
        canApplyOverrides = true,
      )
    val component =
      ServeWeb.viewerPage(
        ServePreview("speaker-card", "Speaker card", section = "Components"),
        token,
        canApplyOverrides = true,
      )
    val sectionlessScreen =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfilePreview", "Profile screen"),
        token,
        canApplyOverrides = true,
      )
    val sectionlessComponent =
      ServeWeb.viewerPage(
        ServePreview("com.example.SpeakerCardPreview", "Speaker card"),
        token,
        canApplyOverrides = true,
      )

    assertTrue(screen.contains("<label>Device size"), "screen Size panel contains device presets")
    assertTrue(screen.contains("id=\"cp-orientation\""), "screen Size panel contains orientation")
    assertFalse(screen.contains("id=\"cp-sizeMode\""), "screen omits component constraints")
    assertFalse(screen.contains("data-cp-group=\"device\""), "screen has no duplicate Device panel")
    listOf("id:pixel_5", "id:pixel_7", "id:pixel_fold", "id:pixel_tablet").forEach {
      assertTrue(screen.contains("value=\"$it\""), "screen offers $it")
    }

    assertTrue(component.contains("id=\"cp-sizeMode\""), "component keeps size constraints")
    assertTrue(component.contains("id=\"cp-fixedW\""), "component keeps fixed sizing")
    assertTrue(component.contains("id=\"cp-minW\""), "component keeps minimum sizing")
    assertTrue(component.contains("id=\"cp-maxW\""), "component keeps maximum sizing")
    assertFalse(component.contains("id=\"cp-device\""), "component hides device overrides")
    assertFalse(
      component.contains("id=\"cp-orientation\""),
      "component hides orientation overrides",
    )
    assertFalse(component.contains("data-cp-group=\"device\""), "component has no Device panel")

    assertTrue(
      sectionlessScreen.contains("<label>Device size") &&
        sectionlessScreen.contains("id=\"cp-orientation\""),
      "sectionless screen keeps device controls in Size",
    )
    assertFalse(
      sectionlessScreen.contains("id=\"cp-sizeMode\""),
      "sectionless screen omits component constraints",
    )
    assertTrue(
      sectionlessComponent.contains("id=\"cp-sizeMode\""),
      "sectionless component keeps size constraints",
    )
    assertFalse(
      sectionlessComponent.contains("id=\"cp-device\"") ||
        sectionlessComponent.contains("id=\"cp-orientation\""),
      "sectionless component still hides device overrides",
    )
  }

  @Test
  fun `a Wear system's screens offer watch shapes instead of phones and no orientation`() {
    val wearScreen =
      ServeWeb.viewerPage(
        ServePreview("settings-complication", "Settings complication", section = "Screens"),
        token,
        canApplyOverrides = true,
        basePath = "/home-assistant-wear",
      )
    val phoneScreen =
      ServeWeb.viewerPage(
        ServePreview("settings", "Settings", section = "Screens"),
        token,
        canApplyOverrides = true,
        basePath = "/meshcore-mobile",
      )

    assertTrue(wearScreen.contains("<label>Device size"), "a Wear screen keeps the device picker")
    listOf(
        "id:wearos_small_round",
        "id:wearos_large_round",
        "id:wearos_xl_round",
        "id:wearos_square",
        "id:wearos_rect",
      )
      .forEach { assertTrue(wearScreen.contains("value=\"$it\""), "Wear screen offers $it") }
    listOf("id:pixel_5", "id:pixel_7", "id:pixel_fold", "id:pixel_tablet").forEach {
      assertFalse(wearScreen.contains("value=\"$it\""), "Wear screen must not offer $it")
      assertTrue(phoneScreen.contains("value=\"$it\""), "a handheld screen still offers $it")
    }
    // Watches don't rotate — the control is omitted rather than left as a dead knob, and neither
    // static-snapshot note may advertise it.
    assertFalse(wearScreen.contains("id=\"cp-orientation\""), "Wear screen omits orientation")
    assertTrue(phoneScreen.contains("id=\"cp-orientation\""), "a handheld screen keeps orientation")

    val staticWearScreen =
      ServeWeb.viewerPage(
        ServePreview("settings-complication", "Settings complication", section = "Screens"),
        token,
        canApplyOverrides = false,
        basePath = "/home-assistant-wear",
      )
    assertFalse(
      staticWearScreen.lowercase().contains("orientation"),
      "the Wear snapshot note must not promise an orientation override",
    )
    assertTrue(
      staticWearScreen.contains("device size, locale, font scale"),
      "the Wear snapshot note still lists the overrides it does carry",
    )
  }

  @Test
  fun `SVG is an on-screen format toggle and an export format when the session can export SVG`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // SVG isn't part of the awkward PNG/live radio group any more, but it's still an on-screen
    // format: a dedicated toggle beside the Live toggle swaps the static snapshot between the
    // raster
    // PNG and the vector SVG. Offered only when the session can export SVG (hasSvgExport).
    val svgView = ServeWeb.viewerPage(card, token, hasSvgExport = true, hasScrollExport = true)
    assertTrue(
      svgView.contains("id=\"cp-svg-toggle\"") && svgView.contains("class=\"cp-fmt-toggle\""),
      "an SVG-exporting session offers the on-screen SVG format toggle",
    )
    // The SVG lane reuses the snapshot <img> but swaps the render extension; the viewer JS carries
    // the snapshotExt seam and stamps the backend badge with SVG.
    assertTrue(
      viewerSource().contains("var snapshotExt = \".png\";") &&
        viewerSource().contains("? \".svg\" : \".png\""),
      "the snapshot lane flips its render extension between PNG and SVG",
    )
    // That the badge then names the lane "▪ SVG" is asserted against the element itself, in
    // `cli/serve-web/test/backendBadge.test.ts` ("names the SVG lane as static").
    // The SVG export also surfaces as a copyable/downloadable URL row plus the "Full page (scroll)"
    // toggle.
    assertTrue(
      svgView.contains("id=\"cp-url-svg\"") && svgView.contains("id=\"cp-scroll-long\""),
      "an SVG-exporting session offers the SVG download row and its Full-page toggle",
    )

    // Scroll export is its own capability: a PNG-only daemon still offers Full page.
    val pngScrollView = ServeWeb.viewerPage(card, token, hasScrollExport = true)
    assertTrue(
      pngScrollView.contains("id=\"cp-scroll-long\"") &&
        !pngScrollView.contains("id=\"cp-url-svg\""),
      "a PNG-only scroll producer offers Full page without advertising SVG",
    )

    // No export capabilities → no SVG toggle, no SVG URL row, and no scroll toggle.
    val plain = ServeWeb.viewerPage(card, token)
    assertFalse(plain.contains("id=\"cp-svg-toggle\""), "no SVG toggle without SVG export")
    assertFalse(plain.contains("id=\"cp-url-svg\""), "no SVG export row without SVG support")
    assertFalse(
      plain.contains("id=\"cp-scroll-long\""),
      "no Full-page toggle without a scroll export",
    )
  }

  @Test
  fun `a card answers the pointer as a tile, not as an underlined hyperlink`() {
    // Every clickable tile on the site — a front-door catalog card and a catalog's preview cards
    // alike — is one `<a class="cp-card">` wrapping an image and several lines of metadata. The
    // sheet's global `a:hover { text-decoration: underline }` therefore underlined ALL of that
    // metadata at once, which reads as four stacked links rather than one target. The card must
    // suppress that and answer as an object instead — in Material 3 terms, by rising an elevation
    // level and taking a `primary` state layer — and the same treatment must be reachable from the
    // keyboard.
    val css = assetText("serve.css")
    assertTrue(
      css.contains(".cp-card:hover, .cp-card:focus-visible {") &&
        css.contains("transform: translateY(-2px); text-decoration: none;") &&
        css.contains("box-shadow: var(--md-sys-elevation-level3);"),
      "hovering a card lifts it to a higher elevation instead of underlining its text",
    )
    // The M3 state layer: the card's own content colour composited over it at the spec's hover /
    // focus opacities, rather than a bespoke fill or rim.
    assertTrue(
      css.contains(".cp-card:hover::after { opacity: var(--md-sys-state-hover-opacity); }") &&
        css.contains(
          ".cp-card:focus-visible::after { opacity: var(--md-sys-state-focus-opacity); }"
        ),
      "a state layer covers the card under the pointer and under keyboard focus",
    )
    assertTrue(
      css.contains(
        ".cp-card:hover .cp-imgwrap img, .cp-card:focus-visible .cp-imgwrap img { transform: scale(1.035); }"
      ),
      "the card's artwork eases in under the pointer",
    )
    assertTrue(
      css.contains(
        ".cp-card:focus-visible { outline: 3px solid var(--md-sys-color-secondary); outline-offset: 2px; }"
      ),
      "keyboard focus gets the card treatment plus M3's own focus indicator",
    )
    // Motion is an enhancement, never the affordance: a visitor who asked for less motion still
    // gets the rim, the shadow and the wipe target — just no travel.
    assertTrue(
      css.contains(".cp-card:hover, .cp-card:focus-visible { transform: none; }"),
      "the lift and zoom are dropped under prefers-reduced-motion",
    )
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
      assetText("serve.css").contains("@media (max-width: 640px) {"),
      "the stylesheet has a narrow-viewport breakpoint",
    )
    assertTrue(
      assetText("serve.css")
        .contains(".cp-stage, .cp-controls, .cp-nav { flex: 1 1 100%; min-width: 0; }"),
      "stage/overrides/nav stack full-width and drop their min-width on a phone",
    )
    // Usability: on mobile the two drawers become bottom sheets reachable from a sticky toggle row
    // (so overrides + the component list are one tap away, not a long scroll below a tall preview),
    // with a scrim behind the open sheet. The row that sticks is the title row, which is where all
    // four disclosures now live.
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-preview-head { position: sticky; top: var(--site-header-height); z-index: 21;"
        ),
      "the disclosure row is sticky below the global header on mobile",
    )
    assertTrue(
      assetText("serve.css").contains(".cp-viewer.cp-controls-open .cp-controls,") &&
        assetText("serve.css").contains("position: fixed; left: 0; right: 0; bottom: 0;"),
      "open drawers render as fixed bottom sheets on mobile",
    )
    assertTrue(
      viewer.contains("id=\"cp-scrim\"") &&
        assetText("serve.css").contains(".cp-scrim.cp-scrim-on"),
      "a dismiss scrim backs the open bottom sheet",
    )
    // The overrides drawer collapses on load on a phone so the preview leads. That is
    // `<cp-viewer-drawers>`'s job now, asserted against the element in
    // `cli/serve-web/test/viewerDrawers.test.ts` ("is closed on a phone so the preview leads");
    // what the page owes it is the tag.
    assertTrue(viewer.contains("<cp-viewer-drawers>"), "the viewer wires its drawers")
    // The breakpoint ships on the landing pages too (shared stylesheet).
    val landing = ServeWeb.landingPage(moduleLabel, previews, token)
    assertTrue(
      assetText("serve.css").contains("@media (max-width: 640px) {"),
      "landing is responsive too",
    )
    assertTrue(
      assetText("serve.css")
        .contains(".cp-card.cp-sys { display: grid; grid-template-rows: 220px 1fr; }") &&
        assetText("serve.css").contains(".cp-syslist .cp-imgwrap { min-height: 0; height: 220px;"),
      "system cards reserve one consistent hero region so metadata aligns across aspect ratios",
    )
    // Three invariants the hero break-out depends on, each of which turns into a silent regression
    // rather than a failing render if it is dropped.
    assertTrue(
      assetText("serve.css").contains(".cp-sys-actions") &&
        assetText("serve.css").contains("pointer-events: none; }") &&
        assetText("serve.css").contains(".cp-sys-actions > a { pointer-events: auto; }"),
      "the action row passes clicks through to the tile link; only its chips take them",
    )
    // Both halves on one line, and both load-bearing: the rounding, because `overflow: visible`
    // leaves nothing to clip the layer's square corners to the card's radius; and `z-index: -1`,
    // which drops the tint UNDER the card's content so it stops washing `primary` across a design
    // system's own screenshot. Lowering the layer rather than raising the hero is deliberate — a
    // raised hero also outranks the stretched tile link and has to refuse pointer events, and then
    // the part of it hanging outside the card stops hovering the card at all. The harness's
    // "the front door's state layer stays under the hero, and the break-out keeps its hover"
    // contract proves that end in a browser; this pins the declaration the Kotlin side ships.
    assertTrue(
      assetText("serve.css")
        .contains(".cp-card.cp-sys::after { border-radius: inherit; z-index: -1; }"),
      "the state layer rounds itself and paints beneath the hero instead of over it",
    )
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-card.cp-sys:focus-within:not(:has(.cp-action-chip:focus-visible)) .cp-sys-title"
        ),
      "keyboard focus on the tile link gets the full card treatment, not just the outline",
    )
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-card.cp-sys:focus-within:not(:has(.cp-action-chip:focus-visible)) " +
            "{ transform: none; }"
        ),
      "…and reduced motion still cancels the lift for it — the blanket reset is keyed on " +
        ":focus-visible, which the card div can never match",
    )
    assertTrue(
      landing.contains("class=\"cp-site-header\"") && landing.contains("id=\"cp-status-link\""),
      "all pages carry the shared site navigation",
    )
  }

  @Test
  fun `the header drops the home and repo links the brand and footer already carry`() {
    val landing = ServeWeb.landingPage(moduleLabel, previews, token, version = version)
    val header =
      landing.substringAfter("<header class=\"cp-site-header\">").substringBefore("</header>")
    // "Catalogs" pointed at `/` — where the brand link beside it already goes. One destination, one
    // entry.
    assertFalse(header.contains(">Catalogs</a>"), "no second link to the home page")
    assertTrue(
      header.contains("class=\"cp-site-brand\" href=\"/?token=$token\""),
      "the brand is the way home",
    )
    // The repo link is a fact about the software, so it sits with the build number in the footer.
    assertFalse(header.contains("github.com/"), "the repo link has left the header")
    val footer = landing.substringAfter("<footer class=\"cp-site-footer\">")
    assertTrue(
      footer.contains("<a href=\"https://github.com/yschimke/compose-ai-tools\">") &&
        footer.contains(" GitHub</a>"),
      "…and lands in the footer, beside /version and the build",
    )
    // Status and Settings stay put: both are about this server's own pages.
    assertTrue(
      header.contains("id=\"cp-status-link\"") && header.contains("class=\"cp-settings\""),
      "the page-scoped nav entries are untouched",
    )
  }

  @Test
  fun `a catalog landing uses the home-linked brand instead of duplicate navigation`() {
    val front = ServeWeb.landingPage(moduleLabel, previews, token, hasHomeIndex = true)
    assertFalse(front.contains("class=\"cp-systems\""), "the sideways design-systems nav is gone")
    assertFalse(front.contains("class=\"cp-back\""), "the home link is not duplicated")
    // No sideways links to the other catalogs any more.
    assertFalse(
      front.contains("href=\"/wear-m3/?token=$token\""),
      "no sideways link to sibling catalogs",
    )

    // Public mode keeps the same single route home.
    val public =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, hasHomeIndex = true)
    assertFalse(public.contains("class=\"cp-back\""), "the public home link is not duplicated")

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
        refreshUrl = "/compose-m3/refresh",
      )
    assertTrue(
      landing.contains("class=\"cp-prov cp-disclosure\" open"),
      "the provenance details render, expanded",
    )
    // It lives in the site footer now, beside the build and source links.
    assertTrue(
      landing.indexOf("<footer class=\"cp-site-footer\">") <
        landing.indexOf("class=\"cp-prov cp-disclosure\"") &&
        landing.indexOf("class=\"cp-prov cp-disclosure\"") <
          landing.indexOf("class=\"cp-site-footer-links\""),
      "catalog details sit in the footer, above its links row",
    )
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
      landing.contains("class=\"cp-prov-refresh\"") &&
        landing.contains("data-refresh-url=\"/compose-m3/refresh\""),
      "the strip offers an immediate catalog refresh next to regenerate",
    )
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
  fun `the home footer surfaces the server version with a GitHub icon`() {
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
    assertTrue(
      home.indexOf("<footer class=\"cp-site-footer\">") < home.indexOf(">server v1.2.3<"),
      "the running server version is in the footer",
    )
    // The front door carries no "about this preview server" explainer at all.
    assertFalse(
      home.contains("About this preview server") ||
        home.contains("class=\"cp-about cp-disclosure\""),
      "the about box is gone from the front door",
    )
    // A null version simply omits the pill (no dangling separator crash), and the footer's own
    // links survive it.
    val noVer = ServeWeb.homeIndexPage(emptyList(), token, isPublic = true)
    assertFalse(noVer.contains("class=\"cp-about-ver\""), "no version pill when version is null")
    assertTrue(
      noVer.contains("<footer class=\"cp-site-footer\">") && noVer.contains("href=\"/version\""),
      "the footer still carries its links without a version",
    )
  }

  @Test
  fun `the home header shows GitHub login state when auth is configured`() {
    val unsigned =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F"),
      )
    assertTrue(
      unsigned.contains("class=\"cp-gh-auth\" href=\"/auth/github/start?return=%2F\""),
      "unsigned home page links to GitHub sign-in",
    )
    assertTrue(unsigned.contains("> Sign in with GitHub</a>"), unsigned)
    assertTrue(
      unsigned.indexOf("<header class=\"cp-site-header\">") <
        unsigned.indexOf("> Sign in with GitHub</a>") &&
        unsigned.indexOf("> Sign in with GitHub</a>") < unsigned.indexOf("</header>"),
      "unsigned GitHub action is in the header",
    )

    val signed =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            login = "yschimke",
          ),
      )
    assertTrue(
      signed.contains("class=\"cp-gh-auth cp-gh-auth--signed\""),
      "signed home page shows GitHub status",
    )
    assertTrue(signed.contains("Signed in as yschimke"), signed)
    assertTrue(
      signed.indexOf("<header class=\"cp-site-header\">") <
        signed.indexOf("Signed in as yschimke") &&
        signed.indexOf("Signed in as yschimke") < signed.indexOf("</header>"),
      "signed GitHub identity is in the header",
    )
    val aboutEnd = signed.indexOf("</details>")
    assertFalse(
      signed.substring(0, aboutEnd).contains("server v") ||
        signed.substring(0, aboutEnd).contains("href=\"/version\""),
      "the home about disclosure no longer contains build metadata",
    )
  }

  @Test
  fun `a catalog landing carries the same GitHub login control as the front door`() {
    val auth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F")
    // A top-level site ([ServeSites]) roots one catalog on a hostname, so THIS page is its front
    // door — no home index sits above it to carry the control. Without it the only sign-in on
    // `wear.preview.coo.ee` was a long-press on a card, and a visitor had to be told to go and sign
    // in on a different hostname before Live would work at all (wear-m3-catalog#68).
    val landing =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, githubAuth = auth)
    assertTrue(
      landing.contains("class=\"cp-gh-auth\" href=\"/auth/github/start?return=%2F\""),
      "an unsigned catalog landing links to GitHub sign-in",
    )
    assertTrue(
      landing.indexOf("<header class=\"cp-site-header\">") <
        landing.indexOf("> Sign in with GitHub</a>") &&
        landing.indexOf("> Sign in with GitHub</a>") < landing.indexOf("</header>"),
      "the landing's GitHub action is in the header",
    )

    val signed =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth = auth.copy(login = "yschimke"),
      )
    assertTrue(signed.contains("Signed in as yschimke"), "…and reports who is signed in")

    // Catalog mode drops the live lane whole — hover-live included — so there is nothing behind a
    // sign-in there, exactly as on every other page that renders this control.
    val catalogMode =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth = auth,
        componentBrowser = true,
      )
    assertFalse(
      catalogMode.contains("cp-gh-auth"),
      "Catalog mode offers no sign-in, because it unlocks nothing there",
    )

    // Unconfigured auth is unchanged: no control, no empty header slot.
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true).contains("cp-gh-auth"),
      "a box with no GitHub auth renders no sign-in control",
    )
  }

  @Test
  fun `the login control names the lane the sign-in actually unlocks`() {
    val auth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F")
    // Live is the broad case — the front door's wording, and a catalog that streams. It names no
    // repository, because being signed in IS the whole gate (wear-m3-catalog#68).
    val live =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, githubAuth = auth)
    assertTrue(
      live.contains("title=\"Live previews require a GitHub sign-in\""),
      "the live lane's gate is the sign-in itself",
    )
    assertFalse(
      live.substringAfter("cp-gh-auth").substringBefore("</a>").contains("access to"),
      "…so the live wording names no repository",
    )

    // A catalog with no live lane but a reachable playground: the control is still worth showing,
    // but repository access is that lane's real gate, so promising Live would be false twice over.
    val playground =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth =
          auth.copy(
            lane = ServeWeb.GatedLane.PLAYGROUND,
            accessRepository = "yschimke/compose-ai-tools",
          ),
      )
    assertTrue(
      playground.contains(
        "title=\"The playground requires a GitHub sign-in with access to " +
          "yschimke/compose-ai-tools\""
      ),
      "a playground-only catalog names the playground and its repository gate",
    )
    assertFalse(
      playground.contains("Live previews require"),
      "…and never promises a Live lane the catalog does not have",
    )

    // The allowlist reshapes either sentence: it narrows who may sign in at all, which neither
    // lane's own gate does.
    assertTrue(
      ServeWeb.landingPage(
          moduleLabel,
          previews,
          token,
          isPublic = true,
          githubAuth =
            auth.copy(
              lane = ServeWeb.GatedLane.PLAYGROUND,
              accessRepository = "yschimke/compose-ai-tools",
              restrictedToAllowedUsers = true,
            ),
        )
        .contains(
          "title=\"Playground access is limited to configured GitHub users with access to " +
            "yschimke/compose-ai-tools\""
        ),
      "an allowlisted box says so on the playground wording too",
    )
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
      viewerSource().contains("location.pathname.replace"),
      "viewer derives its request base from the path",
    )
  }

  @Test
  fun `every page ends with the minimal footer carrying the running version`() {
    // The footer is chrome, not a per-page decision: `document` emits it for every surface, so a
    // new page cannot ship without it. One representative page per kind, public and token-gated.
    val pages =
      mapOf(
        "landing" to ServeWeb.landingPage(moduleLabel, previews, token, version = "1.2.3"),
        "landing (public)" to
          ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, version = "1.2.3"),
        "home" to ServeWeb.homeIndexPage(emptyList(), token, isPublic = true, version = "1.2.3"),
        "viewer" to ServeWeb.viewerPage(previews.first(), token, version = "1.2.3"),
        "comparison" to ServeWeb.comparisonPage(moduleLabel, previews, token, version = "1.2.3"),
        "not found" to ServeWeb.notFoundPage("gone", token, isPublic = true, version = "1.2.3"),
        "doc upload" to
          ServeWeb.docUploadPage(
            token,
            isPublic = true,
            ttlSeconds = 3600,
            urlUploadAllowed = false,
            version = "1.2.3",
          ),
      )
    for ((name, html) in pages) {
      assertTrue(html.contains("<footer class=\"cp-site-footer\">"), "$name has no footer")
      assertTrue(html.contains(">server v1.2.3<"), "$name footer omits the running version")
      assertTrue(
        html.indexOf("</main>") < html.indexOf("<footer class=\"cp-site-footer\">"),
        "$name footer must follow the page body",
      )
    }
  }

  @Test
  fun `no landing carries the about intro, public or not`() {
    for (landing in
      listOf(
        ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true),
        ServeWeb.landingPage(moduleLabel, previews, token),
      )) {
      assertFalse(
        landing.contains("class=\"cp-about cp-disclosure\""),
        "the about disclosure is gone",
      )
      assertFalse(landing.contains("About this preview server"), "the about title is gone")
      assertFalse(
        landing.contains("How previews run and catalogs are trusted"),
        "the about hint is gone",
      )
      // The footer's own links survive its removal.
      assertTrue(landing.contains("href=\"/version\""), "expected a link to /version")
    }
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
    assertTrue(staticView.contains("id=\"cp-sizeMode\" disabled"), "component sizing disabled")
    assertFalse(staticView.contains("id=\"cp-device\""), "component device override omitted")
    assertFalse(staticView.contains("id=\"cp-orientation\""), "component orientation omitted")
    assertTrue(
      staticView.contains("id=\"cp-live\" tabindex=\"-1\" disabled"),
      "live transport radio disabled",
    )
    // With no live lane at all, the chip is itself disabled — and its tooltip says so rather than
    // inviting a click that would do nothing.
    assertTrue(
      staticView.contains("id=\"cp-live-toggle\"") &&
        Regex("id=\"cp-live-toggle\"[^>]* disabled>").containsMatchIn(staticView),
      "the live toggle is disabled on a pure static bundle",
    )
    assertTrue(
      staticView.contains("title=\"Static snapshot — this session has no live lane to switch to\""),
      "a chip with nothing to switch to does not promise a live preview",
    )
    // The tooltip inverts with the chip's meaning, so it is re-derived on every lane transition
    // from the same state that drives the dot — a fixed one would say "click for live" on a chip
    // whose click now exits to the snapshot.
    assertTrue(
      viewerSource().contains("\"Interactive — click to return to the static snapshot\""),
      "the chip's tooltip tracks the lane rather than being written once by the server",
    )
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*data-has-declared-themes=\"false\"[^>]* disabled>")
        .containsMatchIn(staticView),
      "Theme disabled without a renderer or Wasm app",
    )
    assertFalse(
      staticView.contains("id=\"cp-touchOverlay\""),
      "no live stream ⇒ the live-only overlay toggles are omitted entirely, not left dead",
    )
    assertTrue(
      staticView.contains(">Light (Default)</option>") &&
        staticView.contains(">Dark (Default)</option>"),
      "the unified Theme selector always carries the two default modes",
    )

    // Static + Wasm: theme, font scale, and locale go LIVE (the in-browser app honours them), while
    // component sizing stays server-only.
    val wasmView =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
      )
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*>").find(wasmView)?.value?.let {
        it.contains("data-has-declared-themes=\"false\"") && !it.contains(" disabled")
      } == true,
      "Theme is enabled with a Wasm app",
    )
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
    assertTrue(wasmView.contains("id=\"cp-sizeMode\" disabled"), "sizing stays server-only")
    assertFalse(wasmView.contains("id=\"cp-device\""), "component device override stays omitted")
    assertFalse(wasmView.contains("id=\"cp-orientation\""), "component orientation stays omitted")
    // The Wasm override-patch builder forwards the honoured params (theme/font scale/locale) to the
    // running app (via postMessage / the initial `#…` fragment), not the iframe query.
    assertTrue(viewerSource().contains("\"fontScale=\""), "font scale forwarded to Wasm")
    assertTrue(viewerSource().contains("\"localeTag=\""), "locale forwarded to Wasm")
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
      viewerSource().contains("setMode(\"wasm\");"),
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
      liveCatalogWasm.contains("id=\"cp-sizeMode\" disabled"),
      "server-render-only sizing stays disabled on a live catalog's static snapshot",
    )
    // A live-stream session offers the daemon-composited overlay toggle ENABLED, even though the
    // viewer opens on the static snapshot: ticking it switches into Live Compose (viewer.js'
    // onOverlayChanged) rather than sitting dead until "Live preview" is clicked first.
    assertTrue(
      liveCatalogWasm.contains("cp-overlays") &&
        liveCatalogWasm.contains("id=\"cp-touchOverlay\" type=\"checkbox\">"),
      "live stream offers the overlay toggles enabled from the static lane",
    )
    // The accessibility layer is NOT one of them: it is drawn client-side from the daemon's a11y
    // data products, so it is gated on the host advertising them, not on the live stream. A
    // catalog-live session that can't produce them offers no dead checkbox at all.
    assertFalse(
      liveCatalogWasm.contains("cp-inspect"),
      "inspection layers are gated on the data products, not on the live stream",
    )
    assertTrue(
      viewerSource()
        .contains("if (anyOverlayChecked() && live && !live.disabled) setMode(\"live\");"),
      "checking an overlay off the live lane enters Live Compose",
    )
    // Overlays are URL-owned state, not live-socket-only state: collected by `overrides()` (the map
    // `query()` serializes) and listed in URL_STATE_PARAMS, so a ticked box rides the page URL, the
    // export links and the stream's connect query. Collected only in `liveOverrides()` it would
    // reach the daemon and nowhere else — unshareable, unrestorable by Back, and applied a frame
    // late via the onopen replay instead of arriving with `stream/start`.
    // The list moved to `cli/serve-web/src/viewer/ownedParams.ts`, where
    // `viewerOwnedParams.test.ts`
    // asserts each family's membership by name — including what must NOT be owned, which a grep for
    // one line of the list could never express. What the served asset must still do is ask.
    assertTrue(
      viewerSource().contains("rules.ownsUrlParam(name)"),
      "overlays are URL-owned params, decided by the shared list rather than a second copy",
    )
    // The stream replays the full liveOverrides() on open so an overlay checked while the socket
    // was
    // still connecting (its change event dropped by the readyState guard) still reaches the daemon.
    assertTrue(
      viewerSource().contains("sock.onopen = function () {"),
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
        hasScrollExport = true,
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
          "data-knob-default=\"Filled\" " +
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
    // below). Matched without the parameter list: the handler's *existence* is the contract here,
    // and pinning its arity broke this when it grew one.
    assertTrue(
      viewerSource().contains("function onKnobEdited(") &&
        viewerSource().contains("function knobRoute()"),
      "knob edits have a dedicated, transport-aware handler",
    )
    assertTrue(
      viewerSource().contains("setSnapshotLoading(true)") &&
        viewerSource().contains("data-reloading") &&
        assetText("serve.css").contains("cp-reload-spin"),
      "snapshot overrides fade the current preview and show a spinner while re-rendering",
    )
    assertTrue(
      viewerSource()
        .contains(
          "function cancelSnapshotLoading() {\n    snapshotGen++;\n    status.textContent = \"\";"
        ),
      "cancelling a snapshot render clears its stale rendering status",
    )
    // During an active Live (stream), the override map sent over the WebSocket must carry the knob
    // values too (as knob.<key> entries), not just the display fields — otherwise the daemon resets
    // an edited knob to its default. The setOverrides sends use liveOverrides(), which folds them
    // in.
    assertTrue(
      viewerSource().contains("function liveOverrides()") &&
        viewerSource().contains("o[\"knob.\" + key]"),
      "the live-stream override map includes the declared knob values",
    )
    assertFalse(
      viewerSource().contains("setOverrides\", overrides: overrides()"),
      "live-stream setOverrides sends liveOverrides() (knobs included), not the display-only map",
    )
    // A live catalog's carried daemon re-renders an override on demand (canRenderOverrides), so the
    // display controls (Size / Locale / Day-Night / …) render ENABLED right
    // in the static snapshot — editing one re-points /render, which the daemon serves freshly. This
    // is the fix for "most override modes disabled for CMP": they no longer sit greyed out until a
    // live stream is opened.
    assertTrue(
      viewerSource().contains("function syncServerControls()"),
      "the viewer has a syncServerControls() that keeps the display controls in sync",
    )
    assertTrue(
      viewerSource().contains("syncServerControls();"),
      "syncServerControls() is invoked on every mode transition",
    )
    assertTrue(
      viewerSource().contains("!staticSnapshot || canRenderOverrides || !!(live && live.checked)"),
      "display controls are live whenever the server can render an override (on-demand or streaming)",
    )
    // The server-render controls render ENABLED in the baked markup (canRenderOverrides), not
    // disabled-until-live: size and locale take effect immediately via on-demand /render.
    assertTrue(
      catalogKnobs.contains("id=\"cp-sizeMode\">") &&
        !catalogKnobs.contains("id=\"cp-device\"") &&
        !catalogKnobs.contains("id=\"cp-orientation\"") &&
        !catalogKnobs.contains(
          "id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\" placeholder=\"e.g. en-GB, zh-Hant-TW\" autocomplete=\"off\" disabled"
        ),
      "component display controls render enabled without device overrides on an on-demand catalog",
    )
    // A plain static bundle (no daemon) still shows the knobs as DISABLED, informational controls.
    val staticKnobs = ServeWeb.viewerPage(knobPreview, token)
    assertTrue(
      staticKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "data-knob-default=\"Filled\" " +
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
          "data-knob-default=\"Filled\" " +
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
      viewerSource().contains("function onKnobEdited(") &&
        viewerSource().contains("wasmFrame!.contentWindow.postMessage(wasmOverridePatch()"),
      "a knob edit routes to the Wasm iframe when that tier is active",
    )
    // wasmOverridePatch() carries the changed knob into the iframe fragment / postMessage,
    // alongside
    // the display axes — without this the app never sees the edit.
    assertTrue(
      viewerSource().contains("function wasmOverridePatch()") &&
        viewerSourceFlat().contains("parts.push( \"knob.\" + encodeURIComponent(key)"),
      "the wasm override patch includes the author-declared knob values",
    )
    val viewerScript = viewerSource()
    assertTrue(
      viewerScript.contains("src += \"&theme=\" + encodeURIComponent(uiMode.value)") &&
        viewerScript.contains("if (rcWasmActive())") &&
        viewerScript.contains("(id === \"uiMode\" && onRcWasm)"),
      "the RC Wasm lane forwards Day/Night and keeps that control enabled while active",
    )
    // Deep-link parity: the knob controls hydrate from the page URL's `knob.<key>` params on load,
    // so opening `/p/…?knob.label=Hello` (or a copied direct link) renders the override immediately
    // in every transport — including the Wasm iframe, whose patch is built purely from control
    // state
    // — rather than the author default until the user edits the control.
    assertTrue(
      viewerSource().contains("q.get(\"knob.\" + key)"),
      "the viewer hydrates declared knob controls from the URL's knob.<key> params",
    )

    // Copyable direct links: every viewer offers a PNG URL row (copy + download); a session that
    // can
    // export SVG (a catalog / daemon) also offers an SVG row. The URLs are built client-side from
    // location.origin with the current overrides so a copied link reproduces the on-screen render.
    // …and the bar is a plain always-visible line, not a disclosure: the hand-off must not be one
    // click deep.
    assertTrue(
      catalogKnobs.contains("<div class=\"cp-export\" aria-label=\"Export the current view\">") &&
        !catalogKnobs.contains("cp-export cp-disclosure"),
      "the export bar is shown open, not behind a summary",
    )
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-png\"") && catalogKnobs.contains("id=\"cp-dl-png\""),
      "the PNG group has a URL field and a download link",
    )
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-svg\"") && catalogKnobs.contains("id=\"cp-dl-svg\""),
      "an SVG-exporting session also offers an SVG group",
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
      viewerSource().contains("readAsDataURL") &&
        viewerSource().contains("navigator.clipboard.writeText"),
      "the Copy PNG/SVG handler fetches the render and writes it to the clipboard as text",
    )
    // Copy PNG puts REAL image/png bytes on the clipboard where the browser supports it, so one
    // paste into a GitHub issue uploads the exact render — the base64 data: URI above is the
    // fallback, not the primary path. The blob is passed as a promise (Safari builds the
    // ClipboardItem synchronously inside the click).
    assertTrue(
      viewerSource().contains("new ClipboardItem({ \"image/png\": pngBlob })"),
      "Copy PNG writes image/png to the clipboard so it pastes as a picture",
    )
    // The prefilled "report an issue" link follows the on-screen overrides, and never carries the
    // session token into a body destined for a public issue.
    assertTrue(
      viewerSource().contains("function refreshReportLink()") &&
        viewerSource().contains("{{render}}") &&
        viewerSource().contains("stripToken("),
      "the report body is re-substituted at the current render, token stripped",
    )
    // …by writing an INPUT VALUE, never an href. The affordance is a GET form whose action is a
    // server-rendered literal, so no page-derived string can reach a navigation sink.
    val refreshReportLinkSource =
      viewerSource()
        .substringAfter("function refreshReportLink()")
        .substringBefore("function stripToken(")
    assertTrue(
      refreshReportLinkSource.contains("body.value = tpl.replace(") &&
        !refreshReportLinkSource.contains(".href = "),
      "the report prefill goes into a form input, not a navigation sink",
    )
    // The URL is copied by a plainly-named button rather than by clicking a field whose only clue
    // was a `title`. The field itself stays in the DOM (refreshLinks writes it, both copy buttons
    // read it) but off-screen — nobody reads a 200-character absolute /render URL.
    assertTrue(
      catalogKnobs.contains("class=\"cp-copyurl\" data-copyurl-target=\"cp-url-png\"") &&
        catalogKnobs.contains(">Copy link</button>"),
      "each format offers a Copy link button that says what it does",
    )
    assertTrue(
      viewerSource().contains("querySelectorAll<HTMLElement>(\".cp-copyurl\")") &&
        viewerSource().contains("data-copyurl-target"),
      "the Copy link handler copies the field it targets",
    )
    assertTrue(
      assetText("serve.css").contains(".cp-url { position: absolute;"),
      "the URL field is taken out of the flow rather than given a third of the line",
    )
    assertTrue(
      // Matched without the parameter list — the helper's existence is the contract, not its
      // arity, which grew a `skipUrlSync` opt-out for the wasm auto-enable path.
      viewerSource().contains("function refreshLinks(") &&
        viewerSource().contains("location.origin"),
      "the links are rebuilt from location.origin as the controls change",
    )
    assertTrue(
      viewerSource().contains(".cp-revision, .cp-pinned-current") &&
        viewerSource().contains("destination.searchParams.set(name, value)"),
      "revision links follow a theme selected after the page was rendered",
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
    assertTrue(liveView.contains("id=\"cp-sizeMode\">"), "sizing enabled on a live session")
    assertFalse(liveView.contains("id=\"cp-device\""), "device remains omitted on a live component")
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
    // One selector carries the default day/night modes and each provider FQN with its human name.
    assertTrue(
      view.contains("id=\"cp-theme\"") &&
        view.contains(">Light (Default)</option>") &&
        view.contains(">Dark (Default)</option>"),
      "declared themes share one selector with the two default modes",
    )
    assertTrue(
      view.contains(
        "<option value=\"theme:com.example.BrandLightThemeCatalog\" data-theme-mode=\"light\">Brand Light</option>"
      ),
      "each declared theme is an option keyed by its provider FQN",
    )
    // `@ThemeCatalog(group=…)` buckets themes into <optgroup>s; an ungrouped theme stays flat.
    assertTrue(view.contains("<optgroup label=\"Brand\">"), "grouped themes get an <optgroup>")
    assertTrue(
      view.contains(
        "<option value=\"theme:com.example.HighContrastThemeCatalog\">High Contrast</option>"
      ),
      "an ungrouped theme is a flat option",
    )
    // Enabled on a daemon host and routed like a knob (the daemon path, never the wasm
    // auto-enable).
    assertFalse(
      view.contains("data-has-declared-themes=\"true\" disabled"),
      "the theme selector is enabled on a daemon-backed host",
    )
    assertTrue(
      viewerSource().contains("if (chosenThemeProvider()) onKnobChanged();"),
      "declared choices route through the daemon (knob) path",
    )
    assertTrue(
      viewerSource().contains("parts.push(\"themeProvider=\""),
      "a chosen theme is appended to the /render URL as themeProvider",
    )

    val discoveredNightPreview =
      ServeWeb.viewerPage(
        ServePreview("com.example.PlainPreview", "Plain preview", uiMode = 0x20),
        token,
        canApplyOverrides = true,
        declaredThemes = themes,
      )
    assertTrue(
      discoveredNightPreview.contains("<option value=\"dark\" selected>Dark (Default)</option>"),
      "a preview discovered with night uiMode selects its actual baked default",
    )
    assertFalse(
      discoveredNightPreview.contains("<option value=\"light\" selected>Light (Default)</option>"),
      "a night preview does not fall back to the ID-based day heuristic",
    )

    // A static bundle can't load a provider, so the selector renders disabled (informational).
    val staticThemed = ServeWeb.viewerPage(previews.first(), token, declaredThemes = themes)
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*data-has-declared-themes=\"true\"[^>]* disabled>")
        .containsMatchIn(staticThemed) &&
        staticThemed.contains(
          "value=\"theme:com.example.BrandLightThemeCatalog\" data-theme-mode=\"light\" disabled"
        ),
      "the theme selector is disabled on a static bundle (no daemon to apply it)",
    )
  }

  @Test
  fun `trust badge renders trusted and unverified variants and is absent for a live module`() {
    val trusted = ServeWeb.landingPage(moduleLabel, previews, token, trust = "branch:repo@b")
    assertFalse(trusted.contains("class=\"cp-badge"), "trusted catalogs carry no badge")

    val unverified = ServeWeb.viewerPage(previews.first(), token, trust = "unverified")
    assertTrue(unverified.contains("cp-badge--unverified"), "expected an unverified badge")

    // A live daemon-backed module carries no trust verdict → no badge element.
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
      pairedHtml.contains("id=\"cp-catalog-theme-bar\""),
      "a theme-paired catalog shows the Light/Dark toggle",
    )
    // Two components × two themes → two swap cards, each carrying both themes' render.
    assertEquals(
      2,
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").findAll(pairedHtml).count(),
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
        .contains("id=\"cp-catalog-theme-bar\""),
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
      ServeWeb.landingPage("x", darkOnly, token).contains("id=\"cp-catalog-theme-bar\""),
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
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").findAll(html).count(),
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

  /**
   * Compare every page golden against what `ServeWeb` renders now, and report **all** of the drift
   * in one failure rather than aborting on the first mismatch.
   *
   * Asserting per fixture (the previous shape) surfaced one file at a time and, in the console log
   * CI prints, only a line number inside the helper — so a run that moved twenty pages looked
   * identical to one that moved a single unrelated page, and the reported line pointed at whatever
   * assertion happened to sit there. That is what makes this check easy to regenerate past instead
   * of read (#3442): the output never says what actually moved.
   *
   * The message names each drifted fixture with the first line that differs, which distinguishes
   * the two causes that look the same from the outside — a deliberate `ServeWeb` change whose
   * goldens want regenerating (drift concentrated in the pages you touched) versus goldens
   * regenerated on a branch *before* merging `main`, where `main` then changed the markup for
   * everything (drift across unrelated pages, on a line nobody on the branch edited).
   */
  /**
   * The committed unfurl-card rasters still match what [ServeSocialCard] draws today.
   *
   * Compared with a tolerance rather than by bytes — see [meanPixelDifference] for why an exact
   * comparison would fail across JDK builds for a reason nobody could act on. The threshold is
   * generous against antialiasing noise and nowhere near what a real change costs: moving the
   * headline, changing a colour, or dropping a thumbnail all shift whole regions.
   */
  private fun assertUnfurlCardsInSync(
    pagesDir: File,
    cards: List<Pair<String, ServeSocialCard.Card>>,
  ) {
    val problems = cards.mapNotNull { (name, card) ->
      val file = File(pagesDir, name)
      if (!file.isFile) return@mapNotNull "$name: missing"
      val committed = ImageIO.read(file) ?: return@mapNotNull "$name: not a readable PNG"
      val drawn = ImageIO.read(java.io.ByteArrayInputStream(card.bytes))!!
      val difference = meanPixelDifference(committed, drawn)
      if (difference <= UNFURL_CARD_TOLERANCE) null
      else
        "$name: differs from the drawn card (mean channel difference " +
          "${"%.2f".format(difference)} > $UNFURL_CARD_TOLERANCE)"
    }
    if (problems.isEmpty()) return
    fail(
      "the committed link-unfurl cards are out of sync with ServeSocialCard:\n  " +
        problems.joinToString("\n  ") +
        "\n\nRegenerate with UPDATE_SERVE_WEB_FIXTURES=true — and look at the result, because " +
        "these are what a shared link shows in Slack, iMessage and search results."
    )
  }

  private fun assertGoldensInSync(pagesDir: File, goldens: List<Pair<String, String>>) {
    // Every page loads at least one hashed asset, so a golden without the placeholder means
    // `stableAssetHrefs` stopped matching what `ServeWeb` emits — the URL shape changed, or the
    // digest format did. Say so directly instead of letting it surface as 36 files of "drift",
    // which is the failure this normalisation exists to stop being noise.
    val unnormalised =
      goldens
        .filterNot { (name, html) -> name in ASSETLESS_GOLDENS || STABLE_ASSET_PREFIX in html }
        .map { it.first }
    if (unnormalised.isNotEmpty()) {
      fail(
        "no `$STABLE_ASSET_PREFIX` href in: ${unnormalised.joinToString(", ")}.\n" +
          "ServeWeb's asset URLs no longer match the shape ServeWebFixtureTest normalises " +
          "(`$ASSET_HREF_PATTERN`). Update stableAssetHrefs to match ServeWebAssets.href, or the " +
          "goldens will start pinning real content hashes again and every asset edit will drift " +
          "every page."
      )
    }
    val missing = goldens.filterNot { (name, _) -> File(pagesDir, name).isFile }.map { it.first }
    val drifted =
      goldens
        .filter { (name, _) -> File(pagesDir, name).isFile }
        .mapNotNull { (name, rendered) ->
          val golden = File(pagesDir, name).readText()
          if (golden == rendered) null else name to firstDifference(golden, rendered)
        }
    if (missing.isEmpty() && drifted.isEmpty()) return

    val report = buildString {
      append("serve web page fixtures are out of sync with ServeWeb")
      append(" (${drifted.size} stale, ${missing.size} missing of ${goldens.size}).")
      if (missing.isNotEmpty()) append("\n  missing: ${missing.joinToString(", ")}")
      drifted.forEach { (name, where) -> append("\n  $name: $where") }
      append(
        "\n\nRegenerate with UPDATE_SERVE_WEB_FIXTURES=true — but read the list first. Drift across " +
          "pages this branch never touched usually means the goldens were regenerated before " +
          "merging main and main has since changed the markup, not that ServeWeb is broken; " +
          "re-merge and regenerate again rather than assuming the check is flaky."
      )
    }
    fail(report)
  }

  /**
   * Replace the cache-busting content hash in every asset href with a constant.
   *
   * [ServeWebAssets.href] builds `/assets/serve/<size>-<sha256 prefix>/<file>`, so editing any one
   * of the eleven CSS/JS assets changes a hash that up to **36** goldens embed. That drift is pure
   * noise: the hash is not a surface anyone reviews, it cannot appear in a screenshot, and the
   * preview-harness deliberately ignores it — both its static server and the Playwright routes
   * match these URLs by basename precisely because "the hash is cache-busting and changes whenever
   * the asset does". What it did instead was fail this test on every branch that touched
   * `viewer.js`, and twice reach `main` red (#3446, #3456) when a merge landed the asset without
   * the regeneration. Worse, it trained the reflex the failure message argues against: 36 files of
   * drift you did not cause looks exactly like the "regenerated before merging main" case, so the
   * honest response and the lazy one are the same command.
   *
   * Pinning it here is the same move this test already makes for the server version ([version] =
   * `0.0.0-fixture`, so a release does not churn the goldens) and for the fixed provenance date —
   * hold the volatile-but-uninteresting field constant so the diff only ever shows markup.
   *
   * This does not weaken the guard. The regex matches only the versioned form, so if `ServeWeb`
   * ever stopped emitting one the rendered text would pass through unchanged and
   * [assertGoldensInSync] would fail on the missing placeholder rather than silently accepting a
   * broken URL. Production still serves the real hash: [ServeHttpServer] 404s a mismatched version,
   * and nothing about that path changes here.
   */
  private fun stableAssetHrefs(html: String): String =
    ASSET_HREF_PATTERN.replace(html, STABLE_ASSET_PREFIX)

  /** `line N: golden … | rendered …` for the first line where the two texts diverge. */
  private fun firstDifference(golden: String, rendered: String): String {
    val a = golden.lines()
    val b = rendered.lines()
    val index = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
    if (index == null) return "differs only in trailing newline"
    fun show(line: String?) = line?.trim()?.take(120) ?: "<end of file>"
    return "line ${index + 1}: golden ${show(a.getOrNull(index))} | rendered " +
      show(b.getOrNull(index))
  }

  /**
   * A fixed, phone-shaped placeholder the harness serves for the daemon's `/render/<id>.png`
   * endpoint (which has no backend in CI). Gives every preview tile a realistic size so the
   * captured layout doesn't collapse on broken images. Deterministic so it never churns the visual
   * diff.
   *
   * Deliberately **font-free**: it used to draw the word "preview" with `Font("SansSerif", …)`,
   * which resolves to whatever font files the host JDK/OS maps that logical family to, so the
   * committed PNG was a function of the machine that last regenerated it and churned the diff for
   * anyone else. The label is now a geometric stand-in for a rendered screen — the same shapes
   * [renderPlaceholderSvg] uses for its vector counterpart — making the bytes a pure function of
   * this code.
   */
  // --- The link-unfurl card as a captured visual surface ----------------------------------------

  /**
   * The two shapes of unfurl card ([ServeSocialCard]) this server draws, named for their fixture
   * files: the front door's (a shelf of catalogs) and a single catalog landing's.
   *
   * Composed from the harness's own committed placeholder artwork rather than a real render, for
   * the same reason every other fixture is: the picture has to be identical on every machine that
   * regenerates it. [placeholderWatchPng] exists so the front door's card exercises the *two*-hero
   * shelf with artwork of differing aspect — a phone beside a square face — which is the layout
   * rule most likely to break silently.
   */
  private fun socialCardFixtures(): List<Pair<String, ServeSocialCard.Card>> {
    val cards = ServeSocialCard()
    val phone = ServeHeroImages.Hero(placeholderPng(), "phone.png", "\"phone\"", 200, 420)
    val watch = ServeHeroImages.Hero(placeholderWatchPng(), "watch.png", "\"watch\"", 240, 240)
    val systems =
      listOf(
        ServeWeb.HomeSystem(
          system = "compose-m3",
          title = "Compose Material 3",
          subtitle = null,
          previewCount = 42,
          trust = null,
          heroPreviewId = null,
        ),
        ServeWeb.HomeSystem(
          system = "wear-m3",
          title = "Wear Compose Material 3",
          subtitle = null,
          previewCount = 18,
          trust = null,
          heroPreviewId = null,
        ),
      )
    return listOfNotNull(
      cards
        .cardFor(
          ServeSocialCard.Spec(
            title = ServeWeb.HOME_TITLE,
            subtitle = ServeWeb.homeCardSubtitle(systems),
            heroes = listOf(phone, watch),
          )
        )
        ?.let { "_social-card-home.png" to it },
      cards
        .cardFor(
          ServeSocialCard.Spec(
            title = "Wear Compose Material 3",
            subtitle = ServeWeb.catalogCardSubtitle(18),
            heroes = listOf(watch),
          )
        )
        ?.let { "_social-card-catalog.png" to it },
    )
  }

  /**
   * A page whose whole content is those cards at 1:1, so the harness screenshots them and the
   * visual-diff bot comments on any change to the card's layout, palette or type — the same
   * automatic coverage every other serve surface gets.
   *
   * Hand-written rather than rendered by [ServeWeb], because the card is not a page: it is a raster
   * served off `/social/`, and the only way to put a raster in front of a DOM screenshotter is to
   * frame it in one. The frame is deliberately plain and identical in both themes — the card itself
   * is always dark (an unfurl raster has no `prefers-color-scheme`), so a themed frame would imply
   * a variation that does not exist.
   */
  private fun socialCardPage(cards: List<Pair<String, ServeSocialCard.Card>>): String {
    val figures =
      cards.joinToString("\n") { (file, card) ->
        """
        <figure>
          <img src="$file" width="${card.width}" height="${card.height}" alt="Unfurl card">
          <figcaption>$file — ${card.width}×${card.height}</figcaption>
        </figure>
        """
          .trimIndent()
          .prependIndent("    ")
      }
    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Link unfurl cards — compose-preview</title>
        <style>
          body { margin: 0; padding: 32px; background: #6e6a75; color: #ffffff;
            font: 14px/1.4 system-ui, sans-serif; display: grid; gap: 32px; justify-items: start; }
          figure { margin: 0; display: grid; gap: 8px; }
          img { display: block; max-width: 100%; height: auto; border-radius: 12px; }
          figcaption { font-variant-numeric: tabular-nums; opacity: 0.85; }
        </style>
      </head>
      <body>
$figures
      </body>
    </html>
    """
      .trimIndent()
  }

  /**
   * Mean absolute per-channel difference between two images, or [Double.MAX_VALUE] when they aren't
   * even the same size.
   *
   * The card goldens are compared with a tolerance rather than byte-for-byte, unlike the HTML ones.
   * They are *rasterized text*, and font hinting and antialiasing differ slightly between JDK
   * builds — an exact comparison would fail on a contributor's machine for a reason no one could
   * act on. A layout, palette or copy change moves whole regions and clears this threshold by
   * orders of magnitude, so the drift guard still does its job.
   */
  private fun meanPixelDifference(a: BufferedImage, b: BufferedImage): Double {
    if (a.width != b.width || a.height != b.height) return Double.MAX_VALUE
    var total = 0L
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val p = a.getRGB(x, y)
        val q = b.getRGB(x, y)
        for (shift in intArrayOf(16, 8, 0)) {
          total += Math.abs(((p shr shift) and 0xff) - ((q shr shift) and 0xff)).toLong()
        }
      }
    }
    return total.toDouble() / (a.width.toLong() * a.height * 3)
  }

  private fun placeholderPng(): ByteArray {
    val file = File.createTempFile("placeholder", ".png")
    try {
      writePlaceholderPng(file)
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  /** A square, watch-shaped companion to [writePlaceholderPng], in the same flat M3 style. */
  private fun placeholderWatchPng(): ByteArray {
    val size = 240
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x14, 0x12, 0x18)
    g.fillRect(0, 0, size, size)
    g.color = Color(0x21, 0x1F, 0x26)
    g.fill(Ellipse2D.Float(8f, 8f, 224f, 224f))
    g.color = Color(0xD0, 0xBC, 0xFF)
    g.fill(RoundRectangle2D.Float(70f, 60f, 100f, 18f, 18f, 18f))
    g.color = Color(0xCA, 0xC4, 0xD0)
    g.fill(RoundRectangle2D.Float(56f, 96f, 128f, 14f, 14f, 14f))
    g.fill(RoundRectangle2D.Float(66f, 122f, 108f, 14f, 14f, 14f))
    g.color = Color(0x4F, 0x37, 0x8B)
    g.fill(RoundRectangle2D.Float(78f, 154f, 84f, 32f, 32f, 32f))
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  private fun writePlaceholderPng(file: File) {
    val w = 200
    val h = 420
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint =
      GradientPaint(0f, 0f, Color(0xCF, 0xD8, 0xFF), 0f, h.toFloat(), Color(0x9A, 0xA7, 0xE6))
    g.fillRect(0, 0, w, h)
    // App bar, title line, hero card, two list rows, FAB — mirrors the SVG placeholder's layout,
    // scaled to this tile's 200×420 (the SVG is 200×400).
    g.color = Color(0x67, 0x50, 0xA4)
    g.fill(RoundRectangle2D.Float(18f, 26f, 164f, 28f, 28f, 28f))
    g.color = Color(0x79, 0x74, 0x7E)
    g.fill(RoundRectangle2D.Float(18f, 76f, 112f, 14f, 14f, 14f))
    g.color = Color(0xE8, 0xDE, 0xF8)
    g.fill(RoundRectangle2D.Float(18f, 104f, 164f, 90f, 32f, 32f))
    g.color = Color(0xFF, 0xFF, 0xFF)
    g.fill(RoundRectangle2D.Float(18f, 212f, 164f, 60f, 32f, 32f))
    g.fill(RoundRectangle2D.Float(18f, 292f, 164f, 60f, 32f, 32f))
    g.color = Color(0x67, 0x50, 0xA4)
    g.fill(Ellipse2D.Float(88f, 374f, 24f, 24f))
    g.dispose()
    ImageIO.write(img, "png", file)
  }

  /** Deterministic vector counterpart used by the format-comparison page fixture. */
  private fun renderPlaceholderSvg(): String =
    """
    <svg xmlns="http://www.w3.org/2000/svg" width="200" height="400" viewBox="0 0 200 400">
      <rect width="200" height="400" rx="24" fill="#f2f0f7"/>
      <rect x="18" y="24" width="164" height="28" rx="14" fill="#6750a4"/>
      <rect x="18" y="72" width="112" height="14" rx="7" fill="#79747e"/>
      <rect x="18" y="98" width="164" height="86" rx="16" fill="#e8def8"/>
      <rect x="18" y="202" width="164" height="58" rx="16" fill="#ffffff"/>
      <rect x="18" y="278" width="164" height="58" rx="16" fill="#ffffff"/>
      <circle cx="100" cy="368" r="12" fill="#6750a4"/>
    </svg>
    """
      .trimIndent()

  private companion object {
    /** What the goldens carry in place of a real content hash. See [stableAssetHrefs]. */
    const val STABLE_ASSET_PREFIX = "/assets/serve/fixture/"

    /**
     * Goldens that legitimately load none of the server's hashed assets, and so are exempt from the
     * normalisation check above. An explicit list rather than a "no assets ⇒ fine" rule, because
     * the whole point of that check is to notice when a *page* stops matching the URL shape
     * [stableAssetHrefs] rewrites.
     *
     * Only the unfurl-card frame is here: it is not a served page at all, just a `<figure>` around
     * the committed card rasters so the harness screenshots them (see [socialCardPage]).
     */
    val ASSETLESS_GOLDENS = setOf("serve-social-card.html")

    /**
     * Mean per-channel difference (0..255) tolerated between a committed unfurl card and a freshly
     * drawn one. Sized to absorb font-rasterization differences between JDK builds — those move a
     * fraction of a level averaged over 756,000 pixels — while a layout or palette change moves
     * whole regions and lands orders of magnitude above it.
     */
    const val UNFURL_CARD_TOLERANCE = 1.5

    /**
     * `/assets/serve/<size-hex>-<16 hex digits>/` — the exact shape [ServeWebAssets.href] builds
     * from an asset's byte length and SHA-256 prefix. Deliberately narrow: a looser pattern
     * (`[^/]+`) would keep matching if that scheme changed, and the goldens would go on looking
     * normalised while pinning something else.
     */
    val ASSET_HREF_PATTERN = Regex("""/assets/serve/[0-9a-f]+-[0-9a-f]{16}/""")
  }
}
