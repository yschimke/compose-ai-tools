package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiscoveryFunctionalTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun createCmpTestProject(): File {
        val projectDir = tempDir.root

        // settings.gradle.kts
        File(projectDir, "settings.gradle.kts").writeText(
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
            """.trimIndent()
        )

        // build.gradle.kts
        File(projectDir, "build.gradle.kts").writeText(
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
            """.trimIndent()
        )

        File(projectDir, "gradle.properties").writeText(
            "org.gradle.configuration-cache=true\n"
        )

        // Source file with previews
        val srcDir = File(projectDir, "src/main/kotlin/test")
        srcDir.mkdirs()
        File(srcDir, "Previews.kt").writeText(
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
            """.trimIndent()
        )

        return projectDir
    }

    @Test
    fun `discoverPreviews finds annotated composables`() {
        val projectDir = createCmpTestProject()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    }

    @Test
    fun `discoverPreviews is UP-TO-DATE on second run`() {
        val projectDir = createCmpTestProject()

        // First run
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
            .withPluginClasspath()
            .build()

        // Second run — should be UP-TO-DATE
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `discoverPreviews resolves multi-preview meta-annotations`() {
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )

        // @LightAndDark expands to two previews on the one function
        assertThat(manifest.previews).hasSize(2)
        assertThat(manifest.previews.map { it.functionName }).containsExactly(
            "ThemedBoxPreview", "ThemedBoxPreview",
        )
        val labels = manifest.previews.map { it.params.name }
        assertThat(labels).containsExactly("Light", "Dark")
    }

    @Test
    fun `discoverPreviews resolves nested meta-annotations`() {
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )
        assertThat(manifest.previews).hasSize(1)
        assertThat(manifest.previews[0].functionName).isEqualTo("NestedPreview")
        assertThat(manifest.previews[0].params.name).isEqualTo("Inner")
    }

    @Test
    fun `discoverPreviews handles cycles in meta-annotations without hanging`() {
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )
        assertThat(manifest.previews).isEmpty()
    }

    @Test
    fun `discoverPreviews captures PreviewWrapper provider FQN`() {
        val projectDir = createCmpTestProject()

        // Declare our own @PreviewWrapper / PreviewWrapperProvider under the real
        // androidx FQN. CMP 1.10 (which this test uses) doesn't ship them yet, so
        // stubbing them locally exercises the discovery path via ClassGraph without
        // pinning the test to an unreleased dependency. The real 1.11 types are
        // source-compatible, so production discovery on real apps behaves identically.
        val previewFqnDir = File(projectDir, "src/main/kotlin/androidx/compose/ui/tooling/preview")
        previewFqnDir.mkdirs()
        File(previewFqnDir, "PreviewWrapper.kt").writeText(
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
            """.trimIndent()
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
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
    fun `discoverPreviews resolves device dimensions and disambiguates ids`() {
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
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
    fun `discoverPreviews picks up @ScrollingPreview`() {
        val projectDir = createCmpTestProject()

        // Stub out @ScrollingPreview at its canonical FQN inside the synthetic
        // project — mirrors the @PreviewWrapper test above so the functional
        // test doesn't need the preview-annotations artifact on its classpath.
        val scrollingFqnDir = File(projectDir, "src/main/kotlin/ee/schimke/composeai/preview")
        scrollingFqnDir.mkdirs()
        File(scrollingFqnDir, "ScrollingPreview.kt").writeText(
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
            """.trimIndent()
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
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
            assertThat(p.captures.first().scroll).isEqualTo(
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
        assertThat(longPreview.captures.single().scroll).isEqualTo(
            ScrollCapture(
                mode = ScrollMode.LONG,
                axis = ScrollAxis.HORIZONTAL,
                maxScrollPx = 4000,
                reduceMotion = false,
                frameIntervalMs = 80,
            )
        )

        val plain = manifest.previews.single { it.functionName == "PlainPreview" }
        assertThat(plain.captures.single().scroll).isNull()

        // Multi-mode: one preview yields two captures, one per mode, with
        // distinct `_SCROLL_<mode>` filenames. Modes sort by enum ordinal
        // (TOP, END, LONG, GIF) so the renderer captures the initial frame
        // before driving the scroller.
        val topAndEnd = manifest.previews.single { it.functionName == "TopAndEndScrollPreview" }
        assertThat(topAndEnd.captures).hasSize(2)
        assertThat(topAndEnd.captures.map { it.scroll?.mode })
            .containsExactly(ScrollMode.TOP, ScrollMode.END).inOrder()
        // renderOutput strips the common `test.` package prefix from every
        // preview id — the full FQN is retained on `preview.id` itself (and
        // also stays addressable in `renderOutput`'s basename), but the
        // on-disk filename drops the shared prefix.
        assertThat(topAndEnd.captures.map { it.renderOutput }).containsExactly(
            "renders/TopAndEndScrollPreview_Scroll_SCROLL_top.png",
            "renders/TopAndEndScrollPreview_Scroll_SCROLL_end.png",
        ).inOrder()

        // Single-mode GIF: keeps the plain `renders/<id>.gif` name (no
        // `_SCROLL_gif` suffix) and round-trips `frameIntervalMs` onto the
        // manifest so the renderer can honour it.
        val gifOnly = manifest.previews.single { it.functionName == "GifScrollPreview" }
        assertThat(gifOnly.captures).hasSize(1)
        assertThat(gifOnly.captures.single().scroll).isEqualTo(
            ScrollCapture(
                mode = ScrollMode.GIF,
                axis = ScrollAxis.VERTICAL,
                maxScrollPx = 0,
                reduceMotion = true,
                frameIntervalMs = 120,
            )
        )
        assertThat(gifOnly.captures.single().renderOutput)
            .isEqualTo("renders/GifScrollPreview_Gif.gif")

        // Multi-mode with GIF sibling: each capture gets its mode's
        // extension (PNG for END, GIF for GIF) — the extension branches
        // per-capture rather than per-preview.
        val endAndGif = manifest.previews.single { it.functionName == "EndAndGifScrollPreview" }
        assertThat(endAndGif.captures).hasSize(2)
        assertThat(endAndGif.captures.map { it.scroll?.mode })
            .containsExactly(ScrollMode.END, ScrollMode.GIF).inOrder()
        assertThat(endAndGif.captures.map { it.renderOutput }).containsExactly(
            "renders/EndAndGifScrollPreview_EndAndGif_SCROLL_end.png",
            "renders/EndAndGifScrollPreview_EndAndGif_SCROLL_gif.gif",
        ).inOrder()
    }

    @Test
    fun `discoverPreviews records @PreviewParameter provider FQN`() {
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
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
    fun `renderOutput strips common package prefix and sanitises spaces and parens`() {
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

            @Preview(name = "plain")
            @Composable
            fun TileDarkStates() {
                Box(modifier = Modifier.size(50.dp).background(Color.Blue))
            }
            """.trimIndent()
        )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        val manifest = json.decodeFromString<PreviewManifest>(
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
            .isEqualTo("renders/TileDarkStates_plain.png")
    }

    @Test
    fun `discoverPreviews re-runs when source changes`() {
        val projectDir = createCmpTestProject()

        // First run
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
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
            """.trimIndent()
        )

        // Second run — should re-run and find 3 previews
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )
        assertThat(manifest.previews).hasSize(3)
    }

    /**
     * Regression for issue #157: previews were only surfaced for top-level
     * modules. Nested Gradle paths (`:auth:composables`) use `:` as a
     * separator, but the CLI was treating that string as a filesystem path
     * and looking under `projectRoot/auth:composables/build/...` — which
     * doesn't exist. The plugin itself always wrote manifests to the real
     * subproject directory (`auth/composables/build/...`); this test locks
     * that behaviour in so the CLI fix (`PreviewModule.projectDir` via the
     * Tooling API) has a stable contract to read against.
     */
    @Test
    fun `discoverPreviews runs in a nested subproject`() {
        val projectDir = tempDir.root

        // Root settings with a :auth:composables nested subproject.
        File(projectDir, "settings.gradle.kts").writeText(
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
            """.trimIndent()
        )

        // Root build.gradle.kts is empty — the plugin is only applied to the
        // nested subproject to mirror the Horologist-style layout in #157.
        File(projectDir, "build.gradle.kts").writeText("")

        File(projectDir, "gradle.properties").writeText(
            "org.gradle.configuration-cache=true\n"
        )

        val childDir = File(projectDir, "auth/composables")
        childDir.mkdirs()
        File(childDir, "build.gradle.kts").writeText(
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
            """.trimIndent()
        )

        val childSrc = File(childDir, "src/main/kotlin/nested")
        childSrc.mkdirs()
        File(childSrc, "Previews.kt").writeText(
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
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":auth:composables:discoverPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":auth:composables:discoverPreviews")?.outcome)
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
}
