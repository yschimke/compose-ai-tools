package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Both directions, on `src/test/resources/smoke.authoring.json` (not `.rc.json`, which is what `rc
 * dump` writes — the other dialect).
 *
 * The fixture covers the shapes hand-written encoders broke on first: a named colour resource
 * (emitted before its user), a modifier shorthand string, an ordered modifier list, a text
 * component (separate `TextData` op) and a canvas with a paint op.
 */
class RemoteComposeJsonTest {

  private val authoringJson: String =
    checkNotNull(javaClass.getResourceAsStream("/smoke.authoring.json")) { "fixture missing" }
      .use { it.readBytes().decodeToString() }

  @Test
  fun `compiles authoring json to a playable document`() {
    val bytes = RemoteComposeJson.compile(authoringJson)

    // Not `isNotEmpty`. An empty-but-valid 17-byte header is what compiling a *wrapped* document
    // produces (a generation-library entry puts the document under a `json` key), and it looks
    // exactly like a working build until you play it. Any real document clears this comfortably.
    assertThat(bytes.size).isGreaterThan(64)
  }

  @Test
  fun `header reports the declared size, not the measured one`() {
    val header = RemoteComposeJson.header(RemoteComposeJson.compile(authoringJson))

    assertThat(header.width).isEqualTo(300)
    assertThat(header.height).isEqualTo(300)
    assertThat(header.contentDescription).isEqualTo("Remote Compose JSON smoke")
    assertThat(header.version).matches("""\d+\.\d+\.\d+""")
    assertThat(header.byteLength).isGreaterThan(64)
  }

  @Test
  fun `dumps a compiled document to document json`() {
    val dump = RemoteComposeJson.dumpToJsonObject(RemoteComposeJson.compile(authoringJson))

    assertThat(dump.keys).containsExactly("header", "operations")
    assertThat(dump["header"]!!.jsonObject["width"]!!.jsonPrimitive.content).isEqualTo("300")

    val types = (dump["operations"] as JsonArray).mapNotNull { it.typeName() }
    // The named colour becomes a `ColorConstant` plus the `NamedVariable` that gives it its name —
    // both hoisted ahead of the layout tree, because a reference cannot precede its definition.
    assertThat(types).containsAtLeast("ColorConstant", "NamedVariable", "RootLayoutComponent")
    assertThat(types.indexOf("ColorConstant")).isLessThan(types.indexOf("RootLayoutComponent"))
  }

  @Test
  fun `document json carries the layout tree with its component tags`() {
    val dump = RemoteComposeJson.dumpToJsonObject(RemoteComposeJson.compile(authoringJson))
    val root =
      (dump["operations"] as JsonArray)
        .map { it.jsonObject }
        .single { it.typeName() == "RootLayoutComponent" }

    assertThat(root[JsonMapSerializer.TAGS].toString()).contains("COMPONENT")
    val column =
      (root["list"] as JsonArray).map { it.jsonObject }.single { it.typeName() == "COLUMN" }
    val modifiers =
      (column["list"] as JsonArray)
        .map { it.jsonObject }
        .single { it.typeName() == "ComponentModifiers" }
        .let { it["modifiers"] as JsonArray }
        .map { it.jsonObject.typeName() }

    // Order is the assertion: `.background().padding()` and `.padding().background()` differ in
    // pixels.
    assertThat(modifiers)
      .containsAtLeast(
        "WidthModifierOperation",
        "HeightModifierOperation",
        "BackgroundModifierOperation",
        "PaddingModifierOperation",
      )
      .inOrder()
  }

  @Test
  fun `non-finite floats survive as strings rather than breaking the json`() {
    val dump = RemoteComposeJson.dump(RemoteComposeJson.compile(authoringJson))

    // `fillMaxSize` encodes as a NaN-marked width/height. A dump that emitted a bare `NaN` token
    // would not be JSON at all — `Json.parseToJsonElement` would reject its own output — and one
    // that emitted `null` would erase the difference between "fill" and "unset".
    assertThat(dump).doesNotContain(": NaN")
    assertThat(dump).contains("\"NaN\"")
    kotlinx.serialization.json.Json.parseToJsonElement(dump) // must round-trip as JSON
  }

  @Test
  fun `rejects text that is not a document`() {
    assertThrows(RemoteComposeJsonException::class.java) { RemoteComposeJson.compile("not json") }
    assertThrows(RemoteComposeJsonException::class.java) { RemoteComposeJson.compile("{}") }
  }

  @Test
  fun `rejects bytes that are not a document`() {
    val e =
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.dumpToJsonObject("not a remote compose document".toByteArray())
      }
    assertThat(e).hasMessageThat().contains("bytes")
  }

  @Test
  fun `names the dialect mistake instead of failing arbitrarily`() {
    // `rc dump doc.json` is the shape of it. Left to the inflater this reads the ASCII of
    // `{"header":` as opcodes and dies with something like `Path too long`, which sends the reader
    // after a filesystem problem that does not exist.
    val e =
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.dumpToJsonObject(authoringJson.toByteArray())
      }

    assertThat(e).hasMessageThat().contains("JSON text")
    assertThat(e).hasMessageThat().contains("compile")

    // `header` is the same entry point for the same slip — `rc header doc.json` — so it refuses
    // the same way. It read the bytes directly until this was pinned, and answered with whatever
    // the wire-format reader made of `{"header":`.
    val fromHeader =
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.header(authoringJson.toByteArray())
      }
    assertThat(fromHeader).hasMessageThat().contains("JSON text")
  }

  @Test
  fun `header reads a document whose later operations cannot be inflated`() {
    // The point of a header-only read: a document with an opcode this `remote-core` doesn't know
    // still has a readable header. The fixture appends an unknown opcode rather than truncating,
    // since the buffer tolerates a short tail and truncation would make the second assertion
    // vacuous.
    val unreadable = RemoteComposeJson.compile(authoringJson) + byteArrayOf(110, 0, 0, 0)

    val header = RemoteComposeJson.header(unreadable)

    assertThat(header.width).isEqualTo(300)
    assertThat(header.contentDescription).isEqualTo("Remote Compose JSON smoke")
    // And the full projection genuinely cannot read it, so the assertion above is not vacuous.
    assertThrows(RemoteComposeJsonException::class.java) {
      RemoteComposeJson.dumpToJsonObject(unreadable)
    }
  }

  @Test
  fun `a null collection is not an empty one`() {
    val serializer = JsonMapSerializer()

    serializer.add("absentList", null as List<String>?)
    serializer.add("emptyList", emptyList<String>())
    serializer.add("absentMap", null as Map<String, String>?)
    serializer.add("emptyMap", emptyMap<String, String>())

    // Collapsing these to `[]` / `{}` would mean an operation whose optional list went from absent
    // to present-and-empty produced no diff at all — a direct hit on the one job this projection
    // has. Every other nullable overload here already preserves the distinction.
    val result = serializer.result()
    assertThat(result["absentList"]).isEqualTo(kotlinx.serialization.json.JsonNull)
    assertThat(result["emptyList"].toString()).isEqualTo("[]")
    assertThat(result["absentMap"]).isEqualTo(kotlinx.serialization.json.JsonNull)
    assertThat(result["emptyMap"].toString()).isEqualTo("{}")
  }

  @Test
  fun `a path compiles to real geometry, not a stub`() {
    // Pinned because this file used to claim the opposite. The default platform's text measurement
    // IS a stub; its path parsing is not, and the difference matters — a caller told that geometry
    // is mangled off-device would reach for a real player it does not need.
    val document =
      RemoteComposeJson.compile(
        """{"header":{"width":100,"height":100},"root":[{"canvas":{"modifiers":[{"size":""" +
          """[100.0,100.0]}],"commands":[{"type":"drawPath","path":"M 10 10 L 90 10 L 90 90 Z"}]""" +
          """}}]}"""
      )

    val path = RemoteComposeJson.dumpToJsonObject(document).find("PathData")!!["path"] as JsonArray

    // `@10` / `@11` / `@15` are MOVE / LINE / CLOSE as NaN-encoded opcodes, and the coordinates
    // between them are the triangle that was written. Asserting the coordinates rather than just
    // the opcodes is the point: a stub could plausibly emit the verbs and drop the numbers.
    assertThat(path.take(8).map { it.jsonPrimitive.content })
      .containsExactly("@10", "10.0", "10.0", "@11", "0.0", "0.0", "90.0", "10.0")
      .inOrder()
  }

  @Test
  fun `a non-finite density does not break the projection`() {
    // A non-finite density as a JSON number would make `Json.encodeToString` throw over one
    // optional field.
    val header =
      RemoteComposeDocumentHeader(
        version = "1.1.0",
        width = 10,
        height = 10,
        contentDescription = null,
        profiles = null,
        desiredFps = null,
        densityAtGeneration = Float.NaN,
        byteLength = 39,
      )

    val encoded = Json.encodeToString(JsonObject.serializer(), header.toJsonObject())

    assertThat(encoded).contains("\"densityAtGeneration\":\"NaN\"")
  }

  @Test
  fun `the header serializer and the projection agree on field names`() {
    val header =
      RemoteComposeDocumentHeader(
        version = "1.1.0",
        width = 10,
        height = 10,
        contentDescription = null,
        profiles = null,
        desiredFps = 30,
        densityAtGeneration = null,
        byteLength = 39,
      )

    // A consumer of the published type may serialize it directly rather than through
    // `toJsonObject()`, and until `@SerialName` was added the two disagreed on exactly one field —
    // so a `jq` query written against a dump's header block missed silently on the other shape.
    val generated = Json.encodeToString(RemoteComposeDocumentHeader.serializer(), header)
    assertThat(generated).contains("\"desiredFPS\":30")
    assertThat(header.toJsonObject()["desiredFPS"]!!.jsonPrimitive.content).isEqualTo("30")
  }

  @Test
  fun `a header round-trips through its own json`() {
    val header = RemoteComposeJson.header(RemoteComposeJson.compile(authoringJson))

    // `toJsonObject()` omits what the document did not declare, and without null defaults on the
    // constructor the generated decoder treats every nullable parameter as required — so a
    // consumer was handed JSON by this type that the same type could not read back.
    val emitted = Json.encodeToString(JsonObject.serializer(), header.toJsonObject())
    val decoded = Json.decodeFromString(RemoteComposeDocumentHeader.serializer(), emitted)

    assertThat(decoded.width).isEqualTo(300)
    assertThat(decoded.contentDescription).isEqualTo("Remote Compose JSON smoke")

    // The sparse shape is the one that actually failed: a header naming nothing but its version.
    val sparse =
      Json.decodeFromString(
        RemoteComposeDocumentHeader.serializer(),
        """{"version":"1.1.0","byteLength":17}""",
      )
    assertThat(sparse.width).isNull()
    assertThat(sparse.desiredFps).isNull()
  }

  @Test
  fun `a non-finite density round-trips through the public serializer`() {
    // `Json` cannot write a bare NaN, so the generated serializer used to refuse the whole header
    // over one optional field — while `toJsonObject()` emitted a perfectly good string for it. A
    // consumer should not have to know which of the two produced their JSON.
    val header = RemoteComposeDocumentHeader(version = "1.1.0", byteLength = 17)

    for ((value, wire) in
      listOf(
        Float.NaN to "\"NaN\"",
        Float.POSITIVE_INFINITY to "\"Infinity\"",
        Float.NEGATIVE_INFINITY to "\"-Infinity\"",
        2.0f to "2.0",
      )) {
      val encoded =
        Json.encodeToString(
          RemoteComposeDocumentHeader.serializer(),
          header.copy(densityAtGeneration = value),
        )
      assertWithMessage(encoded).that(encoded).contains("\"densityAtGeneration\":$wire")

      val decoded = Json.decodeFromString(RemoteComposeDocumentHeader.serializer(), encoded)
      assertThat(decoded.densityAtGeneration).isEqualTo(value)
    }

    // An id-bearing NaN keeps its payload, which is the case that makes this worth encoding rather
    // than dropping: a non-finite float in this format is usually a reference, not a missing value.
    val encoded =
      Json.encodeToString(
        RemoteComposeDocumentHeader.serializer(),
        header.copy(densityAtGeneration = androidx.compose.remote.core.operations.Utils.asNan(42)),
      )
    assertThat(encoded).contains("\"densityAtGeneration\":\"@42\"")
    val decoded = Json.decodeFromString(RemoteComposeDocumentHeader.serializer(), encoded)
    assertThat(
        androidx.compose.remote.core.operations.Utils.idFromNan(decoded.densityAtGeneration!!)
      )
      .isEqualTo(42)
  }

  /** The first operation of [type] anywhere in the projection, nesting included. */
  private fun kotlinx.serialization.json.JsonElement.find(type: String): JsonObject? =
    when (this) {
      is JsonObject ->
        if (typeName() == type) this else values.firstNotNullOfOrNull { it.find(type) }
      is JsonArray -> firstNotNullOfOrNull { it.find(type) }
      else -> null
    }

  private fun kotlinx.serialization.json.JsonElement.typeName(): String? =
    (this as? JsonObject)?.typeName()

  private fun JsonObject.typeName(): String? = (this["type"] as? JsonPrimitive)?.content
}
