// Focus-mode inspector — the side panel that appears when a single
// preview is focused. Three sections: an Inspect product picker (a11y
// / Layout / Strings / Resources / Fonts / Render / Theme /
// Recomposition), a History accordion (diff vs HEAD, diff vs main),
// and a Tools strip (a11y, device, live, record action buttons).
//
// Lifted verbatim from `behavior.ts`'s `renderFocusInspector` cluster
// (`sectionHeader` / `actionButton` / `historyPanel` / `productSpec` /
// `productPicker` / `productOption` / `toggleFocusProduct` /
// `buildFocusPlaceholders`). Same DOM structure, same CSS classes,
// same accordion-open persistence (`focusProductPickerOpen` /
// `focusHistoryOpen`) — those flags and the `enabledFocusProducts`
// Set live as private state on the controller now rather than free
// variables in `setupPreviewBehavior`.
//
// Action callbacks (toggleA11yOverlay / toggleInteractive /
// toggleRecording / requestFocusedDiff / requestLaunchOnDevice) and
// the per-preview state queries (a11y findings / nodes, live set,
// store-backed `a11yOverlayId`) are passed in via `FocusInspectorConfig`
// so the module doesn't need closure access to the rest of
// `behavior.ts`. `clearAll` resets the `enabledFocusProducts` Set.
//
// `renderSummary` and `themeSummary` from the imperative copy were
// dead code (defined but never called) and are dropped; behaviour
// unchanged.

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";

export interface FocusInspectorConfig {
    /** The `<div id="focus-inspector">` element rendered by `<preview-app>`. */
    el: HTMLElement;
    /** Whether `composePreview.earlyFeatures` is on — when off the
     *  inspector hides and the caller's `clear` path clears it. */
    earlyFeatures(): boolean;
    /** Look up the manifest entry for a preview, or `undefined` when
     *  the preview is unknown to this panel (e.g. transient state
     *  during a `setPreviews` swap). */
    getPreview(previewId: string): PreviewInfo | undefined;
    /** Latest a11y findings for a preview. Reads the runtime cache
     *  populated from `setPreviews` / `updateA11y`. */
    getA11yFindings(previewId: string): readonly AccessibilityFinding[];
    /** Latest a11y hierarchy nodes for a preview. */
    getA11yNodes(previewId: string): readonly AccessibilityNode[];
    /** `previewId` whose a11y overlay subscription is currently on, or
     *  `null` when no card is subscribed. */
    getA11yOverlayId(): string | null;
    /** Whether [previewId] is currently in the live (interactive) set. */
    isLive(previewId: string): boolean;
    /** Click handlers shared with the focus toolbar — pressed from
     *  the inspector's product-picker rows / tools strip. */
    onToggleA11yOverlay(): void;
    /** `shift = false` matches the toolbar Live button — single-target. */
    onToggleInteractive(shift: boolean): void;
    onToggleRecording(): void;
    onRequestFocusedDiff(against: string): void;
    onRequestLaunchOnDevice(): void;
}

interface ProductOptionSpec {
    icon: string;
    label: string;
    value: string;
    enabled: boolean;
    state: "ok" | "warn" | "idle";
    onToggle(): void;
}

export class FocusInspectorController {
    private readonly enabled = new Set<string>();
    private productPickerOpen = false;
    private historyOpen = false;
    /** Last card we rendered. Held so `toggleProduct` can re-render
     *  the panel on the same card after flipping a checkbox. */
    private lastCard: HTMLElement | null = null;

    constructor(private readonly config: FocusInspectorConfig) {}

    /** Repaint the inspector for [card], or clear it when [card] is
     *  `null` / earlyFeatures is off. Called by `applyLayout`,
     *  `setInteractiveForCard`, `toggleRecording`, etc. */
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
        const p = previewId ? this.config.getPreview(previewId) : undefined;
        if (!previewId || !p) {
            this.lastCard = null;
            return;
        }
        this.lastCard = card;

        const inspect = document.createElement("section");
        inspect.className = "focus-panel focus-inspect-panel";
        inspect.appendChild(sectionHeader("search", "Inspect"));
        const findings = this.config.getA11yFindings(previewId);
        const nodes = this.config.getA11yNodes(previewId);
        inspect.appendChild(
            this.productPicker([
                {
                    icon: "eye",
                    label: "Accessibility",
                    value:
                        findings.length > 0
                            ? findings.length +
                              " finding" +
                              (findings.length === 1 ? "" : "s")
                            : "Overlay",
                    enabled: previewId === this.config.getA11yOverlayId(),
                    state: findings.length > 0 ? "warn" : "idle",
                    onToggle: () => this.config.onToggleA11yOverlay(),
                },
                {
                    icon: "list-tree",
                    label: "Layout",
                    value:
                        nodes.length > 0
                            ? nodes.length +
                              " node" +
                              (nodes.length === 1 ? "" : "s")
                            : "Placeholder",
                    enabled: this.enabled.has("layout"),
                    state: nodes.length > 0 ? "ok" : "idle",
                    onToggle: () => this.toggleProduct("layout"),
                },
                this.productSpec("symbol-string", "Strings", "strings"),
                this.productSpec("file-code", "Resources", "resources"),
                this.productSpec("text-size", "Fonts", "fonts"),
                this.productSpec("pulse", "Render", "render"),
                this.productSpec("symbol-color", "Theme", "theme"),
                {
                    icon: "sync",
                    label: "Recomposition",
                    value: this.config.isLive(previewId)
                        ? "Live"
                        : "Placeholder",
                    enabled:
                        this.enabled.has("recomposition") ||
                        this.config.isLive(previewId),
                    state: this.config.isLive(previewId) ? "ok" : "idle",
                    onToggle: () => this.toggleProduct("recomposition"),
                },
            ]),
        );
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
        el.appendChild(inspect);
        el.appendChild(this.historyPanel());
        el.appendChild(controls);
        const placeholders = this.buildPlaceholders();
        if (placeholders) inspect.appendChild(placeholders);
    }

    /**
     * Reset the per-preview product-enable Set. Called by `behavior.ts`
     * on the `clearAll` message path (panel scope changed) so the
     * inspector doesn't carry stale "Layout enabled" / "Render enabled"
     * checkboxes into the new module's previews.
     */
    clearProducts(): void {
        this.enabled.clear();
    }

    private toggleProduct(id: string): void {
        if (this.enabled.has(id)) {
            this.enabled.delete(id);
        } else {
            this.enabled.add(id);
        }
        if (this.lastCard) this.render(this.lastCard);
    }

    private productSpec(
        icon: string,
        label: string,
        id: string,
    ): ProductOptionSpec {
        return {
            icon,
            label,
            value: "Placeholder",
            enabled: this.enabled.has(id),
            state: "idle",
            onToggle: () => this.toggleProduct(id),
        };
    }

    private productPicker(products: ProductOptionSpec[]): HTMLElement {
        const picker = document.createElement("details");
        picker.className = "focus-product-picker";
        picker.open = this.productPickerOpen;
        picker.addEventListener("toggle", () => {
            this.productPickerOpen = picker.open;
        });
        const summary = document.createElement("summary");
        summary.className = "focus-product-summary";
        const selected = products.filter((product) => product.enabled);
        const summaryText = document.createElement("span");
        summaryText.textContent =
            selected.length === 0
                ? "Choose inspection layers"
                : selected.length === 1
                  ? selected[0].label
                  : selected.length + " layers selected";
        summary.appendChild(summaryText);
        const chevron = document.createElement("i");
        chevron.className =
            "codicon codicon-chevron-down focus-summary-chevron";
        chevron.setAttribute("aria-hidden", "true");
        summary.appendChild(chevron);
        picker.appendChild(summary);

        const menu = document.createElement("div");
        menu.className = "focus-product-menu";
        for (const product of products) {
            menu.appendChild(productOption(product));
        }
        picker.appendChild(menu);
        return picker;
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

    private buildPlaceholders(): HTMLElement | null {
        const defs: ReadonlyArray<readonly [string, string, string]> = [
            ["layout", "Layout", "layout tree and bounds"],
            ["strings", "Strings", "text/strings and i18n/translations"],
            ["resources", "Resources", "resources/used"],
            ["fonts", "Fonts", "fonts/used"],
            ["render", "Render", "render/trace and render/composeAiTrace"],
            ["theme", "Theme", "compose/theme"],
            ["recomposition", "Recomposition", "compose/recomposition"],
        ];
        const active = defs.filter(([id]) => this.enabled.has(id));
        if (active.length === 0) return null;
        const wrapper = document.createElement("div");
        wrapper.className = "focus-placeholder-list";
        for (const [id, label, kind] of active) {
            const details = document.createElement("details");
            details.className = "focus-placeholder";
            details.dataset.product = id;
            const summary = document.createElement("summary");
            summary.textContent = label;
            details.appendChild(summary);
            const body = document.createElement("div");
            body.className = "focus-placeholder-body";
            body.textContent = kind;
            details.appendChild(body);
            wrapper.appendChild(details);
        }
        return wrapper;
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

function productOption(product: ProductOptionSpec): HTMLElement {
    const option = document.createElement("label");
    option.className = "focus-product-option";
    option.dataset.state = product.state;
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = product.enabled;
    input.addEventListener("change", product.onToggle);
    option.appendChild(input);
    const icon = document.createElement("i");
    icon.className = "codicon codicon-" + product.icon;
    icon.setAttribute("aria-hidden", "true");
    option.appendChild(icon);
    const text = document.createElement("div");
    text.className = "focus-product-text";
    const name = document.createElement("span");
    name.className = "focus-product-name";
    name.textContent = product.label;
    const val = document.createElement("span");
    val.className = "focus-product-value";
    val.textContent = product.value;
    text.appendChild(name);
    text.appendChild(val);
    option.appendChild(text);
    return option;
}
