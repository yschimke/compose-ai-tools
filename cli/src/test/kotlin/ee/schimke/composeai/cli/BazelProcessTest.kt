package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.LINUX, OS.MAC)
class BazelProcessTest {
  @Test
  fun `argv remains literal and output larger than a pipe buffer completes`() {
    val root = Files.createTempDirectory("bazel-process").toFile()
    try {
      val literal = "spaces; dollar \$HOME"
      val result =
        BazelProcess.run(
          listOf(
            "/bin/sh",
            "-c",
            "printf '%s\\n' \"\$1\"; dd if=/dev/zero bs=1024 count=128 2>/dev/null",
            "unused",
            literal,
          ),
          root,
          10,
        )
      assertTrue(result.startsWith(literal + "\n"))
      assertTrue(result.length > 128 * 1024)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `nonzero exit and deadline surface failures`() {
    val root = Files.createTempDirectory("bazel-process").toFile()
    try {
      val failed =
        assertFailsWith<IllegalStateException> {
          BazelProcess.run(listOf("/bin/sh", "-c", "printf diagnostic; exit 7"), root, 10)
        }
      assertTrue(failed.message!!.contains("exited 7"))
      assertTrue(failed.message!!.contains("diagnostic"))
      val start = System.nanoTime()
      val timedOut =
        assertFailsWith<IllegalStateException> {
          BazelProcess.run(listOf("/bin/sh", "-c", "sleep 30 & wait"), root, 1)
        }
      assertTrue(timedOut.message!!.contains("timed out"))
      assertTrue((System.nanoTime() - start) / 1_000_000_000 < 10)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `a failure quotes stderr, keeping an early ERROR line past the tail, and still streams it`() {
    val root = Files.createTempDirectory("bazel-process").toFile()
    try {
      val streamed = ByteArrayOutputStream()
      val failed =
        assertFailsWith<BazelCommandFailed> {
          BazelProcess.run(
            listOf(
              "/bin/sh",
              "-c",
              "echo 'ERROR: no such package' >&2; " +
                "i=0; while [ \$i -lt 60 ]; do echo \"INFO: line \$i\" >&2; i=\$((i+1)); done; " +
                "echo 'FAILED: Build did NOT complete successfully' >&2; exit 1",
            ),
            root,
            10,
            PrintStream(streamed, true),
          )
        }
      assertEquals(1, failed.exitCode)
      val message = failed.message!!
      assertTrue(message.contains("ERROR: no such package"), message)
      assertTrue(message.contains("FAILED: Build did NOT complete successfully"), message)
      assertTrue(message.contains("INFO: line 59"), message)
      assertTrue(!message.contains("INFO: line 0\n"), message)
      assertTrue(streamed.toString().contains("INFO: line 0\n"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `failure lines put diagnostics that left the tail first`() {
    assertEquals("ERROR: a\nx\ny", failureLines(listOf("x", "y"), listOf("ERROR: a")))
    assertEquals("ERROR: a\ny", failureLines(listOf("ERROR: a", "y"), listOf("ERROR: a")))
  }
}
