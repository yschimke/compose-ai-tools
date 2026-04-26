# Changelog

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
