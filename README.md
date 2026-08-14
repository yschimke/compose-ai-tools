# wear-os-samples (ComposeStarter) — Compose previews

Auto-rendered by the integration matrix from [`android/wear-os-samples@main`](https://github.com/android/wear-os-samples/tree/main). Updated on every push to `main`.

## CI notes

- Exercises the full Android render pipeline end-to-end
  (renderer-android AAR resolution + Robolectric launch) plus
  the daemon round-trip (spawn → render → edit → re-render).
- Runs with isolated-projects + configuration-cache enabled, so
  it doubles as the IP/CC end-to-end cell. No build-script
  workarounds applied.
- The gallery below is the clean baseline render, captured
  *before* the daemon round-trip edits a source file, so the
  images reflect upstream `main` rather than the test edit.


### Workarounds applied by the integration harness

- Source: [`android/wear-os-samples@main`](https://github.com/android/wear-os-samples/tree/main)
- No source or build-script workarounds — the project renders against the locally-built plugin snapshot as-is.

## app

| Preview | Image |
|---------|-------|
| `MainActivity` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/activity__MainActivity.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Devices_Large_Round-9a18e797.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Devices_Small_Round-f252ea82.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Large-617795ea.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Larger-03875738.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Largest-8a48596a.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Medium-7eaead98.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Normal-bb568c66.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Small-aff577f2.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Devices_Large_Round-a2cea868.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Devices_Small_Round-098d05af.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Large-48b7ef6f.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Larger-641b3ff3.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Largest-122a19f9.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Medium-3a151642.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Normal-93c561e0.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Small-8ac30c9d.png" width="150" /> |

