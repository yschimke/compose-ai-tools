// Behavioural contract for `<cp-revision-runs>`.
//
// The rules are pinned in `renderRuns.test.ts`; these cover what only the element can answer — that
// it stays quiet until the disclosure is opened, that it decorates the server's rows rather than
// replacing them, and that every failure leaves the revision menu exactly as it was.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/RevisionRuns.js";

const HEAD_A = "a".repeat(40);
const MIDDLE = "c".repeat(40);
const HEAD_B = "b".repeat(40);

const PAYLOAD = {
    revisions: 3,
    runs: [
        { head: HEAD_A, sourceSha: "d9628859", commits: 2 },
        { head: HEAD_B, sourceSha: "eede08a2", commits: 1 },
    ],
};

/** Swap in a fetch that answers once, and report what it was asked for. */
function stubFetch(response: { ok: boolean; body?: unknown }): {
    calls: string[];
} {
    const calls: string[] = [];
    Object.defineProperty(globalThis, "fetch", {
        configurable: true,
        value: (url: string) => {
            calls.push(String(url));
            return Promise.resolve({
                ok: response.ok,
                json: () => Promise.resolve(response.body ?? null),
            });
        },
    });
    return { calls };
}

/** The revision menu as the server renders it: a disclosure over rows stamped with their shas. */
async function mount(
    options: { runsUrl?: string | null; renderUrl?: string | null } = {},
): Promise<HTMLDetailsElement> {
    const {
        runsUrl = "/wear/api/render-runs/media",
        renderUrl = "/wear/render/media.png",
    } = options;
    const attrs = [
        runsUrl ? `data-runs-url="${runsUrl}"` : "",
        renderUrl ? `data-render-url="${renderUrl}"` : "",
    ].join(" ");
    document.body.innerHTML = `
      <details class="cp-revisions">
        <summary class="cp-revisions-btn">current</summary>
        <div class="cp-revisions-menu">
          <cp-revision-runs ${attrs}></cp-revision-runs>
          <nav class="cp-revision-list">
            <a class="cp-revision" href="/p/media" data-revision="${HEAD_A}">newest</a>
            <a class="cp-revision" href="/p/media?at=${MIDDLE}" data-revision="${MIDDLE}">middle</a>
            <a class="cp-revision" href="/p/media?at=${HEAD_B}" data-revision="${HEAD_B}">oldest</a>
          </nav>
        </div>
      </details>`;
    await flush();
    return document.querySelector("details") as HTMLDetailsElement;
}

/** Open the disclosure the way a browser does, and let the fetch settle. */
async function open(details: HTMLDetailsElement): Promise<void> {
    details.open = true;
    details.dispatchEvent(new Event("toggle"));
    await flush();
    await flush();
}

describe("<cp-revision-runs>", () => {
    afterEach(() => resetDom());

    it("asks nothing until the menu is opened", async () => {
        const fetches = stubFetch({ ok: true, body: PAYLOAD });
        await mount();
        // The answer costs a delivery-branch read and most visits never open this control.
        assert.deepEqual(fetches.calls, []);
    });

    it("marks the head of each run and indents the rest", async () => {
        stubFetch({ ok: true, body: PAYLOAD });
        const details = await mount();
        await open(details);

        const rows = [...details.querySelectorAll<HTMLElement>(".cp-revision")];
        assert.equal(rows[0].getAttribute("data-run-head"), "first");
        // The middle row shares its pixels with the row above, so it carries no marker.
        assert.equal(rows[1].getAttribute("data-run-head"), null);
        assert.equal(rows[2].getAttribute("data-run-head"), "1");
        assert.equal(
            rows[0].querySelector("img.cp-revision-thumb")?.getAttribute("src"),
            `/wear/render/media.png?at=${HEAD_A}`,
        );
        assert.equal(rows[1].querySelector("img.cp-revision-thumb"), null);
        // The run of two says so; the run of one has nothing a reader can't already see.
        assert.equal(
            rows[0].querySelector(".cp-revision-span")?.textContent,
            "×2",
        );
        assert.equal(rows[2].querySelector(".cp-revision-span"), null);
        assert.equal(
            details.querySelector(".cp-revision-runs-summary")?.textContent,
            "2 distinct renders across these 3 publishes",
        );
    });

    it("asks once, however often the menu is reopened", async () => {
        const fetches = stubFetch({ ok: true, body: PAYLOAD });
        const details = await mount();
        await open(details);
        details.open = false;
        details.dispatchEvent(new Event("toggle"));
        await open(details);
        assert.equal(fetches.calls.length, 1);
        assert.equal(fetches.calls[0], "/wear/api/render-runs/media");
        // Reopening must not stack a second image onto a row it already marked.
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            2,
        );
    });

    it("says how many publishes match when none of them differ", async () => {
        stubFetch({
            ok: true,
            body: { revisions: 12, runs: [{ head: HEAD_A, commits: 12 }] },
        });
        const details = await mount();
        await open(details);
        // One run is no difference to point at, so no row is marked — but the count is exactly the
        // question a reader opened the menu to answer, so it is still stated.
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            0,
        );
        assert.equal(
            details.querySelector(".cp-revision-runs-summary")?.textContent,
            "All 12 publishes render identically",
        );
    });

    it("leaves the menu untouched when the lane has no answer", async () => {
        stubFetch({ ok: false });
        const details = await mount();
        await open(details);
        // A catalog with no delivery branch, or a feed that could not be read. The menu was a
        // working control before this element existed and has to stay one.
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            0,
        );
        assert.equal(details.querySelector(".cp-revision-runs-summary"), null);
        assert.equal(
            details
                .querySelector(".cp-revision-list")
                ?.getAttribute("data-runs"),
            null,
        );
    });

    it("prefers an inlined payload, so a fixture draws offline", async () => {
        const fetches = stubFetch({ ok: true, body: PAYLOAD });
        const details = await mount();
        // The harness cannot reach the runs lane, so without this path its capture of this menu
        // would look identical whether the markers work or the whole feature is gone.
        const script = document.createElement("script");
        script.type = "application/json";
        script.id = "cp-revision-runs-data";
        script.textContent = JSON.stringify(PAYLOAD);
        document.body.appendChild(script);
        await open(details);
        assert.deepEqual(fetches.calls, []);
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            2,
        );
    });

    it("draws nothing when the answer describes a newer window than the page", async () => {
        // The menu is fetched lazily, so a catalog that republished since the page was rendered
        // answers over a window whose newest publish this list does not contain. Marking what is
        // left would leave the page's own newest row unmarked and indented under a head nobody can
        // see, and the summary would count a different set of publishes.
        stubFetch({
            ok: true,
            body: {
                revisions: 4,
                runs: [
                    { head: "f".repeat(40), commits: 1 },
                    { head: HEAD_A, commits: 2 },
                ],
            },
        });
        const details = await mount();
        await open(details);
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            0,
        );
        assert.equal(details.querySelector(".cp-revision-runs-summary"), null);
        assert.equal(
            details
                .querySelector(".cp-revision-list")
                ?.getAttribute("data-runs"),
            null,
        );
    });

    it("refuses a stale single-run answer, which makes the boldest claim of all", async () => {
        // `runsViewOf` returns null for one run, so this branch skips the marker path entirely —
        // and it is the branch that says "All N publishes render identically". A guard living only
        // inside the marker path would leave the strongest possible falsehood unguarded.
        stubFetch({
            ok: true,
            body: {
                revisions: 13,
                runs: [{ head: "f".repeat(40), commits: 13 }],
            },
        });
        const details = await mount();
        await open(details);
        assert.equal(details.querySelector(".cp-revision-runs-summary"), null);
    });

    it("still summarises a single-run answer about the rows on this page", async () => {
        // The counterpart to the test above: same shape, but the window matches, so the count is
        // exactly what a reader opened the menu to find out.
        stubFetch({
            ok: true,
            body: { revisions: 3, runs: [{ head: HEAD_A, commits: 3 }] },
        });
        const details = await mount();
        await open(details);
        assert.equal(
            details.querySelector(".cp-revision-runs-summary")?.textContent,
            "All 3 publishes render identically",
        );
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            0,
        );
    });

    it("draws nothing when the server named no usable render URL", async () => {
        const fetches = stubFetch({ ok: true, body: PAYLOAD });
        const details = await mount({ renderUrl: "//evil.example/a.png" });
        await open(details);
        assert.deepEqual(fetches.calls, []);
        assert.equal(
            details.querySelectorAll("img.cp-revision-thumb").length,
            0,
        );
    });
});
