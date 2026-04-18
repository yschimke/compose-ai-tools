package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorReportSerializationTest {
    @Test fun `round-trips a report with all fields populated`() {
        val report = DoctorReport(
            schema = "compose-preview-doctor/v1",
            pluginVersion = "0.4.0",
            overall = "warning",
            checks = listOf(
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
                    remediation = DoctorRemediation(
                        summary = "add it",
                        commands = listOf("testImplementation(\"x:y\")"),
                        docs = "https://example.com",
                    ),
                ),
            ),
            summary = DoctorSummary(ok = 1, warning = 0, error = 1, skipped = 0),
        )

        val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }
        val text = json.encodeToString(DoctorReport.serializer(), report)
        val parsed = json.decodeFromString(DoctorReport.serializer(), text)
        assertEquals(report, parsed)

        // Schema contract — consumers should be able to grep for this
        // without parsing the whole document.
        assertTrue("\"schema\": \"compose-preview-doctor/v1\"" in text)
    }

    @Test fun `default schema field stays at v1`() {
        // Bump this test intentionally when rolling to v2 — guards against
        // accidentally breaking the CLI→agent contract.
        val report = DoctorReport(
            pluginVersion = "0.0.0",
            overall = "ok",
            checks = emptyList(),
            summary = DoctorSummary(0, 0, 0, 0),
        )
        assertEquals("compose-preview-doctor/v1", report.schema)
    }
}
