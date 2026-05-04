# Changelog

## [0.9.2](https://github.com/yschimke/compose-ai-tools/compare/v0.9.1...v0.9.2) (2026-05-04)


### Features

* **a11y:** hierarchy-android producer in its own small module ([#724](https://github.com/yschimke/compose-ai-tools/issues/724)) ([99e5148](https://github.com/yschimke/compose-ai-tools/commit/99e514843cd2f9034ccae3a5a0fc0c5aad7ce1dc))
* **a11y:** OverlayExtension as 3-input PostCaptureProcessor ([#719](https://github.com/yschimke/compose-ai-tools/issues/719)) ([489b756](https://github.com/yschimke/compose-ai-tools/commit/489b756fa3a48d4feb752acfe0966f9194c52df6))
* **a11y:** OverlayExtension runs through the typed pipeline ([#732](https://github.com/yschimke/compose-ai-tools/issues/732)) ([720fe49](https://github.com/yschimke/compose-ai-tools/commit/720fe492de8831a376fe6f60fa6f5a6c1572c738))
* **a11y:** route TouchTargets through typed pipeline at runtime ([#726](https://github.com/yschimke/compose-ai-tools/issues/726)) ([44cbbb4](https://github.com/yschimke/compose-ai-tools/commit/44cbbb415362d5391fefdb1a80a75b88387cc88a))
* **a11y:** TouchTargetsExtension via new PostCaptureProcessor hook ([#717](https://github.com/yschimke/compose-ai-tools/issues/717)) ([0aa157c](https://github.com/yschimke/compose-ai-tools/commit/0aa157cf8eebc57bfa1e734cf0dcf5ba7fbb9c68))
* **a11y:** wire 11 more a11y.action.* dispatchers via SemanticsActions ([#738](https://github.com/yschimke/compose-ai-tools/issues/738)) ([7876d19](https://github.com/yschimke/compose-ai-tools/commit/7876d19f7171393d2f8aa78e83ccdc1505aef588))
* **a11y:** wire a11y.action.click end-to-end through SemanticsActions.OnClick ([#734](https://github.com/yschimke/compose-ai-tools/issues/734)) ([8523180](https://github.com/yschimke/compose-ai-tools/commit/8523180602e74c010d13418e760f844e6c8d170c))
* **a11y:** wire AccessibilityHierarchyExtension on both render paths ([#751](https://github.com/yschimke/compose-ai-tools/issues/751)) ([8dd4421](https://github.com/yschimke/compose-ai-tools/commit/8dd4421ba2592f658128ae1822a9c4fe5ae60a28))
* add device background around composable hook ([#699](https://github.com/yschimke/compose-ai-tools/issues/699)) ([90a1ae4](https://github.com/yschimke/compose-ai-tools/commit/90a1ae4b3da958b597d39b31271aa7a37bf76845))
* add device background preview extension ([#682](https://github.com/yschimke/compose-ai-tools/issues/682)) ([0451390](https://github.com/yschimke/compose-ai-tools/commit/04513901a48c591cd01c3f71d842041eacfb21f5))
* add device background theme capture facade ([#710](https://github.com/yschimke/compose-ai-tools/issues/710)) ([24e4015](https://github.com/yschimke/compose-ai-tools/commit/24e4015aa985e141758ee86411bf888450bc2653))
* add device clip around composable hook ([#700](https://github.com/yschimke/compose-ai-tools/issues/700)) ([b1e7161](https://github.com/yschimke/compose-ai-tools/commit/b1e716160da7dccd62eef86b90f2819df371836d))
* add layout inspector capture context ([#709](https://github.com/yschimke/compose-ai-tools/issues/709)) ([dc0c2dc](https://github.com/yschimke/compose-ai-tools/commit/dc0c2dc6fc7d4055e1c5bebfc535afe44d7a1da8))
* add material theme preview overrides ([#683](https://github.com/yschimke/compose-ai-tools/issues/683)) ([1ce7959](https://github.com/yschimke/compose-ai-tools/commit/1ce7959e1ed12a553ec8ee7993f198e14c1884e2))
* add scroll gif frame driver hook ([#703](https://github.com/yschimke/compose-ai-tools/issues/703)) ([b62e030](https://github.com/yschimke/compose-ai-tools/commit/b62e030904a42a60142f2e6f8661ebcccb058388))
* add theme token capture facade ([#714](https://github.com/yschimke/compose-ai-tools/issues/714)) ([2c64548](https://github.com/yschimke/compose-ai-tools/commit/2c64548f720cca295efbe3f73a6981e4cf00d80e))
* **daemon:** wire lifecycle.event as a host-owned recording-script extension ([#741](https://github.com/yschimke/compose-ai-tools/issues/741)) ([d2f7ef2](https://github.com/yschimke/compose-ai-tools/commit/d2f7ef2b01ef63e78b20878089980ecd224c86aa))
* **daemon:** wire preview.reload via key(...) invalidation ([#742](https://github.com/yschimke/compose-ai-tools/issues/742)) ([74ac182](https://github.com/yschimke/compose-ai-tools/commit/74ac182d396d6435d1a76db0748bdf97fb79f2f0))
* **daemon:** wire state.recreate via SaveableStateRegistry snapshot+restore ([#744](https://github.com/yschimke/compose-ai-tools/issues/744)) ([eec9948](https://github.com/yschimke/compose-ai-tools/commit/eec99487906ad9055ee06ee6d29c58db5f287719))
* **daemon:** wire state.save / state.restore with named checkpoints ([#749](https://github.com/yschimke/compose-ai-tools/issues/749)) ([94f96d8](https://github.com/yschimke/compose-ai-tools/commit/94f96d8b9aeaa055f858316cf749295be4b6f4ee))
* **extensions:** typed context keys for non-product hook inputs ([#739](https://github.com/yschimke/compose-ai-tools/issues/739)) ([872f723](https://github.com/yschimke/compose-ai-tools/commit/872f7235641f5b25ac4c2605df134a03a9afa9ba))
* **extensions:** typed runtime contract for data extensions ([#716](https://github.com/yschimke/compose-ai-tools/issues/716)) ([6a57253](https://github.com/yschimke/compose-ai-tools/commit/6a57253132e735cb890f3c5b6f752addc5250d87))


### Bug Fixes

* **a11y:** plan only transitively-runnable extensions per render ([#728](https://github.com/yschimke/compose-ai-tools/issues/728)) ([55708aa](https://github.com/yschimke/compose-ai-tools/commit/55708aa8e43f6436c883b6b6f13b4efa2c2f7f58))
* **daemon-android:** import getOrNull for SemanticsConfiguration ([#745](https://github.com/yschimke/compose-ai-tools/issues/745)) ([1fa7755](https://github.com/yschimke/compose-ai-tools/commit/1fa7755851b01d62cb3e1d225f80b44657ed87b8))
* **plugin:** defer KMP-Android desktop runtime classpath lookup ([#725](https://github.com/yschimke/compose-ai-tools/issues/725)) ([3b4f086](https://github.com/yschimke/compose-ai-tools/commit/3b4f086ebe34777829c2826aeeb972890233d4be))
* **vscode:** hide icon-button[hidden] in the focus-mode toolbar ([#765](https://github.com/yschimke/compose-ai-tools/issues/765)) ([2cd106a](https://github.com/yschimke/compose-ai-tools/commit/2cd106a21aac0b67957e9c88e4926b1b76ba139c))
* **vscode:** pin webview esbuild to tsconfig.webview.json ([#758](https://github.com/yschimke/compose-ai-tools/issues/758)) ([2d026f5](https://github.com/yschimke/compose-ai-tools/commit/2d026f50f7bd5a0050eb89d862ea1c7b566024b0))

## [0.9.1](https://github.com/yschimke/compose-ai-tools/compare/v0.9.0...v0.9.1) (2026-05-03)


### Bug Fixes

* **cli:** keep JSON output free of Gradle stdout ([#666](https://github.com/yschimke/compose-ai-tools/issues/666)) ([b660b04](https://github.com/yschimke/compose-ai-tools/commit/b660b04d505eb1eef5a829008a118c9832ce5886))
* handle MCP serve without project ([#678](https://github.com/yschimke/compose-ai-tools/issues/678)) ([ec9980d](https://github.com/yschimke/compose-ai-tools/commit/ec9980dc08bc8272e77f4df8bd6edbd3ee0411aa))

## [0.9.0](https://github.com/yschimke/compose-ai-tools/compare/v0.8.12...v0.9.0) (2026-05-03)


### ⚠ BREAKING CHANGES

* request 0.9.0 release ([#658](https://github.com/yschimke/compose-ai-tools/issues/658))

### Features

* add text strings data product ([#549](https://github.com/yschimke/compose-ai-tools/issues/549)) ([9fe1038](https://github.com/yschimke/compose-ai-tools/commit/9fe1038799f2fd54e9226b3f1da76690da245964))
* **cli:** add daemon library foundation ([#616](https://github.com/yschimke/compose-ai-tools/issues/616)) ([60a09cd](https://github.com/yschimke/compose-ai-tools/commit/60a09cdeb649c02f77ffb3459cc2d83439d3ae78))
* **cli:** add data product commands ([#620](https://github.com/yschimke/compose-ai-tools/issues/620)) ([14f9d57](https://github.com/yschimke/compose-ai-tools/commit/14f9d5745deccec688398b573a5e865a13c19ce0))
* **cli:** add history commands ([#624](https://github.com/yschimke/compose-ai-tools/issues/624)) ([ddb4bfb](https://github.com/yschimke/compose-ai-tools/commit/ddb4bfb2339c061929fb7100756dbe70611408b5))
* **daemon-desktop:** DesktopInteractiveSession holds scene across inputs ([#408](https://github.com/yschimke/compose-ai-tools/issues/408)) ([c8fdb63](https://github.com/yschimke/compose-ai-tools/commit/c8fdb63cfca71948f7517eb516b61f221539efdd))
* **daemon,mcp:** mp4 / webm recording via optional ffmpeg (P3) ([#487](https://github.com/yschimke/compose-ai-tools/issues/487)) ([54ad3d9](https://github.com/yschimke/compose-ai-tools/commit/54ad3d9c82de0bd372228396016febc6796539dc))
* **daemon,plugin:** thread display dimensions through PreviewInfoDto ([#439](https://github.com/yschimke/compose-ai-tools/issues/439)) ([e4e0a94](https://github.com/yschimke/compose-ai-tools/commit/e4e0a943c1400202747967b73ecad5a50c2f9ca8))
* **daemon,vscode:** D2 a11y data products end-to-end + focus-mode toggle ([#410](https://github.com/yschimke/compose-ai-tools/issues/410)) ([e7db84d](https://github.com/yschimke/compose-ai-tools/commit/e7db84d40cb17bb79b4bf7472c47b6848d614977))
* **daemon:** add v3 Android-interactive bridge primitives ([#459](https://github.com/yschimke/compose-ai-tools/issues/459)) ([e789b0b](https://github.com/yschimke/compose-ai-tools/commit/e789b0b69c27209caa3b1c71abf87efc47df2c3c))
* **daemon:** advertise InitializeResult.capabilities.interactive ([#425](https://github.com/yschimke/compose-ai-tools/issues/425)) ([28e0005](https://github.com/yschimke/compose-ai-tools/commit/28e0005d58535b3873af9bf1d65d2a12e846a330))
* **daemon:** advertise known device catalog via initialize capabilities ([#433](https://github.com/yschimke/compose-ai-tools/issues/433)) ([3b9dee5](https://github.com/yschimke/compose-ai-tools/commit/3b9dee52328c98f319687df1c629277b5d21b148))
* **daemon:** advertise renderer backend in initialize capabilities ([#458](https://github.com/yschimke/compose-ai-tools/issues/458)) ([4f4e4c2](https://github.com/yschimke/compose-ai-tools/commit/4f4e4c27e07fd3cf52aeb8c8c15cb8226d7806b8))
* **daemon:** advertise supportedOverrides in initialize capabilities ([#441](https://github.com/yschimke/compose-ai-tools/issues/441)) ([2811a47](https://github.com/yschimke/compose-ai-tools/commit/2811a47cd6261beca1f32e37f7764f277537e7d6))
* **daemon:** affinity-aware sandbox-pool dispatch (previewId-keyed) ([#374](https://github.com/yschimke/compose-ai-tools/issues/374)) ([85217a0](https://github.com/yschimke/compose-ai-tools/commit/85217a0e6207ae61e1827b52c62501733970d5c9))
* **daemon:** Android (Robolectric) scripted recording (P5) ([#496](https://github.com/yschimke/compose-ai-tools/issues/496)) ([49fc0a3](https://github.com/yschimke/compose-ai-tools/commit/49fc0a31e8e806bc86d3ddb531a678ffbdaab832))
* **daemon:** auto-prune + history/prune RPC + historyPruned notification (H4) ([#335](https://github.com/yschimke/compose-ai-tools/issues/335)) ([b183559](https://github.com/yschimke/compose-ai-tools/commit/b183559ba1cfb6c72ec17ee1b2f20dcd32bde359))
* **daemon:** coalesce interactive input bursts on in-flight render ([#409](https://github.com/yschimke/compose-ai-tools/issues/409)) ([740cee6](https://github.com/yschimke/compose-ai-tools/commit/740cee6ee0fb6b0a8d29bc3a174862311e870126))
* **daemon:** compose/recomposition delta producer (D5) ([#444](https://github.com/yschimke/compose-ai-tools/issues/444)) ([48e19dc](https://github.com/yschimke/compose-ai-tools/commit/48e19dce2c7129c31114a7620a5bc1092c4ef783))
* **daemon:** D2 a11y data products end-to-end (daemon side) ([#412](https://github.com/yschimke/compose-ai-tools/issues/412)) ([784d52e](https://github.com/yschimke/compose-ai-tools/commit/784d52e0354cda83060312f80d2bad12cff4bb71))
* **daemon:** data/fetch re-render-on-demand with per-request budget ([#419](https://github.com/yschimke/compose-ai-tools/issues/419)) ([9b2935d](https://github.com/yschimke/compose-ai-tools/commit/9b2935d21b4299b50d6a83812b33ce29859e28d1))
* **daemon:** device override on renderNow ([#423](https://github.com/yschimke/compose-ai-tools/issues/423)) ([95f4e7f](https://github.com/yschimke/compose-ai-tools/commit/95f4e7ff06ed479f48f61630deaae438d4847572))
* **daemon:** harden v3 Android interactive lifecycle ([#473](https://github.com/yschimke/compose-ai-tools/issues/473)) ([9704e00](https://github.com/yschimke/compose-ai-tools/commit/9704e0000e0ef1c5f3d07d14b05f78d8fb33268d))
* **daemon:** history/diff (metadata) + GitRefHistorySource read (H3 + H10a) ([#322](https://github.com/yschimke/compose-ai-tools/issues/322)) ([310dbab](https://github.com/yschimke/compose-ai-tools/commit/310dbabb0c0616f00fe914a101337b0bebdb50cd))
* **daemon:** image-processor seam + extras on data products (D2.1) ([#472](https://github.com/yschimke/compose-ai-tools/issues/472)) ([2f3d3cb](https://github.com/yschimke/compose-ai-tools/commit/2f3d3cb12dbcacdd81913b543a01809223ba0db1))
* **daemon:** in-JVM sandbox pool — RobolectricHost(sandboxCount = N) now works ([#350](https://github.com/yschimke/compose-ai-tools/issues/350)) ([a1a3942](https://github.com/yschimke/compose-ai-tools/commit/a1a3942be37488479d63d7249d3426a8e5a9df60))
* **daemon:** interactive RPC + frame dedup + multi-target streams ([#400](https://github.com/yschimke/compose-ai-tools/issues/400)) ([8a51383](https://github.com/yschimke/compose-ai-tools/commit/8a5138348a791d37f83a3a7750f8076d6a84219a))
* **daemon:** InteractiveSession interface + RenderHost.acquireInteractiveSession ([#406](https://github.com/yschimke/compose-ai-tools/issues/406)) ([537a10f](https://github.com/yschimke/compose-ai-tools/commit/537a10f953fed9f4b458e82283a5eee3cacc370a))
* **daemon:** live (non-scripted) recording driven by recording/input (P4) ([#491](https://github.com/yschimke/compose-ai-tools/issues/491)) ([4f87535](https://github.com/yschimke/compose-ai-tools/commit/4f875354a813879c9507395b6a4b304c3a95a6b6))
* **daemon:** per-call display-property overrides on renderNow ([#402](https://github.com/yschimke/compose-ai-tools/issues/402)) ([634099a](https://github.com/yschimke/compose-ai-tools/commit/634099aa1e4c628dfea5d326172688e63c5aa444))
* **daemon:** per-kind subscribe params + producer subscription lifecycle ([#435](https://github.com/yschimke/compose-ai-tools/issues/435)) ([a23ce2a](https://github.com/yschimke/compose-ai-tools/commit/a23ce2a43d7f921d8e24d52d7d9097eff0737996))
* **daemon:** per-render captureAdvanceMs override + bumpable maxRenderMs ([#460](https://github.com/yschimke/compose-ai-tools/issues/460)) ([6d382c0](https://github.com/yschimke/compose-ai-tools/commit/6d382c0b1eed7a470b2b53a3b3f0d11040110380))
* **daemon:** per-slot user-class child loaders — pool now hot-reload-compatible ([#377](https://github.com/yschimke/compose-ai-tools/issues/377)) ([02d0c0b](https://github.com/yschimke/compose-ai-tools/commit/02d0c0b23458ae48e253d303ab1845d27071f582))
* **daemon:** persistent preview daemon — design + B1/B2 implementation (opt-in, speculative) ([#303](https://github.com/yschimke/compose-ai-tools/issues/303)) ([b4e63ee](https://github.com/yschimke/compose-ai-tools/commit/b4e63ee314e54e9642268bed23d384dd4c16ea3c))
* **daemon:** publish daemon-core, daemon-desktop, daemon-android to Maven Central ([#373](https://github.com/yschimke/compose-ai-tools/issues/373)) ([27ba566](https://github.com/yschimke/compose-ai-tools/commit/27ba566a4cd82b933835e54699ae188173330466))
* **daemon:** record history per render + history/list + history/read (H1+H2) ([#318](https://github.com/yschimke/compose-ai-tools/issues/318)) ([46bd81b](https://github.com/yschimke/compose-ai-tools/commit/46bd81bced7cd2791a03a14053ac92890e9c09a0))
* **daemon:** scripted screen-record surface with virtual frame clock (P1) ([#478](https://github.com/yschimke/compose-ai-tools/issues/478)) ([0f6bbec](https://github.com/yschimke/compose-ai-tools/commit/0f6bbec6afa5fe639b2fd89f0bdf431967041d0c))
* **daemon:** silent metadata reconcile on save; render PNGs before discoveryUpdated ([#378](https://github.com/yschimke/compose-ai-tools/issues/378)) ([b21b292](https://github.com/yschimke/compose-ai-tools/commit/b21b292f3ba95f3fce45774053ec94c5c14a651f))
* **daemon:** startup timeline instrumentation + RobolectricHost.start blocks until ready ([#327](https://github.com/yschimke/compose-ai-tools/issues/327)) ([0a374fc](https://github.com/yschimke/compose-ai-tools/commit/0a374fc7a4a2902ad7352c30e63bb5784d91ab49))
* **daemon:** wire v3 Android-interactive held-rule loop ([#467](https://github.com/yschimke/compose-ai-tools/issues/467)) ([31e65a5](https://github.com/yschimke/compose-ai-tools/commit/31e65a5e9f42fa34ad0e73c0724b26ac7ffbe3ed))
* expose history prune initialize options ([#555](https://github.com/yschimke/compose-ai-tools/issues/555)) ([0793d9a](https://github.com/yschimke/compose-ai-tools/commit/0793d9a49810869307346ace3a81fe3e1d1eb77f))
* **mcp:** 1+N replica model per (workspace, module) for parallel renders ([#338](https://github.com/yschimke/compose-ai-tools/issues/338)) ([92b3096](https://github.com/yschimke/compose-ai-tools/commit/92b3096c5c85dbf8c021ee23c0fc91acde704782))
* **mcp:** auto-render in get_preview_data + refcount data subscriptions ([#415](https://github.com/yschimke/compose-ai-tools/issues/415)) ([5891a85](https://github.com/yschimke/compose-ai-tools/commit/5891a852e2e68a1820c43103ef1e836b896e52cf))
* **mcp:** bundle MCP server in the CLI launcher; document agent flows ([#485](https://github.com/yschimke/compose-ai-tools/issues/485)) ([3f05950](https://github.com/yschimke/compose-ai-tools/commit/3f05950c29e0c05258024a7068ba453554680d59))
* **mcp:** cache attached data products + --attach-data-product flag ([#430](https://github.com/yschimke/compose-ai-tools/issues/430)) ([73944e5](https://github.com/yschimke/compose-ai-tools/commit/73944e5333b903b6a09438c507ea178b5f101eb1))
* **mcp:** default replicasPerDaemon to 3 (4 sandboxes per daemon) ([#366](https://github.com/yschimke/compose-ai-tools/issues/366)) ([6a69e63](https://github.com/yschimke/compose-ai-tools/commit/6a69e636c673a64726c2aece6c4659312ce4b49a))
* **mcp:** expose data products via list/get/subscribe tools ([#404](https://github.com/yschimke/compose-ai-tools/issues/404)) ([d45ae75](https://github.com/yschimke/compose-ai-tools/commit/d45ae75cee1271983a6e552774fda98e67908b68))
* **mcp:** expose data products via list/get/subscribe tools ([#405](https://github.com/yschimke/compose-ai-tools/issues/405)) ([e98d2c7](https://github.com/yschimke/compose-ai-tools/commit/e98d2c7c8566dc326e4170fd4fbd133363f67823))
* **mcp:** list_devices tool surfaces the @Preview device catalog ([#438](https://github.com/yschimke/compose-ai-tools/issues/438)) ([6ea5924](https://github.com/yschimke/compose-ai-tools/commit/6ea59242978f7f91c0c3deedcfa9e2af543a558a))
* **mcp:** record_preview tool driving daemon recording surface (P2) ([#484](https://github.com/yschimke/compose-ai-tools/issues/484)) ([08abe96](https://github.com/yschimke/compose-ai-tools/commit/08abe969d5a14a22e2f8bd1f33871f8de403c247))
* **mcp:** set_visible/set_focus, targeted historyAdded fan-out, Session interface, README + docs ([#332](https://github.com/yschimke/compose-ai-tools/issues/332)) ([52275ac](https://github.com/yschimke/compose-ai-tools/commit/52275ac004ee974e413d3e7ce9aa65eab6197b7d))
* **mcp:** supervisor wire-up — replicasPerDaemon = in-JVM sandbox pool ([#357](https://github.com/yschimke/compose-ai-tools/issues/357)) ([e2c1e63](https://github.com/yschimke/compose-ai-tools/commit/e2c1e63889f2c93aede469e92f15f262f7a9f7f3))
* **mcp:** top-level :mcp module — Model Context Protocol server over the preview daemon ([#309](https://github.com/yschimke/compose-ai-tools/issues/309)) ([6b74573](https://github.com/yschimke/compose-ai-tools/commit/6b745734a606ba96f9bab6f8b3241501e96b5293))
* **mcp:** validate render_preview overrides against daemon capabilities ([#457](https://github.com/yschimke/compose-ai-tools/issues/457)) ([3328094](https://github.com/yschimke/compose-ai-tools/commit/33280942824695a06f7145162739dea9698008fc))
* **plugin:** register composePreviewDaemonStart for desktop modules ([#316](https://github.com/yschimke/compose-ai-tools/issues/316)) ([f92f7cc](https://github.com/yschimke/compose-ai-tools/commit/f92f7cca72dd8b1446b75ff26a82c88df48a9cf5))
* **renderer-android:** structured per-preview runtime errors via sidecar ([#389](https://github.com/yschimke/compose-ai-tools/issues/389)) ([75e6c47](https://github.com/yschimke/compose-ai-tools/commit/75e6c47c1c459ea92a861cac4081c9ee23aa7f01))
* request 0.9.0 release ([#658](https://github.com/yschimke/compose-ai-tools/issues/658)) ([08f11fe](https://github.com/yschimke/compose-ai-tools/commit/08f11fe89890d4362e27bbce0834cbff7f4ae888))
* **samples/wear:** animate FixedPreviewTimeSource in previews ([#490](https://github.com/yschimke/compose-ai-tools/issues/490)) ([d3267e7](https://github.com/yschimke/compose-ai-tools/commit/d3267e7d9b0773da5c5dcc532f64bddb0bc3c530))
* settings to gate a11y data products on producer + consumer sides ([#429](https://github.com/yschimke/compose-ai-tools/issues/429)) ([14ae185](https://github.com/yschimke/compose-ai-tools/commit/14ae185c5cb13586e7434897dedecd751638b061))
* support desktop locale overrides when available ([#562](https://github.com/yschimke/compose-ai-tools/issues/562)) ([97f78d6](https://github.com/yschimke/compose-ai-tools/commit/97f78d659ddfb1c050abaf7c96592d7a5ea988d7))
* support desktop locale overrides when available ([#573](https://github.com/yschimke/compose-ai-tools/issues/573)) ([4a81a09](https://github.com/yschimke/compose-ai-tools/commit/4a81a09b982dbeb4ae6f571140bf2059ecc3d9fa))
* **vscode,renderer-desktop:** structured per-preview runtime errors on failing cards ([#385](https://github.com/yschimke/compose-ai-tools/issues/385)) ([2cb2e14](https://github.com/yschimke/compose-ai-tools/commit/2cb2e1406568b755cd89b9a418a39b42eac487b0))
* **vscode:** add Launch on Device button to preview view ([#392](https://github.com/yschimke/compose-ai-tools/issues/392)) ([b03609b](https://github.com/yschimke/compose-ai-tools/commit/b03609bf09a4d93021529df8899c95d7a97159c8))
* **vscode:** channel-close stream cleanup + Shift+LIVE multi-stream UI ([#424](https://github.com/yschimke/compose-ai-tools/issues/424)) ([cf2cdb9](https://github.com/yschimke/compose-ai-tools/commit/cf2cdb9a3bc0f908dbc55b427d0c7c4b666e8e36))
* **vscode:** close the LSP-gate loop — auto-retry on diagnostic clear, debounce save-edge reads ([#380](https://github.com/yschimke/compose-ai-tools/issues/380)) ([a8bef48](https://github.com/yschimke/compose-ai-tools/commit/a8bef48509f5ada570f9d96c6d7c4e8d6f6ac158))
* **vscode:** default the preview daemon to enabled ([#381](https://github.com/yschimke/compose-ai-tools/issues/381)) ([0d7e08f](https://github.com/yschimke/compose-ai-tools/commit/0d7e08f80f90ec7e2d11624c41bead8eea8afc9a))
* **vscode:** Diff All Previews vs Main command ([#367](https://github.com/yschimke/compose-ai-tools/issues/367)) ([54a20f8](https://github.com/yschimke/compose-ai-tools/commit/54a20f8113574fae7bfe8443bc9d2c54600a5db2))
* **vscode:** diff mode toggle — side / overlay / onion-skin ([#361](https://github.com/yschimke/compose-ai-tools/issues/361)) ([e161ea9](https://github.com/yschimke/compose-ai-tools/commit/e161ea9f6fb72f369c241a4885cec8ee5c84b33a))
* **vscode:** discoverable focus mode + History panel scope chip ([#348](https://github.com/yschimke/compose-ai-tools/issues/348)) ([424717b](https://github.com/yschimke/compose-ai-tools/commit/424717b5f19a9f088254666db75e17d3302f36bd))
* **vscode:** focus-mode arrow-key nav + stable history scope on save ([#349](https://github.com/yschimke/compose-ai-tools/issues/349)) ([9291611](https://github.com/yschimke/compose-ai-tools/commit/9291611fd9dcadff32ae64eb7a1aa9ff4826ee97))
* **vscode:** gate early focus features ([#660](https://github.com/yschimke/compose-ai-tools/issues/660)) ([e0c47b3](https://github.com/yschimke/compose-ai-tools/commit/e0c47b3d116a97a50b28a1f17d74d945743626ec))
* **vscode:** history row diff vs current / vs previous (MVP) ([#354](https://github.com/yschimke/compose-ai-tools/issues/354)) ([4deb0b0](https://github.com/yschimke/compose-ai-tools/commit/4deb0b012667a5baaa9c048147fbe5115862d864))
* **vscode:** history thumbnails, relative timestamps, drop JSON dump ([#352](https://github.com/yschimke/compose-ai-tools/issues/352)) ([a9bf2d6](https://github.com/yschimke/compose-ai-tools/commit/a9bf2d6e18acacf2c8996600ab64d5c7e87951e1))
* **vscode:** launch previews via PreviewActivity instead of LAUNCHER ([#393](https://github.com/yschimke/compose-ai-tools/issues/393)) ([ce135d9](https://github.com/yschimke/compose-ai-tools/commit/ce135d9eefc9c6e2fb01802886423955ac29fcae))
* **vscode:** live panel diff vs HEAD / vs main (MVP) ([#359](https://github.com/yschimke/compose-ai-tools/issues/359)) ([a50d1ec](https://github.com/yschimke/compose-ai-tools/commit/a50d1ecbd584a4ed27728fcc68a20be4ef0f434f))
* **vscode:** live-stream interactive mode in focus view (daemon-only) ([#394](https://github.com/yschimke/compose-ai-tools/issues/394)) ([b8c3d4a](https://github.com/yschimke/compose-ai-tools/commit/b8c3d4a22efb6d839c64134d7686f966925d844e))
* **vscode:** one-click LIVE on any preview, auto-stop on focus/scroll ([#437](https://github.com/yschimke/compose-ai-tools/issues/437)) ([ef21fa7](https://github.com/yschimke/compose-ai-tools/commit/ef21fa75f68c9f22fdab5727f351f43bb30871d8))
* **vscode:** parse kotlinc errors from Gradle output ([#356](https://github.com/yschimke/compose-ai-tools/issues/356)) ([bdd00a9](https://github.com/yschimke/compose-ai-tools/commit/bdd00a99f991adef43df4e587b179d31e1d6bc84))
* **vscode:** per-row vs-main dot in history panel ([#365](https://github.com/yschimke/compose-ai-tools/issues/365)) ([c410d7b](https://github.com/yschimke/compose-ai-tools/commit/c410d7badf233596bd8627a9a8e3f51ca9f87dbd))
* **vscode:** pixel-stats line on diff results ([#363](https://github.com/yschimke/compose-ai-tools/issues/363)) ([17d9e75](https://github.com/yschimke/compose-ai-tools/commit/17d9e750e5440bcd85b80b4c613229dbf3fd7510))
* **vscode:** preload cached previews on activation so the panel never opens empty ([#388](https://github.com/yschimke/compose-ai-tools/issues/388)) ([58cdb04](https://github.com/yschimke/compose-ai-tools/commit/58cdb0499a92a76ab8dd66928b8c6e710105abd1))
* **vscode:** preview history panel + FS reader + historyAdded subscription (H7 B/C/D) ([#329](https://github.com/yschimke/compose-ai-tools/issues/329)) ([e5fdac7](https://github.com/yschimke/compose-ai-tools/commit/e5fdac7a806f3582b92ac5479d6e0d290b44e782))
* **vscode:** progress bar, glitch-free fast refresh, LSP compile-error gate ([#344](https://github.com/yschimke/compose-ai-tools/issues/344)) ([753decd](https://github.com/yschimke/compose-ai-tools/commit/753decd7fda5fff26f1b3710dff6b9070ac7409b))
* **vscode:** refresh open diff overlay when live render lands ([#379](https://github.com/yschimke/compose-ai-tools/issues/379)) ([08d09fd](https://github.com/yschimke/compose-ai-tools/commit/08d09fdbef17db3806e55eff3b79833feac695e7))
* **vscode:** refresh open vs-main diff when preview_main ref moves ([#375](https://github.com/yschimke/compose-ai-tools/issues/375)) ([39c3354](https://github.com/yschimke/compose-ai-tools/commit/39c33545046d72e4880660e3aa6efcbeb138e47c))
* **vscode:** rich error card for runtime render failures ([#390](https://github.com/yschimke/compose-ai-tools/issues/390)) ([cde6706](https://github.com/yschimke/compose-ai-tools/commit/cde67060441f9df2b81c8cdf3beb7473d7845f6b))
* **vscode:** show current renders in history panel when no recorded history ([#333](https://github.com/yschimke/compose-ai-tools/issues/333)) ([562a501](https://github.com/yschimke/compose-ai-tools/commit/562a5017df1ff720c46320713871dcfb9f5db252))
* **vscode:** status-bar hint when interactive runs on v1-fallback host ([#431](https://github.com/yschimke/compose-ai-tools/issues/431)) ([c2eada2](https://github.com/yschimke/compose-ai-tools/commit/c2eada2f2c5ffa45c9baee0bd7a46a32ab80dd71))
* **vscode:** trim "Compose Preview" output via composePreview.logging.level ([#345](https://github.com/yschimke/compose-ai-tools/issues/345)) ([06ea11f](https://github.com/yschimke/compose-ai-tools/commit/06ea11f4295bea34f1892090306c3f3b54340a00))
* **vscode:** vs-main fallback to preview_main baselines branch ([#371](https://github.com/yschimke/compose-ai-tools/issues/371)) ([3f4f4e8](https://github.com/yschimke/compose-ai-tools/commit/3f4f4e853e7a70ed3a0ad8c8eef33a94cd2b3b17))


### Bug Fixes

* **build:** apply maven.publish in root with apply false ([#387](https://github.com/yschimke/compose-ai-tools/issues/387)) ([5176983](https://github.com/yschimke/compose-ai-tools/commit/51769834e11c904a973fb512cee8aae11b1b4c40))
* **ci:** unblock format check + preview action self-references ([#346](https://github.com/yschimke/compose-ai-tools/issues/346)) ([82bf6d5](https://github.com/yschimke/compose-ai-tools/commit/82bf6d542d92884d2a27451e8da1af5ff295d3c7))
* **cli:** derive BUNDLE_VERSION from build, not a hand-edited literal ([#383](https://github.com/yschimke/compose-ai-tools/issues/383)) ([dec3d90](https://github.com/yschimke/compose-ai-tools/commit/dec3d9086645b2e59bafc3f3df6051cbaa4dce48))
* **daemon-harness:** pull skiko native bundle via testFixtures classpath ([#436](https://github.com/yschimke/compose-ai-tools/issues/436)) ([ea4e26f](https://github.com/yschimke/compose-ai-tools/commit/ea4e26fc0e9fb5417275d9784aa625d7d3714d2b))
* **daemon,vscode:** nested params schema + source-vs-PNG stale banner + auto-render on activate ([#360](https://github.com/yschimke/compose-ai-tools/issues/360)) ([45f2b70](https://github.com/yschimke/compose-ai-tools/commit/45f2b709faa9636d6e30383af0b42b7bc73420b2))
* **daemon:** apply wear-round circular crop on android backend ([#339](https://github.com/yschimke/compose-ai-tools/issues/339)) ([81d38b5](https://github.com/yschimke/compose-ai-tools/commit/81d38b5376079e72ab1129b63edd875cc88cd0f0))
* **daemon:** close interactive sessions immediately on transport EOF ([#475](https://github.com/yschimke/compose-ai-tools/issues/475)) ([8559133](https://github.com/yschimke/compose-ai-tools/commit/8559133ec948bbd3db2e127fd429de3e345a2f20))
* **daemon:** live recording propagates tick failures + guarantees first frame ([#492](https://github.com/yschimke/compose-ai-tools/issues/492)) ([2762869](https://github.com/yschimke/compose-ai-tools/commit/276286902726e0abc9464c0e598f7e1f1f676a4f))
* **daemon:** resolve renderNow device override to dimensions in production path ([#476](https://github.com/yschimke/compose-ai-tools/issues/476)) ([582fc80](https://github.com/yschimke/compose-ai-tools/commit/582fc801db7ae962767c100e6ca3faac6d473174))
* **daemon:** skip writing sidecars when bytes match the most recent entry ([#340](https://github.com/yschimke/compose-ai-tools/issues/340)) ([b9bcede](https://github.com/yschimke/compose-ai-tools/commit/b9bcede1b290c885ef4e853e83c64c658b76aea7))
* **deps:** update dependency org.checkerframework:checker-qual to v4 ([#466](https://github.com/yschimke/compose-ai-tools/issues/466)) ([0aedfaa](https://github.com/yschimke/compose-ai-tools/commit/0aedfaaac2acdd9d32314c9d6997dc7c25cca2d1))
* **diff-bot,cli:** surface CLI stdout pollution + flush before every exit ([#486](https://github.com/yschimke/compose-ai-tools/issues/486)) ([eec0a1e](https://github.com/yschimke/compose-ai-tools/commit/eec0a1edeaae388f3a16b575c82a9a38fd174bec))
* **mcp:** record_preview content-block shape + strict fps/scale ([#488](https://github.com/yschimke/compose-ai-tools/issues/488)) ([c072169](https://github.com/yschimke/compose-ai-tools/commit/c0721690779416130f2a4777785ae57896117ea4))
* **mcp:** serialize per-previewId render queue to fix wrong-bytes hazard ([#445](https://github.com/yschimke/compose-ai-tools/issues/445)) ([efbdb7c](https://github.com/yschimke/compose-ai-tools/commit/efbdb7c6b7e7907b8f6cb2e03218febce854981f))
* **mcp:** thread overrides through awaitNextRender + re-key dedup ([#432](https://github.com/yschimke/compose-ai-tools/issues/432)) ([1aa4239](https://github.com/yschimke/compose-ai-tools/commit/1aa423922dc47875dfd5d5b96b9267412c64fa08))
* **plugin,ci:** unbreak wear-os-samples daemon-roundtrip integration ([#442](https://github.com/yschimke/compose-ai-tools/issues/442)) ([af54203](https://github.com/yschimke/compose-ai-tools/commit/af54203c2016ca66eb550516a71595848e73021c))
* **plugin:** emit composeai.daemon.historyDir from composePreviewDaemonStart (history view was empty) ([#334](https://github.com/yschimke/compose-ai-tools/issues/334)) ([658f01e](https://github.com/yschimke/compose-ai-tools/commit/658f01ec45f49bdcc7c18f77db6dd1d35aecc22e))
* **plugin:** make composePreviewDaemonStart config-cache safe + wire AGP producer tasks ([#315](https://github.com/yschimke/compose-ai-tools/issues/315)) ([8e984e4](https://github.com/yschimke/compose-ai-tools/commit/8e984e44f97e5626bc5eebfc972bbfbaf7c17fc1))
* publish daemon data product dependencies ([#619](https://github.com/yschimke/compose-ai-tools/issues/619)) ([491664a](https://github.com/yschimke/compose-ai-tools/commit/491664a73b724cc78b23f8395fa07c9f0dbfd4f6))
* **release-please:** restore manifest to last released, target 0.9.0 ([#324](https://github.com/yschimke/compose-ai-tools/issues/324)) ([0a36e46](https://github.com/yschimke/compose-ai-tools/commit/0a36e4660f7736751697973e0d1362fc5758d5a5))
* report MCP watch discovery readiness ([#657](https://github.com/yschimke/compose-ai-tools/issues/657)) ([856da0e](https://github.com/yschimke/compose-ai-tools/commit/856da0e87ee9f4edf185ca69b6f90b1cd5d9eb5b))
* serialize focus recording mutations ([#635](https://github.com/yschimke/compose-ai-tools/issues/635)) ([fd334e8](https://github.com/yschimke/compose-ai-tools/commit/fd334e897a09a0bbf6de2b05d083a4e235950fdd))
* **vscode,daemon:** cancellation gate, classloader URL ordering, restart command, self-diagnostic logs ([#353](https://github.com/yschimke/compose-ai-tools/issues/353)) ([bf8e279](https://github.com/yschimke/compose-ai-tools/commit/bf8e27969abf6cebe25c985eaf82240517de5963))
* **vscode:** deliberate daemon/gradle switch on save (no more parallel renders) ([#331](https://github.com/yschimke/compose-ai-tools/issues/331)) ([6b854bb](https://github.com/yschimke/compose-ai-tools/commit/6b854bbd28857b81be49b4c4a0175bd5c2a5975b))
* **vscode:** empty history when unscoped + read() through live scope ([#351](https://github.com/yschimke/compose-ai-tools/issues/351)) ([7e91a7d](https://github.com/yschimke/compose-ai-tools/commit/7e91a7d61bf2c20e9d3f6709be2242516174efa0))
* **vscode:** guard post-warm daemon refresh ([#502](https://github.com/yschimke/compose-ai-tools/issues/502)) ([0f9d439](https://github.com/yschimke/compose-ai-tools/commit/0f9d439b49e4b72b122d2b00f145796911c37960))
* **vscode:** hide live preview title overlay ([#513](https://github.com/yschimke/compose-ai-tools/issues/513)) ([53798ba](https://github.com/yschimke/compose-ai-tools/commit/53798baa7d4b9dfd5d879ae98def102ccf643951))
* **vscode:** ignore generated preview scope ([#512](https://github.com/yschimke/compose-ai-tools/issues/512)) ([82440e0](https://github.com/yschimke/compose-ai-tools/commit/82440e0bee4acd227fd79744d1b0a1d014d60eb1))
* **vscode:** keep daemon reconcile scoped ([#523](https://github.com/yschimke/compose-ai-tools/issues/523)) ([fab40e5](https://github.com/yschimke/compose-ai-tools/commit/fab40e5ec39b3974ad12b60dd09ac93d59f88ab5))
* **vscode:** keep startup previews steady ([#515](https://github.com/yschimke/compose-ai-tools/issues/515)) ([9c16e99](https://github.com/yschimke/compose-ai-tools/commit/9c16e992ccaa81c9ff5a8d4778b3abb75ab5f429))
* **vscode:** no layout shift on refresh; auto-render stale source; no stale-image flash ([#369](https://github.com/yschimke/compose-ai-tools/issues/369)) ([5b4261e](https://github.com/yschimke/compose-ai-tools/commit/5b4261e9e7752e34f13177d20d3b35f3a5aad43a))
* **vscode:** recompile before notifying daemon of save ([#342](https://github.com/yschimke/compose-ai-tools/issues/342)) ([d309d0e](https://github.com/yschimke/compose-ai-tools/commit/d309d0ec68b52aa9987e04d0419465ca635cabfe))
* **vscode:** reconcile daemon preview ids before render ([#521](https://github.com/yschimke/compose-ai-tools/issues/521)) ([7d39555](https://github.com/yschimke/compose-ai-tools/commit/7d39555e46f60cbd1e902ff83fa1eeb9a4aab050))
* **vscode:** reserve a row for the progress strip instead of overlaying the toolbar ([#358](https://github.com/yschimke/compose-ai-tools/issues/358)) ([a48b8fa](https://github.com/yschimke/compose-ai-tools/commit/a48b8faa9e15ed071b5b2e150cf94309482994c4))
* **vscode:** scope history to focused preview, dblclick-to-focus ([#343](https://github.com/yschimke/compose-ai-tools/issues/343)) ([48e02a0](https://github.com/yschimke/compose-ai-tools/commit/48e02a0125b23550dadc5e2f954b3b8145617177))
* **vscode:** stop progress bar getting stuck after a slow refresh ([#364](https://github.com/yschimke/compose-ai-tools/issues/364)) ([710a705](https://github.com/yschimke/compose-ai-tools/commit/710a705d1205bc1757ca546309b4d49fe2c31e12))
* **vscode:** surface live fallback mode ([#510](https://github.com/yschimke/compose-ai-tools/issues/510)) ([bdea5d9](https://github.com/yschimke/compose-ai-tools/commit/bdea5d909177ec36015ed2c235b20db1e7614a85))


### Performance Improvements

* **vscode:** long-running git cat-file --batch for preview_main lookups ([#372](https://github.com/yschimke/compose-ai-tools/issues/372)) ([a763a75](https://github.com/yschimke/compose-ai-tools/commit/a763a75ad99473dc603cacb6474f4d50de4a0f73))


### Reverts

* **mcp:** drop speculative --attach-data-product CLI flag ([#434](https://github.com/yschimke/compose-ai-tools/issues/434)) ([8233cd9](https://github.com/yschimke/compose-ai-tools/commit/8233cd97c28b61ea55527d805dc0d1578e723497))

## [0.8.12](https://github.com/yschimke/compose-ai-tools/compare/v0.8.11...v0.8.12) (2026-04-29)


### Bug Fixes

* **vscode:** find nested modules + silence cancel-as-failure noise ([#304](https://github.com/yschimke/compose-ai-tools/issues/304)) ([3bf73e4](https://github.com/yschimke/compose-ai-tools/commit/3bf73e4ffd28f286f2a300935dc6e362069e83ea))

## [0.8.11](https://github.com/yschimke/compose-ai-tools/compare/v0.8.10...v0.8.11) (2026-04-29)


### Features

* **resource-preview:** themed monochrome + squircle adaptive-icon captures ([#294](https://github.com/yschimke/compose-ai-tools/issues/294)) ([e0ff21e](https://github.com/yschimke/compose-ai-tools/commit/e0ff21e625ae3c2448f3e94bad7e3c039e8c8659))
* **vscode:** add AndroidManifest CodeLens for icon attributes ([#272](https://github.com/yschimke/compose-ai-tools/issues/272)) ([0d1647b](https://github.com/yschimke/compose-ai-tools/commit/0d1647b30df1443561a82f583f7988a2deb842ed))

## [0.8.10](https://github.com/yschimke/compose-ai-tools/compare/v0.8.9...v0.8.10) (2026-04-26)


### Bug Fixes

* **diff-bot:** perceptual filter for resource captures ([#287](https://github.com/yschimke/compose-ai-tools/issues/287)) ([646d6fa](https://github.com/yschimke/compose-ai-tools/commit/646d6fae2dcd3af1cc22943770b0c3d5a4a0a4bf))

## [0.8.9](https://github.com/yschimke/compose-ai-tools/compare/v0.8.8...v0.8.9) (2026-04-26)


### Features

* **actions:** include resource diffs in PR preview comments ([#269](https://github.com/yschimke/compose-ai-tools/issues/269)) ([2852250](https://github.com/yschimke/compose-ai-tools/commit/285225016f61a70187e03addc843d8be5b8ec5f4))
* **cli:** add publish-images subcommand for preview_pr-style branch pushes ([#274](https://github.com/yschimke/compose-ai-tools/issues/274)) ([7c6102c](https://github.com/yschimke/compose-ai-tools/commit/7c6102c73632e2c8cea616f20f08631da63516a4))
* **cli:** add share-gist subcommand for markdown + image attachments ([#271](https://github.com/yschimke/compose-ai-tools/issues/271)) ([c2a602a](https://github.com/yschimke/compose-ai-tools/commit/c2a602a05dd5bc7d6f310df14f522f7fa743d661))
* **cli:** doctor surfaces the applied plugin version ([#268](https://github.com/yschimke/compose-ai-tools/issues/268)) ([5d4d893](https://github.com/yschimke/compose-ai-tools/commit/5d4d893c6f07959aaccaf054dce8bcbb71bf9723))
* **render:** android XML resource previews (vector / AVD / adaptive icon) ([#259](https://github.com/yschimke/compose-ai-tools/issues/259)) ([da187a6](https://github.com/yschimke/compose-ai-tools/commit/da187a6598620a98796dbded404fc270899fad83))
* **render:** stage Android resource renders into preview_main ([#267](https://github.com/yschimke/compose-ai-tools/issues/267)) ([e7405f9](https://github.com/yschimke/compose-ai-tools/commit/e7405f9b3273f1153d1e10b23f63a5aa03fe37e4))


### Bug Fixes

* **ci:** stop install smoke test racing release uploads ([#261](https://github.com/yschimke/compose-ai-tools/issues/261)) ([8aed84e](https://github.com/yschimke/compose-ai-tools/commit/8aed84e23811b660e4f2a151e729d7670d682457))
* **cli:** publish-images branch-name allowlist + refname validation ([#278](https://github.com/yschimke/compose-ai-tools/issues/278)) ([66e5cb4](https://github.com/yschimke/compose-ai-tools/commit/66e5cb4ebc041aec7da0416e03da316a61a36a4d))
* **diff-bot:** perceptual filter for sha-different-but-AA-identical previews ([#270](https://github.com/yschimke/compose-ai-tools/issues/270)) ([476d0aa](https://github.com/yschimke/compose-ai-tools/commit/476d0aa18b34ae457288037cb318469cadaa02e3))
* **diff:** tolerate empty / missing baselines.json in preview-comment action ([#273](https://github.com/yschimke/compose-ai-tools/issues/273)) ([4840346](https://github.com/yschimke/compose-ai-tools/commit/484034686389a90625069474c0e2d8886d705e75))
* **plugin:** align Hamcrest on renderer classpath; doctor flags 2.x/1.3 skew ([#282](https://github.com/yschimke/compose-ai-tools/issues/282)) ([d86ee97](https://github.com/yschimke/compose-ai-tools/commit/d86ee97aabd99f5839b6edc172e254b9f97c371a))

## [0.8.8](https://github.com/yschimke/compose-ai-tools/compare/v0.8.7...v0.8.8) (2026-04-26)


### Features

* **a11y:** legend right of screenshot, inline merged children ([#257](https://github.com/yschimke/compose-ai-tools/issues/257)) ([81b4937](https://github.com/yschimke/compose-ai-tools/commit/81b4937c1fa3fa8273c08fecf0c2999921f52aa8))
* **renderer:** paint synthetic system bars when showSystemUi = true ([#258](https://github.com/yschimke/compose-ai-tools/issues/258)) ([bd73749](https://github.com/yschimke/compose-ai-tools/commit/bd73749797ed1bc4bbb042740ed98f9ccd56f3d0))

## [0.8.7](https://github.com/yschimke/compose-ai-tools/compare/v0.8.6...v0.8.7) (2026-04-26)


### Bug Fixes

* **plugin:** route com.android.kotlin.multiplatform.library through the desktop renderer ([#254](https://github.com/yschimke/compose-ai-tools/issues/254)) ([315a961](https://github.com/yschimke/compose-ai-tools/commit/315a961a9e47737e7085dfc9a3e7c260939374b3))

## [0.8.6](https://github.com/yschimke/compose-ai-tools/compare/v0.8.5...v0.8.6) (2026-04-26)


### Bug Fixes

* **plugin:** walk a copyRecursive() so transitive detection doesn't lock parent configs ([#244](https://github.com/yschimke/compose-ai-tools/issues/244)) ([5c5b518](https://github.com/yschimke/compose-ai-tools/commit/5c5b5184e37c904618b8aee800e66de397d5cda7))

## [0.8.5](https://github.com/yschimke/compose-ai-tools/compare/v0.8.4...v0.8.5) (2026-04-26)


### Bug Fixes

* **plugin:** detect transitive @Preview dep in CMP-Android layouts ([#242](https://github.com/yschimke/compose-ai-tools/issues/242)) ([1caf3f9](https://github.com/yschimke/compose-ai-tools/commit/1caf3f927cac71930ecad2c90a9faac59416721e))

## [0.8.4](https://github.com/yschimke/compose-ai-tools/compare/v0.8.3...v0.8.4) (2026-04-26)


### Bug Fixes

* **actions:** pin nested uses: refs so external consumers resolve ([#240](https://github.com/yschimke/compose-ai-tools/issues/240)) ([ade2acd](https://github.com/yschimke/compose-ai-tools/commit/ade2acdb3707f8ca7c946fb4623b524d702870f8))
* **format:** reformat Commands.kt + auto-install git hooks on session start ([#237](https://github.com/yschimke/compose-ai-tools/issues/237)) ([a81c37a](https://github.com/yschimke/compose-ai-tools/commit/a81c37a0fabdeacbaf54e8491f72d975f2c3f2a9))

## [0.8.3](https://github.com/yschimke/compose-ai-tools/compare/v0.8.2...v0.8.3) (2026-04-26)


### Features

* **actions:** add consumer-facing install composite action ([#233](https://github.com/yschimke/compose-ai-tools/issues/233)) ([a64939a](https://github.com/yschimke/compose-ai-tools/commit/a64939aaf2dd07511639dc4c0689f1b85e424163))

## [0.8.2](https://github.com/yschimke/compose-ai-tools/compare/v0.8.1...v0.8.2) (2026-04-26)


### Features

* **install:** add --android-sdk flag for cloud bootstrapping ([#215](https://github.com/yschimke/compose-ai-tools/issues/215)) ([cd58cc5](https://github.com/yschimke/compose-ai-tools/commit/cd58cc543b43ee15d9d68139a410fd673a5ff96b))
* **plugin:** cost-aware shard auto-tuning + LPT bin-packing ([#207](https://github.com/yschimke/compose-ai-tools/issues/207)) ([44b080d](https://github.com/yschimke/compose-ai-tools/commit/44b080d1f7e9bd31757d7964b83c336ef5f09710))
* **plugin:** fail-fast on too-old Gradle at apply time ([#214](https://github.com/yschimke/compose-ai-tools/issues/214)) ([60a04be](https://github.com/yschimke/compose-ai-tools/commit/60a04be8c0def61e21849ba33d309374d51a71cc))
* **plugin:** support AGP 8.x consumers; add agp8-min integration fixture ([#217](https://github.com/yschimke/compose-ai-tools/issues/217)) ([1e4c559](https://github.com/yschimke/compose-ai-tools/commit/1e4c559f3a996c100ab981944fe0476669d70401))
* **renderer:** mark unmerged a11y nodes and surface more semantic state ([#234](https://github.com/yschimke/compose-ai-tools/issues/234)) ([5f0e887](https://github.com/yschimke/compose-ai-tools/commit/5f0e8877e921f3532a51c2f19ce6ebc1a0f76626))


### Bug Fixes

* **cli:** hash GIF previews by first+last frame ([#209](https://github.com/yschimke/compose-ai-tools/issues/209)) ([#231](https://github.com/yschimke/compose-ai-tools/issues/231)) ([0a32deb](https://github.com/yschimke/compose-ai-tools/commit/0a32debd4cf5404e4d2c51537c6888c8c399b8fa))
* **cli:** surface failing renderPreviews tests on build failure ([#224](https://github.com/yschimke/compose-ai-tools/issues/224)) ([96710a4](https://github.com/yschimke/compose-ai-tools/commit/96710a4d6745edc9d108d38676e0daa731a6d956))
* **deps:** hold compose-remote at alpha08 to match remote-material3 alpha02 ([#232](https://github.com/yschimke/compose-ai-tools/issues/232)) ([ec6df08](https://github.com/yschimke/compose-ai-tools/commit/ec6df08df0c3cf0fea7e0c4f9560f1a80cc5e0ba))
* **deps:** update dependency androidx.compose:compose-bom to v2026 ([#206](https://github.com/yschimke/compose-ai-tools/issues/206)) ([5f67f00](https://github.com/yschimke/compose-ai-tools/commit/5f67f001a0c49748f148538cffc531b0fe05d97b))
* **deps:** update gradle minor/patch ([#199](https://github.com/yschimke/compose-ai-tools/issues/199)) ([1290600](https://github.com/yschimke/compose-ai-tools/commit/12906003270ecf4b1ee502abed134b2e6eae2d11))
* **install:** pre-write Android SDK license hashes instead of `yes |` pipe ([#219](https://github.com/yschimke/compose-ai-tools/issues/219)) ([f0535b8](https://github.com/yschimke/compose-ai-tools/commit/f0535b8c272c35a262dd61ad56742471363a3c29))
* **plugin:** accept org.jetbrains.compose.ui:ui-tooling-preview as @Preview signal ([#220](https://github.com/yschimke/compose-ai-tools/issues/220)) ([222eb69](https://github.com/yschimke/compose-ai-tools/commit/222eb69c6f85417738db2a0a4ed15ed7190e6cb2))
* **renderer:** support PreviewAnimationClock 1.11 constructor shape ([#228](https://github.com/yschimke/compose-ai-tools/issues/228)) ([04745df](https://github.com/yschimke/compose-ai-tools/commit/04745df5a1a770b68069bd4b54e248220841f859))

## [0.8.1](https://github.com/yschimke/compose-ai-tools/compare/v0.8.0...v0.8.1) (2026-04-25)


### Features

* **ext:** two-tier preview rendering + fix stuck "Building…" banner ([#196](https://github.com/yschimke/compose-ai-tools/issues/196)) ([e9308fc](https://github.com/yschimke/compose-ai-tools/commit/e9308fce68d2f1fd8e6405b46450ccdb387a025e))

## [0.8.0](https://github.com/yschimke/compose-ai-tools/compare/v0.7.12...v0.8.0) (2026-04-25)


### Features

* **renderer:** @AnimatedPreview annotation + GIF + curve sidecar ([#183](https://github.com/yschimke/compose-ai-tools/issues/183)) ([b0d4674](https://github.com/yschimke/compose-ai-tools/commit/b0d46741fe931e8451983ac127d9a0870aa7b6d9))
* **renderer:** ANI legend with matched colours, skip dynamic previews ([#193](https://github.com/yschimke/compose-ai-tools/issues/193)) ([8aa8388](https://github.com/yschimke/compose-ai-tools/commit/8aa838835570ed2b5af359c9f02472ecd8432463))


### Bug Fixes

* keep .a11y.png siblings during stale-render cleanup ([#185](https://github.com/yschimke/compose-ai-tools/issues/185)) ([c69617f](https://github.com/yschimke/compose-ai-tools/commit/c69617fac443542099934eb4f746cb2c3f25097d))
* **renderer:** plot IntSize/IntOffset animation values correctly ([#192](https://github.com/yschimke/compose-ai-tools/issues/192)) ([0af9231](https://github.com/yschimke/compose-ai-tools/commit/0af9231356f29de50b5ae219f3fe9b016c729e46))


### Miscellaneous Chores

* release 0.8.0 ([444d934](https://github.com/yschimke/compose-ai-tools/commit/444d93479c11c692936df22cc4e6310bff895626))

## [0.7.12](https://github.com/yschimke/compose-ai-tools/compare/v0.7.11...v0.7.12) (2026-04-24)


### Features

* **renderer:** realistic scroll shape for ScrollMode.GIF ([#180](https://github.com/yschimke/compose-ai-tools/issues/180)) ([9efb72d](https://github.com/yschimke/compose-ai-tools/commit/9efb72dd2fdeb5bf98323be964fff700b8f30d4f))

## [0.7.11](https://github.com/yschimke/compose-ai-tools/compare/v0.7.10...v0.7.11) (2026-04-24)


### Features

* **plugin:** composePreview.manageDependencies opt-out ([#179](https://github.com/yschimke/compose-ai-tools/issues/179)) ([31e560a](https://github.com/yschimke/compose-ai-tools/commit/31e560a65c4284c1435047b78441d8777a2d0957))


### Bug Fixes

* **install:** reuse pre-installed JDK 21 on Claude Cloud instead of forcing JDK 17 ([#174](https://github.com/yschimke/compose-ai-tools/issues/174)) ([2199334](https://github.com/yschimke/compose-ai-tools/commit/219933448da8167890b7fbe0252e348ad4ac1f71))
* **install:** translate $https_proxy into JAVA_TOOL_OPTIONS in cloud mode ([#176](https://github.com/yschimke/compose-ai-tools/issues/176)) ([d397cbd](https://github.com/yschimke/compose-ai-tools/commit/d397cbd4aae26c7aa337bbc716f83be87781d3ae))
* **plugin:** widen dep-jar filter + fail fast when @Preview not on classpath ([#162](https://github.com/yschimke/compose-ai-tools/issues/162)) ([#178](https://github.com/yschimke/compose-ai-tools/issues/178)) ([a00553e](https://github.com/yschimke/compose-ai-tools/commit/a00553ea6e08ae381d129f50fc482b13855ff157))
* **renderer:** wear anchor-based stitch + wire sample-wear tests into CI ([#177](https://github.com/yschimke/compose-ai-tools/issues/177)) ([c32e450](https://github.com/yschimke/compose-ai-tools/commit/c32e450158cec70c704d417048c0918e817c407a))

## [0.7.10](https://github.com/yschimke/compose-ai-tools/compare/v0.7.9...v0.7.10) (2026-04-24)


### Bug Fixes

* **doctor:** drop GitHub Packages credential + probe checks ([#170](https://github.com/yschimke/compose-ai-tools/issues/170)) ([80b6f2e](https://github.com/yschimke/compose-ai-tools/commit/80b6f2e8652e32d1deeefe103977227ac87fe042))
* **renderer:** mask pinned-bottom chrome off intermediate LONG slices ([#173](https://github.com/yschimke/compose-ai-tools/issues/173)) ([6e73810](https://github.com/yschimke/compose-ai-tools/commit/6e7381003ded99027c360b29b3b56c9b7e921dfe))

## [0.7.9](https://github.com/yschimke/compose-ai-tools/compare/v0.7.8...v0.7.9) (2026-04-23)


### Features

* **install:** one-shot skill + CLI bundle for Claude Code cloud ([#164](https://github.com/yschimke/compose-ai-tools/issues/164)) ([6719665](https://github.com/yschimke/compose-ai-tools/commit/67196655019196072a39cad873ab3bb8f0fc8b92))
* **plugin:** composePreview.failOnEmpty flag + discovery diagnostics ([#168](https://github.com/yschimke/compose-ai-tools/issues/168)) ([d8d41e7](https://github.com/yschimke/compose-ai-tools/commit/d8d41e710c47e7f663324f4a0a16da7df50f40b4))


### Bug Fixes

* **doctor:** gate GitHub Packages check on actual consumer usage ([#166](https://github.com/yschimke/compose-ai-tools/issues/166)) ([f6a8b51](https://github.com/yschimke/compose-ai-tools/commit/f6a8b51ca958d7fd2f68e6104b3d2b463a5ccb15))
* **renderer:** reset scroll before LONG/GIF in multi-mode captures ([#167](https://github.com/yschimke/compose-ai-tools/issues/167)) ([67a68f9](https://github.com/yschimke/compose-ai-tools/commit/67a68f98f3310ea39e222fffe5fb1956c11886d0))

## [0.7.8](https://github.com/yschimke/compose-ai-tools/compare/v0.7.7...v0.7.8) (2026-04-23)


### Bug Fixes

* **cli:** resolve nested-module manifests via projectDir ([#157](https://github.com/yschimke/compose-ai-tools/issues/157)) ([#160](https://github.com/yschimke/compose-ai-tools/issues/160)) ([d34c6dd](https://github.com/yschimke/compose-ai-tools/commit/d34c6dd2eb4112e582788556480670a3530c25eb))
* **renderer:** stretch GIF scroll to ~2s for a typical Wear app ([#155](https://github.com/yschimke/compose-ai-tools/issues/155)) ([eecb6fd](https://github.com/yschimke/compose-ai-tools/commit/eecb6fd3ba0c3d2ca1630efa74047ec0db68551c))

## [0.7.7](https://github.com/yschimke/compose-ai-tools/compare/v0.7.6...v0.7.7) (2026-04-22)


### Bug Fixes

* **ithinkihaveacat:** move sdk/graphicsMode to robolectric.properties ([#142](https://github.com/yschimke/compose-ai-tools/issues/142)) ([#151](https://github.com/yschimke/compose-ai-tools/issues/151)) ([91b8032](https://github.com/yschimke/compose-ai-tools/commit/91b80320c4d61aa41c808f2560691495ca341270))

## [0.7.6](https://github.com/yschimke/compose-ai-tools/compare/v0.7.5...v0.7.6) (2026-04-22)


### Features

* **doctor:** surface triage diagnostics for renderPreviews bug reports ([#149](https://github.com/yschimke/compose-ai-tools/issues/149)) ([9b22c39](https://github.com/yschimke/compose-ai-tools/commit/9b22c3993deec3634d96bd2733bc9bcbeede144f))


### Bug Fixes

* **ci:** keep PR preview images resolving after merge ([#146](https://github.com/yschimke/compose-ai-tools/issues/146)) ([434f92e](https://github.com/yschimke/compose-ai-tools/commit/434f92e09ca8243c50dcc1c42ca17913126a52d6))
* **ext:** blank preview panel when scoped .kt is no longer visible ([#148](https://github.com/yschimke/compose-ai-tools/issues/148)) ([637eb0a](https://github.com/yschimke/compose-ai-tools/commit/637eb0a91c47caa329e3cbe16f334072303ef445))

## [0.7.5](https://github.com/yschimke/compose-ai-tools/compare/v0.7.4...v0.7.5) (2026-04-21)


### Features

* **plugin:** derive human-readable filenames for @PreviewParameter fan-outs ([#140](https://github.com/yschimke/compose-ai-tools/issues/140)) ([c746cf5](https://github.com/yschimke/compose-ai-tools/commit/c746cf53f59abbbf233accf34b84d7cce512ca37))

## [0.7.4](https://github.com/yschimke/compose-ai-tools/compare/v0.7.3...v0.7.4) (2026-04-20)


### Bug Fixes

* **plugin:** support com.android.library on AGP 9.x ([#136](https://github.com/yschimke/compose-ai-tools/issues/136)) ([#137](https://github.com/yschimke/compose-ai-tools/issues/137)) ([c46a54a](https://github.com/yschimke/compose-ai-tools/commit/c46a54a48243daaea24fad8ccdda0b0f87ea69c2))

## [0.7.3](https://github.com/yschimke/compose-ai-tools/compare/v0.7.2...v0.7.3) (2026-04-19)


### Features

* **vscode:** detect applied plugin via sidecar marker + catalog alias ([#130](https://github.com/yschimke/compose-ai-tools/issues/130)) ([c6b4b8e](https://github.com/yschimke/compose-ai-tools/commit/c6b4b8e9bea4a7559bbaea72a4edccd045ec700f))

## [0.7.2](https://github.com/yschimke/compose-ai-tools/compare/v0.7.1...v0.7.2) (2026-04-19)


### Features

* **renderer:** support @PreviewParameter fan-out across Android + Desktop ([#126](https://github.com/yschimke/compose-ai-tools/issues/126)) ([075c6ad](https://github.com/yschimke/compose-ai-tools/commit/075c6ad96d381e54de38099a084095dffcdc6be0))
* **sample:** sample-remotecompose — both Remote Compose preview shapes ([#127](https://github.com/yschimke/compose-ai-tools/issues/127)) ([8842ebb](https://github.com/yschimke/compose-ai-tools/commit/8842ebbc386a1828543645d85273f7f1ed3dea8a))

## [0.7.1](https://github.com/yschimke/compose-ai-tools/compare/v0.7.0...v0.7.1) (2026-04-19)


### Features

* **fonts:** render GoogleFont previews correctly under Robolectric ([#116](https://github.com/yschimke/compose-ai-tools/issues/116)) ([8e7603c](https://github.com/yschimke/compose-ai-tools/commit/8e7603c8952d84902461b09a74f20f57e5bfa353))
* **fonts:** transparent DeviceFontFamilyName → GoogleFont swap ([#125](https://github.com/yschimke/compose-ai-tools/issues/125)) ([ba22f06](https://github.com/yschimke/compose-ai-tools/commit/ba22f06d416bbaed69c64a857e0f7999bd01677b))


### Bug Fixes

* **annotations:** new ScrollMode.GIF — animated scroll captures ([#113](https://github.com/yschimke/compose-ai-tools/issues/113)) ([f0b72ab](https://github.com/yschimke/compose-ai-tools/commit/f0b72ab19381c736421e88cd30b6e405129f9464))
* **ci:** publish to Open VSX even when Marketplace publish fails ([156e2ed](https://github.com/yschimke/compose-ai-tools/commit/156e2ed402054ec78245308db5cc48cd0c38af2e))
* **doctor:** flag Gradle versions below AGP 9.1.x's floor ([#115](https://github.com/yschimke/compose-ai-tools/issues/115)) ([1459123](https://github.com/yschimke/compose-ai-tools/commit/14591230ee6e03ce89c02bb574fc92fddc0ab7fb))
* **fonts:** showcase Roboto/Roboto Flex/Google Sans Flex/Lobster Two ([#118](https://github.com/yschimke/compose-ai-tools/issues/118)) ([94cec90](https://github.com/yschimke/compose-ai-tools/commit/94cec90a72371d5827305c29639e13a34bea9014))
* **plugin:** co-exist with com.android.compose.screenshot ([#111](https://github.com/yschimke/compose-ai-tools/issues/111)) ([16af238](https://github.com/yschimke/compose-ai-tools/commit/16af238d7e3fb945b0288c8bc41f0be7ead80fef))
* **release-please:** drop stale release-as override pinning 0.7.0 ([7786478](https://github.com/yschimke/compose-ai-tools/commit/7786478c6823792f2e2e12a7fcb25b70deb62b40))
* **renderer:** replace only the animating tail with settled final LONG frame ([#124](https://github.com/yschimke/compose-ai-tools/issues/124)) ([e9e5f0b](https://github.com/yschimke/compose-ai-tools/commit/e9e5f0b09241fb05c8b682be27532d672ed1904f))

## [0.7.0](https://github.com/yschimke/compose-ai-tools/compare/v0.6.2...v0.7.0) (2026-04-19)


### ⚠ BREAKING CHANGES

* **annotations:** multi-mode @ScrollingPreview ([#104](https://github.com/yschimke/compose-ai-tools/issues/104))

### Features

* **annotations:** multi-mode @ScrollingPreview ([#104](https://github.com/yschimke/compose-ai-tools/issues/104)) ([11bcd2a](https://github.com/yschimke/compose-ai-tools/commit/11bcd2a26f2a0f429e44bfaa17dbcfb24a5542a5))
* auto-publish VS Code extension to Marketplace and Open VSX on release ([ca3964c](https://github.com/yschimke/compose-ai-tools/commit/ca3964c700f9d6b9e6252fe14d752ddc8046f5d5))


### Bug Fixes

* **ci:** expand multi-capture previews in PR diff bot ([#106](https://github.com/yschimke/compose-ai-tools/issues/106)) ([9c39a27](https://github.com/yschimke/compose-ai-tools/commit/9c39a273469ed4e90498e26c0d316746e3a94e00))
* **renderer:** settle post-scroll animations before final LONG slice ([#110](https://github.com/yschimke/compose-ai-tools/issues/110)) ([5439cb0](https://github.com/yschimke/compose-ai-tools/commit/5439cb037f1d9fdcc992579d3fd5cbc132027bb4))

## [0.6.2](https://github.com/yschimke/compose-ai-tools/compare/v0.6.1...v0.6.2) (2026-04-19)


### Bug Fixes

* **vscode:** detect JRE-without-jlink and suggest a real JDK ([#101](https://github.com/yschimke/compose-ai-tools/issues/101)) ([4f2b5ae](https://github.com/yschimke/compose-ai-tools/commit/4f2b5aee7ac61e81a0434a89f1f0b366db7899c6))

## [0.6.1](https://github.com/yschimke/compose-ai-tools/compare/v0.6.0...v0.6.1) (2026-04-18)


### Bug Fixes

* **renderer:** stitch LONG scroll slices by pixel content, not reported offsets ([#100](https://github.com/yschimke/compose-ai-tools/issues/100)) ([040f433](https://github.com/yschimke/compose-ai-tools/commit/040f4335d609fea095b1fa736c9b321013b61bb6))
* **vscode:** stop webview going blank after successful refresh ([#97](https://github.com/yschimke/compose-ai-tools/issues/97)) ([6400226](https://github.com/yschimke/compose-ai-tools/commit/64002262009512edcf1cf26a11cf2965b7a203fa))

## [0.6.0](https://github.com/yschimke/compose-ai-tools/compare/v0.5.0...v0.6.0) (2026-04-18)


### Features

* **doctor:** print concise findings from composePreviewDoctor task ([#92](https://github.com/yschimke/compose-ai-tools/issues/92)) ([79ac91d](https://github.com/yschimke/compose-ai-tools/commit/79ac91d3559ba86afc09f9fb37ecd0734ec4ad04))
* **plugin:** make Wear preview rendering robust for real-world consumers ([#94](https://github.com/yschimke/compose-ai-tools/issues/94)) ([ec36863](https://github.com/yschimke/compose-ai-tools/commit/ec3686339332ae0e1a76e632ff2ee6fb3138c538))

## [0.5.0](https://github.com/yschimke/compose-ai-tools/compare/v0.4.0...v0.5.0) (2026-04-18)


### Features

* **a11y:** add opt-in ATF accessibility checks for previews ([#58](https://github.com/yschimke/compose-ai-tools/issues/58)) ([1271d3b](https://github.com/yschimke/compose-ai-tools/commit/1271d3b6bbc25fb299b21c8c1d3f13216ed8fcc3))
* **a11y:** annotated screenshot overlay + interactive VSCode legend ([#63](https://github.com/yschimke/compose-ai-tools/issues/63)) ([5b525fa](https://github.com/yschimke/compose-ai-tools/commit/5b525fa7e90f1808d0214dd3fb155572ab63787e))
* **capture:** unify animation / scroll variants as Preview.captures ([#72](https://github.com/yschimke/compose-ai-tools/issues/72)) ([876828f](https://github.com/yschimke/compose-ai-tools/commit/876828f4c4e227eadf365ec137dd4ae99889887f))
* **cli:** surface multi-capture, brief JSON, --changed-only ([#76](https://github.com/yschimke/compose-ai-tools/issues/76)) ([c887519](https://github.com/yschimke/compose-ai-tools/commit/c887519253d5497fb83d9c27c179a217e748a79b))
* **devices:** per-device density + 60-device coverage from ComposablePreviewScanner ([#70](https://github.com/yschimke/compose-ai-tools/issues/70)) ([bf2dc94](https://github.com/yschimke/compose-ai-tools/commit/bf2dc944bc210f94fee1e0156ce929ad547bd3ea))
* **doctor:** surface AndroidX compat mismatches in CLI and VS Code ([#66](https://github.com/yschimke/compose-ai-tools/issues/66)) ([8397a06](https://github.com/yschimke/compose-ai-tools/commit/8397a06ae5d93366f4364fdfe37ab24609036fcd))
* **doctor:** warn when tracked AndroidX libs are behind head ([#85](https://github.com/yschimke/compose-ai-tools/issues/85)) ([2db6faa](https://github.com/yschimke/compose-ai-tools/commit/2db6faa2b91a37431ad4b3e288149eb673516ce9))
* **renderer:** deterministic frame budget via paused mainClock ([#62](https://github.com/yschimke/compose-ai-tools/issues/62)) ([ffd4f19](https://github.com/yschimke/compose-ai-tools/commit/ffd4f19d1712f25903dedc5e77129cd74b5537ca))
* **renderer:** honour reduceMotion for @ScrollingPreview LONG captures ([#88](https://github.com/yschimke/compose-ai-tools/issues/88)) ([6a1c366](https://github.com/yschimke/compose-ai-tools/commit/6a1c366be9cdb6fa779f6122db71787bfc167838))
* **renderer:** mirror Compose's LocalScrollCaptureInProgress for scroll captures ([#86](https://github.com/yschimke/compose-ai-tools/issues/86)) ([2419d44](https://github.com/yschimke/compose-ai-tools/commit/2419d449d8067d61536bedb2fd0b6cf349936f06))
* **renderer:** per-preview clock control via @RoboComposePreviewOptions ([#67](https://github.com/yschimke/compose-ai-tools/issues/67)) ([0d659bf](https://github.com/yschimke/compose-ai-tools/commit/0d659bf3f9b2ddd2eaf4718decbfecc5fd8c469e))
* **renderer:** tile previews get black bg + auto round crop ([#68](https://github.com/yschimke/compose-ai-tools/issues/68)) ([465e3ca](https://github.com/yschimke/compose-ai-tools/commit/465e3cac50875068f5d1dbea6dfb8e50d0ef5c3e))
* **renderer:** wrap @Preview to intrinsic size, Android-Studio style ([#74](https://github.com/yschimke/compose-ai-tools/issues/74)) ([c6a4aa0](https://github.com/yschimke/compose-ai-tools/commit/c6a4aa06316ca2efe89563ecc7f5742fcfa40038))
* **scroll:** @ScrollingPreview annotation with scroll-to-end capture ([#69](https://github.com/yschimke/compose-ai-tools/issues/69)) ([a9baed6](https://github.com/yschimke/compose-ai-tools/commit/a9baed69279d804d61445c20ded4daf6924cd460))
* **scroll:** stitched capture for @ScrollingPreview(mode = LONG) ([#78](https://github.com/yschimke/compose-ai-tools/issues/78)) ([fea2360](https://github.com/yschimke/compose-ai-tools/commit/fea2360c010dd856250f862104fe3bf6ded95cbe))


### Bug Fixes

* **actions:** handle versioned envelope from compose-preview show --json ([#80](https://github.com/yschimke/compose-ai-tools/issues/80)) ([c754121](https://github.com/yschimke/compose-ai-tools/commit/c75412134188a10b36ec73f9dacd90c404a75eba))
* **discovery:** pin DEFAULT_DENSITY on wrap-content previews ([#75](https://github.com/yschimke/compose-ai-tools/issues/75)) ([5bc2aaf](https://github.com/yschimke/compose-ai-tools/commit/5bc2aafe74613fdb69c3decb9ae6078006a1bb38))
* **plugin:** silence VFS watcher warning and make doctor task config-cache safe ([#87](https://github.com/yschimke/compose-ai-tools/issues/87)) ([1169b47](https://github.com/yschimke/compose-ai-tools/commit/1169b47087899f53cea60e8b926f026376e32e79))
* **renderer:** don't poison consumer classpath with newer AndroidX ([#60](https://github.com/yschimke/compose-ai-tools/issues/60)) ([e2de3b8](https://github.com/yschimke/compose-ai-tools/commit/e2de3b84a1e098dc1ce54750c7b9cb12ba8d455f))
* **vscode:** stop the jumpy preview render loop ([#64](https://github.com/yschimke/compose-ai-tools/issues/64)) ([0a09165](https://github.com/yschimke/compose-ai-tools/commit/0a09165e1829192e6e0048d48c1205939ebdba8f))
* **vscode:** suppress plugin-not-applied nudge for worktree files ([#71](https://github.com/yschimke/compose-ai-tools/issues/71)) ([ea7f5d5](https://github.com/yschimke/compose-ai-tools/commit/ea7f5d5f340cafc53ba8f83312c82c3bc9bfaadd))


### Reverts

* re-enable Roborazzi ActionBar workaround ([#79](https://github.com/yschimke/compose-ai-tools/issues/79)) ([#82](https://github.com/yschimke/compose-ai-tools/issues/82)) ([588b34e](https://github.com/yschimke/compose-ai-tools/commit/588b34ecb9094f11b1bc566babc2e6863a47ccfc))

## [0.4.0](https://github.com/yschimke/compose-ai-tools/compare/v0.3.5...v0.4.0) (2026-04-17)


### Features

* **vscode:** warn when Gradle plugin isn't applied ([#55](https://github.com/yschimke/compose-ai-tools/issues/55)) ([e558893](https://github.com/yschimke/compose-ai-tools/commit/e55889396ed72737f5cdfad8fbc1d58e63d9b415))

## [0.3.5](https://github.com/yschimke/compose-ai-tools/compare/v0.3.4...v0.3.5) (2026-04-17)


### Bug Fixes

* **plugin:** declare renders dir as output of Android renderPreviews ([#46](https://github.com/yschimke/compose-ai-tools/issues/46)) ([2dce507](https://github.com/yschimke/compose-ai-tools/commit/2dce507fef893c7b33816094c94bceb13dbd6614))
* surface and fail loudly when preview renders don't land ([#42](https://github.com/yschimke/compose-ai-tools/issues/42)) ([244ac55](https://github.com/yschimke/compose-ai-tools/commit/244ac55744a56e205ee51667d2c3fe3094c3744b))
