package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import ee.schimke.composeai.discovery.PreviewParams
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenshotTestInteropTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { encodeDefaults = true }

  private fun writeSolidPng(file: File, width: Int, height: Int, color: Color) {
    file.parentFile?.mkdirs()
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.color = color
      g.fillRect(0, 0, width, height)
    } finally {
      g.dispose()
    }
    ImageIO.write(img, "PNG", file)
  }

  @Test
  fun `resolveReferenceFile matches Google Layoutlib hashed filenames and creates deterministic paths`() {
    val refRoot = tempDir.newFolder("reference")
    val classDir =
      File(refRoot, "com/example/sampleandroidscreenshot/StudioParityMatrixKt").apply { mkdirs() }
    val existingWear =
      File(classDir, "ParityWearDevicePreview_device-wear_1b853a0e_0.png").apply {
        writeText("png")
      }
    val existingUnnamed = File(classDir, "SimplePreview_a1b2c3d4_0.png").apply { writeText("png") }

    // Matches existing Google Layoutlib hashed filename in place.
    assertThat(
        ScreenshotTestInterop.resolveReferenceFile(
          referenceDir = refRoot,
          className = "com.example.sampleandroidscreenshot.StudioParityMatrixKt",
          functionName = "ParityWearDevicePreview",
          previewName = "device-wear",
          index = 0,
        ),
      )
      .isEqualTo(existingWear)

    // Matches existing unnamed @Preview Layoutlib reference.
    assertThat(
        ScreenshotTestInterop.resolveReferenceFile(
          referenceDir = refRoot,
          className = "com.example.sampleandroidscreenshot.StudioParityMatrixKt",
          functionName = "SimplePreview",
          previewName = "",
          index = 0,
        ),
      )
      .isEqualTo(existingUnnamed)

    // Creates deterministic filename when no reference exists yet.
    val fresh =
      ScreenshotTestInterop.resolveReferenceFile(
        referenceDir = refRoot,
        className = "com.example.sampleandroidscreenshot.StudioParityMatrixKt",
        functionName = "NewCardPreview",
        previewName = "Dark Mode",
        index = 1,
      )
    assertThat(fresh.name).isEqualTo("NewCardPreview_dark_mode_1.png")
    assertThat(fresh.parentFile).isEqualTo(classDir)

    // Does not cross-match an existing golden for a different preview name on the same function.
    val siblingName =
      ScreenshotTestInterop.resolveReferenceFile(
        referenceDir = refRoot,
        className = "com.example.sampleandroidscreenshot.StudioParityMatrixKt",
        functionName = "ParityWearDevicePreview",
        previewName = "device-wear-dark",
        index = 0,
      )
    assertThat(siblingName.name).isEqualTo("ParityWearDevicePreview_device_wear_dark_0.png")
  }

  @Test
  fun `selectScreenshotTestPreviews prefers @PreviewTest annotations and falls back to src screenshotTest`() {
    val mainPreview =
      PreviewInfo(
        id = "com_example_MainKt_MainPreview",
        functionName = "MainPreview",
        className = "com.example.MainKt",
        sourceFile = "src/main/kotlin/com/example/Main.kt",
        previewTest = false,
      )
    val unannotatedScreenshotPreview =
      PreviewInfo(
        id = "com_example_ShotKt_ShotPreview",
        functionName = "ShotPreview",
        className = "com.example.ShotKt",
        sourceFile = "src/screenshotTest/kotlin/com/example/Shot.kt",
        previewTest = false,
      )
    val annotatedPreviewTest =
      PreviewInfo(
        id = "com_example_ShotKt_AnnotatedShot",
        functionName = "AnnotatedShot",
        className = "com.example.ShotKt",
        sourceFile = "src/screenshotTest/kotlin/com/example/Shot.kt",
        previewTest = true,
      )

    // When @PreviewTest exists, only annotated previews are selected.
    assertThat(
        ScreenshotTestInterop.selectScreenshotTestPreviews(
          listOf(mainPreview, unannotatedScreenshotPreview, annotatedPreviewTest),
          requirePreviewTestAnnotation = false,
        ),
      )
      .containsExactly(annotatedPreviewTest)

    // When no @PreviewTest exists, falls back to src/screenshotTest/.
    assertThat(
        ScreenshotTestInterop.selectScreenshotTestPreviews(
          listOf(mainPreview, unannotatedScreenshotPreview),
          requirePreviewTestAnnotation = false,
        ),
      )
      .containsExactly(unannotatedScreenshotPreview)

    // When requirePreviewTestAnnotation = true, unannotated previews are excluded.
    assertThat(
        ScreenshotTestInterop.selectScreenshotTestPreviews(
          listOf(mainPreview, unannotatedScreenshotPreview),
          requirePreviewTestAnnotation = true,
        ),
      )
      .isEmpty()
  }

  @Test
  fun `compareImages honours channel tolerance and detects regressions and size mismatches`() {
    val dir = tempDir.newFolder("imgs")
    val ref = File(dir, "ref.png")
    val near = File(dir, "near.png")
    val changed = File(dir, "changed.png")
    val resized = File(dir, "resized.png")

    writeSolidPng(ref, 20, 20, Color(100, 120, 140, 255))
    writeSolidPng(near, 20, 20, Color(101, 121, 141, 255))
    writeSolidPng(changed, 20, 20, Color(200, 20, 20, 255))
    writeSolidPng(resized, 24, 20, Color(100, 120, 140, 255))

    val refImg = ImageIO.read(ref)
    val withinTol =
      ScreenshotTestInterop.compareImages(
        refImg,
        ImageIO.read(near),
        threshold = 0.01,
        channelTolerance = 2,
      )
    assertThat(withinTol.matched).isTrue()
    assertThat(withinTol.diffFraction).isEqualTo(0.0)

    val diverged =
      ScreenshotTestInterop.compareImages(
        refImg,
        ImageIO.read(changed),
        threshold = 0.01,
        channelTolerance = 2,
      )
    assertThat(diverged.matched).isFalse()
    assertThat(diverged.diffFraction).isEqualTo(1.0)
    assertThat(diverged.sizeMismatch).isFalse()

    val sizeBad =
      ScreenshotTestInterop.compareImages(
        refImg,
        ImageIO.read(resized),
        threshold = 0.01,
        channelTolerance = 2,
      )
    assertThat(sizeBad.matched).isFalse()
    assertThat(sizeBad.sizeMismatch).isTrue()

    val diffOut = File(dir, "diff.png")
    ScreenshotTestInterop.writeDiffComposite(diffOut, refImg, ImageIO.read(changed), 2)
    val composite = ImageIO.read(diffOut)
    assertThat(composite.width).isEqualTo(20 * 3 + 16)
    assertThat(composite.height).isEqualTo(20)
  }

  @Test
  fun `update and validate tasks create references, pass on match, and fail with diff on drift`() {
    val projectDir = tempDir.newFolder("proj")
    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()

    val rendersDir = File(projectDir, "build/compose-previews/renders").apply { mkdirs() }
    val refDir = File(projectDir, "src/screenshotTestDebug/reference")
    val actualPng = File(rendersDir, "com_example_CardsKt_CardPreview_light.png")
    writeSolidPng(actualPng, 32, 32, Color(40, 80, 160, 255))

    val manifest =
      PreviewManifest(
        module = ":app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "com_example_CardsKt_CardPreview_light",
              functionName = "CardPreview",
              className = "com.example.CardsKt",
              sourceFile = "src/screenshotTest/kotlin/com/example/Cards.kt",
              params = PreviewParams(name = "light"),
              captures = listOf(Capture(renderOutput = "renders/${actualPng.name}")),
              previewTest = true,
            ),
          ),
      )
    val manifestFile = File(projectDir, "build/compose-previews/manifest.json")
    manifestFile.parentFile.mkdirs()
    manifestFile.writeText(json.encodeToString(manifest))

    val updateTask =
      project.tasks
        .register("testUpdate", UpdateScreenshotTestTask::class.java) {
          this.manifestFile.set(manifestFile)
          this.rendersDir.set(rendersDir)
          this.referenceDirPath.set(refDir.absolutePath)
          this.requirePreviewTestAnnotation.set(false)
        }
        .get()
    updateTask.update()

    val createdRefs = File(refDir, "com/example/CardsKt").listFiles().orEmpty()
    assertThat(createdRefs.map { it.name })
      .containsExactly(
        "CardPreview_light_${ScreenshotTestInterop.configHash(manifest.previews.single())}_0.png",
      )

    val reportFile = File(projectDir, "build/reports/screenshot-test/report.json")
    val diffsDir = File(projectDir, "build/reports/screenshot-test/diffs")
    val validateTask =
      project.tasks
        .register("testValidate", ValidateScreenshotTestTask::class.java) {
          this.manifestFile.set(manifestFile)
          this.rendersDir.set(rendersDir)
          this.referenceDir.set(refDir)
          this.referenceDirFallbackPath.set(refDir.absolutePath)
          this.imageDifferenceThreshold.set(0.01)
          this.channelTolerance.set(2)
          this.failOnMissingReferences.set(true)
          this.requirePreviewTestAnnotation.set(false)
          this.modulePath.set(":app")
          this.variantName.set("debug")
          this.reportFile.set(reportFile)
          this.diffsDir.set(diffsDir)
          this.testBinaryResultsDir.set(File(projectDir, "build/test-results/shot/binary"))
          this.testHtmlReportDir.set(File(projectDir, "build/reports/tests/shot"))
        }
        .get()

    validateTask.validate()
    assertThat(reportFile.readText()).contains("\"passed\": 1")
    assertThat(File(projectDir, "build/reports/tests/shot/index.html").isFile).isTrue()

    // Mutate the rendered PNG to simulate a visual regression.
    writeSolidPng(actualPng, 32, 32, Color(200, 30, 30, 255))
    val error = assertThrows(GradleException::class.java) { validateTask.validate() }
    assertThat(error.message).contains("100.00% pixels differ")
    assertThat(error.message).contains("composePreviewUpdateScreenshotTest")
    assertThat(diffsDir.listFiles()?.map { it.name })
      .contains("com_example_cardskt_cardpreview_light_0_diff.png")
  }

  @Test
  fun `resolveRenderedCaptures handles _PARAM_idx and labelled fan-outs while excluding siblings`() {
    val rendersDir = tempDir.newFolder("renders")
    val param0 = File(rendersDir, "BadgePreview-11112222_PARAM_0.png").apply { writeText("0") }
    val param2 = File(rendersDir, "BadgePreview-11112222_PARAM_2.png").apply { writeText("2") }
    val param10 = File(rendersDir, "BadgePreview-11112222_PARAM_10.png").apply { writeText("10") }

    val paramPreview =
      PreviewInfo(
        id = "com.example.BadgesKt.BadgePreview",
        functionName = "BadgePreview",
        className = "com.example.BadgesKt",
        params = PreviewParams(previewParameterProviderClassName = "com.example.BadgeProvider"),
        captures = listOf(Capture(renderOutput = "renders/BadgePreview-11112222.png")),
        previewTest = true,
      )
    val resolvedParam = ScreenshotTestInterop.resolveRenderedCaptures(paramPreview, rendersDir)
    assertThat(resolvedParam.map { it.index to it.file })
      .containsExactly(0 to param0, 2 to param2, 10 to param10)
      .inOrder()
    assertThat(resolvedParam.all { it.rowToken == null }).isTrue()

    val dark = File(rendersDir, "Card_Dark.png").apply { writeText("sibling") }
    val darkRow = File(rendersDir, "Card_Dark_Compact.png").apply { writeText("sibling-row") }
    val cardAlpha = File(rendersDir, "Card_Alpha.png").apply { writeText("alpha") }
    val cardBeta = File(rendersDir, "Card_Beta.png").apply { writeText("beta") }
    assertThat(dark.isFile && darkRow.isFile).isTrue()

    val labelledPreview =
      PreviewInfo(
        id = "com.example.CardsKt.Card",
        functionName = "Card",
        className = "com.example.CardsKt",
        params = PreviewParams(previewParameterProviderClassName = "com.example.CardProvider"),
        captures = listOf(Capture(renderOutput = "renders/Card.png")),
        previewTest = true,
      )
    val resolvedLabelled =
      ScreenshotTestInterop.resolveRenderedCaptures(
        preview = labelledPreview,
        rendersDir = rendersDir,
        siblingOutputs = setOf("renders/Card_Dark.png"),
      )
    assertThat(resolvedLabelled.map { it.rowToken to it.file })
      .containsExactly("Alpha" to cardAlpha, "Beta" to cardBeta)
      .inOrder()
  }

  @Test
  fun `multiple unnamed or same-named preview configurations keep distinct references`() {
    val projectDir = tempDir.newFolder("multi-config")
    val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    val rendersDir = File(projectDir, "build/compose-previews/renders").apply { mkdirs() }
    val refDir = File(projectDir, "src/screenshotTestDebug/reference")

    val lightPng = File(rendersDir, "MultiPreview-aaaa1111.png")
    val darkPng = File(rendersDir, "MultiPreview-bbbb2222.png")
    writeSolidPng(lightPng, 24, 24, Color(240, 240, 240, 255))
    writeSolidPng(darkPng, 24, 24, Color(20, 20, 20, 255))

    val lightPreview =
      PreviewInfo(
        id = "com.example.MultiKt.MultiPreview_day",
        functionName = "MultiPreview",
        className = "com.example.MultiKt",
        sourceFile = "src/screenshotTest/kotlin/com/example/Multi.kt",
        params = PreviewParams(name = "", uiMode = 16),
        captures = listOf(Capture(renderOutput = "renders/${lightPng.name}")),
        previewTest = true,
      )
    val darkPreview =
      PreviewInfo(
        id = "com.example.MultiKt.MultiPreview_night",
        functionName = "MultiPreview",
        className = "com.example.MultiKt",
        sourceFile = "src/screenshotTest/kotlin/com/example/Multi.kt",
        params = PreviewParams(name = "", uiMode = 32),
        captures = listOf(Capture(renderOutput = "renders/${darkPng.name}")),
        previewTest = true,
      )

    val manifest =
      PreviewManifest(
        module = ":app",
        variant = "debug",
        previews = listOf(lightPreview, darkPreview),
      )
    val manifestFile = File(projectDir, "build/compose-previews/manifest.json")
    manifestFile.parentFile.mkdirs()
    manifestFile.writeText(json.encodeToString(manifest))

    val updateTask =
      project.tasks
        .register("multiUpdate", UpdateScreenshotTestTask::class.java) {
          this.manifestFile.set(manifestFile)
          this.rendersDir.set(rendersDir)
          this.referenceDirPath.set(refDir.absolutePath)
          this.requirePreviewTestAnnotation.set(true)
        }
        .get()
    updateTask.update()

    val createdFiles = File(refDir, "com/example/MultiKt").listFiles().orEmpty()
    assertThat(createdFiles).hasLength(2)

    val reportFile = File(projectDir, "build/reports/screenshot-test/report.json")
    val diffsDir = File(projectDir, "build/reports/screenshot-test/diffs")
    val validateTask =
      project.tasks
        .register("multiValidate", ValidateScreenshotTestTask::class.java) {
          this.manifestFile.set(manifestFile)
          this.rendersDir.set(rendersDir)
          this.referenceDir.set(refDir)
          this.referenceDirFallbackPath.set(refDir.absolutePath)
          this.imageDifferenceThreshold.set(0.01)
          this.channelTolerance.set(2)
          this.failOnMissingReferences.set(true)
          this.requirePreviewTestAnnotation.set(true)
          this.modulePath.set(":app")
          this.variantName.set("debug")
          this.reportFile.set(reportFile)
          this.diffsDir.set(diffsDir)
          this.testBinaryResultsDir.set(File(projectDir, "build/test-results/multi/binary"))
          this.testHtmlReportDir.set(File(projectDir, "build/reports/tests/multi"))
        }
        .get()

    validateTask.validate()
    assertThat(reportFile.readText()).contains("\"passed\": 2")

    // Mutate only the dark render and verify only that configuration fails.
    writeSolidPng(darkPng, 24, 24, Color(200, 10, 10, 255))
    val err = assertThrows(GradleException::class.java) { validateTask.validate() }
    assertThat(err.message).contains("1 of 2 failed")
  }
}
