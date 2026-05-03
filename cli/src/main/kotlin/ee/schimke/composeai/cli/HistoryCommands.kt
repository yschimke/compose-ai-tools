package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistoryFilter
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val HISTORY_LIST_SCHEMA = "compose-preview-history-list/v1"
internal const val HISTORY_DIFF_SCHEMA = "compose-preview-history-diff/v1"

private val historyJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
}

@Serializable
data class CliHistoryListResponse(
  val schema: String = HISTORY_LIST_SCHEMA,
  val entries: List<HistoryEntry>,
  val totalCount: Int,
)

@Serializable
data class CliHistoryDiffResponse(
  val schema: String = HISTORY_DIFF_SCHEMA,
  val pngHashChanged: Boolean,
  val fromMetadata: HistoryEntry,
  val toMetadata: HistoryEntry,
)

class HistoryCommand(args: List<String>) : Command(args) {
  private val subcommand = args.historySubcommand()
  private val jsonOutput = "--json" in args
  private val from: String? = args.flagValue("--from")
  private val to: String? = args.flagValue("--to")
  private val limit: Int? = args.flagValue("--limit")?.toIntOrNull()

  override fun run() {
    if ("--help" in args || "-h" in args || subcommand == "help") {
      printUsage()
      return
    }
    when (subcommand) {
      "list" -> runList()
      "diff" -> runDiff()
      null -> {
        System.err.println("No history subcommand specified.")
        printUsage()
        exitProcess(1)
      }
      else -> {
        System.err.println("Unknown history subcommand: $subcommand")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun runList() {
    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val response = listLocalHistory(modules = modules, previewId = exactId, limit = limit)
      if (jsonOutput) {
        println(historyJson.encodeToString(CliHistoryListResponse.serializer(), response))
      } else {
        printHistoryList(response)
      }
    }
  }

  private fun runDiff() {
    val fromId =
      from
        ?: run {
          System.err.println("history diff requires --from <entry-id>.")
          exitProcess(1)
        }
    val toId =
      to
        ?: run {
          System.err.println("history diff requires --to <entry-id>.")
          exitProcess(1)
        }

    withGradle { gradle ->
      val modules = resolveModules(gradle)
      val response =
        diffLocalHistory(modules = modules, fromId = fromId, toId = toId)
          ?: run {
            System.err.println(
              "History entry not found or unreadable: from='$fromId' to='$toId'. " +
                "Check .compose-preview-history sidecars and PNG paths."
            )
            exitProcess(3)
          }
      if (response.fromMetadata.previewId != response.toMetadata.previewId) {
        System.err.println(
          "History entries are for different previews: " +
            "'${response.fromMetadata.previewId}' vs '${response.toMetadata.previewId}'."
        )
        exitProcess(1)
      }
      if (jsonOutput) {
        println(historyJson.encodeToString(CliHistoryDiffResponse.serializer(), response))
      } else {
        val changed = if (response.pngHashChanged) "changed" else "unchanged"
        println("${response.fromMetadata.id} -> ${response.toMetadata.id}: png hash $changed")
      }
    }
  }

  private fun printHistoryList(response: CliHistoryListResponse) {
    if (response.entries.isEmpty()) {
      println("No history entries found.")
      return
    }
    for (entry in response.entries) {
      val shortHash = entry.pngHash.take(12)
      println(
        "${entry.timestamp}  ${entry.module}  ${entry.previewId}  ${entry.id}  sha=$shortHash"
      )
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview history list [--module M] [--id PREVIEW] [--limit N] [--json]
      compose-preview history diff --from ENTRY --to ENTRY [--module M] [--json]

      Reads local .compose-preview-history entries using the daemon history
      model. The first diff mode is metadata/hash only; it does not compute a
      pixel diff and does not read git-ref history sources.
      """
        .trimIndent()
    )
  }
}

internal fun listLocalHistory(
  modules: List<PreviewModule>,
  previewId: String? = null,
  limit: Int? = null,
): CliHistoryListResponse {
  val all =
    modules
      .flatMap { module ->
        localHistorySource(module.projectDir)
          .list(HistoryFilter(previewId = previewId, limit = LocalFsHistorySource.MAX_LIMIT))
          .entries
      }
      .sortedWith(compareByDescending<HistoryEntry> { it.timestamp }.thenByDescending { it.id })
  val capped =
    all.take(
      (limit ?: LocalFsHistorySource.DEFAULT_LIMIT).coerceIn(1, LocalFsHistorySource.MAX_LIMIT)
    )
  return CliHistoryListResponse(entries = capped, totalCount = all.size)
}

internal fun diffLocalHistory(
  modules: List<PreviewModule>,
  fromId: String,
  toId: String,
): CliHistoryDiffResponse? {
  val from = readUniqueHistoryEntry(modules, fromId) ?: return null
  val to = readUniqueHistoryEntry(modules, toId) ?: return null
  return CliHistoryDiffResponse(
    pngHashChanged = from.pngHash != to.pngHash,
    fromMetadata = from,
    toMetadata = to,
  )
}

private fun readUniqueHistoryEntry(modules: List<PreviewModule>, entryId: String): HistoryEntry? {
  val matches = modules.mapNotNull { module ->
    localHistorySource(module.projectDir).read(entryId)?.entry
  }
  return matches.singleOrNull()
}

private fun localHistorySource(projectDir: File): LocalFsHistorySource =
  LocalFsHistorySource(projectDir.resolve(".compose-preview-history").toPath())

private fun List<String>.historySubcommand(): String? {
  val valuedFlags = setOf("--module", "--id", "--from", "--to", "--limit", "--timeout")
  var i = 0
  while (i < size) {
    val arg = this[i]
    when {
      valuedFlags.any { arg.startsWith("$it=") } -> i++
      arg in valuedFlags -> i += 2
      arg.startsWith("-") -> i++
      else -> return arg
    }
  }
  return null
}
