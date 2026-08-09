import assert from "node:assert/strict";
import test from "node:test";

import { installedPackageVersion } from "./package-version.mjs";

test("reads the catalog-export version when package.json is not exported", () => {
  assert.equal(
    installedPackageVersion("@design-parity/catalog-export", import.meta.url),
    "0.1.45",
  );
});

test("returns undefined for an unavailable package", () => {
  assert.equal(
    installedPackageVersion("@design-parity/does-not-exist", import.meta.url),
    undefined,
  );
});
