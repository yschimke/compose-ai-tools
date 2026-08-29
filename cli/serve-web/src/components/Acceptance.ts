// `<cp-acceptance>` — what this catalog has accepted about the comparison on screen, and what that
// leaves unaccepted.
//
// The band is the visible half of `compose-preview-known-differences/v1`. It renders three numbers
// and a row per acceptance, and the rules behind every one of them live in the shared engine next
// door (`src/parity/acceptance.ts` → `scripts/design-artifacts/`), not here. This file decides only
// what a reader is shown.
//
// Three numbers because they answer three questions and none is the difference of the others:
//
//   - **raw** is the pair as though nothing had been accepted. It is never hidden. That is the
//     epic's requirement and the reason acceptance is not an ignore rectangle: an accepted
//     difference is *moved*, not deleted.
//   - **unaccepted** is everything outside the surviving masks — what still needs looking at.
//   - **accepted** is the accepted region's own match, on the same scale and explicitly not
//     comparable to the other two by subtraction. A reader wanting "what did acceptance buy" reads
//     `unaccepted` against `raw`, which is a signed effect and can legitimately go either way.
//
// And a row per acceptance, because a single aggregate cannot express a mixed-validity set. An
// acceptance that is `invalidated` or `refused` suppresses nothing and is the row worth reading:
// it is a known difference that has stopped matching what was recorded, which is the whole
// lifecycle signal this workflow exists to produce.

import { ControllerElement, customElement } from "../controllerElement.js";
import { Fragment, h, render, type VNode, type VNodeChild } from "vue";
import {
    evaluateComparison,
    type AcceptanceReport,
    type AcceptanceStatus,
} from "../parity/acceptance.js";
import { whenParsed } from "../dom/whenParsed.js";

/** The payload the page embeds. Mirrors `KnownDifferenceContext` on the server. */
interface Payload {
    documentUrl: string;
    artifactBase: string;
    artifactQuery: string;
    referenceUrl: string;
    candidateUrl: string;
    issues: Array<{
        repository: string;
        number: number;
        state: "open" | "closed";
    }>;
    scope: {
        system: string;
        component: string;
        previewId: string;
        referenceId: string;
        variant: string;
        overrides: Record<string, string>;
        referenceSha256: string | null;
        tagIndex: Record<string, { count: number; bounds?: unknown }>;
    };
}

/**
 * How each status reads, and whether it is something to act on.
 *
 * `refused` and the four invalidation causes are deliberately *not* collapsed into one "problem"
 * word. They are different problems with different fixes — a broken artifact, a moved element, a
 * changed reference — and a band that said only "not applied" would leave the reader to re-derive
 * which from the record.
 */
const STATUS_LABELS: Record<string, string> = {
    valid: "accepted",
    resolved:
        "appears resolved — the difference is gone and the acceptance can be removed",
    invalidated: "no longer matches — needs review",
    refused: "refused",
    "out-of-scope": "authored for another comparison",
};

@customElement("cp-acceptance")
export class Acceptance extends ControllerElement {
    private report: AcceptanceReport | null = null;
    private failed = false;

    private band: HTMLElement | null = null;
    private installed = false;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    /**
     * Find the band and the payload, then evaluate.
     *
     * The band is server-rendered and starts `hidden`, like the result line beside it: the numbers
     * are the browser's, and a band that appeared with "0 accepted" before the engine had run would
     * be asserting something nobody had measured.
     */
    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const band = document.getElementById("cp-acceptance");
        const script = document.getElementById("cp-known-differences");
        if (!band || !script) return false;
        this.installed = true;
        this.band = band;
        let payload: Payload;
        try {
            payload = JSON.parse(script.textContent ?? "") as Payload;
        } catch {
            return true;
        }
        void this.evaluate(payload);
        return true;
    }

    private async evaluate(payload: Payload): Promise<void> {
        try {
            this.report = await evaluateComparison(
                {
                    documentUrl: payload.documentUrl,
                    artifactUrl: (path) =>
                        `${payload.artifactBase}${path}${payload.artifactQuery}`,
                    referenceUrl: payload.referenceUrl,
                    candidateUrl: payload.candidateUrl,
                },
                payload.scope,
                payload.scope.tagIndex,
                payload.issues ?? [],
            );
        } catch {
            // An engine that threw has said nothing about the catalog, so the band says nothing
            // either — rather than reporting a clean comparison it never evaluated, which is the
            // one failure mode a suppression model must not have.
            this.failed = true;
        }
        this.paint();
    }

    /**
     * Render into the server's band.
     *
     * Into it rather than into this element, for the reason `<cp-backend-badge>` does the same: the
     * `role="status"` live region is in the server's HTML, and a live region created by script with
     * its text already in place is not announced.
     */
    private paint(): void {
        if (!this.band) return;
        const content = this.content();
        if (content === null) {
            this.band.hidden = true;
            render(null, this.band);
            return;
        }
        this.band.hidden = false;
        render(content, this.band);
    }

    private content(): VNode | null {
        if (this.failed) {
            return h(
                "span",
                { class: "cp-acceptance-note" },
                "Known differences could not be evaluated.",
            );
        }
        const report = this.report;
        if (!report) return null;
        // **`unavailable` is not `absent`.** A document the page could not fetch is a page that
        // measured nothing, and hiding the band there would read as "nothing is accepted here" — a
        // clean bill of health for a comparison nobody evaluated.
        if (report.state === "unavailable") {
            return h(
                "span",
                { class: "cp-acceptance-note" },
                "This catalog publishes known differences, and they could not be fetched — nothing on this comparison has been evaluated against them.",
            );
        }
        if (report.state === "absent") return null;

        const rows = Object.entries(report.statuses).filter(
            ([, entry]) => entry.status !== "out-of-scope",
        );
        // **A stalled comparison is not an empty one.** With no pair the engine runs its
        // validation-only pass and every in-scope acceptance comes back `out-of-scope` — the same
        // token a record authored elsewhere gets, and the one filtered out just above. So a
        // transient 503 on the render lane, or a reference whose bytes no longer match the digest
        // this page was built from, would otherwise hide the band exactly as if the catalog had
        // nothing to say here. It has; it could not be asked.
        const stalled =
            report.pair === "unavailable" &&
            Object.keys(report.statuses).length > 0;
        // Every acceptance in the document belongs to some other comparison: this page has nothing
        // to say, and saying "0 accepted" on it would be noise on every comparison in the catalog.
        if (
            rows.length === 0 &&
            report.validationFailures.length === 0 &&
            !stalled
        ) {
            return null;
        }

        return h(Fragment, null, [
            this.scores(report),
            this.documentFailures(report),
            h(
                "ul",
                { class: "cp-acceptance-list" },
                rows.map(([id, entry]) => this.row(id, entry)),
            ),
        ]);
    }

    /**
     * The failures that belong to the **document** rather than to any acceptance.
     *
     * A document that is malformed, carries a duplicated id, or is past its size ceiling is rejected
     * wholesale: the engine returns no `statuses` at all and reports the reason only through
     * `validationFailures`. Without this the band showed scores above an empty list, which reads as
     * "this catalog accepts nothing here" rather than "this catalog's acceptance document was
     * refused" — and those are the same picture with opposite meanings.
     *
     * **Which failures those are is the engine's own answer, not a guess from their shape.** A
     * document-level rejection is the one that omits `statuses`, and `duplicate-id` — the loudest of
     * them — is deliberately attributed to the first spelling seen, so it carries an `id` exactly
     * like a per-record refusal does. Selecting on that `id` dropped it, and a duplicated document
     * then showed scores over an empty list with nothing explaining that none of it had been
     * applied.
     */
    private documentFailures(report: AcceptanceReport): VNode | null {
        if (!report.documentRejected) return null;
        // Attributed where the engine attributed it: `duplicate-id (glyph)` names the spelling to go
        // and look at, and `id-missing (#2)` the record that has no name to be called by.
        const reasons = report.validationFailures.map((failure) => {
            if (failure.id !== undefined)
                return `${failure.reason} (${failure.id})`;
            if (failure.index !== undefined)
                return `${failure.reason} (#${failure.index})`;
            return failure.reason;
        });
        if (reasons.length === 0) return null;
        return h(
            "span",
            { class: "cp-acceptance-row", "data-status": "refused" },
            `This catalog's known-difference document was refused (${reasons.join(", ")}), so nothing in it is being applied.`,
        );
    }

    private scores(report: AcceptanceReport): VNode | null {
        const scores = report.scores;
        if (!scores) {
            // Said plainly, because the alternative reading is the dangerous one: no scores here
            // means nothing on this comparison was measured against the catalog's known differences,
            // not that there was nothing to measure.
            return h(
                "span",
                { class: "cp-acceptance-note" },
                report.pair === "unavailable"
                    ? "The rendered pair could not be read as this page describes it, so nothing on this comparison has been evaluated against this catalog's known differences. Reloading usually resolves it."
                    : "This pair could not be scored, so only the acceptance verdicts are shown.",
            );
        }
        // `raw` first and always, because it is the number that must never be hidden.
        //
        // It used to carry a disclaimer, and no longer needs one. Acceptance has always been
        // measured with the portable kernel — an area average both engines can reproduce — while
        // the result line above came from the browser's own `drawImage` filter, which no offline
        // engine can; the two differed slightly, and this band said so rather than leaving a reader
        // to discover two numbers for one question. The rebaseline (D3) made the portable path the
        // live scorer, so both numbers are now one pixel path: measured over the eleven committed
        // `renders/lane-parity` pairs the two agree to 0.007pp, which is well inside the one decimal
        // either of them prints.
        return h("span", { class: "cp-acceptance-scores" }, [
            h("strong", null, `${scores.raw.toFixed(1)}%`),
            " raw · ",
            h("strong", null, `${scores.unaccepted.toFixed(1)}%`),
            " unaccepted · ",
            h("strong", null, `${scores.accepted.toFixed(1)}%`),
            " over the accepted region",
        ]);
    }

    private row(id: string, entry: AcceptanceStatus): VNode {
        const label = STATUS_LABELS[entry.status] ?? entry.status;
        // The causes and reasons are the engine's own tokens, shown rather than translated: they are
        // what an author greps for, and a friendlier paraphrase would be a second vocabulary for the
        // same set.
        const detail = [...(entry.causes ?? []), ...(entry.reasons ?? [])];
        const lifecycle = this.report?.lifecycles[id];
        const lifecycleLabel = lifecycle?.stale
            ? "stale configuration — the issue is closed while this acceptance is still live"
            : lifecycle?.lifecycle === "closed" && entry.status === "resolved"
              ? "verified; issue closed; remove the acceptance"
              : lifecycle?.lifecycle === "closed"
                ? "issue closed"
                : lifecycle?.lifecycle === "open"
                  ? "issue open"
                  : "issue state unknown";
        const children: VNodeChild[] = [h("code", null, id), ` — ${label}`];
        if (detail.length > 0) children.push(` (${detail.join(", ")})`);
        children.push(` · ${lifecycleLabel}`);
        return h(
            "li",
            {
                class: "cp-acceptance-row",
                "data-status": entry.status,
                "data-lifecycle": lifecycle?.lifecycle ?? "unknown",
                "data-stale": lifecycle?.stale ? "" : undefined,
            },
            children,
        );
    }
}
