package ee.schimke.composeai.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bundle launches keep their argv until an option outgrows one `execve` argument (#5945). */
class JavaArgFileTest {
  @Test
  fun `an ordinary command is launched unchanged`() {
    val command = listOf("java", "-Da=1", "-cp", "a.jar:b.jar", "Main")
    assertEquals(command, fitJavaCommand(command))
  }

  @Test
  fun `a classpath past the execve limit reaches the JVM through an argfile`() {
    val classpath =
      (0 until 2_000).joinToString(File.pathSeparator) {
        "/home/builder/.cache/compose-preview/bundles/extracted/deps/lib$it/lib-$it-1.0.0.jar"
      }
    assertTrue(classpath.length > 128 * 1024)
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val command =
      fitJavaCommand(
        listOf(java, "-Dcomposeai.test=a \"quoted\" \\ value", "-cp", classpath, "-version")
      )

    assertEquals(3, command.size)
    assertTrue(command[1].startsWith("@"))
    // `-version` stands in for the main class: the launcher reads every option from the argfile.
    val probe =
      ProcessBuilder(command[0], command[1], "-XshowSettings:properties", command[2])
        .redirectErrorStream(true)
        .start()
    val output = probe.inputStream.bufferedReader().readText()
    assertTrue(probe.waitFor(60, TimeUnit.SECONDS))
    assertEquals(0, probe.exitValue(), output)
    assertTrue("lib1999/lib-1999-1.0.0.jar" in output, output.take(2000))
    assertTrue("composeai.test = a \"quoted\" \\ value" in output, output.take(2000))
  }
}
