package ee.schimke.composeai.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 *
 * A target's `contexts`, keyed by structural role, carry the further `parameters` it writes there
 * and the `modifier` links (screen-document `ChainLink`s) appended after the node's own chain:
 * ```json
 * "contexts": { "list-item": {
 *   "parameters": { "transformation": { "fixed": … } },
 *   "modifier": [ { "callableFqn": "…minimumVerticalContentPadding", "positional": [ … ] } ] } }
 * ```
 *
 * A target's `slotPatterns`, keyed by wire slot, recognise its children: matchers are a `component`
 * (optionally `optional`), `lines` of a text component (`min`, `max`, extra lines to `rest`) or
 * `any` remaining children, and `otherwise` is `"refuse"` or `{ "to": parameter }`:
 * ```json
 * "slotPatterns": { "content": {
 *   "patterns": [ { "match": [
 *     { "component": "wear-m3/icon", "optional": true, "to": "icon" },
 *     { "lines": { "text": "wear-m3/text", "min": 1, "max": 2 }, "to": ["label", "secondaryLabel"] } ] } ],
 *   "otherwise": { "to": "content" } } }
 * ```
 *
 * A target's `synthesize`, keyed by composable parameter, writes it as a child of another catalog
 * component whose properties are copied from the parent's:
 * ```json
 * "synthesize": { "content": { "component": "wear-m3/text", "properties": { "text": "text" } } }
 * ```
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
            if (target.contexts.isNotEmpty()) {
              put("contexts", JsonObject(target.contexts.mapValues { (_, c) -> context(c) }))
            }
            if (target.synthesize.isNotEmpty()) {
              put(
                "synthesize",
                JsonObject(
                  target.synthesize.mapValues { (_, synthesis) ->
                    buildJsonObject {
                      put("component", synthesis.component)
                      put("properties", strings(synthesis.properties))
                    }
                  }
                ),
              )
            }
            if (target.slotPatterns.isNotEmpty()) {
              put(
                "slotPatterns",
                JsonObject(target.slotPatterns.mapValues { (_, p) -> slotPatterns(p) }),
              )
            }
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
            contexts =
              target["contexts"]?.jsonObject.orEmpty().mapValues { (_, c) -> readContext(c) },
            slotPatterns =
              target["slotPatterns"]?.jsonObject.orEmpty().mapValues { (_, p) ->
                readSlotPatterns(p)
              },
            synthesize =
              target["synthesize"]?.jsonObject.orEmpty().mapValues { (_, raw) ->
                ExportProjection.Synthesis(
                  raw.jsonObject.getValue("component").jsonPrimitive.content,
                  stringMap(raw.jsonObject.getValue("properties")),
                )
              },
          )
        },
    )
  }

  private fun context(context: ExportProjection.Context): JsonObject = buildJsonObject {
    if (context.parameters.isNotEmpty()) {
      put("parameters", JsonObject(context.parameters.mapValues { (_, v) -> value(v) }))
    }
    if (context.modifier.isNotEmpty()) {
      put(
        "modifier",
        JsonArray(
          context.modifier.map { screenJson.encodeToJsonElement(ChainLink.serializer(), it) }
        ),
      )
    }
  }

  private fun readContext(element: JsonElement): ExportProjection.Context {
    val obj = element.jsonObject
    return ExportProjection.Context(
      parameters = obj["parameters"]?.jsonObject.orEmpty().mapValues { (_, v) -> readValue(v) },
      modifier =
        obj["modifier"]?.jsonArray.orEmpty().map {
          screenJson.decodeFromJsonElement(ChainLink.serializer(), it)
        },
    )
  }

  private fun slotPatterns(patterns: ExportProjection.SlotPatterns): JsonObject = buildJsonObject {
    put(
      "patterns",
      JsonArray(
        patterns.patterns.map { pattern ->
          buildJsonObject { put("match", JsonArray(pattern.match.map(::matcher))) }
        }
      ),
    )
    put(
      "otherwise",
      patterns.otherwise?.let { buildJsonObject { put("to", it) } } ?: JsonPrimitive("refuse"),
    )
  }

  private fun matcher(matcher: ExportProjection.Matcher): JsonObject = buildJsonObject {
    when (matcher) {
      is ExportProjection.Matcher.Component -> {
        put("component", matcher.componentId)
        if (matcher.optional) put("optional", true)
        put("to", matcher.to)
      }
      is ExportProjection.Matcher.Lines -> {
        put(
          "lines",
          buildJsonObject {
            put("text", matcher.textComponent)
            put("min", matcher.min)
            matcher.max?.let { put("max", it) }
          },
        )
        put("to", JsonArray(matcher.to.map(::JsonPrimitive)))
        matcher.rest?.let { put("rest", it) }
      }
      is ExportProjection.Matcher.Rest -> {
        put("any", true)
        put("to", matcher.to)
      }
    }
  }

  private fun readSlotPatterns(element: JsonElement): ExportProjection.SlotPatterns {
    val obj = element.jsonObject
    val otherwise =
      when (val raw = obj["otherwise"]) {
        null -> null
        is JsonPrimitive ->
          if (raw.content == "refuse") null
          else throw IllegalArgumentException("`otherwise` is \"refuse\" or {\"to\": …}")
        else -> raw.jsonObject.getValue("to").jsonPrimitive.content
      }
    return ExportProjection.SlotPatterns(
      patterns =
        obj["patterns"]?.jsonArray.orEmpty().map { pattern ->
          ExportProjection.SlotPattern(
            pattern.jsonObject.getValue("match").jsonArray.map { readMatcher(it.jsonObject) }
          )
        },
      otherwise = otherwise,
    )
  }

  private fun readMatcher(obj: JsonObject): ExportProjection.Matcher =
    when {
      "component" in obj ->
        ExportProjection.Matcher.Component(
          obj.getValue("component").jsonPrimitive.content,
          obj.getValue("to").jsonPrimitive.content,
          obj["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
      "lines" in obj -> {
        val lines = obj.getValue("lines").jsonObject
        ExportProjection.Matcher.Lines(
          textComponent = lines.getValue("text").jsonPrimitive.content,
          to = obj.getValue("to").jsonArray.map { it.jsonPrimitive.content },
          min = lines["min"]?.jsonPrimitive?.content?.toInt() ?: 1,
          max = lines["max"]?.jsonPrimitive?.content?.toInt(),
          rest = obj["rest"]?.jsonPrimitive?.contentOrNull,
        )
      }
      "any" in obj -> ExportProjection.Matcher.Rest(obj.getValue("to").jsonPrimitive.content)
      else -> throw IllegalArgumentException("a slot matcher is component, lines or any")
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
      is ExportProjection.Codec.Unit ->
        buildJsonObject {
          put(
            "unit",
            buildJsonObject {
              put("unit", codec.unit)
              if (codec.positive) put("positive", true)
            },
          )
        }
      is ExportProjection.Codec.Token ->
        buildJsonObject {
          put(
            "token",
            buildJsonObject {
              put("root", codec.rootFqn)
              put("members", JsonArray(codec.members.map(::JsonPrimitive)))
              put("type", codec.typeFqn)
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
    obj["unit"]?.jsonObject?.let { unit ->
      return ExportProjection.Codec.Unit(
        unit.getValue("unit").jsonPrimitive.content,
        unit["positive"]?.jsonPrimitive?.booleanOrNull ?: false,
      )
    }
    obj["token"]?.jsonObject?.let { token ->
      return ExportProjection.Codec.Token(
        rootFqn = token.getValue("root").jsonPrimitive.content,
        members = token.getValue("members").jsonArray.map { it.jsonPrimitive.content },
        typeFqn = token.getValue("type").jsonPrimitive.content,
        tokens = token["tokens"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
      )
    }
    throw IllegalArgumentException("unknown codec $element")
  }
}
