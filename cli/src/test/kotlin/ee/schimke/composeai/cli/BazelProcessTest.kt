package ee.schimke.composeai.cli

import java.nio.file.Files
import kotlin.test.Test
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
}
