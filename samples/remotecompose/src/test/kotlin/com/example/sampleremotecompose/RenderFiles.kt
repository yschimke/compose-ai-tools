package com.example.sampleremotecompose

import java.io.File

/**
 * Resolve a rendered artifact by its **readable** stem.
 *
 * Render filenames are `<readable>-<digest><structural suffix>.<ext>` — see
 * `docs/RENDER_FILENAMES.md`. The digest is 8 hex chars of `sha256(preview.id)`, which is what
 * keeps filenames unique and stable, but it means a test can't spell the whole name literally.
 * Match on the shape instead: the readable part and any structural suffix (`_SCROLL_top`,
 * `_FOCUS_2`, `_TIME_800ms`, a `@PreviewParameter` row label) are still predictable.
 *
 * Returns a non-existent [File] when nothing matches, so an `exists()` assertion fails with a
 * readable path rather than a null.
 */
internal fun renderFile(
  dir: File,
  stem: String,
  suffix: String = "",
  ext: String = "png",
): File {
  val pattern =
    Regex("^${Regex.escape(stem)}-[0-9a-f]{8}${Regex.escape(suffix)}\\.${Regex.escape(ext)}$")
  return dir.listFiles()?.firstOrNull { pattern.matches(it.name) } ?: File(dir, "$stem$suffix.$ext")
}

/**
 * The readable stem of a render filename — everything before the `-<digest>`. Inverse of
 * [renderFile] for tests that enumerate a directory and assert on which previews landed there.
 */
internal fun readableStem(fileName: String): String =
  Regex("^(.*)-[0-9a-f]{8}(?:[._].*)?$").find(fileName.substringBefore(".png"))?.groupValues?.get(1)
    ?: fileName.substringBefore(".png")
