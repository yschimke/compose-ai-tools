#!/usr/bin/env node
// Repairs the mangled-backtick markdown that agent-written PR descriptions keep
// arriving with, so the visual evidence a PR body is *supposed* to show
// actually renders.
//
// The failure mode, seen verbatim in real PR bodies:
//
//   ![before: one lane](``https://raw.githubusercontent.com/.../before.png)``
//
// GitHub renders that as literal text plus a stray code span — the image never
// appears — and the repo's PR rules require the pixels to be *viewable inline*,
// not merely linked. The fix is mechanical: a link destination never legally
// starts with a backtick, so backticks wrapping the destination of a markdown
// link or image are unambiguously junk and can be dropped.
//
// Deliberately narrow. Only backtick runs ADJACENT TO A LINK DESTINATION are
// touched, and only when the destination still looks like a URL or path:
//
//   ](``URL)``   ->  ](URL)     opener after `](`, closer trailing the `)`
//   ](``URL``)   ->  ](URL)     both runs inside the parens
//   ](``URL)     ->  ](URL)     stray opener only
//
// NOT touched, because they are legitimate markdown that someone may have
// written on purpose:
//   • a whole link/image inside a code span — `![alt](url)` — which is how you
//     quote markdown literally (this file's own comments do it);
//   • anything inside a fenced code block, for the same reason;
//   • backticks anywhere else on the line.
//
// The transform is idempotent: its own output contains no backtick adjacent to
// a link destination, so a second pass is a no-op. That matters because the
// workflow's own edit re-fires the `edited` trigger — the second run has to
// find nothing to change and stop, rather than oscillating.
//
// Usage:
//   fix-pr-body-markdown.mjs [<file>]   read <file> (or stdin), write the
//                                       repaired text to stdout
//   fix-pr-body-markdown.mjs --check [<file>]
//                                       write nothing; exit 0 if already clean,
//                                       exit 1 if it would change
//
// Exit codes: 0 clean / repaired, 1 --check found problems, 2 bad usage.

import { readFileSync } from 'node:fs'

// A link destination we are willing to rewrite: no backticks (they are what we
// are stripping), no whitespace, no parens (bare-destination form only), and
// shaped like a target — a URL, a path, or an anchor. Anything else is left
// alone rather than guessed at.
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

// Fenced code blocks are quoted content — an example of the broken syntax
// inside a fence (a bug report about it, say) must survive untouched. A fence
// opens on ``` / ~~~ (3+, indented up to 3 spaces) and closes on a run of the
// same character that is at least as long, per CommonMark.
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
