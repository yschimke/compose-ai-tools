// `<cp-parity-scores>` — the parity page's "Visual differences" band. Half of `assets/parity.js`.
//
// Every published render/reference pair is scored with the same edge-tolerant metric the focused
// comparison page uses, which is what turns this page from a coverage list into an issues view:
// without it, "mapped" reads as "matching". Four queues overlap image decoding so a large catalog
// stays responsive.
//
// The element now owns the whole band rather than filling in a server-rendered shell. That removes
// the two things the shell cost: `parity.js` built the issues table by hand-escaping strings into
// `innerHTML` — with an `esc()` that neutralised `<`, `>` and `&` but NOT `"`, while its output was
// interpolated straight into `href="…"` — and a page with JavaScript off was left showing
// "Checking 40 mapped comparison(s)…" forever, which is a lie rather than an absence. A Lit
// template escapes by construction, and an element that renders nothing until it has something to
// say leaves no false promise behind.
//
// The judgements — what counts as a finding, how a cell reads, what order the table is in, what
// the summary sentence says — live in `parity/findings.ts` as a table of cases.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    checkingOf,
    findingResult,
    geometryOf,
    isFinding,
    progressOf,
    scoreCell,
    sortFindings,
    summaryOf,
    unavailableCell,
    type Finding,
    type Measurement,
    type ScoreCell,
} from "../parity/findings.js";

/** The slice of `format-compare.js`'s global this band uses. */
interface CompareApi {
    scoreImageUrls(reference: string, actual: string): Promise<Measurement>;
}

declare global {
    interface Window {
        ComposePreviewCompare?: CompareApi;
    }
}

/** Four queues overlap image decoding without swamping a large catalog. */
const LANES = 4;

@customElement("cp-parity-scores")
export class ParityScores extends LitElement {
    @state() private status = "";
    @state() private findings: Finding[] | null = null;

    private rows: HTMLElement[] = [];

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    // The rows this scores live in the "All comparisons" table further down the page, so connect
    // time is too early to find them. See `dom/whenParsed.ts`.
    connectedCallback(): void {
        super.connectedCallback();
        void whenParsed().then(() => this.scan());
    }

    private async scan(): Promise<void> {
        const compare = window.ComposePreviewCompare;
        this.rows = Array.from(
            document.querySelectorAll<HTMLElement>("[data-parity-comparison]"),
        );
        // No scorer loaded, or nothing mapped to score: draw nothing at all. A heading over an
        // empty table says less than the absence does.
        if (!compare || !this.rows.length) return;

        this.status = checkingOf(this.rows.length);
        const found: Finding[] = [];
        let next = 0;
        let completed = 0;
        const worker = async (): Promise<void> => {
            for (let i = next++; i < this.rows.length; i = next++) {
                const finding = await this.score(compare, this.rows[i]);
                if (finding) found.push(finding);
                this.status = progressOf(++completed, this.rows.length);
            }
        };
        await Promise.all(Array.from({ length: LANES }, worker));

        this.findings = sortFindings(found);
        this.status = summaryOf(this.rows.length, found);
    }

    /** Score one pair, writing its cell, and return a finding when it is worth reporting. */
    private async score(
        compare: CompareApi,
        row: HTMLElement,
    ): Promise<Finding | null> {
        const name = row.dataset.name ?? "";
        const review = row.dataset.review ?? "";
        try {
            const measured = await compare.scoreImageUrls(
                row.dataset.reference ?? "",
                row.dataset.actual ?? "",
            );
            const geometry = geometryOf(measured);
            this.writeCell(row, scoreCell(measured));
            if (!isFinding(measured.percent, geometry)) return null;
            return {
                name,
                review,
                score: measured.percent,
                geometry,
                unavailable: false,
            };
        } catch {
            // A missing render or an image that will not decode. Expected on a catalog mid-publish,
            // and the pair is still worth listing — silently dropping it would report the page as
            // clean on exactly the components nobody can see.
            this.writeCell(row, unavailableCell());
            return {
                name,
                review,
                score: null,
                geometry: 0,
                unavailable: true,
            };
        }
    }

    /**
     * The per-row cell lives in the "All comparisons" table, which the server renders and this
     * element does not own — so this one write stays imperative. What it says is still decided in
     * `parity/findings.ts`, so the cell and the issues table can never disagree about whether a
     * pair passed.
     */
    private writeCell(row: HTMLElement, cell: ScoreCell): void {
        const target = row.querySelector<HTMLElement>(".cp-parity-score");
        if (!target) return;
        target.textContent = cell.text;
        target.className = `cp-parity-score ${cell.ok ? "cp-ok" : "cp-parity-missing"}`;
        if (cell.title) target.title = cell.title;
        else target.removeAttribute("title");
    }

    protected render(): TemplateResult | typeof nothing {
        if (!this.status) return nothing;
        const findings = this.findings;
        return html`<section
            class="cp-parity-visual-issues"
            id="cp-parity-visual-issues"
        >
            <h3 class="cp-parity-sub">Visual differences</h3>
            <p class="cp-muted" id="cp-parity-score-status">${this.status}</p>
            ${
                findings?.length
                    ? html`<div
                          class="cp-status-scroll"
                          id="cp-parity-score-results"
                      >
                          <table class="cp-table">
                              <thead>
                                  <tr>
                                      <th>Component</th>
                                      <th>Structural match</th>
                                      <th>Review</th>
                                  </tr>
                              </thead>
                              <tbody id="cp-parity-score-issues">
                                  ${findings.map((finding) => this.issue(finding))}
                              </tbody>
                          </table>
                      </div>`
                    : nothing
            }
        </section>`;
    }

    private issue(finding: Finding): TemplateResult {
        return html`<tr>
            <td>${finding.name}</td>
            <td class="cp-parity-missing">${findingResult(finding)}</td>
            <td><a href=${finding.review}>Compare</a></td>
        </tr>`;
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-parity-scores": ParityScores;
    }
}
