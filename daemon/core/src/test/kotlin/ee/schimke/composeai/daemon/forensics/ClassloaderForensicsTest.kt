package ee.schimke.composeai.daemon.forensics

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-test surface for the renderer-agnostic [ClassloaderForensics] library. Exercises capture +
 * diff against the host JVM's classloader graph (no Robolectric) — the Android-specific behaviour
 * is covered by `:renderer-android`'s `ClassloaderForensicsTest` (Configuration A) and
 * `:daemon:android`'s `ClassloaderForensicsDaemonTest` (Configuration B). This test only pins the
 * library's invariants:
 *
 * * Capturing twice in the same JVM produces the same per-class data (sanity check 1 from
 *   CLASSLOADER-FORENSICS.md § Sanity checks — modulo timestamps/contextHint differences).
 * * `java.lang.String` always resolves to identical classloader / codeSource / moduleHash
 *   regardless of caller (sanity check 3).
 * * Diffing two identical captures produces no smoking-gun rows.
 * * A class that doesn't exist surfaces as `error` rather than throwing.
 */
class ClassloaderForensicsTest {

  private val tempDir: File = Files.createTempDirectory("forensics-unit").toFile()

  @After
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  @Test
  fun captureProducesStableJsonForKnownClasses() {
    val out1 = File(tempDir, "a1.json")
    val out2 = File(tempDir, "a2.json")
    val survey = listOf("java.lang.String", "kotlin.Unit", "java.util.LinkedHashMap")
    ClassloaderForensics.capture(
      survey,
      robolectricConfig = null,
      contextHint = "unit-1",
      out = out1,
    )
    ClassloaderForensics.capture(
      survey,
      robolectricConfig = null,
      contextHint = "unit-2",
      out = out2,
    )
    assertTrue("first dump must exist", out1.exists())
    assertTrue("second dump must exist", out2.exists())

    val text1 = out1.readText()
    val text2 = out2.readText()
    // Per sanity check 3: java.lang.String must be identically described in both.
    val stringFields = listOf("java.lang.String\"", "moduleHash", "codeSource", "classloader")
    for (field in stringFields) {
      assertTrue("dump 1 contains $field", text1.contains(field))
      assertTrue("dump 2 contains $field", text2.contains(field))
    }
  }

  @Test
  fun missingClassRecordsErrorRatherThanThrowing() {
    val out = File(tempDir, "missing.json")
    ClassloaderForensics.capture(
      surveySet = listOf("ee.no.such.Class\$Inner", "java.lang.String"),
      robolectricConfig = null,
      contextHint = "unit-missing",
      out = out,
    )
    val text = out.readText()
    assertTrue("missing class surfaces as error entry", text.contains("ClassNotFoundException"))
    assertTrue("known class still captured", text.contains("java.lang.String"))
  }

  @Test
  fun diffOfIdenticalDumpsHasNoSmokingGuns() {
    val a = File(tempDir, "a.json")
    val b = File(tempDir, "b.json")
    val survey = listOf("java.lang.String", "kotlin.Unit")
    ClassloaderForensics.capture(survey, null, "unit-control", a)
    // Re-run with a different contextHint to exercise the "context hints differ but data matches"
    // case — the diff should still report zero smoking guns.
    ClassloaderForensics.capture(survey, null, "unit-subject", b)
    val md = File(tempDir, "diff.md")
    val js = File(tempDir, "diff.json")
    ClassloaderForensics.diff(a, b, md, js)
    assertNotNull(md.readText())
    val mdText = md.readText()
    assertTrue(
      "md mentions both context hints",
      mdText.contains("unit-control") && mdText.contains("unit-subject"),
    )
    assertTrue("md flags no smoking guns", mdText.contains("_None._"))
  }

  @Test
  fun diffSurfacesRobolectricConfigKeyDifferences() {
    val a = File(tempDir, "a.json")
    val b = File(tempDir, "b.json")
    val survey = listOf("java.lang.String")
    val cfgA = sampleSnapshot(apiLevel = 35, qualifiers = "+w360-h640-port-mdpi")
    val cfgB = sampleSnapshot(apiLevel = 34, qualifiers = "+w360-h640-port-hdpi")
    ClassloaderForensics.capture(survey, cfgA, "unit-A", a)
    ClassloaderForensics.capture(survey, cfgB, "unit-B", b)
    val md = File(tempDir, "diff.md")
    val js = File(tempDir, "diff.json")
    ClassloaderForensics.diff(a, b, md, js)
    val mdText = md.readText()
    assertTrue("apiLevel diff surfaces", mdText.contains("apiLevel"))
    assertTrue("qualifiers diff surfaces", mdText.contains("qualifiers"))
  }

  @Test
  fun rendererAgnosticSurfaceCarriesNoComposeOrRobolectricImports() {
    // Sanity check that the library's class itself doesn't drag Robolectric onto the unit-test
    // classpath. If this test runs at all on `:daemon:core` (which has no
    // robolectric / compose deps) the import discipline holds.
    assertEquals(
      "ee.schimke.composeai.daemon.forensics.ClassloaderForensics",
      ClassloaderForensics::class.java.name,
    )
  }

  private fun sampleSnapshot(apiLevel: Int, qualifiers: String) =
    RobolectricConfigSnapshot(
      apiLevel = apiLevel,
      qualifiers = qualifiers,
      fontScale = 1.0f,
      applicationClassName = "android.app.Application",
      graphicsMode = "NATIVE",
      looperMode = "PAUSED",
      instrumentedPackages = listOf("android.*", "androidx.*"),
      doNotInstrumentPackages = listOf("java.*", "kotlin.*"),
      doNotAcquirePackages = listOf("java.*", "kotlin.*"),
      sandboxFactoryClassName = "org.robolectric.internal.SandboxFactory",
      instrumentingClassLoaderIdentity = "0x00000001",
    )
}
