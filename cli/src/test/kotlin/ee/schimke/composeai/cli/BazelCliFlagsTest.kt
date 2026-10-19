package ee.schimke.composeai.cli

import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #6115: `--target=`, `--output` placement, `--help` on the normal commands. */
class BazelCliFlagsTest {
  private val root: File = Files.createTempDirectory("bazel-cli-flags").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun preview(function: String, suffix: String = "", output: String = "") =
    PreviewInfo(
      id = "com.example.UiKt.$function$suffix",
      functionName = function,
      className = "com.example.UiKt",
      captures = listOf(Capture(renderOutput = output)),
    )

  @Test
  fun `one preview goes to the named file`() {
    val only = preview("Foo")
    val png = root.resolve("foo.png")
    assertEquals(mapOf(only.id to png), bazelOutputFiles(listOf(only), png))
  }

  @Test
  fun `several previews for one file get one file each, named by variant`() {
    val light = preview("Foo", "_Light")
    val dark = preview("Foo", "_Dark - Large Round")
    val plain = preview("Foo")
    val outputs = bazelOutputFiles(listOf(light, dark, plain), root.resolve("foo.png"))
    assertEquals(root.resolve("foo_Light.png"), outputs[light.id])
    assertEquals(root.resolve("foo_Dark_-_Large_Round.png"), outputs[dark.id])
    // Nothing distinguishes it from the function: its position does.
    assertEquals(root.resolve("foo_3.png"), outputs[plain.id])
  }

  @Test
  fun `a directory receives every preview under its capture name`() {
    val a = preview("A", output = "renders/A-1234.png")
    val b = preview("B", output = "renders/B-5678.png")
    val dir = root.resolve("out").apply { mkdirs() }
    assertEquals(
      mapOf(a.id to dir.resolve("A-1234.png"), b.id to dir.resolve("B-5678.png")),
      bazelOutputFiles(listOf(a, b), dir),
    )
    // A path ending in a separator is a directory even before it exists.
    val typed = root.resolve("fresh").path + File.separator
    assertEquals(
      root.resolve("fresh/A-1234.png"),
      bazelOutputFiles(listOf(a), File(typed), typed)[a.id],
    )
  }

  @Test
  fun `a positional name never takes one a variant already claimed`() {
    val two = preview("Foo", "2")
    val plain = preview("Foo")
    val outputs = bazelOutputFiles(listOf(two, plain), root.resolve("foo.png"))
    assertEquals(root.resolve("foo_2.png"), outputs[two.id])
    assertEquals(root.resolve("foo_3.png"), outputs[plain.id])
  }

  @Test
  fun `names that differ only in case are still distinct files`() {
    val dark = preview("Foo", "Dark")
    val lower = preview("Foo", "dark")
    val outputs = bazelOutputFiles(listOf(dark, lower), root.resolve("foo.png"))
    assertEquals(root.resolve("foo_Dark.png"), outputs[dark.id])
    assertEquals(root.resolve("foo_2.png"), outputs[lower.id])
  }

  @Test
  fun `--function narrows results and render scope to that exact function`() {
    val foo = preview("Foo")
    val fooDisabled = preview("FooPreview_Disabled")
    assertTrue(previewIdMatchesRequest(foo.id, null, null, functionName = "Foo", function = "Foo"))
    assertFalse(
      previewIdMatchesRequest(
        fooDisabled.id,
        null,
        null,
        functionName = "FooPreview_Disabled",
        function = "Foo",
      )
    )
    assertFalse(
      previewMatchesRequestIncludingRows(
        fooDisabled,
        exactId = null,
        filter = null,
        exactIdExists = false,
        function = "Foo",
      )
    )
  }

  @Test
  fun `previews of several targets never share an output`() {
    val a = preview("Foo", output = "renders/Foo.png")
    val b = preview("Foo", output = "renders/Foo.png")
    val file = bazelOutputPlan(listOf("//a" to a, "//b" to b), root.resolve("foo.png"))
    assertEquals(2, file.values.toSet().size, "$file")
    val dir = root.resolve("out").apply { mkdirs() }
    val inDir = bazelOutputPlan(listOf("//a" to a, "//b" to b), dir)
    assertEquals(dir.resolve("Foo.png"), inDir["//a" to a.id])
    assertEquals(dir.resolve("Foo_2.png"), inDir["//b" to b.id])
  }

  @Test
  fun `target labels survive the equals form`() {
    val workspace = root.resolve("ws").apply { mkdirs() }
    workspace.resolve("MODULE.bazel").writeText("")
    workspace.resolve("app/feature").apply { mkdirs() }.resolve("BUILD.bazel").writeText("")
    val argv = root.resolve("argv.log")
    val fake =
      root.resolve("fakebazel.sh").apply {
        writeText("#!/bin/sh\necho \"$*\" >> ${argv.path}\nexit 1\n")
        setExecutable(true)
      }
    for (form in
      listOf(
        listOf("--target=//app/feature:ui_lib"),
        listOf("--target", "//app/feature:ui_lib"),
      )) {
      argv.delete()
      runCatching {
        BazelCommand(
            listOf("list") +
              form +
              listOf("--project", workspace.path, "--build-command", fake.path)
          )
          .execute()
      }
      val built = argv.readText()
      assertTrue(built.trim().endsWith(" //app/feature:ui_lib"), "$form built: $built")
      assertTrue("--target" !in built, "$form leaked into the build: $built")
    }
  }

  @Test
  fun `the normal commands answer --help themselves`() {
    for (command in listOf("list", "render", "show")) {
      val help = projectPreviewCommandHelp(command)
      assertTrue(help.startsWith("compose-preview $command"), help)
      assertTrue("--function" in help, help)
      assertTrue(CliFlagValidation.unknownFlags(command, listOf("--help")).isEmpty())
      assertTrue(CliFlagValidation.unknownFlags(command, listOf("-h")).isEmpty())
    }
  }
}
