package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * **S3.5 — render-after-recompile (the actual save-loop scenario).**
 *
 * The load-bearing assertion for B2.0's disposable user-classloader (see
 * [CLASSLOADER.md](../../../../../../../../docs/daemon/CLASSLOADER.md)). Pre-B2.0 both backends
 * silently returned stale bytecode after a recompile because `Class.forName` caches by name and
 * ignores updated bytes on disk. B2.0 splits the daemon's classloader hierarchy: a long-lived
 * parent (Robolectric `InstrumentingClassLoader` on Android, the JVM app classloader on desktop)
 * and a disposable child [java.net.URLClassLoader] reading the user's compiled-class output.
 * `fileChanged({ kind: "source" })` swaps the child; the next render reads recompiled bytecode.
 *
 * **Implementation choice — option 2 (runtime ASM rewrite).** The harness already ships `RedSquare`
 * / `BlueSquare` composables compiled by Kotlin + Compose compiler. We use ASM's [ClassRemapper] to
 * clone each one into a single FQN (`ee.schimke.composeai.daemon.MutableSquare`); the resulting
 * `.class` files differ only in their underlying colour-constant origin. Avoids the dual-sourceset
 * Gradle plumbing of option 1 — the trade-off the placeholder's KDoc spelled out — and lets us
 * reuse already-compose-compiled `@Composable` functions rather than fighting the compiler to mint
 * them by hand.
 *
 * **Wire shape.**
 * 1. Mint `MutableSquare.class` from RedSquare bytes; copy into a temp `userClassDirs` directory.
 * 2. Spawn the daemon with `-Dcomposeai.daemon.userClassDirs=<tempDir>`. The manifest references
 *    `ee.schimke.composeai.daemon.MutableSquare#MutableSquare()`.
 * 3. `renderNow` → expect red.
 * 4. Mint `MutableSquare.class` from BlueSquare bytes; overwrite the temp file.
 * 5. `fileChanged(path=<class file>, kind=source)` → daemon swaps the child loader.
 * 6. `renderNow` → expect blue.
 *
 * Pre-B2.0 the second render would still be red (the cached `Class<?>` from step 1 stays bound to
 * the host's classloader). Post-B2.0 step 6 reads MutableSquare.class fresh off disk via the new
 * child URLClassLoader and Compose recomposes against the new bytecode → blue.
 *
 * **Cross-target.** Both `-Ptarget=desktop` and `-Ptarget=android` skip via `Assume.assumeTrue` for
 * `host=fake`; the assertion shape is identical for both targets.
 */
class S3_5RecompileSaveLoopRealModeTest {

  @Test
  fun `recompiled bytecode flows through to the next render`() {
    Assume.assumeTrue(
      "Skipping S3_5RecompileSaveLoopRealModeTest — set -Pharness.host=real to enable.",
      HarnessTestSupport.harnessHost() == "real",
    )

    val target = HarnessTestSupport.harnessTarget()
    // B2.0-followup — the Android-skip was originally diagnosed as
    // ASM-bytecode-clone-vs-Compose-compiler-method-mangling. The classloader forensics dump
    // (CLASSLOADER-FORENSICS.md, compare generated forensic diffs when this fails)
    // surfaced the actual root cause as classloader-identity skew: UserClassLoaderHolder's
    // child URLClassLoader had been inheriting the host thread's app loader as parent rather
    // than the Robolectric sandbox loader, so framework classes (Composer, Activity, etc.)
    // resolved via two distinct Class<?> instances and getDeclaredComposableMethod's
    // parameter-type comparison failed. The fix wires a parent supplier that reads
    // DaemonHostBridge.sandboxClassLoaderRef (set inside SandboxHoldingRunner.holdSandboxOpen
    // before any render). Android S3.5 is now expected to pass; un-skipped here.
    val mutableFqn = "ee.schimke.composeai.daemon.MutableSquare"
    val mutableInternalName = "ee/schimke/composeai/daemon/MutableSquare"
    val mutableClassFile = "$mutableInternalName.class"

    // Source classes we clone into MutableSquare. Same FQN across both targets — testFixtures of
    // the chosen target are on the harness's *test* classpath via the Real(Desktop|Android)
    // launcher's classpath sysprop.
    val sourceClass = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"
    val sourceInternal = sourceClass.replace('.', '/')

    val tempUserClassesDir =
      File(System.getProperty("java.io.tmpdir"), "compose-ai-b2.0-userclasses-${System.nanoTime()}")
        .apply {
          deleteRecursively()
          mkdirs()
        }
    val mutableClassPath = File(tempUserClassesDir, mutableClassFile).apply { parentFile.mkdirs() }

    // Locate the RedFixturePreviewsKt bytes on the harness's *test* classpath. ClassLoader's
    // resource lookup routes through the parent loader → the test JVM's `java.class.path` →
    // testFixtures jar / dir.
    val sourceBytes =
      Thread.currentThread().contextClassLoader.getResourceAsStream("$sourceInternal.class")?.use {
        it.readBytes()
      }
        ?: javaClass.classLoader.getResourceAsStream("$sourceInternal.class")?.use {
          it.readBytes()
        }
        ?: error(
          "S3.5: $sourceInternal.class not found on the harness test classpath; " +
            "the testFixtures dependency must expose it"
        )

    // Multi-iteration cycle exposes the "first edit updates, subsequent edits stick" failure mode
    // — a single recompile pass (red → blue) was sufficient to verify the disposable child loader
    // swap in B2.0, but it can't see a regression where only the *first* swap takes hold and a
    // stale loader / cached `Class<?>` survives subsequent swaps. Cycling red → blue → red → blue
    // across four versions forces the swap path to work repeatedly; the assertion at every step
    // catches a failure the moment it happens rather than letting a hidden cache mask it.
    val versions: List<Pair<String, ByteArray>> =
      listOf(
          "RedSquare" to "RedSquare",
          "BlueSquare" to "BlueSquare",
          "RedSquare" to "RedSquare",
          "BlueSquare" to "BlueSquare",
        )
        .map { (label, sourceMethod) ->
          label to
            cloneAsMutableSquare(
              sourceBytes,
              sourceInternal = sourceInternal,
              targetInternal = mutableInternalName,
              sourceMethod = sourceMethod,
              targetMethod = "MutableSquare",
            )
        }
    mutableClassPath.writeBytes(versions[0].second)

    val rendersDir =
      File("build/daemon-harness/renders/s3.5").apply {
        deleteRecursively()
        mkdirs()
      }
    val reportsDir =
      File("build/reports/daemon-harness/s3.5").apply {
        deleteRecursively()
        mkdirs()
      }
    val manifestFile =
      File("build/daemon-harness/manifests/s3.5-previews.json").apply { parentFile.mkdirs() }
    manifestFile.writeText(
      """{"previews":[{"id":"mutable-square","className":"$mutableFqn",""" +
        """"functionName":"MutableSquare","widthPx":64,"heightPx":64,"density":1.0,""" +
        """"showBackground":true,"outputBaseName":"mutable-square"}]}"""
    )

    // Build a launcher with the user-class-dirs sysprop pointing at our temp dir. The daemon's
    // [UserClassLoaderHolder] picks it up at start; `RedFixturePreviewsKt` (the parent that
    // MutableSquare came from) stays on the test JVM's classpath, but child-first delegation in
    // [UserClassLoaderHolder]'s [ChildFirstURLClassLoader] makes the child win for MutableSquare.
    val launcher: HarnessLauncher =
      when (target) {
        "desktop" -> {
          val classpath =
            System.getProperty("java.class.path")
              .split(File.pathSeparator)
              .filter { it.isNotBlank() }
              .map { File(it) }
          RealDesktopHarnessLauncher(
            rendersDir = rendersDir,
            previewsManifest = manifestFile,
            classpath = classpath,
            extraJvmArgs =
              listOf("-Dcomposeai.daemon.userClassDirs=${tempUserClassesDir.absolutePath}"),
          )
        }
        "android" -> {
          val classpath =
            RealAndroidHarnessLauncher.classpathFromProperty()
              ?: run {
                Assume.assumeTrue(
                  "Skipping S3.5 android — `composeai.harness.androidDaemonClasspath` unset.",
                  false,
                )
                return
              }
          RealAndroidHarnessLauncher(
            rendersDir = rendersDir,
            previewsManifest = manifestFile,
            classpath = classpath,
            extraJvmArgs =
              listOf("-Dcomposeai.daemon.userClassDirs=${tempUserClassesDir.absolutePath}"),
          )
        }
        else -> {
          Assume.assumeTrue("Skipping S3.5 — unknown target=$target.", false)
          return
        }
      }

    val client = HarnessClient.start(launcher)
    val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
    val perIterationBytes = mutableListOf<ByteArray>()
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      versions.forEachIndexed { index, (label, bytes) ->
        // First iteration uses the bytes already written to disk + the daemon's initial loader; no
        // fileChanged needed. Subsequent iterations are the post-recompile save-loop step we want
        // to verify: overwrite the .class on disk → notify the daemon → next render must reflect
        // the new bytecode.
        if (index > 0) {
          mutableClassPath.writeBytes(bytes)
          client.fileChanged(path = mutableClassPath.absolutePath, kind = FileKind.SOURCE)
        }

        val iterationStart = System.currentTimeMillis()
        val rn = client.renderNow(previews = listOf("mutable-square"), tier = RenderTier.FAST)
        assertEquals(listOf("mutable-square"), rn.queued)
        val finished = client.pollRenderFinishedFor("mutable-square", timeout = 120.seconds)
        val iterationFinishedAt = System.currentTimeMillis()
        val pngPath =
          finished["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished missing pngPath: $finished")
        val rendered = File(pngPath).readBytes()
        perIterationBytes += rendered

        // Same-version iterations must produce byte-identical PNGs (renderer is deterministic
        // when the bytecode is the same); different-version iterations must differ. The pattern
        // [Red, Blue, Red, Blue] gives us both shapes — iterations 0/2 must match, 1/3 must
        // match, but 0 vs 1 and 2 vs 3 must differ. The "stuck after first edit" bug shows up as
        // index 2 matching index 1 (still blue) instead of index 0 (red again).
        for (priorIndex in 0 until index) {
          val priorBytes = perIterationBytes[priorIndex]
          val priorLabel = versions[priorIndex].first
          val sameLabel = priorLabel == label
          val diff = PixelDiff.compare(actual = rendered, expected = priorBytes)
          if (sameLabel && !diff.ok) {
            PixelDiff.writeDiffArtefacts(
              actual = rendered,
              expected = priorBytes,
              outDir = File(reportsDir, "iter-$index-vs-$priorIndex"),
            )
            throw AssertionError(
              "S3.5 [$target]: iteration $index ($label) produced different bytes than " +
                "iteration $priorIndex ($priorLabel) — same bytecode should render identical PNGs. " +
                "${diff.message}. Stderr=\n${client.dumpStderr()}"
            )
          }
          if (!sameLabel && diff.ok) {
            PixelDiff.writeDiffArtefacts(
              actual = rendered,
              expected = priorBytes,
              outDir = File(reportsDir, "iter-$index-vs-$priorIndex"),
            )
            throw AssertionError(
              "S3.5 [$target]: iteration $index ($label) produced identical bytes to " +
                "iteration $priorIndex ($priorLabel) — daemon served stale user-classloader " +
                "bytecode after a recompile-and-fileChanged. " +
                "${if (priorIndex == 0) "B2.0's disposable child loader is not swapping at all." else "Disposable child loader works for the first edit but not subsequent edits — the failure mode this multi-iteration cycle was added to expose."} " +
                "Stderr=\n${client.dumpStderr()}"
            )
          }
        }

        recorder.record(
          scenario = "s3.5-real-$target",
          preview = "mutable-square@iter$index-$label",
          actualMs = iterationFinishedAt - iterationStart,
          notes = "S3.5 real $target: iteration $index ($label)",
        )
        assertTrue(
          "S3.5 iteration $index render took unreasonably long (>120s); B2.0 child-loader swap may be stalling.",
          iterationFinishedAt - iterationStart < 120_000,
        )
      }

      // Clean shutdown.
      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)
    } catch (t: Throwable) {
      System.err.println(
        "S3_5RecompileSaveLoopRealModeTest [$target] failed; daemon stderr:\n${client.dumpStderr()}"
      )
      throw t
    } finally {
      try {
        client.close()
      } catch (_: Throwable) {}
      tempUserClassesDir.deleteRecursively()
    }
  }

  /**
   * Clones [sourceBytes] (a Kotlin file-class containing [sourceMethod] as a `@Composable`) into a
   * fresh class with FQN [targetInternal] whose [targetMethod] is the byte-for-byte copy of
   * [sourceMethod]. Other methods on the source class are dropped — we only care about a single
   * composable.
   *
   * Implementation: ASM's [ClassRemapper] handles internal-name rewriting (the source class's
   * self-references); a custom [ClassVisitor] filters down to the synthetic `<init>` + the named
   * composable method only.
   */
  private fun cloneAsMutableSquare(
    sourceBytes: ByteArray,
    sourceInternal: String,
    targetInternal: String,
    sourceMethod: String,
    targetMethod: String,
  ): ByteArray {
    val reader = ClassReader(sourceBytes)
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
    val remapper =
      object : Remapper() {
        override fun map(internalName: String): String =
          if (internalName == sourceInternal) targetInternal else super.map(internalName)
      }
    // Remap the FQN. Keep every method body intact — Compose-emitted helper methods
    // (`RedSquare$lambda$0`, `ComposableSingletons$RedFixturePreviewsKt`, etc.) often live on the
    // file class and the named composable's body references them. Dropping helpers would surface
    // as `NoSuchMethodError` at first composition. Only rename the chosen method to
    // [targetMethod]; all other methods retain their original names but live on the new FQN. Any
    // collisions between renamed and original methods are deliberately avoided by picking
    // [targetMethod] = `MutableSquare` which doesn't exist on the source.
    val filter =
      object : ClassVisitor(Opcodes.ASM9, ClassRemapper(writer, remapper)) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?,
        ) =
          super.visitMethod(
            access,
            if (name == sourceMethod) targetMethod else name,
            descriptor,
            signature,
            exceptions,
          )
      }
    reader.accept(filter, 0)
    return writer.toByteArray()
  }
}
