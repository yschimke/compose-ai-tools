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

  /** How long a fetched index is used before it is fetched again. */
  internal const val INDEX_MAX_AGE_MS = 24L * 60 * 60 * 1000

  internal const val SCHEMA = "compose-preview-host-natives/v1"

  @Serializable internal data class Index(val schema: String, val libraries: List<Library>)

  @Serializable
  internal data class Library(
    /** The name the app passes to `System.loadLibrary`. */
    val library: String,
    /** `group:name` of the Android artifact, whose `META-INF/<group>_<name>.version` dates it. */
    val artifact: String,
    /** The lowest simulated API level a build serves. */
    val minSdk: Int = 0,
    /** The first version that no longer loads the library off Android, so needs none. */
    val fixedIn: String? = null,
    val builds: List<Build> = emptyList(),
  ) {
    val versionMarker: String
      get() = "META-INF/${artifact.replace(':', '_')}.version"
  }

  @Serializable internal data class Build(val version: String, val platforms: Map<String, Entry>)

  @Serializable internal data class Entry(val url: String, val sha256: String, val entry: String)

  /** One library to provide: [build]'s [entry], chosen for an APK at [apkVersion]. */
  internal data class Choice(
    val library: Library,
    val build: Build,
    val entry: Entry,
    val apkVersion: String?,
  ) {
    val exact: Boolean
      get() = apkVersion == build.version
  }

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
    val sorted = builds.sortedWith { a, b -> compareSemver(a.version, b.version) }
    if (version == null) return sorted.last()
    sorted
      .firstOrNull { it.version == version }
      ?.let {
        return it
      }
    return sorted.lastOrNull { compareSemver(it.version, version) < 0 }
      ?: sorted.firstOrNull { compareSemver(it.version, version) > 0 }
  }

  /** The native library names an APK carries, and the AndroidX version markers it has. */
  internal data class ApkNatives(val libraries: Set<String>, val versions: Map<String, String>)

  internal fun readApk(apk: File): ApkNatives? = runCatching {
    ZipFile(apk).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toList()
      val libraries =
        names
          .filter { it.startsWith("lib/") && it.endsWith(".so") }
          .map { it.substringAfterLast('/').removePrefix("lib").removeSuffix(".so") }
          .toSet()
      val versions =
        names
          .filter { it.startsWith("META-INF/") && it.endsWith(".version") }
          .associateWith { name ->
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes().decodeToString().trim() }
          }
      ApkNatives(libraries, versions)
    }
  }
    .getOrNull()

  /**
   * What [apk] at [sdk] gets on [platform] from [index]: one [Choice] per library it carries that
   * the index has a build of. Pure for testing, and logs each inexact match.
   */
  internal fun plan(
    apk: ApkNatives,
    sdk: Int,
    index: Index,
    platform: String,
    log: (String) -> Unit = {},
  ): List<Choice> =
    index.libraries.mapNotNull { library ->
      if (library.library !in apk.libraries || sdk < library.minSdk) return@mapNotNull null
      val version = apk.versions[library.versionMarker]
      if (version != null && library.fixedIn != null) {
        if (compareSemver(version, library.fixedIn) >= 0) return@mapNotNull null
      }
      val build =
        chooseBuild(library.builds.filter { platform in it.platforms }, version)
          ?: return@mapNotNull null
      Choice(library, build, build.platforms.getValue(platform), version).also {
        if (!it.exact) {
          log(
            "host-natives: no ${library.library} build for ${library.artifact} " +
              "${version ?: "(version unknown)"} on $platform; using ${build.version}'s, which " +
              "may not match"
          )
        }
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
          "`compose-preview provision --host-natives`."
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
          if (stale != null) "; using the cached copy" else "; skipping"
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
    val name = choice.library.library
    val dir = File(root, "files/${choice.entry.sha256}/$platform")
    val target = File(dir, fileName(name, platform))
    if (target.isFile) return dir
    val url = choice.entry.url
    if (noNetwork) {
      log(
        "host-natives: the network is off and $name ${choice.build.version} is not cached; " +
          "skipping. Pre-provision with `compose-preview provision --host-natives`."
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

  /**
   * The directories to put on the simulator's `java.library.path` for [apk] at [sdk]: one per
   * library it carries that the index has a build of. Empty when there are none or nothing could be
   * provisioned.
   */
  fun libraryDirs(
    apk: File,
    sdk: Int,
    fetcher: Fetcher = defaultFetcher,
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
    log: (String) -> Unit = { System.err.println(it) },
    noNetwork: Boolean = NetworkPolicy.disabled,
    platform: String? = currentPlatformToken(),
  ): List<File> {
    val natives = readApk(apk) ?: return emptyList()
    // An APK with no native code needs no index, so no network.
    if (natives.libraries.isEmpty() || platform == null) return emptyList()
    val root = cacheRoot(env, userHome)
    val index = loadIndex(root, fetcher, env, noNetwork, log) ?: return emptyList()
    return plan(natives, sdk, index, platform, log).mapNotNull { choice ->
      ensureFile(root, choice, platform, fetcher, noNetwork, log)
    }
  }

  /**
   * For `provision --host-natives`: a fresh index, and every build it lists for this host, so a
   * later offline launch finds whichever one its APK needs. The cache root, or null when the index
   * could not be fetched or a build failed.
   */
  fun provisionAll(
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
    var ok = true
    for (library in index.libraries) {
      for (build in library.builds) {
        val entry = build.platforms[platform] ?: continue
        val choice = Choice(library, build, entry, build.version)
        if (ensureFile(root, choice, platform, fetcher, noNetwork, log) == null) ok = false
      }
    }
    return root.takeIf { ok }
  }
}
