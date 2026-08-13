import * as fs from "fs";
import * as path from "path";
import { BUNDLED_PLUGIN_VERSION } from "./version.generated";

/**
 * The **project version pin** — one place a project names the compose-preview
 * version, honoured by every entrypoint (issue #3738).
 *
 * This is the TypeScript half of the CLI's `VersionPin.kt`: same sources, same
 * precedence, same normalisation. Without it the extension auto-injected the
 * plugin at whatever `BUNDLED_PLUGIN_VERSION` its VSIX was built from, so a
 * project whose CLI and CI were pinned to one release still rendered against a
 * different one the moment someone opened it in VS Code — the skew class issue
 * #1920 documented from the CI side.
 *
 * Sources, in precedence order:
 * 1. `COMPOSE_PREVIEW_VERSION` in the environment.
 * 2. `gradle.properties` → `composePreview.version` (what `compose-preview pin`
 *    writes).
 * 3. `gradle/libs.versions.toml` → `[versions] composePreviewCli` (the
 *    Renovate-friendly convention the `install` / `apply` actions already read).
 *
 * Nothing found → `undefined`, and the caller falls back to
 * `BUNDLED_PLUGIN_VERSION`. The extension has no `--plugin-version` equivalent,
 * so the CLI's first source has no counterpart here.
 */

/** `gradle.properties` key holding the pin. */
export const VERSION_PIN_PROPERTY = "composePreview.version";

/** Environment override, read before anything on disk. */
export const VERSION_PIN_ENV = "COMPOSE_PREVIEW_VERSION";

/** Version-catalog path scanned for {@link VERSION_PIN_CATALOG_KEY}. */
export const VERSION_PIN_CATALOG_PATH = "gradle/libs.versions.toml";

/** `[versions]` key read from the catalog. */
export const VERSION_PIN_CATALOG_KEY = "composePreviewCli";

/** Where a resolved pin came from. Values match the CLI's `VersionPinSource.display`. */
export type VersionPinSource =
    | "COMPOSE_PREVIEW_VERSION"
    | "gradle.properties (composePreview.version)"
    | "gradle/libs.versions.toml ([versions] composePreviewCli)";

export interface ResolvedVersionPin {
    readonly version: string;
    readonly source: VersionPinSource;
}

/**
 * Trims a raw pin value and drops a leading `v` (`v1.1.0` → `1.1.0`). Blank
 * values are treated as absent, so an empty `composePreview.version=` line
 * doesn't pin the workspace to the empty string.
 */
function normalize(raw: string | undefined): string | undefined {
    if (raw === undefined) {
        return undefined;
    }
    const trimmed = raw.trim().replace(/^v/, "");
    return trimmed.length > 0 ? trimmed : undefined;
}

/**
 * Matches a `composePreview.version` assignment on an already-trimmed line.
 *
 * All three separators a Java properties file allows — `key=v`, `key : v`, and
 * bare `key v` — because the CLI reads this file through `java.util.Properties`,
 * which accepts all three. Recognising fewer forms here than the CLI does is
 * precisely the cross-entrypoint skew this feature exists to eliminate: the CLI
 * would inject the pin while the extension fell back to its bundled version.
 * Kept in lockstep with `VersionPin.kt`, `resolve-version.py` and `check-skew.py`.
 */
const PIN_ASSIGNMENT_RE =
    /^composePreview\.version(?:[ \t]*[=:][ \t]*|[ \t]+)(.*?)[ \t]*$/;

/**
 * Reads `composePreview.version` out of `<workspaceRoot>/gradle.properties`.
 *
 * A small hand-rolled properties reader rather than a dependency: a version pin
 * is a bare token on one line, and nothing about it is exotic enough to justify
 * pulling a properties parser into the VSIX. Takes the **last** assignment, as
 * `Properties.load` does, so a file with duplicates resolves the same way in
 * both. Unreadable file → `undefined`, never a throw.
 */
export function readGradlePropertiesPin(
    workspaceRoot: string,
): string | undefined {
    let text: string;
    try {
        text = fs.readFileSync(
            path.join(workspaceRoot, "gradle.properties"),
            "utf-8",
        );
    } catch {
        return undefined;
    }
    let found: string | undefined;
    for (const line of text.split(/\r?\n/)) {
        const trimmed = line.trimStart();
        if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
            continue;
        }
        const match = trimmed.match(PIN_ASSIGNMENT_RE);
        if (match) {
            found = normalize(match[1]);
        }
    }
    return found;
}

/**
 * Reads the `[versions]` entry named {@link VERSION_PIN_CATALOG_KEY} out of the
 * workspace's version catalog.
 *
 * Scoped to the `[versions]` table so an identically named key under
 * `[libraries]` / `[plugins]` can't be mistaken for the pin. A scan rather than
 * a TOML parse, for the same reason as above and matching how the init script's
 * own catalog-accessor detector reads this file.
 */
export function readCatalogPin(
    workspaceRoot: string,
    catalogPath: string = VERSION_PIN_CATALOG_PATH,
    key: string = VERSION_PIN_CATALOG_KEY,
): string | undefined {
    let text: string;
    try {
        text = fs.readFileSync(path.join(workspaceRoot, catalogPath), "utf-8");
    } catch {
        return undefined;
    }
    const header = /^[ \t]*\[versions\][ \t]*$/m.exec(text);
    if (!header) {
        return undefined;
    }
    const sectionStart = header.index + header[0].length;
    const nextSection = /^[ \t]*\[/m.exec(text.slice(sectionStart));
    const section = text.slice(
        sectionStart,
        nextSection ? sectionStart + nextSection.index : text.length,
    );
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const entry = new RegExp(
        `^[ \\t]*${escaped}[ \\t]*=[ \\t]*["']([^"']*)["']`,
        "m",
    ).exec(section);
    return entry ? normalize(entry[1]) : undefined;
}

/** Resolves the workspace's version pin, or `undefined` when nothing pins one. */
export function resolveVersionPin(
    workspaceRoot: string,
    env: NodeJS.ProcessEnv = process.env,
): ResolvedVersionPin | undefined {
    const fromEnv = normalize(env[VERSION_PIN_ENV]);
    if (fromEnv) {
        return { version: fromEnv, source: "COMPOSE_PREVIEW_VERSION" };
    }
    const fromProperties = readGradlePropertiesPin(workspaceRoot);
    if (fromProperties) {
        return {
            version: fromProperties,
            source: "gradle.properties (composePreview.version)",
        };
    }
    const fromCatalog = readCatalogPin(workspaceRoot);
    if (fromCatalog) {
        return {
            version: fromCatalog,
            source: "gradle/libs.versions.toml ([versions] composePreviewCli)",
        };
    }
    return undefined;
}

/**
 * The plugin version the extension should auto-inject: the workspace's pin when
 * it has one, else the version bundled into this VSIX.
 */
export function resolvePluginVersion(
    workspaceRoot: string,
    env: NodeJS.ProcessEnv = process.env,
    fallback: string = BUNDLED_PLUGIN_VERSION,
): string {
    return resolveVersionPin(workspaceRoot, env)?.version ?? fallback;
}
