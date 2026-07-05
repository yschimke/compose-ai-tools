package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source

/**
 * Bundle → daemon-launch plumbing shared by every consumer that spawns a preview daemon straight
 * from a packed bundle with no Gradle build in between: [BundleDaemonCommand] (`bundle daemon`,
 * stdio-driven subprocess for the VS Code bundle viewer) and
 * [ee.schimke.composeai.cli.serve.ServeBundleDaemon] (`serve --catalogs --allow-render-trusted`'s
 * in-process `daemon-launch.json` synthesis). Kept as plain top-level functions — the two callers
 * extract/launch differently enough (inherited stdio vs. a written descriptor file) that a shared
 * class would just be a bag of parameters.
 */

/**
 * Extract `classes/app.jar` → [classesDir] and `previews.json` → [previewsJson] from a bundle's
 * [zipBytes]. Throws if `previews.json` is missing; `classes/app.jar` is required only when
 * [requireAppJar] is true (false for a v5+ bundle whose previews are all IR-only — a fully
 * IR-backed bundle legitimately carries no consumer classes).
 */
internal fun extractBundleClassesAndManifest(
  zipBytes: ByteArray,
  classesDir: File,
  previewsJson: File,
  bundleFile: File,
  requireAppJar: Boolean,
  fileSystem: FileSystem = SystemFileSystem,
) {
  var sawAppJar = false
  var sawPreviewsJson = false
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      when (entry.name) {
        "previews.json" -> {
          fileSystem.write(previewsJson.path.toPath()) { write(zin.readBytes()) }
          sawPreviewsJson = true
        }
        "classes/app.jar" -> {
          val appJarBytes = zin.readBytes()
          expandBundleJarBytesSafely(appJarBytes, classesDir, fileSystem)
          sawAppJar = true
        }
      }
      zin.closeEntry()
    }
  }
  require(sawAppJar || !requireAppJar) {
    "classes/app.jar missing in ${bundleFile.path} — not a packed bundle"
  }
  require(sawPreviewsJson) { "previews.json missing in ${bundleFile.path} — not a packed bundle" }
}

/**
 * Unpack an app jar's bytes into [targetDir], rejecting Zip Slip. Shares its containment-check
 * shape with `BundleCommand`'s `safeExtractZip` (a bundle's own zip) — duplicated rather than
 * reused since this one unpacks an in-memory *jar's* bytes, a different call shape.
 */
internal fun expandBundleJarBytesSafely(
  appJarBytes: ByteArray,
  targetDir: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val targetPath = targetDir.canonicalFile.toPath()
  ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      // Resolve + normalize the entry against the target and verify containment via
      // Path.startsWith — the form CodeQL's java/zipslip recognizes as sanitization.
      val resolved = targetPath.resolve(entry.name).normalize()
      if (!resolved.startsWith(targetPath)) {
        throw SecurityException(
          "bundle: app jar entry escapes target dir: ${entry.name} → $resolved"
        )
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
 * Locate a sidecar jar dir (`lib-daemon-desktop` / `lib-renderer` / `lib-daemon-android`) inside
 * the CLI install. In order: explicit sysprop override (`composeai.cli.lib<Name>Dir`),
 * `$APP_HOME/<name>`, `<jar-parent>/../<name>` (IDE / `JavaExec` runs).
 */
internal fun locateBundleSidecarJars(sidecarName: String): List<File> {
  val sysprop = bundleSidecarSysprop(sidecarName)
  val override = System.getProperty(sysprop)
  val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
  val candidates =
    listOfNotNull(
        override?.let { File(it) },
        appHome?.let { File(it, sidecarName) },
        inferBundleSidecarFromClasspath(sidecarName),
      )
      .distinct()
  val firstExistingDir = candidates.firstOrNull { it.isDirectory } ?: return emptyList()
  return firstExistingDir
    .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
    ?.sortedBy { it.name }
    .orEmpty()
}

/** Human-readable description of where [locateBundleSidecarJars] looked, for error messages. */
internal fun bundleSidecarSearchDescription(sidecarName: String): String {
  val sysprop = bundleSidecarSysprop(sidecarName)
  val override = System.getProperty(sysprop)
  val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
  return listOfNotNull(
      override?.let { "-D$sysprop=$it" },
      appHome?.let { "$it/$sidecarName" },
      "<classpath-parent>/../$sidecarName",
    )
    .joinToString(" or ")
}

private fun bundleSidecarSysprop(sidecarName: String): String =
  when (sidecarName) {
    "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
    "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
    "lib-renderer" -> "composeai.cli.libRendererDir"
    else -> "composeai.cli.${sidecarName.replace('-', '.')}Dir"
  }

private fun inferBundleSidecarFromClasspath(sidecarName: String): File? {
  val cp = System.getProperty("java.class.path") ?: return null
  val firstEntry = cp.split(File.pathSeparator).firstOrNull { it.endsWith(".jar") } ?: return null
  val libDir = File(firstEntry).parentFile ?: return null
  val installRoot = libDir.parentFile ?: return null
  val candidate = File(installRoot, sidecarName)
  return candidate.takeIf { it.isDirectory }
}
