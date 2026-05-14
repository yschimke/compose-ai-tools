// Focus-mode inspector — the slim side panel that appears when a single
// preview is focused. After the chip / bucket / presenter pipeline moved
// to the new bundle shell (#1099), this controller only owns three
// sections, top to bottom:
//
//   1. **Error banner** — surfaces the per-card render error when one
//      is present. Driven by the `local/render/error` presenter so the
//      shape stays consistent with the bundle-shell renderer.
//   2. **History** — diff buttons (HEAD / main).
//   3. **Tools** — A11y / Device / Live / Record buttons.
//
// Everything else (suggestion chips, bucket list, legends, reports,
// overlay stacks, MRU plumbing) was deleted with the pipeline it served.
// The remaining public API — `render`, `clearProducts`, `releasePreview`,
// `setProducts` — is kept so the host wiring in `main.ts` doesn't have
// to learn that the inspector is mostly a stub now; future redesigns
// will collapse it further.

import type { PreviewInfo } from "../shared/types";
import {
    type PresenterContext,
    type PresentationError,
    type ProductPresentation,
    getPresenter,
} from "./focusPresentation";

export interface FocusInspectorConfig {
    /** The `<div id="focus-inspector">` element rendered by `<preview-app>`. */
    el: HTMLElement;
    /** Whether `composePreview.earlyFeatures` is on — when off the
     *  inspector hides and the caller's `clear` path clears it. */
    earlyFeatures(): boolean;
    /** Look up the manifest entry for a preview, or `undefined` when
     *  the preview is unknown to this panel. */
    getPreview(previewId: string): PreviewInfo | undefined;
    /** Click handlers shared with the focus toolbar. */
    onToggleA11yOverlay(): void;
    /** `shift = false` matches the toolbar Live button — single-target. */
    onToggleInteractive(shift: boolean): void;
    onToggleRecording(): void;
    onRequestFocusedDiff(against: "head" | "main"): void;
    onRequestLaunchOnDevice(): void;
}

export class FocusInspectorController {
    private historyOpen = false;
    /** Last card we rendered. Held so external state changes (e.g. an
     *  incoming render error) can repaint without the caller having to
     *  remember the focused card. */
    private lastCard: HTMLElement | null = null;

    constructor(private readonly config: FocusInspectorConfig) {}

    /**
     * Kept for source compatibility with the daemon-capabilities
     * dispatch in `messageHandlers.ts`. The slim render path no longer
     * advertises any per-product UI, so the descriptor list is dropped
     * on the floor — but accepting the call keeps the host wiring
     * cohesive while the bundle shell takes over capability surfacing.
     */
    setProducts(_products: unknown): void {
        // intentionally empty
    }

    /** Repaint the inspector for [card], or clear it when [card] is
     *  `null` / earlyFeatures is off. */
    render(card: HTMLElement | null): void {
        const el = this.config.el;
        el.innerHTML = "";
        const visible = !!card && this.config.earlyFeatures();
        el.hidden = !visible;
        if (!visible || !card) {
            this.lastCard = null;
            return;
        }
        const previewId = card.dataset.previewId ?? "";
        const preview = previewId
            ? this.config.getPreview(previewId)
            : undefined;
        if (!previewId || !preview) {
            this.lastCard = null;
            return;
        }
        this.lastCard = card;

        const errorBanner = this.renderErrors(
            this.collectErrorPresentationsOnly(card, preview),
        );
        if (errorBanner) el.appendChild(errorBanner);
        el.appendChild(this.historyPanel());
        el.appendChild(this.controlsPanel());
    }

    /**
     * Reset cached per-preview state. After the chip/bucket prune
     * there's nothing per-preview left to clear — kept as a no-op so
     * the `clearAll` / `setEarlyFeatures off` path in
     * `messageHandlers.ts` doesn't have to learn the shape changed.
     */
    clearProducts(): void {
        // intentionally empty
    }

    /**
     * Focus is moving away from [previewId]. The slim inspector no
     * longer owns subscription state, so this is a no-op — bundle
     * controllers manage their own teardown.
     */
    releasePreview(_previewId: string): void {
        // intentionally empty
    }

    /**
     * Invoke just the `local/render/error` presenter so the inspector
     * can surface a render-error banner without re-running the whole
     * presenter pipeline. Returns an empty array when no error is
     * present, keeping the call shape consistent with the previous
     * `collectPresentations` helper.
     */
    private collectErrorPresentationsOnly(
        card: HTMLElement,
        preview: PreviewInfo,
    ): { kind: string; presentation: ProductPresentation }[] {
        const ctx: PresenterContext = {
            card,
            preview,
            findings: [],
            nodes: [],
        };
        const out: { kind: string; presentation: ProductPresentation }[] = [];
        const errorPresenter = getPresenter("local/render/error");
        if (errorPresenter) {
            const presentation = errorPresenter(ctx);
            if (presentation) {
                out.push({ kind: "local/render/error", presentation });
            }
        }
        return out;
    }

    private renderErrors(
        presentations: readonly {
            kind: string;
            presentation: ProductPresentation;
        }[],
    ): HTMLElement | null {
        const errors: { kind: string; error: PresentationError }[] = [];
        for (const { kind, presentation } of presentations) {
            if (presentation.error) {
                errors.push({ kind, error: presentation.error });
            }
        }
        if (errors.length === 0) return null;
        const banner = document.createElement("section");
        banner.className = "focus-panel focus-error-panel";
        banner.setAttribute("role", "alert");
        for (const { kind, error } of errors) {
            const row = document.createElement("div");
            row.className = "focus-error-row";
            row.dataset.kind = kind;
            const icon = document.createElement("i");
            icon.className = "codicon codicon-error";
            icon.setAttribute("aria-hidden", "true");
            row.appendChild(icon);
            const body = document.createElement("div");
            body.className = "focus-error-body";
            const title = document.createElement("div");
            title.className = "focus-error-title";
            title.textContent = error.title;
            const message = document.createElement("div");
            message.className = "focus-error-message";
            message.textContent = error.message;
            body.appendChild(title);
            body.appendChild(message);
            if (error.detail) {
                const detail = document.createElement("div");
                detail.className = "focus-error-detail";
                detail.textContent = error.detail;
                body.appendChild(detail);
            }
            row.appendChild(body);
            banner.appendChild(row);
        }
        return banner;
    }

    private historyPanel(): HTMLElement {
        const history = document.createElement("details");
        history.className = "focus-panel focus-history-panel";
        history.open = this.historyOpen;
        history.addEventListener("toggle", () => {
            this.historyOpen = history.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-panel-header focus-history-summary";
        summary.innerHTML =
            '<i class="codicon codicon-history" aria-hidden="true"></i>';
        const label = document.createElement("span");
        label.textContent = "History";
        summary.appendChild(label);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        history.appendChild(summary);
        const historyActions = document.createElement("div");
        historyActions.className = "focus-actions";
        historyActions.appendChild(
            actionButton(
                "git-compare",
                "HEAD",
                "Diff vs last archived render",
                () => this.config.onRequestFocusedDiff("head"),
            ),
        );
        historyActions.appendChild(
            actionButton(
                "source-control",
                "main",
                "Diff vs latest archived main render",
                () => this.config.onRequestFocusedDiff("main"),
            ),
        );
        history.appendChild(historyActions);
        return history;
    }

    private controlsPanel(): HTMLElement {
        const controls = document.createElement("section");
        controls.className = "focus-panel focus-controls-panel";
        controls.appendChild(sectionHeader("settings-gear", "Tools"));
        const toolActions = document.createElement("div");
        toolActions.className = "focus-actions";
        toolActions.appendChild(
            actionButton("eye", "A11y", "Toggle accessibility overlay", () =>
                this.config.onToggleA11yOverlay(),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "device-mobile",
                "Device",
                "Launch on connected Android device",
                () => this.config.onRequestLaunchOnDevice(),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "circle-large-outline",
                "Live",
                "Toggle live preview",
                () => this.config.onToggleInteractive(false),
            ),
        );
        toolActions.appendChild(
            actionButton(
                "record-keys",
                "Record",
                "Record focused preview",
                () => this.config.onToggleRecording(),
            ),
        );
        controls.appendChild(toolActions);
        return controls;
    }
}

function sectionHeader(icon: string, label: string): HTMLElement {
    const header = document.createElement("div");
    header.className = "focus-panel-header";
    header.innerHTML =
        '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
    const span = document.createElement("span");
    span.textContent = label;
    header.appendChild(span);
    return header;
}

function actionButton(
    icon: string,
    label: string,
    title: string,
    onClick: () => void,
): HTMLElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "focus-action";
    btn.title = title;
    btn.innerHTML =
        '<i class="codicon codicon-' + icon + '" aria-hidden="true"></i>';
    const span = document.createElement("span");
    span.textContent = label;
    btn.appendChild(span);
    btn.addEventListener("click", onClick);
    return btn;
}
