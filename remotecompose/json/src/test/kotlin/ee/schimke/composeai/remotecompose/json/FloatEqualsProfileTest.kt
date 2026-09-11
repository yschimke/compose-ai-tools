package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class FloatEqualsProfileTest {
  private fun comparison(left: Any = "@page", right: Any = 1.25) =
    JSONObject()
      .put("type", "floatEquals")
      .put("name", "match")
      .put("left", left)
      .put("right", right)

  private fun source(vararg nodes: JSONObject): JSONObject {
    val resources =
      JSONObject()
        .put("type", "resources")
        .put(
          "variables",
          JSONObject().put("page", JSONObject().put("value", 1.25).put("export", true)),
        )
    val root = JSONObject().put("type", "box").put("children", JSONArray(nodes.toList()))
    return JSONObject()
      .put("compilerProfile", RemoteComposeJson.STATE_PROFILE)
      .put("header", JSONObject().put("width", 100).put("height", 100))
      .put("root", JSONArray().put(resources).put(root))
  }

  @Test
  fun `comparison emits standard float and integer expressions deterministically`() {
    val source =
      source(
        comparison(),
        JSONObject()
          .put("type", "integerExpression")
          .put("name", "index")
          .put("value", "1 - @match"),
      )
    val bytes = RemoteComposeJson.compile(source.toString())
    assertThat(RemoteComposeJson.compile(source.toString())).isEqualTo(bytes)
    val dump = RemoteComposeJson.dump(bytes)
    assertThat(dump).contains("FloatExpression")
    assertThat(dump).contains("IntegerExpression")
  }

  @Test
  fun `unextended profiles refuse float comparison`() {
    for (profile in listOf<String?>(null, RemoteComposeJson.INTEGER_EXPRESSIONS_PROFILE)) {
      val source = source(comparison())
      if (profile == null) source.remove("compilerProfile")
      else source.put("compilerProfile", profile)
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.compile(source.toString())
      }
    }
  }

  @Test
  fun `invalid operands and names are located refusals`() {
    val invalid =
      listOf(
        comparison("@missing"),
        comparison("@page + 1"),
        comparison(true),
        comparison(JSONObject.NULL),
        comparison(1e100),
        comparison().put("name", "page"),
        comparison().put("name", "not a name"),
        comparison().put("extra", 1),
      )
    for (node in invalid) {
      val error =
        assertThrows(RemoteComposeJsonException::class.java) {
          RemoteComposeJson.compile(source(node).toString())
        }
      assertThat(error).hasMessageThat().contains("floatEquals")
      assertThat(error).hasMessageThat().contains("ContextPath")
    }
    assertThrows(RemoteComposeJsonException::class.java) {
      RemoteComposeJson.compile(source(comparison(), comparison()).toString())
    }
  }

  @Test
  fun `integer and text references cannot masquerade as floats`() {
    for (declaration in
      listOf(
        JSONObject().put("type", "integerExpression").put("name", "other").put("value", "1"),
        JSONObject().put("type", "mutableString").put("name", "other").put("value", "1.25"),
      )) {
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.compile(source(declaration, comparison("@other")).toString())
      }
    }
  }
}
