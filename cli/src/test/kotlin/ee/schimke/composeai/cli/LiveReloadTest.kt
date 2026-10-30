package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveReloadTest {

  private fun fixture(body: (File) -> Unit) {
    val root = Files.createTempDirectory("live-reload-test").toFile()
    try {
      body(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun File.write(path: String, text: String): File =
    File(this, path).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  /** A class file for [internalName] under [dir], whose content differs by [marker]. */
  private fun File.writeClass(dir: String, internalName: String, marker: String): File {
    val writer = org.objectweb.asm.ClassWriter(0)
    writer.visit(
      org.objectweb.asm.Opcodes.V17,
      org.objectweb.asm.Opcodes.ACC_PUBLIC,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, marker, "I", null, null).visitEnd()
    writer.visitEnd()
    return File(this, "$dir/$internalName.class").apply {
      parentFile.mkdirs()
      writeBytes(writer.toByteArray())
    }
  }

  private fun File.marker(): String {
    var field = ""
    org.objectweb.asm
      .ClassReader(readBytes())
      .accept(
        object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
          override fun visitField(
            access: Int,
            name: String,
            descriptor: String?,
            signature: String?,
            value: Any?,
          ): org.objectweb.asm.FieldVisitor? {
            field = name
            return null
          }
        },
        0,
      )
    return field
  }

  private fun zip(file: File, entries: Map<String, String>): File {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((name, text) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
    return file
  }

  private val ok = LiveReload.Build(ok = true, ms = 1, tasks = emptyList(), errors = emptyList())

  private fun live(project: File, work: File, builds: MutableList<List<String>> = mutableListOf()) =
    LiveReload(
      projectDir = project,
      module = ":app",
      variant = "debug",
      gradleArguments = emptyList(),
      workDir = work,
      log = {},
      runner = { tasks ->
        builds += tasks
        ok
      },
    )

  @Test
  fun `a reload overlays only the classes that changed or are new`() = fixture { root ->
    val project = File(root, "project").apply { write("settings.gradle.kts", "") }
    val kotlin = "app/build/tmp/kotlin-classes/debug"
    val javac = "lib/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    project.writeClass(kotlin, "com/example/A", "a1")
    project.writeClass(kotlin, "com/example/B", "b1")
    // A library module's javac output, and build logic that is not the app.
    project.writeClass(javac, "lib/L", "l1")
    project.writeClass("buildSrc/build/classes/kotlin/main", "Plugin", "p1")
    val apk = zip(File(root, "app.apk"), mapOf("classes.dex" to "dex"))
    val builds = mutableListOf<List<String>>()
    val live = live(project, File(root, "work"), builds)
    live.recordBaseline()

    assertIs<LiveReload.Plan.Unchanged>(live.prepare(apk, full = false))

    project.writeClass(kotlin, "com/example/A", "a2")
    project.writeClass(kotlin, "com/example/C", "c1")
    project.writeClass(javac, "lib/L", "l2")
    project.writeClass("buildSrc/build/classes/kotlin/main", "Plugin", "p2")
    val plan = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))

    assertEquals(
      listOf("com/example/A.class", "com/example/C.class", "lib/L.class"),
      plan.changedClasses,
    )
    assertEquals("a2", File(plan.overlay, "com/example/A.class").marker())
    assertFalse(File(plan.overlay, "com/example/B.class").exists())
    assertFalse(plan.resources)
    assertEquals(apk, plan.resourceApk, "unchanged resources come from the launched APK")
    assertEquals(listOf(":app:compileDebugJavaWithJavac"), builds.last())

    // Once running, the same build is nothing new; a further edit is.
    live.applied()
    assertIs<LiveReload.Plan.Unchanged>(live.prepare(apk, full = false))
    project.writeClass(kotlin, "com/example/B", "b2")
    val next = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
    assertEquals(
      listOf("com/example/A.class", "com/example/B.class", "com/example/C.class", "lib/L.class"),
      next.changedClasses,
      "every change since the launch: each reload starts over from the converted APK",
    )
  }

  @Test
  fun `relinked resources replace the apk's, and moved ids bring the r classes`() =
    fixture { root ->
      val project = File(root, "project").apply { write("settings.gradle.kts", "") }
      val linked =
        "app/build/intermediates/linked_resources_binary_format/debug/processDebugResources/" +
          "linked-resources-binary-format-debug.ap_"
      val symbols = "app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt"
      val rJar =
        "app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/" +
          "processDebugResources/R.jar"
      zip(File(project, linked), mapOf("resources.arsc" to "old", "AndroidManifest.xml" to "m1"))
      project.write(symbols, "int string hello 0x7f010000")
      val apk =
        zip(
          File(root, "app.apk"),
          mapOf(
            "resources.arsc" to "old",
            "res/layout/a.xml" to "old layout",
            "assets/data.json" to "{}",
            "classes.dex" to "dex",
            "AndroidManifest.xml" to "m1",
          ),
        )
      val live = live(project, File(root, "work"))
      live.recordBaseline()

      zip(
        File(project, linked),
        mapOf("resources.arsc" to "new", "AndroidManifest.xml" to "m2", "res/layout/b.xml" to "b"),
      )
      val values = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      assertTrue(values.resources)
      assertFalse(values.resourceIds)
      assertNull(values.overlay, "no class changed")
      ZipFile(values.resourceApk).use { merged ->
        assertEquals("new", merged.text("resources.arsc"))
        assertEquals("m2", merged.text("AndroidManifest.xml"))
        assertEquals("{}", merged.text("assets/data.json"), "assets stay")
        assertNull(merged.getEntry("res/layout/a.xml"), "the old res/ is replaced whole")
        assertEquals("b", merged.text("res/layout/b.xml"))
      }

      project.write(symbols, "int string hello 0x7f010000\nint string bye 0x7f010001")
      val rClass = File(root, "r").writeClass("classes", "com/example/R\$string", "r")
      File(project, rJar).parentFile.mkdirs()
      ZipOutputStream(File(project, rJar).outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("com/example/R\$string.class"))
        zip.write(rClass.readBytes())
        zip.closeEntry()
      }
      zip(File(project, linked), mapOf("resources.arsc" to "newer"))
      val ids = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      assertTrue(ids.resourceIds)
      assertEquals("r", File(ids.overlay, "com/example/R\$string.class").marker())
    }

  @Test
  fun `changed assets and a failed build fall back or stop`() = fixture { root ->
    val project = File(root, "project").apply { write("settings.gradle.kts", "") }
    project.write("app/build/tmp/kotlin-classes/debug/A.class", "a")
    project.write("app/src/main/assets/a.txt", "1")
    project.write("app/build/outputs/apk/debug/app-debug.apk", "apk")
    val apk = zip(File(root, "app.apk"), mapOf("classes.dex" to "dex"))
    val builds = mutableListOf<List<String>>()
    val live = live(project, File(root, "work"), builds)
    live.recordBaseline()

    project.write("app/src/main/assets/a.txt", "22")
    val rebuild = assertIs<LiveReload.Plan.Rebuild>(live.prepare(apk, full = false))
    assertEquals(File(project, "app/build/outputs/apk/debug/app-debug.apk"), rebuild.apk)
    assertEquals(listOf(":app:assembleDebug"), builds.last())

    val failing =
      LiveReload(project, ":app", "debug", emptyList(), File(root, "work2"), log = {}) {
        LiveReload.Build(false, 1, it, listOf("e: A.kt:3:1 Unresolved reference"))
      }
    failing.recordBaseline()
    val failed = assertIs<LiveReload.Plan.Failed>(failing.prepare(apk, full = false))
    assertEquals(listOf("e: A.kt:3:1 Unresolved reference"), failed.build.errors)
  }

  @Test
  fun `a flavored variant finds its apk and its build type's library outputs`() = fixture { root ->
    val project = File(root, "project").apply { write("settings.gradle.kts", "") }
    project.write("app/build/outputs/apk/demo/debug/app-demo-debug.apk", "apk")
    project.write("app/build/tmp/kotlin-classes/demoDebug/A.class", "a")
    project.write("core/build/tmp/kotlin-classes/debug/C.class", "c")
    val live =
      LiveReload(project, ":app", "demoDebug", emptyList(), File(root, "work"), log = {}) { ok }
    assertEquals(
      File(project, "app/build/outputs/apk/demo/debug/app-demo-debug.apk"),
      live.findApk(),
    )
    assertEquals(setOf("A.class", "C.class"), live.fingerprint().classes.keys)
    assertEquals(":app:assembleDemoDebug", live.assembleTask)
  }

  private fun ZipFile.text(name: String): String? =
    getEntry(name)?.let { getInputStream(it).readBytes().decodeToString() }

  @Test
  fun `overlaid classes take the converter's names, without dashes`() = fixture { root ->
    // A Kotlin value-class call and a `lambda-1` class, as kotlinc writes them.
    val writer = org.objectweb.asm.ClassWriter(0)
    writer.visit(
      org.objectweb.asm.Opcodes.V17,
      org.objectweb.asm.Opcodes.ACC_PUBLIC,
      "app/Screen\$lambda-1",
      null,
      "java/lang/Object",
      null,
    )
    val method =
      writer.visitMethod(
        org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC,
        "draw-0d7_KjU",
        "()V",
        null,
        null,
      )
    method.visitCode()
    method.visitMethodInsn(
      org.objectweb.asm.Opcodes.INVOKESTATIC,
      "androidx/compose/runtime/Updater",
      "constructor-impl",
      "()V",
      false,
    )
    method.visitInsn(org.objectweb.asm.Opcodes.RETURN)
    method.visitMaxs(0, 0)
    method.visitEnd()
    writer.visitEnd()

    val name = LiveReload.writeOverlayClass(writer.toByteArray(), root)

    assertEquals("app/Screen\$lambda_1", name)
    val written = File(root, "app/Screen\$lambda_1.class")
    assertTrue(written.isFile)
    val calls = mutableListOf<String>()
    val methods = mutableListOf<String>()
    org.objectweb.asm
      .ClassReader(written.readBytes())
      .accept(
        object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
          override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
          ): org.objectweb.asm.MethodVisitor {
            methods += name
            return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
              override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
              ) {
                calls += "$owner.$name"
              }
            }
          }
        },
        0,
      )
    assertEquals(listOf("draw_0d7_KjU"), methods)
    assertEquals(listOf("androidx/compose/runtime/Updater.constructor_impl"), calls)
  }

  @Test
  fun `a class removed since the launch rebuilds the apk`() = fixture { root ->
    val project = File(root, "project").apply { write("settings.gradle.kts", "") }
    val kotlin = "app/build/tmp/kotlin-classes/debug"
    project.writeClass(kotlin, "com/example/A", "a1")
    val old = project.writeClass(kotlin, "com/example/Old", "o1")
    project.write("app/build/outputs/apk/debug/app-debug.apk", "apk")
    val apk = zip(File(root, "app.apk"), mapOf("classes.dex" to "dex"))
    val live = live(project, File(root, "work"))
    live.recordBaseline()

    old.delete()
    project.writeClass(kotlin, "com/example/New", "n1")
    val plan = assertIs<LiveReload.Plan.Rebuild>(live.prepare(apk, full = false))
    assertTrue(plan.reason.contains("removed"), plan.reason)
  }

  @Test
  fun `only a launch that can reload outlives its app`() {
    assertTrue(LaunchCommand.outlivesExits(project = true, control = true, live = false))
    assertTrue(LaunchCommand.outlivesExits(project = true, control = false, live = true))
    assertFalse(LaunchCommand.outlivesExits(project = true, control = false, live = false))
    assertFalse(LaunchCommand.outlivesExits(project = false, control = true, live = false))
  }
}
