package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.json.ComposePreviewFloatEquals
import androidx.compose.remote.creation.json.ComposePreviewIntegerExpressions
import androidx.compose.remote.creation.json.ComposePreviewMutableStrings
import androidx.compose.remote.creation.json.RemoteComposeJsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONException

/**
 * The two JSON dialects of a Remote Compose document, and the supported way between them and the
 * binary `.rc` wire format: `authoring JSON --compile--> .rc --dump--> document JSON`, one way.
 *
 * **Authoring JSON** is the source language [RemoteComposeJsonParser] reads (named resources, infix
 * expressions, modifier shorthands); compiling it is lossy. **Document JSON** is the
 * operation-level projection [dump] writes for inspection and diffing; nothing reads it back, and
 * it does not resemble the authoring JSON that produced it (`RemoteComposeJsonTest` pins that).
 *
 * [dump] inflates with `remote-core` and walks AndroidX's own
 * `androidx.compose.remote.core.serialize.Serializable` hook via [JsonMapSerializer], rather than a
 * hand-written reader that would silently drift across alphas; an operation upstream can't
 * serialize shows up as [JsonMapSerializer.UNSERIALIZED].
 *
 * Both directions run on a bare JVM. The parser needs `org.json`, which this module declares
 * itself, and [compile]'s default platform stubs text measurement, so text-dependent layout is only
 * meaningful once a real player measures it. Path parsing is real.
 */
public object RemoteComposeJson {

  /**
   * Opt-in authoring profile for named integer expressions. Put this value in the document's
   * top-level `compilerProfile` field. The resulting bytes use standard Remote Compose operations;
   * the source requires this compiler, rather than AndroidX's unextended JSON parser.
   */
  public const val INTEGER_EXPRESSIONS_PROFILE: String = "compose-preview-integer-expressions-v1"

  /**
   * Named integer expressions, exact Float equality and independent mutable string declarations.
   */
  public const val STATE_PROFILE: String = "compose-preview-state-v1"

  /**
   * Compile an authoring JSON document to binary `.rc` bytes.
   *
   * @throws RemoteComposeJsonException if [json] is not valid JSON or the parser refuses it; the
   *   parser's message, which names the JSON path, is preserved.
   */
  public fun compile(json: String): ByteArray {
    val document = requireRoot(json)
    val profile = document["compilerProfile"]
    if (
      profile != null &&
        (profile !is JsonPrimitive ||
          !profile.isString ||
          profile.content !in setOf(INTEGER_EXPRESSIONS_PROFILE, STATE_PROFILE))
    ) {
      throw RemoteComposeJsonException("Unsupported compilerProfile: $profile")
    }
    return try {
      if (profile != null) {
        val writer =
          RemoteComposeWriter(
            RemoteComposeJsonParser.DEFAULT_PLATFORM,
            RemoteComposeJsonParser.parseApiLevel(json),
            *RemoteComposeJsonParser.parseHeaderOnly(json).sortedBy { it.tag }.toTypedArray(),
          )
        val parser = RemoteComposeJsonParser(writer)
        ComposePreviewIntegerExpressions.install(parser)
        if (profile.content == STATE_PROFILE) {
          ComposePreviewMutableStrings.install(parser)
          ComposePreviewFloatEquals.install(parser)
        }
        parser.parse(json)
        return writer.encodeToByteArray()
      }
      val buffer = RemoteComposeJsonParser.parseToByteBuffer(json)
      ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (e: JSONException) {
      throw RemoteComposeJsonException("Not a valid RemoteCompose JSON document: ${e.message}", e)
    } catch (e: RuntimeException) {
      // Semantic refusals (unknown component, wrong modifier shape) surface as unchecked
      // exceptions; to a caller they are the same failure as a syntax error.
      throw RemoteComposeJsonException("Failed to compile RemoteCompose JSON: ${e.message}", e)
    }
  }

  /**
   * Refuse a document with no `root`. The parser doesn't enforce the schema's only required
   * property: `{}`, or a generation-library entry wrapping the document under `json`, compiles to a
   * valid header-only document that renders blank with no error anywhere.
   */
  private fun requireRoot(json: String): JsonObject {
    val parsed =
      try {
        Json.parseToJsonElement(json)
      } catch (e: SerializationException) {
        throw RemoteComposeJsonException("Not a valid RemoteCompose JSON document: ${e.message}", e)
      }
    val obj =
      parsed as? JsonObject
        ?: throw RemoteComposeJsonException(
          "Not a RemoteCompose JSON document: the top level is ${parsed::class.simpleName}, " +
            "expected an object with a \"root\""
        )
    if ("root" in obj) return obj
    val wrapped =
      if ("json" in obj)
        " It looks like a generation-library entry — compile its \"json\" value, not the wrapper."
      else ""
    throw RemoteComposeJsonException(
      "Not a RemoteCompose JSON document: no \"root\" (keys: ${obj.keys.joinToString()}). " +
        "Compiling it would succeed and produce an empty header-only document that plays blank.$wrapped"
    )
  }

  /**
   * Refuse authoring JSON handed to a document entry point (`rc dump doc.json`), which would
   * otherwise fail with an arbitrary parse error such as `Path too long`.
   */
  private fun refuseAuthoringJson(document: ByteArray) {
    val first = document.firstOrNull { !it.toInt().toChar().isWhitespace() } ?: return
    if (first.toInt().toChar() != '{') return
    throw RemoteComposeJsonException(
      "That is JSON text, not a binary RemoteCompose document. If it is authoring JSON, compile " +
        "it first — the document dialect this reads is not the dialect a person writes."
    )
  }

  /**
   * Inflate binary `.rc` [document] bytes and project them as document JSON: `{"header": {...},
   * "operations": [...]}`. The header is the decoded [Header] operation, since an unmeasured
   * [CoreDocument] reports `0 x 0`.
   */
  public fun dumpToJsonObject(document: ByteArray): JsonObject {
    refuseAuthoringJson(document)
    val buffer =
      try {
        ByteArrayInputStream(document).use { RemoteComposeBuffer.fromInputStream(it) }
      } catch (e: RuntimeException) {
        throw RemoteComposeJsonException(
          "Not a RemoteCompose document (${document.size} bytes): ${e.message}",
          e,
        )
      }
    val core =
      try {
        CoreDocument().apply { initFromBuffer(buffer) }
      } catch (e: RuntimeException) {
        throw RemoteComposeJsonException(
          "Failed to inflate RemoteCompose document (${document.size} bytes): ${e.message}",
          e,
        )
      }
    val serializer = JsonMapSerializer()
    core.serialize(serializer)
    return buildJsonObject {
      put("header", RemoteComposeDocumentHeader(readHeader(document), document.size).toJsonObject())
      // Drop `serialize`'s unmeasured width/height; the header carries the declared size.
      serializer.result()["operations"]?.let { put("operations", it) }
    }
  }

  /** [dumpToJsonObject] rendered as text. Pretty-printed by default because a human reads it. */
  public fun dump(document: ByteArray, pretty: Boolean = true): String =
    (if (pretty) PRETTY else COMPACT).encodeToString(
      JsonObject.serializer(),
      dumpToJsonObject(document),
    )

  /**
   * Decode just the [Header] of [document]. `Header.readDirect` stops after the first operation, so
   * a document with an opcode this `remote-core` doesn't know still reports its profile and
   * version.
   */
  public fun header(document: ByteArray): RemoteComposeDocumentHeader {
    refuseAuthoringJson(document)
    return RemoteComposeDocumentHeader(readHeader(document), document.size)
  }

  private fun readHeader(document: ByteArray): Header =
    try {
      ByteArrayInputStream(document).use { Header.readDirect(it) }
    } catch (e: IOException) {
      throw RemoteComposeJsonException(
        "Not a RemoteCompose document (${document.size} bytes): ${e.message}",
        e,
      )
    } catch (e: RuntimeException) {
      // Malformed bytes can also surface as unchecked exceptions from the buffer.
      throw RemoteComposeJsonException(
        "Not a RemoteCompose document (${document.size} bytes): ${e.message}",
        e,
      )
    }

  private val PRETTY = Json { prettyPrint = true }
  private val COMPACT = Json
}

/**
 * A failure to compile, inflate or project a Remote Compose document. One type for both directions:
 * every caller reports the message and skips the preview either way.
 */
public class RemoteComposeJsonException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
