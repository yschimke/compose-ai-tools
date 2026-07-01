# The public preview server

`compose-preview serve` can run as a **public** preview server (the deployment behind
`preview.coo.ee`) with two things to show:

1. **Uploaded bundles** — anyone can `POST /bundles/<name>` a portable bundle (or point at one with
   `?url=`) and get a shareable `?session=<name>` link. The server shows the bundle's **data tiers**
   (baked PNGs, Remote Compose / Protolayout / Lottie IR) for any uploader, and reports a **trust
   verdict** so you can tell a bundle from a producer you trust from an anonymous one.
2. **The design systems we publish** — `--catalogs compose-m3,wear-m3` fetches each published
   `design-artifacts/<system>` catalog and serves it read-only at its canonical path `/<system>/`
   (the legacy `?session=<system>` form still works). Browsing that branch and opening a live,
   customisable render are then two ends of one workflow (the branch's README + `catalog.json` carry
   `livePreview` deep links back here).

   A catalog entry may name a **per-system source repo** as `<system>@<owner>/<repo>`, so one server
   can serve systems published to *different* repos — e.g. `compose-m3,wear-m3` from this repo
   alongside `--catalogs-unlisted meshcore-mobile@yschimke/meshcore-mobile` from the app's own repo.
   `--catalogs-unlisted` serves a system exactly like `--catalogs` but keeps it **off the front-page
   "Design systems" nav** — reachable at `/<system>/` (and `?session=`) but not advertised on the
   landing page. Every catalog's branch (whatever repo) must be in the `--trust-store` to badge
   `Trusted(Branch)`; otherwise it serves `Unverified` (the data tiers serve either way).

In `--public` mode the landing page opens with a short **"about" intro** explaining what the host is
and its safety model, with a link to the machine-readable [`/version`](#endpoints):

![Public landing "about" intro (light)](images/serve-about-public-light.png)

![Public landing "about" intro (dark)](images/serve-about-public-dark.png)

## Two axes: trust × format

These are orthogonal. **Trust** decides attribution; **format** decides what draws the pixels. Neither
ever lets untrusted code run *on the server*.

### Trust

A bundle/catalog is `Trusted(by …)` or `Unverified`. Trust gates only **server-side re-render** of a
bundle's *executable* Compose; the data tiers serve regardless. Three bases (`--trust-store
trust/producers.json`):

| Basis | How | Strength |
|---|---|---|
| **Signature** | An Ed25519 `signatures.json` signed by a key in the store's `keys` (`bundle sign`). | Strongest — cryptographic, offline. |
| **Branch** | The server fetched the catalog from a branch in the store's `branches` (e.g. `design-artifacts/*`). | Origin/TLS trust. |
| **Provenance** | A CI OIDC identity in the store's `oidc`. | Advisory — annotates an already-signature-verified bundle; full Sigstore/Rekor is a follow-up. |

Empty store ⇒ trust nothing (fail-closed). See [`trust/producers.json`](../trust/producers.json) for
the starter, and `compose-preview bundle keygen | sign | verify` to mint a key, sign a bundle, and
check a verdict.

The landing + viewer pages **badge** the session's verdict — green ✓ for a trusted
signature/branch/provenance, amber ⚠ for `unverified` (a live daemon-backed module carries no
badge):

![Trusted session badge on the landing page](images/serve-trust-badge-trusted.png)

![Unverified session badge on the viewer page](images/serve-trust-badge-unverified.png)

### Format (each its own renderer; none executes code on the server)

| Format | In-browser | Server render | Data-only / safe | Server render needs trust |
|---|---|---|---|---|
| **Compose Android** | — | Robolectric (daemon) | no (runs Kotlin) | **yes** |
| **Compose MP (CMP)** | **Kotlin/Wasm** (browser sandbox) | Skiko desktop (daemon) | no (runs Kotlin) | server: **yes**; Wasm: sandboxed |
| **Remote Compose** | RemoteDocument player | player | **yes** | no |
| **Protolayout / Lottie** | web player | renderer | **yes** | no |
| **Baked PNG** | `<img>` | — | **yes** | no |

So: **CMP renders in the browser** (Wasm sandbox), **Compose Android uses the server**, and a **baked
PNG** is the universal fallback when an image is needed. Remote Compose / Protolayout are *separate,
data-only* formats — the safest uploads.

The CMP-Wasm tier is built (`:samples:cmp-wasm-catalog`, see
[`wasm-cmp-spike.md`](wasm-cmp-spike.md)): a CMP catalog session's viewer shows a **"Run in browser
(Wasm)"** toggle that mounts the M3 components client-side in a sandboxed iframe — no server
round-trip, so safe even for an unverified session. The app is sourced two ways:

- **From the trusted branch (default).** When the `design-artifacts/<system>` catalog declares a
  `webRender` (a `web/wasm/` app committed to the branch), `--catalogs` fetches it alongside
  `catalog.json` + `images/` and serves it at `/wasm/<system>/` — **trusted by the same branch
  origin**, no local build needed.
- **From a local build.** `--wasm-dir <system>=<dist>` points at a `wasmCatalogDist` output, which
  overrides the branch app for that system (handy when iterating locally).

The `/wasm/` assets are sent with `Cache-Control` + an `ETag`, so the heavy skiko + app wasm (≈ 8 MB
gzipped) is cached and revalidated cheaply (304) instead of re-downloaded each viewer load.

## Running one

```bash
compose-preview serve \
  --module :samples:design-catalog-m3 \   # a base module is the default session
  --public \                              # open every route (no token)
  --catalogs compose-m3,wear-m3 \         # published design systems, listed on the front-page nav
  --catalogs-unlisted \                   # served at /<system>/ but hidden from the nav; each from its own repo
      meshcore-mobile@yschimke/meshcore-mobile,homeassistant-remotecompose@yschimke/homeassistant-remotecompose \
  --trust-store trust/producers.json \    # who we trust (must list every catalog's branch/repo)
  --host 0.0.0.0 --port 8080

# The listed systems open at https://preview.coo.ee/compose-m3/ ; the unlisted app
# systems at https://preview.coo.ee/meshcore-mobile/ (not on the front page, but shareable).
```

- **`--public`** drops the token gate (the deployed server is meant to be open). It is **safe by
  construction**: rendering a bundle/catalog executes no code, re-rendering untrusted Compose is
  refused, uploads are size-capped, and the `?url=` fetch is SSRF-gated (`--accept-bundles-from`).

## Deploying `preview.coo.ee`

Both container profiles take this config from env (the entrypoint maps `SERVE_PUBLIC`,
`SERVE_CATALOGS`, `SERVE_CATALOGS_UNLISTED`, `SERVE_TRUST_STORE`, `SERVE_WASM_DIR`,
`SERVE_ACCEPT_BUNDLES` → flags) and put **Caddy** in front for TLS. They default to the **open public
profile** (`SERVE_PUBLIC=1`, catalogs `compose-m3,wear-m3` on the nav, plus the app systems
`meshcore-mobile` / `homeassistant-remotecompose` served unlisted at `/<system>/` from their own
repos via `SERVE_CATALOGS_UNLISTED`); set `SERVE_PUBLIC=0` + `SERVE_TOKEN` for a token-gated box.

The prebuilt `deploy/image` **bakes a branch-trust store** at `/trust/producers.json` (trusting
`design-artifacts/*` on `yschimke/compose-ai-tools`, `yschimke/meshcore-mobile`, and
`yschimke/homeassistant-remotecompose`) and the entrypoint defaults `SERVE_TRUST_STORE` to
it, so the published catalogs badge as `Trusted(Branch)` out of the box rather than `unverified`.
Mount your own over that path (or set `SERVE_TRUST_STORE` to it) to pin different producers, or set
`SERVE_TRUST_STORE=none` to run trustless. (Empty falls back to the baked default — use `none` to opt
out — which also means a bare image pull self-heals a box without editing its compose.)

The **catalog set is baked into the image the same way**: the entrypoint defaults `SERVE_CATALOGS`
to `compose-m3,wear-m3` (front-page nav) and `SERVE_CATALOGS_UNLISTED` to
`meshcore-mobile@yschimke/meshcore-mobile,homeassistant-remotecompose@yschimke/homeassistant-remotecompose`
(served at `/<system>/`, off the nav). So a bare `docker pull` / Watchtower update serves them
without editing the box's compose. Override either with your own comma list, or `none` to serve none
of that kind (empty inherits the baked default). The `deploy/vps` from-source path still sets these
in its compose (it builds `main`, so the flags exist immediately; the prebuilt image needs a CLI
release that carries `--catalogs-unlisted`).

| | [`deploy/vps`](../deploy/vps) (from source) | [`deploy/image`](../deploy/image) (prebuilt) |
|---|---|---|
| CLI | compiled from this checkout (~8 min build) | the **released** tarball (`docker pull`, no build) + Watchtower auto-update |
| Has the latest serve features? | **immediately** (built from `main`) | only once they're in a **published CLI release** (bump `CP_VERSION`) |
| In-browser Wasm tier | local build, `SERVE_WASM_DIR=compose-m3=samples/cmp-wasm-catalog/build/wasmDist` | branch-fetch: `--catalogs` pulls each system's `web/wasm/` from the trusted branch (needs the branch to carry it) |

So **today** (before a release), deploy from source: `cd deploy/vps && DOMAIN=preview.coo.ee ./setup.sh`
— it builds the current `main`, including the Wasm app, and comes up public. **After** the serve
features ship in a CLI release *and* the `design-artifacts/compose-m3` branch carries `web/wasm/`,
the prebuilt `deploy/image` path serves the same thing with no host build (and Watchtower keeps it
current).
- **Re-render of trusted Compose** stays off unless the operator opts in; a public box should leave
  `--revisions` *off* (that path runs arbitrary Gradle = RCE).

## Trusted server-side re-render (`--allow-render-trusted`)

By default a catalog serves **baked PNGs** — the viewer's device/orientation/etc. controls can't
re-render a static image (they're disabled, with the in-browser Wasm tier carrying theme/font-scale/
locale for CMP). For **full-fidelity** server-side overrides, a catalog can declare a buildable
`source: { repo, ref, module }` in its `catalog.json` (the design-artifacts pipeline emits it), and
an operator can opt in with `--allow-render-trusted`: a catalog that verifies **`Trusted`** *and*
declares a `source` is then served by a live, daemon-backed session built from that source, so every
control re-renders for real.

It is **off by default** and gated three ways, all fail-closed: the catalog must be `Trusted`
(an `Unverified`/spoofed catalog never reaches the builder — no RCE lever), its `source.ref` must
clear the `--revisions-allow` allowlist, and its `source.repo` must be the server's own repo.

**Never enable it on a box that can't build the catalog source.** Building runs the source's Gradle
(code execution), and the published catalogs are **Android** modules — the desktop-only public image
(`deploy/image`, `preview.coo.ee`) has no Android toolchain, so it leaves `SERVE_ALLOW_RENDER_TRUSTED`
**unset** and relies on the Wasm tier for CMP. Enable it only on a box with the matching toolchain
(set `SERVE_ALLOW_RENDER_TRUSTED=1` + `SERVE_REVISIONS_ALLOW=main`), where the heavier per-session
Gradle build + live render is acceptable.

## Endpoints

`GET /` index · `GET /p/{id}?session=<s>` viewer · `GET /render/{id}.png` PNG ·
`GET /api/previews` JSON (now includes `trust`) · `POST /bundles/{name}` upload (returns `trust`) ·
`GET /wasm/{system}/…` in-browser CMP app (ungated static assets) · `GET /healthz` ·
`GET /version`. In `--public` mode all are open; otherwise the token gates everything but
`/healthz`, `/version`, and `/wasm/` (static, no session data).

Every session-selecting route also has a **path form** where the leading `/{system}` segment picks
the session instead of `?session=`: `GET /{system}/` index · `GET /{system}/p/{id}` viewer ·
`GET /{system}/render/{id}.png` PNG · `GET /{system}/api/previews` JSON · `GET /{system}/bundle.zip`
· `WS /{system}/ws/{id}` stream. This is the canonical public URL for a published catalog
(`/compose-m3/`, `/meshcore-mobile/`, …); the `?session=` form stays for back-compat. The constant
routes (`/healthz`, `/version`, `/bundle.zip`, `/wasm/…`) outrank the `/{system}` catch-all, so an
unknown single segment just 404s like a bad session.

`GET /version` is the host's machine-readable identity — ungated so a deployer, Watchtower check, or
the design-artifacts gallery can confirm which build is live without a token:

```json
{ "schema": "compose-preview-serve/version/v1", "version": "0.16.5",
  "serveSchema": "compose-preview-serve/v1", "public": true }
```
