package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.PreviewHistory
import ee.schimke.composeai.cli.serve.PreviewHistoryManifest
import java.io.File
import kotlin.system.exitProcess

/**
 * Writes `history.json` for a published baseline delivery branch — the precomputed per-preview
 * render timeline the hosted viewer reads instead of walking git itself.
 *
 * Meant to be driven by the publish pipeline right after it pushes renders, which is why the
 * defaults match that layout (`--branch compose-preview/main`, output `history.json`, baselines
 * read from the branch). It is a normal command rather than a private script so it stays testable
 * and so anyone debugging a delivery branch can regenerate the file by hand and diff it.
 *
 * ### Not `compose-preview history`
 *
 * The neighbouring [HistoryCommand] reads the **daemon's reporting branch** — one directory per
 * preview, `entry.json` sidecars with semantics / a11y / theme snapshots — and answers questions
 * about a single preview interactively. This reads a **CI baseline delivery branch** — a flat
 * `renders/<module>/` tree, no sidecars — and emits one static file covering every preview. Same
 * word, different branch, different shape; see [PreviewHistoryManifest] for why they aren't merged.
 *
 * ### Two branch layouts
 *
 * `--layout renders` (the default) is the baseline branch described above. `--layout images` is the
 * **design catalog** branch (`design-artifacts/<system>`), which stores
 * `images/<slug>/<variant>.png` and ships no `baselines.json` — there the join is derived from the
 * paths themselves, because the id the viewer addresses a preview by is exactly that path
 * flattened. See [PreviewHistoryManifest.Layout].
 *
 * Usage:
 * ```
 * compose-preview inspect history-manifest [--branch REF] [--repo DIR] [--output FILE]
 *                                          [--layout renders|images] [--baselines FILE] [--quiet]
 * ```
 *
 * `--branch` is any git ref the repo can resolve (`FETCH_HEAD` after a fetch, a remote-tracking
 * ref, a sha). `--baselines` overrides where `baselines.json` is read from; by default it comes out
 * of the same ref, so the manifest and the baselines it joins against are always the same snapshot.
 * It is meaningless on `--layout images`, which reads no sidecar.
 */
class HistoryManifestCommand(
  private val args: List<String>,
  private val workingDir: File = File("."),
  private val stdout: (String) -> Unit = ::println,
  private val stderr: (String) -> Unit = System.err::println,
  private val readBaselines: (File, String, String) -> String? = ::gitShow,
  /** Injectable so the refuse-to-publish paths below are testable without killing the JVM. */
  private val exit: (Int) -> Nothing = { exitProcess(it) },
) {

  fun run() {
    if ("--help" in args || "-h" in args) {
      stdout(USAGE)
      return
    }

    val branch = args.valueOf("--branch") ?: DEFAULT_BRANCH
    val repoDir = args.valueOf("--repo")?.let { File(it) } ?: workingDir
    val output = File(args.valueOf("--output") ?: PreviewHistoryManifest.FILE_NAME)
    val quiet = "--quiet" in args

    // Which delivery-branch layout this branch uses. Defaults to the baseline one so every existing
    // invocation — the `apply` action's, and anyone regenerating a branch by hand — keeps behaving
    // exactly as before.
    val layoutArg = args.valueOf("--layout")
    val layout = PreviewHistoryManifest.Layout.of(layoutArg ?: DEFAULT_LAYOUT.name)
    if (layout == null) {
      stderr(
        "compose-preview history-manifest: unknown --layout '$layoutArg'; expected one of " +
          PreviewHistoryManifest.Layout.entries.joinToString(", ") { it.name.lowercase() } +
          "."
      )
      exit(1)
    }

    // A design-catalog branch carries no `baselines.json` and needs none: its ids are the render
    // paths flattened, so the join is derived below from whatever git reports. Reading a sidecar
    // here would be inventing a requirement the layout does not have.
    val baselinesJson =
      if (layout != PreviewHistoryManifest.Layout.RENDERS) null
      else
        args.valueOf("--baselines")?.let { path ->
          val file = File(path)
          if (!file.isFile) {
            stderr("compose-preview history-manifest: no baselines file at ${file.path}.")
            exit(1)
          }
          file.readText()
        } ?: readBaselines(repoDir, branch, "baselines.json")

    if (layout == PreviewHistoryManifest.Layout.RENDERS && baselinesJson == null) {
      // Without baselines.json there is no path→preview-id mapping, so every timeline would be
      // dropped and we would write an empty manifest that looks like "this branch has no history".
      // Failing loudly is better than publishing that.
      stderr(
        "compose-preview history-manifest: could not read baselines.json from '$branch'. " +
          "Pass --baselines to point at it explicitly."
      )
      exit(1)
    }

    val pathToPreviewId = baselinesJson?.let { PreviewHistoryManifest.renderPathsToPreviewIds(it) }
    if (baselinesJson != null && pathToPreviewId.isNullOrEmpty()) {
      stderr(
        "compose-preview history-manifest: baselines.json from '$branch' yielded no usable " +
          "entries; refusing to write an empty manifest."
      )
      exit(1)
    }

    // Resolve the ref up front. PreviewHistory.read returns an empty map when git exits non-zero,
    // so a typo'd or unfetched ref is otherwise indistinguishable from "no history" — and since
    // the publish step overwrites history.json with whatever this writes, that would silently
    // replace a good manifest with an empty one.
    if (resolveSha(repoDir, branch) == null) {
      stderr(
        "compose-preview history-manifest: '$branch' is not a ref this repository can resolve."
      )
      exit(1)
    }

    // Anchor to the newest commit that touched renders, NOT the branch tip. The manifest ships in
    // its own commit, which moves the tip — so a tip-derived value changes on every publish even
    // when no render did, the regenerated file never matches the published one, and each baseline
    // run appends another history commit forever. Pinning to the render tip makes a no-op run
    // regenerate a byte-identical file, which the push then skips. It also describes the manifest
    // better: this is the render state the timeline covers.
    val generatedFrom = renderTip(repoDir, branch) ?: resolveSha(repoDir, branch) ?: branch

    val timelines =
      try {
        PreviewHistory.read(repoDir, branch, layout.dir)
      } catch (e: Exception) {
        stderr(
          "compose-preview history-manifest: failed to read history from '$branch': ${e.message}"
        )
        exit(1)
      }

    // A resolvable ref with no render history means the log read failed or the pathspec matched
    // nothing — never a legitimately empty branch. Refuse rather than publish a manifest asserting
    // this branch has no history.
    if (timelines.isEmpty()) {
      val against =
        pathToPreviewId?.let { ", while baselines.json lists ${it.size} previews" }.orEmpty()
      stderr(
        "compose-preview history-manifest: '$branch' resolved but yielded no render history " +
          "under '${layout.dir}/'$against; refusing to write an empty manifest."
      )
      exit(1)
    }

    // On the image layout the join is total over what git reported, so it is derived here rather
    // than read: every render on the branch is a render the timeline can key.
    val join = pathToPreviewId ?: PreviewHistoryManifest.imagePathsToPreviewIds(timelines.keys)
    if (join.isEmpty()) {
      stderr(
        "compose-preview history-manifest: none of the ${timelines.size} render paths under " +
          "'${layout.dir}/' on '$branch' could be keyed to a preview; refusing to write an " +
          "empty manifest."
      )
      exit(1)
    }
    val manifest = PreviewHistoryManifest.build(timelines, join, generatedFrom)

    output.absoluteFile.parentFile?.mkdirs()
    output.writeText(PreviewHistoryManifest.encode(manifest))

    if (!quiet) {
      val versions = manifest.previews.values.sumOf { it.versions.size }
      val unstable = manifest.previews.values.count { it.unstable }
      // Report the drop count explicitly: silence here would read as "every render was covered",
      // when in fact renders for deleted or renamed previews are intentionally left out.
      val dropped = timelines.keys.count { it !in join }
      stdout(
        "compose-preview history-manifest: wrote ${output.path}: " +
          "${manifest.previews.size} previews, $versions versions, $unstable unstable" +
          if (dropped > 0) ", $dropped unmatched render paths dropped." else "."
      )
    }
  }

  private companion object {
    const val DEFAULT_BRANCH = "compose-preview/main"

    /** Keeps every pre-existing invocation on the baseline layout it was written for. */
    val DEFAULT_LAYOUT = PreviewHistoryManifest.Layout.RENDERS

    val USAGE =
      """
      Usage: compose-preview inspect history-manifest [options]

      Writes history.json for a baseline delivery branch: the per-preview render
      timeline a viewer reads instead of walking git. Not the same as
      `compose-preview history`, which inspects the daemon's reporting branch.

        --branch REF     Delivery-branch ref to read (default: $DEFAULT_BRANCH)
        --repo DIR       Repository to run git in (default: current directory)
        --output FILE    Where to write (default: ${PreviewHistoryManifest.FILE_NAME})
        --layout NAME    Branch layout: renders (default) or images. `renders` reads
                         renders/ and joins through baselines.json; `images` reads
                         images/ and derives ids from the paths, as the design
                         catalog branches (design-artifacts/<system>) are published.
        --baselines FILE Read baselines.json from here instead of from REF
                         (renders layout only)
        --quiet          Suppress the summary line
      """
        .trimIndent()

    fun List<String>.valueOf(flag: String): String? {
      val i = indexOf(flag)
      return if (i >= 0 && i + 1 < size) this[i + 1] else null
    }
  }
}

private fun gitShow(repoDir: File, ref: String, path: String): String? = runCatching {
  val p =
    ProcessBuilder("git", "show", "$ref:$path")
      .directory(repoDir)
      .redirectErrorStream(false)
      .start()
  val out = p.inputStream.bufferedReader().readText()
  if (p.waitFor() == 0 && out.isNotBlank()) out else null
}
  .getOrNull()

/** Newest commit on [ref] that touched the renders tree, ignoring history-only commits. */
private fun renderTip(repoDir: File, ref: String, pathspec: String = "renders"): String? =
  runCatching {
    val p = ProcessBuilder("git", "rev-list", "-1", ref, "--", pathspec).directory(repoDir).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0 && out.isNotEmpty()) out else null
  }
  .getOrNull()

private fun resolveSha(repoDir: File, ref: String): String? = runCatching {
  val p = ProcessBuilder("git", "rev-parse", ref).directory(repoDir).start()
  val out = p.inputStream.bufferedReader().readText().trim()
  if (p.waitFor() == 0 && out.isNotEmpty()) out else null
}
  .getOrNull()
