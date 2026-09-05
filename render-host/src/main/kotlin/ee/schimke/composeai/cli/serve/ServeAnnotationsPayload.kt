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
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
object ServeAnnotationsPayload {

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
          put("tags", tagsJson(tags))
        },
      )
      .encodeToByteArray()

  /**
   * `{"previewId":…, "tags":{…}}` — the **published** tag index on its own, for `GET /tags/{id}`.
   *
   * Two keys of the three above, and the same encoder for the one that matters, because the wire
   * type is the load-bearing part: [ServeSemanticsTags.TagEntry] carries `space`, and
   * [ServeTagIndexStore] refuses an entry that declares none rather than defaulting it. A second
   * hand-rolled copy of this map is how one of the two lanes quietly stops naming its plane, and
   * the symptom of that is not a parse failure anywhere — it is an element gate comparing bounds in
   * a plane nobody stated.
   *
   * No `annotations` key, deliberately, rather than an empty one: this route answers from the
   * catalog's published `tags/index.json` and performs no render, so it has no annotation layer to
   * describe and must not look as though it found none.
   */
  fun encodeTags(previewId: String, tags: Map<String, ServeSemanticsTags.TagEntry>): ByteArray =
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
