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

    if (update) {
      pagesDir.mkdirs()
      File(pagesDir, "serve-landing.html").writeText(landing)
      File(pagesDir, "serve-landing-public.html").writeText(landingPublic)
      File(pagesDir, "serve-viewer.html").writeText(viewer)
      File(pagesDir, "serve-viewer-wasm.html").writeText(wasmViewer)
      writePlaceholderPng(File(pagesDir, "_render-placeholder.png"))
      return
    }

    assertGolden(File(pagesDir, "serve-landing.html"), landing)
    assertGolden(File(pagesDir, "serve-landing-public.html"), landingPublic)
    assertGolden(File(pagesDir, "serve-viewer.html"), viewer)
    assertGolden(File(pagesDir, "serve-viewer-wasm.html"), wasmViewer)
    assertTrue(
      File(pagesDir, "_render-placeholder.png").isFile,
      "missing _render-placeholder.png — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
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

    // No wasmSrc → snapshot viewer is unchanged: no toggle, no iframe element.
    val plain = ServeWeb.viewerPage(card, token)
    assertTrue(!plain.contains("Run in browser (Wasm)"))
    assertTrue(!plain.contains("id=\"cp-wasm\""))
  }

  @Test
  fun `landing lists served catalogs as session nav links, marking the current one`() {
    // Default session (sessionId null): every catalog is a link to its ?session=.
    val front =
      ServeWeb.landingPage(moduleLabel, previews, token, catalogs = listOf("compose-m3", "wear-m3"))
    assertTrue(front.contains("class=\"cp-systems\""), "expected the design-systems nav")
    assertTrue(
      front.contains("href=\"/?token=$token&session=compose-m3\""),
      "expected a compose-m3 link",
    )
    assertTrue(front.contains("href=\"/?token=$token&session=wear-m3\""), "expected a wear-m3 link")

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
    assertTrue(onCompose.contains("session=wear-m3"), "other catalog stays a link")

    // No catalogs → no nav row.
    assertTrue(!ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-systems\""))
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
    // The Wasm iframe URL builder forwards the honoured params (theme/font scale/locale).
    assertTrue(
      wasmView.contains("u.searchParams.set(\"fontScale\""),
      "font scale forwarded to Wasm",
    )
    assertTrue(wasmView.contains("u.searchParams.set(\"localeTag\""), "locale forwarded to Wasm")

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
