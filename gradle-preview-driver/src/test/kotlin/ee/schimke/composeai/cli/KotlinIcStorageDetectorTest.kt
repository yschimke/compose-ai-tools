package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinIcStorageDetectorTest {
  @Test
  fun `passes bytes through to delegate verbatim`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    detector.write("hello world\n".toByteArray())
    detector.write("more\n".toByteArray())

    assertEquals("hello world\nmore\n", sink.toString())
    assertTrue(detector.detectedCachesJvmDirs().isEmpty())
  }

  @Test
  fun `extracts caches-jvm dir from Kotlin storage already registered marker`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    val msg =
      "e: Incremental compilation failed: Storage for [/home/u/proj/samples/cmp/build/kotlin/" +
        "compileKotlin/cacheable/caches-jvm/jvm/kotlin/source-to-classes.tab] is already registered\n"
    detector.write(msg.toByteArray())

    val expected = File("/home/u/proj/samples/cmp/build/kotlin/compileKotlin/cacheable/caches-jvm")
    assertEquals(setOf(expected), detector.detectedCachesJvmDirs())
  }

  @Test
  fun `detects marker streamed byte-by-byte across multiple writes`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    val msg =
      "Storage for [/x/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/" +
        "source-to-classes.tab] is already registered\n"
    for (b in msg.toByteArray()) detector.write(b.toInt())

    val expected = File("/x/build/kotlin/compileKotlin/cacheable/caches-jvm")
    assertEquals(setOf(expected), detector.detectedCachesJvmDirs())
  }

  @Test
  fun `dedups repeated markers for the same caches-jvm dir`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    val line =
      "Storage for [/p/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/" +
        "source-to-classes.tab] is already registered\n"
    detector.write(line.toByteArray())
    detector.write(line.toByteArray())

    assertEquals(1, detector.detectedCachesJvmDirs().size)
  }

  @Test
  fun `collects distinct caches-jvm dirs across modules`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    detector.write(
      ("Storage for [/p/a/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/" +
          "source-to-classes.tab] is already registered\n")
        .toByteArray()
    )
    detector.write(
      ("Storage for [/p/b/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/" +
          "java-to-kotlin.tab] is already registered\n")
        .toByteArray()
    )

    assertEquals(
      setOf(
        File("/p/a/build/kotlin/compileKotlin/cacheable/caches-jvm"),
        File("/p/b/build/kotlin/compileKotlin/cacheable/caches-jvm"),
      ),
      detector.detectedCachesJvmDirs(),
    )
  }

  @Test
  fun `ignores unrelated lines`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    detector.write("> Task :app:compileKotlin\n".toByteArray())
    detector.write("BUILD SUCCESSFUL in 3s\n".toByteArray())
    detector.write("Storage for something else without brackets\n".toByteArray())

    assertTrue(detector.detectedCachesJvmDirs().isEmpty())
  }

  @Test
  fun `returns null when path has no caches-jvm ancestor`() {
    assertNull(
      KotlinIcStorageDetector.findCachesJvmAncestor(File("/some/other/path/source-to-classes.tab"))
    )
  }

  @Test
  fun `flushes trailing line on close even without newline`() {
    val sink = ByteArrayOutputStream()
    val detector = KotlinIcStorageDetector(sink)

    detector.write(
      ("Storage for [/p/build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin/" +
          "source-to-classes.tab] is already registered")
        .toByteArray()
    )
    detector.close()

    assertEquals(
      setOf(File("/p/build/kotlin/compileKotlin/cacheable/caches-jvm")),
      detector.detectedCachesJvmDirs(),
    )
  }
}
