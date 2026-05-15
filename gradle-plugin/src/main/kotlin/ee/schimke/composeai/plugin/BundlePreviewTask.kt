package ee.schimke.composeai.plugin

import io.github.classgraph.ClassGraph
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Pack a portable preview bundle — a PNG+ZIP polyglot containing the selected previews' metadata,
 * the minimal set of consumer classes reachable from those previews, the runtime jars that
 * contribute to the closure, and a minimization report. See [PreviewBundleFormat] for the on-disk
 * layout.
 *
 * The closure walk is driven by ClassGraph's inter-class dependency map: starting from each
 * selected preview's enclosing class FQN, we BFS through every class the closure references and
 * collect the set. Module classes are repacked per-class (small, ours, safe to surgically prune).
 * Third-party jars are included whole when they contribute ≥1 reachable class — stripping inside a
 * jar is risky (lambdas, kotlin metadata, resources, services) and the per-jar size win is the
 * dominant lever anyway.
 */
abstract class BundlePreviewTask : DefaultTask() {

  /**
   * `previews.json` produced by [DiscoverPreviewsTask]. The task reads it to (a) resolve the
   * selected ids' enclosing class FQNs (the closure entry points) and (b) write a filtered copy
   * into the bundle.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val previewsJson: RegularFileProperty

  /**
   * Consumer module's class dirs (`build/classes/kotlin/jvm/main`, etc.). Walked for per-class
   * minimization — only `.class` files for reachable FQNs land in `classes/app.jar`. Module
   * resources directory is taken separately via [moduleResourcesDir] when present.
   */
  @get:Classpath abstract val moduleClassDirs: ConfigurableFileCollection

  /**
   * Consumer module's processed resources directory (e.g. `build/processedResources/jvm/main`).
   * Bundled wholesale alongside the minimized classes — resources are typically small, and
   * string-id references in bytecode make them hard to prune deterministically.
   */
  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val moduleResourcesDir: DirectoryProperty

  /**
   * Third-party runtime classpath jars. Each is included whole into `libs/` if and only if the
   * closure walk lands on at least one of its classes.
   */
  @get:Classpath abstract val dependencyJars: ConfigurableFileCollection

  /**
   * Renders directory from the preceding `renderPreviews` task. The cover preview's PNG is read
   * from here and prepended to the polyglot; when missing or empty, the task emits a stub gray PNG
   * so the bundle is still well-formed (and `file(1)` still reports PNG).
   *
   * Marked `@Internal` because the dir may legitimately not exist (bundling without a prior render
   * is a supported v1 flow). Tracking it as an `@InputDirectory @Optional` errors out when Gradle
   * resolves the property to a path on disk that doesn't yet exist. The bundle is still re-packed
   * whenever the upstream `discoverPreviews` output or the classpath change.
   */
  @get:Internal abstract val rendersDir: DirectoryProperty

  /**
   * Preview ids to include. First entry is the cover. Empty means "all previews in the manifest";
   * passing the empty list intentionally — most callers will populate this from CLI input.
   */
  @get:Input abstract val previewIds: ListProperty<String>

  /** Gradle module path, recorded into the bundle for forensics. */
  @get:Input abstract val modulePath: Property<String>

  /** Producer-version string for diagnostics, e.g. "compose-preview $BUNDLE_VERSION". */
  @get:Input abstract val producedBy: Property<String>

  /** Backend identifier. v1 = "desktop". */
  @get:Input abstract val backend: Property<String>

  /** Output `.png` polyglot file. */
  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun pack() {
    val manifestFile = previewsJson.get().asFile
    val manifest = JSON.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
    val selected = resolveSelection(manifest, previewIds.get())
    val coverId = selected.first().id
    val entryClassFqns = selected.map { it.className }.toSet()

    val classDirsList = moduleClassDirs.files.filter { it.exists() && it.isDirectory }
    val jarsList = dependencyJars.files.filter { it.isFile && it.name.endsWith(".jar") }
    val scanPaths = (classDirsList + jarsList).map { it.absolutePath }

    val closure = closureWalk(scanPaths, entryClassFqns)

    val moduleClassFqns = collectClassFqns(classDirsList)
    val reachableModuleClasses = moduleClassFqns intersect closure.reachable
    val keptModuleClassFiles = packModuleClasses(classDirsList, reachableModuleClasses)

    val keptJars = jarsList.mapNotNull { jar ->
      val totals = closure.perElement[jar.absolutePath]
      val total = totals?.total ?: 0
      val reachable = totals?.reachable ?: 0
      val keep = reachable > 0
      LibraryJarDecision(
        sourcePath = jar.absolutePath,
        bundledAs = if (keep) "libs/${dedupeJarName(jar.name)}" else null,
        totalClasses = total,
        reachableClasses = reachable,
        originalBytes = jar.length(),
        kept = keep,
      )
    }

    val classpathOrder = mutableListOf("classes/app.jar")
    keptJars.filter { it.kept }.forEach { classpathOrder += it.bundledAs!! }

    val appJarBytes = buildJar(keptModuleClassFiles, moduleResourcesDir.orNull?.asFile)
    val report =
      MinimizationReport(
        entryClassFqns = entryClassFqns.sorted(),
        reachableClassCount = closure.reachable.size,
        totalScannedClassCount = closure.totalScanned,
        moduleClasses =
          ModuleClassesStats(
            totalClasses = moduleClassFqns.size,
            reachableClasses = reachableModuleClasses.size,
            packedBytes = appJarBytes.size.toLong(),
          ),
        libraryJars = keptJars,
      )

    val bundle =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = backend.get(),
        previewIds = selected.map { it.id },
        coverPreviewId = coverId,
        classpath = classpathOrder,
        modulePath = modulePath.get(),
        producedBy = producedBy.get(),
      )

    val filteredManifest = manifest.copy(previews = selected)
    val zipBytes =
      buildZip(
        bundleJson = JSON.encodeToString(BundleManifest.serializer(), bundle),
        previewsJson = JSON.encodeToString(PreviewManifest.serializer(), filteredManifest),
        appJar = appJarBytes,
        keptJars = keptJars.filter { it.kept },
        report = JSON.encodeToString(MinimizationReport.serializer(), report),
      )

    val coverPng = resolveCoverPng(selected.first())
    val outFile = output.get().asFile
    writePngZipPolyglot(coverPng, zipBytes, outFile)

    logger.lifecycle(
      "composePreviewBundle — wrote ${outFile.name} (${outFile.length()} bytes)\n" +
        "  entry classes:        ${report.entryClassFqns.size}\n" +
        "  reachable classes:    ${report.reachableClassCount} / ${report.totalScannedClassCount}\n" +
        "  module classes kept:  ${report.moduleClasses.reachableClasses} / ${report.moduleClasses.totalClasses}\n" +
        "  jars kept:            ${report.libraryJars.count { it.kept }} / ${report.libraryJars.size}\n" +
        "  bytes saved (jars):   ${report.libraryJars.filter { !it.kept }.sumOf { it.originalBytes }}"
    )
  }

  private fun resolveSelection(manifest: PreviewManifest, ids: List<String>): List<PreviewInfo> {
    if (ids.isEmpty()) {
      if (manifest.previews.isEmpty()) {
        throw GradleException(
          "composePreviewBundle: previews.json is empty — nothing to bundle. Run discoverPreviews first."
        )
      }
      return manifest.previews
    }
    val byId = manifest.previews.associateBy { it.id }
    val resolved = ids.map { id ->
      byId[id]
        ?: throw GradleException(
          "composePreviewBundle: preview id not found: $id\nAvailable: ${byId.keys.sorted().joinToString()}"
        )
    }
    return resolved
  }

  private fun resolveCoverPng(cover: PreviewInfo): ByteArray {
    val rendersRoot = rendersDir.orNull?.asFile
    if (rendersRoot != null) {
      val candidate =
        cover.captures.firstOrNull()?.renderOutput?.let { rel ->
          // `renderOutput` is module-relative under `build/compose-previews/`, e.g.
          // `renders/<id>.png`. The rendersDir input points at the `renders/` dir itself.
          val name = rel.substringAfterLast('/')
          File(rendersRoot, name)
        }
      if (candidate != null && candidate.isFile && candidate.length() > 0) {
        return candidate.readBytes()
      }
    }
    return STUB_GRAY_PNG
  }

  private fun packModuleClasses(
    classDirs: List<File>,
    reachable: Set<String>,
  ): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    for (root in classDirs) {
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { f ->
          val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
          val fqn = rel.removeSuffix(".class").replace('/', '.')
          if (fqn in reachable) {
            result[rel] = f.readBytes()
          }
        }
    }
    return result
  }

  private fun collectClassFqns(classDirs: List<File>): Set<String> {
    val result = mutableSetOf<String>()
    for (root in classDirs) {
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { f ->
          val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
          result += rel.removeSuffix(".class").replace('/', '.')
        }
    }
    return result
  }

  private fun buildJar(classes: Map<String, ByteArray>, resourcesDir: File?): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      classes.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      if (resourcesDir != null && resourcesDir.isDirectory) {
        resourcesDir
          .walkTopDown()
          .filter { it.isFile }
          .forEach { f ->
            val rel = f.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
            if (rel !in classes) zip.writeFile(rel, f.readBytes())
          }
      }
    }
    return baos.toByteArray()
  }

  private fun buildZip(
    bundleJson: String,
    previewsJson: String,
    appJar: ByteArray,
    keptJars: List<LibraryJarDecision>,
    report: String,
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    val usedNames = mutableSetOf<String>()
    ZipOutputStream(baos).use { zip ->
      zip.writeFile("bundle.json", bundleJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("previews.json", previewsJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("classes/app.jar", appJar)
      keptJars.forEach { decision ->
        val bundled = decision.bundledAs ?: return@forEach
        // dedupeJarName already ran on the manifest entry, but defend against duplicates
        // sneaking in via a renamed manifest path during refactor.
        if (bundled in usedNames) return@forEach
        usedNames += bundled
        zip.writeFile(bundled, File(decision.sourcePath).readBytes())
      }
      zip.writeFile("report.json", report.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
  }

  private fun ZipOutputStream.writeFile(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
  }

  /**
   * Two consumer dependencies can resolve to jars with the same basename (e.g. two
   * `kotlinx-coroutines-core-jvm-…` from different configurations); dedupe by suffixing a counter
   * when a collision is detected. Order matches the input classpath, so the renderer's load order
   * is preserved.
   */
  private val seenJarNames = mutableMapOf<String, Int>()

  private fun dedupeJarName(name: String): String {
    val count = seenJarNames.getOrDefault(name, 0)
    seenJarNames[name] = count + 1
    return if (count == 0) name else name.removeSuffix(".jar") + "-$count.jar"
  }

  private data class PerElementCount(val reachable: Int, val total: Int)

  private data class Closure(
    val reachable: Set<String>,
    val perElement: Map<String, PerElementCount>,
    val totalScanned: Int,
  )

  private fun closureWalk(scanPaths: List<String>, entries: Set<String>): Closure {
    if (scanPaths.isEmpty()) {
      return Closure(reachable = entries.toSet(), perElement = emptyMap(), totalScanned = 0)
    }
    ClassGraph()
      .enableAllInfo()
      .enableInterClassDependencies()
      .overrideClasspath(scanPaths)
      .ignoreParentClassLoaders()
      .scan()
      .use { scan ->
        val depMap = scan.classDependencyMap
        val all = scan.allClasses
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        // Seed with every class in the enclosing FQN's compilation unit — Kotlin top-level
        // functions live on `FooKt` and their generated companions (`ComposableSingletons$FooKt`,
        // `FooKt$lambda-1`, …) share the prefix. Adding them all up front means we don't depend on
        // ClassGraph having an edge from the FooKt class to its lambda inner classes (it usually
        // does, but the seed is cheap insurance).
        for (entry in entries) {
          for (ci in all) {
            if (ci.name == entry || ci.name.startsWith("$entry$")) {
              if (visited.add(ci.name)) queue += ci.name
            }
          }
        }
        while (queue.isNotEmpty()) {
          val current = queue.removeFirst()
          val ci = scan.getClassInfo(current) ?: continue
          val deps = depMap[ci] ?: continue
          for (dep in deps) {
            if (visited.add(dep.name)) queue += dep.name
          }
        }
        val perElementReachable = HashMap<String, IntArray>() // [reachable, total]
        for (ci in all) {
          val file = ci.classpathElementFile?.absolutePath ?: continue
          val counts = perElementReachable.getOrPut(file) { IntArray(2) }
          counts[1]++
          if (ci.name in visited) counts[0]++
        }
        return Closure(
          reachable = visited,
          perElement = perElementReachable.mapValues { (_, c) -> PerElementCount(c[0], c[1]) },
          totalScanned = all.size,
        )
      }
  }

  private companion object {
    val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }

    /**
     * 1×1 gray PNG built on demand. Used as the cover when no rendered PNG is available — file(1)
     * still reports PNG and viewers render a single neutral pixel, conveying "no render yet".
     */
    val STUB_GRAY_PNG: ByteArray by lazy {
      val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
      img.setRGB(0, 0, 0x808080)
      val baos = ByteArrayOutputStream()
      ImageIO.write(img, "png", baos)
      baos.toByteArray()
    }
  }
}
