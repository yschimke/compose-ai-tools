import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./validate-stage.mjs", import.meta.url));
const roots = [];

afterEach(() => {
  for (const root of roots.splice(0))
    fs.rmSync(root, { recursive: true, force: true });
});

function fixture(ids = ["one", "two"]) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "rc-lane-ab-"));
  roots.push(root);
  const input = path.join(root, "input");
  const lanes = path.join(root, "lanes");
  fs.mkdirSync(input);
  fs.mkdirSync(path.join(lanes, "view"), { recursive: true });
  fs.mkdirSync(path.join(lanes, "embedded"), { recursive: true });
  fs.writeFileSync(
    path.join(input, "manifest.json"),
    JSON.stringify(ids.map((id) => ({ id, width: 1, height: 1 }))),
  );
  for (const id of ids) fs.writeFileSync(path.join(input, `${id}.rc`), "rc");
  return { input, lanes, ids };
}

function run(mode, input, lanes) {
  return spawnSync(
    process.execPath,
    [script, mode, input, ...(lanes ? [lanes] : [])],
    {
      encoding: "utf8",
    },
  );
}

test("accepts exactly the manifest inputs and one result per lane", () => {
  const { input, lanes, ids } = fixture();
  for (const id of ids) {
    fs.writeFileSync(path.join(lanes, "view", `${id}.png`), "png");
    fs.writeFileSync(path.join(lanes, "embedded", `${id}.png`), "png");
  }
  assert.equal(run("results", input, lanes).status, 0);
});

test("rejects missing and stale staged inputs", () => {
  const { input } = fixture();
  fs.rmSync(path.join(input, "one.rc"));
  fs.writeFileSync(path.join(input, "stale.rc"), "rc");
  const result = run("inputs", input);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /missing: one/);
  assert.match(result.stderr, /extra: stale/);
});

test("rejects a view error instead of silently omitting it from scoring", () => {
  const { input, lanes, ids } = fixture();
  fs.writeFileSync(path.join(lanes, "view", "one.error"), "boom");
  fs.writeFileSync(path.join(lanes, "view", "two.png"), "png");
  for (const id of ids)
    fs.writeFileSync(path.join(lanes, "embedded", `${id}.png`), "png");
  const result = run("results", input, lanes);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /view failed to render one/);
});

test("allows one embedded error but rejects missing or duplicate results", () => {
  const { input, lanes, ids } = fixture();
  for (const id of ids)
    fs.writeFileSync(path.join(lanes, "view", `${id}.png`), "png");
  fs.writeFileSync(path.join(lanes, "embedded", "one.error"), "boom");
  fs.writeFileSync(path.join(lanes, "embedded", "two.png"), "png");
  assert.equal(run("results", input, lanes).status, 0);

  fs.writeFileSync(path.join(lanes, "embedded", "one.png"), "png");
  const duplicate = run("results", input, lanes);
  assert.equal(duplicate.status, 1);
  assert.match(duplicate.stderr, /exactly one result for one/);
});
