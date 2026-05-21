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

import type { PreviewOverrides } from "./daemonProtocol";

/** Identity of every webview→extension message a live-mode gesture
 *  can produce. Subset of `WebviewToExtension`; pinning the literal
 *  command names here keeps the wire shape tied to the routing rule. */
export type LiveCommand =
    | {
          command: "requestStreamStart";
          previewId: string;
          /**
           * Per-session preview overrides — see `PreviewOverrides`. Carries the per-card
           * `touchOverlay` and `keyboard` toggle state at the moment live mode starts so
           * the daemon's session is set up with the right `AroundComposable` chain. The
           * field is omitted when no toggle is on, keeping the wire payload byte-identical
           * to the pre-toggle shape for the common case.
           */
          overrides?: PreviewOverrides;
      }
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
 *
 * [overrides] is forwarded on the start command only — `stop` ignores it.
 * Pass `undefined` (or omit) when no per-card toggle is active so the
 * payload stays at the legacy 2-field shape.
 */
export function liveToggleCommand(
    previewId: string,
    enabled: boolean,
    overrides?: PreviewOverrides,
): LiveCommand {
    if (!enabled) return { command: "requestStreamStop", previewId };
    return overrides && hasAnyOverride(overrides)
        ? { command: "requestStreamStart", previewId, overrides }
        : { command: "requestStreamStart", previewId };
}

/**
 * `true` when [overrides] has at least one defined field. Lets [liveToggleCommand] drop the
 * `overrides` field from the wire payload entirely when every toggle is off — the daemon's
 * `interactive/start` then proceeds on the un-overridden spec, same as before.
 */
function hasAnyOverride(overrides: PreviewOverrides): boolean {
    for (const key of Object.keys(overrides) as (keyof PreviewOverrides)[]) {
        if (overrides[key] !== undefined) return true;
    }
    return false;
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
