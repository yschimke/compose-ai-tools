// What `<cp-acceptance>` shows, and — the harder half — what it withholds.
//
// The adapter's own suite next door proves the engine reaches the right verdict. This one is about
// the step after, and it exists because that step has its own failure mode: every interesting
// verdict here is reported by an *absence* — a document-level rejection omits `statuses`, a
// comparison that could not be fetched leaves every acceptance `out-of-scope` — and a band that
// reads an absence as "nothing to say" hides the finding while looking entirely healthy. A report
// assertion cannot catch that; only asking what the reader ends up seeing can.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import {
    SOURCES,
    catalogRoutes,
    installFetch,
    knownDifferencesJson,
    scope,
    world,
} from "./support/knownDifferences.js";
import "../src/components/Acceptance.js";

let net: ReturnType<typeof installFetch> | null = null;

/**
 * Mount the server's markup — the band and the payload script — and let the element settle.
 *
 * The band is server-rendered, starts `hidden`, and carries the `role="status"` live region; the
 * element renders *into* it rather than replacing it, so this fixture is the real contract between
 * the two and not a convenience.
 */
async function mount(
    routes: Record<string, Uint8Array | string | number>,
    payloadScope: Record<string, unknown>,
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
        referenceUrl: SOURCES.referenceUrl,
        candidateUrl: SOURCES.candidateUrl,
        issues,
        scope: { ...payloadScope, tagIndex: {} },
    };
    net = installFetch(routes);
    document.body.innerHTML = `
      <div class="cp-acceptance" id="cp-acceptance" role="status" hidden></div>
      <script type="application/json" id="cp-known-differences">${JSON.stringify(payload)}</script>
      <cp-acceptance></cp-acceptance>`;
    const band = document.getElementById("cp-acceptance") as HTMLElement;
    // The element fetches a document, a pair and every artifact before it paints, so settling is a
    // handful of turns rather than one. Bounded, and it leaves early the moment the band appears;
    // a case that expects it to stay hidden pays the whole budget and then checks `asked` to prove
    // the work happened at all.
    for (let turn = 0; turn < 40 && band.hidden; turn++) await flush();
    return band;
}

/** Prove the element really ran, so a hidden band is a verdict rather than a race. */
function ran(): void {
    assert.ok(
        net?.asked.includes(SOURCES.documentUrl),
        "the element never fetched the document",
    );
}

/** The band's visible text, whitespace-collapsed so assertions can quote it as written. */
function text(band: HTMLElement): string {
    return (band.textContent ?? "").replace(/\s+/g, " ").trim();
}

describe("<cp-acceptance>", () => {
    afterEach(() => {
        net?.restore();
        net = null;
        resetDom();
    });

    it("shows the three numbers and a row when an acceptance applies", async () => {
        const scene = world();
        const band = await mount(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            scope(scene),
        );
        assert.equal(band.hidden, false);
        assert.match(text(band), /raw/);
        assert.match(text(band), /unaccepted/);
        assert.match(text(band), /over the accepted region/);
        assert.equal(band.querySelectorAll("li.cp-acceptance-row").length, 1);
        assert.match(text(band), /glyph — accepted/);
        assert.match(text(band), /issue state unknown/);
    });

    it("marks closed issue plus a live acceptance as stale using canonical identity", async () => {
        const scene = world();
        const document_ = knownDifferencesJson(scene, {
            issue: "https://WWW.GITHUB.COM/YSchimke/M3-Catalog/issues/40/#issuecomment-1",
        });
        const band = await mount(
            catalogRoutes(scene, document_),
            scope(scene),
            [
                {
                    repository: "yschimke/m3-catalog",
                    number: 40,
                    state: "closed",
                },
            ],
        );
        const row = band.querySelector("li.cp-acceptance-row") as HTMLElement;
        assert.equal(row.dataset.lifecycle, "closed");
        assert.equal(row.hasAttribute("data-stale"), true);
        assert.match(text(row), /stale configuration/);
    });

    it("renders resolved plus closed as completion rather than stale", async () => {
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        // The accepted red glyph now matches the black reference: the engine's own precedence calls
        // this resolved, and the issue axis must not turn the completed loop back into a warning.
        routes[SOURCES.candidateUrl] = scene.referencePng;
        const band = await mount(routes, scope(scene), [
            {
                repository: "yschimke/m3-catalog",
                number: 40,
                state: "closed",
            },
        ]);
        const row = band.querySelector("li.cp-acceptance-row") as HTMLElement;
        assert.equal(row.dataset.status, "resolved");
        assert.equal(row.dataset.lifecycle, "closed");
        assert.equal(row.hasAttribute("data-stale"), false);
        assert.match(
            text(row),
            /verified; issue closed; remove the acceptance/,
        );
        assert.doesNotMatch(text(row), /stale configuration/);
    });

    it("says nothing on a comparison every acceptance was authored elsewhere for", async () => {
        // The ordinary case, and the reason the band is conditional at all: an "0 accepted" strip on
        // every comparison in the catalog is noise, not information.
        const scene = world();
        const band = await mount(
            catalogRoutes(
                scene,
                knownDifferencesJson(scene, { system: "wear-m3" }),
            ),
            scope(scene),
        );
        ran();
        assert.equal(band.hidden, true);
        assert.equal(text(band), "");
    });

    it("explains a document rejected wholesale instead of showing an empty list", async () => {
        // `duplicate-id` is attributed to the first spelling seen, so the failure carries an `id`
        // just as a per-record refusal does — while `statuses` is absent, because nothing was
        // judged. Reading "document-level" off that missing `id` dropped this row entirely, and the
        // band then showed scores above an empty list: the same picture as a catalog that accepts
        // nothing here, with the opposite meaning.
        const scene = world();
        const document_ = JSON.parse(knownDifferencesJson(scene)) as {
            acceptances: unknown[];
        };
        document_.acceptances.push({ ...(document_.acceptances[0] as object) });
        const band = await mount(
            catalogRoutes(scene, JSON.stringify(document_)),
            scope(scene),
        );
        assert.equal(band.hidden, false);
        assert.match(
            text(band),
            /document was refused \(duplicate-id \(glyph\)\)/,
        );
        assert.match(text(band), /nothing in it is being applied/);
        assert.equal(band.querySelectorAll("li.cp-acceptance-row").length, 0);
    });

    it("stays visible when the comparison itself could not be fetched", async () => {
        // With no pair the engine runs its validation-only pass and every in-scope acceptance comes
        // back `out-of-scope` — the token a record authored elsewhere gets, and the one the case
        // above hides the band for. So a transient 503 on the render lane would read as a clean
        // bill of health for a comparison nobody measured.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.candidateUrl] = 503;
        const band = await mount(routes, scope(scene));
        assert.equal(band.hidden, false);
        assert.match(text(band), /could not be read as this page describes it/);
        assert.doesNotMatch(
            text(band),
            /raw/,
            "nothing was measured, so no number is shown",
        );
    });

    it("stays visible when the reference bytes are of another catalog generation", async () => {
        // The same stall by the other route: the digest this page was built from does not describe
        // the bytes the fetch returned, so the fingerprint gate would be passing against a
        // generation nobody scored. Silence here is what would let a mask suppress it.
        const scene = world();
        const band = await mount(
            catalogRoutes(scene, knownDifferencesJson(scene)),
            scope(scene, { referenceSha256: "b".repeat(64) }),
        );
        assert.equal(band.hidden, false);
        assert.match(text(band), /could not be read as this page describes it/);
    });

    it("says so when the engine could not be run at all", async () => {
        // Distinct from every case above: those are verdicts, this is their absence. A band that
        // fell silent here would be reporting a clean comparison it never evaluated.
        const scene = world();
        const routes = catalogRoutes(scene, knownDifferencesJson(scene));
        routes[SOURCES.documentUrl] = 500;
        const band = await mount(routes, scope(scene));
        assert.equal(band.hidden, false);
        assert.match(text(band), /could not be fetched/);
    });
});
