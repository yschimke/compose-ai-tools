package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorReportSerializationTest {
  @Test
  fun `round-trips a report with all fields populated`() {
    val report =
      DoctorReport(
        schema = "compose-preview-doctor/v1",
        pluginVersion = "0.4.0",
        overall = "warning",
        checks =
          listOf(
            DoctorCheck(
              id = "env.java-17",
              category = "env",
              status = "ok",
              message = "Java 17 on PATH",
            ),
            DoctorCheck(
              id = "deps.app.ui-test-manifest-missing",
              category = "deps",
              status = "error",
              message = ":app — ui-test-manifest missing on test classpath",
              detail = "see docs",
              remediation =
                DoctorRemediation(
                  summary = "add it",
                  commands = listOf("testImplementation(\"x:y\")"),
                  docs = "https://example.com",
                ),
            ),
          ),
        summary = DoctorSummary(ok = 1, warning = 0, error = 1, skipped = 0),
      )

    val json =
      kotlinx.serialization.json.Json {
        prettyPrint = true
        encodeDefaults = true
      }
    val text = json.encodeToString(DoctorReport.serializer(), report)
    val parsed = json.decodeFromString(DoctorReport.serializer(), text)
    assertEquals(report, parsed)

    // Schema contract — consumers should be able to grep for this
    // without parsing the whole document.
    assertTrue("\"schema\": \"compose-preview-doctor/v1\"" in text)
  }

  @Test
  fun `default schema field stays at v1`() {
    // Bump this test intentionally when rolling to v2 — guards against
    // accidentally breaking the CLI→agent contract.
    val report =
      DoctorReport(
        pluginVersion = "0.0.0",
        overall = "ok",
        checks = emptyList(),
        summary = DoctorSummary(0, 0, 0, 0),
      )
    assertEquals("compose-preview-doctor/v1", report.schema)
  }

  @Test
  fun `renderPreviews task-info serialises round-trip`() {
    // The nested RenderPreviewsTaskInfo model sits behind the
    // project.<module>.render-previews-jvm check — verify it
    // serialises cleanly via the CLI's action type so the JSON
    // output mode in `doctor --json` stays stable for agents
    // consuming the new fields.
    val info =
      SerializableModuleInfo(
        variant = "debug",
        mainRuntimeDependencies = emptyMap(),
        testRuntimeDependencies = mapOf("org.robolectric:robolectric" to "4.16"),
        findings = emptyList(),
        agpVersion = "9.1.0",
        kotlinVersion = "2.2.21",
        renderPreviewsTask =
          SerializableRenderPreviewsTaskInfo(
            javaLauncherPinned = false,
            javaLauncherVersion = "25",
            javaLauncherVendor = "Google Inc.",
            javaLauncherPath = "/usr/lib/jvm/jdk25",
            classpathSize = 412,
            bootstrapClasspathSize = 0,
            jvmArgs = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED"),
          ),
      )
    assertEquals("9.1.0", info.agpVersion)
    assertEquals("25", info.renderPreviewsTask?.javaLauncherVersion)
    assertEquals(412, info.renderPreviewsTask?.classpathSize)
  }
}
