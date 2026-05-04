// `<filter-toolbar>` — replaces the imperative three-select toolbar
// (function filter, group filter, layout mode) that used to live in
// `behavior.ts`.
//
// The component owns the rendering and the user-interaction events,
// fires typed `filter-changed` / `layout-changed` CustomEvents on
// user picks, and exposes typed `setXxx` / `getXxx` methods for the
// in-webview callers in `behavior.ts` (programmatic state restore,
// option repopulation on `setPreviews`, programmatic layout change
// from `focusOnCard` / `exitFocus`, etc.). Programmatic setters DO
// NOT fire the change event — `behavior.ts` orchestrates persistence
// and `applyFilters` / `applyLayout` itself in those cases, matching
// the previous control flow exactly.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

export type LayoutMode = "grid" | "flow" | "column" | "focus";

export interface FilterChangedDetail {
    fn: string;
    group: string;
}

export interface LayoutChangedDetail {
    layout: LayoutMode;
}

@customElement("filter-toolbar")
export class FilterToolbar extends LitElement {
    @state() private fnOptions: readonly string[] = [];
    @state() private grpOptions: readonly string[] = [];
    @state() private fnValue = "all";
    @state() private grpValue = "all";
    @state() private layoutValue: LayoutMode = "grid";

    setFunctionOptions(opts: readonly string[]): void {
        this.fnOptions = opts;
    }

    setGroupOptions(opts: readonly string[]): void {
        this.grpOptions = opts;
    }

    /**
     * Set the function filter. Ensures the option exists in the
     * dropdown — this matters for the `setFunctionFilter` path triggered
     * by the gutter-icon hover, which can fire before `setPreviews` has
     * populated the options. Without this the value would visibly
     * snap back to "All functions" on the next render.
     */
    setFunctionValue(value: string): void {
        if (value !== "all" && !this.fnOptions.includes(value)) {
            this.fnOptions = [...this.fnOptions, value];
        }
        this.fnValue = value;
    }

    setGroupValue(value: string): void {
        this.grpValue = value;
    }

    setLayoutValue(value: LayoutMode): void {
        this.layoutValue = value;
    }

    getFunctionValue(): string {
        return this.fnValue;
    }

    getGroupValue(): string {
        return this.grpValue;
    }

    getLayoutValue(): LayoutMode {
        return this.layoutValue;
    }

    hasFunctionOption(value: string): boolean {
        return value === "all" || this.fnOptions.includes(value);
    }

    hasGroupOption(value: string): boolean {
        return value === "all" || this.grpOptions.includes(value);
    }

    private dispatchFilterChanged(): void {
        this.dispatchEvent(
            new CustomEvent<FilterChangedDetail>("filter-changed", {
                detail: { fn: this.fnValue, group: this.grpValue },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private dispatchLayoutChanged(): void {
        this.dispatchEvent(
            new CustomEvent<LayoutChangedDetail>("layout-changed", {
                detail: { layout: this.layoutValue },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onFnChange(e: Event): void {
        this.fnValue = (e.target as HTMLSelectElement).value;
        this.dispatchFilterChanged();
    }

    private onGrpChange(e: Event): void {
        this.grpValue = (e.target as HTMLSelectElement).value;
        this.dispatchFilterChanged();
    }

    private onLayoutChange(e: Event): void {
        this.layoutValue = (e.target as HTMLSelectElement).value as LayoutMode;
        this.dispatchLayoutChanged();
    }

    // Light DOM so existing `media/preview.css` `.toolbar` /
    // `.select-wrapper` / `.select-chevron` rules apply unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <div
                class="toolbar"
                id="toolbar"
                role="toolbar"
                aria-label="Preview filters"
            >
                <div class="select-wrapper">
                    <select
                        title="Filter by function"
                        aria-label="Function filter"
                        .value=${this.fnValue}
                        @change=${this.onFnChange}
                    >
                        <option value="all">All functions</option>
                        ${this.fnOptions.map(
                            (v) => html`<option value=${v}>${v}</option>`,
                        )}
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
                <div class="select-wrapper">
                    <select
                        title="Filter by @Preview group"
                        aria-label="Group filter"
                        .value=${this.grpValue}
                        @change=${this.onGrpChange}
                    >
                        <option value="all">All groups</option>
                        ${this.grpOptions.map(
                            (v) => html`<option value=${v}>${v}</option>`,
                        )}
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
                <div class="select-wrapper">
                    <select
                        title="Layout"
                        aria-label="Layout mode"
                        .value=${this.layoutValue}
                        @change=${this.onLayoutChange}
                    >
                        <option value="grid">Grid</option>
                        <option value="flow">Flow</option>
                        <option value="column">Column</option>
                        <option value="focus">Focus</option>
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
            </div>
        `;
    }
}
