// `<bundle-chip-bar>` — chip strip rendered below the preview grid in
// focus mode. Each chip is an icon + label toggle for a data-extension
// bundle. Pressing a chip opens the matching tab in `<data-tabs>` and
// starts subscriptions to the bundle's default-ON kinds; re-pressing
// it tears them down. The chip + the tab `×` are deliberately
// redundant so the dismiss path is reachable from wherever the user's
// eye lands (see `docs/design/EXTENSION_DATA_EXPOSURE.md` § "Chip ↔
// tab ↔ overlay state machine").
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
    /** Bundle ids the user is allowed to toggle in the current panel
     *  configuration. When `null`, every bundle in `bundles` is shown
     *  (the early-features-on default). When a set, only those bundles
     *  paint chips — the rest are hidden so a basic-mode panel surfaces
     *  just the graduated extensions (e.g. a11y) without leaking the
     *  in-progress ones. */
    @state() private availableBundles: ReadonlySet<BundleId> | null = null;

    setState(snapshot: {
        bundles: readonly BundleDescriptor[];
        activeBundles: readonly BundleId[];
        availableBundles?: readonly BundleId[] | null;
    }): void {
        this.bundles = snapshot.bundles;
        this.activeBundles = snapshot.activeBundles;
        this.availableBundles =
            snapshot.availableBundles === undefined ||
            snapshot.availableBundles === null
                ? null
                : new Set(snapshot.availableBundles);
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        const visible = this.bundles.filter(
            (b) =>
                this.availableBundles === null ||
                this.availableBundles.has(b.id),
        );
        return html`
            <div
                class="bundle-chip-bar"
                role="toolbar"
                aria-label="Data extensions"
            >
                ${visible.map((b) => this.renderChip(b))}
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
