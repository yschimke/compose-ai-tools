// `<bundle-chip-bar>` — chip strip below the filter toolbar. Each chip
// is a toggle for a data-extension bundle. Pressing a chip opens the
// matching tab in `<data-tabs>` and starts subscriptions to the
// bundle's default-ON kinds; re-pressing it tears them down. The chip
// + the tab `×` are deliberately redundant so the dismiss path is
// reachable from wherever the user's eye lands (see
// `docs/design/EXTENSION_DATA_EXPOSURE.md` § "Chip ↔ tab ↔ overlay
// state machine").
//
// The component is read-only state — `BundleController` owns the
// truth and pushes updates via `setState`. User clicks fire
// `bundle-toggled` events for the host to forward to the controller.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import type { BundleDescriptor, BundleId } from "../bundleRegistry";

export interface BundleToggledDetail {
    id: BundleId;
}

@customElement("bundle-chip-bar")
export class BundleChipBar extends LitElement {
    @state() private bundles: readonly BundleDescriptor[] = [];
    @state() private activeBundles: readonly BundleId[] = [];

    setState(snapshot: {
        bundles: readonly BundleDescriptor[];
        activeBundles: readonly BundleId[];
    }): void {
        this.bundles = snapshot.bundles;
        this.activeBundles = snapshot.activeBundles;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <div
                class="bundle-chip-bar"
                role="toolbar"
                aria-label="Data extensions"
            >
                ${this.bundles.map((b) => this.renderChip(b))}
            </div>
        `;
    }

    private renderChip(b: BundleDescriptor): TemplateResult {
        const pressed = this.activeBundles.includes(b.id);
        return html`
            <button
                type="button"
                class=${pressed ? "bundle-chip bundle-chip-on" : "bundle-chip"}
                aria-pressed=${pressed ? "true" : "false"}
                data-bundle=${b.id}
                title=${b.label}
                @click=${() => this.onClick(b.id)}
            >
                <i class=${"codicon codicon-" + b.icon} aria-hidden="true"></i>
                <span class="bundle-chip-label">${b.label}</span>
            </button>
        `;
    }

    private onClick(id: BundleId): void {
        this.dispatchEvent(
            new CustomEvent<BundleToggledDetail>("bundle-toggled", {
                detail: { id },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
