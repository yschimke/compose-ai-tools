#!/usr/bin/env node
// Checks a catalog's `ui-builder.guidelines.json` before it is published beside its
// `ui-builder.json` (format: compose-ui-builder/catalog-guidelines/v1). Only what a reader cannot
// work around — the same checks as the Gradle plugin's UiBuilderGuidelinesFile — and it fails the
// publish loudly, because a guidelines file that is silently dropped looks exactly like a catalog
// that has none.
//
//   node validate-ui-builder-guidelines.mjs --guidelines <file> [--catalog <ui-builder catalog id>]
import { readFileSync } from 'node:fs';

const args = process.argv.slice(2);
const arg = (name) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : undefined;
};
const file = arg('guidelines');
const catalogId = arg('catalog');
if (!file) {
  console.error('usage: validate-ui-builder-guidelines.mjs --guidelines <file> [--catalog <id>]');
  process.exit(2);
}

const SCHEMA = 'compose-ui-builder/catalog-guidelines/v1';
const problems = [];
let root;
try {
  root = JSON.parse(readFileSync(file, 'utf8'));
} catch (e) {
  problems.push(`is not readable JSON (${e.message})`);
}
if (root !== undefined && (root === null || typeof root !== 'object' || Array.isArray(root))) {
  problems.push('is not a JSON object');
} else if (root) {
  if (root.schema !== SCHEMA) problems.push(`\`schema\` is not \`${SCHEMA}\``);
  if (catalogId && root.catalog !== catalogId) {
    problems.push(`\`catalog\` is \`${root.catalog ?? 'missing'}\`, not \`${catalogId}\``);
  }
  if (!Number.isInteger(root.version)) problems.push('`version` is not an integer');
  if (!Array.isArray(root.rules)) problems.push('`rules` is not a list');
  (Array.isArray(root.rules) ? root.rules : []).forEach((rule, index) => {
    const name = (rule && typeof rule.id === 'string' && rule.id) || `#${index}`;
    if (!rule || typeof rule !== 'object') return problems.push(`rule ${name} is not an object`);
    for (const field of ['id', 'kind', 'severity', 'guidance', 'check', 'source']) {
      if (typeof rule[field] !== 'string' || !rule[field].trim()) {
        problems.push(`rule ${name} has no \`${field}\``);
      }
    }
    if (typeof rule.check === 'string' && !rule.check.includes('?')) {
      problems.push(`rule ${name}'s \`check\` is not a question`);
    }
    if (typeof rule.source === 'string' && !rule.source.startsWith('https://')) {
      problems.push(`rule ${name}'s \`source\` is not an https URL`);
    }
  });
}

if (problems.length) {
  for (const p of problems) {
    console.log(`::error file=${file},title=Malformed ui-builder.guidelines.json::${p}`);
  }
  process.exit(1);
}
console.log(`${file}: ${root.rules.length} rule(s), ${(root.frames ?? []).length} frame(s), ok`);
