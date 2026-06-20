# Test models — provenance & licensing

Small, license-clean glTF binaries used to exercise the GLB-loading / 3D
device-shape viewer path in tests, without network access or third-party API
keys.

## `avocado-cc0.glb`

- **Source:** Khronos `glTF-Sample-Assets`, the *Avocado* model
  (<https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Avocado>).
- **Original license:** [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/legalcode)
  (public domain dedication — no attribution required; recorded here as a courtesy).
- **Modification:** all images/textures/samplers were stripped, leaving geometry
  plus PBR material **color factors** only. This drops the file from ~8 MB to
  ~24 KB so it can live in-tree as a fixture. The strip is a mechanical,
  deterministic transform of the buffer (kept only accessor-referenced
  bufferViews, reindexed, texture references removed from materials). A CC0
  derivative remains CC0.
- **Form factor:** this is a piece of fruit, **not** a device — it is a
  throwaway placeholder chosen purely because it is the smallest unambiguously
  CC0 mesh available. Real device models (smartwatch / phone / foldable) are
  tracked separately; see the "device-model fetch pipeline" issue.

### Why not the original textured model?

The repo's largest committed binary is ~900 KB; an 8–12 MB textured GLB would be
an order of magnitude larger than anything else in-tree. A geometry-only fixture
is enough to validate that a GLB parses and loads.

## Committed fixture vs. runtime device models

This committed `.glb` is the **offline** fixture: license-clean (CC0) and small,
so unit tests and CI can exercise the GLB path without a network fetch.

**Real device models** (e.g. a phone or watch) are *not* committed. They are
referenced by URL in
[`DeviceModelCatalog`](../../../../data/deviceframe/core/src/main/kotlin/ee/schimke/composeai/data/deviceframe/DeviceModelCatalog.kt)
and fetched at runtime + cached — the same "reference, don't redistribute"
posture the device-art bezels use (`DeviceArtCatalog`). Each carries an
attribution string that must be surfaced on any rendered output. `render_glb_preview.py`
renders either a committed `.glb` path or a catalog URL:

```bash
python3 render_glb_preview.py <DeviceModelCatalog url> out.png
```
