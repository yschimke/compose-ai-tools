#!/usr/bin/env node
// Checks a catalog's `ui-builder.guidelines.json` before it is published beside its
// `ui-builder.json` (format: compose-ui-builder/catalog-guidelines/v1). Only what a reader cannot
// work around — the same checks as the Gradle plugin's UiBuilderGuidelinesFile — and it fails the
// publish loudly, because a guidelines file that is silently dropped looks exactly like a catalog
// that has none.
//
// A file's `includes` (shared rule packs, each `{ url, sha256 }`) are fetched and checked against
// their pins here too: the Gradle plugin flattens them into the published file, and a pin that does
// not resolve would otherwise publish a catalog quietly missing every rule of the pack.
//
//   node validate-ui-builder-guidelines.mjs --guidelines <file> [--catalog <ui-builder catalog id>]
import { createHash } from 'node:crypto';
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
// The engine's and the Gradle plugin's bounds (GuidelinesIncludes, UiBuilderGuidelinesFile).
const MAX_INCLUDES = 8;
const MAX_PACK_BYTES = 1024 * 1024;
const problems = [];

// The checks every rule must pass, in the catalog's own file and in every pack it includes: the
// loader refuses a merged file with one bad rule, so a bad pack rule must fail here, not there.
const checkRules = (rules, where) => {
  if (!Array.isArray(rules)) return problems.push(`${where}\`rules\` is not a list`);
  rules.forEach((rule, index) => {
    const name = (rule && typeof rule.id === 'string' && rule.id) || `#${index}`;
    if (!rule || typeof rule !== 'object') {
      return problems.push(`${where}rule ${name} is not an object`);
    }
    for (const field of ['id', 'kind', 'severity', 'guidance', 'check', 'source']) {
      if (typeof rule[field] !== 'string' || !rule[field].trim()) {
        problems.push(`${where}rule ${name} has no \`${field}\``);
      }
    }
    if (typeof rule.check === 'string' && !rule.check.includes('?')) {
      problems.push(`${where}rule ${name}'s \`check\` is not a question`);
    }
    if (typeof rule.source === 'string' && !rule.source.startsWith('https://')) {
      problems.push(`${where}rule ${name}'s \`source\` is not an https URL`);
    }
  });
};

// The body of [response], refusing it as soon as it passes MAX_PACK_BYTES rather than after
// buffering all of it.
const readCapped = async (response) => {
  const chunks = [];
  let size = 0;
  const reader = response.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > MAX_PACK_BYTES) {
      await reader.cancel();
      throw new Error(`larger than ${MAX_PACK_BYTES} bytes`);
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks);
};

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
  checkRules(root.rules, '');
  if (root.includes !== undefined && !Array.isArray(root.includes)) {
    problems.push('`includes` is not a list');
  }
  const includes = Array.isArray(root.includes) ? root.includes : [];
  if (includes.length > MAX_INCLUDES) {
    problems.push(`more than ${MAX_INCLUDES} includes`);
  }
  for (const [index, include] of includes.slice(0, MAX_INCLUDES).entries()) {
    const url = include && typeof include.url === 'string' ? include.url : undefined;
    if (!url || !url.startsWith('https://')) {
      problems.push(`include #${index}'s \`url\` is not an https URL`);
      continue;
    }
    if (typeof include.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(include.sha256)) {
      problems.push(`include ${url} has no \`sha256\` (64 lowercase hex digits)`);
      continue;
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
      if (!response.ok) throw new Error(`answered ${response.status}`);
      const bytes = await readCapped(response);
      const actual = createHash('sha256').update(bytes).digest('hex');
      if (actual !== include.sha256) {
        problems.push(`include ${url} does not match its pin (sha256 ${actual})`);
        continue;
      }
      const pack = JSON.parse(bytes.toString('utf8'));
      if (!pack || typeof pack !== 'object' || pack.schema !== SCHEMA) {
        problems.push(`include ${url} is not a guidelines pack`);
        continue;
      }
      if (Array.isArray(pack.includes) && pack.includes.length) {
        problems.push(`include ${url} includes others; packs may not nest`);
      }
      checkRules(pack.rules, `include ${url}: `);
    } catch (e) {
      problems.push(`include ${url} could not be read (${e.message})`);
    }
  }
}

if (problems.length) {
  for (const p of problems) {
    console.log(`::error file=${file},title=Malformed ui-builder.guidelines.json::${p}`);
  }
  process.exit(1);
}
console.log(
  `${file}: ${root.rules.length} rule(s), ${(root.frames ?? []).length} frame(s), ` +
    `${(root.includes ?? []).length} include(s), ok`,
);
