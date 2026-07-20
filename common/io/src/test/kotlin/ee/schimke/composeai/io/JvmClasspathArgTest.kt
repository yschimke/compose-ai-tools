package ee.schimke.composeai.io

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmClasspathArgTest {

  @Test
  fun `emits an @argfile token backed by a -classpath file`() {
    val cp = listOf("/a/b.jar", "/c/d.jar")
    val token = classpathArgFile(cp)

    assertTrue(token.startsWith("@"), "expected an @argfile token, got: $token")
    val file = File(token.removePrefix("@"))
    assertTrue(file.isFile, "argfile should exist on disk")
    assertEquals("-classpath \"${cp.joinToString(File.pathSeparator)}\"\n", file.readText())
  }

  @Test
  fun `command length stays bounded no matter how large the classpath is`() {
    // The whole point: a full app's render classpath (hundreds of long jar paths) must NOT land on
    // the command line, where it overflows ARG_MAX (execve E2BIG). The @argfile token is a short,
    // fixed-shape reference regardless of classpath size.
    val huge = (1..5_000).map { "/nix/store/${"x".repeat(40)}/lib/artifact-$it.jar" }
    val joinedBytes = huge.joinToString(File.pathSeparator).length
    val token = classpathArgFile(huge)

    assertTrue(joinedBytes > 200_000, "sanity: the raw classpath really is huge ($joinedBytes B)")
    assertTrue(
      token.length < 512,
      "the @argfile token must stay short (was ${token.length} B) so argv can't overflow",
    )
    // …and the file still round-trips the full classpath.
    val body = File(token.removePrefix("@")).readText()
    assertTrue(body.contains("artifact-5000.jar"), "argfile should carry every entry")
  }

  @Test
  fun `quotes and escapes spaces, backslashes and quotes for the argfile grammar`() {
    // Wrapped in quotes; backslashes doubled (argfile un-escapes \\ -> \); embedded quote escaped.
    assertEquals("\"/plain/path.jar\"", quoteForArgFile("/plain/path.jar"))
    assertEquals("\"/has a space/x.jar\"", quoteForArgFile("/has a space/x.jar"))
    assertEquals("\"C:\\\\Program Files\\\\a.jar\"", quoteForArgFile("C:\\Program Files\\a.jar"))
    assertEquals("\"weird\\\"name.jar\"", quoteForArgFile("weird\"name.jar"))
  }

  @Test
  fun `rejects an empty classpath`() {
    assertFailsWith<IllegalArgumentException> { classpathArgFile(emptyList()) }
  }
}
