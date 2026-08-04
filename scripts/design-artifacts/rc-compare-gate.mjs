import fs from "node:fs";

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Read the deliberately small temporary-exception format used by the strict CMP/Wasm lane:
 *
 *   { "entries": [{ "id": "preview.id", "reason": "issue URL or explanation",
 *                   "expires": "2026-08-10" }] }
 */
export function readCmpWasmAllowlist(file, today = new Date().toISOString().slice(0, 10)) {
  if (!file) return new Map();
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!parsed || !Array.isArray(parsed.entries)) {
    throw new Error("CMP/Wasm allowlist must be an object with an entries array");
  }
  const result = new Map();
  for (const [index, entry] of parsed.entries.entries()) {
    const label = `CMP/Wasm allowlist entry ${index + 1}`;
    if (!entry || typeof entry.id !== "string" || !entry.id.trim()) {
      throw new Error(`${label} must have a non-empty id`);
    }
    if (typeof entry.reason !== "string" || !entry.reason.trim()) {
      throw new Error(`${label} (${entry.id}) must have a non-empty reason`);
    }
    if (typeof entry.expires !== "string" || !ISO_DATE.test(entry.expires)) {
      throw new Error(`${label} (${entry.id}) must have an expires date in YYYY-MM-DD form`);
    }
    if (new Date(`${entry.expires}T00:00:00Z`).toISOString().slice(0, 10) !== entry.expires) {
      throw new Error(`${label} (${entry.id}) has an invalid expires date ${entry.expires}`);
    }
    if (entry.expires < today) {
      throw new Error(`${label} (${entry.id}) expired on ${entry.expires}`);
    }
    if (result.has(entry.id)) throw new Error(`${label} duplicates id ${entry.id}`);
    result.set(entry.id, { reason: entry.reason.trim(), expires: entry.expires });
  }
  return result;
}

/** Return the strict-lane verdict without throwing, so callers can write all evidence first. */
export function evaluateCmpWasmGate(expectedIds, rows, allowlist = new Map()) {
  const byId = new Map(rows.map((row) => [row.id, row]));
  const failures = [];
  const allowed = [];
  for (const id of expectedIds) {
    const row = byId.get(id);
    if (row?.cmpWasmRendered === true) continue;
    const exception = allowlist.get(id);
    const note = row?.cmpWasmNote || (row ? "CMP/Wasm produced no result" : "row was dropped");
    if (exception) allowed.push({ id, note, ...exception });
    else failures.push({ id, note });
  }
  return {
    passed: failures.length === 0,
    expected: expectedIds.length,
    rendered: expectedIds.filter((id) => byId.get(id)?.cmpWasmRendered === true).length,
    allowed,
    failures,
  };
}

export function formatCmpWasmGate(gate) {
  const lines = [
    `CMP/Wasm: ${gate.rendered}/${gate.expected} rendered, ${gate.allowed.length} temporarily allowed, ${gate.failures.length} failed.`,
  ];
  for (const failure of gate.failures) lines.push(`- ${failure.id}: ${failure.note}`);
  for (const entry of gate.allowed) {
    lines.push(`- ${entry.id}: allowed until ${entry.expires} — ${entry.reason} (${entry.note})`);
  }
  return lines.join("\n");
}
