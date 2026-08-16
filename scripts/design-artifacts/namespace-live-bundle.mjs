#!/usr/bin/env node
import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { namespaceLiveBundle } from "./live-bundle-namespace.mjs";

const { values } = parseArgs({
  options: {
    input: { type: "string" },
    output: { type: "string" },
    module: { type: "string" },
  },
});
if (!values.input || !values.output || !values.module) {
  console.error("usage: namespace-live-bundle --input <bundle> --output <bundle> --module <:path>");
  process.exit(2);
}

const input = new Uint8Array(await readFile(values.input));
await writeFile(values.output, namespaceLiveBundle(input, values.module));
