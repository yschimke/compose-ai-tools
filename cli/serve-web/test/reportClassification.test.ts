// `<cp-report-classification>` and the one body line it owns.
//
// The label is the transport and needs no script (`ServeWeb.reportClassificationHtml`), so what is
// worth pinning here is the half that can silently go wrong: the body line agreeing with the
// control, and staying in agreement after the focused comparison recomposes the body from its
// template — the exact way an earlier producer's contribution used to be lost.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import { reportBody } from "../src/report/body.js";
import {
    CLASSIFICATION_PREFIX,
    withClassification,
} from "../src/report/classification.js";
import "../src/components/ReportClassification.js";

const UPSTREAM = "Upstream: the framework, not the catalog.";
const TRIAGE = "Needs investigating: the reporter could not tell.";

/** The server's body, as `ServeIssueReport.body` writes it. */
function body(
    line = `${CLASSIFICATION_PREFIX}as labelled on this issue`,
): string {
    return [
        "### What's wrong",
        "",
        "<!-- What did you expect to see, and what did you get? -->",
        "",
        "",
        line,
        "",
        "### Screenshot",
        "",
    ].join("\n");
}

function form(template?: string): HTMLInputElement {
    document.body.innerHTML = `
      <form class="cp-report-form">
        <cp-report-classification>
          <select name="labels">
            <option value="parity:upstream" data-cp-sentence="${UPSTREAM}">Upstream</option>
            <option value="parity:verification-needed" data-cp-sentence="${TRIAGE}" selected>Not sure</option>
          </select>
        </cp-report-classification>
        <input type="hidden" name="body" id="cp-report-body" value="${body()}"${
            template ? ` data-report-template="${template}"` : ""
        }>
      </form>`;
    return document.querySelector<HTMLInputElement>('input[name="body"]')!;
}

describe("withClassification", () => {
    it("restates the classification line and nothing else", () => {
        const filled = withClassification(body(), UPSTREAM);
        assert.equal(filled, body(`${CLASSIFICATION_PREFIX}${UPSTREAM}`));
    });

    it("leaves a body with no classification line alone", () => {
        const plain = "### What's wrong\n\nnothing to see\n";
        assert.equal(withClassification(plain, UPSTREAM), plain);
    });

    // An empty sentence is "the control told us nothing", and the server's own line — which points
    // at the label — is a better answer than an empty assertion replacing it.
    it("leaves the body alone when there is no sentence", () => {
        assert.equal(withClassification(body(), ""), body());
    });
});

describe("<cp-report-classification>", () => {
    afterEach(() => resetDom());

    it("states the default answer without waiting to be touched", async () => {
        const field = form();
        await flush();
        assert.ok(
            field.value.includes(`${CLASSIFICATION_PREFIX}${TRIAGE}`),
            field.value,
        );
    });

    it("follows the control", async () => {
        const field = form();
        await flush();
        const select = document.querySelector("select")!;
        select.value = "parity:upstream";
        select.dispatchEvent(new Event("change"));
        assert.ok(
            field.value.includes(`${CLASSIFICATION_PREFIX}${UPSTREAM}`),
            field.value,
        );
        assert.ok(!field.value.includes(TRIAGE), field.value);
    });

    // The focused comparison recomposes the body from its template whenever the scorer or the
    // element selector reports in. A classification written straight onto the field would be undone
    // by the next one of those, with nothing anywhere to notice.
    it("survives the body being recomposed from its template", async () => {
        const template = body(
            `${CLASSIFICATION_PREFIX}as labelled on this issue`,
        ).replace("### Screenshot", "[PNG]({{render}})\n\n### Screenshot");
        const field = form(template);
        await flush();
        const select = document.querySelector("select")!;
        select.value = "parity:upstream";
        select.dispatchEvent(new Event("change"));
        reportBody.set({ render: "https://preview.example/render/button.png" });
        assert.ok(
            field.value.includes(`${CLASSIFICATION_PREFIX}${UPSTREAM}`),
            field.value,
        );
        assert.ok(
            field.value.includes(
                "[PNG](https://preview.example/render/button.png)",
            ),
            field.value,
        );
    });

    it("listens only while connected and reinstalls after a move", async () => {
        const field = form();
        await flush();
        const element = document.querySelector("cp-report-classification")!;
        const select = element.querySelector("select")!;
        element.remove();

        field.value = body();
        select.value = "parity:upstream";
        select.dispatchEvent(new Event("change"));
        assert.ok(!field.value.includes(UPSTREAM), field.value);

        field.before(element);
        await flush();
        assert.ok(field.value.includes(UPSTREAM), field.value);
    });
});
