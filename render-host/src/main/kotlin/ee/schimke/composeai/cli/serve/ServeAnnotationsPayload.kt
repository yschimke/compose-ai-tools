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
 * The `/render/<id>.annotations` response body, encoded in one place: [ServeRenderHost] and
 * [ServeBundleHost] answer it from different sources, and a second encoder could drift silently
 * (the overlay would just draw nothing).
 */
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
public object ServeAnnotationsPayload {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * `{"previewId":…, "annotations":[…], "tags":{…}}` — the annotations the viewer draws, plus
   * [ServeSemanticsTags]' tag index over the same frame (empty where the source carries none).
   */
  public fun encode(
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
          put("tags", tagsJson(tags))
        },
      )
      .encodeToByteArray()

  /**
   * `{"previewId":…, "tags":{…}}` — the published tag index alone, for `GET /tags/{id}`. Shares the
   * encoder because [ServeSemanticsTags.TagEntry]'s `space` must always be on the wire. No
   * `annotations` key (not an empty one): this route renders nothing.
   */
  public fun encodeTags(
    previewId: String,
    tags: Map<String, ServeSemanticsTags.TagEntry>,
  ): ByteArray =
    json
      .encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("previewId", JsonPrimitive(previewId))
          put("tags", tagsJson(tags))
        },
      )
      .encodeToByteArray()

  private fun tagsJson(tags: Map<String, ServeSemanticsTags.TagEntry>) =
    json.encodeToJsonElement(
      MapSerializer(String.serializer(), ServeSemanticsTags.TagEntry.serializer()),
      tags,
    )
}
