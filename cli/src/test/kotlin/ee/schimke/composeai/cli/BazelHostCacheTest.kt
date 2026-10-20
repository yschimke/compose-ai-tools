package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.discovery.PreviewDiscovery
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The memos a warm `bazel render` reuses (#6104): each must miss on any change to its inputs. */
class BazelHostCacheTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-host-cache").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  /** A clock far enough ahead that every file written by the test has settled. */
  private val settled = { System.currentTimeMillis() + 60_000 }

  private fun File.jar(name: String, vararg entries: String): File =
    resolve(name).apply {
      ZipOutputStream(outputStream()).use { zip ->
        entries.forEach {
          zip.putNextEntry(ZipEntry(it))
          zip.closeEntry()
        }
      }
    }

  @Test
  fun `a value is reused until the file changes, even keeping its size and mtime`() =
    fixture { root ->
      val file = root.resolve("lib.jar").apply { writeText("first build") }
      val store = root.resolve("cache/memo.json")
      var computed = 0
      val compute = { f: File ->
        computed++
        f.readText()
      }
      val memo = StampedFileMemo(store, settled)
      assertEquals("first build", memo.get(file, compute))
      assertEquals("first build", memo.get(file, compute))
      assertEquals(1, computed, "unchanged file is a hit")
      memo.save()
      assertEquals("first build", StampedFileMemo(store, settled).get(file, compute))
      assertEquals(1, computed, "a saved memo survives the process")

      // Same size, same mtime, different bytes: a cache restore or `cp -p` can do this, the
      // inode-change time still moves.
      val mtime = file.lastModified()
      Thread.sleep(20)
      file.writeText("first BUILD")
      file.setLastModified(mtime)
      assertEquals(mtime, file.lastModified())
      assertEquals("first BUILD", memo.get(file, compute))
      assertEquals(2, computed)

      file.writeText("a longer second build")
      assertEquals("a longer second build", memo.get(file, compute))
      assertEquals(3, computed, "a size change is a miss")
    }

  @Test
  fun `a value recorded right after the file changed is not trusted`() = fixture { root ->
    // On a filesystem with one-second timestamps a rewrite within the same tick and of the same
    // size leaves the stamp as it was, so a just-written file is read again until it settles.
    val file = root.resolve("lib.jar").apply { writeText("x") }
    var computed = 0
    val memo = StampedFileMemo(root.resolve("memo.json"))
    repeat(2) {
      memo.get(file) {
        computed++
        it.readText()
      }
    }
    assertEquals(2, computed)
  }

  @Test
  fun `a filesystem without an inode-change time is never memoised`() = fixture { root ->
    // Windows and FAT: creation time and file key survive an in-place rewrite that keeps size and
    // mtime, so nothing could tell the old bytes from the new.
    val file = root.resolve("lib.jar").apply { writeText("first build") }
    assertNull(FileStamp.of(file) { null })
    var computed = 0
    val memo =
      StampedFileMemo(root.resolve("memo.json"), settled, stampOf = { FileStamp.of(it) { null } })
    repeat(3) {
      memo.get(file) {
        computed++
        it.readText()
      }
    }
    assertEquals(3, computed, "every read recomputes")
    memo.save()
    assertEquals(false, root.resolve("memo.json").exists(), "nothing was recorded")
  }

  @Test
  fun `a corrupt or missing cache file is an empty cache`() = fixture { root ->
    val file = root.resolve("lib.jar").apply { writeText("content") }
    val store = root.resolve("memo.json").apply { writeText("{not json") }
    var computed = 0
    val memo = StampedFileMemo(store, settled)
    assertEquals(
      "content",
      memo.get(file) {
        computed++
        it.readText()
      },
    )
    assertEquals(1, computed)
    memo.save()
    // Rewritten whole, so the next process reads it.
    assertEquals(
      "content",
      StampedFileMemo(store, settled).get(file) { error("should be a hit") },
    )

    val keyedFile = root.resolve("keyed.json").apply { writeText("[1,") }
    val keyed = KeyedMemo(keyedFile)
    assertNull(keyed["k"])
    keyed["k"] = "v"
    keyed.save()
    assertEquals("v", KeyedMemo(keyedFile)["k"])

    // A missing file is never memoised.
    assertNull(memo.get(root.resolve("absent.jar")) { null })
  }

  @Test
  fun `pruning with a warm cache decides as a cold one does, for any final R jar`() =
    fixture { root ->
      val rFinal = root.jar("r-final.jar", "com/example/R.class", "com/example/R\$string.class")
      val app = root.jar("app.jar", "com/example/MainKt.class")
      val libR = root.jar("lib_resources.jar", "com/example/R\$string.class")
      val stub = root.jar("stub.jar", "META-INF/MANIFEST.MF")
      val ui = root.jar("ui.jar", "androidx/compose/ui/Modifier.class")
      val runtime = listOf(app, libR, stub, ui)
      val cache = BazelHostCache(root.resolve("cache"), settled)

      val cold = pruneRuntimeClasspath(runtime, setOf(app), rFinal)
      val first = pruneRuntimeClasspath(runtime, setOf(app), rFinal, cache)
      val warm =
        pruneRuntimeClasspath(runtime, setOf(app), rFinal, BazelHostCache(cache.root, settled))
      assertEquals(cold, first)
      assertEquals(cold, warm)
      assertEquals(listOf(libR), warm.supersededRJars)
      assertEquals(listOf(stub), warm.empty)
      // The memo records what a jar holds, not the verdict: without a final R jar the same entry
      // keeps the library's R jar.
      assertEquals(
        pruneRuntimeClasspath(runtime, setOf(app), null),
        pruneRuntimeClasspath(runtime, setOf(app), null, BazelHostCache(cache.root, settled)),
      )

      // A rebuilt jar is read again.
      root.jar("stub.jar", "com/example/Now.class")
      val rebuilt =
        pruneRuntimeClasspath(runtime, setOf(app), rFinal, BazelHostCache(cache.root, settled))
      assertEquals(listOf(app, stub, ui), rebuilt.jars)
      assertEquals(emptyList(), rebuilt.empty)
    }

  @Test
  fun `the discovery key moves with every input and only then`() = fixture { root ->
    val cache = BazelHostCache(root.resolve("cache"), settled)
    val app = root.resolve("app.jar").apply { writeText("app v1") }
    val a = root.resolve("a.jar").apply { writeText("a") }
    val b = root.resolve("b.jar").apply { writeText("b") }
    val source = root.resolve("Greeting.kt").apply { writeText("fun Greeting() {}") }
    fun input(deps: List<File> = listOf(a, b), failOnEmpty: Boolean = true) =
      PreviewDiscovery.Input(
        classDirs = emptyList(),
        projectClassJars = listOf(app),
        dependencyJars = deps,
        sourceFiles = listOf(source),
        moduleName = "//:app",
        variantName = "desktop",
        projectDirectory = root,
        failOnEmpty = failOnEmpty,
      )
    val base = bazelDiscoveryKey(input(), cache)
    assertEquals(base, bazelDiscoveryKey(input(), cache))
    assertEquals(base, bazelDiscoveryKey(input(), BazelHostCache(cache.root, settled)))
    assertNotEquals(base, bazelDiscoveryKey(input(deps = listOf(b, a)), cache), "jar order")
    assertNotEquals(base, bazelDiscoveryKey(input(deps = listOf(a)), cache), "jar set")
    assertNotEquals(base, bazelDiscoveryKey(input(failOnEmpty = false), cache), "options")

    // The project jar keeps its size and mtime: it is compared by content.
    val mtime = app.lastModified()
    app.writeText("app v2")
    app.setLastModified(mtime)
    val edited = bazelDiscoveryKey(input(), cache)
    assertNotEquals(base, edited, "project jar")

    b.writeText("b2")
    assertNotEquals(edited, bazelDiscoveryKey(input(), cache), "dependency jar")
    val rebuilt = bazelDiscoveryKey(input(), cache)
    source.writeText("fun Greeting() { }")
    assertNotEquals(rebuilt, bazelDiscoveryKey(input(), cache), "source")
  }

  @Test
  fun `previews json is reused only while the discovery inputs are unchanged`() = fixture { root ->
    val cache = BazelHostCache(root.resolve("cache"), settled)
    val app = root.jar("app.jar", "META-INF/MANIFEST.MF")
    val source = root.resolve("Greeting.kt").apply { writeText("fun Greeting() {}") }
    val artifacts =
      BazelArtifacts(
          "//:app",
          "desktop",
          listOf(app.path),
          listOf(app.path),
          sourceFiles = listOf(source.path),
        )
        .resolve(root, hostCache = null)
    val output = root.resolve("out")
    val first = prepareBazelPreviews(artifacts, root, output, failOnEmpty = false, cache = cache)
    assertEquals(emptyList(), first.manifest.previews)

    // Swap the kept result for a marker: a hit returns it, a miss replaces it.
    val entryFile = cache.root.resolve("discovery").listFiles()!!.single()
    val entry = bazelJson.decodeFromString<BazelDiscoveryEntry>(entryFile.readText())
    val marker = PreviewManifest("//:marker", "desktop", emptyList())
    entryFile.writeText(bazelJson.encodeToString(entry.copy(manifest = marker)))
    val reused = prepareBazelPreviews(artifacts, root, output, failOnEmpty = false, cache = cache)
    assertEquals("//:marker", reused.manifest.module)
    assertEquals(
      "//:marker",
      bazelJson.decodeFromString<PreviewManifest>(reused.manifestFile.readText()).module,
    )

    source.writeText("fun Greeting() { /* edited */ }")
    val rescanned =
      prepareBazelPreviews(artifacts, root, output, failOnEmpty = false, cache = cache)
    assertEquals("//:app", rescanned.manifest.module)

    // A corrupt entry is a miss.
    entryFile.writeText("{")
    assertEquals(
      "//:app",
      prepareBazelPreviews(artifacts, root, output, failOnEmpty = false, cache = cache)
        .manifest
        .module,
    )
  }

  @Test
  fun `android jar level is read once per version of the jar`() = fixture { root ->
    val cache = BazelHostCache(root.resolve("cache"), settled)
    val jar = root.jar("android.jar", "android/app/Activity.class")
    assertNull(AndroidPlatformLevel.fromContents(jar, cache))
    assertEquals(
      BazelHostCache.NONE,
      BazelHostCache(cache.root, settled).androidJarLevel.get(jar) { error("should be a hit") },
    )
  }

  @Test
  fun `the build event stream names the execution root`() = fixture { root ->
    val events =
      sequenceOf(
        """{"id":{"started":{}},"started":{"uuid":"x"}}""",
        """{"id":{"workspace":{}},"workspaceInfo":{"localExecRoot":"${root.absolutePath}"}}""",
      )
    assertEquals(root.absolutePath, bazelEventExecutionRoot(events))
    assertNull(bazelEventExecutionRoot(sequenceOf("""{"id":{"started":{}}}""")))
    assertNull(
      bazelEventExecutionRoot(
        sequenceOf(
          """{"id":{"workspace":{}},"workspaceInfo":{"localExecRoot":"${root.resolve("gone").absolutePath}"}}"""
        )
      ),
      "a directory that is not there falls back to bazel info",
    )
  }

  /** A FUSE's digest attribute, simulated: [digests] by path, else `ENODATA`. */
  private class FakeDigests {
    val digests = mutableMapOf<Path, ByteArray>()
    var reads = 0

    fun read(path: Path): ByteArray? {
      reads++
      return digests[path.toAbsolutePath()]
        ?: throw FileSystemException(path.toString(), null, "No data available")
    }
  }

  private fun counting(memo: StampedFileMemo, file: File): Pair<String?, Boolean> {
    var computed = false
    val value =
      memo.get(file) {
        computed = true
        it.readText()
      }
    return value to computed
  }

  @Test
  fun `a digest stamp hits across a remount that moves mtime, ctime and inode`() = fixture { root ->
    val fake = FakeDigests()
    val stamper = FileStamper("user.checksum.sha256", fake::read)
    val file =
      root.resolve("bin/lib.jar").apply {
        parentFile.mkdirs()
        writeText("dependency")
      }
    fake.digests[file.toPath().toAbsolutePath()] =
      "ab".repeat(32).toByteArray() + "_0000abcd".toByteArray()
    val unixBefore = FileStamp.of(file)!!
    val store = root.resolve("cache/memo.json")
    // No settled clock: a digest stamp is trusted as soon as it is recorded.
    val memo = StampedFileMemo(store, stampOf = stamper::stamp)
    assertEquals("dependency" to true, counting(memo, file))
    memo.save()

    // The remount: the same bytes, written afresh, under a new inode and new timestamps.
    Thread.sleep(20)
    file.delete()
    file.writeText("dependency")
    file.setLastModified(System.currentTimeMillis() + 10_000)
    assertNotEquals(unixBefore.text, FileStamp.of(file)!!.text, "every unix attribute moved")
    assertEquals(
      "dependency" to false,
      counting(StampedFileMemo(store, stampOf = stamper::stamp), file),
    )
    assertTrue(stamper.digestStamps.get() > 0)
    assertEquals(0, stamper.unixStamps.get())
  }

  @Test
  fun `a changed digest is a miss even keeping size and mtime`() = fixture { root ->
    val fake = FakeDigests()
    val stamper = FileStamper("checksum", fake::read)
    val file = root.resolve("lib.jar").apply { writeText("v1") }
    val path = file.toPath().toAbsolutePath()
    fake.digests[path] = byteArrayOf(1, 2, 3)
    val memo = StampedFileMemo(root.resolve("memo.json"), settled, stamper::stamp)
    assertEquals("v1" to true, counting(memo, file))
    assertEquals("v1" to false, counting(memo, file))
    val mtime = file.lastModified()
    file.writeText("v2")
    file.setLastModified(mtime)
    fake.digests[path] = byteArrayOf(1, 2, 4)
    assertEquals("v2" to true, counting(memo, file))
    // Binary digests are kept whole, in hex, with the attribute and the size.
    assertEquals("digest:checksum:2:010204", stamper.stamp(file)!!.text)
  }

  @Test
  fun `a file without the attribute keeps the unix stamp`() = fixture { root ->
    val file = root.resolve("external.jar").apply { writeText("external") }
    val unix = FileStamp.of(file)!!
    for (reader in
      listOf<(Path) -> ByteArray?>(
        { throw FileSystemException(it.toString(), null, "No data available") },
        { throw UnsupportedOperationException("no user view") },
        { throw IllegalArgumentException("bad name") },
        { null },
        { ByteArray(0) },
      )) {
      val stamper = FileStamper("checksum", reader)
      val stamp = stamper.stamp(file)!!
      assertEquals(unix, stamp)
      assertEquals(false, stamp.contentAddressed)
      assertEquals(1, stamper.unixStamps.get())
      // The racy rule still applies to it.
      val memo =
        StampedFileMemo(root.resolve("memo-${reader.hashCode()}.json"), stampOf = stamper::stamp)
      assertEquals("external" to true, counting(memo, file))
      assertEquals("external" to true, counting(memo, file), "just written: not yet trusted")
    }
    // Unconfigured, the attribute is never read.
    val fake = FakeDigests()
    assertEquals(unix, FileStamper(null, fake::read).stamp(file))
    assertEquals(unix, FileStamper("  ", fake::read).stamp(file))
    assertEquals(0, fake.reads)
  }

  @Test
  fun `digest and unix entries never match each other`() = fixture { root ->
    val fake = FakeDigests()
    val file = root.resolve("lib.jar").apply { writeText("same bytes") }
    val store = root.resolve("memo.json")
    val unixMemo = StampedFileMemo(store, settled, FileStamper.UNIX::stamp)
    assertEquals("same bytes" to true, counting(unixMemo, file))
    unixMemo.save()

    fake.digests[file.toPath().toAbsolutePath()] = "cafe".toByteArray()
    val digestStamper = FileStamper("checksum", fake::read)
    val digestMemo = StampedFileMemo(store, settled, digestStamper::stamp)
    assertEquals("same bytes" to true, counting(digestMemo, file), "a unix entry is a digest miss")
    digestMemo.save()
    assertEquals(
      "same bytes" to true,
      counting(StampedFileMemo(store, settled, FileStamper.UNIX::stamp), file),
      "a digest entry is a unix miss",
    )
    // Nor do two attributes' digests of the same bytes.
    val other = FileStamper("blake3", fake::read)
    assertNotEquals(digestStamper.stamp(file)!!.text, other.stamp(file)!!.text)
    assertTrue(digestStamper.stamp(file)!!.text.startsWith(FileStamp.DIGEST_PREFIX))
    assertTrue(!FileStamp.of(file)!!.text.startsWith(FileStamp.DIGEST_PREFIX))
  }

  @Test
  fun `a digest-keyed sha256 memo still holds the flat file SHA-256`() = fixture { root ->
    val fake = FakeDigests()
    val stamper = FileStamper("checksum", fake::read)
    val jar = root.jar("lib.jar", "com/example/A.class")
    fake.digests[jar.toPath().toAbsolutePath()] = ByteArray(36) { it.toByte() }
    val cache = BazelHostCache(root.resolve("cache"), stamp = stamper::stamp)
    val flat = bazelBundleSha(jar)
    assertEquals(flat, cache.sha256(jar))
    cache.save()
    assertEquals(
      flat,
      BazelHostCache(cache.root, stamp = stamper::stamp).sha256.get(jar) {
        error("should be a hit")
      },
    )
    assertNotEquals(flat, ByteArray(36) { it.toByte() }.joinToString("") { "%02x".format(it) })
  }

  @Test
  fun `the digest attribute comes from the environment, then bazel json`() {
    val config =
      bazelJson.decodeFromString<BazelBuildConfig>("""{"digestXattr": "user.checksum.sha256"}""")
    assertEquals("user.checksum.sha256", config.digestXattr)
    assertNull(BazelBuildConfig().digestXattr)
    val none = { _: String -> null }
    fun env(value: String?) = { name: String ->
      if (name == BazelHostCache.DIGEST_XATTR_ENV) value else null
    }

    assertNull(BazelHostCache.digestAttribute(BazelBuildConfig(), none))
    assertEquals(
      DigestAttributeSetting("checksum.sha256", "digestXattr in bazel.json"),
      BazelHostCache.digestAttribute(config, none),
    )
    assertEquals(
      DigestAttributeSetting("blake3", "\$${BazelHostCache.DIGEST_XATTR_ENV}"),
      BazelHostCache.digestAttribute(BazelBuildConfig(), env("blake3")),
    )
    assertEquals(
      "blake3",
      BazelHostCache.digestAttribute(config, env("user.blake3"))?.attribute,
      "the environment wins",
    )
    assertNull(BazelHostCache.digestAttribute(config, env("")), "empty turns a committed one off")
    assertNull(BazelHostCache.digestAttribute(config.copy(digestXattr = "user."), none))
  }

  @Test
  fun `the default reader reads a real user xattr by name`() = fixture { root ->
    val file = root.resolve("lib.jar").apply { writeText("bytes") }
    val view = Files.getFileAttributeView(file.toPath(), UserDefinedFileAttributeView::class.java)
    val digest = "0123456789abcdef".repeat(4) + "_00000005"
    val written =
      view != null &&
        runCatching {
          view.write("compose-preview.test-digest", ByteBuffer.wrap(digest.toByteArray()))
        }
          .isSuccess
    assumeTrue(written, "this filesystem rejects user xattrs")
    val stamper = FileStamper("user.compose-preview.test-digest")
    val stamp = stamper.stamp(file)!!
    assertEquals(
      "digest:compose-preview.test-digest:5:" +
        digest.toByteArray().joinToString("") { "%02x".format(it) },
      stamp.text,
    )
    assertTrue(stamp.contentAddressed)
    // An attribute the file does not carry (ENODATA) falls back.
    assertEquals(FileStamp.of(file), FileStamper("compose-preview.absent").stamp(file))
  }

  @Test
  fun `the cache can be turned off`() {
    assertNull(BazelHostCache.shared { if (it == BazelHostCache.DISABLE_ENV) "0" else null })
  }
}
