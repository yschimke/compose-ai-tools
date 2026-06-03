package ee.schimke.composeai.io

import java.io.File
import okio.FileSystem
import okio.Path

/**
 * The real process filesystem — the production default for all file IO.
 *
 * A single indirection point so the codebase funnels through Okio rather than `java.io.File` /
 * `java.nio`. Use it with Okio's own blocking `read { … }` / `write { … }`. This module is
 * intentionally synchronous and coroutines-free so it stays safe on the render subprocess classpath
 * (a `kotlinx-coroutines` version skew there breaks Compose rendering — see
 * `docs/RENDERER_COMPATIBILITY.md`).
 *
 * **Injection convention.** Code that touches files takes an Okio [FileSystem] rather than reaching
 * for this constant directly: stateless functions / `object`s accept a defaulted `fileSystem:
 * FileSystem = SystemFileSystem` parameter, and stateful classes take a `private val fileSystem:
 * FileSystem = SystemFileSystem` constructor parameter. Production wiring is unchanged (the default
 * is this real filesystem), while tests inject a `FakeFileSystem` (the `okio-fakefilesystem`
 * artifact) and exercise the IO entirely in memory. The composition roots (`main` entry points) are
 * the one place that legitimately names this constant.
 */
val SystemFileSystem: FileSystem = FileSystem.SYSTEM

/** Okio's process-temp directory, e.g. `$TMPDIR`. */
val TemporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

/**
 * Root of compose-ai-tools' user-level cache, following the XDG Base Directory spec:
 * `$XDG_CACHE_HOME/composeai` when `XDG_CACHE_HOME` is set and non-blank (Linux/BSD), else
 * `~/.cache/composeai`.
 *
 * Regenerable, machine-local artifacts — downloaded Google Fonts, remote-fetched bundle
 * dependencies, materialised Gradle init scripts — belong here, *outside* any project tree: they're
 * caches, not sources, so they shouldn't clutter a working copy or land in version control.
 * [subdir] names the per-feature subtree (e.g. `"fonts"`, `"bundle-deps"`, `"init"`).
 *
 * Note this is deliberately a single shared location across projects: a font keyed by `(family,
 * weight, italic)` or a dependency keyed by Maven coordinate is identical regardless of which
 * project asked for it, so one cache serves them all.
 */
fun composeAiCacheDir(subdir: String): File {
  val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
  val base =
    if (xdg != null) File(xdg, "composeai")
    else File(System.getProperty("user.home") ?: ".", ".cache/composeai")
  return File(base, subdir)
}
