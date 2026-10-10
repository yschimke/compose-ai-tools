package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelineResultCache
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.ModelResponse
import ee.schimke.composeai.guidelines.PreviewSubject
import ee.schimke.composeai.guidelines.SubjectPicture
import ee.schimke.composeai.guidelines.failures
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewDataProduct
import ee.schimke.composeai.previewdata.PreviewInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GuidelinesEvidenceTest {
  @Test
  fun `a preview's source runs from its fun line to the end of its body`() {
    val lines =
      """
      @Composable
      fun Stop(enabled: Boolean = true) = Sticker {
        val label = "Stop {now}"
        Button(Modifier.size(36.dp), enabled = enabled) { Text(label) } // a { brace
        /* } in a comment */
      }

      fun Next() {}
      """
        .trimIndent()
        .lines()

    val source = PreviewSourceReader.extract(lines, start = 1)

    assertTrue(source.startsWith("fun Stop("), source)
    assertTrue(source.endsWith("}"), source)
    assertTrue("Modifier.size(36.dp)" in source)
    assertTrue("fun Next" !in source, source)
  }

  @Test
  fun `a preview's source includes the wrapper it calls from another file in its module`() {
    val module = Files.createTempDirectory("guidelines-wrapper").toFile()
    val pkg = module.resolve("src/main/kotlin/demo").apply { mkdirs() }
    val preview =
      pkg.resolve("Lists.kt").apply {
        writeText(
          """
          package demo

          @Preview
          @Composable
          fun WearList() = WearScreen {
            ScreenScaffold(scrollState = rememberScrollState()) { Text("Hi") }
          }
          """
            .trimIndent()
        )
      }
    pkg
      .resolve("Frame.kt")
      .writeText(
        """
        package demo

        @Composable
        internal fun WearScreen(content: @Composable () -> Unit) {
          AppScaffold(timeText = { TimeText() }) { content() }
        }
        """
          .trimIndent()
      )
    val source = PreviewSourceReader.readWithCallees(preview, 4)!!
    assertTrue(source.startsWith("@Composable\nfun WearList()"), source)
    assertTrue("// WearScreen, which the preview calls (Frame.kt:4):" in source, source)
    assertTrue("AppScaffold(timeText = { TimeText() })" in source, source)
    // A library composable has no declaration in the module and is not pulled in.
    assertTrue("fun ScreenScaffold" !in source)

    // A handoff root that does not contain the module's `src` gives no index.
    val elsewhere = Files.createTempDirectory("guidelines-elsewhere").toFile()
    assertNull(SourceIndex.forSourceFile(preview, within = elsewhere))
  }

  @Test
  fun `an expression body ends where its brackets close`() {
    val lines = listOf("fun Chip() = Chip(", "  label = \"A\",", ")", "", "fun Other() {}")
    assertEquals("fun Chip() = Chip(\n  label = \"A\",\n)", PreviewSourceReader.extract(lines, 0))
  }

  @Test
  fun `handoff reads renders, source and nodes from the CI files, and findings cite the nodes`() {
    val dir = Files.createTempDirectory("guidelines-handoff").toFile()
    val module = dir.resolve("catalog").apply { mkdirs() }
    module.resolve("src/main/kotlin/Stop.kt").apply {
      parentFile.mkdirs()
      writeText(
        "package x\n\n@Composable\nfun StopButton() {\n  IconButton(Modifier.size(36.dp)) {}\n}\n"
      )
    }
    val buildDir = module.resolve("build/compose-previews").apply { mkdirs() }
    buildDir.resolve("renders").mkdirs()
    buildDir.resolve("renders/StopButton-abc.png").writeBytes(png())
    buildDir
      .resolve("previews.json")
      .writeText(
        """
        {"module": "catalog", "variant": "debug", "previews": [
          {"id": "x.StopKt.StopButton", "functionName": "StopButton",
           "sourceFile": "src/main/kotlin/Stop.kt", "bodyLine": 4,
           "params": {"device": "id:wearos_small_round", "widthDp": 192, "heightDp": 192},
           "captures": [{"renderOutput": "renders/StopButton-abc.png"}]}
        ]}
        """
      )
    val a11y =
      buildDir.resolve("accessibility.json").apply {
        writeText(
          """
          {"module": "catalog", "entries": [
            {"previewId": "x.StopKt.StopButton", "findings": [], "nodes": [
              {"label": "Stop", "role": "Button", "ref": "stop", "states": [], "merged": true,
               "boundsInScreen": "10,10,82,82"}
            ]}
          ]}
          """
        )
      }

    val inputs =
      HandoffInputs.read(
        previewsJson = buildDir.resolve("previews.json"),
        rendersDir = null,
        a11yJson = a11y,
        sourceRoot = module,
        surfaceOverride = null,
      )

    val subject = inputs.subjects.single()
    assertEquals("x.StopKt.StopButton", subject.previewId)
    assertEquals(GuidelineSurfaces.SCREEN, subject.surface)
    assertTrue(subject.source!!.contains("Modifier.size(36.dp)"), subject.source)
    assertEquals(listOf("stop"), subject.nodes.map { it.id })

    val model = FakeModel()
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines(), inputs.subjects)

    val request = model.requests.single()
    assertTrue(request.sourceAttached)
    assertEquals(
      setOf(GuidelineEvidenceNeedV1.KIND_A11Y_HIERARCHY, GuidelineEvidenceNeedV1.KIND_SOURCE),
      request.evidence.map { it.kind }.toSet(),
    )
    assertEquals(listOf("stop"), run.results.single().failures().single().nodeIds)
  }

  @Test
  fun `handoff offers the staged long screenshot as evidence and sends the ATF results`() {
    val dir = Files.createTempDirectory("guidelines-handoff-long").toFile()
    val renders = dir.resolve("renders").apply { mkdirs() }
    renders.resolve("List-1.png").writeBytes(png())
    renders.resolve("List-1_SCROLL_long.png").writeBytes(png())
    dir
      .resolve("previews.json")
      .writeText(
        """
        {"module": "catalog", "previews": [
          {"id": "x.List", "functionName": "List",
           "params": {"device": "id:wearos_small_round", "widthDp": 192, "heightDp": 192},
           "captures": [{"renderOutput": "renders/List-1.png", "scroll": {"mode": "END"}}]}
        ]}
        """
      )
    val a11y =
      dir.resolve("accessibility.json").apply {
        writeText(
          """
          {"module": "catalog", "entries": [
            {"previewId": "x.List", "findings": [
              {"level": "ERROR", "type": "TouchTargetSizeCheck", "message": "24dp tall.",
               "viewDescription": "Button", "boundsInScreen": "4,4,28,28"}
            ], "nodes": [
              {"label": "", "role": null, "ref": "list", "states": ["scrollable"], "merged": false,
               "boundsInScreen": "0,0,40,90"}
            ]}
          ]}
          """
        )
      }

    val inputs = HandoffInputs.read(dir.resolve("previews.json"), null, a11y, null, null)
    val subject = inputs.subjects.single()

    // The long screenshot is not sent up front: it is offered, and served from the staged file.
    assertEquals(listOf("device"), subject.pictures.map { it.kind })
    assertEquals(listOf("scroll-capture"), inputs.host.available("x.List"))
    assertEquals(emptyList<String>(), inputs.host.available("x.Other"))
    val need = GuidelineEvidenceNeedV1.Builder("scroll-capture").build()
    val long = inputs.host.render("x.List", need)!!
    assertEquals("scroll-capture", long.kind)
    assertEquals(192, long.widthDp)
    assertTrue(long.description!!.contains("long screenshot"))
    assertEquals(null, inputs.host.render("x.Other", need))
    // A changed long screenshot is a different judgement: it is part of the cached identity.
    val before = subject.renderHash
    renders.resolve("List-1_SCROLL_long.png").writeBytes(png() + byteArrayOf(0))
    val after =
      HandoffInputs.read(dir.resolve("previews.json"), null, a11y, null, null)
        .subjects
        .single()
        .renderHash
    assertTrue(before != after, "$before == $after")
    assertEquals(listOf("scrollable"), subject.nodes.single().states)
    assertEquals("TouchTargetSizeCheck", subject.checks.single().type)
    assertEquals("4,4,28,28", subject.checks.single().bounds)
  }

  @Test
  fun `a gradle run files a render under the identity a handoff run gives the staged copy`() {
    // The catalog publish (Gradle mode) saves results a PR's check (handoff mode) reads, so the
    // same render, long screenshot included, must hash the same in both.
    val module = Files.createTempDirectory("guidelines-identity").toFile()
    val buildDir = module.resolve("build/compose-previews").apply { mkdirs() }
    val render = buildDir.resolve("renders/List-1.png").apply { parentFile.mkdirs() }
    render.writeBytes(png())
    val long = buildDir.resolve("data/render-scroll-long/List-1_SCROLL_long.png")
    long.parentFile.mkdirs()
    long.writeBytes(png() + byteArrayOf(7))
    buildDir.resolve("renders/Plain-2.png").writeBytes(png() + byteArrayOf(1))
    val info =
      PreviewInfo(
        id = "x.List",
        functionName = "List",
        className = "x.ListKt",
        captures = listOf(Capture(renderOutput = "renders/List-1.png")),
        dataProducts =
          listOf(
            PreviewDataProduct(
              kind = HandoffInputs.LONG_KIND,
              output = "data/render-scroll-long/List-1_SCROLL_long.png",
            )
          ),
      )
    val plain =
      PreviewInfo(
        id = "x.Plain",
        functionName = "Plain",
        className = "x.PlainKt",
        captures = listOf(Capture(renderOutput = "renders/Plain-2.png")),
      )
    fun gradle(info: PreviewInfo, file: java.io.File): String =
      HandoffInputs.renderHash(
        sha256(file.readBytes()),
        HandoffInputs.longCaptureOf(info, file, buildDir),
      )

    // What guidelines-stage.py stages: each render, and the long screenshot beside it.
    val staged = Files.createTempDirectory("guidelines-identity-staged").toFile()
    staged.resolve("renders").mkdirs()
    render.copyTo(staged.resolve("renders/List-1.png"))
    long.copyTo(staged.resolve("renders/List-1_SCROLL_long.png"))
    buildDir.resolve("renders/Plain-2.png").copyTo(staged.resolve("renders/Plain-2.png"))
    staged
      .resolve("previews.json")
      .writeText(
        """
        {"previews": [
          {"id": "x.List", "captures": [{"renderOutput": "renders/List-1.png"}]},
          {"id": "x.Plain", "captures": [{"renderOutput": "renders/Plain-2.png"}]}
        ]}
        """
      )
    val handoff =
      HandoffInputs.read(staged.resolve("previews.json"), null, null, null, null)
        .subjects
        .associate { it.previewId to it.renderHash }

    assertEquals(handoff["x.List"], gradle(info, render))
    assertTrue(handoff["x.List"]!!.contains("+scroll:"))
    assertEquals(handoff["x.Plain"], gradle(plain, buildDir.resolve("renders/Plain-2.png")))
  }

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  @Test
  fun `a handoff with no extra captures offers no evidence, as before`() {
    val dir = Files.createTempDirectory("guidelines-handoff-plain").toFile()
    dir.resolve("A.png").writeBytes(png())
    val inputs = HandoffInputs.read(null, dir, null, null, null)
    assertEquals(emptyList<String>(), inputs.host.available)
  }

  @Test
  fun `a capture scrolled to its end says so, so scrolled-away content is not judged missing`() {
    val end = HandoffInputs.describeCapture(192, 192, "END")!!
    assertTrue("END" in end && "out of view" in end, end)
    assertEquals(
      "device render at 192×192dp, its first frame",
      HandoffInputs.describeCapture(192, 192, null),
    )
    assertNull(HandoffInputs.describeCapture(0, 0, null))
  }

  @Test
  fun `a handoff path leaving the staged tree is refused`() {
    val root = Files.createTempDirectory("guidelines-within").toFile()
    root.resolve("src").mkdirs()
    kotlin.test.assertNotNull(HandoffInputs.within(root, "src/A.kt"))
    assertNull(HandoffInputs.within(root, "../outside.kt"))
    assertNull(HandoffInputs.within(root, "src/../../outside.kt"))
    assertNull(HandoffInputs.within(root, "/etc/passwd"))
  }

  @Test
  fun `a flat id list narrows the PNGs in a renders directory`() {
    val dir = Files.createTempDirectory("guidelines-flat").toFile()
    dir.resolve("A.png").writeBytes(png())
    dir.resolve("B.png").writeBytes(png())
    dir.resolve("A.guidelines.png").writeBytes(png())
    val ids = dir.resolve("ids.json").apply { writeText("""["A"]""") }

    val inputs = HandoffInputs.read(ids, dir, null, null, null)

    assertEquals(listOf("A"), inputs.subjects.map { it.previewId })
    assertNull(inputs.subjects.single().source)
  }

  @Test
  fun `a render need becomes a render cell, and one asking nothing a cell changes does not`() {
    val dark =
      GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_RENDER)
        .apply {
          theme = "dark"
          fontScale = 1.5
        }
        .build()
    val cell = CliEvidenceHost.cellFor(dark)!!
    assertEquals("dark", cell.uiMode)
    assertEquals(1.5f, cell.fontScale)
    val nothing =
      GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_RENDER)
        .apply { layoutDirection = "rtl" }
        .build()
    assertNull(CliEvidenceHost.cellFor(nothing))
  }

  private fun guidelines() =
    CatalogGuidelinesLoader.parse(
        """
        {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "wear-m3",
         "platform": "wear", "version": 6, "rules": [
          {"id": "touch", "kind": "structure", "severity": "warning", "guidance": "48dp",
           "check": "Is every tap target at least 48dp?",
           "source": "https://developer.android.com/x", "surfaces": ["screen"]}
        ]}
        """
      )
      .guidelines!!

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  private class FakeModel : GuidelineModel {
    val requests = mutableListOf<GuidelineRequestV1>()

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      requests += request
      val content =
        """{"verdicts":[{"subjectId":"s1","ruleId":"touch","verdict":"fail","confidence":0.9,""" +
          """"nodeIds":["stop"],"reason":"The icon button is fixed at 36dp.","needs":[],""" +
          """"regions":[]}]}"""
      val body = buildJsonObject {
        put("id", "gen-1")
        put("model", "deepseek/deepseek-v4.1-flash")
        put(
          "choices",
          buildJsonArray {
            add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
          },
        )
      }
      return ModelResponse(200, body.toString())
    }

    override fun decide(body: JsonObject): ModelResponse = ModelResponse(500, "{}")
  }

  @Test
  fun `a run answered from the cache still writes every result to guidelines json`() {
    val buildDir = Files.createTempDirectory("guidelines-report").toFile()
    val subjects =
      listOf("a", "b").map { id ->
        PreviewSubject(
          previewId = id,
          label = id,
          surface = GuidelineSurfaces.SCREEN,
          renderHash = "h-$id",
          pictures = listOf(SubjectPicture("device", png(), 8, 8)),
        )
      }
    val model = FakeModel()
    fun run() =
      GuidelineEngine(
          model,
          cache = GuidelineResultCache(buildDir.resolve("guidelines")),
          options = GuidelineRunOptions(triage = false),
        )
        .run(guidelines(), subjects)
    run()
    val cached = run()
    assertEquals(1, model.requests.size)
    assertTrue(cached.results.all { it.fromCache })

    writeGuidelinesReport(
      buildDir,
      ModuleGuidelines(":catalog", "wear-m3", "m", cached.results),
      narrowed = false,
    )

    val written =
      GuidelinesCommand.REPORT_JSON.decodeFromString(
        ModuleGuidelines.serializer(),
        buildDir.resolve("guidelines.json").readText(),
      )
    assertEquals(listOf("a", "b"), written.results.map { it.previewId })
    assertTrue(written.results.all { it.fromCache && !it.pending })
    assertEquals(1, written.results.single { it.previewId == "a" }.failures().size)
    buildDir.deleteRecursively()
  }

  @Test
  fun `a widget preview in the manifest is judged as a widget of its profile`() {
    // remote-m3-catalog#72's sticker: a fixed widthDp/heightDp and no device, so before discovery
    // recorded `widget` it was judged a component and asked none of the catalog's widget rules.
    val dir = Files.createTempDirectory("guidelines-handoff-widget").toFile()
    val renders = dir.resolve("renders").apply { mkdirs() }
    renders.resolve("Widget-1.png").writeBytes(png())
    renders.resolve("Button-1.png").writeBytes(png())
    val previews =
      dir.resolve("previews.json").apply {
        writeText(
          """
          {"module": "remote-catalog", "previews": [
            {"id": "x.WidgetContainerLargeRemote", "functionName": "WidgetContainerLargeRemote",
             "params": {"device": null, "widthDp": 216, "heightDp": 124},
             "captures": [{"renderOutput": "renders/Widget-1.png"}],
             "widget": {"host": "wear", "profile": "wear-widgets"}},
            {"id": "x.FilledRemoteButton", "functionName": "FilledRemoteButton",
             "params": {"device": null},
             "captures": [{"renderOutput": "renders/Button-1.png"}]}
          ]}
          """
        )
      }

    val subjects =
      HandoffInputs.read(previews, null, null, null, null).subjects.associateBy { it.previewId }
    assertEquals(
      GuidelineSurfaces.WIDGET,
      subjects.getValue("x.WidgetContainerLargeRemote").surface,
    )
    assertEquals("wear-widgets", subjects.getValue("x.WidgetContainerLargeRemote").profile)
    assertEquals(GuidelineSurfaces.COMPONENT, subjects.getValue("x.FilledRemoteButton").surface)
    assertNull(subjects.getValue("x.FilledRemoteButton").profile)

    // The overrides replace both, for a manifest from a plugin that predates the field.
    val overridden =
      HandoffInputs.read(previews, null, null, null, "widget", profileOverride = "wear-widgets")
        .subjects
    assertTrue(overridden.all { it.surface == "widget" && it.profile == "wear-widgets" })
    // A live run reads the same classification off the module's manifest.
    assertEquals(
      GuidelineSurfaces.WIDGET,
      HandoffInputs.readKinds(previews).getValue("x.WidgetContainerLargeRemote").surface,
    )
  }

  @Test
  fun `guidelines json says when nothing was judged`() {
    val buildDir = Files.createTempDirectory("guidelines-unjudged").toFile()
    val subjects =
      listOf(
        PreviewSubject(
          previewId = "a",
          surface = GuidelineSurfaces.WIDGET,
          renderHash = "h-a",
          pictures = listOf(SubjectPicture("device", png(), 8, 8)),
        )
      )
    // guidelines() asks only `screen` rules: a widget is asked nothing, and no request is made.
    val model = FakeModel()
    val run =
      GuidelineEngine(model, options = GuidelineRunOptions(triage = false))
        .run(guidelines(), subjects)
    assertEquals(0, model.requests.size)

    writeGuidelinesReport(buildDir, ModuleGuidelines.of(":catalog", "wear-m3", "m", run), false)

    val written =
      GuidelinesCommand.REPORT_JSON.decodeFromString(
        ModuleGuidelines.serializer(),
        buildDir.resolve("guidelines.json").readText(),
      )
    assertEquals(0, written.requests)
    assertEquals(0, written.failedRequests)
    assertTrue(written.results.single().noRules!!.contains("surface `widget`"))
    assertTrue(written.problems.single().contains("not checked"), written.problems.toString())
    buildDir.deleteRecursively()
  }

  @Test
  fun `an incomplete check exits 2 even with findings, so CI never reads it as clean`() {
    assertEquals(2, guidelinesExitCode(incomplete = true, failed = false, buildOk = true))
    assertEquals(2, guidelinesExitCode(incomplete = true, failed = true, buildOk = true))
    assertEquals(1, guidelinesExitCode(incomplete = false, failed = true, buildOk = true))
    assertEquals(0, guidelinesExitCode(incomplete = false, failed = false, buildOk = true))
    assertEquals(2, guidelinesExitCode(incomplete = false, failed = false, buildOk = false))
  }
}
