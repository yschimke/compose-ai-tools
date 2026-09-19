package ee.schimke.composeai.remotecompose.json

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class IntegerExpressionsProfileTest {
  private fun expression(name: String, value: String): JSONObject =
    JSONObject().put("type", "integerExpression").put("name", name).put("value", value)

  private fun source(vararg expressions: JSONObject): JSONObject =
    JSONObject()
      .put("compilerProfile", RemoteComposeJson.INTEGER_EXPRESSIONS_PROFILE)
      .put("header", JSONObject().put("width", 100).put("height", 100))
      .put(
        "root",
        JSONArray()
          .put(
            JSONObject()
              .put("type", "resources")
              .put("integers", JSONObject().put("page", Int.MIN_VALUE))
          )
          .put(JSONObject().put("type", "box").put("children", JSONArray(expressions.toList()))),
      )

  @Test
  fun `profile emits real integer expressions and a state layout`() {
    val document =
      source(expression("low", "@page % 65536"), expression("index", "min(1, abs(@low))"))
    document
      .getJSONArray("root")
      .getJSONObject(1)
      .getJSONArray("children")
      .put(
        JSONObject()
          .put("type", "stateLayout")
          .put("indexId", "@index")
          .put("children", JSONArray().put(JSONObject().put("type", "box")))
      )
    val bytes = RemoteComposeJson.compile(document.toString())
    val dump = RemoteComposeJson.dump(bytes)
    assertThat(dump).contains("IntegerExpression")
    assertThat(dump).contains("STATE_LAYOUT")
    assertThat(RemoteComposeJson.header(bytes).width).isEqualTo(100)
    assertThat(RemoteComposeJson.compile(document.toString())).isEqualTo(bytes)
  }

  @Test
  fun `stock documents keep their previous compilation path`() {
    val document = source()
    document.remove("compilerProfile")
    val buffer =
      androidx.compose.remote.creation.json.RemoteComposeJsonParser.parseToByteBuffer(
        document.toString()
      )
    val expected = ByteArray(buffer.remaining()).also { buffer.get(it) }
    assertThat(RemoteComposeJson.compile(document.toString())).isEqualTo(expected)
  }

  @Test
  fun `extension requires an explicit profile`() {
    val document = source(expression("index", "@page + 1"))
    document.remove("compilerProfile")
    assertThrows(RemoteComposeJsonException::class.java) {
      RemoteComposeJson.compile(document.toString())
    }
  }

  @Test
  fun `unknown and malformed profiles are refused even without extended nodes`() {
    for (profile in listOf("future-v2", "", 1, JSONObject.NULL, JSONObject())) {
      val document = source().put("compilerProfile", profile)
      val failure =
        assertThrows(RemoteComposeJsonException::class.java) {
          RemoteComposeJson.compile(document.toString())
        }
      assertThat(failure).hasMessageThat().contains("Unsupported compilerProfile")
    }
  }

  @Test
  fun `misspelled and forward integer references are refused`() {
    refuses("unknown integer reference", expression("index", "@missing + 1"))
    refuses(
      "unknown integer reference",
      expression("index", "@later + 1"),
      expression("later", "2"),
    )
  }

  @Test
  fun `duplicate declarations cannot change a reference silently`() {
    refuses("duplicate name", expression("page", "1"))
    refuses("duplicate name", expression("index", "1"), expression("index", "2"))
  }

  @Test
  fun `malformed expression stack and unsupported syntax are refused at compilation`() {
    for (value in
      listOf(
        "",
        "1 +",
        "min(1)",
        "1 2",
        "min(1, 2, 3)",
        "(1 + 2",
        "1 + 2)",
        "1 & 2",
        "1.5",
        "sin(1)",
      )) {
      refuses("integerExpression 'index'", expression("index", value))
    }
  }

  @Test
  fun `wire operand mask cannot truncate a long expression`() {
    refuses("1–32 operands/operators", expression("index", (1..17).joinToString(" + ")))
    val document = source(expression("index", (1..16).joinToString(" + ")))
    assertThat(RemoteComposeJson.compile(document.toString()).size).isGreaterThan(64)
  }

  @Test
  fun `wrong function arities cannot cancel each other in the expression stack`() {
    refuses("function requires", expression("index", "min(1, 2, 3) + max(4)"))
    refuses("unexpected comma", expression("index", "(1, 2) + 3"))
  }

  @Test
  fun `all supported functions and negative integer extremes compile`() {
    for (value in
      listOf(
        "-2147483648",
        "2147483647",
        "abs(-10)",
        "min(1, 2)",
        "max(1, 2)",
        "clamp(1, 2, 3)",
        "-@page",
        "@page / 65536",
      )) {
      assertThat(RemoteComposeJson.compile(source(expression("index", value)).toString()).size)
        .isGreaterThan(64)
    }
  }

  @Test
  fun `float references are not coerced to integer ids`() {
    val document = source(expression("index", "@fraction + 1"))
    document
      .getJSONArray("root")
      .getJSONObject(0)
      .put("variables", JSONObject().put("fraction", 1.5))
    val failure =
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.compile(document.toString())
      }
    assertThat(failure).hasMessageThat().contains("unknown integer reference")
  }

  private fun refuses(message: String, vararg expressions: JSONObject) {
    val failure =
      assertThrows(RemoteComposeJsonException::class.java) {
        RemoteComposeJson.compile(source(*expressions).toString())
      }
    assertThat(failure).hasMessageThat().contains(message)
  }
}
