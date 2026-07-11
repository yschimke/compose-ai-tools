package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class DaemonSupervisorTest {
  @Test
  fun `registerProject tolerates root directory without explicit project name`() {
    val supervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
      )

    val project = supervisor.registerProject(File("/"))

    assertThat(project.rootProjectName).isEqualTo("workspace")
    assertThat(project.workspaceId.value).startsWith("workspace-")
  }

  @Test
  fun `readingFromDisk resolves descriptor for a projectDir-remapped module`() {
    val root = createTempDirectory("cp-supervisor-test").toFile()
    // `:featureTasks` can be remapped to shared/features/tasks in settings.gradle.kts, so the Gradle
    // path does NOT mirror the on-disk layout. The layout fast path (<root>/featureTasks) must miss,
    // and the fallback scan must locate the descriptor by the modulePath recorded inside it.
    val previewsDir = File(root, "shared/features/tasks/build/compose-previews")
    previewsDir.mkdirs()
    File(previewsDir, "daemon-launch.json")
      .writeText(
        """
        {
          "schemaVersion": 2,
          "modulePath": ":featureTasks",
          "variant": "desktop",
          "enabled": true,
          "mainClass": "ee.schimke.composeai.daemon.DaemonMain",
          "classpath": [],
          "jvmArgs": [],
          "systemProperties": {},
          "workingDirectory": "${previewsDir.parentFile.parent}",
          "manifestPath": "manifest.json"
        }
        """
          .trimIndent(),
      )

    val project =
      RegisteredProject(
        workspaceId = WorkspaceId("ws-test"),
        rootProjectName = "client",
        path = root,
        knownModules = mutableListOf(":featureTasks"),
      )

    val descriptor = DescriptorProvider.readingFromDisk().descriptorFor(project, ":featureTasks")

    assertThat(descriptor.modulePath).isEqualTo(":featureTasks")
    assertThat(descriptor.variant).isEqualTo("desktop")
  }
}
