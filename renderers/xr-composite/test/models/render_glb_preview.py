#!/usr/bin/env python3
"""Render a committed .glb fixture to a PNG preview, headless and GPU-free.

This exists so the visual reference next to a model fixture is *reproducible*
rather than a one-off screenshot: re-run it whenever the fixture changes.

It is a tiny CPU rasteriser (z-buffered, flat diffuse shading) that reads the
glTF-binary directly — no WebGL / OpenGL, no Android, no three.js — so it runs
in any headless container. It only needs numpy + Pillow:

    pip install numpy pillow
    python3 render_glb_preview.py avocado-cc0.glb avocado-cc0.preview.png

The model stands in as a *device* showing a preview. Slab-shaped models (a
phone) get the preview as a portrait screen inset flush into the front face;
other models (the avocado) get it as a planar decal projected onto — and
shaded with — the curved surface. Either way it tilts with the model and is
occluded by the geometry via the shared z-buffer, the same idea as the
xr-composite renderer painting a Compose preview onto a device. The screen
texture defaults to the spatial now-playing panel. A committed `.glb` path or a
runtime DeviceModelCatalog URL may be given; two orbit angles are rendered side
by side.
"""
import json
import struct
import sys

import numpy as np
from PIL import Image

_COMPONENT = {
    5120: (np.int8, 1), 5121: (np.uint8, 1), 5122: (np.int16, 2),
    5123: (np.uint16, 2), 5125: (np.uint32, 4), 5126: (np.float32, 4),
}
_NUMCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _load_glb(path):
    if path.startswith(("http://", "https://")):
        # Runtime-fetched device models (see DeviceModelCatalog) are referenced by
        # URL, never committed: download to a temp cache and render from there.
        import hashlib
        import os
        import tempfile
        import urllib.request
        cache = os.path.join(tempfile.gettempdir(), "device-models",
                             hashlib.sha1(path.encode()).hexdigest()[:12] + ".glb")
        if not os.path.exists(cache):
            os.makedirs(os.path.dirname(cache), exist_ok=True)
            req = urllib.request.Request(path, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                open(cache, "wb").write(resp.read())
        path = cache
    raw = open(path, "rb").read()
    if raw[:4] != b"glTF":
        raise ValueError("not a glTF-binary")
    off, chunks = 12, {}
    while off < len(raw):
        clen, ctype = struct.unpack_from("<I4s", raw, off)
        off += 8
        chunks[ctype] = raw[off:off + clen]
        off += clen
    return json.loads(chunks[b"JSON"]), chunks[b"BIN\x00"]


def _accessor(gltf, bin_, idx):
    acc = gltf["accessors"][idx]
    view = gltf["bufferViews"][acc["bufferView"]]
    dtype, size = _COMPONENT[acc["componentType"]]
    ncomp = _NUMCOMP[acc["type"]]
    base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride") or size * ncomp
    out = np.empty((acc["count"], ncomp), dtype=dtype)
    for i in range(acc["count"]):
        out[i] = np.frombuffer(bin_, dtype=dtype, count=ncomp,
                               offset=base + i * stride)
    return out


def _mesh(gltf, bin_):
    verts, faces, voff = [], [], 0
    for mesh in gltf["meshes"]:
        for prim in mesh["primitives"]:
            pos = _accessor(gltf, bin_, prim["attributes"]["POSITION"]).astype(float)
            if "indices" in prim:
                idx = _accessor(gltf, bin_, prim["indices"]).reshape(-1, 3).astype(int)
            else:
                idx = np.arange(len(pos)).reshape(-1, 3)
            verts.append(pos)
            faces.append(idx + voff)
            voff += len(pos)
    return np.concatenate(verts), np.concatenate(faces)


def _bary(xs, ys, x0, x1, y0, y1):
    """Barycentric weights of every pixel in the bbox for triangle (xs, ys)."""
    det = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
    if abs(det) < 1e-9:
        return None
    yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
    l1 = ((ys[1] - ys[2]) * (xx - xs[2]) + (xs[2] - xs[1]) * (yy - ys[2])) / det
    l2 = ((ys[2] - ys[0]) * (xx - xs[2]) + (xs[0] - xs[2]) * (yy - ys[2])) / det
    return l1, l2, 1 - l1 - l2


def _project(pts, rot, size):
    p = pts @ rot.T
    scale = size * 0.42
    return p[:, 0] * scale + size / 2, -p[:, 1] * scale + size / 2, p[:, 2]


def _rot(yaw, pitch):
    cy, sy, cp, sp = np.cos(yaw), np.sin(yaw), np.cos(pitch), np.sin(pitch)
    return (np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]]) @
            np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]))


def _render(verts, faces, yaw, pitch, screen=None, size=720,
            colour=(0.62, 0.78, 0.40), decal=None):
    rot = _rot(yaw, pitch)
    proj = verts @ rot.T
    light = np.array([0.4, 0.7, 0.6])
    light /= np.linalg.norm(light)
    img = np.full((size, size, 3), 250.0)
    zbuf = np.full((size, size), -1e9)
    sx, sy_, sz = _project(verts, rot, size)
    base = np.asarray(colour)
    duv = dtex = None
    if decal is not None:
        duv, dtex = decal["uv"], decal["texture"]
        dth, dtw = dtex.shape[:2]

    for a, b, c in faces:
        normal = np.cross(proj[b] - proj[a], proj[c] - proj[a])
        norm = np.linalg.norm(normal)
        if norm == 0:
            continue
        shade = 0.25 + 0.75 * max(0.0, abs(normal / norm @ light))
        col = np.clip(base * shade * 255, 0, 255)
        xs, ys, zs = sx[[a, b, c]], sy_[[a, b, c]], sz[[a, b, c]]
        x0, x1 = int(max(0, np.floor(xs.min()))), int(min(size - 1, np.ceil(xs.max())))
        y0, y1 = int(max(0, np.floor(ys.min()))), int(min(size - 1, np.ceil(ys.max())))
        if x0 > x1 or y0 > y1:
            continue
        w = _bary(xs, ys, x0, x1, y0, y1)
        if w is None:
            continue
        l1, l2, l3 = w
        inside = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        if not inside.any():
            continue
        z = l1 * zs[0] + l2 * zs[1] + l3 * zs[2]
        zsub = zbuf[y0:y1 + 1, x0:x1 + 1]
        upd = inside & (z > zsub)
        # Flat-shaded base, with an optional decal projected onto the front-facing
        # (model +Z) triangles so the preview is painted onto the surface and
        # follows its curvature.
        region = np.empty((y1 - y0 + 1, x1 - x0 + 1, 3))
        region[:] = col
        if duv is not None and np.cross(verts[b] - verts[a], verts[c] - verts[a])[2] > 0:
            u = l1 * duv[a, 0] + l2 * duv[b, 0] + l3 * duv[c, 0]
            v = l1 * duv[a, 1] + l2 * duv[b, 1] + l3 * duv[c, 1]
            on = inside & (u >= 0) & (u <= 1) & (v >= 0) & (v <= 1)
            if on.any():
                tx = np.clip((u * (dtw - 1)).astype(int), 0, dtw - 1)
                ty = np.clip((v * (dth - 1)).astype(int), 0, dth - 1)
                region[on] = np.clip((dtex[ty, tx] * shade)[on], 0, 255)
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = region[upd]

    if screen is not None:
        _composite_screen(img, zbuf, rot, size, **screen)
    return Image.fromarray(img.astype(np.uint8))


def _quad(img, zbuf, rot, size, corners, shade):
    """Fill a model-space quad with per-vertex colours (used for the bezel)."""
    qx, qy, qz = _project(corners, rot, size)
    for tri in ((0, 1, 2), (0, 2, 3)):
        xs, ys, zs = qx[list(tri)], qy[list(tri)], qz[list(tri)]
        x0, x1 = int(max(0, np.floor(xs.min()))), int(min(size - 1, np.ceil(xs.max())))
        y0, y1 = int(max(0, np.floor(ys.min()))), int(min(size - 1, np.ceil(ys.max())))
        if x0 > x1 or y0 > y1:
            continue
        w = _bary(xs, ys, x0, x1, y0, y1)
        if w is None:
            continue
        l1, l2, l3 = w
        inside = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        z = l1 * zs[0] + l2 * zs[1] + l3 * zs[2]
        zsub = zbuf[y0:y1 + 1, x0:x1 + 1]
        upd = inside & (z >= zsub)
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = shade


def _composite_screen(img, zbuf, rot, size, corners, uv, texture, bezel=None):
    """Paint `texture` onto a model-space quad (the device's "screen")."""
    if bezel is not None:
        _quad(img, zbuf, rot, size, bezel, np.array([18.0, 18.0, 22.0]))  # dark bezel
    th, tw = texture.shape[:2]
    has_alpha = texture.shape[-1] == 4  # RGBA -> transparent corners show the body
    qx, qy, qz = _project(corners, rot, size)
    for tri in ((0, 1, 2), (0, 2, 3)):
        i = list(tri)
        xs, ys, zs = qx[i], qy[i], qz[i]
        uvs = uv[i]
        x0, x1 = int(max(0, np.floor(xs.min()))), int(min(size - 1, np.ceil(xs.max())))
        y0, y1 = int(max(0, np.floor(ys.min()))), int(min(size - 1, np.ceil(ys.max())))
        if x0 > x1 or y0 > y1:
            continue
        w = _bary(xs, ys, x0, x1, y0, y1)
        if w is None:
            continue
        l1, l2, l3 = w
        inside = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        if not inside.any():
            continue
        z = l1 * zs[0] + l2 * zs[1] + l3 * zs[2]
        u = l1 * uvs[0, 0] + l2 * uvs[1, 0] + l3 * uvs[2, 0]
        v = l1 * uvs[0, 1] + l2 * uvs[1, 1] + l3 * uvs[2, 1]
        tx = np.clip((u * (tw - 1)).astype(int), 0, tw - 1)
        ty = np.clip((v * (th - 1)).astype(int), 0, th - 1)
        sample = texture[ty, tx]
        zsub = zbuf[y0:y1 + 1, x0:x1 + 1]
        upd = inside & (z >= zsub)
        if has_alpha:
            upd = upd & (sample[..., 3] >= 128)
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = sample[..., :3][upd]


def _orient_device(verts):
    """Canonicalise axis order so a slab-shaped device stands upright with its
    display face toward +Z (longest extent -> Y, thinnest -> Z). Chunky/round
    models (e.g. the avocado, already canonical) pass through unchanged."""
    ext = verts.max(0) - verts.min(0)
    order = np.argsort(ext)[::-1]  # axis indices: long, mid, thin
    if ext[order[2]] / ext[order[0]] >= 0.6:
        return verts
    perm = np.zeros((3, 3))
    perm[0, order[1]] = 1  # mid  -> X (width)
    perm[1, order[0]] = 1  # long -> Y (height)
    perm[2, order[2]] = 1  # thin -> Z (toward camera)
    if np.linalg.det(perm) < 0:
        perm[2, order[2]] = -1  # keep right-handed; don't mirror the normals
    return verts @ perm.T


def _portrait_screen(panel, aspect, radius_frac=0.055):
    """Lay the (landscape) preview panel into a portrait phone-screen image of
    the given width/height `aspect`, with rounded corners (transparent outside)
    so the display matches the device's rounded glass instead of being a square
    sticker."""
    from PIL import ImageDraw
    height = 1024
    width = max(1, int(round(height * aspect)))
    screen = Image.new("RGB", (width, height), (248, 247, 250))
    panel_h = max(1, int(round(width * panel.height / panel.width)))
    screen.paste(panel.resize((width, panel_h)), (0, int(height * 0.06)))
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, width - 1, height - 1],
        radius=int(min(width, height) * radius_frac), fill=255)
    rgba = screen.convert("RGBA")
    rgba.putalpha(mask)
    return rgba


def _screen_fill_face(verts, panel, bezel_frac=0.05):
    """A phone-style screen: a portrait display inset into (and flush with) the
    device's front face, filled with the preview content. Reads as a real screen
    rather than a panel floating in front of the device."""
    xmin, ymin, _ = verts.min(0)
    xmax, ymax, zmax = verts.max(0)
    margin = min(xmax - xmin, ymax - ymin) * bezel_frac
    x0, x1 = xmin + margin, xmax - margin
    y0, y1 = ymin + margin, ymax - margin
    zf = zmax + 0.004  # flush on the face, a hair proud so it isn't z-fought
    corners = np.array([[x0, y1, zf], [x1, y1, zf], [x1, y0, zf], [x0, y0, zf]])
    uv = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], float)
    tex = _portrait_screen(panel, (x1 - x0) / (y1 - y0))
    return dict(corners=corners, uv=uv, texture=np.asarray(tex), bezel=None)


def _decal_on(verts, panel, cover=0.92):
    """Planar decal: project `panel` orthographically onto the model's front (+Z)
    face, preserving the panel's aspect, returning per-vertex UVs + the texture.
    Used for non-slab models (e.g. the avocado) so the preview is painted onto the
    curved surface instead of floating in front of it."""
    xmin, ymin, _ = verts.min(0)
    xmax, ymax, _ = verts.max(0)
    cx, cy = (xmin + xmax) / 2, (ymin + ymax) / 2
    fw, fh = (xmax - xmin) * cover, (ymax - ymin) * cover
    aspect = panel.width / panel.height
    rw, rh = (fh * aspect, fh) if fw / fh > aspect else (fw, fw / aspect)
    x_left, y_top = cx - rw / 2, cy + rh / 2
    u = (verts[:, 0] - x_left) / rw
    v = (y_top - verts[:, 1]) / rh
    return dict(uv=np.stack([u, v], 1), texture=np.asarray(panel.convert("RGB")))


def _screen_on(verts, texture, normal="+z"):
    """A screen quad on the model's front face, sized to the texture aspect."""
    texture = np.asarray(texture)
    zmax = verts[:, 2].max()
    aspect = texture.shape[1] / texture.shape[0]  # w/h
    h = 0.42
    w = h * aspect
    cy = 0.05
    zf = zmax + 0.02  # screen plane, just proud of the frontmost vertex
    zb = zmax + 0.01  # bezel plane, just behind the screen
    pad = 0.06
    # corners CCW: top-left, top-right, bottom-right, bottom-left
    corners = np.array([[-w, cy + h, zf], [w, cy + h, zf],
                        [w, cy - h, zf], [-w, cy - h, zf]])
    bezel = np.array([[-w - pad, cy + h + pad, zb], [w + pad, cy + h + pad, zb],
                      [w + pad, cy - h - pad, zb], [-w - pad, cy - h - pad, zb]])
    uv = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], float)
    return dict(corners=corners, uv=uv, texture=np.asarray(texture), bezel=bezel)


def main(model, out, screen_png="../../../../docs/design/xr-spatial/now-playing.png",
         colour=None):
    # `model` is a committed .glb path or a runtime-fetched URL (DeviceModelCatalog).
    gltf, bin_ = _load_glb(model)
    verts, faces = _mesh(gltf, bin_)
    verts = _orient_device(verts)
    centre = (verts.max(0) + verts.min(0)) / 2
    verts = (verts - centre) / np.abs(verts - centre).max()
    if colour is None:
        colour = (0.62, 0.78, 0.40) if "avocado" in model else (0.30, 0.32, 0.36)

    ext = verts.max(0) - verts.min(0)
    order = np.argsort(ext)[::-1]
    is_phone = ext[order[2]] / ext[order[0]] < 0.25  # thin slab -> real screen

    screen = decal = None
    if screen_png:
        tex = Image.open(screen_png).convert("RGBA")
        flat = Image.new("RGBA", tex.size, (255, 255, 255, 255))
        tex = Image.alpha_composite(flat, tex).convert("RGB")  # transparent -> white
        if is_phone:
            screen = _screen_fill_face(verts, tex)  # inset display, flush in bezel
        else:
            decal = _decal_on(verts, tex)  # painted onto the curved surface

    a = _render(verts, faces, np.radians(-26), np.radians(14), screen, colour=colour, decal=decal)
    b = _render(verts, faces, np.radians(30), np.radians(18), screen, colour=colour, decal=decal)
    combo = Image.new("RGB", (a.width + b.width + 20, a.height), (255, 255, 255))
    combo.paste(a, (0, 0))
    combo.paste(b, (a.width + 20, 0))
    combo.save(out)
    note = "screen decal" if decal else "device screen" if screen else "geometry only"
    print(f"{model}: {len(verts)} verts / {len(faces)} tris ({note}) -> {out}")


if __name__ == "__main__":
    main(*(sys.argv[1:3] or ["avocado-cc0.glb", "avocado-cc0.preview.png"]))
