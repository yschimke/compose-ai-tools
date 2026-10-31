package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class HotSwapTest {

  private val composer = "Landroidx/compose/runtime/Composer;"

  /**
   * A method of the class [classBytes] writes: its name, descriptor, the string it loads, its line.
   */
  private data class M(
    val name: String,
    val descriptor: String,
    val text: String,
    val line: Int = 10,
  )

  private fun classBytes(
    name: String,
    methods: List<M>,
    fields: List<String> = emptyList(),
    constant: Int? = null,
  ): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
    for (field in fields) writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd()
    if (constant != null) {
      val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
      writer.visitField(access, "LIMIT", "I", null, constant).visitEnd()
    }
    for (m in methods) {
      val mv =
        writer.visitMethod(
          Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
          m.name,
          m.descriptor,
          null,
          null,
        )
      mv.visitCode()
      val start = Label()
      mv.visitLabel(start)
      mv.visitLineNumber(m.line, start)
      mv.visitLdcInsn(m.text)
      mv.visitInsn(Opcodes.POP)
      mv.visitInsn(Opcodes.RETURN)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private val greeting = M("Greeting", "(${composer}I)V", "Hello")
  private val onCreate = M("onCreate", "(Landroid/os/Bundle;)V", "created")

  @Test
  fun `a changed composable body can swap, but not other code, fields or methods`() {
    val before = classBytes("com/example/UiKt", listOf(greeting, onCreate))

    assertNull(
      HotSwapCheck.why(
        before,
        classBytes("com/example/UiKt", listOf(greeting.copy(text = "Hi"), onCreate)),
      )
    )
    // An edit above shifts every line below it: not a change.
    assertNull(
      HotSwapCheck.why(
        before,
        classBytes("com/example/UiKt", listOf(greeting.copy(line = 12), onCreate.copy(line = 30))),
      )
    )

    val notComposable =
      HotSwapCheck.why(
        before,
        classBytes("com/example/UiKt", listOf(greeting, onCreate.copy(text = "again"))),
      )
    assertNotNull(notComposable)
    assertTrue("onCreate" in notComposable && "not composable" in notComposable, notComposable)

    val added =
      HotSwapCheck.why(
        before,
        classBytes(
          "com/example/UiKt",
          listOf(greeting, onCreate, M("Extra", "(${composer}I)V", "x")),
        ),
      )
    assertNotNull(added)
    assertTrue("added" in added, added)
    assertNotNull(
      HotSwapCheck.why(before, classBytes("com/example/UiKt", listOf(greeting))),
      "a removed method",
    )
    assertNotNull(
      HotSwapCheck.why(
        before,
        classBytes("com/example/UiKt", listOf(greeting, onCreate), fields = listOf("count")),
      ),
      "an added field",
    )
  }

  @Test
  fun `a changed constant restarts, since redefinition keeps the field's old value`() {
    val before = classBytes("com/example/UiKt", listOf(greeting), constant = 3)
    assertNull(
      HotSwapCheck.why(before, classBytes("com/example/UiKt", listOf(greeting), constant = 3))
    )
    val why =
      HotSwapCheck.why(before, classBytes("com/example/UiKt", listOf(greeting), constant = 4))
    assertNotNull(why)
    assertTrue("constants" in why, why)
  }

  private fun fixture(body: (File) -> Unit) {
    val root = Files.createTempDirectory("hot-swap-test").toFile()
    try {
      body(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private val kotlin = "app/build/tmp/kotlin-classes/debug"

  private fun File.compile(internalName: String, methods: List<M>) =
    File(this, "$kotlin/$internalName.class").apply {
      parentFile.mkdirs()
      writeBytes(classBytes(internalName, methods))
    }

  private fun setup(root: File): Triple<File, File, LiveReload> {
    val project = File(root, "project")
    File(project, "settings.gradle.kts").apply { parentFile.mkdirs() }.writeText("")
    val apk = File(root, "app.apk")
    ZipOutputStream(apk.outputStream()).use {
      it.putNextEntry(ZipEntry("classes.dex"))
      it.closeEntry()
    }
    val live =
      LiveReload(
        projectDir = project,
        module = ":app",
        variant = "debug",
        gradleArguments = emptyList(),
        workDir = File(root, "work"),
        log = {},
        runner = { tasks ->
          LiveReload.Build(ok = true, ms = 1, tasks = tasks, errors = emptyList())
        },
      )
    return Triple(project, apk, live)
  }

  @Test
  fun `a class swaps once it runs as compiler output, and only its composable edits`() =
    fixture { root ->
      val (project, apk, live) = setup(root)
      project.compile("com/example/UiKt", listOf(greeting, onCreate))
      live.recordBaseline()

      // First edit since the launch: the simulator runs the converted APK's copy; restart.
      project.compile("com/example/UiKt", listOf(greeting.copy(text = "1"), onCreate))
      val first = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      val restart = assertIs<LiveReload.Swap.Restart>(live.hotSwap(first))
      assertTrue("first edited" in restart.reason, restart.reason)
      live.applied()

      // Now it runs from the overlay, as compiler output: a composable edit swaps.
      project.compile("com/example/UiKt", listOf(greeting.copy(text = "2"), onCreate))
      val second = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      val swap = assertIs<LiveReload.Swap.Classes>(live.hotSwap(second))
      assertEquals(listOf("com/example/UiKt"), swap.names)
      assertTrue(File(second.overlay, "com/example/UiKt.class").isFile)
      live.applied()

      // A non-composable edit restarts, and so does putting the class back as it was at the launch.
      project.compile(
        "com/example/UiKt",
        listOf(greeting.copy(text = "2"), onCreate.copy(text = "3")),
      )
      val nonComposable = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      assertIs<LiveReload.Swap.Restart>(live.hotSwap(nonComposable))
      project.compile("com/example/UiKt", listOf(greeting, onCreate))
      val reverted = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
      val back = assertIs<LiveReload.Swap.Restart>(live.hotSwap(reverted))
      assertTrue("back to the launch" in back.reason, back.reason)
    }

  @Test
  fun `only the classes changed since what runs are swapped`() = fixture { root ->
    val (project, apk, live) = setup(root)
    project.compile("com/example/AKt", listOf(greeting))
    project.compile("com/example/BKt", listOf(greeting))
    live.recordBaseline()
    project.compile("com/example/AKt", listOf(greeting.copy(text = "a1")))
    project.compile("com/example/BKt", listOf(greeting.copy(text = "b1")))
    live.prepare(apk, full = false)
    live.applied()

    project.compile("com/example/BKt", listOf(greeting.copy(text = "b2")))
    val plan = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false))
    assertEquals(listOf("com/example/AKt.class", "com/example/BKt.class"), plan.changedClasses)
    assertEquals(
      listOf("com/example/BKt"),
      assertIs<LiveReload.Swap.Classes>(live.hotSwap(plan)).names,
    )
  }

  @Test
  fun `a reload without compiling runs no build and diffs what another compiler wrote`() =
    fixture { root ->
      val (project, apk, _) = setup(root)
      val builds = mutableListOf<List<String>>()
      val live =
        LiveReload(
          projectDir = project,
          module = ":app",
          variant = "debug",
          gradleArguments = emptyList(),
          workDir = File(root, "work"),
          log = {},
          runner = { tasks ->
            builds += tasks
            LiveReload.Build(ok = true, ms = 1, tasks = tasks, errors = emptyList())
          },
        )
      project.compile("com/example/UiKt", listOf(greeting, onCreate))
      live.recordBaseline()
      project.compile("com/example/UiKt", listOf(greeting.copy(text = "1"), onCreate))
      live.prepare(apk, full = false)
      live.applied()
      builds.clear()

      // The preview daemon's compiler wrote the edit into the class directory: diff it, build
      // nothing.
      project.compile("com/example/UiKt", listOf(greeting.copy(text = "2"), onCreate))
      val plan = assertIs<LiveReload.Plan.Overlay>(live.prepare(apk, full = false, compile = false))
      assertTrue(builds.isEmpty(), "no Gradle task ran: $builds")
      assertTrue(plan.build.ok && plan.build.tasks.isEmpty())
      assertEquals(
        listOf("com/example/UiKt"),
        assertIs<LiveReload.Swap.Classes>(live.hotSwap(plan)).names,
      )

      // Nothing written since: nothing to deliver.
      live.applied()
      assertIs<LiveReload.Plan.Unchanged>(live.prepare(apk, full = false, compile = false))
    }

  @Test
  fun `only the configuration cache's own refusal turns it off`() {
    assertTrue(
      LiveReload.refusedConfigurationCache(
        "1 problem was found storing the configuration cache.\nConfiguration cache problems found in this build."
      )
    )
    assertTrue(
      LiveReload.refusedConfigurationCache(
        "Configuration cache state could not be cached: field `x`"
      )
    )
    assertFalse(
      LiveReload.refusedConfigurationCache(
        "e: MainActivity.kt:12 Unresolved reference 'Txt'.\nConfiguration cache entry stored."
      )
    )
  }
}
