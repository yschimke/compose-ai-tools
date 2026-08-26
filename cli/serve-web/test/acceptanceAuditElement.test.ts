// What `<cp-acceptance-audit>` reports on the dashboard — and, again, what it refuses to report.
//
// The panel exists for one finding the comparison band structurally cannot reach: an acceptance
// whose target the catalog no longer has is scoped into no comparison, so only a walk sees it. The
// cases below are therefore about the walk's two axes — the status the document supports on its own,
// and the lifecycle join — plus the two absences that must not read as health: a document that could
// not be fetched, and one the engine refused wholesale.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import {
    SOURCES,
    installFetch,
    knownDifferencesJson,
    world,
} from "./support/knownDifferences.js";
import "../src/components/AcceptanceAudit.js";

let net: ReturnType<typeof installFetch> | null = null;

/** The band's prose, whitespace-normalised: the templates wrap, the sentences do not. */
const text = (band: HTMLElement) =>
    (band.textContent ?? "").replace(/\s+/g, " ");

const SCENE = world();

/** The catalog inventory the acceptance in `knownDifferencesJson` targets. */
const PREVIEWS = [
    {
        system: "m3",
        id: "iconbutton-tonal__ideal__default__light",
        component: "IconButton/Tonal",
        variant: "ideal/default/light",
        referenceIds: ["iconbutton-tonal-ideal-light"],
    },
];

function routes(
    document: string,
): Record<string, Uint8Array | string | number> {
    return {
        [SOURCES.documentUrl]: document,
        "/m3/parity/known-differences/glyph/mask.png": SCENE.mask,
        "/m3/parity/known-differences/glyph/accepted-candidate.png":
            SCENE.accepted,
    };
}

async function mount(
    served: Record<string, Uint8Array | string | number>,
    previews: unknown[],
    issues: Array<{
        repository: string;
        number: number;
        state: "open" | "closed";
    }> = [],
): Promise<HTMLElement> {
    const payload = {
        documentUrl: SOURCES.documentUrl,
        artifactBase: "/m3/parity/known-differences/",
        artifactQuery: "",
        previews,
        issues,
    };
    net = installFetch(served);
    document.body.innerHTML = `
      <div class="cp-acceptance-audit" id="cp-acceptance-audit" role="status" hidden></div>
      <script type="application/json" id="cp-known-difference-audit">${JSON.stringify(payload)}</script>
      <cp-acceptance-audit></cp-acceptance-audit>`;
    const band = document.getElementById("cp-acceptance-audit") as HTMLElement;
    for (let turn = 0; turn < 40 && band.hidden; turn++) await flush();
    await flush();
    return band;
}

afterEach(() => {
    net?.restore();
    net = null;
    resetDom();
});

describe("<cp-acceptance-audit>", () => {
    it("reports an acceptance whose target the catalog no longer has", async () => {
        // The preview was renamed; nothing else changed. No comparison on this catalog scopes the
        // record in any more, which is exactly why the walk has to.
        const band = await mount(routes(knownDifferencesJson(SCENE)), [
            { ...PREVIEWS[0], id: "iconbutton-tonal__ideal__default__dark" },
        ]);
        assert.equal(band.hidden, false);
        assert.match(text(band), /Needs attention \(1\)/);
        assert.match(text(band), /glyph/);
        assert.match(text(band), /no longer has/);
    });

    it("says nothing is wrong when every target exists and the issue is open", async () => {
        const band = await mount(
            routes(knownDifferencesJson(SCENE)),
            PREVIEWS,
            [{ repository: "yschimke/m3-catalog", number: 40, state: "open" }],
        );
        assert.equal(band.hidden, false);
        assert.doesNotMatch(text(band), /Needs attention/);
        assert.doesNotMatch(text(band), /Closed issue/);
        assert.match(text(band), /Known differences \(1\)/);
        assert.match(text(band), /no tracking issue is reported closed/);
        // Nothing is unknown here, so the all-clear line carries no caveat.
        assert.doesNotMatch(text(band), /unknown rather than open/);
    });

    it("does not report an unknown lifecycle as an open issue", async () => {
        // The index is fail-soft and capped, so an acceptance it never mentions is missing evidence.
        // Saying \"every tracking issue is open\" over it would turn a file that failed to parse into
        // a clean bill of health for acceptances that may every one of them be stale.
        const band = await mount(routes(knownDifferencesJson(SCENE)), PREVIEWS);
        assert.match(text(band), /no tracking issue is reported closed/);
        assert.match(text(band), /says nothing about 1 of them/);
        assert.doesNotMatch(text(band), /every tracking issue is open/);
    });

    it("lists an acceptance whose tracking issue has closed", async () => {
        const band = await mount(
            routes(knownDifferencesJson(SCENE)),
            PREVIEWS,
            [
                {
                    repository: "yschimke/m3-catalog",
                    number: 40,
                    state: "closed",
                },
            ],
        );
        assert.match(text(band), /Closed issue/);
        assert.match(text(band), /yschimke\/m3-catalog#40/);
        const link = band.querySelector("a");
        assert.equal(
            link?.getAttribute("href"),
            "https://github.com/yschimke/m3-catalog/issues/40",
        );
    });

    it("never infers closure from an acceptance the index does not mention", async () => {
        // The index is fail-soft and can lag. Absence is `unknown`, and `unknown` is never stale.
        const band = await mount(routes(knownDifferencesJson(SCENE)), PREVIEWS);
        assert.doesNotMatch(text(band), /Closed issue/);
    });

    it("never issues a request a traversal path would have redirected", async () => {
        // `fetch` normalises `..` before the request, so a document naming one would reach some other
        // same-origin route — with the session credential attached — instead of the artifact route
        // that owns containment. The answer is the host's own 403 token, computed without asking.
        const document = knownDifferencesJson(SCENE, {
            mask: "../../../../status",
        });
        const band = await mount(routes(document), PREVIEWS);
        assert.ok(
            !(net?.asked ?? []).some((url) => url.includes("status")),
            `no request escaped the artifact route: ${net?.asked.join(", ")}`,
        );
        assert.match(text(band), /path-not-contained/);
    });

    it("recognises a dot segment the way the URL parser does, not by spelling", async () => {
        // `%2e%2e` is a double-dot segment to the WHATWG path state, so it normalises away exactly
        // like `..`: `new URL(base + "glyph/%2e%2e/%2e%2e/status").pathname` is `/parity/status`.
        const document = knownDifferencesJson(SCENE, {
            mask: "%2e%2e/%2E%2e/status",
        });
        const band = await mount(routes(document), PREVIEWS);
        assert.ok(
            !(net?.asked ?? []).some((url) => url.includes("status")),
            `no request escaped the artifact route: ${net?.asked.join(", ")}`,
        );
        assert.match(text(band), /path-not-contained/);
    });

    it("refuses a segment the parser strips its way into a dot segment", async () => {
        // Tab, CR and LF are removed from a URL before it is resolved, so `\t..` is a `..` by the
        // time it matters: `new URL(base + "glyph/\t../\t../status").pathname` is `/parity/status`.
        const document = knownDifferencesJson(SCENE, {
            mask: "\t../\t../status",
        });
        const band = await mount(routes(document), PREVIEWS);
        assert.ok(
            !(net?.asked ?? []).some((url) => url.includes("status")),
            `no request escaped the artifact route: ${net?.asked.join(", ")}`,
        );
        assert.match(text(band), /path-not-contained/);
    });

    it("reports a refused document rather than an empty audit", async () => {
        const broken = JSON.stringify({
            schema: "compose-preview-known-differences/v1",
            acceptances: [{ id: "glyph" }, { id: "glyph" }],
        });
        const band = await mount(routes(broken), PREVIEWS);
        assert.match(text(band), /was refused/);
        assert.match(text(band), /duplicate-id/);
    });

    it("says so when the document could not be fetched", async () => {
        const band = await mount({ [SOURCES.documentUrl]: 500 }, PREVIEWS);
        assert.match(text(band).replace(/\s+/g, " "), /could not be fetched/);
    });

    it("stays hidden when the document has gone", async () => {
        const band = await mount({}, PREVIEWS);
        assert.equal(band.hidden, true);
        assert.ok(net?.asked.includes(SOURCES.documentUrl));
    });
});
