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
    // (CLASSLOADER-FORENSICS.md, the diff at docs/daemon/classloader-forensics-diff.md)
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

    val v1Bytes =
      cloneAsMutableSquare(
        sourceBytes,
        sourceInternal = sourceInternal,
        targetInternal = mutableInternalName,
        sourceMethod = "RedSquare",
        targetMethod = "MutableSquare",
      )
    val v2Bytes =
      cloneAsMutableSquare(
        sourceBytes,
        sourceInternal = sourceInternal,
        targetInternal = mutableInternalName,
        sourceMethod = "BlueSquare",
        targetMethod = "MutableSquare",
      )

    mutableClassPath.writeBytes(v1Bytes)

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

    val firstStart = System.currentTimeMillis()
    val client = HarnessClient.start(launcher)
    try {
      assertEquals(1, client.initialize().protocolVersion)
      client.sendInitialized()

      // 1. First render — MutableSquare cloned from RedSquare. Expect red.
      val rn1 = client.renderNow(previews = listOf("mutable-square"), tier = RenderTier.FAST)
      assertEquals(listOf("mutable-square"), rn1.queued)
      val finished1 = client.pollRenderFinishedFor("mutable-square", timeout = 120.seconds)
      val firstFinishedAt = System.currentTimeMillis()
      val redPath =
        finished1["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finished1")
      val redBytes = File(redPath).readBytes()

      // 2. Recompile — overwrite MutableSquare.class with BlueSquare bytes. Notify the daemon.
      mutableClassPath.writeBytes(v2Bytes)
      client.fileChanged(path = mutableClassPath.absolutePath, kind = FileKind.SOURCE)

      // 3. Second render — MutableSquare cloned from BlueSquare. Expect blue.
      val secondStart = System.currentTimeMillis()
      val rn2 = client.renderNow(previews = listOf("mutable-square"), tier = RenderTier.FAST)
      assertEquals(listOf("mutable-square"), rn2.queued)
      val finished2 = client.pollRenderFinishedFor("mutable-square", timeout = 120.seconds)
      val secondFinishedAt = System.currentTimeMillis()
      val bluePath =
        finished2["params"]?.jsonObject?.get("pngPath")?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $finished2")
      val blueBytes = File(bluePath).readBytes()

      // 4. The load-bearing assertion: red and blue must differ. Pre-B2.0 the second render
      //    returned the cached red bytecode and the bytes were identical — exactly the staleness
      //    failure mode B2.0 fixes.
      val diff = PixelDiff.compare(actual = redBytes, expected = blueBytes)
      if (diff.ok) {
        PixelDiff.writeDiffArtefacts(actual = redBytes, expected = blueBytes, outDir = reportsDir)
        throw AssertionError(
          "S3.5 [$target]: post-fileChanged render produced identical bytes — daemon served stale " +
            "user-classloader bytecode. B2.0's disposable child loader is not swapping. " +
            "Stderr=\n${client.dumpStderr()}"
        )
      }

      // 5. Clean shutdown.
      val exitCode = client.shutdownAndExit(timeout = 30.seconds)
      assertEquals("Daemon must exit cleanly. Stderr=\n${client.dumpStderr()}", 0, exitCode)

      val recorder = LatencyRecorder(csvFile = HarnessTestSupport.LATENCY_CSV)
      recorder.record(
        scenario = "s3.5-real-$target",
        preview = "mutable-square@v1",
        actualMs = firstFinishedAt - firstStart,
        notes = "S3.5 real $target: pre-recompile render (red)",
      )
      recorder.record(
        scenario = "s3.5-real-$target",
        preview = "mutable-square@v2",
        actualMs = secondFinishedAt - secondStart,
        notes = "S3.5 real $target: post-recompile render (blue, after fileChanged swap)",
      )
      assertTrue(
        "S3.5 second render took unreasonably long (>120s); B2.0 child-loader swap may be stalling.",
        secondFinishedAt - secondStart < 120_000,
      )
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
