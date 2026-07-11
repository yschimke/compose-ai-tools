# Changelog

## [0.16.36](https://github.com/yschimke/compose-ai-tools/compare/v0.16.35...v0.16.36) (2026-07-11)


### Features

* **serve:** consume per-preview bundles as the default live lane ([#2389](https://github.com/yschimke/compose-ai-tools/issues/2389)) ([4aa479f](https://github.com/yschimke/compose-ai-tools/commit/4aa479fe8f8f2a3c979710736226115d77e56735))


### Bug Fixes

* **figma-svg:** mask a round Wear device screen to its inscribed circle ([#2391](https://github.com/yschimke/compose-ai-tools/issues/2391)) ([892d09f](https://github.com/yschimke/compose-ai-tools/commit/892d09fff973c389d77eed9fed445d8cec1a7793))
* **serve:** apply named-knob overrides on the un-enabled preview-server daemon ([#2392](https://github.com/yschimke/compose-ai-tools/issues/2392)) ([fc13bb4](https://github.com/yschimke/compose-ai-tools/commit/fc13bb4f626054d6836591abf8cb3764fadf070f))

## [0.16.35](https://github.com/yschimke/compose-ai-tools/compare/v0.16.34...v0.16.35) (2026-07-10)


### Features

* **design-artifacts:** pin published previews to released preview-runtimes ([#2384](https://github.com/yschimke/compose-ai-tools/issues/2384)) ([da1b58a](https://github.com/yschimke/compose-ai-tools/commit/da1b58a15e53252cd9092a2a5cfd252fe1fbf2ce))
* **serve:** per-preview live-bundle host + FULL split publish (foundation) ([#2376](https://github.com/yschimke/compose-ai-tools/issues/2376)) ([534854a](https://github.com/yschimke/compose-ai-tools/commit/534854a52403f909d0500b8127628dc46f09ebe9))


### Bug Fixes

* **figma-svg:** grow container fills to their measured size, except touch-target-inflated nodes ([#2375](https://github.com/yschimke/compose-ai-tools/issues/2375)) ([cc77d2f](https://github.com/yschimke/compose-ai-tools/commit/cc77d2f8c61d4f7e4794204486c6bdac5b1a67ce))
* **figma-svg:** raster a container fill whose painter isn't a plain ColorPainter ([#2385](https://github.com/yschimke/compose-ai-tools/issues/2385)) ([ff17ac5](https://github.com/yschimke/compose-ai-tools/commit/ff17ac5ac5d79278304ed1017bc7f4ece5f050e8))
* **serve:** expose per-preview bakedHost + split the externalised bundle FULL ([#2378](https://github.com/yschimke/compose-ai-tools/issues/2378)) ([072455f](https://github.com/yschimke/compose-ai-tools/commit/072455fef7bb7d6b8756650e5f5c467314845421))

## [0.16.34](https://github.com/yschimke/compose-ai-tools/compare/v0.16.33...v0.16.34) (2026-07-10)


### Features

* **serve:** Android-gated gesture-hints control in the viewer ([#2368](https://github.com/yschimke/compose-ai-tools/issues/2368)) ([b81322e](https://github.com/yschimke/compose-ai-tools/commit/b81322e195204ab3d0c334593381e631c0260ec6))


### Bug Fixes

* **daemon:** don't wrap a preview whose params are unknown (null block) ([#2370](https://github.com/yschimke/compose-ai-tools/issues/2370)) ([355350f](https://github.com/yschimke/compose-ai-tools/commit/355350fc09d877585755a1367f75be0ce9de3bc5))
* **daemon:** preserve prior @Preview params across incremental diffs ([#2371](https://github.com/yschimke/compose-ai-tools/issues/2371)) ([3cf3b2b](https://github.com/yschimke/compose-ai-tools/commit/3cf3b2b20eb9ca2ae280ba944157811e4a68b5bb))
* **daemon:** wrap-content parity for the interactive/stream render lane ([#2369](https://github.com/yschimke/compose-ai-tools/issues/2369)) ([52c12b6](https://github.com/yschimke/compose-ai-tools/commit/52c12b64d9b7a8140ade660880711d37cfb0ec0d))
* **figma-svg:** honor Modifier.paint alpha and colorFilter in fill tokens ([#2348](https://github.com/yschimke/compose-ai-tools/issues/2348)) ([ec14712](https://github.com/yschimke/compose-ai-tools/commit/ec147124b1662ecdafb04ff95817e4f0e658374d))
* **figma-svg:** resolve wear M3 painter-based container fills ([#2345](https://github.com/yschimke/compose-ai-tools/issues/2345)) ([6438f80](https://github.com/yschimke/compose-ai-tools/commit/6438f80a626444d6be8fcb6bbc87f16c681f19b9))
* **serve:** letterbox the live canvas instead of stretching it to the snapshot box ([#2372](https://github.com/yschimke/compose-ai-tools/issues/2372)) ([bb25a30](https://github.com/yschimke/compose-ai-tools/commit/bb25a307ae3cddf81ad60c113b061fd0dd76b4e8))
* **wear:** freeze GestureGalleryPreview clock with FixedPreviewTimeSource ([#2347](https://github.com/yschimke/compose-ai-tools/issues/2347)) ([73e8ece](https://github.com/yschimke/compose-ai-tools/commit/73e8ece07818a96a2f647f93ca11f6fbeed16c13))
* **wear:** render device-less wear previews at wear density, not the phone default ([#2373](https://github.com/yschimke/compose-ai-tools/issues/2373)) ([129085b](https://github.com/yschimke/compose-ai-tools/commit/129085bf0b573ae3a69aa99d3c08317eafd1fe4c))

## [0.16.33](https://github.com/yschimke/compose-ai-tools/compare/v0.16.32...v0.16.33) (2026-07-10)


### Features

* **bundle:** pack each preview as its own valid bundle (--per-preview) ([#2340](https://github.com/yschimke/compose-ai-tools/issues/2340)) ([991b012](https://github.com/yschimke/compose-ai-tools/commit/991b0122780dc4c7cbfe79242385c6aacf75219f))
* **bundle:** publish per-preview bundles from the catalog sheet (bundle split) ([#2342](https://github.com/yschimke/compose-ai-tools/issues/2342)) ([2c22954](https://github.com/yschimke/compose-ai-tools/commit/2c2295416d1898a1c6d55963924b291819d5fb0e))
* **serve:** add an SVG render mode to the viewer's mode toggle ([#2338](https://github.com/yschimke/compose-ai-tools/issues/2338)) ([0cb853a](https://github.com/yschimke/compose-ai-tools/commit/0cb853adcd45d9da303b81571f08ab5d05a7b12d))
* **serve:** carry declared @ThemeCatalog themes into bundle & catalog hosts ([#2343](https://github.com/yschimke/compose-ai-tools/issues/2343)) ([9d96fce](https://github.com/yschimke/compose-ai-tools/commit/9d96fcefeeda7dca986efd032cb3463b92ad528f))
* **serve:** detected-feature controls in the viewer (keyboard focus) ([#2344](https://github.com/yschimke/compose-ai-tools/issues/2344)) ([ac27de6](https://github.com/yschimke/compose-ai-tools/commit/ac27de6082bb549a8dec17d5256490714776d826))
* **serve:** render previews under app-declared @ThemeCatalog themes ([#2341](https://github.com/yschimke/compose-ai-tools/issues/2341)) ([6fc4c6a](https://github.com/yschimke/compose-ai-tools/commit/6fc4c6aa68a41c27d8edd3434934bef2e98e25d0))


### Bug Fixes

* **figma-svg:** capture border width; drop fully-transparent borders ([#2335](https://github.com/yschimke/compose-ai-tools/issues/2335)) ([f724137](https://github.com/yschimke/compose-ai-tools/commit/f72413710518476977f2c132621d601269fd7c68))
* **figma-svg:** draw a min-size-expanded circular badge at its measured size ([#2339](https://github.com/yschimke/compose-ai-tools/issues/2339)) ([f4f1483](https://github.com/yschimke/compose-ai-tools/commit/f4f14839902d3218c738f444851bde9d326d9291))

## [0.16.32](https://github.com/yschimke/compose-ai-tools/compare/v0.16.31...v0.16.32) (2026-07-10)


### Features

* **figma-svg:** close PNG↔SVG parity gap on the compare page ([#2327](https://github.com/yschimke/compose-ai-tools/issues/2327)) ([9a95c5c](https://github.com/yschimke/compose-ai-tools/commit/9a95c5c4123f8d0afcd83adb1207ab05ee67771f))
* **figma-svg:** emit a drop shadow for elevated surfaces ([#2333](https://github.com/yschimke/compose-ai-tools/issues/2333)) ([f1863a8](https://github.com/yschimke/compose-ai-tools/commit/f1863a89211432c0ef8066a6f56ad11357786e42))
* **gestures:** full-screen two-gesture hint demo ([#2323](https://github.com/yschimke/compose-ai-tools/issues/2323)) ([dc19643](https://github.com/yschimke/compose-ai-tools/commit/dc1964382ff5e518822107e97e6e23dd7a2591a2))
* **serve:** live-only overlay toggles (TalkBack / touch) in the viewer ([#2332](https://github.com/yschimke/compose-ai-tools/issues/2332)) ([0d96762](https://github.com/yschimke/compose-ai-tools/commit/0d96762616e6c3c2c63ff15050bae112a607870e))
* **serve:** render-mode radio group (PNG / Live Compose / Wasm) ([#2328](https://github.com/yschimke/compose-ai-tools/issues/2328)) ([7b9ad15](https://github.com/yschimke/compose-ai-tools/commit/7b9ad15c25b3dfc97081b4dc37dcebf97883f082))


### Bug Fixes

* **design-artifacts:** load compare-page images cross-origin so scores work on htmlpreview ([#2324](https://github.com/yschimke/compose-ai-tools/issues/2324)) ([2be3e1c](https://github.com/yschimke/compose-ai-tools/commit/2be3e1cff0593017d39cb3481dbc0df8e8012784))
* **renderer:** apply @Preview(uiMode) dark to the composition, not just chrome ([#2330](https://github.com/yschimke/compose-ai-tools/issues/2330)) ([a21d469](https://github.com/yschimke/compose-ai-tools/commit/a21d469b0be2d6495cb84af381ae6bad4f5e2b1b))
* **samples:** ship Compose resources in the Wasm catalog dist ([#2326](https://github.com/yschimke/compose-ai-tools/issues/2326)) ([a278af8](https://github.com/yschimke/compose-ai-tools/commit/a278af8e5bb30e6af7c3e2397bf6a47313c76363))
* **serve:** honor display-axis overrides on the published catalog ([#2325](https://github.com/yschimke/compose-ai-tools/issues/2325)) ([8550400](https://github.com/yschimke/compose-ai-tools/commit/85504004dd699be4aef45b9733f2f426aab33785))
* **serve:** pin the live canvas to the snapshot box so modes don't resize ([#2331](https://github.com/yschimke/compose-ai-tools/issues/2331)) ([9af1309](https://github.com/yschimke/compose-ai-tools/commit/9af13095cf368d8dc2c6b6eb05e226a1ca1e8edd))
* **serve:** seed the daemon with current overrides when the live socket opens ([#2334](https://github.com/yschimke/compose-ai-tools/issues/2334)) ([86ebdca](https://github.com/yschimke/compose-ai-tools/commit/86ebdcaf7f180c4ba0c909e9681aa8b7c193ca3c))

## [0.16.31](https://github.com/yschimke/compose-ai-tools/compare/v0.16.30...v0.16.31) (2026-07-10)


### Features

* **gestures:** @GestureHintPreview — force-show the Wear gesture hint from outside the screen ([#2318](https://github.com/yschimke/compose-ai-tools/issues/2318)) ([622a11d](https://github.com/yschimke/compose-ai-tools/commit/622a11dc40d17ccc164c50dcb430762acc55eb35))


### Bug Fixes

* **figma-svg:** capture previews at natural size, embed real faces + tracking ([#2321](https://github.com/yschimke/compose-ai-tools/issues/2321)) ([5322578](https://github.com/yschimke/compose-ai-tools/commit/5322578c3e161720a51de6bd4d0c20279426d623))

## [0.16.30](https://github.com/yschimke/compose-ai-tools/compare/v0.16.29...v0.16.30) (2026-07-10)


### Features

* **design-artifacts:** sort compare page worst-first + inline hybrid rasters ([#2316](https://github.com/yschimke/compose-ai-tools/issues/2316)) ([4e27208](https://github.com/yschimke/compose-ai-tools/commit/4e2720826c740cf39a5c9d07f34abc59e0d20dd8))
* **samples:** Wear one-handed gesture gallery + compose/gestures data product ([#2313](https://github.com/yschimke/compose-ai-tools/issues/2313)) ([8463762](https://github.com/yschimke/compose-ai-tools/commit/8463762f85988a07340eff176639cbdbcb8c7f15))


### Bug Fixes

* **design-artifacts:** align figma-svg padding out before SSIM scoring ([#2315](https://github.com/yschimke/compose-ai-tools/issues/2315)) ([c18494f](https://github.com/yschimke/compose-ai-tools/commit/c18494f7f2d268959b70729520f17d96568b2370))
* **serve:** grant the Wasm iframe same-origin to stop SecurityErrors ([#2319](https://github.com/yschimke/compose-ai-tools/issues/2319)) ([a752400](https://github.com/yschimke/compose-ai-tools/commit/a75240049a1cf4735db2b5e37d9095fd9ac1d196))

## [0.16.29](https://github.com/yschimke/compose-ai-tools/compare/v0.16.28...v0.16.29) (2026-07-09)


### Features

* **design-artifacts:** add PNG-vs-SVG compare page with live SSIM score ([#2312](https://github.com/yschimke/compose-ai-tools/issues/2312)) ([7468587](https://github.com/yschimke/compose-ai-tools/commit/74685878389c958a8fb0bf4ff058a319a95eb92f))


### Bug Fixes

* **serve:** serve baked snapshot on load, drop knob type prefix ([#2310](https://github.com/yschimke/compose-ai-tools/issues/2310)) ([70f4274](https://github.com/yschimke/compose-ai-tools/commit/70f427465ad10ca9b9e2116c491886ab98067045))

## [0.16.28](https://github.com/yschimke/compose-ai-tools/compare/v0.16.27...v0.16.28) (2026-07-08)


### Features

* **figma-svg:** subset embedded fonts to the glyphs the SVG draws ([#2300](https://github.com/yschimke/compose-ai-tools/issues/2300)) ([c92a3c1](https://github.com/yschimke/compose-ai-tools/commit/c92a3c191f335161fadd35d09278f04f266b4dc6))
* **samples:** back m3 + wear-m3 catalog labels with translatable string resources ([#2308](https://github.com/yschimke/compose-ai-tools/issues/2308)) ([51b3c11](https://github.com/yschimke/compose-ai-tools/commit/51b3c11f6e8a23cffedf5ed8204011397bf27c8b))
* **wear:** add image tile previews (inline + drawable resource) ([#2301](https://github.com/yschimke/compose-ai-tools/issues/2301)) ([fbe8402](https://github.com/yschimke/compose-ai-tools/commit/fbe84027e7c7474a69eef05a88395d9e2cf04cdc))


### Bug Fixes

* **codegen:** emit ktfmt-stable Kotlin from the SpatialScene generator ([#2307](https://github.com/yschimke/compose-ai-tools/issues/2307)) ([eb0c5ab](https://github.com/yschimke/compose-ai-tools/commit/eb0c5ab092191407a5513fe83a0b0872c54b3746))
* **serve:** share previewOverride runtime across the live-daemon classloader split ([#2297](https://github.com/yschimke/compose-ai-tools/issues/2297)) ([33acdb2](https://github.com/yschimke/compose-ai-tools/commit/33acdb2079e5f06f4963041520bb4b05d1c11e1b))
* **tiles:** render tile images registered via ProtoLayoutScope ([#2305](https://github.com/yschimke/compose-ai-tools/issues/2305)) ([a1244b0](https://github.com/yschimke/compose-ai-tools/commit/a1244b050805b030819ff0d8788dcc1251b25f85))
* **vscode:** compose per-preview render overrides into one bag ([#2298](https://github.com/yschimke/compose-ai-tools/issues/2298)) ([3f55ba5](https://github.com/yschimke/compose-ai-tools/commit/3f55ba5a7c2e866a964cf2a1917464cc6091e8c2))

## [0.16.27](https://github.com/yschimke/compose-ai-tools/compare/v0.16.26...v0.16.27) (2026-07-08)


### Features

* add overrideable clearBackground ("crisp outline") preview toggle ([#2284](https://github.com/yschimke/compose-ai-tools/issues/2284)) ([dc8469e](https://github.com/yschimke/compose-ai-tools/commit/dc8469edcba9b6497d3362ee9aea3f0fd672cf92))
* **serve:** render any fetched preview bundle live without a module ([#2289](https://github.com/yschimke/compose-ai-tools/issues/2289)) ([3325203](https://github.com/yschimke/compose-ai-tools/commit/3325203e122674ca1b8931ec210225321f20944b))
* **theme-catalog:** emit per-theme resolved token sidecar ([#2288](https://github.com/yschimke/compose-ai-tools/issues/2288)) ([0aab11b](https://github.com/yschimke/compose-ai-tools/commit/0aab11b45346461014ba80c92a8fc208d1f27451))
* **vscode:** add clear-background (crisp outline) focus-toolbar toggle ([#2293](https://github.com/yschimke/compose-ai-tools/issues/2293)) ([15ae0a5](https://github.com/yschimke/compose-ai-tools/commit/15ae0a5857029bb56f7b841c02bab5d7f04cf421))


### Bug Fixes

* **deps:** update dependency dev.mobile:dadb to v2 ([#2286](https://github.com/yschimke/compose-ai-tools/issues/2286)) ([7690bc8](https://github.com/yschimke/compose-ai-tools/commit/7690bc87c7e56701d65e73cb8ac369f5187a62e7))
* **deps:** update gradle minor/patch ([#2285](https://github.com/yschimke/compose-ai-tools/issues/2285)) ([3dfe194](https://github.com/yschimke/compose-ai-tools/commit/3dfe194abbb93b9983dc4bed906e0f0bc70b7285))
* **figma-svg:** faithful text typeface, rastered Material chrome, density strokes ([#2292](https://github.com/yschimke/compose-ai-tools/issues/2292)) ([6baeadb](https://github.com/yschimke/compose-ai-tools/commit/6baeadb8fe1aedc82b2be61be6b6df39296ea438))
* **figma-svg:** wrap multi-line text where the render wrapped it ([#2295](https://github.com/yschimke/compose-ai-tools/issues/2295)) ([9dc8bc7](https://github.com/yschimke/compose-ai-tools/commit/9dc8bc741b2f23b198a899094705cc8326c06b7d))
* **serve:** surface catalog knobs + copyable PNG/SVG override URLs ([#2287](https://github.com/yschimke/compose-ai-tools/issues/2287)) ([54c2bd3](https://github.com/yschimke/compose-ai-tools/commit/54c2bd397ae67f6b62143b67322d1c295ae23c5b))

## [0.16.26](https://github.com/yschimke/compose-ai-tools/compare/v0.16.25...v0.16.26) (2026-07-06)


### Features

* **design-catalog:** apply preview overrides + Figma slot placeholders across the M3 and Wear M3 catalogs ([#2281](https://github.com/yschimke/compose-ai-tools/issues/2281)) ([c07a446](https://github.com/yschimke/compose-ai-tools/commit/c07a446387ff44d26b766186beecdc5a620b26d3))


### Bug Fixes

* **samples:** build design-catalog-m3-android at compileSdk 37 for material3 alpha22 ([#2275](https://github.com/yschimke/compose-ai-tools/issues/2275)) ([3f1e13e](https://github.com/yschimke/compose-ai-tools/commit/3f1e13ebca708216af46e0f09ea84492afd2bdd5))
* **serve:** stop the preview jumping when Live mode is toggled ([#2274](https://github.com/yschimke/compose-ai-tools/issues/2274)) ([d50974d](https://github.com/yschimke/compose-ai-tools/commit/d50974db95be6f92104465cb58dfe959e823676b))

## [0.16.25](https://github.com/yschimke/compose-ai-tools/compare/v0.16.24...v0.16.25) (2026-07-06)


### Features

* **bundle:** externalize fonts from the bundle, rehydrate from a server cache ([#2272](https://github.com/yschimke/compose-ai-tools/issues/2272)) ([c2838f4](https://github.com/yschimke/compose-ai-tools/commit/c2838f4c7bce06fadc284caee459812f221293c7))


### Bug Fixes

* **deps:** update dependency androidx.compose.material3:material3 to v1.5.0-alpha22 ([#2270](https://github.com/yschimke/compose-ai-tools/issues/2270)) ([dc9248f](https://github.com/yschimke/compose-ai-tools/commit/dc9248f5d84aa5f71e55f634b8ecbc91546d7ee7))

## [0.16.24](https://github.com/yschimke/compose-ai-tools/compare/v0.16.23...v0.16.24) (2026-07-06)


### Bug Fixes

* **bundle:** pack module runtime resources so a bundle can re-render live ([#2269](https://github.com/yschimke/compose-ai-tools/issues/2269)) ([386a3f9](https://github.com/yschimke/compose-ai-tools/commit/386a3f94cfb421ad9edb8a6d422816de015b15dc))
* **deps:** update gradle minor/patch ([#2266](https://github.com/yschimke/compose-ai-tools/issues/2266)) ([faab5b9](https://github.com/yschimke/compose-ai-tools/commit/faab5b9061e1b433b9a5fbf57bfd09c11ccc4fe7))
* **deps:** update npm minor/patch ([#2268](https://github.com/yschimke/compose-ai-tools/issues/2268)) ([c07a255](https://github.com/yschimke/compose-ai-tools/commit/c07a255126caa9064bb4ba08bddcc8fa4f2600ab))

## [0.16.23](https://github.com/yschimke/compose-ai-tools/compare/v0.16.22...v0.16.23) (2026-07-06)


### Bug Fixes

* **serve:** keep a live catalog static-snapshot + trusted, live on demand ([#2263](https://github.com/yschimke/compose-ai-tools/issues/2263)) ([8984252](https://github.com/yschimke/compose-ai-tools/commit/8984252ae789f18fc11ef682ac8b520adcddbc77))
* **slots:** autosize the slot placeholder label to fit its box ([#2261](https://github.com/yschimke/compose-ai-tools/issues/2261)) ([b877e3f](https://github.com/yschimke/compose-ai-tools/commit/b877e3f87dade5ad404c75b4b896254344eecb6f))

## [0.16.22](https://github.com/yschimke/compose-ai-tools/compare/v0.16.21...v0.16.22) (2026-07-05)


### Features

* **catalog:** add a slotted M3 Card demonstrating PreviewSlot ([#2256](https://github.com/yschimke/compose-ai-tools/issues/2256)) ([d4c807d](https://github.com/yschimke/compose-ai-tools/commit/d4c807d93fd53b854ccc204cd616caba7a188764))
* **design-catalog:** declare the icon+label content variant + fold props in regen ([#2257](https://github.com/yschimke/compose-ai-tools/issues/2257)) ([938329a](https://github.com/yschimke/compose-ai-tools/commit/938329aea1696d175c5a29bc0de7d555b81f6e16))

## [0.16.21](https://github.com/yschimke/compose-ai-tools/compare/v0.16.20...v0.16.21) (2026-07-05)


### Features

* **design-catalog:** render an icon+label FilledButton (content-axis groundwork) ([#2252](https://github.com/yschimke/compose-ai-tools/issues/2252)) ([2b4b44b](https://github.com/yschimke/compose-ai-tools/commit/2b4b44bd5bf8ed0b7fbb6fc332f25247c89c4994))
* **serve:** add the slotMode render override (desktop path) ([#2254](https://github.com/yschimke/compose-ai-tools/issues/2254)) ([9efd517](https://github.com/yschimke/compose-ai-tools/commit/9efd5175a2580f4f228e42d590707b1536628329))
* **slots:** add slot-preview-runtime with the PreviewSlot marker ([#2251](https://github.com/yschimke/compose-ai-tools/issues/2251)) ([a6c7d1d](https://github.com/yschimke/compose-ai-tools/commit/a6c7d1d472416e0a4c5536818589a8db10c212fa))

## [0.16.20](https://github.com/yschimke/compose-ai-tools/compare/v0.16.19...v0.16.20) (2026-07-05)


### Features

* extract and serve named preview slots ([#2248](https://github.com/yschimke/compose-ai-tools/issues/2248)) ([182ab29](https://github.com/yschimke/compose-ai-tools/commit/182ab29f10149b9acaca43b3445ef050bd3c7c82))
* **serve:** live-render a trusted catalog from its carried bundle, no build ([#2246](https://github.com/yschimke/compose-ai-tools/issues/2246)) ([111f3cb](https://github.com/yschimke/compose-ai-tools/commit/111f3cbff4377ff8218b0895d129b468674fd74b))


### Bug Fixes

* **serve:** bridge catalog ids to the live daemon so deep links keep resolving ([#2249](https://github.com/yschimke/compose-ai-tools/issues/2249)) ([ce55207](https://github.com/yschimke/compose-ai-tools/commit/ce55207fd28f7a10b3eb789b26128c2d0784c446))

## [0.16.19](https://github.com/yschimke/compose-ai-tools/compare/v0.16.18...v0.16.19) (2026-07-05)


### Features

* **serve:** --catalog-source-root so deploy/image can live-render compose-m3 ([#2242](https://github.com/yschimke/compose-ai-tools/issues/2242)) ([7182d71](https://github.com/yschimke/compose-ai-tools/commit/7182d71792cbc6d7423b75f02096962ac8ea10cb))
* **serve:** serve a catalog's baked figma-svg via /render/&lt;id&gt;.svg ([#2244](https://github.com/yschimke/compose-ai-tools/issues/2244)) ([0ef17b0](https://github.com/yschimke/compose-ai-tools/commit/0ef17b0481af055be3da8eae65692bbc71876f63))


### Bug Fixes

* **design-catalog:** inline @Preview on the Android focus-ring supplement ([#2243](https://github.com/yschimke/compose-ai-tools/issues/2243)) ([670cfe4](https://github.com/yschimke/compose-ai-tools/commit/670cfe4527d94276d9b5fac9bde7ba79a7ca72cb))

## [0.16.18](https://github.com/yschimke/compose-ai-tools/compare/v0.16.17...v0.16.18) (2026-07-05)


### Features

* **design-catalog:** make compose-m3 a CMP catalog + enable live daemon render ([#2239](https://github.com/yschimke/compose-ai-tools/issues/2239)) ([8697aa0](https://github.com/yschimke/compose-ai-tools/commit/8697aa0307d8536b0a76bbce774bacce4f6c34f9))
* **serve:** serve figma-svg per preview via /render/&lt;id&gt;.svg ([#2240](https://github.com/yschimke/compose-ai-tools/issues/2240)) ([57616a5](https://github.com/yschimke/compose-ai-tools/commit/57616a5d56ab72d6e6cc81913ac9a95ba1f572b5))


### Bug Fixes

* **figma-svg:** keep serif/monospace text families instead of collapsing to sans ([#2238](https://github.com/yschimke/compose-ai-tools/issues/2238)) ([5185307](https://github.com/yschimke/compose-ai-tools/commit/51853077f5335d9a746469491236d26c86bff5ad))

## [0.16.17](https://github.com/yschimke/compose-ai-tools/compare/v0.16.16...v0.16.17) (2026-07-05)


### Features

* **catalog:** add scaffold templates to the M3 and Wear M3 catalogs ([#2233](https://github.com/yschimke/compose-ai-tools/issues/2233)) ([4d01f07](https://github.com/yschimke/compose-ai-tools/commit/4d01f07a495b29f1464482d692cd3ac246407f04))
* **design-catalog:** fold states into a single-component view; add M3 keyboard-focus ring ([#2236](https://github.com/yschimke/compose-ai-tools/issues/2236)) ([1c86c47](https://github.com/yschimke/compose-ai-tools/commit/1c86c47546111657207a5b851e01189b22b247fc))
* model CutCornerShape (chamfer) in the figma-svg export ([#2229](https://github.com/yschimke/compose-ai-tools/issues/2229)) ([8f9340f](https://github.com/yschimke/compose-ai-tools/commit/8f9340f8d63a46f8b5de93bd58a64db03bc72432))
* **serve:** link snapshot note to local preview server instructions ([#2234](https://github.com/yschimke/compose-ai-tools/issues/2234)) ([9a5630f](https://github.com/yschimke/compose-ai-tools/commit/9a5630fe032d9648ceb7b4791d638f5920142601))


### Bug Fixes

* **design-artifacts:** embed the measured Roboto face in published figma-svg exports ([#2232](https://github.com/yschimke/compose-ai-tools/issues/2232)) ([3cdaa5b](https://github.com/yschimke/compose-ai-tools/commit/3cdaa5b0c1538788f7113d25da217477bd1fa6de))
* render the hero image in the design-catalog index (not "no render") ([#2231](https://github.com/yschimke/compose-ai-tools/issues/2231)) ([7c0c784](https://github.com/yschimke/compose-ai-tools/commit/7c0c784c1783ec1551453a20451ec942e77fb70e))
* **wasm-catalog:** remove iframe scrollbar and restore snapshot size parity ([#2235](https://github.com/yschimke/compose-ai-tools/issues/2235)) ([6a8cbd7](https://github.com/yschimke/compose-ai-tools/commit/6a8cbd751ae49a8185a1c9f19cc1621de3730956))

## [0.16.16](https://github.com/yschimke/compose-ai-tools/compare/v0.16.15...v0.16.16) (2026-07-05)


### Features

* add compose/figma-svg layered SVG export for Figma import ([#2203](https://github.com/yschimke/compose-ai-tools/issues/2203)) ([747e786](https://github.com/yschimke/compose-ai-tools/commit/747e786a6903ea1c4a85b42dc234c0a1e03bbdae))
* add SVG asset discovery and rendering support ([#2207](https://github.com/yschimke/compose-ai-tools/issues/2207)) ([d688082](https://github.com/yschimke/compose-ai-tools/commit/d68808206a625ecdb6142b8961c0334229f05546))
* carry figma-raster crops for hybrid figma-svg catalog stickers ([#2225](https://github.com/yschimke/compose-ai-tools/issues/2225)) ([1fe99cf](https://github.com/yschimke/compose-ai-tools/commit/1fe99cfc49e1ce6b7a41c9db9a151f66c0c8cc8c))
* carry raw-px corner radii (RoundedCornerShape(&lt;px&gt;f)) into figma-svg ([#2227](https://github.com/yschimke/compose-ai-tools/issues/2227)) ([96641dc](https://github.com/yschimke/compose-ai-tools/commit/96641dc6dbb56bbd6e6b94e26b6462dfbd8df9d9))
* **cli:** render-matrix --cells-dir per-cell output + coalesced-retry fix, Figma roundtrip docs ([#2202](https://github.com/yschimke/compose-ai-tools/issues/2202)) ([ce3bad8](https://github.com/yschimke/compose-ai-tools/commit/ce3bad87a27cc173f0ba5668886ea54002163e07))
* embed Google downloadable fonts in the Android figma-svg export ([#2219](https://github.com/yschimke/compose-ai-tools/issues/2219)) ([2df314f](https://github.com/yschimke/compose-ai-tools/commit/2df314f86268284ac42d4fde29d389827087f646))
* figma-svg embeds the actual font file the render loaded ([#2220](https://github.com/yschimke/compose-ai-tools/issues/2220)) ([0b237f8](https://github.com/yschimke/compose-ai-tools/commit/0b237f847e499dc6907d3d4175e7efae9d14413a))
* figma-svg fidelity harness (render vs SVG score + composite) ([#2212](https://github.com/yschimke/compose-ai-tools/issues/2212)) ([7fffad8](https://github.com/yschimke/compose-ai-tools/commit/7fffad8b98b3024ad28ecfd8ac36c8c28bec9f89))
* figma-svg text fidelity — typography baselines + embedded Google fonts ([#2218](https://github.com/yschimke/compose-ai-tools/issues/2218)) ([f6585fc](https://github.com/yschimke/compose-ai-tools/commit/f6585fc7f8b16eb256a87ea920849c09a1678385))
* font preview wrapper + reusable @PreviewWrapperClass multi-preview ([#2221](https://github.com/yschimke/compose-ai-tools/issues/2221)) ([9f15e39](https://github.com/yschimke/compose-ai-tools/commit/9f15e395bc09fba63471f7a01920a10d3f47507c))
* hybrid vector/PNG export for figma-svg (opaque components as &lt;image&gt;) ([#2204](https://github.com/yschimke/compose-ai-tools/issues/2204)) ([ae73989](https://github.com/yschimke/compose-ai-tools/commit/ae73989e91a971c1c4a5a6bc8e244db0946f443c))
* portable SVG bundles + SvgPreview authoring composable ([#2215](https://github.com/yschimke/compose-ai-tools/issues/2215)) ([5b45012](https://github.com/yschimke/compose-ai-tools/commit/5b45012cdb99f8a4dff44f526f1722b4e7453dbf))
* **serve:** expose per-preview override declarations on /api/previews ([#2228](https://github.com/yschimke/compose-ai-tools/issues/2228)) ([4ab78d9](https://github.com/yschimke/compose-ai-tools/commit/4ab78d9a658d31534dc52d8a1d86aea8739e9d1a))
* ship the editable compose/figma-svg per sticker in design-catalog bundles ([#2223](https://github.com/yschimke/compose-ai-tools/issues/2223)) ([50cebc2](https://github.com/yschimke/compose-ai-tools/commit/50cebc20bd3abab360ea894604c0d126f7cad3c3))
* turn on figma-svg hybrid raster export end-to-end ([#2206](https://github.com/yschimke/compose-ai-tools/issues/2206)) ([2c39508](https://github.com/yschimke/compose-ai-tools/commit/2c39508016ce6161b7e214d33b1db4e281f16954))


### Bug Fixes

* carry SVG/Lottie asset IR in Android bundles ([#2217](https://github.com/yschimke/compose-ai-tools/issues/2217)) ([3c83aa9](https://github.com/yschimke/compose-ai-tools/commit/3c83aa93885d5f40f53c8f2c32f056cda1a4aeec))
* correct review-comment tool name and handle missing follow-up label ([#2214](https://github.com/yschimke/compose-ai-tools/issues/2214)) ([62d8e16](https://github.com/yschimke/compose-ai-tools/commit/62d8e161b66966f5f3a41b0a49d1cc19e6ec9b50))
* reject path-traversal crop names in the design-catalog figma-raster carry ([#2226](https://github.com/yschimke/compose-ai-tools/issues/2226)) ([ec29552](https://github.com/yschimke/compose-ai-tools/commit/ec29552e865cf640a5b4704c791be22690bd42a0))
* structural (±1px) fidelity diff + locale-stable score JSON ([#2213](https://github.com/yschimke/compose-ai-tools/issues/2213)) ([2688714](https://github.com/yschimke/compose-ai-tools/commit/2688714d7777ec73fa5213940988069ce0c90097))
* use valid attribution settings keys and tighten Bash allowlist ([#2208](https://github.com/yschimke/compose-ai-tools/issues/2208)) ([0fb9369](https://github.com/yschimke/compose-ai-tools/commit/0fb936978a38d73375d5c06cf3e1094e5c7fddb7))

## [0.16.15](https://github.com/yschimke/compose-ai-tools/compare/v0.16.14...v0.16.15) (2026-07-03)


### Features

* **ci:** change-scoped preview renders, parallel pipelines, /rerun PR command ([#2195](https://github.com/yschimke/compose-ai-tools/issues/2195)) ([7c477f3](https://github.com/yschimke/compose-ai-tools/commit/7c477f36da68e3eedd2635bd5c81491dc13c6018))


### Bug Fixes

* honour @AnimatedPreview durationMs=0 auto-detect on the desktop renderer ([#2197](https://github.com/yschimke/compose-ai-tools/issues/2197)) ([e0757a1](https://github.com/yschimke/compose-ai-tools/commit/e0757a1d76aa7d765fc4d5435835ff3f43587534))
* **renderer-desktop:** refuse single-frame fall-through into scroll data products ([#2196](https://github.com/yschimke/compose-ai-tools/issues/2196)) ([ade74a6](https://github.com/yschimke/compose-ai-tools/commit/ade74a6bb9c8a0cdfaec659bc80640b5f42a895c))
* **renderers:** bring display-filter output paths to parity with device-frame ([#2199](https://github.com/yschimke/compose-ai-tools/issues/2199)) ([c0ccd4e](https://github.com/yschimke/compose-ai-tools/commit/c0ccd4ecd7579b73967bcd82a3c29b8812daca67))
* restrict fan-out sibling protection to same-extension outputs ([#2200](https://github.com/yschimke/compose-ai-tools/issues/2200)) ([2e61069](https://github.com/yschimke/compose-ai-tools/commit/2e6106992442c39872bb5db30b864db9d47c3ae4))
* stop stale fan-out cleanup deleting sibling previews' renders ([#2198](https://github.com/yschimke/compose-ai-tools/issues/2198)) ([af12d2a](https://github.com/yschimke/compose-ai-tools/commit/af12d2acae955d2d79659e1b49eb1147437ca9e2))

## [0.16.14](https://github.com/yschimke/compose-ai-tools/compare/v0.16.13...v0.16.14) (2026-07-02)


### Features

* **catalog:** generate the wasm tier's fonts.json from recorded font usage ([#2188](https://github.com/yschimke/compose-ai-tools/issues/2188)) ([b2abbb4](https://github.com/yschimke/compose-ai-tools/commit/b2abbb43267f7063751e4fde2325f9e92d1f4c5b))
* **catalog:** generic-family fonts — serif/mono specimens rendered from the same files on both tiers ([#2186](https://github.com/yschimke/compose-ai-tools/issues/2186)) ([5292c08](https://github.com/yschimke/compose-ai-tools/commit/5292c08bf9b14d5e469580e3072de59959057e7e))
* publish preview-annotations as a Kotlin Multiplatform artifact ([#2185](https://github.com/yschimke/compose-ai-tools/issues/2185)) ([3e47d1b](https://github.com/yschimke/compose-ai-tools/commit/3e47d1b04000fa56a391a5de2cc3fb381c4e5f61))


### Bug Fixes

* treat optional captures as expected skips in the missing-render gate and diff bot ([#2187](https://github.com/yschimke/compose-ai-tools/issues/2187)) ([0adda67](https://github.com/yschimke/compose-ai-tools/commit/0adda6727750375d53ce469bfebef7bb333f7734))

## [0.16.13](https://github.com/yschimke/compose-ai-tools/compare/v0.16.12...v0.16.13) (2026-07-02)


### Features

* add @ThemeCatalog for auto-discovered alternative-theme sheets ([#2182](https://github.com/yschimke/compose-ai-tools/issues/2182)) ([2064cb1](https://github.com/yschimke/compose-ai-tools/commit/2064cb1b35ec177d8c6b7c40a7323876542e1415))
* carry @ColorCatalog/@TypographyCatalog tokens in the preview bundle ([#2172](https://github.com/yschimke/compose-ai-tools/issues/2172)) ([eeed109](https://github.com/yschimke/compose-ai-tools/commit/eeed109f2d2cefa99f31e61018167bb04e9d07d4))
* **design-artifacts:** fold @ColorCatalog/@TypographyCatalog tokens into the kit ([#2178](https://github.com/yschimke/compose-ai-tools/issues/2178)) ([0d878da](https://github.com/yschimke/compose-ai-tools/commit/0d878da472f2f4617202e97e84086a028b63ee87))
* **serve:** make the public front page a design-systems index ([#2177](https://github.com/yschimke/compose-ai-tools/issues/2177)) ([58eaaff](https://github.com/yschimke/compose-ai-tools/commit/58eaaffd4b0f7dbe3795a141b32b1e1b887691b2))
* **wasm-catalog:** load Roboto by URL for snapshot text parity ([#2174](https://github.com/yschimke/compose-ai-tools/issues/2174)) ([265937f](https://github.com/yschimke/compose-ai-tools/commit/265937f7cf3b1b458c3864f63f0f459bdb253a3c))
* **wasm-catalog:** manifest-driven fonts, prefetched in parallel with the Wasm boot ([#2181](https://github.com/yschimke/compose-ai-tools/issues/2181)) ([a787693](https://github.com/yschimke/compose-ai-tools/commit/a787693ec5267c272c3950f7a5ee1f1d1c1ddd30))


### Bug Fixes

* **plugin:** keep scenecore-spatial backends off the composePreviewRenderXr classpath ([#2183](https://github.com/yschimke/compose-ai-tools/issues/2183)) ([d73f489](https://github.com/yschimke/compose-ai-tools/commit/d73f489d79efa7a559bc145faa60a6f749aaf710))
* **wasm-catalog:** make font fetches cancellable so a stalled origin can't hold the first frame ([#2176](https://github.com/yschimke/compose-ai-tools/issues/2176)) ([1ed906e](https://github.com/yschimke/compose-ai-tools/commit/1ed906e7aa44cdb8c5b718364da084bb659535c9))

## [0.16.12](https://github.com/yschimke/compose-ai-tools/compare/v0.16.11...v0.16.12) (2026-07-02)


### Features

* add @TypographyCatalog for auto-discovered type specimen sheets ([#2165](https://github.com/yschimke/compose-ai-tools/issues/2165)) ([6f1a09f](https://github.com/yschimke/compose-ai-tools/commit/6f1a09fab9fa3816fb3258b9f5ae7c6a55eb3593))
* emit @ColorCatalog/@TypographyCatalog tokens as a data product ([#2168](https://github.com/yschimke/compose-ai-tools/issues/2168)) ([33b09cb](https://github.com/yschimke/compose-ai-tools/commit/33b09cbeae4afaa390741cd074e7c941ad79e1cb))
* **serve:** pixel-exact Wasm tier with flash-free switch and no-background toggle ([#2169](https://github.com/yschimke/compose-ai-tools/issues/2169)) ([5673155](https://github.com/yschimke/compose-ai-tools/commit/56731552f4fe8045e9baf756d8d29a6c2e849cf7))


### Bug Fixes

* **plugin:** compile render shards at release 17, not 21 ([#2170](https://github.com/yschimke/compose-ai-tools/issues/2170)) ([b421be4](https://github.com/yschimke/compose-ai-tools/commit/b421be4e63f9fba69c99bf86e58d9da38839f29f))
* **plugin:** declare render tasks depend on generateUnitTestConfig ([#2162](https://github.com/yschimke/compose-ai-tools/issues/2162)) ([e1698bd](https://github.com/yschimke/compose-ai-tools/commit/e1698bd04dbcd0e0ac04b38a38bb1ebc34637b71))
* **plugin:** render tasks also depend on compileScreenshotTestKotlin ([#2166](https://github.com/yschimke/compose-ai-tools/issues/2166)) ([b9785fe](https://github.com/yschimke/compose-ai-tools/commit/b9785fe544f18675e1af117d907a4c44f95b1125))

## [0.16.11](https://github.com/yschimke/compose-ai-tools/compare/v0.16.10...v0.16.11) (2026-07-01)


### Features

* auto-discover @ColorCatalog design tokens into rendered swatch sheets ([#2148](https://github.com/yschimke/compose-ai-tools/issues/2148)) ([5098543](https://github.com/yschimke/compose-ai-tools/commit/5098543a8f5d75085dd9fc884b8a4496b650718a))
* **fake-emulator:** accept adb install and inspect APKs for preview discovery ([#2153](https://github.com/yschimke/compose-ai-tools/issues/2153)) ([ed1608d](https://github.com/yschimke/compose-ai-tools/commit/ed1608dfee1236f084164191fa3541da4eb136ae))


### Bug Fixes

* cap PR comment body to GitHub's 65,536-char limit ([#2150](https://github.com/yschimke/compose-ai-tools/issues/2150)) ([3585538](https://github.com/yschimke/compose-ai-tools/commit/3585538b32e4cc1c4f5a9a871c284abf3084289d))
* **cli:** honor --missing-renders in show-resources ([#2156](https://github.com/yschimke/compose-ai-tools/issues/2156)) ([9a6e321](https://github.com/yschimke/compose-ai-tools/commit/9a6e321dd7a65ae43a5fe79daca17ada7dd779a7))
* don't crash desktop renders on @ColorCatalog catalog sheets ([#2154](https://github.com/yschimke/compose-ai-tools/issues/2154)) ([c3c1d04](https://github.com/yschimke/compose-ai-tools/commit/c3c1d04400fdcfa83f701b675a173d75ed0d0035))
* **fake-emulator:** use escaped unicode literals instead of raw NUL bytes ([#2155](https://github.com/yschimke/compose-ai-tools/issues/2155)) ([5e668cb](https://github.com/yschimke/compose-ai-tools/commit/5e668cb171e46548de5f9ad3d84bf869f0559419))
* keep Android @ColorCatalog renders required in the render gate ([#2158](https://github.com/yschimke/compose-ai-tools/issues/2158)) ([c2e9c9c](https://github.com/yschimke/compose-ai-tools/commit/c2e9c9c326cc2b03327a7ce40cec6ecc9ca29e33))
* make @ColorCatalog capture optionality backend-aware at discovery ([#2160](https://github.com/yschimke/compose-ai-tools/issues/2160)) ([e8312ef](https://github.com/yschimke/compose-ai-tools/commit/e8312ef49c293b7911f0b6e8ac29a1e64d606373))


### Performance Improvements

* **preview-ci:** shard renders by default and widen the fork cap ([#2152](https://github.com/yschimke/compose-ai-tools/issues/2152)) ([777f914](https://github.com/yschimke/compose-ai-tools/commit/777f914b6f05827f8c778d231b1a0441b062daaf))

## [0.16.10](https://github.com/yschimke/compose-ai-tools/compare/v0.16.9...v0.16.10) (2026-07-01)


### Features

* add :color-preview-runtime colour/token specimen sheets ([#2140](https://github.com/yschimke/compose-ai-tools/issues/2140)) ([84fa3af](https://github.com/yschimke/compose-ai-tools/commit/84fa3af0136352359ec28b3593b34e1188e2fc7b))
* **deploy:** bake the published catalog set into the prebuilt image ([#2132](https://github.com/yschimke/compose-ai-tools/issues/2132)) ([9b5471a](https://github.com/yschimke/compose-ai-tools/commit/9b5471a0b55e5382673c9dd63cb4a2bf68aa5895))
* **samples:** add Remote Compose design catalog (remote-m3) ([#2134](https://github.com/yschimke/compose-ai-tools/issues/2134)) ([fc40ccd](https://github.com/yschimke/compose-ai-tools/commit/fc40ccdbadcb34750ffe40df5053b61260bc8c33))
* **serve:** add search filter to the preview browser landing grid ([#2145](https://github.com/yschimke/compose-ai-tools/issues/2145)) ([1cad9e0](https://github.com/yschimke/compose-ai-tools/commit/1cad9e090adaf959a33ac2a894d3e36f641af2df))
* **serve:** backend-provenance badge on the viewer stage ([#2131](https://github.com/yschimke/compose-ai-tools/issues/2131)) ([8915432](https://github.com/yschimke/compose-ai-tools/commit/8915432b6043cfc13815f86035fdef3f58bf0b0e))
* **serve:** bounded live-seat cap for the daemon-backed stream tier ([#2144](https://github.com/yschimke/compose-ai-tools/issues/2144)) ([bbf21df](https://github.com/yschimke/compose-ai-tools/commit/bbf21df440b353690e61bb76dd9edea6891bdb83))
* **serve:** recompose the Wasm tier in place instead of reloading the iframe ([#2127](https://github.com/yschimke/compose-ai-tools/issues/2127)) ([b81785f](https://github.com/yschimke/compose-ai-tools/commit/b81785fc6dbdb02cff427d39d4d2267515bb6db5))
* **serve:** sticky light/dark theme toggle on the catalog ([#2130](https://github.com/yschimke/compose-ai-tools/issues/2130)) ([cb20c32](https://github.com/yschimke/compose-ai-tools/commit/cb20c32d9b46d0743e33a77ba8844c91b4b6f03e))
* **wasm-catalog:** frame parity — scale the in-browser component to fill the stage ([#2139](https://github.com/yschimke/compose-ai-tools/issues/2139)) ([e39bca4](https://github.com/yschimke/compose-ai-tools/commit/e39bca46ea87b1faeac1fca0dc45d7f271eb8d6e))
* **wasm-catalog:** run the progress indicators live (indeterminate) ([#2142](https://github.com/yschimke/compose-ai-tools/issues/2142)) ([0d7333e](https://github.com/yschimke/compose-ai-tools/commit/0d7333ecf56134b2dd8c458a6ef2d3fb1f94b3eb))


### Bug Fixes

* **ci:** skip compose PR comment when previews.json is empty ([#2141](https://github.com/yschimke/compose-ai-tools/issues/2141)) ([be313d6](https://github.com/yschimke/compose-ai-tools/commit/be313d609edef345cf4200aa0755e25fae353e74))
* **preview-diff:** tolerate resource rasterizer jitter in the diff bot ([#2133](https://github.com/yschimke/compose-ai-tools/issues/2133)) ([69b8b55](https://github.com/yschimke/compose-ai-tools/commit/69b8b554e5437276d48492c20a65d496d6e17b92))
* **renderer-xr:** make FakeXrHeadPose seeding best-effort across XR versions ([#2146](https://github.com/yschimke/compose-ai-tools/issues/2146)) ([b5b85bf](https://github.com/yschimke/compose-ai-tools/commit/b5b85bf5d808a9f2206c7451efbece4ae9bfd6a5))

## [0.16.9](https://github.com/yschimke/compose-ai-tools/compare/v0.16.8...v0.16.9) (2026-07-01)


### Features

* **serve:** path-based catalog URLs + per-repo & unlisted --catalogs ([#2126](https://github.com/yschimke/compose-ai-tools/issues/2126)) ([bea1caa](https://github.com/yschimke/compose-ai-tools/commit/bea1caa63a39c2a1f2bfdfa493034ee22b995094))


### Bug Fixes

* **deploy:** move Watchtower to maintained fork ([#2122](https://github.com/yschimke/compose-ai-tools/issues/2122)) ([c6b6680](https://github.com/yschimke/compose-ai-tools/commit/c6b6680c2c1d951b1bdef5b1f742deac07b7e1f5))
* **samples:** opt in to ExperimentalRotateToLookAtUserApi in xr-spatial test ([#2120](https://github.com/yschimke/compose-ai-tools/issues/2120)) ([6c5a8df](https://github.com/yschimke/compose-ai-tools/commit/6c5a8df186f284b7f7d664a0ff4d20347a3eb317))
* **serve:** font-scale/theme/locale controls work on first interaction on catalog pages ([#2123](https://github.com/yschimke/compose-ai-tools/issues/2123)) ([73807b4](https://github.com/yschimke/compose-ai-tools/commit/73807b430004c908c835bcf8dcf5c449293122bc))
* **serve:** preserve the baked variant theme when opening the Wasm tier ([#2124](https://github.com/yschimke/compose-ai-tools/issues/2124)) ([7b71908](https://github.com/yschimke/compose-ai-tools/commit/7b71908fb2f4e6e785c98d09349a769b86d1d0ad))

## [0.16.8](https://github.com/yschimke/compose-ai-tools/compare/v0.16.7...v0.16.8) (2026-06-30)


### Features

* **serve:** live-edit declared knobs on daemon sessions ([#2117](https://github.com/yschimke/compose-ai-tools/issues/2117)) ([9ab1f07](https://github.com/yschimke/compose-ai-tools/commit/9ab1f07958a80776e6d77ed6962cd90aaffbecc6))

## [0.16.7](https://github.com/yschimke/compose-ai-tools/compare/v0.16.6...v0.16.7) (2026-06-30)


### Features

* **serve:** drive font scale + locale through the in-browser Wasm render ([#2115](https://github.com/yschimke/compose-ai-tools/issues/2115)) ([a9d6ed8](https://github.com/yschimke/compose-ai-tools/commit/a9d6ed89d85bfb650b45779cb78e0e3fac185446))
* **serve:** link served catalogs on the landing + disable dead viewer controls ([#2113](https://github.com/yschimke/compose-ai-tools/issues/2113)) ([c253e4c](https://github.com/yschimke/compose-ai-tools/commit/c253e4c40f861aa6e4c196227981e6bc1e123052))
* **serve:** trusted server-side re-render for catalogs (--allow-render-trusted) ([#2116](https://github.com/yschimke/compose-ai-tools/issues/2116)) ([d2ed3a6](https://github.com/yschimke/compose-ai-tools/commit/d2ed3a6a8443d214211cb3073d38106d9867e905))


### Bug Fixes

* **deploy:** bake a branch-trust store into the prebuilt image ([#2112](https://github.com/yschimke/compose-ai-tools/issues/2112)) ([92df70e](https://github.com/yschimke/compose-ai-tools/commit/92df70ec5ace926910913d65099e3c915c90d296))
* **deploy:** only print the ?token= URL when the box is token-gated ([#2109](https://github.com/yschimke/compose-ai-tools/issues/2109)) ([5a08e04](https://github.com/yschimke/compose-ai-tools/commit/5a08e045c05410775418fc4a65047be4e1064ef6))
* **deps:** update gradle minor/patch (excluding held remote compose artifacts) ([#2104](https://github.com/yschimke/compose-ai-tools/issues/2104)) ([464cfce](https://github.com/yschimke/compose-ai-tools/commit/464cfce04194eba92f00a8b54b3a72a52765b92d))
* **release:** stop the prebuilt-image job skipping on the release chain ([#2111](https://github.com/yschimke/compose-ai-tools/issues/2111)) ([03b3b26](https://github.com/yschimke/compose-ai-tools/commit/03b3b267e41fa24e729a979d602c42c24ebd83ac))
* **samples:** opt in to ExperimentalRotateToLookAtUserApi in xr-spatial ([#2114](https://github.com/yschimke/compose-ai-tools/issues/2114)) ([4c2de16](https://github.com/yschimke/compose-ai-tools/commit/4c2de160f43302e7b1465bfce9e9b15f8115229d))

## [0.16.6](https://github.com/yschimke/compose-ai-tools/compare/v0.16.5...v0.16.6) (2026-06-29)


### Features

* **serve:** add /version endpoint and a public-mode about intro ([#2108](https://github.com/yschimke/compose-ai-tools/issues/2108)) ([91170e8](https://github.com/yschimke/compose-ai-tools/commit/91170e883cf70fed6b157b064432187fd2379abc))


### Bug Fixes

* **release:** seed the prebuilt image from same-run Maven artifacts ([#2107](https://github.com/yschimke/compose-ai-tools/issues/2107)) ([2017a46](https://github.com/yschimke/compose-ai-tools/commit/2017a4656c9c31fb651bc56fdefb92d13f88d178))
* **release:** wait for published artifacts before building the host image ([#2105](https://github.com/yschimke/compose-ai-tools/issues/2105)) ([55b2cf5](https://github.com/yschimke/compose-ai-tools/commit/55b2cf52045b6b30981ea5377ce5566aac3ddb9d))

## [0.16.5](https://github.com/yschimke/compose-ai-tools/compare/v0.16.4...v0.16.5) (2026-06-29)


### Features

* **deploy:** public preview-server profile for preview.coo.ee ([#2101](https://github.com/yschimke/compose-ai-tools/issues/2101)) ([71d82be](https://github.com/yschimke/compose-ai-tools/commit/71d82be4595bb990f8820dd30dfb46965f36bedd))
* **design-artifacts:** publish the CMP Wasm app into the catalog branch ([#2102](https://github.com/yschimke/compose-ai-tools/issues/2102)) ([5c0f405](https://github.com/yschimke/compose-ai-tools/commit/5c0f405cc400d2d77e4bc842dc07c23fd577e716))
* **serve:** carry the CMP Wasm app on the trusted catalog branch + cache it ([#2099](https://github.com/yschimke/compose-ai-tools/issues/2099)) ([7efa49f](https://github.com/yschimke/compose-ai-tools/commit/7efa49fe3d1449d654682d7197285df6e896f517))

## [0.16.4](https://github.com/yschimke/compose-ai-tools/compare/v0.16.3...v0.16.4) (2026-06-29)


### Features

* **design-artifacts:** deep-link published catalogs to the live preview server ([#2086](https://github.com/yschimke/compose-ai-tools/issues/2086)) ([49d5086](https://github.com/yschimke/compose-ai-tools/commit/49d50869f7a690ef3473163689d9e4b7601f52e9))
* **serve:** --public open mode + trust-store starter + public-server docs ([#2087](https://github.com/yschimke/compose-ai-tools/issues/2087)) ([4b448cb](https://github.com/yschimke/compose-ai-tools/commit/4b448cb8d7518431ac4fc00798de4b5368095733))
* **serve:** badge the producer-trust verdict in the viewer ([#2095](https://github.com/yschimke/compose-ai-tools/issues/2095)) ([9282c44](https://github.com/yschimke/compose-ai-tools/commit/9282c44101a1ccdb1560eb7d861907a77a9145db))
* **serve:** in-browser CMP catalog via Kotlin/Wasm (compose-m3 live tier) ([#2097](https://github.com/yschimke/compose-ai-tools/issues/2097)) ([ee4a0f6](https://github.com/yschimke/compose-ai-tools/commit/ee4a0f6c330a5713a165e1e54ec1638b394eadd9))
* **serve:** mount the in-browser CMP Wasm tier in the viewer ([#2098](https://github.com/yschimke/compose-ai-tools/issues/2098)) ([64df61c](https://github.com/yschimke/compose-ai-tools/commit/64df61c515b68960958aa3d2f353bac511a181a5))


### Bug Fixes

* **release:** split release-please into separate PRs so the root release tags on merge ([#2088](https://github.com/yschimke/compose-ai-tools/issues/2088)) ([a67736e](https://github.com/yschimke/compose-ai-tools/commit/a67736e856a6fac216b73ce0ba99748988b4299e))

## [0.16.3](https://github.com/yschimke/compose-ai-tools/compare/v0.16.2...v0.16.3) (2026-06-29)


### Features

* **bundle:** Ed25519 signing + trust verification for portable bundles ([#2076](https://github.com/yschimke/compose-ai-tools/issues/2076)) ([cd73e42](https://github.com/yschimke/compose-ai-tools/commit/cd73e42199f7c6b8d9c59f43c9a9ba5de78e4bec))
* **daemon:** capture coordinate-free script from live recordings ([#2047](https://github.com/yschimke/compose-ai-tools/issues/2047)) ([96f7b5b](https://github.com/yschimke/compose-ai-tools/commit/96f7b5b5fd328ee569c55278d4262ef903e31c11))
* **deploy:** add generic VPS (Hetzner) hosting for the preview server ([#2068](https://github.com/yschimke/compose-ai-tools/issues/2068)) ([d4b2147](https://github.com/yschimke/compose-ai-tools/commit/d4b21471bcb64c6bf004f0449ed8cf56c3da3f49))
* **deploy:** auto-update the prebuilt image via Watchtower ([#2072](https://github.com/yschimke/compose-ai-tools/issues/2072)) ([e1a33e8](https://github.com/yschimke/compose-ai-tools/commit/e1a33e82befae4517dfa8c0da07fe1c20fa1e67e))
* **deploy:** prebuilt preview-host image from published artifacts ([#2070](https://github.com/yschimke/compose-ai-tools/issues/2070)) ([3f29ef2](https://github.com/yschimke/compose-ai-tools/commit/3f29ef29f0c41eee05d2b07c00e3a07cc0eb0b24))
* **design-artifacts:** editable SVG wireframes in the export bundle ([#2064](https://github.com/yschimke/compose-ai-tools/issues/2064)) ([346d5b3](https://github.com/yschimke/compose-ai-tools/commit/346d5b3b3282c289ac230759ed741bb927cc558f))
* **design-artifacts:** emit a browsable index.html per catalog ([#2059](https://github.com/yschimke/compose-ai-tools/issues/2059)) ([35f21e3](https://github.com/yschimke/compose-ai-tools/commit/35f21e3d5618101eb06ad30cc9d62d18aac6cf4d))
* **design-artifacts:** generate a README.md on each delivery branch ([#2067](https://github.com/yschimke/compose-ai-tools/issues/2067)) ([97a4258](https://github.com/yschimke/compose-ai-tools/commit/97a42580382d33dab4a0afdd56742770f3cd56a9))
* **design-artifacts:** wireframes from the layout-inspector tree ([#2065](https://github.com/yschimke/compose-ai-tools/issues/2065)) ([81b3b82](https://github.com/yschimke/compose-ai-tools/commit/81b3b826af30e5a0fc05baade7711aba6824ee4e))
* editable preview overrides for plain Compose, carried in bundles and served ([#2074](https://github.com/yschimke/compose-ai-tools/issues/2074)) ([8ab79dc](https://github.com/yschimke/compose-ai-tools/commit/8ab79dc864667b8b267f85417816c614e1eac5c5))
* **overrides:** surface declared compose/overrides knobs on Android via sandbox bridge ([#2083](https://github.com/yschimke/compose-ai-tools/issues/2083)) ([1e57b15](https://github.com/yschimke/compose-ai-tools/commit/1e57b15fb81d5cea0c4f0f936d5f34690cac0fee))
* **renderer:** render the Material pressed ripple by settling the Android clock ([#2055](https://github.com/yschimke/compose-ai-tools/issues/2055)) ([c31ea02](https://github.com/yschimke/compose-ai-tools/commit/c31ea0224843557f4363d31b2ee74cd89c7f6b5f))
* **samples:** add component States group (pressed/focused/disabled/toggle) to catalogs ([#2052](https://github.com/yschimke/compose-ai-tools/issues/2052)) ([a810f80](https://github.com/yschimke/compose-ai-tools/commit/a810f80512b3bd060a638c3e89f537486d44cad3))
* **samples:** add design-catalog-m3 sticker-sheet module ([#2048](https://github.com/yschimke/compose-ai-tools/issues/2048)) ([33474c8](https://github.com/yschimke/compose-ai-tools/commit/33474c81a02d2481aeb10766c5583bd6f02f7c70))
* **samples:** add design-catalog-wear-m3 sticker-sheet module ([#2049](https://github.com/yschimke/compose-ai-tools/issues/2049)) ([5d3d1d8](https://github.com/yschimke/compose-ai-tools/commit/5d3d1d81b99719716ffbc9501d1e1e0ecc1a43a1))
* **samples:** blank Wear list-layout template at the breakpoints ([#2063](https://github.com/yschimke/compose-ai-tools/issues/2063)) ([5af3265](https://github.com/yschimke/compose-ai-tools/commit/5af3265ff99cf869da737144b6df886a2f08f839))
* **samples:** transparent backgrounds for Wear component stickers ([#2061](https://github.com/yschimke/compose-ai-tools/issues/2061)) ([a4a7284](https://github.com/yschimke/compose-ai-tools/commit/a4a728432951fabb525b5dbe0fd27e82a314c15c))
* **samples:** Wear size breakpoints + scaling list sample ([#2060](https://github.com/yschimke/compose-ai-tools/issues/2060)) ([8bbad5c](https://github.com/yschimke/compose-ai-tools/commit/8bbad5c50a0965b72741f33d2e7eb9ffef31deb3))
* **serve:** serve published design systems from trusted design-artifacts branches ([#2084](https://github.com/yschimke/compose-ai-tools/issues/2084)) ([9df6ef7](https://github.com/yschimke/compose-ai-tools/commit/9df6ef78f7cd84a0baab0d8a5602019e7a1313ac))
* **serve:** verify uploaded bundles against a producer trust store ([#2081](https://github.com/yschimke/compose-ai-tools/issues/2081)) ([4154388](https://github.com/yschimke/compose-ai-tools/commit/41543884fbac1b44f3cca1ddbb14812ecfedb412))


### Bug Fixes

* **ci:** authenticate the design-parity clone in design-artifacts job ([26583d8](https://github.com/yschimke/compose-ai-tools/commit/26583d85044999508483b53a6ce56571ceef1433))
* **ci:** clone design-parity anonymously in design-artifacts job ([#2053](https://github.com/yschimke/compose-ai-tools/issues/2053)) ([8ca2766](https://github.com/yschimke/compose-ai-tools/commit/8ca2766ec1dacb7bdcb68285ab709b2432c3f968))
* **ci:** guard null widthDp in the design-artifacts driver ([058ee10](https://github.com/yschimke/compose-ai-tools/commit/058ee104ec36b72bf1c0c77ebf2218c4cc1ac372))
* **ci:** source the design-artifacts export engine from npm, not a private clone ([#2056](https://github.com/yschimke/compose-ai-tools/issues/2056)) ([d5e8f77](https://github.com/yschimke/compose-ai-tools/commit/d5e8f77b220873ad58043ef186ded24522be041b))
* **deploy:** install Mesa software GL so Skiko renders headless ([#2069](https://github.com/yschimke/compose-ai-tools/issues/2069)) ([e5d97d8](https://github.com/yschimke/compose-ai-tools/commit/e5d97d8d8174f7e2ce8b8645888f4ed87c63ce71))
* **renderer:** re-capture animation/scroll/focus frames that fail to decode ([#2082](https://github.com/yschimke/compose-ai-tools/issues/2082)) ([41c8926](https://github.com/yschimke/compose-ai-tools/commit/41c8926dcdba41c731d83d8f3848a0042ce4df4a))
* **renderer:** settle END scroll captures so the Wear EdgeButton renders at rest ([3ed856c](https://github.com/yschimke/compose-ai-tools/commit/3ed856c5e1df6002399317ca60aefdb61db1e7a0))
* **samples:** make Wear EdgeButton sticker render deterministic ([#2051](https://github.com/yschimke/compose-ai-tools/issues/2051)) ([46f69c1](https://github.com/yschimke/compose-ai-tools/commit/46f69c1185fa461e083b9fd47c443e8eaabd38e1))
* **serve:** retry coalesced renders, cap concurrency, bound frame time ([#2071](https://github.com/yschimke/compose-ai-tools/issues/2071)) ([71be715](https://github.com/yschimke/compose-ai-tools/commit/71be7153de10212eb02c23d5f206dbe90c77c4e7))

## [0.16.2](https://github.com/yschimke/compose-ai-tools/compare/v0.16.1...v0.16.2) (2026-06-22)


### Bug Fixes

* **release:** don't let clients-v* releases claim GitHub "latest" ([#2045](https://github.com/yschimke/compose-ai-tools/issues/2045)) ([c1077b6](https://github.com/yschimke/compose-ai-tools/commit/c1077b68d0adb916409d09baa78e28ee5dfb3a81))

## [0.16.1](https://github.com/yschimke/compose-ai-tools/compare/v0.16.0...v0.16.1) (2026-06-22)


### Features

* **a11y:** render the TalkBack focus overlay in desktop recordings ([#1956](https://github.com/yschimke/compose-ai-tools/issues/1956)) ([#1973](https://github.com/yschimke/compose-ai-tools/issues/1973)) ([4abaab1](https://github.com/yschimke/compose-ai-tools/commit/4abaab1e57a42fd0bb9236cfbdf97db9e26b1ad7))
* **a11y:** scriptable TalkBack next/previous focus navigation ([#1956](https://github.com/yschimke/compose-ai-tools/issues/1956)) ([#1976](https://github.com/yschimke/compose-ai-tools/issues/1976)) ([b0ca2b2](https://github.com/yschimke/compose-ai-tools/commit/b0ca2b26196b30529cea08f10c7f63277dbe6d0f))
* **a11y:** TalkBack visualization, utterance composition + scriptable verbs ([#1956](https://github.com/yschimke/compose-ai-tools/issues/1956)) ([#1962](https://github.com/yschimke/compose-ai-tools/issues/1962)) ([a7bc439](https://github.com/yschimke/compose-ai-tools/commit/a7bc43900cdb6ac36951d6b09d5c59b86f361613))
* add launcher mode for widget previews ([#1971](https://github.com/yschimke/compose-ai-tools/issues/1971)) ([8c4e066](https://github.com/yschimke/compose-ai-tools/commit/8c4e066dd4689cc323c0cc8364126f04749d7a19))
* **cli:** accept client-contributed bundles at runtime (shared/public mode) ([#2027](https://github.com/yschimke/compose-ai-tools/issues/2027)) ([fa2e7cf](https://github.com/yschimke/compose-ai-tools/commit/fa2e7cf42a89efad7676191970f2709660c0bc4f))
* **clients:** mobile + wear session-viewer clients for the serve stream lane ([#1997](https://github.com/yschimke/compose-ai-tools/issues/1997)) ([20d24e0](https://github.com/yschimke/compose-ai-tools/commit/20d24e07b0282a6ef04c529fd2e6b3df13774523))
* **cli:** ephemeral mode — exit the server when idle ([#2024](https://github.com/yschimke/compose-ai-tools/issues/2024)) ([6b589e0](https://github.com/yschimke/compose-ai-tools/commit/6b589e037e41429089c167b0e29737a73a949258))
* **cli:** group non-core commands under inspect/capture/share/setup ([#2009](https://github.com/yschimke/compose-ai-tools/issues/2009)) ([4f0e489](https://github.com/yschimke/compose-ai-tools/commit/4f0e4895f8406a355fe8672d13838743f0a7830e))
* **cli:** richer interactive input for serve live lane (drag, scroll, keyboard) ([#2003](https://github.com/yschimke/compose-ai-tools/issues/2003)) ([790b494](https://github.com/yschimke/compose-ai-tools/commit/790b494a12b0892515c04064f1dbaefca68782e3))
* **cli:** serve — LAN preview server + portable bundle (Remote Sessions tier 1) ([#1982](https://github.com/yschimke/compose-ai-tools/issues/1982)) ([c205613](https://github.com/yschimke/compose-ai-tools/commit/c20561318f30d0d370a137629f1d4f6ffa826170))
* **cli:** serve streamed-frame lane over WebSocket (tier-2 spike) ([#1989](https://github.com/yschimke/compose-ai-tools/issues/1989)) ([4eb5c11](https://github.com/yschimke/compose-ai-tools/commit/4eb5c11bb74214b7b8c8c27e205565b18c817c68))
* **cli:** share one daemon stream across watchers + preview switch ([#2010](https://github.com/yschimke/compose-ai-tools/issues/2010)) ([6565170](https://github.com/yschimke/compose-ai-tools/commit/65651702be1a751f933eca1448408b60981af0f2))
* **cli:** shared mode — host pre-rendered portable bundles ([#2025](https://github.com/yschimke/compose-ai-tools/issues/2025)) ([4dad933](https://github.com/yschimke/compose-ai-tools/commit/4dad9335155551ae12184f7922d5cb85c45fe696))
* **cli:** suspend/resume serve sessions instead of running daemons forever ([#2017](https://github.com/yschimke/compose-ai-tools/issues/2017)) ([c34d792](https://github.com/yschimke/compose-ai-tools/commit/c34d7924789dfce228ceaf238f50cae0444134ac))
* composite previews into real device-art bezels ([#1981](https://github.com/yschimke/compose-ai-tools/issues/1981)) ([cbd61d0](https://github.com/yschimke/compose-ai-tools/commit/cbd61d057a0ae8d79076267af5c6ad2c61195482))
* **data:** add compose/shared-element data-product core model ([#1974](https://github.com/yschimke/compose-ai-tools/issues/1974)) ([7d3fbfa](https://github.com/yschimke/compose-ai-tools/commit/7d3fbfa50fe5177be42ff06b76da59338431426d))
* **deploy:** add Cloud Run hosting for the preview server ([#2037](https://github.com/yschimke/compose-ai-tools/issues/2037)) ([693a114](https://github.com/yschimke/compose-ai-tools/commit/693a1148781b8507690cb48a43fea3c77d216044))
* **deploy:** add Oracle Cloud Always Free hosting for the preview server ([#2038](https://github.com/yschimke/compose-ai-tools/issues/2038)) ([00ec811](https://github.com/yschimke/compose-ai-tools/commit/00ec8113071186b2584b48a42f4255332c27054b))
* **fake-emulator:** full EmulatorController gRPC surface + adb-shell device-setting overrides ([#2034](https://github.com/yschimke/compose-ai-tools/issues/2034)) ([cd63959](https://github.com/yschimke/compose-ai-tools/commit/cd639599f48e841f5e62f2280e56731d5c515cc9))
* **fake-emulator:** impersonate an Android emulator over ADB + emulator gRPC ([#2032](https://github.com/yschimke/compose-ai-tools/issues/2032)) ([ede7330](https://github.com/yschimke/compose-ai-tools/commit/ede73301e1920ebcb43fc619cc7ccc197b012fb2))
* **gradle-plugin:** add configuration-only preview plugin and shared DSL module ([#1985](https://github.com/yschimke/compose-ai-tools/issues/1985)) ([f89f25a](https://github.com/yschimke/compose-ai-tools/commit/f89f25a551e1293c39a6cd13fe48de073a2c7b00))
* **gradle-plugin:** record configured variant + enabled in the applied marker ([#2001](https://github.com/yschimke/compose-ai-tools/issues/2001)) ([508d655](https://github.com/yschimke/compose-ai-tools/commit/508d655fe2bc1d4add6908d6e8f4d8d6428b991a))
* **recording:** add `compose-preview record` command and first-class GIF format ([#1960](https://github.com/yschimke/compose-ai-tools/issues/1960)) ([6097a58](https://github.com/yschimke/compose-ai-tools/commit/6097a58228de4f139f3de109aee907926ab053c4))
* **recording:** assert.a11y gates a recording on ATF findings ([#1966](https://github.com/yschimke/compose-ai-tools/issues/1966)) ([#1983](https://github.com/yschimke/compose-ai-tools/issues/1983)) ([1912da9](https://github.com/yschimke/compose-ai-tools/commit/1912da9a4f340f428fbdb4d39cd11d4ae1e60b5c))
* **recording:** assert.pixels golden-image check against a committed baseline ([#1967](https://github.com/yschimke/compose-ai-tools/issues/1967)) ([#1987](https://github.com/yschimke/compose-ai-tools/issues/1987)) ([2a5dcdc](https://github.com/yschimke/compose-ai-tools/commit/2a5dcdc8f4b1c2849a0e3139a9d7f8a92c992808))
* **recording:** assert.textEquals — assert a resolved node's text ([#1972](https://github.com/yschimke/compose-ai-tools/issues/1972)) ([773c654](https://github.com/yschimke/compose-ai-tools/commit/773c654dcecdbd4f7cfecae09b3b1571d278012f))
* **recording:** assert.visible / assert.notVisible script events that fail the recording ([#1963](https://github.com/yschimke/compose-ai-tools/issues/1963)) ([e71fc64](https://github.com/yschimke/compose-ai-tools/commit/e71fc647776a6248f9d24d8e6494702145cd33b9))
* **recording:** wire assert.visible / assert.notVisible on the Android backend ([#1977](https://github.com/yschimke/compose-ai-tools/issues/1977)) ([fd4a985](https://github.com/yschimke/compose-ai-tools/commit/fd4a985f3a18dcbf098dbe90399035504df8bbd1))
* **samples:** cover runtime shaders (SkSL + AGSL) in the preview pipeline ([#1952](https://github.com/yschimke/compose-ai-tools/issues/1952)) ([61f06ec](https://github.com/yschimke/compose-ai-tools/commit/61f06ec355af49f981fa19dc145f5871794dddac))
* **samples:** Remote Compose gradient-shader preview with a named-color control ([#1961](https://github.com/yschimke/compose-ai-tools/issues/1961)) ([3d4f89a](https://github.com/yschimke/compose-ai-tools/commit/3d4f89a8101b55db0a89deb5e1bc006854f8d4e6))
* **samples:** shader feature-survey gallery (SkSL + AGSL, still + animated) ([#1958](https://github.com/yschimke/compose-ai-tools/issues/1958)) ([110b1e5](https://github.com/yschimke/compose-ai-tools/commit/110b1e5d574a85b6c2e4fee2e1236fedd44207d9))
* **touch-overlay:** fling velocity arrow + long-press progress ring ([#1959](https://github.com/yschimke/compose-ai-tools/issues/1959)) ([2235dee](https://github.com/yschimke/compose-ai-tools/commit/2235dee524a7da98cc0d0c9d9e28460ae2f4bd43))
* **touch-overlay:** mark releases the composition didn't consume ([#1970](https://github.com/yschimke/compose-ai-tools/issues/1970)) ([4020383](https://github.com/yschimke/compose-ai-tools/commit/40203831ca74c0653f2a43af2801bb00cf2d22ee))
* **touch-overlay:** richer tap / drag / pinch gesture overlay ([#1954](https://github.com/yschimke/compose-ai-tools/issues/1954)) ([6cbe14c](https://github.com/yschimke/compose-ai-tools/commit/6cbe14c3cb7601b05cc3f34c2006fbfcef07afdc))
* **touch-overlay:** two-finger rotation + pan, N-touch hull + count badge ([#1978](https://github.com/yschimke/compose-ai-tools/issues/1978)) ([03a7691](https://github.com/yschimke/compose-ai-tools/commit/03a7691598e8a3d2b9f6ad0ad756f40d2cf22559))
* **vscode:** read configured variant/enabled from the applied marker ([#2014](https://github.com/yschimke/compose-ai-tools/issues/2014)) ([8b612de](https://github.com/yschimke/compose-ai-tools/commit/8b612deeff622b1d88cdc0b727a2fa19784bf86f))
* **xr-composite:** map the preview onto the device surface in the GLB preview ([#1996](https://github.com/yschimke/compose-ai-tools/issues/1996)) ([e1efc93](https://github.com/yschimke/compose-ai-tools/commit/e1efc93e6d87d7b826a48ad99c28a8b9081a6e4b))


### Bug Fixes

* **cli:** derive command detection from a guarded flag registry ([#2006](https://github.com/yschimke/compose-ai-tools/issues/2006)) ([6512aa3](https://github.com/yschimke/compose-ai-tools/commit/6512aa3e794104911fefd96d3718c0dc7c9cf2ec))
* **cli:** fail project-mode revision builds closed (RCE) pending hardening ([#2029](https://github.com/yschimke/compose-ai-tools/issues/2029)) ([80ee188](https://github.com/yschimke/compose-ai-tools/commit/80ee1881e7d0e34cfb6649fa0c81d83bf0ef8f11))
* **cli:** gate project-mode revisions behind a ref allowlist (RCE) ([#2031](https://github.com/yschimke/compose-ai-tools/issues/2031)) ([5d0fe8b](https://github.com/yschimke/compose-ai-tools/commit/5d0fe8bf558063783b55b1896c89a4830fd1eb52))
* **cli:** keep auto-inject on when only the config-only plugin is convention-supplied ([#1992](https://github.com/yschimke/compose-ai-tools/issues/1992)) ([44b809b](https://github.com/yschimke/compose-ai-tools/commit/44b809b8855f883188ceb07eafdae2f643e1bf94))
* **fake-emulator:** move the a11y subscription with the launched preview ([#2036](https://github.com/yschimke/compose-ai-tools/issues/2036)) ([e094943](https://github.com/yschimke/compose-ai-tools/commit/e094943cab64bb56e5fc79ad1f9980c254b5155d))
* make device-frame prefetch work and un-break Bundle Render E2E ([#1984](https://github.com/yschimke/compose-ai-tools/issues/1984)) ([983ec6a](https://github.com/yschimke/compose-ai-tools/commit/983ec6aeef52bc72efa12e4921e4f45ecdecb4f0))
* **recording:** publish-safe PixelDiff + snapshot assert.pixels at event position ([#1967](https://github.com/yschimke/compose-ai-tools/issues/1967)) ([#1990](https://github.com/yschimke/compose-ai-tools/issues/1990)) ([556d6e4](https://github.com/yschimke/compose-ai-tools/commit/556d6e4dfa5f75fcd56c084eafae75b2fad65211))
* **renderer:** concise RenderPreviewEntry.toString so preview test filenames don't overflow ([#2023](https://github.com/yschimke/compose-ai-tools/issues/2023)) ([dd26e50](https://github.com/yschimke/compose-ai-tools/commit/dd26e50f7b9bd45d442b8c9b795f3a7a29bddb20))
* **renderer:** diagnose truncated animated-frame PNGs instead of opaque IIOException ([#2035](https://github.com/yschimke/compose-ai-tools/issues/2035)) ([e3df7fe](https://github.com/yschimke/compose-ai-tools/commit/e3df7fef8479c43f39d096eaba4173bc7201aafa))

## [0.16.0](https://github.com/yschimke/compose-ai-tools/compare/v0.15.13...v0.16.0) (2026-06-18)


### ⚠ BREAKING CHANGES

* **compose/semantics:** consolidate flat layout* text fields into themed sub-objects ([#1945](https://github.com/yschimke/compose-ai-tools/issues/1945))

### Features

* **cli:** guide convention-plugin builds when auto-inject can't see AGP ([#1948](https://github.com/yschimke/compose-ai-tools/issues/1948)) ([9e36505](https://github.com/yschimke/compose-ai-tools/commit/9e365053410c3460b152a231062b777dcce44164))
* **compose/semantics:** consolidate flat layout* text fields into themed sub-objects ([#1945](https://github.com/yschimke/compose-ai-tools/issues/1945)) ([d45889d](https://github.com/yschimke/compose-ai-tools/commit/d45889dc29a491d62d4f8e45e9948bf7b996ecad))
* **compose/semantics:** emit per-node font identity (family/weight/style/variation) ([#1935](https://github.com/yschimke/compose-ai-tools/issues/1935)) ([5e814a4](https://github.com/yschimke/compose-ai-tools/commit/5e814a43659d449f28e977342e57c7e26a1d65c6))
* **daemon/history:** history/diff data mode — a11y/semantics/theme diff ([#1873](https://github.com/yschimke/compose-ai-tools/issues/1873)) ([#1949](https://github.com/yschimke/compose-ai-tools/issues/1949)) ([f5f079c](https://github.com/yschimke/compose-ai-tools/commit/f5f079c5c5634c5fe42287647a7a4640ef7f3081))
* **samples:** shared-element transitions + Compose 1.11 visual-debug previews ([#1944](https://github.com/yschimke/compose-ai-tools/issues/1944)) ([167f213](https://github.com/yschimke/compose-ai-tools/commit/167f2134b08f23211f43127656812fd12808de06))


### Bug Fixes

* **bundle:** don't require classes/app.jar in IR-backed bundles ([#1950](https://github.com/yschimke/compose-ai-tools/issues/1950)) ([129a447](https://github.com/yschimke/compose-ai-tools/commit/129a4470430b06e8afcf4a915c671bf268b016cc))

## [0.15.13](https://github.com/yschimke/compose-ai-tools/compare/v0.15.12...v0.15.13) (2026-06-17)


### Features

* A/B compare nominated preview variants side-by-side in CI ([#1937](https://github.com/yschimke/compose-ai-tools/issues/1937)) ([573000d](https://github.com/yschimke/compose-ai-tools/commit/573000d7c1275dd9072e504eb82876e6303ee9f0))
* produce layout/inspector on desktop and unify modifier-token resolution ([#1941](https://github.com/yschimke/compose-ai-tools/issues/1941)) ([9351469](https://github.com/yschimke/compose-ai-tools/commit/935146920446c02fb233453da4ef8f5c0cdbfacb))
* **renderer-desktop:** simulate Android system bars for showSystemUi ([#1930](https://github.com/yschimke/compose-ai-tools/issues/1930)) ([#1942](https://github.com/yschimke/compose-ai-tools/issues/1942)) ([8d1725f](https://github.com/yschimke/compose-ai-tools/commit/8d1725f9f32f97a1445ef56c744a58be6b468452))


### Bug Fixes

* **apply:** default cli-version to auto so the CLI tracks the pinned plugin ([#1920](https://github.com/yschimke/compose-ai-tools/issues/1920)) ([#1938](https://github.com/yschimke/compose-ai-tools/issues/1938)) ([2cf261d](https://github.com/yschimke/compose-ai-tools/commit/2cf261d1beb195d4194cc9422f699c1f0cf9ad1f))
* **cli:** keep discovery working when a convention plugin supplies the preview plugin ([#1939](https://github.com/yschimke/compose-ai-tools/issues/1939)) ([ff6893e](https://github.com/yschimke/compose-ai-tools/commit/ff6893ee53371b40f71633a97f5bcbb6420504ac))

## [0.15.12](https://github.com/yschimke/compose-ai-tools/compare/v0.15.11...v0.15.12) (2026-06-16)


### Bug Fixes

* **bundle:** pack module bytecode from the scoped PROJECT CLASSES artifact ([#1928](https://github.com/yschimke/compose-ai-tools/issues/1928)) ([d646485](https://github.com/yschimke/compose-ai-tools/commit/d64648550b1d3acb111f986629c1f24f907d7698))
* **discovery:** match project classes by canonical path for symlinked build trees ([#1931](https://github.com/yschimke/compose-ai-tools/issues/1931)) ([8ca3063](https://github.com/yschimke/compose-ai-tools/commit/8ca306380c61b194ea75261ef08bc842935e8213))

## [0.15.11](https://github.com/yschimke/compose-ai-tools/compare/v0.15.10...v0.15.11) (2026-06-16)


### Bug Fixes

* **discovery:** find previews under AGP 9.x built-in Kotlin ([#1924](https://github.com/yschimke/compose-ai-tools/issues/1924)) ([#1925](https://github.com/yschimke/compose-ai-tools/issues/1925)) ([7953931](https://github.com/yschimke/compose-ai-tools/commit/7953931eeba628144de88105335bfbd9095fa0dc))

## [0.15.10](https://github.com/yschimke/compose-ai-tools/compare/v0.15.9...v0.15.10) (2026-06-16)


### Features

* **daemon:** curate the reporting branch by publish policy (skip dirty / off-branch renders) ([#1922](https://github.com/yschimke/compose-ai-tools/issues/1922)) ([d8dfab9](https://github.com/yschimke/compose-ai-tools/commit/d8dfab99788b6a897fbd102126bae07ccc7bb45a))
* **daemon:** GitRefHistorySource WRITE_PUSH — publish the reporting branch to a remote ([#1918](https://github.com/yschimke/compose-ai-tools/issues/1918)) ([2d41cc0](https://github.com/yschimke/compose-ai-tools/commit/2d41cc079322ed8a1ba95227147b6b5448f87feb))
* **vscode:** read data-product diffs from the reporting branch ([#1915](https://github.com/yschimke/compose-ai-tools/issues/1915)) ([630d127](https://github.com/yschimke/compose-ai-tools/commit/630d127d18620575da4f0673d8b555c31a89dd9b))


### Bug Fixes

* **apply:** guard against CLI/plugin version skew ([#1920](https://github.com/yschimke/compose-ai-tools/issues/1920)) ([#1921](https://github.com/yschimke/compose-ai-tools/issues/1921)) ([de9b334](https://github.com/yschimke/compose-ai-tools/commit/de9b33423990caf10f36f6381f0ef025f504348c))
* **daemon:** read live git state for the reporting-branch curation gate ([#1923](https://github.com/yschimke/compose-ai-tools/issues/1923)) ([d13b91b](https://github.com/yschimke/compose-ai-tools/commit/d13b91b9f64c2173afafcb75becfaa589618f1b7))


### Performance Improvements

* **daemon:** debounce reporting-branch commits across a render burst ([#1919](https://github.com/yschimke/compose-ai-tools/issues/1919)) ([3ff98f0](https://github.com/yschimke/compose-ai-tools/commit/3ff98f04ba69df0d8bd761e3d551b94cd3b8f1c0))

## [0.15.9](https://github.com/yschimke/compose-ai-tools/compare/v0.15.8...v0.15.9) (2026-06-15)


### Features

* **daemon:** commit-walk timeline read for the reporting branch ([#1913](https://github.com/yschimke/compose-ai-tools/issues/1913)) ([098f0fe](https://github.com/yschimke/compose-ai-tools/commit/098f0feb09850a897a95e6215e9038e8e16fef98))
* **daemon:** extend compose/semantics token capture (gap, border, non-dp radii) ([#1910](https://github.com/yschimke/compose-ai-tools/issues/1910)) ([bb44285](https://github.com/yschimke/compose-ai-tools/commit/bb442858838299340798ed442cb0d05a823a99b8))
* **daemon:** per-call ref param for history list/read/diff ([#1909](https://github.com/yschimke/compose-ai-tools/issues/1909)) ([000b5c9](https://github.com/yschimke/compose-ai-tools/commit/000b5c90601e738bd83f2cb834a5b5366c7d9038))
* **vscode:** show a11y data-diff below the pixel diff in history panel ([#1914](https://github.com/yschimke/compose-ai-tools/issues/1914)) ([00dc550](https://github.com/yschimke/compose-ai-tools/commit/00dc5500c8eba4b3bf0a4c2be0711cb3853a8f16))
* **vscode:** show semantics data-diff below the pixel diff in history panel ([#1904](https://github.com/yschimke/compose-ai-tools/issues/1904)) ([e684701](https://github.com/yschimke/compose-ai-tools/commit/e6847012f9f4ecd9fd1f0261c7c419964b54703e))
* **vscode:** show theme data-diff below the pixel diff in history panel ([#1912](https://github.com/yschimke/compose-ai-tools/issues/1912)) ([6076e07](https://github.com/yschimke/compose-ai-tools/commit/6076e07861fad5a32f7a72c5c12cb633e899a693))
* **vscode:** view render history from a reporting branch in the History panel ([#1911](https://github.com/yschimke/compose-ai-tools/issues/1911)) ([c3ff762](https://github.com/yschimke/compose-ai-tools/commit/c3ff7628d3e0d0900b4a4b187ea61f4a9d7264d4))


### Bug Fixes

* **vscode:** correct history semantics-diff direction; add harness fixture ([#1907](https://github.com/yschimke/compose-ai-tools/issues/1907)) ([0e57c59](https://github.com/yschimke/compose-ai-tools/commit/0e57c597c05d51b3982ca16448ff60d2794267a1))

## [0.15.8](https://github.com/yschimke/compose-ai-tools/compare/v0.15.7...v0.15.8) (2026-06-15)


### Features

* **cli:** history diff --mode pixel (local pixel diff) ([#1896](https://github.com/yschimke/compose-ai-tools/issues/1896)) ([ffd6ae3](https://github.com/yschimke/compose-ai-tools/commit/ffd6ae3b4913d87a470d65e16376ef5810a72354))
* **cli:** history diff --mode semantics (local semantics diff) ([#1900](https://github.com/yschimke/compose-ai-tools/issues/1900)) ([f76eb1e](https://github.com/yschimke/compose-ai-tools/commit/f76eb1e6fcbf1a09091260428cbad7cf9fb5673f))
* **daemon:** emit resolved design tokens on compose/semantics nodes ([#1897](https://github.com/yschimke/compose-ai-tools/issues/1897)) ([#1901](https://github.com/yschimke/compose-ai-tools/issues/1901)) ([827a72e](https://github.com/yschimke/compose-ai-tools/commit/827a72ef389094c77f484f93ac3002553f8ade36))
* **vscode:** port semantics differ + history data-diff presenter ([#1902](https://github.com/yschimke/compose-ai-tools/issues/1902)) ([9990606](https://github.com/yschimke/compose-ai-tools/commit/99906066b7adb1cd87b7bfe6ea6aeae53fbc2811))

## [0.15.7](https://github.com/yschimke/compose-ai-tools/compare/v0.15.6...v0.15.7) (2026-06-15)


### Features

* **daemon:** implement history/diff pixel mode (H5) ([#1892](https://github.com/yschimke/compose-ai-tools/issues/1892)) ([10f1bca](https://github.com/yschimke/compose-ai-tools/commit/10f1bca04e3a230dae673efce6242b3e4daa5ecf))


### Bug Fixes

* **daemon:** write compose/semantics JSON sidecar on the desktop backend ([#1885](https://github.com/yschimke/compose-ai-tools/issues/1885) follow-up) ([#1891](https://github.com/yschimke/compose-ai-tools/issues/1891)) ([86e300a](https://github.com/yschimke/compose-ai-tools/commit/86e300a53e34ce9d5f80a289c0b992c0e0a9e5fc))

## [0.15.6](https://github.com/yschimke/compose-ai-tools/compare/v0.15.5...v0.15.6) (2026-06-15)


### Features

* **cli:** add history list/read/diff commands ([#1883](https://github.com/yschimke/compose-ai-tools/issues/1883)) ([90ce3a8](https://github.com/yschimke/compose-ai-tools/commit/90ce3a80ea304795e7994eb67112bb3625206039))
* **daemon:** enable history recording by default; gate VS Code history UI behind early-access flag ([#1884](https://github.com/yschimke/compose-ai-tools/issues/1884)) ([9c8be60](https://github.com/yschimke/compose-ai-tools/commit/9c8be60777a7f83af23ff7c40c9a0be5d089bca7))
* **daemon:** GitRefHistorySource WRITE_LOCAL — publish render history to the reporting branch ([#1878](https://github.com/yschimke/compose-ai-tools/issues/1878)) ([66c04ed](https://github.com/yschimke/compose-ai-tools/commit/66c04ed744e59b9e3b4e8ac0c5b64945d3d82f3d))


### Bug Fixes

* **cli:** keep bundle pack --with-semantics best-effort when daemon-start fails ([#1885](https://github.com/yschimke/compose-ai-tools/issues/1885)) ([#1886](https://github.com/yschimke/compose-ai-tools/issues/1886)) ([e312740](https://github.com/yschimke/compose-ai-tools/commit/e312740c3934ec1fcb6b98773ade6768bd3451c3))

## [0.15.5](https://github.com/yschimke/compose-ai-tools/compare/v0.15.4...v0.15.5) (2026-06-14)


### Features

* **daemon:** archive a11y + theme data products with each history entry ([#1876](https://github.com/yschimke/compose-ai-tools/issues/1876)) ([a96c05a](https://github.com/yschimke/compose-ai-tools/commit/a96c05afb04ea05c31cfef39a836bf8199bb0417))
* **schema:** publish versioned report schemas + reporting-branch contract ([#1874](https://github.com/yschimke/compose-ai-tools/issues/1874)) ([55c6924](https://github.com/yschimke/compose-ai-tools/commit/55c6924c47042a7ab99b7b082f4f55ef06e6f410))


### Bug Fixes

* **cli:** don't inject the plugin classpath into ancestors of a pre-applied module ([#1855](https://github.com/yschimke/compose-ai-tools/issues/1855)) ([#1877](https://github.com/yschimke/compose-ai-tools/issues/1877)) ([71cdba3](https://github.com/yschimke/compose-ai-tools/commit/71cdba3544806f7fe0f9f31e3f380538b2a9122e))

## [0.15.4](https://github.com/yschimke/compose-ai-tools/compare/v0.15.3...v0.15.4) (2026-06-14)


### Features

* **daemon:** a11y/hierarchy refs + structured target-resolution diagnostics ([#1784](https://github.com/yschimke/compose-ai-tools/issues/1784)) ([#1861](https://github.com/yschimke/compose-ai-tools/issues/1861)) ([f262fd3](https://github.com/yschimke/compose-ai-tools/commit/f262fd334fd03b18f2a4973587054d0ffe3149c4))
* **daemon:** classify renderFailed into a fine-grained error taxonomy ([#1856](https://github.com/yschimke/compose-ai-tools/issues/1856)) ([2ba930d](https://github.com/yschimke/compose-ai-tools/commit/2ba930db4eb14bbf75f78f0ca5941e254ea413e9))
* **daemon:** history/diff mode=SEMANTICS — pixel-free semantics regression diff ([#1785](https://github.com/yschimke/compose-ai-tools/issues/1785)) ([#1862](https://github.com/yschimke/compose-ai-tools/issues/1862)) ([43ce04f](https://github.com/yschimke/compose-ai-tools/commit/43ce04f2ae28f0e892b4c7fe7a90dba0c535e180))
* **mcp:** default record_preview to the frames observation ([#1865](https://github.com/yschimke/compose-ai-tools/issues/1865)) ([d1c4c5a](https://github.com/yschimke/compose-ai-tools/commit/d1c4c5a23ce6b73156e578889cbb9d2dfffc5fba))
* **mcp:** default render_preview to the structured-first observation ([#1857](https://github.com/yschimke/compose-ai-tools/issues/1857)) ([287e0e3](https://github.com/yschimke/compose-ai-tools/commit/287e0e35ba585c83001a1f12c6123a7fde24ce9d))
* **mcp:** infer probe assertions in record_preview emitTest codegen ([#1858](https://github.com/yschimke/compose-ai-tools/issues/1858)) ([a6e2686](https://github.com/yschimke/compose-ai-tools/commit/a6e2686df261f58b275779a4ac23338da94028ca))
* render-matrix CLI command + render_matrix contact sheet ([#1788](https://github.com/yschimke/compose-ai-tools/issues/1788)) ([#1864](https://github.com/yschimke/compose-ai-tools/issues/1864)) ([58742a9](https://github.com/yschimke/compose-ai-tools/commit/58742a945d4b16205731937cec7896c0abe07b63))


### Bug Fixes

* **plugin:** keep CLI detection working when a non-renderable KMP-Android module is present ([#1855](https://github.com/yschimke/compose-ai-tools/issues/1855)) ([#1863](https://github.com/yschimke/compose-ai-tools/issues/1863)) ([1c113d6](https://github.com/yschimke/compose-ai-tools/commit/1c113d6638a9b3afcc66e18d44483765d2448c11))

## [0.15.3](https://github.com/yschimke/compose-ai-tools/compare/v0.15.2...v0.15.3) (2026-06-14)


### Bug Fixes

* **plugin:** skip non-renderable KMP-Android modules instead of failing the desktop render ([#1853](https://github.com/yschimke/compose-ai-tools/issues/1853)) ([c4b85cb](https://github.com/yschimke/compose-ai-tools/commit/c4b85cb0418f281bce963ff765dc57704a099c4a))

## [0.15.2](https://github.com/yschimke/compose-ai-tools/compare/v0.15.1...v0.15.2) (2026-06-14)


### Features

* **cli:** auto-inject the preview plugin into KMP-Android library modules ([#1850](https://github.com/yschimke/compose-ai-tools/issues/1850)) ([ee4bee6](https://github.com/yschimke/compose-ai-tools/commit/ee4bee6700ed66d1576c1a19eae9591e66fcb2af))
* **theme:** populate compose/theme.consumers via resolved-value attribution ([#1848](https://github.com/yschimke/compose-ai-tools/issues/1848)) ([67d318d](https://github.com/yschimke/compose-ai-tools/commit/67d318ddd0e9278f247d0bcc1db79f8659b21d0b))


### Bug Fixes

* **plugin:** scope desktop lenient artifact views to the androidRuntimeClasspath fallback ([#1851](https://github.com/yschimke/compose-ai-tools/issues/1851)) ([d6cdd24](https://github.com/yschimke/compose-ai-tools/commit/d6cdd24e94b87cd81701bd0330e54dfa0ad174b7))

## [0.15.1](https://github.com/yschimke/compose-ai-tools/compare/v0.15.0...v0.15.1) (2026-06-13)


### Features

* **bundle:** carry per-preview semantics blob via pack --with-semantics ([#1845](https://github.com/yschimke/compose-ai-tools/issues/1845)) ([6e9c4b9](https://github.com/yschimke/compose-ai-tools/commit/6e9c4b9e465d124bd95b51b0f2d61255560bdb66))
* **bundle:** optionally carry cover-preview data-extension reports (schema v7) ([#1840](https://github.com/yschimke/compose-ai-tools/issues/1840)) ([f8d083d](https://github.com/yschimke/compose-ai-tools/commit/f8d083d8718a0b13da674e29be2d39382f5a1a1a))
* **cli:** add bundle embed — web embed ("js bundle") from a preview bundle ([#1837](https://github.com/yschimke/compose-ai-tools/issues/1837)) ([a1f5c7f](https://github.com/yschimke/compose-ai-tools/commit/a1f5c7f48f15de664332f2899ee182cb3aaa943a))
* **cli:** bundle embed --in-bundle — embed web resources into the bundle zip ([#1839](https://github.com/yschimke/compose-ai-tools/issues/1839)) ([50db98b](https://github.com/yschimke/compose-ai-tools/commit/50db98bc2c1537bc0098b29c8bb3691b5eb0f55b))


### Bug Fixes

* **desktop:** resolve renderer in the consumer's graph so Skiko stays coherent ([#1846](https://github.com/yschimke/compose-ai-tools/issues/1846)) ([f8c1264](https://github.com/yschimke/compose-ai-tools/commit/f8c12646772e7f41882e503d5a401b648f415093))

## [0.15.0](https://github.com/yschimke/compose-ai-tools/compare/v0.14.1...v0.15.0) (2026-06-12)


### ⚠ BREAKING CHANGES

* **cli:** unify share-gist + publish-images into share-preview ([#1833](https://github.com/yschimke/compose-ai-tools/issues/1833))

### Features

* **cli:** unify share-gist + publish-images into share-preview ([#1833](https://github.com/yschimke/compose-ai-tools/issues/1833)) ([0e440bb](https://github.com/yschimke/compose-ai-tools/commit/0e440bbce0ae9bf834fa1e1f12c561a6da88f071))


### Bug Fixes

* **ci:** rename reusable codex-review secret off the reserved GITHUB_ prefix ([#1832](https://github.com/yschimke/compose-ai-tools/issues/1832)) ([953e776](https://github.com/yschimke/compose-ai-tools/commit/953e776913014460ca35e2f64b0b2cd3cd11ddaf))
* **mcp:** install request handlers before connecting the stdio transport ([#1834](https://github.com/yschimke/compose-ai-tools/issues/1834)) ([36659e3](https://github.com/yschimke/compose-ai-tools/commit/36659e30af1e0b8be4b4b4b35ee9ee81b71190ab))

## [0.14.1](https://github.com/yschimke/compose-ai-tools/compare/v0.14.0...v0.14.1) (2026-06-11)


### Bug Fixes

* honour @Preview(fontScale) on the Desktop/CMP pipeline ([#1830](https://github.com/yschimke/compose-ai-tools/issues/1830)) ([071a50e](https://github.com/yschimke/compose-ai-tools/commit/071a50e4976cb2df13765b5099d2260e37c8bf37))
* **resources:** skip themed adaptive-icon captures when no `<monochrome>` layer ([#1829](https://github.com/yschimke/compose-ai-tools/issues/1829)) ([147bc36](https://github.com/yschimke/compose-ai-tools/commit/147bc3683b8de435be97a88bf45ff78cd02ab865))

## [0.14.0](https://github.com/yschimke/compose-ai-tools/compare/v0.13.4...v0.14.0) (2026-06-11)


### ⚠ BREAKING CHANGES

* bump to 0.14.0 ([#1822](https://github.com/yschimke/compose-ai-tools/issues/1822))

### Features

* 2D semantics wireframe data product + unified spatial-semantics-tree foundation ([#1755](https://github.com/yschimke/compose-ai-tools/issues/1755)) ([6f32748](https://github.com/yschimke/compose-ai-tools/commit/6f327481d2f6d4dc5678d9af932158ef65fe29c4))
* bump to 0.14.0 ([#1822](https://github.com/yschimke/compose-ai-tools/issues/1822)) ([1baa880](https://github.com/yschimke/compose-ai-tools/commit/1baa880bf86edf4880f80f49fe014922020fb39c))
* **ci:** capture the 3D spatial webview from the real XR render ([#1826](https://github.com/yschimke/compose-ai-tools/issues/1826)) ([4eef208](https://github.com/yschimke/compose-ai-tools/commit/4eef20814b417ba46c0c976ef690b8cd8c25784d))
* **cli:** diff-semantics command — pixel-free semantics regression diff ([#1803](https://github.com/yschimke/compose-ai-tools/issues/1803)) ([bb2a9f8](https://github.com/yschimke/compose-ai-tools/commit/bb2a9f8c110a0dc4cc6e9754828e7d7ab89a4422))
* compose/spatial-semantics data product (Level-2 XR a11y capture) ([#1816](https://github.com/yschimke/compose-ai-tools/issues/1816)) ([23ea818](https://github.com/yschimke/compose-ai-tools/commit/23ea8187fbc9b10c2253e5de618692238cba462f))
* **daemon:** classify renderFailed into a typed kind + fix hint ([#1789](https://github.com/yschimke/compose-ai-tools/issues/1789)) ([#1809](https://github.com/yschimke/compose-ai-tools/issues/1809)) ([a8d35be](https://github.com/yschimke/compose-ai-tools/commit/a8d35bee81833c40fda518659bf23e6f718a62ea))
* **daemon:** resolve interactive input targets by semantic ref (desktop) ([#1793](https://github.com/yschimke/compose-ai-tools/issues/1793)) ([0b4b19c](https://github.com/yschimke/compose-ai-tools/commit/0b4b19c90c9f4be861b128a58d7b0e1d4282b141))
* **daemon:** sandbox-side semantic-target dispatch on Android ([#1784](https://github.com/yschimke/compose-ai-tools/issues/1784)) ([#1801](https://github.com/yschimke/compose-ai-tools/issues/1801)) ([fdf5fef](https://github.com/yschimke/compose-ai-tools/commit/fdf5fefa1614b9caf57a9d7bdd88906dbd00a538))
* **daemon:** semantic-target dispatch for record_preview script events (desktop) ([#1798](https://github.com/yschimke/compose-ai-tools/issues/1798)) ([7ef2e44](https://github.com/yschimke/compose-ai-tools/commit/7ef2e44f993cbaa39358aa4b27ff2bb17ee5f2cc))
* **lottie:** discover + render Android-module Lottie assets via the desktop path ([#1771](https://github.com/yschimke/compose-ai-tools/issues/1771)) ([80eea2f](https://github.com/yschimke/compose-ai-tools/commit/80eea2f917fa67a8b901ff700dd5220c39f903e2))
* **lottie:** interactive timeline — progress scrub + animation/lottie metadata product ([#1769](https://github.com/yschimke/compose-ai-tools/issues/1769)) ([152fcfd](https://github.com/yschimke/compose-ai-tools/commit/152fcfdfb145ab27b59069890a36c353911bad1d))
* **lottie:** live timeline scrub via held session (interactive/setLottie) ([#1781](https://github.com/yschimke/compose-ai-tools/issues/1781)) ([e297b09](https://github.com/yschimke/compose-ai-tools/commit/e297b09ceb83ce2ceaa1d3dd352bad8e10d66931))
* **lottie:** render Lottie animation previews on the Desktop backend ([#1761](https://github.com/yschimke/compose-ai-tools/issues/1761)) ([8ab25d6](https://github.com/yschimke/compose-ai-tools/commit/8ab25d690e6845154da2e9f965aeea3f774f1d61))
* **mcp:** crop render_preview to a single element by semantic ref ([#1819](https://github.com/yschimke/compose-ai-tools/issues/1819)) ([ba29080](https://github.com/yschimke/compose-ai-tools/commit/ba29080eb8f4604ccbf7b5bdbf1fa01afc7daf48))
* **mcp:** diff_semantics tool — pixel-free semantics regression signal ([#1795](https://github.com/yschimke/compose-ai-tools/issues/1795)) ([1c25dda](https://github.com/yschimke/compose-ai-tools/commit/1c25dda348afb46207092e27d32904b7a90e6eeb))
* **mcp:** emit a Compose UI test from a record_preview interaction ([#1786](https://github.com/yschimke/compose-ai-tools/issues/1786)) ([#1807](https://github.com/yschimke/compose-ai-tools/issues/1807)) ([5330637](https://github.com/yschimke/compose-ai-tools/commit/5330637a2fda1d7c05bd2713c95496fa90b29376))
* **mcp:** render_matrix across device/locale/uiMode/fontScale ([#1788](https://github.com/yschimke/compose-ai-tools/issues/1788)) ([#1814](https://github.com/yschimke/compose-ai-tools/issues/1814)) ([bd7a556](https://github.com/yschimke/compose-ai-tools/commit/bd7a556578fe2896ce5da3421f4dcd80fd23fb41))
* **mcp:** token-frugal render_preview observe modes ([#1787](https://github.com/yschimke/compose-ai-tools/issues/1787)) ([#1812](https://github.com/yschimke/compose-ai-tools/issues/1812)) ([6703836](https://github.com/yschimke/compose-ai-tools/commit/670383682a3468c172e05605b20cfff4fada7b81))
* **panel:** gate bundle chips to the focused preview's type ([#1783](https://github.com/yschimke/compose-ai-tools/issues/1783)) ([e6de776](https://github.com/yschimke/compose-ai-tools/commit/e6de776e78375b0de27ad4283310f4ae0f913eb4))
* render JetStream XR subspace previews in CI, auto-enabled from androidx.xr.compose ([#1766](https://github.com/yschimke/compose-ai-tools/issues/1766)) ([6a5f124](https://github.com/yschimke/compose-ai-tools/commit/6a5f124b42f5869832db59f821b286c0003556da))
* **semantics:** stable node refs, target resolver, and tree diff engine ([#1790](https://github.com/yschimke/compose-ai-tools/issues/1790)) ([98feb25](https://github.com/yschimke/compose-ai-tools/commit/98feb259d78117233f21cc3f2941f61a8d8ff90a))
* **vscode:** add Google Fonts browser with live customiser ([#1757](https://github.com/yschimke/compose-ai-tools/issues/1757)) ([50b1e83](https://github.com/yschimke/compose-ai-tools/commit/50b1e83c26f7098f99d714b6bfb4580e1453aaf6))
* **vscode:** feed real XR renders into the 3D spatial view ([#1821](https://github.com/yschimke/compose-ai-tools/issues/1821)) ([9e0ae1c](https://github.com/yschimke/compose-ai-tools/commit/9e0ae1c06e62584ef7154f7c5eaf522abcac0053))
* **vscode:** generate downloadable Google Fonts snippet with variations ([#1762](https://github.com/yschimke/compose-ai-tools/issues/1762)) ([d303156](https://github.com/yschimke/compose-ai-tools/commit/d303156b66bf9fc86c7843d7262ab8bbf8622d3f))
* **vscode:** Lottie timeline scrubber slider in the preview panel ([#1778](https://github.com/yschimke/compose-ai-tools/issues/1778)) ([006dd98](https://github.com/yschimke/compose-ai-tools/commit/006dd98f357857e5920c8a7d3891cd4d9501c170))
* **vscode:** overlay per-panel wireframes in the spatial view ([#1818](https://github.com/yschimke/compose-ai-tools/issues/1818)) ([1779f7d](https://github.com/yschimke/compose-ai-tools/commit/1779f7d3e6fa9fb9af323fe729f991e927e47643))
* **vscode:** render wireframe-overlay panel faces from SpatialSemanticsTree ([#1763](https://github.com/yschimke/compose-ai-tools/issues/1763)) ([7d26db3](https://github.com/yschimke/compose-ai-tools/commit/7d26db339ac163781e47265d013a95e982377ee7))
* **vscode:** track the focused preview in the 3D spatial view ([#1825](https://github.com/yschimke/compose-ai-tools/issues/1825)) ([c718929](https://github.com/yschimke/compose-ai-tools/commit/c718929443fdc8a33ef9cbc95434c3a3e3c092b6))
* **xr:** daemon JSON-RPC surface for the XR render service ([#1792](https://github.com/yschimke/compose-ai-tools/issues/1792)) ([e10329c](https://github.com/yschimke/compose-ai-tools/commit/e10329cbdd0f4205898317cafc1775179ad29912))
* **xr:** gate XR frames through FrameStreamRegistry (visibility/fps/dedup) ([#1800](https://github.com/yschimke/compose-ai-tools/issues/1800)) ([c6b3e0d](https://github.com/yschimke/compose-ai-tools/commit/c6b3e0d297c26369d9ec7e978a6f118a114f43ab))
* **xr:** JVM client for the native XR render server (daemon-fronting groundwork) ([#1780](https://github.com/yschimke/compose-ai-tools/issues/1780)) ([026348d](https://github.com/yschimke/compose-ai-tools/commit/026348d48aa21768b27468e56d2ba8a4588491e4))
* **xr:** keep xr/structure in step with updatePanels deltas ([#1808](https://github.com/yschimke/compose-ai-tools/issues/1808)) ([e85260d](https://github.com/yschimke/compose-ai-tools/commit/e85260d39f71293667c9de8f33d50d0d27127036))
* **xr:** multi-session support in the native render server ([#1802](https://github.com/yschimke/compose-ai-tools/issues/1802)) ([7bb8be4](https://github.com/yschimke/compose-ai-tools/commit/7bb8be4a758f1c4f5fd8ebba744fce975f4ed766))
* **xr:** multiplex XR sessions over one shared process (JVM side) ([#1805](https://github.com/yschimke/compose-ai-tools/issues/1805)) ([b51aefc](https://github.com/yschimke/compose-ai-tools/commit/b51aefcd1fce46fc97da3938edbfdf9533960cbb))
* **xr:** re-spawn the shared XR process after it dies ([#1810](https://github.com/yschimke/compose-ai-tools/issues/1810)) ([51db6f1](https://github.com/yschimke/compose-ai-tools/commit/51db6f1133f7ac986e9d5526c96cfadce8700891))
* **xr:** single-source SpatialScene codegen + a per-frame Filament render server ([#1779](https://github.com/yschimke/compose-ai-tools/issues/1779)) ([12cc6cc](https://github.com/yschimke/compose-ai-tools/commit/12cc6cc6320baeb71fdcc0e80993b93237f1c5c9))
* **xr:** supervise + resolve the native XR render server from the JVM ([#1782](https://github.com/yschimke/compose-ai-tools/issues/1782)) ([a51156c](https://github.com/yschimke/compose-ai-tools/commit/a51156cf8bb97a5cfc6ea7376112c2ed34e35f78))
* **xr:** wire the native XR render server into the desktop daemon ([#1797](https://github.com/yschimke/compose-ai-tools/issues/1797)) ([a992976](https://github.com/yschimke/compose-ai-tools/commit/a992976cba77687a03dc686c76bd7c3dbcc2fd41))
* **xr:** XR session manager + fakeable render-server handle (daemon-fronting core) ([#1791](https://github.com/yschimke/compose-ai-tools/issues/1791)) ([4894855](https://github.com/yschimke/compose-ai-tools/commit/4894855ac1a74a52f2d908bbc15658e69cabf1e4))
* **xr:** xr/structure data product (held panel tree + poses) ([#1806](https://github.com/yschimke/compose-ai-tools/issues/1806)) ([57bb87d](https://github.com/yschimke/compose-ai-tools/commit/57bb87df179e6fbde610e611c948a5b6298d5014))


### Bug Fixes

* **deps:** update gradle minor/patch ([#1827](https://github.com/yschimke/compose-ai-tools/issues/1827)) ([b9a143c](https://github.com/yschimke/compose-ai-tools/commit/b9a143c557f298e90370295c82ffbf7861deecb5))
* **gradle-plugin:** make desktop classpath guard config-cache serializable ([#1799](https://github.com/yschimke/compose-ai-tools/issues/1799)) ([3e28510](https://github.com/yschimke/compose-ai-tools/commit/3e28510852125e65d7aa305a46799f68fc6742fa))
* **integration:** drop duplicate java_version key on the jetstream-xr cell ([#1764](https://github.com/yschimke/compose-ai-tools/issues/1764)) ([5ea0f1f](https://github.com/yschimke/compose-ai-tools/commit/5ea0f1f09b5c5e0ddc590cac601d91277fecb1d6))
* **mcp:** emit the real composable name in record_preview generated tests ([#1813](https://github.com/yschimke/compose-ai-tools/issues/1813)) ([4ebdb21](https://github.com/yschimke/compose-ai-tools/commit/4ebdb214db9e31c501d8ba1f59d31d82d9625e3b))
* publish :renderer-xr so external consumers can resolve the XR render path ([#1768](https://github.com/yschimke/compose-ai-tools/issues/1768)) ([cf0201e](https://github.com/yschimke/compose-ai-tools/commit/cf0201e6c5251ef34d6d222331badbb37e1e1985))
* **vscode-preview:** wait for webfonts before snapshotting ([#1824](https://github.com/yschimke/compose-ai-tools/issues/1824)) ([8e9f547](https://github.com/yschimke/compose-ai-tools/commit/8e9f547c932d601b07ac03b8ec87d7445d1ce80f))
* **xr:** add renderer-xr-client to the bundle-render e2e publish set ([#1794](https://github.com/yschimke/compose-ai-tools/issues/1794)) ([1f60175](https://github.com/yschimke/compose-ai-tools/commit/1f60175bd21d7d545676bcd7549160126f16d66f))

## [0.13.4](https://github.com/yschimke/compose-ai-tools/compare/v0.13.3...v0.13.4) (2026-06-04)


### Features

* **doctor:** flag transitive libraries whose minSdk exceeds the module ([#1750](https://github.com/yschimke/compose-ai-tools/issues/1750)) ([3c51b72](https://github.com/yschimke/compose-ai-tools/commit/3c51b72637f6d18499c6891080685289910acf4d))
* **spatial-viewer:** render the gradient backdrop in the WebGL viewer ([#1749](https://github.com/yschimke/compose-ai-tools/issues/1749)) ([bd128a1](https://github.com/yschimke/compose-ai-tools/commit/bd128a1e0bf5422851ebe961dd44541728466a63))
* **xr-composite:** rounded panels, soft shadow, edge rim + tighter framing ([#1745](https://github.com/yschimke/compose-ai-tools/issues/1745)) ([c088506](https://github.com/yschimke/compose-ai-tools/commit/c08850633c7bae17218b55b7dbeed456c5d83303))
* **xr-spatial:** reference-grid repro + pose-modifier previews & test ([#1751](https://github.com/yschimke/compose-ai-tools/issues/1751)) ([b78d804](https://github.com/yschimke/compose-ai-tools/commit/b78d8042e7bfad9d0c0fec161641cea06dbd3788))
* **xr:** bake rotateToLookAtUser billboarding offline via fake perception runtime ([#1752](https://github.com/yschimke/compose-ai-tools/issues/1752)) ([1991842](https://github.com/yschimke/compose-ai-tools/commit/1991842bf41dd3b9ddc2a573a531dd0241e8357d))


### Bug Fixes

* **cli:** don't fail --missing-renders on non-PNG preview kinds; list offenders ([#1748](https://github.com/yschimke/compose-ai-tools/issues/1748)) ([ad879ef](https://github.com/yschimke/compose-ai-tools/commit/ad879ef1c698920ef44b650a44a6161d265af724))
* **samples:** pin android-alpha activity-compose &gt;= 1.11 for navigationevent ([#1744](https://github.com/yschimke/compose-ai-tools/issues/1744)) ([1937dbe](https://github.com/yschimke/compose-ai-tools/commit/1937dbe52518358eb0763282b860dec3391cf97d))
* **xr:** seed rotateToLookAtUser head pose in the render task + guard NaN poses ([#1753](https://github.com/yschimke/compose-ai-tools/issues/1753)) ([eac2a37](https://github.com/yschimke/compose-ai-tools/commit/eac2a37c0c613fcacccf12b9f9758010e49728f9))

## [0.13.3](https://github.com/yschimke/compose-ai-tools/compare/v0.13.2...v0.13.3) (2026-06-04)


### Features

* **samples:** showcase spatial Compose previews + gradient composite backdrop ([#1741](https://github.com/yschimke/compose-ai-tools/issues/1741)) ([ed4b7fd](https://github.com/yschimke/compose-ai-tools/commit/ed4b7fd4781b2945b5189ea708e4e0529a5f78e6))


### Bug Fixes

* **daemon:** render private @Preview composables via setAccessible ([#1739](https://github.com/yschimke/compose-ai-tools/issues/1739)) ([bcd255a](https://github.com/yschimke/compose-ai-tools/commit/bcd255a414c836de646d8f20fb040114eb46513a))

## [0.13.2](https://github.com/yschimke/compose-ai-tools/compare/v0.13.1...v0.13.2) (2026-06-03)


### Features

* **cli:** auto-provision the xr-composite binary from releases ([#1732](https://github.com/yschimke/compose-ai-tools/issues/1732)) ([99aa4d0](https://github.com/yschimke/compose-ai-tools/commit/99aa4d0e4b8b4960e21aa8df2cc46f9a3077b3dc))
* **plugin:** show XR subspace composites in the preview manifest ([#1730](https://github.com/yschimke/compose-ai-tools/issues/1730)) ([2acccb8](https://github.com/yschimke/compose-ai-tools/commit/2acccb8169e586e5680d9744fe70782aaf9c59bd))
* **xr-composite:** native Filament tool to bake spatial scenes to a composite PNG ([#1725](https://github.com/yschimke/compose-ai-tools/issues/1725)) ([abe831f](https://github.com/yschimke/compose-ai-tools/commit/abe831f943519925e2ed6ace3930d3b93a3c7e2b))


### Bug Fixes

* **cli:** re-provision partial xr-composite caches instead of trusting them ([#1733](https://github.com/yschimke/compose-ai-tools/issues/1733)) ([278e620](https://github.com/yschimke/compose-ai-tools/commit/278e620b5b6f217f99c3c8b94cf385d5a768cb7d))
* **xr-composite:** statically link libc++ so the Linux binary is self-contained ([#1737](https://github.com/yschimke/compose-ai-tools/issues/1737)) ([23d2fc2](https://github.com/yschimke/compose-ai-tools/commit/23d2fc295226f28fb3d458fe82262a7ca79439ed))


### Performance Improvements

* **daemon:** keep the manifest snapshot across identity saves ([#1724](https://github.com/yschimke/compose-ai-tools/issues/1724)) ([75b62e1](https://github.com/yschimke/compose-ai-tools/commit/75b62e1d6494c73cfee9dcc1807a21fd6be311af))

## [0.13.1](https://github.com/yschimke/compose-ai-tools/compare/v0.13.0...v0.13.1) (2026-06-03)


### Features

* **plugin:** composePreviewRenderXr — render @XrSubspacePreview to scene.json ([#1720](https://github.com/yschimke/compose-ai-tools/issues/1720)) ([902dd88](https://github.com/yschimke/compose-ai-tools/commit/902dd883bab61f3c4f59c4334a7bcb9958822af6))
* **renderer-xr:** capture real per-panel textures for XR previews ([#1722](https://github.com/yschimke/compose-ai-tools/issues/1722)) ([b93bcfd](https://github.com/yschimke/compose-ai-tools/commit/b93bcfd699a5527f912173fa9a61066a3412da35))


### Bug Fixes

* **daemon:** re-render on save when the manifest cache was invalidated ([#1723](https://github.com/yschimke/compose-ai-tools/issues/1723)) ([f390bc5](https://github.com/yschimke/compose-ai-tools/commit/f390bc5061eaff41ef8bafb94d849a6c19c77f86))

## [0.13.0](https://github.com/yschimke/compose-ai-tools/compare/v0.12.5...v0.13.0) (2026-06-03)


### ⚠ BREAKING CHANGES

* adopt Okio + Dispatchers.IO for file IO across the codebase ([#1699](https://github.com/yschimke/compose-ai-tools/issues/1699))

### Features

* add progress bar feedback for daemon save path ([#1712](https://github.com/yschimke/compose-ai-tools/issues/1712)) ([a743874](https://github.com/yschimke/compose-ai-tools/commit/a743874179e01663fa5da0ca093c91f4785f8ef9))
* add Shift+click multi-stream mode for grid card previews ([#1711](https://github.com/yschimke/compose-ai-tools/issues/1711)) ([251aa7a](https://github.com/yschimke/compose-ai-tools/commit/251aa7a92bc475bb59075927b85b4f0f2230c9c1))
* adopt Okio + Dispatchers.IO for file IO across the codebase ([#1699](https://github.com/yschimke/compose-ai-tools/issues/1699)) ([2256a15](https://github.com/yschimke/compose-ai-tools/commit/2256a157f240c8b795442d4b3fff59a74af6a761))
* **bundle:** carry Android resources for protolayout tile replay (schema v6) ([#1692](https://github.com/yschimke/compose-ai-tools/issues/1692)) ([e0f747d](https://github.com/yschimke/compose-ai-tools/commit/e0f747dec51706a510a82dfc8b63774937367d11))
* **plugin:** discover @XrSubspacePreview as PreviewKind.XR_SUBSPACE ([#1707](https://github.com/yschimke/compose-ai-tools/issues/1707)) ([038a7ae](https://github.com/yschimke/compose-ai-tools/commit/038a7aee3ef72b4e026f60e3224b2a13d900ad9a))
* **renderer-xr:** auto-enumerate tagged subspace panels (recordAll) ([#1706](https://github.com/yschimke/compose-ai-tools/issues/1706)) ([bd21aa9](https://github.com/yschimke/compose-ai-tools/commit/bd21aa992a43bc5226c83c5effa3542fcc3368db))
* **renderer-xr:** manifest-driven XR render entry (XrSubspaceRenderTest) ([#1716](https://github.com/yschimke/compose-ai-tools/issues/1716)) ([cdec78b](https://github.com/yschimke/compose-ai-tools/commit/cdec78bf39e763e3bf523d4ba43ab5b795db7a40))
* **renderer-xr:** offline SpatialScene producer (poses + textures + scene.json) ([#1703](https://github.com/yschimke/compose-ai-tools/issues/1703)) ([86eac21](https://github.com/yschimke/compose-ai-tools/commit/86eac2120ddbd06ed296ec0b230cd6ee3375adb4))
* **renderer-xr:** render an @XrSubspacePreview to scene.json ([#1710](https://github.com/yschimke/compose-ai-tools/issues/1710)) ([5593aca](https://github.com/yschimke/compose-ai-tools/commit/5593aca322e8cbb9cfb19a0c44a46d44c35a7e77))
* **samples:** add XR spatial preview sample (androidx.xr.compose) ([#1690](https://github.com/yschimke/compose-ai-tools/issues/1690)) ([c929fe2](https://github.com/yschimke/compose-ai-tools/commit/c929fe20b1bf74ee98479c4636b7637837bb4c33))
* **vscode:** 3D spatial-layout viewer with 2D⇄3D panel toggle ([#1704](https://github.com/yschimke/compose-ai-tools/issues/1704)) ([6ddeabf](https://github.com/yschimke/compose-ai-tools/commit/6ddeabf0b3534394aa00631a8218123a929f0810))
* **vscode:** define SpatialScene contract for the 3D spatial-layout viewer ([#1697](https://github.com/yschimke/compose-ai-tools/issues/1697)) ([759e1fe](https://github.com/yschimke/compose-ai-tools/commit/759e1fe1b92c379d0cdc88b8cfcd070b2c159283))


### Bug Fixes

* **bundle:** walk unit-test config as a file tree so protolayout tile replay carries its resources ([#1694](https://github.com/yschimke/compose-ai-tools/issues/1694)) ([60533ea](https://github.com/yschimke/compose-ai-tools/commit/60533ea18ce153af6913e7e4ce595be6e3d34fd4))
* **daemon:** use the stub Application on the daemon render path ([#1718](https://github.com/yschimke/compose-ai-tools/issues/1718)) ([35c38d1](https://github.com/yschimke/compose-ai-tools/commit/35c38d1ce2798237a575b602b0da427aa816379b))
* **plugin:** reject parameterized @XrSubspacePreview at discovery ([#1714](https://github.com/yschimke/compose-ai-tools/issues/1714)) ([8a375d7](https://github.com/yschimke/compose-ai-tools/commit/8a375d7280ff884df07ac8e382195df9b75403ad))
* render Android bundle-daemon classic previews end-to-end ([#1685](https://github.com/yschimke/compose-ai-tools/issues/1685) wiring + [#1687](https://github.com/yschimke/compose-ai-tools/issues/1687)) ([#1689](https://github.com/yschimke/compose-ai-tools/issues/1689)) ([62a6b30](https://github.com/yschimke/compose-ai-tools/commit/62a6b30b3bd36344ccf06d650b6c872116ddcf35))
* stamp preview card id synchronously for layout sizing ([#1715](https://github.com/yschimke/compose-ai-tools/issues/1715)) ([34a6b53](https://github.com/yschimke/compose-ai-tools/commit/34a6b537f99bcddb68fed049a49fdbed92dfdb02))
* **vscode:** collapse duplicate live-mode stop button in focus toolbar ([#1709](https://github.com/yschimke/compose-ai-tools/issues/1709)) ([1e6b627](https://github.com/yschimke/compose-ai-tools/commit/1e6b6271464848dc761340d833d6eed567616b51))
* **vscode:** don't re-enter focus mode on IDE restart; leave it on editor switch ([#1705](https://github.com/yschimke/compose-ai-tools/issues/1705)) ([c5b0611](https://github.com/yschimke/compose-ai-tools/commit/c5b06110d860674b017220a438165064a1861e16))
* **vscode:** reject unsupported SpatialScene versions in isSpatialScene ([#1700](https://github.com/yschimke/compose-ai-tools/issues/1700)) ([22991c6](https://github.com/yschimke/compose-ai-tools/commit/22991c6e501b7ef658c278f4eb52f5d6ea1f90d1))

## [0.12.5](https://github.com/yschimke/compose-ai-tools/compare/v0.12.4...v0.12.5) (2026-06-02)


### Features

* **vscode:** open Android preview bundles in the bundle viewer ([#1679](https://github.com/yschimke/compose-ai-tools/issues/1679)) ([9c33c0b](https://github.com/yschimke/compose-ai-tools/commit/9c33c0bad7597f31d63a5db9f1b5e5750ef94842))


### Bug Fixes

* **cli:** ship the Android daemon runtime as a separate on-demand archive ([#1685](https://github.com/yschimke/compose-ai-tools/issues/1685)) ([f3d7d15](https://github.com/yschimke/compose-ai-tools/commit/f3d7d151d1ae5927cb15586110aacda01e472fef))
* **daemon:** Android bundle-daemon E2E render-path bug ([#1687](https://github.com/yschimke/compose-ai-tools/issues/1687)) ([#1688](https://github.com/yschimke/compose-ai-tools/issues/1688)) ([e14af60](https://github.com/yschimke/compose-ai-tools/commit/e14af60aaaf3a57d54d8851a0da834443cc87ae3))
* **plugin:** tolerate unselectable runtime deps when probing aar/jar packaging ([#1686](https://github.com/yschimke/compose-ai-tools/issues/1686)) ([32ca9dc](https://github.com/yschimke/compose-ai-tools/commit/32ca9dc81821be1e3687b2e861434eb8299f9ea7))

## [0.12.4](https://github.com/yschimke/compose-ai-tools/compare/v0.12.3...v0.12.4) (2026-06-02)


### Bug Fixes

* **cli:** make stageDaemonAndroidLibs build script compile (java.io.File shadowing) ([#1680](https://github.com/yschimke/compose-ai-tools/issues/1680)) ([5d2383b](https://github.com/yschimke/compose-ai-tools/commit/5d2383b60292f0ae42ee2f33d50cf74f8282f3fe))

## [0.12.3](https://github.com/yschimke/compose-ai-tools/compare/v0.12.2...v0.12.3) (2026-06-02)


### Features

* **cli:** ship lib-daemon-android + e2e for Android bundle-daemon IR render ([#1677](https://github.com/yschimke/compose-ai-tools/issues/1677)) ([dc3c2d2](https://github.com/yschimke/compose-ai-tools/commit/dc3c2d2dcc484bc12543485547bb1dc648607b79))


### Bug Fixes

* **release:** disable Isolated Projects for the Windows MSI viewer build ([#1676](https://github.com/yschimke/compose-ai-tools/issues/1676)) ([8b025e3](https://github.com/yschimke/compose-ai-tools/commit/8b025e3f118906ba7a76d53bd78f25518c6cde42))

## [0.12.2](https://github.com/yschimke/compose-ai-tools/compare/v0.12.1...v0.12.2) (2026-06-02)


### Features

* **bundle-viewer:** native installers (.deb/.dmg/.msi) for non-Java recipients ([#1655](https://github.com/yschimke/compose-ai-tools/issues/1655)) ([d30bcd0](https://github.com/yschimke/compose-ai-tools/commit/d30bcd0dced68223dc5563eb7e10dae4a5d3a8e9))
* **bundle-viewer:** network coordinate resolution at viewer parity ([#1644](https://github.com/yschimke/compose-ai-tools/issues/1644)) ([de20406](https://github.com/yschimke/compose-ai-tools/commit/de2040663842c7d30cc8835a99b667695840a56f))
* **bundle-viewer:** ship the viewer as a runnable Compose uber jar ([#1650](https://github.com/yschimke/compose-ai-tools/issues/1650)) ([ca20581](https://github.com/yschimke/compose-ai-tools/commit/ca20581407b171ead60694cbf0c339ef19ba28ea))
* **bundle:** Android re-render player foundation (Phase 1) ([#1651](https://github.com/yschimke/compose-ai-tools/issues/1651)) ([e558f22](https://github.com/yschimke/compose-ai-tools/commit/e558f22119c4fff41fd75de20261b5af417ff78b))
* **bundle:** bake every preview into the bundle + TUI ASCII dump ([#1627](https://github.com/yschimke/compose-ai-tools/issues/1627)) ([4accaac](https://github.com/yschimke/compose-ai-tools/commit/4accaaccb264a73af60d8b31302da8ae193f0668))
* **bundle:** branch bundle daemon on backend (Android Robolectric daemon) ([#1656](https://github.com/yschimke/compose-ai-tools/issues/1656)) ([9ee989d](https://github.com/yschimke/compose-ai-tools/commit/9ee989dc3e697ac0da3cc348824425ac159dca73))
* **bundle:** carry IR-preview deps + pass ir/ to the daemon (Piece B foundation) ([#1665](https://github.com/yschimke/compose-ai-tools/issues/1665)) ([e63fc64](https://github.com/yschimke/compose-ai-tools/commit/e63fc6456094a29f94edf854cd7eaeaf0d7f162b))
* **bundle:** emit protolayout IR sidecar at render time (Piece A, tiles) ([#1662](https://github.com/yschimke/compose-ai-tools/issues/1662)) ([9c3bd82](https://github.com/yschimke/compose-ai-tools/commit/9c3bd82403192f1ab48972909df7a59afe94522b))
* **bundle:** emit Remote Compose IR sidecar at render time (Piece A) ([#1659](https://github.com/yschimke/compose-ai-tools/issues/1659)) ([809a7b3](https://github.com/yschimke/compose-ai-tools/commit/809a7b30e2a94194a1e291274b2f152dd40a5a67))
* **bundle:** open bundles from a URL, not just a local path ([#1636](https://github.com/yschimke/compose-ai-tools/issues/1636)) ([bb9488b](https://github.com/yschimke/compose-ai-tools/commit/bb9488b6795f560dd6c8861fcfb8c1f5f7bb9b32))
* **bundle:** register composePreviewBundle on the Android path ([#1645](https://github.com/yschimke/compose-ai-tools/issues/1645)) ([2fe0837](https://github.com/yschimke/compose-ai-tools/commit/2fe08373e5df30ff2cb0ece10df34f39265fc8b6))
* **bundle:** replay protolayout IR in the Android daemon (Piece B, tiles) ([#1666](https://github.com/yschimke/compose-ai-tools/issues/1666)) ([9042237](https://github.com/yschimke/compose-ai-tools/commit/90422379effe81122a130b8b9746c447e9b614ff))
* **bundle:** replay Remote Compose IR in the Android daemon (Piece B, RC) ([#1672](https://github.com/yschimke/compose-ai-tools/issues/1672)) ([01306ac](https://github.com/yschimke/compose-ai-tools/commit/01306acbf5c79803dde2399277480a86e198c69d))
* **bundle:** replay remote-compose & protolayout previews from a captured IR (schema v5) ([#1654](https://github.com/yschimke/compose-ai-tools/issues/1654)) ([1ec9382](https://github.com/yschimke/compose-ai-tools/commit/1ec93821275ac3627dbaed171ddd443ded2b2fbc))
* **bundle:** resolve maven coordinates in the daemon + desktop viewer ([#1639](https://github.com/yschimke/compose-ai-tools/issues/1639)) ([a954294](https://github.com/yschimke/compose-ai-tools/commit/a95429441e8ca27aa8d777f1506214409208c25f))
* **bundle:** schema v3 — Embedded classpath kind + producer/resolution fields ([#1633](https://github.com/yschimke/compose-ai-tools/issues/1633)) ([8c7dbcd](https://github.com/yschimke/compose-ai-tools/commit/8c7dbcd4a1f4b9e0d72ab6a85285cf069d76af85))
* **bundle:** schema v4 — content-hashed detached dependencies ([#1634](https://github.com/yschimke/compose-ai-tools/issues/1634)) ([7eb6d95](https://github.com/yschimke/compose-ai-tools/commit/7eb6d9540992b2584f6b096cd2c2f2d4e57e5f60))
* **cli:** add opt-in --bundle flag to render ([#1641](https://github.com/yschimke/compose-ai-tools/issues/1641)) ([b825f3f](https://github.com/yschimke/compose-ai-tools/commit/b825f3fe7605d5379b9c2e3982fc10aa9bcfc17f))
* **cli:** resolve detached maven coordinates from local repos (Tier 3) ([#1635](https://github.com/yschimke/compose-ai-tools/issues/1635)) ([3b69feb](https://github.com/yschimke/compose-ai-tools/commit/3b69feb74a982292574df700f69aff7903d5200c))
* **cli:** resolver downloads missing coordinates from remote repos ([#1640](https://github.com/yschimke/compose-ai-tools/issues/1640)) ([74f272e](https://github.com/yschimke/compose-ai-tools/commit/74f272e7cfa15e70ad2d259cc334c5e2b56c998d))
* **daemon:** clarify in-process compile (BTA) startup logging ([#1664](https://github.com/yschimke/compose-ai-tools/issues/1664)) ([0e4be98](https://github.com/yschimke/compose-ai-tools/commit/0e4be9824aa62b406362e475c5f056bea159c198))
* **tui-cli:** consume Mosaic fork 0.18.0-1 release and build TUI by default ([#1643](https://github.com/yschimke/compose-ai-tools/issues/1643)) ([038e873](https://github.com/yschimke/compose-ai-tools/commit/038e873b30f9d253bf38f48d7c5a58aa2de044d2))
* **tui-cli:** open a bundle PNG straight into a live image-only view ([#1622](https://github.com/yschimke/compose-ai-tools/issues/1622)) ([6871ab7](https://github.com/yschimke/compose-ai-tools/commit/6871ab729f2a7abb522cf1e2b3338b315d00706f))
* **tui-cli:** view a bundle with no project context ([#1628](https://github.com/yschimke/compose-ai-tools/issues/1628)) ([82a4325](https://github.com/yschimke/compose-ai-tools/commit/82a43253e30dfcc74d095e12d7b903675f73aaf6))
* **vscode:** declutter focus-mode preview toolbar ([#1668](https://github.com/yschimke/compose-ai-tools/issues/1668)) ([408677c](https://github.com/yschimke/compose-ai-tools/commit/408677c04ca2676c8022ec49b36bd6a6ece6e8d5))
* **vscode:** disable live mode for non-interactive preview kinds ([#1670](https://github.com/yschimke/compose-ai-tools/issues/1670)) ([707e3ef](https://github.com/yschimke/compose-ai-tools/commit/707e3ef12e761ccf158caffaeb588a1dbd233e22))
* **xr-glimmer:** calibrate previews to Studio's Glimmer angular + contrast model ([#1671](https://github.com/yschimke/compose-ai-tools/issues/1671)) ([b4a9267](https://github.com/yschimke/compose-ai-tools/commit/b4a9267557a1c7aff0e382ee1a6a35efb6be7ede))


### Bug Fixes

* **a11y-report:** tolerate boundsInScreen jitter in PR-comment diff ([#1653](https://github.com/yschimke/compose-ai-tools/issues/1653)) ([84351bb](https://github.com/yschimke/compose-ai-tools/commit/84351bb4da8c1c2be0e0fc8c38b110e16fc45cd9))
* **bundle:** carry + resolve the tile renderer runtime for protolayout IR replay ([#1669](https://github.com/yschimke/compose-ai-tools/issues/1669)) ([54682d5](https://github.com/yschimke/compose-ai-tools/commit/54682d5f8dfe1b6dd24b81c834ffc345dbf4b4ed))
* **bundle:** carry IR replay libs onto the daemon launch -cp ([#1675](https://github.com/yschimke/compose-ai-tools/issues/1675)) ([57b184d](https://github.com/yschimke/compose-ai-tools/commit/57b184d9f12a4c38e5a30ee61d0eb42a94373ae2))
* **bundle:** skip IR-backed previews in renderAndroid (parity with renderDesktop) ([#1657](https://github.com/yschimke/compose-ai-tools/issues/1657)) ([36ce462](https://github.com/yschimke/compose-ai-tools/commit/36ce462fae27ec6ead4533f6e4802d3564a2c8d1))
* **daemon:** expand classpath [@argfile](https://github.com/argfile) before user JVM args ([#1623](https://github.com/yschimke/compose-ai-tools/issues/1623)) ([e249bf6](https://github.com/yschimke/compose-ai-tools/commit/e249bf61f1efbd2137220fef8f1731f1fc1d7774))
* **daemon:** render non-composable previews in live mode instead of blanking ([#1667](https://github.com/yschimke/compose-ai-tools/issues/1667)) ([8bb9526](https://github.com/yschimke/compose-ai-tools/commit/8bb95262996de570c065e6f24b4e9f10282d95a5))
* **daemon:** respawn on launch-descriptor change after first warm ([#1630](https://github.com/yschimke/compose-ai-tools/issues/1630)) ([cf3190e](https://github.com/yschimke/compose-ai-tools/commit/cf3190e24ae885e9d7932407f68123e64d9e76ef))
* **daemon:** strip classpath/argfile-control options from jvmArgs ([#1625](https://github.com/yschimke/compose-ai-tools/issues/1625)) ([9c0b564](https://github.com/yschimke/compose-ai-tools/commit/9c0b56428bebd2b03205a6b8904c3ebaa4a36c51))
* **daemon:** tolerate missing previews.json on first warm ([#1629](https://github.com/yschimke/compose-ai-tools/issues/1629)) ([6133c5e](https://github.com/yschimke/compose-ai-tools/commit/6133c5e16a7ab81e38e82451f8dbda4180507a77))
* **deps:** update gradle minor/patch ([#1649](https://github.com/yschimke/compose-ai-tools/issues/1649)) ([cea9ad2](https://github.com/yschimke/compose-ai-tools/commit/cea9ad2fc6587527dcb114bac222156d405b3c33))
* **gradle-plugin:** clamp Robolectric SDK to 35 when render JVM is JDK &lt; 21 ([#1646](https://github.com/yschimke/compose-ai-tools/issues/1646)) ([1c3f83f](https://github.com/yschimke/compose-ai-tools/commit/1c3f83f36b0e819e3c0ce6854f89a1f6d11739cc))
* **vscode:** signal daemon readiness in preview-harness so chip fixtures render ([#1673](https://github.com/yschimke/compose-ai-tools/issues/1673)) ([3dc1947](https://github.com/yschimke/compose-ai-tools/commit/3dc19479d894d8ab22b561ef8382f796935ba311))

## [0.12.1](https://github.com/yschimke/compose-ai-tools/compare/v0.12.0...v0.12.1) (2026-05-30)


### Features

* **plugin:** make ValidatePreviewToolingPresent opt-in ([#1620](https://github.com/yschimke/compose-ai-tools/issues/1620)) ([8ffeb1e](https://github.com/yschimke/compose-ai-tools/commit/8ffeb1e465e80f2441a6440636ee087d0bc7d724))
* **tui-cli:** interactive Mosaic-based preview browser ([#1518](https://github.com/yschimke/compose-ai-tools/issues/1518)) ([cc09f9e](https://github.com/yschimke/compose-ai-tools/commit/cc09f9eb1511c4f0e67f3e03697b16f1f1923c71))


### Bug Fixes

* **daemon:** pass classpath via [@argfile](https://github.com/argfile) to avoid spawn E2BIG ([#1621](https://github.com/yschimke/compose-ai-tools/issues/1621)) ([20333b4](https://github.com/yschimke/compose-ai-tools/commit/20333b428071b0416e956f6616b325e51b3fd8bc))
* discover preview modules without realizing the whole task graph ([#1617](https://github.com/yschimke/compose-ai-tools/issues/1617)) ([a72b0d9](https://github.com/yschimke/compose-ai-tools/commit/a72b0d9592659bf4de0d63b6bb66cffa1b999d7b))

## [0.12.0](https://github.com/yschimke/compose-ai-tools/compare/v0.11.16...v0.12.0) (2026-05-29)


### ⚠ BREAKING CHANGES

* **renderer-android:** default LocalInspectionMode to true for static previews ([#1614](https://github.com/yschimke/compose-ai-tools/issues/1614))

### Features

* **daemon-bench:** drive stage-1/stage-2 compile legs + CI smoke ([#1586](https://github.com/yschimke/compose-ai-tools/issues/1586)) ([#1615](https://github.com/yschimke/compose-ai-tools/issues/1615)) ([a7a077c](https://github.com/yschimke/compose-ai-tools/commit/a7a077c62d2ecdef10b1a6ce73ce90d69c5bac0a))
* **daemon:** wire desktop scroll scenario runner (render/scroll/long + gif) ([#1609](https://github.com/yschimke/compose-ai-tools/issues/1609)) ([9eed691](https://github.com/yschimke/compose-ai-tools/commit/9eed6919bdc77708620d5d16eb42012330ece0b3))
* **mcp:** accept and validate override-extension fields in render_preview ([#1611](https://github.com/yschimke/compose-ai-tools/issues/1611)) ([6728921](https://github.com/yschimke/compose-ai-tools/commit/67289211c0d564d3c2e213b1ee8a3d0b7d760f79))
* **recomposition:** v2 schema with per-scope invalidation reason ([#1612](https://github.com/yschimke/compose-ai-tools/issues/1612)) ([4f30a64](https://github.com/yschimke/compose-ai-tools/commit/4f30a64a67f9420ce2cdb9c479f222417d1abab9))
* **renderer-android:** default LocalInspectionMode to true for static previews ([#1614](https://github.com/yschimke/compose-ai-tools/issues/1614)) ([154ecef](https://github.com/yschimke/compose-ai-tools/commit/154ecefe5de14466c517802a0c2ae08cecb064a6))


### Bug Fixes

* **a11y:** preserve baseline findings on ATF-unavailable runs ([#1608](https://github.com/yschimke/compose-ai-tools/issues/1608)) ([ff17f6b](https://github.com/yschimke/compose-ai-tools/commit/ff17f6b0eab0f2e7752254d42f3e2b1768834cf7))
* **cli,gradle-plugin:** tighten --variant matching and propagate to doctor ([#1599](https://github.com/yschimke/compose-ai-tools/issues/1599)) ([2d503ba](https://github.com/yschimke/compose-ai-tools/commit/2d503ba923c276e21d84566cb2c87afbb400abd1))
* **daemon:** truthful supportedOverrides + drop stale Android-only docstrings ([#1603](https://github.com/yschimke/compose-ai-tools/issues/1603)) ([027f611](https://github.com/yschimke/compose-ai-tools/commit/027f61108010ef2fc8bb7207251ef7eadd411f8e))
* **notifications:** render @NotificationPreview at 400dp wide, not a 320 square ([#1592](https://github.com/yschimke/compose-ai-tools/issues/1592)) ([fd9c7ed](https://github.com/yschimke/compose-ai-tools/commit/fd9c7ed7d996831d408ae3ae6688fbdd8e0be5b2))
* **notifications:** treat @PreviewParameter fan-out variants as declared ([#1597](https://github.com/yschimke/compose-ai-tools/issues/1597)) ([9e3b9f8](https://github.com/yschimke/compose-ai-tools/commit/9e3b9f8925dac8d3fb4f116442f9b7b1fd06c75d))
* **permissions:** scope sandbox query bridge by previewId ([#1610](https://github.com/yschimke/compose-ai-tools/issues/1610)) ([f72e29a](https://github.com/yschimke/compose-ai-tools/commit/f72e29ac406bcafefe943b5fce42bce3990d68a3))
* **samples:** additively composite Glimmer XR menu so the env shows through ([#1601](https://github.com/yschimke/compose-ai-tools/issues/1601)) ([5aa391d](https://github.com/yschimke/compose-ai-tools/commit/5aa391da584d34142a9a5db883e16edccf13924f))
* **vscode-extension:** address codex follow-ups on preview-tab + focus refresh ([#1598](https://github.com/yschimke/compose-ai-tools/issues/1598)) ([0bab05b](https://github.com/yschimke/compose-ai-tools/commit/0bab05b5a545265cd487745fd20837e8b47802f1))

## [0.11.16](https://github.com/yschimke/compose-ai-tools/compare/v0.11.15...v0.11.16) (2026-05-29)


### Features

* **a11y:** render overlays + legends across all sample modules, incl. CMP/desktop ([#1575](https://github.com/yschimke/compose-ai-tools/issues/1575)) ([e423f42](https://github.com/yschimke/compose-ai-tools/commit/e423f4260ed3fca4dc0c5758311f3de63c1333b8))
* **daemon:** complete stage-2 in-process compile (BTA) behind experimental flag ([#1587](https://github.com/yschimke/compose-ai-tools/issues/1587)) ([6542768](https://github.com/yschimke/compose-ai-tools/commit/654276825fd7313665d43b078861b4816050f9a3))
* **daemon:** produce @ScrollingPreview artefacts via data/fetch ([#1579](https://github.com/yschimke/compose-ai-tools/issues/1579)) ([6ca2330](https://github.com/yschimke/compose-ai-tools/commit/6ca2330590f7ef0b181f5f70544600209627ea83))
* IP-safe cross-project metadata service for preview tooling detection ([#1580](https://github.com/yschimke/compose-ai-tools/issues/1580)) ([8064930](https://github.com/yschimke/compose-ai-tools/commit/8064930d58b958145b16cca2c6758c01d1870185))
* **permissions:** cross-classloader data/fetch readback for compose/permissions queries ([#1582](https://github.com/yschimke/compose-ai-tools/issues/1582)) ([2520b30](https://github.com/yschimke/compose-ai-tools/commit/2520b306af1d62344edb8e1e3647faf6846a8953))
* **preview:** @FocusedPreview(pressed = true) dispatches indirect-pointer Press ([#1585](https://github.com/yschimke/compose-ai-tools/issues/1585)) ([8e3a23c](https://github.com/yschimke/compose-ai-tools/commit/8e3a23c50252f0596d1b278d54d2b39a56724f12))
* **xr-glimmer:** real-interaction Default + Focused state captures, enterPlacesFocus opt-in ([#1583](https://github.com/yschimke/compose-ai-tools/issues/1583)) ([5429c12](https://github.com/yschimke/compose-ai-tools/commit/5429c1283b709c3aa6cddd6b56e17454457e5533))


### Bug Fixes

* **a11y:** surface only changed previews in PR comment ([#1590](https://github.com/yschimke/compose-ai-tools/issues/1590)) ([560e748](https://github.com/yschimke/compose-ai-tools/commit/560e74890f2be2a7776c9220d97066cd8fed1534))
* **focus-connector:** release indirect-pointer Press across multi-index captures ([#1589](https://github.com/yschimke/compose-ai-tools/issues/1589)) ([ef0462a](https://github.com/yschimke/compose-ai-tools/commit/ef0462a8460b245fae348115070702800af784e8))
* **notifications:** distinguish failed renders from removals in PR comment ([#1591](https://github.com/yschimke/compose-ai-tools/issues/1591)) ([934cf60](https://github.com/yschimke/compose-ai-tools/commit/934cf604f652b1fe4b182a74bba84d44a592e1d6))

## [0.11.15](https://github.com/yschimke/compose-ai-tools/compare/v0.11.14...v0.11.15) (2026-05-28)


### Features

* **samples:** add :samples:xr-glimmer for Jetpack Compose Glimmer (Android XR) ([#1561](https://github.com/yschimke/compose-ai-tools/issues/1561)) ([4b6fa37](https://github.com/yschimke/compose-ai-tools/commit/4b6fa37a94138b086db31b9138889df28c04ca7e))
* **samples:** fan GlimmerXrMenuNavigation across all four env backdrops ([#1564](https://github.com/yschimke/compose-ai-tools/issues/1564)) ([ad29751](https://github.com/yschimke/compose-ai-tools/commit/ad297510103ca3c15ae049e02ca9c34d51939842))
* **samples:** interactive XR menu navigation GIF with touchpad gesture overlay ([#1563](https://github.com/yschimke/compose-ai-tools/issues/1563)) ([70faa58](https://github.com/yschimke/compose-ai-tools/commit/70faa5879acaf68117bbf2c2419b4f6bec948630))
* surface private @Preview methods via reflection ([#1572](https://github.com/yschimke/compose-ai-tools/issues/1572)) ([c02ea5b](https://github.com/yschimke/compose-ai-tools/commit/c02ea5bb277677e340cc4d7c76016a77c6ac3b96))


### Bug Fixes

* **a11y:** don't report list items as merged into a scrollable ([#1569](https://github.com/yschimke/compose-ai-tools/issues/1569)) ([9aad66a](https://github.com/yschimke/compose-ai-tools/commit/9aad66a3a11ef362fe32b8437c194e745938033f))
* allow private @Preview Glance composables to be invoked ([#1574](https://github.com/yschimke/compose-ai-tools/issues/1574)) ([597ad0d](https://github.com/yschimke/compose-ai-tools/commit/597ad0d9512d07b7efa585fbd55a941b97589bd9))
* set explicit notification width to match renderer canvas ([#1576](https://github.com/yschimke/compose-ai-tools/issues/1576)) ([2017d8c](https://github.com/yschimke/compose-ai-tools/commit/2017d8cb6bf0593cfaed32f671060191b8757d48))
* **vscode-extension:** clear stale previews when last editor closes ([#1568](https://github.com/yschimke/compose-ai-tools/issues/1568)) ([4f6b69c](https://github.com/yschimke/compose-ai-tools/commit/4f6b69c7f5b6cdc88cc8d02ac85ad398f68d7917))
* **vscode-extension:** recover from partial render failures, break drift loop ([#1558](https://github.com/yschimke/compose-ai-tools/issues/1558)) ([98c4c16](https://github.com/yschimke/compose-ai-tools/commit/98c4c16ac8cc721fe82cd55769b66c852a520ee1))
* **vscode-extension:** scope bundle overlays to the focused preview ([#1570](https://github.com/yschimke/compose-ai-tools/issues/1570)) ([600e844](https://github.com/yschimke/compose-ai-tools/commit/600e8442e5d9c8b2073671444040bf3aff746608))

## [0.11.14](https://github.com/yschimke/compose-ai-tools/compare/v0.11.13...v0.11.14) (2026-05-28)


### Features

* **doctor:** warn when Gradle daemon runs above JDK 21 on AGP projects ([#1554](https://github.com/yschimke/compose-ai-tools/issues/1554)) ([55714e9](https://github.com/yschimke/compose-ai-tools/commit/55714e975785ae1c9946784c8c09693798fc441e))


### Bug Fixes

* **daemon:** stub Application by default, plumb useConsumerApplication ([#1557](https://github.com/yschimke/compose-ai-tools/issues/1557)) ([288b7f8](https://github.com/yschimke/compose-ai-tools/commit/288b7f88fce1bc3e3bfbd75955c01ea8862bb594))
* **daemon:** surface bootstrap failures from RobolectricHost workers ([#1556](https://github.com/yschimke/compose-ai-tools/issues/1556)) ([c6faa40](https://github.com/yschimke/compose-ai-tools/commit/c6faa407576a0239f219f7bc9229bf5b4eb1c8ff))

## [0.11.13](https://github.com/yschimke/compose-ai-tools/compare/v0.11.12...v0.11.13) (2026-05-27)


### Features

* add permissions override UI to inspection panel ([#1540](https://github.com/yschimke/compose-ai-tools/issues/1540)) ([e7745d8](https://github.com/yschimke/compose-ai-tools/commit/e7745d8f645fa8f346e8c74f82a2437e26508232))
* detect render/manifest drift after a sanitiser change ([#1548](https://github.com/yschimke/compose-ai-tools/issues/1548)) ([a4e06b6](https://github.com/yschimke/compose-ai-tools/commit/a4e06b6781624b13314fa872ce11d22ffe723f76))
* **doctor:** surface module.preview-tooling-not-declared finding ([#1553](https://github.com/yschimke/compose-ai-tools/issues/1553)) ([245160d](https://github.com/yschimke/compose-ai-tools/commit/245160dd7492590d2a57499114c6f7f19b4189b9))


### Bug Fixes

* detect transitive preview deps in unevaluated projects ([#1541](https://github.com/yschimke/compose-ai-tools/issues/1541)) ([2a81e56](https://github.com/yschimke/compose-ai-tools/commit/2a81e56f9660ddc3c0f821bce43751f517d4b6e5))
* match flavored AGP variants and add --variant CLI flag ([#1551](https://github.com/yschimke/compose-ai-tools/issues/1551)) ([6f000ad](https://github.com/yschimke/compose-ai-tools/commit/6f000ad9e28b474a54c42d0cb75bfaf864172fa4))

## [0.11.12](https://github.com/yschimke/compose-ai-tools/compare/v0.11.11...v0.11.12) (2026-05-26)


### Features

* add per-card loading spinner for data extension subscriptions ([#1537](https://github.com/yschimke/compose-ai-tools/issues/1537)) ([f816202](https://github.com/yschimke/compose-ai-tools/commit/f8162028839aa2175bb30ae94d1f75cb535d32ec))


### Bug Fixes

* **daemon:** ScopedDataProducts forwards renderModeFor to the owning … ([#1535](https://github.com/yschimke/compose-ai-tools/issues/1535)) ([30baaab](https://github.com/yschimke/compose-ai-tools/commit/30baaab05b425a9115ca6fe965be9173ca734fba))
* **plugin:** nicer renderOutput sanitiser, per-preview shortest unique stem ([#1530](https://github.com/yschimke/compose-ai-tools/issues/1530)) ([f5b8d08](https://github.com/yschimke/compose-ai-tools/commit/f5b8d081ede765687c3639e88202c920207731d6))
* stop emitting phantom static capture for @ScrollingPreview LONG/GIF-only ([#1526](https://github.com/yschimke/compose-ai-tools/issues/1526)) ([d5def43](https://github.com/yschimke/compose-ai-tools/commit/d5def436cc4fb766be6980a8e1533678e010a25f))
* **vscode-extension:** a11y toggle works without composePreview.early… ([#1534](https://github.com/yschimke/compose-ai-tools/issues/1534)) ([f3fd784](https://github.com/yschimke/compose-ai-tools/commit/f3fd784a023675582eeedafa577d6f39e401f053))
* **vscode-extension:** backfill @ScrollingPreview image data products via Gradle ([#1516](https://github.com/yschimke/compose-ai-tools/issues/1516)) ([f9699d1](https://github.com/yschimke/compose-ai-tools/commit/f9699d1b97f7667390d20d52bf4388933a52b9c4))
* **vscode-extension:** backfill @ScrollingPreview image data products via Gradle ([#1519](https://github.com/yschimke/compose-ai-tools/issues/1519)) ([a580b25](https://github.com/yschimke/compose-ai-tools/commit/a580b250cb6216f7d0be2740acd734dd19c86f1e))
* **vscode-extension:** drop post-subscribe refresh that stuck a11y ca… ([#1536](https://github.com/yschimke/compose-ai-tools/issues/1536)) ([8115bb6](https://github.com/yschimke/compose-ai-tools/commit/8115bb64b7e7c83eecdd4a26ae30de91f3504534))
* **vscode-extension:** keep @ScrollingPreview cards at device aspect ratio in every layout ([#1531](https://github.com/yschimke/compose-ai-tools/issues/1531)) ([e38850d](https://github.com/yschimke/compose-ai-tools/commit/e38850df9b70c0d0cc4cc233e81edaf9d994ad9e))

## [0.11.11](https://github.com/yschimke/compose-ai-tools/compare/v0.11.10...v0.11.11) (2026-05-26)


### Features

* add inline image rendering for terminal preview display ([#1515](https://github.com/yschimke/compose-ai-tools/issues/1515)) ([18e56b5](https://github.com/yschimke/compose-ai-tools/commit/18e56b51da8ce9ce85a4b7b22d939dd7aea71a6c))
* **vscode-extension:** add 'Compose Preview: Verify' consistency-check command ([#1509](https://github.com/yschimke/compose-ai-tools/issues/1509)) ([d89929e](https://github.com/yschimke/compose-ai-tools/commit/d89929ec9decfe0392b35fdf7dc01f05fb8430f6))
* **vscode-extension:** trace preload skips + gradle cancel source ([#1508](https://github.com/yschimke/compose-ai-tools/issues/1508)) ([0923d74](https://github.com/yschimke/compose-ai-tools/commit/0923d743cb6fbe3bca3d5c22c882c5c9ca786a77))


### Bug Fixes

* **cli:** auto-enable mavenLocal for SNAPSHOT plugin versions ([#1512](https://github.com/yschimke/compose-ai-tools/issues/1512)) ([ccf235d](https://github.com/yschimke/compose-ai-tools/commit/ccf235d26c57b838fea11699dd37ca63ff67c19f))
* stream large PR comment bodies via file to avoid ARG_MAX ([#1511](https://github.com/yschimke/compose-ai-tools/issues/1511)) ([6fe6e0f](https://github.com/yschimke/compose-ai-tools/commit/6fe6e0fbe0b622a573cb7925c956e90c15dc61d8))
* **vscode-extension:** PreviewRegistry preserves image bytes across refresh ([#1513](https://github.com/yschimke/compose-ai-tools/issues/1513)) ([8f04638](https://github.com/yschimke/compose-ai-tools/commit/8f04638ce338c0c7869b561f42a64f13564b7f65))

## [0.11.10](https://github.com/yschimke/compose-ai-tools/compare/v0.11.9...v0.11.10) (2026-05-25)


### Features

* add missing-renders policy to apply action ([#1500](https://github.com/yschimke/compose-ai-tools/issues/1500)) ([d7ef04c](https://github.com/yschimke/compose-ai-tools/commit/d7ef04c248c8e1336b8b8abfa127e0760185a246))
* support multi-module a11y + notification pipelines in apply action ([#1495](https://github.com/yschimke/compose-ai-tools/issues/1495)) ([fdc69d8](https://github.com/yschimke/compose-ai-tools/commit/fdc69d8bbfe94dc1c4f68ef12fd7cc4114274a36))
* surface preview render errors from .error.json sidecars ([#1501](https://github.com/yschimke/compose-ai-tools/issues/1501)) ([2007020](https://github.com/yschimke/compose-ai-tools/commit/20070203246991d7690c004d5cf49c73fe138e4c))


### Bug Fixes

* retry daemon bootstrap on cancellation and coalesce concurrent warms ([#1497](https://github.com/yschimke/compose-ai-tools/issues/1497)) ([cccf80d](https://github.com/yschimke/compose-ai-tools/commit/cccf80dda8e2a8fcfa2c7e2d75d51cd314decd30))

## [0.11.9](https://github.com/yschimke/compose-ai-tools/compare/v0.11.8...v0.11.9) (2026-05-25)


### Bug Fixes

* skip classpath dep injection for modules without buildscript repos in exclusiveContent shape ([#1491](https://github.com/yschimke/compose-ai-tools/issues/1491)) ([f352cf1](https://github.com/yschimke/compose-ai-tools/commit/f352cf128398618ce65aa4506f76cb5338edf467))

## [0.11.8](https://github.com/yschimke/compose-ai-tools/compare/v0.11.7...v0.11.8) (2026-05-25)


### Features

* cap preview image magnification with --preview-max-zoom ([#1486](https://github.com/yschimke/compose-ai-tools/issues/1486)) ([4f696e4](https://github.com/yschimke/compose-ai-tools/commit/4f696e4bf52fc85a585894976024e3a3bfb58536))


### Bug Fixes

* detect exclusiveContent in settings to suppress buildscript injection ([#1490](https://github.com/yschimke/compose-ai-tools/issues/1490)) ([0abdde9](https://github.com/yschimke/compose-ai-tools/commit/0abdde99c7dd7c6c1eb75d0cf34577064740e242))
* tolerate KMP-Android per-target Kotlin compile task names ([#1488](https://github.com/yschimke/compose-ai-tools/issues/1488)) ([dd44219](https://github.com/yschimke/compose-ai-tools/commit/dd44219fb51081ff50e35c7d8b4bbdd166ca5465))

## [0.11.7](https://github.com/yschimke/compose-ai-tools/compare/v0.11.6...v0.11.7) (2026-05-25)


### Features

* add skip-render mode for non-Gradle build systems ([#1476](https://github.com/yschimke/compose-ai-tools/issues/1476)) ([631e269](https://github.com/yschimke/compose-ai-tools/commit/631e26944264ea1c52fb6a16f40ac8eea964c16c))


### Bug Fixes

* guard AmbientPreviewOverrideExtension and AmbientInputDispatchOb… ([#1482](https://github.com/yschimke/compose-ai-tools/issues/1482)) ([73fc992](https://github.com/yschimke/compose-ai-tools/commit/73fc99293d29d935c8222e9b4cad3de269424e25))
* handle missing ambient connector in wear sandbox bootstrap ([#1479](https://github.com/yschimke/compose-ai-tools/issues/1479)) ([8efff66](https://github.com/yschimke/compose-ai-tools/commit/8efff663898f8d458ffadfba5b0e14e994dac70b))
* improve kotlin file detection during extension activation ([#1481](https://github.com/yschimke/compose-ai-tools/issues/1481)) ([40d9aff](https://github.com/yschimke/compose-ai-tools/commit/40d9affa79564d7c0e52232f9b59278896551794))
* load compose-preview plugin via initscript classpath instead of buildscript injection ([#1483](https://github.com/yschimke/compose-ai-tools/issues/1483)) ([8f091ba](https://github.com/yschimke/compose-ai-tools/commit/8f091bac1d391d343a12ad4da19dc0cfc6bf3afe))
* preserve gradle args in compose preview test mode ([#1478](https://github.com/yschimke/compose-ai-tools/issues/1478)) ([14168b6](https://github.com/yschimke/compose-ai-tools/commit/14168b69bc4d07e0bffb2f2819194284ac8e16b3))

## [0.11.6](https://github.com/yschimke/compose-ai-tools/compare/v0.11.5...v0.11.6) (2026-05-25)


### Features

* publish renderer-desktop and resolve via Maven coord by default ([#1472](https://github.com/yschimke/compose-ai-tools/issues/1472)) ([ed18fb6](https://github.com/yschimke/compose-ai-tools/commit/ed18fb612eebbff90e851ded91d569af8c4be840))
* unify compose-preview CI into single apply action ([#1465](https://github.com/yschimke/compose-ai-tools/issues/1465)) ([6d7b1c0](https://github.com/yschimke/compose-ai-tools/commit/6d7b1c008c414a81bbe9bb99172ed0e2bd21c144))


### Bug Fixes

* abort pending refresh before preloading previews ([#1471](https://github.com/yschimke/compose-ai-tools/issues/1471)) ([5525f51](https://github.com/yschimke/compose-ai-tools/commit/5525f5110447af09e94c23cfaf164609980cefb0))
* prevent preview panel from blanking during gradle warmup ([#1467](https://github.com/yschimke/compose-ai-tools/issues/1467)) ([49ff61d](https://github.com/yschimke/compose-ai-tools/commit/49ff61d973cad75539b90ad3ca62566b6d358abe))
* skip composite-included builds in init script ([#1470](https://github.com/yschimke/compose-ai-tools/issues/1470)) ([fc2d12d](https://github.com/yschimke/compose-ai-tools/commit/fc2d12de5f3c6dc03eed7110a0bc2d9e61045c27))

## [0.11.5](https://github.com/yschimke/compose-ai-tools/compare/v0.11.4...v0.11.5) (2026-05-24)


### Features

* **daemon:** stage-2 BTA spike — in-process compile with Compose plugin ([#1338](https://github.com/yschimke/compose-ai-tools/issues/1338)) ([83cec58](https://github.com/yschimke/compose-ai-tools/commit/83cec583d3e6ccb2e8d71f159ab4b271f0d046d6))
* **data-remotecompose:** bridge connector overrides into the remote player ([#1422](https://github.com/yschimke/compose-ai-tools/issues/1422)) ([d9822ea](https://github.com/yschimke/compose-ai-tools/commit/d9822eadbdc1c6f60fd513f9938cc3d91f1c6e87))
* **glance:** native FQN discovery of androidx.glance.preview.Preview ([#1414](https://github.com/yschimke/compose-ai-tools/issues/1414)) ([c75581f](https://github.com/yschimke/compose-ai-tools/commit/c75581f65c8bb43207919046eb0ceee25230d392))
* **launcher-widget:** add @LauncherWidgetResize multi-capture annotation ([#1421](https://github.com/yschimke/compose-ai-tools/issues/1421)) ([461fa48](https://github.com/yschimke/compose-ai-tools/commit/461fa48b367c9bd63e7bbc5abfc0d9b27a3582c1))
* **launcher-widget:** add data product registry + VS Code protocol types ([#1428](https://github.com/yschimke/compose-ai-tools/issues/1428)) ([c3123b8](https://github.com/yschimke/compose-ai-tools/commit/c3123b89457077d8a2bde1dda770a2cd5190d66e))
* **launcher-widget:** auto-discover appwidget-provider XML metadata ([#1445](https://github.com/yschimke/compose-ai-tools/issues/1445)) ([0e999b1](https://github.com/yschimke/compose-ai-tools/commit/0e999b153e4d1acf07f9cd5362061732d9f2ff1f))
* **launcher-widget:** plumb Glance previewSizeMode into payload constraints ([#1432](https://github.com/yschimke/compose-ai-tools/issues/1432)) ([e2d6420](https://github.com/yschimke/compose-ai-tools/commit/e2d64208439d906c56fdf4e90bed18f773ea667a))
* **resources:** keyframe filmstrip output for AVD previews ([#1426](https://github.com/yschimke/compose-ai-tools/issues/1426)) ([563434f](https://github.com/yschimke/compose-ai-tools/commit/563434f3212aeabcba570f5fc3bc64ef1eb93dd9))
* **resources:** preview 9-patch drawables at intrinsic + 2× stretch variants ([#1423](https://github.com/yschimke/compose-ai-tools/issues/1423)) ([6bfc6a6](https://github.com/yschimke/compose-ai-tools/commit/6bfc6a60f4885f38f4811e2586c0f7606a17d87e))
* **samples/remotecompose:** demonstrate `LocalRemoteComposeHost` named-value bind ([#1412](https://github.com/yschimke/compose-ai-tools/issues/1412)) ([d351c0b](https://github.com/yschimke/compose-ai-tools/commit/d351c0b12935eb65d99f55aaa3f2ea626acee4ac))
* **samples:** flesh out wear and remotecompose manifests with launcher icons and strings ([#1437](https://github.com/yschimke/compose-ai-tools/issues/1437)) ([aa22755](https://github.com/yschimke/compose-ai-tools/commit/aa227558972446aae1af9119755f531c02a197bb))
* **splash:** SplashScreenSurface runtime helper for Android 12 splash window previews ([#1425](https://github.com/yschimke/compose-ai-tools/issues/1425)) ([75b7afa](https://github.com/yschimke/compose-ai-tools/commit/75b7afadc0976df97df328de30ef94b0ae405c07))
* **typography:** runtime helpers for Typography / FontFamily / fallback specimens ([#1424](https://github.com/yschimke/compose-ai-tools/issues/1424)) ([68bb70b](https://github.com/yschimke/compose-ai-tools/commit/68bb70b14e52760e17fd92782ffb8e8cbff6d404))
* **vscode-extension:** add launcher-widget cell-grid picker module ([#1451](https://github.com/yschimke/compose-ai-tools/issues/1451)) ([5726ff3](https://github.com/yschimke/compose-ai-tools/commit/5726ff35089cfc0c409ab62d4f9b14452597e829))
* **vscode-extension:** add launcher-widget cell-grid picker module ([#1459](https://github.com/yschimke/compose-ai-tools/issues/1459)) ([3eef7f7](https://github.com/yschimke/compose-ai-tools/commit/3eef7f7c6c14887865e8956c5fafe4a8f2b478bd))
* **vscode-extension:** hover + CodeLens for R.drawable / R.mipmap references in Kotlin and res XML ([#1431](https://github.com/yschimke/compose-ai-tools/issues/1431)) ([d6fd79f](https://github.com/yschimke/compose-ai-tools/commit/d6fd79f138b69ba63b0df992f9432853601fe5d6))
* **vscode-extension:** launcher-widget picker payload + persistence ([#1460](https://github.com/yschimke/compose-ai-tools/issues/1460)) ([7e97d3c](https://github.com/yschimke/compose-ai-tools/commit/7e97d3c31518ff068bd9a260e9a7a3d27f7755b6))
* **vscode-extension:** move per-card touch / keyboard / controls toggles into focus bar ([#1433](https://github.com/yschimke/compose-ai-tools/issues/1433)) ([8dd05c3](https://github.com/yschimke/compose-ai-tools/commit/8dd05c3a04a58ccc256b6097b46f1ecd17defba8))
* **vscode-extension:** show all adaptive-icon variants on AndroidManifest.xml hover ([#1420](https://github.com/yschimke/compose-ai-tools/issues/1420)) ([f99306c](https://github.com/yschimke/compose-ai-tools/commit/f99306cb9a91b52f0d54275bcfafc118a31aa1d5))
* **vscode-extension:** surface manifest-backed icon when an Activity Kotlin file is open ([#1429](https://github.com/yschimke/compose-ai-tools/issues/1429)) ([da51f9b](https://github.com/yschimke/compose-ai-tools/commit/da51f9b0962779d231274c621342201568c0e11d))


### Bug Fixes

* **a11y:** surface ATF-unavailable instead of synthesising "no findings" ([#1456](https://github.com/yschimke/compose-ai-tools/issues/1456)) ([36ee8ac](https://github.com/yschimke/compose-ai-tools/commit/36ee8ac3463c0cff0f6dc06b32f0f436e73df1e6))
* address simple codex review findings from last 24h ([#1447](https://github.com/yschimke/compose-ai-tools/issues/1447)) ([2aff7b8](https://github.com/yschimke/compose-ai-tools/commit/2aff7b89bc15c47040d14d5fbf15f5503dce704e))
* close live-edit override pipeline gaps ([#1448](https://github.com/yschimke/compose-ai-tools/issues/1448)) ([d02f909](https://github.com/yschimke/compose-ai-tools/commit/d02f9092620b4738099312494b2dd83840169336))
* codex follow-ups — discovery, inspection UI, install + publishing ([#1450](https://github.com/yschimke/compose-ai-tools/issues/1450)) ([eac5b8a](https://github.com/yschimke/compose-ai-tools/commit/eac5b8a02ce353af1a1fa97c82c25d05a1cc1939))
* **daemon:** honour @PreviewWrapper in render engine ([#1439](https://github.com/yschimke/compose-ai-tools/issues/1439)) ([006269f](https://github.com/yschimke/compose-ai-tools/commit/006269f1c338603ad182a0ecca30368c1f7893f2))
* **daemon:** plumb @PreviewWrapper FQN through manifest and dispatch GLANCE_APPWIDGET ([#1449](https://github.com/yschimke/compose-ai-tools/issues/1449)) ([29d81a0](https://github.com/yschimke/compose-ai-tools/commit/29d81a05770325c8319148ac76c2b14739165333))
* **data-remotecompose:** guard hex parse when applying ColorValue override ([#1444](https://github.com/yschimke/compose-ai-tools/issues/1444)) ([b7c2ca9](https://github.com/yschimke/compose-ai-tools/commit/b7c2ca92dd125bd1e787852716bcac6f9834406a))
* **touch-overlay:** stamp pulses with event uptimeMillis so single clicks render ([#1435](https://github.com/yschimke/compose-ai-tools/issues/1435)) ([fa58bb0](https://github.com/yschimke/compose-ai-tools/commit/fa58bb08240b4ca202239d55c7019e2d3f10d67e))
* **vscode-extension:** route daemon static PNG to the matching capture slot ([#1434](https://github.com/yschimke/compose-ai-tools/issues/1434)) ([31c960e](https://github.com/yschimke/compose-ai-tools/commit/31c960e8d30498bc46ca207cabdf62267c0b59eb))
* **vscode-extension:** trust boundary for workspace `resources.json` hover ([#1446](https://github.com/yschimke/compose-ai-tools/issues/1446)) ([bbfa0f1](https://github.com/yschimke/compose-ai-tools/commit/bbfa0f16cd5a39c90023dc1a9eb37a079380030c))


### Performance Improvements

* **vscode-extension:** bundle cold-start Gradle tasks into one invocation ([#1438](https://github.com/yschimke/compose-ai-tools/issues/1438)) ([94e63af](https://github.com/yschimke/compose-ai-tools/commit/94e63af049db37830db485e0c62a10228e6d7de6))

## [0.11.4](https://github.com/yschimke/compose-ai-tools/compare/v0.11.3...v0.11.4) (2026-05-23)


### Features

* **launcher-widget:** add @LauncherWidgetPreview annotation + discovery ([#1407](https://github.com/yschimke/compose-ai-tools/issues/1407)) ([767e531](https://github.com/yschimke/compose-ai-tools/commit/767e5311879ceb99c43a9bdbf665333a7e697be8))


### Bug Fixes

* **cli:** skip auto-inject for KMP-Android modules ([#1411](https://github.com/yschimke/compose-ai-tools/issues/1411)) ([298f381](https://github.com/yschimke/compose-ai-tools/commit/298f381a057231bc96a8922bab0f06cb8d698356))
* **daemon-desktop:** bundle per-platform Skiko native runtimes in the POM ([#1413](https://github.com/yschimke/compose-ai-tools/issues/1413)) ([5ef1de0](https://github.com/yschimke/compose-ai-tools/commit/5ef1de0ae975622f88ed415afc6466a4d849ea60))

## [0.11.3](https://github.com/yschimke/compose-ai-tools/compare/v0.11.2...v0.11.3) (2026-05-23)


### Features

* launcher widget previews — data extension + Glance/RemoteViews runtime ([#1368](https://github.com/yschimke/compose-ai-tools/issues/1368)) ([c0e7347](https://github.com/yschimke/compose-ai-tools/commit/c0e7347eb99ec63452beeb576c4b313758e485a9))
* **vscode-extension:** wire Remote Compose tab body edits back to the daemon ([#1401](https://github.com/yschimke/compose-ai-tools/issues/1401)) ([fcabe8d](https://github.com/yschimke/compose-ai-tools/commit/fcabe8dad1bf6886ae7899980d701453f04f04c9))


### Bug Fixes

* **install:** resolve `latest` to the last release with a complete CLI asset ([#1403](https://github.com/yschimke/compose-ai-tools/issues/1403)) ([49399db](https://github.com/yschimke/compose-ai-tools/commit/49399db540359451f6b3736a5cc62be8da3f72cc))
* **mcp:** publish :mcp to Maven Central ([#1404](https://github.com/yschimke/compose-ai-tools/issues/1404)) ([ff60b57](https://github.com/yschimke/compose-ai-tools/commit/ff60b5724e24d0d27b1e41e1d4826cd6d3882844))

## [0.11.2](https://github.com/yschimke/compose-ai-tools/compare/v0.11.1...v0.11.2) (2026-05-23)


### Features

* **samples:** add Metro ViewModel preview sample ([#1369](https://github.com/yschimke/compose-ai-tools/issues/1369)) ([7c68b02](https://github.com/yschimke/compose-ai-tools/commit/7c68b020082462aed8595e4ddce9b6ab70e34106))

## [0.11.1](https://github.com/yschimke/compose-ai-tools/compare/v0.11.0...v0.11.1) (2026-05-23)


### Features

* **cli:** add `compose-preview script` Kotlin scripting MVP ([#1084](https://github.com/yschimke/compose-ai-tools/issues/1084)) ([#1375](https://github.com/yschimke/compose-ai-tools/issues/1375)) ([a2e98b6](https://github.com/yschimke/compose-ai-tools/commit/a2e98b663b94d2a3457d82ab9dd31d3b4592f805))
* **data-permissions:** add runtime-permissions data extension ([#1370](https://github.com/yschimke/compose-ai-tools/issues/1370)) ([3ce360c](https://github.com/yschimke/compose-ai-tools/commit/3ce360c4c0021a3730d8182e40c3635fcc5db512))
* **data-permissions:** sample preview + panel bundle entry ([#1395](https://github.com/yschimke/compose-ai-tools/issues/1395)) ([07d5938](https://github.com/yschimke/compose-ai-tools/commit/07d5938c6f26b2c27c795a7b2b4a2a1cc7d42bf1))
* **data-remotecompose:** add Remote Compose data extension ([#1378](https://github.com/yschimke/compose-ai-tools/issues/1378)) ([77dad95](https://github.com/yschimke/compose-ai-tools/commit/77dad958cad92e950828a09a035744d2ee86f3f6))
* **data/pseudolocale:** pseudolocalise stringResource on CMP Desktop ([#1339](https://github.com/yschimke/compose-ai-tools/issues/1339)) ([bf26bd8](https://github.com/yschimke/compose-ai-tools/commit/bf26bd884b6f203bc23754d97fa6be6f4ed11271))
* **notification-preview-runtime:** surface axis + content edge-case gallery ([#1354](https://github.com/yschimke/compose-ai-tools/issues/1354)) ([ce08046](https://github.com/yschimke/compose-ai-tools/commit/ce08046d1905df3d63d1005914bc880ddc434273))
* **renderer-desktop:** wire @ScrollingPreview LONG / GIF on CMP Desktop ([#1346](https://github.com/yschimke/compose-ai-tools/issues/1346)) ([42d634c](https://github.com/yschimke/compose-ai-tools/commit/42d634cfa59e5df067811871cf12a16fb1864a13))
* **samples:** MediaStyle + DecoratedCustomViewStyle in notification gallery ([#1350](https://github.com/yschimke/compose-ai-tools/issues/1350)) ([4ab03c9](https://github.com/yschimke/compose-ai-tools/commit/4ab03c9a3c645e293861ee6da10f573c6c2acbb4))
* **vscode-extension:** auto-light Errors chip on renderFailed via dispatcher ([#1365](https://github.com/yschimke/compose-ai-tools/issues/1365)) ([35ab16a](https://github.com/yschimke/compose-ai-tools/commit/35ab16a71869a1974a8df7f890793a669fe080cf))
* **vscode-extension:** paint compose/semantics mergeDescendants nodes with warning palette ([#1358](https://github.com/yschimke/compose-ai-tools/issues/1358)) ([653eb60](https://github.com/yschimke/compose-ai-tools/commit/653eb607ad833568f12ba710bd0944de1733e2ab))
* **vscode-extension:** surface a11y/touchTargets overlap count as a Size-cell badge ([#1359](https://github.com/yschimke/compose-ai-tools/issues/1359)) ([498aee1](https://github.com/yschimke/compose-ai-tools/commit/498aee12de426ceecb65f6d7ec7af906f0fb449f))
* **vscode-extension:** tint consumer nodes on resources row hover ([#1380](https://github.com/yschimke/compose-ai-tools/issues/1380)) ([aa6a20d](https://github.com/yschimke/compose-ai-tools/commit/aa6a20decc6e7a2cc78045a0d0978859e4d57a33))
* **vscode-extension:** tint consumer nodes on theming swatch hover ([#1376](https://github.com/yschimke/compose-ai-tools/issues/1376)) ([89f0b11](https://github.com/yschimke/compose-ai-tools/commit/89f0b112dbe5f0479b3571bf5b7f0c981aa4444d))
* **vscode-extension:** wire 'Copy as selector' row action on uia/hierarchy rows ([#1357](https://github.com/yschimke/compose-ai-tools/issues/1357)) ([365f09f](https://github.com/yschimke/compose-ai-tools/commit/365f09f20db360efed148c019b4c388b55a9c239))
* **vscode:** spike continuous-compile worker behind opt-in flag ([#1332](https://github.com/yschimke/compose-ai-tools/issues/1332)) ([0706934](https://github.com/yschimke/compose-ai-tools/commit/07069347edefd6595f5b86dca7eb119965b61a56))


### Bug Fixes

* **cli:** gate auto-inject buildscript classpath per project ([#1388](https://github.com/yschimke/compose-ai-tools/issues/1388)) ([ee7201a](https://github.com/yschimke/compose-ai-tools/commit/ee7201aa6315f3b72a95a91b00e11b1de28e382d))
* **contrib:** document `java -cp` as the only supported CLI invocation ([#1383](https://github.com/yschimke/compose-ai-tools/issues/1383)) ([01d5b50](https://github.com/yschimke/compose-ai-tools/commit/01d5b50a6b3698b6fed88a57dd4207535a2ee0b2))
* **daemon-desktop:** pin held-scene operations to a single-thread executor ([#1229](https://github.com/yschimke/compose-ai-tools/issues/1229)) ([#1348](https://github.com/yschimke/compose-ai-tools/issues/1348)) ([b04e5e5](https://github.com/yschimke/compose-ai-tools/commit/b04e5e51088d917c5b4ebd1f9d089ab04fee7f91))
* **data-extensions:** address recent codex P1s ([#1360](https://github.com/yschimke/compose-ai-tools/issues/1360)) ([#1387](https://github.com/yschimke/compose-ai-tools/issues/1387)) ([6804c88](https://github.com/yschimke/compose-ai-tools/commit/6804c8870121adebd7e67bde6d8f493111171a2c))
* **deps:** update gradle minor/patch ([#1377](https://github.com/yschimke/compose-ai-tools/issues/1377)) ([aa3d71b](https://github.com/yschimke/compose-ai-tools/commit/aa3d71b94990df89a88cef911b0a7e1318968372))
* **gradle-plugin:** open jdk.internal.access for Robolectric on JDK 25+ ([#1335](https://github.com/yschimke/compose-ai-tools/issues/1335)) ([cee6030](https://github.com/yschimke/compose-ai-tools/commit/cee60301e4e5b6c41c80ec94292becd75787d284))
* **notification-preview-runtime:** make surface parameter recompose-aware ([#1363](https://github.com/yschimke/compose-ai-tools/issues/1363)) ([#1385](https://github.com/yschimke/compose-ai-tools/issues/1385)) ([53ba5c6](https://github.com/yschimke/compose-ai-tools/commit/53ba5c68ae6d00bc155f304ca643c5ac14043ea5))
* **preview-discovery:** surface skip-reason warnings on failure path ([#1364](https://github.com/yschimke/compose-ai-tools/issues/1364)) ([#1386](https://github.com/yschimke/compose-ai-tools/issues/1386)) ([a5124cf](https://github.com/yschimke/compose-ai-tools/commit/a5124cf78ca9929d4ba8f20afd7bfb4a9e16642d))
* **samples:** default sdk-matrix to compileSdk=35 so JDK 17 baselines render ([#1330](https://github.com/yschimke/compose-ai-tools/issues/1330)) ([57ac24f](https://github.com/yschimke/compose-ai-tools/commit/57ac24f365da0826fab375cc7ed951f9356bfd29))
* **vscode-extension:** close recent codex robustness findings ([#1362](https://github.com/yschimke/compose-ai-tools/issues/1362)) ([#1366](https://github.com/yschimke/compose-ai-tools/issues/1366)) ([406e4ba](https://github.com/yschimke/compose-ai-tools/commit/406e4ba27aa4389b6dbf8c4ddcd0d3c94ff92ebf))
* **vscode-extension:** close recent codex robustness findings ([#1362](https://github.com/yschimke/compose-ai-tools/issues/1362)) ([#1379](https://github.com/yschimke/compose-ai-tools/issues/1379)) ([c3bbea8](https://github.com/yschimke/compose-ai-tools/commit/c3bbea8a3f34284722b9bef6318abdf1e9de8fd9))
* **vscode-extension:** hide bundle legend when leaving focus mode ([#1347](https://github.com/yschimke/compose-ai-tools/issues/1347)) ([3bc8e96](https://github.com/yschimke/compose-ai-tools/commit/3bc8e96d2987bb8b8c2f0814a3e1d27fd81b04de))
* **vscode-extension:** include JVM exit code + stderr tail when daemon spawn fails ([#1343](https://github.com/yschimke/compose-ai-tools/issues/1343)) ([fb722e2](https://github.com/yschimke/compose-ai-tools/commit/fb722e2600153b638ec16b4e042f03895a4520cd))
* **vscode:** skip auto-inject when settings.gradle includes :gradle-plugin ([#1337](https://github.com/yschimke/compose-ai-tools/issues/1337)) ([ca9a0b7](https://github.com/yschimke/compose-ai-tools/commit/ca9a0b7851a45ae0ed3190ba46d0228e4f3d01d3))
* **vscode:** skip Gradle auto-render during activation when the daemon will render ([#1331](https://github.com/yschimke/compose-ai-tools/issues/1331)) ([86b4493](https://github.com/yschimke/compose-ai-tools/commit/86b4493b2138e6698ea5866fea6427d018a6ff80))

## [0.11.0](https://github.com/yschimke/compose-ai-tools/compare/v0.10.19...v0.11.0) (2026-05-22)


### ⚠ BREAKING CHANGES

* **contrib:** Phase C cutover — non-Gradle code moves to compose-ai-contrib ([#1318](https://github.com/yschimke/compose-ai-tools/issues/1318))
* **gradle-plugin:** namespace all task names under composePreview* ([#1314](https://github.com/yschimke/compose-ai-tools/issues/1314))
* remove the legacy ImageProcessor seam ([#1286](https://github.com/yschimke/compose-ai-tools/issues/1286))

### Features

* add Amper Android sample and Bazel APK target ([#1276](https://github.com/yschimke/compose-ai-tools/issues/1276)) ([08b193d](https://github.com/yschimke/compose-ai-tools/commit/08b193d8c2b2a266e9ce3d018bcbb18ee70d7616))
* **bazel-apk:** try Kotlin 2.x toolchain with the bundled Compose plugin ([#1296](https://github.com/yschimke/compose-ai-tools/issues/1296)) ([64345cc](https://github.com/yschimke/compose-ai-tools/commit/64345cc1cd790165a8d4f15418b51763a8820307))
* **daemon-android:** route PreviewOverrides through interactive acquire ([#1317](https://github.com/yschimke/compose-ai-tools/issues/1317)) ([c2e5052](https://github.com/yschimke/compose-ai-tools/commit/c2e5052e6c1a2f829f0db6387c54e91107ff7267))
* **daemon-desktop:** advertise touch-overlay + keyboard-band data-extension descriptors ([#1312](https://github.com/yschimke/compose-ai-tools/issues/1312)) ([56ea2d4](https://github.com/yschimke/compose-ai-tools/commit/56ea2d4e87684607d02f16d0b23eab766fabb152))
* **daemon-desktop:** honor orientation override via widthPx/heightPx swap ([#1288](https://github.com/yschimke/compose-ai-tools/issues/1288)) ([0e92121](https://github.com/yschimke/compose-ai-tools/commit/0e921215f5f201c625bc725bec39d4edbe69ac0e))
* **daemon-desktop:** touch-event visualization extension + multi-pointer pinch dispatch ([#1301](https://github.com/yschimke/compose-ai-tools/issues/1301)) ([9700b4a](https://github.com/yschimke/compose-ai-tools/commit/9700b4ad0b85897c582cf92883c75ea751e7ed22))
* **daemon-desktop:** touch-event visualization extension + multi-pointer pinch dispatch ([#1304](https://github.com/yschimke/compose-ai-tools/issues/1304)) ([db942ef](https://github.com/yschimke/compose-ai-tools/commit/db942ef8cc4dac0eda03631da40ad05a64a42e27))
* **daemon-launch-builder:** new :daemon-launch-builder library + CLI ([#1309](https://github.com/yschimke/compose-ai-tools/issues/1309)) ([17c5cf8](https://github.com/yschimke/compose-ai-tools/commit/17c5cf8a66008f5443fabe4bc7ccc8121dddf83f))
* **data-focus:** add desktop connector + wire focus override into desktop daemon ([#1289](https://github.com/yschimke/compose-ai-tools/issues/1289)) ([797424b](https://github.com/yschimke/compose-ai-tools/commit/797424b8dfdb3f348f0c3c9944241a51c468b879))
* **data-touch-overlay:** shared connector module + Android parity ([#1313](https://github.com/yschimke/compose-ai-tools/issues/1313)) ([43b5968](https://github.com/yschimke/compose-ai-tools/commit/43b59683dcdbf7c0bfca8ee290042e43bd8f6f7f))
* **data/keyboard:** publish WindowInsets.ime so layouts adapt to the band ([#1303](https://github.com/yschimke/compose-ai-tools/issues/1303)) ([acf2c34](https://github.com/yschimke/compose-ai-tools/commit/acf2c34caa7ae7eb8ed10620741679edd9793f47))
* **data/keyboard:** soft-keyboard overlay as a data extension ([#1298](https://github.com/yschimke/compose-ai-tools/issues/1298)) ([5fe29a9](https://github.com/yschimke/compose-ai-tools/commit/5fe29a93b03f1f1eea015b7450e5563732d64f81))
* **gradle-plugin:** namespace all task names under composePreview* ([#1314](https://github.com/yschimke/compose-ai-tools/issues/1314)) ([11848f4](https://github.com/yschimke/compose-ai-tools/commit/11848f40118f6d32df8cd5c94c48994a7a3d6ec1))
* **interactive:** expand keycode table with F-keys, numpad, punctuation, locks ([#1290](https://github.com/yschimke/compose-ai-tools/issues/1290)) ([0a96837](https://github.com/yschimke/compose-ai-tools/commit/0a9683738a58509fc6c068efd599905d0179a04f))
* **preview-discovery:** add java -jar CLI entry point ([#1307](https://github.com/yschimke/compose-ai-tools/issues/1307)) ([f91998c](https://github.com/yschimke/compose-ai-tools/commit/f91998ca950d448ae4265f918199a60818d8ad6a))
* publish :notification-preview-runtime artifact with sidecar emission ([#1281](https://github.com/yschimke/compose-ai-tools/issues/1281)) ([e6cb0de](https://github.com/yschimke/compose-ai-tools/commit/e6cb0def49ee37adf06eb158a7a0b854526c920a))
* **render-cli:** new :render-cli library — java -jar over render-session-subprocess ([#1310](https://github.com/yschimke/compose-ai-tools/issues/1310)) ([92e2e1e](https://github.com/yschimke/compose-ai-tools/commit/92e2e1ef60a3e951dc4d63cca7639d6f142abee4))
* **renderer-android:** Compose-free notification bitmap renderer primitive ([#1280](https://github.com/yschimke/compose-ai-tools/issues/1280)) ([812412e](https://github.com/yschimke/compose-ai-tools/commit/812412ec47bca72ae9d9e3230f12b67c28a8d55c))
* **renderer-android:** structured-fields JSON sidecar for @Notificat… ([#1271](https://github.com/yschimke/compose-ai-tools/issues/1271)) ([56d9cec](https://github.com/yschimke/compose-ai-tools/commit/56d9cec96b7841c5a0bc8bb96676353e8b219f5b))
* **samples:** BigPictureStyle entry in the notification gallery ([#1272](https://github.com/yschimke/compose-ai-tools/issues/1272)) ([a7ae7cb](https://github.com/yschimke/compose-ai-tools/commit/a7ae7cb4c4a55c2a3dcd03328d637444e33600b1))
* **samples:** drawing canvas with tap/drag/pinch gestures ([#1321](https://github.com/yschimke/compose-ai-tools/issues/1321)) ([17daa64](https://github.com/yschimke/compose-ai-tools/commit/17daa64ecbcccb27b1d1d43d6ade5907dd37c1e0))
* **vscode-extension:** add per-card Controls toggle for interactive … ([#1275](https://github.com/yschimke/compose-ai-tools/issues/1275)) ([4f075da](https://github.com/yschimke/compose-ai-tools/commit/4f075da4b0dd2deb7478529174d4c2476c85c2a9))
* **vscode:** per-card touch-overlay + keyboard-band toggle buttons ([#1308](https://github.com/yschimke/compose-ai-tools/issues/1308)) ([0ef2599](https://github.com/yschimke/compose-ai-tools/commit/0ef25995ed0c8b8858c4c2e7e97470c1127c29f0))


### Bug Fixes

* align notification fixture ids with real preview naming ([#1268](https://github.com/yschimke/compose-ai-tools/issues/1268)) ([c730e9a](https://github.com/yschimke/compose-ai-tools/commit/c730e9aa345f69f367871bf5c4b59c6d7b3f3f4a))
* **bazel-apk:** pin Bazel 7 to clear the JavaPluginInfo load error ([#1292](https://github.com/yschimke/compose-ai-tools/issues/1292)) ([4fb7ff2](https://github.com/yschimke/compose-ai-tools/commit/4fb7ff2187629f5f196b2012d30988599c78ba5d))
* **ci:** flip snapshot probe cells to expect: fail (UnknownSdk; upstream hasn't shipped API 37 jar) ([#1270](https://github.com/yschimke/compose-ai-tools/issues/1270)) ([33f3169](https://github.com/yschimke/compose-ai-tools/commit/33f3169a446bfa46842608d09ca3d62aae8a51dd))
* **daemon-android:** wrap held composition with previewOverrideExtensions chain ([#1320](https://github.com/yschimke/compose-ai-tools/issues/1320)) ([0c9df6d](https://github.com/yschimke/compose-ai-tools/commit/0c9df6debebd1eccbb70125ea2d80edd5c053f13))
* **daemon-desktop:** make orientation-override swap idempotent ([#1294](https://github.com/yschimke/compose-ai-tools/issues/1294)) ([eba71ed](https://github.com/yschimke/compose-ai-tools/commit/eba71edb3586cf31aed87b8db1590279d76de9d9))
* **interactive:** map NumpadComma to KEYCODE_NUMPAD_COMMA (159) ([#1295](https://github.com/yschimke/compose-ai-tools/issues/1295)) ([b04cc5f](https://github.com/yschimke/compose-ai-tools/commit/b04cc5febee95bac6f7bc0c932a16a2c58334fb1))
* **renderer-android:** paint a uiMode-aware surface behind @Notificat… ([#1274](https://github.com/yschimke/compose-ai-tools/issues/1274)) ([e4e2091](https://github.com/yschimke/compose-ai-tools/commit/e4e2091d6cb33f7295a1b85daf5f96a1512fe204))
* **vscode-extension:** clear Controls flag on plain-toggle deactivate + follow-focus teardown ([#1285](https://github.com/yschimke/compose-ai-tools/issues/1285)) ([e4b74c8](https://github.com/yschimke/compose-ai-tools/commit/e4b74c8a85e8f7b32b53b077e45cc93410b9bf1a))
* **vscode:** gate per-card touch + keyboard toggles on the new data-extension descriptors ([#1315](https://github.com/yschimke/compose-ai-tools/issues/1315)) ([ec9dce0](https://github.com/yschimke/compose-ai-tools/commit/ec9dce01fd7b8c77782adb7634203d12e41e7508))


### Code Refactoring

* **contrib:** Phase C cutover — non-Gradle code moves to compose-ai-contrib ([#1318](https://github.com/yschimke/compose-ai-tools/issues/1318)) ([74e8ea3](https://github.com/yschimke/compose-ai-tools/commit/74e8ea3699a0aa3fe70b10b9d593ed48a5dd207a))
* remove the legacy ImageProcessor seam ([#1286](https://github.com/yschimke/compose-ai-tools/issues/1286)) ([b80d1d7](https://github.com/yschimke/compose-ai-tools/commit/b80d1d70d6374ba27dcf9d7206727e805d1d8f20))

## [0.10.19](https://github.com/yschimke/compose-ai-tools/compare/v0.10.18...v0.10.19) (2026-05-19)


### Features

* add Bazel sample for resources discovery ([#1256](https://github.com/yschimke/compose-ai-tools/issues/1256)) ([842a53d](https://github.com/yschimke/compose-ai-tools/commit/842a53da54cfa8a6622d217ccb553d7eabbf0f4a))
* add notification preview composable and CI workflow ([#1259](https://github.com/yschimke/compose-ai-tools/issues/1259)) ([ae36685](https://github.com/yschimke/compose-ai-tools/commit/ae36685d2210ced2cde5d5d73791a315959ecb48))
* add notification preview support ([#1255](https://github.com/yschimke/compose-ai-tools/issues/1255)) ([d4e2f41](https://github.com/yschimke/compose-ai-tools/commit/d4e2f4130e409d3daaffd619fb95b53fedd30c37))
* add NotificationStyleGallery with messaging, inbox, and actions previews ([#1263](https://github.com/yschimke/compose-ai-tools/issues/1263)) ([0b832fb](https://github.com/yschimke/compose-ai-tools/commit/0b832fb35a37ba6727069bd1814cbfba335bf67c))
* **ci:** add Robolectric snapshot probe cells to SDK matrix ([#1260](https://github.com/yschimke/compose-ai-tools/issues/1260)) ([591d686](https://github.com/yschimke/compose-ai-tools/commit/591d6864fd12dd61e8406cac852381636ff5d976))
* **ci:** add SDK compatibility matrix workflow + docs ([#1258](https://github.com/yschimke/compose-ai-tools/issues/1258)) ([d423fe4](https://github.com/yschimke/compose-ai-tools/commit/d423fe4d31a8fa1b0a679b3c5645b59909f98d6a))


### Bug Fixes

* **ci:** point SDK matrix snapshot probe at the new Sonatype Central URL ([#1267](https://github.com/yschimke/compose-ai-tools/issues/1267)) ([a66afc0](https://github.com/yschimke/compose-ai-tools/commit/a66afc058754b96a7eb8728dd1a14fac91cca72d))
* **ci:** wire SDK matrix JDK axis through toolchain + surface per-cell failure reasons ([#1265](https://github.com/yschimke/compose-ai-tools/issues/1265)) ([23de5bc](https://github.com/yschimke/compose-ai-tools/commit/23de5bc7c07b327d89d28da5859e5a954e70da3f))
* **gradle-plugin:** auto-detect Robolectric SDK from consumer compileSdk ([#1254](https://github.com/yschimke/compose-ai-tools/issues/1254)) ([8318c94](https://github.com/yschimke/compose-ai-tools/commit/8318c941da974646e40b3a5b189539253eb98507))
* restore default plugin repos when seeding mavenLocal ([#1261](https://github.com/yschimke/compose-ai-tools/issues/1261)) ([74f9530](https://github.com/yschimke/compose-ai-tools/commit/74f95301100c90a762afa26a3d6ea6aa1431ca39))

## [0.10.18](https://github.com/yschimke/compose-ai-tools/compare/v0.10.17...v0.10.18) (2026-05-18)


### Bug Fixes

* **daemon:** use className= in ShadowAmbientLifecycleObserver @Implements ([#1247](https://github.com/yschimke/compose-ai-tools/issues/1247)) ([955546c](https://github.com/yschimke/compose-ai-tools/commit/955546c26816caba9a7839ac90dc5ae63e0ee97f))
* **gradle-plugin:** harden renderPreviews against missing android.jar ([#1243](https://github.com/yschimke/compose-ai-tools/issues/1243)) ([#1245](https://github.com/yschimke/compose-ai-tools/issues/1245)) ([2f53ebd](https://github.com/yschimke/compose-ai-tools/commit/2f53ebd2f05741cc02193d6cad0ba1b17fb81419))
* scroll long/gif preview cards no longer show static base capture ([#1241](https://github.com/yschimke/compose-ai-tools/issues/1241)) ([ca74a28](https://github.com/yschimke/compose-ai-tools/commit/ca74a288f13e18756f663868dcdb2728d41e3d4c))
* **vscode-extension:** buffer daemon stderr across pipe chunk boundaries ([#1238](https://github.com/yschimke/compose-ai-tools/issues/1238)) ([521a51a](https://github.com/yschimke/compose-ai-tools/commit/521a51abc2ad40f01fb51dabde5b90a839e3887d))
* **vscode:** only restore focus mode on boot when the focused preview is still present ([#1240](https://github.com/yschimke/compose-ai-tools/issues/1240)) ([20018fc](https://github.com/yschimke/compose-ai-tools/commit/20018fcb078e04714dd438c510b6a117313134ac))

## [0.10.17](https://github.com/yschimke/compose-ai-tools/compare/v0.10.16...v0.10.17) (2026-05-18)


### Features

* add bundle viewer panel for preview bundles ([#1213](https://github.com/yschimke/compose-ai-tools/issues/1213)) ([3234934](https://github.com/yschimke/compose-ai-tools/commit/32349345f5ffe7b615ec49615a49bd3889bad9cb))
* add data extension progress tracking and doctor report ([#1197](https://github.com/yschimke/compose-ai-tools/issues/1197)) ([2e35236](https://github.com/yschimke/compose-ai-tools/commit/2e3523603c66ba012c533e576364c50e1c87ea3b))
* add early-feature preview bundle export ([#1196](https://github.com/yschimke/compose-ai-tools/issues/1196)) ([76b9a45](https://github.com/yschimke/compose-ai-tools/commit/76b9a455a7cfd11f39e86b2638646cc79a96fe85))
* add keyboard and rotary scroll input support to interactive mode ([#1212](https://github.com/yschimke/compose-ai-tools/issues/1212)) ([ede5ef7](https://github.com/yschimke/compose-ai-tools/commit/ede5ef786df65a3bc967f19c32f6a07e513d6d84))
* add live-mode streaming support to bundle viewer ([#1216](https://github.com/yschimke/compose-ai-tools/issues/1216)) ([dcf5f80](https://github.com/yschimke/compose-ai-tools/commit/dcf5f80536459d85e6ecebcce74429ba0d31b4da))
* **daemon-desktop:** advertise fonts/used and displayfilter on desktop ([#1201](https://github.com/yschimke/compose-ai-tools/issues/1201)) ([#1209](https://github.com/yschimke/compose-ai-tools/issues/1209)) ([3c2056f](https://github.com/yschimke/compose-ai-tools/commit/3c2056f4388d8e31517043f6975857496d7b826f))
* gate history surface behind post-1.0 feature flag ([#1220](https://github.com/yschimke/compose-ai-tools/issues/1220)) ([f741de6](https://github.com/yschimke/compose-ai-tools/commit/f741de6481b1eaf7ea8ee7f4fa05af87e1ed58fe))
* implement compose/recomposition observer for Android interactive sessions ([#1211](https://github.com/yschimke/compose-ai-tools/issues/1211)) ([6732397](https://github.com/yschimke/compose-ai-tools/commit/6732397e0d8c9102e25563fddf9c9ea02394c52e))
* **vscode-extension:** pull current branch before F5 build ([#1199](https://github.com/yschimke/compose-ai-tools/issues/1199)) ([d74fb35](https://github.com/yschimke/compose-ai-tools/commit/d74fb35bed0ee4793f5e73b6bb3784c7b7738686))
* **vscode:** show merged child text in a11y legend, render table as merge-aware tree ([#1190](https://github.com/yschimke/compose-ai-tools/issues/1190)) ([017c826](https://github.com/yschimke/compose-ai-tools/commit/017c8264b198834ce39a3d6455c8c45f8fc2003b))
* **vscode:** wire recording through the per-tab bundle daemon ([#1219](https://github.com/yschimke/compose-ai-tools/issues/1219)) ([27fcbb5](https://github.com/yschimke/compose-ai-tools/commit/27fcbb5d983c66aad79df3841a68ea66f595c186))


### Bug Fixes

* **daemon-android:** plug recomposition bridge leaks on session shutdown ([#1215](https://github.com/yschimke/compose-ai-tools/issues/1215)) ([4dee1f9](https://github.com/yschimke/compose-ai-tools/commit/4dee1f985f3787f1fd7f94f996fd3ff597ec7472))
* **daemon-harness:** propagate composeai.history.enabled to spawned daemons ([#1222](https://github.com/yschimke/compose-ai-tools/issues/1222)) ([8beea39](https://github.com/yschimke/compose-ai-tools/commit/8beea3954d6025989874fea961e886dac64b0fcb))
* **daemon:** survive missing connector classes on launch classpath ([#1236](https://github.com/yschimke/compose-ai-tools/issues/1236)) ([0fc1bbc](https://github.com/yschimke/compose-ai-tools/commit/0fc1bbc6db634763a0be0fa54cb38ffcc5c47eb9))
* **deps:** update gradle minor/patch ([#1233](https://github.com/yschimke/compose-ai-tools/issues/1233)) ([265eb19](https://github.com/yschimke/compose-ai-tools/commit/265eb1921e263a48757679e3ede6bf1b91ee4ed2))
* guard daemon readiness check against uninitialized liveState ([#1195](https://github.com/yschimke/compose-ai-tools/issues/1195)) ([9dbf945](https://github.com/yschimke/compose-ai-tools/commit/9dbf945255c7ee634016fe4953b287cd58d3d6b3))
* rename annotated overlays to prevent collisions in flat output ([#1198](https://github.com/yschimke/compose-ai-tools/issues/1198)) ([038eb03](https://github.com/yschimke/compose-ai-tools/commit/038eb03135f33691f454cd886886afa6ec31c0ae))
* **vscode-extension:** drain pending data/subscribe before warm-up render ([#1192](https://github.com/yschimke/compose-ai-tools/issues/1192)) ([7e5619c](https://github.com/yschimke/compose-ai-tools/commit/7e5619cd4c132086d618493ace31b5127a6e8923))
* **vscode-extension:** make Theming swatches visible and previewable ([#1193](https://github.com/yschimke/compose-ai-tools/issues/1193)) ([23c9e47](https://github.com/yschimke/compose-ai-tools/commit/23c9e47f5063c82c719ae6f37da771f82e5c34dd))
* **vscode-extension:** tighten quiet/normal log filtering ([#1189](https://github.com/yschimke/compose-ai-tools/issues/1189)) ([f374dc9](https://github.com/yschimke/compose-ai-tools/commit/f374dc9ecd71e8fb2524aa82f6a49b16057032d4))
* **vscode-preview:** absorb harness AA jitter with pixelmatch ([#1194](https://github.com/yschimke/compose-ai-tools/issues/1194)) ([2cdff46](https://github.com/yschimke/compose-ai-tools/commit/2cdff46e59992da0c62087feac7d5d29d66beb05))

## [0.10.16](https://github.com/yschimke/compose-ai-tools/compare/v0.10.15...v0.10.16) (2026-05-16)


### Features

* anchor bundle chip bar to panel footer ([#1174](https://github.com/yschimke/compose-ai-tools/issues/1174)) ([9f3916c](https://github.com/yschimke/compose-ai-tools/commit/9f3916c72ea7d6afb376426439930b9ac581a1e7))
* batch data extension subscriptions to fix partial bundle data ([#1175](https://github.com/yschimke/compose-ai-tools/issues/1175)) ([d45d7e0](https://github.com/yschimke/compose-ai-tools/commit/d45d7e0875f0fb7278b349520a113d8dc3884dd1))
* **daemon/android:** advertise compose/recomposition kind via a stub registry ([#1186](https://github.com/yschimke/compose-ai-tools/issues/1186)) ([12fefd2](https://github.com/yschimke/compose-ai-tools/commit/12fefd22eb098fe17a167a74dd264b727b7ebc39))
* **daemon/android:** advertise compose/recomposition kind via a stub registry ([#1188](https://github.com/yschimke/compose-ai-tools/issues/1188)) ([549d54d](https://github.com/yschimke/compose-ai-tools/commit/549d54d51845689d09627856ca7fd8f914c25c01))


### Bug Fixes

* align JSON export shape with daemon wire format ([#1178](https://github.com/yschimke/compose-ai-tools/issues/1178)) ([e61967f](https://github.com/yschimke/compose-ai-tools/commit/e61967fa9e9551f127237ff5529038963e16c95b))
* detect pre-applied plugin via version catalog aliases ([#1183](https://github.com/yschimke/compose-ai-tools/issues/1183)) ([087d016](https://github.com/yschimke/compose-ai-tools/commit/087d01669dac5d02985e425054dc24e0b5480663))
* filter bare-status progress noise from Gradle output ([#1182](https://github.com/yschimke/compose-ai-tools/issues/1182)) ([ff8fb4e](https://github.com/yschimke/compose-ai-tools/commit/ff8fb4e230e1bcf1c41faa611b64d6e1d77d3973))
* gate panel data/subscribe by daemon-advertised kinds, use displayfilter/variants ([#1187](https://github.com/yschimke/compose-ai-tools/issues/1187)) ([dd049f4](https://github.com/yschimke/compose-ai-tools/commit/dd049f4a292bfce6d9ef78ae1d0bb7ffe0286250))
* use plain console output to prevent ANSI artifacts in logs ([#1177](https://github.com/yschimke/compose-ai-tools/issues/1177)) ([08680ee](https://github.com/yschimke/compose-ai-tools/commit/08680eec072d29de18d14364ba22416aca67b98a))
* **vscode-extension:** close the a11y-only race where caches stayed empty ([#1184](https://github.com/yschimke/compose-ai-tools/issues/1184)) ([f09d866](https://github.com/yschimke/compose-ai-tools/commit/f09d866a48cb7684c1a68f83cd76d5fc4b8ba63f))
* **vscode-extension:** snap chip activation back to default kinds when stored set is empty ([#1185](https://github.com/yschimke/compose-ai-tools/issues/1185)) ([66247e4](https://github.com/yschimke/compose-ai-tools/commit/66247e4b72f63da966ac87f4ce0b8a6ff59b6a44))
* **vscode-extension:** teach harness contract matcher about batched setDataExtensionEnabled ([#1181](https://github.com/yschimke/compose-ai-tools/issues/1181)) ([97cb25d](https://github.com/yschimke/compose-ai-tools/commit/97cb25d9f91d3850292bf0f6a9d9f7fa08a59c02))

## [0.10.15](https://github.com/yschimke/compose-ai-tools/compare/v0.10.14...v0.10.15) (2026-05-16)


### Features

* add a11y-wear fixture and improve legend layout ([#1169](https://github.com/yschimke/compose-ai-tools/issues/1169)) ([733a55b](https://github.com/yschimke/compose-ai-tools/commit/733a55b35b9b5a321e85caef61692d26b5d99006))
* add plugin pre-application detection and warning ([#1171](https://github.com/yschimke/compose-ai-tools/issues/1171)) ([5850bfa](https://github.com/yschimke/compose-ai-tools/commit/5850bfaa865bf3859849c03ba43b4ac775c4ad6d))
* **bundle-viewer:** single-window desktop app that opens & renders bundles live ([#1165](https://github.com/yschimke/compose-ai-tools/issues/1165)) ([74a3064](https://github.com/yschimke/compose-ai-tools/commit/74a3064a41bcfaedf1b1e5b05b9a0666b81fe4aa))
* **cli:** bundle render — re-render a packed .png outside any Gradle project ([#1162](https://github.com/yschimke/compose-ai-tools/issues/1162)) ([feea3f4](https://github.com/yschimke/compose-ai-tools/commit/feea3f41c3dec3922b25fa80ced904533fe0be2c))
* **cli:** bundle render — re-render a packed .png outside any Gradle project ([#1164](https://github.com/yschimke/compose-ai-tools/issues/1164)) ([caaea9e](https://github.com/yschimke/compose-ai-tools/commit/caaea9eab8d0b43b848083afc8a50619f957cb5f))
* **release:** ship the bundle viewer alongside the CLI and MCP ([#1167](https://github.com/yschimke/compose-ai-tools/issues/1167)) ([775ac1b](https://github.com/yschimke/compose-ai-tools/commit/775ac1b08564a7dd8f9dbcd1af90528dc756f464))


### Bug Fixes

* dedupe concurrent discoverPreviews and spare critical tasks from cancellation ([#1170](https://github.com/yschimke/compose-ai-tools/issues/1170)) ([11331ba](https://github.com/yschimke/compose-ai-tools/commit/11331ba15a11bdb1f78f933f1833a1a19aa7a2ee))
* treat missing `merged` field as true in accessibility nodes ([#1173](https://github.com/yschimke/compose-ai-tools/issues/1173)) ([519f2dc](https://github.com/yschimke/compose-ai-tools/commit/519f2dca9be10bc3b031031252e06c7853e81665))

## [0.10.14](https://github.com/yschimke/compose-ai-tools/compare/v0.10.13...v0.10.14) (2026-05-15)


### Features

* add portable preview bundle support (PNG+ZIP polyglot) ([#1159](https://github.com/yschimke/compose-ai-tools/issues/1159)) ([e575cc1](https://github.com/yschimke/compose-ai-tools/commit/e575cc1fa7497a09610677a35b5b46d2da3d3dca))


### Bug Fixes

* inject compose-foundation to fix tile preview rendering ([#1161](https://github.com/yschimke/compose-ai-tools/issues/1161)) ([a2992a1](https://github.com/yschimke/compose-ai-tools/commit/a2992a16542d20b64dce7f90892cb015365c7338))
* suppress JDK 24+ restricted method warnings from Gradle Tooling API ([#1158](https://github.com/yschimke/compose-ai-tools/issues/1158)) ([5e1b9c3](https://github.com/yschimke/compose-ai-tools/commit/5e1b9c3577bea9bc9ea7db799d24c8b34c918ff3))

## [0.10.13](https://github.com/yschimke/compose-ai-tools/compare/v0.10.12...v0.10.13) (2026-05-15)


### Features

* add Fish shell completions for compose-preview CLI ([#1156](https://github.com/yschimke/compose-ai-tools/issues/1156)) ([8516421](https://github.com/yschimke/compose-ai-tools/commit/8516421e21c3a6f3395c115ef77d4bef9aa513c9))
* **cli:** auto-inject the preview plugin via --init-script ([#1110](https://github.com/yschimke/compose-ai-tools/issues/1110)) ([f53f0a2](https://github.com/yschimke/compose-ai-tools/commit/f53f0a2258aac612a2b160114382d4fe8893b7f9))
* enable a11y extension in daemon render sessions ([#1136](https://github.com/yschimke/compose-ai-tools/issues/1136)) ([2a8618c](https://github.com/yschimke/compose-ai-tools/commit/2a8618ce6c341ac7d7474009074826982fddd698))
* **mcp:** expose RenderSession view on SupervisedDaemon ([#1147](https://github.com/yschimke/compose-ai-tools/issues/1147)) ([619aaee](https://github.com/yschimke/compose-ai-tools/commit/619aaee7dbf0af4e77f6a8b5116a757917e3b9d9))
* **render-session:** in-process Compose Desktop backend ([#1141](https://github.com/yschimke/compose-ai-tools/issues/1141)) ([547de0c](https://github.com/yschimke/compose-ai-tools/commit/547de0ccf571a001d3fb15ca10ec9bcb9e7a5dca))
* **render-session:** supported library for driving render sessions ([#1133](https://github.com/yschimke/compose-ai-tools/issues/1133)) ([f9f138f](https://github.com/yschimke/compose-ai-tools/commit/f9f138ff72ced15997c26f1a5c79db569ef4b3c6))
* **vscode-extension:** add a11y-findings preview-harness fixture ([#1131](https://github.com/yschimke/compose-ai-tools/issues/1131)) ([8c5e329](https://github.com/yschimke/compose-ai-tools/commit/8c5e329fc4ca8581a001364478e8be154e5bba2f))
* **vscode-extension:** add headless preview-harness for design iteration ([#1129](https://github.com/yschimke/compose-ai-tools/issues/1129)) ([d5b17a2](https://github.com/yschimke/compose-ai-tools/commit/d5b17a2e37714a65b13a2330f8ef4fcfed6df964))
* **vscode-extension:** adopt bundle-legend in Inspection / Text / History bundles ([#1142](https://github.com/yschimke/compose-ai-tools/issues/1142)) ([c013afc](https://github.com/yschimke/compose-ai-tools/commit/c013afce625e08558199a9d271d7765acdc1bf7b))
* **vscode-extension:** bridge a11y chip subscription to Gradle render ([#1117](https://github.com/yschimke/compose-ai-tools/issues/1117)) ([bf78bf1](https://github.com/yschimke/compose-ai-tools/commit/bf78bf12a921ccaee4bd5972793eaaa747c47f1b))
* **vscode-extension:** generic bundle-legend panel beside the focused preview ([#1130](https://github.com/yschimke/compose-ai-tools/issues/1130)) ([c5db992](https://github.com/yschimke/compose-ai-tools/commit/c5db992a75e3cf54e30e6631f95dc8a9ab1068fa))
* **vscode-extension:** render a11y/touchTargets in the Accessibility bundle tab ([#1128](https://github.com/yschimke/compose-ai-tools/issues/1128)) ([89656d1](https://github.com/yschimke/compose-ai-tools/commit/89656d1c43d264a283a4f2c491b96f865aa087cf))
* **vscode-extension:** row-click detail for Text and Inspection bundles ([#1148](https://github.com/yschimke/compose-ai-tools/issues/1148)) ([563e598](https://github.com/yschimke/compose-ai-tools/commit/563e598ae0c4be27ab4bcac075806db6149317be))
* **vscode-extension:** row-click detail for the History diff bundle ([#1149](https://github.com/yschimke/compose-ai-tools/issues/1149)) ([e33be16](https://github.com/yschimke/compose-ai-tools/commit/e33be1683a0bc739f633313344640b55edc5aaf3))
* **vscode-extension:** row-click detail panel for the Accessibility bundle ([#1138](https://github.com/yschimke/compose-ai-tools/issues/1138)) ([396402c](https://github.com/yschimke/compose-ai-tools/commit/396402cd08ad6310987b12493516af7cdc1fb919))
* **vscode-extension:** tree-indent unmerged a11y rows ([#1140](https://github.com/yschimke/compose-ai-tools/issues/1140)) ([acdfe70](https://github.com/yschimke/compose-ai-tools/commit/acdfe7010f57a56b089b010dea8ca53fadf47a6c))
* **vscode-extension:** typed event bus for webview CustomEvents ([#1119](https://github.com/yschimke/compose-ai-tools/issues/1119)) ([f048b98](https://github.com/yschimke/compose-ai-tools/commit/f048b98733769848ada58995ad547751ce2bb138))


### Bug Fixes

* **ci:** drive a11y-report action via compose-preview a11y ([#1137](https://github.com/yschimke/compose-ai-tools/issues/1137)) ([248d734](https://github.com/yschimke/compose-ai-tools/commit/248d73451735c31d7b33fcc7fcd8049268525f2f))
* **daemon:** route TILE-kind previews through TilePreviewComposable ([#1120](https://github.com/yschimke/compose-ai-tools/issues/1120)) ([e34f8fd](https://github.com/yschimke/compose-ai-tools/commit/e34f8fd1a0a3bccec0d00728fd94736f5c6563a3))
* **gradle-plugin:** inject compose-ui floor on main variant for tile-only consumers ([#1134](https://github.com/yschimke/compose-ai-tools/issues/1134)) ([8dc5173](https://github.com/yschimke/compose-ai-tools/commit/8dc517315c027074425f5d664194519a7545da33))
* **vscode-extension:** guard reflectLegendActiveTab against focusController TDZ ([#1135](https://github.com/yschimke/compose-ai-tools/issues/1135)) ([1082b28](https://github.com/yschimke/compose-ai-tools/commit/1082b2879058f9cdea388fef24390b0fdc0146fb))
* **vscode-extension:** hide data tabs outside focus mode and drop the More tab ([#1114](https://github.com/yschimke/compose-ai-tools/issues/1114)) ([6a37ea7](https://github.com/yschimke/compose-ai-tools/commit/6a37ea706ec8e0e5280b97c5016e33f58de6521b))
* **vscode-extension:** keep last image on minimal-mode save and add apply-plugin link ([#1112](https://github.com/yschimke/compose-ai-tools/issues/1112)) ([d7d001f](https://github.com/yschimke/compose-ai-tools/commit/d7d001f71af1cc31eed18f42090aa23474d94e19))
* **vscode-extension:** show bundle labels next to chip icons ([#1115](https://github.com/yschimke/compose-ai-tools/issues/1115)) ([8277171](https://github.com/yschimke/compose-ai-tools/commit/8277171d1b334ec99fe6b0aa1e454003fcf3aa50))
* **vscode-extension:** silence expected createImageBitmap noise in the stream painter (closes [#1125](https://github.com/yschimke/compose-ai-tools/issues/1125)) ([#1152](https://github.com/yschimke/compose-ai-tools/issues/1152)) ([20286fc](https://github.com/yschimke/compose-ai-tools/commit/20286fcf577660788c87f108b63683b5c4bcb591))
* **vscode-extension:** swap minimal→full backend in place when auto-inject reveals the plugin ([#1116](https://github.com/yschimke/compose-ai-tools/issues/1116)) ([05164d3](https://github.com/yschimke/compose-ai-tools/commit/05164d34dbe6e7f129717ae4f8c0a6dcbedeca87))
* **vscode-extension:** swap minimal→full backend in place when auto-inject reveals the plugin ([#1118](https://github.com/yschimke/compose-ai-tools/issues/1118)) ([26f1270](https://github.com/yschimke/compose-ai-tools/commit/26f1270567a031e4cd9797d854ee90a097f7a8eb))
* **vscode-extension:** wire Resources bundle, gate bundle UI on earlyFeatures, satisfy webview CSP ([#1124](https://github.com/yschimke/compose-ai-tools/issues/1124)) ([54e7b3c](https://github.com/yschimke/compose-ai-tools/commit/54e7b3c0e0fe9c88c4e7a9e9e3d9f3b0584ffa84))

## [0.10.12](https://github.com/yschimke/compose-ai-tools/compare/v0.10.11...v0.10.12) (2026-05-14)


### Bug Fixes

* **ci:** drop fetched agent-audit script into expected 3-deep path ([#1107](https://github.com/yschimke/compose-ai-tools/issues/1107)) ([dec1537](https://github.com/yschimke/compose-ai-tools/commit/dec1537a36b825eab7b84a78dd0225e182cb47df))
* **vscode-extension:** auto mode no longer starts daemon when the plugin isn't applied ([#1109](https://github.com/yschimke/compose-ai-tools/issues/1109)) ([e2ed6c1](https://github.com/yschimke/compose-ai-tools/commit/e2ed6c15a5fb96199d42ef2ccb9e7ed74b107e58))
* **vscode-extension:** surface refresh and hide extensions UI in minimal mode ([#1106](https://github.com/yschimke/compose-ai-tools/issues/1106)) ([167b611](https://github.com/yschimke/compose-ai-tools/commit/167b61169c65830a1b76308304bbc9e442259b7c))

## [0.10.11](https://github.com/yschimke/compose-ai-tools/compare/v0.10.10...v0.10.11) (2026-05-14)


### Features

* add minimal mode for gradle-only preview rendering ([#1073](https://github.com/yschimke/compose-ai-tools/issues/1073)) ([e81e2c3](https://github.com/yschimke/compose-ai-tools/commit/e81e2c37f939a7120727d2ad6a931aa37371983a))
* **cli:** declarative `compose-preview profile <path.json>` command ([#1085](https://github.com/yschimke/compose-ai-tools/issues/1085)) ([d02acb7](https://github.com/yschimke/compose-ai-tools/commit/d02acb7b8a2dd24b0f9112c7b2e164fa9c1b48c5))
* **cli:** doctor --daemon spawns each module's daemon to verify it starts ([#1072](https://github.com/yschimke/compose-ai-tools/issues/1072)) ([77833ee](https://github.com/yschimke/compose-ai-tools/commit/77833ee45b5610eb403781a361499909dac661b1))
* make a11y (accessibility) opt-in instead of always-on ([#1074](https://github.com/yschimke/compose-ai-tools/issues/1074)) ([d10a75b](https://github.com/yschimke/compose-ai-tools/commit/d10a75b58f56353f62f6fa6fbcd0225d1ab87b97))
* move skills + install.sh to yschimke/skills ([#1101](https://github.com/yschimke/compose-ai-tools/issues/1101)) ([e1f05bc](https://github.com/yschimke/compose-ai-tools/commit/e1f05bcf1a2ec89409a8b069b86aed0120d69fae))
* **vscode-extension:** …More tab for disabled bundles ([#1095](https://github.com/yschimke/compose-ai-tools/issues/1095)) ([71f3b73](https://github.com/yschimke/compose-ai-tools/commit/71f3b739765d849f103fb5dfdfd7a6062f3f09d3))
* **vscode-extension:** A11y bundle paints overlay via cardBundleOverlay ([#1087](https://github.com/yschimke/compose-ai-tools/issues/1087)) ([58e69f6](https://github.com/yschimke/compose-ai-tools/commit/58e69f628c7e8e7a6410fb602be6ddcc651bdbce))
* **vscode-extension:** add 'Configure…' expander to bundle tab bodies ([#1069](https://github.com/yschimke/compose-ai-tools/issues/1069)) ([9d0bcec](https://github.com/yschimke/compose-ai-tools/commit/9d0bcec07300cdd35ca06f43f74930d041490f16))
* **vscode-extension:** bundle 'Configure…' expander for per-kind overrides ([#1075](https://github.com/yschimke/compose-ai-tools/issues/1075)) ([f4fcc45](https://github.com/yschimke/compose-ai-tools/commit/f4fcc4524618955b0d37947663e801dd87a3a944))
* **vscode-extension:** consume v2 dataExtensionReports manifest map ([#1091](https://github.com/yschimke/compose-ai-tools/issues/1091)) ([9f18cc7](https://github.com/yschimke/compose-ai-tools/commit/9f18cc77016cebd471b0623ca6c0f89a88ef1a06))
* **vscode-extension:** data-URI font preview in Text bundle ([#1102](https://github.com/yschimke/compose-ai-tools/issues/1102)) ([ded6694](https://github.com/yschimke/compose-ai-tools/commit/ded6694e8597eadfa0362c29369b0542ebb8084a))
* **vscode-extension:** Inspection bundle paints node bounds via cardBundleOverlay ([#1097](https://github.com/yschimke/compose-ai-tools/issues/1097)) ([946b658](https://github.com/yschimke/compose-ai-tools/commit/946b658f8d026aaf16f5f1e0ea1022dbd4e667fa))
* **vscode-extension:** misc bundles — display, watch, history-diff, errors ([#1078](https://github.com/yschimke/compose-ai-tools/issues/1078)) ([cf98f1f](https://github.com/yschimke/compose-ai-tools/commit/cf98f1f9b755f7f9cd786a3a016c1d92cea31525))
* **vscode-extension:** one-click Perfetto handoff — copy trace and open ui.perfetto.dev ([#1100](https://github.com/yschimke/compose-ai-tools/issues/1100)) ([6ecc38b](https://github.com/yschimke/compose-ai-tools/commit/6ecc38b4e18e59bae10888034dc20936e1e2d9ec))
* **vscode-extension:** paint bundle overlays on every visible card in grid mode ([#1096](https://github.com/yschimke/compose-ai-tools/issues/1096)) ([ddaac1b](https://github.com/yschimke/compose-ai-tools/commit/ddaac1b51c3817ce556881fc8eb8b7a384e04f87))
* **vscode-extension:** paint history-diff overlay on focused card ([#1086](https://github.com/yschimke/compose-ai-tools/issues/1086)) ([e644094](https://github.com/yschimke/compose-ai-tools/commit/e644094618bc9d7c4030d37c3ee18abe546c02bd))
* **vscode-extension:** performance bundle — migrate recomposition + render/trace + perfetto ([#1079](https://github.com/yschimke/compose-ai-tools/issues/1079)) ([f80bba9](https://github.com/yschimke/compose-ai-tools/commit/f80bba98de4cd6ca804e5d18f2562dce13ca8e14))
* **vscode-extension:** Text bundle paints overflow boxes via cardBundleOverlay ([#1093](https://github.com/yschimke/compose-ai-tools/issues/1093)) ([084f11e](https://github.com/yschimke/compose-ai-tools/commit/084f11ebea1ef85c41a418e72d4857fe674c1228))
* **vscode-extension:** text/i18n bundle — fonts (google) + drawn text + translations ([#1081](https://github.com/yschimke/compose-ai-tools/issues/1081)) ([d92fd0b](https://github.com/yschimke/compose-ai-tools/commit/d92fd0b6d8ed2b7f9e84efd3435886ed524a4f81))
* **vscode-extension:** theming bundle — compose/theme tokens + wallpaper derived scheme ([#1077](https://github.com/yschimke/compose-ai-tools/issues/1077)) ([e9bf9e6](https://github.com/yschimke/compose-ai-tools/commit/e9bf9e6188a602a13d6acb76e78c93eca8c5dc71))


### Bug Fixes

* revert FontUsedEntry.provider field to unbreak runtime classpath ([#1089](https://github.com/yschimke/compose-ai-tools/issues/1089)) ([08a8d39](https://github.com/yschimke/compose-ai-tools/commit/08a8d39ddce71d23a29592a41cb237380ef3c542))
* **vscode-extension:** hide data-extensions chip bar outside focus mode ([#1090](https://github.com/yschimke/compose-ai-tools/issues/1090)) ([24939ad](https://github.com/yschimke/compose-ai-tools/commit/24939adc665dc32f41c6e04de872784fca625124))
* **vscode-extension:** perf bundle review followups from [#1079](https://github.com/yschimke/compose-ai-tools/issues/1079) (Perfetto JSON + stale sections) ([#1080](https://github.com/yschimke/compose-ai-tools/issues/1080)) ([6b483ec](https://github.com/yschimke/compose-ai-tools/commit/6b483ec161d117caf6d3d95087e81d247ec49759))

## [0.10.10](https://github.com/yschimke/compose-ai-tools/compare/v0.10.9...v0.10.10) (2026-05-13)


### Features

* add compose/recomposition and render/trace presenters ([#1063](https://github.com/yschimke/compose-ai-tools/issues/1063)) ([fd9527b](https://github.com/yschimke/compose-ai-tools/commit/fd9527b4a6fc5b1612cd4c536c603e4a0131b36b))
* add hierarchy legend with per-node color swatches ([#1062](https://github.com/yschimke/compose-ai-tools/issues/1062)) ([f24e7f2](https://github.com/yschimke/compose-ai-tools/commit/f24e7f2aa0119d97ea4caf04804b733026441883))
* **vscode-extension:** inspection bundle presenters (semantics, layout, uia) ([#1068](https://github.com/yschimke/compose-ai-tools/issues/1068)) ([0d6b3d9](https://github.com/yschimke/compose-ai-tools/commit/0d6b3d93a72dd36638d2b338a5a08687256c2055))
* **vscode-extension:** resources/used table with jump-to-resource-file ([#1066](https://github.com/yschimke/compose-ai-tools/issues/1066)) ([df3af63](https://github.com/yschimke/compose-ai-tools/commit/df3af634210c08a1ff399af740eacf45e9b3ccd8))

## [0.10.9](https://github.com/yschimke/compose-ai-tools/compare/v0.10.8...v0.10.9) (2026-05-13)


### Features

* **a11y:** subscription-driven a11y data products + protocol + UX fixes ([#1007](https://github.com/yschimke/compose-ai-tools/issues/1007)) ([c819a8f](https://github.com/yschimke/compose-ai-tools/commit/c819a8f075c79260328bd75ca5f812ca14246dc9))
* add diagnostic logging for data products flow ([#1050](https://github.com/yschimke/compose-ai-tools/issues/1050)) ([080edfd](https://github.com/yschimke/compose-ai-tools/commit/080edfdff9807bf20e45b076e9197fece787cf89))
* add shape-aware generic payload renderers for focus inspector ([#1045](https://github.com/yschimke/compose-ai-tools/issues/1045)) ([a5f0377](https://github.com/yschimke/compose-ai-tools/commit/a5f037737ce6e76f96e3dd073c05a9cd38ddae7e))
* add variant count chip to collapsed preview cards ([#1051](https://github.com/yschimke/compose-ai-tools/issues/1051)) ([8bd1762](https://github.com/yschimke/compose-ai-tools/commit/8bd1762e5445117a500173158c6647f00ab89bf7))
* **focus:** add @FocusedPreview(gif = true) and remove sample hack ([#1031](https://github.com/yschimke/compose-ai-tools/issues/1031)) ([f079dfd](https://github.com/yschimke/compose-ai-tools/commit/f079dfd3ab849b33b82e865bbfefd9dd22a056cd))
* generic fallback presenter and a11y/overlay PNG support ([#1044](https://github.com/yschimke/compose-ai-tools/issues/1044)) ([1a31551](https://github.com/yschimke/compose-ai-tools/commit/1a31551d2915dcd20131734d0378ca81387901ca))
* **install:** multi-JDK install + agent-aware paths, drop --yes gate ([#1029](https://github.com/yschimke/compose-ai-tools/issues/1029)) ([138f868](https://github.com/yschimke/compose-ai-tools/commit/138f868cc5df3acb2a7cb73be8e9c68559da6ac0))
* **vscode:** collapse @Preview variants by default, expand on filter ([#1028](https://github.com/yschimke/compose-ai-tools/issues/1028)) ([d42d85f](https://github.com/yschimke/compose-ai-tools/commit/d42d85f295351fde052115368de7f4479de083e5))


### Bug Fixes

* **discovery:** keep tile previews despite the unsupported-parameter gate ([#1036](https://github.com/yschimke/compose-ai-tools/issues/1036)) ([826786d](https://github.com/yschimke/compose-ai-tools/commit/826786d5567d004f76e0af1d4538ea84d0212362))
* pin androidx.activity to 1.10.0 for Robolectric compatibility ([#1041](https://github.com/yschimke/compose-ai-tools/issues/1041)) ([f296e60](https://github.com/yschimke/compose-ai-tools/commit/f296e604230af6f81050ad7b6ab5d714a251d531))
* reposition variant count chip to bottom-left ([#1053](https://github.com/yschimke/compose-ai-tools/issues/1053)) ([4c094ff](https://github.com/yschimke/compose-ai-tools/commit/4c094ff0ba6aaa042fe9515678d076705bcea06e))
* resolve renderer jars lazily to avoid configuration-time warnings ([#1040](https://github.com/yschimke/compose-ai-tools/issues/1040)) ([914b275](https://github.com/yschimke/compose-ai-tools/commit/914b275950397916396a28898730872b2ca13987))
* skip library class methods to reduce preview scan warnings ([#1042](https://github.com/yschimke/compose-ai-tools/issues/1042)) ([b2db7fb](https://github.com/yschimke/compose-ai-tools/commit/b2db7fbbec019f9b3f8fd5ab0a34a9eaac84ea43))
* switch to zero-code integration for compose-ai plugin in CI ([#1034](https://github.com/yschimke/compose-ai-tools/issues/1034)) ([1eed04f](https://github.com/yschimke/compose-ai-tools/commit/1eed04fa3129681db42400ba31d72c307fa2452f))
* **vscode-extension:** handle historyPruned + surface class-version errors ([#1049](https://github.com/yschimke/compose-ai-tools/issues/1049)) ([cd1a058](https://github.com/yschimke/compose-ai-tools/commit/cd1a0584a1024ad8fa5c4a4bde819e0f643d3889))

## [0.10.8](https://github.com/yschimke/compose-ai-tools/compare/v0.10.7...v0.10.8) (2026-05-09)


### Features

* add reusable AI PR review workflow and cloud image-size overrides ([#982](https://github.com/yschimke/compose-ai-tools/issues/982)) ([848351f](https://github.com/yschimke/compose-ai-tools/commit/848351f60d105711b08915b59723d6fdc3409543))


### Bug Fixes

* always advertise a11y data products ([#1003](https://github.com/yschimke/compose-ai-tools/issues/1003)) ([5730bb2](https://github.com/yschimke/compose-ai-tools/commit/5730bb20e58e0203a06f25183ff753de3277eac1))
* **build:** resolve Gradle lint warnings ([#992](https://github.com/yschimke/compose-ai-tools/issues/992)) ([1dcc321](https://github.com/yschimke/compose-ai-tools/commit/1dcc321fc9dddfbd150e273dba10330254899fb6))
* **ci:** use canonical action pins ([#995](https://github.com/yschimke/compose-ai-tools/issues/995)) ([42ed969](https://github.com/yschimke/compose-ai-tools/commit/42ed969e7220bd263f21052890e37de2a3d3072b))
* correct YAML indentation in codex review reusable workflow ([#983](https://github.com/yschimke/compose-ai-tools/issues/983)) ([5d6ae31](https://github.com/yschimke/compose-ai-tools/commit/5d6ae31a5e6aff484b836fe1e8e47b6fff4db13c))
* **plugin:** discover Compose previews with compiler params ([#994](https://github.com/yschimke/compose-ai-tools/issues/994)) ([6ebe735](https://github.com/yschimke/compose-ai-tools/commit/6ebe7354505c9042b4bde2864a00b6c9dde8bfe5))
* **plugin:** drop private @Preview methods at discovery with a warning ([#967](https://github.com/yschimke/compose-ai-tools/issues/967)) ([0790e35](https://github.com/yschimke/compose-ai-tools/commit/0790e35311b4c14947bcc2568025420488d05c64))
* sanitize preview name suffixes for render output paths ([#991](https://github.com/yschimke/compose-ai-tools/issues/991)) ([9bc472e](https://github.com/yschimke/compose-ai-tools/commit/9bc472e6b5309cc9de16884d014408c50766b10b))
* skip unsupported preview methods and add regression tests ([#984](https://github.com/yschimke/compose-ai-tools/issues/984)) ([0d6437e](https://github.com/yschimke/compose-ai-tools/commit/0d6437e71e3f7e13b95fb4c75576a625d65863b4))
* unblock preview review audit checks ([#988](https://github.com/yschimke/compose-ai-tools/issues/988)) ([3286082](https://github.com/yschimke/compose-ai-tools/commit/328608261cbe764140ed4440ce2e2ed34f027c7e))
* **vscode:** restore CI test jobs ([#993](https://github.com/yschimke/compose-ai-tools/issues/993)) ([099f485](https://github.com/yschimke/compose-ai-tools/commit/099f4859662e9996d95fe4da7f1fc7a9b15e86cd))
* **vscode:** scope focus-inspector data extensions per preview with placeholder + background fetch ([#969](https://github.com/yschimke/compose-ai-tools/issues/969)) ([9d71dbc](https://github.com/yschimke/compose-ai-tools/commit/9d71dbc52b13ef3e406b36edc3c87a498597312b))
* **vscode:** wire daemon data products into focus inspector reports ([#985](https://github.com/yschimke/compose-ai-tools/issues/985)) ([dd5098b](https://github.com/yschimke/compose-ai-tools/commit/dd5098b439e68c4dcc1b1f3452f99a6e38dbc43e))

## [0.10.7](https://github.com/yschimke/compose-ai-tools/compare/v0.10.6...v0.10.7) (2026-05-08)


### Bug Fixes

* **vscode:** coalesce identical concurrent refresh calls instead of aborting Gradle ([#963](https://github.com/yschimke/compose-ai-tools/issues/963)) ([ac58146](https://github.com/yschimke/compose-ai-tools/commit/ac581462c0244acab44b42ce4bf40c201783b1dd))
* **vscode:** suppress duplicate refresh logs and collapse blank-line gaps ([#961](https://github.com/yschimke/compose-ai-tools/issues/961)) ([58fdc57](https://github.com/yschimke/compose-ai-tools/commit/58fdc579b8f065b2644c095655c6de478a7128ce))

## [0.10.6](https://github.com/yschimke/compose-ai-tools/compare/v0.10.5...v0.10.6) (2026-05-08)


### Features

* emit display-filter variants from the gradle-plugin direct render path ([#958](https://github.com/yschimke/compose-ai-tools/issues/958)) ([901d58d](https://github.com/yschimke/compose-ai-tools/commit/901d58db8919b79fca5462f29e73eec7b0a2e649))
* **pseudolocale:** CMP Desktop layout-direction support ([#949](https://github.com/yschimke/compose-ai-tools/issues/949)) ([e98ff95](https://github.com/yschimke/compose-ai-tools/commit/e98ff95b7ffd308e59ca58d00f36680030c20752))


### Bug Fixes

* **gradle-plugin:** stop resolving runtime classpath at configuration time ([#952](https://github.com/yschimke/compose-ai-tools/issues/952)) ([e69cd31](https://github.com/yschimke/compose-ai-tools/commit/e69cd31e540004f5e6d01918b3d20aec287708d6))
* **pseudolocale:** preserve spans + plan locale-only overrides ([#953](https://github.com/yschimke/compose-ai-tools/issues/953)) ([35dba92](https://github.com/yschimke/compose-ai-tools/commit/35dba92ccb62e56087bfe20c2956efe43bafddf7))
* **pseudolocale:** snap paragraph-span boundaries when reattaching ([#955](https://github.com/yschimke/compose-ai-tools/issues/955)) ([06ad3cd](https://github.com/yschimke/compose-ai-tools/commit/06ad3cda534c0df84fa18124216cf6aebdfedfa9))
* **vscode:** detect plugin in Groovy build.gradle, not just .kts ([#944](https://github.com/yschimke/compose-ai-tools/issues/944)) ([24072a3](https://github.com/yschimke/compose-ai-tools/commit/24072a37e66f73ce613134df32907f7c37d132cb))
* **vscode:** follow symlinked subdirectories when walking for preview modules ([#951](https://github.com/yschimke/compose-ai-tools/issues/951)) ([0d89ab5](https://github.com/yschimke/compose-ai-tools/commit/0d89ab5a8f8340a25c5fa6b76ae4ea9113a9b07d))
* **vscode:** support modules whose projectDir != Gradle modulePath ([#948](https://github.com/yschimke/compose-ai-tools/issues/948)) ([fee3153](https://github.com/yschimke/compose-ai-tools/commit/fee3153045875d88cc739c794ace96a9ec8eb342))

## [0.10.5](https://github.com/yschimke/compose-ai-tools/compare/v0.10.4...v0.10.5) (2026-05-08)


### Bug Fixes

* **ci:** collect nested release artifacts via find instead of shallow glob ([#940](https://github.com/yschimke/compose-ai-tools/issues/940)) ([9096c44](https://github.com/yschimke/compose-ai-tools/commit/9096c44c7470caa5cc22fb78a349839ef0059f19))

## [0.10.4](https://github.com/yschimke/compose-ai-tools/compare/v0.10.3...v0.10.4) (2026-05-08)


### Bug Fixes

* trigger release ([#938](https://github.com/yschimke/compose-ai-tools/issues/938)) ([40b4f9c](https://github.com/yschimke/compose-ai-tools/commit/40b4f9c963074cd54c7d0f5f5586c70bd54aabf2))

## [0.10.3](https://github.com/yschimke/compose-ai-tools/compare/v0.10.2...v0.10.3) (2026-05-08)


### Features

* **mcp:** add enable_extensions tool + use it from agent-audit script ([#935](https://github.com/yschimke/compose-ai-tools/issues/935)) ([559251c](https://github.com/yschimke/compose-ai-tools/commit/559251c36c43d46aff88d3990213595af6ad517c))

## [0.10.2](https://github.com/yschimke/compose-ai-tools/compare/v0.10.1...v0.10.2) (2026-05-08)


### Features

* add `force` escape hatch to render_preview + CLI to replace `rm -rf build/classes/` ([#927](https://github.com/yschimke/compose-ai-tools/issues/927)) ([9aa5690](https://github.com/yschimke/compose-ai-tools/commit/9aa569016e4f066a3a66183b3e9ac6c20a7f6a7c))


### Bug Fixes

* **daemon:** gate Wear ambient connector on consumer classpath presence ([#933](https://github.com/yschimke/compose-ai-tools/issues/933)) ([e3a68c8](https://github.com/yschimke/compose-ai-tools/commit/e3a68c889959da1ddebc72f832c3973b51ad480a))
* **plugin:** pin ui-test-manifest / ui-test-junit4 to renderer floor for tile-only consumers ([#934](https://github.com/yschimke/compose-ai-tools/issues/934)) ([69dc703](https://github.com/yschimke/compose-ai-tools/commit/69dc703df875fc43ba9bae79df55c9087977d1d3))

## [0.10.1](https://github.com/yschimke/compose-ai-tools/compare/v0.10.0...v0.10.1) (2026-05-08)


### Features

* **daemon:** add navigation script-event extension (deep link, back, predictive back) ([#901](https://github.com/yschimke/compose-ai-tools/issues/901)) ([2c0585b](https://github.com/yschimke/compose-ai-tools/commit/2c0585b4c5f19a81c86b923b32df62117db248c5))
* **plugin:** move pixel-test wiring out of samples into composePreview { renderBeforeUnitTests } ([#904](https://github.com/yschimke/compose-ai-tools/issues/904)) ([37f2b95](https://github.com/yschimke/compose-ai-tools/commit/37f2b95a869d5c43608facdf08c00fb0ecbf48bc))
* **preview-annotations:** add @AmbientPreview to drive Wear ambient state in previews ([#914](https://github.com/yschimke/compose-ai-tools/issues/914)) ([9021ca6](https://github.com/yschimke/compose-ai-tools/commit/9021ca6f0c7cef4a481b1e232c1aba552b415ca4))
* **preview-annotations:** add @FocusedPreview to drive focus in previews ([#897](https://github.com/yschimke/compose-ai-tools/issues/897)) ([962ec74](https://github.com/yschimke/compose-ai-tools/commit/962ec747633f5a99c3ca5ed53ab804dc871a61e4))
* **preview-annotations:** add traverse + overlay to @FocusedPreview ([#899](https://github.com/yschimke/compose-ai-tools/issues/899)) ([cf69a4a](https://github.com/yschimke/compose-ai-tools/commit/cf69a4a025a7a3a7a1c5b384a8032bf6f73fdd3e))
* **uia:** typed unsupported-reason evidence for uia.* dispatches ([#874](https://github.com/yschimke/compose-ai-tools/issues/874)) ([#916](https://github.com/yschimke/compose-ai-tools/issues/916)) ([b4a8157](https://github.com/yschimke/compose-ai-tools/commit/b4a81576f26b2e32500510e871d23d7091639ab7))
* **vscode:** log edit→preview-update journey time ([#902](https://github.com/yschimke/compose-ai-tools/issues/902)) ([d564fe5](https://github.com/yschimke/compose-ai-tools/commit/d564fe520c37fd012b7693c6a088e332ae182b39))


### Bug Fixes

* **daemon:** propagate session-closed hook for proactive interactive cleanup ([#896](https://github.com/yschimke/compose-ai-tools/issues/896)) ([8a13f8b](https://github.com/yschimke/compose-ai-tools/commit/8a13f8b41a7b6090c6feef3aa3ca6287927525cb))
* **samples:** drive wear ambient previews via androidx AmbientMode ([#907](https://github.com/yschimke/compose-ai-tools/issues/907)) ([01860b6](https://github.com/yschimke/compose-ai-tools/commit/01860b6574260d683d1bc29d8d8cbd0451c12be0))

## [0.10.0](https://github.com/yschimke/compose-ai-tools/compare/v0.9.3...v0.10.0) (2026-05-07)


### ⚠ BREAKING CHANGES

* **daemon:** gate history/diff behind experimental sysprop for 1.0 ([#875](https://github.com/yschimke/compose-ai-tools/issues/875))
* **vscode:** remove composePreview.streaming.enabled setting ([#889](https://github.com/yschimke/compose-ai-tools/issues/889))
* **vscode:** remove composePreview.daemon.enabled setting ([#878](https://github.com/yschimke/compose-ai-tools/issues/878))

### Features

* **daemon:** add composestream/1 live-frame streaming protocol ([#847](https://github.com/yschimke/compose-ai-tools/issues/847)) ([524b566](https://github.com/yschimke/compose-ai-tools/commit/524b56663873b9426bb99977e8fd9db61f1cfbed))
* **daemon:** add Wear OS ambient-mode preview override ([#891](https://github.com/yschimke/compose-ai-tools/issues/891)) ([9ba0fbe](https://github.com/yschimke/compose-ai-tools/commit/9ba0fbee5a80e6a1370265969913b854ebe084c7))
* **daemon:** default daemon to no extensions, opt-in via extensions/enable ([#854](https://github.com/yschimke/compose-ai-tools/issues/854)) ([afc5066](https://github.com/yschimke/compose-ai-tools/commit/afc50664d330646b926a25581166bc66faa187ec))
* **daemon:** gate history/diff behind experimental sysprop for 1.0 ([#875](https://github.com/yschimke/compose-ai-tools/issues/875)) ([6d97728](https://github.com/yschimke/compose-ai-tools/commit/6d97728c95a990029e9f1eae98982bb7b207bfc0))
* **data/uiautomator:** Compose SemanticsNode support + JSON wire format ([#864](https://github.com/yschimke/compose-ai-tools/issues/864)) ([9040556](https://github.com/yschimke/compose-ai-tools/commit/9040556120e991248b035e158e148ce941b97dfc))
* **data/uiautomator:** end-to-end uia.* dispatch through record_preview ([#872](https://github.com/yschimke/compose-ai-tools/issues/872)) ([8f5e8c6](https://github.com/yschimke/compose-ai-tools/commit/8f5e8c60065b2d5e3e8c8587ab9942592c5f614a))
* **data/uiautomator:** prototype UIAutomator-shaped query/action API ([#832](https://github.com/yschimke/compose-ai-tools/issues/832)) ([ec18a23](https://github.com/yschimke/compose-ai-tools/commit/ec18a23dea45aafbdbff6f09a02dcd2bde54d768))
* index preview targets and surface them in VS Code ([#821](https://github.com/yschimke/compose-ai-tools/issues/821)) ([967bca4](https://github.com/yschimke/compose-ai-tools/commit/967bca4a6c8371c239cee2408cdb8ce584b572ac))
* **mcp:** close the source-freshness gaps agents see as stale renders ([#826](https://github.com/yschimke/compose-ai-tools/issues/826)) ([7def542](https://github.com/yschimke/compose-ai-tools/commit/7def5428c007b07640fd33f2ef710b2a57c76ff0))
* **mcp:** mcp doctor verdicts + skill guidance to stop spurious reinstalls ([#827](https://github.com/yschimke/compose-ai-tools/issues/827)) ([7b1aa57](https://github.com/yschimke/compose-ai-tools/commit/7b1aa57eb4e0c71504c8ef4ca13c61b0612a6554))
* **samples:** add inset focus ring demo in :samples:android-alpha ([#895](https://github.com/yschimke/compose-ai-tools/issues/895)) ([8079a1b](https://github.com/yschimke/compose-ai-tools/commit/8079a1b22184c64777ff5fea300978ca133ff742))
* **strings:** surface text truncation in text/strings v2 ([#844](https://github.com/yschimke/compose-ai-tools/issues/844)) ([da388de](https://github.com/yschimke/compose-ai-tools/commit/da388de2b0bbd011ad33c78303d4d1a35012c2bf))
* **vscode:** drop static base capture when scroll image data product exists ([#893](https://github.com/yschimke/compose-ai-tools/issues/893)) ([5d2e9b5](https://github.com/yschimke/compose-ai-tools/commit/5d2e9b514403cf666935232642bf13c1ba99ac40))
* **vscode:** mirror PROTOCOL v2 and enable all advertised extensions on connect ([#866](https://github.com/yschimke/compose-ai-tools/issues/866)) ([1422362](https://github.com/yschimke/compose-ai-tools/commit/14223625085d8bfad1d5974675c53f84dc8844ac))
* **vscode:** remove composePreview.daemon.enabled setting ([#878](https://github.com/yschimke/compose-ai-tools/issues/878)) ([13ea62e](https://github.com/yschimke/compose-ai-tools/commit/13ea62ec96cbf3610876d6fb1f875a45b8881e2f))
* **vscode:** remove composePreview.streaming.enabled setting ([#889](https://github.com/yschimke/compose-ai-tools/issues/889)) ([3b40a1d](https://github.com/yschimke/compose-ai-tools/commit/3b40a1d8f4282e3d1ce9e3ef40034891622293eb))
* **vscode:** restore composePreview.daemon.enabled as deprecated no-op ([#890](https://github.com/yschimke/compose-ai-tools/issues/890)) ([e637116](https://github.com/yschimke/compose-ai-tools/commit/e6371165c9d5f3ebf9ea22de7f79f9f9779ec242))


### Bug Fixes

* **daemon:** break live-frame loop when interactive session auto-closes ([#892](https://github.com/yschimke/compose-ai-tools/issues/892)) ([31085fc](https://github.com/yschimke/compose-ai-tools/commit/31085fc97d71d22ac56be8d837fcc61b6c787884))
* **deps:** update gradle minor/patch ([#793](https://github.com/yschimke/compose-ai-tools/issues/793)) ([1a61966](https://github.com/yschimke/compose-ai-tools/commit/1a6196659715db3ed38bd314027c71b6fb57d745))
* **install:** require --yes/--upgrade so agents can't silently download ([#803](https://github.com/yschimke/compose-ai-tools/issues/803)) ([d27a409](https://github.com/yschimke/compose-ai-tools/commit/d27a40941ff45cf84032e4831679277a399b4bc3))
* **mcp:** notify on every bootstrap-to-full tool catalog transition ([#837](https://github.com/yschimke/compose-ai-tools/issues/837)) ([4387cdc](https://github.com/yschimke/compose-ai-tools/commit/4387cdcb6ff6d54e5eb2e36cd51e1b239d9f71d2))
* **mcp:** re-import previews.json when discoverPreviews rewrites it ([#843](https://github.com/yschimke/compose-ai-tools/issues/843)) ([1c402bc](https://github.com/yschimke/compose-ai-tools/commit/1c402bc0ce2a3bee732ebfa8a13c62c1bcc036d3))
* **plugin:** pin androidx.core:core 1.16 floor for renderer test APK ([#811](https://github.com/yschimke/compose-ai-tools/issues/811)) ([9b17fe9](https://github.com/yschimke/compose-ai-tools/commit/9b17fe9eb616fc1c10e12cd4233b46aa3272cb9d))
* **streaming:** plug stream/stop teardown leaks + decode-order race ([#861](https://github.com/yschimke/compose-ai-tools/issues/861)) ([7f013f3](https://github.com/yschimke/compose-ai-tools/commit/7f013f39a161c4c68394ae9353bdf16b05001a98))
* **vscode:** coalesce live pointerMove sends to rAF cadence ([#885](https://github.com/yschimke/compose-ai-tools/issues/885)) ([7bdc2ad](https://github.com/yschimke/compose-ai-tools/commit/7bdc2ad6b4e2aa8a4608a5c47622b6e140c85350))
* **vscode:** exempt composePreviewDaemonStart from refresh cancel ([#840](https://github.com/yschimke/compose-ai-tools/issues/840)) ([92b9ce6](https://github.com/yschimke/compose-ai-tools/commit/92b9ce6e07b22c9379ecbdb232865304e978ebcf))
* **vscode:** forward live pointer events through the streaming canvas ([#882](https://github.com/yschimke/compose-ai-tools/issues/882)) ([8ee7368](https://github.com/yschimke/compose-ai-tools/commit/8ee7368396332bf6044d750c1ad4dbae209d3d95))
* **vscode:** forward live wheel events through the streaming canvas ([#880](https://github.com/yschimke/compose-ai-tools/issues/880)) ([2dac098](https://github.com/yschimke/compose-ai-tools/commit/2dac098e4d73aaeb67037db47a5755c663ae0d74))

## [0.9.3](https://github.com/yschimke/compose-ai-tools/compare/v0.9.2...v0.9.3) (2026-05-05)


### Features

* **data/wallpaper:** use material-kolor for seed → ColorScheme derivation ([#788](https://github.com/yschimke/compose-ai-tools/issues/788)) ([8af237a](https://github.com/yschimke/compose-ai-tools/commit/8af237ae3b12650f3a9fedc6d7e6438d9b76f1aa))
* **data:** add wallpaper data extension that drives Material3 dynamic theme from a seed color ([#780](https://github.com/yschimke/compose-ai-tools/issues/780)) ([0157a53](https://github.com/yschimke/compose-ai-tools/commit/0157a537f085d68aa55192b09da5a2771231e427))
* **install:** symlink skill bundles into Codex / Antigravity skill dirs ([#782](https://github.com/yschimke/compose-ai-tools/issues/782)) ([0a2cadc](https://github.com/yschimke/compose-ai-tools/commit/0a2cadcb3b3f600c178eb6b1a3b27be4c6f815c2))
* **mcp:** register compose-preview-mcp with every detected agent host ([#775](https://github.com/yschimke/compose-ai-tools/issues/775)) ([23bb7b7](https://github.com/yschimke/compose-ai-tools/commit/23bb7b7272563aa968b5bc6e5ee3fe0f967b3c3f))


### Bug Fixes

* **vscode:** republish state on webviewReady so the panel populates after late resolution ([#778](https://github.com/yschimke/compose-ai-tools/issues/778)) ([2019807](https://github.com/yschimke/compose-ai-tools/commit/2019807b7b9ced7733fd0212728ff76ae0c27242))
* **vscode:** surface @ScrollingPreview LONG/GIF data products in the panel ([#789](https://github.com/yschimke/compose-ai-tools/issues/789)) ([9930707](https://github.com/yschimke/compose-ai-tools/commit/99307070993a167dde1101918e6ed91e7286f68c))

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
