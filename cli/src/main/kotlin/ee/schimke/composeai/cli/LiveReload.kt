package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * `launch --project <dir>`: the app's Gradle build behind the simulator, so an edit reaches it
 * without an APK. A reload compiles the module ([compileTasks]: Kotlin and Java, which also run
 * `process<Variant>Resources`), takes the classes that changed since the launch, and starts a fresh
 * simulator with them ahead of the APK's converted classes: no dexing, no packaging, no conversion.
 *
 * - Classes: every project module's compiler output ([classDirs]) is fingerprinted at launch; a
 *   reload copies each class whose bytes differ (or that is new) into an overlay directory. The
 *   simulator's class path is the APK, the overlay, then the converted jar, so the overlay wins.
 * - Resources: `process<Variant>Resources` links them (aapt2) into a resource-only APK. When it
 *   changed, the simulator's resource APK is the launched APK with that one's `resources.arsc`,
 *   `res/` and manifest swapped in; when resource ids moved, the R classes come along too.
 * - What a compile cannot deliver — assets or native libraries changed, or a forced `full` reload —
 *   falls back to `assemble<Variant>` and an `update` to the rebuilt APK.
 *
 * The Gradle connection stays open between reloads, so its daemon stays warm.
 */
internal class LiveReload(
  private val projectDir: File,
  /** The app module's Gradle path, `:app`. */
  private val module: String,
  /** The variant, `debug` or `demoDebug`. */
  private val variant: String,
  private val gradleArguments: List<String>,
  /** Where overlays and merged resource APKs go; one directory per reload. */
  private val workDir: File,
  private val log: (String) -> Unit,
  /** Runs Gradle tasks; the Tooling API unless a test stands in. */
  private val runner: ((List<String>) -> Build)? = null,
) : AutoCloseable {

  private val connection: ProjectConnection by lazy {
    GradleConnector.newConnector().forProjectDirectory(projectDir).connect()
  }
  private val variantTitle = variant.replaceFirstChar { it.uppercase() }
  /** The build type, the variant's last word: `debug` for `demoDebug`; libraries' variant. */
  private val buildType = variant.split(Regex("(?=[A-Z])")).last().lowercase()
  private val moduleDir = File(projectDir, module.trim(':').replace(':', '/'))

  private var baseline: Fingerprint? = null
  private var generation = 0

  /** The app module's Kotlin and Java compile tasks: they run its resources' link too. */
  val compileTasks: List<String>
    get() = listOf("$module:compile${variantTitle}JavaWithJavac")

  val assembleTask: String
    get() = "$module:assemble$variantTitle"

  /** What a Gradle invocation did. */
  data class Build(val ok: Boolean, val ms: Long, val tasks: List<String>, val errors: List<String>)

  /** Builds the APK, for a launch from a project with no APK given. */
  fun assemble(): Pair<Build, File?> {
    val build = run(listOf(assembleTask))
    return build to if (build.ok) findApk() else null
  }

  /** The variant's APK under the module's `build/outputs/apk/`. */
  fun findApk(): File? {
    val root = File(moduleDir, "build/outputs/apk")
    val flavorPath =
      variant
        .split(Regex("(?=[A-Z])"))
        .dropLast(1)
        .joinToString("") { it }
        .replaceFirstChar { it.lowercase() }
    val dir =
      if (flavorPath.isEmpty()) File(root, buildType) else File(root, "$flavorPath/$buildType")
    return dir
      .listFiles { f -> f.isFile && f.name.endsWith(".apk") }
      ?.maxByOrNull { it.lastModified() }
  }

  /** Records what the running APK was built from: the reference every reload compares against. */
  fun recordBaseline() {
    baseline = fingerprint()
  }

  /** What a reload hands the simulator. */
  sealed interface Plan {
    val build: Build

    /** Freshly compiled classes (and maybe resources) ahead of the converted APK. */
    data class Overlay(
      override val build: Build,
      val resourceApk: File,
      val overlay: File?,
      val changedClasses: List<String>,
      val resources: Boolean,
      val resourceIds: Boolean,
      val prepareMs: Long,
      /** Each changed class's internal name in [overlay], as the simulator loads it. */
      val overlayNames: Map<String, String> = emptyMap(),
    ) : Plan

    /** A rebuilt APK, for what only packaging delivers. */
    data class Rebuild(override val build: Build, val apk: File, val reason: String) : Plan

    /** Nothing changed since the last reload. */
    data class Unchanged(override val build: Build) : Plan

    /** The build failed: the app keeps running. */
    data class Failed(override val build: Build) : Plan
  }

  /**
   * Compiles and works out how to deliver the result to a simulator running [launchedApk] (the APK
   * the session started from; its converted classes stay underneath every overlay). A build that
   * changed nothing the running simulator does not already have is [Plan.Unchanged].
   */
  fun prepare(launchedApk: File, full: Boolean, compile: Boolean = true): Plan {
    val base = baseline ?: fingerprint().also { baseline = it }
    if (full) return rebuild("a full reload was asked for")
    // compile = false: the caller has compiled the edit into the module's class directories itself
    // (the preview daemon's in-process compiler writes where Gradle's Kotlin compile does, with the
    // module's own compiler plugins), so only the diff runs. Nothing links resources: a resource
    // edit needs a compiling reload.
    val build =
      if (compile) run(compileTasks)
      else Build(ok = true, ms = 0, tasks = emptyList(), errors = emptyList())
    if (!build.ok) return Plan.Failed(build)
    val start = System.nanoTime()
    val now = fingerprint()
    if (now.assets != base.assets) return rebuild("assets or native libraries changed", build)
    // A class deleted or renamed since the launch is still in the converted jar under the overlay,
    // where it stays loadable: only a rebuilt APK drops it.
    val removed = base.classes.keys - now.classes.keys
    if (removed.isNotEmpty()) {
      return rebuild(
        "${removed.size} class(es) removed or renamed (${removed.sorted().first()}…)",
        build,
      )
    }
    val changed =
      now.classes.filter { (name, stamp) -> base.classes[name]?.first != stamp.first }.keys.sorted()
    val resources = now.linkedResources != base.linkedResources
    val resourceIds = now.symbols != base.symbols
    val state = changed.associateWith { now.classes.getValue(it).first } to now.linkedResources
    if (state == (applied ?: (emptyMap<String, Long>() to base.linkedResources))) {
      return Plan.Unchanged(build)
    }
    val dir =
      File(workDir, "reload-${++generation}").apply {
        deleteRecursively()
        mkdirs()
      }
    // Older generations' overlays belong to simulators already replaced; the last one's may still
    // be loading classes until this reload replaces it.
    workDir
      .listFiles { f ->
        f.isDirectory &&
          f.name.startsWith("reload-") &&
          (f.name.removePrefix("reload-").toIntOrNull() ?: 0) < generation - 1
      }
      ?.forEach { it.deleteRecursively() }
    var overlay: File? = null
    val bytes = LinkedHashMap<String, ByteArray>()
    val overlayNames = LinkedHashMap<String, String>()
    if (changed.isNotEmpty() || resourceIds) {
      overlay = File(dir, "classes").apply { mkdirs() }
      for (name in changed) {
        val compiled = now.classes.getValue(name).second.readBytes()
        bytes[name] = compiled
        overlayNames[name] = writeOverlayClass(compiled, overlay)
      }
      if (resourceIds) {
        // Moved ids: every R class, as the module's classes now see them.
        rJar()?.let { jar -> unzipClasses(jar, overlay) }
      }
    }
    val resourceApk =
      if (resources) {
        val linked = linkedResourcesFile() ?: return rebuild("no linked resources found", build)
        // One file per resource state, so a later class-only reload names the same resource APK
        // (and a standby prepared for it still fits).
        File(
            workDir,
            "resources-${now.linkedResources.toULong().toString(16)}-${launchedApk.nameWithoutExtension}.apk",
          )
          .also { merged ->
            if (!merged.isFile) {
              val partial = File(merged.path + ".partial")
              mergeResources(launchedApk, linked, partial)
              partial.renameTo(merged)
            }
          }
      } else launchedApk
    pending = state
    pendingBytes = bytes
    return Plan.Overlay(
      build,
      resourceApk,
      overlay,
      changed,
      resources,
      resourceIds,
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
      overlayNames,
    )
  }

  /** What the running simulator has: changed classes' stamps and the linked resources' stamp. */
  private var applied: Pair<Map<String, Long>, Long>? = null
  private var pending: Pair<Map<String, Long>, Long>? = null

  /**
   * Each changed class's compiler output as the running simulator has it: what the last applied
   * overlay held. A class edited for the first time since the launch is absent; the simulator runs
   * it from the APK's converted classes, whose shape is the converter's, not the compiler's.
   */
  private val running = HashMap<String, ByteArray>()
  private var pendingBytes: Map<String, ByteArray> = emptyMap()

  /** The last [prepare]d overlay is now running, by a restart or a hot swap. */
  fun applied() {
    applied = pending
    running.clear()
    running.putAll(pendingBytes)
  }

  /** Whether [plan] can be redefined in the running simulator, and which classes. */
  sealed interface Swap {
    /** Redefine these (internal names in the overlay); everything else is unchanged. */
    data class Classes(val names: List<String>) : Swap

    /** Restart instead, for [reason]. */
    data class Restart(val reason: String) : Swap
  }

  /**
   * Whether [plan] can reach the running simulator as a hot swap: only classes changed since what
   * it runs, each already running as compiler output, its fields and methods unchanged, and every
   * method whose code changed a composable one. A composable recomposes and shows the edit; other
   * code (an `onCreate`, an initializer) has already run, and a swap would hide the edit, so it
   * restarts. Speed only: whatever this says, a restart delivers the same classes.
   */
  fun hotSwap(plan: Plan.Overlay): Swap {
    if (plan.resources || plan.resourceIds) return Swap.Restart("resources changed")
    // Edited, then put back as it was at the launch: no longer in the overlay, still edited in the
    // simulator; only a restart takes it back to the APK's copy.
    (running.keys - plan.changedClasses.toSet()).firstOrNull()?.let {
      return Swap.Restart("$it is back to the launch's version; a restart loads it")
    }
    val delta =
      plan.changedClasses.filter { name -> !pendingBytes[name].contentEqualsOrNull(running[name]) }
    if (delta.isEmpty()) return Swap.Restart("no class changed since the running simulator's")
    for (name in delta) {
      val before =
        running[name]
          ?: return Swap.Restart("$name is first edited since the launch; a restart loads it")
      HotSwapCheck.why(before, pendingBytes.getValue(name))?.let {
        return Swap.Restart("$name: $it")
      }
    }
    return Swap.Classes(delta.map { plan.overlayNames.getValue(it) })
  }

  private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)

  private fun rebuild(reason: String, compiled: Build? = null): Plan {
    log("compose-preview launch: reload: $reason; rebuilding the APK")
    val (build, apk) = assemble()
    val combined =
      compiled?.let { Build(build.ok, it.ms + build.ms, it.tasks + build.tasks, build.errors) }
        ?: build
    if (!build.ok || apk == null) return Plan.Failed(combined)
    // The rebuilt APK is the new reference.
    baseline = fingerprint()
    applied = null
    running.clear()
    pendingBytes = emptyMap()
    return Plan.Rebuild(combined, apk, reason)
  }

  /** Runs [tasks], capturing the build's output for the errors a failure reports. */
  /**
   * Whether builds ask for Gradle's configuration cache, which saves about half a second of each
   * reload's compile once warm. On unless [gradleArguments] decide it; off for the rest of the
   * session after a build that fails on it, so a project that does not support it still reloads.
   */
  private var configurationCache = gradleArguments.none { arg ->
    CONFIGURATION_CACHE_ARGUMENTS.any { arg == it || arg.startsWith("$it=") }
  }

  fun run(tasks: List<String>): Build {
    runner?.let {
      return it(tasks)
    }
    if (configurationCache) {
      val (build, output) = runOnce(tasks, gradleArguments + "--configuration-cache")
      // Only the cache's own refusal: every build with it on prints "Configuration cache entry
      // stored", a compile error included, and that must not turn it off.
      if (build.ok || !refusedConfigurationCache(output)) return build
      configurationCache = false
      log(
        "compose-preview launch: the build fails with the configuration cache; reloading without it"
      )
    }
    return runOnce(tasks, gradleArguments).first
  }

  /** One Gradle build of [tasks], and the tail of what it printed. */
  private fun runOnce(tasks: List<String>, arguments: List<String>): Pair<Build, String> {
    val start = System.nanoTime()
    val output = TailBuffer()
    val ok =
      try {
        connection
          .newBuild()
          .forTasks(*tasks.toTypedArray())
          .withArguments(arguments)
          .setStandardOutput(output)
          .setStandardError(output)
          .run()
        true
      } catch (e: BuildException) {
        false
      } catch (e: Exception) {
        output.write("${e.message}\n".toByteArray())
        false
      }
    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    val errors = if (ok) emptyList() else output.errorLines()
    log(
      "compose-preview launch: gradle ${tasks.joinToString(" ")}: ${if (ok) "ok" else "FAILED"} in $ms ms"
    )
    if (!ok) errors.forEach { log("compose-preview launch:   $it") }
    return Build(ok, ms, tasks, errors) to output.text()
  }

  // ---------------------------------------------------------------------------------------------

  /** Class files by path (`com/example/A.class`) with their content stamp and file. */
  data class Fingerprint(
    val classes: Map<String, Pair<Long, File>>,
    val linkedResources: Long,
    val symbols: Long,
    val assets: Long,
  )

  fun fingerprint(): Fingerprint {
    val classes = linkedMapOf<String, Pair<Long, File>>()
    for (dir in classDirs()) {
      val root = dir.toPath()
      Files.walk(root).use { walk ->
        walk
          .filter { it.toString().endsWith(".class") && Files.isRegularFile(it) }
          .forEach { path ->
            val name = root.relativize(path).toString().replace(File.separatorChar, '/')
            classes.putIfAbsent(name, crc(path.toFile()) to path.toFile())
          }
      }
    }
    return Fingerprint(
      classes,
      linkedResourcesFile()?.let(::crc) ?: 0,
      symbolsFile()?.let(::crc) ?: 0,
      assetsStamp(),
    )
  }

  /**
   * Every project module's compiler output for the variant: Kotlin (`kotlin-classes`, or AGP's
   * built-in Kotlin), javac, and plain JVM modules' `classes/<lang>/main`. Included builds and
   * `buildSrc` are build logic, not the app, and are skipped.
   */
  fun classDirs(): List<File> =
    moduleBuildDirs().flatMap { build ->
      buildList {
        for (v in listOf(variant, buildType).distinct()) {
          val title = v.replaceFirstChar { it.uppercase() }
          add(File(build, "tmp/kotlin-classes/$v"))
          add(File(build, "intermediates/javac/$v/compile${title}JavaWithJavac/classes"))
          add(File(build, "intermediates/built_in_kotlinc/$v/compile${title}Kotlin/classes"))
        }
        add(File(build, "classes/kotlin/main"))
        add(File(build, "classes/java/main"))
        add(File(build, "classes/kotlin/jvm/main"))
      }
        .filter { it.isDirectory }
    }

  private fun moduleBuildDirs(): List<File> {
    val out = mutableListOf<File>()
    fun visit(dir: File, depth: Int) {
      if (depth > MAX_MODULE_DEPTH) return
      val children = dir.listFiles { f -> f.isDirectory } ?: return
      for (child in children) {
        val name = child.name
        if (name.startsWith(".") || name == "node_modules" || name == "buildSrc") continue
        if (name == "build") {
          out += child
          continue
        }
        if (
          depth > 0 &&
            (File(child, "settings.gradle.kts").isFile || File(child, "settings.gradle").isFile)
        ) {
          continue
        }
        if (name == "src") continue
        visit(child, depth + 1)
      }
    }
    visit(projectDir, 0)
    return out
  }

  private fun intermediates(kind: String): File =
    File(moduleDir, "build/intermediates/$kind/$variant/process${variantTitle}Resources")

  /** aapt2's linked resources: a resource-only APK. */
  private fun linkedResourcesFile(): File? =
    // AGP 9 keeps aapt2's output under linked_resources_binary_format, AGP 8 under processed_res.
    listOf("linked_resources_binary_format", "processed_res")
      .map { File(moduleDir, "build/intermediates/$it/$variant") }
      .firstNotNullOfOrNull { dir ->
        dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".ap_") }
      }

  private fun symbolsFile(): File? =
    File(intermediates("runtime_symbol_list"), "R.txt").takeIf { it.isFile }

  private fun rJar(): File? =
    File(intermediates("compile_and_runtime_not_namespaced_r_class_jar"), "R.jar").takeIf {
      it.isFile
    }

  /** Sizes and times of every module's `src/<set>/assets` and `jniLibs`: packaging-only inputs. */
  private fun assetsStamp(): Long {
    val crc = CRC32()
    for (build in moduleBuildDirs()) {
      val src = File(build.parentFile, "src")
      val sets = src.listFiles { f -> f.isDirectory } ?: continue
      for (set in sets.sortedBy { it.name }) {
        for (kind in listOf("assets", "jniLibs")) {
          File(set, kind)
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .forEach { crc.update("${it.path}:${it.length()}:${it.lastModified()}".toByteArray()) }
        }
      }
    }
    return crc.value
  }

  /** The source directories a `--live` session watches: every module's `src/`. */
  fun sourceDirs(): List<File> =
    moduleBuildDirs().map { File(it.parentFile, "src") }.filter { it.isDirectory }

  override fun close() {
    runCatching { connection.close() }
  }

  /**
   * Watches [sourceDirs] and calls [onChange] once edits have settled for [quietMs]: an editor's
   * save is often several writes, and a refactor several files.
   */
  fun watch(quietMs: Long = 400, onChange: (List<Path>) -> Unit): AutoCloseable {
    val service: WatchService = FileSystems.getDefault().newWatchService()
    val keys = mutableMapOf<WatchKey, Path>()
    fun register(dir: Path) {
      Files.walk(dir).use { walk ->
        walk
          .filter { Files.isDirectory(it) }
          .forEach { d ->
            keys[
              d.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
              )] = d
          }
      }
    }
    sourceDirs().forEach { register(it.toPath()) }
    val thread =
      Thread(
        {
          try {
            while (true) {
              val first = service.take()
              val changed = mutableListOf<Path>()
              var key: WatchKey? = first
              while (key != null) {
                val dir = keys[key]
                for (event in key.pollEvents()) {
                  val path = dir?.resolve(event.context() as? Path ?: continue) ?: continue
                  if (
                    event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)
                  ) {
                    register(path)
                  }
                  val name = path.fileName.toString()
                  if (!name.startsWith(".") && !name.endsWith("~")) changed.add(path)
                }
                key.reset()
                key = service.poll(quietMs, TimeUnit.MILLISECONDS)
              }
              if (changed.isNotEmpty()) onChange(changed.distinct())
            }
          } catch (_: InterruptedException) {} catch (
            _: java.nio.file.ClosedWatchServiceException) {}
        },
        "live-reload-watch",
      )
    thread.isDaemon = true
    thread.start()
    return AutoCloseable {
      thread.interrupt()
      service.close()
    }
  }

  companion object {
    private const val MAX_MODULE_DEPTH = 4

    fun crc(file: File): Long {
      val crc = CRC32()
      file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          crc.update(buffer, 0, read)
        }
      }
      return (crc.value shl 32) xor file.length()
    }

    /** What Gradle prints when a build cannot run with the configuration cache. */
    private val CONFIGURATION_CACHE_REFUSED =
      listOf("configuration cache problem", "configuration cache state could not be cached")

    /** Whether a failed build's [output] is the configuration cache's refusal, not the code's. */
    internal fun refusedConfigurationCache(output: String): Boolean =
      CONFIGURATION_CACHE_REFUSED.any {
        output.contains(it, ignoreCase = true)
      }

    /** Arguments that already choose whether builds use the configuration cache. */
    private val CONFIGURATION_CACHE_ARGUMENTS =
      listOf(
        "--configuration-cache",
        "--no-configuration-cache",
        "-Dorg.gradle.configuration-cache",
        "-Dorg.gradle.unsafe.configuration-cache",
      )

    /**
     * The converter's dex2jar replaces every `-` in class, method and field names with `_`
     * (`constructor-impl` is `constructor_impl`, `lambda-1` is `lambda_1` in the converted jar), so
     * an overlaid class, straight from kotlinc, would call names that do not exist there: Compose
     * code fails at its first value-class call with `NoSuchMethodError`. Each overlaid class gets
     * the same names, declared and referenced, and is written under its renamed path.
     */
    internal fun writeOverlayClass(bytes: ByteArray, into: File): String {
      val reader = ClassReader(bytes)
      val writer = ClassWriter(0)
      reader.accept(ClassRemapper(writer, DexNames), 0)
      val name = DexNames.map(reader.className)
      val target = File(into, "$name.class")
      target.parentFile.mkdirs()
      target.writeBytes(writer.toByteArray())
      return name
    }

    /** dex2jar's name sanitizing (`LambadaNameSafeClassAdapter`): `-` becomes `_`. */
    private object DexNames : Remapper(Opcodes.ASM9) {
      private fun fix(name: String?): String? = name?.replace('-', '_')

      override fun map(internalName: String): String = fix(internalName)!!

      override fun mapMethodName(owner: String, name: String, descriptor: String): String =
        fix(name)!!

      override fun mapFieldName(owner: String, name: String, descriptor: String): String =
        fix(name)!!

      override fun mapInvokeDynamicMethodName(name: String, descriptor: String): String =
        fix(name)!!

      override fun mapInnerClassName(name: String, ownerName: String?, innerName: String?) =
        fix(super.mapInnerClassName(name, ownerName, innerName))

      override fun mapRecordComponentName(owner: String, name: String, descriptor: String) =
        fix(name)!!
    }

    private fun unzipClasses(jar: File, into: File) {
      ZipFile(jar).use { zip ->
        for (entry in zip.entries()) {
          if (entry.isDirectory || !entry.name.endsWith(".class")) continue
          writeOverlayClass(zip.getInputStream(entry).use { it.readBytes() }, into)
        }
      }
    }

    /**
     * [apk] with [linked]'s resources: its `resources.arsc`, `res/` and `AndroidManifest.xml`
     * replace the APK's, and everything else (assets, native libraries, dex) is kept. The simulator
     * reads only the resources, manifest and assets of this file.
     */
    fun mergeResources(apk: File, linked: File, out: File) {
      fun fromLinked(name: String) =
        name == "resources.arsc" || name == "AndroidManifest.xml" || name.startsWith("res/")
      ZipOutputStream(out.outputStream().buffered()).use { zip ->
        val written = mutableSetOf<String>()
        fun copy(source: ZipFile, entry: ZipEntry) {
          if (!written.add(entry.name)) return
          val copy = ZipEntry(entry.name)
          if (entry.method == ZipEntry.STORED) {
            copy.method = ZipEntry.STORED
            copy.size = entry.size
            copy.compressedSize = entry.size
            copy.crc = entry.crc
          }
          zip.putNextEntry(copy)
          source.getInputStream(entry).use { it.copyTo(zip) }
          zip.closeEntry()
        }
        ZipFile(linked).use { source ->
          for (entry in source.entries()) if (!entry.isDirectory) copy(source, entry)
        }
        ZipFile(apk).use { source ->
          for (entry in source.entries()) {
            if (entry.isDirectory || fromLinked(entry.name)) continue
            if (entry.name.startsWith("META-INF/")) continue
            copy(source, entry)
          }
        }
      }
    }
  }

  /** Keeps a build's last output, for the compiler errors a failed reload reports. */
  private class TailBuffer : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Synchronized override fun write(b: Int) = buffer.write(b)

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

    @Synchronized fun text(): String = buffer.toString(Charsets.UTF_8)

    @Synchronized
    fun errorLines(): List<String> {
      val lines = buffer.toString(Charsets.UTF_8).lines()
      // The compilers' own lines, then Gradle's "What went wrong" paragraph.
      val compiler = lines.filter { it.startsWith("e: ") || it.contains(": error:") }
      val wrong = lines.indexOfFirst { it.startsWith("* What went wrong") }
      val summary =
        if (wrong < 0) emptyList()
        else lines.drop(wrong + 1).takeWhile { !it.startsWith("* Try") }.filter { it.isNotBlank() }
      val errors = compiler + summary
      return (errors.ifEmpty { lines.filter { it.isNotBlank() }.takeLast(20) }).take(40)
    }
  }
}

/** The JSON a reload answers with. */
internal fun LiveReload.Build.toJson(): JsonObject = buildJsonObject {
  put("ok", ok)
  put("ms", ms)
  put("tasks", JsonArray(tasks.map(::JsonPrimitive)))
  if (errors.isNotEmpty()) put("errors", JsonArray(errors.map(::JsonPrimitive)))
}
