package ee.schimke.composeai.bundle

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
public fun extractBundleClassesAndManifest(
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
          expandZipBytesSafely(appJarBytes, classesDir, fileSystem, "bundle: app jar entry")
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
 * Extract the v5+ IR replay payload from [zipBytes]: every `ir/<leaf>` entry into [irDir]
 * (flattened to its basename, matching [BundleIr.path]) plus `bundle.json` into [manifestFile].
 *
 * Both detached-daemon entry points need these files: `bundle daemon` passes them directly to its
 * subprocess, while the public catalog server records them in `daemon-launch.json`. Keeping the
 * extraction here prevents those two launch paths from silently diverging again.
 */
public fun extractBundleIrArtifacts(
  zipBytes: ByteArray,
  irDir: File,
  manifestFile: File,
  bundleFile: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  val canonicalIr = irDir.canonicalFile
  var sawManifest = false
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      val name = entry.name.replace('\\', '/')
      when {
        name == "bundle.json" -> {
          fileSystem.write(manifestFile.path.toPath()) { write(zin.readBytes()) }
          sawManifest = true
        }
        !entry.isDirectory && name.startsWith("ir/") && ".." !in name.split("/") -> {
          val dest = File(irDir, File(name).name).canonicalFile
          if (dest.path.startsWith(canonicalIr.path + File.separator)) {
            fileSystem.write(dest.path.toPath()) { writeAll(zin.source()) }
          }
        }
      }
      zin.closeEntry()
    }
  }
  require(sawManifest) {
    "bundle daemon: bundle.json missing in ${bundleFile.path} — cannot resolve IR descriptors"
  }
}

/**
 * Unpack a zip or jar's [bytes] into [targetDir], rejecting Zip Slip. Resolve + normalize +
 * `Path.startsWith` is the containment check CodeQL's `java/zipslip` recognizes as sanitization; an
 * equally safe `canonicalFile` + `String.startsWith` guard is reported as a false positive.
 *
 * [what] names the offending entry in the rejection message ("bundle entry", "app jar entry", …).
 */
public fun expandZipBytesSafely(
  bytes: ByteArray,
  targetDir: File,
  fileSystem: FileSystem = SystemFileSystem,
  what: String = "bundle entry",
) {
  val targetPath = targetDir.canonicalFile.toPath()
  ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      val resolved = targetPath.resolve(entry.name).normalize()
      if (!resolved.startsWith(targetPath)) {
        throw SecurityException("$what escapes target dir: ${entry.name} → $resolved")
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
public fun locateBundleSidecarJars(sidecarName: String): List<File> {
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
  val sidecarJars =
    firstExistingDir
      .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name }
      .orEmpty()
  // The CLI distribution deliberately omits every platform's Skiko native runtime. The CLI
  // provisioner resolves exactly the current host's matching jar and publishes its directory via
  // this property before any desktop subprocess is assembled. Append it to the daemon sidecar so
  // callers compiled in other repositories (serve's bundle daemon and RC JVM renderer) inherit the
  // native without needing a parallel locator or a release-coupled API change.
  return if (sidecarName == "lib-daemon-desktop") {
    (configuredSkikoJars() + sidecarJars).distinctBy { it.absoluteFile.normalize().path }
  } else {
    sidecarJars
  }
}

/** Human-readable description of where [locateBundleSidecarJars] looked, for error messages. */
public fun bundleSidecarSearchDescription(sidecarName: String): String {
  val sysprop = bundleSidecarSysprop(sidecarName)
  val override = System.getProperty(sysprop)
  val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
  return listOfNotNull(
      override?.let { "-D$sysprop=$it" },
      appHome?.let { "$it/$sidecarName" },
      "<classpath-parent>/../$sidecarName",
      if (sidecarName == "lib-daemon-desktop") {
        System.getProperty(CLI_SKIKO_DIR_PROPERTY)?.let { "-D$CLI_SKIKO_DIR_PROPERTY=$it" }
      } else null,
    )
    .joinToString(" or ")
}

/** Directory containing the one host-specific Skiko runtime provisioned by the CLI. */
public const val CLI_SKIKO_DIR_PROPERTY: String = "composeai.cli.skikoDir"

private fun configuredSkikoJars(): List<File> {
  val dir = System.getProperty(CLI_SKIKO_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
  if (dir?.isDirectory != true) return emptyList()
  return dir
    .listFiles { file ->
      file.isFile && file.name.startsWith("skiko-awt-runtime-") && file.name.endsWith(".jar")
    }
    ?.sortedBy { it.name }
    .orEmpty()
}

private fun bundleSidecarSysprop(sidecarName: String): String =
  when (sidecarName) {
    "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
    "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
    "lib-renderer" -> "composeai.cli.libRendererDir"
    "lib-rcjvm" -> "composeai.cli.libRcjvmDir"
    "lib-bta" -> "composeai.cli.libBtaDir"
    "lib-usage-psi" -> "composeai.cli.libUsagePsiDir"
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
