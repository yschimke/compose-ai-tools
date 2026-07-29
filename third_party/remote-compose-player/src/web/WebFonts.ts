// WebFonts: on-demand registration of *named* font families, served from Google Fonts.
//
// A Remote Compose document names its typeface in one of two ways. The four generic ids
// (`0=DEFAULT, 1=SANS_SERIF, 2=SERIF, 3=MONOSPACE`) are a closed set that `cssFontStackFor` maps to
// the concrete faces Android resolves them to. Anything else — `RemoteFontFamily.Named("Orbitron")`
// — reaches the paint layer as the document's *text id* for the family name, not as a name, because
// `CoreText.updateVariables` falls through to `mType = mFontFamilyId` for a family it doesn't
// recognise. Resolving that id back through the text table is what turns it into a family a browser
// can be asked for; this module is what makes the ask resolvable, by registering the face.
//
// Fetching from Google Fonts (rather than vendoring) is the only approach that generalises: a
// document may name *any* family, and the set isn't known until the document is read. The faces
// Android's downloadable-font provider serves at a given name are the same ones Google Fonts serves
// at that name, so this matches the baked raster for the same reason the vendored generics do.
//
// Which families to fetch is stated by the document, not guessed: a family is namespaced
// `"google:Orbitron"` to mean "the Google Fonts family Orbitron". Treating *any* unrecognised family
// as a Google Font would be the wrong default — it turns a typo, or a name that only means something
// on the host ("SF Pro"), into a network request, and it leaves no way to say "this one is local".
// The prefix is a convention over `RemoteFontFamily.Named`, which carries an opaque string; both
// render lanes parse it the same way, so a document means the same thing in the browser as in the
// snapshot renderer.

/** Runtime knobs. Both matter to embedders, so neither is baked in. */
export interface WebFontConfig {
    /**
     * Whether to reach the network at all. Off ⇒ every named family degrades to the fallback
     * generic, which is the correct behaviour under a webview CSP that forbids the font origins
     * (the VS Code webview) and in hermetic CI, where a network fetch is a flake source.
     */
    enabled: boolean;
    /** Base URL of the CSS API. Point it at a mirror or a local fixture server to render offline. */
    baseUrl: string;
}

const DEFAULT_BASE_URL = 'https://fonts.googleapis.com/css2';

let config: WebFontConfig = { enabled: true, baseUrl: DEFAULT_BASE_URL };

export function configureWebFonts(patch: Partial<WebFontConfig>): void {
    config = { ...config, ...patch };
}

export function webFontConfig(): Readonly<WebFontConfig> {
    return config;
}

/**
 * The `ital,wght` matrix to request. Google's CSS API returns *only the faces the family actually
 * ships* — asking Orbitron for all eighteen yields its real 400..900 normal faces and nothing else —
 * so over-asking costs nothing and under-asking silently loses a weight the document uses.
 *
 * It has to be this enumerated form. The API rejects (HTTP 400) a *range* like `wght@100..900` for
 * any family that isn't variable — `Lobster:wght@100..900` and `Pacifico:wght@400..700` both 400 —
 * while the enumeration is accepted for variable and single-weight families alike. A 400 here is
 * indistinguishable at the `<link>` from a network failure, so preferring the tolerant form is what
 * keeps a static family from being reported as a broken one.
 */
const WEIGHTS = [100, 200, 300, 400, 500, 600, 700, 800, 900];

/** The css2 URL for [family]. Exported (and pure) so the request form is covered by tests. */
export function googleFontsUrl(family: string, baseUrl: string = config.baseUrl): string {
    const axis = WEIGHTS.map((w) => `0,${w}`).concat(WEIGHTS.map((w) => `1,${w}`)).join(';');
    // Google's canonical spelling uses `+` for spaces; encodeURIComponent's %20 also resolves, but
    // the `+` form is what every cache in front of the API is keyed on.
    const name = encodeURIComponent(family.trim()).replace(/%20/g, '+');
    // `display=block` keeps the canvas from painting a frame in the fallback face and then
    // reflowing — the swap period is exactly the window a single-shot renderer screenshots in.
    return `${baseUrl}?family=${name}:ital,wght@${axis}&display=block`;
}

/** The namespace marking a family as one to fetch from Google Fonts. */
export const GOOGLE_PREFIX = 'google:';

/**
 * Split a document's family name into where the face comes from and what it is called.
 *
 * `"google:Space Grotesk"` → `{ source: 'google', name: 'Space Grotesk' }`; anything unprefixed →
 * `{ source: 'local', name: <as written> }`, meaning "whatever the host resolves this to", which is
 * the safe reading for a name we were given no provenance for.
 *
 * Note the returned `name` is always the *bare* family: it is what goes in the CSS stack and what is
 * asked of the API, so a stray prefix can never leak into either.
 */
export function parseFamily(family: string): { source: 'google' | 'local'; name: string } {
    const trimmed = family.trim();
    if (trimmed.toLowerCase().startsWith(GOOGLE_PREFIX)) {
        return { source: 'google', name: trimmed.slice(GOOGLE_PREFIX.length).trim() };
    }
    return { source: 'local', name: trimmed };
}

/** In-flight/settled registrations, keyed case-insensitively (CSS family names are ASCII-caseless). */
const registrations = new Map<string, Promise<void>>();

/** Families already reported as unavailable, so a 400 is logged once rather than per paint. */
const failed = new Set<string>();

function unquote(family: string): string {
    return family.replace(/^["']|["']$/g, '');
}

/**
 * Whether the page already carries a face for [family] — a vendored `@font-face` the host inlined
 * (the parity harness does exactly this for Roboto / Noto Serif / Droid Sans Mono). A local face is
 * both faster and more faithful than the network copy, so it wins and no request is made.
 */
function hasLocalFace(family: string): boolean {
    const want = family.toLowerCase();
    let found = false;
    document.fonts.forEach((face: FontFace) => {
        if (unquote(face.family).toLowerCase() === want) found = true;
    });
    return found;
}

function loadStylesheet(url: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        const link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = url;
        link.addEventListener('load', () => resolve());
        // Fires for a 400 (unknown family) as well as a genuine network failure; the two are not
        // distinguishable from here, and both mean "render the fallback".
        link.addEventListener('error', () => reject(new Error(`stylesheet did not load: ${url}`)));
        document.head.appendChild(link);
    });
}

/**
 * Force the faces the stylesheet just declared to actually load.
 *
 * `@font-face` is lazy and canvas does not drive it: `ctx.font` neither triggers a load nor waits
 * for one, and `document.fonts.ready` resolves while a declared face is still `unloaded`. Without
 * this the page reports the family, the shorthand names it, and the canvas still paints the
 * fallback. Loading the `FontFace` objects by identity (rather than guessing weights via
 * `document.fonts.load('400 16px …')`) covers exactly the faces the family really has.
 */
async function loadDeclaredFaces(family: string): Promise<void> {
    const want = family.toLowerCase();
    const faces: FontFace[] = [];
    document.fonts.forEach((face: FontFace) => {
        if (unquote(face.family).toLowerCase() === want) faces.push(face);
    });
    await Promise.all(faces.map((f) => f.load()));
}

async function register(family: string): Promise<void> {
    if (!config.enabled) return;
    // The bundle also runs under node-canvas, where there is no document and no font registry; a
    // named family there simply falls through to the fallback generic.
    if (typeof document === 'undefined' || !document.fonts) return;
    if (hasLocalFace(family)) return;
    await loadStylesheet(googleFontsUrl(family));
    await loadDeclaredFaces(family);
}

/**
 * Register [family] if it isn't already, and run [onLoaded] once it is paintable.
 *
 * Idempotent per family: the first call starts the work, later ones join the same promise. Never
 * rejects — a family Google doesn't serve is a document authored against a font we can't get, not a
 * player fault, and the CSS stack already carries a generic fallback for exactly that case.
 * [onLoaded] is how an interactive player repaints text that was first painted in the fallback.
 */
export function ensureWebFont(family: string, onLoaded?: () => void): Promise<void> {
    const key = family.toLowerCase();
    const existing = registrations.get(key);
    if (existing) return existing;
    const p = register(family)
        .then(() => {
            if (onLoaded) onLoaded();
        })
        .catch((e) => {
            if (!failed.has(key)) {
                failed.add(key);
                console.warn(`WebFonts: no web font for "${family}", using the fallback stack`, e);
            }
        });
    registrations.set(key, p);
    return p;
}

/**
 * Resolves when every family requested so far has settled.
 *
 * A single-shot renderer (the parity harness, any screenshot path) has no second frame in which a
 * late face could appear, so it must await this between loading the document and painting the frame
 * it keeps. An interactive player doesn't need it — it gets the repaint via `onLoaded`.
 */
export async function webFontsReady(): Promise<void> {
    // Re-read after awaiting: painting a frame can request a family we hadn't seen when we started.
    let pending = [...registrations.values()];
    while (pending.length > 0) {
        await Promise.all(pending);
        const next = [...registrations.values()];
        if (next.length === pending.length) break;
        pending = next;
    }
}

/** Drop all registration state. Tests only. */
export function resetWebFonts(): void {
    registrations.clear();
    failed.clear();
    config = { enabled: true, baseUrl: DEFAULT_BASE_URL };
}
