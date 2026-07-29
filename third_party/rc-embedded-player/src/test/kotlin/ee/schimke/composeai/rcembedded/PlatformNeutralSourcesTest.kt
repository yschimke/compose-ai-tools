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
 * Guards the decoupling work behind the CMP android/jvm split (see `PROVENANCE.md`).
 *
 * Nothing in the build enforces any of this today: the module is a plain android library, so every
 * source file has the Android SDK on its classpath and an `android.*` import compiles happily
 * wherever it lands. It would keep compiling after the restructure too — with only the android
 * target configured, `jvmCommonMain` is compiled as part of the android compilation. The coupling
 * would only surface much later, when the `jvm` target is added. This test is the constraint
 * standing in for the missing target.
 *
 * **Two things are required to move a file to `jvmCommonMain`, and this test checks one and a
 * half.**
 * 1. The file must not import an Android platform API. That is [IMPORT_CLEAN] below, checked for
 *    every file listed.
 * 2. The file must not reference a declaration that stays in `androidMain` — `jvmCommonMain` cannot
 *    see those. Only files in [READY_FOR_JVM_COMMON] are checked for this, and only for
 *    *cross-package* references: the vendored player splits into `embedded`, `embedded.layout`,
 *    `embedded.modifier` and `embedded.state`, so a file in a sub-package must import anything it
 *    uses from the root package and the import scan catches it. References *within* the root
 *    package need no import and are invisible here — `PROVENANCE.md`'s chain table is the record for
 *    those, not this test.
 *
 * So [IMPORT_CLEAN] is the weaker claim ("the decoupling done to this file has not regressed") and
 * [READY_FOR_JVM_COMMON] is the stronger one ("this file can move"). Keeping them apart matters:
 * `state/RcPlayerState.kt` is import-clean but reads `LocalGraphContext`, whose type extends
 * `AndroidRemoteContext`, so it cannot move until that chain is split. Listing it as ready would
 * certify a move that will not compile.
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
  fun declaredFilesImportNothingAndroid() {
    val offenders = mutableListOf<String>()
    forEachDeclaredFile(IMPORT_CLEAN + READY_FOR_JVM_COMMON) { relative, imported, line ->
      if (forbiddenPrefixes.any { imported.startsWith(it) }) offenders += "$relative: $line"
    }
    assertEquals(
      "These files were decoupled from the Android platform on purpose (PROVENANCE.md, " +
        "'CMP android/jvm') and have picked an Android import back up. Either drop the import, or " +
        "remove the file from this test and say why in PROVENANCE.md.",
      emptyList<String>(),
      offenders,
    )
  }

  @Test
  fun jvmCommonReadyFilesImportNothingThatStaysInAndroidMain() {
    val offenders = mutableListOf<String>()
    forEachDeclaredFile(READY_FOR_JVM_COMMON) { relative, imported, line ->
      if (imported.startsWith(PLAYER_PACKAGE)) {
        val simpleName = importedSimpleName(imported)
        // A star-import of the player package drags in whatever stays in `androidMain` and offers
        // no name to check, so it is refused rather than waved through.
        if (simpleName == "*" || simpleName in ANDROID_MAIN_DECLARATIONS) {
          offenders += "$relative: $line"
        }
      }
    }
    assertEquals(
      "These files are declared ready for jvmCommonMain but import a declaration that stays in " +
        "androidMain (or star-import the package it lives in), which jvmCommonMain cannot see — " +
        "the move would not compile. Split the declaration first, or move the file back to " +
        "IMPORT_CLEAN.",
      emptyList<String>(),
      offenders,
    )
  }

  /**
   * The declaration name an `import` line actually binds, ignoring any `as` alias.
   *
   * The alias is a local rename, not part of the declaration's identity — `import …embedded.Foo as
   * Bar` still depends on `Foo`. Taking the simple name off the raw line would compare
   * `"Foo as Bar"` and match nothing, so an aliased import of an `androidMain` declaration would
   * slip past. Not hypothetical: this vendored tree already aliases imports off this very package
   * (`…embedded.R as GoogleFontR` in two files).
   */
  private fun importedSimpleName(imported: String): String =
    imported.substringBefore(" as ").trim().substringAfterLast('.')

  @Test
  fun importedSimpleNameIgnoresAliases() {
    assertEquals("LocalGraphContext", importedSimpleName("$PLAYER_PACKAGE.LocalGraphContext"))
    assertEquals(
      "LocalGraphContext",
      importedSimpleName("$PLAYER_PACKAGE.LocalGraphContext as PlayerGraphContext"),
    )
    // The real aliased import in the tree today.
    assertEquals("R", importedSimpleName("$PLAYER_PACKAGE.R as GoogleFontR"))
    // Star-imports surface as `*` so the caller can refuse them.
    assertEquals("*", importedSimpleName("$PLAYER_PACKAGE.*"))
    // A name merely containing "as" is not an alias.
    assertEquals("CanvasOperations", importedSimpleName("$PLAYER_PACKAGE.CanvasOperations"))
  }

  /** Feeds every `import` line of each declared file to [block] as (relative path, FQN, raw line). */
  private fun forEachDeclaredFile(
    files: List<String>,
    block: (relative: String, imported: String, line: String) -> Unit,
  ) {
    val root = playerSourceRoot()
    for (relative in files) {
      val file = File(root, relative)
      // A rename that silently drops a file from the guard is the failure this catches.
      assertTrue("declared in this test but missing: $relative", file.isFile)
      file.readLines()
        .filter { it.startsWith("import ") }
        .forEach { block(relative, it.removePrefix("import ").trim(), it) }
    }
  }

  /**
   * Locates `src/main/kotlin/androidx/compose/remote/player/compose/embedded`, walking up from the
   * working directory so the test does not depend on which directory Gradle runs it from. Failing
   * to find it fails the test — a guard that silently finds no files to check is worse than none.
   */
  private fun playerSourceRoot(): File {
    val suffix = "src/main/kotlin/${PLAYER_PACKAGE.replace('.', '/')}"
    var dir: File? = File("").absoluteFile
    while (dir != null) {
      val candidate = File(dir, suffix)
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw AssertionError("could not locate $suffix from ${File("").absolutePath}")
  }

  private companion object {
    const val PLAYER_PACKAGE = "androidx.compose.remote.player.compose.embedded"

    /**
     * Declarations that stay in `androidMain` — they name an Android type in their signature or
     * supertype. Mirrors the chain table in `PROVENANCE.md`; splitting one is what lets its
     * dependents graduate from [IMPORT_CLEAN] to [READY_FOR_JVM_COMMON].
     */
    val ANDROID_MAIN_DECLARATIONS =
      setOf(
        // extends AndroidRemoteContext, and the composition local that hands it out
        "GraphContext",
        "LocalGraphContext",
        // android.graphics.Bitmap / Rect / drawable on the canvas draw path
        "executeOperations",
        "resolveBitmap",
        "resolveCanvasBitmap",
        "rememberRemoteBitmapAsState",
        // framework android.graphics.Paint + RuntimeShader
        "ComposeLocalPaint",
        "updatePaintFromBundle",
        // Drawable-typed image loading
        "RcImageLoader",
        "EmbeddedRcImageLoader",
        "LocalRcImageLoader",
        "DrawablePainter",
        // SuppressLint / PendingIntent / AndroidRemoteContext
        "RcPlayer",
        "RcPlayerChildren",
        "RcPlayerComponent",
        // googlefonts Font/GoogleFont, Typeface, FontRequest
        "RcPlayerText",
        "EmbeddedPlayerTypefaceResolver",
        // AndroidPaintContext
        "drawParticles",
        "drawParticlesCompare",
      )

    /**
     * Import-clean, but still referencing something that stays in `androidMain`, so not yet movable.
     * These are here to hold decoupling work that has already been done against regression.
     */
    val IMPORT_CLEAN =
      listOf(
        // Decoupled by moving `rememberRemoteBitmapAsState` to `state/RcPlayerBitmapState.kt`. Its
        // fourteen sibling helpers are referenced by 19 files outside `state/`, which makes this the
        // highest-leverage file in the split. Still blocked on the `GraphContext` chain: the
        // helpers read `LocalGraphContext` to resolve computed ids.
        "state/RcPlayerState.kt"
      )

    /**
     * Import-clean *and* free of cross-package references into `androidMain` — these are the files
     * that can actually move. Platform-neutral as vendored: the reflective `CoreDocument` accessors,
     * the document data model, and the snapshot-backed state store are plain `remote-core` types.
     */
    val READY_FOR_JVM_COMMON =
      listOf("CoreDataAccessors.kt", "CoreDataModel.kt", "SnapshotRemoteComposeState.kt")
  }
}
