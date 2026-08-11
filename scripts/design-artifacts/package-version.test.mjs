import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { installedPackageVersion } from "./package-version.mjs";

// The expected version comes from the lockfile rather than a literal. A literal pins the assertion
// to whatever `@design-parity/*` release happened to be installed the day it was written, so the
// next routine dependency bump reds CI for a reason that has nothing to do with this helper. The
// lockfile is an independent source from the installed package manifest the helper walks to, so the
// assertion still has teeth: it catches a resolve that lands on the wrong copy of the package.
const lockfile = JSON.parse(
  readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), "package-lock.json"),
    "utf8",
  ),
);
const lockedVersion =
  lockfile.packages["node_modules/@design-parity/catalog-export"].version;

test("reads the catalog-export version when package.json is not exported", () => {
  assert.match(lockedVersion, /^\d+\.\d+\.\d+/);
  assert.equal(
    installedPackageVersion("@design-parity/catalog-export", import.meta.url),
    lockedVersion,
  );
});

test("returns undefined for an unavailable package", () => {
  assert.equal(
    installedPackageVersion("@design-parity/does-not-exist", import.meta.url),
    undefined,
  );
});
