package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The value types beyond text, numbers, lists and objects: `Long` and `Double`, text with a syntax
 * of its own (an [Instant], a hex key), null, sealed types, and callbacks that hand back arguments.
 */
@OptIn(ExperimentalTime::class)
class TypedValueTypesTest {
  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun parameter(
    name: String,
    type: String,
    fqn: String,
    nullable: Boolean = false,
    default: Boolean = false,
  ) =
    TargetParameter.Builder(name, type)
      .also {
        it.typeFqn = fqn
        it.nullable = nullable
        it.hasDefault = default
        if (fqn.startsWith("kotlin.Function")) it.lambdaReturnTypeFqn = "kotlin.Unit"
      }
      .build()

  private fun record(vararg parameters: TargetParameter) =
    ComponentRecord.Builder(
        canonicalId = "app/com.example.StatusKt.Status",
        symbol =
          ComponentSymbol.Builder(
              "com.example.StatusKt",
              "com.example.Status",
              "Status",
              ComponentOrigin.PROJECT,
            )
            .build(),
      )
      .also {
        it.signatureKnown = true
        it.parameters = parameters.toList()
        it.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "Status()"
              c.imports = listOf("com.example.Status")
            }
            .build()
      }
      .build()

  @Test
  fun `long and double store int and number wrappers and keep their range and precision`() {
    val big = 9_007_199_254_740_991L
    assertThat(AdapterValueCodecs.Long.wrap(big)).isEqualTo(json("""{"type":"int","value":$big}"""))
    assertThat(AdapterValueCodecs.Long.unwrap(json("""{"type":"int","value":$big}""")))
      .isEqualTo(big)
    assertThat(AdapterValueCodecs.Long.kotlinType).isEqualTo("kotlin.Long")
    assertThat(AdapterValueCodecs.Long.stateType).isNull()
    assertThat(AdapterValueCodecs.Double.wrap(59.437))
      .isEqualTo(json("""{"type":"number","value":59.437}"""))
    assertThat(AdapterValueCodecs.Double.stateType).isEqualTo("number")
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Long.unwrap(json("""{"type":"int","value":"1"}"""))
    }
    // Beyond JavaScript's safe integers, which a browser's JSON.parse would round.
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Long.unwrap(json("""{"type":"int","value":9007199254740993}"""))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Long.encode(9_007_199_254_740_992L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdapterValueCodecs.Double.encode(Double.NaN)
    }
  }

  @Test
  fun `an instant is ISO text with a date-time format and refuses what it cannot parse`() {
    val codec = AdapterValueCodecs.Instant
    val at = Instant.parse("2026-10-12T08:00:00Z")
    assertThat(codec.wrap(at))
      .isEqualTo(json("""{"type":"string","value":"2026-10-12T08:00:00Z"}"""))
    assertThat(codec.unwrap(codec.wrap(at))).isEqualTo(at)
    assertThat(codec.shape()["format"]).isEqualTo(JsonPrimitive("date-time"))
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        codec.unwrap(json("""{"type":"string","value":"yesterday"}"""))
      }
    assertThat(failure.message).contains("yesterday")
  }

  @Test
  fun `a nullable codec stores null as its own wrapper and publishes nullable`() {
    val codec = AdapterValueCodecs.nullable(AdapterValueCodecs.String)
    assertThat(codec.wrap(null)).isEqualTo(json("""{"type":"null"}"""))
    assertThat(codec.wrap("Ada")).isEqualTo(json("""{"type":"string","value":"Ada"}"""))
    assertThat(codec.unwrap(json("""{"type":"null"}"""))).isNull()
    assertThat(codec.unwrap(json("""{"type":"string","value":"Ada"}"""))).isEqualTo("Ada")
    assertThat(codec.literalType).isEqualTo("string")
    assertThat(codec.stateType).isNull()
    assertThat(codec.shape()["nullable"]).isEqualTo(JsonPrimitive(true))
    assertThrows(IllegalArgumentException::class.java) {
      codec.unwrap(json("""{"type":"null","value":null}"""))
    }
    // A nullable object keeps its recursive editor, and a field of it may be left null.
    val profile = AdapterValueCodecs.nullable(statusFailedCodec)
    assertThat(profile.editor).isEqualTo(statusFailedCodec.editor)
    assertThat(profile.unwrap(json("""{"type":"null"}"""))).isNull()
  }

  @Test
  fun `a union stores its case and fields and refuses unknown cases and stray fields`() {
    val codec = statusCodec
    assertThat(codec.wrap(FixtureStatus.Idle)).isEqualTo(json("""{"type":"union","case":"idle"}"""))
    val failed = FixtureStatus.Failed("timeout", retries = 2)
    val wrapped = codec.wrap(failed)
    assertThat(wrapped)
      .isEqualTo(
        json(
          """{"type":"union","case":"failed","fields":{"message":{"type":"string","value":"timeout"},"retries":{"type":"int","value":2}}}"""
        )
      )
    assertThat(codec.unwrap(wrapped)).isEqualTo(failed)
    assertThat(codec.unwrap(json("""{"type":"union","case":"idle"}""")))
      .isEqualTo(FixtureStatus.Idle)
    assertThat(
        codec.unwrap(
          json(
            """{"type":"union","case":"failed","fields":{"message":{"type":"string","value":"x"}}}"""
          )
        )
      )
      .isEqualTo(FixtureStatus.Failed("x", retries = 0))
    for (bad in
      listOf(
        """{"type":"union","case":"lost"}""",
        """{"type":"union","case":"idle","fields":{}}""",
        """{"type":"union","case":"failed"}""",
        """{"type":"object","fields":{}}""",
        """{"type":"union","case":"failed","fields":{"code":{"type":"int","value":1},"message":{"type":"string","value":"x"}}}""",
      )) {
      assertThrows(bad, IllegalArgumentException::class.java) { codec.unwrap(json(bad)) }
    }
    val editor = codec.editor!!
    assertThat(editor["control"]).isEqualTo(JsonPrimitive("union"))
    assertThat(editor["unionType"]).isEqualTo(JsonPrimitive(STATUS))
    val cases = editor.getValue("cases").jsonArray.map { it.jsonObject }
    assertThat(cases.map { it["name"] })
      .containsExactly(JsonPrimitive("idle"), JsonPrimitive("failed"))
      .inOrder()
    assertThat(cases[0].keys).containsExactly("name", "objectType")
    assertThat(cases[1].getValue("fields").jsonArray).hasSize(2)
  }

  @Test
  fun `nullable parameters need a nullable codec and the other way round`() {
    val nullableSeen = record(parameter("seen", "Instant?", "kotlin.time.Instant", nullable = true))
    val plainSeen = record(parameter("seen", "Instant", "kotlin.time.Instant"))
    class Nullable(record: ComponentRecord) :
      TypedComponentAdapter<SeenProps>("acme/seen", record) {
      val seen =
        property(SeenProps::seen, AdapterValueCodecs.nullable(AdapterValueCodecs.Instant), null)
    }
    assertThat(Nullable(nullableSeen).validateAgainst(nullableSeen)).isEmpty()
    assertThat(Nullable(plainSeen).validateAgainst(plainSeen))
      .containsExactly(
        "acme/seen.seen: expected kotlin.time.Instant?, discovered kotlin.time.Instant"
      )
    val capability = Nullable(nullableSeen).seen.capability().jsonObject
    assertThat(capability["nullable"]).isEqualTo(JsonPrimitive(true))
    assertThat(capability["format"]).isEqualTo(JsonPrimitive("date-time"))
  }

  @Test
  fun `callbacks with arguments are events of their arity, nullable or not`() {
    val target =
      record(
        parameter("onPick", "(Contact) -> Unit", "kotlin.Function1"),
        parameter("onConnect", "(String, Int) -> Unit", "kotlin.Function2"),
        parameter("onRetry", "(() -> Unit)?", "kotlin.Function0", nullable = true),
      )
    class Callbacks(record: ComponentRecord) :
      TypedComponentAdapter<CallbackProps>("acme/callbacks", record) {
      val pick = event(CallbackProps::onPick)
      val connect = event(CallbackProps::onConnect)
      val retry = event(CallbackProps::onRetry)
    }
    val adapter = Callbacks(target)
    assertThat(adapter.validateAgainst(target)).isEmpty()
    assertThat(adapter.pick.arity).isEqualTo(1)
    assertThat(adapter.connect.arity).isEqualTo(2)
    assertThat(adapter.pick.eventName).isEqualTo("pick")
    val swapped =
      record(
        parameter("onPick", "(String, Int) -> Unit", "kotlin.Function2"),
        parameter("onConnect", "(String, Int) -> Unit", "kotlin.Function2"),
        parameter("onRetry", "() -> Unit", "kotlin.Function0"),
      )
    assertThat(Callbacks(swapped).validateAgainst(swapped))
      .containsExactly("acme/callbacks: onPick is not a (A) -> Unit callback")
  }

  @Test
  fun `an export writes each new type as Kotlin and leaves a null to the parameter's default`() {
    val target =
      record(
        parameter("status", "Status", STATUS),
        parameter("key", "Key", KEY, default = true),
        parameter("seen", "Instant?", "kotlin.time.Instant", nullable = true, default = true),
        parameter("storedKb", "Long", "kotlin.Long", default = true),
        parameter("latitude", "Double", "kotlin.Double", default = true),
      )
    class Exported(record: ComponentRecord) :
      TypedComponentAdapter<ExportedProps>("acme/status", record) {
      val status = property(ExportedProps::status, statusCodec, FixtureStatus.Idle)
      val key = property(ExportedProps::key, keyCodec, FixtureKey("00ff"))
      val seen =
        property(ExportedProps::seen, AdapterValueCodecs.nullable(AdapterValueCodecs.Instant), null)
      val storedKb = property(ExportedProps::storedKb, AdapterValueCodecs.Long, 0L)
      val latitude = property(ExportedProps::latitude, AdapterValueCodecs.Double, 0.0)

      init {
        exportAs(
          target(
            record,
            mapOf(
              "status" to from(status),
              "key" to from(key),
              "seen" to from(seen),
              "storedKb" to from(storedKb),
              "latitude" to from(latitude),
            ),
          )
        )
      }
    }
    val adapter = Exported(target)
    assertThat(adapter.validateAgainst(target)).isEmpty()
    val projection = adapter.projection!!
    assertThat(ExportProjectionJson.decode(ExportProjectionJson.encode(projection)).getOrThrow())
      .isEqualTo(projection)
    val failed =
      projection.lower(
        mapOf(
          "status" to
            json(
              """{"type":"union","case":"failed","fields":{"message":{"type":"string","value":"timeout"}}}"""
            ),
          "key" to json("""{"type":"string","value":"a1b2"}"""),
          "seen" to json("""{"type":"null"}"""),
          "storedKb" to json("""{"type":"int","value":4294967296}"""),
          "latitude" to json("""{"type":"number","value":59.437}"""),
        )
      ) as ExportProjection.Lowered.Node
    assertThat(failed.arguments.keys).containsExactly("status", "key", "storedKb", "latitude")
    val source = generated(failed, target)
    assertThat(source)
      .contains(
        "status = FixtureStatus.Failed(message = \"timeout\", retries = 0), key = FixtureKey.fromHex(\"a1b2\"), storedKb = 4294967296L, latitude = 59.437"
      )
    val idle =
      projection.lower(
        mapOf(
          "status" to json("""{"type":"union","case":"idle"}"""),
          "seen" to json("""{"type":"string","value":"2026-10-12T08:00:00Z"}"""),
        )
      ) as ExportProjection.Lowered.Node
    val idleSource = generated(idle, target)
    assertThat(idleSource).contains("status = FixtureStatus.Idle")
    assertThat(idleSource).contains("seen = Instant.parse(\"2026-10-12T08:00:00Z\")")
    assertThat(idleSource).contains("import kotlin.time.Instant")
    assertThat(projection.qualifiedOwners)
      .containsAtLeast("kotlin.time.Instant", "ee.schimke.composeai.discovery.FixtureKey")
  }

  @Test
  fun `a null list element and an unknown case are refused with their path`() {
    val target = record(parameter("names", "List<String?>", "kotlin.collections.List"))
    class Names(record: ComponentRecord) : TypedComponentAdapter<NamesProps>("acme/names", record) {
      val names =
        property(
          NamesProps::names,
          AdapterValueCodecs.list(AdapterValueCodecs.nullable(AdapterValueCodecs.String)),
          emptyList(),
        )

      init {
        exportAs(target(record, mapOf("names" to from(names))))
      }
    }
    val refused =
      Names(target)
        .projection!!
        .lower(mapOf("names" to json("""{"type":"list","values":[{"type":"null"}]}""")))
    assertThat((refused as ExportProjection.Lowered.Refused).reasons)
      .containsExactly("app/com.example.StatusKt.Status.names <- names[0]: cannot be null here")
  }

  @Test
  fun `a set is stored as a list, refuses repeats and exports setOf`() {
    val codec = AdapterValueCodecs.set(AdapterValueCodecs.String)
    val wrapped = codec.wrap(linkedSetOf("a", "b"))
    assertThat(wrapped)
      .isEqualTo(
        json(
          """{"type":"list","values":[{"type":"string","value":"a"},{"type":"string","value":"b"}]}"""
        )
      )
    assertThat(codec.unwrap(wrapped)).containsExactly("a", "b").inOrder()
    assertThat(codec.kotlinType).isEqualTo("kotlin.collections.Set")
    assertThat(codec.editor).isEqualTo(AdapterValueCodecs.list(AdapterValueCodecs.String).editor)
    assertThrows(IllegalArgumentException::class.java) {
      codec.unwrap(
        json(
          """{"type":"list","values":[{"type":"string","value":"a"},{"type":"string","value":"a"}]}"""
        )
      )
    }
  }

  @Test
  fun `an enum value decodes to the member and exports its qualified name`() {
    val codec = AdapterValueCodecs.enumValue(LEVEL, FixtureLevel.entries)
    assertThat(codec.wrap(FixtureLevel.High))
      .isEqualTo(json("""{"type":"string","value":"High"}"""))
    assertThat(codec.unwrap(json("""{"type":"string","value":"Low"}""")))
      .isEqualTo(FixtureLevel.Low)
    assertThat(codec.allowedValues).containsExactly("Low", "High")
    assertThrows(IllegalArgumentException::class.java) {
      codec.unwrap(json("""{"type":"string","value":"Medium"}"""))
    }
  }

  @Test
  fun `a set and an enum value are exported as setOf and the member`() {
    val target =
      record(
        parameter("tags", "Set<String>", "kotlin.collections.Set"),
        parameter("level", "Level", LEVEL, default = true),
      )
    class Tagged(record: ComponentRecord) :
      TypedComponentAdapter<TaggedProps>("acme/tagged", record) {
      val tags =
        property(TaggedProps::tags, AdapterValueCodecs.set(AdapterValueCodecs.String), emptySet())
      val level =
        property(
          TaggedProps::level,
          AdapterValueCodecs.enumValue(LEVEL, FixtureLevel.entries),
          FixtureLevel.Low,
        )

      init {
        exportAs(target(record, mapOf("tags" to from(tags), "level" to from(level))))
      }
    }
    val adapter = Tagged(target)
    assertThat(adapter.validateAgainst(target)).isEmpty()
    val projection = adapter.projection!!
    assertThat(ExportProjectionJson.decode(ExportProjectionJson.encode(projection)).getOrThrow())
      .isEqualTo(projection)
    val node =
      projection.lower(
        mapOf(
          "tags" to json("""{"type":"list","values":[{"type":"string","value":"vip"}]}"""),
          "level" to json("""{"type":"string","value":"High"}"""),
        )
      ) as ExportProjection.Lowered.Node
    val source = generated(node, target)
    assertThat(source).contains("tags = setOf(\"vip\")")
    assertThat(source).contains("level = FixtureLevel.High")
    assertThat(source).contains("import kotlin.collections.setOf")
    val repeated =
      projection.lower(
        mapOf(
          "tags" to
            json(
              """{"type":"list","values":[{"type":"string","value":"vip"},{"type":"string","value":"vip"}]}"""
            )
        )
      )
    assertThat((repeated as ExportProjection.Lowered.Refused).reasons.single())
      .contains("a set holds each value once")
  }

  private fun generated(node: ExportProjection.Lowered.Node, record: ComponentRecord): String {
    val result =
      ScreenGenerator.generate(
        ScreenDocument(
          name = "Projected",
          root = ScreenNode(node.record, arguments = node.arguments),
        ),
        ComponentRecordFile.Builder("app", "debug", listOf(record)).build(),
        expressionPackages =
          setOf(
            "kotlin.time",
            "kotlin.collections",
            "ee.schimke.composeai.discovery",
            "com.example",
          ),
      )
    assertThat(result).isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
    return (result as ScreenGenerator.Result.Emitted).source
  }
}

private const val STATUS = "ee.schimke.composeai.discovery.FixtureStatus"
private const val KEY = "ee.schimke.composeai.discovery.FixtureKey"
private const val LEVEL = "ee.schimke.composeai.discovery.FixtureLevel"

internal enum class FixtureLevel {
  Low,
  High,
}

private data class TaggedProps(val tags: Set<String>, val level: FixtureLevel)

internal sealed interface FixtureStatus {
  data object Idle : FixtureStatus

  data class Failed(val message: String, val retries: Int = 0) : FixtureStatus
}

internal data class FixtureKey(val hex: String) {
  companion object {
    fun fromHex(hex: String): FixtureKey {
      require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" }) { "not hex: $hex" }
      return FixtureKey(hex)
    }
  }
}

private val statusFailedCodec: AdapterObjectCodec<FixtureStatus.Failed> =
  AdapterValueCodecs.obj("$STATUS.Failed") {
    val message = field(FixtureStatus.Failed::message, AdapterValueCodecs.String)
    val retries = field(FixtureStatus.Failed::retries, AdapterValueCodecs.Int, default = 0)
    construct { FixtureStatus.Failed(it[message], it[retries]) }
  }

private val statusCodec: AdapterValueCodec<FixtureStatus> =
  AdapterValueCodecs.union(STATUS) {
    singleton("idle", FixtureStatus.Idle, "$STATUS.Idle")
    case("failed", statusFailedCodec)
  }

private val keyCodec: AdapterValueCodec<FixtureKey> =
  AdapterValueCodecs.parsed(KEY, "$KEY.fromHex", "hex", FixtureKey::fromHex, FixtureKey::hex)

@OptIn(ExperimentalTime::class) private data class SeenProps(val seen: Instant?)

private data class CallbackProps(
  val onPick: (TypedFixtureContact) -> Unit,
  val onConnect: (String, Int) -> Unit,
  val onRetry: () -> Unit,
)

@OptIn(ExperimentalTime::class)
private data class ExportedProps(
  val status: FixtureStatus,
  val key: FixtureKey,
  val seen: Instant?,
  val storedKb: Long,
  val latitude: Double,
)

private data class NamesProps(val names: List<String?>)
