package ee.schimke.composeai.io

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Suspend file/IO helpers built on Okio. Every helper hops onto [Dispatchers.IO] so blocking disk
 * access never runs on a computation or UI dispatcher — that is the whole reason these exist
 * instead of calling Okio's blocking `FileSystem` API directly.
 *
 * They take the [FileSystem] as the receiver (rather than hard-wiring [SystemFileSystem]) so tests
 * can drive a `FakeFileSystem`.
 */

/** Run [block] on [Dispatchers.IO]. Use to wrap small filesystem ops (exists / list / delete). */
suspend fun <T> fileIo(block: suspend CoroutineScope.() -> T): T =
  withContext(Dispatchers.IO, block)

/** Read the whole file at [path] as UTF-8 text. */
suspend fun FileSystem.readUtf8(path: Path): String {
  val fs = this
  return withContext(Dispatchers.IO) { fs.source(path).buffer().use(BufferedSource::readUtf8) }
}

/** Read the whole file at [path] as raw bytes. */
suspend fun FileSystem.readByteString(path: Path): ByteString {
  val fs = this
  return withContext(Dispatchers.IO) {
    fs.source(path).buffer().use(BufferedSource::readByteString)
  }
}

/** Read the whole file at [path] as a byte array. */
suspend fun FileSystem.readBytes(path: Path): ByteArray = readByteString(path).toByteArray()

/**
 * Write [text] to [path] as UTF-8, creating parent directories as needed. Truncates an existing
 * file (the `writeText` default).
 */
suspend fun FileSystem.writeUtf8(path: Path, text: String) {
  val fs = this
  withContext(Dispatchers.IO) {
    path.parent?.let(fs::createDirectories)
    fs.sink(path).buffer().use { it.writeUtf8(text) }
  }
}

/** Write [bytes] to [path], creating parent directories as needed. */
suspend fun FileSystem.writeByteString(path: Path, bytes: ByteString) {
  val fs = this
  withContext(Dispatchers.IO) {
    path.parent?.let(fs::createDirectories)
    fs.sink(path).buffer().use { it.write(bytes) }
  }
}

/** Write [bytes] to [path], creating parent directories as needed. */
suspend fun FileSystem.writeBytes(path: Path, bytes: ByteArray) {
  val fs = this
  withContext(Dispatchers.IO) {
    path.parent?.let(fs::createDirectories)
    fs.sink(path).buffer().use { it.write(bytes) }
  }
}

/** Append [text] to [path] (UTF-8), creating parent directories as needed. */
suspend fun FileSystem.appendUtf8(path: Path, text: String) {
  val fs = this
  withContext(Dispatchers.IO) {
    path.parent?.let(fs::createDirectories)
    fs.appendingSink(path).buffer().use { it.writeUtf8(text) }
  }
}

/** Stream-write to [path] via [block], creating parent directories first. */
suspend fun FileSystem.write(path: Path, block: BufferedSink.() -> Unit) {
  val fs = this
  withContext(Dispatchers.IO) {
    path.parent?.let(fs::createDirectories)
    fs.sink(path).buffer().use(block)
  }
}

/** Stream-read from [path] via [block]. */
suspend fun <T> FileSystem.read(path: Path, block: BufferedSource.() -> T): T {
  val fs = this
  return withContext(Dispatchers.IO) { fs.source(path).buffer().use(block) }
}

// --- JSON convenience (kotlinx.serialization)
// -----------------------------------------------------

/** Decode the JSON file at [path] using [deserializer]. */
suspend fun <T> FileSystem.readJson(
  path: Path,
  json: Json,
  deserializer: DeserializationStrategy<T>,
): T = json.decodeFromString(deserializer, readUtf8(path))

/** Encode [value] with [serializer] and write it to [path] as JSON. */
suspend fun <T> FileSystem.writeJson(
  path: Path,
  json: Json,
  serializer: SerializationStrategy<T>,
  value: T,
) = writeUtf8(path, json.encodeToString(serializer, value))

// --- temp files
// -----------------------------------------------------------------------------------

/**
 * Create a fresh, empty temp file under [TemporaryDirectory] and return its [Path]. The name is
 * `<prefix><uuid><suffix>`. Caller owns cleanup.
 */
suspend fun FileSystem.createTempFile(
  prefix: String = "compose-preview-",
  suffix: String = "",
): Path {
  val fs = this
  return withContext(Dispatchers.IO) {
    val path = TemporaryDirectory / "$prefix${UUID.randomUUID()}$suffix"
    fs.sink(path).close()
    path
  }
}

/** Create a fresh temp directory under [TemporaryDirectory] and return its [Path]. */
suspend fun FileSystem.createTempDirectory(prefix: String = "compose-preview-"): Path {
  val fs = this
  return withContext(Dispatchers.IO) {
    val path = TemporaryDirectory / "$prefix${UUID.randomUUID()}"
    fs.createDirectories(path)
    path
  }
}
