package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpDoctorTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun newTempDir(): File {
    val dir = Files.createTempDirectory("mcp-doctor-test").toFile()
    tempDirs += dir
    return dir
  }

  private fun writeDescriptor(dir: File, body: String): File {
    val f = File(dir, "daemon-launch.json")
    f.writeText(body)
    return f
  }

  @Test
  fun `missing descriptor reports run-mcp-install`() {
    val state = inspectDescriptor("app", File(newTempDir(), "daemon-launch.json"))
    assertEquals("missing", state.status)
    assertEquals("run-mcp-install", state.verdict)
    assertEquals(listOf("descriptor.present"), state.findings.map { it.id })
  }

  @Test
  fun `corrupt descriptor reports run-mcp-install`() {
    val f = writeDescriptor(newTempDir(), "{ not valid json")
    val state = inspectDescriptor("app", f)
    assertEquals("corrupt", state.status)
    assertEquals("run-mcp-install", state.verdict)
    assertEquals("descriptor.parse", state.findings.single().id)
  }

  @Test
  fun `disabled descriptor reports run-mcp-install`() {
    val f =
      writeDescriptor(
        newTempDir(),
        """{"enabled": false, "schemaVersion": $EXPECTED_DESCRIPTOR_SCHEMA_VERSION}""",
      )
    val state = inspectDescriptor("app", f)
    assertEquals("disabled", state.status)
    assertEquals("run-mcp-install", state.verdict)
    assertTrue(state.findings.any { it.id == "descriptor.enabled" })
  }

  @Test
  fun `schema mismatch reports run-mcp-install`() {
    val bumped = EXPECTED_DESCRIPTOR_SCHEMA_VERSION + 1
    val f = writeDescriptor(newTempDir(), """{"enabled": true, "schemaVersion": $bumped}""")
    val state = inspectDescriptor("app", f)
    assertEquals("stale", state.status)
    assertEquals("run-mcp-install", state.verdict)
    val finding = state.findings.single { it.id == "descriptor.schema" }
    assertTrue(finding.message.contains("schemaVersion=$bumped"))
  }

  @Test
  fun `missing launcher reports run-mcp-install`() {
    val f =
      writeDescriptor(
        newTempDir(),
        """
        {"enabled": true, "schemaVersion": $EXPECTED_DESCRIPTOR_SCHEMA_VERSION,
         "javaLauncher": "/no/such/path/to/java"}
        """
          .trimIndent(),
      )
    val state = inspectDescriptor("app", f)
    assertEquals("run-mcp-install", state.verdict)
    assertNotNull(state.findings.singleOrNull { it.id == "descriptor.launcher" })
  }

  @Test
  fun `missing manifest is a non-blocking warning`() {
    // Build a present, executable launcher so the only finding is the manifest one.
    val dir = newTempDir()
    val launcher = File(dir, "java").apply { writeText("#!/bin/sh\nexit 0\n") }
    launcher.setExecutable(true)
    val f =
      writeDescriptor(
        dir,
        """
        {"enabled": true, "schemaVersion": $EXPECTED_DESCRIPTOR_SCHEMA_VERSION,
         "javaLauncher": "${launcher.absolutePath}",
         "manifestPath": "/no/such/previews.json"}
        """
          .trimIndent(),
      )
    val state = inspectDescriptor("app", f)
    assertEquals("ok", state.verdict)
    assertEquals("ok", state.status)
    val finding = state.findings.single { it.id == "descriptor.manifest" }
    assertEquals("warning", finding.level)
  }

  @Test
  fun `clean descriptor reports ok`() {
    val dir = newTempDir()
    val launcher = File(dir, "java").apply { writeText("") }
    launcher.setExecutable(true)
    val manifest = File(dir, "previews.json").apply { writeText("[]") }
    val f =
      writeDescriptor(
        dir,
        """
        {"enabled": true, "schemaVersion": $EXPECTED_DESCRIPTOR_SCHEMA_VERSION,
         "javaLauncher": "${launcher.absolutePath}",
         "manifestPath": "${manifest.absolutePath}"}
        """
          .trimIndent(),
      )
    val state = inspectDescriptor("app", f)
    assertEquals("ok", state.verdict)
    assertEquals("ok", state.status)
    assertTrue(state.findings.isEmpty())
  }

  @Test
  fun `aggregate verdict picks worst per-module verdict`() {
    val ok = DoctorState("a", File("/tmp/a"), status = "ok", enabled = true, verdict = "ok")
    val install =
      DoctorState(
        "b",
        File("/tmp/b"),
        status = "missing",
        enabled = false,
        verdict = "run-mcp-install",
      )
    assertEquals("ok", aggregateVerdict(listOf(ok)))
    assertEquals("run-mcp-install", aggregateVerdict(listOf(ok, install)))
    assertEquals("run-mcp-install", aggregateVerdict(listOf(install)))
  }
}
