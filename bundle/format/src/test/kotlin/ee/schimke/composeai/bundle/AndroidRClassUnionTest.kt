package ee.schimke.composeai.bundle

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The app's R jar widened with the renderer's R fields: the `runs=daemon` render of an app on
 * Compose 1.7.5 against a renderer on 1.12.1, whose `androidx.compose.ui.R$id` gained
 * `androidx_compose_ui_view_compose_view_context`.
 */
class AndroidRClassUnionTest {

  private val root: File = Files.createTempDirectory("r-union").toFile()

  /** Compiles [sources] (path to Java source) and jars the classes as [name]. */
  private fun jar(name: String, sources: Map<String, String>, classpath: File? = null): File {
    val src = root.resolve("$name-src")
    val classes = root.resolve("$name-classes").apply { mkdirs() }
    val files = sources.map { (path, text) ->
      src.resolve(path).apply {
        parentFile.mkdirs()
        writeText(text)
      }
    }
    val args =
      listOfNotNull("--release", "11", "-d", classes.path) +
        (classpath?.let { listOf("-cp", it.path) } ?: emptyList()) +
        files.map { it.path }
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "needs a JDK" }
    assertEquals(0, compiler.run(null, null, null, *args.toTypedArray()))
    val out = root.resolve("$name.jar")
    ZipOutputStream(out.outputStream()).use { zip ->
      classes
        .walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .forEach {
          zip.putNextEntry(ZipEntry(it.relativeTo(classes).invariantSeparatorsPath))
          zip.write(it.readBytes())
          zip.closeEntry()
        }
    }
    return out
  }

  // As link.sh compiles an APK's R: final IDs from the app's (older) libraries.
  private val appR =
    """
    package androidx.compose.ui;
    public final class R {
      public static final class id {
        public static final int accessibility_action=0x7f010001;
        public static final int tag_shared=0x7f010002;
      }
      public static final class string {
        public static final int close_drawer=0x7f020001;
      }
      public static final class styleable {
        public static final int[] Widget={ 0x7f030001 };
        public static final int Widget_size=0;
      }
    }
    """
      .trimIndent()

  // As a library's R: non-final IDs from the renderer's own (newer) resource table.
  private val rendererR =
    """
    package androidx.compose.ui;
    public final class R {
      public static final class id {
        public static int accessibility_action=0x7f0b0001;
        public static int androidx_compose_ui_view_compose_view_context=0x7f0b0002;
        public static int tag_shared=0x7f0b0003;
      }
      public static final class string {
        public static int close_drawer=0x7f0c0001;
      }
      public static final class styleable {
        public static int[] Widget={ 0x7f0d0001 };
        public static int Widget_size=0;
        public static int[] Sheet={ 0x7f0d0002 };
        public static int Sheet_peek=0;
      }
    }
    """
      .trimIndent()

  private val appOwnR =
    """
    package com.example.app;
    public final class R {
      public static final class string { public static final int greeting=0x7f020002; }
    }
    """
      .trimIndent()

  @Test
  fun `the renderer's Compose links against the widened R with the app's IDs`() {
    val app =
      jar("app", mapOf("androidx/compose/ui/R.java" to appR, "com/example/app/R.java" to appOwnR))
    val renderer = jar("renderer", mapOf("androidx/compose/ui/R.java" to rendererR))
    // Compiled against the renderer's R, as the renderer's Compose is.
    val user =
      jar(
        "user",
        mapOf(
          "com/example/user/User.java" to
            """
            package com.example.user;
            import androidx.compose.ui.R;
            public final class User {
              public static int[] ids() {
                return new int[] {
                  R.id.androidx_compose_ui_view_compose_view_context, R.id.accessibility_action,
                  R.string.close_drawer, R.styleable.Sheet_peek
                };
              }
              public static int[] sheet() { return R.styleable.Sheet; }
            }
            """
              .trimIndent()
        ),
        classpath = renderer,
      )

    fun call(rJar: File, method: String): Any? =
      URLClassLoader(arrayOf(rJar.toURI().toURL(), user.toURI().toURL()), null).use {
        it.loadClass("com.example.user.User").getMethod(method).invoke(null)
      }

    // The failure the External Android consumer hit: the app's R lacks the renderer's field.
    val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> { call(app, "ids") }
    assertTrue(failure.targetException is NoSuchFieldError, failure.targetException.toString())

    val union =
      AndroidRClassUnion.union(
        app,
        listOf(root.resolve("missing.jar"), renderer),
        root.resolve("union.jar"),
      )
    assertEquals(
      // Added from the renderer; then the app's own IDs, which name its APK's resources.
      listOf(0x7f0b0002, 0x7f010001, 0x7f020001, 0),
      (call(union, "ids") as IntArray).toList(),
    )
    // A styleable array new to the renderer needs initialisation code, so it is not added.
    val sheet =
      assertFailsWith<java.lang.reflect.InvocationTargetException> { call(union, "sheet") }
    assertTrue(sheet.targetException is NoSuchFieldError)

    val loader = URLClassLoader(arrayOf(union.toURI().toURL()), null)
    val id = loader.loadClass("androidx.compose.ui.R\$id")
    val added = id.getField("androidx_compose_ui_view_compose_view_context")
    assertTrue(java.lang.reflect.Modifier.isFinal(added.modifiers))
    assertEquals(0x7f010002, id.getField("tag_shared").getInt(null))
    ZipFile(app).use { before ->
      ZipFile(union).use { after ->
        assertEquals(
          before.entries().asSequence().map { it.name }.toList(),
          after.entries().asSequence().map { it.name }.toList(),
        )
        // Classes the renderer adds nothing to are copied byte for byte.
        for (name in listOf("com/example/app/R\$string.class", "androidx/compose/ui/R.class")) {
          assertTrue(
            before
              .getInputStream(before.getEntry(name))
              .readBytes()
              .contentEquals(after.getInputStream(after.getEntry(name)).readBytes()),
            name,
          )
        }
      }
    }
  }

  @Test
  fun `nothing to widen returns the app's jar`() {
    val app = jar("own", mapOf("com/example/app/R.java" to appOwnR))
    val renderer = jar("other", mapOf("androidx/compose/ui/R.java" to rendererR))
    assertSame(app, AndroidRClassUnion.union(app, listOf(renderer), root.resolve("unused.jar")))
    assertEquals(
      listOf(root, app),
      AndroidRClassUnion.widen(listOf(root, app), listOf(renderer)),
    )
  }
}
