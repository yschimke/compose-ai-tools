package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ValidateComposeFloorTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `requested version below floor passes when a strict constraint selects above floor`() {
    val repository = temporaryFolder.newFolder("repository")
    publishModule(repository, "androidx.compose.foundation", "foundation", "1.6.5")
    publishModule(repository, "androidx.compose.foundation", "foundation", "1.10.2")
    val project =
      ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
    project.repositories.maven { url = project.uri(repository) }
    val classpath = project.configurations.create("renderClasspath") { isCanBeResolved = true }
    project.dependencies.add(classpath.name, "androidx.compose.foundation:foundation:1.6.5")
    project.dependencies.constraints.add(
      classpath.name,
      "androidx.compose.foundation:foundation",
    ) {
      version { strictly("1.10.2") }
    }

    // This used to throw from eachDependency after reading the original 1.6.5 request, before
    // Gradle could expose the 1.10.2 selection made by the strict constraint.
    AndroidPreviewSupport.applyRenderGraphResolutionRules(classpath, floorComposeLine = false)
    val root = classpath.incoming.resolutionResult.rootComponent.get()

    assertThat(ValidateComposeFloorTask.findBelowFloor(root)).isNull()
    val requestedEdge =
      root.dependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .single { !it.isConstraint }
    assertThat(requestedEdge.requested.displayName)
      .contains("androidx.compose.foundation:foundation:1.6.5")
    assertThat(requestedEdge.selected.id.displayName)
      .contains("androidx.compose.foundation:foundation:1.10.2")
  }

  @Test
  fun `selected version below floor is still rejected`() {
    val repository = temporaryFolder.newFolder("repository")
    publishModule(repository, "androidx.compose.ui", "ui", "1.9.5")
    val project =
      ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
    project.repositories.maven { url = project.uri(repository) }
    val classpath = project.configurations.create("renderClasspath") { isCanBeResolved = true }
    project.dependencies.add(classpath.name, "androidx.compose.ui:ui:1.9.5")
    AndroidPreviewSupport.applyRenderGraphResolutionRules(classpath, floorComposeLine = false)
    val root = classpath.incoming.resolutionResult.rootComponent.get()

    assertThat(ValidateComposeFloorTask.findBelowFloor(root))
      .isEqualTo(ValidateComposeFloorTask.ResolvedComposeModule("androidx.compose.ui:ui", "1.9.5"))

    val task =
      project.tasks.register("validateComposeFloor", ValidateComposeFloorTask::class.java).get()
    task.runtimeClasspathRoot.set(root)
    val failure = assertThrows(GradleException::class.java) { task.validate() }
    assertThat(failure).hasMessageThat().contains("androidx.compose.ui:ui resolves to 1.9.5")
    assertThat(failure)
      .hasMessageThat()
      .contains(AndroidPreviewSupport.RENDERER_COMPOSE_LINK_FLOOR_VERSION)
  }

  private fun publishModule(repository: File, group: String, module: String, version: String) {
    val moduleDir = repository.resolve(group.replace('.', '/')).resolve(module).resolve(version)
    moduleDir.mkdirs()
    moduleDir
      .resolve("$module-$version.pom")
      .writeText(
        """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId>
        <artifactId>$module</artifactId>
        <version>$version</version>
      </project>
      """
          .trimIndent()
      )
  }
}
