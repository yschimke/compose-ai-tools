package ee.schimke.composeai.cli

import ee.schimke.composeai.discovery.PreviewDiscovery
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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

  @Test
  fun `the cache can be turned off`() {
    assertNull(BazelHostCache.shared { if (it == BazelHostCache.DISABLE_ENV) "0" else null })
  }
}
