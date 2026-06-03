package ee.schimke.composeai.io

import okio.FileSystem
import okio.Path

/**
 * The process filesystem used for all production file IO.
 *
 * A single indirection point so the codebase funnels through Okio rather than `java.io.File` /
 * `java.nio`, and so tests can substitute a `FakeFileSystem`. Use it with Okio's own blocking `read
 * { … }` / `write { … }`. This module is intentionally synchronous and coroutines-free so it stays
 * safe on the render subprocess classpath (a `kotlinx-coroutines` version skew there breaks Compose
 * rendering — see `docs/RENDERER_COMPATIBILITY.md`).
 */
val SystemFileSystem: FileSystem = FileSystem.SYSTEM

/** Okio's process-temp directory, e.g. `$TMPDIR`. */
val TemporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
