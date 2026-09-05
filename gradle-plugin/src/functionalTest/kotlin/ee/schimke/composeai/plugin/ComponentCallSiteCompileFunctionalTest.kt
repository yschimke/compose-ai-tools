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
 * The compile gate for `ComponentSnippets` — the Kotlin compiler, not an argument, deciding whether
 * a printed call site is real.
 *
 * `ComponentSnippetsTest` asserts the *text* of a snippet against a hand-written record. That is a
 * regression net and nothing more: every expectation in it is a claim I made about Kotlin, so a
 * wrong belief about what `{}` infers against, or about which parameters `Button` actually
 * defaults, produces a green test and source that does not build. This test removes me from the
 * loop. It discovers real `androidx.compose.material3` components out of a real project, prints
 * their call sites, writes them into that project, and compiles them.
 *
 * It reads `ComponentRecord.code` off the written `components.json` rather than calling
 * `ComponentSnippets` itself, so what is compiled here is the exact bytes a consumer receives. A
 * generator that worked in-process while the record persisted something else would pass a test that
 * called the generator, and fail every consumer.
 *
 * **The vacuity guard is the important assertion.** A generator that refused everything would emit
 * an empty file that compiles perfectly, so "the build passed" alone proves nothing. The test
 * therefore names components it insists were emitted, and fails if the generator quietly stopped
 * producing them.
 */
class ComponentCallSiteCompileFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Components whose call sites this generator is expected to be able to print.
   *
   * `Checkbox` and `Switch` are here for their **nullable callback** (`onCheckedChange: ((Boolean)
   * -> Unit)?`): no lambda-shaped rule accepts a nullable function type, so both were refused until
   * the generator learned to answer `null` for any nullable parameter. They are what keeps that
   * answer honest against a real Material 3 signature rather than a hand-written record.
   *
   * `TextField` and `OutlinedTextField` are here for the **constructed placeholder** (issue #5067):
   * their required `state: TextFieldState` has no literal, and the two were the last refusals on
   * the measured Material 3 surface. `TextFieldState`'s constructor parameters all carry defaults,
   * so `TextFieldState()` compiles from source even though the JVM sees only a `(String, long, int,
   * DefaultConstructorMarker)` bridge — which is exactly why the claim has to be settled by the
   * compiler here rather than by a hand-written record. They also cover the invariant that moved:
   * these are the first snippets that emit an import beyond the callable.
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

    // Vacuity guard: an empty generated file compiles, so the compile below only means something
    // if the generator actually produced these. The refusals are in the message because "why was
    // `Button` skipped" is the first question a failure here raises.
    assertWithMessage(
        "discovered %s; refusals were %s",
        components.components.map { it.symbol.name },
        refused,
      )
      .that(emitted.keys)
      .containsAtLeastElementsIn(expectedEmitted)

    writeGeneratedCallSites(projectDir, emitted)

    // `GradleRunner.build()` throws on a failed build, so reaching this line *is* the gate: the
    // Kotlin compiler accepted every printed call site. The outcome check only rules out the task
    // having been skipped — `FROM_CACHE` is a pass because a cache hit still means these exact
    // sources compiled.
    val compile = runGradle(projectDir, "compileKotlin")
    assertThat(compile.task(":compileKotlin")?.outcome)
      .isIn(listOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
  }

  /**
   * Writes one `@Composable` per snippet into the project's own source set.
   *
   * Each call site goes in its own function rather than one shared body so a single bad snippet
   * fails with the name of the component that produced it, instead of the first compile error
   * hiding the rest.
   */
  private fun writeGeneratedCallSites(projectDir: File, emitted: Map<String, ComponentCode>) {
    // The markers each call needs, imported once and applied per function below. A component
    // guarded by `@ExperimentalMaterial3Api` compiles in its own preview because that file opted
    // in; a generated wrapper inherits nothing, so this is the half of the contract the caller
    // owns — and compiling it here is what stops `requiredOptIns` being an unchecked claim.
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
