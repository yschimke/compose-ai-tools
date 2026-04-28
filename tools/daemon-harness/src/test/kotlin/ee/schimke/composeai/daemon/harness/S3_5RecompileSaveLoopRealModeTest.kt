package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * **S3.5 — render-after-recompile (the actual save-loop scenario).**
 *
 * `@Ignore`d by design. Captures the test we *want* to run once
 * [B2.0 — Disposable user classloader](../../../../../../../../docs/daemon/TODO.md)
 * lands. Until that fix exists, the test would fail in a way that *isn't*
 * a useful regression signal — it would fail because the daemon's
 * classloader caches the user module's bytecode for the daemon's
 * lifetime, returning stale results indefinitely after any source edit.
 *
 * ## Why the existing S3 tests don't cover this
 *
 * `S3RenderAfterEditRealModeTest` (desktop, fake) and
 * `S3RenderAfterEditAndroidRealModeTest` (Android, fake) both swap *which
 * preview the spec payload references* between two pre-loaded composables
 * (`RedSquare` → `BlueSquare`). Both classes are already in the daemon's
 * classloader at spawn time; reflection just dispatches to a different
 * one. **Neither test simulates the actual save-loop a developer hits**:
 * edit `Foo.kt`, kotlinc recompiles `com.example.app.RedSquare` to
 * different bytecode, daemon renders `com.example.app.RedSquare` again
 * and produces the *new* colour.
 *
 * ## What the daemon does today
 *
 * Both [DesktopHost][ee.schimke.composeai.daemon.DesktopHost] and
 * [RobolectricHost][ee.schimke.composeai.daemon.RobolectricHost] resolve
 * preview classes via `Class.forName(spec.className, true,
 * <classloader>)`. The classloader caches by name; once a class is
 * loaded, subsequent lookups return the same `Class<?>` regardless of
 * what's on disk. Robolectric's `InstrumentingClassLoader` makes this
 * worse — it instrumenting-rewrites the bytecode at load time and won't
 * re-read the file even if asked. Result: every render after the first
 * sees stale code.
 *
 * ## What B2.0 fixes
 *
 * Splits the daemon's classloader hierarchy:
 *
 * - **Parent** (long-lived): framework + AndroidX + Compose runtime +
 *   kotlinx-* + Roborazzi + the daemon's own helpers. Excludes the user's
 *   `build/intermediates/built_in_kotlinc/<variant>/classes/` directory.
 * - **Child** (disposable): a fresh `URLClassLoader` whose parent is the
 *   long-lived classloader and whose URLs point at the user's compiled-class
 *   directories. Allocated lazily, dropped on `fileChanged({ kind:
 *   "source" })`.
 *
 * After B2.0 lands the test below stops being `@Ignore`d, the
 * "currently broken" assertion flips to "now fixed", and CI starts
 * enforcing that the save-loop produces fresh bytecode every render.
 *
 * ## Implementation outline (for the future un-ignore PR)
 *
 * The hard part is producing two `.class` files with the **same FQN**
 * but **different bytecode** without invoking kotlinc at test runtime.
 * Two reasonable approaches:
 *
 * 1. Two pre-compiled source-set variants. Add `mutableFixturesV1` and
 *    `mutableFixturesV2` source sets to `:renderer-{android,desktop}-daemon`,
 *    each containing one source file `MutableSquare.kt` with class FQN
 *    `ee.schimke.composeai.daemon.MutableSquare` returning red and blue
 *    respectively. Pre-compile both at build time; expose both class
 *    output dirs as test resources. Test driver:
 *
 *    a. Copy v1 `MutableSquare.class` into a temp daemon-classpath dir.
 *    b. Spawn daemon, render `MutableSquare`, assert red.
 *    c. Overwrite the same file with v2's bytes.
 *    d. Send `fileChanged({ path: <classfile>, kind: "source" })`.
 *    e. Re-render `MutableSquare`, assert **blue** (post-B2.0).
 *
 * 2. Runtime ASM swap. Take a single pre-compiled class, use ASM to
 *    rewrite a constant (e.g. the `Color` argument's `Long` value),
 *    write back to disk, signal. Single fixture, no dual-sourceset
 *    Gradle plumbing. Adds ASM as a test dep.
 *
 * Approach 1 is the more honest test (real kotlinc output, real
 * classloader load); approach 2 is faster to implement. The B2.0 PR
 * picks one and removes `@Ignore`.
 *
 * ## What the assertion shape looks like
 *
 * ```kotlin
 * // post-B2.0:
 * val firstPng = renderAndCapture(spec = "MutableSquare", color = "red")
 * assertDominantColor(firstPng, expected = Color.RED)
 *
 * swapBytecodeOnDisk(spec = "MutableSquare", from = v1ClassBytes, to = v2ClassBytes)
 * client.fileChanged(path = mutableSquareClassFile, kind = "source")
 *
 * val secondPng = renderAndCapture(spec = "MutableSquare", color = "blue")
 * assertDominantColor(secondPng, expected = Color.BLUE) // <-- the load-bearing assertion
 * ```
 *
 * The pre-B2.0 daemon would produce `Color.RED` for both renders.
 *
 * ## Cross-target scope
 *
 * Both desktop and Android backends share this bug — the InstrumentingClassLoader
 * makes Android's failure mode harsher (genuinely cannot re-read the file)
 * but desktop's `URLClassLoader` is also class-name-cached. The B2.0
 * implementation lands in both backends; this single test class covers
 * both via the existing `-Ptarget=desktop|android` parameter (mirror
 * the v2 pattern of parallel test classes per target if the assertions
 * diverge).
 */
class S3_5RecompileSaveLoopRealModeTest {

  @Test
  @Ignore("B2.0 — disposable user classloader. See KDoc for full rationale.")
  fun `recompiled bytecode flows through to the next render`() {
    // Implementation lands when B2.0 unblocks. See KDoc for the outline.
    // Today's daemon would fail this test — it returns stale class bytes
    // for the lifetime of the daemon JVM. Removing @Ignore before the
    // fix lands would only produce an unhelpful red CI run.
  }
}
