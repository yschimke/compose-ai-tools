@file:JvmName("DaemonMain")

package ee.schimke.composeai.daemon

/**
 * Placeholder entry point for the desktop preview daemon JVM — see docs/daemon/DESIGN.md § 4
 * ("Renderer-agnostic surface").
 *
 * **Status: skeleton only (B-desktop.1.1).** This `main` currently just prints a hello banner and
 * exits. The full lifecycle lands in subsequent Stream B-desktop tasks:
 *
 * 1. B-desktop.1.3 introduces `DesktopHost` — the desktop-side [RenderHost] implementation that
 *    holds a long-lived `Recomposer` + Skiko `Surface` warm across renders, mirroring the role
 *    `RobolectricHost` plays in `:renderer-android-daemon`.
 * 2. B-desktop.1.4 duplicates the desktop renderer's per-preview body into a `RenderEngine` here,
 *    invoked by `DesktopHost`.
 * 3. B-desktop.1.5 wires this `main` to the existing `JsonRpcServer` from `:renderer-daemon-core`,
 *    bound to a `DesktopHost`. After that point this file's body becomes structurally identical to
 *    `:renderer-android-daemon`'s `DaemonMain.kt`, with only the concrete `RenderHost` differing —
 *    the renderer-agnostic-surface payoff.
 *
 * The file-level [JvmName] mirrors the convention `:renderer-android-daemon`'s `DaemonMain.kt`
 * adopted in B1.5 so the daemon launch descriptor's `mainClass = "ee.schimke.composeai.daemon
 * .DaemonMain"` resolves cleanly without a `Kt` suffix when the Gradle plugin learns about this
 * target.
 */
fun main(args: Array<String>) {
  println("compose-ai-tools desktop daemon: hello")
}
