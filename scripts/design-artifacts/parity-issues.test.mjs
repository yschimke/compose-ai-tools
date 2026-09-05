import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { NO_LOCATOR, buildIssueIndex, canonicalIssueUrl, parseLocator, parseLocators } from "./parity-issues.mjs";

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
  assert.deepEqual(parseLocator(body), { ok: true, locator: { repository: "yschimke/m3-catalog", system: "m3", component: "IconButton/Tonal", previewId: "iconbutton-tonal__ideal__default__light", referenceId: "iconbutton-tonal-figma", variant: "ideal/default/light", overrides: { fontScale: "1.5", "knob.label": "Send;now=x" }, element: null, bounds: null, revision: "yschimke/m3-catalog@main" } });
});

test("report scope defaults to the component and carries an explicit variant choice", () => {
  const component = buildIssueIndex([{ html_url: "https://github.com/yschimke/m3-catalog/issues/40", title: "Glyph colour", body, state: "open" }]);
  assert.equal(component.issues[0].scope, "component");

  const scoped = body.replace("component: IconButton/Tonal\n", "component: IconButton/Tonal\nscope: variant\n");
  assert.equal(parseLocator(scoped).locator.scope, "variant");
  const variant = buildIssueIndex([{ html_url: "https://github.com/yschimke/m3-catalog/issues/41", title: "Large only", body: scoped, state: "open" }]);
  assert.equal(variant.issues[0].scope, "variant");
  assert.equal(parseLocator(scoped.replace("scope: variant", "scope: all")).error, "scope must be component or variant");
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

// The report form's "Where does it belong?" control files these two, so an index that did not know
// them would drop the classification the reporter took the trouble to make — silently, since an
// unknown `parity:` value is simply not a match. `ServeParityIssuesStore.PARITY` is the same list at
// the reading end.
test("the parity vocabulary carries the answers the report form offers", () => {
  for (const value of ["upstream", "catalog", "verification-needed"]) {
    const index = buildIssueIndex([{ html_url: "https://github.com/yschimke/m3-catalog/issues/40", title: "Glyph colour", body, state: "open", labels: [{ name: `parity:${value}` }] }], { generatedAt: "2026-08-15T10:00:00Z" });
    assert.equal(index.issues[0].parity, value);
  }
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
  // And a good block followed by a dangling opener is a body with an unclosed block in it. Since a
  // body may now carry several blocks, the openers and the complete fences are counted against each
  // other: more openers than fences means one of them never closed, whichever one it was.
  assert.equal(parseLocator(shared.cases[0].block + "\n```compose-parity-locator/v1\nrepository: a/b\n").error, "unterminated locator block");
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

for (const shape of shared.bodies) {
  test(`shared locator fixture body: ${shape.name}`, () => {
    assert.deepEqual(parseLocators(shape.body), shape.parse, shape.why);
  });
}

test("an umbrella issue reaches every component it names", () => {
  // m3-catalog#42's shape: one issue, three components, one row each. The index row is keyed by
  // issue AND component, so `issuesForPreview`'s component join lights up all three pages from one
  // report instead of an arbitrary one of them.
  const umbrella = shared.bodies.find((shape) => shape.name === "umbrella-two-components");
  const index = buildIssueIndex(
    [{ html_url: "https://github.com/yschimke/m3-catalog/issues/42", title: "Elevated shadow level", body: umbrella.body, state: "open", labels: [{ name: "area:component" }] }],
    { generatedAt: "2026-08-15T10:00:00Z" },
  );
  assert.deepEqual(index.issues.map((row) => row.component), ["Button/Elevated", "Card/Elevated"]);
  assert.deepEqual([...new Set(index.issues.map((row) => row.number))], [42], "the rows are one issue");
  assert.deepEqual(index.issues.map((row) => row.previewIds[0]), ["button-elevated__ideal__default__light", "card-elevated__ideal__default__light__compact"]);
  // Every row carries the issue's own facts; only the locator half differs between them.
  for (const row of index.issues) {
    assert.equal(row.title, "Elevated shadow level");
    assert.equal(row.area, "component");
    assert.equal(row.state, "open");
  }
});

test("a body whose blocks contradict each other is rejected, not half-indexed", () => {
  for (const name of ["umbrella-repeats-a-component", "umbrella-disagrees-about-the-repository"]) {
    const shape = shared.bodies.find((body) => body.name === name);
    const errors = [];
    const index = buildIssueIndex(
      [{ html_url: "https://github.com/yschimke/m3-catalog/issues/42", title: "Elevated shadow level", body: shape.body, state: "open" }],
      { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error) },
    );
    assert.deepEqual(index.issues, [], name);
    assert.deepEqual(errors, [shape.parse.error], name);
  }
});

test("a broken block names which one it is, once there is more than one", () => {
  const umbrella = shared.bodies.find((shape) => shape.name === "umbrella-two-components");
  const damaged = umbrella.body.replace("component: Card/Elevated", "component Card/Elevated");
  assert.equal(parseLocators(damaged).error, "locator block 2: malformed locator line: component Card/Elevated");
  // With a single block there is no ordinal worth carrying — the error is about the only locator.
  assert.match(parseLocators(shared.cases[0].block.replace("system:", "system")).error, /^malformed locator line:/);
});

test("the reserved selection fields round-trip, and refuse a rectangle with no settled space", () => {
  // Nothing writes these until batch 03. They are reserved now because adding a key to a frozen v1
  // afterwards is rejected by a strict parser and silently discarded by a permissive one — and both
  // engines here are permissive, so the selection would have vanished with no error at all.
  const reserved = shared.cases.find((shape) => shape.name === "element-and-bounds");
  const parsed = parseLocator(reserved.block);
  assert.equal(parsed.locator.element, "glyph");
  assert.deepEqual(parsed.locator.bounds, { height: 24, space: "render-pixels", width: 24, x: 18, y: 18 });
  // A blank value is a mangled body rather than an absent field, quoted or not.
  assert.equal(parseLocator(reserved.block.replace('element: "glyph"', "element:")).error, "empty locator field(s): element");
  assert.equal(parseLocator(reserved.block.replace('"glyph"', '""')).error, "empty locator field(s): element");
  // The tag is a JSON string precisely so it cannot become syntax: a newline inside it stays inside
  // it, instead of opening a field the reporter never wrote.
  const injected = shared.cases.find((shape) => shape.name === "element-with-a-newline");
  const parsedInjection = parseLocator(injected.block);
  assert.equal(parsedInjection.locator.element, "row\nrevision: injected");
  assert.equal(parsedInjection.locator.revision, null, "the injected line is part of the tag, not a field");
  // Extent has to be real: a zero-width rectangle selects nothing but would suppress its own row.
  assert.match(parseLocator(reserved.block.replace('"width":24', '"width":0')).error, /positive extent|not canonical/);
  // A negative origin is valid: a tagged node can sit above or left of the render root, and the tag
  // index publishes signed coordinates for it.
  const above = shared.cases.find((shape) => shape.name === "bounds-above-the-render-root");
  assert.deepEqual(parseLocator(above.block).locator.bounds, { height: 24, space: "render-pixels", width: 24, x: -4, y: -2 });
});

test("two blocks may not claim the same preview", () => {
  // `issuesForPreview` matches rows by preview id as well as by component, so one preview named by
  // two blocks would show the same issue twice on that page and count two in its badge.
  const shape = shared.bodies.find((body) => body.name === "umbrella-repeats-a-preview");
  const errors = [];
  const index = buildIssueIndex(
    [{ html_url: "https://github.com/yschimke/m3-catalog/issues/42", title: "Elevated shadow level", body: shape.body, state: "open" }],
    { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error) },
  );
  assert.deepEqual(index.issues, []);
  assert.deepEqual(errors, [shape.parse.error]);
});

test("two blocks may not claim the same reference", () => {
  const shape = shared.bodies.find((body) => body.name === "umbrella-repeats-a-reference");
  const errors = [];
  const index = buildIssueIndex(
    [{ html_url: "https://github.com/yschimke/m3-catalog/issues/42", title: "Elevated shadow level", body: shape.body, state: "open" }],
    { generatedAt: "2026-08-15T10:00:00Z", onError: (_, error) => errors.push(error) },
  );
  assert.deepEqual(index.issues, []);
  assert.deepEqual(errors, [shape.parse.error]);
});

// -----------------------------------------------------------------------------------------------
// The mirror. Not expressible as a fixture, because it is about two files agreeing rather than about
// any one parse.
// -----------------------------------------------------------------------------------------------

// `parity-issues.mjs` exists twice: here, and in yschimke/compose-preview-server, whose
// ServeIssueReport and serve-web writers are tested against the same `fixtures/parity-locators.json`.
// The copy in THIS repository is the one that runs in production — `parity-issues-reusable.yml`
// checks out `yschimke/compose-ai-tools` and runs `emit-parity-issues.mjs`, which imports the local
// module — so a feature that lands only on the server's copy is dead code everywhere it matters.
//
// That is not hypothetical. `scope` was added to the server's copy and not to this one, and because
// nothing compared them, every `scope: variant` locator was silently flattened to component scope
// for months: the server and the browser both implement variant-scoped bug pills, and no index ever
// carried a `scope` field for them to act on (compose-ai-tools#5205).
//
// Byte-for-byte, deliberately. The two copies have no repository-specific content — no paths, no
// imports beyond node builtins — so there is no legitimate reason for them to differ, and a
// tolerant comparison is how the last divergence survived. Same optional-sibling arrangement the
// tuning mirror in known-difference-score.test.mjs uses: CI supplies the checkout, and a local run
// without one SKIPS with a reason rather than passing vacuously.
const HERE = dirname(fileURLToPath(import.meta.url));
const SERVER_COPY = join(
  (process.env.COMPOSE_PREVIEW_SERVER_ROOT ?? "").trim() ||
    join(HERE, "..", "..", "..", "compose-preview-server"),
  "scripts/design-artifacts/parity-issues.mjs",
);
const NO_SERVER = {
  skip: existsSync(SERVER_COPY)
    ? false
    : "no compose-preview-server checkout (set COMPOSE_PREVIEW_SERVER_ROOT) — the second copy of " +
      "parity-issues.mjs lives there since #4732",
};

test("the producer is byte-identical to the preview server's copy", NO_SERVER, () => {
  assert.equal(
    readFileSync(SERVER_COPY, "utf8"),
    readFileSync(join(HERE, "parity-issues.mjs"), "utf8"),
    "scripts/design-artifacts/parity-issues.mjs has drifted from the copy in compose-preview-server. " +
      "Both are read by engines that must agree on the wire format; sync them rather than patching one.",
  );
});
