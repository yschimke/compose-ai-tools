package ee.schimke.composeai.io

import okio.FileSystem
import okio.Path

/**
 * The process filesystem used for all production file IO.
 *
 * A single indirection point so the codebase funnels through Okio rather than `java.io.File` /
 * `java.nio`, and so tests can substitute a `FakeFileSystem`. Prefer passing a [FileSystem]
 * receiver to the suspend helpers in `SuspendIo.kt` (`SystemFileSystem.readUtf8(path)`) over
 * reaching for `FileSystem.SYSTEM` directly, so the dependency stays explicit and swappable.
 */
val SystemFileSystem: FileSystem = FileSystem.SYSTEM

/** Okio's process-temp directory, e.g. `$TMPDIR`. */
val TemporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
