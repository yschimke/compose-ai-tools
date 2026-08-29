// `<cp-acceptance-audit>` — the catalog-wide view of what this catalog has accepted, on the design
// parity dashboard.
//
// The band next door (`<cp-acceptance>`) evaluates **one comparison**, and that is the whole reason
// this one exists. An acceptance whose target no longer exists — a removed or renamed preview,
// reference, component or variant — is never scoped into any comparison, so a browser that only ever
// evaluates from inside one leaves `orphaned-target` permanently invisible while `design-parity`
// reports it for the same record. The rule that catches it is `walkCatalog`: the same engine, run
// once against the catalog's preview inventory with no comparison at all.
//
// **It runs the validation-only pass, and says only what that pass can support.** With no rasters
// there are no gates and no scores, so a record whose target exists comes back `out-of-scope` — the
// token that means "not evaluated here", not "valid". This band therefore reports two things and
// refuses to report a third:
//
//   - **refusals and orphans**, which are document facts and need no comparison to be true;
//   - **the lifecycle join** — an acceptance whose tracking issue is closed while the record is
//     still committed, which is the stale configuration §6 asks to surface;
//   - and **never a verdict**: whether an acceptance still matches its recorded difference is a
//     per-comparison answer, and it stays on the comparison page where the pixels are.

import { ControllerElement, customElement } from "../controllerElement.js";
import { Fragment, h, render, type VNode, type VNodeChild } from "vue";
import { walkCatalog, type AcceptanceReport } from "../parity/acceptance.js";
import type { Catalog } from "../parity/engine.js";
import { whenParsed } from "../dom/whenParsed.js";

/** The payload the dashboard embeds. Mirrors `KnownDifferenceAuditContext` on the server. */
interface Payload {
    documentUrl: string;
    artifactBase: string;
    artifactQuery: string;
    previews: Catalog["previews"];
    issues: Array<{
        repository: string;
        number: number;
        state: "open" | "closed";
    }>;
}

/**
 * How a walk's statuses read.
 *
 * `out-of-scope` is deliberately absent: in a validation-only pass it is every healthy record, and
 * naming it in a row would publish "authored for another comparison" against acceptances authored
 * for this catalog's own comparisons.
 */
const STATUS_LABELS: Record<string, string> = {
    refused: "refused",
    invalidated: "no longer matches — needs review",
    resolved: "appears resolved — the acceptance can be removed",
    valid: "accepted",
};

/**
 * The one reason worth a sentence rather than its token.
 *
 * `orphaned-target` is a **reason** under `refused`, not a status of its own — and it is the reason
 * this panel exists, so a row that spelled it as one more grep token beside `artifact-unreadable`
 * would bury the finding no comparison can reach. The rest stay verbatim: they are what an author
 * greps for, and a paraphrase per token would be a second vocabulary for the same set.
 */
const ORPHANED = "orphaned-target";

@customElement("cp-acceptance-audit")
export class AcceptanceAudit extends ControllerElement {
    private report: AcceptanceReport | null = null;
    private failed = false;

    private band: HTMLElement | null = null;
    private issueHref: Record<string, string> = {};
    private installed = false;
    private evaluation = 0;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const band = document.getElementById("cp-acceptance-audit");
        const script = document.getElementById("cp-known-difference-audit");
        if (!band || !script) return false;
        this.installed = true;
        this.band = band;
        let payload: Payload;
        try {
            payload = JSON.parse(script.textContent ?? "") as Payload;
        } catch {
            return true;
        }
        void this.evaluate(payload, ++this.evaluation);
        return true;
    }

    private async evaluate(
        payload: Payload,
        evaluation: number,
    ): Promise<void> {
        for (const issue of payload.issues ?? []) {
            this.issueHref[
                `${issue.repository.toLowerCase()}#${issue.number}`
            ] = `https://github.com/${issue.repository}/issues/${issue.number}`;
        }
        try {
            this.report = await walkCatalog(
                {
                    documentUrl: payload.documentUrl,
                    artifactUrl: (path) =>
                        `${payload.artifactBase}${path}${payload.artifactQuery}`,
                },
                { previews: payload.previews ?? [] },
                payload.issues ?? [],
            );
        } catch {
            // An engine that threw has audited nothing, and a panel that then rendered "0 problems"
            // would be a clean bill of health nobody measured.
            this.failed = true;
        }
        if (evaluation !== this.evaluation || !this.isConnected) return;
        this.paint();
    }

    override disconnectedCallback(): void {
        this.evaluation++;
        if (this.band) {
            render(null, this.band);
            this.band.hidden = true;
        }
        this.band = null;
        this.installed = false;
        this.report = null;
        this.failed = false;
        this.issueHref = {};
        super.disconnectedCallback();
    }

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
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    { class: "cp-muted" },
                    "This catalog's known differences could not be audited.",
                ),
            ]);
        }
        const report = this.report;
        if (!report) return null;
        // `unavailable` is not `absent`, for the reason the comparison band draws the same line: the
        // server only mounts this panel for a catalog that publishes a document, so "could not
        // fetch" must not render as "accepts nothing".
        if (report.state === "unavailable") {
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    { class: "cp-muted" },
                    "This catalog publishes known differences, and they could not be fetched — nothing here has been audited against them.",
                ),
            ]);
        }
        if (report.state === "absent") return null;
        if (report.documentRejected) {
            const reasons = report.validationFailures.map((failure) =>
                failure.id !== undefined
                    ? `${failure.reason} (${failure.id})`
                    : failure.index !== undefined
                      ? `${failure.reason} (#${failure.index})`
                      : failure.reason,
            );
            const reason = reasons.length > 0 ? ` (${reasons.join(", ")})` : "";
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    {
                        class: "cp-acceptance-row",
                        "data-status": "refused",
                    },
                    `This catalog's known-difference document was refused${reason}, so nothing in it is being applied on any comparison.`,
                ),
            ]);
        }

        const entries = Object.entries(report.statuses);
        if (entries.length === 0) return null;
        // Two axes, joined here and nowhere else: `status` is what the walk concluded, `lifecycle`
        // is what the published issue index says. A record can be in both lists — an orphan whose
        // issue also closed is two separate pieces of cleanup — and neither is derived from the
        // other.
        const problems = entries.filter(
            ([, entry]) => entry.status !== "out-of-scope",
        );
        const closed = entries.filter(
            ([id]) => report.lifecycles[id]?.lifecycle === "closed",
        );
        return h(Fragment, null, [
            h(
                "h2",
                { class: "cp-status-sec" },
                `Known differences (${entries.length})`,
            ),
            h(
                "p",
                { class: "cp-muted" },
                "Differences this catalog has accepted against a tracking issue. Each one is still measured on its own comparison — this panel is the document, not the verdict.",
            ),
            this.problems(problems),
            this.closed(closed, report),
            problems.length === 0 && closed.length === 0
                ? this.allClear(entries, report)
                : null,
        ]);
    }

    /**
     * The nothing-to-do line — and it says only as much as the evidence supports.
     *
     * **An unknown lifecycle is not an open one.** The index is fail-soft, capped, and can lag; an
     * acceptance it does not mention stays `unknown`, and that is missing evidence, not a live
     * issue. Reporting "every tracking issue is open" over a set containing one would turn an index
     * that failed to parse into a clean bill of health for a catalog whose acceptances might all be
     * stale — the same inference-from-absence the join itself refuses to make one level down.
     */
    private allClear(
        entries: Array<[string, AcceptanceReport["statuses"][string]]>,
        report: AcceptanceReport,
    ): VNode {
        const unknown = entries.filter(
            ([id]) =>
                (report.lifecycles[id]?.lifecycle ?? "unknown") !== "open",
        ).length;
        const suffix =
            unknown > 0
                ? ` The issue index says nothing about ${unknown} of them, so their issues are unknown rather than open.`
                : "";
        return h(
            "p",
            { class: "cp-muted" },
            `Every accepted difference names a component this catalog still has, and no tracking issue is reported closed.${suffix}`,
        );
    }

    /**
     * Refusals and orphans — the findings a validation-only pass can stand behind.
     *
     * `orphaned-target` is the one this panel exists for: no comparison scopes it in, so the
     * per-comparison band cannot show it at all.
     */
    private problems(
        rows: Array<[string, AcceptanceReport["statuses"][string]]>,
    ): VNode | null {
        if (rows.length === 0) return null;
        const items = rows.map(([id, entry]) => {
            const detail = [...(entry.causes ?? []), ...(entry.reasons ?? [])];
            const orphaned = detail.includes(ORPHANED);
            const rest = detail.filter((token) => token !== ORPHANED);
            const label = orphaned
                ? "names a preview, reference, component or variant this catalog no longer has"
                : (STATUS_LABELS[entry.status] ?? entry.status);
            const children: VNodeChild[] = [h("code", null, id), ` — ${label}`];
            if (rest.length > 0) children.push(` (${rest.join(", ")})`);
            return h(
                "li",
                {
                    class: "cp-acceptance-row",
                    "data-status": entry.status,
                    "data-orphaned": orphaned ? "" : undefined,
                },
                children,
            );
        });
        return h(Fragment, null, [
            h(
                "h3",
                { class: "cp-parity-sub" },
                `Needs attention (${rows.length})`,
            ),
            h("ul", { class: "cp-acceptance-list" }, items),
        ]);
    }

    /**
     * Acceptances whose tracking issue is closed while the record is still committed.
     *
     * **Positive evidence only.** An acceptance missing from the index stays `unknown` and is not
     * listed: the index is fail-soft, capped and can lag, and inferring closure from absence would
     * mark a whole catalog stale the first time the file failed to parse.
     */
    private closed(
        rows: Array<[string, AcceptanceReport["statuses"][string]]>,
        report: AcceptanceReport,
    ): VNode | null {
        if (rows.length === 0) return null;
        const items = rows.map(([id]) => {
            const issue = report.lifecycles[id]?.issue ?? null;
            const href = issue ? this.issueHref[issue] : undefined;
            const issueNode = href
                ? h("a", { href, rel: "noopener" }, issue ?? "")
                : issue
                  ? h("code", null, issue)
                  : "its tracking issue";
            return h(
                "li",
                {
                    class: "cp-acceptance-row",
                    "data-status": "stale",
                    "data-lifecycle": "closed",
                },
                [h("code", null, id), " — ", issueNode, " is closed"],
            );
        });
        return h(Fragment, null, [
            h(
                "h3",
                { class: "cp-parity-sub" },
                `Closed issue, acceptance still committed (${rows.length})`,
            ),
            h(
                "p",
                { class: "cp-muted" },
                "The loop finishes by deleting the acceptance in the same change that closes its issue. These are the halves left behind.",
            ),
            h("ul", { class: "cp-acceptance-list" }, items),
        ]);
    }
}
