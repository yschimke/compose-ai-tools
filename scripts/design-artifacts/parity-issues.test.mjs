import { test } from "node:test";
import assert from "node:assert/strict";
import { buildIssueIndex, canonicalIssueUrl, parseLocator } from "./parity-issues.mjs";

const body = `Difference details.\n\n\`\`\`compose-parity-locator/v1
repository: yschimke/m3-catalog
system: m3
component: IconButton/Tonal
preview: iconbutton-tonal__ideal__default__light
reference: iconbutton-tonal-figma
variant: ideal/default/light
overrides: {"fontScale":"1.5","knob.label":"Send;now=x"}
revision: yschimke/m3-catalog@main
\`\`\``;

test("a locator round-trips the exact identity and canonical overrides", () => {
  assert.deepEqual(parseLocator(body), { ok: true, locator: { repository: "yschimke/m3-catalog", system: "m3", component: "IconButton/Tonal", previewId: "iconbutton-tonal__ideal__default__light", referenceId: "iconbutton-tonal-figma", variant: "ideal/default/light", overrides: { fontScale: "1.5", "knob.label": "Send;now=x" }, revision: "yschimke/m3-catalog@main" } });
});

test("mangled blocks are reported instead of silently skipped", () => {
  const errors = [];
  const index = buildIssueIndex([{ html_url: "https://github.com/yschimke/m3-catalog/issues/40", title: "x", body: body.replace("overrides:", "overrides"), state: "open" }], { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error) });
  assert.deepEqual(index.issues, []);
  assert.match(errors[0], /malformed|missing locator/);
});

test("buildIssueIndex canonicalises URLs, labels, and preserves closed rows", () => {
  const index = buildIssueIndex([{ html_url: "https://WWW.GITHUB.COM/YSCHIMKE/M3-CATALOG/issues/40/", title: "Glyph colour", body, state: "closed", labels: [{ name: "area:component" }, { name: "parity:known-difference" }] }], { generatedAt: "2026-08-15T10:00:00Z" });
  assert.equal(index.issues[0].url, "https://github.com/yschimke/m3-catalog/issues/40");
  assert.equal(index.issues[0].state, "closed");
  assert.equal(index.issues[0].area, "component");
});

test("canonicalIssueUrl rejects non-GitHub and mismatched shapes", () => {
  assert.equal(canonicalIssueUrl("javascript:alert(1)"), null);
  assert.equal(canonicalIssueUrl("https://github.com/o/r/pull/2"), null);
});
