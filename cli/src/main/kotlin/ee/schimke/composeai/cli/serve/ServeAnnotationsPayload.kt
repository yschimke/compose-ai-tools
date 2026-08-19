package ee.schimke.composeai.cli.serve

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The `/render/<id>.annotations` response body, written in one place.
 *
 * Two hosts answer that URL from different sources — [ServeRenderHost] projects the layers off a
 * render's own `compose/semantics` tree, [ServeBundleHost] replays what the catalog published over
 * its baked frame — and the viewer's `<cp-inspect-layers>` parses one shape. A second copy of the
 * encoding is the kind of drift nothing fails on: the overlay simply draws nothing for whichever
 * lane's key it does not recognise, which reads as a broken layer rather than a wrong response.
 */
internal object ServeAnnotationsPayload {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * `{"previewId":…, "annotations":[…], "tags":{…}}` — the annotations the viewer draws, plus
   * [ServeSemanticsTags]' tag index over the same frame (empty where the source carries none).
   */
  fun encode(
    previewId: String,
    annotations: List<DesignAnnotation>,
    tags: Map<String, ServeSemanticsTags.TagEntry>,
  ): ByteArray =
    json
      .encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("previewId", JsonPrimitive(previewId))
          put(
            "annotations",
            json.encodeToJsonElement(ListSerializer(DesignAnnotation.serializer()), annotations),
          )
          put(
            "tags",
            json.encodeToJsonElement(
              MapSerializer(String.serializer(), ServeSemanticsTags.TagEntry.serializer()),
              tags,
            ),
          )
        },
      )
      .encodeToByteArray()
}
