package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleRevisionBuilderTest {
  @Test
  fun `missing wrapper fails closed with an actionable log`() {
    val root = Files.createTempDirectory("revision").toFile()
    val logs = mutableListOf<String>()

    val result =
      GradleRevisionBuilder(onLog = logs::add)
        .build(root, ServeModuleRef(gradlePath = "app", relativePath = "app"), true)

    assertNull(result)
    assertTrue(logs.single().contains("no executable gradlew"))
  }

  @Test
  fun `gradle failure is logged and produces no revision`() {
    val root = Files.createTempDirectory("revision").toFile()
    root.resolve("app").mkdirs()
    root.resolve("gradlew").apply {
      writeText("#!/bin/sh\necho revision-build\nexit 7\n")
      setExecutable(true)
    }
    val logs = mutableListOf<String>()

    val result =
      GradleRevisionBuilder(timeoutSeconds = 5, onLog = logs::add)
        .build(root, ServeModuleRef(gradlePath = "app", relativePath = "app"), true)

    assertNull(result)
    assertTrue(logs.any { it == "[gradle] revision-build" })
  }
}
