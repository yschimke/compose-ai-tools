package ee.schimke.composeai.rcplayer.protocol

/** A structured failure while reading an AndroidX Remote Compose byte stream. */
public class RcWireException(
  public val byteOffset: Int,
  public val operationOpcode: Int? = null,
  public val operationName: String? = null,
  public val fieldName: String? = null,
  message: String,
) :
  IllegalArgumentException(
    buildString {
      append(message)
      append(" at byte ")
      append(byteOffset)
      operationOpcode?.let { append(", opcode=$it") }
      operationName?.let { append(" ($it)") }
      fieldName?.let { append(", field=$it") }
    }
  )

/** Bounds-checked, big-endian reader matching AndroidX `WireBuffer`. */
public class RcWireReader(
  private val bytes: ByteArray,
  public val limits: RcWireLimits = RcWireLimits(),
) {
  public var offset: Int = 0
    private set

  public val remaining: Int
    get() = bytes.size - offset

  private var opcode: Int? = null
  private var operationName: String? = null

  public inline fun <T> inOperation(opcode: Int, name: String, block: RcWireReader.() -> T): T {
    val oldOpcode = currentOpcode()
    val oldName = currentOperationName()
    setOperation(opcode, name)
    return try {
      block()
    } finally {
      setOperation(oldOpcode, oldName)
    }
  }

  public fun readU8(field: String): Int {
    requireAvailable(1, field)
    return bytes[offset++].toInt() and 0xff
  }

  public fun readBoolean(field: String): Boolean =
    when (val value = readU8(field)) {
      0 -> false
      1 -> true
      else -> fail(field, "Invalid boolean byte $value")
    }

  public fun readU16(field: String): Int {
    requireAvailable(2, field)
    return (readU8Unchecked() shl 8) or readU8Unchecked()
  }

  public fun readInt(field: String): Int {
    requireAvailable(4, field)
    return (readU8Unchecked() shl 24) or
      (readU8Unchecked() shl 16) or
      (readU8Unchecked() shl 8) or
      readU8Unchecked()
  }

  public fun readLong(field: String): Long {
    requireAvailable(8, field)
    var result = 0L
    repeat(8) { result = (result shl 8) or readU8Unchecked().toLong() }
    return result
  }

  /** Read a float as raw bits so NaN-boxed ids are never canonicalised. */
  public fun readFloatWord(field: String): RcFloatWord = RcFloatWord(readInt(field))

  public fun readByteArray(field: String, maximum: Int = limits.maxBlobBytes): ByteArray {
    val count = readCount("$field.length", maximum)
    requireAvailable(count, field)
    return bytes.copyOfRange(offset, offset + count).also { offset += count }
  }

  public fun readUtf8(field: String, maximum: Int = limits.maxStringBytes): String {
    val data = readByteArray(field, maximum)
    return try {
      data.decodeToString(throwOnInvalidSequence = true)
    } catch (failure: CharacterCodingException) {
      fail(field, "Invalid UTF-8", failure)
    }
  }

  public fun readCount(field: String, maximum: Int): Int {
    val count = readInt(field)
    if (count < 0 || count > maximum) {
      fail(field, "Invalid count $count; expected 0..$maximum")
    }
    return count
  }

  public fun ensureExhausted() {
    if (remaining != 0) fail(null, "$remaining trailing bytes")
  }

  public fun fail(field: String?, message: String, cause: Throwable? = null): Nothing {
    val detail = if (cause?.message.isNullOrEmpty()) message else "$message: ${cause.message}"
    throw RcWireException(offset, opcode, operationName, field, detail)
  }

  @PublishedApi internal fun currentOpcode(): Int? = opcode

  @PublishedApi internal fun currentOperationName(): String? = operationName

  @PublishedApi
  internal fun setOperation(opcode: Int?, name: String?) {
    this.opcode = opcode
    this.operationName = name
  }

  private fun requireAvailable(count: Int, field: String?) {
    if (count < 0 || remaining < count) {
      fail(field, "Truncated input: need $count bytes, have $remaining")
    }
  }

  private fun readU8Unchecked(): Int = bytes[offset++].toInt() and 0xff
}

public data class RcWireLimits(
  val maxDocumentBytes: Int = 16 * 1024 * 1024,
  val maxBlobBytes: Int = 8 * 1024 * 1024,
  val maxStringBytes: Int = 4_000,
  val maxTableEntries: Int = 1_000,
  val maxPaintWords: Int = 1_024,
  val maxPathWords: Int = 20_000,
  val maxCollectionEntries: Int = 2_000,
  val maxImageDimension: Int = 8_192,
)

/** Growable, big-endian writer used by symmetric operation codecs and conformance tests. */
public class RcWireWriter(initialCapacity: Int = 256) {
  private var bytes: ByteArray = ByteArray(initialCapacity.coerceAtLeast(1))
  public var size: Int = 0
    private set

  public fun writeU8(value: Int) {
    ensureCapacity(1)
    bytes[size++] = value.toByte()
  }

  public fun writeBoolean(value: Boolean) {
    writeU8(if (value) 1 else 0)
  }

  public fun writeU16(value: Int) {
    ensureCapacity(2)
    bytes[size++] = (value ushr 8).toByte()
    bytes[size++] = value.toByte()
  }

  public fun writeInt(value: Int) {
    ensureCapacity(4)
    bytes[size++] = (value ushr 24).toByte()
    bytes[size++] = (value ushr 16).toByte()
    bytes[size++] = (value ushr 8).toByte()
    bytes[size++] = value.toByte()
  }

  public fun writeLong(value: Long) {
    ensureCapacity(8)
    for (shift in 56 downTo 0 step 8) bytes[size++] = (value ushr shift).toByte()
  }

  public fun writeFloatWord(value: RcFloatWord) {
    writeInt(value.bits)
  }

  public fun writeByteArray(value: ByteArray) {
    writeInt(value.size)
    ensureCapacity(value.size)
    value.copyInto(bytes, size)
    size += value.size
  }

  public fun writeUtf8(value: String) {
    writeByteArray(value.encodeToByteArray())
  }

  public fun toByteArray(): ByteArray = bytes.copyOf(size)

  private fun ensureCapacity(additional: Int) {
    val required = size + additional
    if (required <= bytes.size) return
    var next = bytes.size
    while (next < required) next = (next * 2).coerceAtLeast(required)
    bytes = bytes.copyOf(next)
  }
}
