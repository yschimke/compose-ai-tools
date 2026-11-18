package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One `:simulator-host` daemon hosting `DeclarativeWatchFaceRuntime.apk`, as a JSON-lines command
 * channel. The commands are the simulator's own (`watchface`, `screenshot`, ...); the launcher adds
 * no vocabulary of its own.
 *
 * **Not thread-safe.** A daemon answers one command at a time and the channel is request/response
 * over stdio, so a caller that shares a connection serializes its own access.
 */
public interface DwfSimulatorConnection : AutoCloseable {
  /** False once the daemon process has exited; a dead connection is replaced, never revived. */
  public val alive: Boolean

  /**
   * Send [cmd] with [payload]'s fields alongside it and wait for the daemon's answer.
   *
   * @return the response's `result`, or null when it carries none.
   * @throws IllegalStateException when the daemon answers `ok: false` (the message names the
   *   command and carries the daemon's `error`) or closes its stdout.
   */
  public fun command(cmd: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement?
}

/**
 * Starts the runtime daemon for callers that hold their own watch face rather than a served bundle,
 * which [DwfApkSessionBridge] cannot take: it is keyed on a `ServeHost` and a `ServePreview`. The
 * environment lookup (runtime APK, `lib-simulator` / `lib-daemon-android` sidecars, `android.jar`)
 * and the one-time runtime conversion are the same ones the bridge uses, so both share the cache.
 *
 * Packaging is not here: build the face's APK with `ee.schimke.watchface:wff-packager` and pass it
 * to [connect].
 */
public object DwfSimulatorLauncher {
  /**
   * Why a daemon cannot start on this host, or null when it can. `aapt2` is not required: this
   * starts a daemon, it does not package.
   */
  public fun unavailableReason(): String? = DwfApkDaemonBridge.resolveEnvironment(false).second

  /**
   * Start a daemon that boots with [initialApk] (a signed WFF APK) and wait until it answers
   * `watchface status`. Each call starts a new process; the caller owns it and closes it.
   *
   * @throws IllegalStateException when [unavailableReason] is non-null, the runtime cannot be
   *   converted, or the process does not come up.
   */
  public fun connect(initialApk: File): DwfSimulatorConnection {
    val (env, reason) = DwfApkDaemonBridge.resolveEnvironment(requireAapt2 = false)
    checkNotNull(env) { reason ?: "DWF simulator unavailable" }
    val classes = DwfApkDaemonBridge.ensureRuntimeClasses(env)
    return DwfApkDaemonBridge.SimulatorDaemonProcess.start(env, classes, initialApk)
  }
}
