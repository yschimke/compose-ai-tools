package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.HostNativesProvision.Build
import ee.schimke.composeai.cli.HostNativesProvision.Entry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [HostNativesProvision], with the fetch seam faked: no network. */
class HostNativesProvisionTest {
  private val tmp = Files.createTempDirectory("host-natives-test-").toFile()
  private val cache = File(tmp, "cache")
  private val env: (String) -> String? = { if (it == "XDG_CACHE_HOME") cache.path else null }

  @AfterTest
  fun cleanup() {
    tmp.deleteRecursively()
  }

  @Test
  fun `platform tokens name os and architecture`() {
    assertEquals("linux-x86_64", HostNativesProvision.platformToken("Linux", "amd64"))
    assertEquals("linux-arm64", HostNativesProvision.platformToken("Linux", "aarch64"))
    assertEquals("macos-arm64", HostNativesProvision.platformToken("Mac OS X", "aarch64"))
    assertEquals("macos-x86_64", HostNativesProvision.platformToken("Mac OS X", "x86_64"))
    assertEquals("windows-x86_64", HostNativesProvision.platformToken("Windows 11", "amd64"))
    assertNull(HostNativesProvision.platformToken("Linux", "riscv64"))
    assertEquals("libsqliteJni.dylib", HostNativesProvision.fileName("sqliteJni", "macos-arm64"))
    assertEquals("sqliteJni.dll", HostNativesProvision.fileName("sqliteJni", "windows-x86_64"))
  }

  private fun build(version: String) = Build(version, emptyMap())

  @Test
  fun `the exact version wins, else the nearest older, else the nearest newer`() {
    val builds = listOf(build("2.5.0"), build("2.6.2"), build("2.7.1"))
    assertEquals("2.6.2", HostNativesProvision.chooseBuild(builds, "2.6.2")?.version)
    assertEquals("2.7.1", HostNativesProvision.chooseBuild(builds, "2.8.0-alpha03")?.version)
    assertEquals("2.6.2", HostNativesProvision.chooseBuild(builds, "2.7.0")?.version)
    assertEquals("2.5.0", HostNativesProvision.chooseBuild(builds, "2.4.0")?.version)
    assertEquals("2.7.1", HostNativesProvision.chooseBuild(builds, null)?.version)
    assertNull(HostNativesProvision.chooseBuild(emptyList(), "1.0.0"))
  }

  private val index =
    HostNativesProvision.Index(
      HostNativesProvision.SCHEMA,
      listOf(
        HostNativesProvision.Library(
          library = "androidx.graphics.path",
          artifact = "androidx.graphics:graphics-path",
          minSdk = 34,
          fixedIn = "1.1.0",
          builds =
            listOf(
              Build(
                "1.0.1",
                mapOf("linux-x86_64" to Entry("https://x/gp.tar.gz", "a".repeat(64), "lib.so")),
              ),
            ),
        ),
        HostNativesProvision.Library(
          library = "sqliteJni",
          artifact = "androidx.sqlite:sqlite-bundled",
          builds =
            listOf(
              Build(
                "2.6.2",
                mapOf("linux-x86_64" to Entry("https://x/s.jar", "b".repeat(64), "n/lib.so")),
              ),
              Build(
                "2.7.1",
                mapOf("macos-arm64" to Entry("https://x/s2.jar", "c".repeat(64), "n/lib.dylib")),
              ),
            ),
        ),
      ),
    )

  private fun natives(
    libraries: Set<String>,
    versions: Map<String, String> = emptyMap(),
    buildIds: Map<String, Set<String>> = emptyMap(),
  ) =
    HostNativesProvision.ApkNatives(
      libraries.associateWith { buildIds[it] ?: emptySet() },
      versions,
    )

  @Test
  fun `plan matches carried libraries, honours minSdk and fixedIn, and picks per platform`() {
    val logs = mutableListOf<String>()
    val apk =
      natives(
        setOf("androidx.graphics.path", "sqliteJni", "other"),
        mapOf(
          "META-INF/androidx.graphics_graphics-path.version" to "1.0.1",
          "META-INF/androidx.sqlite_sqlite-bundled.version" to "2.7.1",
        ),
      )
    val plan = HostNativesProvision.plan(apk, 35, index, "linux-x86_64") { logs += it }
    assertEquals(listOf("1.0.1", "2.6.2"), plan.map { it.build.version })
    // 2.7.1 has no Linux build, so the nearest that does, said out loud.
    assertTrue(logs.single().contains("using 2.6.2's"), logs.toString())
    // Below graphics-path's minSdk only sqlite remains.
    assertEquals(
      listOf("sqliteJni"),
      HostNativesProvision.plan(apk, 33, index, "linux-x86_64").map { it.loadName },
    )
    // graphics-path 1.1.0 loads nothing off Android.
    val fixed =
      natives(
        setOf("androidx.graphics.path"),
        mapOf("META-INF/androidx.graphics_graphics-path.version" to "1.1.0"),
      )
    assertEquals(emptyList(), HostNativesProvision.plan(fixed, 35, index, "linux-x86_64"))
  }

  @Test
  fun `a build ID names the exact build, a pattern carries the version, else the newest`() {
    val conscrypt =
      HostNativesProvision.Library(
        library = "conscrypt_jni",
        artifact = "org.conscrypt:conscrypt-android",
        builds =
          listOf(
            Build("2.7.0", linux("c1"), androidBuildIds = listOf("aaaa1111")),
            Build("2.8.0", linux("c2"), androidBuildIds = listOf("bbbb2222")),
          ),
      )
    val zstd =
      HostNativesProvision.Library(
        library = "zstd-jni",
        libraryPattern = "zstd-jni-(.+)",
        artifact = "com.github.luben:zstd-jni",
        builds = listOf(Build("1.5.7-9", linux("z1")), Build("1.5.7-25", linux("z2"))),
      )
    val idx = HostNativesProvision.Index(HostNativesProvision.SCHEMA, listOf(conscrypt, zstd))
    val logs = mutableListOf<String>()
    fun plan(apk: HostNativesProvision.ApkNatives) =
      HostNativesProvision.plan(apk, 35, idx, "linux-x86_64") { logs += it }

    // The older build, by the ID the APK's arm64 copy carries: no version needed.
    val byId =
      plan(natives(setOf("conscrypt_jni"), buildIds = mapOf("conscrypt_jni" to setOf("aaaa1111"))))
        .single()
    assertEquals("2.7.0" to HostNativesProvision.Match.BUILD_ID, byId.build.version to byId.match)
    // Unknown: the newest, said out loud.
    assertEquals("2.8.0", plan(natives(setOf("conscrypt_jni"))).single().build.version)
    assertTrue(logs.single().contains("version unknown"), logs.toString())

    val named = plan(natives(setOf("zstd-jni-1.5.7-9"))).single()
    assertEquals("zstd-jni-1.5.7-9", named.loadName)
    assertEquals(
      "1.5.7-9" to HostNativesProvision.Match.VERSION,
      named.build.version to named.match,
    )
  }

  @Test
  fun `versions order numeric suffixes after their release and as numbers`() {
    val sorted =
      listOf("1.5.7-10", "1.5.7", "1.5.7-9", "1.5.7-rc1", "1.5.6")
        .sortedWith(HostNativesProvision::compareVersions)
    assertEquals(listOf("1.5.6", "1.5.7-rc1", "1.5.7", "1.5.7-9", "1.5.7-10"), sorted)
    assertTrue(HostNativesProvision.compareVersions("2.8.0-alpha03", "2.7.1") > 0)
  }

  @Test
  fun `reads the GNU build ID from an ELF note`() {
    // A minimal little-endian ELF64: header, one PT_NOTE program header, one NT_GNU_BUILD_ID note.
    val note =
      java.nio.ByteBuffer.allocate(16 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        putInt(4).putInt(4).putInt(3).put("GNU\u0000".toByteArray())
        put(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
      }
    val elf = java.nio.ByteBuffer.allocate(64 + 56 + 24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    elf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
    elf.putLong(0x20, 64).putShort(0x36, 56).putShort(0x38, 1)
    elf.putInt(64, 4).putLong(64 + 8, 120).putLong(64 + 32, 20)
    elf.position(120)
    elf.put(note.array(), 0, 20)
    assertEquals("deadbeef", HostNativesProvision.elfBuildId(elf.array()))
    assertNull(HostNativesProvision.elfBuildId(ByteArray(80)))
  }

  private fun linux(name: String) =
    mapOf("linux-x86_64" to Entry("https://x/$name.jar", name.padEnd(64, '0'), "n/lib.so"))

  private fun zip(file: File, vararg entries: Pair<String, String>): File {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return file
  }

  private fun sha256(file: File) =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
      "%02x".format(it)
    }

  @Test
  fun `resolve fetches the index and the build, renames it, caches both, and sets properties`() {
    val jar = zip(File(tmp, "served/sqlite.jar"), "natives/linux_x64/libsqliteJni.so" to "elf")
    val indexJson =
      """
      {"schema": "${HostNativesProvision.SCHEMA}", "libraries": [
        {"library": "sqliteJni", "artifact": "androidx.sqlite:sqlite-bundled",
         "upstream": {"ignored": true}, "properties": {"sqlite.dir": "{dir}"},
         "builds": [{"version": "2.6.2", "platforms": {"linux-x86_64": {
           "url": "https://maven/sqlite.jar", "sha256": "${sha256(jar)}",
           "entry": "natives/linux_x64/libsqliteJni.so"}}}]}]}
      """
    val fetched = mutableListOf<String>()
    val fetcher = HostNativesProvision.Fetcher { url, dest ->
      fetched += url
      if (url.endsWith("index.json")) dest.writeText(indexJson) else jar.copyTo(dest, true)
    }
    val apk =
      zip(
        File(tmp, "app.apk"),
        "lib/arm64-v8a/libsqliteJni.so" to "",
        "META-INF/androidx.sqlite_sqlite-bundled.version" to "2.6.2",
      )
    fun resolve() =
      HostNativesProvision.resolve(
        apk,
        35,
        fetcher,
        env = env,
        log = {},
        noNetwork = false,
        platform = "linux-x86_64",
      )
    val first = resolve()
    val dir = first.dirs.single()
    assertTrue(File(dir, "libsqliteJni.so").isFile, first.toString())
    assertEquals(mapOf("sqlite.dir" to dir.absolutePath), first.properties)
    assertEquals(first, resolve())
    assertEquals(2, fetched.size, "the index and the jar, once each: $fetched")
    // Offline from here on still works, from the cache.
    assertEquals(
      first,
      HostNativesProvision.resolve(
        apk,
        35,
        { _, _ -> error("offline") },
        env = env,
        log = {},
        noNetwork = true,
        platform = "linux-x86_64",
      ),
    )
  }

  @Test
  fun `the files a library needs beside it are extracted into its directory`() {
    val staged = File(tmp, "staged").apply { mkdirs() }
    for (name in listOf("libfilament-jni.dylib", "libEGL.dylib", "libGLESv2.dylib")) {
      File(staged, name).writeText(name)
    }
    val tarball = File(tmp, "served/filament.tar.gz").apply { parentFile.mkdirs() }
    val tar =
      ProcessBuilder("tar", "-czf", tarball.absolutePath, "-C", staged.absolutePath, ".")
        .redirectErrorStream(true)
        .start()
    assertEquals(0, tar.waitFor(), tar.inputStream.bufferedReader().readText())
    val indexJson =
      """
      {"schema": "${HostNativesProvision.SCHEMA}", "libraries": [
        {"library": "filament-jni", "artifact": "com.google.android.filament:filament-android",
         "builds": [{"version": "1.77.3", "platforms": {"macos-arm64": {
           "url": "https://x/filament.tar.gz", "sha256": "${sha256(tarball)}",
           "entry": "libfilament-jni.dylib", "files": ["libEGL.dylib", "libGLESv2.dylib"]}}}]}]}
      """
    val fetcher = HostNativesProvision.Fetcher { url, dest ->
      if (url.endsWith("index.json")) dest.writeText(indexJson) else tarball.copyTo(dest, true)
    }
    val apk = zip(File(tmp, "filament.apk"), "lib/arm64-v8a/libfilament-jni.so" to "")
    val resolution =
      HostNativesProvision.resolve(
        apk,
        35,
        fetcher,
        env = env,
        log = {},
        noNetwork = false,
        platform = "macos-arm64",
      )
    val dir = resolution.dirs.single()
    assertEquals(
      listOf("libEGL.dylib", "libGLESv2.dylib", "libfilament-jni.dylib"),
      dir.list()!!.sorted(),
    )
    assertEquals("libEGL.dylib", File(dir, "libEGL.dylib").readText())

    // A cache holding the library without them (a CLI from before `files`) is filled in.
    File(dir, "libEGL.dylib").delete()
    HostNativesProvision.resolve(
      apk,
      35,
      fetcher,
      env = env,
      log = {},
      noNetwork = false,
      platform = "macos-arm64",
    )
    assertEquals("libEGL.dylib", File(dir, "libEGL.dylib").readText())
  }

  @Test
  fun `a hash mismatch, an APK without natives, and no index all skip`() {
    val jar = zip(File(tmp, "served/x.jar"), "lib.so" to "elf")
    val logs = mutableListOf<String>()
    val choice =
      HostNativesProvision.Choice(
        index.libraries[1],
        "sqliteJni",
        index.libraries[1].builds[0],
        Entry("https://x/x.jar", "0".repeat(64), "lib.so"),
        "2.6.2",
        HostNativesProvision.Match.VERSION,
      )
    assertNull(
      HostNativesProvision.ensureFile(
        cache,
        choice,
        "linux-x86_64",
        { _, dest -> jar.copyTo(dest, true) },
        noNetwork = false,
      ) {
        logs += it
      },
    )
    assertTrue(logs.single().contains("the index says"), logs.toString())

    var fetches = 0
    val plain = zip(File(tmp, "plain.apk"), "classes.dex" to "")
    assertEquals(
      HostNativesProvision.Resolution.NONE,
      HostNativesProvision.resolve(
        plain,
        35,
        { _, _ -> fetches++ },
        env = env,
        log = {},
        noNetwork = false,
        platform = "linux-x86_64",
      ),
    )
    assertEquals(0, fetches, "an APK with no native code needs no index")

    val native = zip(File(tmp, "native.apk"), "lib/x86_64/libsqliteJni.so" to "")
    assertEquals(
      HostNativesProvision.Resolution.NONE,
      HostNativesProvision.resolve(
        native,
        35,
        { _, _ -> error("HTTP 404") },
        env = env,
        log = {},
        noNetwork = false,
        platform = "linux-x86_64",
      ),
    )
  }

  @Test
  fun `each index source has its own cache`() {
    val fetched = mutableListOf<String>()
    val fetcher = HostNativesProvision.Fetcher { url, dest ->
      fetched += url
      dest.writeText("""{"schema": "${HostNativesProvision.SCHEMA}", "libraries": []}""")
    }
    fun load(url: String?) =
      HostNativesProvision.loadIndex(
        cache,
        fetcher,
        env = { if (it == HostNativesProvision.INDEX_ENV) url else null },
        noNetwork = false,
        log = {},
      )
    load(null)
    load("https://example.com/other.json")
    load(null)
    load("https://example.com/other.json")
    assertEquals(
      listOf(HostNativesProvision.DEFAULT_INDEX_URL, "https://example.com/other.json"),
      fetched,
    )
  }

  @Test
  fun `the index is refetched once older than an hour, and a stale copy covers a failed fetch`() {
    var fetches = 0
    var failing = false
    val fetcher = HostNativesProvision.Fetcher { _, dest ->
      fetches++
      if (failing) error("HTTP 503")
      dest.writeText("{\"schema\": \"${HostNativesProvision.SCHEMA}\", \"libraries\": []}")
    }
    fun load(now: Long, noNetwork: Boolean = false) =
      HostNativesProvision.loadIndex(
        cache,
        fetcher,
        env = { null },
        noNetwork = noNetwork,
        log = {},
        now = now,
      )
    val fetchedAt = System.currentTimeMillis()
    assertTrue(load(fetchedAt) != null)
    assertEquals(1, fetches)
    // The fetch wrote the cache now; age it from there.
    val cached = cache.listFiles { f -> f.name.startsWith("index-") }!!.single()
    cached.setLastModified(fetchedAt)

    val minute = 60_000L
    assertTrue(load(fetchedAt + 59 * minute) != null)
    assertEquals(1, fetches, "a copy under an hour old is used as is")

    assertTrue(load(fetchedAt + 61 * minute) != null)
    assertEquals(2, fetches, "an hour on, the index is fetched again")

    cached.setLastModified(fetchedAt)
    failing = true
    assertTrue(load(fetchedAt + 3 * 60 * minute) != null, "a failed fetch falls back to the cache")
    assertEquals(3, fetches)
    assertTrue(
      load(fetchedAt + 3 * 60 * minute, noNetwork = true) != null,
      "the network off uses the cache",
    )
    assertEquals(3, fetches)
  }

  /**
   * The index model as the first CLI to read `index.json` shipped it (#6412), frozen. Every CLI
   * since fetches the same file from `main`, so a field it requires, or a type it cannot read,
   * breaks the whole index for it, not just the new entry: a later field must be optional.
   */
  @kotlinx.serialization.Serializable
  private data class V1Index(val schema: String, val libraries: List<V1Library>)

  @kotlinx.serialization.Serializable
  private data class V1Library(
    val library: String,
    val artifact: String,
    val minSdk: Int = 0,
    val fixedIn: String? = null,
    val builds: List<V1Build> = emptyList(),
  )

  @kotlinx.serialization.Serializable
  private data class V1Build(val version: String, val platforms: Map<String, V1Entry>)

  @kotlinx.serialization.Serializable
  private data class V1Entry(val url: String, val sha256: String, val entry: String)

  @Test
  fun `the checked-in index stays readable by the first released CLI`() {
    var dir = File("").absoluteFile
    while (!File(dir, "host-natives/index.json").isFile) dir = dir.parentFile ?: return
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val v1 =
      json.decodeFromString(V1Index.serializer(), File(dir, "host-natives/index.json").readText())
    assertEquals(HostNativesProvision.SCHEMA, v1.schema)
    assertTrue(v1.libraries.any { it.library == "sqliteJni" && it.builds.isNotEmpty() })
  }

  @Test
  fun `the checked-in index parses`() {
    var dir = File("").absoluteFile
    while (!File(dir, "host-natives/index.json").isFile) dir = dir.parentFile ?: return
    val parsed = HostNativesProvision.parseIndex(File(dir, "host-natives/index.json").readText())
    assertTrue(parsed.libraries.any { it.library == "androidx.graphics.path" })
  }
}
