package ee.schimke.composeai.cli.serve

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
    val viewer =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        trust = "unverified",
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
      File(pagesDir, "serve-viewer.html").writeText(viewer)
      File(pagesDir, "serve-viewer-wasm.html").writeText(wasmViewer)
      File(pagesDir, "serve-landing-path.html").writeText(landingPath)
      File(pagesDir, "serve-viewer-path.html").writeText(viewerPath)
      File(pagesDir, "serve-landing-themed.html").writeText(landingThemed)
      writePlaceholderPng(File(pagesDir, "_render-placeholder.png"))
      return
    }

    assertGolden(File(pagesDir, "serve-landing.html"), landing)
    assertGolden(File(pagesDir, "serve-landing-public.html"), landingPublic)
    assertGolden(File(pagesDir, "serve-viewer.html"), viewer)
    assertGolden(File(pagesDir, "serve-viewer-wasm.html"), wasmViewer)
    assertGolden(File(pagesDir, "serve-landing-path.html"), landingPath)
    assertGolden(File(pagesDir, "serve-viewer-path.html"), viewerPath)
    assertGolden(File(pagesDir, "serve-landing-themed.html"), landingThemed)
    assertTrue(
      File(pagesDir, "_render-placeholder.png").isFile,
      "missing _render-placeholder.png — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )

    // The sticky theme toggle appears only for a catalog with per-theme variants, and each themed
    // card is tagged for client-side filtering; a plain component module shows no toggle.
    assertTrue(
      landingThemed.contains("class=\"cp-theme\""),
      "themed catalog shows the theme toggle",
    )
    assertTrue(
      landingThemed.contains("data-card-theme=\"dark\"") &&
        landingThemed.contains("data-card-theme=\"light\""),
      "themed cards are tagged with their baked theme",
    )
    assertTrue(
      landingThemed.contains("localStorage.setItem(\"cp-theme\""),
      "toggle persists the choice to the shared cp-theme key",
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
    assertTrue(withWasm.contains("Run in browser (Wasm)"), "expected the Wasm toggle")
    assertTrue(withWasm.contains("id=\"cp-wasm\""), "expected the Wasm iframe")
    assertTrue(withWasm.contains("data-wasm-src=\"/wasm/compose-m3/?id=card-filled\""))
    assertTrue(withWasm.contains("sandbox=\"allow-scripts\""), "iframe must be sandboxed")
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
    // Guard against re-adding a page-side font preload: the sandboxed iframe's opaque origin gets
    // its own HTTP-cache partition, so nothing this page fetches is reusable inside it. The real
    // prefetch lives in the app's index.html (parallel with the Wasm boot).
    assertFalse(
      withWasm.contains("preloadWasmFonts"),
      "no page-side font preload (cache-partitioned away from the sandboxed iframe)",
    )
    // The in-browser tier can drop the sticker background (component only on the checkerboard).
    assertTrue(
      withWasm.contains("id=\"cp-wasm-bg\"") && withWasm.contains("Component only (no background)"),
      "expected the background toggle",
    )
    assertTrue(withWasm.contains("\"background=off\""), "background knob forwarded to the app")

    // No wasmSrc → snapshot viewer is unchanged: no toggle, no iframe element.
    val plain = ServeWeb.viewerPage(card, token)
    assertTrue(!plain.contains("Run in browser (Wasm)"))
    assertTrue(!plain.contains("id=\"cp-wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm-bg\""))
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
    assertTrue(staticView.contains("value=\"1.0\" disabled"), "font scale disabled")
    assertTrue(staticView.contains("id=\"cp-device\" disabled"), "device disabled")
    assertTrue(staticView.contains("id=\"cp-orientation\" disabled"), "orientation disabled")
    assertTrue(
      staticView.contains("id=\"cp-live\" type=\"checkbox\" disabled"),
      "live stream disabled",
    )
    assertTrue(
      staticView.contains("id=\"cp-uiMode\" disabled"),
      "theme disabled without a Wasm app",
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
    assertTrue(wasmView.contains("id=\"cp-device\" disabled"), "device stays server-only")
    assertTrue(wasmView.contains("id=\"cp-orientation\" disabled"), "orientation stays server-only")
    // The Wasm override-patch builder forwards the honoured params (theme/font scale/locale) to the
    // running app (via postMessage / the initial `#…` fragment), not the iframe query.
    assertTrue(wasmView.contains("\"fontScale=\""), "font scale forwarded to Wasm")
    assertTrue(wasmView.contains("\"localeTag=\""), "locale forwarded to Wasm")
    // On a static snapshot, a wasm-honoured control change auto-enables the Wasm tier (rather than
    // firing a /render the published catalog can't serve), so the control actually takes effect.
    assertTrue(
      wasmView.contains("else if (live.disabled && wasmToggle) {"),
      "static-snapshot wasm controls auto-enable the in-browser tier",
    )

    // Live daemon session (canApplyOverrides = true): everything enabled, no note.
    val liveView = ServeWeb.viewerPage(previews.first(), token, canApplyOverrides = true)
    assertTrue(!liveView.contains("Pre-rendered snapshot"), "no static note on a live session")
    assertTrue(!liveView.contains("value=\"1.0\" disabled"), "font scale enabled on a live session")
    assertTrue(!liveView.contains("id=\"cp-device\" disabled"), "device enabled on a live session")
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
