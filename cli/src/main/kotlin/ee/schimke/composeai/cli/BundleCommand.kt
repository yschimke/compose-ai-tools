package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
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
      "inspect" -> InspectSubcommand(subArgs).run()
      "extract" -> ExtractSubcommand(subArgs).run()
      "embed" -> EmbedSubcommand(subArgs).run()
      "render" -> RenderSubcommand(subArgs).run()
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
        compose-preview bundle inspect <bundle.png | URL>
        compose-preview bundle extract <bundle.png | URL> [-o <dir>]
        compose-preview bundle embed   <bundle.png | URL> [-o <dir|file.png>] [--title T] [--external-images] [--in-bundle]
        compose-preview bundle render  <bundle.png | URL> [-o <dir>]   (v1: stub — prints what would render)
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
  private val verbose: Boolean = "--verbose" in args || "-v" in args
  private val ids: List<String> =
    args
      .flagValuesAll("--id")
      .flatMap { it.split(',') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  fun run() {
    val cmdArgs = buildList {
      module?.let {
        add("--module")
        add(it)
      }
      if (verbose) add("--verbose")
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
            val tasks =
              buildList {
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
                  packSemanticsBlob(target, resolvedOutput, meta)
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
  ): String? {
    val previewIds = meta.manifest.previewIds
    if (previewIds.isEmpty()) return null
    val fetcher = DaemonSemanticsFetcher(onLog = { System.err.println("[daemon semantics] $it") })
    val outcome =
      fetcher.fetch(
        projectDir = target.projectDir,
        moduleName = target.gradlePath,
        previewIds = previewIds,
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
        val written = injectSemanticsIntoBundle(bundleFile, outcome.semanticsById)
        val missing = previewIds.size - written
        // The layout-inspector tree rides alongside the semantics blob (best-effort): a preview
        // that produced a tree gets `previews/<id>.layout.json` so a consumer can build slot-level
        // redlines/wireframes. Injected after semantics so its byte count is reflected too.
        val layoutWritten = injectLayoutIntoBundle(bundleFile, outcome.layoutById)
        // The fonts/used record rides the same render (best-effort, Android daemon only): carried
        // so the design-catalog export can generate the Wasm tier's fonts.json from actual usage.
        val fontsWritten = injectFontsIntoBundle(bundleFile, outcome.fontsById)
        // The layered `compose/figma-svg` export rides the same render (best-effort): carried so
        // the
        // design-catalog export can ship an editable vector per sticker beside the raster PNG.
        val figmaSvgWritten = injectFigmaSvgIntoBundle(bundleFile, outcome.figmaSvgById)
        // A hybrid figma-svg references `figma-raster/<node>.png` crops; carry them verbatim as
        // `previews/<id>.figma-raster/<node>.png` so the SVG's `<image>` layers resolve once the
        // export copies the SVG onto the delivery branch. Empty for the common vector-only case.
        val figmaRasterWritten = injectFigmaRasterIntoBundle(bundleFile, outcome.figmaRasterById)
        val semanticsLine =
          "  semantics:     $written / ${previewIds.size} preview(s) carried as " +
            "previews/<id>$BUNDLE_SEMANTICS_SUFFIX" +
            if (missing > 0) " ($missing without a captured tree)" else ""
        val extraLines = buildString {
          if (layoutWritten > 0)
            append(
              "\n  layout:        $layoutWritten / ${previewIds.size} preview(s) carried " +
                "as previews/<id>$BUNDLE_LAYOUT_SUFFIX"
            )
          if (fontsWritten > 0)
            append(
              "\n  fonts:         $fontsWritten / ${previewIds.size} preview(s) carried " +
                "as previews/<id>$BUNDLE_FONTS_SUFFIX"
            )
          if (figmaSvgWritten > 0)
            append(
              "\n  figma-svg:     $figmaSvgWritten / ${previewIds.size} preview(s) carried " +
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
    if (meta.report != null) {
      println("--- report.json ---")
      println(pretty.encodeToString(BundleReader.Report.serializer(), meta.report))
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
    safeExtractZip(zipBytes, target)
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

    val data = BundleReader.readWebEmbedData(file)
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
    val path = args.firstOrNull { !it.startsWith("-") }
    val outDir = args.flagValue("--output") ?: args.flagValue("-o")
    if (path == null) {
      System.err.println("Usage: compose-preview bundle render <bundle.png | URL> [-o <dir>]")
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

    val renderer = BundleRenderer(bundleFile = file, outputDir = target, verbose = verbose)
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

/**
 * Well-known directory inside a bundle zip holding an optional, self-contained web embed
 * (`web/index.html` + `web/compose-preview-embed.js`, and `web/previews/<id>.png` in external-image
 * mode). Written by `bundle embed --in-bundle`. Additive: an older reader, the renderer, and the
 * daemon all ignore it, so a bundle carrying a `web/` directory is still a valid polyglot.
 */
internal const val BUNDLE_WEB_DIR: String = "web"

/**
 * Well-known directory inside a bundle zip holding the per-preview baked PNGs (`previews/<id>.png`)
 * and, when packed with `--with-semantics`, each preview's semantics sidecar
 * (`previews/<id>.semantics.json`). Mirrors `BUNDLE_PREVIEWS_DIR` in `:gradle-plugin`.
 */
internal const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Suffix for the per-preview semantics blob carried beside `previews/<id>.png` (issue #1843). The
 * payload is the `compose/semantics`
 * [ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload] tree — per-node bounds,
 * label/text, and resolved foreground/background colours — the shape the design-parity static
 * bundle reader consumes as a sibling of the rendered PNG.
 */
internal const val BUNDLE_SEMANTICS_SUFFIX: String = ".semantics.json"

/**
 * Suffix for the per-preview layout-inspector blob carried beside `previews/<id>.png`. The payload
 * is the `layout/inspector` [ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload] tree
 * — the full LayoutNode walk with per-node bounds and resolved design tokens — so a consumer can
 * build slot-level redlines/wireframes the (a11y-shaped) semantics tree can't express.
 */
internal const val BUNDLE_LAYOUT_SUFFIX: String = ".layout.json"

/**
 * Suffix for the per-preview font-usage blob carried beside `previews/<id>.png`. The payload is the
 * `fonts/used` [ee.schimke.composeai.data.fonts.FontsUsedPayload] — every font resolution the
 * render made (requested vs resolved family, weight, style, fallback chain) — recorded by the
 * daemon's always-on FontsRecorderExtension. Carried so the design-catalog export can generate the
 * in-browser Wasm tier's `fonts.json` from what the previews actually resolved instead of a
 * hand-authored manifest.
 */
internal const val BUNDLE_FONTS_SUFFIX: String = ".fonts.json"

/**
 * Suffix for the per-preview layered SVG carried beside `previews/<id>.png`. The payload is the
 * `compose/figma-svg` [ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct] export —
 * an editable vector (real fills/strokes/corner radii + editable text), the same bytes a
 * `data/fetch` for the figma-svg yields — baked by the daemon's always-on render. Carried so the
 * design-catalog export can ship an editable vector per sticker alongside the raster PNG.
 */
internal const val BUNDLE_FIGMA_SVG_SUFFIX: String = ".figma.svg"

/**
 * Directory suffix for a hybrid figma-svg's per-node raster crops, carried beside its
 * `previews/<id>.figma.svg` as `previews/<id>.figma-raster/<node>.png`. Mirrors the
 * `figma-raster/<node>.png` hrefs the SVG's `<image>` layers reference so they resolve once the
 * design-catalog export copies the SVG (and rewrites those hrefs) onto the delivery branch. Absent
 * for a vector-only export.
 */
internal const val BUNDLE_FIGMA_RASTER_DIR_SUFFIX: String = ".figma-raster"

/**
 * Inject `previews/<id>.semantics.json` entries (id → `compose-semantics.json` bytes) into
 * [bundleFile]'s zip portion **in place**, preserving the leading PNG cover and every existing
 * entry. Re-injecting replaces any prior semantics entry for the same id, so a second
 * `--with-semantics` pack is idempotent. New entries are pinned to the DOS epoch so the enriched
 * bundle stays byte-stable. Written via a temp sibling + atomic move so a failure never truncates
 * the bundle. Returns the number of entries written.
 */
internal fun injectSemanticsIntoBundle(
  bundleFile: File,
  semanticsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, semanticsById, BUNDLE_SEMANTICS_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.layout.json` entries (id → `layout-inspector.json` bytes) into [bundleFile]
 * — the full LayoutNode tree (per-node bounds + resolved design tokens) the daemon bakes alongside
 * the semantics blob. Carried so consumers can build slot-level redlines/wireframes the a11y
 * semantics tree can't express. Same in-place, idempotent, byte-stable contract as
 * [injectSemanticsIntoBundle]. Returns the number of entries written.
 */
internal fun injectLayoutIntoBundle(
  bundleFile: File,
  layoutById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, layoutById, BUNDLE_LAYOUT_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.fonts.json` entries (id → `fonts-used.json` bytes) into [bundleFile] — the
 * per-preview `fonts/used` record the daemon bakes alongside the semantics blob. Carried so the
 * design-catalog export can generate the in-browser tier's font manifest from recorded usage. Same
 * in-place, idempotent, byte-stable contract as [injectSemanticsIntoBundle]. Returns the number of
 * entries written.
 */
internal fun injectFontsIntoBundle(
  bundleFile: File,
  fontsById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, fontsById, BUNDLE_FONTS_SUFFIX, fileSystem)

/**
 * Inject `previews/<id>.figma.svg` entries (id → `compose-figma.svg` bytes) into [bundleFile] — the
 * layered editable `compose/figma-svg` export the daemon bakes alongside the semantics blob.
 * Carried so the design-catalog export can ship an editable vector per sticker next to the raster
 * PNG. Same in-place, idempotent, byte-stable contract as [injectSemanticsIntoBundle]. Returns the
 * number of entries written.
 */
internal fun injectFigmaSvgIntoBundle(
  bundleFile: File,
  figmaSvgById: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int = injectSidecarsIntoBundle(bundleFile, figmaSvgById, BUNDLE_FIGMA_SVG_SUFFIX, fileSystem)

/**
 * Inject a hybrid figma-svg's per-node raster crops ([figmaRasterById]: preview id → (crop filename
 * → PNG bytes)) into [bundleFile] as `previews/<id>.figma-raster/<node>.png`, so the SVG's `<image
 * href="figma-raster/<node>.png">` layers resolve after the export carries them. Same in-place,
 * idempotent, byte-stable contract as the other injectors. Returns the number of crops written
 * across all previews.
 */
internal fun injectFigmaRasterIntoBundle(
  bundleFile: File,
  figmaRasterById: Map<String, Map<String, ByteArray>>,
  fileSystem: FileSystem = SystemFileSystem,
): Int {
  val entries =
    figmaRasterById.entries
      .flatMap { (id, crops) ->
        crops.map { (name, bytes) ->
          "$BUNDLE_PREVIEWS_DIR/$id$BUNDLE_FIGMA_RASTER_DIR_SUFFIX/$name" to bytes
        }
      }
      .toMap()
  return injectRawZipEntries(bundleFile, entries, fileSystem)
}

/**
 * Inject `previews/<id><suffix>` entries (id → bytes) into [bundleFile]'s zip portion **in place**,
 * preserving the leading PNG cover and every existing entry. Re-injecting replaces any prior entry
 * for the same id+suffix, so a second pack is idempotent. New entries are pinned to the DOS epoch
 * so the enriched bundle stays byte-stable. Written via a temp sibling + atomic move so a failure
 * never truncates the bundle. Returns the number of entries written.
 */
internal fun injectSidecarsIntoBundle(
  bundleFile: File,
  byId: Map<String, ByteArray>,
  suffix: String,
  fileSystem: FileSystem = SystemFileSystem,
): Int {
  if (byId.isEmpty()) return 0
  val full = fileSystem.read(bundleFile.path.toPath()) { readByteArray() }
  val zip = BundleReader.extractZipBytes(bundleFile)
  // The appended zip is a suffix of the file; everything before it is the leading PNG cover.
  val prefix = full.copyOfRange(0, full.size - zip.size)
  val entries = byId.entries.associate { (id, bytes) -> "$BUNDLE_PREVIEWS_DIR/$id$suffix" to bytes }
  val newZip = addOrReplaceZipEntries(zip, entries)

  val tmp = File(bundleFile.parentFile, "${bundleFile.name}.sidecar-tmp")
  fileSystem.write(tmp.path.toPath()) {
    write(prefix)
    write(newZip)
  }
  fileSystem.atomicMove(tmp.path.toPath(), bundleFile.path.toPath())
  return entries.size
}

/**
 * Inject arbitrary top-level entries (posix zip path → bytes) into [bundleFile]'s zip portion **in
 * place**, preserving the leading PNG cover and every existing entry; an entry with a colliding
 * path is replaced (idempotent). Unlike [injectSidecarsIntoBundle] the paths are used verbatim (no
 * `previews/` prefix), so this is the carrier for whole-bundle sidecars like `signatures.json`.
 * Same temp-sibling + atomic-move + DOS-epoch contract as the other injectors. Returns the count
 * written.
 */
internal fun injectRawZipEntries(
  bundleFile: File,
  entries: Map<String, ByteArray>,
  fileSystem: FileSystem = SystemFileSystem,
): Int {
  if (entries.isEmpty()) return 0
  val full = fileSystem.read(bundleFile.path.toPath()) { readByteArray() }
  val zip = BundleReader.extractZipBytes(bundleFile)
  val prefix = full.copyOfRange(0, full.size - zip.size)
  val newZip = addOrReplaceZipEntries(zip, entries)
  val tmp = File(bundleFile.parentFile, "${bundleFile.name}.rawentry-tmp")
  fileSystem.write(tmp.path.toPath()) {
    write(prefix)
    write(newZip)
  }
  fileSystem.atomicMove(tmp.path.toPath(), bundleFile.path.toPath())
  return entries.size
}

/**
 * Return a copy of [existingZip] with [newEntries] (path → bytes) added, replacing any existing
 * entry with the same name (so the operation is idempotent). Every other original entry is
 * preserved verbatim. New entries are pinned to [ZIP_DOS_EPOCH_MS] for reproducibility. Operates on
 * raw zip bytes — the caller re-attaches the polyglot's leading PNG.
 */
internal fun addOrReplaceZipEntries(
  existingZip: ByteArray,
  newEntries: Map<String, ByteArray>,
): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zout ->
    ZipInputStream(ByteArrayInputStream(existingZip)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && entry.name !in newEntries) {
          zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
          zin.copyTo(zout)
          zout.closeEntry()
        }
        zin.closeEntry()
      }
    }
    for ((path, bytes) in newEntries) {
      zout.putNextEntry(ZipEntry(path).apply { time = ZIP_DOS_EPOCH_MS })
      zout.write(bytes)
      zout.closeEntry()
    }
  }
  return baos.toByteArray()
}

/**
 * Return a copy of [existingZip] with [webFiles] (path → bytes) added. Every original entry is
 * preserved except ones already under `$BUNDLE_WEB_DIR/`, which are dropped first so re-embedding
 * is idempotent (no duplicate `web/…` entries on a second run). New entries are pinned to the DOS
 * epoch so the result is reproducible. Operates on raw zip bytes — the caller re-attaches the
 * polyglot's leading PNG.
 */
internal fun embedWebIntoZip(existingZip: ByteArray, webFiles: Map<String, ByteArray>): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zout ->
    ZipInputStream(ByteArrayInputStream(existingZip)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory && !entry.name.startsWith("$BUNDLE_WEB_DIR/")) {
          zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
          zin.copyTo(zout)
          zout.closeEntry()
        }
        zin.closeEntry()
      }
    }
    for ((path, bytes) in webFiles) {
      zout.putNextEntry(ZipEntry(path).apply { time = ZIP_DOS_EPOCH_MS })
      zout.write(bytes)
      zout.closeEntry()
    }
  }
  return baos.toByteArray()
}

/**
 * 1980-01-01 DOS-epoch floor stamped on entries written by [embedWebIntoZip], matching the plugin's
 * reproducible-bundle writer so an enriched bundle stays byte-stable across runs.
 */
internal val ZIP_DOS_EPOCH_MS: Long =
  java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis

/**
 * The output path for `bundle embed --in-bundle`, or `null` when the caller must error and demand
 * `-o`. An explicit [outArg] always wins. Otherwise we default to rewriting [inputPath] in place —
 * but only for a *local* input: a URL input ([sourceIsUrl]) resolved to a delete-on-exit temp file,
 * and rewriting that "in place" would lose the enriched bundle on exit, so we refuse and require an
 * explicit output instead.
 */
internal fun resolveInBundleTarget(
  outArg: String?,
  inputPath: String,
  sourceIsUrl: Boolean,
): String? =
  when {
    outArg != null -> outArg
    sourceIsUrl -> null
    else -> inputPath
  }

/**
 * Extracts a zip safely — every entry's resolved target path is verified to live inside [target].
 * Defeats Zip Slip (`../../etc/passwd`-style entry names) reported by CodeQL / Codex on the v1
 * extract path; same call site is shared by `extract` and `render`.
 */
private fun safeExtractZip(
  zipBytes: ByteArray,
  target: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val targetPath = target.canonicalFile.toPath()
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      // Resolve + normalize the entry against the target and verify containment via
      // Path.startsWith — rejects "../" traversal and absolute entry names alike, and is the form
      // CodeQL's java/zipslip recognizes as sanitization (the prior canonicalFile +
      // String.startsWith guard was equally safe but flagged as a false positive).
      val resolved = targetPath.resolve(entry.name).normalize()
      if (!resolved.startsWith(targetPath)) {
        throw SecurityException("bundle entry escapes target dir: ${entry.name} → $resolved")
      }
      val candidate = resolved.toFile()
      if (entry.isDirectory) {
        candidate.mkdirs()
      } else {
        candidate.parentFile?.mkdirs()
        fileSystem.write(candidate.path.toPath()) { writeAll(zin.source()) }
      }
      zin.closeEntry()
    }
  }
}

/**
 * In-CLI mirror of the bundle's on-disk schema. We re-declare the data classes here (rather than
 * dragging the gradle-plugin module onto the CLI's compile classpath) because the CLI links against
 * a different module graph; the schema is tiny and rarely changes.
 *
 * Keep field names in lockstep with `PreviewBundleFormat.kt` in `:gradle-plugin`.
 */
internal object BundleReader {

  @Serializable
  data class Manifest(
    val schemaVersion: Int,
    val backend: String,
    val previewIds: List<String>,
    val coverPreviewId: String?,
    val classpath: List<ClasspathEntry>,
    val modulePath: String,
    val producedBy: String,
    /** v3+: producing build system (`gradle`|`amper`|`bazel`). Defaults for v2 bundles. */
    val producer: String = "gradle",
    /** v3+: classpath assembly strategy (`coordinates`|`embedded`|`mixed`). Defaults for v2. */
    val resolution: String = "coordinates",
    /**
     * v5+: previews replayed from a captured intermediate representation (`ir/<id>.<ext>`) rather
     * than by re-running their consumer bytecode. Empty for a classic all-classes bundle.
     */
    val intermediateRepresentations: List<BundleIr> = emptyList(),
    /**
     * v6+: Android resource carriage for protolayout (Wear tile) IR replay — the merged resource
     * APK + manifest + generated R classes under `android/`. Null for desktop / non-protolayout
     * bundles. See `BundleAndroidResources` in `PreviewBundleFormat.kt`.
     */
    val androidResources: AndroidResources? = null,
    /**
     * v7+: optional per-extension data reports carried under `extensions/<id>.json` (a11y findings,
     * theme tokens, …). Empty unless the bundle was packed with `--include-data-extensions`. See
     * `BundleDataExtension` in `PreviewBundleFormat.kt`.
     */
    val dataExtensions: List<DataExtension> = emptyList(),
  )

  /** v7+ mirror of `BundleDataExtension` in `PreviewBundleFormat.kt`. */
  @Serializable data class DataExtension(val extensionId: String, val path: String)

  /** v6+ mirror of `BundleAndroidResources` in `PreviewBundleFormat.kt`. */
  @Serializable
  data class AndroidResources(
    val resourceApkPath: String,
    val mergedManifestPath: String,
    val rClassesJarPath: String? = null,
    /**
     * Consumer application package; written as `android_custom_package` in the synthesized config.
     */
    val applicationPackage: String? = null,
  )

  /** v5+ mirror of `BundleIr` in `PreviewBundleFormat.kt`. */
  @Serializable
  data class BundleIr(
    val previewId: String,
    /** `remotecompose` (RC doc) or `protolayout` (Wear tile Layout proto). */
    val format: String,
    val path: String,
    val resourcesPath: String? = null,
  )

  @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
  @Serializable
  @JsonClassDiscriminator("kind")
  sealed interface ClasspathEntry {
    @Serializable
    @kotlinx.serialization.SerialName("module")
    data class Module(val path: String) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("maven")
    data class Maven(
      val group: String,
      val artifact: String,
      val version: String,
      val type: String,
      /** v4+: hex SHA-256 of the artifact bytes; verify after re-resolving. Null = unverifiable. */
      val sha256: String? = null,
    ) : ClasspathEntry

    @Serializable
    @kotlinx.serialization.SerialName("project")
    data class Project(val path: String, val inlinedAs: String) : ClasspathEntry

    /**
     * v3+: a third-party jar carried inside the bundle's `libs/` — no coordinate, no resolution.
     */
    @Serializable
    @kotlinx.serialization.SerialName("embedded")
    data class Embedded(val inlinedAs: String) : ClasspathEntry
  }

  @Serializable
  data class Report(
    val entryClassFqns: List<String>,
    val reachableClassCount: Int,
    val totalScannedClassCount: Int,
    val moduleClasses: ModuleClasses,
    val dependencies: List<DependencyDecision>,
  )

  @Serializable
  data class ModuleClasses(val totalClasses: Int, val reachableClasses: Int, val packedBytes: Long)

  @Serializable
  data class DependencyDecision(
    val sourcePath: String,
    val coordinate: String?,
    val projectPath: String?,
    val totalClasses: Int,
    val reachableClasses: Int,
    val originalBytes: Long,
    val kept: Boolean,
  )

  data class Metadata(val manifest: Manifest, val report: Report?)

  /**
   * The previews needed to build a web embed, read out of a packed bundle in one pass: the manifest
   * (for ordering + cover) plus each selected preview's baked PNG and a display label.
   *
   * Previews without a baked `previews/<id>.png` (e.g. a `--no-render` pack, or one that failed to
   * render) are dropped — there's nothing to show on a web page. Returned in `previewIds` order
   * with the cover first, matching how the polyglot lays them out.
   */
  data class WebEmbedData(val manifest: Manifest, val previews: List<WebEmbed.Preview>)

  private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
  }

  private val previewsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Read everything a [WebEmbed] needs from a bundle file: the manifest, `previews.json` (for
   * human-readable labels), and every baked `previews/<id>.png`. The PNGs are the single source of
   * what's shown, so previews with no baked image are omitted.
   */
  fun readWebEmbedData(file: File): WebEmbedData {
    val zipBytes = extractZipBytes(file)
    var manifest: Manifest? = null
    val labels = HashMap<String, String>()
    val pngs = HashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        when {
          name == "bundle.json" ->
            manifest =
              json.decodeFromString(Manifest.serializer(), zin.readBytes().toString(Charsets.UTF_8))
          name == "previews.json" -> {
            // Best-effort: labels are a nicety. A malformed/foreign previews.json must not sink the
            // embed — we still have ids from bundle.json to fall back on.
            runCatching {
                previewsJson.decodeFromString(
                  PreviewManifest.serializer(),
                  zin.readBytes().toString(Charsets.UTF_8),
                )
              }
              .getOrNull()
              ?.previews
              ?.forEach { labels[it.id] = it.functionName.ifBlank { it.id } }
          }
          name.startsWith("previews/") && name.endsWith(".png") -> {
            val id = name.removePrefix("previews/").removeSuffix(".png")
            pngs[id] = zin.readBytes()
          }
        }
        zin.closeEntry()
      }
    }
    val m = manifest ?: throw IllegalArgumentException("bundle.json missing in ${file.path}")
    val previews =
      m.previewIds.mapNotNull { id ->
        val png = pngs[id] ?: return@mapNotNull null
        WebEmbed.Preview(
          id = id,
          label = labels[id] ?: id,
          pngBytes = png,
          isCover = id == m.coverPreviewId,
        )
      }
    return WebEmbedData(manifest = m, previews = previews)
  }

  fun readMetadata(file: File): Metadata {
    val zipBytes = extractZipBytes(file)
    var manifest: Manifest? = null
    var report: Report? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        when (entry.name) {
          "bundle.json" ->
            manifest =
              json.decodeFromString(Manifest.serializer(), zin.readBytes().toString(Charsets.UTF_8))
          "report.json" ->
            report =
              json.decodeFromString(Report.serializer(), zin.readBytes().toString(Charsets.UTF_8))
        }
        zin.closeEntry()
      }
    }
    return Metadata(
      manifest = manifest ?: throw IllegalArgumentException("bundle.json missing in ${file.path}"),
      report = report,
    )
  }

  /** Polyglot-aware zip extraction; mirrors [extractZipBytes] in the plugin module. */
  fun extractZipBytes(file: File, fileSystem: FileSystem = SystemFileSystem): ByteArray {
    val bytes = fileSystem.read(file.path.toPath()) { readByteArray() }
    if (bytes.size < 8) {
      throw IllegalArgumentException("not a bundle: ${file.path} is too small (${bytes.size}B)")
    }
    if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes
    if (isPngSignature(bytes)) {
      val zipStart = pngLength(bytes)
      return bytes.copyOfRange(zipStart, bytes.size)
    }
    throw IllegalArgumentException(
      "not a bundle: ${file.path} — leading bytes match neither PNG nor ZIP"
    )
  }

  private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  private fun isPngSignature(bytes: ByteArray): Boolean {
    if (bytes.size < PNG_SIG.size) return false
    for (i in PNG_SIG.indices) if (bytes[i] != PNG_SIG[i]) return false
    return true
  }

  private fun pngLength(bytes: ByteArray): Int {
    var offset = PNG_SIG.size
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return offset
    }
    throw IllegalArgumentException("truncated PNG: IEND not found before EOF")
  }

  /**
   * Extract every embedded jar under `libs/` from a bundle's [zipBytes] into [libsDir], returning
   * the written jar files sorted by name (stable classpath order). Embedded-mode bundles (schema-v3
   * `resolution = "embedded"`) carry their reachable third-party deps here; coordinate bundles
   * carry none, so this returns an empty list.
   *
   * Each entry is flattened to its basename under [libsDir] and the resolved path is verified to
   * live inside [libsDir] — defeats Zip Slip (`../` traversal) on a hostile bundle. Nested paths
   * and directory entries are ignored. Shared by [BundleRenderer] and [BundleDaemonCommand] so the
   * two player paths extract identically.
   */
  fun extractEmbeddedLibs(
    zipBytes: ByteArray,
    libsDir: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): List<File> {
    libsDir.mkdirs()
    val canonicalLibs = libsDir.canonicalFile
    val written = mutableListOf<File>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        if (!entry.isDirectory && name.startsWith("libs/") && name.endsWith(".jar")) {
          val dest = File(libsDir, File(name).name).canonicalFile
          if (dest.path.startsWith(canonicalLibs.path + File.separator)) {
            fileSystem.write(dest.path.toPath()) { writeAll(zin.source()) }
            written += dest
          }
        }
        zin.closeEntry()
      }
    }
    return written.sortedBy { it.name }
  }
}
