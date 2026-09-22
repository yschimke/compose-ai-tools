import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import { zipSync } from "fflate";

import {
  publishUiBuilderRuntime,
  treeIntegrity,
  UI_BUILDER_RUNTIME_MANIFEST,
  UI_BUILDER_RUNTIME_SCHEMA,
  UI_BUILDER_RUNTIME_SCHEMA_V2,
} from "./catalog-ui-builder-runtime.mjs";

test("publishes a verified runtime archive and descriptor", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-"));
  const archivePath = join(root, "runtime.zip");
  const assets = new Map([
    ["index.html", bytes("<!doctype html>")],
    ["renderer.mjs", bytes("export const renderer = true")],
  ]);
  const archive = runtimeArchive("wear-m3-p1-abcd", assets);
  await writeFile(archivePath, archive);

  const descriptor = await publishUiBuilderRuntime(archivePath, join(root, "out"));

  assert.deepEqual(descriptor, {
    path: "ui-builder/runtime.zip",
    runtimeId: "wear-m3-p1-abcd",
    protocolVersion: 1,
    integritySha256: treeIntegrity(assets),
  });
  assert.deepEqual(
    await readFile(join(root, "out", descriptor.path)),
    Buffer.from(archive),
  );
});

test("accepts exact v2 Remote Compose writer and player implementation metadata", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-remote-compose-"));
  const archivePath = join(root, "runtime.zip");
  const assets = new Map([["index.html", bytes("ok")]]);
  const entries = archiveEntries("remote-m3-p3-abcd", assets);
  const manifest = JSON.parse(Buffer.from(entries[UI_BUILDER_RUNTIME_MANIFEST]).toString("utf8"));
  entries[UI_BUILDER_RUNTIME_MANIFEST] = bytes(
    JSON.stringify({
      ...manifest,
      schema: UI_BUILDER_RUNTIME_SCHEMA_V2,
      remoteComposeWriter: "4307936-ps17-cmp01",
      rcPlayer: "1.69.0",
    }),
  );
  await writeFile(archivePath, zipSync(entries));

  const descriptor = await publishUiBuilderRuntime(archivePath, join(root, "out"));

  assert.equal(descriptor.runtimeId, "remote-m3-p3-abcd");
});

test("keeps v1 strict and rejects v2 provenance fields", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-v1-strict-"));
  const assets = new Map([["index.html", bytes("ok")]]);
  const entries = archiveEntries("remote-m3-p3-abcd", assets);
  const manifest = JSON.parse(Buffer.from(entries[UI_BUILDER_RUNTIME_MANIFEST]).toString("utf8"));
  entries[UI_BUILDER_RUNTIME_MANIFEST] = bytes(
    JSON.stringify({ ...manifest, remoteComposeWriter: "4307936-ps17-cmp01" }),
  );
  const archive = join(root, "v1-with-v2-field.zip");
  await writeFile(archive, zipSync(entries));

  await assert.rejects(
    publishUiBuilderRuntime(archive, join(root, "out")),
    /fields do not match compose-ui-builder-runtime\/v1/,
  );
});

test("rejects unknown or malformed v2 implementation metadata", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-metadata-"));
  const assets = new Map([["index.html", bytes("ok")]]);
  const entries = archiveEntries("remote-m3-p3-abcd", assets);
  const manifest = JSON.parse(Buffer.from(entries[UI_BUILDER_RUNTIME_MANIFEST]).toString("utf8"));

  entries[UI_BUILDER_RUNTIME_MANIFEST] = bytes(
    JSON.stringify({ ...manifest, schema: UI_BUILDER_RUNTIME_SCHEMA_V2, arbitrary: "value" }),
  );
  const unknown = join(root, "unknown.zip");
  await writeFile(unknown, zipSync(entries));
  await assert.rejects(publishUiBuilderRuntime(unknown, join(root, "out")), /fields do not match/);

  entries[UI_BUILDER_RUNTIME_MANIFEST] = bytes(
    JSON.stringify({
      ...manifest,
      schema: UI_BUILDER_RUNTIME_SCHEMA_V2,
      remoteComposeWriter: "",
    }),
  );
  const malformed = join(root, "malformed.zip");
  await writeFile(malformed, zipSync(entries));
  await assert.rejects(
    publishUiBuilderRuntime(malformed, join(root, "out")),
    /remoteComposeWriter must be a non-empty string/,
  );
});

test("rejects unsupported manifest schemas before considering their fields", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-schema-"));
  const assets = new Map([["index.html", bytes("ok")]]);
  const entries = archiveEntries("remote-m3-p3-abcd", assets);
  const manifest = JSON.parse(Buffer.from(entries[UI_BUILDER_RUNTIME_MANIFEST]).toString("utf8"));
  entries[UI_BUILDER_RUNTIME_MANIFEST] = bytes(
    JSON.stringify({ ...manifest, schema: "compose-ui-builder-runtime/v3" }),
  );
  const archive = join(root, "unsupported.zip");
  await writeFile(archive, zipSync(entries));

  await assert.rejects(
    publishUiBuilderRuntime(archive, join(root, "out")),
    /unsupported manifest schema/,
  );
});

test("rejects a reused identity whose tree does not match its manifest", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-bad-"));
  const archivePath = join(root, "runtime.zip");
  const original = new Map([["index.html", bytes("original")]]);
  const entries = archiveEntries("wear-m3-p1-stable", original);
  entries["index.html"] = bytes("changed");
  await writeFile(archivePath, zipSync(entries));

  await assert.rejects(
    publishUiBuilderRuntime(archivePath, join(root, "out")),
    /integrity mismatch/,
  );
});

test("rejects reserved ids and unsafe archive paths", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-unsafe-"));
  const reserved = join(root, "reserved.zip");
  await writeFile(reserved, runtimeArchive("latest", new Map([["index.html", bytes("ok")]])));
  await assert.rejects(publishUiBuilderRuntime(reserved, join(root, "out")), /unsafe or reserved/);

  const unsafe = join(root, "unsafe.zip");
  await writeFile(unsafe, zipSync({ "../outside": bytes("bad") }));
  await assert.rejects(publishUiBuilderRuntime(unsafe, join(root, "out")), /unsafe path/);
});

test("publishes one replace-in-place archive path across runtime upgrades", async () => {
  const root = await mkdtemp(join(tmpdir(), "catalog-runtime-upgrade-"));
  const first = join(root, "first.zip");
  const second = join(root, "second.zip");
  await writeFile(first, runtimeArchive("wear-m3-p1-first", new Map([["index.html", bytes("1")]])));
  await writeFile(second, runtimeArchive("wear-m3-p1-second", new Map([["index.html", bytes("2")]])));

  const firstDescriptor = await publishUiBuilderRuntime(first, join(root, "out"));
  const secondDescriptor = await publishUiBuilderRuntime(second, join(root, "out"));

  assert.equal(firstDescriptor.path, "ui-builder/runtime.zip");
  assert.equal(secondDescriptor.path, firstDescriptor.path);
  assert.deepEqual(
    await readFile(join(root, "out", secondDescriptor.path)),
    Buffer.from(await readFile(second)),
  );
});

test("tree integrity matches the compose-preview-contracts v1 vector", async () => {
  const vector = JSON.parse(
    await readFile(
      new URL("./fixtures/ui-builder-runtime-tree-integrity-v1.json", import.meta.url),
      "utf8",
    ),
  );
  const assets = new Map(
    vector.assets.map(({ path, base64 }) => [path, Buffer.from(base64, "base64")]),
  );

  assert.equal(vector.algorithm, "SHA-256");
  assert.equal(treeIntegrity(assets), vector.integritySha256);
});

function runtimeArchive(runtimeId, assets) {
  return zipSync(archiveEntries(runtimeId, assets));
}

function archiveEntries(runtimeId, assets) {
  const integritySha256 = treeIntegrity(assets);
  return Object.fromEntries([
    ...assets,
    [
      UI_BUILDER_RUNTIME_MANIFEST,
      bytes(
        JSON.stringify({
          schema: UI_BUILDER_RUNTIME_SCHEMA,
          runtimeId,
          protocolVersion: 1,
          entrypoint: "index.html",
          integritySha256,
        }),
      ),
    ],
  ]);
}

function bytes(value) {
  return Buffer.from(value, "utf8");
}
