package ee.schimke.composeai.cli

import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCommandCatalog
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val EXTENSION_COMMANDS_SCHEMA = "compose-preview-extension-commands/v1"

private val extensionJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

@Serializable
data class ExtensionCommandsResponse(
  val schema: String = EXTENSION_COMMANDS_SCHEMA,
  val extensions: List<PreviewExtensionDescriptor>,
) {
  val commandCount: Int = extensions.sumOf { it.cliCommands.size }
}

class ExtensionCommandsCommand(
  private val args: List<String>,
  private val registeredExtensions: List<PreviewExtensionDescriptor> =
    PreviewExtensionCommandCatalog.extensions,
) {
  private val jsonOutput = "--json" in args
  private val onlyAgentRecommended = "--agent" in args || "--agent-recommended" in args
  private val positionals = args.filter { !it.startsWith("-") }

  fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    when (positionals.firstOrNull() ?: "commands") {
      "commands",
      "list" -> printCommands()
      "run" -> runExtensionCommand()
      else -> {
        System.err.println("extensions supports: commands, run")
        printUsage()
        exitProcess(1)
      }
    }
  }

  private fun printCommands() {
    val response = ExtensionCommandsResponse(extensions = registeredExtensions)
    val filtered =
      if (onlyAgentRecommended) {
        response.copy(
          extensions =
            response.extensions
              .map { descriptor ->
                descriptor.copy(
                  cliCommands =
                    descriptor.cliCommands.filter { it.agentRecommended && !it.requiresDaemon }
                )
              }
              .filter { it.cliCommands.isNotEmpty() }
        )
      } else {
        response
      }

    if (jsonOutput) {
      println(extensionJson.encodeToString(ExtensionCommandsResponse.serializer(), filtered))
    } else {
      printText(filtered)
    }
  }

  private fun printText(response: ExtensionCommandsResponse) {
    response.extensions.forEach { extension ->
      if (extension.cliCommands.isEmpty()) return@forEach
      println("${extension.id} - ${extension.displayName}")
      extension.cliCommands.forEach { command ->
        val marker = if (command.agentRecommended) " [agent]" else ""
        println("  ${command.id}$marker")
        if (command.summary.isNotBlank()) {
          println("    ${command.summary}")
        }
        println("    ${command.command.joinToString(" ")}")
      }
    }
  }

  private fun printUsage() {
    println(
      """
      compose-preview extensions commands [--json] [--agent]
      compose-preview extensions run COMMAND_ID [command options]

      Lists or runs command metadata contributed by built-in preview extensions.
      Discovery is daemon-free; run routes shrinkwrapped command ids to stable
      CLI primitives such as show, a11y, and data get.
      """
        .trimIndent()
    )
  }

  private fun runExtensionCommand() {
    val commandId =
      positionals.getOrNull(1)
        ?: run {
          System.err.println("extensions run requires COMMAND_ID.")
          printUsage()
          exitProcess(1)
        }
    val registeredCommand = registeredCommandById(commandId)
    if (registeredCommand == null) {
      System.err.println("Unknown extension command: $commandId")
      printUsage()
      exitProcess(1)
    }
    val passthrough = args.withoutFirstPositionals(2)
    when (val invocation = registeredCommand.toCliInvocation()) {
      ExtensionCommandInvocation.A11yRender ->
        A11yCommand(passthrough.withDefault("--json"), forceEnable = true).run()
      ExtensionCommandInvocation.Show -> ShowCommand(passthrough.withDefault("--json")).run()
      is ExtensionCommandInvocation.Data ->
        DataCommand(passthrough.dataGet(invocation.kind, defaultJson = invocation.defaultJson))
          .run()
      ExtensionCommandInvocation.RequiresDaemon -> {
        System.err.println(
          "Extension command '$commandId' requires daemon data/fetch. Use MCP run_extension_command."
        )
        exitProcess(1)
      }
      ExtensionCommandInvocation.Unsupported -> {
        System.err.println("Extension command '$commandId' has no CLI runner yet.")
        exitProcess(1)
      }
    }
  }

  private fun registeredCommandById(id: String): RegisteredExtensionCommand? =
    registeredExtensions
      .asSequence()
      .flatMap { extension -> extension.cliCommands.asSequence().map { extension to it } }
      .firstOrNull { (_, command) -> command.id == id }
      ?.let { (extension, command) -> RegisteredExtensionCommand(extension, command) }
}

internal data class RegisteredExtensionCommand(
  val extension: PreviewExtensionDescriptor,
  val command: PreviewExtensionCliCommand,
)

internal sealed interface ExtensionCommandInvocation {
  data object A11yRender : ExtensionCommandInvocation

  data object Show : ExtensionCommandInvocation

  data class Data(val kind: String, val defaultJson: Boolean) : ExtensionCommandInvocation

  data object RequiresDaemon : ExtensionCommandInvocation

  data object Unsupported : ExtensionCommandInvocation
}

internal fun RegisteredExtensionCommand.toCliInvocation(): ExtensionCommandInvocation {
  if (command.requiresDaemon) return ExtensionCommandInvocation.RequiresDaemon

  if (command.productKinds.size == 1) {
    return ExtensionCommandInvocation.Data(
      kind = command.productKinds.single(),
      defaultJson = "--json" in command.command,
    )
  }

  if (command.id.endsWith(".run") || command.id.endsWith(".render")) {
    return if (isA11yCommand()) {
      ExtensionCommandInvocation.A11yRender
    } else {
      ExtensionCommandInvocation.Show
    }
  }

  return ExtensionCommandInvocation.Unsupported
}

private fun RegisteredExtensionCommand.isA11yCommand(): Boolean =
  extension.id.startsWith("a11y") ||
    extension.id.startsWith("atf") ||
    extension.componentExtensionIds.any { it.startsWith("a11y") || it.startsWith("atf") } ||
    command.productKinds.any { it.startsWith("a11y/") }

private fun List<String>.withoutFirstPositionals(count: Int): List<String> {
  var remaining = count
  return filter { arg ->
    if (remaining > 0 && !arg.startsWith("-")) {
      remaining--
      false
    } else {
      true
    }
  }
}

private fun List<String>.withDefault(flag: String): List<String> =
  if (flag in this) this else this + flag

private fun List<String>.dataGet(kind: String, defaultJson: Boolean): List<String> {
  val out = mutableListOf("get", "--kind", kind)
  out += this
  if (defaultJson && "--json" !in out && "--output" !in out) out += "--json"
  return out
}
