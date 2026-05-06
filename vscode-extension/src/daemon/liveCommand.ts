// Pure (no DOM) wire-decision helpers for live-mode toggles.
//
// The webview's `LiveStateController` calls these to pick between the
// legacy `setInteractive` and the new `composestream/1`
// `requestStreamStart` / `requestStreamStop` /
// `requestStreamVisibility` messages. Lives in `daemon/` (host
// tsconfig) so the routing rule is testable in plain mocha — the
// controller itself depends on the DOM and lives under
// `webview/preview/`.

/** Identity of every webview→extension message a live-mode gesture
 *  can produce. Subset of `WebviewToExtension`; pinning the literal
 *  command names here keeps the wire shape tied to the routing rule. */
export type LiveCommand =
    | { command: "setInteractive"; previewId: string; enabled: boolean }
    | { command: "requestStreamStart"; previewId: string }
    | { command: "requestStreamStop"; previewId: string }
    | {
          command: "requestStreamVisibility";
          previewId: string;
          visible: boolean;
          fps?: number;
      };

/**
 * Picks the right enter/exit command for [previewId] given the current
 * value of `composePreview.streaming.enabled`. Single source of truth so
 * the LIVE button, the stop-all toolbar, the per-card stop overlay, and
 * the focus-mode stop all dispatch through the same rule.
 */
export function liveToggleCommand(
    previewId: string,
    enabled: boolean,
    streamingEnabled: boolean,
): LiveCommand {
    if (streamingEnabled) {
        return enabled
            ? { command: "requestStreamStart", previewId }
            : { command: "requestStreamStop", previewId };
    }
    return { command: "setInteractive", previewId, enabled };
}

/**
 * Picks the right "card scrolled out / back into viewport" command. The
 * streaming path has a softer "throttle to keyframes-only" affordance via
 * `stream/visibility`; the legacy path has only the hard
 * `setInteractive(false)` stop. Returning a [LiveCommand] keeps the call
 * site uniform.
 */
export function liveViewportCommand(
    previewId: string,
    visible: boolean,
    streamingEnabled: boolean,
    fps?: number,
): LiveCommand {
    if (streamingEnabled) {
        return {
            command: "requestStreamVisibility",
            previewId,
            visible,
            fps,
        };
    }
    return {
        command: "setInteractive",
        previewId,
        enabled: visible,
    };
}
