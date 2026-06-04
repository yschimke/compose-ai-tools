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
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Devices_Large_Round.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Devices_Small_Round.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Large.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Larger.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Largest.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Medium.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Normal.png" width="150" /> |
| `GreetingScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/GreetingScreenPreview_Fonts_Small.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Devices_Large_Round.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Devices_Small_Round.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Large.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Larger.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Largest.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Medium.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Normal.png" width="150" /> |
| `ListScreenPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-os-samples/renders/app/ListScreenPreview_Fonts_Small.png" width="150" /> |

