package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the a11y strategy impl. The Gradle Tooling API path (the renders that produce
 * `accessibility.json`) isn't exercised here — we point [A11yReportRenderer.load] at a synthetic
 * on-disk JSON and verify the surrounding contract: it reads via the manifest's v2/v1 pointer,
 * populates [PreviewResult.a11yFindings], reports `hasData`, and prints what `A11yCommand` used to
 * print directly.
 */
class A11yReportRendererTest {

  private lateinit var workspace: File
  private lateinit var moduleDir: File
  private lateinit var capturedOut: ByteArrayOutputStream
  private var savedOut: PrintStream? = null

  @BeforeTest
  fun setUp() {
    workspace = createTempDirectory("a11y-renderer").toFile()
    moduleDir = workspace.resolve("module").apply { mkdirs() }
    workspace.resolve("module/build/compose-previews").mkdirs()
    capturedOut = ByteArrayOutputStream()
    savedOut = System.out
    System.setOut(PrintStream(capturedOut))
  }

  @AfterTest
  fun tearDown() {
    savedOut?.let { System.setOut(it) }
    workspace.deleteRecursively()
  }

  private fun writeReport(content: String) {
    moduleDir.resolve("build/compose-previews/accessibility.json").writeText(content)
  }

  private fun module(): PreviewModule = PreviewModule(gradlePath = "sample", projectDir = moduleDir)

  private fun manifest(reportsView: Map<String, String> = mapOf("a11y" to "accessibility.json")) =
    PreviewManifest(
      module = "sample",
      variant = "debug",
      previews = emptyList(),
      dataExtensionReports = reportsView,
    )

  private fun previewResult(id: String) =
    PreviewResult(
      id = id,
      module = "sample",
      functionName = id,
      className = "com.example.${id}Kt",
      sourceFile = null,
      params = PreviewParams(),
      captures = listOf(CaptureResult()),
    )

  @Test
  fun `load returns enabled modules and annotate fills findings`() {
    writeReport(
      """
      {
        "module": "sample",
        "entries": [
          {
            "previewId": "Foo",
            "findings": [
              {"level": "ERROR", "type": "TouchTargetSize",
               "message": "24x24 below 48dp", "viewDescription": "Button"}
            ]
          }
        ]
      }
      """
        .trimIndent()
    )

    val renderer = A11yReportRenderer()
    val enabled = renderer.load(listOf(module() to manifest()), verbose = false)
    assertEquals(setOf("sample"), enabled)

    val foo = renderer.annotate(previewResult("Foo"), module())
    assertEquals(1, foo.a11yFindings?.size)
    assertEquals("ERROR", foo.a11yFindings?.first()?.level)
    assertTrue(renderer.hasData(foo))

    // Same module, different preview that has no entry: a11yFindings is set to an empty list
    // (the "checks ran, found nothing" signal) rather than null.
    val bar = renderer.annotate(previewResult("Bar"), module())
    assertEquals(emptyList(), bar.a11yFindings)
    assertTrue(renderer.hasData(bar))
  }

  @Test
  fun `module without a11y pointer is not annotated`() {
    val renderer = A11yReportRenderer()
    renderer.load(listOf(module() to manifest(reportsView = emptyMap())), verbose = false)

    val result = renderer.annotate(previewResult("Foo"), module())
    assertNull(result.a11yFindings)
    assertFalse(renderer.hasData(result))
  }

  @Test
  fun `printAll renders findings count and per-row block`() {
    writeReport(
      """
      {
        "module": "sample",
        "entries": [
          {
            "previewId": "Foo",
            "annotatedPath": "annotated/Foo.a11y.png",
            "findings": [
              {"level": "ERROR", "type": "TouchTargetSize",
               "message": "24x24 below 48dp", "viewDescription": "Button"},
              {"level": "WARNING", "type": "TextContrast",
               "message": "Contrast 3.8:1 below 4.5:1"}
            ]
          }
        ]
      }
      """
        .trimIndent()
    )
    // annotated PNG resolves relative to the report file's parent, so place a file there.
    moduleDir.resolve("build/compose-previews/annotated").mkdirs()
    moduleDir.resolve("build/compose-previews/annotated/Foo.a11y.png").writeText("png")

    val renderer = A11yReportRenderer()
    renderer.load(listOf(module() to manifest()), verbose = false)
    val foo = renderer.annotate(previewResult("Foo"), module())

    renderer.printAll(listOf(foo))
    val output = capturedOut.toString()

    assertTrue("2 accessibility finding(s)" in output, "expected total count, got:\n$output")
    assertTrue("[ERROR] Foo · TouchTargetSize" in output, "missing ERROR row:\n$output")
    assertTrue("[WARNING] Foo · TextContrast" in output, "missing WARNING row:\n$output")
    assertTrue("annotated: " in output, "missing annotated PNG hint:\n$output")
    // Annotated path should print exactly once per preview, not per finding.
    assertEquals(1, output.lines().count { it.startsWith("      annotated:") })
  }

  @Test
  fun `thresholdExitCode trips on errors and ignores warnings unless requested`() {
    val renderer = A11yReportRenderer()
    val errorRow =
      previewResult("Foo")
        .copy(
          a11yFindings = listOf(AccessibilityFinding(level = "ERROR", type = "X", message = "x"))
        )
    val warningRow =
      previewResult("Bar")
        .copy(
          a11yFindings = listOf(AccessibilityFinding(level = "WARNING", type = "Y", message = "y"))
        )

    assertEquals(2, renderer.thresholdExitCode(listOf(errorRow), failOn = "errors"))
    assertNull(renderer.thresholdExitCode(listOf(warningRow), failOn = "errors"))
    assertEquals(2, renderer.thresholdExitCode(listOf(warningRow), failOn = "warnings"))
    assertNull(renderer.thresholdExitCode(listOf(errorRow), failOn = "none"))
    assertNull(renderer.thresholdExitCode(listOf(errorRow), failOn = null))
    assertEquals(
      EXIT_UNKNOWN_FAIL_ON,
      renderer.thresholdExitCode(listOf(errorRow), failOn = "bogus"),
    )
  }
}
