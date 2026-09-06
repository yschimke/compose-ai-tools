/**
 * Publish the render bundle's discovered component record (`components.json`) beside `catalog.json`.
 *
 * The Gradle plugin writes `components.json` — the composables a module's previews render, with
 * their recovered signatures and a proven call site for each — into every bundle it packs, and the
 * UI builder in compose-preview-server reads it to offer a served catalog's composables as a
 * component pack and to export against them. A catalog that publishes a live bundle therefore
 * carries the record already, inside a 15–50 MB polyglot a consumer has to fetch and unzip to reach
 * one ~1 MB JSON file; a catalog that publishes no live bundle carries it nowhere a reader can reach.
 *
 * So the record is copied out to the branch root and declared on the manifest as `componentsFile`,
 * the way `tokensFile` declares the token set: a consumer fetches the one file it wants, and a
 * catalog with no executable bundle is still authorable from. Same rule as the motion captures —
 * bytes a reader is told about must exist somewhere the reader can reach.
 *
 * Primary bundle only. A multi-module catalog's additional bundles each carry their own record
 * (`combinedBundleEntries` is primary-wins), and publishing those under `bundle/modules/<key>/`
 * is a follow-up for the day a consumer wants them; the primary's is the record the catalog's own
 * previews were discovered from.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

/** The bundle entry and the published branch path — one name, by construction. */
export const COMPONENT_RECORD_FILE = "components.json";

/**
 * Whether `bytes` are a component record a consumer will read: a JSON object carrying a numeric
 * `schemaVersion` and a `components` array. Structural only — which versions a consumer generates
 * from is the consumer's to decide and say, and refusing an unknown future version here would
 * report a newer plugin's record as a broken bundle.
 */
export function parseComponentRecord(bytes) {
  if (!bytes) return null;
  let parsed;
  try {
    parsed = JSON.parse(Buffer.from(bytes).toString("utf8"));
  } catch {
    return null;
  }
  if (
    !parsed ||
    typeof parsed !== "object" ||
    Array.isArray(parsed) ||
    typeof parsed.schemaVersion !== "number" ||
    !Array.isArray(parsed.components)
  ) {
    return null;
  }
  return parsed;
}

/**
 * Copy the record out of the bundle `entries` into `<outPath>/components.json`.
 *
 * Returns `{ path, schemaVersion, components }` — what the caller stamps on the manifest and logs —
 * or `null` when the bundle carries no readable record, in which case nothing is written and the
 * manifest says nothing: a consumer falls back to the live bundle's own copy where there is one.
 */
export async function publishComponentRecord(entries, outPath) {
  const record = parseComponentRecord(entries?.[COMPONENT_RECORD_FILE]);
  if (!record) return null;
  const target = join(outPath, COMPONENT_RECORD_FILE);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, Buffer.from(entries[COMPONENT_RECORD_FILE]));
  return {
    path: COMPONENT_RECORD_FILE,
    schemaVersion: record.schemaVersion,
    components: record.components.length,
  };
}
