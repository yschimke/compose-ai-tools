#!/usr/bin/env python3
"""Render a committed .glb fixture to a PNG preview, headless and GPU-free.

This exists so the visual reference next to a model fixture is *reproducible*
rather than a one-off screenshot: re-run it whenever the fixture changes.

It is a tiny CPU rasteriser (z-buffered, flat diffuse shading) that reads the
glTF-binary directly — no WebGL / OpenGL, no Android, no three.js — so it runs
in any headless container. It only needs numpy + Pillow:

    pip install numpy pillow
    python3 render_glb_preview.py avocado-cc0.glb avocado-cc0.preview.png

The model stands in as a *device*: a preview "screen" image is composited onto
its front face (anchored in model space, so it tilts with the model and is
occluded by the geometry via the shared z-buffer) — the same idea as the
xr-composite renderer painting a Compose preview onto a device panel. Pass a
screen texture as the third arg; defaults to the spatial now-playing panel.
Two front-ish orbit angles are rendered side by side.
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
            colour=(0.62, 0.78, 0.40)):
    rot = _rot(yaw, pitch)
    proj = verts @ rot.T
    light = np.array([0.4, 0.7, 0.6])
    light /= np.linalg.norm(light)
    img = np.full((size, size, 3), 250.0)
    zbuf = np.full((size, size), -1e9)
    sx, sy_, sz = _project(verts, rot, size)
    base = np.asarray(colour)

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
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = col

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


def _composite_screen(img, zbuf, rot, size, corners, uv, texture, bezel):
    """Paint `texture` onto a model-space quad (the device's "screen")."""
    _quad(img, zbuf, rot, size, bezel, np.array([18.0, 18.0, 22.0]))  # dark bezel
    th, tw = texture.shape[:2]
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
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = sample[upd]


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


def main(glb, out, screen_png="../../../../docs/design/xr-spatial/now-playing.png"):
    gltf, bin_ = _load_glb(glb)
    verts, faces = _mesh(gltf, bin_)
    centre = (verts.max(0) + verts.min(0)) / 2
    verts = (verts - centre) / np.abs(verts - centre).max()

    screen = None
    if screen_png:
        tex = Image.open(screen_png).convert("RGBA")
        flat = Image.new("RGBA", tex.size, (255, 255, 255, 255))
        tex = Image.alpha_composite(flat, tex).convert("RGB")  # transparent -> white
        screen = _screen_on(verts, tex)

    a = _render(verts, faces, np.radians(-26), np.radians(14), screen)
    b = _render(verts, faces, np.radians(30), np.radians(18), screen)
    combo = Image.new("RGB", (a.width + b.width + 20, a.height), (255, 255, 255))
    combo.paste(a, (0, 0))
    combo.paste(b, (a.width + 20, 0))
    combo.save(out)
    note = "device screen composited" if screen else "geometry only"
    print(f"{glb}: {len(verts)} verts / {len(faces)} tris ({note}) -> {out}")


if __name__ == "__main__":
    main(*(sys.argv[1:3] or ["avocado-cc0.glb", "avocado-cc0.preview.png"]))
