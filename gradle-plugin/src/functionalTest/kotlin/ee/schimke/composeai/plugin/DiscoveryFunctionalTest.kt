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
    assertThat(phone.captures.single().renderOutput).endsWith("_pixel_6.png")

    val tablet = manifest.previews.single { it.params.device == "id:pixel_tablet" }
    assertThat(tablet.params.widthDp).isEqualTo(1280)
    assertThat(tablet.params.heightDp).isEqualTo(800)
    assertThat(tablet.id).endsWith("_pixel_tablet")

    // The two variants must not collide on their captures' renderOutput.
    val renderOutputs = manifest.previews.flatMap { it.captures.map { c -> c.renderOutput } }
    assertThat(renderOutputs.toSet()).hasSize(renderOutputs.size)
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
      .isEqualTo("data/render-scroll-long/LongScrollPreview.png")

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
    // renderOutput strips the common `test.` package prefix from every
    // preview id — the full FQN is retained on `preview.id` itself (and
    // also stays addressable in `renderOutput`'s basename), but the
    // on-disk filename drops the shared prefix.
    assertThat(topAndEnd.captures.map { it.renderOutput })
      .containsExactly(
        "renders/TopAndEndScrollPreview_Scroll_SCROLL_top.png",
        "renders/TopAndEndScrollPreview_Scroll_SCROLL_end.png",
      )
      .inOrder()

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
      .isEqualTo("data/render-scroll-gif/GifScrollPreview_Gif.gif")

    // Multi-mode with GIF sibling: END remains a normal capture; GIF moves
    // to a data product with its own `_SCROLL_gif` output path.
    val endAndGif = manifest.previews.single { it.functionName == "EndAndGifScrollPreview" }
    assertThat(endAndGif.captures).hasSize(1)
    assertThat(endAndGif.captures.map { it.scroll?.mode }).containsExactly(ScrollMode.END).inOrder()
    assertThat(endAndGif.captures.map { it.renderOutput })
      .containsExactly("renders/EndAndGifScrollPreview_EndAndGif.png")
      .inOrder()
    assertThat(endAndGif.dataProducts.single().scroll?.mode).isEqualTo(ScrollMode.GIF)
    assertThat(endAndGif.dataProducts.single().output)
      .isEqualTo("data/render-scroll-gif/EndAndGifScrollPreview_EndAndGif_SCROLL_gif.gif")
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

    assertThat(result.output).contains("parameter(s) without @PreviewParameter provider wiring")
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
    // `renderOutput` drops the shared `test.PreviewsKt.` dotted
    // prefix and sanitises the awkward `tile light (light)` variant
    // suffix down to shell-safe `tile_light_light`.
    assertThat(light.captures.single().renderOutput)
      .isEqualTo("renders/TileLightStates_tile_light_light.png")

    val dark = manifest.previews.single { it.functionName == "TileDarkStates" }
    assertThat(dark.captures.single().renderOutput)
      .isEqualTo("renders/TileDarkStates_plain_with_slash.png")
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
