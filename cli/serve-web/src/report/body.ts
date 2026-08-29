// The one writer of the prefilled report's hidden `body` input.
//
// Three things reach that field and none of them knows about the others: the render URL (derivable
// the moment the page parses), the browser scorer's measurements (when it finishes, if it does),
// and the element or region the reporter selected (whenever they click, which may be before OR
// after the scorer lands). Each used to be — or would have become — a `body.value = …` of its own,
// and the last one to run would win: filing a report after selecting an element would drop the
// scores, and scoring after selecting would drop the selection. Neither loss shows up anywhere
// except in the filed issue.
//
// So the field has exactly one writer, holding the template and the latest of each input, and every
// producer hands it a fragment instead of a whole body.
//
// The value is written to a hidden INPUT and nowhere else — never to an `href` or any other
// navigation sink. That is what keeps the report a GET form rather than a CodeQL finding; see
// `ServeIssueReport.action`.

import { fillReport, needsRender } from "../annotate/report.js";
import { withClassification } from "./classification.js";
import { fillLocators, fillSelection, type Selection } from "./locator.js";

/** What the page knows so far. Every field is independently optional. */
export interface ReportInputs {
    /** Token-stripped `/render/<id>.png` at the settings on screen. */
    render?: string;
    /** The scorer's sentence, or null while it has not produced one. */
    scores?: string | null;
    selection?: Selection;
    /**
     * The classification sentence `<cp-report-classification>` last wrote, if any.
     *
     * A fourth producer, and here for the reason the other three are: this writer composes the body
     * from the template every time any of them reports in, so a classification applied to the
     * field directly would be silently undone by the next score or selection. Passing it through
     * keeps the rule that exactly one thing writes the field.
     */
    classification?: string;
    /**
     * One `compose-parity-locator/v1` block per comparison the reader ticked on the wall.
     *
     * The fifth producer, and the only one that adds identity rather than detail: a page-scoped
     * report names no preview until somebody picks some, and these are what turn it into an
     * umbrella issue the catalog's index can join to rows. Empty — the state this starts in — fills
     * the template's placeholder with nothing, reproducing the body the server wrote.
     */
    locators?: string[];
}

export class ReportBody {
    private input: HTMLInputElement | null = null;
    private template = "";
    private state: ReportInputs = { scores: null, selection: {} };

    /**
     * Take over [input], whose `data-report-template` carries the body with its placeholders.
     *
     * Returns false — and leaves the field entirely alone — when there is no template. A page
     * without one is a page whose server wrote a complete body already; overwriting it from here
     * with a half-filled one would be strictly worse than doing nothing.
     *
     * Attaching **resets** what has been learned. This is a singleton (one report form per page)
     * and taking over a different field means a different comparison: carrying the previous one's
     * scores or selection across would file a report describing the preview you just left.
     */
    attach(input: HTMLInputElement | null): boolean {
        const template = input?.getAttribute("data-report-template");
        if (!input || !template) return false;
        // Re-attaching the SAME field is not a different comparison, so it keeps what has been
        // learned. Two elements claim the field on the focused comparison now — the comparison
        // itself and the classification control — and whichever ran second would otherwise reset
        // the other's contribution the moment it arrived.
        if (input === this.input) return true;
        this.input = input;
        this.template = template;
        this.state = { scores: null, selection: {} };
        return true;
    }

    /** Merge in what one producer has learned, and rewrite the field. */
    set(next: ReportInputs): void {
        this.state = { ...this.state, ...next };
        this.write();
    }

    private write(): void {
        const { input, template } = this;
        if (!input || !template) return;
        // No render URL yet means the page has not finished parsing its own panels. The server's
        // body is already in the field and is correct; there is nothing to improve on — and the
        // template's `{{render}}` would be filed verbatim, which is worse than waiting. Asked of
        // the template rather than assumed, because the comparison wall's page-scoped report names
        // no render at all and would otherwise never be composable — so a picked set of rows could
        // never reach the body.
        if (needsRender(template) && !this.state.render) return;
        input.value = withClassification(
            fillLocators(
                fillSelection(
                    fillReport(
                        template,
                        this.state.render ?? "",
                        this.state.scores ?? null,
                    ),
                    this.state.selection ?? {},
                ),
                this.state.locators ?? [],
            ),
            this.state.classification ?? "",
        );
    }
}

/**
 * The page's report field. A module singleton because there is exactly one report form per page and
 * the producers that feed it are separate custom elements with no parent to hold it for them.
 */
export const reportBody = new ReportBody();
