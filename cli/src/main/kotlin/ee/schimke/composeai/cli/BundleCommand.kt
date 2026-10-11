package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX
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
import ee.schimke.composeai.bundle.injectFigmaFontWarningsIntoBundle
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
 * `compose-preview bundle <pack|inspect|extract|render|embed|…>` — produce, inspect, and play
 * portable preview bundles.
 *
 * A bundle is a PNG + ZIP polyglot: a valid cover PNG followed by a standard zip (found via the
 * EOCD signature). Schema: `PreviewBundleFormat.kt` in the plugin module.
 *
 * - `pack` — render and run `composePreviewBundle` on a module; repeatable `--id`, the first is the
 *   cover. `--no-render` packs with a stub cover.
 * - `inspect` — print `bundle.json` + `report.json`, including the minimization report. Read-only.
 * - `extract` — unzip into a directory, rejecting path traversal.
 * - `render` — re-render the bundle's previews from the packed `.png`.
 * - `embed` — convert to a self-contained web embed (`compose-preview-embed.js` + `index.html`,
 *   previews inlined as `data:` URIs); `--in-bundle` writes it under `web/` in the bundle itself.
 *   See [WebEmbed].
 */
class BundleCommand(args: List<String>) : Command(args) {

  override fun run() {
    // Skip leading valued flags to find the subcommand; it keeps those flags in its args.
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
        compose-preview bundle pack [--module <name>] [--id <preview>...] [-o <file.png>] [--no-render] [--with-semantics] [--allow-lost-font-families]
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
                            A preview whose figma-svg export could not name a font family the
                            render drew also carries the export's warning as
                            previews/<id>.figma-fonts.warnings.json, and FAILS THE PACK — see
                            --allow-lost-font-families.
                            Produced by a short-lived daemon render (no separate --with-extension
                            pass needed). Off by default; ignored with --no-render.
        --allow-lost-font-families
                            Pack anyway when a figma-svg export lost a font family. Such a preview
                            exports its text as missing-glyph boxes, which is loud in the sticker
                            and silent in the build log, so the pack refuses by default rather
                            than let a sheet of boxes publish. Same posture as the render's
                            -Dcomposeai.fonts.failOnFallback gate.

      Split flags (sheet → one bundle per preview):
        -o, --output <dir>  Directory to write <id>.png bundles into. Default: <sheet>-split/.
        --view-only         Drop the re-render classpath (classes/app.jar + libs/) from each output,
                            keeping the baked image + every sidecar (semantics / layout / figma.svg /
                            figma-fonts.warnings / overrides / catalog / fonts). Produces small (~tens of KB) addressable
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

  /**
   * Let a pack whose figma-svg export lost a font family succeed. Off by default: missing-glyph
   * boxes are silent in logs and otherwise publish unnoticed (cf.
   * `-Dcomposeai.fonts.failOnFallback`).
   */
  private val allowLostFontFamilies: Boolean = "--allow-lost-font-families" in args
  private val perPreview: Boolean = "--per-preview" in args
  private val verbose: Boolean = "--verbose" in args || "-v" in args
  private val progress: Boolean = verbose || "--progress" in args
  private val timeout: String? = args.flagValue("--timeout")
  /** `--variant <name>`, forwarded to the inner [Command] so it reaches Gradle. */
  private val variant: String? = args.flagValue("--variant")?.trim()?.takeIf { it.isNotEmpty() }
  private val ids: List<String> = PackPreviewIdExclusions.selectedIds(args)

  /**
   * `--exclude-preview-id` patterns: previews this pack must not render or semantics-capture. Falls
   * back to `ORG_GRADLE_PROJECT_composePreview.idExclude`, since only the CLI can thin the
   * semantics pass.
   */
  private val excludePreviewIds: List<String> = PackPreviewIdExclusions.fromArgs(args)

  /**
   * `--exclude-preview-id-file`, kept as a file so the render gets the path: ids may contain
   * commas, and re-joining them would split ids into fragments that match far too much.
   */
  private val excludePreviewIdFile: java.io.File? = PackPreviewIdExclusions.fileFromArgs(args)

  /**
   * `--exclude-preview-row` labels: `@PreviewParameter` rows not to render. Render only; semantics
   * are captured per preview.
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
      // `bundle pack` forwards a whitelist to the inner Command, so `--variant` must be passed
      // explicitly; dropping it silently renders the wrong flavor.
      variant?.let {
        add("--variant")
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
              // Thin the render itself, with the same patterns the semantics pass skips. The file
              // form when available ([excludePreviewIdFile]); absolute because Gradle runs in the
              // module directory.
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
              // Same for the `@PreviewParameter` row axis, resolved inside the render JVM.
              if (excludePreviewRows.isNotEmpty())
                add(
                  "-P${PackPreviewIdExclusions.ROW_GRADLE_PROPERTY}=" +
                    excludePreviewRows.joinToString(",")
                )
              if (embedDeps) add("-PbundleEmbedDeps=true")
              if (includeDataExtensions) add("-PbundleIncludeDataExtensions=true")
              add("-PbundleOutput=${resolvedOutput.absolutePath}")
            }
            // `--with-semantics`: semantics come only from the daemon, started in its own Gradle
            // invocation after the bundle is written, so a failure degrades gracefully. Skipped
            // with `--no-render`.
            val packSemantics = withSemantics && !noRender
            if (withSemantics && noRender) {
              System.err.println(
                "bundle pack: --with-semantics needs a render; ignoring it because --no-render was passed."
              )
            }
            val tasks = buildList {
              if (!noRender) add(":${target.gradlePath}:composePreviewRenderAll")
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
            // Inject semantics before the summary so its byte count is right. Best-effort: failures
            // warn and leave the valid bundle as-is.
            val semanticsLine =
              if (packSemantics) {
                // Separate invocation so a daemon-start failure can't abort the pack.
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
            semanticsLine?.let { println(it.summary) }
            // Missing-glyph boxes are a defect in the artefact, not infrastructure, so refuse the
            // pack.
            lostFontFamilyRefusal(
                degradedPreviewIds = semanticsLine?.degradedPreviewIds.orEmpty(),
                allowed = allowLostFontFamilies,
                bundlePath = resolvedOutput.path,
              )
              ?.let {
                System.err.println(it)
                exitProcess(1)
              }
          }
        }
      }
      .run()
  }

  /**
   * `--per-preview`: emit one self-contained single-preview bundle per preview
   * (`<out-dir>/<id>.png`). Renders once, then packs each through `composePreviewBundle` so each is
   * minimized to its own closure. Each pack re-runs the closure scan; `--with-semantics` is skipped
   * here with a warning.
   */
  private fun runPerPreview() {
    val cmdArgs = buildList {
      module?.let {
        add("--module")
        add(it)
      }
      if (verbose) add("--verbose")
      if (progress && !verbose) add("--progress")
      // Same whitelist, same omission — the per-preview path needs the variant just as much.
      variant?.let {
        add("--variant")
        add(it)
      }
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
              // Apply the render filters to the shared render too; `--id` only selects what gets
              // packed. File form and absolute path as above.
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

            // Render once; the per-preview packs reuse the outputs.
            if (!noRender) {
              val rendered =
                runGradle(
                  gradle,
                  ":${target.gradlePath}:composePreviewRenderAll",
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
            // Fail on an unknown `--id` rather than silently omitting a bundle.
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

            // Distinct ids can sanitize to the same stem; disambiguate with `-2`, `-3`, … rather
            // than overwrite.
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

            // Pack each preview from the already-rendered PNGs (bundle task only).
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
   * What a `--with-semantics` pack carried: the stdout [summary], and previews whose figma-svg
   * export degraded to missing-glyph boxes (empty when healthy).
   */
  private data class PackedSemantics(
    val summary: String,
    val degradedPreviewIds: List<String> = emptyList(),
  )

  /**
   * Carry per-preview semantics inside [bundleFile]: a short-lived daemon
   * ([DaemonSemanticsFetcher]) renders the selected previews and their `compose/semantics` trees
   * are injected as `previews/<id>.semantics.json`.
   *
   * Best-effort for infrastructure failures (warn, leave the bundle valid; returns null when
   * nothing was carried). Lost font families are a defect in the artefact, returned in
   * [PackedSemantics.degradedPreviewIds] for the caller to refuse.
   */
  private fun packSemanticsBlob(
    target: PreviewModule,
    bundleFile: File,
    meta: BundleReader.Metadata,
    renderTimeout: Duration,
  ): PackedSemantics? {
    val previewIds = meta.manifest.previewIds
    if (previewIds.isEmpty()) return null
    // The daemon keys on raw discovery ids; fetch by raw id (falling back to the bundle form for
    // older bundles) and re-key to the sanitised bundle form before injecting.
    val rawIds =
      if (meta.manifest.rawPreviewIds.size == previewIds.size) meta.manifest.rawPreviewIds
      else previewIds
    val bundleIdByRaw = rawIds.zip(previewIds).toMap()
    fun <V> Map<String, V>.keyedByBundleId(): Map<String, V> = entries.associate { (raw, v) ->
      (bundleIdByRaw[raw] ?: raw) to v
    }
    // Skip excluded previews here too (a daemon render each). They stay listed in the bundle so
    // they remain addressable, just without sidecars.
    val captureIds = PackPreviewIdExclusions.retain(rawIds, excludePreviewIds)
    // One line per pattern: a pattern matching nothing is usually a typo.
    for (match in PackPreviewIdExclusions.matches(rawIds, excludePreviewIds)) {
      System.err.println("bundle pack: ${match.line}")
    }
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
        // Layout-inspector trees ride along as `previews/<id>.layout.json` (best-effort).
        val layoutWritten = injectLayoutIntoBundle(bundleFile, canonical.layoutById)
        // `fonts/used` (Android only, best-effort), for generating the Wasm tier's fonts.json.
        val fontsWritten = injectFontsIntoBundle(bundleFile, outcome.fontsById.keyedByBundleId())
        // The editable `compose/figma-svg` export per sticker (best-effort).
        val figmaSvgWritten =
          injectFigmaSvgIntoBundle(bundleFile, outcome.figmaSvgById.keyedByBundleId())
        // Hybrid figma-svg raster crops, as `previews/<id>.figma-raster/<node>.png`; usually none.
        val figmaRasterWritten =
          injectFigmaRasterIntoBundle(bundleFile, outcome.figmaRasterById.keyedByBundleId())
        // Font-warning sidecars exist only for degraded previews; carried with the artefact they
        // describe.
        val fontWarningsWritten =
          injectFigmaFontWarningsIntoBundle(
            bundleFile,
            outcome.figmaFontWarningsById.keyedByBundleId(),
          )
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
          if (fontWarningsWritten > 0)
            append(
              "\n  figma-fonts:   $fontWarningsWritten preview(s) DEGRADED — text exported as " +
                "missing-glyph boxes, carried as previews/<id>$BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX"
            )
        }
        return PackedSemantics(
          summary = semanticsLine + extraLines,
          degradedPreviewIds = outcome.figmaFontWarningsById.keys.sorted(),
        )
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
    // Local so the cross-module property can be smart-cast.
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
      // Paths are generated, but verify anyway so an id can't escape the output dir.
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
   * `--in-bundle`: append the web embed under [BUNDLE_WEB_DIR] (`web/`) in the bundle's zip,
   * keeping the cover and existing entries; readers ignore it, so the polyglot stays valid.
   * Replaces any prior `web/` entries. In place by default, or `-o <file.png>` for a copy (required
   * for URL inputs, which resolve to a temp file).
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
    // Repeatable `--knob key=value` theme overrides; see [parseKnobOverrides].
    val knobs: Map<String, PreviewOverrideValue> = parseKnobOverrides(args)
    // `--res <dir>`: a published bundle's externalized resource pool (`bundle/res/<sha>`), needed
    // when `bundle externalize` lifted its fonts out.
    val resPool = args.flagValue("--res")?.let { File(it) }
    // `--svg`: also export each re-themed preview's `compose/figma-svg` for `bundle repack`. Daemon
    // / `--knob` path only.
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

    // Theme overrides need the daemon path (the one `serve` uses) rather than the source subprocess
    // renderer. See [renderBundleWithOverrides].
    if (knobs.isNotEmpty()) {
      if (!renderBundleWithOverrides(file, target, knobs, resPool, withSvg, verbose)) exitProcess(1)
      return
    }

    if (withSvg) {
      // Without `--knob` there's nothing to re-theme, so `--svg` doesn't apply.
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
 * `bundle repack <bundle> --renders <dir> -o <out.png>`: copy [bundleFile] with each `<id>.png` /
 * `<id>.svg` from `--renders` replacing `previews/<id>.png` / `previews/<id>.figma.svg`. Everything
 * else is preserved verbatim. Renders matching no baked slot are skipped and reported.
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
 * Result of [repackRethemedPreviews]: swapped [png] and [svg] slot counts, and render filenames
 * that matched no slot.
 */
internal data class RepackOutcome(val png: Int, val svg: Int, val unmatched: List<String>) {
  val repacked: Int
    get() = png + svg
}

/**
 * Core of `bundle repack`: copy [source] to [outFile], swapping top-level `previews/<id>.png` and
 * `previews/<id>.figma.svg` for the re-renders in [rendersDir]. Nested raster crops, JSON sidecars
 * and the cover stay verbatim. Throws [IllegalStateException] if nothing matched.
 */
internal fun repackRethemedPreviews(source: File, rendersDir: File, outFile: File): RepackOutcome {
  // Only top-level `previews/<id>.png` / `.figma.svg` are swap targets; deeper paths are raster
  // crops.
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
  // Copy the whole polyglot, then swap in place; the cover stays the source's.
  source.copyTo(outFile, overwrite = true)
  injectRawZipEntries(outFile, entries)
  return RepackOutcome(png, svg, unmatched)
}

/**
 * `bundle merge <base.png> <shard.png>… -o <out.png>`: union the per-preview artifacts of bundles
 * packed from the same module and commit with disjoint render selections — the merge step of a
 * sharded CI render. Not `repack`, which only swaps existing slots and would drop the shards'
 * sidecars.
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
      // Partitions should be disjoint; an overlap wastes render time and silently picks a winner.
      System.err.println(
        "  ${outcome.overlapping.size} preview(s) were baked by more than one shard — " +
          "the base's copy wins; the partition is not disjoint"
      )
      if (verbose) outcome.overlapping.forEach { System.err.println("    overlap $it") }
    }
  }
}

/**
 * Result of [mergeShardBundles]: previews that gained a raster from a shard, total entries copied,
 * and ids baked by more than one bundle (the base's copy wins).
 */
internal data class MergeOutcome(val previews: Int, val entries: Int, val overlapping: List<String>)

/**
 * Zip-entry prefixes holding per-preview render output, i.e. what a shard contributes. Everything
 * else (manifests, classes, libs, cover) is identical across shards and inherited from the base.
 */
private val MERGEABLE_SHARD_PREFIXES = listOf("$BUNDLE_PREVIEWS_DIR/", "ir/", "extensions/")

/**
 * Core of `bundle merge`: copy [base] to [outFile] and add every per-preview artifact the [shards]
 * carry and the base lacks (rasters, sidecars, raster crops, `ir/` documents, `extensions/`
 * reports). Base wins, then earlier shards. Throws [IllegalArgumentException] for a non-bundle.
 * Streams shards so their large shared classpath payload is never held in memory.
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
  // The base already has the manifests, classpath and cover; inject the shards' renders.
  base.copyTo(outFile, overwrite = true)
  injectRawZipEntries(outFile, add)
  return MergeOutcome(
    previews = add.keys.count { bakedPreviewId(it) != null },
    entries = add.size,
    overlapping = overlapping.toList(),
  )
}

/**
 * The preview id of a top-level baked raster entry (`previews/<id>.png`), or null for anything
 * else.
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
 * Parse repeatable `--knob key=value` into string theme overrides, splitting on the first `=` so
 * values keep their own `=`/`,`/`;`. Entries without `=` or with a blank key are dropped; the last
 * repeated key wins.
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
 * Render every preview of [bundleFile] to [outDir] under theme [overrides] via the daemon path
 * `serve` uses ([ServeBundleDaemon.materialize] + [ServeRenderHost]), so published bundles re-skin
 * without a rebuild. Returns true iff every preview rendered.
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
  val backend = readBundleBackendForRender(bundleFile) ?: return false
  // Sidecars are fetched from the compose-preview-daemon release on first use.
  try {
    when (backend) {
      "desktop" -> {
        DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.DESKTOP)
        SkikoNativeProvision.prepareInstalledDesktopSidecars()
      }
      "android" -> DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
    }
  } catch (e: IllegalStateException) {
    System.err.println("bundle render: ${e.message}")
    return false
  }
  // Private temp dir, not under outDir: outDir must contain only rendered PNGs.
  val workspace = Files.createTempDirectory("bundle-render-daemon").toFile()
  try {
    // Rehydrate externalized fonts from `--res`; fail closed, since a missing font corrupts output.
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

internal fun readBundleBackendForRender(
  bundleFile: File,
  report: (String) -> Unit = { System.err.println(it) },
): String? =
  try {
    BundleReader.readMetadata(bundleFile).manifest.backend
  } catch (e: Exception) {
    report("bundle render: cannot read bundle metadata from ${bundleFile.path}: ${e.message}")
    null
  }

/**
 * Rehydrate [bundleFile]'s externalized resources from the content-addressed [pool] into [destDir]
 * at their recorded classpath paths. Returns [destDir], or null for a self-contained bundle. Throws
 * if resources are needed but [pool] is missing or an entry fails verification.
 */
private fun resolveExternalResources(bundleFile: File, pool: File?, destDir: File): File? {
  val resources = runCatching {
    BundleReader.readMetadata(bundleFile).manifest.externalResources
  }
    .getOrDefault(emptyList())
  return materializeExternalResources(resources, pool, destDir)
}

/**
 * Core of [resolveExternalResources]: copy each of [resources] from [pool] (by sha256) to its
 * recorded path under [destDir], verifying size + sha256 and rejecting traversal. Empty → null; any
 * failure throws [IllegalStateException].
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
 * Render every preview [host] exposes to `<outDir>/<sanitized id>.png` under [seed], returning
 * failure descriptions (empty iff all rendered). Does not close [host].
 *
 * With [withSvg] and [ServeRenderHost.hasSvgExport], also writes `<sanitized id>.svg`. SVG is
 * best-effort: its failures are logged and never added to the returned failures.
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
      // Sequential, so Busy shouldn't happen; report it rather than skip.
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
 * Escape `,` and `\` for the comma-separated `-PbundlePreviewIds=` property, so ids like `Phone,
 * dark` survive. Mirrors `BundlePreviewIds.encode` in `:gradle-plugin`.
 */
private fun encodePreviewId(id: String): String =
  buildString(id.length) {
    for (c in id) {
      if (c == '\\' || c == ',') append('\\')
      append(c)
    }
  }

/**
 * Why a `--with-semantics` pack must not publish, or null when it may: figma-svg exports that lost
 * a font family render as missing-glyph boxes. [allowed] is the explicit override.
 */
internal fun lostFontFamilyRefusal(
  degradedPreviewIds: List<String>,
  allowed: Boolean,
  bundlePath: String,
): String? {
  if (degradedPreviewIds.isEmpty() || allowed) return null
  return "bundle pack: ${degradedPreviewIds.size} preview(s) exported their text as " +
    "missing-glyph boxes because the figma-svg export could not name a font family the render " +
    "drew:\n" +
    degradedPreviewIds.joinToString("\n") { "  - $it" } +
    "\nEach carries previews/<id>$BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX in $bundlePath naming the " +
    "face that was lost. Fix the export, or pass --allow-lost-font-families to publish the boxes " +
    "deliberately."
}
