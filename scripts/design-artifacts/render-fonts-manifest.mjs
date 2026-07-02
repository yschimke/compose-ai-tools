/**
 * Generate the in-browser Wasm tier's `fonts.json` from the per-preview `fonts/used` records
 * (`previews/<id>.fonts.json`, carried by `compose-preview bundle pack --with-semantics`).
 *
 * The wasm dist ships a hand-authored manifest as the dev-time default; the export regenerates it
 * from what this catalog's previews *actually resolved*, so the published manifest tracks the
 * component set instead of needing manual upkeep. Families map onto the font files vendored in the
 * dist (extracted from Robolectric's nativeruntime — the exact files the snapshot renderer
 * rasterizes with); a recorded family with no vendored file is reported and dropped, so the app
 * falls back to its bundled font for it, exactly as if the manifest had never listed it.
 */

/**
 * Vendored files by manifest family and design weight, mirroring Android's own `fonts.xml`
 * mapping for the generic families (`serif` → Noto Serif, `monospace` → Droid Sans Mono) and the
 * M3 default (Roboto). The recorder reports `FontFamily.Default` for theme text; `sans-serif` is
 * Android's name for the same Roboto stack, kept as a generic entry so an explicit
 * `FontFamily.SansSerif` request also resolves to the exact files.
 */
const FAMILY_FILES = {
  Roboto: { role: "default", files: { 400: "Roboto-Regular.ttf", 500: "Roboto-Medium.ttf" } },
  serif: { role: "generic", files: { 400: "NotoSerif-Regular.ttf" } },
  monospace: { role: "generic", files: { 400: "DroidSansMono.ttf" } },
  "sans-serif": {
    role: "generic",
    files: { 400: "Roboto-Regular.ttf", 500: "Roboto-Medium.ttf" },
  },
};

/** Map a recorded `requestedFamily` onto a [FAMILY_FILES] key, or null when unknown. */
function familyKey(requestedFamily) {
  if (!requestedFamily || requestedFamily === "FontFamily.Default") return "Roboto";
  return Object.hasOwn(FAMILY_FILES, requestedFamily) ? requestedFamily : null;
}

/**
 * Parse every `previews/<id>.fonts.json` entry out of a read preview bundle into an array of
 * `fonts/used` payloads (`{fonts: [{requestedFamily, weight, style, …}]}`). Unparseable entries
 * are skipped — the record is best-effort by design.
 */
export function fontsPayloadsFromBundle(bundle) {
  const out = [];
  for (const preview of bundle.previews ?? []) {
    const bytes = bundle.entries?.[`previews/${preview.id}.fonts.json`];
    if (!bytes) continue;
    try {
      out.push(JSON.parse(new TextDecoder().decode(bytes)));
    } catch {
      // best-effort sidecar; a corrupt record just doesn't contribute
    }
  }
  return out;
}

/**
 * Build the manifest from [payloads] (an array of `fonts/used` payloads), keeping only files
 * present in [availableFiles] (the dist's `fonts/` directory listing, a Set). Returns
 * `{ manifest, warnings }`; `manifest` is null when nothing usable was recorded (callers keep the
 * committed manifest in that case). The default (Roboto) family is always included when its files
 * are available — the app's whole M3 type scale hangs off it even for previews that render no
 * text of their own.
 */
export function buildFontsManifest(payloads, availableFiles) {
  const warnings = [];
  const wanted = new Map(); // familyKey -> Map(weight -> file)
  const unknown = new Set();
  const missing = new Set();
  let recorded = 0;

  const want = (key, weight) => {
    const table = FAMILY_FILES[key].files;
    const weights = Object.keys(table).map(Number);
    const w = Number(weight) || 400;
    const nearest = weights.reduce((a, b) => (Math.abs(b - w) < Math.abs(a - w) ? b : a));
    const file = table[nearest];
    if (!availableFiles.has(file)) {
      if (!missing.has(file)) {
        missing.add(file);
        warnings.push(`'${key}' needs ${file}, which the dist fonts/ does not carry — dropped`);
      }
      return;
    }
    if (!wanted.has(key)) wanted.set(key, new Map());
    wanted.get(key).set(nearest, file);
  };

  for (const payload of payloads) {
    for (const entry of payload?.fonts ?? []) {
      recorded++;
      const key = familyKey(entry.requestedFamily);
      if (key === null) {
        if (!unknown.has(entry.requestedFamily)) {
          unknown.add(entry.requestedFamily);
          warnings.push(
            `recorded family '${entry.requestedFamily}' has no vendored file — the wasm tier ` +
              `falls back to its bundled font for it`,
          );
        }
        continue;
      }
      want(key, entry.weight);
    }
  }
  if (recorded === 0) return { manifest: null, warnings };

  // The M3 typography always needs the default family, even if every recorded resolution was a
  // generic one (a text-less catalog still themes its diagnostics through it).
  if (!wanted.has("Roboto")) {
    for (const weight of Object.keys(FAMILY_FILES.Roboto.files)) want("Roboto", weight);
  }
  if (wanted.size === 0) return { manifest: null, warnings };

  const roleOrder = (key) => (FAMILY_FILES[key].role === "default" ? 0 : 1);
  const families = [...wanted.entries()]
    .sort(([a], [b]) => roleOrder(a) - roleOrder(b) || a.localeCompare(b))
    .map(([key, byWeight]) => ({
      name: key,
      role: FAMILY_FILES[key].role,
      fonts: [...byWeight.entries()]
        .sort(([a], [b]) => a - b)
        .map(([weight, file]) => ({ file, weight })),
    }));
  return { manifest: { version: 1, families }, warnings };
}
