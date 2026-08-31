package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BUNDLE_FIGMA_RASTER_DIR_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_FIGMA_SVG_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_FONTS_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_LAYOUT_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_PREVIEWS_DIR
import ee.schimke.composeai.bundle.BUNDLE_SEMANTICS_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_WEB_DIR
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.WebEmbed
import ee.schimke.composeai.bundle.embedWebIntoZip
import ee.schimke.composeai.bundle.expandZipBytesSafely
import ee.schimke.composeai.bundle.injectFigmaRasterIntoBundle
import ee.schimke.composeai.bundle.injectFigmaSvgIntoBundle
import ee.schimke.composeai.bundle.injectFontsIntoBundle
import ee.schimke.composeai.bundle.injectLayoutIntoBundle
import ee.schimke.composeai.bundle.injectRawZipEntries
import ee.schimke.composeai.bundle.injectSemanticsIntoBundle
import ee.schimke.composeai.bundle.resolveInBundleTarget
import ee.schimke.composeai.cli.serve.RenderOutcome
import ee.schimke.composeai.cli.serve.ServeBundleDaemon
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.cli.serve.SvgOutcome
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source

/**
 * `compose-preview bundle <pack|inspect|extract|render>` — produce, inspect, and play portable
 * preview bundles.
 *
 * # Bundle file shape
 *
 * The bundle is a PNG + ZIP polyglot. The leading bytes are a valid PNG (the cover preview's
 * rendered image — Finder, Preview.app, GitHub, Slack all show it as an image). The trailing bytes
 * are a standard zip archive that any tooling reads via the EOCD signature `PK\x05\x06`. See
 * `PreviewBundleFormat.kt` in the plugin module for the in-repo schema definitions.
 *
 * # Subcommands
 *
 * - **`pack`** — runs `composePreviewRender` (for the cover) and `composePreviewBundle` against a
 *   Gradle module and writes the resulting `.png` polyglot. Selection is via repeatable `--id`
 *   flags; the first id becomes the cover. `--no-render` skips the render step and packs with a
 *   stub gray cover.
 * - **`inspect`** — open a bundle file and print its `bundle.json` + `report.json` summary,
 *   including the minimization report (how many module classes were kept vs total, which Maven
 *   coordinates contribute reachable classes). Read-only.
 * - **`extract`** — extract the zip portion of a bundle into a directory. Each entry's path is
 *   validated to live inside the target dir — `../` traversal in a hostile bundle is rejected.
 * - **`render`** — re-render the bundle's previews from a packed `.png`, not from a Gradle module.
 *   v1 is a stub: it extracts the bundle, prints the manifest + resolved classpath, and tells you
 *   what *would* render. Actual rendering (resolving Maven coords + spawning DesktopRendererMain)
 *   is the next milestone.
 * - **`embed`** — convert a packed bundle into a **self-contained web embed** (the "js bundle"): a
 *   `compose-preview-embed.js` web component plus an `index.html` demo page, with the baked
 *   previews inlined as `data:` URIs. An app drops the one script into its site and adds
 *   `<compose-preview-gallery>` to put the rendered previews on a web page — no build step, no
 *   framework, no network. By default the files land in a directory; `--in-bundle` instead writes
 *   them into the bundle's own zip under `web/` (the `.png` stays a valid polyglot). See
 *   [WebEmbed].
 */
class BundleCommand(args: List<String>) : Command(args) {

  override fun run() {
    // Find the subcommand skipping any leading valued flags (`bundle --module :app pack`), then
    // hand the subcommand its args with only the subcommand token removed (the leading flags stay).
    val subIndex = CliFlags.firstPositionalIndex(args)
    val sub = if (subIndex >= 0) args[subIndex] else null
    val subArgs =
      if (subIndex >= 0) args.toMutableList().apply { removeAt(subIndex) } else emptyList()
    when (sub) {
      "pack" -> PackSubcommand(subArgs).run()
      "split" -> SplitSubcommand(subArgs).run()
      "inspect" -> InspectSubcommand(subArgs).run()
      "extract" -> ExtractSubcommand(subArgs).run()
      "embed" -> EmbedSubcommand(subArgs).run()
      "externalize" -> ExternalizeSubcommand(subArgs).run()
      "render" -> RenderSubcommand(subArgs).run()
      "repack" -> RepackSubcommand(subArgs).run()
      "merge" -> MergeSubcommand(subArgs).run()
      "keygen" -> KeygenSubcommand(subArgs).run()
      "sign" -> SignSubcommand(subArgs).run()
      "verify" -> VerifySubcommand(subArgs).run()
      "daemon" -> BundleDaemonCommand(subArgs).run()
      null,
      "help",
      "--help",
      "-h" -> {
        printHelp()
        if (sub == null) exitProcess(64)
      }
      else -> {
        System.err.println("Unknown bundle subcommand: $sub")
        printHelp()
        exitProcess(64)
      }
    }
  }

  private fun printHelp() {
    println(
      """
      compose-preview bundle — portable preview bundles (PNG+ZIP polyglot)

      A <bundle> below is a local path OR an http(s)/file URL — URLs are downloaded first.

      Usage:
        compose-preview bundle pack [--module <name>] [--id <preview>...] [-o <file.png>] [--no-render] [--with-semantics]
        compose-preview bundle pack --per-preview [--module <name>] [--id <preview>...] [-o <dir>]
        compose-preview bundle split   <sheet.png | URL> -o <dir> [--view-only | --shared-classpath-out <pool>] [--carriage-report <file.json>]
        compose-preview bundle inspect <bundle.png | URL>
        compose-preview bundle extract <bundle.png | URL> [-o <dir>]
        compose-preview bundle embed   <bundle.png | URL> [-o <dir|file.png>] [--title T] [--external-images] [--in-bundle]
        compose-preview bundle externalize <bundle.png | URL> --res-out <dir> [-o <file.png>] [--ext ttf,otf,woff,woff2] [--json]
        compose-preview bundle render  <bundle.png | URL> [-o <dir>] [--knob k=v …] [--res <pool>] [--svg]  (re-render previews; --knob re-themes, --svg also exports vectors)
        compose-preview bundle repack  <bundle.png | URL> --renders <dir> -o <out.png>  (swap baked previews for re-rendered PNGs + figma.svg)
        compose-preview bundle merge   <base.png | URL> <shard.png | URL>… -o <out.png>  (union the previews of bundles packed from disjoint render selections)
        compose-preview bundle keygen  [-o <key.pem>] [--key-id <id>]  (mint an Ed25519 signing keypair)
        compose-preview bundle sign    <bundle.png> --key <private-key> --key-id <id> [--producer <name>]
        compose-preview bundle verify  <bundle.png | URL> [--trust <store.json>] [--origin <repo@branch>]
        compose-preview bundle daemon  <bundle.png | URL> [-v]         (spawn the desktop daemon over stdio)

      Signing (producer trust for the public preview server):
        keygen  Mint an Ed25519 keypair; prints a ready-to-paste trust-store \"keys\" entry.
        sign    Append a detached signature over the bundle's canonical digest (idempotent per key-id;
                multiple producers can each sign). --provenance-identity attaches a CI OIDC identity.
        verify  Check a bundle against a trust store and print the verdict (signature / branch /
                provenance, or why it's unverified). Exit 3 when unverified.

      Pack flags:
        --id <preview-id>   Preview to include. Repeatable. First is the cover. Default: all.
        --exclude-preview-id <id|glob>
                            Skip rendering (and semantics-capturing) previews whose discovered id
                            matches. Repeatable and comma-separated; '*'/'?' globs, a plain
                            substring, or '=<id>' for an exact match, matching
                            composePreviewRender --exclude-preview-id. Prefer '=' for a GENERATED
                            list (a render sharder's "everything not mine"): ids are hierarchical,
                            so the plain substring form drops a base id's variants too.
                            Unlike --id (which selects whole previews to PACK) this thins the RENDER
                            of one function's multipreview / multi-annotation fan-out — a design
                            catalog deferring a palette to its live server passes the deferred ids
                            here. Excluded previews stay listed in the bundle (addressable, just
                            without a baked PNG). Forwarded to Gradle as
                            -PcomposePreview.idExclude; also read from the
                            ORG_GRADLE_PROJECT_composePreview.idExclude env var when the flag is
                            absent, so an env-only setup thins the semantics pass too.
        --id-file <path>    The previews to pack, one per line, read from a file. The include-side
                            twin of --exclude-preview-id-file below, and needed for the same
                            reason: --id comma-splits its values, so an id containing a comma (a
                            @Preview(widthDp = …, heightDp = …) mints
                            `…AppCard_width=227dp,height=200dp,dpi=320`) is shattered into three.
                            The render hides that — it matches ids by substring — but
                            composePreviewBundle matches exactly and fails with "preview id not
                            found" naming the first fragment. Wins over --id. An unreadable path is
                            an error, not an empty selection.
        --exclude-preview-id-file <path>
                            The same exclusions, one per line, read from a file. Use this for a
                            GENERATED list: a preview id may itself contain a comma (a
                            @Preview(widthDp = …, heightDp = …) mints
                            `…Button_width=227dp, height=100dp, dpi=320`), which the
                            comma-separated flag above cannot carry — the split shatters each id
                            into fragments and, since a plain pattern matches on substring, a
                            fragment like `dpi=320` excludes the whole module. Forwarded to Gradle
                            as -PcomposePreview.idExcludeFile (a path, never re-joined). Wins over
                            --exclude-preview-id and the env var. An unreadable path is an error,
                            not an empty exclusion list.
        --exclude-preview-row <label|glob>
                            Skip rendering the @PreviewParameter rows whose label matches — the fan-out
                            --exclude-preview-id can't reach, because discovery never sees the rows
                            (they exist only once the renderer enumerates the provider). Repeatable
                            and comma-separated; an exact label or a '*'/'?' glob, case-insensitive,
                            matched against the label in <stem>_<label>.png. Never empties a preview's
                            rows. Forwarded as -PcomposePreview.rowExclude; also read from
                            ORG_GRADLE_PROJECT_composePreview.rowExclude when the flag is absent.
                            Desktop render path only (see issue #2977).
        --per-preview       Emit one valid single-preview bundle per preview (<out-dir>/<id>.png)
                            instead of a single sheet — the addressable-preview unit, each openable /
                            re-renderable on its own. Renders once, then packs each (minimized to its
                            own closure). With --per-preview, -o is an output DIRECTORY (default:
                            <module>/build/compose-previews/bundles). --id filters which previews to
                            emit. (--with-semantics is not yet carried per bundle here.)
        -o, --output <file> Output file path. Default: <module>/build/compose-previews/bundle.png.
        --no-render         Skip composePreviewRender — pack with a stub gray cover.
        --embed-deps        Carry reachable third-party jars inside the bundle (libs/) instead of
                            referencing Maven coordinates. Bigger file, but renders with no network
                            and no build system on the other end (resolution=embedded).
        --include-data-extensions
                            Carry the per-extension data reports (a11y findings, theme tokens, drawn
                            strings, …) under extensions/<id>.json, sliced to the cover (default)
                            preview, so a reader can surface the headline image's data without
                            re-rendering. Off by default.
        --with-semantics    Carry each preview's semantics tree (per-node bounds, label/text, and
                            resolved foreground/background colours) as previews/<id>.semantics.json —
                            the shape design-parity reads for contrast/a11y + token checks. Also
                            carries the layout-inspector tree (full LayoutNode walk with per-node
                            bounds + resolved design tokens) as previews/<id>.layout.json, for
                            slot-level redlines/wireframes, the fonts/used record (requested vs
                            resolved font families) as previews/<id>.fonts.json, from which the
                            design-catalog export generates the in-browser tier's fonts.json, and the
                            layered compose/figma-svg export (editable vector) as
                            previews/<id>.figma.svg, shipped per sticker beside the raster PNG.
                            Produced by a short-lived daemon render (no separate --with-extension
                            pass needed). Off by default; ignored with --no-render.

      Split flags (sheet → one bundle per preview):
        -o, --output <dir>  Directory to write <id>.png bundles into. Default: <sheet>-split/.
        --view-only         Drop the re-render classpath (classes/app.jar + libs/) from each output,
                            keeping the baked image + every sidecar (semantics / layout / figma.svg /
                            overrides / catalog / fonts). Produces small (~tens of KB) addressable
                            stickers a viewer / detached reader opens, at the cost of live re-render.
                            Without it, each bundle carries the shared classpath and can re-render
                            (larger — the shared jars repeat per preview). A sheet packed
                            --with-semantics yields per-preview bundles that carry their semantics,
                            with no daemon or re-render.
        --shared-classpath-out <pool-dir>
                            Opt-in live mode: publish classes/app.jar once as <pool>/<sha256> and
                            record that content-addressed entry in every split bundle. The preview
                            server hydrates it for daemon execution and executable downloads. The
                            existing full mode remains self-contained. Cannot combine with
                            --view-only.
        --carriage-report <file.json>
                            Write the measured shared carriage — bytes repeated in every bundle,
                            and its share of the whole split — as JSON, for a publisher that gates
                            on it. The same numbers are printed either way, and reported loudly
                            once the repetition is at least half the output.

      Inspect / extract / render flags:
        -o, --output <dir>  Directory to extract / render into. Default: alongside the bundle.

      Embed flags:
        -o, --output <path> Directory to write the web embed into (default: alongside the bundle),
                            or, with --in-bundle, the output .png (default: rewrite in place).
        --title <text>      Heading shown on the demo page / gallery. Default: the module path.
        --external-images   Write previews as previews/<id>.png files instead of inlining them as
                            data: URIs in the script (cacheable assets vs one self-contained .js).
        --in-bundle         Embed the web resources into the bundle's own zip under web/ instead of
                            a loose directory — the .png stays a valid polyglot and now carries a
                            web/index.html you can open after unzipping. Idempotent.

      Externalize flags:
        --res-out <dir>     Directory the lifted resources are written to, content-addressed by
                            sha256 (`<dir>/<sha256>`). Required. The publish pipeline carries this
                            pool once per branch and a re-rendering server rehydrates from it.
        -o, --output <file> Write the externalized bundle here instead of rewriting in place
                            (required for a URL input, which is a temp file).
        --ext <list>        Comma-separated file extensions to lift out. Default: ttf,otf,woff,woff2
                            (the fonts that dominate a catalog bundle).
        --json              Print a machine-readable summary (bundle path, res dir, externalized
                            {path,sha256,size} list) instead of the human summary.
      """
        .trimIndent()
    )
  }
}

private class PackSubcommand(private val args: List<String>) {
  private val module: String? = args.flagValue("--module")
  private val output: String? = args.flagValue("--output") ?: args.flagValue("-o")
  private val noRender: Boolean = "--no-render" in args
  private val embedDeps: Boolean = "--embed-deps" in args
  private val includeDataExtensions: Boolean = "--include-data-extensions" in args
  private val withSemantics: Boolean = "--with-semantics" in args
  private val perPreview: Boolean = "--per-preview" in args
  private val verbose: Boolean = "--verbose" in args || "-v" in args
  private val progress: Boolean = verbose || "--progress" in args
  private val timeout: String? = args.flagValue("--timeout")
  private val ids: List<String> = PackPreviewIdExclusions.selectedIds(args)

  /**
   * `--exclude-preview-id` patterns (issue #2966) — the previews this pack must NOT render or
   * semantics-capture. Falls back to the `ORG_GRADLE_PROJECT_composePreview.idExclude` env var so a
   * caller that only sets the env var (the way the design-artifacts workflow passes the name filter
   * through to `composePreviewRender`) still gets the semantics pass thinned, which the env var
   * alone cannot do — the CLI, not Gradle, drives that capture.
   */
  private val excludePreviewIds: List<String> = PackPreviewIdExclusions.fromArgs(args)

  /**
   * `--exclude-preview-id-file`, when one was passed: the same patterns as [excludePreviewIds], but
   * kept as a FILE so the render can be handed the path instead of a comma-joined string.
   *
   * That distinction is the whole point of the flag. A preview id may contain a comma, so joining
   * the list for `-PcomposePreview.idExclude` and splitting it back shatters each id into
   * fragments; because a plain pattern matches on substring, a fragment such as `dpi=320` then
   * excludes every preview in the module.
   */
  private val excludePreviewIdFile: java.io.File? = PackPreviewIdExclusions.fileFromArgs(args)

  /**
   * `--exclude-preview-row` labels — the `@PreviewParameter` rows this pack must not render. Render
   * only: the semantics capture is per preview, so a parameterized preview costs one capture
   * whatever its provider fans out to and there is nothing to thin there.
   */
  private val excludePreviewRows: List<String> = PackPreviewIdExclusions.rowsFromArgs(args)

  fun run() {
    if (perPreview) {
      runPerPreview()
      return
    }
    val cmdArgs = buildList {
      module?.let {
        add("--module")
        add(it)
      }
      if (verbose) add("--verbose")
      if (progress && !verbose) add("--progress")
      timeout?.let {
        add("--timeout")
        add(it)
      }
    }
    object : Command(cmdArgs) {
        override fun run() {
          withGradle { gradle ->
            val modules = resolveModules(gradle)
            if (modules.size != 1) {
              System.err.println(
                "bundle pack expects exactly one module; found ${modules.size}. Use --module to disambiguate."
              )
              exitProcess(1)
            }
            val target = modules.single()
            val resolvedOutput =
              output?.let { File(it).absoluteFile }
                ?: target.projectDir.resolve("build/compose-previews/bundle.png")
            resolvedOutput.parentFile?.mkdirs()

            val gradleArgs = buildList {
              if (ids.isNotEmpty())
                add("-PbundlePreviewIds=${ids.joinToString(",") { encodePreviewId(it) }}")
              // Thin the RENDER itself (issue #2966): `composePreviewRender` reads this as the
              // `--exclude-preview-id` convention. Passed explicitly rather than relying on the
              // inherited env var so `--exclude-preview-id` works from any shell, and so the
              // patterns the semantics pass below skips are provably the same ones the render did.
              // The FILE form when there is one — see [excludePreviewIdFile] for why joining is
              // not equivalent. Absolute, because the render's Gradle build runs in the module's
              // own directory rather than the CLI's.
              if (excludePreviewIdFile != null)
                add(
                  "-P${PackPreviewIdExclusions.FILE_GRADLE_PROPERTY}=" +
                    excludePreviewIdFile.absolutePath
                )
              else if (excludePreviewIds.isNotEmpty())
                add(
                  "-P${PackPreviewIdExclusions.GRADLE_PROPERTY}=" +
                    excludePreviewIds.joinToString(",")
                )
              // Same for the `@PreviewParameter` row axis, which the render resolves one level
              // deeper
              // (the labels don't exist until the provider is enumerated inside the render JVM).
              if (excludePreviewRows.isNotEmpty())
                add(
                  "-P${PackPreviewIdExclusions.ROW_GRADLE_PROPERTY}=" +
                    excludePreviewRows.joinToString(",")
                )
              if (embedDeps) add("-PbundleEmbedDeps=true")
              if (includeDataExtensions) add("-PbundleIncludeDataExtensions=true")
              add("-PbundleOutput=${resolvedOutput.absolutePath}")
            }
            // `--with-semantics` carries the per-preview semantics blob (issue #1843). The
            // semantics tree is produced exclusively by the daemon (the standalone
            // composePreviewRender task writes no semantics sidecars), so we start the daemon and
            // read the blobs back below. composePreviewDaemonStart runs in its OWN Gradle
            // invocation (not bundled with render+bundle), AFTER the bundle is already written, so
            // a
            // daemon-start failure degrades gracefully instead of aborting the pack —
            // `--with-semantics` is best-effort (issue #1885). Pointless without a render (the
            // daemon needs something to capture), so skip when --no-render.
            val packSemantics = withSemantics && !noRender
            if (withSemantics && noRender) {
              System.err.println(
                "bundle pack: --with-semantics needs a render; ignoring it because --no-render was passed."
              )
            }
            val tasks = buildList {
              if (!noRender) add(":${target.gradlePath}:composePreviewRender")
              add(":${target.gradlePath}:composePreviewBundle")
            }
              .toTypedArray()
            val ok = runGradle(gradle, *tasks, arguments = gradleArgsWithForce(gradleArgs))
            if (!ok) {
              System.err.println(
                "Gradle bundle task failed." +
                  if (!verbose) " Re-run with --verbose to surface the underlying Gradle error."
                  else ""
              )
              exitProcess(1)
            }

            if (!resolvedOutput.isFile) {
              System.err.println(
                "Bundle task reported success but ${resolvedOutput.path} is missing."
              )
              exitProcess(1)
            }

            val meta =
              try {
                BundleReader.readMetadata(resolvedOutput)
              } catch (e: Exception) {
                System.err.println(
                  "Wrote ${resolvedOutput.path} (${resolvedOutput.length()} bytes) but failed to read it back: ${e.message}"
                )
                exitProcess(1)
              }
            // Inject semantics (if requested) before the summary so its byte count reflects the
            // enriched bundle; the summary line for semantics is printed after the main summary.
            // The bundle is already written and valid at this point, so everything below is
            // best-effort: a daemon-start / open / render failure warns and leaves the bundle as-is
            // (issue #1885), never failing the pack.
            val semanticsLine =
              if (packSemantics) {
                // Start the daemon in a SEPARATE Gradle invocation — its failure must NOT abort the
                // pack. The launch descriptor is regenerated fresh against the consumer's current
                // classpath, then DaemonSemanticsFetcher reads the blobs back.
                val daemonStarted =
                  runGradle(
                    gradle,
                    ":${target.gradlePath}:composePreviewDaemonStart",
                    arguments = gradleArgsWithForce(gradleArgs),
                  )
                if (!daemonStarted) {
                  System.err.println(
                    "bundle pack: --with-semantics could not start the preview daemon for " +
                      "${target.gradlePath} (composePreviewDaemonStart failed) — bundle written " +
                      "without semantics. Re-run with --verbose to surface the underlying Gradle error."
                  )
                  null
                } else {
                  packSemanticsBlob(
                    target = target,
                    bundleFile = resolvedOutput,
                    meta = meta,
                    renderTimeout = timeoutSeconds.seconds,
                  )
                }
              } else null

            printPackSummary(resolvedOutput, meta)
            semanticsLine?.let { println(it) }
          }
        }
      }
      .run()
  }

  /**
   * `--per-preview`: emit one **valid, self-contained** single-preview bundle per discovered
   * preview (`<out-dir>/<id>.png`) instead of a single sheet carrying them all. Each output is a
   * real polyglot a reader can open / re-render on its own — the addressable-preview unit.
   *
   * Renders **once** (the expensive step) and then packs each preview through the same
   * `composePreviewBundle` path a single `--id` pack uses, so every emitted bundle is minimized to
   * just that preview's closure. Because the render daemon no longer rides a preview's classpath, a
   * catalog sticker's bundle is ~tens of KB rather than the ~MB it was when `:daemon:core` was
   * inlined.
   *
   * Trade-off (documented, not hidden): packing is N Gradle bundle invocations over one shared
   * connection — the render is shared, but each pack re-runs the closure scan. A task-level "scan
   * once, emit N" mode is the efficiency follow-up. `--with-semantics` is not yet carried per
   * bundle here (it would start the daemon once per preview); it's skipped with a warning.
   */
  private fun runPerPreview() {
    val cmdArgs = buildList {
      module?.let {
        add("--module")
        add(it)
      }
      if (verbose) add("--verbose")
      if (progress && !verbose) add("--progress")
    }
    object : Command(cmdArgs) {
        override fun run() {
          if (withSemantics) {
            System.err.println(
              "bundle pack: --with-semantics is not yet carried in --per-preview mode; " +
                "writing baked single-preview bundles without semantics sidecars."
            )
          }
          withGradle { gradle ->
            val modules = resolveModules(gradle)
            if (modules.size != 1) {
              System.err.println(
                "bundle pack --per-preview expects exactly one module; found ${modules.size}. " +
                  "Use --module to disambiguate."
              )
              exitProcess(1)
            }
            val target = modules.single()
            val outDir =
              output?.let { File(it).absoluteFile }
                ?: target.projectDir.resolve("build/compose-previews/bundles")
            outDir.mkdirs()

            val sharedArgs = buildList {
              // The render filters ride the shared render too, not just the single-sheet path
              // above:
              // `--per-preview` runs `composePreviewRender` once and packs each result, so leaving
              // them off here would accept the flags and then render every excluded preview/row
              // anyway. `--id` still selects which previews get *packed*; these thin what is
              // *drawn*.
              // The FILE form when there is one — see [excludePreviewIdFile] for why joining is
              // not equivalent. Absolute, because the render's Gradle build runs in the module's
              // own directory rather than the CLI's.
              if (excludePreviewIdFile != null)
                add(
                  "-P${PackPreviewIdExclusions.FILE_GRADLE_PROPERTY}=" +
                    excludePreviewIdFile.absolutePath
                )
              else if (excludePreviewIds.isNotEmpty())
                add(
                  "-P${PackPreviewIdExclusions.GRADLE_PROPERTY}=" +
                    excludePreviewIds.joinToString(",")
                )
              if (excludePreviewRows.isNotEmpty())
                add(
                  "-P${PackPreviewIdExclusions.ROW_GRADLE_PROPERTY}=" +
                    excludePreviewRows.joinToString(",")
                )
              if (embedDeps) add("-PbundleEmbedDeps=true")
              if (includeDataExtensions) add("-PbundleIncludeDataExtensions=true")
            }

            // Render every preview once; the per-preview packs below reuse these render outputs, so
            // the expensive render happens a single time rather than once per bundle.
            if (!noRender) {
              val rendered =
                runGradle(
                  gradle,
                  ":${target.gradlePath}:composePreviewRender",
                  arguments = gradleArgsWithForce(sharedArgs),
                )
              if (!rendered) {
                System.err.println(
                  "Gradle render task failed." +
                    if (!verbose) " Re-run with --verbose to surface the underlying Gradle error."
                    else ""
                )
                exitProcess(1)
              }
            }

            // Enumerate discovered previews from the freshly written manifest.
            val manifest = readManifest(target)
            if (manifest == null || manifest.previews.isEmpty()) {
              System.err.println(
                "bundle pack --per-preview: no previews found for ${target.gradlePath} " +
                  "(did discovery/render run?)."
              )
              exitProcess(1)
            }
            val allIds = manifest.previews.map { it.id }
            // Fail on any unknown --id rather than silently dropping it (parity with the
            // single-pack
            // path's resolveSelection) — a renamed/mistyped id must not quietly omit a bundle.
            if (ids.isNotEmpty()) {
              val known = allIds.toSet()
              val unknown = ids.filterNot { it in known }
              if (unknown.isNotEmpty()) {
                System.err.println(
                  "bundle pack --per-preview: unknown preview id(s): ${unknown.joinToString(", ")}. " +
                    "Available: ${allIds.joinToString(", ")}"
                )
                exitProcess(1)
              }
            }
            val previewIdsToPack = if (ids.isEmpty()) allIds else allIds.filter { it in ids }
            if (previewIdsToPack.isEmpty()) {
              System.err.println("bundle pack --per-preview: --id selection matched no previews.")
              exitProcess(1)
            }

            // Map each id to a UNIQUE output file up front: distinct ids can sanitize to the same
            // stem (e.g. `A B` and `A_B` both → `A_B`), which would silently overwrite. On a
            // collision, append `-2`, `-3`, … so every preview gets its own bundle file.
            val usedStems = HashSet<String>()
            val idToFile = LinkedHashMap<String, File>()
            for (id in previewIdsToPack) {
              val base = sanitizeBundleFileName(id)
              var stem = base
              var n = 1
              while (!usedStems.add(stem)) {
                n++
                stem = "$base-$n"
              }
              idToFile[id] = outDir.resolve("$stem.png")
            }

            // Pack each preview into its own single-preview bundle, reusing the already-rendered
            // PNGs (bundle task only — no re-render).
            val written = mutableListOf<Pair<String, File>>()
            for ((id, outFile) in idToFile) {
              val perArgs =
                sharedArgs +
                  listOf(
                    "-PbundlePreviewIds=${encodePreviewId(id)}",
                    "-PbundleOutput=${outFile.absolutePath}",
                  )
              val ok =
                runGradle(
                  gradle,
                  ":${target.gradlePath}:composePreviewBundle",
                  arguments = gradleArgsWithForce(perArgs),
                )
              if (!ok || !outFile.isFile) {
                System.err.println(
                  "bundle pack --per-preview: failed to pack '$id'." +
                    if (!verbose) " Re-run with --verbose to surface the underlying Gradle error."
                    else ""
                )
                exitProcess(1)
              }
              written += id to outFile
            }

            printPerPreviewSummary(outDir, written)
          }
        }
      }
      .run()
  }

  /** Map a preview id to a filesystem-safe bundle filename stem (keep `A-Za-z0-9._-`, else `_`). */
  private fun sanitizeBundleFileName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }

  private fun printPerPreviewSummary(outDir: File, written: List<Pair<String, File>>) {
    val sizes = written.map { it.second.length() }
    val total = sizes.sum()
    val avg = if (written.isNotEmpty()) total / written.size else 0L
    println(
      "bundle pack --per-preview — wrote ${written.size} bundle(s) to ${outDir.path}\n" +
        "  total:   $total bytes\n" +
        "  size:    min ${sizes.minOrNull() ?: 0} / avg $avg / max ${sizes.maxOrNull() ?: 0} bytes"
    )
    val over =
      written.filter { it.second.length() > 100 * 1024 }.sortedByDescending { it.second.length() }
    if (over.isNotEmpty()) {
      val worst = over.first()
      System.err.println(
        "bundle pack --per-preview: ${over.size} bundle(s) exceed 100 KB " +
          "(largest: ${worst.first} = ${worst.second.length()} bytes) — a single preview should " +
          "normally be well under that; check for --embed-deps or a large inlined project jar."
      )
    }
  }

  /**
   * Carry the per-preview semantics blob inside [bundleFile] (issue #1843). Drives a short-lived
   * daemon ([DaemonSemanticsFetcher]) to render the bundle's selected previews and read back each
   * one's `compose/semantics` tree (with resolved foreground/background colours), then injects them
   * as `previews/<id>.semantics.json` — the location + shape the design-parity static bundle reader
   * expects.
   *
   * Best-effort: any failure (missing descriptor, daemon open/render error, an unsupported backend)
   * warns to stderr and leaves the already-written bundle untouched rather than failing the pack —
   * the cover PNG and every other entry are preserved and the polyglot stays valid. Returns the
   * stdout summary line to print after the main pack summary, or null when nothing was carried.
   */
  private fun packSemanticsBlob(
    target: PreviewModule,
    bundleFile: File,
    meta: BundleReader.Metadata,
    renderTimeout: Duration,
  ): String? {
    val previewIds = meta.manifest.previewIds
    if (previewIds.isEmpty()) return null
    // The manifest's previewIds carry the sanitised in-bundle form; the daemon keys renders on the
    // RAW discovery id. Fetch by raw (rawPreviewIds, parallel to previewIds — falling back to the
    // bundle form for pre-field bundles, correct when nothing needed sanitising) and re-key each
    // returned map to the bundle form before injecting, so `previews/<id>.semantics.json` matches
    // the entry names readers reconstruct.
    val rawIds =
      if (meta.manifest.rawPreviewIds.size == previewIds.size) meta.manifest.rawPreviewIds
      else previewIds
    val bundleIdByRaw = rawIds.zip(previewIds).toMap()
    fun <V> Map<String, V>.keyedByBundleId(): Map<String, V> = entries.associate { (raw, v) ->
      (bundleIdByRaw[raw] ?: raw) to v
    }
    // The other half of the deferral saving (issue #2966): this capture is a daemon render per
    // preview, so filtering only `composePreviewRender` would leave the axis cost here untouched.
    // Excluded previews stay listed in the bundle (they must, to stay addressable on the serve host
    // — see #2965) and simply carry no semantics/layout/figma-svg sidecar, exactly as they carry no
    // PNG.
    val captureIds = PackPreviewIdExclusions.retain(rawIds, excludePreviewIds)
    val excludedFromCapture = rawIds.size - captureIds.size
    if (excludedFromCapture > 0) {
      System.err.println(
        "bundle pack: --with-semantics skipping $excludedFromCapture excluded preview(s) " +
          "(--exclude-preview-id); ${captureIds.size} to capture."
      )
    }
    if (captureIds.isEmpty()) {
      System.err.println(
        "bundle pack: --exclude-preview-id excluded every preview from the semantics capture; " +
          "bundle written without previews/<id>$BUNDLE_SEMANTICS_SUFFIX."
      )
      return null
    }
    val captureCount = captureIds.size
    val fetcher =
      DaemonSemanticsFetcher(
        onLog = { System.err.println("[daemon semantics] $it") },
        renderTimeout = renderTimeout,
      )
    val outcome =
      fetcher.fetch(
        projectDir = target.projectDir,
        moduleName = target.gradlePath,
        previewIds = captureIds,
      )
    when (outcome) {
      is DaemonSemanticsFetcher.Outcome.Ok -> {
        if (outcome.semanticsById.isEmpty()) {
          System.err.println(
            "bundle pack: --with-semantics produced no semantics for ${target.gradlePath} " +
              "(see daemon log above); bundle written without previews/<id>.semantics.json."
          )
          return null
        }
        val canonical =
          InspectionSidecarCanonicalizer.canonicalize(
            semanticsById = outcome.semanticsById.keyedByBundleId(),
            layoutById = outcome.layoutById.keyedByBundleId(),
          )
        val written = injectSemanticsIntoBundle(bundleFile, canonical.semanticsById)
        val missing = captureCount - written
        // The layout-inspector tree rides alongside the semantics blob (best-effort): a preview
        // that produced a tree gets `previews/<id>.layout.json` so a consumer can build slot-level
        // redlines/wireframes. Injected after semantics so its byte count is reflected too.
        val layoutWritten = injectLayoutIntoBundle(bundleFile, canonical.layoutById)
        // The fonts/used record rides the same render (best-effort, Android daemon only): carried
        // so the design-catalog export can generate the Wasm tier's fonts.json from actual usage.
        val fontsWritten = injectFontsIntoBundle(bundleFile, outcome.fontsById.keyedByBundleId())
        // The layered `compose/figma-svg` export rides the same render (best-effort): carried so
        // the
        // design-catalog export can ship an editable vector per sticker beside the raster PNG.
        val figmaSvgWritten =
          injectFigmaSvgIntoBundle(bundleFile, outcome.figmaSvgById.keyedByBundleId())
        // A hybrid figma-svg references `figma-raster/<node>.png` crops; carry them verbatim as
        // `previews/<id>.figma-raster/<node>.png` so the SVG's `<image>` layers resolve once the
        // export copies the SVG onto the delivery branch. Empty for the common vector-only case.
        val figmaRasterWritten =
          injectFigmaRasterIntoBundle(bundleFile, outcome.figmaRasterById.keyedByBundleId())
        val semanticsLine =
          "  semantics:     $written / $captureCount preview(s) carried as " +
            "previews/<id>$BUNDLE_SEMANTICS_SUFFIX" +
            if (missing > 0) " ($missing without a captured tree)" else ""
        val extraLines = buildString {
          if (layoutWritten > 0)
            append(
              "\n  layout:        $layoutWritten / $captureCount preview(s) carried " +
                "as previews/<id>$BUNDLE_LAYOUT_SUFFIX"
            )
          if (fontsWritten > 0)
            append(
              "\n  fonts:         $fontsWritten / $captureCount preview(s) carried " +
                "as previews/<id>$BUNDLE_FONTS_SUFFIX"
            )
          if (figmaSvgWritten > 0)
            append(
              "\n  figma-svg:     $figmaSvgWritten / $captureCount preview(s) carried " +
                "as previews/<id>$BUNDLE_FIGMA_SVG_SUFFIX"
            )
          if (figmaRasterWritten > 0)
            append(
              "\n  figma-raster:  $figmaRasterWritten crop(s) carried as " +
                "previews/<id>$BUNDLE_FIGMA_RASTER_DIR_SUFFIX/<node>.png"
            )
        }
        return semanticsLine + extraLines
      }
      is DaemonSemanticsFetcher.Outcome.DescriptorMissing ->
        System.err.println(
          "bundle pack: --with-semantics could not find daemon-launch.json at " +
            "${outcome.expected.path} — bundle written without semantics."
        )
      is DaemonSemanticsFetcher.Outcome.OpenFailed ->
        System.err.println(
          "bundle pack: --with-semantics could not open a render session (${outcome.reason}) — " +
            "bundle written without semantics."
        )
    }
    return null
  }

  private fun printPackSummary(file: File, meta: BundleReader.Metadata) {
    println("wrote ${file.path} (${file.length()} bytes)")
    println(
      "  schema:        v${meta.manifest.schemaVersion}, backend=${meta.manifest.backend}, " +
        "producer=${meta.manifest.producer}, resolution=${meta.manifest.resolution}"
    )
    println(
      "  previews:      ${meta.manifest.previewIds.size} (cover=${meta.manifest.coverPreviewId})"
    )
    val mavenCount = meta.manifest.classpath.count { it is BundleReader.ClasspathEntry.Maven }
    val projectCount = meta.manifest.classpath.count { it is BundleReader.ClasspathEntry.Project }
    val embeddedCount = meta.manifest.classpath.count { it is BundleReader.ClasspathEntry.Embedded }
    println(
      "  classpath:     ${meta.manifest.classpath.size} entries " +
        "(Maven=$mavenCount, embedded=$embeddedCount, inlined=$projectCount)"
    )
    if (meta.manifest.dataExtensions.isNotEmpty()) {
      println(
        "  data exts:     ${meta.manifest.dataExtensions.size} " +
          "(${meta.manifest.dataExtensions.joinToString(", ") { it.extensionId }})"
      )
    }
    val r = meta.report
    if (r != null) {
      println("  entry classes: ${r.entryClassFqns.size}")
      println(
        "  reachable:     ${r.reachableClassCount} / ${r.totalScannedClassCount} classes scanned"
      )
      println(
        "  module:        ${r.moduleClasses.reachableClasses} / ${r.moduleClasses.totalClasses} classes kept, ${r.moduleClasses.packedBytes} B packed"
      )
      val kept = r.dependencies.count { it.kept }
      println("  deps:          $kept / ${r.dependencies.size} contributed reachable classes")
    }
  }
}

private class InspectSubcommand(private val args: List<String>) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    if (path == null) {
      System.err.println("Usage: compose-preview bundle inspect <bundle.png | URL>")
      exitProcess(64)
    }
    val file =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val meta = BundleReader.readMetadata(file)
    val pretty = Json {
      prettyPrint = true
      classDiscriminator = "kind"
    }
    println("file: ${file.absolutePath}")
    println("size: ${file.length()} bytes")
    println("--- bundle.json ---")
    println(pretty.encodeToString(BundleReader.Manifest.serializer(), meta.manifest))
    // Bound to a local: `report` is a public property of a class in another module now
    // (`:bundle-format`), so the compiler can't smart-cast the null check across the boundary.
    val report = meta.report
    if (report != null) {
      println("--- report.json ---")
      println(pretty.encodeToString(BundleReader.Report.serializer(), report))
    }
  }
}

private class ExtractSubcommand(private val args: List<String>) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val outDir = args.flagValue("--output") ?: args.flagValue("-o")
    if (path == null) {
      System.err.println("Usage: compose-preview bundle extract <bundle.png | URL> [-o <dir>]")
      exitProcess(64)
    }
    val file =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val target =
      File(
          outDir
            ?: (file.absoluteFile.parent.toString() + "/${file.nameWithoutExtension}-extracted")
        )
        .absoluteFile
    target.mkdirs()
    val zipBytes = BundleReader.extractZipBytes(file)
    expandZipBytesSafely(zipBytes, target)
    println("extracted ${file.name} → ${target.path}")
  }
}

private class EmbedSubcommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val outArg = args.flagValue("--output") ?: args.flagValue("-o")
    val title = args.flagValue("--title")
    val inBundle = "--in-bundle" in args
    val mode =
      if ("--external-images" in args) WebEmbed.InlineMode.EXTERNAL else WebEmbed.InlineMode.INLINE
    if (path == null) {
      System.err.println(
        "Usage: compose-preview bundle embed <bundle.png | URL> [-o <dir|file.png>] [--title T] " +
          "[--external-images] [--in-bundle]"
      )
      exitProcess(64)
    }
    val file =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }

    val data = readBundleWebEmbedData(file)
    if (data.previews.isEmpty()) {
      System.err.println(
        "bundle embed: ${file.name} has no baked preview images — nothing to put on a page. " +
          "Pack with a render (drop --no-render) so previews/<id>.png exist."
      )
      exitProcess(1)
    }

    val out =
      WebEmbed.generate(
        title = title ?: data.manifest.modulePath,
        modulePath = data.manifest.modulePath,
        previews = data.previews,
        mode = mode,
      )

    if (inBundle) embedInBundle(file, out, outArg, BundleSource.looksLikeUrl(path))
    else writeToDirectory(file, out, outArg)
  }

  /** Default mode: write the web embed as loose files under a directory. */
  private fun writeToDirectory(file: File, out: WebEmbed.Output, outArg: String?) {
    val target =
      File(outArg ?: (file.absoluteFile.parent.toString() + "/${file.nameWithoutExtension}-web"))
        .absoluteFile
    target.mkdirs()
    val targetPath = target.canonicalFile.toPath()
    for ((rel, bytes) in out.files) {
      val resolved = targetPath.resolve(rel).normalize()
      // Generated paths are all controlled (script / index / previews/<id>.png), but resolve+verify
      // anyway so a stray id can never write outside the output dir.
      if (!resolved.startsWith(targetPath)) {
        System.err.println("bundle embed: refusing to write outside $target: $rel")
        exitProcess(1)
      }
      val dest = resolved.toFile()
      dest.parentFile?.mkdirs()
      fileSystem.write(dest.path.toPath()) { write(bytes) }
    }

    println("wrote web embed for ${out.previewCount} preview(s) → ${target.path}")
    println("  ${WebEmbed.INDEX_NAME}   open this to view the gallery")
    println(
      "  ${WebEmbed.SCRIPT_NAME}  add <script src> + <compose-preview-gallery> to embed in a page"
    )
  }

  /**
   * `--in-bundle` mode: append the web embed's files into the bundle's own zip under
   * [BUNDLE_WEB_DIR] (`web/`), leaving the leading PNG cover and every existing entry untouched.
   * The directory is additive — an older reader / the renderer ignores it — so the same `.png` is
   * still a valid polyglot *and* now carries a `web/index.html` someone can open straight out of
   * the unzipped bundle. Re-embedding replaces any prior `web/` entries (idempotent). Writes in
   * place by default; `-o <file.png>` writes an enriched copy instead.
   *
   * A URL input resolves to a delete-on-exit temp file, so rewriting it "in place" would vanish on
   * exit — `-o` is required for downloaded bundles ([resolveInBundleTarget] returns null and we
   * error rather than silently lose the result).
   */
  private fun embedInBundle(
    file: File,
    out: WebEmbed.Output,
    outArg: String?,
    sourceIsUrl: Boolean,
  ) {
    val targetArg = resolveInBundleTarget(outArg, file.absolutePath, sourceIsUrl)
    if (targetArg == null) {
      System.err.println(
        "bundle embed --in-bundle: the input is a downloaded URL (a temporary file). " +
          "Pass -o <file.png> so the enriched bundle is written somewhere durable."
      )
      exitProcess(64)
    }
    val full = fileSystem.read(file.path.toPath()) { readByteArray() }
    val zip = BundleReader.extractZipBytes(file)
    // The appended zip is a suffix of the file; everything before it is the leading PNG cover.
    val prefix = full.copyOfRange(0, full.size - zip.size)
    val webFiles = out.files.mapKeys { (rel, _) -> "$BUNDLE_WEB_DIR/$rel" }
    val newZip = embedWebIntoZip(zip, webFiles)

    val target = File(targetArg).absoluteFile
    target.parentFile?.mkdirs()
    // Write via a temp sibling + move so an in-place enrich never truncates the bundle on failure.
    val tmp = File(target.parentFile, "${target.name}.embed-tmp")
    fileSystem.write(tmp.path.toPath()) {
      write(prefix)
      write(newZip)
    }
    fileSystem.atomicMove(tmp.path.toPath(), target.path.toPath())

    println(
      "embedded web gallery (${out.previewCount} preview(s)) into ${target.path} " +
        "under $BUNDLE_WEB_DIR/ (${target.length()} bytes)"
    )
    println("  unzip it and open $BUNDLE_WEB_DIR/${WebEmbed.INDEX_NAME}")
  }
}

private class RenderSubcommand(private val args: List<String>) {
  private val verbose: Boolean = "--verbose" in args || "-v" in args

  fun run() {
    // Positional bundle path, skipping any valued flag (`--knob k=v`, `--output dir`) value.
    val path = CliFlags.firstPositional(args)
    val outDir = args.flagValue("--output") ?: args.flagValue("-o")
    // Repeatable `--knob key=value` theme overrides (e.g. `theme.colors=scheme:…`); see
    // [parseKnobOverrides] for the split rules.
    val knobs: Map<String, PreviewOverrideValue> = parseKnobOverrides(args)
    // `--res <dir>` supplies a PUBLISHED bundle's externalized resource pool (the content-addressed
    // `bundle/res/<sha>` dir published beside the bundle on its design-artifacts branch), needed to
    // daemon-render a bundle whose fonts were lifted out by `bundle externalize`.
    val resPool = args.flagValue("--res")?.let { File(it) }
    // `--svg` also exports each re-themed preview's editable vector (`compose/figma-svg`) beside
    // its
    // PNG, so `bundle repack` can swap the baked `previews/<id>.figma.svg` too — the catalog ships
    // both raster and vector per sticker. Only the daemon/`--knob` path can produce it.
    val withSvg = "--svg" in args
    if (path == null) {
      System.err.println(
        "Usage: compose-preview bundle render <bundle.png | URL> [-o <dir>] [--knob key=value …] " +
          "[--res <pool-dir>] [--svg]"
      )
      exitProcess(64)
    }
    val file =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val target =
      File(outDir ?: (file.absoluteFile.parent.toString() + "/${file.nameWithoutExtension}-render"))
        .absoluteFile
    target.mkdirs()

    // Theme overrides can't ride the source subprocess renderer (BundleRenderer →
    // DesktopRendererMain, positional args). Render via the DAEMON path instead — the same one
    // `serve` uses for `/render?knob…` — which applies `PreviewOverrides.namedOverrides` to the
    // PUBLISHED bundle with no source rebuild. See [renderBundleWithOverrides].
    if (knobs.isNotEmpty()) {
      if (!renderBundleWithOverrides(file, target, knobs, resPool, withSvg, verbose)) exitProcess(1)
      return
    }

    if (withSvg) {
      // --svg re-exports the vector from a themed daemon render; without --knob there's nothing to
      // re-theme (the bundle already ships its baked figma.svg), so the stock render can't honour
      // it.
      System.err.println(
        "bundle render: --svg exports re-themed vectors and only applies with --knob; the stock " +
          "render already carries the bundle's baked figma.svg. Ignoring --svg."
      )
    }

    val renderer =
      BundleRenderer(bundleFile = file, outputDir = target, verbose = verbose, resPoolDir = resPool)
    val result =
      try {
        renderer.run()
      } catch (e: Exception) {
        System.err.println("bundle render failed: ${e.message}")
        if (verbose) e.printStackTrace()
        exitProcess(1)
      }

    println(
      "rendered ${result.succeeded.size} / ${result.previewCount} preview(s) → ${target.path}"
    )
    for (rendered in result.succeeded) {
      println("  ok    ${rendered.id}  →  ${rendered.outputFile.name}")
    }
    for (failure in result.failed) {
      println("  FAIL  ${failure.id}  (exit=${failure.exitCode})")
      if (verbose) {
        for (line in failure.tail.lines()) println("        $line")
      }
    }
    if (!result.allOk) exitProcess(1)
  }
}

/**
 * `bundle repack <bundle> --renders <dir> -o <out.png>` — write a copy of [bundleFile] with its
 * baked per-preview artifacts swapped for the re-renders in `--renders` (the output of `bundle
 * render --knob … [--svg]`): each `<id>.png` replaces `previews/<id>.png`, and each `<id>.svg`
 * replaces the editable vector `previews/<id>.figma.svg`. Pure zip surgery: every other entry
 * (previews.json, bundle.json, classes/, libs/, JSON sidecars) and the leading PNG cover are
 * preserved verbatim, so the result is a drop-in re-themed bundle the catalog exporter
 * (`generate-design-catalog.mjs --renders`) consumes exactly like the original. A render whose
 * filename matches no baked slot is skipped (reported), so a partial re-render repacks what it
 * produced.
 */
private class RepackSubcommand(private val args: List<String>) {
  private val verbose: Boolean = "--verbose" in args || "-v" in args

  fun run() {
    val path = CliFlags.firstPositional(args)
    val rendersDir = args.flagValue("--renders")?.let { File(it) }
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    if (path == null || rendersDir == null || out == null) {
      System.err.println(
        "Usage: compose-preview bundle repack <bundle.png | URL> --renders <dir> -o <out.png>"
      )
      exitProcess(64)
    }
    if (!rendersDir.isDirectory) {
      System.err.println("bundle repack: --renders '${rendersDir.path}' is not a directory")
      exitProcess(1)
    }
    val source =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val outFile = File(out).absoluteFile
    val outcome =
      try {
        repackRethemedPreviews(source, rendersDir, outFile)
      } catch (e: IllegalStateException) {
        System.err.println("bundle repack: ${e.message}")
        exitProcess(1)
      }
    val svgNote = if (outcome.svg > 0) " (${outcome.png} png, ${outcome.svg} svg)" else ""
    println("repacked ${outcome.repacked} re-themed artifact(s)$svgNote → ${outFile.path}")
    if (outcome.unmatched.isNotEmpty()) {
      System.err.println(
        "  ${outcome.unmatched.size} render(s) had no matching baked preview — skipped"
      )
      if (verbose) outcome.unmatched.forEach { System.err.println("    skip $it") }
    }
  }
}

/**
 * Result of [repackRethemedPreviews]: how many baked raster [png] and vector [svg] slots were
 * swapped, and the render filenames that matched no baked preview slot. [repacked] is the total.
 */
internal data class RepackOutcome(val png: Int, val svg: Int, val unmatched: List<String>) {
  val repacked: Int
    get() = png + svg
}

/**
 * Core of `bundle repack`: write [outFile] as a copy of [source] with its baked per-preview
 * artifacts replaced by the re-renders in [rendersDir] — a `<id>.png` swaps `previews/<id>.png`,
 * and a `<id>.svg` (from `bundle render --knob --svg`) swaps the editable vector
 * `previews/<id>.figma.svg`. Only top-level preview artifacts are swappable slots: nested
 * figma-raster crops (`previews/<id>.figma-raster/…`) and the JSON sidecars (semantics / layout /
 * overrides) are NOT re-themed here, so they stay verbatim, as does the leading PNG cover and every
 * other zip entry. The result is a drop-in re-themed bundle. Throws [IllegalStateException] if no
 * render matched a baked slot.
 */
internal fun repackRethemedPreviews(source: File, rendersDir: File, outFile: File): RepackOutcome {
  // Top-level baked artifacts we can swap: the raster `previews/<id>.png` and its vector sibling
  // `previews/<id>.figma.svg`. A path with a further `/` after the previews/ prefix is a nested
  // figma-raster crop, not a swap target.
  val baked =
    zipEntryNames(BundleReader.extractZipBytes(source))
      .filter {
        it.startsWith("$BUNDLE_PREVIEWS_DIR/") &&
          '/' !in it.removePrefix("$BUNDLE_PREVIEWS_DIR/") &&
          (it.endsWith(".png") || it.endsWith(BUNDLE_FIGMA_SVG_SUFFIX))
      }
      .toSet()
  val renders =
    (rendersDir.listFiles { f -> f.isFile && (f.name.endsWith(".png") || f.name.endsWith(".svg")) }
        ?: emptyArray())
      .sortedBy { it.name }
  val entries = LinkedHashMap<String, ByteArray>()
  val unmatched = mutableListOf<String>()
  var png = 0
  var svg = 0
  for (f in renders) {
    // A `<id>.svg` render re-skins the baked `previews/<id>.figma.svg`; a `<id>.png`, the raster.
    val isSvg = f.name.endsWith(".svg")
    val target =
      if (isSvg) "$BUNDLE_PREVIEWS_DIR/${f.name.removeSuffix(".svg")}$BUNDLE_FIGMA_SVG_SUFFIX"
      else "$BUNDLE_PREVIEWS_DIR/${f.name}"
    if (target in baked) {
      entries[target] = f.readBytes()
      if (isSvg) svg++ else png++
    } else {
      unmatched += f.name
    }
  }
  check(entries.isNotEmpty()) {
    "none of the ${renders.size} render(s) in ${rendersDir.path} matched a baked preview " +
      "(previews/<id>.png or previews/<id>$BUNDLE_FIGMA_SVG_SUFFIX) in ${source.name} — " +
      "nothing to repack"
  }
  outFile.parentFile?.mkdirs()
  // Copy the whole polyglot (leading PNG cover + every zip entry) then swap the baked previews in
  // place; the cover thumbnail stays the source's, the re-themed pixels live in the zip.
  source.copyTo(outFile, overwrite = true)
  injectRawZipEntries(outFile, entries)
  return RepackOutcome(png, svg, unmatched)
}

/**
 * `bundle merge <base.png> <shard.png>… -o <out.png>` — union the per-preview artifacts of several
 * bundles that were packed from the SAME module and commit but with disjoint render selections,
 * into one bundle carrying every shard's pixels.
 *
 * This is the merge step of a sharded CI render: N jobs each run `bundle pack --exclude-preview-id
 * <everything-not-mine>`, so each emits a structurally identical bundle whose `previews.json` lists
 * every preview and whose `previews/` directory holds only its own partition. Merging them yields
 * exactly the bundle one serial render would have produced.
 *
 * Deliberately NOT `bundle repack`: repack swaps re-renders into slots the target already has and
 * only handles `previews/<id>.png` + `previews/<id>.figma.svg`, so a shard's previews would every
 * one of them be "unmatched" (the base has no slot) and its `.semantics.json` sidecar would be
 * dropped — which the design-catalog completeness gate fails on.
 */
private class MergeSubcommand(private val args: List<String>) {
  private val verbose: Boolean = "--verbose" in args || "-v" in args

  fun run() {
    val inputs = CliFlags.positionals(args)
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    if (inputs.size < 2 || out == null) {
      System.err.println(
        "Usage: compose-preview bundle merge <base.png | URL> <shard.png | URL>… -o <out.png>"
      )
      exitProcess(64)
    }
    val files =
      try {
        inputs.map { BundleSource.resolveToFile(it) }
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val outFile = File(out).absoluteFile
    val outcome =
      try {
        mergeShardBundles(files.first(), files.drop(1), outFile)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle merge: ${e.message}")
        exitProcess(1)
      }
    println(
      "merged ${outcome.previews} preview(s) from ${files.size - 1} shard(s) " +
        "(${outcome.entries} entries) → ${outFile.path}"
    )
    if (outcome.overlapping.isNotEmpty()) {
      // Disjoint partitions are the contract; an overlap means the partition and the exclusion list
      // disagree, which costs render time twice and silently picks a winner. Say so.
      System.err.println(
        "  ${outcome.overlapping.size} preview(s) were baked by more than one shard — " +
          "the base's copy wins; the partition is not disjoint"
      )
      if (verbose) outcome.overlapping.forEach { System.err.println("    overlap $it") }
    }
  }
}

/**
 * Result of [mergeShardBundles]: how many preview ids gained a baked raster from a shard
 * ([previews]), how many zip [entries] were copied in total (rasters plus every sidecar), and the
 * ids more than one bundle had baked ([overlapping] — the base's copy wins).
 */
internal data class MergeOutcome(val previews: Int, val entries: Int, val overlapping: List<String>)

/**
 * Zip-entry prefixes that hold PER-PREVIEW render output, and are therefore what a shard
 * contributes. Everything else — `bundle.json`, `previews.json`, `classes/app.jar`, `libs/`,
 * `android/`, the leading PNG cover — is identical across shards by construction (same module, same
 * commit, same classpath; only the render selection differs) and is taken from the base verbatim.
 * That is also the answer to "does the live classpath survive the merge": it is never merged, it is
 * inherited, so `publish-live-bundle` needs no designated shard.
 */
private val MERGEABLE_SHARD_PREFIXES = listOf("$BUNDLE_PREVIEWS_DIR/", "ir/", "extensions/")

/**
 * Core of `bundle merge`: write [outFile] as a copy of [base] with every per-preview artifact the
 * [shards] carry and [base] lacks added to its zip — the baked `previews/<id>.png`, its
 * `.semantics.json` / `.layout.json` / `.fonts.json` / `.figma.svg` / `.catalog.json` /
 * `.overrides.json` sidecars, the nested `previews/<id>.figma-raster/…` crops, the `ir/<id>.rc`
 * documents and the `extensions/<id>.json` data reports.
 *
 * Base-wins on collision, and earlier shards win over later ones, so the result is deterministic in
 * the order the shards are passed. Throws [IllegalArgumentException] if any input is not a bundle.
 *
 * Streams each shard's zip and retains only the entries it contributes, so the shared re-render
 * payload (`classes/app.jar` + `libs/`, hundreds of MB and identical in every shard) is read past
 * rather than held: peak memory is the base bundle plus the merged preview artifacts, not the sum
 * of the shards.
 */
internal fun mergeShardBundles(base: File, shards: List<File>, outFile: File): MergeOutcome {
  val baseNames = zipEntryNames(BundleReader.extractZipBytes(base)).toSet()
  val add = LinkedHashMap<String, ByteArray>()
  val overlapping = LinkedHashSet<String>()
  for (shard in shards) {
    ZipInputStream(ByteArrayInputStream(BundleReader.extractZipBytes(shard))).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        if (entry.isDirectory || MERGEABLE_SHARD_PREFIXES.none { name.startsWith(it) }) {
          zin.closeEntry()
          continue
        }
        if (name in baseNames || name in add) {
          bakedPreviewId(name)?.let(overlapping::add)
        } else {
          add[name] = zin.readBytes()
        }
        zin.closeEntry()
      }
    }
  }
  outFile.parentFile?.mkdirs()
  // The base IS the merged bundle plus the other shards' pixels: same manifests, same classpath,
  // same cover. Copy it whole, then inject what the shards rendered.
  base.copyTo(outFile, overwrite = true)
  injectRawZipEntries(outFile, add)
  return MergeOutcome(
    previews = add.keys.count { bakedPreviewId(it) != null },
    entries = add.size,
    overlapping = overlapping.toList(),
  )
}

/**
 * The preview id [name] is the top-level baked raster of (`previews/<id>.png`), or null for any
 * other entry — a sidecar, a nested `figma-raster` crop, an `ir/` document. Used to count and to
 * report overlap in whole previews rather than in zip entries, which is what a partition is
 * expressed in.
 */
private fun bakedPreviewId(name: String): String? {
  if (!name.startsWith("$BUNDLE_PREVIEWS_DIR/") || !name.endsWith(".png")) return null
  val rest = name.removePrefix("$BUNDLE_PREVIEWS_DIR/")
  if ('/' in rest) return null
  return rest.removeSuffix(".png")
}

/** The file (non-directory) entry names in [zip], in iteration order. */
internal fun zipEntryNames(zip: ByteArray): List<String> = buildList {
  ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
    while (true) {
      val e = zin.nextEntry ?: break
      if (!e.isDirectory) add(e.name)
      zin.closeEntry()
    }
  }
}

/**
 * Parse repeatable `--knob key=value` flags into theme overrides. Each entry is split on its FIRST
 * `=` so a serialized value keeps its own `=`/`,`/`;` (e.g. `theme.colors=scheme:l=primary:…`).
 * Entries with no `=`, or a blank key, are dropped. Theme knobs are all string-valued, so every
 * value becomes a [PreviewOverrideValue.StringValue]. A repeated key takes its last value.
 */
internal fun parseKnobOverrides(args: List<String>): Map<String, PreviewOverrideValue> =
  args
    .flagValuesAll("--knob")
    .mapNotNull { entry ->
      val i = entry.indexOf('=')
      if (i <= 0) return@mapNotNull null
      val key = entry.substring(0, i).trim()
      if (key.isEmpty()) null else key to PreviewOverrideValue.StringValue(entry.substring(i + 1))
    }
    .toMap()

/**
 * Render every preview of [bundleFile] to a PNG in [outDir] under theme [overrides] — the daemon
 * path `serve` uses for `/render?knob…`, wired to write files. Reuses
 * [ServeBundleDaemon.materialize]
 * + [ServeRenderHost], so a PUBLISHED bundle re-skins with NO source rebuild: the override rides
 *   `PreviewOverrides.namedOverrides` → the daemon's connector extension →
 *   `PreviewOverrideController`. A local `--bundle` path is rendered as-is (same trust posture as
 *   `bundle daemon`); the daemon just runs the bundle the operator handed it. Returns true iff
 *   every preview rendered.
 */
private fun renderBundleWithOverrides(
  bundleFile: File,
  outDir: File,
  overrides: Map<String, PreviewOverrideValue>,
  resPoolDir: File?,
  withSvg: Boolean,
  verbose: Boolean,
): Boolean {
  val log: (String) -> Unit = { if (verbose) System.err.println("[bundle render] $it") }
  if (BundleReader.readMetadata(bundleFile).manifest.backend == "desktop") {
    try {
      SkikoNativeProvision.prepareInstalledDesktopSidecars()
    } catch (e: IllegalStateException) {
      System.err.println("bundle render: ${e.message}")
      return false
    }
  }
  // Materialize the daemon workspace in a private temp dir — NOT under outDir. `materialize`
  // extracts the bundle's classes/libs/manifests here, which are implementation artifacts; the
  // command's contract is that outDir holds only the rendered PNGs, so a `.daemon` tree beside them
  // would leak bytecode/resources into whatever the caller publishes. Torn down once we're done.
  val workspace = Files.createTempDirectory("bundle-render-daemon").toFile()
  try {
    // A PUBLISHED bundle externalizes its fonts to a content-addressed pool; rehydrate them (from
    // --res) onto the daemon classpath at their original resource paths, or the render fails
    // "catalog font resource missing". Fail-closed — a font missing would silently corrupt output.
    val extResourceDir =
      try {
        resolveExternalResources(bundleFile, resPoolDir, File(workspace, "extres"))
      } catch (e: Exception) {
        System.err.println("bundle render: ${e.message}")
        return false
      }
    val state =
      ServeBundleDaemon.materialize(
        bundleFile,
        workspace,
        system = "bundle",
        extraClasspathDirs = listOfNotNull(extResourceDir),
        onLog = log,
      )
    if (state == null) {
      System.err.println(
        "bundle render: can't stand up a render daemon for this bundle — --knob theme overrides need " +
          "a 'desktop'/'android' backend bundle and the matching daemon sidecars (build them with " +
          ":cli:installDist). Re-run without --knob for the stock render."
      )
      return false
    }
    val host =
      try {
        ServeRenderHost.open(
          descriptorPath = state.descriptor,
          workspaceRoot = state.workspaceRoot,
          workspaceName = state.workspaceName,
          previews = state.previews,
          label = state.label,
          declaredThemes = state.declaredThemes,
          onLog = log,
        )
      } catch (e: Exception) {
        System.err.println("bundle render: failed to launch the render daemon (${e.message})")
        if (verbose) e.printStackTrace()
        return false
      }
    val failures =
      try {
        renderPreviewsToDir(
          host,
          outDir,
          PreviewOverrides(namedOverrides = overrides),
          withSvg = withSvg,
        )
      } finally {
        host.close()
      }
    for (f in failures) System.err.println("  FAIL  $f")
    return failures.isEmpty()
  } finally {
    // host.close() (inner finally) has already stopped the daemon subprocess before we delete.
    workspace.deleteRecursively()
  }
}

/**
 * Rehydrate [bundleFile]'s externalized resources (fonts lifted out by `bundle externalize`,
 * recorded in the manifest's `externalResources` as path+sha256+size) from the local
 * content-addressed [pool] (`bundle/res/<sha>`, published beside the bundle on its design-artifacts
 * branch) into [destDir], each materialized at its recorded classpath path so a daemon render
 * resolves `/fonts/…` exactly as it did with the resource inline. Returns [destDir] when the bundle
 * externalized anything, or `null` when it is self-contained (no `--res` needed). Throws
 * (fail-closed) if the bundle externalized resources but no [pool] was given, or a declared
 * resource is missing / fails its sha256+size check.
 */
private fun resolveExternalResources(bundleFile: File, pool: File?, destDir: File): File? {
  val resources = runCatching {
    BundleReader.readMetadata(bundleFile).manifest.externalResources
  }
    .getOrDefault(emptyList())
  return materializeExternalResources(resources, pool, destDir)
}

/**
 * Core of [resolveExternalResources], split out so the rehydration is unit-testable without a real
 * bundle: given the manifest's [resources], copy each from the [pool] (keyed by sha256) to its
 * recorded path under [destDir], verifying size + sha256 and rejecting path traversal. Empty
 * [resources] ⇒ `null` (self-contained). A non-empty list with a null/absent [pool], a missing pool
 * entry, or any integrity failure throws [IllegalStateException].
 */
internal fun materializeExternalResources(
  resources: List<BundleReader.ExternalResource>,
  pool: File?,
  destDir: File,
): File? {
  if (resources.isEmpty()) return null
  checkNotNull(pool) {
    "this bundle externalized ${resources.size} resource(s) (e.g. fonts) — re-run with " +
      "--res <pool-dir> pointing at its content-addressed pool (published at bundle/res/ on the " +
      "design-artifacts branch)"
  }
  check(pool.isDirectory) { "--res pool '${pool.path}' is not a directory" }
  destDir.mkdirs()
  val destRoot = destDir.canonicalFile.toPath()
  for (res in resources) {
    val sha = res.sha256
    check(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) {
      "external resource sha256 '$sha' is malformed"
    }
    check(res.path.isNotBlank() && !res.path.startsWith("/") && ".." !in res.path.split("/")) {
      "external resource path '${res.path}' is invalid"
    }
    val src = File(pool, sha)
    check(src.isFile) {
      "external resource ${res.path} (sha $sha) is missing from the pool ${pool.path}"
    }
    val bytes = src.readBytes()
    check(bytes.size.toLong() == res.size) {
      "external resource ${res.path}: pool bytes ${bytes.size} != declared size ${res.size}"
    }
    check(resSha256Hex(bytes) == sha) { "external resource ${res.path}: sha256 mismatch" }
    val dest = File(destDir, res.path)
    check(dest.canonicalFile.toPath().startsWith(destRoot)) {
      "external resource path '${res.path}' escapes the output dir"
    }
    dest.parentFile?.mkdirs()
    dest.writeBytes(bytes)
  }
  return destDir
}

private fun resSha256Hex(bytes: ByteArray): String =
  java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
    "%02x".format(it)
  }

/**
 * Render every preview [host] exposes to `<outDir>/<sanitized id>.png` under [seed], returning the
 * list of human-readable failure descriptions (empty iff every preview rendered). The theme [seed]
 * — `PreviewOverrides.namedOverrides` — is applied identically to every preview. Factored out of
 * [renderBundleWithOverrides] so the render-and-write loop is unit-testable against a
 * [ServeRenderHost] built over a fake [ee.schimke.composeai.render.session.RenderSession] — no
 * daemon subprocess, no native renderer. Does not close [host]; the caller owns its lifecycle.
 *
 * When [withSvg] is set and the host can export vectors ([ServeRenderHost.hasSvgExport]), each
 * successfully-rendered preview also writes its re-themed `compose/figma-svg` to
 * `<outDir>/<sanitized id>.svg` — the editable vector `bundle repack` swaps into the baked
 * `previews/<id>.figma.svg`. SVG is a **best-effort companion**: the PNG re-theme is the contract,
 * so a host with no figma-svg lane (a single note is logged) or a per-preview SVG failure (logged,
 * not fatal) leaves the PNG output intact and does NOT add to the returned failures.
 */
internal fun renderPreviewsToDir(
  host: ServeRenderHost,
  outDir: File,
  seed: PreviewOverrides,
  withSvg: Boolean = false,
  log: (String) -> Unit = ::println,
): List<String> {
  var rendered = 0
  var svgWritten = 0
  val failures = mutableListOf<String>()
  val exportSvg = withSvg && host.hasSvgExport
  if (withSvg && !host.hasSvgExport) {
    log("  note  --svg requested but this bundle has no figma-svg export — writing PNG only")
  }
  for (preview in host.previews) {
    when (val outcome = host.render(preview.id, seed)) {
      is RenderOutcome.Ok -> {
        val base = sanitizeBundleRenderName(preview.id)
        File(outDir, "$base.png").writeBytes(outcome.png)
        rendered++
        log("  ok    ${preview.id}")
        if (exportSvg) {
          when (val svg = host.renderSvg(preview.id, seed)) {
            is SvgOutcome.Ok -> {
              File(outDir, "$base.svg").writeBytes(svg.svg)
              svgWritten++
            }
            is SvgOutcome.Failed -> log("  svg?  ${preview.id} (${svg.reason})")
            SvgOutcome.NotFound -> log("  svg?  ${preview.id} (no vector export)")
          }
        }
      }
      is RenderOutcome.Failed -> failures += "${preview.id} (${outcome.reason})"
      RenderOutcome.NotFound -> failures += "${preview.id} (not found)"
      // This CLI renders previews sequentially on one thread, so the per-daemon lock is never
      // contended — Busy shouldn't occur — but surface it as a failure rather than skip silently.
      RenderOutcome.Busy -> failures += "${preview.id} (daemon busy)"
    }
  }
  log(
    "rendered $rendered / ${host.previews.size} preview(s) themed" +
      (if (exportSvg) " (+ $svgWritten svg)" else "") +
      " → ${outDir.path}"
  )
  return failures
}

/** Filesystem-safe filename for a preview id — any char outside `[A-Za-z0-9._-]` becomes `_`. */
internal fun sanitizeBundleRenderName(id: String): String =
  buildString(id.length) {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }

/**
 * Escape a preview id for the `-PbundlePreviewIds=` Gradle property: `,` and `\` are
 * backslash-escaped so an id carrying a `@Preview(name = "Phone, dark")` suffix survives the
 * comma-separated transport (an unescaped comma would otherwise split into two ids and the bundle
 * task would fail with "preview id not found"). Mirrors `BundlePreviewIds.encode` in
 * `:gradle-plugin` — the CLI can't depend on that module, same reason [BundleReader] mirrors the
 * on-disk schema. The plugin-side `BundlePreviewIds.parse` is the matching decoder.
 */
private fun encodePreviewId(id: String): String =
  buildString(id.length) {
    for (c in id) {
      if (c == '\\' || c == ',') append('\\')
      append(c)
    }
  }
