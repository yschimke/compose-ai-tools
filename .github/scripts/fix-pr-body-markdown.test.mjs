// Self-test for the PR-body markdown repair. Run: node --test .github/scripts/
//
// This runs on every PR (the `selftest` job in pr-body-syntax.yml) because the
// repair edits PR descriptions unattended: a matcher that over-reaches silently
// rewrites someone's prose, and one that under-reaches silently leaves the
// evidence images broken. Both failure modes are invisible without tests.

import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { fixPrBodyMarkdown } from './fix-pr-body-markdown.mjs'

const SCRIPT = fileURLToPath(new URL('./fix-pr-body-markdown.mjs', import.meta.url))

const URL_ = 'https://raw.githubusercontent.com/yschimke/compose-ai-tools/0a11634/docs/x.png'

test('repairs the observed shape: opener after ]( and closer after )', () => {
  assert.equal(
    fixPrBodyMarkdown(`![before: one lane](\`\`${URL_})\`\``),
    `![before: one lane](${URL_})`,
  )
})

test('repairs both backtick runs inside the parens', () => {
  assert.equal(fixPrBodyMarkdown(`![alt](\`\`${URL_}\`\`)`), `![alt](${URL_})`)
})

test('repairs a stray opener with nothing trailing', () => {
  assert.equal(fixPrBodyMarkdown(`![alt](\`${URL_})`), `![alt](${URL_})`)
})

test('repairs plain links, not just images', () => {
  assert.equal(fixPrBodyMarkdown('see [the run](`https://example.com/run/1)`'), 'see [the run](https://example.com/run/1)')
})

test('repairs relative paths and anchors', () => {
  assert.equal(
    fixPrBodyMarkdown('![w](``docs/design/evidence/a.png)``'),
    '![w](docs/design/evidence/a.png)',
  )
  assert.equal(fixPrBodyMarkdown('[why](`./docs/AGENTS.md#pr-workflow`)'), '[why](./docs/AGENTS.md#pr-workflow)')
})

test('repairs several links on one line and across a body', () => {
  const body = [
    '## Visual evidence',
    '',
    `**Before** — \`main\`: one lane.`,
    '',
    `![before](\`\`${URL_})\`\` ![after](\`\`${URL_})\`\``,
    '',
    'Unrelated `inline code` stays put.',
  ].join('\n')
  assert.equal(
    fixPrBodyMarkdown(body),
    body.replaceAll(`(\`\`${URL_})\`\``, `(${URL_})`),
  )
})

test('is idempotent', () => {
  const once = fixPrBodyMarkdown(`![alt](\`\`${URL_})\`\``)
  assert.equal(fixPrBodyMarkdown(once), once)
})

test('leaves well-formed markdown alone', () => {
  const body = `## Summary\n\n![alt](${URL_})\n\nRun \`./gradlew check\` — see [docs](docs/AGENTS.md).\n`
  assert.equal(fixPrBodyMarkdown(body), body)
})

test('leaves a link quoted inside a code span alone', () => {
  // Quoting markdown literally is legitimate; the backticks are not adjacent to
  // the destination, so nothing here is ambiguous.
  const body = 'write it as `![alt](https://example.com/a.png)` in the body'
  assert.equal(fixPrBodyMarkdown(body), body)
})

test('leaves fenced code blocks untouched', () => {
  const body = ['before the fence', '```markdown', `![alt](\`\`${URL_})\`\``, '```', 'after'].join('\n')
  assert.equal(fixPrBodyMarkdown(body), body)
})

test('leaves tilde fences untouched and resumes fixing after them', () => {
  const body = ['~~~', `![a](\`\`${URL_})\`\``, '~~~', `![b](\`\`${URL_})\`\``].join('\n')
  assert.equal(
    fixPrBodyMarkdown(body),
    ['~~~', `![a](\`\`${URL_})\`\``, '~~~', `![b](${URL_})`].join('\n'),
  )
})

test('a longer closing fence still closes; a shorter run does not', () => {
  const body = ['````', `![a](\`\`${URL_})\`\``, '`````', `![b](\`\`${URL_})\`\``].join('\n')
  assert.equal(
    fixPrBodyMarkdown(body),
    ['````', `![a](\`\`${URL_})\`\``, '`````', `![b](${URL_})`].join('\n'),
  )
})

test('leaves a destination that does not look like a target alone', () => {
  const body = 'the placeholder [x](`TODO`) is not a link yet'
  assert.equal(fixPrBodyMarkdown(body), body)
})

test('handles empty and missing input', () => {
  assert.equal(fixPrBodyMarkdown(''), '')
  assert.equal(fixPrBodyMarkdown(undefined), '')
  assert.equal(fixPrBodyMarkdown(null), '')
})

test('preserves trailing newlines exactly', () => {
  assert.equal(fixPrBodyMarkdown('a\n\n'), 'a\n\n')
  assert.equal(fixPrBodyMarkdown(`![a](\`\`${URL_})\`\`\n`), `![a](${URL_})\n`)
})

test('CLI repairs a file on stdout and --check reports the verdict', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pr-body-'))
  const broken = join(dir, 'broken.md')
  const clean = join(dir, 'clean.md')
  writeFileSync(broken, `![a](\`\`${URL_})\`\`\n`)
  writeFileSync(clean, `![a](${URL_})\n`)

  assert.equal(execFileSync('node', [SCRIPT, broken], { encoding: 'utf8' }), `![a](${URL_})\n`)
  execFileSync('node', [SCRIPT, '--check', clean])

  assert.throws(
    () => execFileSync('node', [SCRIPT, '--check', broken], { stdio: 'pipe' }),
    (e) => e.status === 1,
  )
})
