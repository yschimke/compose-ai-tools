package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Both directions, on the fixture in `src/test/resources/smoke.rc.json`.
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
    checkNotNull(javaClass.getResourceAsStream("/smoke.rc.json")) { "fixture missing" }
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

  private fun kotlinx.serialization.json.JsonElement.typeName(): String? =
    (this as? JsonObject)?.typeName()

  private fun JsonObject.typeName(): String? = (this["type"] as? JsonPrimitive)?.content
}
