package ee.schimke.composeai.cli.serve

import androidx.compose.runtime.Composer
import ee.schimke.composeai.daemon.PreviewManifestRouter
import ee.schimke.composeai.daemon.RenderEngine
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderTarget
import ee.schimke.composeai.daemon.UserClassLoaderHolder
import ee.schimke.composeai.daemon.pathOrNull
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.imageio.ImageIO
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

/** Bytecode metadata → actual daemon routing → encoded PNG, using the released dependency pin. */
class PlaygroundDesktopRenderTest {
  @Test
  fun `compiled phone and tablet previews render at distinct requested dimensions`() {
    val root = Files.createTempDirectory("playground-desktop-render").toFile()
    try {
      val annotation =
        root.resolve("Preview.java").apply {
          writeText(
            """
            package org.jetbrains.compose.ui.tooling.preview;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Preview { int widthDp() default -1; int heightDp() default -1; }
            """
              .trimIndent()
          )
        }
      // A Java fixture with the composable ABI avoids executing a Kotlin compiler or loading
      // snippet classes during discovery. Box itself is the real Compose implementation.
      val source =
        root.resolve("GeneratedKt.java").apply {
          writeText(
            """
            package generated;
            import org.jetbrains.compose.ui.tooling.preview.Preview;
            import androidx.compose.runtime.Composer;
            public class GeneratedKt {
              @Preview(widthDp=412, heightDp=915) public static void Phone(Composer c, int flags) { draw(c); }
              @Preview(widthDp=1280, heightDp=800) public static void Tablet(Composer c, int flags) { draw(c); }
              private static void draw(Composer c) {
                try {
                  androidx.compose.ui.Modifier fill = androidx.compose.foundation.layout.SizeKt.fillMaxSize(androidx.compose.ui.Modifier.Companion, 1f);
                  for (java.lang.reflect.Method m : androidx.compose.foundation.BackgroundKt.class.getMethods()) {
                    if (m.getName().startsWith("background-") && m.getParameterCount() == 3) {
                      fill = (androidx.compose.ui.Modifier)m.invoke(null, fill, androidx.compose.ui.graphics.ColorKt.Color(0xff008577), androidx.compose.ui.graphics.RectangleShapeKt.getRectangleShape());
                      break;
                    }
                  }
                  androidx.compose.foundation.layout.BoxKt.Box(fill, c, 0);
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
              }
            }
            """
              .trimIndent()
          )
        }
      val classes = root.resolve("classes").apply { mkdirs() }
      val classpath =
        listOf(
            Composer::class.java,
            androidx.compose.ui.Modifier::class.java,
            androidx.compose.ui.graphics.Color::class.java,
            androidx.compose.foundation.layout.BoxScope::class.java,
            androidx.compose.foundation.BorderStroke::class.java,
            kotlin.Unit::class.java,
          )
          .map { File(it.protectionDomain.codeSource.location.toURI()).path }
          .distinct()
          .joinToString(File.pathSeparator)
      assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
          .run(
            null,
            null,
            null,
            "-classpath",
            classpath,
            "-d",
            classes.path,
            annotation.path,
            source.path,
          ),
      )
      classes.resolve("org").deleteRecursively()
      val jar = root.resolve("snippet.jar")
      JarOutputStream(jar.outputStream()).use { out ->
        classes
          .walkTopDown()
          .filter { it.isFile }
          .forEach { file ->
            out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
            file.inputStream().use { it.copyTo(out) }
            out.closeEntry()
          }
      }
      for (output in listOf(classes, jar)) {
        val ids = listOf("generated.GeneratedKt.Phone", "generated.GeneratedKt.Tablet")
        val snippet =
          PlaygroundTokenStore.PlaygroundSnippet(
            mode = PlaygroundMode.CMP,
            workDir = root.path.toPath(),
            classesDir = output.path.toPath(),
            classpath = listOf(output.path.toPath()),
            moduleName = "playground-cmp",
            previewId = ids.first(),
            previewIds = ids,
          )
        val manifestFile =
          root.resolve("previews.json").apply {
            writeText(PlaygroundPreviews.previewManifestJson(snippet))
          }
        val host =
          PreviewManifestRouter(
            PreviewManifestRouter.loadManifest(manifestFile),
            engine = RenderEngine(outputDir = root.resolve("renders")),
            userClassloaderHolder =
              UserClassLoaderHolder(listOf(output.toURI().toURL()), { javaClass.classLoader }),
          )
        host.start()
        try {
          val dimensions = mutableListOf<Pair<Int, Int>>()
          ids.forEachIndexed { index, id ->
            val result =
              host.submit(
                RenderRequest.Render(id = index.toLong(), target = RenderTarget.Preview(id)),
                30_000L,
              )
            val png = File(result.artifact.pathOrNull()!!)
            val image = ImageIO.read(png)
            val name = id.substringAfterLast('.')
            val evidence = File("build/playground-dimensions-evidence/${output.name}/$name.png")
            evidence.parentFile.mkdirs()
            png.copyTo(evidence, overwrite = true)
            dimensions += image.width to image.height
            assertEquals(0xff008577.toInt(), image.getRGB(image.width / 2, image.height / 2))
          }
          assertEquals(listOf(824 to 1830, 2560 to 1600), dimensions, output.name)
        } finally {
          host.shutdown()
        }
      }
    } finally {
      root.deleteRecursively()
    }
  }
}
