package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

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
 */
class BundleCommand(args: List<String>) : Command(args) {

  override fun run() {
    val sub = args.firstOrNull { !it.startsWith("-") }
    when (sub) {
      "pack" -> PackSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "inspect" -> InspectSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "extract" -> ExtractSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "render" -> RenderSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "daemon" -> BundleDaemonCommand(args.drop(args.indexOf(sub) + 1)).run()
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

      Usage:
        compose-preview bundle pack [--module <name>] [--id <preview>...] [-o <file.png>] [--no-render]
        compose-preview bundle inspect <bundle.png>
        compose-preview bundle extract <bundle.png> [-o <dir>]
        compose-preview bundle render  <bundle.png> [-o <dir>]   (v1: stub — prints what would render)
        compose-preview bundle daemon  <bundle.png> [-v]         (spawn the desktop daemon over stdio)

      Pack flags:
        --id <preview-id>   Preview to include. Repeatable. First is the cover. Default: all.
        -o, --output <file> Output file path. Default: <module>/build/compose-previews/bundle.png.
        --no-render         Skip composePreviewRender — pack with a stub gray cover.
        --embed-deps        Carry reachable third-party jars inside the bundle (libs/) instead of
                            referencing Maven coordinates. Bigger file, but renders with no network
                            and no build system on the other end (resolution=embedded).

      Inspect / extract / render flags:
        -o, --output <dir>  Directory to extract / render into. Default: alongside the bundle.
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
              if (ids.isNotEmpty()) add("-PbundlePreviewIds=${ids.joinToString(",")}")
              if (embedDeps) add("-PbundleEmbedDeps=true")
              add("-PbundleOutput=${resolvedOutput.absolutePath}")
            }
            val tasks =
              buildList {
                  if (!noRender) add(":${target.gradlePath}:composePreviewRender")
                  add(":${target.gradlePath}:composePreviewBundle")
                }
                .toTypedArray()
            val ok = runGradle(gradle, *tasks, arguments = gradleArgsWithForce(gradleArgs))
            if (!ok) {
              System.err.println("Gradle bundle task failed.")
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
            printPackSummary(resolvedOutput, meta)
          }
        }
      }
      .run()
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
      System.err.println("Usage: compose-preview bundle inspect <bundle.png>")
      exitProcess(64)
    }
    val file = File(path)
    if (!file.isFile) {
      System.err.println("Not a file: $path")
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
      System.err.println("Usage: compose-preview bundle extract <bundle.png> [-o <dir>]")
      exitProcess(64)
    }
    val file = File(path)
    if (!file.isFile) {
      System.err.println("Not a file: $path")
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

private class RenderSubcommand(private val args: List<String>) {
  private val verbose: Boolean = "--verbose" in args || "-v" in args

  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val outDir = args.flagValue("--output") ?: args.flagValue("-o")
    if (path == null) {
      System.err.println("Usage: compose-preview bundle render <bundle.png> [-o <dir>]")
      exitProcess(64)
    }
    val file = File(path)
    if (!file.isFile) {
      System.err.println("Not a file: $path")
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
 * Extracts a zip safely — every entry's resolved target path is verified to live inside [target].
 * Defeats Zip Slip (`../../etc/passwd`-style entry names) reported by CodeQL / Codex on the v1
 * extract path; same call site is shared by `extract` and `render`.
 */
private fun safeExtractZip(zipBytes: ByteArray, target: File) {
  val canonicalTarget = target.canonicalFile
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      val candidate = File(target, entry.name).canonicalFile
      // Reject anything resolving outside the target dir, regardless of whether the entry's name
      // happens to be relative ("foo/..//bar") or absolute on some platforms.
      if (
        candidate != canonicalTarget &&
          !candidate.path.startsWith(canonicalTarget.path + File.separator)
      ) {
        throw SecurityException(
          "bundle entry escapes target dir: ${entry.name} → ${candidate.path}"
        )
      }
      if (entry.isDirectory) {
        candidate.mkdirs()
      } else {
        candidate.parentFile?.mkdirs()
        candidate.outputStream().use { sink -> zin.copyTo(sink) }
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

  private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
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
  fun extractZipBytes(file: File): ByteArray {
    val bytes = file.readBytes()
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
  fun extractEmbeddedLibs(zipBytes: ByteArray, libsDir: File): List<File> {
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
            dest.outputStream().use { sink -> zin.copyTo(sink) }
            written += dest
          }
        }
        zin.closeEntry()
      }
    }
    return written.sortedBy { it.name }
  }
}
