// Behavioural contract for `<cp-history-menu>`.
//
// The rules are pinned in `historyUrls.test.ts` and `historyModel.test.ts`; these cover what only
// the element can answer — that it reads the inline payload a fixture ships, falls back to the
// manifest fetch, and draws nothing at all rather than an empty control when there is no timeline.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/HistoryMenu.js";

const ENTRY = {
    path: "renders/m3/Button.png",
    observations: 7,
    versions: [
        {
            commit: "aaaaaaa",
            date: "2026-08-15T10:00:00Z",
            sourceSha: "src1111",
        },
        { commit: "bbbbbbb", date: "2026-08-01T10:00:00Z", commits: 3 },
    ],
};

/** Mount a viewer whose head-toggles row declares the menu, as the server does. */
async function mount(
    options: {
        inline?: unknown;
        repo?: string | null;
        blobUrl?: string | null;
        historyUrl?: string | null;
    } = {},
): Promise<HTMLElement> {
    const {
        inline = { previews: { "plain.Button": ENTRY } },
        repo = "yschimke/compose-ai-tools",
        blobUrl = null,
        historyUrl = null,
    } = options;
    const attrs = [
        `data-preview-id="plain.Button"`,
        repo ? `data-history-repo="${repo}"` : "",
        blobUrl ? `data-history-blob-url="${blobUrl}"` : "",
        historyUrl ? `data-history-url="${historyUrl}"` : "",
    ].join(" ");
    document.body.innerHTML = `
      <div class="cp-preview-head">
        <div class="cp-head-toggles">
          <cp-history-menu></cp-history-menu>
          <button id="cp-controls-toggle">⚙ Overrides</button>
        </div>
      </div>
      <div class="cp-viewer" ${attrs}></div>
      ${
          inline === null
              ? ""
              : `<script type="application/json" id="cp-history-data">${JSON.stringify(inline)}</script>`
      }`;
    await flush();
    await flush();
    return document.querySelector("cp-history-menu") as HTMLElement;
}

describe("<cp-history-menu>", () => {
    afterEach(() => resetDom());

    it("renders the menu from the inline payload a fixture ships", async () => {
        // Without the inline path the harness capture would be identical whether the timeline
        // works or is deleted, which is no coverage at all.
        const menu = await mount();
        assert.equal(menu.querySelectorAll("a.cp-history-item").length, 2);
        assert.equal(
            menu.querySelector(".cp-history-value")?.textContent,
            "2 versions",
        );
    });

    it("sits where the server put it, with no placement logic of its own", async () => {
        const menu = await mount();
        const row = menu.parentElement;
        assert.equal(row?.className, "cp-head-toggles");
        assert.equal(
            row?.children[1].id,
            "cp-controls-toggle",
            "the Overrides drawer stays last, where the thumb expects it",
        );
    });

    it("opens each render in a new tab, safely", async () => {
        const link = (await mount()).querySelector("a.cp-history-item");
        assert.equal(link?.getAttribute("target"), "_blank");
        assert.equal(link?.getAttribute("rel"), "noopener noreferrer");
        assert.ok(
            link
                ?.getAttribute("href")
                ?.startsWith(
                    "https://raw.githubusercontent.com/yschimke/compose-ai-tools/aaaaaaa/",
                ),
            link?.getAttribute("href") ?? "null",
        );
    });

    it("marks the newest published render as current", async () => {
        const items = (await mount()).querySelectorAll("a.cp-history-item");
        assert.equal(items[0].getAttribute("data-current"), "1");
        assert.equal(
            items[0].querySelector(".cp-history-meta")?.textContent,
            "current",
        );
        assert.equal(items[1].hasAttribute("data-current"), false);
    });

    it("draws nothing when there is no timeline", async () => {
        const menu = await mount({
            inline: {
                previews: {
                    "plain.Button": { ...ENTRY, versions: [ENTRY.versions[0]] },
                },
            },
        });
        assert.equal(
            menu.children.length,
            0,
            "an empty control is worse than none",
        );
    });

    it("draws nothing when neither addressing mode is usable", async () => {
        // A malformed repo would otherwise become a menu of links that cannot be built.
        const menu = await mount({ repo: "not a repo" });
        assert.equal(menu.children.length, 0);
    });

    it("falls back to the manifest fetch when no payload is inline", async () => {
        const calls: string[] = [];
        globalThis.fetch = (async (url: string) => {
            calls.push(String(url));
            return {
                ok: true,
                json: async () => ({ previews: { "plain.Button": ENTRY } }),
            };
        }) as unknown as typeof fetch;
        const menu = await mount({ inline: null, historyUrl: "/history.json" });
        await flush();
        assert.deepEqual(calls, ["/history.json"]);
        assert.equal(menu.querySelectorAll("a.cp-history-item").length, 2);
    });

    it("stays silent when the manifest is missing", async () => {
        // Expected on a branch that has not published one yet; the preview is unaffected.
        globalThis.fetch = (async () => ({
            ok: false,
        })) as unknown as typeof fetch;
        const menu = await mount({ inline: null, historyUrl: "/history.json" });
        await flush();
        assert.equal(menu.children.length, 0);
    });

    it("stays silent on a page with no viewer", async () => {
        document.body.innerHTML = `<cp-history-menu></cp-history-menu>`;
        await flush();
        assert.equal(
            document.querySelector("cp-history-menu")?.children.length,
            0,
        );
    });

    it("does not duplicate its control when moved", async () => {
        const menu = await mount();
        assert.equal(menu.querySelectorAll(".cp-history-menu").length, 1);

        menu.remove();
        document.body.appendChild(menu);
        await flush();

        assert.equal(menu.querySelectorAll(".cp-history-menu").length, 1);
    });
});
