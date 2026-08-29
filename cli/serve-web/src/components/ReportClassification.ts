// `<cp-report-classification>` — keeps the issue body's "Where it belongs" line in step with the
// answer chosen in the report form's classification `<select>`.
//
// The choice itself needs no script. The control is a `<select name="labels">` inside a GET form
// pointed at GitHub's new-issue page, so its value IS the `labels` query parameter and the
// `parity:` label is applied with or without this element — see
// `ServeWeb.reportClassificationHtml`, which explains why that is the transport rather than
// something the page assembles. What this adds is the same answer in the body's prose, so a
// triager reading the issue sees it without opening the label list, and so a repository that has
// no such label to apply still gets told.
//
// The element ADOPTS server-rendered markup rather than rendering its own, the opposite of
// `<cp-bg-toggle>` next door, and for the reason that one gives for its own choice: this control
// does something real with JavaScript off, and there is no no-JS rendering to throw away.
//
// Light DOM, so `serve.css`'s `.cp-report-class` rules apply to the markup the server wrote.

import { ControllerElement, customElement } from "../controllerElement.js";
import { reportBody } from "../report/body.js";
import { withClassification } from "../report/classification.js";
import { whenParsed } from "../dom/whenParsed.js";

@customElement("cp-report-classification")
export class ReportClassification extends ControllerElement {
    private select: HTMLSelectElement | null = null;
    private field: HTMLInputElement | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        // The `<select>` is this element's own child, but the body field is not — it is a sibling
        // further down the same form, so on a page still being parsed it may not exist yet.
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    private install(): boolean {
        if (!this.isConnected) return false;
        if (this.select) return true;
        const select = this.querySelector("select");
        const field =
            this.closest("form")?.querySelector<HTMLInputElement>(
                'input[name="body"]',
            ) ?? null;
        if (!select || !field) return false;
        this.select = select;
        this.field = field;
        this.listen(select, "change", () => this.apply());
        // Applied at once, not only on change: the default answer is a real answer — it is what the
        // form will submit as the label if nobody touches the control — and a body that still
        // pointed at the label while the select said something specific would be the one
        // disagreement this line exists to prevent.
        this.apply();
        return true;
    }

    override disconnectedCallback(): void {
        this.select = null;
        this.field = null;
        super.disconnectedCallback();
    }

    /**
     * Write the chosen sentence into the body, both ways round.
     *
     * The field is edited directly AND the choice is handed to [reportBody], because which of those
     * is the one that matters depends on the page. On the focused comparison the body is recomposed
     * from its template every time the scorer or the element selector reports in, so a direct edit
     * alone would be undone by the next one; everywhere else nothing recomposes it and there is no
     * render URL for the template path to use, so the direct edit is the only writer there will be.
     * Both spellings produce the same line, so whichever runs last is right.
     */
    private apply(): void {
        const { select, field } = this;
        if (!select || !field) return;
        // `options[selectedIndex]`, not `selectedOptions[0]`: the two agree in a browser, and
        // happy-dom leaves `selectedOptions` pointing at the previous choice when the value is set
        // programmatically — which is how the tests drive this, and would have made them pass a
        // sentence the control was no longer showing.
        const sentence =
            select.options[select.selectedIndex]?.getAttribute(
                "data-cp-sentence",
            ) ?? "";
        if (!sentence) return;
        // Guarded on the attach: a field with no template is one this writer does not own, and
        // handing the choice to it anyway would set state belonging to some other form's field.
        if (reportBody.attach(field))
            reportBody.set({ classification: sentence });
        field.value = withClassification(field.value, sentence);
    }
}
