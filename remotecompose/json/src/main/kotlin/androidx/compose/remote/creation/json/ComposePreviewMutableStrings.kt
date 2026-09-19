package androidx.compose.remote.creation.json

import org.json.JSONException

/** Named state must own an ID even when its initial text equals another state or a literal. */
internal object ComposePreviewMutableStrings {
  fun install(parser: RemoteComposeJsonParser) {
    parser.registerComponentParser("mutableString") { component, _, writer, current ->
      val name = component.getString("name")
      try {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "name must be an identifier" }
        require(component.keySet().all { it in setOf("type", "name", "value") }) {
          "only type, name and value are supported"
        }
        require(
          name !in current.mVariables &&
            name !in current.mIntegerVariables &&
            name !in current.mDeferredVariables
        ) {
          "duplicate name '$name'"
        }
        val value = component.get("value")
        require(value is String) { "value must be a non-null string" }
        val id = writer.addNamedString(name, value)
        current.mVariables[name] = id.toFloat()
        current.recordVariable(name, id)
      } catch (e: RuntimeException) {
        throw JSONException("${current.contextPathString}: mutableString '$name': ${e.message}", e)
      }
    }
  }
}
