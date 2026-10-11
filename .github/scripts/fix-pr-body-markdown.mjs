#!/usr/bin/env node
// Repairs the mangled-backtick markdown agent-written PR bodies arrive with, so image evidence
// renders inline:
//
//   ![before: one lane](``https://raw.githubusercontent.com/.../before.png)``
//
// A link destination never legally starts with a backtick, so backtick runs adjacent to a link
// destination that still looks like a URL or path are dropped:
//
//   ](``URL)``   ->  ](URL)
//   ](``URL``)   ->  ](URL)
//   ](``URL)     ->  ](URL)
//
// Code spans, fenced blocks and backticks elsewhere are left alone. Idempotent, so the workflow's
// own edit (which re-fires `edited`) finds nothing to change.
//
// Usage:
//   fix-pr-body-markdown.mjs [<file>]          read <file> (or stdin), write repaired text to stdout
//   fix-pr-body-markdown.mjs --check [<file>]  exit 0 if clean, 1 if it would change
//
// Exit codes: 0 clean / repaired, 1 --check found problems, 2 bad usage.

import { readFileSync } from 'node:fs'

// A destination we'll rewrite: no backticks, whitespace or parens, shaped like a URL, path or
// anchor.
const DEST = String.raw`[^\s\`()<>]+`
const DEST_LOOKS_LIKE_TARGET = /^(?:[a-z][a-z0-9+.-]*:|[.#/]|[\w.@+-]+\/)/i

// `[text](` or `![alt](`. The label may not span lines and may not itself
// contain `]`, which keeps the match anchored to one link.
const LABEL = String.raw`!?\[[^\]\n]*\]\(`

const RULES = [
  // `](``URL)``  — the observed shape: opener after `](`, closer after the `)`.
  new RegExp(String.raw`(${LABEL})\`+(${DEST})\)\`+`, 'g'),
  // `](``URL``)` — both runs inside the parens.
  new RegExp(String.raw`(${LABEL})\`+(${DEST})\`+\)`, 'g'),
  // `](``URL)`   — stray opener, nothing trailing.
  new RegExp(String.raw`(${LABEL})\`+(${DEST})\)`, 'g'),
]

function repairLine(line) {
  let out = line
  for (const rule of RULES) {
    out = out.replace(rule, (match, label, dest) =>
      DEST_LOOKS_LIKE_TARGET.test(dest) ? `${label}${dest})` : match,
    )
  }
  return out
}

// Fenced code blocks are quoted content and stay untouched. CommonMark: opens on 3+ ``` / ~~~
// (indented ≤ 3), closes on a run of the same character at least as long.
const FENCE = /^ {0,3}(`{3,}|~{3,})(.*)$/

/**
 * @param {string} text a PR body (or any markdown)
 * @returns {string} the same text with junk backticks around link destinations removed
 */
export function fixPrBodyMarkdown(text) {
  if (!text) return text ?? ''
  const lines = text.split('\n')
  let fence = null // the open fence's marker, or null outside a fence
  const out = lines.map((line) => {
    const m = FENCE.exec(line)
    if (fence) {
      // Only a run of the same character, at least as long, and with nothing
      // after it closes the fence.
      if (m && m[1][0] === fence[0] && m[1].length >= fence.length && m[2].trim() === '') {
        fence = null
      }
      return line
    }
    if (m) {
      // A ``` fence's info string may not contain a backtick; ~~~ has no such
      // rule. Anything else is an ordinary line.
      if (m[1][0] !== '`' || !m[2].includes('`')) {
        fence = m[1]
        return line
      }
    }
    return repairLine(line)
  })
  return out.join('\n')
}

function main(argv) {
  const args = argv.slice(2)
  const check = args[0] === '--check'
  const rest = check ? args.slice(1) : args
  if (rest.length > 1 || rest.some((a) => a.startsWith('--'))) {
    process.stderr.write('usage: fix-pr-body-markdown.mjs [--check] [<file>]\n')
    return 2
  }
  const source = rest[0] ?? 0 // fd 0 = stdin
  const input = readFileSync(source, 'utf8')
  const fixed = fixPrBodyMarkdown(input)
  if (check) {
    if (fixed === input) return 0
    process.stderr.write('PR body has backticks wrapping a link destination\n')
    return 1
  }
  process.stdout.write(fixed)
  return 0
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = main(process.argv)
}
