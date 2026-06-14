package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.history.GitRefHistorySource
import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistoryFilter
import ee.schimke.composeai.daemon.history.HistorySource
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `compose-preview history <list|read|diff>` — inspect the render history the daemon archives.
 *
 * Reads the local filesystem archive (`.compose-preview-history/`, the daemon's default
 * [LocalFsHistorySource]) by default, or the reporting branch ([GitRefHistorySource]) when `--ref
 * <fullRef>` is given. Both expose the same [HistorySource] read API, so the three subcommands are
 * source-agnostic. Reading off disk gives correct results whether or not a daemon is running, since
 * the daemon writes there.
 *
 * **Structured-first** (cf. #1787): human output is compact metadata; `--json` emits a versioned
 * envelope (`compose-preview-history/v1`); heavy snapshots (PNG bytes, a11y/semantics/theme data)
 * ride only on explicit opt-in (`--inline`, `--out`, `--data`).
 *
 * v1 reads on-disk / on-branch history directly. Preferring a running daemon's `history` JSON-RPC
 * methods (for live, not-yet-flushed state) needs a CLI-side daemon client — tracked as a
 * follow-up; the on-disk read is the source of truth the daemon persists to.
 */
class HistoryCommand(private val args: List<String>) {

  fun run() {
    when (args.firstOrNull { !it.startsWith("-") } ?: "help") {
      "list" -> list()
      "read" -> read()
      "diff" -> diff()
      "help" -> printUsage()
      else -> {
        System.err.println(
          "Unknown history subcommand: ${args.firstOrNull { !it.startsWith("-") }}"
        )
        printUsage()
        exitProcess(1)
      }
    }
  }

  // -------------------------------------------------------------------------
  // Subcommands
  // -------------------------------------------------------------------------

  private fun list() {
    val json = "--json" in args
    val source = openSource(json) ?: return
    val filter =
      HistoryFilter(
        previewId = args.flagValue("--preview"),
        since = args.flagValue("--since"),
        until = args.flagValue("--until"),
        branch = args.flagValue("--branch"),
        commit = args.flagValue("--commit"),
        agentId = args.flagValue("--agent"),
        sourceKind = args.flagValue("--source"),
        cursor = args.flagValue("--cursor"),
        limit = args.flagValue("--limit")?.toIntOrNull() ?: DEFAULT_LIMIT,
      )
    val page = source.list(filter)

    if (json) {
      val payload =
        HistoryListResponse(
          schema = HISTORY_SCHEMA,
          total = page.totalCount,
          nextCursor = page.nextCursor,
          entries = page.entries.map(::leanEntryJson),
        )
      println(OUT.encodeToString(HistoryListResponse.serializer(), payload))
      return
    }

    if (page.entries.isEmpty()) {
      println("No history entries (source: ${source.id}).")
      return
    }
    println("Showing ${page.entries.size} of ${page.totalCount} entries (source: ${source.id}):")
    for (e in page.entries) {
      val short = e.git?.shortCommit?.let { " @$it" } ?: ""
      val data = dataProductsOf(e).takeIf { it.isNotEmpty() }?.joinToString(",", " [", "]") ?: ""
      println("  ${e.id}  ${e.previewId}  ${e.timestamp}  (${e.trigger})$short$data")
    }
    page.nextCursor?.let { println("\nmore: --cursor $it") }
  }

  private fun read() {
    val json = "--json" in args
    val id = positionalAfter("read")
    if (id == null) {
      System.err.println("history read: missing <entryId>")
      exitProcess(1)
    }
    val source =
      openSource(json)
        ?: run {
          // openSource already reported "no history"; a read of a specific id is then a miss.
          System.err.println("history read: entry not found: $id")
          exitProcess(2)
        }
    val out = args.flagValue("--out")
    val wantData = "--data" in args
    val inline = "--inline" in args
    val result = source.read(id, includeBytes = inline || out != null)
    if (result == null) {
      System.err.println("history read: entry not found: $id")
      exitProcess(2)
    }
    val entry = result.entry

    out?.let { path ->
      val bytes = result.pngBytes
      if (bytes == null) {
        System.err.println("history read: no PNG bytes available for $id")
        exitProcess(2)
      }
      Files.write(Paths.get(path), bytes)
    }

    if (json) {
      val payload =
        HistoryReadResponse(
          schema = HISTORY_SCHEMA,
          entry = if (wantData) fullEntryJson(entry) else leanEntryJson(entry),
          pngPath = result.pngPath,
          dataProducts = dataProductsOf(entry),
          pngBytes =
            if (inline) result.pngBytes?.let { Base64.getEncoder().encodeToString(it) } else null,
        )
      println(OUT.encodeToString(HistoryReadResponse.serializer(), payload))
      return
    }

    println(entry.id)
    println("  preview:   ${entry.previewId}  (${entry.module})")
    println("  timestamp: ${entry.timestamp}   trigger: ${entry.trigger}")
    println(
      "  png:       ${result.pngPath}  (${entry.pngSize} bytes, sha ${entry.pngHash.take(12)})"
    )
    entry.git?.let { g ->
      println(
        "  git:       ${g.branch ?: "(detached)"}@${g.shortCommit ?: "?"}  dirty=${g.dirty ?: "?"}"
      )
    }
    val data = dataProductsOf(entry)
    println("  data:      ${if (data.isEmpty()) "none" else data.joinToString(", ")}")
    out?.let { println("  wrote PNG: $it") }
  }

  private fun diff() {
    val json = "--json" in args
    val mode = args.flagValue("--mode") ?: "metadata"
    if (mode != "metadata") {
      System.err.println(
        "history diff: --mode $mode is not available from the CLI yet (pixel/semantics diff is " +
          "daemon-backed via history/diff). Use --mode metadata."
      )
      exitProcess(1)
    }
    val ids = positionalsAfter("diff")
    if (ids.size < 2) {
      System.err.println("history diff: need two entry ids: <fromId> <toId>")
      exitProcess(1)
    }
    val (fromId, toId) = ids
    val source =
      openSource(json)
        ?: run {
          System.err.println("history diff: entries not found")
          exitProcess(2)
        }
    val from = source.read(fromId)?.entry
    val to = source.read(toId)?.entry
    if (from == null || to == null) {
      System.err.println("history diff: entry not found: ${if (from == null) fromId else toId}")
      exitProcess(2)
    }
    if (from.previewId != to.previewId) {
      System.err.println(
        "history diff: entries are different previews (${from.previewId} vs ${to.previewId})"
      )
      exitProcess(2)
    }
    val pngHashChanged = from.pngHash != to.pngHash

    if (json) {
      val payload =
        HistoryDiffResponse(
          schema = HISTORY_SCHEMA,
          mode = "metadata",
          pngHashChanged = pngHashChanged,
          from = leanEntryJson(from),
          to = leanEntryJson(to),
        )
      println(OUT.encodeToString(HistoryDiffResponse.serializer(), payload))
      return
    }

    println("diff ${from.id} → ${to.id}  (preview ${from.previewId})")
    println("  pixels:    ${if (pngHashChanged) "CHANGED" else "unchanged"}")
    if (from.timestamp != to.timestamp) println("  timestamp: ${from.timestamp} → ${to.timestamp}")
    if (from.git?.commit != to.git?.commit) {
      println("  commit:    ${from.git?.shortCommit ?: "?"} → ${to.git?.shortCommit ?: "?"}")
    }
    val fromData = dataProductsOf(from)
    val toData = dataProductsOf(to)
    if (fromData != toData) println("  data:      $fromData → $toData")
  }

  // -------------------------------------------------------------------------
  // Source resolution + helpers
  // -------------------------------------------------------------------------

  /**
   * Opens the history source: `--ref <fullRef>` reads the reporting branch (git), else the local
   * `.compose-preview-history/`. Returns null (after reporting "no history") only for the local
   * case when the dir is absent, so callers can short-circuit.
   */
  private fun openSource(json: Boolean): HistorySource? {
    val ref = args.flagValue("--ref")
    val cwd = Paths.get(System.getProperty("user.dir"))
    if (ref != null) {
      return GitRefHistorySource(repoRoot = cwd, ref = ref)
    }
    val dir = args.flagValue("--history-dir")?.let { Paths.get(it) } ?: cwd.resolve(HISTORY_DIRNAME)
    if (!Files.isDirectory(dir)) {
      if (json) {
        println(
          OUT.encodeToString(
            HistoryListResponse.serializer(),
            HistoryListResponse(schema = HISTORY_SCHEMA, total = 0, entries = emptyList()),
          )
        )
      } else {
        println("No history found at $dir (no renders archived yet).")
      }
      return null
    }
    return LocalFsHistorySource(dir)
  }

  private fun positionalAfter(sub: String): String? = positionalsAfter(sub).firstOrNull()

  /**
   * Operands after the subcommand token, in order (the entry ids). Skips flag tokens *and* the
   * value of a space-separated valued flag (e.g. `--history-dir /tmp/h e1` → `[e1]`), so options
   * may appear before the ids.
   */
  private fun positionalsAfter(sub: String): List<String> {
    val positionals = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
      val a = args[i]
      when {
        a.startsWith("--") && "=" !in a && a in VALUED_FLAGS -> i += 2 // flag + its value
        a.startsWith("-") -> i += 1 // boolean flag, or `--flag=value`
        else -> {
          positionals.add(a)
          i += 1
        }
      }
    }
    val idx = positionals.indexOf(sub)
    return if (idx >= 0) positionals.drop(idx + 1) else emptyList()
  }

  private fun dataProductsOf(e: HistoryEntry): List<String> = buildList {
    if (e.semantics != null) add("compose/semantics")
    if (e.a11yHierarchy != null) add("a11y/hierarchy")
    if (e.a11yAtf != null) add("a11y/atf")
    if (e.a11yTouchTargets != null) add("a11y/touchTargets")
    if (e.theme != null) add("compose/theme")
  }

  private fun leanEntryJson(e: HistoryEntry): JsonElement =
    ENTRY.encodeToJsonElement(
      HistoryEntry.serializer(),
      e.copy(
        semantics = null,
        a11yAtf = null,
        a11yHierarchy = null,
        a11yTouchTargets = null,
        theme = null,
      ),
    )

  private fun fullEntryJson(e: HistoryEntry): JsonElement =
    ENTRY.encodeToJsonElement(HistoryEntry.serializer(), e)

  private fun printUsage() {
    println(
      """
      compose-preview history — inspect archived render history

      Subcommands:
        list                 List history entries (newest first)
        read <entryId>       Show one entry's metadata (and optionally its PNG / data)
        diff <fromId> <toId> Compare two entries (metadata mode)
        help                 Show this help message

      Source:
        --history-dir <dir>  History dir (default: <cwd>/$HISTORY_DIRNAME)
        --ref <fullRef>      Read the reporting branch instead, e.g. refs/heads/preview/main

      list options:
        --preview <id>  --since <iso>  --until <iso>  --branch <b>  --commit <sha>
        --agent <id>    --source <fs|git>  --limit <n>  --cursor <c>

      read options:
        --out <path>   Write the entry's PNG to <path>
        --data         Include the full a11y/semantics/theme snapshots (--json)
        --inline       Include base64 PNG bytes (--json)

      diff options:
        --mode metadata        (pixel/semantics diff is daemon-backed; not in the CLI yet)

      Common:
        --json         Emit JSON (schema: $HISTORY_SCHEMA)
      """
        .trimIndent()
    )
  }

  internal companion object {
    const val HISTORY_SCHEMA = "compose-preview-history/v1"
    const val HISTORY_DIRNAME = ".compose-preview-history"
    private const val DEFAULT_LIMIT = 50

    /** Flags that take a value, so a space-separated value isn't mistaken for an operand. */
    private val VALUED_FLAGS =
      setOf(
        "--history-dir",
        "--ref",
        "--preview",
        "--since",
        "--until",
        "--branch",
        "--commit",
        "--agent",
        "--source",
        "--cursor",
        "--limit",
        "--mode",
        "--out",
      )

    private val OUT = Json {
      prettyPrint = true
      encodeDefaults = true
    }
    private val ENTRY = Json { encodeDefaults = false }
  }
}

@Serializable
internal data class HistoryListResponse(
  val schema: String,
  val total: Int,
  val nextCursor: String? = null,
  val entries: List<JsonElement>,
)

@Serializable
internal data class HistoryReadResponse(
  val schema: String,
  val entry: JsonElement,
  val pngPath: String,
  val dataProducts: List<String>,
  val pngBytes: String? = null,
)

@Serializable
internal data class HistoryDiffResponse(
  val schema: String,
  val mode: String,
  val pngHashChanged: Boolean,
  val from: JsonElement,
  val to: JsonElement,
)
