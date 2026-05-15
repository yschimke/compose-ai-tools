package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `compose-preview bundle <pack|inspect|extract>` — produce and inspect portable preview bundles.
 *
 * # Bundle file shape
 *
 * The bundle is a PNG + ZIP polyglot. The leading bytes are a valid PNG (the cover preview's
 * rendered image — Finder, Preview.app, GitHub, Slack all show it as an image). The trailing bytes
 * are a standard zip archive that any tooling (this CLI, VS Code, `unzip`, your zip library) reads
 * via the EOCD signature `PK\x05\x06`. See `PreviewBundleFormat.kt` in the plugin module for the
 * in-repo schema definitions.
 *
 * # Subcommands
 *
 * - **`pack`** — runs `renderPreviews` (for the cover) and `composePreviewBundle` against a Gradle
 *   module and writes the resulting `.png` polyglot. Selection is via repeatable `--id` flags; the
 *   first id becomes the cover. `--no-render` skips the render step and packs with a stub gray
 *   cover.
 * - **`inspect`** — open a bundle file and print its `bundle.json` + `report.json` summary,
 *   including the minimization report (how many module classes were kept vs total, how many jars
 *   were kept vs dropped, total bytes saved). Read-only.
 * - **`extract`** — extract the zip portion of a bundle into a directory. Useful for forensic
 *   inspection and for the VS Code extension's preview opener.
 */
class BundleCommand(args: List<String>) : Command(args) {

  override fun run() {
    val sub = args.firstOrNull { !it.startsWith("-") }
    when (sub) {
      "pack" -> PackSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "inspect" -> InspectSubcommand(args.drop(args.indexOf(sub) + 1)).run()
      "extract" -> ExtractSubcommand(args.drop(args.indexOf(sub) + 1)).run()
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

      Pack flags:
        --id <preview-id>   Preview to include. Repeatable. First is the cover. Default: all.
        -o, --output <file> Output file path. Default: <module>/build/compose-previews/bundle.png.
        --no-render         Skip renderPreviews — pack with a stub gray cover.

      Inspect / extract flags:
        -o, --output <dir>  (extract only) Directory to extract into. Default: alongside the bundle.
      """
        .trimIndent()
    )
  }
}

private class PackSubcommand(private val args: List<String>) {
  private val module: String? = args.flagValue("--module")
  private val output: String? = args.flagValue("--output") ?: args.flagValue("-o")
  private val noRender: Boolean = "--no-render" in args
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
    // Reuse the existing Command plumbing for module resolution. Wrap in an anonymous shim so we
    // get `withGradle`, `resolveModules`, and the auto-inject init-script flow without duplicating
    // 80 lines of boilerplate.
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
              add("-PbundleOutput=${resolvedOutput.absolutePath}")
            }
            val tasks =
              buildList {
                  if (!noRender) add(":${target.gradlePath}:renderPreviews")
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

            val report =
              try {
                BundleReader.readMetadata(resolvedOutput)
              } catch (e: Exception) {
                System.err.println(
                  "Wrote ${resolvedOutput.path} (${resolvedOutput.length()} bytes) but failed to read it back: ${e.message}"
                )
                exitProcess(1)
              }
            printPackSummary(resolvedOutput, report)
          }
        }
      }
      .run()
  }

  private fun printPackSummary(file: File, meta: BundleReader.Metadata) {
    println("wrote ${file.path} (${file.length()} bytes)")
    println("  schema:        v${meta.manifest.schemaVersion}, backend=${meta.manifest.backend}")
    println(
      "  previews:      ${meta.manifest.previewIds.size} (cover=${meta.manifest.coverPreviewId})"
    )
    println("  classpath:     ${meta.manifest.classpath.size} entries")
    val r = meta.report
    if (r != null) {
      println("  entry classes: ${r.entryClassFqns.size}")
      println(
        "  reachable:     ${r.reachableClassCount} / ${r.totalScannedClassCount} classes scanned"
      )
      println(
        "  module:        ${r.moduleClasses.reachableClasses} / ${r.moduleClasses.totalClasses} classes kept, ${r.moduleClasses.packedBytes} B packed"
      )
      val kept = r.libraryJars.count { it.kept }
      val droppedBytes = r.libraryJars.filter { !it.kept }.sumOf { it.originalBytes }
      println("  jars:          $kept / ${r.libraryJars.size} kept, ${droppedBytes} B dropped")
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
    val pretty = Json { prettyPrint = true }
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
        outDir ?: file.absoluteFile.parent.toString() + "/${file.nameWithoutExtension}-extracted"
      )
    target.mkdirs()
    val zipBytes = BundleReader.extractZipBytes(file)
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val out = File(target, entry.name)
        if (entry.isDirectory) {
          out.mkdirs()
        } else {
          out.parentFile?.mkdirs()
          out.outputStream().use { sink -> zin.copyTo(sink) }
        }
        zin.closeEntry()
      }
    }
    println("extracted ${file.name} → ${target.path}")
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
    val classpath: List<String>,
    val modulePath: String,
    val producedBy: String,
  )

  @Serializable
  data class Report(
    val entryClassFqns: List<String>,
    val reachableClassCount: Int,
    val totalScannedClassCount: Int,
    val moduleClasses: ModuleClasses,
    val libraryJars: List<LibraryJar>,
  )

  @Serializable
  data class ModuleClasses(val totalClasses: Int, val reachableClasses: Int, val packedBytes: Long)

  @Serializable
  data class LibraryJar(
    val sourcePath: String,
    val bundledAs: String?,
    val totalClasses: Int,
    val reachableClasses: Int,
    val originalBytes: Long,
    val kept: Boolean,
  )

  data class Metadata(val manifest: Manifest, val report: Report?)

  private val json = Json { ignoreUnknownKeys = true }

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
}
