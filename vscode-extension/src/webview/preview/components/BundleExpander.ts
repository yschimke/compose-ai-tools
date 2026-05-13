// `<bundle-expander>` — "Configure…" block that appears at the top of
// any bundle's tab body (#1054). One row per kind in the bundle with a
// checkbox; emits `kind-toggled` events the host forwards to
// `BundleController.setKindEnabled`. Closed by default — the chip's
// default-ON kinds are already subscribed, so most users never need to
// open this. Power users open it to add a default-OFF kind (e.g.
// `a11y/touchTargets`, `a11y/overlay` PNG, `compose/wallpaper`) or to
// drop a default-ON kind they don't want.
//
// The expander is read-only state — `BundleController` is the truth.
// The component reads `bundleId`, the list of bundle kinds, and the
// current enabled set via `setState`; user clicks dispatch
// `kind-toggled` events that the host forwards back to the controller.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import type { BundleId, BundleKind } from "../bundleRegistry";

export interface BundleKindToggledDetail {
    bundleId: BundleId;
    kind: string;
    enabled: boolean;
}

@customElement("bundle-expander")
export class BundleExpander extends LitElement {
    @state() private bundleId: BundleId | null = null;
    @state() private kinds: readonly BundleKind[] = [];
    @state() private enabledKinds: ReadonlySet<string> = new Set();
    /** Whether the `<details>` is open. Persisted by the host via the
     *  same scoped MRU as the rest of the bundle state if desired;
     *  defaults to closed because the default-ON kinds are already
     *  subscribed and the common case is "leave it alone." */
    @state() private opened = false;

    setState(snapshot: {
        bundleId: BundleId;
        kinds: readonly BundleKind[];
        enabledKinds: readonly string[];
    }): void {
        this.bundleId = snapshot.bundleId;
        this.kinds = snapshot.kinds;
        this.enabledKinds = new Set(snapshot.enabledKinds);
    }

    setOpened(opened: boolean): void {
        this.opened = opened;
    }

    isOpened(): boolean {
        return this.opened;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        if (!this.bundleId || this.kinds.length === 0) {
            return html``;
        }
        return html`
            <details
                class="bundle-expander"
                ?open=${this.opened}
                @toggle=${this.onToggle}
            >
                <summary class="bundle-expander-summary">
                    <i
                        class="codicon codicon-settings-gear"
                        aria-hidden="true"
                    ></i>
                    <span>Configure</span>
                </summary>
                <div class="bundle-expander-body">
                    ${this.kinds.map((k) => this.renderRow(k))}
                </div>
            </details>
        `;
    }

    private renderRow(k: BundleKind): TemplateResult {
        const checked = this.enabledKinds.has(k.kind);
        const inputId = `bundle-kind-${this.bundleId}-${k.kind.replace(/[^a-z0-9]+/gi, "-")}`;
        return html`
            <label class="bundle-expander-row" for=${inputId}>
                <input
                    id=${inputId}
                    type="checkbox"
                    .checked=${checked}
                    data-kind=${k.kind}
                    @change=${(e: Event) => this.onCheckboxChanged(e, k.kind)}
                />
                <span class="bundle-expander-label">${k.label}</span>
                ${k.defaultOn
                    ? html`<span
                          class="bundle-expander-default"
                          title="On by default"
                          >default</span
                      >`
                    : html``}
                <code class="bundle-expander-kind">${k.kind}</code>
            </label>
        `;
    }

    private onToggle(e: Event): void {
        const det = e.target as HTMLDetailsElement;
        this.opened = det.open;
    }

    private onCheckboxChanged(evt: Event, kind: string): void {
        if (!this.bundleId) return;
        const target = evt.target as HTMLInputElement;
        this.dispatchEvent(
            new CustomEvent<BundleKindToggledDetail>("kind-toggled", {
                detail: {
                    bundleId: this.bundleId,
                    kind,
                    enabled: target.checked,
                },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
