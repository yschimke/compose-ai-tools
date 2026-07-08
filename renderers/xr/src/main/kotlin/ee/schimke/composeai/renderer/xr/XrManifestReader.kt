package ee.schimke.composeai.renderer.xr

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the `XR_SUBSPACE` entries out of the plugin's `previews.json` manifest. Parsed via the
 * `JsonElement` tree (not `@Serializable` classes) so `:renderer-xr` doesn't have to mirror the
 * full manifest schema or apply the serialization compiler plugin — it only needs the
 * class/function/id of each XR preview to reflect + render it.
 */
public object XrManifestReader {

  /** The fields the XR render entry needs from one manifest preview. */
  public data class XrPreview(val id: String, val className: String, val functionName: String)

  /** Returns the `XR_SUBSPACE`-kind previews in [manifestFile], in manifest order. */
  public fun xrPreviews(manifestFile: File): List<XrPreview> {
    val root = Json.parseToJsonElement(manifestFile.readText()).jsonObject
    val previews = root["previews"]?.jsonArray ?: return emptyList()
    return previews.mapNotNull { element ->
      val obj = element.jsonObject
      val kind = obj["params"]?.jsonObject?.get("kind")?.jsonPrimitive?.content
      if (kind != "XR_SUBSPACE") return@mapNotNull null
      val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val className = obj["className"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val functionName = obj["functionName"]?.jsonPrimitive?.content ?: return@mapNotNull null
      XrPreview(id = id, className = className, functionName = functionName)
    }
  }
}
