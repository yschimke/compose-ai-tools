package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.OpenRouterClient
import ee.schimke.composeai.guidelines.OpenRouterModels
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `compose-preview guidelines models [--all] [--json]`: the OpenRouter models `--model` can name,
 * cheapest input first. By default only those that read pictures and keep to a strict reply schema,
 * which the vision checker needs; `--all` lists every model with those two columns. Needs no key.
 */
internal class GuidelinesModelsCommand(
  private val args: List<String>,
  private val fetch: () -> List<OpenRouterModels.Model> = { OpenRouterModels.fetch() },
) {
  fun run() {
    exitProcess(list())
  }

  internal fun list(): Int {
    val models =
      try {
        fetch()
      } catch (e: Exception) {
        System.err.println(
          "guidelines models: could not read OpenRouter's model list (${e.message})"
        )
        return 2
      }
    val shown =
      models
        .filter { "--all" in args || it.suitable }
        .sortedWith(compareBy({ it.promptPerMillion ?: Double.MAX_VALUE }, { it.id }))
    if ("--json" in args) {
      println(
        buildJsonArray {
          shown.forEach { model ->
            add(
              buildJsonObject {
                put("id", model.id)
                put("name", model.name)
                model.contextLength?.let { put("contextLength", it) }
                model.promptPerMillion?.let { put("promptUsdPerMillion", it) }
                model.completionPerMillion?.let { put("completionUsdPerMillion", it) }
                put("vision", model.vision)
                put("structuredOutputs", model.structuredOutputs)
                put("default", JsonPrimitive(model.id == OpenRouterClient.DEFAULT_MODEL))
              }
            )
          }
        }
          .toString()
      )
      return 0
    }
    if (shown.isEmpty()) {
      println("No OpenRouter model reads pictures and keeps to a reply schema right now.")
      return 0
    }
    val width = shown.maxOf { it.id.length }.coerceAtMost(48)
    println(
      "${"model".padEnd(width)}  ${"in \$/M".padStart(8)}  ${"out \$/M".padStart(8)}  context" +
        if ("--all" in args) "  vision  schema" else ""
    )
    shown.forEach { model ->
      val marker = if (model.id == OpenRouterClient.DEFAULT_MODEL) "  (default)" else ""
      println(
        "${model.id.padEnd(width)}  ${price(model.promptPerMillion)}  " +
          "${price(model.completionPerMillion)}  ${model.contextLength ?: "?"}" +
          (if ("--all" in args) "  ${yes(model.vision)}     ${yes(model.structuredOutputs)}"
          else "") +
          marker
      )
    }
    if ("--all" !in args) {
      println("\nOnly models that read pictures and keep to a reply schema; --all lists the rest.")
    }
    return 0
  }

  private fun price(value: Double?): String =
    (value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "?").padStart(8)

  private fun yes(value: Boolean) = if (value) "yes" else "no "
}
