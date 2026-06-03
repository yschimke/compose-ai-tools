// Live (interactive) + recording state controller for the preview panel.
//
// Lifted verbatim from `behavior.ts`'s `applyLiveBadge` / `ensureLiveCardControls`
// / `stopInteractiveForCard` / `toggleInteractive` /
// `setInteractiveForCard` / `enterInteractiveOnCard` / `toggleRecording`
// cluster, plus the lone-stream follow-focus teardown that used to live inline
// in `applyLayout` and the ad-hoc Set manipulations spread across the
// `setInteractiveAvailability` / `clearInteractive` / `clearRecording` /
// `setEarlyFeatures` / `setPreviews` message handlers.
//
// Owns two Sets:
//  - `interactivePreviewIds` — cards currently in v2 live (interactive) mode.
//  - `recordingPreviewIds` — cards currently capturing a recording.
//
// Both sets forward pointer / wheel input to the daemon (predicate exposed via
// `isLive` / `isRecording`); `attachInteractiveInputHandlers` from
// `./interactiveInput.ts` consults them through `interactiveInputConfig.isLive`.
//
// Also owns two per-module availability Maps populated from the
// `setInteractiveAvailability` wire message:
//  - `moduleDaemonReady` — whether the module's daemon is up and ready.
//  - `moduleInteractiveSupported` — whether the daemon advertises full v2
//    live mode (vs the Android/v1 fallback where renders refresh but pointer
//    input doesn't mutate held composition state).
// Writes flow through `setAvailability(moduleId, ready, interactiveSupported)`;
// reads are exposed as ReadonlyMaps so callers (focus toolbar predicates) keep
// their existing signatures.
//
// Plain click on the LIVE button is single-target — drop every prior stream
// before adding (or re-removing) this one. Shift+click is multi-target — toggle
// just this preview without disturbing the others. Recording is currently
// single-target only (Shift modifier intentionally not wired through).
//
// The controller posts the `requestStreamStart` / `requestStreamStop` /
// `setRecording` wire commands and then re-runs the supplied button-state
// hooks so the toolbar reflects the new truth synchronously. Silent
// variants (`handleDaemonLost`,
// `handleExtensionClearInteractive`, `handleExtensionClearRecording`,
// `pruneLive`) drop UI bookkeeping without posting back — those paths are
// triggered after the extension or daemon has already torn the streams down,
// and re-posting would race the flush.

import type {
    LauncherWidgetSize,
    PreviewOverrides,
} from "../../daemon/daemonProtocol";
import { liveToggleCommand } from "../../daemon/liveCommand";
import { kindSupportsLiveMode } from "./cardData";
import { planFollowFocusTeardown } from "./followFocus";
import { attachInteractiveInputHandlers } from "./interactiveInput";
import { previewStore } from "./previewStore";
import type { InteractiveInputConfig } from "./interactiveInput";
import { stampLiveBadgesOnGrid } from "./liveBadge";
import { ensureLiveCardControls } from "./liveCardControls";
import { planLiveToggle, planRecordingToggle } from "./liveTransitions";
import { throttleLiveOnViewportLeave } from "./liveViewportThrottle";
import type { VsCodeApi } from "../shared/vscode";

/**
 * `DataExtensionDescriptor.id` of the touch-overlay extension (`TouchOverlayExtension.ID` on the
 * Kotlin side — see `:data-touch-overlay-connector`). When the daemon advertises this id in
 * `initialize.capabilities.dataExtensions` (PR #1312 desktop / #1313 Android), the per-card
 * touch-overlay toggle button appears. Pinned here so the value stays in lockstep with the
 * daemon constant; a value drift would silently hide the button forever.
 */
const TOUCH_OVERLAY_EXTENSION_ID = "touch-overlay";

/**
 * `DataExtensionDescriptor.id` of the soft-keyboard band extension (`KeyboardOverrideExtension.ID`
 * on the Kotlin side — equal to `Material3KeyboardProduct.KIND`). Same gating story as
 * [TOUCH_OVERLAY_EXTENSION_ID].
 */
const KEYBOARD_BAND_EXTENSION_ID = "compose/keyboard";

/**
 * `DataExtensionDescriptor.id` of the launcher-widget container-size extension
 * (`LauncherWidgetExtension.ID` on the Kotlin side — see
 * `:data-launcher-widget-connector`). Pinned here so the value stays in lockstep
 * with the daemon constant; a value drift would silently hide the picker
 * button forever. Same gating story as [TOUCH_OVERLAY_EXTENSION_ID].
 */
const LAUNCHER_WIDGET_EXTENSION_ID = "compose/launcher-widget";

export interface LiveStateConfig {
    vscode: VsCodeApi<unknown>;
    /** Selected recording format (`mp4` / `webm`). Read fresh on every
     *  recording action so format-dropdown changes take effect on the
     *  next gesture. */
    recordingFormat: HTMLSelectElement;
    /** Predicate config handed through to `attachInteractiveInputHandlers`
     *  for the per-card pointer/wheel listeners. The `isLive` it carries
     *  is expected to consult this controller (`isLive(id) || isRecording(id)`). */
    interactiveInputConfig: InteractiveInputConfig;
    /** Whether `composePreview.earlyFeatures` is on — recording is gated on it. */
    earlyFeatures(): boolean;
    /** Whether the panel is currently in focus layout. The Live / Recording
     *  toolbar buttons only act when one card is focused. */
    inFocus(): boolean;
    /** The currently-focused preview card, or null when none is focused. */
    focusedCard(): HTMLElement | null;
    /** Re-run focus-toolbar button-state hooks after a state change. */
    applyInteractiveButtonState(): void;
    applyRecordingButtonState(): void;
    /**
     * Re-stamp the focus-bar touch-overlay / keyboard-band / controls toggle
     * buttons against the focused preview's current state. Wired from
     * `main.ts` to `FocusController.applyFocusedToggleButtonStates`. Optional
     * so legacy tests that construct `LiveStateController` without a focus
     * controller stay green — the dispatch becomes a silent no-op.
     */
    applyFocusedToggleButtonStates?(): void;
    /** Re-render the focus inspector for [card] — the inspector reads
     *  `isLive` / `isRecording` to keep its Tools strip in sync. */
    renderInspector(card: HTMLElement | null): void;
    /**
     * Notified after the launcher-widget cell-size override for [previewId]
     * has changed — `cells` is the new value, or `null` when the user reset
     * the override. Wired by main.ts to persist the choice into
     * `PersistedState.launcherWidgetOverrides` via `vscode.setState`, so a
     * panel reload restores the override on the next boot. Not fired from
     * [hydrateLauncherWidgetOverride] — that path is the persistence
     * replay itself and would self-trigger an extra `setState` round-trip.
     */
    onLauncherWidgetCellsChanged?(
        previewId: string,
        cells: LauncherWidgetSize | null,
    ): void;
}

export class LiveStateController {
    // Mutable references — the planner-driven mutators in
    // `setInteractiveForCard` / `toggleRecording` replace these with
    // fresh Sets returned by the planners, while the local-mutation
    // methods (`stopInteractiveForCard`, the silent extension-clear paths,
    // etc.) call `.clear()` / `.delete()` directly.
    private interactivePreviewIds: Set<string> = new Set<string>();
    private recordingPreviewIds: Set<string> = new Set<string>();
    // Issue #1203 — per-preview "controls" toggle state. A card with controls
    // enabled is in interactive mode AND has the keyboard listener attached;
    // toggling controls off detaches the listener but leaves live mode alone.
    // Cleared when the card stops live mode (the listener is unreachable
    // without an active session anyway, and re-entering live should not silently
    // re-attach keyboard interception).
    private controlsEnabledPreviewIds: Set<string> = new Set<string>();
    // Per-preview state for the touch-overlay + keyboard-band toggles. Read by
    // `overridesForPreview()` when building the `requestStreamStart` payload so
    // the daemon's session installs the matching `AroundComposable`. Toggling on
    // a live preview restarts the stream to pick up the new override; toggling
    // when not live just remembers the choice for the next `requestStreamStart`.
    // Sticky across live mode tear-down so a user who flips touch overlay on,
    // stops live, and starts again gets the same overlay back.
    private touchOverlayEnabledPreviewIds: Set<string> = new Set<string>();
    private keyboardBandForcedPreviewIds: Set<string> = new Set<string>();
    // Per-preview launcher-widget cell-size override. Read by `overridesForPreview`
    // when building the `requestStreamStart` payload so the daemon's session
    // installs a `LauncherWidgetExtension` planning to the chosen cells. The
    // picker popover writes via `setLauncherWidgetCellsForCard`; clearing the
    // entry (null) drops the override on the next live restart. Sticky across
    // live mode tear-down — same shape as the touch-overlay / keyboard-band
    // toggles above.
    private launcherWidgetCellsByPreviewId: Map<string, LauncherWidgetSize> =
        new Map<string, LauncherWidgetSize>();

    // Per-module availability — written from `setInteractiveAvailability`
    // via `setAvailability`, read by the focus toolbar predicates through
    // the `getModuleDaemonReady` / `getModuleInteractiveSupported`
    // ReadonlyMap accessors. See module-header doc.
    private readonly moduleDaemonReady = new Map<string, boolean>();
    private readonly moduleInteractiveSupported = new Map<string, boolean>();
    // Issue #1203 — per-module set of interactive input kinds (beyond pointer)
    // the daemon dispatches. Written from `setDaemonCapabilities`; read by
    // `supportsInteractiveControl` to decide whether to attach the live-card
    // keyboard listener / show a rotary affordance.
    private readonly moduleInteractiveControlKinds = new Map<
        string,
        ReadonlySet<string>
    >();
    // Issue #1203 — per-module set of data-extension ids whose descriptor
    // carries `requiresInteractive = true`. The panel auto-enters live mode
    // for a preview when the user toggles on any such extension instead of
    // failing silently — keyboard / rotary dispatch can't land outside a held
    // composition. Written from `setDaemonCapabilities`; queried via
    // `extensionRequiresInteractive(id)` and acted on by
    // `enterLiveModeForExtension`.
    private readonly moduleInteractiveOnlyExtensions = new Map<
        string,
        ReadonlySet<string>
    >();
    // Full set of data-extension ids the daemon advertises per module — every
    // `DataExtensionDescriptor` in `dataExtensions`, not just the `requiresInteractive=true`
    // subset. Drives the focus-bar gating for the touch-overlay / keyboard-band toggle
    // buttons (added in #1308 — those toggles are PreviewOverride-driven, not
    // interactive-only, so they need a separate capability check than the #1203 controls
    // button). Written from `setDaemonCapabilities`; queried via
    // `anyModuleAdvertisesExtension(id)`.
    private readonly moduleAdvertisedExtensions = new Map<
        string,
        ReadonlySet<string>
    >();

    constructor(private readonly cfg: LiveStateConfig) {}

    /** Record per-module daemon readiness + interactive-support flags. The
     *  callers (`handleSetInteractiveAvailability`) coerce the wire payload
     *  to booleans before calling. */
    setAvailability(
        moduleId: string,
        ready: boolean,
        interactiveSupported: boolean,
    ): void {
        this.moduleDaemonReady.set(moduleId, ready);
        this.moduleInteractiveSupported.set(moduleId, interactiveSupported);
    }

    /** Read view of the per-module daemon-readiness map. Exposed as a
     *  ReadonlyMap so the existing `isFocusedModuleReady` /
     *  `isFocusedInteractiveSupported` predicates in `./moduleReadiness.ts`
     *  consume it without signature changes. */
    getModuleDaemonReady(): ReadonlyMap<string, boolean> {
        return this.moduleDaemonReady;
    }

    /** Read view of the per-module interactive-supported map. See
     *  `getModuleDaemonReady`. */
    getModuleInteractiveSupported(): ReadonlyMap<string, boolean> {
        return this.moduleInteractiveSupported;
    }

    /**
     * Issue #1203 — record the interactive input kinds [moduleId]'s daemon advertises.
     * Forwarded from `setDaemonCapabilities`. Empty / undefined kinds clear the entry so
     * a daemon restart that drops the capability flips the panel back to pointer-only.
     */
    setInteractiveControlKinds(
        moduleId: string,
        kinds: readonly string[] | undefined,
    ): void {
        if (!kinds || kinds.length === 0) {
            this.moduleInteractiveControlKinds.delete(moduleId);
        } else {
            this.moduleInteractiveControlKinds.set(moduleId, new Set(kinds));
        }
    }

    /**
     * Issue #1203 — true when any module the panel knows about advertises [kind] as an
     * interactive control. The panel is single-module-scoped today (see `moduleReadiness`),
     * so "any module supports it" reduces to "the active module supports it"; if the panel
     * ever shows multiple modules, swap this to look up by the focused card's
     * `data-module-id`.
     */
    supportsInteractiveControl(kind: string): boolean {
        for (const kinds of this.moduleInteractiveControlKinds.values()) {
            if (kinds.has(kind)) return true;
        }
        return false;
    }

    /**
     * Issue #1203 — record which data-extension ids carry
     * `DataExtensionDescriptor.requiresInteractive = true` for [moduleId]. Forwarded from
     * `setDaemonCapabilities`. Empty / undefined clears the entry; a daemon restart that
     * drops the flag flips the panel back to "no auto-enter" behaviour for those extensions.
     */
    setInteractiveOnlyExtensions(
        moduleId: string,
        extensionIds: readonly string[] | undefined,
    ): void {
        if (!extensionIds || extensionIds.length === 0) {
            this.moduleInteractiveOnlyExtensions.delete(moduleId);
        } else {
            this.moduleInteractiveOnlyExtensions.set(
                moduleId,
                new Set(extensionIds),
            );
        }
    }

    /**
     * Issue #1203 — true when [extensionId] is one of the daemon-advertised
     * interactive-only extensions (descriptor `requiresInteractive = true`). The panel is
     * single-module-scoped today, so we union across modules; revisit when the panel grows
     * multi-module support.
     */
    extensionRequiresInteractive(extensionId: string): boolean {
        for (const ids of this.moduleInteractiveOnlyExtensions.values()) {
            if (ids.has(extensionId)) return true;
        }
        return false;
    }

    /**
     * Record the full set of data-extension ids the daemon advertises for [moduleId] —
     * unfiltered, the union of every `DataExtensionDescriptor` in `dataExtensions`. Drives the
     * focus-bar touch-overlay / keyboard-band toggle visibility via
     * [anyModuleAdvertisesExtension]. Forwarded from `setDaemonCapabilities`; empty / undefined
     * clears the entry.
     */
    setAdvertisedExtensions(
        moduleId: string,
        extensionIds: readonly string[] | undefined,
    ): void {
        if (!extensionIds || extensionIds.length === 0) {
            this.moduleAdvertisedExtensions.delete(moduleId);
        } else {
            this.moduleAdvertisedExtensions.set(
                moduleId,
                new Set(extensionIds),
            );
        }
    }

    /**
     * True when any module's daemon advertises [extensionId] in its `dataExtensions` capability
     * snapshot. Used by the focus-bar gating for the touch-overlay (`touch-overlay`) and
     * keyboard-band (`compose/keyboard`) toggle buttons — buttons appear only on backends
     * that actually ship the matching planner, so a daemon without the extension doesn't
     * grow dead UI. Pre-#1312 daemons (no descriptors at all) leave both buttons hidden
     * until the user upgrades.
     */
    anyModuleAdvertisesExtension(extensionId: string): boolean {
        for (const ids of this.moduleAdvertisedExtensions.values()) {
            if (ids.has(extensionId)) return true;
        }
        return false;
    }

    /** Daemon advertises the touch-overlay extension on any known module. */
    isTouchOverlayAdvertised(): boolean {
        return this.anyModuleAdvertisesExtension(TOUCH_OVERLAY_EXTENSION_ID);
    }

    /** Daemon advertises the soft-keyboard band extension on any known module. */
    isKeyboardBandAdvertised(): boolean {
        return this.anyModuleAdvertisesExtension(KEYBOARD_BAND_EXTENSION_ID);
    }

    /**
     * Issue #1203 — any module advertises an interactive-only data extension,
     * which is the gate for the per-preview "Controls" toggle (keyboard input
     * dispatch).
     */
    isControlsAdvertised(): boolean {
        return this.moduleInteractiveOnlyExtensions.size > 0;
    }

    /** Current per-preview touch-overlay toggle state. */
    isTouchOverlayEnabled(previewId: string): boolean {
        return this.touchOverlayEnabledPreviewIds.has(previewId);
    }

    /** Current per-preview soft-keyboard band override state. */
    isKeyboardBandForced(previewId: string): boolean {
        return this.keyboardBandForcedPreviewIds.has(previewId);
    }

    /**
     * Issue #1203 — entry point for a panel toggle that enables an interactive-only
     * data extension on [card]. Idempotent: if the card is already live, this is a no-op.
     * Otherwise we drive the same live-mode-on path the per-card stop button reverses —
     * `setInteractiveForCard` with `shift = true` so a second card already in live mode
     * stays live (the user's toggle didn't ask to take its slot).
     */
    enterLiveModeForExtension(card: HTMLElement): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        if (this.interactivePreviewIds.has(previewId)) return;
        this.setInteractiveForCard(card, true);
    }

    /**
     * Issue #1203 — true when the user has enabled the per-card "controls" toggle for
     * [previewId]. Consumed by the keyboard listener's `supportsControl` gate (via
     * `interactiveInputConfig.isControlsEnabled`) so a card without the toggle on stays
     * pointer-only even when the daemon advertises keyboard dispatch.
     */
    isControlsEnabled(previewId: string): boolean {
        return this.controlsEnabledPreviewIds.has(previewId);
    }

    /**
     * Issue #1203 — per-card "Controls" button click. Toggling on auto-enters live
     * mode (interactive dispatch only lands against a held composition) and attaches
     * the keyboard listener; toggling off detaches the listener but leaves live mode
     * intact — the user may still want to click / drag the preview.
     *
     * Idempotent: re-enabling a card that's already on (e.g. via a duplicate event) is
     * a no-op; disabling a card that's already off is a no-op.
     */
    toggleControlsForCard(card: HTMLElement, enabled: boolean): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        if (enabled) {
            if (this.controlsEnabledPreviewIds.has(previewId)) return;
            this.controlsEnabledPreviewIds.add(previewId);
            this.enterLiveModeForExtension(card);
            attachInteractiveInputHandlers(
                card,
                this.cfg.interactiveInputConfig,
            );
        } else {
            if (!this.controlsEnabledPreviewIds.has(previewId)) return;
            this.controlsEnabledPreviewIds.delete(previewId);
        }
        this.applyControlsToggleButtons();
    }

    /**
     * Re-stamp the focus-bar mirrors for the touch-overlay / keyboard-band /
     * #1203 controls toggles after a daemon-capability or per-card state
     * change. The focus-controls bar is the only home for these toggles — in
     * grid / flow / column layouts they're intentionally not surfaced (no
     * focused preview, nothing to act on).
     */
    applyControlsToggleButtons(): void {
        this.cfg.applyFocusedToggleButtonStates?.();
    }

    /**
     * Per-card touch-overlay toggle. Flipping on while the card is live restarts the
     * stream so the daemon picks up `overrides.touchOverlay = true` for the new
     * session; flipping off similarly restarts to drop the override. The toggle is
     * sticky — when not live, the state is remembered for the next `requestStreamStart`.
     */
    toggleTouchOverlayForCard(card: HTMLElement, enabled: boolean): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        const was = this.touchOverlayEnabledPreviewIds.has(previewId);
        if (was === enabled) return;
        if (enabled) this.touchOverlayEnabledPreviewIds.add(previewId);
        else this.touchOverlayEnabledPreviewIds.delete(previewId);
        this.restartLiveIfActive(previewId);
        this.applyControlsToggleButtons();
    }

    /** Daemon advertises the launcher-widget container-size extension on any known module. */
    isLauncherWidgetAdvertised(): boolean {
        return this.anyModuleAdvertisesExtension(LAUNCHER_WIDGET_EXTENSION_ID);
    }

    /**
     * Current per-preview launcher-widget cell-size override, or `null` when
     * no override is set. Read by the focus toolbar's button-state hook to
     * decide whether the picker button shows its "modified" pressed state,
     * and by the picker popover to highlight the active rectangle.
     */
    launcherWidgetCellsForPreview(
        previewId: string,
    ): LauncherWidgetSize | null {
        return this.launcherWidgetCellsByPreviewId.get(previewId) ?? null;
    }

    /**
     * Picker-popover entry point — store the chosen [cells] (or clear, when
     * passed `null`) and restart the live stream so the daemon picks up
     * `overrides.launcherWidget = { cells }` for the new session. Sticky when
     * not live: the choice is remembered for the next `requestStreamStart`.
     */
    setLauncherWidgetCellsForCard(
        card: HTMLElement,
        cells: LauncherWidgetSize | null,
    ): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        const was = this.launcherWidgetCellsByPreviewId.get(previewId) ?? null;
        if (sameCells(was, cells)) return;
        if (cells === null) {
            this.launcherWidgetCellsByPreviewId.delete(previewId);
        } else {
            this.launcherWidgetCellsByPreviewId.set(previewId, cells);
        }
        this.restartLiveIfActive(previewId);
        this.applyControlsToggleButtons();
        this.cfg.onLauncherWidgetCellsChanged?.(previewId, cells);
    }

    /**
     * Replay a launcher-widget override persisted across a panel reload. Seeds
     * the in-memory map without firing [onLauncherWidgetCellsChanged] (so the
     * boot hydration doesn't bounce straight back into `setState`), and
     * without restarting any stream — at boot no card is live yet anyway, so
     * the picker button will pick the value up via
     * [launcherWidgetCellsForPreview] when the user focuses the card.
     */
    hydrateLauncherWidgetOverride(
        previewId: string,
        cells: LauncherWidgetSize,
    ): void {
        this.launcherWidgetCellsByPreviewId.set(previewId, cells);
    }

    /** Symmetric to {@link toggleTouchOverlayForCard} for the soft-keyboard band. */
    toggleKeyboardBandForCard(card: HTMLElement, enabled: boolean): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        const was = this.keyboardBandForcedPreviewIds.has(previewId);
        if (was === enabled) return;
        if (enabled) this.keyboardBandForcedPreviewIds.add(previewId);
        else this.keyboardBandForcedPreviewIds.delete(previewId);
        this.restartLiveIfActive(previewId);
        this.applyControlsToggleButtons();
    }

    /**
     * Current per-card override payload — `undefined` when no toggle is on, so the
     * existing `requestStreamStart` wire shape stays unchanged for the common case.
     * Consumed by {@link postLiveCommand} when entering live mode.
     */
    overridesForPreview(previewId: string): PreviewOverrides | undefined {
        const touch = this.touchOverlayEnabledPreviewIds.has(previewId);
        const keyboardOn = this.keyboardBandForcedPreviewIds.has(previewId);
        const launcherCells =
            this.launcherWidgetCellsByPreviewId.get(previewId);
        if (!touch && !keyboardOn && !launcherCells) return undefined;
        const overrides: PreviewOverrides = {};
        if (touch) overrides.touchOverlay = true;
        if (keyboardOn) overrides.keyboard = { visible: true };
        if (launcherCells) overrides.launcherWidget = { cells: launcherCells };
        return overrides;
    }

    /**
     * Toggle restart helper: stop + immediately re-start live mode for [previewId] so
     * the daemon session is rebuilt with the current `overridesForPreview` payload.
     * No-op when the card isn't currently live; the new state is picked up on the
     * next manual live start. Posts the two commands as separate notifications so the
     * extension-side handler can run its existing teardown sequence unmodified.
     */
    private restartLiveIfActive(previewId: string): void {
        if (!this.interactivePreviewIds.has(previewId)) return;
        this.postLiveCommand(previewId, false);
        this.postLiveCommand(previewId, true);
    }

    /**
     * Posts the live-mode wire command for [previewId] — routes through
     * [liveToggleCommand] which always picks `requestStreamStart` /
     * `requestStreamStop` now that streaming is the only live path.
     * Single choke point so every per-card / toolbar / focus-mode entry
     * point shares one rule.
     */
    private postLiveCommand(previewId: string, enabled: boolean): void {
        // Only the start command carries overrides; stop ignores them by contract.
        const overrides = enabled
            ? this.overridesForPreview(previewId)
            : undefined;
        this.cfg.vscode.postMessage(
            liveToggleCommand(previewId, enabled, overrides),
        );
    }

    isLive(previewId: string): boolean {
        return this.interactivePreviewIds.has(previewId);
    }

    isRecording(previewId: string): boolean {
        return this.recordingPreviewIds.has(previewId);
    }

    get liveCount(): number {
        return this.interactivePreviewIds.size;
    }

    get recordingCount(): number {
        return this.recordingPreviewIds.size;
    }

    /** Re-stamp every `.preview-card.live` decoration from the current set.
     *  Tear-down first so removals (Shift+click off, daemon-not-ready,
     *  setPreviews dropping a previewId) cleanly wipe the prior decoration.
     *
     *  The DOM mutation lives in `./liveBadge.ts` so it's testable under
     *  happy-dom without dragging this controller's wider transitive
     *  imports into the host tsconfig; we delegate, passing
     *  `ensureLiveCardControls` as the per-card overlay hook. */
    applyLiveBadge(): void {
        stampLiveBadgesOnGrid(this.interactivePreviewIds, (card) =>
            this.ensureLiveCardControls(card),
        );
    }

    /** Per-card stop button (the codicon overlay) — stop only [card]. */
    stopInteractiveForCard(card: HTMLElement): void {
        const previewId = card.dataset.previewId;
        if (!previewId || !this.interactivePreviewIds.has(previewId)) return;
        this.interactivePreviewIds.delete(previewId);
        // Issue #1203 — keyboard listener is gated on isControlsEnabled, but
        // leaving the flag set across a live-mode bounce would silently
        // re-attach interception the next time the user enters live mode for
        // unrelated reasons. Clear it so the toggle stays explicit.
        this.controlsEnabledPreviewIds.delete(previewId);
        this.postLiveCommand(previewId, false);
        this.applyLiveBadge();
        this.applyControlsToggleButtons();
        this.cfg.applyInteractiveButtonState();
    }

    /** Focus-mode LIVE button — operates on the currently focused card. */
    toggleInteractive(shift: boolean): void {
        if (!this.cfg.inFocus()) return;
        const card = this.cfg.focusedCard();
        if (!card) return;
        this.setInteractiveForCard(card, shift);
    }

    /**
     * Toggle interactive mode for [card] honouring plain/Shift semantics:
     *  - Plain: single-target. Drop every prior live target before adding (or
     *    re-removing) this one — keeps the casual UX matching v1's "one card
     *    live at a time" mental model.
     *  - Shift: multi-target. Toggle just this preview in/out of the live set
     *    without touching the others.
     */
    setInteractiveForCard(card: HTMLElement, shift: boolean): void {
        const previewId = card.dataset.previewId;
        if (!previewId) return;
        // Kind gate — refuse to ENTER live mode for non-interactive surfaces
        // (notification / Glance): their rendered output is an inflated View
        // with no Compose tree to stream or drive. The toolbar already disables
        // the button, but this also blocks the in-card click and any
        // auto-enter path (e.g. an interactive-only data extension). Toggling a
        // card that's somehow already live back OFF stays allowed so a stream
        // can never be stranded.
        if (!this.interactivePreviewIds.has(previewId)) {
            const preview = previewStore
                .getState()
                .allPreviews.find((p) => p.id === previewId);
            if (!kindSupportsLiveMode(preview)) return;
        }
        const plan = planLiveToggle(
            this.interactivePreviewIds,
            previewId,
            shift,
        );
        for (const prior of plan.deactivate) {
            // Issue #1203 — clear the Controls flag for any card we're
            // dropping out of live. Without this, a card whose Controls
            // toggle was on (and is now bumped out by a plain LIVE click
            // elsewhere) would silently re-attach keyboard interception
            // the next time it re-enters live mode.
            this.controlsEnabledPreviewIds.delete(prior);
            this.postLiveCommand(prior, false);
        }
        this.interactivePreviewIds = plan.next;
        if (!plan.turnOnTarget) {
            // Issue #1203 — the target itself is being toggled OFF (it's never
            // in `plan.deactivate`, which only carries the *other* priors). Clear
            // its Controls flag too, matching the loop above and
            // `stopInteractiveForCard`, so a later live re-entry doesn't silently
            // re-attach keyboard interception after an explicit stop.
            this.controlsEnabledPreviewIds.delete(previewId);
        }
        if (plan.turnOnTarget) {
            attachInteractiveInputHandlers(
                card,
                this.cfg.interactiveInputConfig,
            );
        }
        this.postLiveCommand(previewId, plan.turnOnTarget);
        this.applyLiveBadge();
        this.applyControlsToggleButtons();
        this.cfg.applyInteractiveButtonState();
        // The focus inspector is a focus-mode-only panel — refreshing it from a
        // grid-layout click would un-hide the Inspect surface (data extensions,
        // legends, reports) on top of the grid. Only repaint when the inspector
        // is actually showing.
        if (this.cfg.inFocus()) {
            this.cfg.renderInspector(card);
        }
    }

    /** Single-click-to-LIVE entry point from the in-card image click handler.
     *  Same effect as `setInteractiveForCard` — alias kept so the call site
     *  documents intent. */
    enterInteractiveOnCard(card: HTMLElement, shift: boolean): void {
        this.setInteractiveForCard(card, shift);
    }

    /** Focus-mode REC button — operates on the currently focused card.
     *  Recording is currently single-target; no Shift modifier. */
    toggleRecording(): void {
        if (!this.cfg.earlyFeatures()) return;
        if (!this.cfg.inFocus()) return;
        const card = this.cfg.focusedCard();
        const previewId = card ? card.dataset.previewId : null;
        if (!card || !previewId) return;
        const format = this.cfg.recordingFormat.value;
        const plan = planRecordingToggle(this.recordingPreviewIds, previewId);
        for (const prior of plan.deactivate) {
            this.cfg.vscode.postMessage({
                command: "setRecording",
                previewId: prior,
                enabled: false,
                format,
            });
        }
        this.recordingPreviewIds = plan.next;
        if (plan.turnOnTarget) {
            attachInteractiveInputHandlers(
                card,
                this.cfg.interactiveInputConfig,
            );
        }
        this.cfg.vscode.postMessage({
            command: "setRecording",
            previewId,
            enabled: plan.turnOnTarget,
            format,
        });
        this.cfg.applyRecordingButtonState();
        this.cfg.renderInspector(card);
    }

    /**
     * Single-target follow-focus teardown: when there is exactly one live
     * stream and the user navigates off it, drop the stream so the LIVE chip
     * follows the focused card. Multi-target (size > 1) is treated as an
     * explicit opt-in via Shift+click — those streams persist across focus
     * navigation until the user explicitly toggles them off.
     */
    enforceSingleTargetFollowFocus(focusedCard: HTMLElement | null): void {
        const focusedPreviewId = focusedCard?.dataset.previewId ?? null;
        const plan = planFollowFocusTeardown(
            this.interactivePreviewIds,
            focusedPreviewId,
        );
        if (!plan) return;
        this.postLiveCommand(plan.teardownId, false);
        this.interactivePreviewIds.clear();
        // Issue #1203 — also drop the Controls flag for the card we just
        // tore down so its toggle stays explicit on re-entry. The planner
        // only fires when exactly one card was live, so `.delete()` on the
        // teardown id is sufficient.
        this.controlsEnabledPreviewIds.delete(plan.teardownId);
        this.applyLiveBadge();
        this.applyControlsToggleButtons();
    }

    /**
     * Viewport callback — soft-throttle a live stream once its card has
     * scrolled fully out of view.
     *
     * The daemon's `stream/visibility` "throttle to keyframes-only" mode
     * keeps the held session warm so scroll-back-into-view repaints from
     * the cached anchor instead of cold-blanking. The local
     * `interactivePreviewIds` set still says "this card is live" so the
     * LIVE badge survives the throttle.
     */
    onCardLeftViewport(previewId: string): void {
        throttleLiveOnViewportLeave(
            previewId,
            this.interactivePreviewIds,
            (msg) => this.cfg.vscode.postMessage(msg),
        );
    }

    /** Drop live previewIds that are gone from a fresh setPreviews manifest.
     *  Silent — the preview no longer exists for the daemon to dispatch into
     *  anyway. Caller is expected to follow up with `applyLiveBadge` +
     *  `applyInteractiveButtonState`. */
    pruneLive(stillExists: (previewId: string) => boolean): void {
        this.interactivePreviewIds.forEach((id) => {
            if (!stillExists(id)) this.interactivePreviewIds.delete(id);
        });
        // Issue #1203 — keep the per-card "Controls" set in sync. A preview
        // that no longer exists for the daemon to dispatch into shouldn't
        // keep a panel-side flag that would re-attach interception on a
        // future preview that happens to reuse the id.
        this.controlsEnabledPreviewIds.forEach((id) => {
            if (!stillExists(id)) this.controlsEnabledPreviewIds.delete(id);
        });
    }

    /** Daemon-not-ready — drop UI bookkeeping silently. */
    handleDaemonLost(): void {
        if (this.interactivePreviewIds.size > 0) {
            this.interactivePreviewIds.clear();
            this.applyLiveBadge();
        }
        if (this.recordingPreviewIds.size > 0) {
            this.recordingPreviewIds.clear();
        }
        // Issue #1203 — controls-enabled flag must also drop when the daemon
        // disappears; the listener wouldn't reach a daemon anyway.
        this.controlsEnabledPreviewIds.clear();
        // Daemon-advertised capability state is now stale — clear so the
        // focus-bar touch-overlay / keyboard-band / #1203 controls toggles
        // (gated on these maps via [applyControlsToggleButtons]) hide
        // immediately instead of pointing at a dead backend until the next
        // `setDaemonCapabilities` lands. Mirrors the `interactivePreviewIds`
        // clear above — these are global single-module-scoped sets today
        // (see `handleDaemonLost`'s docstring); revisit when the panel
        // grows multi-module support.
        this.moduleAdvertisedExtensions.clear();
        this.moduleInteractiveOnlyExtensions.clear();
        this.moduleInteractiveControlKinds.clear();
        this.applyControlsToggleButtons();
        this.cfg.applyInteractiveButtonState();
        this.cfg.applyRecordingButtonState();
    }

    /** Extension-driven `clearInteractive` — silent, the extension already
     *  stopped the streams server-side. Also drop the per-card "Controls"
     *  flag (#1203) so a future live-mode re-entry doesn't silently re-attach
     *  keyboard interception, matching `stopInteractiveForCard`. */
    handleExtensionClearInteractive(previewId: string | null): void {
        if (previewId) {
            this.interactivePreviewIds.delete(previewId);
            this.controlsEnabledPreviewIds.delete(previewId);
            this.applyLiveBadge();
            this.applyControlsToggleButtons();
            this.cfg.applyInteractiveButtonState();
        } else if (this.interactivePreviewIds.size > 0) {
            this.interactivePreviewIds.clear();
            this.controlsEnabledPreviewIds.clear();
            this.applyLiveBadge();
            this.applyControlsToggleButtons();
            this.cfg.applyInteractiveButtonState();
        }
    }

    handleExtensionClearRecording(previewId: string | null): void {
        if (previewId) {
            this.recordingPreviewIds.delete(previewId);
        } else if (this.recordingPreviewIds.size > 0) {
            this.recordingPreviewIds.clear();
        }
        this.cfg.applyRecordingButtonState();
    }

    /** Early-features flag flipped off — explicitly stop every recording
     *  (live is implicitly torn down by the daemon-side teardown earlier in
     *  the same setEarlyFeatures path). */
    handleEarlyFeaturesDisabled(): void {
        if (this.recordingPreviewIds.size === 0) return;
        const format = this.cfg.recordingFormat.value;
        this.recordingPreviewIds.forEach((previewId) => {
            this.cfg.vscode.postMessage({
                command: "setRecording",
                previewId,
                enabled: false,
                format,
            });
        });
        this.recordingPreviewIds.clear();
    }

    private ensureLiveCardControls(card: HTMLElement): void {
        // Button DOM lives in `./liveCardControls.ts` so the mutation
        // is testable under happy-dom without dragging this controller's
        // wider transitive imports into the host tsconfig. The pointer
        // / wheel input wiring stays here — `attachInteractiveInputHandlers`
        // pulls in the broader interactive-input surface that the narrow
        // helper deliberately avoids.
        ensureLiveCardControls(card, (c) => this.stopInteractiveForCard(c));
        attachInteractiveInputHandlers(card, this.cfg.interactiveInputConfig);
    }
}

/** Structural equality for the LauncherWidgetSize compare in setLauncherWidgetCellsForCard. */
function sameCells(
    a: LauncherWidgetSize | null,
    b: LauncherWidgetSize | null,
): boolean {
    if (a === b) return true;
    if (a === null || b === null) return false;
    return a.width === b.width && a.height === b.height;
}
