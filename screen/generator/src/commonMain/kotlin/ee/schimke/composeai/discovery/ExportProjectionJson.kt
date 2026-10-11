package ee.schimke.composeai.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The published spelling of an [ExportProjection], carried verbatim in a component policy's
 * `exportProjection` (compose-preview-contracts' `component-catalog-protocol`):
 * ```json
 * { "variantProperty": "variant",
 *   "targets": [ { "record": "…ButtonKt.FilledTonalButton", "variants": ["filled-tonal"],
 *                  "slots": { "content": "label" }, "events": { "click": "onClick" },
 *                  "parameters": {
 *                    "enabled": { "property": "enabled", "codec": "bool", "omitWhen": true },
 *                    "colors": { "call": "…ButtonDefaults.filledTonalButtonColors",
 *                                "type": "…ButtonColors",
 *                                "arguments": { "containerColor": { "property": "containerColor",
 *                                  "codec": { "color": { "scheme": "…MaterialTheme" } } } } } } } ] }
 * ```
 *
 * A scalar codec is its name (`text`, `bool`, `whole`, `float`); a colour or enum codec is an
 * object naming its kind. A fixed value is a `ScreenValue` in screen-document's own JSON.
 */
object ExportProjectionJson {
  private val screenJson = Json { encodeDefaults = false }

  fun encode(projection: ExportProjection): JsonObject = buildJsonObject {
    projection.variantProperty?.let { put("variantProperty", it) }
    put(
      "targets",
      JsonArray(
        projection.targets.map { target ->
          buildJsonObject {
            put("record", target.record)
            target.variants?.let { put("variants", JsonArray(it.sorted().map(::JsonPrimitive))) }
            if (target.slots.isNotEmpty()) put("slots", strings(target.slots))
            if (target.events.isNotEmpty()) put("events", strings(target.events))
            put("parameters", JsonObject(target.parameters.mapValues { (_, v) -> value(v) }))
          }
        }
      ),
    )
  }

  /** The projection [element] spells, or the reason it cannot be read. */
  fun decode(element: JsonElement): Result<ExportProjection> = runCatching {
    val root = element.jsonObject
    ExportProjection(
      variantProperty = root["variantProperty"]?.jsonPrimitive?.contentOrNull,
      targets =
        root.getValue("targets").jsonArray.map { raw ->
          val target = raw.jsonObject
          ExportProjection.Target(
            record = target.getValue("record").jsonPrimitive.content,
            variants = target["variants"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
            slots = target["slots"]?.let(::stringMap).orEmpty(),
            events = target["events"]?.let(::stringMap).orEmpty(),
            parameters =
              target["parameters"]?.jsonObject.orEmpty().mapValues { (_, v) -> readValue(v) },
          )
        },
    )
  }

  private fun strings(map: Map<String, String>) =
    JsonObject(map.mapValues { JsonPrimitive(it.value) })

  private fun stringMap(element: JsonElement) =
    element.jsonObject.mapValues { it.value.jsonPrimitive.content }

  private fun value(value: ExportProjection.Value): JsonElement =
    when (value) {
      is ExportProjection.Value.Property ->
        buildJsonObject {
          put("property", value.name)
          put("codec", codec(value.codec))
          value.omitWhen?.let { put("omitWhen", it) }
        }
      is ExportProjection.Value.Fixed ->
        buildJsonObject {
          put("fixed", screenJson.encodeToJsonElement(ScreenValue.serializer(), value.value))
        }
      is ExportProjection.Value.Call ->
        buildJsonObject {
          put("call", value.callableFqn)
          put("type", value.typeFqn)
          put("arguments", JsonObject(value.arguments.mapValues { (_, v) -> value(v) }))
        }
    }

  private fun readValue(element: JsonElement): ExportProjection.Value {
    val obj = element.jsonObject
    return when {
      "property" in obj ->
        ExportProjection.Value.Property(
          obj.getValue("property").jsonPrimitive.content,
          readCodec(obj.getValue("codec")),
          obj["omitWhen"]?.jsonPrimitive,
        )
      "fixed" in obj ->
        ExportProjection.Value.Fixed(
          screenJson.decodeFromJsonElement(ScreenValue.serializer(), obj.getValue("fixed"))
        )
      "call" in obj ->
        ExportProjection.Value.Call(
          obj.getValue("call").jsonPrimitive.content,
          obj.getValue("type").jsonPrimitive.content,
          obj["arguments"]?.jsonObject.orEmpty().mapValues { (_, v) -> readValue(v) },
        )
      else -> throw IllegalArgumentException("a projection value is a property, fixed or call")
    }
  }

  private fun codec(codec: ExportProjection.Codec): JsonElement =
    when (codec) {
      ExportProjection.Codec.Text -> JsonPrimitive("text")
      ExportProjection.Codec.Bool -> JsonPrimitive("bool")
      ExportProjection.Codec.Whole -> JsonPrimitive("whole")
      ExportProjection.Codec.Fractional32 -> JsonPrimitive("float")
      is ExportProjection.Codec.Color ->
        buildJsonObject {
          put(
            "color",
            buildJsonObject {
              put("scheme", codec.schemeRootFqn)
              put("members", JsonArray(codec.schemeMembers.map(::JsonPrimitive)))
              if (codec.roles.isNotEmpty()) put("roles", strings(codec.roles))
              codec.tokens?.let { put("tokens", JsonArray(it.sorted().map(::JsonPrimitive))) }
            },
          )
        }
      is ExportProjection.Codec.Enum ->
        buildJsonObject {
          put(
            "enum",
            buildJsonObject {
              put("type", codec.typeFqn)
              put("members", strings(codec.members))
            },
          )
        }
    }

  private fun readCodec(element: JsonElement): ExportProjection.Codec {
    if (element is JsonPrimitive) {
      return when (element.content) {
        "text" -> ExportProjection.Codec.Text
        "bool" -> ExportProjection.Codec.Bool
        "whole" -> ExportProjection.Codec.Whole
        "float" -> ExportProjection.Codec.Fractional32
        else -> throw IllegalArgumentException("unknown codec ${element.content}")
      }
    }
    val obj = element.jsonObject
    obj["color"]?.jsonObject?.let { color ->
      return ExportProjection.Codec.Color(
        schemeRootFqn = color.getValue("scheme").jsonPrimitive.content,
        schemeMembers =
          color["members"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("colorScheme"),
        roles = color["roles"]?.let(::stringMap).orEmpty(),
        tokens = color["tokens"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
      )
    }
    obj["enum"]?.jsonObject?.let { enum ->
      return ExportProjection.Codec.Enum(
        enum.getValue("type").jsonPrimitive.content,
        stringMap(enum.getValue("members")),
      )
    }
    throw IllegalArgumentException("unknown codec $element")
  }
}
