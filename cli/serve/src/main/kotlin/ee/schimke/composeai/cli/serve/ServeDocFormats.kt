package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One labelled fact about an ingested document, shown on the document page's detail list. */
data class ServeDocFact(val key: String, val value: String)

/** A document's declared drawing size, when its format announces one. */
data class ServeDocSize(val width: Int, val height: Int)

/**
 * A **known document format** the serve host can ingest ([ServeDocStore]) and hand back as an
 * expiring permalink. Each format is data-only — a document is a *description* of what to draw, not
 * code — so the server never executes it: it stores the bytes, sniffs which format they are, and
 * the browser plays them back with the format's vendored player.
 *
 * Everything per-format lives here, in the registry, rather than as branches in the store or the
 * HTTP routes: adding a format is one [ServeDocFormat] entry plus its player bundle. The route
 * layer only ever looks a format up by [id] and reads these fields.
 *
 * @param id stable wire id (`remotecompose`, `lottie`) — appears in the upload response and in the
 *   player route `/doc-player/<id>/bundle.js`.
 * @param label human name for the document page.
 * @param extension canonical file extension, used for the raw download's filename.
 * @param contentType what `GET /d/<id>/raw` responds with.
 * @param playerResource classpath path of the vendored browser player bundle served at
 *   `/doc-player/<id>/bundle.js`.
 * @param detect content sniff — true when [ByteArray] really is this format. Runs before anything
 *   else touches the upload, so a mislabelled or hostile file is rejected on shape, not on its
 *   name.
 * @param describe the facts shown on the document page (dimensions, duration, version …).
 *   Best-effort: a document that parses far enough to store but not to summarise yields fewer rows.
 * @param size the document's declared drawing size, when it announces one — the page sizes the
 *   player's stage with it before load (a canvas player derives its viewport from the element's
 *   size at load time, so a later resize can't recover it). Null when the format/document doesn't
 *   say.
 */
data class ServeDocFormat(
  val id: String,
  val label: String,
  val extension: String,
  val contentType: String,
  val playerResource: String,
  val detect: (ByteArray) -> Boolean,
  val describe: (ByteArray) -> List<ServeDocFact>,
  val size: (ByteArray) -> ServeDocSize?,
) {
  /** URL of this format's browser player bundle (mounted by `ServeHttpServer`). */
  val playerPath: String
    get() = "/doc-player/$id/bundle.js"
}

/**
 * The known document formats, and the sniffing that maps uploaded bytes onto one.
 *
 * Both current entries are *data-only* tiers in the serve host's trust × format model (see
 * `docs/public-preview-server.md`): rendering them runs a player in the **viewer's** browser, never
 * Kotlin on the server, so an anonymous upload can be played back safely.
 */
object ServeDocFormats {

  /**
   * Remote Compose document (`.rc`) — the `RemoteDocument` byte stream the Compose connector
   * captures, played back by the same vendored `RC.RcdPlayer` the preview viewer's canvas lane
   * uses.
   *
   * The stream opens with the `Header` operation: opcode `0x00`, then a big-endian int whose high
   * 16 bits are the format magic (`0x048C`) and whose low 16 are the major version. That is the
   * sniff; the rest of [describeRemoteCompose] walks the header's property table for the document's
   * declared size.
   */
  val REMOTE_COMPOSE =
    ServeDocFormat(
      id = "remotecompose",
      label = "Remote Compose",
      extension = ".rc",
      contentType = "application/octet-stream",
      playerResource = "/rc-player/bundle.js",
      detect = ::isRemoteComposeDoc,
      describe = ::describeRemoteCompose,
      size = ::remoteComposeSize,
    )

  /**
   * Lottie animation (Bodymovin JSON) — played back by the vendored `lottie-web` player. Sniffed on
   * shape rather than extension: a JSON object carrying a `layers` array plus the frame-rate /
   * in-point / out-point trio every Bodymovin export writes.
   */
  val LOTTIE =
    ServeDocFormat(
      id = "lottie",
      label = "Lottie",
      extension = ".json",
      contentType = "application/json",
      playerResource = "/lottie-player/bundle.js",
      detect = { bytes -> parseLottie(bytes) != null },
      describe = ::describeLottie,
      size = ::lottieSize,
    )

  /** Every known format, in the order the upload path sniffs them. */
  val ALL: List<ServeDocFormat> = listOf(REMOTE_COMPOSE, LOTTIE)

  fun byId(id: String): ServeDocFormat? = ALL.firstOrNull { it.id == id }

  /**
   * The format [bytes] are, or null when they're not a known document. Content-sniffed — the
   * uploaded filename is never trusted to decide the format, only (via [ALL]'s order) to break a
   * tie that can't happen today.
   */
  fun detect(bytes: ByteArray): ServeDocFormat? = ALL.firstOrNull { it.detect(bytes) }

  /** Human list of what an upload may be, for the error a rejected upload gets back. */
  fun knownSummary(): String = ALL.joinToString(", ") { "${it.label} (${it.extension})" }

  // ---- Remote Compose ----------------------------------------------------------------------

  private const val RC_MAGIC = 0x048C

  private fun isRemoteComposeDoc(bytes: ByteArray): Boolean {
    // opcode(1) + magic|major(4) + minor(4) + patch(4) is the smallest header worth accepting.
    if (bytes.size < 13) return false
    if (bytes[0].toInt() != 0) return false
    val high = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
    return high == RC_MAGIC
  }

  /** Version + declared size from the document header. */
  private fun describeRemoteCompose(bytes: ByteArray): List<ServeDocFact> {
    val header = readRemoteComposeHeader(bytes) ?: return emptyList()
    val facts = mutableListOf<ServeDocFact>()
    facts += ServeDocFact("Format version", header.version)
    header.size?.let { facts += ServeDocFact("Document size", "${it.width} × ${it.height}") }
    return facts
  }

  private fun remoteComposeSize(bytes: ByteArray): ServeDocSize? =
    readRemoteComposeHeader(bytes)?.size

  private class RemoteComposeHeader(val version: String, val size: ServeDocSize?)

  /**
   * Walk the document's `Header` operation for its version + declared size. Deliberately total: any
   * malformed / truncated table stops the walk and yields what was read so far, since this only
   * feeds a display panel and the stage's initial dimensions.
   */
  private fun readRemoteComposeHeader(bytes: ByteArray): RemoteComposeHeader? {
    if (!isRemoteComposeDoc(bytes)) return null
    var version = "unknown"
    var width: Int? = null
    var height: Int? = null
    try {
      val reader = ByteReader(bytes, offset = 1)
      val major = reader.int() and 0xFFFF
      val minor = reader.int()
      val patch = reader.int()
      version = "$major.$minor.$patch"
      val propertyCount = reader.int()
      // The header's property table: a short tag (dataType = tag shr 10, key = tag and 0x3FF), a
      // short byte length, then the value. Unknown types are skipped by their declared length, so
      // an
      // added property key can't derail the walk.
      repeat(propertyCount.coerceIn(0, MAX_HEADER_PROPERTIES)) {
        val tag = reader.short()
        val dataType = tag shr 10
        val key = tag and 0x3FF
        val length = reader.short()
        if (dataType == DATA_TYPE_INT) {
          val value = reader.int()
          if (key == DOC_WIDTH) width = value
          if (key == DOC_HEIGHT) height = value
        } else {
          reader.skip(length)
        }
      }
    } catch (e: IndexOutOfBoundsException) {
      // Truncated header — keep whatever was read.
    }
    val w = width
    val h = height
    return RemoteComposeHeader(version, if (w != null && h != null) ServeDocSize(w, h) else null)
  }

  private const val DOC_WIDTH = 5
  private const val DOC_HEIGHT = 6
  private const val DATA_TYPE_INT = 0

  /** Same ceiling the players apply to the header property table — a bound, not a format rule. */
  private const val MAX_HEADER_PROPERTIES = 1000

  /**
   * Minimal big-endian reader over the header prefix; throws past the end, caught by the caller.
   */
  private class ByteReader(private val bytes: ByteArray, private var offset: Int) {
    fun byte(): Int {
      if (offset >= bytes.size) throw IndexOutOfBoundsException()
      return bytes[offset++].toInt() and 0xFF
    }

    fun short(): Int = (byte() shl 8) or byte()

    fun int(): Int = (short() shl 16) or short()

    fun skip(count: Int) {
      if (count < 0 || offset + count > bytes.size) throw IndexOutOfBoundsException()
      offset += count
    }
  }

  // ---- Lottie ------------------------------------------------------------------------------

  private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

  /** The parsed animation object when [bytes] are a Bodymovin/Lottie document; null otherwise. */
  private fun parseLottie(bytes: ByteArray): JsonObject? {
    // Cheap pre-filter: a Lottie document is a JSON object, so anything that doesn't open like one
    // never reaches the parser (which would otherwise buffer a large binary upload as text).
    val firstByte = bytes.firstOrNull { !it.isJsonWhitespace() } ?: return null
    if (firstByte.toInt().toChar() != '{') return null
    val root =
      try {
        LENIENT_JSON.parseToJsonElement(bytes.decodeToString()) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return null
    if (root["layers"] !is JsonArray) return null
    // `fr` (frame rate) plus the in/out point pair are written by every Bodymovin export and are
    // what the player needs to run a timeline — so requiring them keeps some other `layers`-shaped
    // JSON from being mistaken for an animation.
    val required = listOf("fr", "ip", "op").mapNotNull { root.number(it) }
    if (required.size != 3) return null
    return root
  }

  private fun describeLottie(bytes: ByteArray): List<ServeDocFact> {
    val root = parseLottie(bytes) ?: return emptyList()
    val facts = mutableListOf<ServeDocFact>()
    root["nm"]
      ?.stringOrNull()
      ?.takeIf { it.isNotBlank() }
      ?.let { facts += ServeDocFact("Name", it) }
    root["v"]?.stringOrNull()?.let { facts += ServeDocFact("Bodymovin version", it) }
    lottieSize(bytes)?.let { facts += ServeDocFact("Size", "${it.width} × ${it.height}") }
    val frameRate = root.number("fr")
    val inPoint = root.number("ip")
    val outPoint = root.number("op")
    if (frameRate != null && inPoint != null && outPoint != null && frameRate > 0) {
      val frames = outPoint - inPoint
      facts += ServeDocFact("Frames", "${frames.toInt()} @ ${trimNumber(frameRate)} fps")
      facts += ServeDocFact("Duration", "${trimNumber(frames / frameRate)}s")
    }
    (root["layers"] as? JsonArray)?.let { facts += ServeDocFact("Layers", it.size.toString()) }
    return facts
  }

  private fun lottieSize(bytes: ByteArray): ServeDocSize? {
    val root = parseLottie(bytes) ?: return null
    val width = root.number("w")?.toInt() ?: return null
    val height = root.number("h")?.toInt() ?: return null
    return if (width > 0 && height > 0) ServeDocSize(width, height) else null
  }

  private fun JsonObject.number(key: String): Double? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

  private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)
      ?.takeIf { it.isString }
      ?.jsonPrimitive
      ?.content

  private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format("%.2f", value).trimEnd('0').trimEnd('.')

  private fun Byte.isJsonWhitespace(): Boolean =
    this == ' '.code.toByte() ||
      this == '\n'.code.toByte() ||
      this == '\r'.code.toByte() ||
      this == '\t'.code.toByte()
}
