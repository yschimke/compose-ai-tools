#!/usr/bin/env python3
"""Render a committed .glb fixture to a PNG preview, headless and GPU-free.

This exists so the visual reference next to a model fixture is *reproducible*
rather than a one-off screenshot: re-run it whenever the fixture changes.

It is a tiny CPU rasteriser (z-buffered, flat diffuse shading) that reads the
glTF-binary directly — no WebGL / OpenGL, no Android, no three.js — so it runs
in any headless container. It only needs numpy + Pillow:

    pip install numpy pillow
    python3 render_glb_preview.py avocado-cc0.glb avocado-cc0.preview.png

Two orbit angles are rendered side by side. Colour is a fixed avocado-green
because the lean fixtures are geometry-only (textures stripped); the point of
the preview is to show the *shape* the fixture contains.
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


def _render(verts, faces, yaw, pitch, size=720, colour=(0.62, 0.78, 0.40)):
    cy, sy, cp, sp = np.cos(yaw), np.sin(yaw), np.cos(pitch), np.sin(pitch)
    rot = (np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]]) @
           np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]))
    proj = verts @ rot.T
    light = np.array([0.4, 0.7, 0.6])
    light /= np.linalg.norm(light)
    img = np.full((size, size, 3), 250.0)
    zbuf = np.full((size, size), -1e9)
    scale = size * 0.42
    sx = proj[:, 0] * scale + size / 2
    sy_ = -proj[:, 1] * scale + size / 2
    sz = proj[:, 2]
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
        det = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(det) < 1e-9:
            continue
        yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        l1 = ((ys[1] - ys[2]) * (xx - xs[2]) + (xs[2] - xs[1]) * (yy - ys[2])) / det
        l2 = ((ys[2] - ys[0]) * (xx - xs[2]) + (xs[0] - xs[2]) * (yy - ys[2])) / det
        l3 = 1 - l1 - l2
        inside = (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        if not inside.any():
            continue
        z = l1 * zs[0] + l2 * zs[1] + l3 * zs[2]
        zsub = zbuf[y0:y1 + 1, x0:x1 + 1]
        upd = inside & (z > zsub)
        zsub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = col
    return Image.fromarray(img.astype(np.uint8))


def main(glb, out):
    gltf, bin_ = _load_glb(glb)
    verts, faces = _mesh(gltf, bin_)
    centre = (verts.max(0) + verts.min(0)) / 2
    verts = (verts - centre) / np.abs(verts - centre).max()
    a = _render(verts, faces, np.radians(35), np.radians(20))
    b = _render(verts, faces, np.radians(-120), np.radians(15))
    combo = Image.new("RGB", (a.width + b.width + 20, a.height), (255, 255, 255))
    combo.paste(a, (0, 0))
    combo.paste(b, (a.width + 20, 0))
    combo.save(out)
    print(f"{glb}: {len(verts)} verts / {len(faces)} tris -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "avocado-cc0.glb",
         sys.argv[2] if len(sys.argv) > 2 else "avocado-cc0.preview.png")
