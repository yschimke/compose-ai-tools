package ee.schimke.composeai.cli

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Host (desktop) builds of the native libraries an APK carries only for Android, which `launch`
 * puts on the simulator JVM's `java.library.path` in place of the APK's `lib/<abi>/` copies
 * (#6342, #6411).
 *
 * Nothing is bundled and no version is baked in. The CLI reads an index at run time
 * (`host-natives/index.json` on this repository's `main`, or [INDEX_ENV]) that maps each library to
 * its host builds. A build is an archive per platform, either a `host-natives-<recipe>-<version>`
 * release from `host-natives.yml` or a library's own JVM artifact on Maven, with its sha256 and the
 * library's path inside it. `host-natives/index.py` maintains the index, and scheduled workflows
 * add new versions to it. See `docs/RELEASING.md`, "The host-natives binaries".
 *
 * The build chosen for an APK is the one at its exact AndroidX version when the index has it, and
 * otherwise the nearest, which may well work and is better than the certain failure without one.
 * Every skip or failure logs one line, and the launch carries on with the APK's
 * `UnsatisfiedLinkError` as before.
 */
object HostNativesProvision {
  /** Overrides the index location: an `https://` URL or a local file. */
  const val INDEX_ENV = "COMPOSE_PREVIEW_HOST_NATIVES_INDEX"

  internal const val DEFAULT_INDEX_URL =
    "https://raw.githubusercontent.com/$REPO/main/host-natives/index.json"

  /**
   * How long a fetched index is used before it is fetched again. An hour, not a day: the index is
   * one ~70 KB file, and it is how a fixed build reaches users (a rebuilt `-rN`, a corrected
   * entry). At 24 hours, a host that had fetched it just before graphics-path's glibc-2.28 rebuild
   * kept loading the build that needs GLIBC_2.43 for most of a day. A failed fetch, or the network
   * off, still falls back to the stale copy, so going offline costs nothing.
   */
  internal const val INDEX_MAX_AGE_MS = 60L * 60 * 1000

  internal const val SCHEMA = "compose-preview-host-natives/v1"

  @Serializable internal data class Index(val schema: String, val libraries: List<Library>)

  @Serializable
  internal data class Library(
    /**
     * The name the app passes to `System.loadLibrary`. Required even with [libraryPattern], which
     * takes precedence: CLIs from before patterns parse the same index and need it.
     */
    val library: String,
    /** A regex over load names whose one group is the version (zstd-jni names its library so). */
    val libraryPattern: String? = null,
    /** `group:name` of the Android artifact; an AndroidX one dates the APK by its marker. */
    val artifact: String,
    /** The lowest simulated API level a build serves. */
    val minSdk: Int = 0,
    /** The first version that no longer loads the library off Android, so needs none. */
    val fixedIn: String? = null,
    /** System properties the library needs to find its build; `{dir}` is the build's directory. */
    val properties: Map<String, String> = emptyMap(),
    val builds: List<Build> = emptyList(),
  ) {
    val versionMarker: String
      get() = "META-INF/${artifact.replace(':', '_')}.version"

    val label: String
      get() = library

    private val regex: Regex? by lazy { libraryPattern?.let(::Regex) }

    /**
     * Whether [loadName] is this library's: null when not; otherwise the version its name carries
     * ("" when the name carries none).
     */
    fun match(loadName: String): String? =
      when (val pattern = regex) {
        null -> "".takeIf { loadName == library }
        else -> pattern.matchEntire(loadName)?.groupValues?.getOrNull(1)
      }
  }

  @Serializable
  internal data class Build(
    val version: String,
    val platforms: Map<String, Entry>,
    /** GNU build IDs of this version's Android libraries, every ABI: they name it exactly. */
    val androidBuildIds: List<String> = emptyList(),
  )

  /**
   * One platform's archive: its [url] and [sha256], the library at [entry], and [files] the library
   * needs beside it (filament-jni's ANGLE `libEGL`/`libGLESv2` on macOS), extracted into the same
   * directory under their own names. Only from a `.tar.gz`.
   */
  @Serializable
  internal data class Entry(
    val url: String,
    val sha256: String,
    val entry: String,
    val files: List<String> = emptyList(),
  )

  /** How a [Choice] was matched to the APK, best first. */
  internal enum class Match {
    /** The APK's library has one of the build's Android build IDs: the same binary. */
    BUILD_ID,
    /** The APK's version (marker or library name) is the build's. */
    VERSION,
    /** The nearest version, or the newest when the APK's is unknown: may not match. */
    NEAREST,
  }

  /** One library to provide: [build]'s [entry] for the APK library named [loadName]. */
  internal data class Choice(
    val library: Library,
    val loadName: String,
    val build: Build,
    val entry: Entry,
    val apkVersion: String?,
    val match: Match,
  )

  /**
   * Platform token from `os.name` / `os.arch`, as the index keys builds. Null for a host no build
   * can target. Pure for testing.
   */
  internal fun platformToken(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch =
      when (osArch.lowercase()) {
        "x86_64",
        "amd64" -> "x86_64"
        "aarch64",
        "arm64" -> "arm64"
        else -> return null
      }
    return when {
      os.contains("linux") -> "linux-$arch"
      os.contains("mac") || os.contains("darwin") -> "macos-$arch"
      os.contains("windows") && arch == "x86_64" -> "windows-x86_64"
      else -> null
    }
  }

  internal fun currentPlatformToken(): String? =
    platformToken(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")

  /** `System.mapLibraryName` for [platform]: what the app's `loadLibrary(name)` looks for. */
  internal fun fileName(library: String, platform: String): String =
    when {
      platform.startsWith("macos") -> "lib$library.dylib"
      platform.startsWith("windows") -> "$library.dll"
      else -> "lib$library.so"
    }

  /**
   * The build of [builds] for an APK at [version]: the exact one, else the newest older one, else
   * the oldest newer one; the newest when the version is unknown. Null when there are none.
   */
  internal fun chooseBuild(builds: List<Build>, version: String?): Build? {
    if (builds.isEmpty()) return null
    val sorted = builds.sortedWith { a, b -> compareVersions(a.version, b.version) }
    if (version == null) return sorted.last()
    sorted
      .firstOrNull { it.version == version }
      ?.let {
        return it
      }
    return sorted.lastOrNull { compareVersions(it.version, version) < 0 }
      ?: sorted.firstOrNull { compareVersions(it.version, version) > 0 }
  }

  /**
   * Orders `1.2.3[-suffix]`: a pre-release suffix (`-alpha03`, `-rc01`) before its release, a
   * numeric one (zstd-jni's `1.5.7-9`) after it, suffixes part by part with numbers as numbers.
   * Mirrors `host-natives/index.py`'s `version_key`.
   */
  internal fun compareVersions(a: String, b: String): Int {
    fun key(v: String): Triple<List<Int>, Int, List<String>> {
      val head = v.substringBefore('-')
      val suffix = v.substringAfter('-', "")
      val nums =
        head
          .split('.')
          .map { it.toIntOrNull() ?: 0 }
          .let { it + List(4 - it.size.coerceAtMost(4)) { 0 } }
      val rank =
        when {
          suffix.isEmpty() -> 1
          suffix.first().isDigit() -> 2
          else -> 0
        }
      return Triple(nums, rank, suffix.split('.', '-').filter { it.isNotEmpty() })
    }
    val (an, ar, ap) = key(a)
    val (bn, br, bp) = key(b)
    for (i in 0 until maxOf(an.size, bn.size)) {
      val c = an.getOrElse(i) { 0 }.compareTo(bn.getOrElse(i) { 0 })
      if (c != 0) return c
    }
    if (ar != br) return ar.compareTo(br)
    for (i in 0 until maxOf(ap.size, bp.size)) {
      val x = ap.getOrNull(i) ?: return -1
      val y = bp.getOrNull(i) ?: return 1
      val xn = x.toIntOrNull()
      val yn = y.toIntOrNull()
      val c =
        when {
          xn != null && yn != null -> xn.compareTo(yn)
          xn != null -> -1
          yn != null -> 1
          else -> x.compareTo(y)
        }
      if (c != 0) return c
    }
    return 0
  }

  /**
   * What an APK carries: its native libraries by load name, each with the GNU build IDs of its
   * copies (one per ABI), and its AndroidX version markers.
   */
  internal data class ApkNatives(
    val buildIds: Map<String, Set<String>>,
    val versions: Map<String, String>,
  ) {
    val libraries: Set<String>
      get() = buildIds.keys
  }

  /** How much of a library is read for its build ID: its notes sit just after the headers. */
  private const val ELF_PREFIX_BYTES = 256 * 1024

  internal fun readApk(apk: File): ApkNatives? = runCatching {
    ZipFile(apk).use { zip ->
      val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
      val buildIds = mutableMapOf<String, MutableSet<String>>()
      for (entry in entries) {
        val name = entry.name
        if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
        val load = name.substringAfterLast('/').removePrefix("lib").removeSuffix(".so")
        val ids = buildIds.getOrPut(load) { mutableSetOf() }
        val prefix = zip.getInputStream(entry).use { it.readNBytes(ELF_PREFIX_BYTES) }
        elfBuildId(prefix)?.let(ids::add)
      }
      val versions =
        entries
          .filter { it.name.startsWith("META-INF/") && it.name.endsWith(".version") }
          .associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes().decodeToString().trim() }
          }
      ApkNatives(buildIds, versions)
    }
  }
    .getOrNull()

  /** The GNU build ID (`NT_GNU_BUILD_ID` in a `PT_NOTE`) of an ELF file's first bytes, or null. */
  internal fun elfBuildId(elf: ByteArray): String? {
    if (elf.size < 64 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte()) return null
    val is64 = elf[4] == 2.toByte()
    val order =
      if (elf[5] == 1.toByte()) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN
    val buffer = java.nio.ByteBuffer.wrap(elf).order(order)
    fun word(at: Int): Long = buffer.getInt(at).toLong() and 0xffffffffL
    fun addr(at: Int): Long = if (is64) buffer.getLong(at) else word(at)
    return runCatching {
      val phoff = addr(if (is64) 0x20 else 0x1C).toInt()
      val phentsize = buffer.getShort(if (is64) 0x36 else 0x2A).toInt() and 0xffff
      val phnum = buffer.getShort(if (is64) 0x38 else 0x2C).toInt() and 0xffff
      for (i in 0 until phnum) {
        val header = phoff + i * phentsize
        if (word(header) != 4L) continue // PT_NOTE
        val offset = addr(header + if (is64) 8 else 4).toInt()
        val size = addr(header + if (is64) 32 else 16).toInt()
        var pos = offset
        val end = minOf(offset + size, elf.size)
        while (pos + 12 <= end) {
          val nameSize = word(pos).toInt()
          val descSize = word(pos + 4).toInt()
          val type = word(pos + 8)
          val desc = pos + 12 + ((nameSize + 3) and 3.inv())
          if (
            type == 3L &&
              nameSize >= 3 &&
              String(elf, pos + 12, 3, Charsets.US_ASCII) == "GNU" &&
              desc + descSize <= elf.size
          ) {
            return@runCatching elf.copyOfRange(desc, desc + descSize).joinToString("") {
              "%02x".format(it)
            }
          }
          pos = desc + ((descSize + 3) and 3.inv())
        }
      }
      null
    }
      .getOrNull()
  }

  /**
   * What [apk] at [sdk] gets on [platform] from [index]: one [Choice] per library it carries that
   * the index has a build of. The build is the one sharing a build ID with the APK's copy; else the
   * one at the APK's version (its AndroidX marker, or the version in the library's name); else the
   * nearest, or the newest when the version is unknown, logged as possibly not matching. Pure.
   */
  internal fun plan(
    apk: ApkNatives,
    sdk: Int,
    index: Index,
    platform: String,
    log: (String) -> Unit = {},
  ): List<Choice> =
    index.libraries.flatMap { library ->
      if (sdk < library.minSdk) return@flatMap emptyList()
      apk.buildIds.mapNotNull { (loadName, ids) ->
        val named = library.match(loadName) ?: return@mapNotNull null
        val version = apk.versions[library.versionMarker] ?: named.ifEmpty { null }
        if (version != null && library.fixedIn != null) {
          if (compareVersions(version, library.fixedIn) >= 0) return@mapNotNull null
        }
        val candidates = library.builds.filter { platform in it.platforms }
        val byId = candidates.firstOrNull { build -> build.androidBuildIds.any { it in ids } }
        val build = byId ?: chooseBuild(candidates, version) ?: return@mapNotNull null
        val match =
          when {
            byId != null -> Match.BUILD_ID
            version == build.version -> Match.VERSION
            else -> Match.NEAREST
          }
        if (match == Match.NEAREST) {
          log(
            "host-natives: no $loadName build for ${library.artifact} " +
              "${version ?: "(version unknown)"} on $platform; using ${build.version}'s, which " +
              "may not match",
          )
        }
        Choice(library, loadName, build, build.platforms.getValue(platform), version, match)
      }
    }

  /** `${XDG_CACHE_HOME:-~/.cache}/composeai/host-natives`. */
  internal fun cacheRoot(
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
  ): File {
    val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) File(xdg) else File(userHome, ".cache")
    return File(base, "composeai/host-natives")
  }

  /** Fetch seam for tests. [fetchTo] downloads [url], throwing on non-2xx or network errors. */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  internal val defaultFetcher = Fetcher { url, dest ->
    ktorOkHttpClient().use { client ->
      runBlocking {
        client.prepareGet(url).execute { response ->
          if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
          dest.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
        }
      }
    }
  }

  private val json = Json { ignoreUnknownKeys = true }

  internal fun parseIndex(text: String): Index =
    json.decodeFromString(Index.serializer(), text).also {
      require(it.schema == SCHEMA) { "unknown index schema ${it.schema}" }
    }

  /**
   * The index: [INDEX_ENV]'s file as is; otherwise the cached copy while it is younger than
   * [INDEX_MAX_AGE_MS] (or [refresh] is false and the network is off), else a fresh fetch, falling
   * back to a stale copy. Null, with a note, when there is none.
   */
  internal fun loadIndex(
    root: File,
    fetcher: Fetcher,
    env: (String) -> String?,
    noNetwork: Boolean,
    log: (String) -> Unit,
    now: Long = System.currentTimeMillis(),
    refresh: Boolean = false,
  ): Index? {
    val location = env(INDEX_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_INDEX_URL
    if (!location.startsWith("https://")) {
      return runCatching { parseIndex(File(location).readText()) }
        .onFailure { log("host-natives: could not read the index $location (${it.message})") }
        .getOrNull()
    }
    // Keyed by source, so switching INDEX_ENV never serves another index's cached copy.
    val cached = File(root, "index-${sha256(location.toByteArray()).take(16)}.json")
    val fresh = cached.isFile && now - cached.lastModified() < INDEX_MAX_AGE_MS
    if ((fresh && !refresh) || (noNetwork && cached.isFile)) {
      runCatching { parseIndex(cached.readText()) }
        .getOrNull()
        ?.let {
          return it
        }
    }
    if (noNetwork) {
      log(
        "host-natives: the network is off and no index is cached; skipping. Pre-provision with " +
          "`compose-preview provision --host-natives`.",
      )
      return null
    }
    val tmp = File(root, ".index-${UUID.randomUUID()}.json")
    return try {
      root.mkdirs()
      fetcher.fetchTo(location, tmp)
      val index = parseIndex(tmp.readText())
      Files.move(tmp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
      index
    } catch (e: Exception) {
      val stale = runCatching { parseIndex(cached.readText()) }.getOrNull()
      log(
        "host-natives: could not fetch the index $location (${e.message})" +
          if (stale != null) "; using the cached copy" else "; skipping",
      )
      stale
    } finally {
      tmp.delete()
    }
  }

  /**
   * The directory holding [choice]'s library under its load name, downloading and checking it when
   * absent. `<root>/files/<sha256>/<platform>/<file>`, so builds never collide. Null, with a note,
   * on any failure.
   */
  internal fun ensureFile(
    root: File,
    choice: Choice,
    platform: String,
    fetcher: Fetcher,
    noNetwork: Boolean,
    log: (String) -> Unit,
  ): File? {
    val name = choice.loadName
    val dir = File(root, "files/${choice.entry.sha256}/$platform")
    val target = File(dir, fileName(name, platform))
    // The files beside it too: a CLI from before `files` cached the library alone, and that cache
    // must be filled in rather than accepted.
    val besides = choice.entry.files.map { File(dir, it.substringAfterLast('/')) }
    if (target.isFile && besides.all { it.isFile }) return dir
    val url = choice.entry.url
    if (noNetwork) {
      log(
        "host-natives: the network is off and $name ${choice.build.version} is not cached; " +
          "skipping. Pre-provision with `compose-preview provision --host-natives`.",
      )
      return null
    }
    val tmp = File(root, ".dl-${UUID.randomUUID()}")
    val stage = File(root, ".stage-${UUID.randomUUID()}")
    return try {
      root.mkdirs()
      fetcher.fetchTo(url, tmp)
      val sha = sha256(tmp)
      check(sha == choice.entry.sha256) { "sha256 $sha, the index says ${choice.entry.sha256}" }
      val extracted = extract(tmp, url, choice.entry.entry, stage)
      dir.mkdirs()
      // The files beside it first: the library itself arriving is what marks the build provisioned.
      for (name in choice.entry.files) {
        val file = File(stage, name).takeIf { it.isFile } ?: error("$url has no $name")
        Files.move(
          file.toPath(),
          File(dir, name.substringAfterLast('/')).toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
      try {
        Files.move(extracted.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        Files.move(extracted.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      log("host-natives: provisioned $name ${choice.build.version} for $platform into $dir")
      dir
    } catch (e: Exception) {
      log("host-natives: could not provision $name from $url (${e.message}); skipping")
      null
    } finally {
      tmp.delete()
      stage.deleteRecursively()
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Extracts [entry] of the archive [file] (a `.tar.gz`, or a zip/jar) into [stage]. */
  internal fun extract(file: File, url: String, entry: String, stage: File): File {
    stage.mkdirs()
    if (url.endsWith(".tar.gz") || url.endsWith(".tgz")) {
      XrCompositeProvision.unpackTarGz(file, stage)
      return File(stage, entry).takeIf { it.isFile } ?: error("$url has no $entry")
    }
    val out = File(stage, entry.substringAfterLast('/'))
    ZipFile(file).use { zip ->
      val zipEntry = zip.getEntry(entry) ?: error("$url has no $entry")
      zip.getInputStream(zipEntry).use { input -> out.outputStream().use { input.copyTo(it) } }
    }
    return out
  }

  /** What a launch adds for an APK: library directories, and the libraries' system properties. */
  data class Resolution(val dirs: List<File>, val properties: Map<String, String>) {
    companion object {
      val NONE = Resolution(emptyList(), emptyMap())
    }
  }

  /**
   * The host builds for [apk] at [sdk]: one directory per library it carries that the index has a
   * build of, for the simulator's `java.library.path`, and the system properties those libraries
   * declare (`{dir}` filled in). [Resolution.NONE] when there are none or nothing could be
   * provisioned.
   */
  fun resolve(
    apk: File,
    sdk: Int,
    fetcher: Fetcher = defaultFetcher,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
    log: (String) -> Unit = { System.err.println(it) },
    // `offline`, not `disabled`: the softer COMPOSE_PREVIEW_OFFLINE=1 that `provision` prints
    // must also keep a launch to its cache (a stale index included) rather than fetch.
    noNetwork: Boolean = NetworkPolicy.offline,
    platform: String? = currentPlatformToken(),
  ): Resolution {
    val natives = readApk(apk) ?: return Resolution.NONE
    // An APK with no native code needs no index, so no network.
    if (natives.libraries.isEmpty() || platform == null) return Resolution.NONE
    val root = cacheRoot(env, userHome)
    val index = loadIndex(root, fetcher, env, noNetwork, log) ?: return Resolution.NONE
    val dirs = mutableListOf<File>()
    val properties = linkedMapOf<String, String>()
    for (choice in plan(natives, sdk, index, platform, log)) {
      val dir = ensureFile(root, choice, platform, fetcher, noNetwork, log) ?: continue
      if (dir !in dirs) dirs += dir
      for ((key, value) in choice.library.properties) {
        properties[key] = value.replace("{dir}", dir.absolutePath)
      }
    }
    return Resolution(dirs, properties)
  }

  /** The load name a build's [entry] provides: the library's, else its file's (a pattern's). */
  internal fun loadNameOf(library: Library, entry: Entry): String =
    if (library.libraryPattern == null) library.library
    else entry.entry.substringAfterLast('/').removePrefix("lib").substringBeforeLast('.')

  /**
   * For `provision --host-natives`: a fresh index, and for this host either what each of [apks]
   * needs (at every API level a launch could use) or, with none named, each library's newest build,
   * so a later offline launch finds its libraries. The cache root, or null when the index could not
   * be fetched or a build failed.
   */
  fun provisionAll(
    apks: List<File> = emptyList(),
    fetcher: Fetcher = defaultFetcher,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
    log: (String) -> Unit = { System.err.println(it) },
    noNetwork: Boolean = NetworkPolicy.disabled,
    platform: String? = currentPlatformToken(),
  ): File? {
    if (platform == null) {
      log("host-natives: no host builds target this platform; nothing to provision")
      return null
    }
    val root = cacheRoot(env, userHome)
    val index = loadIndex(root, fetcher, env, noNetwork, log, refresh = true) ?: return null
    val choices =
      if (apks.isNotEmpty()) {
        apks.flatMap { apk ->
          val natives = readApk(apk) ?: error("cannot read $apk")
          plan(natives, Int.MAX_VALUE, index, platform, log)
        }
      } else {
        index.libraries.mapNotNull { library ->
          val build =
            chooseBuild(library.builds.filter { platform in it.platforms }, null)
              ?: return@mapNotNull null
          val entry = build.platforms.getValue(platform)
          Choice(library, loadNameOf(library, entry), build, entry, build.version, Match.VERSION)
        }
      }
    var ok = true
    for (choice in choices.distinctBy { it.entry.sha256 to it.loadName }) {
      if (ensureFile(root, choice, platform, fetcher, noNetwork, log) == null) ok = false
    }
    return root.takeIf { ok }
  }
}
