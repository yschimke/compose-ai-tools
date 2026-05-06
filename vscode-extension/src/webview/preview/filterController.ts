// Filter + message-banner orchestration for the live "Compose Preview"
// panel.
//
// Lifted verbatim from `behavior.ts`'s `applyFilters` / `saveFilterState`
// / `restoreFilterState` / `setMessage` / `ensureNotBlank` cluster.
// These five operations are tightly coupled — `applyFilters` may call
// `setMessage` to surface "no previews match the current filters" and
// `setMessage` always runs `ensureNotBlank()` as its tail, so they fold
// into one controller.
//
// Owns no closure state of its own. The persisted filter state lives on
// the shared `state` object passed via `FilterControllerConfig` (the same
// reference `behavior.ts` and `FocusController` mutate). The
// message-banner is a Lit element accessed via the typed handle in the
// config.

import type { FilterToolbar } from "./components/FilterToolbar";
import type { MessageBanner, MessageOwner } from "./components/MessageBanner";
import type { PreviewGrid } from "./components/PreviewGrid";
import type { PreviewInfo } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";

/** Slim slice of the persisted state the filter controller writes. */
export interface FilterControllerPersistedState {
    filters?: { fn?: string; group?: string };
}

export interface FilterControllerConfig {
    vscode: VsCodeApi<FilterControllerPersistedState>;
    /** Shared mutable persisted-state reference. The controller mutates
     *  `state.filters` and calls `vscode.setState(state)` to persist. */
    state: FilterControllerPersistedState;
    filterToolbar: FilterToolbar;
    grid: PreviewGrid;
    messageBanner: MessageBanner;
    /** Read the current manifest — `apply` checks for the
     *  "no previews at all" state to decide whether the extension owns
     *  the empty-state message. */
    getAllPreviews(): readonly PreviewInfo[];
    /** Re-apply layout after filter change — focus mode needs to
     *  recompute focusIndex bounds against the narrowed visible set. */
    applyLayout(): void;
}

export class FilterController {
    constructor(private readonly config: FilterControllerConfig) {}

    /** Persist the current filter selections via `vscode.setState` so a
     *  panel reload restores them. */
    save(): void {
        this.config.state.filters = {
            fn: this.config.filterToolbar.getFunctionValue(),
            group: this.config.filterToolbar.getGroupValue(),
        };
        this.config.vscode.setState(this.config.state);
    }

    /** Restore filter selections from `state.filters`, but only when
     *  the saved value still exists as an option (the manifest may have
     *  lost the function / group across panel reloads). */
    restore(): void {
        const f = this.config.state.filters || {};
        if (f.fn && this.config.filterToolbar.hasFunctionOption(f.fn))
            this.config.filterToolbar.setFunctionValue(f.fn);
        if (f.group && this.config.filterToolbar.hasGroupOption(f.group))
            this.config.filterToolbar.setGroupValue(f.group);
    }

    /** Apply the active filter to the grid, surface a "no previews
     *  match" message when applicable, then re-apply layout (so focus
     *  mode recomputes focusIndex bounds against the narrowed visible
     *  set). */
    apply(): void {
        const visibleCount = this.config.grid.applyFilters({
            fn: this.config.filterToolbar.getFunctionValue(),
            group: this.config.filterToolbar.getGroupValue(),
        });

        // Only own the message when we have a filter-specific thing to
        // say. When there are no previews at all, the extension owns
        // the message (e.g. "No @Preview functions in this file") —
        // clearing it here was how the view went blank after a refresh.
        if (this.config.getAllPreviews().length > 0 && visibleCount === 0) {
            this.setMessage("No previews match the current filters", "filter");
        } else if (this.config.messageBanner.getOwner() === "filter") {
            // We set this earlier; clear it now that it no longer applies.
            this.setMessage("", "filter");
        }

        // Re-apply layout so focus mode updates correctly after filter change
        this.config.applyLayout();
    }

    /**
     * Thin shim around `<message-banner>.setMessage` that keeps the
     * `ensureNotBlank()` backstop wired in. The owner tag is used only
     * to let `apply` clear its own message without touching extension-
     * set text (empty-file notice, build errors, etc.).
     *
     * `renderPreviews` (in `cardBuilder.ts`) and the `messageHandlers`
     * dispatcher reach for this via the `setMessage` callback in their
     * respective contexts.
     */
    setMessage(text: string, owner?: MessageOwner): void {
        this.config.messageBanner.setMessage(text, owner ?? "extension");
        this.ensureNotBlank();
    }

    /**
     * Safety net: if the grid ends up empty *and* no message is showing,
     * surface a placeholder so the user doesn't stare at a void. This
     * shouldn't normally trigger — the extension sends an explicit
     * message for every empty state — but a silent blank view was the
     * original complaint, so this catches any future regressions.
     */
    ensureNotBlank(): void {
        const hasCards =
            this.config.grid.querySelector(".preview-card") !== null;
        if (!hasCards && !this.config.messageBanner.isVisible()) {
            this.config.messageBanner.setMessage(
                "Preparing previews…",
                "fallback",
            );
        }
    }
}
