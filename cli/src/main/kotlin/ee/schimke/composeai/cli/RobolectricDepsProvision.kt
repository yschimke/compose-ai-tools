package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
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
  fun artifactFor(sidecarJars: List<File>, apiLevel: Int): Resolved {
    val robolectric =
      sidecarJars.filter { it.isFile && it.extension == "jar" }.takeIf { it.isNotEmpty() }
        ?: error("no jars to find Robolectric in")
    URLClassLoader(
        robolectric.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
      .use { loader ->
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
            fun get(name: String) =
              dependency.javaClass.getMethod(name).invoke(dependency) as String?
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
   * Ensure [directory] holds [artifact] under [Artifact.fileName], fetching and verifying it
   * against the strongest checksum Maven Central publishes (`.sha512`, `.sha256`, `.sha1`). A file
   * already there is trusted, as Robolectric trusts it. Throws with the reason when it cannot.
   */
  fun ensure(
    artifact: Artifact,
    directory: File = defaultDir(),
    repository: String = MAVEN_CENTRAL,
    fetcher: Fetcher = defaultFetcher,
    attempts: Int = 3,
    pause: (attempt: Int) -> Unit = { Thread.sleep(2_000L * it) },
    log: (String) -> Unit = { System.err.println(it) },
  ): File {
    val target = File(directory, artifact.fileName)
    if (target.isFile && target.length() > 0) return target
    directory.mkdirs()
    val url = artifact.url(repository)
    val temporary = File(directory, ".${artifact.fileName}.${UUID.randomUUID()}.tmp")
    try {
      log("compose-preview: fetching ${artifact.fileName} from $url")
      retrying(attempts, pause) { fetcher.fetchTo(url, temporary) }
      val (algorithm, expected) =
        checksum(url, directory, fetcher, attempts, pause)
          ?: error("Maven Central publishes no .sha512, .sha256 or .sha1 for $url")
      val actual = digest(temporary, algorithm)
      check(actual.equals(expected, ignoreCase = true)) {
        "$algorithm of ${artifact.fileName} is $actual, but $url.${extension(algorithm)} says $expected"
      }
      try {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      return target
    } finally {
      temporary.delete()
    }
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
        // The file is the hex digest, sometimes followed by the file name.
        val hex = text.trim().substringBefore(' ').substringBefore('\t').trim()
        if (hex.isNotEmpty() && hex.all { it.isLetterOrDigit() }) return algorithm to hex
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
