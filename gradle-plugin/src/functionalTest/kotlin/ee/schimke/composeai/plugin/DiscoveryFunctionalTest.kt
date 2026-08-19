package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun createCmpTestProject(): File {
    val projectDir = tempDir.root

    // settings.gradle.kts
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
        rootProject.name = "test-project"
        """
          .trimIndent()
      )

    // build.gradle.kts
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

    // Source file with previews
    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun RedBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red)) {
                Text("Red")
            }
        }

        @Preview
        @Composable
        fun BlueBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Blue)) {
                Text("Blue")
            }
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  @Test
  fun `composePreviewDiscover finds annotated composables`() {
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifestFile = File(projectDir, "build/compose-previews/previews.json")
    assertThat(manifestFile.exists()).isTrue()

    val manifest = json.decodeFromString<PreviewManifest>(manifestFile.readText())
    assertThat(manifest.previews).hasSize(2)

    val names = manifest.previews.map { it.functionName }
    assertThat(names).containsExactly("RedBoxPreview", "BlueBoxPreview")

    // AS-parity: bare `@Preview` with no device / showSystemUi /
    // widthDp / heightDp must serialize null on both axes so renderers
    // wrap to the composable's intrinsic size instead of defaulting
    // to a 400×800 phone frame.
    manifest.previews.forEach {
      assertThat(it.params.widthDp).isNull()
      assertThat(it.params.heightDp).isNull()
      assertThat(it.params.device).isNull()
      assertThat(it.params.showSystemUi).isFalse()
    }

    // P0.2 daemon prep: at least one preview must surface its `sourceFile`
    // so the daemon's incremental-discovery path (B2.2) has a path to
    // file-event correlation. Field is populated from ClassGraph's
    // bytecode `SourceFile` attribute and rewritten to a package-qualified
    // path in DiscoverPreviewsTask — see PreviewData.sourceFile.
    assertThat(manifest.previews.any { !it.sourceFile.isNullOrBlank() }).isTrue()

    // …and where in that file it is, from the classfile's LineNumberTable. This addresses the
    // *declaration* rather than the file, which is what lets the playground handoff seed one
    // composable instead of every component in a section file.
    //
    // The anchor points INSIDE the body: the fixture declares `RedBoxPreview` with `@Preview` and
    // `@Composable` above it and a `Box { Text(…) }` inside, so what is recorded is the `Box` line
    // — strictly below the `fun` line. Both previews live in one file, `RedBoxPreview` first, so
    // the two anchors are ordered. Asserting the relationship rather than exact numbers keeps this
    // from breaking every time the fixture gains a line.
    val red = manifest.previews.single { it.functionName == "RedBoxPreview" }
    val blue = manifest.previews.single { it.functionName == "BlueBoxPreview" }
    assertThat(red.bodyLine).isNotNull()
    assertThat(blue.bodyLine).isNotNull()
    assertThat(red.bodyLine!!).isGreaterThan(0)
    assertThat(blue.bodyLine!!).isGreaterThan(red.bodyLine!!)
    // Deliberately NOT asserted against a recorded *end* line: there isn't one. Kotlin emits an
    // inlined body into its caller with SMAP line numbers past the end of the caller's file, so a
    // method's last line is not a number worth publishing — see `PreviewInfo.bodyLine`.
  }

  @Test
  fun `composePreviewDiscover expands @OverrideVariant into synthetic seeded previews`() {
    val projectDir = createCmpTestProject()

    // Declare the annotation locally with the discovered FQN so the test needs no external
    // preview-annotations artifact — discovery matches by FQN. `@Repeatable` exercises the
    // synthetic `.Container` holder path for the two-variant case.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "OverrideVariant.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class OverrideVariant(
            val name: String,
            val interaction: VariantInteraction = VariantInteraction.None,
            val interactionIndex: Int = 0,
            val booleans: Array<String> = [],
            val strings: Array<String> = [],
            val ints: Array<String> = [],
            val floats: Array<String> = [],
            val colors: Array<String> = [],
        )

        enum class VariantInteraction { None, Hovered, Focused, Pressed }
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Toggle.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.OverrideVariant
        import ee.schimke.composeai.preview.VariantInteraction

        @Preview
        @OverrideVariant(name = "off", booleans = ["checked=false"])
        @OverrideVariant(name = "labelled", strings = ["label=Hi", "sub#1=Two"])
        @OverrideVariant(name = "hovered", interaction = VariantInteraction.Hovered)
        @OverrideVariant(
            name = "focused",
            interaction = VariantInteraction.Focused,
            interactionIndex = 1,
        )
        @OverrideVariant(name = "pressed", interaction = VariantInteraction.Pressed)
        @Composable
        fun TogglePreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Toggle") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val toggles = manifest.previews.filter { it.functionName == "TogglePreview" }
    // Base preview + one synthetic seeded preview per `@OverrideVariant`.
    assertThat(toggles).hasSize(6)

    val base = toggles.single { it.overrides == null }
    assertThat(base.captures.single().renderOutput).doesNotContain("_VARIANT_")

    val off = toggles.single { it.overrides?.name == "off" }
    assertThat(off.id).endsWith("_VARIANT_off")
    assertThat(off.captures.single().renderOutput).contains("_VARIANT_off")
    assertThat(off.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "checked", index = null, kind = OverrideSeedKind.BOOLEAN, raw = "false")
      )

    // Multiple typed seeds, including an indexed knob (`sub#1` → seedKey `sub[1]`).
    val labelled = toggles.single { it.overrides?.name == "labelled" }
    assertThat(labelled.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "label", index = null, kind = OverrideSeedKind.STRING, raw = "Hi"),
        OverrideSeed(key = "sub", index = 1, kind = OverrideSeedKind.STRING, raw = "Two"),
      )

    val hovered = toggles.single { it.overrides?.name == "hovered" }
    assertThat(hovered.overrides!!.seeds).isEmpty()
    assertThat(hovered.overrides!!.interaction).isEqualTo(OverrideVariantInteraction.Hovered)
    assertThat(hovered.captures.single().hover).isEqualTo(HoverCapture(targetIndex = 0))
    assertThat(hovered.captures.single().focus).isNull()

    val focused = toggles.single { it.overrides?.name == "focused" }
    assertThat(focused.captures.single().focus).isEqualTo(FocusCapture(tabIndex = 1))
    assertThat(focused.captures.single().hover).isNull()

    val pressed = toggles.single { it.overrides?.name == "pressed" }
    assertThat(pressed.captures.single().focus)
      .isEqualTo(FocusCapture(tabIndex = 0, pressed = true))
  }

  /**
   * The hoisted form: a matrix declared once on an annotation class and applied with one line.
   *
   * This is what stops a catalog retyping a cross product per component — five sizes by two shapes
   * is nine cells, and m3-catalog had 237 near-identical annotations across thirteen blocks before
   * `@OverrideVariant` could sit on an annotation class.
   *
   * Three properties are asserted together because they are the ones a naive implementation gets
   * wrong: hoisted variants are found at all; stacking two annotations is a **union** (2 + 1 = 3
   * variants, not 2 x 1 = 2 or some product); and a name carried by both is de-duplicated rather
   * than emitted twice onto the same `_VARIANT_` output path.
   */
  @Test
  fun `composePreviewDiscover expands @OverrideVariant hoisted onto an annotation class`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "OverrideVariant.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class OverrideVariant(
            val name: String,
            val booleans: Array<String> = [],
            val strings: Array<String> = [],
            val ints: Array<String> = [],
            val floats: Array<String> = [],
            val colors: Array<String> = [],
        )
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Sized.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.OverrideVariant

        @OverrideVariant(name = "xs", strings = ["size=xs"])
        @OverrideVariant(name = "l", strings = ["size=l"])
        annotation class SizeMatrix

        @OverrideVariant(name = "square", strings = ["shape=square"])
        annotation class ShapeMatrix

        @Preview
        @SizeMatrix
        @ShapeMatrix
        @Composable
        fun SizedPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Sized") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val sized = manifest.previews.filter { it.functionName == "SizedPreview" }
    // Base + three variants. Union, not product: two annotations carrying 2 and 1 cells give 3.
    assertThat(sized).hasSize(4)
    assertThat(sized.mapNotNull { it.overrides?.name }).containsExactly("xs", "l", "square")

    val xs = sized.single { it.overrides?.name == "xs" }
    assertThat(xs.id).endsWith("_VARIANT_xs")
    assertThat(xs.captures.single().renderOutput).contains("_VARIANT_xs")
    assertThat(xs.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "size", index = null, kind = OverrideSeedKind.STRING, raw = "xs")
      )

    // The annotation is reachable both through ClassGraph's meta-annotation flattening and through
    // the explicit closure walk. Reaching it twice must still produce one variant, not two.
    assertThat(sized.count { it.overrides?.name == "square" }).isEqualTo(1)
  }

  /**
   * `@PermissionPreview` (issue #3676) — the static render lane's way to say which permissions a
   * capture should be taken under, so a permission-gated screen's granted branch is reachable
   * without threading a preview-only `granted: Boolean` through the composable.
   *
   * Three things at once, because they fail together and a partial pass is misleading: the
   * annotation is matched by FQN (declared locally here, exactly as the `@OverrideVariant` tests
   * do, so the test needs no published `preview-annotations` artifact); its `grants` strings parse
   * into the typed capture model and are stamped onto **every** capture of the function; and a
   * malformed entry is dropped with a warning while its well-formed siblings survive.
   *
   * The malformed half matters more than it looks: the symptom of a silently-dropped grant is a
   * preview named "granted" that renders the denied branch — which is the original defect, and is
   * invisible unless discovery says something.
   */
  @Test
  fun `composePreviewDiscover stamps @PermissionPreview grants onto every capture`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PermissionPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class PermissionPreview(val grants: Array<String> = [])
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Camera.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.PermissionPreview

        @Preview
        @Composable
        fun CameraDeniedPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Denied") }
        }

        @Preview
        @PermissionPreview(
            grants = [
                "android.permission.CAMERA=granted",
                "android.permission.RECORD_AUDIO=DENIED",
                "android.permission.ACCESS_FINE_LOCATION",
            ]
        )
        @Composable
        fun CameraGrantedPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Granted") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    // The un-annotated sibling stays clean — the annotation is per-function, not per-module.
    val denied = manifest.previews.single { it.functionName == "CameraDeniedPreview" }
    assertThat(denied.captures.single().permissions).isNull()

    val granted = manifest.previews.single { it.functionName == "CameraGrantedPreview" }
    assertThat(granted.captures).isNotEmpty()
    for (capture in granted.captures) {
      assertThat(capture.permissions)
        .isEqualTo(
          PermissionsCapture(
            grants =
              mapOf(
                "android.permission.CAMERA" to PermissionGrantCaptureState.GRANTED,
                "android.permission.RECORD_AUDIO" to PermissionGrantCaptureState.DENIED,
              )
          )
        )
    }

    // The third entry has no `=`, so it is dropped — loudly, naming the entry.
    assertThat(result.output).contains("@PermissionPreview")
    assertThat(result.output).contains("android.permission.ACCESS_FINE_LOCATION")
  }

  /**
   * Two hoisted matrices that disagree about one cell. The name is the rendered output's identity,
   * so emitting both would have the second silently overwrite the first's PNG.
   */
  @Test
  fun `composePreviewDiscover keeps the first of a colliding @OverrideVariant name and warns`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "OverrideVariant.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class OverrideVariant(
            val name: String,
            val booleans: Array<String> = [],
            val strings: Array<String> = [],
            val ints: Array<String> = [],
            val floats: Array<String> = [],
            val colors: Array<String> = [],
        )
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Clash.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.OverrideVariant

        @OverrideVariant(name = "wide", strings = ["size=xl"])
        annotation class FirstMatrix

        @OverrideVariant(name = "wide", strings = ["width=wide"])
        annotation class SecondMatrix

        @Preview
        @FirstMatrix
        @SecondMatrix
        @Composable
        fun ClashPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Clash") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("more than one @OverrideVariant named 'wide'")

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val clash = manifest.previews.filter { it.functionName == "ClashPreview" }
    // Base + exactly one `wide`, seeded from the annotation that won.
    assertThat(clash).hasSize(2)
    assertThat(clash.single { it.overrides != null }.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "size", index = null, kind = OverrideSeedKind.STRING, raw = "xl")
      )
  }

  /**
   * `@PreviewAxis`: the declarative form of a variant matrix. Two axes on one function multiply
   * into their cross product minus the base cell, each carrying its full assignment as props.
   *
   * The assertions are exactly the properties a naive expansion gets wrong: axes multiply (rather
   * than unioning, which is how stacked `@OverrideVariant`s combine); the all-defaults cell is
   * skipped rather than emitted as a duplicate of the base render; a default value is never seeded
   * but IS part of the cell's props; and `namesEveryValue` puts an axis in every name.
   */
  @Test
  fun `composePreviewDiscover expands @PreviewAxis into the cross product`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PreviewAxis.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class PreviewAxis(
            val key: String,
            val values: Array<String>,
            val default: String = "",
            val kind: PreviewAxisKind = PreviewAxisKind.STRING,
            val slugs: Array<String> = [],
            val namesEveryValue: Boolean = false,
            val order: Int = 0,
        )

        enum class PreviewAxisKind { STRING, BOOLEAN, INT, FLOAT }
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Matrix.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.PreviewAxis

        @Preview
        @PreviewAxis(
            key = "size",
            values = ["xs", "s", "m"],
            default = "s",
            namesEveryValue = true,
            order = 1,
        )
        @PreviewAxis(key = "shape", values = ["round", "square"], order = 2)
        @Composable
        fun MatrixPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Matrix") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val matrix = manifest.previews.filter { it.functionName == "MatrixPreview" }
    // 3 sizes x 2 shapes = 6 cells, minus the all-defaults one, plus the base preview.
    assertThat(matrix).hasSize(6)
    assertThat(matrix.mapNotNull { it.overrides?.name })
      .containsExactly("xs", "xs-square", "s-square", "m", "m-square")

    // `size` names every cell (`s-square`, not `square`); `shape` only when off its default.
    val sSquare = matrix.single { it.overrides?.name == "s-square" }
    // A default value is never seeded — an unseeded knob already resolves to it…
    assertThat(sSquare.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "shape", index = null, kind = OverrideSeedKind.STRING, raw = "square")
      )
    // …but it IS part of what the cell is, so it rides on the props.
    assertThat(sSquare.overrides!!.props.map { it.key to it.value })
      .containsExactly("size" to "s", "shape" to "square")

    val xsSquare = matrix.single { it.overrides?.name == "xs-square" }
    assertThat(xsSquare.overrides!!.seeds).hasSize(2)
    assertThat(xsSquare.overrides!!.props.map { it.key to it.value })
      .containsExactly("size" to "xs", "shape" to "square")

    // The base render is untouched: no seeds, no props, no `_VARIANT_` tag.
    val base = matrix.single { it.overrides == null }
    assertThat(base.captures.single().renderOutput).doesNotContain("_VARIANT_")
  }

  /** Axes hoisted onto an annotation class multiply with a function's own — the composable case. */
  @Test
  fun `composePreviewDiscover multiplies hoisted @PreviewAxis with a direct one`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PreviewAxis.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class PreviewAxis(
            val key: String,
            val values: Array<String>,
            val default: String = "",
            val kind: PreviewAxisKind = PreviewAxisKind.STRING,
            val slugs: Array<String> = [],
            val namesEveryValue: Boolean = false,
            val order: Int = 0,
        )

        enum class PreviewAxisKind { STRING, BOOLEAN, INT, FLOAT }
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Hoisted.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.PreviewAxis
        import ee.schimke.composeai.preview.PreviewAxisKind

        @PreviewAxis(key = "shape", values = ["round", "square"], order = 1)
        annotation class ShapeAxis

        @Preview
        @ShapeAxis
        @PreviewAxis(
            key = "selected",
            values = ["true", "false"],
            default = "true",
            kind = PreviewAxisKind.BOOLEAN,
            slugs = ["on", "off"],
            order = 2,
        )
        @Composable
        fun HoistedPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Hoisted") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val hoisted = manifest.previews.filter { it.functionName == "HoistedPreview" }
    // 2 shapes x 2 selected = 4 cells, minus the all-defaults one, plus the base. A hoisted axis
    // MULTIPLIES with a direct one — a union would have given 2 variants, not 3.
    assertThat(hoisted).hasSize(4)
    assertThat(hoisted.mapNotNull { it.overrides?.name })
      .containsExactly("square", "off", "square-off")

    // `slugs` renames the values for the name only; the seed still carries the value, typed
    // BOOLEAN so a `previewOverrideBoolean` read honours it.
    val off = hoisted.single { it.overrides?.name == "off" }
    assertThat(off.overrides!!.seeds)
      .containsExactly(
        OverrideSeed(key = "selected", index = null, kind = OverrideSeedKind.BOOLEAN, raw = "false")
      )
    assertThat(off.overrides!!.props.map { it.key to it.value })
      .containsExactly("shape" to "round", "selected" to "false")
  }

  /**
   * The three ways an axis expansion can go wrong quietly, all in one project so the warnings are
   * asserted against a single run.
   *
   * `Untyped` — a `BOOLEAN` axis whose values are not boolean literals. The renderer's seed parser
   * drops what it cannot parse, so the cell would bake as a duplicate of the base render while the
   * catalog published props claiming it was that cell: a wrong entry, not a missing one.
   *
   * `Colliding` — two axes sharing a value name. No authoring mistake required, and a name is a
   * render output path, so the two cells would race for one file.
   *
   * `Hoisted` — the documented hoisting flow, which must NOT warn. A hoisted axis is reached twice
   * by design (ClassGraph flattens it into the method's annotation list, and the meta-annotation
   * walk finds it again), and warning on that would fire on every correct use.
   */
  @Test
  fun `composePreviewDiscover rejects unusable @PreviewAxis declarations and warns`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PreviewAxis.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Repeatable
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class PreviewAxis(
            val key: String,
            val values: Array<String>,
            val default: String = "",
            val kind: PreviewAxisKind = PreviewAxisKind.STRING,
            val slugs: Array<String> = [],
            val namesEveryValue: Boolean = false,
            val order: Int = 0,
        )

        enum class PreviewAxisKind { STRING, BOOLEAN, INT, FLOAT }
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Bad.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        import ee.schimke.composeai.preview.PreviewAxis
        import ee.schimke.composeai.preview.PreviewAxisKind

        @Preview
        @PreviewAxis(
            key = "selected",
            values = ["on", "off"],
            kind = PreviewAxisKind.BOOLEAN,
        )
        @Composable
        fun UntypedPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Untyped") }
        }

        @Preview
        @PreviewAxis(key = "a", values = ["p", "q"], order = 1)
        @PreviewAxis(key = "b", values = ["p", "q"], order = 2)
        @Composable
        fun CollidingPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Colliding") }
        }

        @PreviewAxis(key = "shape", values = ["round", "square"])
        annotation class ShapeOnly

        @Preview
        @ShapeOnly
        @Composable
        fun HoistedOkPreview() {
            Box(modifier = Modifier.size(50.dp)) { Text("Hoisted") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()
    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    // The BOOLEAN axis is ignored outright, so the function keeps only its base preview — rather
    // than gaining a cell that bakes identically to it under a props claim.
    assertThat(result.output).contains("are not valid BOOLEAN literals")
    assertThat(manifest.previews.filter { it.functionName == "UntypedPreview" }).hasSize(1)

    // `a=q,b=p` and `a=p,b=q` both name themselves `q`; the second is dropped, `q-q` survives.
    assertThat(result.output).contains("collide on the name(s) q")
    val colliding = manifest.previews.filter { it.functionName == "CollidingPreview" }
    assertThat(colliding.mapNotNull { it.overrides?.name }).containsExactly("q", "q-q")

    // …and the correct hoisted case is silent about duplicates while still producing its cell.
    assertThat(result.output)
      .doesNotContain("declares @PreviewAxis(key = \"shape\") more than once")
    val hoisted = manifest.previews.filter { it.functionName == "HoistedOkPreview" }
    assertThat(hoisted.mapNotNull { it.overrides?.name }).containsExactly("square")
  }

  @Test
  fun `composePreviewDiscover aggregates @ColorCatalog tokens into catalog sheets`() {
    val projectDir = createCmpTestProject()

    // Declare the annotation locally with the discovered FQN so the test needs no external
    // `preview-annotations` artifact — discovery matches by FQN + FIELD target, nothing else.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "ColorCatalog.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FIELD)
        annotation class ColorCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Tokens.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.graphics.Color
        import ee.schimke.composeai.preview.ColorCatalog

        @ColorCatalog(group = "Brand") val Coral: Color = Color(0xFFFF6F61)
        @ColorCatalog(name = "Brand Gold", group = "Brand") val Gold: Color = Color(0xFFFFD700)
        @ColorCatalog(group = "Semantic") val Danger: Color = Color(0xFFC62828)
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val catalogs = manifest.previews.filter { it.params.kind == PreviewKind.CATALOG }

    // Two groups (Brand, Semantic) → two group sheets, plus a module-wide aggregate.
    assertThat(catalogs.map { it.id })
      .containsExactly("colorcatalog__Brand", "colorcatalog__Semantic", "colorcatalog__all")

    val brand = catalogs.first { it.id == "colorcatalog__Brand" }
    // `name` defaults to the property name (`Coral`); the explicit "Brand Gold" overrides it.
    assertThat(brand.params.catalogTokens.map { it.label }).containsExactly("Coral", "Brand Gold")
    assertThat(brand.params.catalogTokens.map { it.member }).containsExactly("Coral", "Gold")
    // Coordinates point at the file's synthetic `TokensKt` class for render-time reflection.
    assertThat(brand.params.catalogTokens.first().className).endsWith("TokensKt")

    // The module aggregate carries every token across both groups.
    assertThat(catalogs.first { it.id == "colorcatalog__all" }.params.catalogTokens).hasSize(3)
    // Colour tokens are tagged COLOR so the renderer picks the swatch layout.
    assertThat(brand.params.catalogTokens.map { it.tokenKind })
      .containsExactly(CatalogTokenKind.COLOR, CatalogTokenKind.COLOR)
  }

  @Test
  fun `composePreviewDiscover attaches @CatalogComponent and @CatalogVariant identity to previews`() {
    val projectDir = createCmpTestProject()

    // Declare the catalog-inventory annotations locally with the discovered FQNs so the test needs
    // no external `preview-annotations` artifact — discovery matches by FQN + target, nothing else.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "CatalogInventory.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class CatalogComponent(
          val id: String = "",
          val group: String = "",
          val caption: String = "",
          val reference: String = "",
          val parallel: String = "",
          val perBreakpoint: Boolean = false,
          val referenceSet: String = "",
          val noReference: String = "",
          val referenceContentsOnly: Boolean = true,
        )

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class CatalogVariant(
          val of: String,
          val state: String = "",
          val caption: String = "",
          val props: Array<String> = [],
        )

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FILE)
        annotation class CatalogGroup(val name: String, val section: String = "")
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/Catalog.kt")
      .writeText(
        """
        @file:CatalogGroup("Buttons", section = "Components")

        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import ee.schimke.composeai.preview.CatalogComponent
        import ee.schimke.composeai.preview.CatalogGroup
        import ee.schimke.composeai.preview.CatalogVariant

        // Explicit slashed id + caption; group + section come from the file `@CatalogGroup`.
        @CatalogComponent(
          id = "Button/Filled",
          caption = "Highest emphasis; the primary action.",
          reference = "figma:AbCdEf/10:5",
          referenceSet = "figma:AbCdEf/10:1",
          referenceContentsOnly = false,
          parallel = "FilledButton",
        )
        @Preview @Composable fun FilledButton() {}

        // A variant folded under the parent, tagged by state + a content-axis prop.
        @CatalogVariant(
          of = "Button/Filled",
          state = "pressed",
          props = ["content=icon+label"],
          caption = "Held press -> pressed state layer.",
        )
        @Preview @Composable fun FilledButtonPressed() {}

        // No arguments: id defaults to the function name, group to the file `@CatalogGroup`.
        @CatalogComponent
        @Preview @Composable fun PlainSticker() {}

        // Breakpoints: the export fans this into one component per breakpoint the function
        // rendered at. WHICH breakpoints is decided by the renders, not restated here.
        @CatalogComponent(id = "Layout/List", perBreakpoint = true)
        @Preview @Composable fun ListLayout() {}

        // No reference, but for a stated reason — which is not the same as nobody having looked.
        @CatalogComponent(
          id = "Button/Retired",
          noReference = "the kit files this under Deprecated and publishes no replacement",
        )
        @Preview @Composable fun RetiredButton() {}

        // No catalog annotation at all: stays out of the inventory (catalog == null).
        @Preview @Composable fun NotACatalogPreview() {}

        // A *member* preview: its `classInfo` is this object, not the file facade that carries
        // `@file:CatalogGroup`, so it must still resolve the file group by source file.
        object MemberStickers {
          @CatalogComponent
          @Preview @Composable fun MemberSticker() {}
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val byFn = manifest.previews.associateBy { it.functionName }

    // Explicit component: slashed id + caption from the annotation, group/section from the file.
    val filled = byFn.getValue("FilledButton").catalog
    assertThat(filled).isNotNull()
    assertThat(filled!!.role).isEqualTo(CatalogRole.COMPONENT)
    assertThat(filled.componentId).isEqualTo("Button/Filled")
    assertThat(filled.group).isEqualTo("Buttons")
    assertThat(filled.section).isEqualTo("Components")
    assertThat(filled.caption).isEqualTo("Highest emphasis; the primary action.")
    assertThat(filled.parallel).isEqualTo("FilledButton")
    // The two kit handles travel side by side: `reference` is the one node a parity run diffs
    // against, `referenceSet` the family a screen's sibling variant matches through.
    assertThat(filled.reference).isEqualTo("figma:AbCdEf/10:5")
    assertThat(filled.referenceSet).isEqualTo("figma:AbCdEf/10:1")
    assertThat(filled.referenceContentsOnly).isFalse()

    // Variant: parent id on componentId, state + parsed `key=value` prop, own caption.
    val pressed = byFn.getValue("FilledButtonPressed").catalog
    assertThat(pressed).isNotNull()
    assertThat(pressed!!.role).isEqualTo(CatalogRole.VARIANT)
    assertThat(pressed.componentId).isEqualTo("Button/Filled")
    assertThat(pressed.state).isEqualTo("pressed")
    assertThat(pressed.props).containsExactly(CatalogVariantProp("content", "icon+label"))

    // Defaulted component: id falls back to the function name, group to the file default.
    val plain = byFn.getValue("PlainSticker").catalog
    assertThat(plain).isNotNull()
    assertThat(plain!!.componentId).isEqualTo("PlainSticker")
    assertThat(plain.group).isEqualTo("Buttons")
    assertThat(plain.caption).isNull()
    assertThat(plain.parallel).isNull()
    assertThat(plain.reference).isNull()
    assertThat(plain.referenceSet).isNull()
    assertThat(plain.referenceContentsOnly).isTrue()
    // Said nothing either way, so `noReference` is silent too. This is the "nobody has looked yet"
    // case the stated-absence assertion below has to stay distinguishable from.
    assertThat(plain.noReference).isNull()

    // A stated absence: still no reference, but the reason travels with the component, so a
    // consumer can tell a finding about the kit apart from an unfilled gap.
    val retired = byFn.getValue("RetiredButton").catalog
    assertThat(retired).isNotNull()
    assertThat(retired!!.reference).isNull()
    assertThat(retired.noReference)
      .isEqualTo("the kit files this under Deprecated and publishes no replacement")

    // The fan-out intent rides the component entry as a flag; the export resolves the actual
    // breakpoints from the renders (`Layout/List/smallRound`, `…/largeRound`) rather than from a
    // list restated in the annotation, so there is nothing here that could contradict them.
    val list = byFn.getValue("ListLayout").catalog
    assertThat(list).isNotNull()
    assertThat(list!!.perBreakpoint).isTrue()

    // An un-argumented component keeps the folded default.
    assertThat(plain.perBreakpoint).isFalse()

    // A preview with no catalog annotation stays out of the inventory.
    assertThat(byFn.getValue("NotACatalogPreview").catalog).isNull()

    // A member-function preview still inherits the file `@CatalogGroup`, resolved by source file
    // (its classInfo is the enclosing object, not the file facade the annotation lands on).
    val member = byFn.getValue("MemberSticker").catalog
    assertThat(member).isNotNull()
    assertThat(member!!.componentId).isEqualTo("MemberSticker")
    assertThat(member.group).isEqualTo("Buttons")
    assertThat(member.section).isEqualTo("Components")
  }

  @Test
  fun `composePreviewDiscover aggregates @TypographyCatalog tokens into type-style sheets`() {
    val projectDir = createCmpTestProject()

    // Same FQN-match policy as `@ColorCatalog`, just on a `TextStyle` field — declare the
    // annotation
    // locally so the test needs no external `preview-annotations` artifact.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "TypographyCatalog.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FIELD)
        annotation class TypographyCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/TypeTokens.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.text.TextStyle
        import androidx.compose.ui.unit.sp
        import ee.schimke.composeai.preview.TypographyCatalog

        @TypographyCatalog(group = "Display") val DisplayLarge: TextStyle = TextStyle(fontSize = 57.sp)
        @TypographyCatalog(name = "Body Large", group = "Body") val BodyL: TextStyle = TextStyle(fontSize = 16.sp)
        @TypographyCatalog(group = "Body") val Caption: TextStyle = TextStyle(fontSize = 11.sp)
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val catalogs = manifest.previews.filter { it.params.kind == PreviewKind.CATALOG }

    // Two groups (Display, Body) → two group sheets, plus a module-wide aggregate — namespaced
    // under `typographycatalog__` so they never collide with `@ColorCatalog` sheets.
    assertThat(catalogs.map { it.id })
      .containsExactly(
        "typographycatalog__Display",
        "typographycatalog__Body",
        "typographycatalog__all",
      )

    val body = catalogs.first { it.id == "typographycatalog__Body" }
    // `name` defaults to the property name (`Caption`); the explicit "Body Large" overrides it.
    assertThat(body.params.catalogTokens.map { it.label }).containsExactly("Body Large", "Caption")
    assertThat(body.params.catalogTokens.map { it.member }).containsExactly("BodyL", "Caption")
    // Type tokens are tagged TEXT_STYLE so the renderer picks the specimen layout, not a swatch.
    assertThat(body.params.catalogTokens.map { it.tokenKind })
      .containsExactly(CatalogTokenKind.TEXT_STYLE, CatalogTokenKind.TEXT_STYLE)

    assertThat(catalogs.first { it.id == "typographycatalog__all" }.params.catalogTokens).hasSize(3)
  }

  @Test
  fun `composePreviewDiscover aggregates @ShapeCatalog and whole-object catalogs by field type`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    // Declare all three field-target catalog annotations locally so the test needs no external
    // `preview-annotations` artifact — same policy as the colour/type tests above.
    File(annDir, "Catalogs.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FIELD)
        annotation class ShapeCatalog(val name: String = "", val group: String = "")

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FIELD)
        annotation class ColorCatalog(val name: String = "", val group: String = "")

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FIELD)
        annotation class TypographyCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )

    File(projectDir, "src/main/kotlin/test/ThemeTokens.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.ColorScheme
        import androidx.compose.material3.Shapes
        import androidx.compose.material3.Typography
        import androidx.compose.material3.lightColorScheme
        import androidx.compose.ui.graphics.Shape
        import ee.schimke.composeai.preview.ColorCatalog
        import ee.schimke.composeai.preview.ShapeCatalog
        import ee.schimke.composeai.preview.TypographyCatalog

        // Single-token Shape vs whole Shapes — dispatched by field type.
        @ShapeCatalog(group = "Shape") val Pill: Shape = RoundedCornerShape(50)
        @ShapeCatalog(name = "Scale", group = "Shape") val BrandShapes: Shapes = Shapes()

        // Whole-object ColorScheme / Typography under the colour / type annotations.
        @ColorCatalog(name = "Scheme", group = "Palette") val BrandScheme: ColorScheme = lightColorScheme()
        @TypographyCatalog(name = "Scale", group = "Type") val BrandType: Typography = Typography()
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val byId = manifest.previews.associateBy { it.id }

    // `@ShapeCatalog` sheet namespaced under `shapecatalog__`, carrying a single-Shape token and a
    // whole-Shapes token tagged with distinct kinds.
    val shapeSheet = byId.getValue("shapecatalog__Shape")
    assertThat(shapeSheet.params.kind).isEqualTo(PreviewKind.CATALOG)
    assertThat(shapeSheet.params.catalogTokens.map { it.tokenKind })
      .containsExactly(CatalogTokenKind.SHAPE, CatalogTokenKind.SHAPES)

    // A `@ColorCatalog` on a whole `ColorScheme` is tagged COLOR_SCHEME (not COLOR).
    assertThat(byId.getValue("colorcatalog__Palette").params.catalogTokens.single().tokenKind)
      .isEqualTo(CatalogTokenKind.COLOR_SCHEME)
    // A `@TypographyCatalog` on a whole `Typography` is tagged TYPOGRAPHY (not TEXT_STYLE).
    assertThat(byId.getValue("typographycatalog__Type").params.catalogTokens.single().tokenKind)
      .isEqualTo(CatalogTokenKind.TYPOGRAPHY)
  }

  @Test
  fun `composePreviewDiscover emits a theme catalog sheet per @ThemeCatalog provider`() {
    val projectDir = createCmpTestProject()

    // Declare the annotation locally (FQN match, no external artifact). Discovery only reads the
    // class annotation — it doesn't verify the `PreviewWrapperProvider` interface (the renderer
    // does) — so a bare annotated class is enough to exercise the discovery path.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "ThemeCatalog.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        annotation class ThemeCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )
    File(projectDir, "src/main/kotlin/test/Themes.kt")
      .writeText(
        """
        package test

        import ee.schimke.composeai.preview.ThemeCatalog

        @ThemeCatalog(name = "Brand Light") class BrandLightTheme
        @ThemeCatalog(name = "Brand Dark") class BrandDarkTheme
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val themes = manifest.previews.filter { it.params.kind == PreviewKind.THEME_CATALOG }

    // One sheet per provider, keyed `themecatalog__<sanitized name>`.
    assertThat(themes.map { it.id })
      .containsExactly("themecatalog__Brand_Light", "themecatalog__Brand_Dark")
    // The provider FQN travels on `wrapperClassName` — that's what the renderer resolves + invokes.
    val light = themes.first { it.id == "themecatalog__Brand_Light" }
    assertThat(light.params.wrapperClassName).isEqualTo("test.BrandLightTheme")
    // `params.name` is the clean theme name (the renderer keys the per-theme token sidecar by it);
    // the " theme" display label lives on `functionName`.
    assertThat(light.params.name).isEqualTo("Brand Light")
    assertThat(light.functionName).isEqualTo("Brand Light theme")
    // A CMP/desktop project can't render catalog sheets, so the capture is optional
    // (expected-absent
    // rather than a missing-render regression) — same backend-aware policy as the token catalogs.
    assertThat(light.captures.single().optional).isTrue()
    // A theme sheet's SUBJECT is the theme, so it is fixed by construction: a preview host that
    // re-rendered it under another `themeProvider` would leave a sheet captioned "Brand Light"
    // drawing Brand Dark's colours. No `@FixedTheme` needed on the consumer's side.
    assertThat(themes.map { it.fixedTheme }).containsExactly(true, true)
  }

  @Test
  fun `composePreviewDiscover marks an @FixedTheme preview as theme-fixed`() {
    val projectDir = createCmpTestProject()

    // Declared locally with the discovered FQN — discovery matches by FQN + target and nothing
    // else, so the test needs no external `preview-annotations` artifact.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "FixedTheme.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class FixedTheme
        """
          .trimIndent()
      )
    File(projectDir, "src/main/kotlin/test/Specimens.kt")
      .writeText(
        """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import ee.schimke.composeai.preview.FixedTheme

        // A specimen with no catalog section to speak for it — the annotation is the only signal.
        @FixedTheme
        @Preview @Composable fun BrandLightSpecimen() {}

        // Its ordinary neighbour keeps re-rendering under a theme override.
        @Preview @Composable fun ContactRow() {}
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val byFn = manifest.previews.associateBy { it.functionName }
    assertThat(byFn.getValue("BrandLightSpecimen").fixedTheme).isTrue()
    assertThat(byFn.getValue("ContactRow").fixedTheme).isFalse()
  }

  @Test
  fun `composePreviewDiscover carries PreviewHelper a11y opt out`() {
    val projectDir = createCmpTestProject()
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PreviewHelper.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class PreviewHelper(val includeInA11y: Boolean = true)
        """
          .trimIndent()
      )
    File(projectDir, "src/main/kotlin/test/Helpers.kt")
      .writeText(
        """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import ee.schimke.composeai.preview.PreviewHelper

        @PreviewHelper(includeInA11y = false)
        @Preview @Composable fun ColorSchemeSpecimenPreview() {}

        @Preview @Composable fun ContactRow() {}
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val byFn = manifest.previews.associateBy { it.functionName }
    assertThat(byFn.getValue("ColorSchemeSpecimenPreview").includeInA11y).isFalse()
    assertThat(byFn.getValue("ContactRow").includeInA11y).isTrue()
  }

  @Test
  fun `composePreviewDiscover warns when a preview installs a theme under declared theme catalogs`() {
    val projectDir = createCmpTestProject()

    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "ThemeCatalog.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        annotation class ThemeCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )
    // Two declared themes → this module has a theme axis the viewer can drive. `AppTheme` is the
    // app's own wrapper, exactly like Confetti's `ConfettiThemeFixed`: the preview calls it, and it
    // installs the real `MaterialTheme`. Leaving colorScheme/typography/shapes defaulted is what
    // makes the compiler emit the `MaterialTheme$default` bridge, so this also pins that the
    // detector matches the shape real code produces.
    File(projectDir, "src/main/kotlin/test/Themes.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.MaterialTheme
        import androidx.compose.runtime.Composable
        import ee.schimke.composeai.preview.ThemeCatalog

        @ThemeCatalog(name = "Brand Light") class BrandLightTheme
        @ThemeCatalog(name = "Brand Dark") class BrandDarkTheme

        @Composable
        fun AppTheme(content: @Composable () -> Unit) {
            MaterialTheme(content = content)
        }
        """
          .trimIndent()
      )
    File(projectDir, "src/main/kotlin/test/ShadowingPreviews.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun ShadowedPreview() {
            AppTheme { Text("shadowed") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    // Names the preview and the hop through the app wrapper, so the fix is obvious from the log.
    assertThat(result.output).contains("install a theme in their own body")
    assertThat(result.output).contains("test.ShadowingPreviewsKt.ShadowedPreview")
    assertThat(result.output).contains("AppTheme")
    // Advisory only — discovery still succeeds and the preview is still published.
    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews.map { it.functionName }).contains("ShadowedPreview")
  }

  @Test
  fun `composePreviewDiscover stays quiet about body themes when no theme catalog is declared`() {
    val projectDir = createCmpTestProject()

    // Same body-installed theme, but nothing declares a theme catalog — there is no theme axis to
    // shadow, so this is just an ordinary app preview and must not be warned about.
    File(projectDir, "src/main/kotlin/test/Themes.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.MaterialTheme
        import androidx.compose.runtime.Composable

        @Composable
        fun AppTheme(content: @Composable () -> Unit) {
            MaterialTheme(content = content)
        }
        """
          .trimIndent()
      )
    File(projectDir, "src/main/kotlin/test/ShadowingPreviews.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun ThemedPreview() {
            AppTheme { Text("fine") }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("install a theme in their own body")
  }

  @Test
  fun `composePreviewDiscover routes @WearThemeCatalog to its own kind and id namespace`() {
    val projectDir = createCmpTestProject()

    // Both annotations declared locally (FQN match, no external artifact) so one project exercises
    // the platform split.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "ThemeCatalogs.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        annotation class ThemeCatalog(val name: String = "", val group: String = "")

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        annotation class WearThemeCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )
    // Deliberately the SAME display name on both platforms — the id namespace is what has to keep
    // them apart, otherwise the two sheets would collide on one `renders/<id>.png`.
    File(projectDir, "src/main/kotlin/test/Themes.kt")
      .writeText(
        """
        package test

        import ee.schimke.composeai.preview.ThemeCatalog
        import ee.schimke.composeai.preview.WearThemeCatalog

        @ThemeCatalog(name = "Brand", group = "B") class BrandPhoneTheme
        @WearThemeCatalog(name = "Brand", group = "B") class BrandWearTheme
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val byId = manifest.previews.associateBy { it.id }

    // Same name, distinct ids — the platform prefix, not the FQN disambiguator, separates them.
    val phone = byId.getValue("themecatalog__Brand")
    val wear = byId.getValue("wearthemecatalog__Brand")
    assertThat(phone.params.kind).isEqualTo(PreviewKind.THEME_CATALOG)
    assertThat(wear.params.kind).isEqualTo(PreviewKind.WEAR_THEME_CATALOG)
    // Each still points at its own provider — that's what the renderer resolves and wraps.
    assertThat(phone.params.wrapperClassName).isEqualTo("test.BrandPhoneTheme")
    assertThat(wear.params.wrapperClassName).isEqualTo("test.BrandWearTheme")
    // Everything else about the Wear entry matches the mobile shape (name/label/group/optional), so
    // downstream consumers need no per-platform special-casing beyond the kind.
    assertThat(wear.params.name).isEqualTo("Brand")
    assertThat(wear.params.group).isEqualTo("B")
    assertThat(wear.functionName).isEqualTo("Brand theme")
    assertThat(wear.captures.single().optional).isTrue()
  }

  @Test
  fun `composePreviewDiscover disambiguates theme catalog ids when two providers share a name`() {
    val projectDir = createCmpTestProject()
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "ThemeCatalog.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.CLASS)
        annotation class ThemeCatalog(val name: String = "", val group: String = "")
        """
          .trimIndent()
      )
    // Two providers with the SAME display name in different groups — would collide on
    // `themecatalog__Light` (and its render output) without FQN disambiguation.
    File(projectDir, "src/main/kotlin/test/Themes.kt")
      .writeText(
        """
        package test

        import ee.schimke.composeai.preview.ThemeCatalog

        @ThemeCatalog(name = "Light", group = "BrandA") class BrandALight
        @ThemeCatalog(name = "Light", group = "BrandB") class BrandBLight
        """
          .trimIndent()
      )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover", "--stacktrace")
      .withPluginClasspath()
      .build()

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val themes = manifest.previews.filter { it.params.kind == PreviewKind.THEME_CATALOG }

    // Distinct ids (FQN-suffixed) → distinct render outputs, so neither sheet clobbers the other.
    assertThat(themes.map { it.id })
      .containsExactly(
        "themecatalog__Light__test.BrandALight",
        "themecatalog__Light__test.BrandBLight",
      )
    assertThat(themes.map { it.captures.single().renderOutput }.toSet()).hasSize(2)
  }

  @Test
  fun `composePreviewDiscover is UP-TO-DATE on second run`() {
    val projectDir = createCmpTestProject()

    // First run
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()

    // Second run — should be UP-TO-DATE
    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `composePreviewDiscover resolves multi-preview meta-annotations`() {
    val projectDir = createCmpTestProject()

    // Define a custom meta-annotation that itself carries @Preview,
    // mirroring the @WearPreviewDevices / @PreviewParameterProvider pattern.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      // Custom multi-preview annotation — applies two @Preview variants
      @Preview(name = "Light", backgroundColor = 0xFFFFFFFF, showBackground = true)
      @Preview(name = "Dark", backgroundColor = 0xFF000000, showBackground = true)
      annotation class LightAndDark

      @LightAndDark
      @Composable
      fun ThemedBoxPreview() {
          Box(modifier = Modifier.size(100.dp).background(Color.Red)) {
              Text("Red")
          }
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    // @LightAndDark expands to two previews on the one function
    assertThat(manifest.previews).hasSize(2)
    assertThat(manifest.previews.map { it.functionName })
      .containsExactly("ThemedBoxPreview", "ThemedBoxPreview")
    val labels = manifest.previews.map { it.params.name }
    assertThat(labels).containsExactly("Light", "Dark")
  }

  @Test
  fun `composePreviewDiscover resolves nested meta-annotations`() {
    val projectDir = createCmpTestProject()

    // Two levels of meta-annotation: @Outer → @Inner → @Preview
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @Preview(name = "Inner")
      annotation class InnerPreview

      @InnerPreview
      annotation class OuterPreview

      @OuterPreview
      @Composable
      fun NestedPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews).hasSize(1)
    assertThat(manifest.previews[0].functionName).isEqualTo("NestedPreview")
    assertThat(manifest.previews[0].params.name).isEqualTo("Inner")
  }

  @Test
  fun `composePreviewDiscover handles cycles in meta-annotations without hanging`() {
    val projectDir = createCmpTestProject()

    // A → B → A cycle. Neither carries @Preview directly.
    // Expected: no previews, no infinite loop / stack overflow.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @AnnotB
      annotation class AnnotA

      @AnnotA
      annotation class AnnotB

      @AnnotA
      @Composable
      fun CyclicPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews).isEmpty()
  }

  @Test
  fun `composePreviewDiscover hoists PreviewWrapperClass from a multi-preview annotation`() {
    val projectDir = createCmpTestProject()

    // Stub our own @PreviewWrapperClass under its real FQN so the test doesn't need the
    // preview-annotations artifact on the fixture classpath — same self-contained approach the
    // @PreviewWrapper test below uses for the androidx annotation.
    val annDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    annDir.mkdirs()
    File(annDir, "PreviewWrapperClass.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class PreviewWrapperClass(val wrapperClassName: String)
        """
          .trimIndent()
      )

    // A multi-preview annotation that carries the wrapper once — no @PreviewWrapper on the
    // function. Discovery must hoist `wrapperClassName` onto every expansion.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp
      import ee.schimke.composeai.preview.PreviewWrapperClass

      @Preview(name = "Light", backgroundColor = 0xFFFFFFFF, showBackground = true)
      @Preview(name = "Dark", backgroundColor = 0xFF000000, showBackground = true)
      @PreviewWrapperClass("test.FontWrapper")
      annotation class FontPreview

      @FontPreview
      @Composable
      fun HoistedPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }

      // A wrapper declared directly on the function wins over the one hoisted from @FontPreview.
      @FontPreview
      @PreviewWrapperClass("test.OverrideWrapper")
      @Composable
      fun OverriddenPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Green))
      }

      @Preview
      @Composable
      fun PlainPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    val hoisted = manifest.previews.filter { it.functionName == "HoistedPreview" }
    assertThat(hoisted).hasSize(2)
    // The wrapper declared once on @FontPreview lands on every expansion.
    assertThat(hoisted.map { it.params.wrapperClassName })
      .containsExactly("test.FontWrapper", "test.FontWrapper")

    // A direct @PreviewWrapperClass on the function overrides the hoisted one on every expansion.
    val overridden = manifest.previews.filter { it.functionName == "OverriddenPreview" }
    assertThat(overridden).hasSize(2)
    assertThat(overridden.map { it.params.wrapperClassName })
      .containsExactly("test.OverrideWrapper", "test.OverrideWrapper")

    // A plain preview elsewhere in the module is unaffected.
    val plain = manifest.previews.single { it.functionName == "PlainPreview" }
    assertThat(plain.params.wrapperClassName).isNull()
  }

  @Test
  fun `composePreviewDiscover captures PreviewWrapper provider FQN`() {
    val projectDir = createCmpTestProject()

    // Declare our own @PreviewWrapper / PreviewWrapperProvider under the real
    // androidx FQN. CMP 1.10 (which this test uses) doesn't ship them yet, so
    // stubbing them locally exercises the discovery path via ClassGraph without
    // pinning the test to an unreleased dependency. The real 1.11 types are
    // source-compatible, so production discovery on real apps behaves identically.
    val previewFqnDir = File(projectDir, "src/main/kotlin/androidx/compose/ui/tooling/preview")
    previewFqnDir.mkdirs()
    File(previewFqnDir, "PreviewWrapper.kt")
      .writeText(
        """
        package androidx.compose.ui.tooling.preview

        import androidx.compose.runtime.Composable
        import kotlin.reflect.KClass

        interface PreviewWrapperProvider {
            @Composable fun Wrap(content: @Composable () -> Unit)
        }

        @MustBeDocumented
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class PreviewWrapper(val wrapper: KClass<out PreviewWrapperProvider>)
        """
          .trimIndent()
      )

    // Preview file that uses the wrapper on a function carrying both a direct
    // @Preview and a multi-preview meta-annotation — assert the wrapper FQN
    // propagates to every produced preview.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewWrapper
      import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
      import androidx.compose.ui.unit.dp

      class ThemeWrapper : PreviewWrapperProvider {
          @Composable override fun Wrap(content: @Composable () -> Unit) {
              content()
          }
      }

      @Preview(name = "Light", backgroundColor = 0xFFFFFFFF, showBackground = true)
      @Preview(name = "Dark", backgroundColor = 0xFF000000, showBackground = true)
      annotation class LightAndDark

      @LightAndDark
      @PreviewWrapper(ThemeWrapper::class)
      @Composable
      fun WrappedPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red)) {
              Text("x")
          }
      }

      @Preview
      @Composable
      fun PlainPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    val wrapped = manifest.previews.filter { it.functionName == "WrappedPreview" }
    assertThat(wrapped).hasSize(2)
    // Every expansion of the multi-preview carries the wrapper FQN.
    assertThat(wrapped.map { it.params.wrapperClassName })
      .containsExactly("test.ThemeWrapper", "test.ThemeWrapper")

    // Plain preview (no @PreviewWrapper) reports null.
    val plain = manifest.previews.single { it.functionName == "PlainPreview" }
    assertThat(plain.params.wrapperClassName).isNull()
  }

  @Test
  fun `composePreviewDiscover resolves device dimensions and disambiguates ids`() {
    val projectDir = createCmpTestProject()

    // Multi-preview with two devices, no explicit name — mirrors @WearPreviewDevices.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @Preview(device = "id:pixel_6")
      @Preview(device = "id:pixel_tablet")
      annotation class PhoneAndTablet

      @PhoneAndTablet
      @Composable
      fun MultiDevicePreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    assertThat(manifest.previews).hasSize(2)

    val phone = manifest.previews.single { it.params.device == "id:pixel_6" }
    assertThat(phone.params.widthDp).isEqualTo(411)
    // Pixel 6 = 1080x2400 px @ 420dpi → 411x914 dp. (Earlier revisions of
    // DeviceDimensions used the Pixel 6 Pro height here.)
    assertThat(phone.params.heightDp).isEqualTo(914)
    assertThat(phone.id).endsWith("_pixel_6")
    // `renderOutput` is `<readable>-<digest>.png`, so the device variant is the tail of the
    // readable part rather than of the whole leaf.
    assertThat(phone.captures.single().renderOutput).matches("renders/.*_pixel_6-[0-9a-f]{8}\\.png")

    val tablet = manifest.previews.single { it.params.device == "id:pixel_tablet" }
    assertThat(tablet.params.widthDp).isEqualTo(1280)
    assertThat(tablet.params.heightDp).isEqualTo(800)
    assertThat(tablet.id).endsWith("_pixel_tablet")

    // The two variants must not collide on their captures' renderOutput.
    val renderOutputs = manifest.previews.flatMap { it.captures.map { c -> c.renderOutput } }
    assertThat(renderOutputs.toSet()).hasSize(renderOutputs.size)
  }

  @Test
  fun `composePreviewDiscover keeps render stems intact when a preview name contains a dot`() {
    // Regression: `@Preview(name = "...1.5x")` carries a dot, which was appended verbatim to the
    // preview id. `resolveRenderStems` splits the id on `.` to derive the on-disk stem, so the
    // fractional part became a spurious trailing segment ("1" | "5x") and the
    // shortest-unique-suffix
    // walk could collapse the whole filename down to just "5x". 1.0x / 2.0x happened to survive
    // because their "0x" tails collided and forced a longer suffix; 1.5x's unique "5x" did not.
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @Preview(name = "Font scale 1.0x", fontScale = 1.0f)
      @Composable
      fun FontScale100Preview() { Box(Modifier.size(50.dp).background(Color.Red)) }

      @Preview(name = "Font scale 1.5x", fontScale = 1.5f)
      @Composable
      fun FontScale150Preview() { Box(Modifier.size(50.dp).background(Color.Red)) }

      @Preview(name = "Font scale 2.0x", fontScale = 2.0f)
      @Composable
      fun FontScale200Preview() { Box(Modifier.size(50.dp).background(Color.Red)) }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    // fontScale is captured per preview (the annotation knob the renderer honours via Density).
    assertThat(manifest.previews.map { it.params.fontScale }.toSet())
      .containsExactly(1.0f, 1.5f, 2.0f)

    // Every renderOutput leaf must still carry its own function name — none collapsed to a bare
    // fragment like `5x.png` — and all three stay distinct.
    val leaves =
      manifest.previews.associate {
        it.functionName to it.captures.single().renderOutput.substringAfterLast('/')
      }
    assertThat(leaves["FontScale100Preview"]).startsWith("FontScale100Preview_")
    assertThat(leaves["FontScale150Preview"]).startsWith("FontScale150Preview_")
    assertThat(leaves["FontScale200Preview"]).startsWith("FontScale200Preview_")
    assertThat(leaves.values.toSet()).hasSize(3)
  }

  @Test
  fun `composePreviewDiscover picks up @SettledPreview`() {
    val projectDir = createCmpTestProject()

    // Stub the annotation at its canonical FQN inside the synthetic project, same as the
    // @ScrollingPreview test below — discovery matches by FQN, not by artifact.
    val settledFqnDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    settledFqnDir.mkdirs()
    File(settledFqnDir, "SettledPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class SettledPreview(val afterMs: Int = 0, val maxMs: Int = 1000)
        """
          .trimIndent()
      )

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp
      import ee.schimke.composeai.preview.SettledPreview

      // Hoisted onto a multi-preview annotation — the shape a catalog uses to settle every
      // sticker at once, which is the whole point of allowing ANNOTATION_CLASS as a target.
      @SettledPreview
      @Preview(name = "Light")
      @Preview(name = "Dark")
      annotation class StickerPreview

      @StickerPreview
      @Composable
      fun HoistedSettledPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }

      @Preview
      @SettledPreview(afterMs = 600, maxMs = 4000)
      @Composable
      fun ExactSettledPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }

      // Out-of-range knobs are clamped at discovery, so both renderers read one already-sane
      // window instead of clamping it differently in each.
      @Preview
      @SettledPreview(afterMs = -5, maxMs = 99999)
      @Composable
      fun ClampedSettledPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Green))
      }

      @Preview
      @Composable
      fun UnsettledPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Magenta))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    // Hoisted onto the multi-preview annotation: every expansion inherits the settle.
    val hoisted = manifest.previews.filter { it.functionName == "HoistedSettledPreview" }
    assertThat(hoisted).hasSize(2)
    for (p in hoisted) {
      assertThat(p.captures.single().settle).isEqualTo(SettleCapture(afterMs = 0, maxMs = 1000))
    }

    val exact = manifest.previews.single { it.functionName == "ExactSettledPreview" }
    assertThat(exact.captures.single().settle).isEqualTo(SettleCapture(afterMs = 600, maxMs = 4000))

    // afterMs < 0 degrades to auto; maxMs is capped at MAX_SETTLE_MS rather than trusted.
    val clamped = manifest.previews.single { it.functionName == "ClampedSettledPreview" }
    assertThat(clamped.captures.single().settle)
      .isEqualTo(SettleCapture(afterMs = 0, maxMs = MAX_SETTLE_MS))

    // A settled still keeps the plain `renders/<id>.png` name — the whole point of a dedicated
    // field rather than reusing `advanceTimeMillis`, which stamps a `_TIME_<n>ms` suffix.
    assertThat(exact.captures.single().renderOutput).doesNotContain("_TIME_")
    assertThat(exact.captures.single().advanceTimeMillis).isNull()

    val unsettled = manifest.previews.single { it.functionName == "UnsettledPreview" }
    assertThat(unsettled.captures.single().settle).isNull()
  }

  @Test
  fun `composePreviewDiscover picks up @ScrollingPreview`() {
    val projectDir = createCmpTestProject()

    // Stub out @ScrollingPreview at its canonical FQN inside the synthetic
    // project — mirrors the @PreviewWrapper test above so the functional
    // test doesn't need the preview-annotations artifact on its classpath.
    val scrollingFqnDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    scrollingFqnDir.mkdirs()
    File(scrollingFqnDir, "ScrollingPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        enum class ScrollMode { TOP, END, LONG, GIF }
        enum class ScrollAxis { VERTICAL, HORIZONTAL }

        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class ScrollingPreview(
            val modes: Array<ScrollMode> = [ScrollMode.END],
            val maxScrollPx: Int = 0,
            val reduceMotion: Boolean = true,
            val axis: ScrollAxis = ScrollAxis.VERTICAL,
            val frameIntervalMs: Int = 80,
        )
        """
          .trimIndent()
      )

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp
      import ee.schimke.composeai.preview.ScrollAxis
      import ee.schimke.composeai.preview.ScrollMode
      import ee.schimke.composeai.preview.ScrollingPreview

      // Multi-preview meta-annotation to prove the scroll spec propagates to
      // every expansion, same pattern as the PreviewWrapper test.
      @Preview(name = "Light", backgroundColor = 0xFFFFFFFF, showBackground = true)
      @Preview(name = "Dark", backgroundColor = 0xFF000000, showBackground = true)
      annotation class LightAndDark

      @LightAndDark
      @ScrollingPreview(modes = [ScrollMode.END])
      @Composable
      fun EndScrollPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }

      @Preview
      @ScrollingPreview(
          modes = [ScrollMode.LONG],
          maxScrollPx = 4000,
          reduceMotion = false,
          axis = ScrollAxis.HORIZONTAL,
      )
      @Composable
      fun LongScrollPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }

      // Multi-mode fan-out: one preview function emits both an
      // unscrolled initial capture and a scroll-to-end capture,
      // disambiguated on disk by a _SCROLL_<mode> suffix.
      @Preview(name = "Scroll")
      @ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END])
      @Composable
      fun TopAndEndScrollPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Magenta))
      }

      // GIF-mode capture: single-mode annotation lands at .gif, not .png.
      @Preview(name = "Gif")
      @ScrollingPreview(modes = [ScrollMode.GIF], frameIntervalMs = 120)
      @Composable
      fun GifScrollPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Cyan))
      }

      // Multi-mode with GIF sibling: each capture keeps its own
      // extension — .png for END, .gif for GIF.
      @Preview(name = "EndAndGif")
      @ScrollingPreview(modes = [ScrollMode.END, ScrollMode.GIF])
      @Composable
      fun EndAndGifScrollPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Yellow))
      }

      @Preview
      @Composable
      fun PlainPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Green))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    val endPreviews = manifest.previews.filter { it.functionName == "EndScrollPreview" }
    assertThat(endPreviews).hasSize(2)
    // @ScrollingPreview propagates identically to every @LightAndDark expansion,
    // using its declared-in-source defaults (reduceMotion=true, axis=VERTICAL).
    // Scroll state lives on each capture now (Capture.scroll) — single-capture
    // previews carry it on the first element.
    // `frameIntervalMs` on the annotation applies to every capture in
    // the manifest even though it's only meaningful for GIF mode —
    // discovery reads the field unconditionally for a uniform shape.
    // Test stub declares 80 as the default (matches the real
    // annotation's DEFAULT_GIF_FRAME_INTERVAL_MS).
    for (p in endPreviews) {
      assertThat(p.captures).hasSize(1)
      assertThat(p.captures.first().scroll)
        .isEqualTo(
          ScrollCapture(
            mode = ScrollMode.END,
            axis = ScrollAxis.VERTICAL,
            maxScrollPx = 0,
            reduceMotion = true,
            frameIntervalMs = 80,
          )
        )
    }

    val longPreview = manifest.previews.single { it.functionName == "LongScrollPreview" }
    // `@ScrollingPreview(modes = [LONG])` with nothing else cross-products to a static frame
    // produces ONLY a data product — no phantom `renders/<id>.png` capture. The data product
    // IS the rendered output; emitting a sibling static would just write the unscrolled
    // initial frame to renders/, which is what issue #1524 reported as confusing.
    assertThat(longPreview.captures).isEmpty()
    assertThat(longPreview.dataProducts.single().scroll)
      .isEqualTo(
        ScrollCapture(
          mode = ScrollMode.LONG,
          axis = ScrollAxis.HORIZONTAL,
          maxScrollPx = 4000,
          reduceMotion = false,
          frameIntervalMs = 80,
        )
      )
    assertThat(longPreview.dataProducts.single().kind).isEqualTo("render/scroll/long")
    assertThat(longPreview.dataProducts.single().output)
      .matches("data/render-scroll-long/LongScrollPreview-[0-9a-f]{8}\\.png")

    val plain = manifest.previews.single { it.functionName == "PlainPreview" }
    assertThat(plain.captures.single().scroll).isNull()

    // Multi-mode: one preview yields two captures, one per mode, with
    // distinct `_SCROLL_<mode>` filenames. Modes sort by enum ordinal
    // (TOP, END, LONG, GIF) so the renderer captures the initial frame
    // before driving the scroller.
    val topAndEnd = manifest.previews.single { it.functionName == "TopAndEndScrollPreview" }
    assertThat(topAndEnd.captures).hasSize(2)
    assertThat(topAndEnd.captures.map { it.scroll?.mode })
      .containsExactly(ScrollMode.TOP, ScrollMode.END)
      .inOrder()
    // renderOutput drops the package-and-class prefix from every preview id — the full FQN is
    // retained on `preview.id` itself. The id digest sits between the readable part and the
    // structural `_SCROLL_<mode>` suffix, which is what keeps the two namespaces from colliding.
    val scrollOutputs = topAndEnd.captures.map { it.renderOutput }
    assertThat(scrollOutputs[0])
      .matches("renders/TopAndEndScrollPreview_Scroll-[0-9a-f]{8}_SCROLL_top\\.png")
    assertThat(scrollOutputs[1])
      .matches("renders/TopAndEndScrollPreview_Scroll-[0-9a-f]{8}_SCROLL_end\\.png")

    // Single-mode GIF: moves to the scroll data-product path (no
    // `_SCROLL_gif` suffix) and round-trips `frameIntervalMs` onto the
    // manifest so the renderer can honour it.
    val gifOnly = manifest.previews.single { it.functionName == "GifScrollPreview" }
    // GIF-only follows the same rule as LONG-only — pure data product, no static sibling.
    assertThat(gifOnly.captures).isEmpty()
    assertThat(gifOnly.dataProducts.single().scroll)
      .isEqualTo(
        ScrollCapture(
          mode = ScrollMode.GIF,
          axis = ScrollAxis.VERTICAL,
          maxScrollPx = 0,
          reduceMotion = true,
          frameIntervalMs = 120,
        )
      )
    assertThat(gifOnly.dataProducts.single().kind).isEqualTo("render/scroll/gif")
    assertThat(gifOnly.dataProducts.single().output)
      .matches("data/render-scroll-gif/GifScrollPreview_Gif-[0-9a-f]{8}\\.gif")

    // Multi-mode with GIF sibling: END remains a normal capture; GIF moves
    // to a data product with its own `_SCROLL_gif` output path.
    val endAndGif = manifest.previews.single { it.functionName == "EndAndGifScrollPreview" }
    assertThat(endAndGif.captures).hasSize(1)
    assertThat(endAndGif.captures.map { it.scroll?.mode }).containsExactly(ScrollMode.END).inOrder()
    assertThat(endAndGif.captures.single().renderOutput)
      .matches("renders/EndAndGifScrollPreview_EndAndGif-[0-9a-f]{8}\\.png")
    assertThat(endAndGif.dataProducts.single().scroll?.mode).isEqualTo(ScrollMode.GIF)
    assertThat(endAndGif.dataProducts.single().output)
      .matches(
        "data/render-scroll-gif/EndAndGifScrollPreview_EndAndGif-[0-9a-f]{8}_SCROLL_gif\\.gif"
      )
  }

  @Test
  fun `composePreviewDiscover records @PreviewParameter provider FQN`() {
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewParameter
      import androidx.compose.ui.tooling.preview.PreviewParameterProvider
      import androidx.compose.ui.unit.dp

      class ColorProvider : PreviewParameterProvider<Long> {
          override val values: Sequence<Long>
              get() = sequenceOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL)
      }

      @Preview(name = "Swatch")
      @Composable
      fun SwatchPreview(
          @PreviewParameter(ColorProvider::class) color: Long,
      ) {
          Box(modifier = Modifier.size(50.dp).background(Color(color.toInt())))
      }

      // Limit arg: annotation takes only the first entry.
      @Preview(name = "Limited")
      @Composable
      fun LimitedPreview(
          @PreviewParameter(ColorProvider::class, limit = 1) color: Long,
      ) {
          Box(modifier = Modifier.size(50.dp).background(Color(color.toInt())))
      }

      // No provider — must not surface a previewParameterProviderClassName.
      @Preview
      @Composable
      fun PlainPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Gray))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    val swatch = manifest.previews.single { it.functionName == "SwatchPreview" }
    assertThat(swatch.params.previewParameterProviderClassName).isEqualTo("test.ColorProvider")
    assertThat(swatch.params.previewParameterLimit).isEqualTo(Int.MAX_VALUE)

    val limited = manifest.previews.single { it.functionName == "LimitedPreview" }
    assertThat(limited.params.previewParameterProviderClassName).isEqualTo("test.ColorProvider")
    assertThat(limited.params.previewParameterLimit).isEqualTo(1)

    val plain = manifest.previews.single { it.functionName == "PlainPreview" }
    assertThat(plain.params.previewParameterProviderClassName).isNull()
    assertThat(plain.params.previewParameterLimit).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  fun `composePreviewDiscover accepts a preview whose parameters are all defaulted`() {
    val projectDir = createCmpTestProject()

    File(projectDir, "src/main/kotlin/test/Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        // The shape a production composable annotated @Preview in place almost always has.
        // Studio renders it by filling every parameter from Kotlin's synthetic ${'$'}default
        // bridge, and so does our renderer — discovery must not drop it.
        // `size` is a plain Int rather than a Color: an inline value class in the signature
        // mangles the JVM method name (DefaultedPreview-iJQMabo), which is orthogonal to the
        // rule under test.
        @Preview(name = "Defaulted")
        @Composable
        fun DefaultedPreview(modifier: Modifier = Modifier, size: Int = 50) {
            Box(modifier = modifier.size(size.dp).background(Color.Gray))
        }

        // Only SOME parameters defaulted: `label` is required, so an all-bits default mask
        // would pass it null and NPE at render. Must still be skipped.
        @Preview(name = "Partially defaulted")
        @Composable
        fun PartiallyDefaultedPreview(label: String, modifier: Modifier = Modifier) {
            Box(modifier = modifier.size(label.length.dp).background(Color.Gray))
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val functionNames = manifest.previews.map { it.functionName }
    assertThat(functionNames).contains("DefaultedPreview")
    assertThat(functionNames).doesNotContain("PartiallyDefaultedPreview")
  }

  @Test
  fun `composePreviewDiscover discovers private preview methods`() {
    val projectDir = createCmpTestProject()

    File(projectDir, "src/main/kotlin/test/Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        private fun PrivatePreview() {
            Box(modifier = Modifier.size(50.dp).background(Color.Red))
        }

        @Preview
        @Composable
        fun PublicPreview() {
            Box(modifier = Modifier.size(50.dp).background(Color.Blue))
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    // Private previews are surfaced by ClassGraph's `ignoreMethodVisibility()`
    // and invoked through reflection with `setAccessible(true)` at render time,
    // so they no longer get dropped with a "skipping private @Preview" warning.
    assertThat(result.output).doesNotContain("skipping private @Preview")
    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews.map { it.functionName })
      .containsExactly("PrivatePreview", "PublicPreview")
  }

  @Test
  fun `composePreviewDiscover skips previews with unsupported parameters`() {
    val projectDir = createCmpTestProject()

    File(projectDir, "src/main/kotlin/test/Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.tooling.preview.PreviewParameter
        import androidx.compose.ui.tooling.preview.PreviewParameterProvider
        import androidx.compose.ui.unit.dp

        class IntProvider : PreviewParameterProvider<Int> {
            override val values: Sequence<Int> = sequenceOf(1)
        }

        @Preview
        @Composable
        fun UnsupportedArgPreview(value: String) {
            Box(modifier = Modifier.size(50.dp).background(Color.Red))
        }

        @Preview
        @Composable
        fun TooManyArgsPreview(
            @PreviewParameter(IntProvider::class) first: Int,
            second: Int,
        ) {
            Box(modifier = Modifier.size(50.dp).background(Color.Green))
        }

        @Preview
        @Composable
        fun SupportedPreview(
            @PreviewParameter(IntProvider::class) value: Int,
        ) {
            Box(modifier = Modifier.size(50.dp).background(Color(value)))
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    // The warning now enumerates all three admitted shapes, since "no @PreviewParameter" stopped
    // being the whole story once fully-defaulted parameter lists became renderable.
    assertThat(result.output)
      .contains("parameter(s) that are neither @PreviewParameter-injected nor fully defaulted")
    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews.map { it.functionName }).containsExactly("SupportedPreview")
  }

  @Test
  fun `composePreviewDiscover keeps tile previews that take a Context parameter`() {
    val projectDir = createCmpTestProject()

    // Stub the tile @Preview annotation under its real FQN. CMP 1.10 (the
    // version this fixture uses) doesn't ship `androidx.wear.tiles.tooling`,
    // but discovery is FQN-driven — the stub exercises the same code path
    // wear-os-samples' WearTilesKotlin hit when its tile previews started
    // returning zero entries after PR #984's parameter gate landed.
    val previewFqnDir = File(projectDir, "src/main/kotlin/androidx/wear/tiles/tooling/preview")
    previewFqnDir.mkdirs()
    File(previewFqnDir, "Preview.kt")
      .writeText(
        """
        package androidx.wear.tiles.tooling.preview

        @MustBeDocumented
        @Retention(AnnotationRetention.BINARY)
        @Repeatable
        @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
        annotation class Preview(val name: String = "")
        """
          .trimIndent()
      )

    val srcFile = File(projectDir, "src/main/kotlin/test/Tiles.kt")
    srcFile.writeText(
      """
      package test

      import androidx.wear.tiles.tooling.preview.Preview

      // Stand-in for `android.content.Context`. Discovery doesn't reflect
      // parameter types beyond @PreviewParameter, so any single non-Composer
      // parameter exercises the gate the same way the real tile-preview
      // signature does.
      class FakeContext

      // Multi-preview meta-annotation expanding to two tile previews — the
      // upstream WearTilesKotlin `MultiRoundDevicesWithFontScalePreviews`
      // shape, minimised. Exercises the multi-preview branch of the tile
      // exemption alongside the direct one below.
      @Preview(name = "Small Round")
      @Preview(name = "Large Round")
      annotation class MultiRoundTiles

      @MultiRoundTiles
      fun MetaTilePreview(context: FakeContext) = "stub"

      @Preview(name = "Direct")
      fun DirectTilePreview(context: FakeContext) = "stub"
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    // The "unsupported parameters" warning must NOT fire for tile previews —
    // their (Context) parameter is part of the supported contract.
    assertThat(result.output).doesNotContain("MetaTilePreview' — method has parameter(s)")
    assertThat(result.output).doesNotContain("DirectTilePreview' — method has parameter(s)")

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val tilePreviews =
      manifest.previews.filter {
        it.functionName == "MetaTilePreview" || it.functionName == "DirectTilePreview"
      }
    assertThat(tilePreviews.map { it.functionName })
      .containsExactly("MetaTilePreview", "MetaTilePreview", "DirectTilePreview")
    assertThat(tilePreviews.map { it.params.kind }.toSet()).containsExactly(PreviewKind.TILE)
  }

  @Test
  fun `renderOutput strips common package prefix and sanitises path-unsafe preview names`() {
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp

      @Preview(name = "tile light (light)")
      @Composable
      fun TileLightStates() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }

      @Preview(name = "plain/with slash")
      @Composable
      fun TileDarkStates() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }
      """
        .trimIndent()
    )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover", "--stacktrace")
      .withPluginClasspath()
      .build()

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )

    val light = manifest.previews.single { it.functionName == "TileLightStates" }
    // `id` stays as the full FQN — consumers key by it. Top-level
    // Kotlin functions land on the synthetic `<File>Kt` class, so
    // the id is `test.PreviewsKt.TileLightStates_tile light (light)`.
    assertThat(light.id).isEqualTo("test.PreviewsKt.TileLightStates_tile light (light)")
    // `renderOutput` drops the `test.PreviewsKt.` package-and-class
    // prefix, sanitises the awkward `tile light (light)` variant suffix
    // down to shell-safe `tile_light_light`, and appends the id digest
    // that keeps the filename unique and stable.
    assertThat(light.captures.single().renderOutput)
      .matches("renders/TileLightStates_tile_light_light-[0-9a-f]{8}\\.png")

    val dark = manifest.previews.single { it.functionName == "TileDarkStates" }
    assertThat(dark.captures.single().renderOutput)
      .matches("renders/TileDarkStates_plain_with_slash-[0-9a-f]{8}\\.png")
  }

  @Test
  fun `composePreviewDiscover re-runs when source changes`() {
    val projectDir = createCmpTestProject()

    // First run
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()

    // Add a new preview
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.appendText(
      """

      @Preview
      @Composable
      fun GreenBoxPreview() {
          Box(modifier = Modifier.size(100.dp).background(Color.Green)) {
              Text("Green")
          }
      }
      """
        .trimIndent()
    )

    // Second run — should re-run and find 3 previews
    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews).hasSize(3)
  }

  /**
   * Regression for issue #157: previews were only surfaced for top-level modules. Nested Gradle
   * paths (`:auth:composables`) use `:` as a separator, but the CLI was treating that string as a
   * filesystem path and looking under `projectRoot/auth:composables/build/...` — which doesn't
   * exist. The plugin itself always wrote manifests to the real subproject directory
   * (`auth/composables/build/...`); this test locks that behaviour in so the CLI fix
   * (`PreviewModule.projectDir` via the Tooling API) has a stable contract to read against.
   */
  @Test
  fun `composePreviewDiscover runs in a nested subproject`() {
    val projectDir = tempDir.root

    // Root settings with a :auth:composables nested subproject.
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
        rootProject.name = "nested-test"
        include(":auth:composables")
        """
          .trimIndent()
      )

    // Root build.gradle.kts is empty — the plugin is only applied to the
    // nested subproject to mirror the Horologist-style layout in #157.
    File(projectDir, "build.gradle.kts").writeText("")

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val childDir = File(projectDir, "auth/composables")
    childDir.mkdirs()
    File(childDir, "build.gradle.kts")
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

    val childSrc = File(childDir, "src/main/kotlin/nested")
    childSrc.mkdirs()
    File(childSrc, "Previews.kt")
      .writeText(
        """
        package nested

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun NestedPreview() {
            Box(modifier = Modifier.size(80.dp).background(Color.Magenta)) {
                Text("nested")
            }
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":auth:composables:composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":auth:composables:composePreviewDiscover")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    // Manifest lives under the real subproject dir (`auth/composables/…`),
    // not a literal `auth:composables/…` path. This is the invariant the
    // CLI's `PreviewModule.projectDir` relies on.
    val manifestFile = File(childDir, "build/compose-previews/previews.json")
    assertThat(manifestFile.exists()).isTrue()

    // A stray `auth:composables/` directory would mean someone resolved
    // the Gradle path as a filesystem path — the exact #157 bug.
    assertThat(File(projectDir, "auth:composables").exists()).isFalse()

    val manifest = json.decodeFromString<PreviewManifest>(manifestFile.readText())
    assertThat(manifest.previews).hasSize(1)
    assertThat(manifest.previews[0].functionName).isEqualTo("NestedPreview")
  }

  @Test
  fun `composePreviewDiscover finds public, internal, and private previews`() {
    // Teams that don't want @Preview functions to leak into their public
    // API mark them `private` (or `internal`). Kotlin compiles `private
    // fun` to JVM `private` and `internal fun` to JVM `public` (with the
    // `name$module` mangle); ClassGraph's `ignoreMethodVisibility()` surfaces
    // both, and the renderer's `setAccessible(true)` lets the private one be
    // invoked. Asserting all three shapes so the visibility regression doesn't
    // return on any axis.
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @Preview
      @Composable
      fun PublicPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Red))
      }

      @Preview
      @Composable
      internal fun InternalPreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Green))
      }

      @Preview
      @Composable
      private fun PrivatePreview() {
          Box(modifier = Modifier.size(50.dp).background(Color.Blue))
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("skipping private @Preview")

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    // `internal fun` has its JVM name mangled to `InternalPreview$<module>`
    // so we match by prefix rather than exact equality.
    val names = manifest.previews.map { it.functionName }
    assertThat(names).contains("PublicPreview")
    assertThat(names.any { it.startsWith("InternalPreview") }).isTrue()
    assertThat(names).contains("PrivatePreview")
    assertThat(manifest.previews).hasSize(3)
  }

  @Test
  fun `composePreviewDiscover infers cross-file target composable`() {
    // Idiomatic preview-file layout: production composable `HomeScreen` lives in `HomeScreen.kt`,
    // its `@Preview` lives in a sibling `Previews.kt`. PreviewTargetInference walks the preview
    // method's bytecode, finds the single project-local @Composable call into HomeScreen, and
    // attaches it as a target.
    val projectDir = createCmpTestProject()

    val srcDir = File(projectDir, "src/main/kotlin/test")
    File(srcDir, "Previews.kt").delete()

    File(srcDir, "HomeScreen.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.unit.dp

        @Composable
        fun HomeScreen() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red)) {
                Text("home")
            }
        }
        """
          .trimIndent()
      )

    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview
        @Composable
        fun HomeScreenPreview() {
            HomeScreen()
        }
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val preview = manifest.previews.single { it.functionName == "HomeScreenPreview" }
    val target = preview.targets.single()
    assertThat(target.className).isEqualTo("test.HomeScreenKt")
    assertThat(target.functionName).isEqualTo("HomeScreen")
    // Single project-local composable call + matching name + cross-file → HIGH confidence.
    assertThat(target.confidence).isEqualTo(TargetConfidence.HIGH)
    assertThat(target.signals)
      .containsAtLeast(
        TargetSignal.SINGLE_PROJECT_COMPOSABLE_CALL,
        TargetSignal.NAME_MATCH,
        TargetSignal.CROSS_FILE,
      )
    // Source path resolves to the production file, not the preview file.
    assertThat(target.sourceFile).contains("HomeScreen.kt")
  }

  @Test
  fun `composePreviewDiscover looks through theme and catalog lambdas and rejects mangled targets`() {
    val projectDir = createCmpTestProject()

    val srcDir = File(projectDir, "src/main/kotlin/test")
    File(srcDir, "Previews.kt").delete()

    File(srcDir, "Components.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        @Composable
        fun HomeScreen() {
            Text("home")
        }

        @JvmInline
        value class ScreenValue(val value: Int)

        @Composable
        fun Screen(value: ScreenValue = ScreenValue(0)) {
            Text("${'$'}{value.value}")
        }
        """
          .trimIndent()
      )

    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        fun DemoTheme(content: @Composable () -> Unit) {
            content()
        }

        @Composable
        fun Wrap(content: @Composable () -> Unit) {
            content()
        }

        @Preview
        @Composable
        fun ThemePreview() {
            DemoTheme { HomeScreen() }
        }

        @Preview
        @Composable
        fun WrapPreview() {
            Wrap { HomeScreen() }
        }

        @Preview
        @Composable
        fun MangledPreview() {
            Screen()
        }
        """
          .trimIndent()
      )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover", "--stacktrace")
      .withPluginClasspath()
      .build()
    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val themeTarget =
      manifest.previews.single { it.functionName == "ThemePreview" }.targets.single()
    val wrapTarget = manifest.previews.single { it.functionName == "WrapPreview" }.targets.single()
    assertThat(themeTarget.functionName).isEqualTo("HomeScreen")
    assertThat(wrapTarget.functionName).isEqualTo("HomeScreen")
    assertThat(themeTarget.sourceFile).contains("Components.kt")
    assertThat(wrapTarget.sourceFile).contains("Components.kt")
    assertThat(themeTarget.signals).contains(TargetSignal.WRAPPER_UNWRAPPED)
    assertThat(wrapTarget.signals).contains(TargetSignal.WRAPPER_UNWRAPPED)
    assertThat(manifest.previews.single { it.functionName == "MangledPreview" }.targets).isEmpty()
  }

  @Test
  fun `composePreviewDiscover emits no target when preview body is purely framework`() {
    // A preview that only calls AndroidX Compose primitives (no project-local composable) gets no
    // target — the inference should not invent one from theming / layout calls.
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp

      @Preview
      @Composable
      fun PrimitivesOnlyPreview() {
          Box(modifier = Modifier.size(40.dp).background(Color.Red))
      }
      """
        .trimIndent()
    )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover", "--stacktrace")
      .withPluginClasspath()
      .build()

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    assertThat(manifest.previews.single().targets).isEmpty()
  }

  @Test
  fun `composePreviewDiscover skips other previews as candidate targets`() {
    // A preview function that calls a *sibling* preview (instead of the production composable)
    // should not surface that sibling as a target.
    val projectDir = createCmpTestProject()

    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.unit.dp

      @Preview
      @Composable
      fun FirstPreview() {
          Box(modifier = Modifier.size(40.dp).background(Color.Red))
      }

      @Preview
      @Composable
      fun WrapperPreview() {
          FirstPreview()
      }
      """
        .trimIndent()
    )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover", "--stacktrace")
      .withPluginClasspath()
      .build()

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val wrapper = manifest.previews.single { it.functionName == "WrapperPreview" }
    // FirstPreview itself is annotated @Preview, so it must be filtered out — no target emitted.
    assertThat(wrapper.targets).isEmpty()
  }

  @Test
  fun `failOnEmpty fails the build and emits diagnostics when no previews exist`() {
    val projectDir = createCmpTestProject()

    // Replace the preview source file with one that has NO @Preview annotations.
    // Keeps Compose on the classpath so ClassGraph has real classes to report
    // about — exactly the diagnostic "scan found classes but no @Preview" path.
    val srcFile = File(projectDir, "src/main/kotlin/test/Previews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.foundation.background
      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.size
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.graphics.Color
      import androidx.compose.ui.unit.dp

      @Composable
      fun NotAPreview() {
          Box(modifier = Modifier.size(100.dp).background(Color.Red)) {
              Text("Red")
          }
      }
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewDiscover",
          "-PcomposePreview.failOnEmpty=true",
          "--stacktrace",
        )
        .withPluginClasspath()
        .buildAndFail()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.FAILED)
    // The failure message names the module so CI logs make the regression obvious.
    assertThat(result.output).contains("discovered 0 previews in module 'test-project'")
    // Diagnostics block: classDirs listing (directory existence + class counts)
    // and the ClassGraph summary. These are the two lines users need to see to
    // disambiguate "wrong class dir" from "wrong @Preview FQN".
    assertThat(result.output).contains("composePreview: failOnEmpty diagnostics")
    assertThat(result.output).contains("classDirs (")
    assertThat(result.output).contains("ClassGraph scan:")
    // The sample had Compose classes but no @Preview — we should see the
    // "no known @Preview FQN seen" branch, not the "FQNs WERE seen" branch.
    assertThat(result.output).contains("no known @Preview FQN seen on any scanned method")
  }

  @Test
  fun `failOnEmpty is quiet when previews exist`() {
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewDiscover",
          "-PcomposePreview.failOnEmpty=true",
          "--stacktrace",
        )
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    // Diagnostics only emit on the empty path; a populated run must not
    // spam the log with them.
    assertThat(result.output).doesNotContain("failOnEmpty diagnostics")
  }

  @Test
  fun `composePreviewDiscover keeps notification previews that take a Context parameter`() {
    val projectDir = createCmpTestProject()

    // Stub `@NotificationPreview` under its real FQN. The CMP fixture doesn't depend on
    // `:preview-annotations`, but discovery is FQN-driven — the stub exercises the same code
    // path a real consumer would. Mirrors the `Preview.kt` stub the tile-previews test uses.
    val previewFqnDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    previewFqnDir.mkdirs()
    File(previewFqnDir, "NotificationPreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @MustBeDocumented
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class NotificationPreview
        """
          .trimIndent()
      )

    val srcFile = File(projectDir, "src/main/kotlin/test/Notifications.kt")
    srcFile.writeText(
      """
      package test

      import ee.schimke.composeai.preview.NotificationPreview

      // Stand-in for `android.content.Context`. Discovery doesn't reflect parameter types beyond
      // @PreviewParameter, so any single non-Composer parameter exercises the bypass gate the same
      // way the real `(Context) -> Notification` signature does.
      class FakeContext

      @NotificationPreview
      fun SimpleNotificationPreview(context: FakeContext) = "stub"

      // Parameterless overload is also accepted (mirrors the tile-preview contract, since
      // `findNotificationPreviewMethod` falls back to the no-arg overload).
      @NotificationPreview
      fun NoArgNotificationPreview() = "stub"
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    // The "unsupported parameters" warning must NOT fire for notification previews — their
    // (Context) parameter is part of the supported contract, same as tile previews.
    assertThat(result.output).doesNotContain("SimpleNotificationPreview' — method has parameter(s)")

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val notificationPreviews =
      manifest.previews.filter {
        it.functionName == "SimpleNotificationPreview" ||
          it.functionName == "NoArgNotificationPreview"
      }
    assertThat(notificationPreviews.map { it.functionName })
      .containsExactly("SimpleNotificationPreview", "NoArgNotificationPreview")
    assertThat(notificationPreviews.map { it.params.kind }.toSet())
      .containsExactly(PreviewKind.NOTIFICATION)
    // `widthDp` is pinned to the 400dp sandbox width so the shade renders at its wide footprint
    // rather than the router's 320dp square default (#1249). Height is left to the renderer.
    assertThat(notificationPreviews.map { it.params.widthDp }.toSet()).containsExactly(400)
    // Each notification preview produces exactly one capture (no scroll / time / focus / ambient
    // fan-out — `buildOutputPlan` treats NOTIFICATION the same as TILE for dimensional axes).
    assertThat(notificationPreviews.map { it.captures.size }).containsExactly(1, 1)
    // Target inference is skipped for non-composable kinds, so `targets` stays empty.
    assertThat(notificationPreviews.flatMap { it.targets }).isEmpty()
  }

  @Test
  fun `composePreviewDiscover tags XrSubspacePreview functions as XR_SUBSPACE`() {
    val projectDir = createCmpTestProject()

    // Stub `@XrSubspacePreview` under its real FQN — discovery is FQN-driven, so the stub exercises
    // the same path a real consumer (depending on `:preview-annotations`) would, without dragging
    // `androidx.xr.compose` onto the fixture's classpath.
    val previewFqnDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
    previewFqnDir.mkdirs()
    File(previewFqnDir, "XrSubspacePreview.kt")
      .writeText(
        """
        package ee.schimke.composeai.preview

        @MustBeDocumented
        @Retention(AnnotationRetention.BINARY)
        @Target(AnnotationTarget.FUNCTION)
        annotation class XrSubspacePreview
        """
          .trimIndent()
      )

    val srcFile = File(projectDir, "src/main/kotlin/test/XrPreviews.kt")
    srcFile.writeText(
      """
      package test

      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.XrSubspacePreview

      // The body would be a `Subspace { … }` in a real consumer; discovery is annotation-driven and
      // doesn't reflect the body, so an empty composable exercises the detection + kind assignment.
      @XrSubspacePreview
      @Composable
      fun MySpatialPreview() {}

      // A parameterized XR preview must be skipped — the XR renderer composes parameterless and has
      // no @PreviewParameter injection path.
      @XrSubspacePreview
      @Composable
      fun ParameterizedSpatialPreview(label: String) {}
      """
        .trimIndent()
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "--stacktrace")
        .withPluginClasspath()
        .build()

    // The "unsupported parameters" warning must NOT fire — XR previews bypass the composable-param
    // check the same way tile / notification / glance do.
    assertThat(result.output).doesNotContain("MySpatialPreview' — method has parameter(s)")

    val manifest =
      json.decodeFromString<PreviewManifest>(
        File(projectDir, "build/compose-previews/previews.json").readText()
      )
    val xrPreviews = manifest.previews.filter { it.functionName == "MySpatialPreview" }
    assertThat(xrPreviews.map { it.functionName }).containsExactly("MySpatialPreview")
    assertThat(xrPreviews.map { it.params.kind }.toSet()).containsExactly(PreviewKind.XR_SUBSPACE)
    // XR subspace previews emit a SINGLE optional composite capture (and no data product). The
    // composite.png is baked out-of-band by `composePreviewCompositeXr` from the scene.json
    // `composePreviewRenderXr` writes; marking it `optional = true` means it shows in the listing
    // when present but composePreviewRenderAll's missing-render gate never requires it. The subdir
    // uses the same `[^A-Za-z0-9._-]` → `_` sanitisation as the render task. Also no target
    // inference (non-composable kind).
    val xrCapture = xrPreviews.single().captures.single()
    val sanitizedId = xrPreviews.single().id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    assertThat(xrCapture.renderOutput).isEqualTo("renders/$sanitizedId/composite.png")
    assertThat(xrCapture.optional).isTrue()
    assertThat(xrPreviews.single().dataProducts).isEmpty()
    assertThat(xrPreviews.flatMap { it.targets }).isEmpty()

    // The parameterized XR preview is rejected with a clear warning and never reaches the manifest.
    assertThat(result.output)
      .contains("ParameterizedSpatialPreview' — XR subspace previews must be parameterless")
    assertThat(manifest.previews.map { it.functionName })
      .doesNotContain("ParameterizedSpatialPreview")
  }
}
