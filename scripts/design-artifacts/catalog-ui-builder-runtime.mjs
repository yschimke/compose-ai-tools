import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

import { unzipSync } from "fflate";

// Wire spellings are owned by compose-preview-contracts' RuntimeV1. This Node publisher cannot
// link the KMP coordinate, so its conformance test consumes that contract's versioned vector.
export const UI_BUILDER_RUNTIME_MANIFEST = "runtime-manifest.json";
export const UI_BUILDER_RUNTIME_SCHEMA_V1 = "compose-ui-builder-runtime/v1";
export const UI_BUILDER_RUNTIME_SCHEMA_V2 = "compose-ui-builder-runtime/v2";
// Kept as the v1 spelling for callers that assemble the original five-field manifest.
export const UI_BUILDER_RUNTIME_SCHEMA = UI_BUILDER_RUNTIME_SCHEMA_V1;

const SAFE_RUNTIME_ID = /^[A-Za-z0-9._-]+$/;
const SHA256 = /^[a-f0-9]{64}$/;
const RESERVED_RUNTIME_IDS = new Set(["current", "latest"]);
const MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
const MAX_EXPANDED_BYTES = 512 * 1024 * 1024;
const MAX_FILE_BYTES = 256 * 1024 * 1024;
const MAX_FILES = 4096;
const MANIFEST_FIELDS_V1 = [
  "entrypoint",
  "integritySha256",
  "protocolVersion",
  "runtimeId",
  "schema",
];
// v2 adds declared implementation provenance while leaving v1 strict. The schema is read before
// choosing the allowed field set so a v1 manifest cannot smuggle v2-only metadata through an
// optional-key escape hatch. Unknown fields fail closed in both versions.
const PROVENANCE_FIELDS_V2 = ["rcPlayer", "remoteComposeWriter"];
const MANIFEST_FIELDS_V2 = [...MANIFEST_FIELDS_V1, ...PROVENANCE_FIELDS_V2];

/** Publish one verified, self-contained catalog renderer archive beside `ui-builder.json`. */
export async function publishUiBuilderRuntime(archivePath, outPath) {
  if (!archivePath) return null;
  const archive = await readFile(archivePath);
  if (archive.length > MAX_ARCHIVE_BYTES)
    throw new Error(`UI-builder runtime archive exceeds ${MAX_ARCHIVE_BYTES} bytes`);
  const names = new Set();
  let expandedBytes = 0;
  const entries = unzipSync(archive, {
    // Inspect the central-directory sizes before fflate allocates or expands an entry. The server
    // enforces its own limits too; the publication workflow must not be a decompression-bomb lane.
    filter: ({ name, originalSize }) => {
      if (names.has(name)) throw new Error(`UI-builder runtime repeats path: ${name}`);
      names.add(name);
      if (names.size > MAX_FILES)
        throw new Error(`UI-builder runtime contains more than ${MAX_FILES} entries`);
      if (originalSize > MAX_FILE_BYTES)
        throw new Error(`UI-builder runtime entry '${name}' exceeds ${MAX_FILE_BYTES} bytes`);
      expandedBytes += originalSize;
      if (expandedBytes > MAX_EXPANDED_BYTES)
        throw new Error(`UI-builder runtime expands beyond ${MAX_EXPANDED_BYTES} bytes`);
      return true;
    },
  });
  const files = new Map();
  for (const [rawPath, bytes] of Object.entries(entries)) {
    const path = rawPath.endsWith("/") ? rawPath.slice(0, -1) : rawPath;
    if (!path) continue;
    if (normalizePath(path) !== path)
      throw new Error(`UI-builder runtime contains an unsafe path: ${rawPath}`);
    if (rawPath.endsWith("/")) continue;
    if (files.has(path)) throw new Error(`UI-builder runtime repeats path: ${path}`);
    files.set(path, bytes);
  }

  const manifestBytes = files.get(UI_BUILDER_RUNTIME_MANIFEST);
  if (!manifestBytes)
    throw new Error(`UI-builder runtime has no ${UI_BUILDER_RUNTIME_MANIFEST}`);
  let manifest;
  try {
    manifest = JSON.parse(Buffer.from(manifestBytes).toString("utf8"));
  } catch (error) {
    throw new Error(`UI-builder runtime has an invalid ${UI_BUILDER_RUNTIME_MANIFEST}`, {
      cause: error,
    });
  }
  if (!manifest || Array.isArray(manifest))
    throw new Error(
      `UI-builder runtime manifest fields do not match ${UI_BUILDER_RUNTIME_SCHEMA_V1}`,
    );
  const manifestFields = manifestFieldsForSchema(manifest.schema);
  if (
    MANIFEST_FIELDS_V1.some((field) => !(field in manifest)) ||
    Object.keys(manifest).some((field) => !manifestFields.includes(field))
  ) {
    throw new Error(`UI-builder runtime manifest fields do not match ${manifest.schema}`);
  }
  for (const field of PROVENANCE_FIELDS_V2) {
    if (field in manifest && (typeof manifest[field] !== "string" || !manifest[field])) {
      throw new Error(`UI-builder runtime manifest ${field} must be a non-empty string`);
    }
  }
  if (
    typeof manifest.runtimeId !== "string" ||
    !SAFE_RUNTIME_ID.test(manifest.runtimeId) ||
    RESERVED_RUNTIME_IDS.has(manifest.runtimeId)
  ) {
    throw new Error(`UI-builder runtime id '${manifest.runtimeId}' is unsafe or reserved`);
  }
  if (!Number.isInteger(manifest.protocolVersion) || manifest.protocolVersion <= 0)
    throw new Error("UI-builder runtime protocolVersion must be a positive integer");
  if (
    typeof manifest.entrypoint !== "string" ||
    normalizePath(manifest.entrypoint) !== manifest.entrypoint ||
    !files.has(manifest.entrypoint)
  ) {
    throw new Error("UI-builder runtime has an unsafe or missing entrypoint");
  }
  if (
    typeof manifest.integritySha256 !== "string" ||
    !SHA256.test(manifest.integritySha256)
  ) {
    throw new Error("UI-builder runtime must declare a lowercase SHA-256 integrity digest");
  }
  const actualIntegrity = treeIntegrity(
    new Map([...files].filter(([path]) => path !== UI_BUILDER_RUNTIME_MANIFEST)),
  );
  if (actualIntegrity !== manifest.integritySha256) {
    throw new Error(
      `UI-builder runtime integrity mismatch: expected ${manifest.integritySha256}, ` +
        `calculated ${actualIntegrity}`,
    );
  }

  // One runtime per catalog generation, at one replace-in-place delivery path. Exact catalog
  // revisions preserve old ZIPs; the moving delivery branch must not accumulate every runtime ever
  // published. The server installs verified bytes under runtimeId when it activates a generation.
  const relativePath = "ui-builder/runtime.zip";
  const target = join(outPath, relativePath);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, archive);
  return {
    path: relativePath,
    runtimeId: manifest.runtimeId,
    protocolVersion: manifest.protocolVersion,
    integritySha256: manifest.integritySha256,
  };
}

function manifestFieldsForSchema(schema) {
  if (schema === UI_BUILDER_RUNTIME_SCHEMA_V1) return MANIFEST_FIELDS_V1;
  if (schema === UI_BUILDER_RUNTIME_SCHEMA_V2) return MANIFEST_FIELDS_V2;
  throw new Error("UI-builder runtime has an unsupported manifest schema");
}

/**
 * The runtime assembler and server use the same path/length/content framing. The manifest is
 * excluded because it contains this digest; every other file, including the entrypoint, is covered.
 */
export function treeIntegrity(files) {
  const digest = createHash("sha256");
  for (const [path, bytes] of [...files].sort(([a], [b]) =>
    Buffer.compare(Buffer.from(a, "utf8"), Buffer.from(b, "utf8")),
  )) {
    if (normalizePath(path) !== path || path === UI_BUILDER_RUNTIME_MANIFEST)
      throw new Error(`Unsafe runtime asset path '${path}'`);
    digest.update(path, "utf8");
    digest.update(Uint8Array.of(0));
    digest.update(String(bytes.length), "utf8");
    digest.update(Uint8Array.of(0));
    digest.update(bytes);
  }
  return digest.digest("hex");
}

function normalizePath(path) {
  if (
    typeof path !== "string" ||
    !path ||
    path.startsWith("/") ||
    path.includes("\\") ||
    path.includes("\0")
  )
    return null;
  const segments = path.split("/");
  if (segments.some((segment) => !segment || segment === "." || segment === "..")) return null;
  return segments.join("/");
}
