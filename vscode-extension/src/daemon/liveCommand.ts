// Pure (no DOM) wire-decision helpers for live-mode toggles.
//
// The webview's `LiveStateController` calls these to pick between the
// `composestream/1` `requestStreamStart` / `requestStreamStop` /
// `requestStreamVisibility` messages. Lives in `daemon/` (host
// tsconfig) so the routing rule is testable in plain mocha — the
// controller itself depends on the DOM and lives under
// `webview/preview/`.
//
// Streaming is the only live path; the legacy `setInteractive`
// `<img src=…>` swap was retired once the `composestream/1` painter
// proved out (see PR removing `composePreview.streaming.enabled`).

/** Identity of every webview→extension message a live-mode gesture
 *  can produce. Subset of `WebviewToExtension`; pinning the literal
 *  command names here keeps the wire shape tied to the routing rule. */
export type LiveCommand =
    | { command: "requestStreamStart"; previewId: string }
    | { command: "requestStreamStop"; previewId: string }
    | {
          command: "requestStreamVisibility";
          previewId: string;
          visible: boolean;
          fps?: number;
      };

/**
 * Picks the right enter/exit command for [previewId]. Single source of
 * truth so the LIVE button, the stop-all toolbar, the per-card stop
 * overlay, and the focus-mode stop all dispatch through the same rule.
 */
export function liveToggleCommand(
    previewId: string,
    enabled: boolean,
): LiveCommand {
    return enabled
        ? { command: "requestStreamStart", previewId }
        : { command: "requestStreamStop", previewId };
}

/**
 * Picks the right "card scrolled out / back into viewport" command. The
 * streaming path's `stream/visibility` throttles to keyframes-only when
 * a card scrolls out, so the held session stays warm and scroll-back-
 * into-view repaints from the cached anchor instead of cold-blanking.
 */
export function liveViewportCommand(
    previewId: string,
    visible: boolean,
    fps?: number,
): LiveCommand {
    return {
        command: "requestStreamVisibility",
        previewId,
        visible,
        fps,
    };
}
