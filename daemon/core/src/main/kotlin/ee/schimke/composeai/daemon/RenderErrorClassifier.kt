package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RenderErrorKind

/**
 * Classifies a failed render's [Throwable] into a [RenderErrorKind] plus a one-line remediation
 * (issue #1789), so `renderFailed` carries a typed kind and a specific fix hint instead of the old
 * blanket `kind = "internal"`. The signatures come from the load-bearing skew cases catalogued in
 * `docs/RENDERER_COMPATIBILITY.md` / `docs/SDK_COMPATIBILITY.md` / the cloud-sandbox notes.
 *
 * Pure (matches on the throwable's message + class names down the cause chain) so it is
 * unit-testable and works whether the cause is a real desktop throwable or a re-thrown sandbox
 * error whose message carries the signature text. New fine-grained kinds (e.g. a dedicated
 * `classpathSkew`) wait on the wire-level tolerant-enum work; until then the skew is conveyed
 * through the suggestion, keeping the change additive and decode-safe for old clients.
 */
object RenderErrorClassifier {

  data class Classification(val kind: RenderErrorKind, val suggestion: String? = null)

  fun classify(cause: Throwable): Classification {
    val diagnostic = buildString {
      var t: Throwable? = cause
      var depth = 0
      while (t != null && depth < MAX_CAUSE_DEPTH) {
        append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
        t = t.cause
        depth++
      }
    }
    return classify(diagnostic)
  }

  /** Classify from the pre-flattened cause-chain diagnostic text. Internal for direct testing. */
  internal fun classify(diagnostic: String): Classification {
    val s = diagnostic.lowercase()
    fun has(vararg needles: String): Boolean = needles.any { it in s }

    return when {
      has("implemented only in jetbrains fork") ||
        (has("nosuchmethoderror", "noclassdeffounderror") && has("androidx.compose")) ||
        (has("androidx.compose") && has("jvmstubs")) ->
        Classification(
          RenderErrorKind.RUNTIME,
          "Desktop classpath contains AndroidX Compose UI artifacts; use the org.jetbrains.compose " +
            "UI artifacts for Compose Multiplatform desktop. See docs/RENDERER_COMPATIBILITY.md.",
        )
      // Require the specific "newer sdk version" text — a bare PackageParser error (e.g. a
      // malformed manifest) is a different failure and shouldn't get the compileSdk hint.
      has("requires newer sdk version") || (has("packageparser") && has("newer sdk")) ->
        Classification(
          RenderErrorKind.RUNTIME,
          "Robolectric's SDK is below the consumer's compileSdk; set composePreview.sdkVersion " +
            "(SDK 36 also needs a JDK 21 toolchain). See docs/SDK_COMPATIBILITY.md.",
        )
      has(".robolectric") && has("lock", "download", "read-only", "permission denied") ->
        Classification(
          RenderErrorKind.CAPTURE,
          "Robolectric could not write its host cache/lock; allow \$HOME/.robolectric-download-lock " +
            "in the sandbox network/FS policy. See the compose-preview agent-cloud reference.",
        )
      has("getdeclaredcomposablemethod") ||
        (has("nosuchmethodexception") && has("composer")) ||
        has("is not a @composable") ->
        Classification(
          RenderErrorKind.RUNTIME,
          "The preview must be a @Composable function with no required parameters (or a " +
            "@PreviewParameter provider). Non-composable previews (tile / notification / Glance) " +
            "render through their own kind.",
        )
      has("@previewparameter") || (has("parameter") && has("no value", "missing", "required")) ->
        Classification(
          RenderErrorKind.RUNTIME,
          "The preview function has required parameters; give them defaults or a @PreviewParameter " +
            "provider.",
        )
      has(
        "captureroboimage",
        "pixelcopy",
        "imagereader",
        "hardwarerenderer",
        "nativecreateplanes",
        "roborazzi",
      ) ->
        Classification(
          RenderErrorKind.CAPTURE,
          "The Robolectric capture path failed — usually a Robolectric × compileSdk skew. See " +
            "docs/RENDERER_COMPATIBILITY.md.",
        )
      has("timeoutexception", "timed out", "timeout") ->
        Classification(
          RenderErrorKind.TIMEOUT,
          "The render exceeded its time budget; raise composeai.daemon.renderTimeoutMs or simplify " +
            "the preview (paused-clock animations terminate deterministically).",
        )
      has("compilation error", "unresolved reference", "cannot access class") ->
        Classification(
          RenderErrorKind.COMPILE,
          "The module did not compile; fix the build error before rendering.",
        )
      // Host / sandbox infrastructure failure (e.g. host.submit throwing before the preview body
      // ran — a crashed sandbox's EOF/broken pipe, a closed stdio stream). These aren't user
      // runtime errors, so keep them `internal` rather than falling through to the runtime default.
      has("eofexception") ||
        has("broken pipe") ||
        (has("ioexception") && has("closed", "pipe", "reset", "stream")) ||
        (has("sandbox") && has("crash", "died", "exited", "terminated", "killed")) ->
        Classification(
          RenderErrorKind.INTERNAL,
          "The render host or sandbox failed before/around running the preview (not a fault in the " +
            "preview itself); check the daemon log.",
        )
      // Reached emitRenderFailed without an infra signature → the render ran the preview and threw;
      // runtime is the accurate default (more useful than the old blanket `internal`).
      else -> Classification(RenderErrorKind.RUNTIME)
    }
  }

  private const val MAX_CAUSE_DEPTH = 8
}
