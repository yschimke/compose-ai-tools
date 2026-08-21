import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { NO_LOCATOR, buildIssueIndex, canonicalIssueUrl, parseLocator } from "./parity-issues.mjs";

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

// The half of the contract this file owns. The other half — that `ServeIssueReport.locatorBlock`
// emits these exact bytes — is asserted by ServeIssueReportTest against the same file, which is the
// only thing stopping a Kotlin writer and a JavaScript producer from drifting apart on a contract
// neither of them can see the other half of. See the fixture's own $comment.
const shared = JSON.parse(
  readFileSync(fileURLToPath(new URL("./fixtures/parity-locators.json", import.meta.url)), "utf8"),
);

for (const shape of shared.cases) {
  test(`shared locator fixture: ${shape.name}`, () => {
    assert.deepEqual(parseLocator(shape.block), shape.parse, shape.why);
  });
}

test("an indexed issue survives a locator that names no revision", () => {
  // The writer omits `revision` whenever the session has no delivery provenance — a developer's
  // local serve, or a live daemon. Requiring it dropped exactly those reports.
  const local = shared.cases.find((shape) => shape.name === "no-revision");
  const index = buildIssueIndex(
    [{ html_url: "https://github.com/yschimke/m3-catalog/issues/40", title: "Glyph colour", body: local.block, state: "open" }],
    { generatedAt: "2026-08-15T10:00:00Z" },
  );
  assert.equal(index.issues.length, 1);
  assert.equal(index.issues[0].component, "IconButton/Tonal");
});

test("an ordinary issue is skipped, not rejected", () => {
  // Every repository is mostly issues that are not parity reports. Counting each of them as a
  // failure made the producer red on a healthy repo, which is why no catalog could adopt it.
  const errors = [];
  const skips = [];
  const index = buildIssueIndex(
    [
      { html_url: "https://github.com/yschimke/m3-catalog/issues/71", title: "Dependency Dashboard", body: "no locator here", state: "open" },
      { html_url: "https://github.com/yschimke/m3-catalog/issues/72", title: "Filed without its identity", body: "no locator here", state: "open", labels: [{ name: "parity:known-difference" }] },
    ],
    { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error), onSkip: (issue, info) => skips.push([issue.title, info.labelled]) },
  );
  assert.deepEqual(index.issues, []);
  assert.deepEqual(errors, [], "an absent locator is not an error");
  // …but a parity-labelled issue with no locator is a mis-filed report, and says so.
  assert.deepEqual(skips, [["Dependency Dashboard", false], ["Filed without its identity", true]]);
  assert.equal(parseLocator("nothing").error, NO_LOCATOR);
});

test("a locator that fails to close is broken, not absent", () => {
  // The skip path exists for issues that are not parity reports. A fence whose closing ``` was
  // deleted matches the full-fence regex zero times and would otherwise look exactly like one —
  // sending a real, damaged report down the silent path and letting the run go green without it.
  const errors = [];
  const skips = [];
  const truncated = shared.cases[0].block.replace(/\n```\n$/, "\n");
  buildIssueIndex(
    [{ html_url: "https://github.com/yschimke/m3-catalog/issues/40", title: "Glyph colour", body: truncated, state: "open" }],
    { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error), onSkip: () => skips.push(true) },
  );
  assert.deepEqual(errors, ["unterminated locator block"]);
  assert.deepEqual(skips, [], "a broken locator must never be skipped");
  // A body that merely names the fence in prose still carries no locator.
  assert.equal(parseLocator("I pasted a compose-parity-locator/v1 block once.").error, NO_LOCATOR);
  // And a good block followed by a dangling opener is ambiguous, not usable.
  assert.equal(parseLocator(shared.cases[0].block + "\n```compose-parity-locator/v1\nrepository: a/b\n").error, "multiple locator blocks");
});

test("a fence indented the way CommonMark allows is still a locator", () => {
  // One to three leading spaces renders as an ordinary fenced block on GitHub — pasting the report
  // inside a list item is enough to produce it — so a column-zero-only pattern reads a locator the
  // reporter can plainly see as absent, and skips it.
  const indent = (block, n) => block.split("\n").map((line) => (line ? " ".repeat(n) + line : line)).join("\n");
  const good = shared.cases.find((shape) => shape.name === "full");
  for (const n of [1, 2, 3]) {
    assert.deepEqual(parseLocator(indent(good.block, n)), good.parse, `indented by ${n}`);
  }
  // Four is an indented code block, not a fence: the marker is literal text, so there is no locator.
  assert.equal(parseLocator(indent(good.block, 4)).error, NO_LOCATOR);
  // An indented block that fails to close is still broken rather than absent.
  assert.equal(parseLocator("  ```compose-parity-locator/v1\n  repository: a/b\n").error, "unterminated locator block");
});
