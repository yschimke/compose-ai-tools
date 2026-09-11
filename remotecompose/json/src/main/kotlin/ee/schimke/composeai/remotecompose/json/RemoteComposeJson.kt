package ee.schimke.composeai.remotecompose.json

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
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
 * The two JSON dialects of a Remote Compose document, and the only supported way between them and
 * the binary `.rc` wire format.
 *
 * ## Two dialects, deliberately not one
 *
 * "The RemoteCompose JSON format" is two formats, and conflating them is the mistake this class
 * exists to make hard. They are not inverses of each other and neither round-trips into the other.
 *
 * **Authoring JSON** is what a person writes and what [compile] reads: the format
 * [androidx.compose.remote.creation.json.RemoteComposeJsonParser] parses, described by AndroidX's
 * `remote_compose_schema.json` and `Documentation/parts/json-parser.md`. It is a *source* language
 * — `{"column": {"modifiers": ["fillMaxWidth"], "children": [...]}}` — with named resources, infix
 * expressions (`"@w / 2.0"`), modifier shorthands and component sugar. Compiling it is lossy in the
 * direction that matters: names collapse to integer ids, `fillMaxWidth` becomes a
 * `WidthModifierOperation` carrying a NaN-encoded fill marker, and the ordered modifier list is
 * flattened into the operation stream.
 *
 * **Document JSON** is what [dump] writes: the *operation-level* projection of an inflated
 * [CoreDocument] — every operation in document order with the fields it actually carries. It is an
 * inspection and diffing format, not a source language, and there is no parser that reads it back.
 * It exists because a `.rc` is otherwise opaque: a byte diff between two captured stickers says
 * nothing, and `deepToString` is prose.
 *
 * So: `authoring JSON --compile--> .rc --dump--> document JSON`, one way, and the document JSON of
 * a compiled document does **not** resemble the authoring JSON that produced it — a dump names
 * `ColorConstant` and `WidthModifierOperation` where the source named `bg` and `fillMaxSize`.
 * `RemoteComposeJsonTest` pins that shape rather than leaving it to be discovered by someone who
 * expected an inverse.
 *
 * ## Why this rides on AndroidX's own serializer
 *
 * [dump] does not read the wire format. It inflates the document with `remote-core` — the same
 * class Android runs — and then walks it through
 * `androidx.compose.remote.core.serialize.Serializable`, the structured serialization hook every
 * operation already implements for AndroidX's own tooling. A [JsonMapSerializer] collects that into
 * a [JsonObject].
 *
 * The alternative was a hand-written binary reader, which is what every third-party rc→JSON dumper
 * is, and it is the wrong trade twice over. The wire format has no checksums and 300-odd opcodes
 * that move between alphas, so a reader drifts silently — it keeps parsing, just wrongly. Riding
 * the upstream hook means an operation that gains a field gains it here, and an operation that
 * upstream has not taught to serialize shows up as [JsonMapSerializer.UNSERIALIZED] rather than as
 * plausible-looking nonsense.
 *
 * ## Both directions run on a bare JVM
 *
 * `remote-core` and `remote-creation-core` are plain `java-library` publications — no Android
 * runtime, no `compileSdk` — which is what lets this module sit in layer 1 beside the bundle format
 * rather than inside a Robolectric render. Two consequences worth stating because neither is
 * visible from the coordinates:
 *
 * - `RemoteComposeJsonParser` is written against `org.json.JSONObject`, which Android supplies from
 *   the platform and a JVM does not. `remote-creation-core` neither shades nor declares it, so this
 *   module declares `org.json:json` itself.
 * - [compile] runs on `RemoteComposeJsonParser.DEFAULT_PLATFORM`, whose **text measurement** is a
 *   stub. A document whose *layout* depends on measured text therefore compiles here but must be
 *   measured by a real player before its bounds mean anything. Compiling is not rendering, and this
 *   class never claims otherwise.
 *
 *   **Path parsing is not** among the stubs, contrary to what this file used to say. Measured: the
 *   default platform's `parsePath` returns a real `RemotePathBase`, and `PathParser.parsePathData`
 *   turns `"M 10 10 L 90 10 L 90 90 Z"` into the NaN-opcode float array a player draws — `["@10",
 *   10.0, 10.0, "@11", …]` in a dump, `@10`/`@11`/`@15` being MOVE/LINE/CLOSE. Geometry survives
 *   compilation here intact.
 */
public object RemoteComposeJson {

  /**
   * Opt-in authoring profile for named integer expressions. Put this value in the document's
   * top-level `compilerProfile` field. The resulting bytes use standard Remote Compose operations;
   * the source requires this compiler, rather than AndroidX's unextended JSON parser.
   */
  public const val INTEGER_EXPRESSIONS_PROFILE: String = "compose-preview-integer-expressions-v1"

  /** Named integer expressions and independent mutable string declarations. */
  public const val STATE_PROFILE: String = "compose-preview-state-v1"

  /**
   * Compile an **authoring JSON** document to binary `.rc` bytes.
   *
   * @throws RemoteComposeJsonException if [json] is not valid JSON, or is valid JSON that the
   *   parser refuses — an unknown component type, a malformed expression, an unresolvable resource
   *   reference. The parser's own message is preserved as the cause's message: it names the JSON
   *   path it gave up on, which is the only thing that makes a 200-line document debuggable.
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
        if (profile.content == STATE_PROFILE) ComposePreviewMutableStrings.install(parser)
        parser.parse(json)
        return writer.encodeToByteArray()
      }
      val buffer = RemoteComposeJsonParser.parseToByteBuffer(json)
      ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (e: JSONException) {
      throw RemoteComposeJsonException("Not a valid RemoteCompose JSON document: ${e.message}", e)
    } catch (e: RuntimeException) {
      // The parser throws IllegalArgumentException / IllegalStateException / NPE for a document
      // that is syntactically JSON but semantically not a document — an unregistered component
      // type, a modifier with the wrong argument shape. Those reach a caller as the same failure
      // as a syntax error, because to a caller they are: this text is not a document.
      throw RemoteComposeJsonException("Failed to compile RemoteCompose JSON: ${e.message}", e)
    }
  }

  /**
   * Refuse a document with no `root` before handing it to the parser.
   *
   * `root` is the schema's only `required` property, and the parser does not enforce it: given
   * `{}`, or given a **generation-library entry** — which wraps the real document under a `json`
   * key next to its prose metadata — it succeeds and returns a valid, playable, 17-byte header-only
   * document. That failure is worth spending a JSON parse to catch, because it is invisible at
   * every later stage: the bytes are a real document, the bundle packs them, the daemon replays
   * them, and the preview renders blank with nothing anywhere reporting an error. The wrapper case
   * gets its own sentence in the message because unwrapping is the fix and "missing root" alone
   * does not suggest it.
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
   * Refuse *authoring* JSON handed to a *document* entry point, by name.
   *
   * The two dialects are the confusion this whole class is shaped around, and the confusion has a
   * command-line form: `rc dump doc.json`. Without this check the bytes reach the inflater, which
   * reads the ASCII of `{"header":` as opcodes and fails somewhere arbitrary — the observed message
   * was `Path too long`, which sends the reader looking for a filesystem problem that does not
   * exist. Naming the mistake costs one byte of lookahead.
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
   * Inflate binary `.rc` [document] bytes and project them as **document JSON**.
   *
   * The returned object is `{"header": {...}, "operations": [...]}`. [header] is the decoded
   * [Header] operation rather than the inflated document's geometry, because a document that has
   * never been laid out reports `0 x 0` for both — [CoreDocument.getWidth] is the *measured* width
   * and there is no measure pass here.
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
      // `CoreDocument.serialize` puts the operation list under `operations` alongside its own
      // (unmeasured, therefore zero) width/height. Only the list is worth keeping — the geometry is
      // the header's, above, where it is the *declared* size rather than a measure result.
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
   * Decode just the [Header] of [document] — the one part of a `.rc` readable without inflating the
   * whole operation stream, and all a caller needs to size a canvas or check an API level.
   */
  /**
   * Decode just the [Header] of [document] — genuinely just it.
   *
   * `Header.readDirect` reads the first operation and stops, where inflating a [CoreDocument] would
   * walk the whole stream. That is the difference between an answer and a failure for the case this
   * method exists for: a document carrying an opcode this `remote-core` does not know still has a
   * readable header, and "which profile / which version does this thing want" is exactly the
   * question being asked when a document will not play.
   */
  public fun header(document: ByteArray): RemoteComposeDocumentHeader {
    // The same refusal [dumpToJsonObject] makes, and for the same reason: `rc header doc.json` is
    // the same slip as `rc dump doc.json`, and without this the ASCII of `{"header":` is read as
    // wire-format fields and answers with an arbitrary low-level parse error instead of naming the
    // dialect. The check belonged on both entry points from the start; it reached only one.
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
      // `readDirect` is declared to throw `IOException`, but a byte sequence that parses far enough
      // to be read and not far enough to make sense surfaces as an unchecked one from the buffer
      // underneath. Both mean the same thing to a caller.
      throw RemoteComposeJsonException(
        "Not a RemoteCompose document (${document.size} bytes): ${e.message}",
        e,
      )
    }

  private val PRETTY = Json { prettyPrint = true }
  private val COMPACT = Json
}

/**
 * A failure to compile, inflate or project a Remote Compose document.
 *
 * One exception type for both directions on purpose. A caller — the bundle IR resolver, the CLI,
 * the server's playground — reacts the same way to "this text is not a document" and "these bytes
 * are not a document": report the message and skip this preview. Distinguishing them would push a
 * `when` into every call site to reach the same branch twice.
 */
public class RemoteComposeJsonException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
