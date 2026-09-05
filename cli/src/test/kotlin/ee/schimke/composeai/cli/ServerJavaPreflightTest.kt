package ee.schimke.composeai.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The decision `serve` and `mcp serve` make about the JVM they are about to hand a start script.
 *
 * Every case is driven through the seams rather than through a real JDK, because the failure being
 * prevented needs *two* Java versions on the machine to reproduce, and CI has one. What is worth
 * pinning is not that a JVM can be interrogated — it is that the answer is read from the
 * distribution, that a launch is only ever refused on a positive answer, and that the refusal names
 * what a reader has to change.
 */
class ServerJavaPreflightTest {

  private fun distribution(javaMin: String?): File {
    val root = createTempDirectory("preflight").toFile()
    File(root, "bin").mkdirs()
    val binary = File(root, "bin/compose-preview-server")
    binary.writeText("#!/bin/sh\n")
    if (javaMin != null) {
      File(root, ServerJavaPreflight.MANIFEST).writeText("javaMin=$javaMin\n")
    }
    return binary
  }

  private fun failure(
    javaMin: String?,
    running: Int?,
    java: File? = File("/opt/jdk/bin/java"),
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    env: (String) -> String? = { null },
  ): String? {
    val binary = distribution(javaMin)
    return ServerJavaPreflight.failure(
      choice = ServerBinaryDiscovery.Choice(binary.path, "PATH"),
      distribution = distribution,
      env = env,
      javaExecutable = { java },
      javaFeatureVersion = { running },
    )
  }

  @Test
  fun `a JVM below the declared floor is refused`() {
    val message = assertNotNull(failure(javaMin = "21", running = 17))

    // The four facts. A message missing any one of them sends the reader back to guessing, which
    // is what the raw `UnsupportedClassVersionError` already did.
    assertContains(message, "Java 21")
    assertContains(message, "Java 17")
    assertContains(message, "/opt/jdk/bin/java")
    assertContains(message, "JAVA_HOME")
  }

  @Test
  fun `a JVM at or above the floor launches`() {
    assertNull(failure(javaMin = "17", running = 17))
    assertNull(failure(javaMin = "17", running = 21))
  }

  @Test
  fun `a distribution that declares no floor is not preflighted`() {
    // Every server released before the distribution began carrying the file. Refusing to launch
    // one, or inventing a number for it, would break a working setup to improve an error message.
    assertNull(failure(javaMin = null, running = 8))
  }

  @Test
  fun `an unusable declaration is not preflighted`() {
    assertNull(failure(javaMin = "", running = 8))
    assertNull(failure(javaMin = "seventeen", running = 8))
  }

  @Test
  fun `an unresolvable or unreadable JVM is not preflighted`() {
    assertNull(failure(javaMin = "21", running = 17, java = null))
    assertNull(failure(javaMin = "21", running = null))
  }

  @Test
  fun `the refusal names the flag of the distribution being launched`() {
    val server = assertNotNull(failure(javaMin = "21", running = 17))
    assertContains(server, ReleasedDistribution.SERVER.flag)

    val mcp =
      assertNotNull(failure(javaMin = "21", running = 17, distribution = ReleasedDistribution.MCP))
    assertContains(mcp, ReleasedDistribution.MCP.flag)
    assertContains(mcp, ReleasedDistribution.MCP.label)
  }

  @Test
  fun `the refusal says where the JVM came from`() {
    val fromHome =
      assertNotNull(
        failure(javaMin = "21", running = 17, env = { if (it == "JAVA_HOME") "/opt/jdk" else null })
      )
    assertContains(fromHome, "JAVA_HOME=/opt/jdk")

    val fromPath = assertNotNull(failure(javaMin = "21", running = 17))
    assertContains(fromPath, "first `java` on PATH")
  }

  @Test
  fun `the floor is read from beside the binary, through a symlink`() {
    val binary = distribution("21")
    val link = createTempDirectory("preflight-link").toFile().resolve("compose-preview-server")
    java.nio.file.Files.createSymbolicLink(link.toPath(), binary.toPath())

    // `PATH` and `--server-binary` both routinely name a link. Resolving relative to the link
    // rather than its target would look for the file in an unrelated directory and silently skip
    // the preflight on exactly the installs that use one.
    assertEquals(21, ServerJavaPreflight.declaredMinimum(link))
  }

  @Test
  fun `JAVA_HOME wins over PATH, and a JAVA_HOME with no java is left to the start script`() {
    val home = createTempDirectory("javahome").toFile()
    File(home, "bin").mkdirs()
    val java = File(home, "bin/java").apply { writeText("") }

    assertEquals(
      java.path,
      ServerJavaPreflight.resolveJava { if (it == "JAVA_HOME") home.path else "/nowhere" }?.path,
    )
    // Not a fallback to PATH: the start script would fail on this JAVA_HOME in its own words, and
    // checking a JVM it will never run is worse than checking none.
    assertNull(ServerJavaPreflight.resolveJava { if (it == "JAVA_HOME") "/no/such/home" else null })
  }

  @Test
  fun `the whole chain runs against a real JVM, with no seams`() {
    // The seam-driven cases above each pin one decision; this one pins that they compose — the
    // manifest is found beside the binary, a `java` is resolved from JAVA_HOME, that JVM is
    // actually executed and its output parsed, and the comparison lands. It runs the test JVM
    // against itself, so it needs no second JDK and cannot be version-specific.
    val here = System.getProperty("java.home")
    val env = { name: String -> if (name == "JAVA_HOME") here else null }
    val choice = { javaMin: String ->
      ServerBinaryDiscovery.Choice(distribution(javaMin).path, "PATH")
    }

    val refused =
      assertNotNull(
        ServerJavaPreflight.failure(choice("99"), ReleasedDistribution.SERVER, env = env)
      )
    assertContains(refused, "Java 99")
    assertContains(refused, "would have run on Java ${Runtime.version().feature()}")

    assertNull(ServerJavaPreflight.failure(choice("8"), ReleasedDistribution.SERVER, env = env))
  }

  @Test
  fun `the feature version is read out of the quoted value every vendor prints`() {
    assertEquals(21, ServerJavaPreflight.parseFeatureVersion("""openjdk version "21.0.12" 2026"""))
    assertEquals(17, ServerJavaPreflight.parseFeatureVersion("""openjdk version "17.0.19+10""""))
    assertEquals(25, ServerJavaPreflight.parseFeatureVersion("""java version "25" 2026-09-16"""))
    // Pre-9 spelling, where the feature version is the second component and not the first.
    assertEquals(8, ServerJavaPreflight.parseFeatureVersion("""java version "1.8.0_452""""))
    assertNull(ServerJavaPreflight.parseFeatureVersion("bash: java: command not found"))
  }
}
