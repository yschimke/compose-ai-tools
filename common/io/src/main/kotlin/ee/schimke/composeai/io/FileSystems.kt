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

/** Directory name of the legacy in-tree history archive, kept for backwards compatibility. */
const val LEGACY_HISTORY_DIRNAME: String = ".compose-preview-history"

/**
 * Where a module's render history lives:
 * `composeAiCacheDir("history")/<workspaceSlug>/<moduleRel>`.
 *
 * History is a semi-persistent timeline of local edits — regenerable enough to be a cache, and
 * never something the user authored. It used to live at `<projectDir>/.compose-preview-history`,
 * which meant every previewed module grew an untracked directory next to its sources (and, on
 * projects that never opted in, one that appeared without the user asking). Relocating it under the
 * user-level cache root puts it alongside the font cache and bundle deps, which are the same
 * category of data.
 *
 * The reporting-branch flow is unaffected: that publishes to a git ref (see
 * `docs/daemon/REPORTING-BRANCH.md`), and the in-tree directory was only ever its local staging
 * area.
 *
 * **Legacy directories win.** When `<projectDir>/.compose-preview-history` already exists, it is
 * returned unchanged so an existing timeline isn't stranded by an upgrade. Delete it (or move it
 * under the cache root) to migrate; nothing recreates it.
 *
 * The layout must be reproduced byte-for-byte by two other implementations that can't call this one
 * — the Gradle plugin (no dependency on this module) and the VS Code extension (TypeScript).
 * [composeAiHistoryWorkspaceSlug] documents the slug contract those mirror; `HistoryPathsTest` and
 * its TS counterpart pin the same vectors on both sides.
 */
fun composeAiHistoryDir(workspaceRoot: File, projectDir: File): File {
  val legacy = File(projectDir, LEGACY_HISTORY_DIRNAME)
  if (legacy.isDirectory) return legacy
  return File(
    File(composeAiCacheDir("history"), composeAiHistoryWorkspaceSlug(workspaceRoot)),
    composeAiHistoryModuleSegment(workspaceRoot, projectDir),
  )
}

/**
 * Where the reporting-branch (`GitRefHistorySource`) working cache lives when no module history
 * directory is configured: `<cache>/history/<workspaceSlug>/.git-ref-cache`.
 *
 * The cache is keyed by repo rather than by module — it's a bare git working area for reading and
 * writing the `refs/heads/preview/…` reporting branches, and one per checkout is enough. When a
 * module history directory *is* configured, `GitRefHistorySource.defaultCacheDir` nests the cache
 * inside it instead; this is only the standalone fallback, which previously landed in the repo
 * working tree.
 */
fun composeAiGitRefCacheDir(repoRoot: File): File =
  File(
    File(composeAiCacheDir("history"), composeAiHistoryWorkspaceSlug(repoRoot)),
    ".git-ref-cache",
  )

/**
 * Stable per-workspace directory name: `<sanitised basename>-<sha256(path) truncated to 12 hex>`.
 *
 * The readable prefix keeps `~/.cache/composeai/history/` browsable; the hash keeps two checkouts
 * of the same repo (`~/src/app` and `~/src/app-worktree`, or the same name under different parents)
 * from colliding. The hash is taken over the absolute path with separators normalised to `/` —
 * deliberately NOT the real (symlink-resolved) path, because Gradle, the daemon and the VS Code
 * extension each learn the workspace root by a different route and only the unresolved form is
 * reliably identical across all three.
 */
fun composeAiHistoryWorkspaceSlug(workspaceRoot: File): String {
  val normalised = workspaceRoot.absolutePath.replace('\\', '/').trimEnd('/')
  val digest =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(normalised.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(12)
  val name = sanitiseHistorySegment(normalised.substringAfterLast('/'))
  return if (name.isEmpty()) digest else "$name-$digest"
}

/**
 * The module's path relative to [workspaceRoot], `/`-joined and sanitised per segment — e.g.
 * `auth/composables`. The root project maps to `_root`, as does any module that doesn't sit under
 * the workspace root (a `projectDir` reassigned outside the tree by `settings.gradle.kts`), which
 * falls back to a hash of its own path so two such modules can't collide.
 */
fun composeAiHistoryModuleSegment(workspaceRoot: File, projectDir: File): String {
  val root = workspaceRoot.absolutePath.replace('\\', '/').trimEnd('/')
  val module = projectDir.absolutePath.replace('\\', '/').trimEnd('/')
  if (module == root) return "_root"
  if (!module.startsWith("$root/")) {
    // Outside the workspace tree — no meaningful relative path, so key it by its own identity.
    return "_external-" + composeAiHistoryWorkspaceSlug(projectDir)
  }
  return module
    .removePrefix("$root/")
    .split('/')
    .filter { it.isNotEmpty() }
    .joinToString("/") { sanitiseHistorySegment(it) }
    .ifEmpty { "_root" }
}

/**
 * Replaces anything outside `[A-Za-z0-9._-]` with `-` so a segment is safe on every filesystem.
 *
 * Deliberately ASCII-only — `Char.isLetterOrDigit()` would accept Unicode letters, and the mirrored
 * TypeScript implementation uses a plain `[^A-Za-z0-9._-]` character class. A module directory with
 * non-ASCII characters must slugify identically on both sides or the two would read different
 * directories.
 */
private fun sanitiseHistorySegment(segment: String): String =
  segment
    .map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".-_") it else '-' }
    .joinToString("")
