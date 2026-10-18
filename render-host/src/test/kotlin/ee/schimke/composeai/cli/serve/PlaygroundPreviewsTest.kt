package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/**
 * The synthesized `previews.json` a bundle-less playground daemon renders against. What matters
 * here is coverage: the daemon can only stream a preview the manifest names, so a snippet that
 * compiled several must see all of them listed — otherwise the extras are compiled and then
 * unreachable, which is exactly the single-preview limit this manifest used to impose.
 */
class PlaygroundPreviewsTest {

  @Test
  fun `compiled preview dimensions survive manifest synthesis without executing the snippet`() {
    val directory = Files.createTempDirectory("playground-preview-dimensions").toFile()
    try {
      val annotation =
        directory.resolve("Preview.java").apply {
          writeText(
            """
            package androidx.compose.ui.tooling.preview;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Preview { int widthDp() default -1; int heightDp() default -1; }
            """
              .trimIndent()
          )
        }
      val settledAnnotation =
        directory.resolve("SettledPreview.java").apply {
          writeText(
            """
            package ee.schimke.composeai.preview;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface SettledPreview { int afterMs() default 0; int maxMs() default 1000; }
            """
              .trimIndent()
          )
        }
      val source =
        directory.resolve("Snippet.java").apply {
          writeText(
            """
            package sample;
            import androidx.compose.ui.tooling.preview.Preview;
            public class Snippet {
              static { if (true) throw new AssertionError("Do not execute preview code during discovery"); }
              @ee.schimke.composeai.preview.SettledPreview(afterMs=350, maxMs=900)
              @Preview(widthDp=360, heightDp=216) public static void Fixed() {}
              @Preview(widthDp=120) public static void WidthOnly() {}
              @Preview public static void Wrapped() {}
              @Preview(widthDp=800, heightDp=800) public static void NotSelected() {}
            }
            """
              .trimIndent()
          )
        }
      assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
          .run(
            null,
            null,
            null,
            "-d",
            directory.path,
            annotation.path,
            settledAnnotation.path,
            source.path,
          ),
      )
      val manifest =
        json.decodeFromString<PreviewManifest>(
          PlaygroundPreviews.previewManifestJson(
            snippet(
                listOf("sample.Snippet.Fixed", "sample.Snippet.WidthOnly", "sample.Snippet.Wrapped")
              )
              .copy(classesDir = directory.path.toPath())
          )
        )
      assertEquals(3, manifest.previews.size)
      assertEquals(
        350,
        manifest.previews[0]
          .captures
          .single()
          .settle
          ?.jsonObject
          ?.get("afterMs")
          ?.jsonPrimitive
          ?.int,
      )
      assertEquals(
        900,
        manifest.previews[0].captures.single().settle?.jsonObject?.get("maxMs")?.jsonPrimitive?.int,
      )
      assertEquals(
        0,
        manifest.previews[1]
          .captures
          .single()
          .settle
          ?.jsonObject
          ?.get("afterMs")
          ?.jsonPrimitive
          ?.int,
      )
      assertEquals(
        1000,
        manifest.previews[1].captures.single().settle?.jsonObject?.get("maxMs")?.jsonPrimitive?.int,
      )
      assertEquals(360, manifest.previews[0].params.widthDp)
      assertEquals(216, manifest.previews[0].params.heightDp)
      assertEquals(120, manifest.previews[1].params.widthDp)
      assertNull(manifest.previews[1].params.heightDp)
      assertNull(manifest.previews[2].params.widthDp)
      assertNull(manifest.previews[2].params.heightDp)
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun `phone and tablet metadata survives a jar with external annotation definitions`() {
    val directory = Files.createTempDirectory("playground-jar-dimensions").toFile()
    try {
      val annotation =
        directory.resolve("Preview.java").apply {
          writeText(
            """
            package org.jetbrains.compose.ui.tooling.preview;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Preview { int widthDp() default -1; int heightDp() default -1; }
            """
              .trimIndent()
          )
        }
      val source =
        directory.resolve("GeneratedKt.java").apply {
          writeText(
            """
            package generated;
            import org.jetbrains.compose.ui.tooling.preview.Preview;
            public class GeneratedKt {
              static { if (true) throw new AssertionError("Do not execute discovery"); }
              @Preview(widthDp=412, heightDp=915) public static void Phone() {}
              @Preview(widthDp=1280, heightDp=800) public static void Tablet() {}
            }
            """
              .trimIndent()
          )
        }
      val classes = directory.resolve("classes").apply { mkdirs() }
      assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
          .run(null, null, null, "-d", classes.path, annotation.path, source.path),
      )
      // Production compilation emits only snippet classes; annotation definitions live in catalog
      // jars.
      classes.resolve("org").deleteRecursively()
      val ids = listOf("generated.GeneratedKt.Phone", "generated.GeneratedKt.Tablet")
      fun dimensions(path: java.io.File) =
        json
          .decodeFromString<PreviewManifest>(
            PlaygroundPreviews.previewManifestJson(
              snippet(ids).copy(classesDir = path.path.toPath())
            )
          )
          .previews
          .map { it.params.widthDp to it.params.heightDp }
      val expected = listOf(412 to 915, 1280 to 800)
      assertEquals(expected, dimensions(classes))
      val jar = directory.resolve("snippet.jar")
      JarOutputStream(jar.outputStream()).use { output ->
        classes
          .walkTopDown()
          .filter { it.isFile }
          .forEach { file ->
            output.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
            file.inputStream().use { it.copyTo(output) }
            output.closeEntry()
          }
      }
      assertEquals(expected, dimensions(jar))
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun `desktop stills settle but explicit timeline annotations and Android keep their capture policy`() {
    val directory = Files.createTempDirectory("playground-capture-policy").toFile()
    try {
      val sources =
        mapOf(
            "Preview.java" to
              """
          package androidx.compose.desktop.ui.tooling.preview;
          @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
          public @interface Preview {}
        """,
            "AnimatedPreview.java" to
              """
          package ee.schimke.composeai.preview;
          @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
          public @interface AnimatedPreview {}
        """,
            "FocusedPreview.java" to
              """
          package ee.schimke.composeai.preview;
          @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
          public @interface FocusedPreview { boolean gif() default false; }
        """,
            "RoboComposePreviewOptions.java" to
              """
          package com.github.takahirom.roborazzi.annotations;
          @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
          public @interface RoboComposePreviewOptions {}
        """,
            "Snippet.java" to
              """
          package sample;
          import androidx.compose.desktop.ui.tooling.preview.Preview;
          public class Snippet {
            @Preview public static void Still() {}
            @Preview @ee.schimke.composeai.preview.AnimatedPreview public static void Motion() {}
            @Preview @com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions public static void Timed() {}
            @Preview @ee.schimke.composeai.preview.FocusedPreview(gif=true) public static void FocusGif() {}
            @Preview @ee.schimke.composeai.preview.FocusedPreview(gif=false) public static void FocusStill() {}
            @Preview @ee.schimke.composeai.preview.FocusedPreview public static void FocusDefault() {}
          }
        """,
          )
          .map { (name, source) ->
            directory.resolve(name).apply { writeText(source.trimIndent()) }
          }
      assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
          .run(
            null,
            null,
            null,
            "-d",
            directory.path,
            *sources.map { it.path }.toTypedArray(),
          ),
      )
      // Production annotation definitions normally live outside the snippet's compiled output.
      directory.resolve("ee/schimke/composeai/preview/FocusedPreview.class").delete()
      val ids =
        listOf("Still", "Motion", "Timed", "FocusGif", "FocusStill", "FocusDefault").map {
          "sample.Snippet.$it"
        }
      fun manifest(mode: PlaygroundMode) =
        json.decodeFromString<PreviewManifest>(
          PlaygroundPreviews.previewManifestJson(
            snippet(ids).copy(mode = mode, classesDir = directory.path.toPath())
          )
        )
      val desktop = manifest(PlaygroundMode.CMP)
      assertEquals(
        0,
        desktop.previews[0]
          .captures
          .single()
          .settle
          ?.jsonObject
          ?.get("afterMs")
          ?.jsonPrimitive
          ?.int,
      )
      assertEquals(
        1000,
        desktop.previews[0].captures.single().settle?.jsonObject?.get("maxMs")?.jsonPrimitive?.int,
      )
      assertNull(desktop.previews[1].captures.single().settle)
      assertNull(desktop.previews[2].captures.single().settle)
      assertNull(
        desktop.previews[3].captures.single().settle,
        "a focused GIF must retain its timeline",
      )
      for (index in listOf(4, 5)) {
        assertEquals(
          0,
          desktop.previews[index]
            .captures
            .single()
            .settle
            ?.jsonObject
            ?.get("afterMs")
            ?.jsonPrimitive
            ?.int,
          "a focused still should settle",
        )
        assertEquals(
          1000,
          desktop.previews[index]
            .captures
            .single()
            .settle
            ?.jsonObject
            ?.get("maxMs")
            ?.jsonPrimitive
            ?.int,
        )
      }
      manifest(PlaygroundMode.ANDROID).previews.forEach { assertNull(it.captures.single().settle) }
    } finally {
      directory.deleteRecursively()
    }
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun snippet(previewIds: List<String>) =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.CMP,
      workDir = "/w/snippet".toPath(),
      classesDir = "/w/snippet/classes".toPath(),
      classpath = listOf("/w/snippet/classes".toPath()),
      moduleName = "playground-cmp",
      previewId = previewIds.first(),
      previewIds = previewIds,
    )

  private fun decode(previewIds: List<String>): PreviewManifest =
    json.decodeFromString(
      PreviewManifest.serializer(),
      PlaygroundPreviews.previewManifestJson(snippet(previewIds)),
    )

  @Test
  fun `every declared preview is listed, in declaration order`() {
    val manifest =
      decode(
        listOf(
          "com.example.SnippetKt.Alpha",
          "com.example.OtherKt.Beta",
          "com.example.OtherKt.Gamma",
        )
      )
    assertEquals(
      listOf(
        "com.example.SnippetKt.Alpha",
        "com.example.OtherKt.Beta",
        "com.example.OtherKt.Gamma",
      ),
      manifest.previews.map { it.id },
      "the live session can only navigate to previews the manifest names",
    )
    assertEquals("playground-cmp", manifest.module)
  }

  @Test
  fun `an id is split back into its class and function`() {
    val entry = decode(listOf("com.example.SnippetKt.Alpha")).previews.single()
    assertEquals("com.example.SnippetKt", entry.className)
    assertEquals("Alpha", entry.functionName)
  }

  @Test
  fun `a snippet that names only one preview still yields exactly one entry`() {
    // The default `previewIds = listOf(previewId)` keeps every pre-existing single-preview caller
    // producing the manifest it always did.
    val manifest =
      json.decodeFromString(
        PreviewManifest.serializer(),
        PlaygroundPreviews.previewManifestJson(
          PlaygroundTokenStore.PlaygroundSnippet(
            mode = PlaygroundMode.CMP,
            workDir = "/w/snippet".toPath(),
            classesDir = "/w/snippet/classes".toPath(),
            classpath = listOf("/w/snippet/classes".toPath()),
            moduleName = "playground-cmp",
            previewId = "com.example.SnippetKt.Only",
          )
        ),
      )
    assertEquals(listOf("com.example.SnippetKt.Only"), manifest.previews.map { it.id })
  }
}
