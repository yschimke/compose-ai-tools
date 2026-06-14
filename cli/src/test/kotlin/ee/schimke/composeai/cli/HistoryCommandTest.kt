package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistorySourceInfo
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Coverage for `compose-preview history list|read|diff` over a seeded local FS archive. */
class HistoryCommandTest {

  private lateinit var historyDir: Path
  private lateinit var capturedOut: ByteArrayOutputStream
  private var savedOut: PrintStream? = null

  @BeforeTest
  fun setUp() {
    historyDir = Files.createTempDirectory("history-cmd-test")
    capturedOut = ByteArrayOutputStream()
    savedOut = System.out
    System.setOut(PrintStream(capturedOut))
  }

  @AfterTest
  fun tearDown() {
    savedOut?.let { System.setOut(it) }
    historyDir.toFile().deleteRecursively()
  }

  private fun output(): String = capturedOut.toString()

  /** Seeds one entry; returns its id. */
  private fun seed(
    id: String,
    previewId: String,
    bytes: ByteArray,
    timestamp: String,
    a11yHierarchy: Boolean = false,
  ): String {
    val source = LocalFsHistorySource(historyDir)
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":t",
        timestamp = timestamp,
        pngHash = LocalFsHistorySource.sha256Hex(bytes),
        pngSize = bytes.size.toLong(),
        pngPath = "$id.png",
        producer = "daemon",
        trigger = "renderNow",
        source = HistorySourceInfo(kind = "fs", id = "fs:${historyDir.toAbsolutePath()}"),
        renderTookMs = 1L,
        a11yHierarchy =
          if (a11yHierarchy)
            Json.parseToJsonElement("""{"nodes":[{"label":"x","boundsInScreen":"0,0,1,1"}]}""")
          else null,
      )
    source.write(entry, bytes)
    return id
  }

  @Test
  fun `list --json emits versioned envelope newest-first`() {
    seed("20260430-100000-aaaaaaaa", "com.example.A", "v1".toByteArray(), "2026-04-30T10:00:00Z")
    seed("20260430-100100-bbbbbbbb", "com.example.A", "v2".toByteArray(), "2026-04-30T10:01:00Z")

    HistoryCommand(listOf("list", "--history-dir", historyDir.toString(), "--json")).run()
    val payload = Json.parseToJsonElement(output()).jsonObject

    assertEquals(JsonPrimitive(HistoryCommand.HISTORY_SCHEMA), payload["schema"])
    assertEquals(2, payload["total"]?.jsonPrimitive?.content?.toInt())
    val ids = payload["entries"]!!.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.content }
    assertEquals(
      listOf("20260430-100100-bbbbbbbb", "20260430-100000-aaaaaaaa"),
      ids,
      "newest-first",
    )
  }

  @Test
  fun `read --json returns metadata, pngPath and dataProducts`() {
    val id =
      seed(
        "20260430-100000-cccccccc",
        "com.example.Card",
        "x".toByteArray(),
        "2026-04-30T10:00:00Z",
        a11yHierarchy = true,
      )

    HistoryCommand(listOf("read", id, "--history-dir", historyDir.toString(), "--json")).run()
    val payload = Json.parseToJsonElement(output()).jsonObject

    assertEquals(JsonPrimitive(HistoryCommand.HISTORY_SCHEMA), payload["schema"])
    assertEquals(id, payload["entry"]!!.jsonObject["id"]?.jsonPrimitive?.content)
    assertTrue(payload["pngPath"]?.jsonPrimitive?.content?.endsWith(".png") == true)
    val data = payload["dataProducts"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertTrue("a11y/hierarchy" in data, "expected a11y/hierarchy in $data")
  }

  @Test
  fun `diff --json reports pngHashChanged for differing renders`() {
    val from =
      seed("20260430-100000-d0d0d0d0", "com.example.A", "v1".toByteArray(), "2026-04-30T10:00:00Z")
    val to =
      seed("20260430-100100-e1e1e1e1", "com.example.A", "v2".toByteArray(), "2026-04-30T10:01:00Z")

    HistoryCommand(listOf("diff", from, to, "--history-dir", historyDir.toString(), "--json")).run()
    val payload = Json.parseToJsonElement(output()).jsonObject

    assertEquals(JsonPrimitive(HistoryCommand.HISTORY_SCHEMA), payload["schema"])
    assertEquals(true, payload["pngHashChanged"]?.jsonPrimitive?.content?.toBoolean())
  }

  @Test
  fun `read resolves the id when a valued option precedes it`() {
    val id =
      seed("20260430-100000-ffffffff", "com.example.A", "x".toByteArray(), "2026-04-30T10:00:00Z")

    // --history-dir's value sits between the subcommand and the id.
    HistoryCommand(listOf("read", "--history-dir", historyDir.toString(), id, "--json")).run()
    val payload = Json.parseToJsonElement(output()).jsonObject

    assertEquals(id, payload["entry"]!!.jsonObject["id"]?.jsonPrimitive?.content)
  }

  @Test
  fun `diff resolves both ids when --mode precedes them`() {
    val from =
      seed("20260430-100000-a1a1a1a1", "com.example.A", "v1".toByteArray(), "2026-04-30T10:00:00Z")
    val to =
      seed("20260430-100100-b2b2b2b2", "com.example.A", "v2".toByteArray(), "2026-04-30T10:01:00Z")

    HistoryCommand(
        listOf(
          "diff",
          "--mode",
          "metadata",
          "--history-dir",
          historyDir.toString(),
          from,
          to,
          "--json",
        )
      )
      .run()
    val payload = Json.parseToJsonElement(output()).jsonObject

    assertEquals(true, payload["pngHashChanged"]?.jsonPrimitive?.content?.toBoolean())
  }

  @Test
  fun `list on a missing history dir emits an empty envelope`() {
    val missing = historyDir.resolve("nope")
    HistoryCommand(listOf("list", "--history-dir", missing.toString(), "--json")).run()
    val payload = Json.parseToJsonElement(output()).jsonObject
    assertEquals(0, payload["total"]?.jsonPrimitive?.content?.toInt())
    assertTrue(payload["entries"]!!.jsonArray.isEmpty())
  }
}
