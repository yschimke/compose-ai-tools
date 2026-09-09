#!/usr/bin/env node
// Validate a `ui-builder.policy.json` — structurally, and before a ~90-minute Design Artifacts
// render is the first thing to read it.
//
//   node validate-ui-builder-policy.mjs                                  # ./ui-builder.policy.json
//   node validate-ui-builder-policy.mjs --policy remote-catalog/ui-builder.policy.json
//   node validate-ui-builder-policy.mjs --policy … --strict              # warnings fail too
//
// Exit 0 when there are no errors (warnings do not fail unless --strict); 1 on errors or bad args.
// A missing file is exit 0 and a line saying so: most catalogs author no policy, and that is the
// design rather than an omission.

import { readFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { validatePolicy } from "./ui-builder-policy.mjs";

const { values } = parseArgs({
  options: {
    policy: { type: "string", default: "ui-builder.policy.json" },
    strict: { type: "boolean", default: false },
  },
});

let text;
try {
  text = await readFile(values.policy, "utf8");
} catch (failure) {
  if (failure.code === "ENOENT") {
    console.log(`${values.policy}: absent — this catalog does not describe itself to a UI builder.`);
    process.exit(0);
  }
  console.error(`${values.policy}: ${failure.message}`);
  process.exit(1);
}

let policy;
try {
  policy = JSON.parse(text);
} catch (failure) {
  console.error(`${values.policy}: not valid JSON — ${failure.message}`);
  process.exit(1);
}

const { errors, warnings } = validatePolicy(policy);

for (const warning of warnings) console.log(`  warn  ${warning}`);
for (const error of errors) console.error(`  error ${error}`);

if (errors.length === 0 && warnings.length === 0) {
  console.log(`${values.policy}: ok (platform ${policy.platform}).`);
} else {
  console.log(
    `${values.policy}: ${errors.length} error(s), ${warnings.length} warning(s).`,
  );
}

process.exit(errors.length > 0 || (values.strict && warnings.length > 0) ? 1 : 0);
