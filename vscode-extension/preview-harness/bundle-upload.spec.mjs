// End-to-end proof for the shared preview-server bundle handoff:
// local bundle file -> POST /bundles/{name} -> returned URL -> browsable session
// -> rendered preview bytes.

import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";

const TOKEN = process.env.SERVE_TOKEN ?? "bundle-upload-e2e";
const BUNDLE_PATH = process.env.BUNDLE_PATH;
const SESSION = process.env.BUNDLE_SESSION ?? "local-e2e-bundle";
const SERVE_URL = process.env.SERVE_URL || "http://127.0.0.1:8728";

function requireBundle() {
  if (!BUNDLE_PATH && process.env.CI) {
    throw new Error("BUNDLE_PATH is required for the bundle upload e2e");
  }
  test.skip(!BUNDLE_PATH, "set BUNDLE_PATH to a locally generated compose-preview bundle");
}

test("uploads a local bundle and opens the returned preview URL", async ({ request }) => {
  requireBundle();

  const bundle = readFileSync(BUNDLE_PATH);
  expect(bundle.length, "generated bundle is non-empty").toBeGreaterThan(0);

  const upload = await request.post(
    `/bundles/${encodeURIComponent(SESSION)}?token=${encodeURIComponent(TOKEN)}`,
    {
      headers: { "content-type": "application/octet-stream" },
      data: bundle,
    },
  );
  expect(upload.status(), await upload.text()).toBe(201);

  const accepted = await upload.json();
  expect(accepted.session).toBe(SESSION);
  expect(accepted.previews).toBeGreaterThan(0);
  expect(accepted.path).toBe(`/?session=${SESSION}`);

  const landing = await request.get(`${accepted.path}&token=${encodeURIComponent(TOKEN)}`);
  expect(landing.status(), "returned upload URL").toBe(200);
  const landingHtml = await landing.text();
  const previewHref = landingHtml.match(/href="([^"]*\/p\/[^"]+)"/)?.[1];
  expect(previewHref, "uploaded bundle landing has preview links").toBeTruthy();
  expect(previewHref, "landing preview link carries the uploaded session").toContain(
    `session=${encodeURIComponent(SESSION)}`,
  );
  const previewUrl = previewHref;
  const viewer = await request.get(previewUrl);
  expect(viewer.status(), `viewer linked from landing (${previewUrl})`).toBe(200);
  expect(await viewer.text(), "linked viewer belongs to uploaded session").toContain(
    `data-preview-id=`,
  );
  const linkedViewer = new URL(previewUrl, SERVE_URL);

  const previews = await request.get(
    `/api/previews?session=${encodeURIComponent(SESSION)}&token=${encodeURIComponent(TOKEN)}`,
  );
  expect(previews.status(), "uploaded session preview API").toBe(200);
  const body = await previews.json();
  const list = Array.isArray(body) ? body : (body.previews ?? []);
  expect(list.length, "uploaded session previews").toBeGreaterThan(0);
  const previewId = decodeURIComponent(linkedViewer.pathname.match(/\/p\/([^/]+)$/)?.[1] ?? "");
  expect(previewId, "uploaded preview id").toBeTruthy();
  expect(
    list.some((p) => p.id === previewId),
    "landing-linked preview is present in uploaded session API",
  ).toBeTruthy();

  const render = await request.get(
    `/render/${encodeURIComponent(previewId)}.png${linkedViewer.search}`,
  );
  expect(render.status(), "uploaded preview render").toBe(200);
  expect(render.headers()["content-type"] ?? "").toContain("image/png");
  expect((await render.body()).length, "uploaded preview PNG bytes").toBeGreaterThan(0);
});
