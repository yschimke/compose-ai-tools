package ee.schimke.composeai.cli

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.SemanticsDelta
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import ee.schimke.composeai.data.layoutinspector.SemanticsNodeSummary
import ee.schimke.composeai.io.SystemFileSystem
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

private val semanticsDiffJson = Json {
  prettyPrint = true
  encodeDefaults = true
  ignoreUnknownKeys = true
}

/**
 * `compose-preview diff-semantics <base> <head>` — diff two `compose/semantics` trees and report
 * what changed semantically (issue #1785). The cheap, deterministic, pixel-free regression signal —
 * the Compose analogue of Playwright's aria-snapshot diff — for the `compose-preview-review`
 * skill's base-vs-head loop: render both sides, then diff their `compose-semantics.json` artifacts
 * instead of reading two PNGs.
 *
 * Each argument is either a `compose-semantics.json` file or a directory containing one. Output is
 * a human summary by default, or the versioned JSON [SemanticsDelta] with `--json`.
 * `--fail-on-change` exits 2 when the trees differ, so CI can gate on it.
 */
class SemanticsDiffCommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val jsonOutput = "--json" in args
  private val failOnChange = "--fail-on-change" in args
  private val positionals = collectOperands(args)

  fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }
    if (positionals.size < 2) {
      System.err.println(
        "diff-semantics: expected <base> and <head> compose-semantics.json paths (or directories)"
      )
      printUsage()
      exitProcess(64)
    }
    when (val outcome = computeSemanticsDiff(positionals[0], positionals[1], fileSystem)) {
      is SemanticsDiffOutcome.Error -> {
        System.err.println("diff-semantics: ${outcome.message}")
        exitProcess(outcome.code)
      }
      is SemanticsDiffOutcome.Ok -> {
        println(
          if (jsonOutput) {
            semanticsDiffJson.encodeToString(SemanticsDelta.serializer(), outcome.delta)
          } else {
            formatSemanticsDeltaHuman(outcome.delta)
          }
        )
        if (failOnChange && !outcome.delta.isEmpty) exitProcess(2)
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview diff-semantics <base> <head> [--json] [--fail-on-change]

      Diff two compose/semantics trees and report what changed semantically — added /
      removed nodes and per-node field changes (text, label, role, testTag, overflow, …),
      matched by each node's stable `ref`. A cheap, deterministic, pixel-free regression
      signal; copy edits show as field changes on the same ref, not remove+add.

      <base> / <head>  A compose-semantics.json file, or a directory containing one
                       (the daemon writes build/compose-previews/data/<id>/compose-semantics.json).
      --json           Emit the versioned ${ComposeSemanticsDiffSchema.SCHEMA} JSON delta.
      --fail-on-change Exit 2 when the trees differ (for CI gating).
      """
        .trimIndent()
    )
  }
}

/** Stable schema id for the JSON delta, re-exported for the usage text. */
internal object ComposeSemanticsDiffSchema {
  val SCHEMA: String = SemanticsDelta().schema
}

/** This command's own boolean flags — everything else `--x` is treated as a valued option. */
private val SEMANTICS_DIFF_BOOLEAN_FLAGS = setOf("--json", "--fail-on-change", "--help", "-h")

/**
 * Collect the two path operands, skipping the *value* of any valued option (e.g. a global `--module
 * :app` that `Main` forwards) so it isn't mistaken for `<base>`/`<head>`. Tokens starting with `-`
 * are flags: this command's own flags (and `--x=value` forms) consume one token, any other bare
 * `--x` consumes its following value too.
 */
internal fun collectOperands(args: List<String>): List<String> {
  val operands = mutableListOf<String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      !arg.startsWith("-") -> {
        operands.add(arg)
        i++
      }
      arg in SEMANTICS_DIFF_BOOLEAN_FLAGS || arg.contains("=") -> i++
      else -> i += 2 // valued option: skip the flag and its value
    }
  }
  return operands
}

internal sealed interface SemanticsDiffOutcome {
  data class Ok(val delta: SemanticsDelta) : SemanticsDiffOutcome

  data class Error(val message: String, val code: Int) : SemanticsDiffOutcome
}

internal fun computeSemanticsDiff(
  baseArg: String,
  headArg: String,
  fileSystem: FileSystem = SystemFileSystem,
): SemanticsDiffOutcome {
  val base =
    readSemanticsPayload(baseArg, fileSystem)
      ?: return SemanticsDiffOutcome.Error(
        "could not read base compose/semantics from '$baseArg'",
        1,
      )
  val head =
    readSemanticsPayload(headArg, fileSystem)
      ?: return SemanticsDiffOutcome.Error(
        "could not read head compose/semantics from '$headArg'",
        1,
      )
  return SemanticsDiffOutcome.Ok(SemanticsDiff.diff(base, head))
}

/**
 * Resolve [arg] to a compose-semantics.json file (directories resolve their canonical name) and
 * parse it.
 */
internal fun readSemanticsPayload(
  arg: String,
  fileSystem: FileSystem = SystemFileSystem,
): ComposeSemanticsPayload? {
  val path = arg.toPath()
  val file =
    if (fileSystem.metadataOrNull(path)?.isDirectory == true) path / ComposeSemanticsProduct.FILE
    else path
  if (!fileSystem.exists(file)) return null
  val text =
    try {
      fileSystem.read(file) { readUtf8() }
    } catch (_: Throwable) {
      return null
    }
  return try {
    semanticsDiffJson.decodeFromString(ComposeSemanticsPayload.serializer(), text)
  } catch (_: Throwable) {
    null
  }
}

internal fun formatSemanticsDeltaHuman(delta: SemanticsDelta): String {
  if (delta.isEmpty) return "No semantic changes."
  return buildString {
      appendLine(
        "${delta.added.size} added, ${delta.removed.size} removed, ${delta.changed.size} changed"
      )
      delta.removed.forEach { appendLine("  - removed ${describeNode(it)}") }
      delta.added.forEach { appendLine("  + added ${describeNode(it)}") }
      delta.changed.forEach { change ->
        appendLine("  ~ ${change.anchor ?: change.ref}")
        change.changes.forEach { field ->
          appendLine("      ${field.field}: ${field.from ?: "∅"} → ${field.to ?: "∅"}")
        }
      }
    }
    .trimEnd()
}

private fun describeNode(node: SemanticsNodeSummary): String {
  val parts =
    listOfNotNull(
      node.testTag?.let { "testTag=$it" },
      node.role?.let { "role=$it" },
      (node.text ?: node.label)?.let { "text=\"$it\"" },
    )
  return if (parts.isEmpty()) node.ref else "${node.ref} (${parts.joinToString(" ")})"
}
