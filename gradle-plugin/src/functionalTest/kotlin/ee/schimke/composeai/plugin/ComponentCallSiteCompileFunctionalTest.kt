package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The compile gate for `ComponentSnippets`: the Kotlin compiler decides whether printed call sites
 * are real. `ComponentSnippetsTest` only checks text against hand-written records. This discovers
 * real material3 components, writes their `components.json` snippets (the exact bytes consumers
 * get) into the project, and compiles them.
 *
 * **The vacuity guard matters most:** an empty file compiles, so the test names components that
 * must have been emitted.
 */
class ComponentCallSiteCompileFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Components the generator must be able to print.
   *
   * `Checkbox` / `Switch` cover nullable callbacks (`((Boolean) -> Unit)?`), answered with `null`.
   *
   * `TextField` / `OutlinedTextField` cover the constructed placeholder (#5067): `state:
   * TextFieldState` has no literal, and only the compiler can confirm `TextFieldState()` (the JVM
   * sees just the marker bridge). They also emit an import beyond the callable, and check that
   * `rememberTextFieldState` was found and preferred ([assertPrefersTheRememberFactory]).
   */
  private val expectedEmitted =
    setOf("Text", "Button", "Card", "Checkbox", "Switch", "TextField", "OutlinedTextField")

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
        rootProject.name = "test-call-site-compile"
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
    // Ordinary previews over stock Material 3 — the shape the library-component inference exists
    // for. Nothing here is written for the generator's benefit.
    File(srcDir, "Components.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Button
        import androidx.compose.material3.Card
        import androidx.compose.material3.Checkbox
        import androidx.compose.material3.OutlinedTextField
        import androidx.compose.material3.Switch
        import androidx.compose.material3.Text
        import androidx.compose.material3.TextField
        import androidx.compose.foundation.text.input.rememberTextFieldState
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

        @Preview
        @Composable
        fun TogglesPreview() {
            Checkbox(checked = true, onCheckedChange = {})
            Switch(checked = true, onCheckedChange = {})
        }

        // The `state`-based text fields: a required parameter with no literal, whose type
        // nonetheless constructs itself with no arguments (issue #5067).
        @Preview
        @Composable
        fun FieldsPreview() {
            TextField(state = rememberTextFieldState())
            OutlinedTextField(state = rememberTextFieldState())
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

  @Test
  fun `printed call sites for discovered Material3 components compile`() {
    val projectDir = createTestProject()

    val discover = runGradle(projectDir, "composePreviewDiscover")
    assertThat(discover.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val componentsFile = File(projectDir, "build/compose-previews/components.json")
    assertThat(componentsFile.exists()).isTrue()
    val components =
      json.decodeFromString(ComponentRecordFile.serializer(), componentsFile.readText())

    val emitted = mutableMapOf<String, ComponentCode>()
    val refused = mutableMapOf<String, String>()
    for (record in components.components) {
      val code = record.code
      // A record with no `code` at all is a producer bug, not a refusal, so it is reported as one
      // rather than counted among the components that legitimately have no writable call site.
      when {
        code == null -> refused[record.symbol.name] = "record carried no `code` field"
        code.call != null -> emitted[record.symbol.name] = code
        else -> refused[record.symbol.name] = code.refusedReason ?: "refused without a reason"
      }
    }

    // Vacuity guard; the refusals are in the message to explain why something was skipped.
    assertWithMessage(
        "discovered %s; refusals were %s",
        components.components.map { it.symbol.name },
        refused,
      )
      .that(emitted.keys)
      .containsAtLeastElementsIn(expectedEmitted)

    assertPrefersTheRememberFactory(emitted)

    writeGeneratedCallSites(projectDir, emitted)

    // `build()` throws on failure, so reaching here is the gate; `FROM_CACHE` still means these
    // sources compiled.
    val compile = runGradle(projectDir, "compileKotlin")
    assertThat(compile.task(":compileKotlin")?.outcome)
      .isIn(listOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
  }

  /**
   * Text fields must use `rememberTextFieldState()`, which the compiler can't distinguish from
   * `TextFieldState()`. The only check of the preference against a real classpath.
   */
  private fun assertPrefersTheRememberFactory(emitted: Map<String, ComponentCode>) {
    for (name in listOf("TextField", "OutlinedTextField")) {
      val code = emitted.getValue(name)
      assertWithMessage("%s call site", name)
        .that(code.call)
        .contains("state = rememberTextFieldState()")
      assertWithMessage("%s imports", name)
        .that(code.imports)
        .contains("androidx.compose.foundation.text.input.rememberTextFieldState")
    }
  }

  /** One `@Composable` per snippet, so a bad snippet fails with its component's name. */
  private fun writeGeneratedCallSites(projectDir: File, emitted: Map<String, ComponentCode>) {
    // Opt-in markers, imported once and applied per function: generated wrappers inherit nothing,
    // so compiling here checks `requiredOptIns`.
    val imports =
      (emitted.values.flatMap { it.imports } + emitted.values.flatMap { it.requiredOptIns })
        .toSortedSet()
    val body =
      emitted.entries
        .sortedBy { it.key }
        .joinToString("\n\n") { (name, code) ->
          val optIn =
            if (code.requiredOptIns.isEmpty()) ""
            else
              code.requiredOptIns.joinToString(
                prefix = "@OptIn(",
                postfix = "::class)\n",
                separator = "::class, ",
              ) {
                it.substringAfterLast('.')
              }
          """
          |$optIn@Composable
          |fun Generated$name() {
          |    ${code.call}
          |}
          """
            .trimMargin()
        }
    val generatedDir = File(projectDir, "src/main/kotlin/generated")
    generatedDir.mkdirs()
    File(generatedDir, "GeneratedCallSites.kt")
      .writeText(
        buildString {
          appendLine("package generated")
          appendLine()
          appendLine("import androidx.compose.runtime.Composable")
          imports.forEach { appendLine("import $it") }
          appendLine()
          appendLine(body)
        }
      )
  }
}
