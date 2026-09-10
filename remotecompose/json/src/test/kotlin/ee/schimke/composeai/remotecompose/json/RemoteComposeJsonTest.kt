package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Both directions, on the fixture in `src/test/resources/smoke.authoring.json`.
 *
 * Named `.authoring.json` and not `.rc.json` deliberately. `<stem>.rc.json` is what `rc dump`
 * WRITES, and a fixture holding authoring JSON under the name of a dump output is the same
 * two-dialects confusion this class documents, sitting in the tests that document it.
 *
 * The fixture is small on purpose but not trivial on purpose either: it carries a named colour
 * resource, a modifier shorthand string (`"fillMaxSize"`), an ordered modifier list, a text
 * component and a canvas with a paint op. Those are the five shapes that broke first in every
 * hand-written encoder this replaced — a resource that must be emitted *before* the component
 * referencing it, a modifier the parser expands from a bare string, modifier order mattering, a
 * component whose text lands in a separate `TextData` operation, and a nested command list.
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

    // Modifier ORDER is the assertion, not membership. `.background(...).padding(12)` and
    // `.padding(12).background(...)` produce the same set and different pixels, and the authoring
    // dialect's `"modifiers"` array exists precisely to pin the order — so a dump that lost it
    // would be useless for the diffing it is for.
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
    // The whole point of a header-only read. A document carrying an opcode this `remote-core` does
    // not know — a sticker baked on a newer Remote Compose alpha — still has a readable header, and
    // "which profile, which version does this want" is exactly what someone asks when a document
    // will not play. Inflating the stream to answer it would fail on the question it was asked to
    // settle.
    // A trailing opcode `remote-core` has no reader for. Truncation is NOT the way to build this
    // fixture — the buffer tolerates a short tail and inflates what it has, so a truncated document
    // would leave the second assertion below vacuous and the test claiming something it had not
    // shown.
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
    // `Json.encodeToString` rejects a bare `NaN` / `Infinity` token outright, so putting a
    // non-finite density in as a number took the whole dump down with a `JsonEncodingException` —
    // thrown from outside this module's exception type, i.e. as a stack trace, over one optional
    // header field nothing else depends on.
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
