package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The end-to-end prototype: a builder's document, the components a real build discovered, and a
 * screen the Kotlin compiler accepts.
 *
 * `ComponentCallSiteCompileFunctionalTest` proves one component can be *called*. This proves a
 * screen can be *composed* — values bound, components nested into slots — which is the thing a UI
 * builder is for and the thing no test covered. The exporter it replaces asserted balanced braces
 * on its output; balanced braces are not a compiler.
 *
 * Nothing here is hand-fed. The catalog is whatever `composePreviewDiscover` wrote for a project of
 * ordinary previews, addressed by canonical id, so a change that stops a component being
 * discoverable — or stops its call site being printable — fails here rather than in a consumer.
 */
class ScreenGeneratorCompileFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun createTestProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "test-screen-generator"
        """
          .trimIndent()
      )

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.uiTooling)
            implementation(compose.components.uiToolingPreview)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Components.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Button
        import androidx.compose.material3.Card
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun LabelPreview() {
            Text(text = "Hello")
        }

        @Preview
        @Composable
        fun ActionPreview() {
            Button(onClick = {}) { Text(text = "Go") }
        }

        @Preview
        @Composable
        fun ContainerPreview() {
            Card { Text(text = "Inside") }
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  private fun runGradle(projectDir: File, vararg arguments: String) =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*arguments)
      .withPluginClasspath()
      .build()

  /** `<module>/<jvmOwner>.<name>`, the id a builder stores when a component is placed. */
  private fun idOf(components: ComponentRecordFile, name: String): String {
    val record =
      components.components.firstOrNull { it.symbol.name == name && it.code?.call != null }
    assertWithMessage(
        "no usable `%s` in the discovered catalog; components were %s",
        name,
        components.components.map {
          "${it.symbol.name}=${it.code?.call ?: it.code?.refusedReason}"
        },
      )
      .that(record)
      .isNotNull()
    return record!!.canonicalId
  }

  @Test
  fun `a screen composed from discovered components compiles`() {
    val projectDir = createTestProject()

    val discover = runGradle(projectDir, "composePreviewDiscover")
    assertThat(discover.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val components =
      json.decodeFromString(
        ComponentRecordFile.serializer(),
        File(projectDir, "build/compose-previews/components.json").readText(),
      )

    // The document a builder would hold after someone dropped a Card on the canvas, typed a
    // heading into a Text, and put a Button under it with its own label.
    val screen =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = idOf(components, "Card"),
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      componentId = idOf(components, "Text"),
                      arguments = mapOf("text" to ScreenValue.Text("Good morning")),
                    ),
                    ScreenNode(
                      componentId = idOf(components, "Button"),
                      slots =
                        mapOf(
                          "content" to
                            listOf(
                              ScreenNode(
                                componentId = idOf(components, "Text"),
                                arguments = mapOf("text" to ScreenValue.Text("Continue")),
                              )
                            )
                        ),
                    ),
                  )
              ),
          ),
      )

    val result = ScreenGenerator.generate(screen, components)
    val emitted =
      assertWithMessage(
          "generation refused: %s",
          (result as? ScreenGenerator.Result.Refused)?.reasons,
        )
        .that(result)
        .isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
        .let { result as ScreenGenerator.Result.Emitted }

    // The designed values reached the source, rather than the placeholders a call site prints.
    assertThat(emitted.source).contains("""Text(text = "Good morning")""")
    assertThat(emitted.source).contains("""Text(text = "Continue")""")
    // And by their *simple* names, imported once. Both `Text`s sit inside a receiver slot —
    // `Card`'s `ColumnScope` and `Button`'s `RowScope` — which the generator used to qualify on
    // the premise that an import could not reach inside one. The `contains` assertions above pass
    // either way, since a qualified call ends in the same characters; these are what tell the two
    // apart, and the compile below is what proves the imported spelling actually resolves.
    assertThat(emitted.source).contains("import androidx.compose.material3.Text")
    assertThat(emitted.source).doesNotContain("androidx.compose.material3.Text(text = ")
    // Stock Material 3 needs no opt-in at a call site. The markers the Compose compiler stamps
    // onto the JVM method are not themselves opt-in requirements, and reading the meta-annotation
    // closure rather than the direct annotations reported them anyway — which told consumers to
    // opt into Compose internals to place a `Card`. This is the gate on that against a real
    // library; `ComposableSignatureTest` covers the same trap on fixtures.
    assertThat(emitted.requiredOptIns).isEmpty()
    assertThat(emitted.source).doesNotContain("@OptIn")

    val generated = File(projectDir, "src/main/kotlin/generated")
    generated.mkdirs()
    File(generated, "HomeScreen.kt").writeText(emitted.source)

    // `GradleRunner.build()` throws on failure, so reaching this line is the gate: the Kotlin
    // compiler accepted a screen assembled entirely from the discovered catalog.
    val compile = runGradle(projectDir, "compileKotlin")
    assertThat(compile.task(":compileKotlin")?.outcome)
      .isIn(listOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
  }
}
