/**
 * Unit tests for the `README.md` committed into each `design-artifacts/<system>`
 * branch. The README is the landing page a designer hits first on GitHub, and its
 * prominent links (index / compare / matches) are htmlpreview URLs pinned to a
 * specific repo + branch. The repo MUST be the one the branch actually publishes
 * to — a bundle generated from a sibling repo (meshcore-mobile runs this same
 * generator against a compose-ai-tools checkout, then force-pushes to its OWN
 * `design-artifacts/meshcore-mobile` branch) has to link at that sibling, not at
 * the default compose-ai-tools, or every htmlpreview link 404s.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderReadmeMd } from "./render-readme-md.mjs";

const catalog = {
  meta: {
    system: "meshcore-mobile",
    title: "MeshCore Mobile",
    library: ["androidx.compose.material3"],
    renderer: "compose-preview 0.16.54",
  },
  components: [
    { componentId: "Button/Filled", group: "Buttons", images: [{ path: "images/a.png" }] },
    { componentId: "Card/Outlined", group: "Cards", images: [{ path: "images/b.png" }] },
  ],
};

test("htmlpreview links point at the given repo's branch, not the default", () => {
  const md = renderReadmeMd(catalog, { repo: "yschimke/meshcore-mobile" });
  const branch = "design-artifacts/meshcore-mobile";
  for (const page of ["index.html", "compare.html"]) {
    const url = `https://htmlpreview.github.io/?https://github.com/yschimke/meshcore-mobile/blob/${branch}/${page}`;
    assert.ok(md.includes(url), `expected README to link ${page} at ${url}`);
  }
  // The default repo must NOT leak into a sibling repo's README — that's the 404.
  assert.ok(
    !md.includes("github.com/yschimke/compose-ai-tools/blob/design-artifacts/meshcore-mobile"),
    "sibling README must not link its branch under compose-ai-tools",
  );
});

test("repo defaults to compose-ai-tools when none is given", () => {
  const md = renderReadmeMd(catalog);
  assert.ok(
    md.includes(
      "https://htmlpreview.github.io/?https://github.com/yschimke/compose-ai-tools/blob/design-artifacts/meshcore-mobile/index.html",
    ),
    "default repo should remain yschimke/compose-ai-tools",
  );
});
