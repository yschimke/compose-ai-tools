package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class MutableStringsProfileTest {
  private fun declaration(name: String, value: Any = "Ready") =
    JSONObject().put("type", "mutableString").put("name", name).put("value", value)

  private fun source(vararg nodes: JSONObject) =
    JSONObject()
      .put("compilerProfile", RemoteComposeJson.STATE_PROFILE)
      .put("header", JSONObject().put("width", 100).put("height", 100))
      .put("root", JSONArray(nodes.toList() + JSONObject().put("type", "box")))

  @Test
  fun `equal initial strings own distinct IDs and cannot alias literals`() {
    val document =
      source(
        declaration("first"),
        declaration("second"),
        JSONObject()
          .put("type", "variable")
          .put("vtype", "string")
          .put("name", "literal")
          .put("value", "Ready")
          .put("export", true),
      )
    val bytes = RemoteComposeJson.compile(document.toString())
    val operations = JSONObject(RemoteComposeJson.dump(bytes)).getJSONArray("operations")
    fun objects(value: Any): List<JSONObject> =
      when (value) {
        is JSONObject -> listOf(value) + value.keySet().flatMap { objects(value.get(it)) }
        is JSONArray -> (0 until value.length()).flatMap { objects(value.get(it)) }
        else -> emptyList()
      }
    val names = objects(operations).filter { it.optString("type") == "NamedVariable" }
    assertThat(names.map { it.getString("varName") }).containsExactly("first", "second", "literal")
    assertThat(names.map { it.getInt("varId") }.toSet()).hasSize(3)
    assertThat(RemoteComposeJson.compile(document.toString())).isEqualTo(bytes)
  }

  @Test
  fun `declarations require the explicit state profile`() {
    for (profile in listOf<String?>(null, RemoteComposeJson.INTEGER_EXPRESSIONS_PROFILE)) {
      val document = source(declaration("first"))
      if (profile == null) document.remove("compilerProfile")
      else document.put("compilerProfile", profile)
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.compile(document.toString())
      }
    }
  }

  @Test
  fun `bad declarations and duplicate names are refused with a path`() {
    val invalid =
      listOf(
        arrayOf(declaration("first", JSONObject.NULL)),
        arrayOf(declaration("first", 1)),
        arrayOf(declaration("not a name")),
        arrayOf(declaration("first"), declaration("first")),
        arrayOf(declaration("first").put("export", false)),
      )
    for (nodes in invalid) {
      val error =
        assertThrows(RemoteComposeJsonException::class.java) {
          RemoteComposeJson.compile(source(*nodes).toString())
        }
      assertThat(error).hasMessageThat().contains("mutableString")
      assertThat(error).hasMessageThat().contains("ContextPath")
    }
  }
}
