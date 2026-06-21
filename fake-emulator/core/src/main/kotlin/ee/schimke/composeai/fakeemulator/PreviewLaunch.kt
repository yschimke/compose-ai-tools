package ee.schimke.composeai.fakeemulator

/**
 * A request to display one preview, produced by parsing the `am start … PreviewActivity` intent the
 * fake emulator receives over ADB shell. [composableFqn] is the fully-qualified composable name
 * (file class + function, e.g. `com.example.PreviewsKt.MyPreview`) carried in the `composable`
 * intent extra — the exact contract Android Studio's "Deploy Preview to Device" and the VS Code
 * extension emit (`vscode-extension/src/launchOnDevice.ts`).
 */
data class PreviewLaunchRequest(
  val composableFqn: String,
  val parameterProviderClassName: String? = null,
  /**
   * The component the intent targeted, e.g. `app.id/androidx.compose.ui.tooling.PreviewActivity`.
   */
  val component: String? = null,
  /** All `--es` string extras, verbatim. */
  val extras: Map<String, String> = emptyMap(),
)

/** Outcome of a [PreviewLauncher.launch]. */
sealed interface PreviewLaunchResult {
  /** The preview was accepted; its frames should start flowing through the [FrameSource]. */
  data object Launched : PreviewLaunchResult

  /** The launcher could not honour the request (unknown preview, no session, …). */
  data class Rejected(val reason: String) : PreviewLaunchResult
}

/**
 * Sink for preview-launch intents. Implementations route the request into a render backend — the
 * `:fake-emulator` app's implementation opens / switches a `RenderSession` stream so the named
 * preview becomes the emulator display. A no-op default is handy for tests and the bare ADB core.
 */
fun interface PreviewLauncher {
  fun launch(request: PreviewLaunchRequest): PreviewLaunchResult

  companion object {
    /** Accepts every request and does nothing — for tests and console-only bring-up. */
    val NOOP: PreviewLauncher = PreviewLauncher { PreviewLaunchResult.Launched }
  }
}
