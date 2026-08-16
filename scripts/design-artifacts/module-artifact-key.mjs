#!/usr/bin/env node
import { parseArgs } from "node:util";

import { moduleArtifactKey } from "./multi-module-catalog.mjs";

const { values } = parseArgs({ options: { module: { type: "string" } } });
if (!values.module) {
  console.error("usage: module-artifact-key --module <:path>");
  process.exit(2);
}
process.stdout.write(`${moduleArtifactKey(values.module)}\n`);
