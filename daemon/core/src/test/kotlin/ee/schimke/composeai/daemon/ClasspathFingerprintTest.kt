package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [ClasspathFingerprint] — Tier-1 dirty detection (DESIGN § 8). Pins the documented
 * properties of both hashes:
 *
 * - **Cheap hash** is sensitive to file *bytes* (so a comment-only edit IS detected — the classpath
 *   hash then catches it as a false alarm and the daemon stays running).
 * - **Classpath hash** is sensitive to `(absolutePath, length, lastModified)` only — a JAR being
 *   re-resolved on disk (different mtime) flips the hash without reading bytes.
 * - Both are deterministic across calls when the inputs are unchanged.
 * - SHA-256 hex output (64 chars).
 */
class ClasspathFingerprintTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun cheap_hash_is_deterministic() {
    val a = tmp.newFile("a.toml").apply { writeText("foo = 1\n") }
    val b = tmp.newFile("b.gradle.kts").apply { writeText("plugins {}\n") }
    val fp1 = ClasspathFingerprint(cheapSignalFiles = listOf(a, b), classpathEntries = emptyList())
    val fp2 = ClasspathFingerprint(cheapSignalFiles = listOf(a, b), classpathEntries = emptyList())
    val h1 = fp1.cheapHash()
    val h2 = fp2.cheapHash()
    assertEquals("cheap hash must be stable across instances over the same disk state", h1, h2)
    assertEquals(
      "cheap hash must be SHA-256 hex",
      ClasspathFingerprint.SHA_256_HEX_LENGTH,
      h1.length,
    )
  }

  @Test
  fun cheap_hash_changes_when_file_bytes_change() {
    val a = tmp.newFile("a.toml").apply { writeText("foo = 1\n") }
    val fp = ClasspathFingerprint(cheapSignalFiles = listOf(a), classpathEntries = emptyList())
    val before = fp.cheapHash()
    a.writeText("foo = 2\n")
    val after = fp.cheapHash()
    assertNotEquals("editing a cheap-signal file's bytes must drift the cheap hash", before, after)
  }

  @Test
  fun cheap_hash_handles_missing_files() {
    val present = tmp.newFile("present.toml").apply { writeText("x = 1\n") }
    val ghost = File(tmp.root, "ghost.toml") // never created
    val fp =
      ClasspathFingerprint(
        cheapSignalFiles = listOf(present, ghost),
        classpathEntries = emptyList(),
      )
    val before = fp.cheapHash()
    assertEquals(ClasspathFingerprint.SHA_256_HEX_LENGTH, before.length)
    // Creating the ghost must drift the hash.
    ghost.writeText("y = 2\n")
    val after = fp.cheapHash()
    assertNotEquals(before, after)
  }

  @Test
  fun classpath_hash_is_deterministic() {
    val jar = tmp.newFile("a.jar").apply { writeBytes(ByteArray(128) { 1 }) }
    val cls = tmp.newFolder("classes")
    val fp =
      ClasspathFingerprint(cheapSignalFiles = emptyList(), classpathEntries = listOf(jar, cls))
    val h1 = fp.classpathHash()
    val h2 = fp.classpathHash()
    assertEquals("classpath hash must be stable", h1, h2)
    assertEquals(ClasspathFingerprint.SHA_256_HEX_LENGTH, h1.length)
  }

  @Test
  fun classpath_hash_drifts_when_jar_mtime_changes() {
    val jar = tmp.newFile("a.jar").apply { writeBytes(ByteArray(128) { 1 }) }
    val fp = ClasspathFingerprint(cheapSignalFiles = emptyList(), classpathEntries = listOf(jar))
    val before = fp.classpathHash()
    // Force a fresh mtime — the FS resolution is millisecond-level on most modern Linux + macOS;
    // sleep 50ms to ensure the new value differs from the original.
    Thread.sleep(50)
    jar.setLastModified(System.currentTimeMillis())
    val after = fp.classpathHash()
    assertNotEquals("changing a classpath JAR's mtime must drift the classpath hash", before, after)
  }

  @Test
  fun classpath_hash_drifts_when_jar_size_changes() {
    val jar = tmp.newFile("a.jar").apply { writeBytes(ByteArray(128) { 1 }) }
    val fp = ClasspathFingerprint(cheapSignalFiles = emptyList(), classpathEntries = listOf(jar))
    val before = fp.classpathHash()
    jar.writeBytes(ByteArray(256) { 1 })
    val after = fp.classpathHash()
    assertNotEquals("changing a classpath JAR's size must drift the classpath hash", before, after)
  }

  @Test
  fun snapshot_packages_both_hashes() {
    val a = tmp.newFile("a.toml").apply { writeText("foo = 1\n") }
    val jar = tmp.newFile("a.jar").apply { writeBytes(ByteArray(8) { 1 }) }
    val fp = ClasspathFingerprint(cheapSignalFiles = listOf(a), classpathEntries = listOf(jar))
    val snap = fp.snapshot()
    assertNotNull(snap)
    assertEquals(fp.cheapHash(), snap.cheapHash)
    assertEquals(fp.classpathHash(), snap.classpathHash)
  }

  @Test
  fun parses_sysprop_with_path_separator() {
    val a = File("/abs/gradle/libs.versions.toml").absoluteFile
    val b = File("/abs/build.gradle.kts").absoluteFile
    val value = listOf(a, b).joinToString(File.pathSeparator) { it.absolutePath }
    val parsed = ClasspathFingerprint.parseCheapSignalFilesSysprop(value)
    assertEquals(2, parsed.size)
    assertEquals(a.absolutePath, parsed[0].absolutePath)
    assertEquals(b.absolutePath, parsed[1].absolutePath)
  }

  @Test
  fun parses_empty_sysprop_to_empty_list() {
    assertTrue(ClasspathFingerprint.parseCheapSignalFilesSysprop(null).isEmpty())
    assertTrue(ClasspathFingerprint.parseCheapSignalFilesSysprop("").isEmpty())
    assertTrue(ClasspathFingerprint.parseCheapSignalFilesSysprop("   ").isEmpty())
  }

  @Test
  fun cheap_hash_microbenchmark_bounded() {
    // Realistic project: 50 build.gradle.kts files at ~2KB each. We assert the cheap recompute
    // stays well under 100ms (DESIGN § 8 — "few ms even for a large project"). Test runs on the
    // dev box; CI may be slower but 100ms is a generous ceiling that catches a real perf
    // regression without flap.
    val files =
      (1..50).map { i ->
        tmp.newFile("build-$i.gradle.kts").apply {
          writeBytes(ByteArray(2048) { (it and 0xFF).toByte() })
        }
      }
    val fp = ClasspathFingerprint(cheapSignalFiles = files, classpathEntries = emptyList())
    // Warm — first call pays JIT + MessageDigest init.
    fp.cheapHash()
    val start = System.nanoTime()
    repeat(10) { fp.cheapHash() }
    val tookMsTotal = (System.nanoTime() - start) / 1_000_000
    System.err.println(
      "ClasspathFingerprintTest.cheap_hash_microbenchmark_bounded: 50 files × 2KB × 10 iters = ${tookMsTotal}ms"
    )
    assertTrue(
      "cheap hash should recompute fast — 50 files × 2KB × 10 iters in ${tookMsTotal}ms",
      tookMsTotal < 1_000,
    )
  }
}
