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
 * Pull the display name out of a downloadable-GoogleFont `requestedFamily` record, e.g.
 * `Font(GoogleFont("Space Grotesk", bestEffort=true), weight=…, …)` → `"Space Grotesk"`. Returns
 * null for any record that isn't a GoogleFont request. The name is the same string the consumer's
 * `Font(GoogleFont("Space Grotesk"), …)` passes, so the wasm/desktop tiers can key a vendored
 * family off it.
 */
export function googleFontName(requestedFamily) {
  const m = /GoogleFont\(\s*"([^"]+)"/.exec(requestedFamily ?? "");
  return m ? m[1] : null;
}

/**
 * Slugify a GoogleFont display name into the vendored-file stem, matching the renderer's
 * `GoogleFontKey.slugify` (lowercase, every non-alphanumeric run collapsed to a single `-`, no
 * leading/trailing `-`). `"Space Grotesk"` → `"space-grotesk"`, so its 400-weight face vendors as
 * `space-grotesk-400.ttf` — the exact file the download step writes.
 */
export function fontSlug(name) {
  let out = "";
  let prevDash = true;
  for (const ch of name.toLowerCase()) {
    if ((ch >= "a" && ch <= "z") || (ch >= "0" && ch <= "9")) {
      out += ch;
      prevDash = false;
    } else if (!prevDash) {
      out += "-";
      prevDash = true;
    }
  }
  return out.replace(/^-+|-+$/g, "") || "font";
}

/** Vendored file stem for a named GoogleFont at [weight] (italic faces get an `-italic` suffix). */
function namedFontFile(name, weight, italic) {
  return `${fontSlug(name)}-${weight}${italic ? "-italic" : ""}.ttf`;
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
 *
 * [committed] is the dist's own `fonts.json` (already parsed) when the caller has one. Its
 * `role: "default"` and `role: "named"` families are the catalog's declared **theme-override**
 * typefaces (e.g. a Roboto Flex default, a Lobster Two named face). Those are applied to *clean*
 * previews via the theme wrapper, so the recorder never sees them in `fonts/used` — regeneration
 * would otherwise drop them and the published Wasm tier's font-override picks would silently fall
 * back. So a committed default (whose files are still vendored) supersedes the forced Roboto
 * default, and committed named faces are merged in alongside any recorded GoogleFont families.
 * Generic families and recorded GoogleFonts still come from [payloads]. Omit [committed] (or pass
 * null) for the pure-recorded behaviour.
 */
export function buildFontsManifest(payloads, availableFiles, committed = null) {
  const warnings = [];
  const wanted = new Map(); // familyKey -> Map(weight -> file)
  const namedWanted = new Map(); // GoogleFont display name -> Map("<weight>[i]" -> {file,weight,italic})
  const unknown = new Set();
  const missing = new Set();
  let recorded = 0;

  // A downloadable GoogleFont resolves to one vendored file per (weight, italic). Unlike the
  // generic families there's no nearest-weight snap: each recorded face has its own vendored TTF
  // (`<slug>-<weight>[-italic].ttf`), so a face whose file the dist doesn't carry warns and drops
  // just that face (the tier falls back to its bundled default for it) without taking the family's
  // other weights down.
  const wantNamed = (name, weight, italic) => {
    const w = Number(weight) || 400;
    const file = namedFontFile(name, w, italic);
    if (!availableFiles.has(file)) {
      if (!missing.has(file)) {
        missing.add(file);
        warnings.push(`'${name}' needs ${file}, which the dist fonts/ does not carry — dropped`);
      }
      return;
    }
    if (!namedWanted.has(name)) namedWanted.set(name, new Map());
    namedWanted.get(name).set(`${w}${italic ? "i" : ""}`, { file, weight: w, italic });
  };

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
        const gf = googleFontName(entry.requestedFamily);
        if (gf) {
          wantNamed(gf, entry.weight, entry.style === "italic");
          continue;
        }
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
  // Committed theme-override faces the recorder can't re-derive (clean previews apply them only via
  // the theme wrapper): a `role: "default"` typeface and any `role: "named"` faces, kept only when
  // every one of their files is still vendored in the dist.
  const committedFamilies = Array.isArray(committed?.families) ? committed.families : [];
  const vendored = (fam) =>
    Array.isArray(fam?.fonts) &&
    fam.fonts.length > 0 &&
    fam.fonts.every((f) => availableFiles.has(f.file));
  const normalizeCommitted = (fam) => ({
    name: fam.name,
    role: fam.role,
    fonts: [...fam.fonts]
      .sort((a, b) => a.weight - b.weight || Number(Boolean(a.style)) - Number(Boolean(b.style)))
      .map((f) =>
        f.style
          ? { file: f.file, weight: f.weight, style: f.style }
          : { file: f.file, weight: f.weight },
      ),
  });
  const committedDefault = committedFamilies.find((f) => f.role === "default" && vendored(f)) ?? null;
  const committedNamed = committedFamilies.filter((f) => f.role === "named" && vendored(f));

  if (recorded === 0 && !committedDefault && committedNamed.length === 0) {
    return { manifest: null, warnings };
  }

  // The M3 typography always needs a default family. A committed default (the catalog's declared
  // default typeface, e.g. Roboto Flex) wins and supersedes the recorded static-Roboto default;
  // otherwise force the bundled M3 Roboto pair, even if every recorded resolution was a generic one
  // (a text-less catalog still themes its diagnostics through the default).
  if (committedDefault) {
    wanted.delete("Roboto");
  } else if (!wanted.has("Roboto")) {
    for (const weight of Object.keys(FAMILY_FILES.Roboto.files)) want("Roboto", weight);
  }
  if (!committedDefault && wanted.size === 0 && committedNamed.length === 0) {
    return { manifest: null, warnings };
  }

  const roleOrder = (key) => (FAMILY_FILES[key].role === "default" ? 0 : 1);
  const recordedFamilies = [...wanted.entries()]
    .sort(([a], [b]) => roleOrder(a) - roleOrder(b) || a.localeCompare(b))
    .map(([key, byWeight]) => ({
      name: key,
      role: FAMILY_FILES[key].role,
      fonts: [...byWeight.entries()]
        .sort(([a], [b]) => a - b)
        .map(([weight, file]) => ({ file, weight })),
    }));
  // The committed default (if any) leads the block; recorded default/generic families follow.
  const families = committedDefault
    ? [normalizeCommitted(committedDefault), ...recordedFamilies]
    : recordedFamilies;

  // Named families follow the default/generic block, alphabetically: recorded GoogleFont families
  // (each carrying the GoogleFont display name so the wasm/desktop tiers can resolve a
  // `Font(GoogleFont("<name>"), …)` request onto the vendored faces) plus any committed override
  // faces the recorder never saw. Recorded usage wins on name collisions.
  const namedByName = new Map(
    [...namedWanted.entries()].map(([name, byFace]) => [
      name,
      {
        name,
        role: "named",
        fonts: [...byFace.values()]
          .sort((a, b) => a.weight - b.weight || Number(a.italic) - Number(b.italic))
          // `style: "italic"` (not `italic: true`): the Wasm manifest bridge (`flattenFontsManifest`
          // in the cmp-wasm-catalog app) keys a face's style off `f.style`, so an italic face must
          // carry that field or it registers as normal and an Italic request can't match it. Normal
          // faces omit style entirely — the reader defaults a missing style to normal.
          .map(({ file, weight, italic }) =>
            italic ? { file, weight, style: "italic" } : { file, weight },
          ),
      },
    ]),
  );
  for (const fam of committedNamed) {
    if (!namedByName.has(fam.name)) namedByName.set(fam.name, normalizeCommitted(fam));
  }
  const named = [...namedByName.values()].sort((a, b) => a.name.localeCompare(b.name));

  return { manifest: { version: 1, families: [...families, ...named] }, warnings };
}
