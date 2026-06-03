package ee.schimke.composeai.io

import okio.FileSystem
import okio.Path

/**
 * The process filesystem used for all production file IO.
 *
 * A single indirection point so the codebase funnels through Okio rather than `java.io.File` /
 * `java.nio`, and so tests can substitute a `FakeFileSystem`. Use it with Okio's own blocking `read
 * { … }` / `write { … }` for synchronous code; the suspend / `Dispatchers.IO` wrappers live in the
 * separate `:common-io-suspend` module (kept out of here so this foundation stays coroutines-free
 * for the render subprocess classpath).
 */
val SystemFileSystem: FileSystem = FileSystem.SYSTEM

/** Okio's process-temp directory, e.g. `$TMPDIR`. */
val TemporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
