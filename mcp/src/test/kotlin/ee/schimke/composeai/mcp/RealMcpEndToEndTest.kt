package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Real-mode end-to-end test of the MCP server against the live `:samples:cmp` desktop daemon.
 *
 * Mirrors the Python smoke under
 * [`mcp/scripts/real_e2e_smoke.py`](../../../../scripts/real_e2e_smoke.py) but as a JUnit-shaped
 * test so it integrates with `:mcp:check` once opted-in.
 *
 * **Skipped by default.** Pass `-Pmcp.real=true` to opt in. Cost: spawns a real desktop daemon
 * subprocess and runs `./gradlew :samples:cmp:compileKotlin` twice (~30s warm). Mutates
 * `samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt` and reverts via `git checkout` —
 * only safe on a clean tree.
 *
 * **Preconditions checked on entry** (failing the assumption rather than the test if any are
 * missing, so an under-prepared workstation gets a useful "do this first" message):
 *
 * - `:samples:cmp:composePreviewDaemonStart` has been run with the daemon enabled, producing
 *   `samples/cmp/build/compose-previews/daemon-launch.json` with `"enabled": true`.
 * - `:samples:cmp:discoverPreviews` has been run, producing `previews.json` next to it.
 * - `:mcp:jar` and `:daemon:core:jar` have been built.
 * - The JVM running this test has both jars + their kotlinx runtime deps on `java.class.path`; the
 *   test reuses that same classpath when spawning the MCP server subprocess.
 *
 * **Asserts**:
 * 1. `before == revert` (byte-equal — `git checkout` restored the original render).
 * 2. `before != after` (the edit produced a visibly different render).
 */
class RealMcpEndToEndTest {

  private val workdir =
    File(System.getProperty("composeai.mcp.workdir") ?: "/home/user/compose-ai-tools")
  private val previewsKt =
    File(workdir, "samples/cmp/src/main/kotlin/com/example/samplecmp/Previews.kt")
  private val descriptorJson =
    File(workdir, "samples/cmp/build/compose-previews/daemon-launch.json")
  private val previewsJson = File(workdir, "samples/cmp/build/compose-previews/previews.json")

  private lateinit var workspaceId: WorkspaceId
  private lateinit var previewUri: String
  private lateinit var process: Process
  private lateinit var client: McpTestClient

  @Before
  fun setUp() {
    Assume.assumeTrue(
      "Skipping RealMcpEndToEndTest — set -Pmcp.real=true to enable.",
      System.getProperty("composeai.mcp.real") == "true",
    )
    Assume.assumeTrue(
      "Skipping RealMcpEndToEndTest — workdir '${workdir.absolutePath}' does not exist.",
      workdir.isDirectory,
    )
    Assume.assumeTrue(
      "Skipping RealMcpEndToEndTest — '$descriptorJson' missing. Run " +
        "`./gradlew :samples:cmp:composePreviewDaemonStart -PcomposePreview.experimental.daemon.enabled=true` " +
        "first; if the descriptor reports `enabled: false`, also enable the experimental flag in " +
        "the sample's build.gradle.kts (see DaemonExtension.kt KDoc).",
      descriptorJson.isFile && descriptorJson.readText().contains("\"enabled\": true"),
    )
    Assume.assumeTrue(
      "Skipping RealMcpEndToEndTest — '$previewsJson' missing. Run " +
        "`./gradlew :samples:cmp:discoverPreviews` first.",
      previewsJson.isFile,
    )
    // The :mcp module's classes are on the test JVM's classpath either as a jar
    // (`mcp/build/libs/mcp-*.jar`) or as a classes directory
    // (`mcp/build/classes/kotlin/main`). Either is fine — we forward the same `java.class.path`
    // to the spawned subprocess.
    val mcpOnCp =
      System.getProperty("java.class.path").split(File.pathSeparator).any {
        it.contains("/mcp/build/")
      }
    Assume.assumeTrue(
      "Skipping RealMcpEndToEndTest — :mcp output not on test runtime classpath. " +
        "Should be picked up automatically when running via `./gradlew :mcp:test -Pmcp.real=true`.",
      mcpOnCp,
    )

    // Workspace id is path-derived; recompute the same way `WorkspaceId.derive` does so the
    // subprocess we spawn comes up with the matching id, since `register_project` keys off
    // the canonical path → 8-char-sha256 prefix.
    workspaceId = WorkspaceId.derive("compose-ai-tools", workdir)
    previewUri =
      "compose-preview://${workspaceId.value}/_samples_cmp/" +
        "com.example.samplecmp.PreviewsKt.RedBoxPreview_Red Box"

    val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    val classpath = System.getProperty("java.class.path")
    process =
      ProcessBuilder(javaBin, "-cp", classpath, "ee.schimke.composeai.mcp.DaemonMcpMain")
        .redirectErrorStream(false)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    // Drain stderr in the background so the subprocess never wedges on a full pipe and so any
    // failures surface in the test log.
    Thread(
        {
          process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { System.err.println("[mcp] $it") }
          }
        },
        "mcp-real-stderr",
      )
      .apply { isDaemon = true }
      .start()
    client = McpTestClient(input = process.inputStream, output = process.outputStream)

    client.initialize()
    client.callTool(
      "register_project",
      buildJsonObject {
        put("path", workdir.absolutePath)
        put("rootProjectName", "compose-ai-tools")
        putJsonArray("modules") { add(JsonPrimitive(":samples:cmp")) }
      },
    )
    client.callTool(
      "watch",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("module", ":samples:cmp")
      },
    )
    // Give the desktop daemon time to initialize + emit its initial discovery.
    Thread.sleep(2_000)
  }

  @After
  fun tearDown() {
    // The Assume gate may have skipped setUp entirely; guard against the lateinits to avoid
    // turning a clean skip into a TestCouldNotBeSkippedException during tearDown.
    if (!::process.isInitialized) return

    // Always revert the source first; the subprocess teardown can fail later without losing
    // user changes.
    runCatching {
      val proc =
        ProcessBuilder("git", "-C", workdir.absolutePath, "checkout", "--", previewsKt.absolutePath)
          .redirectErrorStream(true)
          .start()
      proc.waitFor(10, TimeUnit.SECONDS)
    }
    runCatching { client.close() }
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
  }

  @Test
  fun edit_recompile_notify_revert_round_trip_round_trips() {
    val before = readPreviewBytes()

    // Mutate the source — change the literal "Red" displayed in the preview to "Edited".
    val original = previewsKt.readText()
    val mutated =
      original.replace(
        """Text("Red", color = Color.White)""",
        """Text("Edited", color = Color.White)""",
      )
    check(mutated != original) { "expected source-edit pattern not present in $previewsKt" }
    previewsKt.writeText(mutated)
    runGradle(":samples:cmp:compileKotlin")

    client.callTool(
      "notify_file_changed",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("path", previewsKt.absolutePath)
        put("kind", "source")
      },
    )
    Thread.sleep(1_000)
    val after = readPreviewBytes()

    // Revert and recompile.
    val revert = runGradleAndRead(":samples:cmp:compileKotlin", revertFirst = true)

    // Verdict: the edit produced different bytes; the revert restored the originals.
    assertThat(sha256(after)).isNotEqualTo(sha256(before))
    assertThat(sha256(revert)).isEqualTo(sha256(before))
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private fun readPreviewBytes(): ByteArray {
    val response =
      client.request(
        "resources/read",
        buildJsonObject { put("uri", previewUri) },
        timeoutMs = 60_000,
      )
    val contents =
      response["contents"]?.let { it as? kotlinx.serialization.json.JsonArray }
        ?: error("resources/read returned no contents")
    val first = contents.first().jsonObject
    val blob =
      first["blob"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        ?: error("resources/read first content has no blob: $first")
    return Base64.getDecoder().decode(blob)
  }

  private fun runGradle(vararg targets: String) {
    val cmd = listOf("./gradlew", *targets, "-q")
    val proc =
      ProcessBuilder(cmd)
        .directory(workdir)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val ok = proc.waitFor(180, TimeUnit.SECONDS)
    if (!ok || proc.exitValue() != 0) {
      throw RuntimeException("gradle ${targets.joinToString(" ")} failed: $out")
    }
  }

  private fun runGradleAndRead(target: String, revertFirst: Boolean): ByteArray {
    if (revertFirst) {
      ProcessBuilder("git", "-C", workdir.absolutePath, "checkout", "--", previewsKt.absolutePath)
        .redirectErrorStream(true)
        .start()
        .waitFor(10, TimeUnit.SECONDS)
    }
    runGradle(target)
    client.callTool(
      "notify_file_changed",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("path", previewsKt.absolutePath)
        put("kind", "source")
      },
    )
    Thread.sleep(1_000)
    return readPreviewBytes()
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
