import { test } from "node:test";
import assert from "node:assert/strict";

import { familiesFromCsv, renderList } from "./build-google-fonts-list.mjs";

test("familiesFromCsv reads column 1, dedupes across tag rows, sorts case-insensitively", () => {
  const csv = [
    "Roboto Flex,,/Sans/Grotesque,10",
    "Roboto Flex,,/Feeling/Business,75",
    "ABeeZee,,/Expressive/Active,10",
    "abcde,,/x,1",
    "Lobster Two,,/Serif,3",
  ].join("\n");
  // Case-insensitive: "abcde" precedes "ABeeZee" (…c… < …e…); case is not the primary key.
  assert.deepEqual(familiesFromCsv(csv), ["abcde", "ABeeZee", "Lobster Two", "Roboto Flex"]);
});

test("familiesFromCsv tolerates trailing newline, blank lines, and a name with no comma", () => {
  const csv = "Inter,,/x,1\n\nPoppins\n";
  assert.deepEqual(familiesFromCsv(csv), ["Inter", "Poppins"]);
});

test("familiesFromCsv trims surrounding whitespace on the family column", () => {
  assert.deepEqual(familiesFromCsv("  Open Sans  ,,/x,1"), ["Open Sans"]);
});

test("renderList emits a provenance header then one family per line, newline-terminated", () => {
  const body = renderList(["Aaa", "Bbb"]);
  const lines = body.split("\n");
  assert.match(lines[0], /^# Google Fonts family names/);
  assert.equal(body.endsWith("Aaa\nBbb\n"), true);
  // Every non-comment line is a family; comments are provenance the loader skips.
  assert.deepEqual(
    lines.filter((l) => l && !l.startsWith("#")),
    ["Aaa", "Bbb"],
  );
});
