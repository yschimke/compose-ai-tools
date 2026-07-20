package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.FakeRenderSession
import ee.schimke.composeai.cli.serve.ServePreview
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two seams `bundle render --knob` adds to [BundleCommand]:
 * - [parseKnobOverrides] — turning repeatable `--knob key=value` flags into the
 *   `PreviewOverrides.namedOverrides` theme seed, and
 * - [renderPreviewsToDir] — writing one themed PNG per preview to disk, driven here against a fake
 *   [ee.schimke.composeai.render.session.RenderSession] so no daemon subprocess or native renderer
 *   is needed (the same skiko-free pattern as `ServeRenderHostTest`).
 *
 * The materialize + daemon-launch orchestration around them (`renderBundleWithOverrides`) needs a
 * real desktop/android bundle plus the `:cli:installDist` sidecars, so it is exercised by the
 * self-skipping `ServeBundleDaemonTest` integration test rather than here.
 */
class BundleRenderKnobTest {

  private fun tempDir(prefix: String): File =
    Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private fun s(value: String) = PreviewOverrideValue.StringValue(value)

  @Test
  fun `parseKnobOverrides splits on the first equals so a serialized value keeps its own separators`() {
    // A wire-form theme.colors blob carries its own `=`, `,` and `;` — the split must not shred it.
    val blob = "scheme:l=primary:FF6750A4,secondary:FF625B71;d=primary:FFD0BCFF"
    val args = listOf("render", "bundle.png", "--knob", "theme.colors=$blob")
    assertEquals(mapOf("theme.colors" to s(blob)), parseKnobOverrides(args))
  }

  @Test
  fun `parseKnobOverrides is repeatable across flags and a repeated key takes its last value`() {
    val args =
      listOf(
        "--knob",
        "theme.colors=teal",
        "--knob",
        "theme.font=Roboto",
        // attached `--knob=` form, and a second theme.colors that must win.
        "--knob=theme.colors=indigo",
      )
    assertEquals(
      mapOf("theme.colors" to s("indigo"), "theme.font" to s("Roboto")),
      parseKnobOverrides(args),
    )
  }

  @Test
  fun `parseKnobOverrides drops entries with no equals or a blank key`() {
    val args =
      listOf(
        "--knob",
        "noequals", // no '=' at all
        "--knob",
        "=valueonly", // '=' at index 0 → empty key
        "--knob",
        "   =blankkey", // key is all whitespace
        "--knob",
        "good=v", // the one keeper
      )
    assertEquals(mapOf("good" to s("v")), parseKnobOverrides(args))
  }

  @Test
  fun `parseKnobOverrides is empty when no --knob flags are present`() {
    assertEquals(emptyMap(), parseKnobOverrides(listOf("render", "bundle.png", "-o", "out")))
  }

  @Test
  fun `sanitizeBundleRenderName keeps safe chars and replaces the rest with underscore`() {
    // Preview ids can carry a `@Preview(name = "Phone, dark")` suffix and package dots.
    assertEquals(
      "com.example.Btn_Phone__dark_",
      sanitizeBundleRenderName("com.example.Btn/Phone, dark:"),
    )
  }

  @Test
  fun `renderPreviewsToDir writes one themed PNG per preview and reports no failures`() {
    val session = FakeRenderSession(tempDir("render-root"))
    val outDir = tempDir("render-out")
    val host =
      ServeRenderHost(
        session = session,
        previews =
          listOf(
            ServePreview("com.example.Filled", "Filled"),
            ServePreview("com.example.Tonal", "Tonal"),
          ),
        renderTimeoutSeconds = 30,
      )
    val seed =
      PreviewOverrides(namedOverrides = mapOf("theme.colors" to s("scheme:l=primary:FF00695C")))

    val failures = host.use { renderPreviewsToDir(it, outDir, seed, log = {}) }

    assertEquals(emptyList(), failures)
    assertEquals(2, session.renderCount.get(), "each preview renders once")
    // Both files exist and carry the render output bytes (not an empty stub).
    val filled = File(outDir, "com.example.Filled.png")
    val tonal = File(outDir, "com.example.Tonal.png")
    assertTrue(filled.exists() && tonal.exists(), "one PNG written per preview")
    assertEquals("png:null:null:null", filled.readText(), "the render output is written verbatim")
  }

  @Test
  fun `renderPreviewsToDir renders the raw id but writes under a sanitized filename`() {
    // The id given to the daemon must be the raw one (else it 404s); only the FILENAME is
    // sanitized.
    val session = FakeRenderSession(tempDir("render-root"))
    val outDir = tempDir("render-out")
    val host =
      ServeRenderHost(
        session = session,
        previews = listOf(ServePreview("com.example.Btn:Phone, dark", "Btn")),
        renderTimeoutSeconds = 30,
      )

    val failures = host.use { renderPreviewsToDir(it, outDir, PreviewOverrides(), log = {}) }

    assertEquals(emptyList(), failures, "the raw id must resolve — a swap to the sanitized id 404s")
    assertTrue(
      File(outDir, "com.example.Btn_Phone__dark.png").exists(),
      "the file is named by the sanitized id",
    )
  }

  @Test
  fun `renderPreviewsToDir reports a rejected render as a failure and writes no file`() {
    val session = FakeRenderSession(tempDir("render-root"), rejectAll = true)
    val outDir = tempDir("render-out")
    val host =
      ServeRenderHost(
        session = session,
        previews = listOf(ServePreview("com.example.Filled", "Filled")),
        renderTimeoutSeconds = 30,
      )

    val failures = host.use { renderPreviewsToDir(it, outDir, PreviewOverrides(), log = {}) }

    assertEquals(1, failures.size, "the rejected preview is reported")
    assertTrue(failures.single().startsWith("com.example.Filled"), "failure names the preview")
    assertFalse(
      File(outDir, "com.example.Filled.png").exists(),
      "a failed render must not leave a PNG behind",
    )
  }

  @Test
  fun `renderPreviewsToDir with withSvg writes a re-themed SVG beside each PNG`() {
    // A figma-svg-capable backend (FakeRenderSession default) → --svg exports the editable vector
    // beside the raster, the pair `bundle repack` swaps into previews/<id>.png + .figma.svg.
    val session = FakeRenderSession(tempDir("render-root"))
    val outDir = tempDir("render-out")
    val host =
      ServeRenderHost(
        session = session,
        previews =
          listOf(
            ServePreview("com.example.Filled", "Filled"),
            ServePreview("com.example.Tonal", "Tonal"),
          ),
        renderTimeoutSeconds = 30,
      )
    val seed =
      PreviewOverrides(namedOverrides = mapOf("theme.colors" to s("scheme:l=primary:FF00695C")))

    val failures = host.use { renderPreviewsToDir(it, outDir, seed, withSvg = true, log = {}) }

    assertEquals(emptyList(), failures)
    for (id in listOf("com.example.Filled", "com.example.Tonal")) {
      assertTrue(File(outDir, "$id.png").exists(), "$id PNG written")
      assertTrue(File(outDir, "$id.svg").exists(), "$id SVG written beside the PNG")
    }
  }

  @Test
  fun `renderPreviewsToDir with withSvg but no vector export writes only PNG and still succeeds`() {
    // A backend without the compose/figma-svg product reports the kinds as unknown → hasSvgExport
    // false. --svg is best-effort: the PNG re-theme lands, no .svg is written, and it is NOT a
    // failure.
    val session = FakeRenderSession(tempDir("render-root"), figmaSvgAvailable = false)
    val outDir = tempDir("render-out")
    val host =
      ServeRenderHost(
        session = session,
        previews = listOf(ServePreview("com.example.Filled", "Filled")),
        renderTimeoutSeconds = 30,
      )

    val failures = host.use {
      renderPreviewsToDir(it, outDir, PreviewOverrides(), withSvg = true, log = {})
    }

    assertEquals(emptyList(), failures)
    assertTrue(File(outDir, "com.example.Filled.png").exists(), "PNG still written")
    assertFalse(
      File(outDir, "com.example.Filled.svg").exists(),
      "no SVG when the backend can't export it",
    )
  }

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  @Test
  fun `materializeExternalResources returns null for a self-contained bundle`() {
    assertNull(materializeExternalResources(emptyList(), null, tempDir("ext")))
  }

  @Test
  fun `materializeExternalResources requires a pool when the bundle externalized resources`() {
    val res = BundleReader.ExternalResource("fonts/Roboto-Regular.ttf", sha256(byteArrayOf(1)), 1)
    val e =
      assertFailsWith<IllegalStateException> {
        materializeExternalResources(listOf(res), null, tempDir("ext"))
      }
    assertTrue(e.message!!.contains("--res"), "the error tells the user to pass --res")
  }

  @Test
  fun `materializeExternalResources rehydrates each resource at its recorded classpath path`() {
    val pool = tempDir("pool")
    val dest = tempDir("ext")
    val bytes = "ROBOTO-REGULAR-TTF-BYTES".toByteArray()
    val sha = sha256(bytes)
    File(pool, sha).writeBytes(bytes)
    val res = BundleReader.ExternalResource("fonts/Roboto-Regular.ttf", sha, bytes.size.toLong())

    val out = materializeExternalResources(listOf(res), pool, dest)

    assertEquals(dest, out)
    assertContentEquals(
      bytes,
      File(dest, "fonts/Roboto-Regular.ttf").readBytes(),
      "the font is materialized at its recorded /fonts/… path",
    )
  }

  @Test
  fun `materializeExternalResources fails closed on a missing pool entry`() {
    val res = BundleReader.ExternalResource("fonts/X.ttf", sha256(byteArrayOf(9)), 1)
    val e =
      assertFailsWith<IllegalStateException> {
        materializeExternalResources(listOf(res), tempDir("pool"), tempDir("ext"))
      }
    assertTrue(e.message!!.contains("missing from the pool"))
  }

  @Test
  fun `materializeExternalResources fails closed on a size mismatch`() {
    val pool = tempDir("pool")
    val bytes = "abc".toByteArray()
    val sha = sha256(bytes)
    File(pool, sha).writeBytes(bytes)
    val res = BundleReader.ExternalResource("fonts/X.ttf", sha, 999L) // declared size is wrong
    assertFailsWith<IllegalStateException> {
      materializeExternalResources(listOf(res), pool, tempDir("ext"))
    }
  }

  @Test
  fun `materializeExternalResources fails closed on a sha256 mismatch`() {
    val pool = tempDir("pool")
    val bytes = "real-bytes".toByteArray()
    val declaredSha = sha256("different-bytes".toByteArray())
    File(pool, declaredSha).writeBytes(bytes) // the pool file doesn't hash to its name
    val res = BundleReader.ExternalResource("fonts/X.ttf", declaredSha, bytes.size.toLong())
    assertFailsWith<IllegalStateException> {
      materializeExternalResources(listOf(res), pool, tempDir("ext"))
    }
  }

  @Test
  fun `materializeExternalResources rejects a path escaping the output dir`() {
    val pool = tempDir("pool")
    val bytes = "x".toByteArray()
    val sha = sha256(bytes)
    File(pool, sha).writeBytes(bytes)
    val res = BundleReader.ExternalResource("../evil.ttf", sha, bytes.size.toLong())
    assertFailsWith<IllegalStateException> {
      materializeExternalResources(listOf(res), pool, tempDir("ext"))
    }
  }
}
