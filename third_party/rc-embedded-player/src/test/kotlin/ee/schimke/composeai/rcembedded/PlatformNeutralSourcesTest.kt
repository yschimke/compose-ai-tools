/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the platform-neutral half of the CMP android/jvm split (see `PROVENANCE.md`).
 *
 * Files listed in [PLATFORM_NEUTRAL] have been deliberately decoupled from the Android platform so
 * they can move to `jvmCommonMain` when the module is restructured. Nothing in the *current* build
 * enforces that: the module is a plain android library, so every source file has the Android SDK on
 * its classpath and an `android.*` import in any of them compiles happily. It would keep compiling
 * after the restructure too — with only the android target configured, `jvmCommonMain` is compiled
 * as part of the android compilation. The coupling would only surface much later, when the `jvm`
 * target is added and the file no longer resolves.
 *
 * So this test is the constraint standing in for the missing target. It is deliberately a *source*
 * scan rather than a bytecode one: the question is which source set a file may live in, which is a
 * question about its imports.
 *
 * Adding a file here is a claim that it is ready for `jvmCommonMain`. Removing one is a decision to
 * give up on that, and belongs in `PROVENANCE.md` with a reason.
 */
class PlatformNeutralSourcesTest {

  /**
   * Import prefixes that tie a file to the Android platform — the same four the CMP measurement in
   * `PROVENANCE.md` counts.
   */
  private val forbiddenPrefixes =
    listOf(
      "android.",
      "androidx.core.",
      "androidx.compose.remote.player.core.platform.",
      "androidx.compose.ui.text.googlefonts.",
    )

  @Test
  fun platformNeutralSourcesImportNothingAndroid() {
    val root = playerSourceRoot()
    val offenders = mutableListOf<String>()
    for (relative in PLATFORM_NEUTRAL) {
      val file = File(root, relative)
      // A rename that silently drops a file from the guard is the failure this catches.
      assertTrue("declared platform-neutral but missing: $relative", file.isFile)
      file.readLines()
        .filter { it.startsWith("import ") }
        .forEach { line ->
          val imported = line.removePrefix("import ").trim()
          if (forbiddenPrefixes.any { imported.startsWith(it) }) {
            offenders += "$relative: $line"
          }
        }
    }
    assertEquals(
      "These files are declared ready for jvmCommonMain (PROVENANCE.md, 'CMP android/jvm') but " +
        "import Android platform APIs. Either drop the import or remove the file from " +
        "PLATFORM_NEUTRAL and say why in PROVENANCE.md.",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * Locates `src/main/kotlin/androidx/compose/remote/player/compose/embedded`, walking up from the
   * working directory so the test does not depend on which directory Gradle runs it from. Failing
   * to find it fails the test — a guard that silently finds no files to check is worse than none.
   */
  private fun playerSourceRoot(): File {
    val suffix = "src/main/kotlin/androidx/compose/remote/player/compose/embedded"
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      val candidate = File(dir, suffix)
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw AssertionError("could not locate $suffix from ${File("").absolutePath}")
  }

  private companion object {
    /**
     * Files decoupled from the Android platform so far. This is not yet the whole `jvmCommonMain`
     * set — `PROVENANCE.md` tracks which chains are still to be split, and each split adds its file
     * here.
     */
    val PLATFORM_NEUTRAL =
      listOf(
        // Decoupled by moving `rememberRemoteBitmapAsState` to `state/RcPlayerBitmapState.kt`.
        // Its fourteen sibling helpers are referenced by 19 files outside `state/`, which makes
        // this the single highest-leverage file in the split.
        "state/RcPlayerState.kt",
        // Platform-neutral as vendored — the reflective `CoreDocument` accessors and the document
        // data model are plain `remote-core` types.
        "CoreDataAccessors.kt",
        "CoreDataModel.kt",
        "SnapshotRemoteComposeState.kt",
      )
  }
}
