package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Fetches the `android-all-instrumented` jar Robolectric loads as the Android framework for an SDK
 * level, into a directory [ROBOLECTRIC_DEPS_DIR_ENV] can name. With it set, the Android daemon runs
 * Robolectric offline (`robolectric.offline=true`, `robolectric.dependency.dir`), and Robolectric's
 * `LocalDependencyResolver` then looks for exactly `<artifactId>-<version>[-<classifier>].jar`
 * there.
 *
 * **Which artifact** is Robolectric's own answer, not a table here: the Robolectric jar on the
 * Android daemon sidecar's classpath is loaded in an isolated class loader and its
 * `DefaultSdkProvider` asked for the level's `DependencyJar`, the coordinate a render on that
 * sidecar requests. A Robolectric bump in the daemon therefore moves this with it.
 */
internal object RobolectricDepsProvision {
  internal const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

  /**
   * Google's mirror of Maven Central. Cloud sandboxes share a few egress addresses, and Central
   * rate-limits per address (HTTP 429) where the mirror has far more headroom; Central stays behind
   * it for anything published too recently to be mirrored yet.
   */
  internal const val CENTRAL_MIRROR = "https://maven-central.storage-download.googleapis.com/maven2"

  /** Where [ensure] looks, in order. */
  internal val REPOSITORIES = listOf(CENTRAL_MIRROR, MAVEN_CENTRAL)

  /** One Maven artifact Robolectric resolves, named as its `DependencyJar` names it. */
  data class Artifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val classifier: String? = null,
    val type: String = "jar",
  ) {
    /** The name `LocalDependencyResolver` looks up in `robolectric.dependency.dir`. */
    val fileName: String
      get() = "$artifactId-$version${classifier?.let { "-$it" }.orEmpty()}.$type"

    fun url(repository: String = MAVEN_CENTRAL): String =
      "$repository/${groupId.replace('.', '/')}/$artifactId/$version/$fileName"
  }

  /** What [artifactFor] found: the artifact, and the JDK Robolectric needs to run that level. */
  data class Resolved(val apiLevel: Int, val artifact: Artifact, val requiredJava: Int)

  /** Fetch seam, faked in tests. Downloads [url] to [dest], throwing on a non-2xx. */
  fun interface Fetcher {
    fun fetchTo(url: String, dest: File)
  }

  private val defaultFetcher = Fetcher { url, dest -> ServerDistributionProvision.fetch(url, dest) }

  /** `${XDG_CACHE_HOME:-~/.cache}/composeai/robolectric-deps`. */
  fun defaultDir(): File = composeAiCacheDir("robolectric-deps")

  /**
   * The `android-all` artifact the Robolectric in [sidecarJars] requests for [apiLevel].
   *
   * Robolectric refuses (`UnsupportedClassVersionError`) to name the artifact of a level that needs
   * a newer JDK than this one; the artifact is still wanted, since the render may run on another
   * JDK, so it is then rebuilt from the same `DefaultSdk` fields the refused method joins.
   */
  fun artifactFor(sidecarJars: List<File>, apiLevel: Int): Resolved =
    robolectricLoader(sidecarJars).use { artifactFor(it, apiLevel) }

  /**
   * The highest SDK level from [requested] down to [floor] whose `android-all` artifact, as the
   * Robolectric in [sidecarJars] names it, is in [directory]; null when there is none.
   *
   * An offline render can only run on a level whose framework jar was provisioned, and a build's
   * compileSdk can be newer than any `android-all` published for the daemon's Robolectric (a
   * preview platform installed on the build machine), so the render settles for the newest one it
   * has, as it does for a JDK too old for the requested level.
   */
  fun highestProvisionedLevel(
    sidecarJars: List<File>,
    directory: File,
    requested: Int,
    floor: Int,
  ): Int? =
    robolectricLoader(sidecarJars).use { loader ->
      (requested downTo floor).firstOrNull { level ->
        val artifact =
          runCatching { artifactFor(loader, level).artifact }.getOrNull()
            ?: return@firstOrNull false
        File(directory, artifact.fileName).isFile
      }
    }

  private fun robolectricLoader(sidecarJars: List<File>): URLClassLoader {
    val robolectric =
      sidecarJars.filter { it.isFile && it.extension == "jar" }.takeIf { it.isNotEmpty() }
        ?: error("no jars to find Robolectric in")
    return URLClassLoader(
      robolectric.map { it.toURI().toURL() }.toTypedArray(),
      ClassLoader.getPlatformClassLoader(),
    )
  }

  private fun artifactFor(loader: ClassLoader, apiLevel: Int): Resolved {
    run {
      val sdk = defaultSdk(loader, apiLevel)
      val required = sdk.field("requiredJavaVersion") as Int
      val dependency =
        try {
          sdk.javaClass.getDeclaredMethod("getAndroidSdkDependency").let {
            it.isAccessible = true
            it.invoke(sdk)
          }
        } catch (e: InvocationTargetException) {
          if (e.cause !is UnsupportedClassVersionError) throw e.cause ?: e
          null
        }
      val artifact =
        if (dependency != null) {
          fun get(name: String) = dependency.javaClass.getMethod(name).invoke(dependency) as String?
          Artifact(
            groupId = get("getGroupId")!!,
            artifactId = get("getArtifactId")!!,
            version = get("getVersion")!!,
            classifier = get("getClassifier"),
            type = get("getType") ?: "jar",
          )
        } else preinstrumentedFromFields(sdk)
      return Resolved(apiLevel, artifact, required)
    }
  }

  /**
   * The artifact `DefaultSdk.getAndroidSdkDependency` would return with pre-instrumented jars on
   * (Robolectric's default): `<androidVersion>-robolectric-<robolectricVersion>-i<N>`.
   */
  internal fun preinstrumentedFromFields(sdk: Any): Artifact {
    val provider = sdk.javaClass.enclosingClass
    val instrumentation =
      provider.getDeclaredField("PREINSTRUMENTED_VERSION").let {
        it.isAccessible = true
        it.getInt(null)
      }
    return Artifact(
      groupId = "org.robolectric",
      artifactId = "android-all-instrumented",
      version =
        listOf(
            sdk.field("androidVersion"),
            "robolectric",
            sdk.field("robolectricVersion"),
            "i$instrumentation",
          )
          .joinToString("-"),
    )
  }

  /** The `DefaultSdk` for [apiLevel] from a `DefaultSdkProvider` that is never asked to resolve. */
  internal fun defaultSdk(loader: ClassLoader, apiLevel: Int): Any {
    val resolverType = loader.loadClass("org.robolectric.internal.dependency.DependencyResolver")
    val resolver =
      Proxy.newProxyInstance(loader, arrayOf(resolverType)) { proxy, method, args ->
        when (method.name) {
          "toString" -> "compose-preview provision"
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          else -> error("provisioning resolves nothing through Robolectric (${method.name})")
        }
      }
    val providerType = loader.loadClass("org.robolectric.plugins.DefaultSdkProvider")
    val provider = providerType.getConstructor(resolverType).newInstance(resolver)
    val sdks = providerType.getMethod("getSdks").invoke(provider) as Collection<*>
    val levels =
      sdks.filterNotNull().associateBy { it.javaClass.getMethod("getApiLevel").invoke(it) }
    return levels[apiLevel]
      ?: error("Robolectric knows no SDK $apiLevel; it ships ${levels.keys.joinToString(", ")}")
  }

  private fun Any.field(name: String): Any? =
    javaClass.getDeclaredField(name).let {
      it.isAccessible = true
      it.get(this)
    }

  /**
   * Ensure [directory] holds [artifact] under [Artifact.fileName], fetching it from the first of
   * [repositories] that serves it and verifying it against the strongest checksum that same
   * repository publishes (`.sha512`, `.sha256`, `.sha1`). A file already there is trusted, as
   * Robolectric trusts it: one only ever appears there whole, by an atomic rename. Throws with the
   * reason when it cannot.
   *
   * Concurrent callers, in this JVM or another (`launch --pair` starts two, and several agents can
   * share a host), take a lock on `.<fileName>.lock` beside it for the one artifact, so one fetches
   * while the rest wait and then find it; a different artifact is not held up. The lock is an OS
   * file lock, released when its holder dies, and its file is never deleted, since deleting a lock
   * file another process is waiting on lets a third lock a new one beside it. A fetch a killed
   * process left half done is a `.tmp` file nothing reads, and the next holder removes it.
   *
   * With [seed] (Robolectric's own copy in its Maven-local repository, [mavenLocalCopy]) the jar is
   * linked or copied from there instead of fetched, once it matches a checksum: the one Robolectric
   * stored beside it, else the one the first of [repositories] publishes.
   */
  fun ensure(
    artifact: Artifact,
    directory: File = defaultDir(),
    repositories: List<String> = REPOSITORIES,
    fetcher: Fetcher = defaultFetcher,
    attempts: Int = 3,
    pause: (attempt: Int) -> Unit = { Thread.sleep(2_000L * it) },
    log: (String) -> Unit = { System.err.println(it) },
    seed: File? = null,
  ): File {
    val target = File(directory, artifact.fileName)
    if (target.isFile && target.length() > 0) return target
    directory.mkdirs()
    return withArtifactLock(directory, artifact) {
      if (target.isFile && target.length() > 0) return@withArtifactLock target
      removeAbandoned(directory, artifact)
      val temporary = File(directory, ".${artifact.fileName}.${UUID.randomUUID()}.tmp")
      try {
        val firstUrl = artifact.url(repositories.first())
        if (
          seed != null && seedFrom(seed, temporary, firstUrl, directory, fetcher, attempts, pause)
        ) {
          log("compose-preview: ${artifact.fileName} from ${seed.parentFile}")
        } else {
          var failure: Exception? = null
          for (repository in repositories) {
            val url = artifact.url(repository)
            try {
              fetchVerified(artifact, url, temporary, directory, fetcher, attempts, pause, log)
              failure = null
              break
            } catch (e: Exception) {
              if (e is NetworkDisabledException) throw e
              temporary.delete()
              failure = e
              log("compose-preview: could not fetch ${artifact.fileName} from $url (${e.message})")
            }
          }
          failure?.let { throw it }
        }
        try {
          Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
          Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target
      } finally {
        temporary.delete()
      }
    }
  }

  /**
   * Where Robolectric's `MavenDependencyResolver` keeps [artifact] when it fetched it itself:
   * `maven.repo.local`, else `~/.m2/repository`. Null when it is not there.
   */
  fun mavenLocalCopy(
    artifact: Artifact,
    repository: File =
      System.getProperty("maven.repo.local")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".m2/repository"),
  ): File? =
    File(
        repository,
        "${artifact.groupId.replace('.', '/')}/${artifact.artifactId}/${artifact.version}/" +
          artifact.fileName,
      )
      .takeIf { it.isFile && it.length() > 0 }

  /** In-JVM guard: two threads of one JVM asking for one file lock throw rather than wait. */
  private val inProcess = java.util.concurrent.ConcurrentHashMap<String, Any>()

  private fun <T> withArtifactLock(directory: File, artifact: Artifact, block: () -> T): T {
    val lockFile = File(directory, ".${artifact.fileName}.lock")
    synchronized(inProcess.computeIfAbsent(lockFile.absolutePath) { Any() }) {
      RandomAccessFile(lockFile, "rw").use { file ->
        file.channel.lock().use {
          return block()
        }
      }
    }
  }

  /** `.tmp` files of [artifact] a fetch left behind when its process died; the lock is ours. */
  private fun removeAbandoned(directory: File, artifact: Artifact) {
    directory
      .listFiles { file ->
        file.name.startsWith(".${artifact.fileName}.") && file.name.endsWith(".tmp")
      }
      ?.forEach { it.delete() }
  }

  /**
   * Links (or, across file systems, copies) [seed] to [temporary] when it matches a checksum:
   * Robolectric's own `.sha512` beside it (or a `.sha256` / `.sha1` Maven left), else [url]'s.
   * False, with [temporary] removed, when it does not or no checksum can be had.
   */
  /** Fetches [url] to [temporary] and checks it against the checksum published beside it. */
  private fun fetchVerified(
    artifact: Artifact,
    url: String,
    temporary: File,
    directory: File,
    fetcher: Fetcher,
    attempts: Int,
    pause: (Int) -> Unit,
    log: (String) -> Unit,
  ) {
    log("compose-preview: fetching ${artifact.fileName} from $url")
    retrying(attempts, pause) { fetcher.fetchTo(url, temporary) }
    val (algorithm, expected) =
      checksum(url, directory, fetcher, attempts, pause)
        ?: error("$url has no .sha512, .sha256 or .sha1 beside it")
    val actual = digest(temporary, algorithm)
    check(actual.equals(expected, ignoreCase = true)) {
      "$algorithm of ${artifact.fileName} is $actual, but $url.${extension(algorithm)} " +
        "says $expected"
    }
  }

  private fun seedFrom(
    seed: File,
    temporary: File,
    url: String,
    directory: File,
    fetcher: Fetcher,
    attempts: Int,
    pause: (Int) -> Unit,
  ): Boolean {
    val local = CHECKSUMS.firstNotNullOfOrNull { algorithm ->
      File(seed.path + "." + extension(algorithm))
        .takeIf { it.isFile }
        ?.let { hex(it.readText()) }
        ?.let { algorithm to it }
    }
    val (algorithm, expected) =
      local
        ?: runCatching { checksum(url, directory, fetcher, attempts, pause) }.getOrNull()
        ?: return false
    if (!digest(seed, algorithm).equals(expected, ignoreCase = true)) return false
    try {
      Files.createLink(temporary.toPath(), seed.toPath())
    } catch (_: Exception) {
      Files.copy(seed.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    return true
  }

  /** The hex digest in a checksum file, which may be followed by the file name. */
  private fun hex(text: String): String? =
    text.trim().substringBefore(' ').substringBefore('\t').trim().takeIf {
      it.isNotEmpty() && it.all(Char::isLetterOrDigit)
    }

  private val CHECKSUMS = listOf("SHA-512", "SHA-256", "SHA-1")

  private fun extension(algorithm: String) = algorithm.lowercase().replace("-", "")

  /** The first checksum published for [url], as (algorithm, hex). */
  private fun checksum(
    url: String,
    directory: File,
    fetcher: Fetcher,
    attempts: Int,
    pause: (Int) -> Unit,
  ): Pair<String, String>? {
    for (algorithm in CHECKSUMS) {
      val file = File(directory, ".checksum.${UUID.randomUUID()}.tmp")
      try {
        val text =
          runCatching {
            retrying(attempts, pause) { fetcher.fetchTo("$url.${extension(algorithm)}", file) }
            file.readText()
          }
            .getOrNull() ?: continue
        hex(text)?.let {
          return algorithm to it
        }
      } finally {
        file.delete()
      }
    }
    return null
  }

  /**
   * Runs [block] up to [attempts] times: Maven Central rate-limits (HTTP 429) bursts of large
   * downloads, and a retry after a pause is what it asks for. A 404, or the network switch, is
   * final.
   */
  private fun retrying(attempts: Int, pause: (Int) -> Unit, block: () -> Unit) {
    var attempt = 1
    while (true) {
      try {
        return block()
      } catch (e: Exception) {
        if (
          attempt >= attempts ||
            e is NetworkDisabledException ||
            e.message?.contains("HTTP 404") == true
        )
          throw e
        pause(attempt)
        attempt++
      }
    }
  }

  internal fun digest(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
