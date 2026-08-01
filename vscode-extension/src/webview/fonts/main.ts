// Bundled entry for the "Google Fonts" browser webview panel.
//
// `<font-browser-app>` renders three regions in light DOM (so
// `media/fonts.css` and injected `<style>`/`@font-face` rules apply):
//
//   1. Search + category toolbar over the keyless Google Fonts catalog.
//      Each result row paints in its *real* typeface via a lazily
//      injected `fonts.googleapis.com/css2` <link>.
//   2. A "Downloaded" shelf — fonts the host cached under global
//      storage, rendered through `@font-face` rules pointing at the
//      cached files (`webview.asWebviewUri`).
//   3. A live customiser for the selected downloaded font: weight,
//      italic, size / letter-spacing / line-height, and per-axis
//      sliders for variable fonts, with a generated Compose snippet.
//
// The host owns all network + disk work; this bundle only renders and
// posts intent messages. See `protocol.ts` for the message shapes.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { getVsCodeApi } from "../shared/vscode";
import {
    buildCss2BrowseUrl,
    searchCatalog,
    type FontCatalog,
    type FontFamilyMeta,
} from "../../googleFontsCatalog";
import {
    buildAllFontFaceCss,
    cssVariationSettings,
    defaultAxisValues,
    pickFace,
    sliderAxes,
    webviewFamilyName,
    weightRange,
    type DownloadedFontView,
} from "./fontBrowserLogic";
import { generateComposeSnippet } from "./composeFontSnippet";
import type { HostToWebview } from "./protocol";

const DEFAULT_SPECIMEN = "The quick brown fox jumps over the lazy dog";

interface Customiser {
    weight: number;
    italic: boolean;
    fontSizeSp: number;
    letterSpacingSp: number;
    lineHeightSp: number;
    axisValues: Record<string, number>;
}

@customElement("font-browser-app")
export class FontBrowserApp extends LitElement {
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    private readonly vscode = getVsCodeApi<unknown>();

    @state() private catalog: FontCatalog | null = null;
    @state() private catalogError: string | null = null;
    @state() private query = "";
    @state() private category = "all";
    @state() private downloaded: DownloadedFontView[] = [];
    @state() private downloading = new Set<string>();
    @state() private selectedId: string | null = null;
    @state() private specimen = DEFAULT_SPECIMEN;
    @state() private customiser: Customiser | null = null;
    @state() private copied = false;

    /** Families we've already injected a browse-preview <link> for. */
    private readonly linkedFamilies = new Set<string>();

    connectedCallback(): void {
        super.connectedCallback();
        window.addEventListener("message", this.onMessage);
    }

    disconnectedCallback(): void {
        window.removeEventListener("message", this.onMessage);
        super.disconnectedCallback();
    }

    protected firstUpdated(): void {
        this.post({ command: "ready" });
    }

    private readonly onMessage = (ev: MessageEvent): void => {
        const msg = ev.data as HostToWebview;
        if (!msg || typeof msg !== "object") return;
        switch (msg.command) {
            case "catalog":
                this.catalog = msg.catalog;
                this.catalogError = null;
                break;
            case "catalogLoading":
                this.catalogError = null;
                break;
            case "catalogError":
                this.catalogError = msg.message;
                break;
            case "downloaded":
                this.downloaded = msg.fonts;
                this.syncFontFaces();
                // Drop a selection that no longer exists.
                if (
                    this.selectedId &&
                    !msg.fonts.some((f) => f.familyId === this.selectedId)
                ) {
                    this.selectedId = null;
                    this.customiser = null;
                }
                break;
            case "downloadState": {
                const next = new Set(this.downloading);
                if (msg.state === "downloading") next.add(msg.familyId);
                else next.delete(msg.familyId);
                this.downloading = next;
                break;
            }
        }
    };

    private post(msg: import("./protocol").WebviewToHost): void {
        this.vscode.postMessage(msg);
    }

    // ---- browse-preview <link> injection --------------------------------

    private ensureBrowseLink(family: string): void {
        if (this.linkedFamilies.has(family)) return;
        this.linkedFamilies.add(family);
        const link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = buildCss2BrowseUrl(family);
        document.head.appendChild(link);
    }

    // ---- downloaded @font-face injection --------------------------------

    private syncFontFaces(): void {
        let styleEl = document.getElementById(
            "gfb-font-faces",
        ) as HTMLStyleElement | null;
        if (!styleEl) {
            styleEl = document.createElement("style");
            styleEl.id = "gfb-font-faces";
            document.head.appendChild(styleEl);
        }
        styleEl.textContent = buildAllFontFaceCss(this.downloaded);
    }

    // ---- actions --------------------------------------------------------

    private onDownload(meta: FontFamilyMeta): void {
        const id = slug(meta.family);
        if (this.downloading.has(id) || this.isDownloaded(id)) return;
        const next = new Set(this.downloading);
        next.add(id);
        this.downloading = next;
        this.post({ command: "download", family: meta.family });
    }

    private onRemove(font: DownloadedFontView): void {
        this.post({ command: "removeDownloaded", familyId: font.familyId });
    }

    private onSelect(font: DownloadedFontView): void {
        this.selectedId = font.familyId;
        const range = weightRange(font);
        this.customiser = {
            weight: clamp(400, range.min, range.max),
            italic: false,
            fontSizeSp: 32,
            letterSpacingSp: 0,
            lineHeightSp: 40,
            axisValues: defaultAxisValues(font.axes),
        };
        this.copied = false;
    }

    private updateCustomiser(patch: Partial<Customiser>): void {
        if (!this.customiser) return;
        this.customiser = { ...this.customiser, ...patch };
        this.copied = false;
    }

    private updateAxis(tag: string, value: number): void {
        if (!this.customiser) return;
        this.customiser = {
            ...this.customiser,
            axisValues: { ...this.customiser.axisValues, [tag]: value },
        };
        this.copied = false;
    }

    private isDownloaded(familyId: string): boolean {
        return this.downloaded.some((f) => f.familyId === familyId);
    }

    private get selected(): DownloadedFontView | null {
        return (
            this.downloaded.find((f) => f.familyId === this.selectedId) ?? null
        );
    }

    private copySnippet(): void {
        const font = this.selected;
        const c = this.customiser;
        if (!font || !c) return;
        this.post({ command: "copySnippet", text: this.snippetFor(font, c) });
        this.copied = true;
    }

    private snippetFor(font: DownloadedFontView, c: Customiser): string {
        return generateComposeSnippet({
            family: font.family,
            weight: Math.round(c.weight),
            italic: c.italic,
            isVariable: font.isVariable,
            axisValues: c.axisValues,
            axes: font.axes,
            fontSizeSp: c.fontSizeSp,
            letterSpacingSp: c.letterSpacingSp,
            lineHeightSp: c.lineHeightSp,
        });
    }

    // ---- render ---------------------------------------------------------

    protected render(): TemplateResult {
        return html`
            ${this.renderToolbar()} ${this.renderDownloaded()}
            ${this.renderCustomiser()} ${this.renderResults()}
        `;
    }

    private renderToolbar(): TemplateResult {
        const categories = this.catalog?.categories ?? [];
        return html`
            <div class="gfb-toolbar" role="toolbar" aria-label="Font search">
                <input
                    class="gfb-search"
                    type="search"
                    placeholder="Search Google Fonts…"
                    .value=${this.query}
                    aria-label="Search fonts"
                    @input=${(e: Event) =>
                        (this.query = (e.target as HTMLInputElement).value)}
                />
                <select
                    class="gfb-category"
                    aria-label="Category"
                    .value=${this.category}
                    @change=${(e: Event) =>
                        (this.category = (e.target as HTMLSelectElement).value)}
                >
                    <option value="all">All categories</option>
                    ${categories.map(
                        (c) => html`<option value=${c}>${c}</option>`,
                    )}
                </select>
                <button
                    class="gfb-btn"
                    title="Reload the catalog from Google Fonts"
                    @click=${() => this.post({ command: "refreshCatalog" })}
                >
                    Reload
                </button>
            </div>
        `;
    }

    private renderResults(): TemplateResult {
        if (this.catalogError) {
            return html`<div class="gfb-message gfb-error">
                Couldn't load the Google Fonts catalog: ${this.catalogError}
                <button
                    class="gfb-btn"
                    @click=${() => this.post({ command: "refreshCatalog" })}
                >
                    Retry
                </button>
            </div>`;
        }
        if (!this.catalog) {
            return html`<div class="gfb-message">Loading catalog…</div>`;
        }
        const results = searchCatalog(this.catalog, {
            query: this.query,
            category: this.category,
            limit: 80,
        });
        if (results.length === 0) {
            return html`<div class="gfb-message">No fonts match.</div>`;
        }
        return html`
            <h2 class="gfb-section-title">
                Browse
                <span class="gfb-count">${results.length} shown</span>
            </h2>
            <div class="gfb-results" role="list">
                ${results.map((meta) => this.renderResultRow(meta))}
            </div>
        `;
    }

    private renderResultRow(meta: FontFamilyMeta): TemplateResult {
        this.ensureBrowseLink(meta.family);
        const id = slug(meta.family);
        const downloaded = this.isDownloaded(id);
        const busy = this.downloading.has(id);
        return html`
            <div class="gfb-row" role="listitem">
                <div class="gfb-row-main">
                    <div
                        class="gfb-specimen-line"
                        style="font-family: '${meta.family}', sans-serif"
                    >
                        ${meta.family}
                    </div>
                    <div class="gfb-row-meta">
                        <span class="gfb-tag">${meta.category}</span>
                        ${
                            meta.isVariable
                                ? html`<span class="gfb-tag gfb-variable"
                                      >variable · ${meta.axes.length} axes</span
                                  >`
                                : html`<span class="gfb-tag"
                                      >${meta.weights.length} weights</span
                                  >`
                        }
                        ${
                            meta.hasItalic
                                ? html`<span class="gfb-tag">italic</span>`
                                : nothing
                        }
                    </div>
                </div>
                <div class="gfb-row-actions">
                    <button
                        class="gfb-link"
                        title="Open on fonts.google.com"
                        @click=${() =>
                            this.post({
                                command: "openExternal",
                                url: `https://fonts.google.com/specimen/${encodeURIComponent(
                                    meta.family.replace(/ /g, "+"),
                                )}`,
                            })}
                    >
                        Specimen ↗
                    </button>
                    ${
                        downloaded
                            ? html`<span class="gfb-downloaded-pill"
                                  >Downloaded</span
                              >`
                            : html`<button
                                  class="gfb-btn gfb-primary"
                                  ?disabled=${busy}
                                  @click=${() => this.onDownload(meta)}
                              >
                                  ${busy ? "Downloading…" : "Download"}
                              </button>`
                    }
                </div>
            </div>
        `;
    }

    private renderDownloaded(): TemplateResult {
        if (this.downloaded.length === 0) return html``;
        return html`
            <h2 class="gfb-section-title">
                Downloaded
                <span class="gfb-count">${this.downloaded.length}</span>
            </h2>
            <div class="gfb-downloaded" role="list">
                ${this.downloaded.map((font) => {
                    const active = font.familyId === this.selectedId;
                    return html`<button
                        class="gfb-chip ${active ? "gfb-chip-active" : ""}"
                        role="listitem"
                        @click=${() => this.onSelect(font)}
                        style="font-family: '${webviewFamilyName(
                            font.familyId,
                        )}', sans-serif"
                    >
                        ${font.family}
                    </button>`;
                })}
            </div>
        `;
    }

    private renderCustomiser(): TemplateResult {
        const font = this.selected;
        const c = this.customiser;
        if (!font || !c) return html``;
        const range = weightRange(font);
        const previewFamily = webviewFamilyName(font.familyId);
        const face = pickFace(font, Math.round(c.weight), c.italic);
        const variation = font.isVariable
            ? cssVariationSettings({
                  ...c.axisValues,
                  wght: Math.round(c.weight),
              })
            : "normal";
        const previewStyle = [
            `font-family: '${previewFamily}', sans-serif`,
            `font-weight: ${Math.round(c.weight)}`,
            `font-style: ${c.italic ? "italic" : "normal"}`,
            `font-size: ${c.fontSizeSp}px`,
            `letter-spacing: ${c.letterSpacingSp}px`,
            `line-height: ${c.lineHeightSp}px`,
            `font-variation-settings: ${variation}`,
        ].join("; ");

        return html`
            <div class="gfb-customiser">
                <div class="gfb-customiser-head">
                    <h2 class="gfb-section-title">${font.family}</h2>
                    <div class="gfb-customiser-head-actions">
                        ${
                            face
                                ? nothing
                                : html`<span class="gfb-warn"
                                      >no matching face</span
                                  >`
                        }
                        <button
                            class="gfb-btn"
                            @click=${() => this.onRemove(font)}
                        >
                            Remove
                        </button>
                    </div>
                </div>

                <textarea
                    class="gfb-specimen-input"
                    aria-label="Specimen text"
                    .value=${this.specimen}
                    @input=${(e: Event) =>
                        (this.specimen = (
                            e.target as HTMLTextAreaElement
                        ).value)}
                ></textarea>

                <div class="gfb-preview" style=${previewStyle}>
                    ${this.specimen || DEFAULT_SPECIMEN}
                </div>

                <div class="gfb-controls">
                    ${this.renderSlider(
                        "Weight",
                        c.weight,
                        range.min,
                        range.max,
                        1,
                        (v) => this.updateCustomiser({ weight: v }),
                        Math.round(c.weight).toString(),
                    )}
                    <label class="gfb-control gfb-checkbox">
                        <input
                            type="checkbox"
                            .checked=${c.italic}
                            ?disabled=${!font.faces.some(
                                (f) => f.style === "italic",
                            )}
                            @change=${(e: Event) =>
                                this.updateCustomiser({
                                    italic: (e.target as HTMLInputElement)
                                        .checked,
                                })}
                        />
                        Italic
                    </label>
                    ${this.renderSlider(
                        "Size",
                        c.fontSizeSp,
                        8,
                        96,
                        1,
                        (v) => this.updateCustomiser({ fontSizeSp: v }),
                        `${c.fontSizeSp}sp`,
                    )}
                    ${this.renderSlider(
                        "Letter spacing",
                        c.letterSpacingSp,
                        -2,
                        10,
                        0.1,
                        (v) => this.updateCustomiser({ letterSpacingSp: v }),
                        `${c.letterSpacingSp.toFixed(1)}sp`,
                    )}
                    ${this.renderSlider(
                        "Line height",
                        c.lineHeightSp,
                        8,
                        120,
                        1,
                        (v) => this.updateCustomiser({ lineHeightSp: v }),
                        `${c.lineHeightSp}sp`,
                    )}
                    ${sliderAxes(font).map((axis) =>
                        this.renderSlider(
                            `${axis.displayName} (${axis.tag})`,
                            c.axisValues[axis.tag] ?? axis.defaultValue,
                            axis.min,
                            axis.max,
                            (axis.max - axis.min) / 100 || 1,
                            (v) => this.updateAxis(axis.tag, v),
                            fmtAxis(
                                c.axisValues[axis.tag] ?? axis.defaultValue,
                            ),
                        ),
                    )}
                </div>

                <div class="gfb-snippet-head">
                    <span>Compose</span>
                    <button class="gfb-btn" @click=${() => this.copySnippet()}>
                        ${this.copied ? "Copied!" : "Copy"}
                    </button>
                </div>
                <pre class="gfb-snippet"><code>${this.snippetFor(
                    font,
                    c,
                )}</code></pre>
            </div>
        `;
    }

    private renderSlider(
        label: string,
        value: number,
        min: number,
        max: number,
        step: number,
        onChange: (v: number) => void,
        display: string,
    ): TemplateResult {
        return html`<label class="gfb-control">
            <span class="gfb-control-label"
                >${label}<span class="gfb-control-value">${display}</span></span
            >
            <input
                type="range"
                min=${min}
                max=${max}
                step=${step}
                .value=${String(value)}
                @input=${(e: Event) =>
                    onChange(Number((e.target as HTMLInputElement).value))}
            />
        </label>`;
    }
}

function slug(family: string): string {
    return (
        family
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, "-")
            .replace(/^-+|-+$/g, "") || "font"
    );
}

function clamp(value: number, min: number, max: number): number {
    return Math.max(min, Math.min(max, value));
}

function fmtAxis(value: number): string {
    return Number.isInteger(value) ? String(value) : value.toFixed(1);
}
