# Changelog

## [1.19.0](https://github.com/yschimke/compose-ai-tools/compare/v1.18.0...v1.19.0) (2026-08-19)


### Features

* **design-map:** let --strict accept an absence somebody wrote down ([#4250](https://github.com/yschimke/compose-ai-tools/issues/4250)) ([9eabefa](https://github.com/yschimke/compose-ai-tools/commit/9eabefa2234dd2ee33e2174194db45384cd98ae9))


### Bug Fixes

* **preview:** record the clock position the render loop actually reached ([#4248](https://github.com/yschimke/compose-ai-tools/issues/4248)) ([6bbb744](https://github.com/yschimke/compose-ai-tools/commit/6bbb744aa11beca03feef1b27ce88733a20112b0))

## [1.18.0](https://github.com/yschimke/compose-ai-tools/compare/v1.17.0...v1.18.0) (2026-08-19)


### Features

* **preview:** settle a time-driven reveal before the still capture ([#4242](https://github.com/yschimke/compose-ai-tools/issues/4242)) ([5f7a4da](https://github.com/yschimke/compose-ai-tools/commit/5f7a4da2241935e0140bc7faf687633491659fd4))


### Bug Fixes

* **preview:** honour an exact settle coordinate, and widen the motion collision ([#4246](https://github.com/yschimke/compose-ai-tools/issues/4246)) ([5e0be96](https://github.com/yschimke/compose-ai-tools/commit/5e0be966eb74d29e9acd081dd9345a58274f7673))

## [1.17.0](https://github.com/yschimke/compose-ai-tools/compare/v1.16.0...v1.17.0) (2026-08-19)


### Features

* **renderer:** implement @InteractionPreview on Robolectric, and stop a motion failure taking the still ([#4240](https://github.com/yschimke/compose-ai-tools/issues/4240)) ([0b26f81](https://github.com/yschimke/compose-ai-tools/commit/0b26f81b6efa2fea8e58c7d6916c5d4905467386))
* **serve:** make catalog startup load order configurable ([#4233](https://github.com/yschimke/compose-ai-tools/issues/4233)) ([df13f8d](https://github.com/yschimke/compose-ai-tools/commit/df13f8dcd14258ea48c486bad99e7ef0e6c640df))


### Bug Fixes

* **daemon:** seed the Remote Compose override before the first composition pass ([#4241](https://github.com/yschimke/compose-ai-tools/issues/4241)) ([ba14c3c](https://github.com/yschimke/compose-ai-tools/commit/ba14c3c0fb37597841b4ce22e748a452a0bbd269))
* **plugin:** scope the skiko skew check to one resolved classpath ([#4235](https://github.com/yschimke/compose-ai-tools/issues/4235)) ([9b7199b](https://github.com/yschimke/compose-ai-tools/commit/9b7199b5c931aa7a8e0ddcc6fda87b88e79f51f7))
* **serve:** ask the reporter for a title on the catalog issue report ([#4237](https://github.com/yschimke/compose-ai-tools/issues/4237)) ([67c1589](https://github.com/yschimke/compose-ai-tools/commit/67c1589441747b198ed49c158c8e1bb5662c90ab))
* **serve:** keep a shared-line scaffold annotation's declaration, and index extensions by callable name ([#4227](https://github.com/yschimke/compose-ai-tools/issues/4227)) ([7b6fcd9](https://github.com/yschimke/compose-ai-tools/commit/7b6fcd92da6c08c66e36a729f16b45b5bbd123dc))
* **serve:** name the Skiko skew in the 409 a dead render lane returns ([#4243](https://github.com/yschimke/compose-ai-tools/issues/4243)) ([55d5652](https://github.com/yschimke/compose-ai-tools/commit/55d5652e8b6409b6cdef9e206546ec25e32ae107))
* **serve:** pair a bundle's Skiko bindings with its native, and stop a tripped lane blacking out baked pixels ([#4236](https://github.com/yschimke/compose-ai-tools/issues/4236)) ([e49413c](https://github.com/yschimke/compose-ai-tools/commit/e49413cd904eef181db9b6c540291b38fba6d764))
* **serve:** stop a sectioned catalog spending a row on its `⋯` menu ([#4232](https://github.com/yschimke/compose-ai-tools/issues/4232)) ([bc447f1](https://github.com/yschimke/compose-ai-tools/commit/bc447f1d5e98dbaa669f7376b85b4a903f5adba2))

## [1.16.0](https://github.com/yschimke/compose-ai-tools/compare/v1.15.0...v1.16.0) (2026-08-19)


### Features

* **design-artifacts:** let a catalog exempt semantics-less renders from the completeness gate ([#4175](https://github.com/yschimke/compose-ai-tools/issues/4175)) ([453bc84](https://github.com/yschimke/compose-ai-tools/commit/453bc844d0dcfd4885d81aca8a9a3e398837a3de))
* **design-catalog:** unlist compose-m3 / wear-m3 and scope both to pipeline features ([#4195](https://github.com/yschimke/compose-ai-tools/issues/4195)) ([58ed189](https://github.com/yschimke/compose-ai-tools/commit/58ed189fd32be131894ed0926188c6cf86ffdce3))
* **rc-player:** distribute the Wasm player as a consumable npm bundle ([#4194](https://github.com/yschimke/compose-ai-tools/issues/4194)) ([f1f4eb3](https://github.com/yschimke/compose-ai-tools/commit/f1f4eb33671b5b690083073844301cf4c8640912))
* **rc-player:** replace the host font map with an RcTypefaceLoader interface ([#4178](https://github.com/yschimke/compose-ai-tools/issues/4178)) ([654a33d](https://github.com/yschimke/compose-ai-tools/commit/654a33ddaaa3b4eb1e52975f9e1190a9309ae13b))
* **rc-player:** ship a real default RcTypefaceLoader and move manifest loading into shared code ([#4185](https://github.com/yschimke/compose-ai-tools/issues/4185)) ([72947d7](https://github.com/yschimke/compose-ai-tools/commit/72947d74b81f5215bab2005e0522f8b02be309c6))
* **rc-player:** ship the iOS player as an XCFramework consumable from Swift ([#4193](https://github.com/yschimke/compose-ai-tools/issues/4193)) ([2c1faab](https://github.com/yschimke/compose-ai-tools/commit/2c1faab3d1788c3c2fab3b4029f83d09961b0ac0))
* serve the Wear M3 design-kit catalog on preview.coo.ee ([#4191](https://github.com/yschimke/compose-ai-tools/issues/4191)) ([09b6111](https://github.com/yschimke/compose-ai-tools/commit/09b6111d80225c7a887817251ec72a4363518c8a))


### Bug Fixes

* **build:** stop publishing the vendored embedded player, and record its package collision ([#4217](https://github.com/yschimke/compose-ai-tools/issues/4217)) ([eb84fd9](https://github.com/yschimke/compose-ai-tools/commit/eb84fd9ad53ad5c5180583dc72a522a67e40ac9e))
* **ci:** give the Kotlin/Native compiler its own heap for the release link ([#4205](https://github.com/yschimke/compose-ai-tools/issues/4205)) ([25e4872](https://github.com/yschimke/compose-ai-tools/commit/25e4872120b1cf01f8499da7275db3d142681229))
* **ci:** make the filmstrip, the interactive E2E and the serve routing tests deterministic ([#4187](https://github.com/yschimke/compose-ai-tools/issues/4187)) ([e654e1a](https://github.com/yschimke/compose-ai-tools/commit/e654e1abd7b915a9e0358c4daa5c70bd04ffce82))
* **ci:** repair serve cache and fixture expectations ([#4188](https://github.com/yschimke/compose-ai-tools/issues/4188)) ([fe0e0dc](https://github.com/yschimke/compose-ai-tools/commit/fe0e0dc46cc6555e3d84fb57891ea34f10a0c1fc))
* **daemon:** seed named overrides before the first composition pass ([#4223](https://github.com/yschimke/compose-ai-tools/issues/4223)) ([f404798](https://github.com/yschimke/compose-ai-tools/commit/f404798569b9c29566531f6fa44602f5a051e1ff))
* **deploy,serve:** address Codex review on the rollout and verification fixes ([#4207](https://github.com/yschimke/compose-ai-tools/issues/4207)) ([dff399d](https://github.com/yschimke/compose-ai-tools/commit/dff399ddc6e00530369b041ae3d2392deefa4ca7))
* **deploy,serve:** stop reporting success over surviving replicas, held locks and stale caches ([#4199](https://github.com/yschimke/compose-ai-tools/issues/4199)) ([c01e56c](https://github.com/yschimke/compose-ai-tools/commit/c01e56cb11fba4576e140d82fba87e285de80977))
* **design-artifacts:** stop split-mode taking the expensive default in silence ([#4226](https://github.com/yschimke/compose-ai-tools/issues/4226)) ([465b908](https://github.com/yschimke/compose-ai-tools/commit/465b90880ccff0a875958e2e3386606a4f238ff4))
* **design-artifacts:** type-check completeness.$comment ([#4201](https://github.com/yschimke/compose-ai-tools/issues/4201)) ([32f71e0](https://github.com/yschimke/compose-ai-tools/commit/32f71e00f972cd314b166dcb8d11a427acf69f43))
* **design-map:** pair a dark-only catalog with its sole capture ([#4196](https://github.com/yschimke/compose-ai-tools/issues/4196)) ([e745f58](https://github.com/yschimke/compose-ai-tools/commit/e745f58919944d582dd44062810a47dc5ce1088b))
* **preview:** coordinate rollouts and optimizer load ([#4168](https://github.com/yschimke/compose-ai-tools/issues/4168)) ([5b11d4c](https://github.com/yschimke/compose-ai-tools/commit/5b11d4cbcf50b77b8870a0abbc8d4da6d0788fcd))
* **rc-js-player:** request a second document's axis, and re-measure for it ([#4180](https://github.com/yschimke/compose-ai-tools/issues/4180)) ([e50e2d1](https://github.com/yschimke/compose-ai-tools/commit/e50e2d1a47dd1a6895af1e0e8532bbe2e39fb393))
* **rc-player:** evaluate content-state operations declared at the document root ([#4221](https://github.com/yschimke/compose-ai-tools/issues/4221)) ([14f3368](https://github.com/yschimke/compose-ai-tools/commit/14f33684fbe19378c2d2afe2816ca2ce9dccaf31))
* **rc-player:** follow a replaced named-value holder, and rebuild semantics on invalidation ([#4213](https://github.com/yschimke/compose-ai-tools/issues/4213)) ([78c3b16](https://github.com/yschimke/compose-ai-tools/commit/78c3b166bca5389e4a4ca01cd0ab2e6644ae3215))
* **rc-player:** named-value changes rebuild RcPlayerState and discard animation and touch state ([#4181](https://github.com/yschimke/compose-ai-tools/issues/4181)) ([7f90b25](https://github.com/yschimke/compose-ai-tools/commit/7f90b252f4df8f4cf9b3c2e3cedefe33f717b481))
* **rc-player:** resolve branded typefaces in both embedded lanes ([#4174](https://github.com/yschimke/compose-ai-tools/issues/4174)) ([0d9216c](https://github.com/yschimke/compose-ai-tools/commit/0d9216c6d8d250eb025ea83365f7b558fa2223b9))
* **rc-player:** resolve colours computed in a draw-content block ([#4171](https://github.com/yschimke/compose-ai-tools/issues/4171)) ([f92344f](https://github.com/yschimke/compose-ai-tools/commit/f92344f8b50bd605ca3397a5b4d7d6ab18e2b28c))
* **rc-player:** un-strand RcNamedValueRenderTest from the old source set ([#4186](https://github.com/yschimke/compose-ai-tools/issues/4186)) ([2e91f6d](https://github.com/yschimke/compose-ai-tools/commit/2e91f6da9a31d12a0bdc9fe2383935d44abafa6e))
* **rc-player:** unrepresentable named types, colliding font identities, swallowed cancellation ([#4198](https://github.com/yschimke/compose-ai-tools/issues/4198)) ([f0a8613](https://github.com/yschimke/compose-ai-tools/commit/f0a86139d03803f47f4d7102dca68c532fc15699))
* **release:** check the Swift tag before writing, and compute one snapshot version ([#4208](https://github.com/yschimke/compose-ai-tools/issues/4208)) ([550dd18](https://github.com/yschimke/compose-ai-tools/commit/550dd18c23bcd8cf0120f9185c00a4e532bada36))
* **release:** publish Apple targets from macOS, and make the Swift tag resolvable ([#4197](https://github.com/yschimke/compose-ai-tools/issues/4197)) ([dc9b115](https://github.com/yschimke/compose-ai-tools/commit/dc9b1152753ea20f47f20e345fc12fb39d8f10a1))
* **renderer:** bind the desktop PNG encode against whatever skiko resolves ([#4200](https://github.com/yschimke/compose-ai-tools/issues/4200)) ([42d2776](https://github.com/yschimke/compose-ai-tools/commit/42d2776c00a165942e57f1ce0dd243c2b85e09da))
* **serve:** assert the viewer cache header a laneless catalog actually gets ([#4203](https://github.com/yschimke/compose-ai-tools/issues/4203)) ([4693b8d](https://github.com/yschimke/compose-ai-tools/commit/4693b8d7ba55655cc057a824bbdbf290d306dfac))
* **serve:** rename a helper colliding with the preview, and scope usage rules per module ([#4206](https://github.com/yschimke/compose-ai-tools/issues/4206)) ([a8ccf1c](https://github.com/yschimke/compose-ai-tools/commit/a8ccf1c4a9486de0c9663b871fe7d49fdcbabff6))
* **serve:** show the component a delegating sticker delegates to ([#4179](https://github.com/yschimke/compose-ai-tools/issues/4179)) ([157287b](https://github.com/yschimke/compose-ai-tools/commit/157287b78b61b61651547a565d5ff32ff93237c6))

## [1.15.0](https://github.com/yschimke/compose-ai-tools/compare/v1.14.1...v1.15.0) (2026-08-18)


### Features

* **serve:** persist warmed theme renders across restarts and regenerations ([#4145](https://github.com/yschimke/compose-ai-tools/issues/4145)) ([bd112b8](https://github.com/yschimke/compose-ai-tools/commit/bd112b8d3421146410aeaaa112815c769673924d))


### Bug Fixes

* **deps:** update dependency com.squareup.okhttp3:okhttp to v5.5.0 ([#4154](https://github.com/yschimke/compose-ai-tools/issues/4154)) ([cf2ab41](https://github.com/yschimke/compose-ai-tools/commit/cf2ab415d7cf81eaf16edc11a0f83c3781b3f09f))
* **serve:** address missed Codex review findings ([#4162](https://github.com/yschimke/compose-ai-tools/issues/4162)) ([4781e20](https://github.com/yschimke/compose-ai-tools/commit/4781e20284ec69a0ec0c0e254fa118e35ef21f22))
* **serve:** address PR 4162 review feedback ([#4163](https://github.com/yschimke/compose-ai-tools/issues/4163)) ([c4a737d](https://github.com/yschimke/compose-ai-tools/commit/c4a737d961f27af4a80980d6c0be1efbc6016126))
* **serve:** await cold style override renders ([#4152](https://github.com/yschimke/compose-ai-tools/issues/4152)) ([4c0c7a6](https://github.com/yschimke/compose-ai-tools/commit/4c0c7a6a4e5c262b114bed5419510d051922dd0a))
* **serve:** clarify status health and capacity ([#4155](https://github.com/yschimke/compose-ai-tools/issues/4155)) ([12e921f](https://github.com/yschimke/compose-ai-tools/commit/12e921feec907e7576a4b8ce74280e32412fe9e9))
* **serve:** improve UI issue reports ([#4153](https://github.com/yschimke/compose-ai-tools/issues/4153)) ([57ad6b2](https://github.com/yschimke/compose-ai-tools/commit/57ad6b2c661d8682365b64d895c206bdbbac10ef))
* **serve:** keep pinned revision controls consistent ([#4157](https://github.com/yschimke/compose-ai-tools/issues/4157)) ([e608bb4](https://github.com/yschimke/compose-ai-tools/commit/e608bb4d63391a7e59c32541ddfb5d21b33bc468))
* **serve:** name the renderer in Catalog mode, and offer what the staging can answer ([#4160](https://github.com/yschimke/compose-ai-tools/issues/4160)) ([54dfa91](https://github.com/yschimke/compose-ai-tools/commit/54dfa91898dac5a51f2d74e583f398c6ada26db9))
* **serve:** resolve UI consistency and state issues ([#4156](https://github.com/yschimke/compose-ai-tools/issues/4156)) ([3bb7bd0](https://github.com/yschimke/compose-ai-tools/commit/3bb7bd051491fff07b9e4618c7d86bf389f483e8))
* **serve:** stop a cold daemon permanently disabling a Remote Compose player ([#4158](https://github.com/yschimke/compose-ai-tools/issues/4158)) ([17169cf](https://github.com/yschimke/compose-ai-tools/commit/17169cf08a4a3182ff555000a45f88f354f84ee5))

## [1.14.1](https://github.com/yschimke/compose-ai-tools/compare/v1.14.0...v1.14.1) (2026-08-17)


### Bug Fixes

* **ci:** retry harness repository throttling ([#4131](https://github.com/yschimke/compose-ai-tools/issues/4131)) ([e6dd6e7](https://github.com/yschimke/compose-ai-tools/commit/e6dd6e7eb266c93a64fe59e885d9c15a9351ad4e))
* **ci:** stabilize preview edit e2e saves ([#4128](https://github.com/yschimke/compose-ai-tools/issues/4128)) ([2fc6f42](https://github.com/yschimke/compose-ai-tools/commit/2fc6f4221b7477637d4ca45f2a1288e6caca0e0b))
* **serve:** rotate optimizer lanes so a large catalog cannot be starved ([#4143](https://github.com/yschimke/compose-ai-tools/issues/4143)) ([0a020f7](https://github.com/yschimke/compose-ai-tools/commit/0a020f7a0c5474acd1ceba4d4214ed3152b0b9c7))

## [1.14.0](https://github.com/yschimke/compose-ai-tools/compare/v1.13.0...v1.14.0) (2026-08-17)


### Features

* **serve:** cap concurrent theme-optimizer passes, and let an admin pause them ([#4124](https://github.com/yschimke/compose-ai-tools/issues/4124)) ([5d6b0df](https://github.com/yschimke/compose-ai-tools/commit/5d6b0dfba6663e1ec8976fc83c5ccdf3bc9258ae))
* **serve:** drive the Motion lane's playback, with a transport under it ([#4126](https://github.com/yschimke/compose-ai-tools/issues/4126)) ([6cf8a0e](https://github.com/yschimke/compose-ai-tools/commit/6cf8a0e2d65a4bec6dedde09011cbc6c1f40b06d))
* **serve:** drop the header's duplicate home and repo links ([#4121](https://github.com/yschimke/compose-ai-tools/issues/4121)) ([d40ff91](https://github.com/yschimke/compose-ai-tools/commit/d40ff917f424fb24a3f0b6c765d3beea05885b49))
* **serve:** make the Motion capture picker a menu with a detail line ([#4123](https://github.com/yschimke/compose-ai-tools/issues/4123)) ([de7b61b](https://github.com/yschimke/compose-ai-tools/commit/de7b61b09245d1faeb1a0cccc02f92cde0d0da24))


### Bug Fixes

* **serve:** restore the per-preview issue report on the viewer ([#4125](https://github.com/yschimke/compose-ai-tools/issues/4125)) ([afae301](https://github.com/yschimke/compose-ai-tools/commit/afae301310a1647b9e6b494ca6e226a92abeba93))
* **vscode:** render a @Preview added to the file already on screen ([#4120](https://github.com/yschimke/compose-ai-tools/issues/4120)) ([839eca5](https://github.com/yschimke/compose-ai-tools/commit/839eca56f04d8771b0829aa11c85eaf2ebd87c3a))

## [1.13.0](https://github.com/yschimke/compose-ai-tools/compare/v1.12.0...v1.13.0) (2026-08-17)


### Features

* **serve:** answer 503 for a capture the branch is refusing ([#4116](https://github.com/yschimke/compose-ai-tools/issues/4116)) ([6c947ac](https://github.com/yschimke/compose-ai-tools/commit/6c947ac71fb1e20323d3a61291a10587e1bcb3d3))
* **serve:** report delivery-branch read counters on /status.json ([#4118](https://github.com/yschimke/compose-ai-tools/issues/4118)) ([b23711d](https://github.com/yschimke/compose-ai-tools/commit/b23711d6718f9a467eeb8def79df184137780c02))
* **serve:** tell a throttled branch read apart from a missing one ([#4113](https://github.com/yschimke/compose-ai-tools/issues/4113)) ([9edb671](https://github.com/yschimke/compose-ai-tools/commit/9edb6719d7dac1f04008078121da0e4cae1e782a))
* **splash:** capture the splash window in motion, not just the icon ([#4114](https://github.com/yschimke/compose-ai-tools/issues/4114)) ([03c51f2](https://github.com/yschimke/compose-ai-tools/commit/03c51f27cac42972359262089493f20ffbcbcc1e))


### Bug Fixes

* **ci:** refresh the design-artifacts driver pin to v1.12.0 ([#4106](https://github.com/yschimke/compose-ai-tools/issues/4106)) ([0568bcc](https://github.com/yschimke/compose-ai-tools/commit/0568bcc99fc5b895179b62d421eb69b11e9df151))
* **ci:** stop the export-driver pin needing a hand-written PR ([#4112](https://github.com/yschimke/compose-ai-tools/issues/4112)) ([25872ee](https://github.com/yschimke/compose-ai-tools/commit/25872ee42bcf523049428d2acf1fbf4a2b2d1e47))
* **design-artifacts:** union the tag index when folding a catalog section ([#4115](https://github.com/yschimke/compose-ai-tools/issues/4115)) ([3573e08](https://github.com/yschimke/compose-ai-tools/commit/3573e0804562818ffef53288339151b730de83b6))
* **serve:** keep the motion lease safe under cancellation and unknown ids ([#4111](https://github.com/yschimke/compose-ai-tools/issues/4111)) ([bdb3b56](https://github.com/yschimke/compose-ai-tools/commit/bdb3b56cd6fe1caf49bd3ea1fe0e0a31e6516abc))
* **serve:** make published motion captures reachable again ([#4110](https://github.com/yschimke/compose-ai-tools/issues/4110)) ([8714b36](https://github.com/yschimke/compose-ai-tools/commit/8714b369712e4454d16e2410f8ea1a08bdf128e8))

## [1.12.0](https://github.com/yschimke/compose-ai-tools/compare/v1.11.1...v1.12.0) (2026-08-17)


### Features

* **design-map:** project a variant's declared kit axis and value ([#4094](https://github.com/yschimke/compose-ai-tools/issues/4094)) ([db09322](https://github.com/yschimke/compose-ai-tools/commit/db09322e7b7409fe1eeebf059263b41d1e4c9fe9))
* **render:** add an `auto` mode to the rewritten-SlotTable opt-in ([#4099](https://github.com/yschimke/compose-ai-tools/issues/4099)) ([4baeea0](https://github.com/yschimke/compose-ai-tools/commit/4baeea00e15d92818b09b5e259ea735b488c1847))


### Bug Fixes

* **design-artifacts:** import bundleModulePath in the catalog generator ([#4103](https://github.com/yschimke/compose-ai-tools/issues/4103)) ([9ca91f7](https://github.com/yschimke/compose-ai-tools/commit/9ca91f765082c80bb46cb502a9df133fe915cfdb))
* **render:** degrade the composer opt-in only for a real version floor ([#4104](https://github.com/yschimke/compose-ai-tools/issues/4104)) ([6b9ae9e](https://github.com/yschimke/compose-ai-tools/commit/6b9ae9e3e65036966a475acab2ca562387e63651))
* **renderer:** share the pressed-ripple settle with focus GIFs ([#4100](https://github.com/yschimke/compose-ai-tools/issues/4100)) ([b365017](https://github.com/yschimke/compose-ai-tools/commit/b36501708b852ae614f844b2a123b5b57e1839c1))

## [1.11.1](https://github.com/yschimke/compose-ai-tools/compare/v1.11.0...v1.11.1) (2026-08-17)


### Bug Fixes

* **catalog:** stop the Wear pressed specimen depending on the shard layout ([#4095](https://github.com/yschimke/compose-ai-tools/issues/4095)) ([ba309d9](https://github.com/yschimke/compose-ai-tools/commit/ba309d9da71090aed2ba4de270c2f4dbd275f27f))
* **ci:** repair the three red workflows on main ([#4092](https://github.com/yschimke/compose-ai-tools/issues/4092)) ([02b540f](https://github.com/yschimke/compose-ai-tools/commit/02b540ffcdd7d908b673fa25ae2eac4eed4080de))
* **renderer:** settle the pressed ripple independently of sandbox age ([#4096](https://github.com/yschimke/compose-ai-tools/issues/4096)) ([f23e128](https://github.com/yschimke/compose-ai-tools/commit/f23e1287467804994f447f19910b32b3ddaf35c4))
* **serve:** enable the daemon's a11y extension before fetching the overlay ([#4090](https://github.com/yschimke/compose-ai-tools/issues/4090)) ([a2cf3e7](https://github.com/yschimke/compose-ai-tools/commit/a2cf3e7deacf1fd146fdb01f74c651f9c0567dd2))
* **serve:** open Catalog mode on the baked snapshot ([#4093](https://github.com/yschimke/compose-ai-tools/issues/4093)) ([d2ccc99](https://github.com/yschimke/compose-ai-tools/commit/d2ccc99204a6f99e9eb766f39ae6b2a23137695a))
* **serve:** stop scoring a render the imported spec cannot describe ([#4088](https://github.com/yschimke/compose-ai-tools/issues/4088)) ([e09ecc5](https://github.com/yschimke/compose-ai-tools/commit/e09ecc5bd65ce992f416ba3e6c17a074d0d5d912))
* **serve:** stop the render-server badge painting as a bar in Catalog mode ([#4091](https://github.com/yschimke/compose-ai-tools/issues/4091)) ([7532357](https://github.com/yschimke/compose-ai-tools/commit/7532357976dbf0805230b1485c706a6d22d4981b))

## [1.11.0](https://github.com/yschimke/compose-ai-tools/compare/v1.10.1...v1.11.0) (2026-08-16)


### Features

* **design-artifacts:** support live bundles across all modules ([#4080](https://github.com/yschimke/compose-ai-tools/issues/4080)) ([6200880](https://github.com/yschimke/compose-ai-tools/commit/62008800ae1c15be95b9a8f891e7261f431d2fdf))
* **serve:** remember Catalog / Dev in a cookie, not in every URL ([#4087](https://github.com/yschimke/compose-ai-tools/issues/4087)) ([3ecf53b](https://github.com/yschimke/compose-ai-tools/commit/3ecf53b2416a155a95bad407f009ac33bdcce68a))


### Bug Fixes

* **catalog:** pair a motion capture with its own theme ([#4082](https://github.com/yschimke/compose-ai-tools/issues/4082)) ([2f12fd6](https://github.com/yschimke/compose-ai-tools/commit/2f12fd64f9d3abf7d6d646445c911386cc56d14d))
* **ci:** pin live module bundle driver ([#4085](https://github.com/yschimke/compose-ai-tools/issues/4085)) ([09176c6](https://github.com/yschimke/compose-ai-tools/commit/09176c695185ffd9fd222825d555c68c7ce471e9))
* **ci:** refresh design artifact driver pin ([#4084](https://github.com/yschimke/compose-ai-tools/issues/4084)) ([7101f91](https://github.com/yschimke/compose-ai-tools/commit/7101f914448cad3b0439d20018b168e55492815f))
* **design-map:** project an @OverrideVariant cell that sits on a @CatalogVariant ([#4081](https://github.com/yschimke/compose-ai-tools/issues/4081)) ([b960825](https://github.com/yschimke/compose-ai-tools/commit/b9608259a51d835a90ff58ed77d0ea9f1c5ced82))

## [1.10.1](https://github.com/yschimke/compose-ai-tools/compare/v1.10.0...v1.10.1) (2026-08-16)


### Bug Fixes

* Address recent review feedback ([#4075](https://github.com/yschimke/compose-ai-tools/issues/4075)) ([61f7e2f](https://github.com/yschimke/compose-ai-tools/commit/61f7e2fd06ae61874e795aa1143e94ef220bc47a))
* **catalog:** publish the motion axis the render already produced ([#4074](https://github.com/yschimke/compose-ai-tools/issues/4074)) ([b2660a2](https://github.com/yschimke/compose-ai-tools/commit/b2660a25118b634ddb6a94ccb0e7979d65b157e8))
* **ci:** refresh design artifact driver pin ([#4079](https://github.com/yschimke/compose-ai-tools/issues/4079)) ([b712683](https://github.com/yschimke/compose-ai-tools/commit/b71268397c95d014873a672ad87c899fa6cf73af))
* Tighten component browser layout ([#4077](https://github.com/yschimke/compose-ai-tools/issues/4077)) ([407702f](https://github.com/yschimke/compose-ai-tools/commit/407702f26590c31efde4e563d2f43e4c5202041d))
* **vscode:** await daemon warmup for viewport updates ([#4076](https://github.com/yschimke/compose-ai-tools/issues/4076)) ([0404cca](https://github.com/yschimke/compose-ai-tools/commit/0404cca257ec4e13f427258d8df130d39b305b48))

## [1.10.0](https://github.com/yschimke/compose-ai-tools/compare/v1.9.0...v1.10.0) (2026-08-16)


### Features

* **serve:** report a bug in the preview server itself ([#4069](https://github.com/yschimke/compose-ai-tools/issues/4069)) ([a116a61](https://github.com/yschimke/compose-ai-tools/commit/a116a6138f73bd8c1381efca4351398cb3ac5f2a))
* **serve:** surface resolved typography details ([#4051](https://github.com/yschimke/compose-ai-tools/issues/4051)) ([e0568a9](https://github.com/yschimke/compose-ai-tools/commit/e0568a997ba11d1c73c3cdad27010de4cbbfe156))
* **serve:** surface typography across preview comparisons ([#4053](https://github.com/yschimke/compose-ai-tools/issues/4053)) ([71d754f](https://github.com/yschimke/compose-ai-tools/commit/71d754f10e4f92c15dc0f0ea4d9f26884ff38121))


### Bug Fixes

* repair component browser CI regressions ([#4071](https://github.com/yschimke/compose-ai-tools/issues/4071)) ([85c4346](https://github.com/yschimke/compose-ai-tools/commit/85c4346c7f52809d2d507f9b743c877989b1c258))
* **serve:** expose accessibility inspection on catalog previews ([#4070](https://github.com/yschimke/compose-ai-tools/issues/4070)) ([0ab4a9b](https://github.com/yschimke/compose-ai-tools/commit/0ab4a9b0177b48785c54d4155703d10a45d6de32))

## [1.9.0](https://github.com/yschimke/compose-ai-tools/compare/v1.8.0...v1.9.0) (2026-08-16)


### Features

* **a11y:** skip annotated preview helpers ([#3987](https://github.com/yschimke/compose-ai-tools/issues/3987)) ([5d32e5d](https://github.com/yschimke/compose-ai-tools/commit/5d32e5dc2a83c505e943714799e28c3bd8ee0cd1))
* **design-artifacts:** publish all preview modules ([#3986](https://github.com/yschimke/compose-ai-tools/issues/3986)) ([5670501](https://github.com/yschimke/compose-ai-tools/commit/5670501d3552c5287ddd679d92d3de667207bab9))
* **design-catalog-remote-m3:** add a ColorTheme sticker for the second theming path ([#3971](https://github.com/yschimke/compose-ai-tools/issues/3971)) ([f95bd55](https://github.com/yschimke/compose-ai-tools/commit/f95bd556ed699f953be8154a3dafbf79c9d93c8d))
* **glimmer:** add environment post-compositor ([#4003](https://github.com/yschimke/compose-ai-tools/issues/4003)) ([8c8c5d8](https://github.com/yschimke/compose-ai-tools/commit/8c8c5d8c6aa4ea4e85bb65a85870166f1488eb30))
* **playground:** add incremental editing lease ([#3991](https://github.com/yschimke/compose-ai-tools/issues/3991)) ([9fc7b30](https://github.com/yschimke/compose-ai-tools/commit/9fc7b301a40ecb8cb096f46d49395333a6004217))
* **serve:** drop the about box, move catalog details into the footer ([#3964](https://github.com/yschimke/compose-ai-tools/issues/3964)) ([2bd0526](https://github.com/yschimke/compose-ai-tools/commit/2bd0526ee5aa8fca1d8e54db3ee3c35900e6f9a3))
* **serve:** highlight preview source view ([#4049](https://github.com/yschimke/compose-ai-tools/issues/4049)) ([22d0667](https://github.com/yschimke/compose-ai-tools/commit/22d0667006562651280e4a3df25ec75e3c7dba88))
* **serve:** make exploded views easier to read ([#3965](https://github.com/yschimke/compose-ai-tools/issues/3965)) ([a475898](https://github.com/yschimke/compose-ai-tools/commit/a4758989a7ef758584747958ea9b06184069b9ac))
* **serve:** open Remote Compose previews on the embedded player ([#4012](https://github.com/yschimke/compose-ai-tools/issues/4012)) ([a48aa55](https://github.com/yschimke/compose-ai-tools/commit/a48aa55cbeae99145abc1a0fb7419c06c18d246e))
* **serve:** reuse the viewer's Theme dropdown and title row on the catalog ([#3967](https://github.com/yschimke/compose-ai-tools/issues/3967)) ([7b9c6af](https://github.com/yschimke/compose-ai-tools/commit/7b9c6afb75d10a5efe045039ae287269a52a149d))


### Bug Fixes

* **build:** restore JVM checks ([#4038](https://github.com/yschimke/compose-ai-tools/issues/4038)) ([b841929](https://github.com/yschimke/compose-ai-tools/commit/b8419297008ff4923ab6f3520caf1f0001c68143))
* **catalog:** isolate multi-module preview identities ([#4040](https://github.com/yschimke/compose-ai-tools/issues/4040)) ([c7f2758](https://github.com/yschimke/compose-ai-tools/commit/c7f2758689383558308ecc1edad1f24b46a9f0b4))
* **ci:** align AdaptiveJetStream with XR alpha17 ([#4044](https://github.com/yschimke/compose-ai-tools/issues/4044)) ([aa55b36](https://github.com/yschimke/compose-ai-tools/commit/aa55b363b1ca96fc5d8fe30830a920fc0f522f79))
* **ci:** refresh design artifact driver pin ([#4016](https://github.com/yschimke/compose-ai-tools/issues/4016)) ([59d0e75](https://github.com/yschimke/compose-ai-tools/commit/59d0e751861596d41a8ecba633a4e65552865e0f))
* **ci:** repair main build failures ([#4021](https://github.com/yschimke/compose-ai-tools/issues/4021)) ([8b4cbe8](https://github.com/yschimke/compose-ai-tools/commit/8b4cbe87d9362373f2c9f3398d850f341f5dc3f0))
* **ci:** resolve code scanning alerts ([#4002](https://github.com/yschimke/compose-ai-tools/issues/4002)) ([81727e9](https://github.com/yschimke/compose-ai-tools/commit/81727e959c63ff46ba6e598760771d9905386d9a))
* **ci:** restore Wear pressed-state fixture ([#4046](https://github.com/yschimke/compose-ai-tools/issues/4046)) ([257735a](https://github.com/yschimke/compose-ai-tools/commit/257735ac487cd340dbae332d45bc7cc3042c2abb))
* **ci:** run Confetti smoke on Java 21 ([#4047](https://github.com/yschimke/compose-ai-tools/issues/4047)) ([bfc18e1](https://github.com/yschimke/compose-ai-tools/commit/bfc18e1d42a374b7204cdeb97ab052f008e406ba))
* **cli:** make URL state assertion resilient ([#4043](https://github.com/yschimke/compose-ai-tools/issues/4043)) ([3048b8b](https://github.com/yschimke/compose-ai-tools/commit/3048b8bc7fda9a0ecd43156cd0540798fa7ca1c9))
* **deps:** update dependency androidx.tracing:tracing to v2 ([#3997](https://github.com/yschimke/compose-ai-tools/issues/3997)) ([6cb3b3f](https://github.com/yschimke/compose-ai-tools/commit/6cb3b3f669de421b8b4e9140f38fe862fadd163e))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.189 ([#3996](https://github.com/yschimke/compose-ai-tools/issues/3996)) ([bd8a78d](https://github.com/yschimke/compose-ai-tools/commit/bd8a78d3b5200c09eefe21237d0b173ff125a9e4))
* **deps:** update gradle minor/patch ([#4018](https://github.com/yschimke/compose-ai-tools/issues/4018)) ([b7f76fd](https://github.com/yschimke/compose-ai-tools/commit/b7f76fdf67f1033c1279af351c89ba0892872a71))
* **deps:** update Wear Compose to 1.7.0-beta01 ([#4031](https://github.com/yschimke/compose-ai-tools/issues/4031)) ([5c767e0](https://github.com/yschimke/compose-ai-tools/commit/5c767e0770b90c9958f9c60d48f55bfae3ae2ab7))
* **glimmer:** address environment render feedback ([#4022](https://github.com/yschimke/compose-ai-tools/issues/4022)) ([49eedc1](https://github.com/yschimke/compose-ai-tools/commit/49eedc1a350f216fe9badb9fa3d703b972af9d72))
* **playground:** harden editing lease state ([#4014](https://github.com/yschimke/compose-ai-tools/issues/4014)) ([68a04b5](https://github.com/yschimke/compose-ai-tools/commit/68a04b5615689113386677996be4f061bac54e84))
* **playground:** ignore comments in empty wrappers ([#4042](https://github.com/yschimke/compose-ai-tools/issues/4042)) ([0c044aa](https://github.com/yschimke/compose-ai-tools/commit/0c044aaeb118b99f2fc32e8231ba18969355170a))
* **playground:** keep diagnostics with edited buffers ([#4034](https://github.com/yschimke/compose-ai-tools/issues/4034)) ([bac865d](https://github.com/yschimke/compose-ai-tools/commit/bac865d239c1e6d60efe32e188d36c3103669624))
* **playground:** make Material 3 themes follow night mode ([#3988](https://github.com/yschimke/compose-ai-tools/issues/3988)) ([0d0a05f](https://github.com/yschimke/compose-ai-tools/commit/0d0a05f6cf7659cb407272485070b4304908b7b1))
* **playground:** preserve rejected lease state ([#4026](https://github.com/yschimke/compose-ai-tools/issues/4026)) ([70e947a](https://github.com/yschimke/compose-ai-tools/commit/70e947a7a80fd71b2307b6d9e68b4207ef5b41c8))
* **playground:** qualify expanded theme wrappers ([#4035](https://github.com/yschimke/compose-ai-tools/issues/4035)) ([b74b6d4](https://github.com/yschimke/compose-ai-tools/commit/b74b6d4582a7fbc4c7ded3d094583f19a7066ecf))
* **playground:** release idle editing leases ([#3998](https://github.com/yschimke/compose-ai-tools/issues/3998)) ([0b7bcc3](https://github.com/yschimke/compose-ai-tools/commit/0b7bcc32b16416f4da2ec4841c66f3641152d543))
* **playground:** retain last editing revision ([#4019](https://github.com/yschimke/compose-ai-tools/issues/4019)) ([b902cc4](https://github.com/yschimke/compose-ai-tools/commit/b902cc4f87576d08c5aae5880c807233663df03e))
* **playground:** show compile errors inline ([#3980](https://github.com/yschimke/compose-ai-tools/issues/3980)) ([91538eb](https://github.com/yschimke/compose-ai-tools/commit/91538eb7280f506ba096b2c62615c51a820b6ce9))
* **preview-annotations:** expose design-kit variant mappings ([#4005](https://github.com/yschimke/compose-ai-tools/issues/4005)) ([20389c4](https://github.com/yschimke/compose-ai-tools/commit/20389c475dd46441a1cdcd6ce6bb6216bb43edf1)), closes [#3899](https://github.com/yschimke/compose-ai-tools/issues/3899)
* **preview:** carry and capture motion surfaces ([#4023](https://github.com/yschimke/compose-ai-tools/issues/4023)) ([bbaeac9](https://github.com/yschimke/compose-ai-tools/commit/bbaeac9257909a7958b59d4cb29c6e1ad9bb93f3))
* **rc-embedded-player:** clip inside layout modifiers, not outside them ([#4008](https://github.com/yschimke/compose-ai-tools/issues/4008)) ([e00d85d](https://github.com/yschimke/compose-ai-tools/commit/e00d85d28738cff5b3f806d646e7fa805d38fddd))
* **rc-embedded-player:** evaluate paint-channel ops so dynamic colours resolve ([#3977](https://github.com/yschimke/compose-ai-tools/issues/3977)) ([80c6111](https://github.com/yschimke/compose-ai-tools/commit/80c61119357ce6660bc864d285c5925b999e23c9))
* **rc-embedded-player:** isolate bitmap decode failures ([#4000](https://github.com/yschimke/compose-ai-tools/issues/4000)) ([7790706](https://github.com/yschimke/compose-ai-tools/commit/7790706bc10f2c1d640fc20e59bf496dc4693460)), closes [#3993](https://github.com/yschimke/compose-ai-tools/issues/3993)
* **rc-embedded-player:** let the graph read measured component sizes ([#3995](https://github.com/yschimke/compose-ai-tools/issues/3995)) ([8f76c84](https://github.com/yschimke/compose-ai-tools/commit/8f76c84ac70ae64959d95b56a50e9e01921d053c))
* **rc-embedded-player:** skip nested bitmap setup ([#4020](https://github.com/yschimke/compose-ai-tools/issues/4020)) ([0f0a0c5](https://github.com/yschimke/compose-ai-tools/commit/0f0a0c5f893c0292205834f838bf37b99b4ab7dc))
* **rc-harness:** validate manifest-complete A/B runs ([#4036](https://github.com/yschimke/compose-ai-tools/issues/4036)) ([d075e9c](https://github.com/yschimke/compose-ai-tools/commit/d075e9c931b63a06e2d89a995f847d85ca4bd190))
* **rc-player:** honor default renderer and theme ([#4028](https://github.com/yschimke/compose-ai-tools/issues/4028)) ([8c7fa44](https://github.com/yschimke/compose-ai-tools/commit/8c7fa44221fef0990dad141e2fcbf334b845a1b8))
* **rc-player:** make dynamic color diagnostics reproducible ([#4037](https://github.com/yschimke/compose-ai-tools/issues/4037)) ([8ee66e0](https://github.com/yschimke/compose-ai-tools/commit/8ee66e085f217c0a8672613616f80af97a68ebec))
* **rc-player:** preserve JVM text correctness ([#4039](https://github.com/yschimke/compose-ai-tools/issues/4039)) ([a131d6d](https://github.com/yschimke/compose-ai-tools/commit/a131d6d5f94e6687cb5620a784f3383c31be22df))
* **rc-player:** resolve ColorTheme indices and modes in every player ([#3962](https://github.com/yschimke/compose-ai-tools/issues/3962)) ([8890d61](https://github.com/yschimke/compose-ai-tools/commit/8890d61f267285b45554914a0cea2420278b7e0c))
* **rc-player:** track diagnostic task output ([#4045](https://github.com/yschimke/compose-ai-tools/issues/4045)) ([ec9f727](https://github.com/yschimke/compose-ai-tools/commit/ec9f727a6373aaebc19805295a52748f5c90d845))
* **release:** keep action refs on release tag ([#4027](https://github.com/yschimke/compose-ai-tools/issues/4027)) ([31cc7be](https://github.com/yschimke/compose-ai-tools/commit/31cc7bef96adcf7df3953c321ef762f707ec556b))
* **renderer:** press Wear buttons through focused key input ([#4011](https://github.com/yschimke/compose-ai-tools/issues/4011)) ([12b5f99](https://github.com/yschimke/compose-ai-tools/commit/12b5f99410f5a7b824d4179b961749838e4364ad))
* **samples:** adopt Material 3 alpha26 ripple API ([#4033](https://github.com/yschimke/compose-ai-tools/issues/4033)) ([ebb4cd8](https://github.com/yschimke/compose-ai-tools/commit/ebb4cd8224bd1877c928a532ebbf595343310182))
* **serve:** compare the typography family the table actually shows ([#3981](https://github.com/yschimke/compose-ai-tools/issues/3981)) ([4bcdc37](https://github.com/yschimke/compose-ai-tools/commit/4bcdc37d6f76397b42a279e056fd7f0507041369))
* **serve:** filter unavailable historic preview revisions ([#3984](https://github.com/yschimke/compose-ai-tools/issues/3984)) ([b5358bd](https://github.com/yschimke/compose-ai-tools/commit/b5358bd590778108b88d3d95b9d54298f19e7440))
* **serve:** keep disabled themes at spec baseline ([#4013](https://github.com/yschimke/compose-ai-tools/issues/4013)) ([048ffb1](https://github.com/yschimke/compose-ai-tools/commit/048ffb1826b31cffea82123892c893f925c28740))
* **serve:** point 'compare to Figma' at the reference comparison ([#3968](https://github.com/yschimke/compose-ai-tools/issues/3968)) ([964fade](https://github.com/yschimke/compose-ai-tools/commit/964faded786d763009bfe6e8b59b154d409b53f2))
* **serve:** preserve Remote Compose defaults ([#4041](https://github.com/yschimke/compose-ai-tools/issues/4041)) ([061e21e](https://github.com/yschimke/compose-ai-tools/commit/061e21e108ff99a5df76c962579a92c8d48a2f13))
* **serve:** publish themed spec baseline early ([#4001](https://github.com/yschimke/compose-ai-tools/issues/4001)) ([63dd154](https://github.com/yschimke/compose-ai-tools/commit/63dd154e6f1ad728066e02a2d843b2829d5e363a))
* **serve:** read a fractional translate from a Figma SVG export ([#3979](https://github.com/yschimke/compose-ai-tools/issues/3979)) ([0756218](https://github.com/yschimke/compose-ai-tools/commit/07562186c3126f24061ec3377fa82015dab09bc2))
* **serve:** reset reference compare on reconnect ([#4032](https://github.com/yschimke/compose-ai-tools/issues/4032)) ([42a3514](https://github.com/yschimke/compose-ai-tools/commit/42a3514a1116a0e0771d9b44d3d46bd7ebf5959f))
* **serve:** tie spec verdict to visible baseline ([#4029](https://github.com/yschimke/compose-ai-tools/issues/4029)) ([2b191e8](https://github.com/yschimke/compose-ai-tools/commit/2b191e8be2254750abe781f174f8147aec68bbf4))
* **vscode:** reconcile manifests before daemon startup ([#4050](https://github.com/yschimke/compose-ai-tools/issues/4050)) ([d821f1f](https://github.com/yschimke/compose-ai-tools/commit/d821f1fee20cd8ea59444a590eb6cae993a9cf3b))

## [1.8.0](https://github.com/yschimke/compose-ai-tools/compare/v1.7.0...v1.8.0) (2026-08-15)


### Features

* **design-artifacts:** declare interaction variants, and gate --strict before writing ([#3901](https://github.com/yschimke/compose-ai-tools/issues/3901)) ([855bcc1](https://github.com/yschimke/compose-ai-tools/commit/855bcc1280211001391246b0e9825f4fc32b4b9e))
* **design-artifacts:** publish motion captures onto the delivery branch ([#3917](https://github.com/yschimke/compose-ai-tools/issues/3917)) ([b38de4e](https://github.com/yschimke/compose-ai-tools/commit/b38de4ece8a3169c25133c3a886bc7770ad85fc5))
* **design-map:** publish the projection as an npm package ([#3918](https://github.com/yschimke/compose-ai-tools/issues/3918)) ([8330994](https://github.com/yschimke/compose-ai-tools/commit/833099499e679dd2edb5a52cb7e3a11b536ae2eb))
* **rc-player:** read a document's override surface from its own operations ([#3947](https://github.com/yschimke/compose-ai-tools/issues/3947)) ([e405cd2](https://github.com/yschimke/compose-ai-tools/commit/e405cd2c311ebc7b1b394b7018af23de22a457cf))
* **release:** comment deploy and Maven milestones on the release PR ([#3903](https://github.com/yschimke/compose-ai-tools/issues/3903)) ([43d1656](https://github.com/yschimke/compose-ai-tools/commit/43d1656d2fbf81112fdbbef86f9be97a4ee369aa))
* **serve:** add demand-activated catalog RSS feeds ([#3933](https://github.com/yschimke/compose-ai-tools/issues/3933)) ([07903a7](https://github.com/yschimke/compose-ai-tools/commit/07903a7fda7453196a0deb321453356bf9c62da7))
* **serve:** carry published motion captures through the store, host and route ([#3921](https://github.com/yschimke/compose-ai-tools/issues/3921)) ([7625eae](https://github.com/yschimke/compose-ai-tools/commit/7625eae0c31bba76b3bb23005d665f002352b329))
* **serve:** combine the status count into the Status link, and make render history a menu ([#3924](https://github.com/yschimke/compose-ai-tools/issues/3924)) ([b857c5c](https://github.com/yschimke/compose-ai-tools/commit/b857c5c12f6b7b293d69b683270a813c9856cf91))
* **serve:** Components and Pages are two searchable sidebar panes ([#3926](https://github.com/yschimke/compose-ai-tools/issues/3926)) ([dac63d1](https://github.com/yschimke/compose-ai-tools/commit/dac63d1356c01fee78a46128e552e0ee550bc0c8))
* **serve:** offer a preview's recorded interaction as a selectable Motion lane ([#3928](https://github.com/yschimke/compose-ai-tools/issues/3928)) ([581cbbe](https://github.com/yschimke/compose-ai-tools/commit/581cbbe5a142fdbda49b8dea71b0a395d8ede553))
* **serve:** sign in on a top-level site host via a parent cookie domain ([#3920](https://github.com/yschimke/compose-ai-tools/issues/3920)) ([7046d98](https://github.com/yschimke/compose-ai-tools/commit/7046d987d4d04b94880fe7f8735f5bb63c2deef6))
* **serve:** the catalog's chrome is one toolbar row on a phone ([#3906](https://github.com/yschimke/compose-ai-tools/issues/3906)) ([298e821](https://github.com/yschimke/compose-ai-tools/commit/298e821fae58552137d219230471fbad4147d9e1))
* **serve:** the design-spec chip states the match, and opens the diff ([#3910](https://github.com/yschimke/compose-ai-tools/issues/3910)) ([633a2b6](https://github.com/yschimke/compose-ai-tools/commit/633a2b606203c84eeaca4e9ae6e8c994f1a352fa))
* **serve:** the Pages pane is a tree of each page's major sections ([#3927](https://github.com/yschimke/compose-ai-tools/issues/3927)) ([5432af6](https://github.com/yschimke/compose-ai-tools/commit/5432af66e67878499fbdaaba32b2a5fdc35bcf6c))
* **serve:** zoom and drill into a design page's sheet ([#3904](https://github.com/yschimke/compose-ai-tools/issues/3904)) ([61599de](https://github.com/yschimke/compose-ai-tools/commit/61599de6ebaa0ac6420c335aab3ea9e98b142291))


### Bug Fixes

* address missed review feedback ([#3954](https://github.com/yschimke/compose-ai-tools/issues/3954)) ([fdd01dd](https://github.com/yschimke/compose-ai-tools/commit/fdd01dd21dcf4246f034058a14f00cdc1c6b7122))
* **ci:** compare shared CMP/Wasm render failures ([#3953](https://github.com/yschimke/compose-ai-tools/issues/3953)) ([88d49e7](https://github.com/yschimke/compose-ai-tools/commit/88d49e7e7bc06ed9fd6055ad231b320994182e72))
* **cli:** address keyboard navigation review feedback ([#3942](https://github.com/yschimke/compose-ai-tools/issues/3942)) ([3ab9c80](https://github.com/yschimke/compose-ai-tools/commit/3ab9c80d2f9f6236706cec0f7b4b7fb911f9f665))
* **design-artifacts:** stamp @OverrideVariant stickers with their renderer density ([#3908](https://github.com/yschimke/compose-ai-tools/issues/3908)) ([1b2b48e](https://github.com/yschimke/compose-ai-tools/commit/1b2b48ed2474e2cdb5c7a1070fdd9d806bdf3353))
* **design-map:** drop the leading ./ from the bin path ([#3925](https://github.com/yschimke/compose-ai-tools/issues/3925)) ([4e75fad](https://github.com/yschimke/compose-ai-tools/commit/4e75fadcb58cf2265f65cc25f2e3d6747cbf1293))
* **preview:** wrap motion captures to their content, not the device sandbox ([#3912](https://github.com/yschimke/compose-ai-tools/issues/3912)) ([8322d55](https://github.com/yschimke/compose-ai-tools/commit/8322d55d37db2bd03156314fffcd98e087b01160))
* **rc-player:** render tinted icons ([#3937](https://github.com/yschimke/compose-ai-tools/issues/3937)) ([a875c0e](https://github.com/yschimke/compose-ai-tools/commit/a875c0e557eb1b01d2363d9182ab2b63b2eade35))
* **rc-player:** resolve embedded theme colors ([#3952](https://github.com/yschimke/compose-ai-tools/issues/3952)) ([78c8088](https://github.com/yschimke/compose-ai-tools/commit/78c808836871b4b072923fe1f3e47cf04cb63f16))
* **remote-m3:** address post-merge preview failures ([#3944](https://github.com/yschimke/compose-ai-tools/issues/3944)) ([96d9ae3](https://github.com/yschimke/compose-ai-tools/commit/96d9ae3787e3a7afcb09f95917c27d23ac88a6c0))
* **remote-m3:** address preview review feedback ([#3939](https://github.com/yschimke/compose-ai-tools/issues/3939)) ([688801d](https://github.com/yschimke/compose-ai-tools/commit/688801d9775d6e4e7f000878fa74777b281144df))
* **renderer:** preserve capture timing while settling ([#3945](https://github.com/yschimke/compose-ai-tools/issues/3945)) ([a2c9e43](https://github.com/yschimke/compose-ai-tools/commit/a2c9e43085906f02979199c83881090cc23a9bb4))
* **renderer:** settle still-frame captures adaptively ([#3938](https://github.com/yschimke/compose-ai-tools/issues/3938)) ([8000ca2](https://github.com/yschimke/compose-ai-tools/commit/8000ca208835c8a8269e8eda60692e6bb411487f))
* **serve:** a design page's headers are furniture, not missing components ([#3919](https://github.com/yschimke/compose-ai-tools/issues/3919)) ([4a0ac92](https://github.com/yschimke/compose-ai-tools/commit/4a0ac9267cf49756b662504465e4626883d8430c))
* **serve:** fit a design page's renders to the component, not the canvas ([#3900](https://github.com/yschimke/compose-ai-tools/issues/3900)) ([2f114aa](https://github.com/yschimke/compose-ai-tools/commit/2f114aaa62609c46e69556bd7106ae5d48b57edc))
* **serve:** harden catalog change feeds ([#3941](https://github.com/yschimke/compose-ai-tools/issues/3941)) ([cb9e1a0](https://github.com/yschimke/compose-ai-tools/commit/cb9e1a050e0b611aaaf0c22777f85205a7bd0102))
* **serve:** land section links on an anchor that exists, and keep Enter navigating ([#3930](https://github.com/yschimke/compose-ai-tools/issues/3930)) ([7c24948](https://github.com/yschimke/compose-ai-tools/commit/7c249483f56ef906ef939776cba0ee0045f797ad))
* **serve:** move the catalog's tally below the grid, and four review fixes ([#3907](https://github.com/yschimke/compose-ai-tools/issues/3907)) ([fc28cb8](https://github.com/yschimke/compose-ai-tools/commit/fc28cb8e0ad16cb795286331e46353e8b3b765e3))
* **serve:** repair main, and close the review findings on the chip verdict ([#3915](https://github.com/yschimke/compose-ai-tools/issues/3915)) ([60b34b2](https://github.com/yschimke/compose-ai-tools/commit/60b34b27677c979a886a094f979624324dc6d612))
* **serve:** revalidate published captures instead of promising them immutable ([#3932](https://github.com/yschimke/compose-ai-tools/issues/3932)) ([2c1b401](https://github.com/yschimke/compose-ai-tools/commit/2c1b4016466e0c16c46f0289b637c03ef14f56ba))

## [1.7.0](https://github.com/yschimke/compose-ai-tools/compare/v1.6.0...v1.7.0) (2026-08-15)


### Features

* **design-artifacts:** emit design-map.json from the catalog annotations ([#3894](https://github.com/yschimke/compose-ai-tools/issues/3894)) ([811be0b](https://github.com/yschimke/compose-ai-tools/commit/811be0b16ec70d02c713876369566c1c157878ff))
* **preview:** capture a preview's interaction as a 60fps motion artifact ([#3897](https://github.com/yschimke/compose-ai-tools/issues/3897)) ([f19e499](https://github.com/yschimke/compose-ai-tools/commit/f19e499dfe143f7d0ff8d2f1158aa2af029e0b63))
* **serve:** a phone gets the bar, the title, and the page ([#3898](https://github.com/yschimke/compose-ai-tools/issues/3898)) ([97130be](https://github.com/yschimke/compose-ai-tools/commit/97130be5909dd6da301289132262797e4264f67a))
* **serve:** add parity locator reporting ([#3887](https://github.com/yschimke/compose-ai-tools/issues/3887)) ([3bd6ecd](https://github.com/yschimke/compose-ai-tools/commit/3bd6ecd62d05421ed9b10e685cb192023053876b))


### Bug Fixes

* **design-artifacts:** exempt declared no-sticker previews from the shard render check ([#3888](https://github.com/yschimke/compose-ai-tools/issues/3888)) ([9391cbf](https://github.com/yschimke/compose-ai-tools/commit/9391cbfedf4a2ccbc6dd423bff79712ba2656a1b))
* **design-artifacts:** fail a sharded render that lost previews ([#3885](https://github.com/yschimke/compose-ai-tools/issues/3885)) ([4485b19](https://github.com/yschimke/compose-ai-tools/commit/4485b19dc7bf8618827e3b9fab0582aa6bfd0d02))
* **serve:** give a phone screen back to the previews ([#3895](https://github.com/yschimke/compose-ai-tools/issues/3895)) ([b94413d](https://github.com/yschimke/compose-ai-tools/commit/b94413df3a2e901b718ced2f1c3e31d2036ecee9))
* **serve:** scope unknown-kind tolerance to the kind field ([#3891](https://github.com/yschimke/compose-ai-tools/issues/3891)) ([bfff6ee](https://github.com/yschimke/compose-ai-tools/commit/bfff6ee9e989e682b21351b8d2261ec09154dd32))
* **serve:** tolerate a usage rule kind this build does not know ([#3890](https://github.com/yschimke/compose-ai-tools/issues/3890)) ([ca45e8d](https://github.com/yschimke/compose-ai-tools/commit/ca45e8df4e3dbc9fac71b9dbb2a40f101a3619a7))

## [1.6.0](https://github.com/yschimke/compose-ai-tools/compare/v1.5.0...v1.6.0) (2026-08-15)


### Features

* **catalog:** add interactive shape morph viewer ([#3871](https://github.com/yschimke/compose-ai-tools/issues/3871)) ([b6c6248](https://github.com/yschimke/compose-ai-tools/commit/b6c6248cb5dbe1c51b9938cd316dc2f569044b19))
* **parity:** publish a tag index with the catalog, and read it back ([#3860](https://github.com/yschimke/compose-ai-tools/issues/3860)) ([c826a24](https://github.com/yschimke/compose-ai-tools/commit/c826a246a1699fdb98bc334857501550a3454678))
* **previews:** support addressable interaction variants ([#3877](https://github.com/yschimke/compose-ai-tools/issues/3877)) ([ef5d1ba](https://github.com/yschimke/compose-ai-tools/commit/ef5d1baa7ca66f776d53e3e82f4aa25bb1c7e71a))
* **serve:** add the DESTRUCTURE rule kind ([#3869](https://github.com/yschimke/compose-ai-tools/issues/3869)) ([5a4ca8d](https://github.com/yschimke/compose-ai-tools/commit/5a4ca8d492bc9f366483d7e6412885d99121b95e))
* **serve:** give the usage cleaner a real parse, behind the isolated loader ([#3861](https://github.com/yschimke/compose-ai-tools/issues/3861)) ([9a874b1](https://github.com/yschimke/compose-ai-tools/commit/9a874b1d91e62ac3543e0dfa06d9689344f71866))


### Bug Fixes

* **cli:** warn about unknown command flags ([#3867](https://github.com/yschimke/compose-ai-tools/issues/3867)) ([86d1120](https://github.com/yschimke/compose-ai-tools/commit/86d1120b0e0b47d451c2ad06be4c68ae40332361))
* **deps:** update gradle minor/patch ([#3859](https://github.com/yschimke/compose-ai-tools/issues/3859)) ([872f190](https://github.com/yschimke/compose-ai-tools/commit/872f1905e8abab236a82b4e1f7bd644826cf479f))
* **format:** reformat for ktfmt 0.27 ([#3881](https://github.com/yschimke/compose-ai-tools/issues/3881)) ([ec4681d](https://github.com/yschimke/compose-ai-tools/commit/ec4681d31646582a97131dc1c8e73d63a27ae37b))
* **parity:** wire the tag index to the host, require its declared space, and cover unbridged images ([#3864](https://github.com/yschimke/compose-ai-tools/issues/3864)) ([e672a0b](https://github.com/yschimke/compose-ai-tools/commit/e672a0b4211c7bfde1e3d199a88f951259c87645))
* **renderer:** open private @Preview methods on every desktop path ([#3880](https://github.com/yschimke/compose-ai-tools/issues/3880)) ([99a04b6](https://github.com/yschimke/compose-ai-tools/commit/99a04b6872e307dbe5e8d156797e26feac665e06))
* **serve:** focus page gaps on components ([#3876](https://github.com/yschimke/compose-ai-tools/issues/3876)) ([eb2f4db](https://github.com/yschimke/compose-ai-tools/commit/eb2f4db31d6304177c03eef11f107f9c49ee8b35))
* **serve:** honour addImports on every usage rule kind, not just DESTRUCTURE ([#3874](https://github.com/yschimke/compose-ai-tools/issues/3874)) ([ba7a4e4](https://github.com/yschimke/compose-ai-tools/commit/ba7a4e493aef61accc3b85a9bc7a7a091b56f54b))
* **serve:** keep receivers the substitution rules do not own ([#3865](https://github.com/yschimke/compose-ai-tools/issues/3865)) ([3121ddb](https://github.com/yschimke/compose-ai-tools/commit/3121ddb8174d091f5a318628bbfe4812c62f306e))
* stabilize shared element debug previews ([#3868](https://github.com/yschimke/compose-ai-tools/issues/3868)) ([d1761ee](https://github.com/yschimke/compose-ai-tools/commit/d1761eeb82732b17f4a0063bda007565c2b80521))
* **vscode:** refresh discovery after preview additions ([#3862](https://github.com/yschimke/compose-ai-tools/issues/3862)) ([9798c9e](https://github.com/yschimke/compose-ai-tools/commit/9798c9ebc13b155be3b6d86f490415a3f4b4c962))

## [1.5.0](https://github.com/yschimke/compose-ai-tools/compare/v1.4.0...v1.5.0) (2026-08-14)


### Features

* **render:** opt renders into the rewritten Compose SlotTable ([#3840](https://github.com/yschimke/compose-ai-tools/issues/3840)) ([02881ce](https://github.com/yschimke/compose-ai-tools/commit/02881cedd3bb001d4fb882077e50092d339a57a9))
* **render:** render this repo's own catalogs with the rewritten SlotTable ([#3845](https://github.com/yschimke/compose-ai-tools/issues/3845)) ([aed817b](https://github.com/yschimke/compose-ai-tools/commit/aed817b2979e69f235d4314e887f2d8ba37f3c49))


### Bug Fixes

* **discovery:** case-fold the render stem tie test ([#3843](https://github.com/yschimke/compose-ai-tools/issues/3843)) ([9c2cac2](https://github.com/yschimke/compose-ai-tools/commit/9c2cac200714d5a7a92510fe52bfa57833ebcb8f))
* **discovery:** guarantee render output paths are unique manifest-wide ([#3847](https://github.com/yschimke/compose-ai-tools/issues/3847)) ([01609fc](https://github.com/yschimke/compose-ai-tools/commit/01609fc49c4cabc9d6ab8c11acd5db083b0bc8b3))
* **harness:** make the serve page captures deterministic ([#3848](https://github.com/yschimke/compose-ai-tools/issues/3848)) ([adc2e9a](https://github.com/yschimke/compose-ai-tools/commit/adc2e9a587443ece74bdc85a823d587f94a9b070))
* **samples:** restore the Remote Compose doc-capture guard, unstale an xr-glimmer comment ([#3846](https://github.com/yschimke/compose-ai-tools/issues/3846)) ([979d472](https://github.com/yschimke/compose-ai-tools/commit/979d4722b3937795c31e3b2bd3d56ec65363af46))
* **serve:** capture session snapshots as part of the registry's transitions ([#3842](https://github.com/yschimke/compose-ai-tools/issues/3842)) ([a8200ed](https://github.com/yschimke/compose-ai-tools/commit/a8200edb46d600613836031800668015af321748))
* **serve:** compare corpus paths with invariant separators ([#3855](https://github.com/yschimke/compose-ai-tools/issues/3855)) ([5e93198](https://github.com/yschimke/compose-ai-tools/commit/5e93198e23a743d8db3519deaa0961d8cc798ca8))
* **serve:** open the query when pinning a token-free link, and make revisions a menu ([#3858](https://github.com/yschimke/compose-ai-tools/issues/3858)) ([b31c1e4](https://github.com/yschimke/compose-ai-tools/commit/b31c1e45ab8dc194d8b1ada79242f84c0d7cb8c6))
* **serve:** rewrite fully qualified knob calls, and make the corpus fail loudly ([#3851](https://github.com/yschimke/compose-ai-tools/issues/3851)) ([822dfcd](https://github.com/yschimke/compose-ai-tools/commit/822dfcd2219769bdaec732d496dd00ff7cd1a09b))
* **serve:** unqualify only declared scaffold packages, not package-shaped receivers ([#3853](https://github.com/yschimke/compose-ai-tools/issues/3853)) ([520b613](https://github.com/yschimke/compose-ai-tools/commit/520b61323bbd08bcecc6b66f5f1b413820ddc972))

## [1.4.0](https://github.com/yschimke/compose-ai-tools/compare/v1.3.0...v1.4.0) (2026-08-14)


### Features

* **cli:** select @PreviewParameter rows on show, list and render ([#3825](https://github.com/yschimke/compose-ai-tools/issues/3825)) ([6131db9](https://github.com/yschimke/compose-ai-tools/commit/6131db95448ac3e78d98edc389389e92a43678e5))
* **serve:** add a Source lane to the viewer ([#3827](https://github.com/yschimke/compose-ai-tools/issues/3827)) ([eeb2953](https://github.com/yschimke/compose-ai-tools/commit/eeb2953ca3c04ca304d3cc415ccd236d09cf1c11))
* **serve:** point to describe, click to go, and only call a real gap a gap ([#3828](https://github.com/yschimke/compose-ai-tools/issues/3828)) ([399a355](https://github.com/yschimke/compose-ai-tools/commit/399a355d2fe2a74e66d74bb64c810aa488f2c5fd))
* **serve:** project a tag index off the scored render, and let the theme layer be seen ([#3830](https://github.com/yschimke/compose-ai-tools/issues/3830)) ([74e7678](https://github.com/yschimke/compose-ai-tools/commit/74e7678bd5c058b96572da4b371c15a3321b7c31))
* **serve:** score a design page's drift per node, and open the full diff from it ([#3818](https://github.com/yschimke/compose-ai-tools/issues/3818)) ([37c7452](https://github.com/yschimke/compose-ai-tools/commit/37c74520cb7133fb22838a2fd184af264a4a3c65))
* **serve:** seed the playground with usage code, not sticker source ([#3816](https://github.com/yschimke/compose-ai-tools/issues/3816)) ([222369f](https://github.com/yschimke/compose-ai-tools/commit/222369fe1488746d79648588c72a4d1cbee34bc8))


### Bug Fixes

* **design-artifacts:** republish a page node's type, so containers are exact ([#3831](https://github.com/yschimke/compose-ai-tools/issues/3831)) ([634bfbd](https://github.com/yschimke/compose-ai-tools/commit/634bfbd9f310bc82bc81ce59d11c9c0471457455))
* **discovery:** make render filenames collision-free and stable ([#3839](https://github.com/yschimke/compose-ai-tools/issues/3839)) ([c97d558](https://github.com/yschimke/compose-ai-tools/commit/c97d558951d4609b193aa0cf188ca058ce920347))
* **pages:** read the container flag the import states, not one inferred from depth ([#3834](https://github.com/yschimke/compose-ai-tools/issues/3834)) ([b3cdcf9](https://github.com/yschimke/compose-ai-tools/commit/b3cdcf985307385122018020f1d9215d2ec39faf))
* **renderer:** scope fan-out companions to their own output's extension ([#3829](https://github.com/yschimke/compose-ai-tools/issues/3829)) ([3ca8fa1](https://github.com/yschimke/compose-ai-tools/commit/3ca8fa12ea5aede89f20dd75e2a5f36ce8e02d04))
* **renderer:** sweep .error.json companions with stale fan-out rows ([#3822](https://github.com/yschimke/compose-ai-tools/issues/3822)) ([3dcc3a8](https://github.com/yschimke/compose-ai-tools/commit/3dcc3a8093470aaa96f40f1b0e5f6407209dd112))
* **serve:** evict catalog snapshots on retirement, and close the suspend race ([#3841](https://github.com/yschimke/compose-ai-tools/issues/3841)) ([174bf91](https://github.com/yschimke/compose-ai-tools/commit/174bf91699925c08448914a97779a62fa560ce08))
* **serve:** keep the design's drawing when a render never arrives ([#3814](https://github.com/yschimke/compose-ai-tools/issues/3814)) ([c96f34e](https://github.com/yschimke/compose-ai-tools/commit/c96f34e4061f6bc4a7f4929eab431f1ee7365eec))
* **serve:** make /usage answer for suspended catalogs and escaped ids ([#3833](https://github.com/yschimke/compose-ai-tools/issues/3833)) ([c828a4a](https://github.com/yschimke/compose-ai-tools/commit/c828a4a425b334f8a2c08de8e9823f3d3705bd9b))
* **serve:** make a resident catalog authoritative for /usage locations ([#3835](https://github.com/yschimke/compose-ai-tools/issues/3835)) ([ac04205](https://github.com/yschimke/compose-ai-tools/commit/ac042051c62d368145c587c96ae65eed4a9efef4))
* **serve:** report drift, not match, in the design page's diff lane ([#3821](https://github.com/yschimke/compose-ai-tools/issues/3821)) ([3fda2fe](https://github.com/yschimke/compose-ai-tools/commit/3fda2fe8df843669e69edc4750506083ce42be49))
* **serve:** stop the usage cleaner emitting seeds that do not compile ([#3820](https://github.com/yschimke/compose-ai-tools/issues/3820)) ([a078979](https://github.com/yschimke/compose-ai-tools/commit/a078979243db7a67758e3187a71a3e29122cc9b6))

## [1.3.0](https://github.com/yschimke/compose-ai-tools/compare/v1.2.0...v1.3.0) (2026-08-14)


### Features

* **cli:** honour --preview as a preview reference on the render commands ([#3780](https://github.com/yschimke/compose-ai-tools/issues/3780)) ([c74baec](https://github.com/yschimke/compose-ai-tools/commit/c74baec5296081d0e19d4fd0800f8d72e3f6a59f))
* **daemon:** add preview/rows to enumerate @PreviewParameter rows ([#3788](https://github.com/yschimke/compose-ai-tools/issues/3788)) ([6da9bc9](https://github.com/yschimke/compose-ai-tools/commit/6da9bc95ff48043a04c9c5ec0d23a750e0be6514))
* **serve:** make a design page lead with the sheet, not its annotation ([#3813](https://github.com/yschimke/compose-ai-tools/issues/3813)) ([71e7d6a](https://github.com/yschimke/compose-ai-tools/commit/71e7d6ab7f87118cc30da89fd84a47eae347bd27))
* **serve:** top-level sites — one catalog on a hostname of its own ([#3783](https://github.com/yschimke/compose-ai-tools/issues/3783)) ([1f9fa26](https://github.com/yschimke/compose-ai-tools/commit/1f9fa2685905cfb23bfccfae430a61534680d4b5))


### Bug Fixes

* **cli:** force every permutation's own render, discard a failed restore ([#3784](https://github.com/yschimke/compose-ai-tools/issues/3784)) ([b096f64](https://github.com/yschimke/compose-ai-tools/commit/b096f6405379a0a55b9c012aaff0cf0444d58946))
* **cli:** keep every render-error sidecar, and only quote fresh ones ([#3789](https://github.com/yschimke/compose-ai-tools/issues/3789)) ([e3d336a](https://github.com/yschimke/compose-ai-tools/commit/e3d336ab1ae03b7bcb58c3956cab0de1741f554c))
* **cli:** let a @PreviewParameter row id select its module and narrow the render ([#3795](https://github.com/yschimke/compose-ai-tools/issues/3795)) ([8184262](https://github.com/yschimke/compose-ai-tools/commit/81842629b1aad1689a64ae4ecf147cced81e39b3))
* **cli:** name the renderer that actually skipped, and keep the blank-capture stem ([#3794](https://github.com/yschimke/compose-ai-tools/issues/3794)) ([cf3e9a0](https://github.com/yschimke/compose-ai-tools/commit/cf3e9a0b233242af8707da49a6425666c3001eb0))
* **cli:** read freshness from the renderer that owns the preview's output ([#3793](https://github.com/yschimke/compose-ai-tools/issues/3793)) ([8e1b2d4](https://github.com/yschimke/compose-ai-tools/commit/8e1b2d47b0728b193340ccf454c7b9a5d49cf521))
* **cli:** refuse a pre-permutation hold that could not be cleared ([#3791](https://github.com/yschimke/compose-ai-tools/issues/3791)) ([ec9fd2a](https://github.com/yschimke/compose-ai-tools/commit/ec9fd2abdf601908671db80792169ca516b8d8e2))
* **cli:** resolve @PreviewParameter rows before serve counts modules ([#3800](https://github.com/yschimke/compose-ai-tools/issues/3800)) ([cdac7f8](https://github.com/yschimke/compose-ai-tools/commit/cdac7f8debf54457572634d91151f3ad1bc9154f))
* **cli:** resolve exact preview ids before treating a selector as a row ([#3798](https://github.com/yschimke/compose-ai-tools/issues/3798)) ([8a747c9](https://github.com/yschimke/compose-ai-tools/commit/8a747c922c02d8cf50eb942e1595ea78e47e384a))
* **cli:** scope the exact-id row gate to --id, not the substring selectors ([#3799](https://github.com/yschimke/compose-ai-tools/issues/3799)) ([e1d2bfb](https://github.com/yschimke/compose-ai-tools/commit/e1d2bfbbaa56a32722c9a7940a381145c6cb99a5))
* **daemon:** make preview/rows work on the production desktop host ([#3792](https://github.com/yschimke/compose-ai-tools/issues/3792)) ([afdf50d](https://github.com/yschimke/compose-ai-tools/commit/afdf50dac6f4cd4bdc362fb989f466a17ebd6a30))
* **serve:** make site isolation an allowlist, not a chase ([#3797](https://github.com/yschimke/compose-ai-tools/issues/3797)) ([6f86fb1](https://github.com/yschimke/compose-ai-tools/commit/6f86fb13861265f5a11a8db2074fae7412bc2772))

## [1.2.0](https://github.com/yschimke/compose-ai-tools/compare/v1.1.0...v1.2.0) (2026-08-13)


### Features

* **cli:** check a11y for each --permutations variant, at its own configuration ([#3776](https://github.com/yschimke/compose-ai-tools/issues/3776)) ([a9d672f](https://github.com/yschimke/compose-ai-tools/commit/a9d672fedc35c1159150872e223a86485eb0d74f))
* **cli:** pin one compose-preview version for every entrypoint ([#3771](https://github.com/yschimke/compose-ai-tools/issues/3771)) ([b4bf8f9](https://github.com/yschimke/compose-ai-tools/commit/b4bf8f9d0bb0925fd94277ed227e05c6f8ade09d))
* **daemon:** address @PreviewParameter rows, not just value 0 ([#3759](https://github.com/yschimke/compose-ai-tools/issues/3759)) ([3716267](https://github.com/yschimke/compose-ai-tools/commit/3716267294163cd3f76dc90e3b02228f8fef5735))
* **pages:** serve whole design pages as addressable SVG, not screen backdrops ([#3750](https://github.com/yschimke/compose-ai-tools/issues/3750)) ([0b0c206](https://github.com/yschimke/compose-ai-tools/commit/0b0c2063ebad22732d37c0c144cea605069b8382))
* **serve:** fold the default render into the component row, and flow a long axis into columns ([#3755](https://github.com/yschimke/compose-ai-tools/issues/3755)) ([ce6f2b8](https://github.com/yschimke/compose-ai-tools/commit/ce6f2b8456cebe9ba33018c37b40d693e137c36f))
* **serve:** fold the viewer's disclosures, and navigate a component by the catalog tree ([#3748](https://github.com/yschimke/compose-ai-tools/issues/3748)) ([1affb54](https://github.com/yschimke/compose-ai-tools/commit/1affb54e60383966b6ef53e6fd0523a6d1d64383))
* **serve:** list a catalog's design pages in its navigation tree ([#3756](https://github.com/yschimke/compose-ai-tools/issues/3756)) ([d6c3198](https://github.com/yschimke/compose-ai-tools/commit/d6c3198df9d9bfb7d02004ebfd16d24a8a687e50))
* **serve:** list every @PreviewParameter row, not just value 0 ([#3772](https://github.com/yschimke/compose-ai-tools/issues/3772)) ([5ab3844](https://github.com/yschimke/compose-ai-tools/commit/5ab38442869cc6f853f3efe85e39abf1876599e9))
* **serve:** pin a catalog page to a delivery-branch revision ([#3758](https://github.com/yschimke/compose-ai-tools/issues/3758)) ([2690d0c](https://github.com/yschimke/compose-ai-tools/commit/2690d0c2d9ff37da2f70878ec3c2183989eb0844))


### Bug Fixes

* **a11y-report:** don't let same-named modules delete each other's renders ([#3770](https://github.com/yschimke/compose-ai-tools/issues/3770)) ([c5a69d9](https://github.com/yschimke/compose-ai-tools/commit/c5a69d93d5ed3343d1fb9d64d1f6e1ee9158ea86))
* **a11y-report:** record every unchecked variant, not just whole functions ([#3767](https://github.com/yschimke/compose-ai-tools/issues/3767)) ([4d55b04](https://github.com/yschimke/compose-ai-tools/commit/4d55b04e31be6ffea985c2d50a45189422e5e3e4))
* **build:** publish data-focus-connector-desktop for the bundle-render e2e ([#3782](https://github.com/yschimke/compose-ai-tools/issues/3782)) ([255aef8](https://github.com/yschimke/compose-ai-tools/commit/255aef8d2ed830dc2a92ddfb8ce286f398526df2))
* **cli:** narrow the a11y daemon fan-out to the requested previews ([#3757](https://github.com/yschimke/compose-ai-tools/issues/3757)) ([d4c90a2](https://github.com/yschimke/compose-ai-tools/commit/d4c90a217b2d1c6632ceace134f083cd32b99f90))
* **cli:** report the render error sidecar instead of guessing NO-SOURCE ([#3779](https://github.com/yschimke/compose-ai-tools/issues/3779)) ([348a24f](https://github.com/yschimke/compose-ai-tools/commit/348a24ffddc5441f038be4e0ffadb07063498001))
* **daemon:** repair the Android harness lane broken by [#3759](https://github.com/yschimke/compose-ai-tools/issues/3759)'s test fixture ([#3765](https://github.com/yschimke/compose-ai-tools/issues/3765)) ([4980ff5](https://github.com/yschimke/compose-ai-tools/commit/4980ff55c73bc3bc60dd63f22d21502f4feab39a))
* **renderer:** give each render JVM its own sandbox-library directory ([#3761](https://github.com/yschimke/compose-ai-tools/issues/3761)) ([ff5b62e](https://github.com/yschimke/compose-ai-tools/commit/ff5b62e79b23281ae2800054055459067972b66e))
* **renderer:** keep the sandbox-library token out of worker JVMs ([#3766](https://github.com/yschimke/compose-ai-tools/issues/3766)) ([b0fb831](https://github.com/yschimke/compose-ai-tools/commit/b0fb831456463cf2e2b5eda1170cea9568dfe792))
* **serve:** a pinned page must offer nothing rendered from today's code ([#3768](https://github.com/yschimke/compose-ai-tools/issues/3768)) ([0e8d07a](https://github.com/yschimke/compose-ai-tools/commit/0e8d07a119d2aaa780c8fad359af452bee006fd1))
* **serve:** bound the pinned lane, and read a load through one commit ([#3773](https://github.com/yschimke/compose-ai-tools/issues/3773)) ([6c67cf0](https://github.com/yschimke/compose-ai-tools/commit/6c67cf0492720cae1ec43ccb2f201ad788b3e816))
* **serve:** make a pinned revision authoritative about what it published ([#3769](https://github.com/yschimke/compose-ai-tools/issues/3769)) ([96ae741](https://github.com/yschimke/compose-ai-tools/commit/96ae741d12ed6b5db69c2afc4c3eea4328e99cc2))
* **serve:** name both axes on a cross-product component's subtree rows ([#3753](https://github.com/yschimke/compose-ai-tools/issues/3753)) ([c5c1495](https://github.com/yschimke/compose-ai-tools/commit/c5c1495d9e6922a8e67a39610eace75f1df7d5f5))
* **serve:** page a preview from the revision that published it ([#3775](https://github.com/yschimke/compose-ai-tools/issues/3775)) ([6093bb9](https://github.com/yschimke/compose-ai-tools/commit/6093bb9f12d3dbaa16414e4278d7fa8a3d853ee6))

## [1.1.0](https://github.com/yschimke/compose-ai-tools/compare/v1.0.0...v1.1.0) (2026-08-12)


### Features

* **apply:** add fork-safe render/publish phase split ([#3736](https://github.com/yschimke/compose-ai-tools/issues/3736)) ([b574714](https://github.com/yschimke/compose-ai-tools/commit/b574714ea7816117639c3a1311dbf15949ee1c54))
* **design-references:** publish variant bindings as secondary references ([#3732](https://github.com/yschimke/compose-ai-tools/issues/3732)) ([7b573ec](https://github.com/yschimke/compose-ai-tools/commit/7b573ecc31bcbe38bb47a982cb38c9e3f07ea8d3))
* **serve:** grow the catalog tree down to components and their variants ([#3739](https://github.com/yschimke/compose-ai-tools/issues/3739)) ([95b53fd](https://github.com/yschimke/compose-ai-tools/commit/95b53fd706c8e883fdeba82132c9af0c2e932058))
* **serve:** navigate a sectioned catalog as a tree, not a row of tabs ([#3735](https://github.com/yschimke/compose-ai-tools/issues/3735)) ([ee38eee](https://github.com/yschimke/compose-ai-tools/commit/ee38eeef2789cca2cc8d932caaf486649996c418))
* **serve:** show whole design screens with the code laid over them ([#3714](https://github.com/yschimke/compose-ai-tools/issues/3714)) ([e9d71b1](https://github.com/yschimke/compose-ai-tools/commit/e9d71b141d9369234c99d8b012cc73c4d9ad8801))


### Bug Fixes

* **apply:** close publish-side escalation paths in the fork handoff ([#3737](https://github.com/yschimke/compose-ai-tools/issues/3737)) ([37ca1a3](https://github.com/yschimke/compose-ai-tools/commit/37ca1a308afe58210774991dd3c2123909857d46))
* **apply:** tar the fork handoff so nested module paths can upload ([#3745](https://github.com/yschimke/compose-ai-tools/issues/3745)) ([c4d3ebf](https://github.com/yschimke/compose-ai-tools/commit/c4d3ebf3ee5ec53eefc8f500cf29cef418c2f97a))
* **apply:** validate the handoff tarball and keep _pipelines outside it ([#3746](https://github.com/yschimke/compose-ai-tools/issues/3746)) ([10f91c7](https://github.com/yschimke/compose-ai-tools/commit/10f91c7d728cee4c9d3621f197997735becc1af9))
* **cli:** narrow the Gradle render to the previews --id/--filter names ([#3734](https://github.com/yschimke/compose-ai-tools/issues/3734)) ([462a00c](https://github.com/yschimke/compose-ai-tools/commit/462a00ce4c0646829865450f81d0a80a9d37b97d))
* **cli:** raise the default build timeout above what a cold render costs ([#3722](https://github.com/yschimke/compose-ai-tools/issues/3722)) ([9ffc3b9](https://github.com/yschimke/compose-ai-tools/commit/9ffc3b9cf573da2223f3a95ff5437548b88811c7))
* **daemon:** compose recording frames under the preview's locale ([#3729](https://github.com/yschimke/compose-ai-tools/issues/3729)) ([f29f402](https://github.com/yschimke/compose-ai-tools/commit/f29f40229943cf2782eda034c702be463dd6f90e))
* **daemon:** serialise the process-global localeTag override ([#3724](https://github.com/yschimke/compose-ai-tools/issues/3724)) ([e97bf82](https://github.com/yschimke/compose-ai-tools/commit/e97bf825eae9ba540deaf676d428a560b675315e))
* **driver:** keep the no-arg discovery methods on the published artifact ([#3726](https://github.com/yschimke/compose-ai-tools/issues/3726)) ([d4d0303](https://github.com/yschimke/compose-ai-tools/commit/d4d0303f1d55751a7b25296e5a9b68b5fe8e54da))
* **serve:** draw a real link-unfurl card instead of pointing at a phone render ([#3731](https://github.com/yschimke/compose-ai-tools/issues/3731)) ([b97b351](https://github.com/yschimke/compose-ai-tools/commit/b97b351db5faa3134af232df6bfbed2dac7b73e8))
* **serve:** filter, focus and theme-lane corrections for the catalog tree ([#3740](https://github.com/yschimke/compose-ai-tools/issues/3740)) ([8d52a58](https://github.com/yschimke/compose-ai-tools/commit/8d52a5895ed6225cd845120734b823709a032ef9))

## [1.0.0](https://github.com/yschimke/compose-ai-tools/compare/v0.19.61...v1.0.0) (2026-08-12)


### Features

* **overrides:** let a knob declare its value set, and render it as a picker ([#3712](https://github.com/yschimke/compose-ai-tools/issues/3712)) ([7c8c32e](https://github.com/yschimke/compose-ai-tools/commit/7c8c32e8d6e7ff06c3401e686b696474c59fb4b7))
* **renderer-desktop:** drive @FocusedPreview with real focus and press on CMP desktop ([#3699](https://github.com/yschimke/compose-ai-tools/issues/3699)) ([ddd16e8](https://github.com/yschimke/compose-ai-tools/commit/ddd16e82ea9fde42ebb00e7df5c9da77606d5de9))
* **serve:** exploded 3D view of a screen, one sheet per composable ([#3700](https://github.com/yschimke/compose-ai-tools/issues/3700)) ([d9abe80](https://github.com/yschimke/compose-ai-tools/commit/d9abe8050996160cd9ef5f87d6628681b73abc6b))
* **serve:** split the catalog's comparison actions and name the design tool ([#3709](https://github.com/yschimke/compose-ai-tools/issues/3709)) ([122d747](https://github.com/yschimke/compose-ai-tools/commit/122d7472ac0b8a4656bea13e4d4bd236794acdc5))


### Bug Fixes

* **cloud-jdk:** honour a version pin over install-dir reuse, and absolutise discovered homes ([#3713](https://github.com/yschimke/compose-ai-tools/issues/3713)) ([0848728](https://github.com/yschimke/compose-ai-tools/commit/08487282475fe818d408ed3e707e70094250a31e))
* **cloud-jdk:** reuse a JDK the box already has instead of downloading one ([#3707](https://github.com/yschimke/compose-ai-tools/issues/3707)) ([9bca82b](https://github.com/yschimke/compose-ai-tools/commit/9bca82b4608cca903a384d27d2e8b390262b536a))
* **daemon:** run the press-settling frame under the preview's locale ([#3715](https://github.com/yschimke/compose-ai-tools/issues/3715)) ([2346b2f](https://github.com/yschimke/compose-ai-tools/commit/2346b2ff91cf543ad2d3ff7fda665414207318fa))
* **daemon:** settle a pointer press before the next event can arrive ([#3711](https://github.com/yschimke/compose-ai-tools/issues/3711)) ([1922af5](https://github.com/yschimke/compose-ai-tools/commit/1922af5548851297a813ddbe911ae8514217bf11))
* **figma-svg:** stop rastering content the vector model can represent ([#3693](https://github.com/yschimke/compose-ai-tools/issues/3693)) ([dafedc9](https://github.com/yschimke/compose-ai-tools/commit/dafedc9d7819b2ff12e5af74cfda73fdd5d14f64))
* green up the four CI failures on main ([#3688](https://github.com/yschimke/compose-ai-tools/issues/3688)) ([5278cd0](https://github.com/yschimke/compose-ai-tools/commit/5278cd0a38bb33d31f088a1e22c28defaf578cee))
* **render:** keep package-store libs out of a system-glibc render JVM ([#3701](https://github.com/yschimke/compose-ai-tools/issues/3701)) ([bd4a6a2](https://github.com/yschimke/compose-ai-tools/commit/bd4a6a25cb63ef5c3500aa1d191f7c55bbea1b86))
* **render:** rank doctor verdicts, and stop non-skiko failures flooding the log ([#3708](https://github.com/yschimke/compose-ai-tools/issues/3708)) ([f1d62bf](https://github.com/yschimke/compose-ai-tools/commit/f1d62bfda6a0c18f32143874e50929b9fa233be6))
* **render:** tighten the native-env pruning and its diagnosis ([#3703](https://github.com/yschimke/compose-ai-tools/issues/3703)) ([7906cef](https://github.com/yschimke/compose-ai-tools/commit/7906ceffa18f99e8f043ee8483d242d4144255de))
* **samples:** make previews capture the state they claim ([#3691](https://github.com/yschimke/compose-ai-tools/issues/3691)) ([78138e1](https://github.com/yschimke/compose-ai-tools/commit/78138e13b7ff3083a71cf7af2871ab312fbd3073))
* **serve:** correct the exploded 3D view's masks, shadows and bounds ([#3705](https://github.com/yschimke/compose-ai-tools/issues/3705)) ([6d2325d](https://github.com/yschimke/compose-ai-tools/commit/6d2325d492a357a5af14f01802b3eedb7c37bfc5))
* **serve:** keep untagged renders in the primary theme lane so the state switcher finds them ([#3704](https://github.com/yschimke/compose-ai-tools/issues/3704)) ([e26aa19](https://github.com/yschimke/compose-ai-tools/commit/e26aa19409a3ed6b6d5ece4afb20a72eb50004b2))
* **serve:** make link unfurling work in Slack and Google Chat ([#3717](https://github.com/yschimke/compose-ai-tools/issues/3717)) ([2467849](https://github.com/yschimke/compose-ai-tools/commit/2467849e838a5b841a5d3c33ac51498db9ba37f1))
* **serve:** stop a knob edit spending the previous history entry to auto-enable Wasm ([#3719](https://github.com/yschimke/compose-ai-tools/issues/3719)) ([6685153](https://github.com/yschimke/compose-ai-tools/commit/6685153b17e49cf1f20414613c989bcfc6c9f711))
* **serve:** stop HEAD probes and crawlers reaching the remaining live renders ([#3720](https://github.com/yschimke/compose-ai-tools/issues/3720)) ([355e49b](https://github.com/yschimke/compose-ai-tools/commit/355e49bfc3d505bfbcb050e3666edfc475ee389e))

## [0.19.61](https://github.com/yschimke/compose-ai-tools/compare/v0.19.60...v0.19.61) (2026-08-11)


### Bug Fixes

* address missed Codex review findings ([#3683](https://github.com/yschimke/compose-ai-tools/issues/3683)) ([e3f46de](https://github.com/yschimke/compose-ai-tools/commit/e3f46de11888a297b7ae29617e3ef9d78077b45e))
* **figma-svg:** exclude accessibility-only text ([#3685](https://github.com/yschimke/compose-ai-tools/issues/3685)) ([0d110d8](https://github.com/yschimke/compose-ai-tools/commit/0d110d87825de9d3c3d0a713008a46ce1513e31c))
* **figma-svg:** preserve delegated draws and axis mirrors ([#3686](https://github.com/yschimke/compose-ai-tools/issues/3686)) ([61c92a0](https://github.com/yschimke/compose-ai-tools/commit/61c92a0752bdb724c15e5caec9a14c51b78e2710))
* stage font installer for preview host image ([#3682](https://github.com/yschimke/compose-ai-tools/issues/3682)) ([4e5c9ae](https://github.com/yschimke/compose-ai-tools/commit/4e5c9ae7bf5430bebbb40231c7dc58fa92aa80db))

## [0.19.60](https://github.com/yschimke/compose-ai-tools/compare/v0.19.59...v0.19.60) (2026-08-11)


### Features

* annotate per-reference Figma backgrounds ([#3678](https://github.com/yschimke/compose-ai-tools/issues/3678)) ([8551345](https://github.com/yschimke/compose-ai-tools/commit/85513450ddb60f5f110f6a5ced3c4fe3699d73a0))
* **rc-js-player:** implement FontData opcode 189 ([#3656](https://github.com/yschimke/compose-ai-tools/issues/3656)) ([d4ce778](https://github.com/yschimke/compose-ai-tools/commit/d4ce7781d48c8dc38278391c74451fd71b1a9b21))
* **rc-player:** implement extended CoreText properties ([#3667](https://github.com/yschimke/compose-ai-tools/issues/3667)) ([7bd928d](https://github.com/yschimke/compose-ai-tools/commit/7bd928dca06ae51cb82a7714ff70b759b48d3872))


### Bug Fixes

* **daemon:** preserve natural pixel input coordinates ([#3661](https://github.com/yschimke/compose-ai-tools/issues/3661)) ([4cc584e](https://github.com/yschimke/compose-ai-tools/commit/4cc584e4ec9d5572f8218ab001754522698195db))
* match Figma and render layout annotations ([#3668](https://github.com/yschimke/compose-ai-tools/issues/3668)) ([7cd2f7d](https://github.com/yschimke/compose-ai-tools/commit/7cd2f7dab03704df2e34c0254c78821e76cebb9c))
* **previews:** detect empty compiled outputs ([#3654](https://github.com/yschimke/compose-ai-tools/issues/3654)) ([2f23b53](https://github.com/yschimke/compose-ai-tools/commit/2f23b536dc27f357043c4619539da60523a32c6a))
* **rc-player:** match Java text layout modes ([#3663](https://github.com/yschimke/compose-ai-tools/issues/3663)) ([6afd025](https://github.com/yschimke/compose-ai-tools/commit/6afd0252cba621b7886b4ecbaacd882b514de116))
* **render:** provide hosted font fallbacks ([#3679](https://github.com/yschimke/compose-ai-tools/issues/3679)) ([71be0bc](https://github.com/yschimke/compose-ai-tools/commit/71be0bcbe15df57a271cc4fe60daf174d3e02407))
* tolerate rasterizer edge differences in parity scores ([#3657](https://github.com/yschimke/compose-ai-tools/issues/3657)) ([02cb33a](https://github.com/yschimke/compose-ai-tools/commit/02cb33acf7b73ae668e83928b0940f00e0ece2fa))

## [0.19.59](https://github.com/yschimke/compose-ai-tools/compare/v0.19.58...v0.19.59) (2026-08-11)


### Bug Fixes

* address recent Codex review findings ([#3649](https://github.com/yschimke/compose-ai-tools/issues/3649)) ([53afe23](https://github.com/yschimke/compose-ai-tools/commit/53afe233fb310a903dedc9d841ab6532a8382835))
* **figma-svg:** preserve variant state exports ([#3652](https://github.com/yschimke/compose-ai-tools/issues/3652)) ([8b0691e](https://github.com/yschimke/compose-ai-tools/commit/8b0691e18bb63d1e3824c168f6540268b6d73d33))
* **rc-player:** align text measurement across players ([#3653](https://github.com/yschimke/compose-ai-tools/issues/3653)) ([5fc1ab5](https://github.com/yschimke/compose-ai-tools/commit/5fc1ab501b32f9dcf599a48511125efab3dff555))
* **rc-player:** keep canvas decorations outside padding ([#3648](https://github.com/yschimke/compose-ai-tools/issues/3648)) ([b225a45](https://github.com/yschimke/compose-ai-tools/commit/b225a45bfcd37299b5026e33f55ad696444a4b1c))
* **rc-player:** preserve lexical theme and canvas scope ([#3655](https://github.com/yschimke/compose-ai-tools/issues/3655)) ([b9070cb](https://github.com/yschimke/compose-ai-tools/commit/b9070cb098e5daf1d0eaaf3caa3ff91b3460822e))

## [0.19.58](https://github.com/yschimke/compose-ai-tools/compare/v0.19.57...v0.19.58) (2026-08-11)


### Features

* **bundle:** add opt-in shared classpath hydration ([#3641](https://github.com/yschimke/compose-ai-tools/issues/3641)) ([a021662](https://github.com/yschimke/compose-ai-tools/commit/a0216620b3abd5f0405f9fafb75efef791706b42))
* **serve:** surface actionable design parity issues ([#3642](https://github.com/yschimke/compose-ai-tools/issues/3642)) ([16a739c](https://github.com/yschimke/compose-ai-tools/commit/16a739ca7e094091b0c40d08bcac8e591514c7be))


### Bug Fixes

* **bundle:** publish complete executable downloads ([#3645](https://github.com/yschimke/compose-ai-tools/issues/3645)) ([1bb5eea](https://github.com/yschimke/compose-ai-tools/commit/1bb5eeac9d6bb1e720a72a42006042934b4037fe))
* **ci:** compare CMP Wasm against PR base ([#3638](https://github.com/yschimke/compose-ai-tools/issues/3638)) ([78ac354](https://github.com/yschimke/compose-ai-tools/commit/78ac354a9bf49caff27d4cee56856427e3ef3cbb))
* **design-artifacts:** preserve Figma reference scale ([#3644](https://github.com/yschimke/compose-ai-tools/issues/3644)) ([3cf99ca](https://github.com/yschimke/compose-ai-tools/commit/3cf99caac2f2af20660b0ad4e9ff5337d78a65f8))
* **layout:** canonicalize semantics property values ([#3640](https://github.com/yschimke/compose-ai-tools/issues/3640)) ([1156263](https://github.com/yschimke/compose-ai-tools/commit/11562631867f1bbea3741a132d93571e146b637b))

## [0.19.57](https://github.com/yschimke/compose-ai-tools/compare/v0.19.56...v0.19.57) (2026-08-11)


### Features

* **rc-player:** support state layout operation ([#3634](https://github.com/yschimke/compose-ai-tools/issues/3634)) ([876d0a7](https://github.com/yschimke/compose-ai-tools/commit/876d0a751ef4de7dcfb12a8253a0f35d13ce17e5))


### Bug Fixes

* batch Figma design reference requests ([#3626](https://github.com/yschimke/compose-ai-tools/issues/3626)) ([92c0075](https://github.com/yschimke/compose-ai-tools/commit/92c00758c7c5b705756d085731f1dd476325704b))
* **layout:** stabilize semantics property order ([#3637](https://github.com/yschimke/compose-ai-tools/issues/3637)) ([702bb2f](https://github.com/yschimke/compose-ai-tools/commit/702bb2f9b6ce18c4851418e8e8a44a72192c0999))
* **rc-player:** align AndroidX operation semantics ([#3636](https://github.com/yschimke/compose-ai-tools/issues/3636)) ([90a8dcc](https://github.com/yschimke/compose-ai-tools/commit/90a8dcc34f8f821e1ece59fd65d0ed6f2ab885f4))
* **rc-player:** mirror integer values into float state ([#3632](https://github.com/yschimke/compose-ai-tools/issues/3632)) ([0f396c9](https://github.com/yschimke/compose-ai-tools/commit/0f396c985b6c73ea5f9bc0728f0cdd9ed61d0465))
* **rc-player:** replay layout color attributes ([#3629](https://github.com/yschimke/compose-ai-tools/issues/3629)) ([543f806](https://github.com/yschimke/compose-ai-tools/commit/543f806676830ae3b287be7fd161fb94666b1f50))
* **rc-player:** replay root layout state operations ([#3631](https://github.com/yschimke/compose-ai-tools/issues/3631)) ([3de4f9e](https://github.com/yschimke/compose-ai-tools/commit/3de4f9e9680e13b6717fd86b42097c698d8bde2d))

## [0.19.56](https://github.com/yschimke/compose-ai-tools/compare/v0.19.55...v0.19.56) (2026-08-10)


### Features

* **serve:** surface failed renders in the UI ([#3613](https://github.com/yschimke/compose-ai-tools/issues/3613)) ([a95b683](https://github.com/yschimke/compose-ai-tools/commit/a95b683f37977bcfc7eea058f84fcf186ab18aa8))


### Bug Fixes

* **bundle:** canonicalize inspection sidecars ([#3622](https://github.com/yschimke/compose-ai-tools/issues/3622)) ([ad11140](https://github.com/yschimke/compose-ai-tools/commit/ad11140c101f6acea8f707ba53102845ab27e051))
* **rc-compare:** honor document generation density ([#3621](https://github.com/yschimke/compose-ai-tools/issues/3621)) ([16607c8](https://github.com/yschimke/compose-ai-tools/commit/16607c8a65d57b1621b5b1d946be708d97feaf17))
* **rc-compare:** render external image placeholders ([#3625](https://github.com/yschimke/compose-ai-tools/issues/3625)) ([a95abd9](https://github.com/yschimke/compose-ai-tools/commit/a95abd993a77f86c9e06e04a272c5ace4c8559c2))
* **rc-player:** evaluate layout modifier colors ([#3623](https://github.com/yschimke/compose-ai-tools/issues/3623)) ([6dc74a2](https://github.com/yschimke/compose-ai-tools/commit/6dc74a2cca030106a956fddcf7677276e088a896))

## [0.19.55](https://github.com/yschimke/compose-ai-tools/compare/v0.19.54...v0.19.55) (2026-08-10)


### Bug Fixes

* avoid truncating large catalogs ([#3612](https://github.com/yschimke/compose-ai-tools/issues/3612)) ([8e046e8](https://github.com/yschimke/compose-ai-tools/commit/8e046e8f57b424d983a14cba577cb1cf75f58c9c))
* **design-artifacts:** --allow-incomplete rejects empty catalogs ([#3614](https://github.com/yschimke/compose-ai-tools/issues/3614)) ([8f93e99](https://github.com/yschimke/compose-ai-tools/commit/8f93e9925b281344b374d4918c9e70e9db9fdc67))
* lower Compose preview floor to 1.10 ([#3620](https://github.com/yschimke/compose-ai-tools/issues/3620)) ([3193147](https://github.com/yschimke/compose-ai-tools/commit/31931479e0af53a9bb9aeb2b0d8ab9be0cc2127f))
* **serve:** resolve Robolectric dependencies in sandbox ([#3617](https://github.com/yschimke/compose-ai-tools/issues/3617)) ([97a82f7](https://github.com/yschimke/compose-ai-tools/commit/97a82f7f9912e8df894d193809097450b3a511e3))
* validate published design references ([#3619](https://github.com/yschimke/compose-ai-tools/issues/3619)) ([6973203](https://github.com/yschimke/compose-ai-tools/commit/6973203494ba359f085774f07d672a81d672a1a8))

## [0.19.54](https://github.com/yschimke/compose-ai-tools/compare/v0.19.53...v0.19.54) (2026-08-09)


### Features

* **remote-m3:** port the Wear M3 palettes and apply themes at replay, not capture ([#3604](https://github.com/yschimke/compose-ai-tools/issues/3604)) ([7a27f29](https://github.com/yschimke/compose-ai-tools/commit/7a27f29ffbde414c4bbd8428d8af11dc43c4d199))
* **serve:** apply a declared theme to a replayed preview as named colour seeds ([#3608](https://github.com/yschimke/compose-ai-tools/issues/3608)) ([6c9b3f4](https://github.com/yschimke/compose-ai-tools/commit/6c9b3f4ebc076587f8536a9f8770f56fad6f66fd))


### Bug Fixes

* **plugin:** calibrate the compose link floor, and fail loudly where it cannot be applied ([#3607](https://github.com/yschimke/compose-ai-tools/issues/3607)) ([650235f](https://github.com/yschimke/compose-ai-tools/commit/650235fc336ca475071b35fbfceb5bb05531f2fe))
* **serve:** apply a replayed theme on every lane, and read a six-digit seed as opaque ([#3609](https://github.com/yschimke/compose-ai-tools/issues/3609)) ([9c08e8c](https://github.com/yschimke/compose-ai-tools/commit/9c08e8cef7c28493ac5efd968a7df896a33db874))

## [0.19.53](https://github.com/yschimke/compose-ai-tools/compare/v0.19.52...v0.19.53) (2026-08-09)


### Features

* **deploy:** install bubblewrap so the playground jail can actually contain ([#3587](https://github.com/yschimke/compose-ai-tools/issues/3587)) ([dba1384](https://github.com/yschimke/compose-ai-tools/commit/dba1384641e8b836119ef373d76977cfbf192110))


### Bug Fixes

* bump the build-cache salt to orphan an empty compileKotlin entry ([#3601](https://github.com/yschimke/compose-ai-tools/issues/3601)) ([804ae9f](https://github.com/yschimke/compose-ai-tools/commit/804ae9fa9d6fe2416507d3740f5ad3ab751cf68e))
* **figma-svg:** evaluate a layer block against the node's real density ([#3594](https://github.com/yschimke/compose-ai-tools/issues/3594)) ([09c74cb](https://github.com/yschimke/compose-ai-tools/commit/09c74cb12e39c82b43b17717b4df9570103a0e0d))
* **figma-svg:** fade a Wear scaling card's fill with its container layer ([#3589](https://github.com/yschimke/compose-ai-tools/issues/3589)) ([d9df173](https://github.com/yschimke/compose-ai-tools/commit/d9df173e48a7b29f90f5f8e3e38a2bb774fa8df6))
* **plugin:** floor the render classpath's compose-ui line so the renderer links ([#3596](https://github.com/yschimke/compose-ai-tools/issues/3596)) ([559e716](https://github.com/yschimke/compose-ai-tools/commit/559e716633dc0a28a044f7bc6624afb60e4bd485))
* **rc-compare:** stop scoring a blank CMP/Wasm capture as a parity number ([#3597](https://github.com/yschimke/compose-ai-tools/issues/3597)) ([4354d40](https://github.com/yschimke/compose-ai-tools/commit/4354d4017b5089e95820cfb170b62261da7c9cc6))
* **rc-embedded:** let both embedded lanes parse URL-encoded bitmaps ([#3598](https://github.com/yschimke/compose-ai-tools/issues/3598)) ([e0a9755](https://github.com/yschimke/compose-ai-tools/commit/e0a97558bc3ce923fa235849b180d364ef76651b))
* **serve:** stop offering declared themes a replayed preview can never apply ([#3583](https://github.com/yschimke/compose-ai-tools/issues/3583)) ([b0fe7ca](https://github.com/yschimke/compose-ai-tools/commit/b0fe7cac3fa598e71b6b2518f65c393ea9dd78ee))

## [0.19.52](https://github.com/yschimke/compose-ai-tools/compare/v0.19.51...v0.19.52) (2026-08-09)


### Features

* **deploy:** forward the playground selector env into the preview container ([#3577](https://github.com/yschimke/compose-ai-tools/issues/3577)) ([b7d068a](https://github.com/yschimke/compose-ai-tools/commit/b7d068ab5ac126dcabeffbe26450e8e714196f20))
* **design-artifacts:** carry referenceSet onto the published catalog ([#3560](https://github.com/yschimke/compose-ai-tools/issues/3560)) ([f2793ec](https://github.com/yschimke/compose-ai-tools/commit/f2793ec32acca4fda828fa8b013da5428ac1c119))
* **design-artifacts:** publish each declared theme's token set ([#3573](https://github.com/yschimke/compose-ai-tools/issues/3573)) ([4d51aa1](https://github.com/yschimke/compose-ai-tools/commit/4d51aa1affd846d16862b25b469c74139e5460f1))
* **design-artifacts:** resolve each published theme's dark flag ([#3575](https://github.com/yschimke/compose-ai-tools/issues/3575)) ([a3a1b16](https://github.com/yschimke/compose-ai-tools/commit/a3a1b1667b8207a561d363cb17c272f22fa90519))
* **preview:** anchored `=&lt;id&gt;` id patterns, so a generated exclusion list is safe ([#3561](https://github.com/yschimke/compose-ai-tools/issues/3561)) ([40680b2](https://github.com/yschimke/compose-ai-tools/commit/40680b2fe74314c73ff4864ca9a84d03afb4d3f7))
* **serve:** paint each Light/Dark chip in the theme it selects ([#3562](https://github.com/yschimke/compose-ai-tools/issues/3562)) ([fcca191](https://github.com/yschimke/compose-ai-tools/commit/fcca1912163bdabc8870bf2103f007b6b85da259))


### Bug Fixes

* **design-artifacts:** anchor every generated render exclusion ([#3570](https://github.com/yschimke/compose-ai-tools/issues/3570)) ([923a622](https://github.com/yschimke/compose-ai-tools/commit/923a622f398e8acbc0745de0c0f0d5812668f09f))
* **discovery:** rotate a spec: device for orientation=portrait, and read parent= ([#3582](https://github.com/yschimke/compose-ai-tools/issues/3582)) ([0ad9720](https://github.com/yschimke/compose-ai-tools/commit/0ad97206a1d1cf11b08155572298df0bcd15a487))
* **figma-svg:** grow a brush-filled container to its measured box ([#3571](https://github.com/yschimke/compose-ai-tools/issues/3571)) ([ccc7afd](https://github.com/yschimke/compose-ai-tools/commit/ccc7afd4e95ab07c8c672ea787defb1087c4089e))
* **figma-svg:** measure the paint box, and fix the two defects that sweep found ([#3580](https://github.com/yschimke/compose-ai-tools/issues/3580)) ([5a31445](https://github.com/yschimke/compose-ai-tools/commit/5a3144528c555f11bb936ba36d4652d19a6c2caa))
* **figma-svg:** tighten the paint-box capture and the raster-text rule ([#3581](https://github.com/yschimke/compose-ai-tools/issues/3581)) ([7677a3f](https://github.com/yschimke/compose-ai-tools/commit/7677a3f24b3b9b5f972523dca4f9652e33959d61))
* **serve:** keep the theme chip's ring and disabled state inside its own box ([#3567](https://github.com/yschimke/compose-ai-tools/issues/3567)) ([5b1df46](https://github.com/yschimke/compose-ai-tools/commit/5b1df46f432a5852ef22f45e8ab86be0fee421de))
* **vscode:** give the e2e Gradle render a cold-CI budget, and fail fast when it dies ([#3568](https://github.com/yschimke/compose-ai-tools/issues/3568)) ([0c0d208](https://github.com/yschimke/compose-ai-tools/commit/0c0d2088e81a31e56c200f738ce2542a6aa3c60b))

## [0.19.51](https://github.com/yschimke/compose-ai-tools/compare/v0.19.50...v0.19.51) (2026-08-08)


### Features

* **catalog:** record WHY a component has no design reference ([#3532](https://github.com/yschimke/compose-ai-tools/issues/3532)) ([5733ad8](https://github.com/yschimke/compose-ai-tools/commit/5733ad89fdac9710e3717a96a7976058fa0664f4))
* **renderer:** redesign the theme specimen sheets, and stop them truncating ([#3541](https://github.com/yschimke/compose-ai-tools/issues/3541)) ([023818b](https://github.com/yschimke/compose-ai-tools/commit/023818b6ae61e87605a1dfccbdf01e753010f52f))


### Bug Fixes

* **daemon:** let recordings type printable text and mouse-select ([#3551](https://github.com/yschimke/compose-ai-tools/issues/3551)) ([d30c0fe](https://github.com/yschimke/compose-ai-tools/commit/d30c0fe4577a704302ded6c7bb97ee5fd9849cad))
* **daemon:** rotate the device frame when orientation asks for it ([#3552](https://github.com/yschimke/compose-ai-tools/issues/3552)) ([9a200c2](https://github.com/yschimke/compose-ai-tools/commit/9a200c23623a0add395a0401e620ea0ad764da50))
* **design-artifacts:** carry noReference and referenceSet to the published catalog ([#3543](https://github.com/yschimke/compose-ai-tools/issues/3543)) ([135326d](https://github.com/yschimke/compose-ai-tools/commit/135326d9f8edbd5c5f58dd971f2d46c31d137854))
* **plugin:** resolve the render worker launcher from the running JVM ([#3554](https://github.com/yschimke/compose-ai-tools/issues/3554)) ([68b336f](https://github.com/yschimke/compose-ai-tools/commit/68b336f70b310b310221e36e66a9426ce828f57c))
* **renderer-desktop:** give driven END captures the framing an ordinary render gets ([#3553](https://github.com/yschimke/compose-ai-tools/issues/3553)) ([ea7ccb7](https://github.com/yschimke/compose-ai-tools/commit/ea7ccb78cb5dbdaf1ef5b8ffb8867e5509336601))
* **renderer-desktop:** reset override seeds per render, and gate JVM reuse across captures ([#3540](https://github.com/yschimke/compose-ai-tools/issues/3540)) ([d8afc09](https://github.com/yschimke/compose-ai-tools/commit/d8afc09a194120d62205dcb12ff16492fc4a8069))
* **renderer:** pin the preview wall clock so clock-bearing renders stop diffing ([#3547](https://github.com/yschimke/compose-ai-tools/issues/3547)) ([ceef2f5](https://github.com/yschimke/compose-ai-tools/commit/ceef2f5a667b79b1035036a7e8a375180c7ace05))
* **serve:** drop the viewer's duplicate Background override ([#3549](https://github.com/yschimke/compose-ai-tools/issues/3549)) ([579de78](https://github.com/yschimke/compose-ai-tools/commit/579de78382ae77faf814fbc073183bc3faffb17a))
* **serve:** hide the playground handoff for catalogs this host cannot compile ([#3550](https://github.com/yschimke/compose-ai-tools/issues/3550)) ([b615d48](https://github.com/yschimke/compose-ai-tools/commit/b615d485e8e015934f179f3f6eb2cbb2ba7a165e))
* **serve:** keep the viewer chrome and fit cap honest after the page moves ([#3544](https://github.com/yschimke/compose-ai-tools/issues/3544)) ([75c5272](https://github.com/yschimke/compose-ai-tools/commit/75c5272d70b24d099e9c1dcd7b072393a6d24ecd))
* **serve:** repaint the viewer chrome on Back and Forward ([#3539](https://github.com/yschimke/compose-ai-tools/issues/3539)) ([3c56b04](https://github.com/yschimke/compose-ai-tools/commit/3c56b042e6d744b31ea931405231e96487a55ce1))


### Performance Improvements

* **plugin:** draw captures on a warm renderer instead of forking a JVM each time ([#3548](https://github.com/yschimke/compose-ai-tools/issues/3548)) ([59bed74](https://github.com/yschimke/compose-ai-tools/commit/59bed74e9898c8947f0a7413fff105a10c753ec2))

## [0.19.50](https://github.com/yschimke/compose-ai-tools/compare/v0.19.49...v0.19.50) (2026-08-08)


### Features

* **daemon-desktop:** honour @ScrollingPreview(END) in the served render ([#3517](https://github.com/yschimke/compose-ai-tools/issues/3517)) ([5e80c40](https://github.com/yschimke/compose-ai-tools/commit/5e80c406678d8628a0d5bdca521a89ff6b709216))
* **design-catalog-remote-m3:** declare Roboto Flex and Google Sans Flex typeface themes ([#3516](https://github.com/yschimke/compose-ai-tools/issues/3516)) ([9817e3d](https://github.com/yschimke/compose-ai-tools/commit/9817e3dbad05fc00926ab6777fb6a945356e966d))
* **preview-annotations:** let @OverrideVariant sit on an annotation class ([#3523](https://github.com/yschimke/compose-ai-tools/issues/3523)) ([a41e0af](https://github.com/yschimke/compose-ai-tools/commit/a41e0af3a0706018b6a53e302d9b1eaac03e267b))
* **serve:** offer diff, triptych and slider on the viewer's spec lane ([#3521](https://github.com/yschimke/compose-ai-tools/issues/3521)) ([8400c6d](https://github.com/yschimke/compose-ai-tools/commit/8400c6d08849d1e7d45b113a8344752bd476b294))


### Bug Fixes

* **daemon:** deliver printable key events where nothing can type them, and type astral characters ([#3519](https://github.com/yschimke/compose-ai-tools/issues/3519)) ([5a2b72c](https://github.com/yschimke/compose-ai-tools/commit/5a2b72cc95d90e93ef2d14947b842835bc89c311))
* **design-catalog-wear-m3:** make theme catalogs change the typeface, not just the palette ([#3515](https://github.com/yschimke/compose-ai-tools/issues/3515)) ([e52f4f3](https://github.com/yschimke/compose-ai-tools/commit/e52f4f3c8ee365ef960cc1ede549e680223a6f7e))
* **renderer-desktop:** drive long scrolls on geometry, not the axis range ([#3520](https://github.com/yschimke/compose-ai-tools/issues/3520)) ([8da4dd1](https://github.com/yschimke/compose-ai-tools/commit/8da4dd14bd07fccd7c863c4b06c2ed07aed298f9))
* **serve:** make typing and mouse selection work on the live preview lanes ([#3504](https://github.com/yschimke/compose-ai-tools/issues/3504)) ([93d3a11](https://github.com/yschimke/compose-ai-tools/commit/93d3a1138adcf64a814ba074198ed6f739e4351c))
* **vscode:** let the panel type into text fields and select with the mouse ([#3524](https://github.com/yschimke/compose-ai-tools/issues/3524)) ([66af0c1](https://github.com/yschimke/compose-ai-tools/commit/66af0c1193726d565ea1a022952ceb9a6ed297f5))


### Performance Improvements

* **serve:** pool the cmp-jvm render workers instead of a JVM per document ([#3514](https://github.com/yschimke/compose-ai-tools/issues/3514)) ([b727a21](https://github.com/yschimke/compose-ai-tools/commit/b727a21f4e66c202849ee99d3073f0d3b344274d))

## [0.19.49](https://github.com/yschimke/compose-ai-tools/compare/v0.19.48...v0.19.49) (2026-08-08)


### Features

* **preview-data-api:** consume design-parity's page-backdrop manifest ([#3500](https://github.com/yschimke/compose-ai-tools/issues/3500)) ([3946b7e](https://github.com/yschimke/compose-ai-tools/commit/3946b7e7f5dad8b523c5507ecfee0ce470dc5653))
* **renderer-desktop:** drive @ScrollingPreview(END) on CMP ([#3513](https://github.com/yschimke/compose-ai-tools/issues/3513)) ([06ec7fb](https://github.com/yschimke/compose-ai-tools/commit/06ec7fb4fa16bcacc75edac071f50e947a755754))


### Bug Fixes

* **daemon:** honour @ScrollingPreview(END) in the static render ([#3499](https://github.com/yschimke/compose-ai-tools/issues/3499)) ([501bd3f](https://github.com/yschimke/compose-ai-tools/commit/501bd3f690c77a56c3443979d665be74ec3f0810))
* **daemon:** replay IR-backed previews in live interactive sessions ([#3510](https://github.com/yschimke/compose-ai-tools/issues/3510)) ([442e1af](https://github.com/yschimke/compose-ai-tools/commit/442e1aff7c07fafe098c7e72f8bbccffc1bf4b4a))
* **figma-svg:** crop a clipping draw at the region it draws ([#3505](https://github.com/yschimke/compose-ai-tools/issues/3505)) ([962fe1b](https://github.com/yschimke/compose-ai-tools/commit/962fe1bec6c9cbc69637e4a0d8bdd92f5a43819e))
* **figma-svg:** place a curved run at its drawn geometry ([#3509](https://github.com/yschimke/compose-ai-tools/issues/3509)) ([0c2d8cd](https://github.com/yschimke/compose-ai-tools/commit/0c2d8cd729b561a2ebdb8e88ab76902cb154ed3c))
* **preview-data-api:** tolerate a page-backdrop manifest without ref ([#3511](https://github.com/yschimke/compose-ai-tools/issues/3511)) ([2fe39e2](https://github.com/yschimke/compose-ai-tools/commit/2fe39e24b0898e8f9be3e8e1a2f872c2480ee719))
* **rc-player:** instance font axes on the variable file in the view lane ([#3503](https://github.com/yschimke/compose-ai-tools/issues/3503)) ([64d54f3](https://github.com/yschimke/compose-ai-tools/commit/64d54f3a44471400117621750c8f6affc5db31be))
* **renderer:** mirror the capture for real RTL locales, not just ar-XB ([#3502](https://github.com/yschimke/compose-ai-tools/issues/3502)) ([820744b](https://github.com/yschimke/compose-ai-tools/commit/820744bf922323b1099a2a4469a1282858f825ae))
* **serve:** make the render placeholder fixture font-free and host-reproducible ([#3501](https://github.com/yschimke/compose-ai-tools/issues/3501)) ([f372435](https://github.com/yschimke/compose-ai-tools/commit/f3724353e94f745311f341a1f853eeec3e582823))
* **serve:** register the vendored typefaces for the browser Remote Compose lane ([#3507](https://github.com/yschimke/compose-ai-tools/issues/3507)) ([a5b3c17](https://github.com/yschimke/compose-ai-tools/commit/a5b3c17a7417f67cbc1d66df115f61544864f654))
* **serve:** stop reporting overrides an IR replay never applied as applied ([#3512](https://github.com/yschimke/compose-ai-tools/issues/3512)) ([d9f9fe1](https://github.com/yschimke/compose-ai-tools/commit/d9f9fe1163920e872573180f3c5210b35e9c65f6))


### Performance Improvements

* **rc-compare:** hand the CMP/Wasm player each document in place, and capture on convergence ([#3508](https://github.com/yschimke/compose-ai-tools/issues/3508)) ([f6cb4f9](https://github.com/yschimke/compose-ai-tools/commit/f6cb4f9c1efd99b38055ee282d184a5ae1f59709))

## [0.19.48](https://github.com/yschimke/compose-ai-tools/compare/v0.19.47...v0.19.48) (2026-08-08)


### Features

* **serve:** end every page with the minimal footer, close with the about box ([#3491](https://github.com/yschimke/compose-ai-tools/issues/3491)) ([0530fdb](https://github.com/yschimke/compose-ai-tools/commit/0530fdba50f6afcdcfe90f7fe9b3301a855f0fbd))
* **serve:** one chip + one combo for the viewer's renderer, and a single-line export bar ([#3496](https://github.com/yschimke/compose-ai-tools/issues/3496)) ([e8fafbf](https://github.com/yschimke/compose-ai-tools/commit/e8fafbf8786243cb4afe64b739583f18e2249bd3))
* **serve:** replace the baked TalkBack overlay with inspection layers ([#3497](https://github.com/yschimke/compose-ai-tools/issues/3497)) ([1a34da2](https://github.com/yschimke/compose-ai-tools/commit/1a34da2fc0d0287dae2d50ce651b07f6c8169a42))


### Bug Fixes

* **figma-svg:** stop a clipped-away run landing on an unrelated node ([#3495](https://github.com/yschimke/compose-ai-tools/issues/3495)) ([4b712a7](https://github.com/yschimke/compose-ai-tools/commit/4b712a74d621a731a8313f690e02c612dfaf33a6))
* **rc-compare:** report CMP/Wasm pixel parity, guard regressions on the PR ([#3492](https://github.com/yschimke/compose-ai-tools/issues/3492)) ([05bbe8f](https://github.com/yschimke/compose-ai-tools/commit/05bbe8feb043502ba9cccb93f6306d413043f1d8))
* **serve:** pin the site header's slots so the nav stops moving between pages ([#3493](https://github.com/yschimke/compose-ai-tools/issues/3493)) ([36bb1e9](https://github.com/yschimke/compose-ai-tools/commit/36bb1e9479020e27c20bba6596af85522f29d258))

## [0.19.47](https://github.com/yschimke/compose-ai-tools/compare/v0.19.46...v0.19.47) (2026-08-08)


### Features

* **serve:** apply Material 3 to the preview server chrome ([#3490](https://github.com/yschimke/compose-ai-tools/issues/3490)) ([9eea184](https://github.com/yschimke/compose-ai-tools/commit/9eea184cf1e5c613e08bac9542363d0d71ef0f38))
* **serve:** give the viewer the theme bar + Background/Transparent, as flow-row buttons ([#3489](https://github.com/yschimke/compose-ai-tools/issues/3489)) ([6076899](https://github.com/yschimke/compose-ai-tools/commit/607689931c41ef0c83f4c3c69614682c38b54470))
* **serve:** offer the imported design spec as a viewer lane beside the players ([#3488](https://github.com/yschimke/compose-ai-tools/issues/3488)) ([3e6b041](https://github.com/yschimke/compose-ai-tools/commit/3e6b041aa13700fd68b8faf69d376e06780a05d4))


### Bug Fixes

* **ci:** restore green main — iOS 26 SDK for the player, Rule 3 only where it applies ([#3485](https://github.com/yschimke/compose-ai-tools/issues/3485)) ([e9b1cb7](https://github.com/yschimke/compose-ai-tools/commit/e9b1cb756c8d70f92269b68c7aeda21ef6a33729))

## [0.19.46](https://github.com/yschimke/compose-ai-tools/compare/v0.19.45...v0.19.46) (2026-08-07)


### Bug Fixes

* **plugin:** scope Rule 3's Compose exclusion to our own render dependencies ([#3483](https://github.com/yschimke/compose-ai-tools/issues/3483)) ([a73a88e](https://github.com/yschimke/compose-ai-tools/commit/a73a88e1b377f2cd4eb7bbae1f421f3f77b39280))

## [0.19.45](https://github.com/yschimke/compose-ai-tools/compare/v0.19.44...v0.19.45) (2026-08-07)


### Features

* **serve:** add a design-parity view to the catalog ([#3470](https://github.com/yschimke/compose-ai-tools/issues/3470)) ([036697a](https://github.com/yschimke/compose-ai-tools/commit/036697a58672fae9a67e99cc2982882e540d6ca5))
* **serve:** show every Remote Compose player side by side on the compare page ([#3475](https://github.com/yschimke/compose-ai-tools/issues/3475)) ([c968eb9](https://github.com/yschimke/compose-ai-tools/commit/c968eb95909cb0a5308bb1bd64e0c9b4f7c67139))
* **serve:** show the render-history timeline in project mode from local git ([#3456](https://github.com/yschimke/compose-ai-tools/issues/3456)) ([0f82fd1](https://github.com/yschimke/compose-ai-tools/commit/0f82fd1cc15843271d844efed4442efbf906aa6e))


### Bug Fixes

* **actions:** upload the .error.json sidecars with the render report ([#3471](https://github.com/yschimke/compose-ai-tools/issues/3471)) ([e89c3f5](https://github.com/yschimke/compose-ai-tools/commit/e89c3f5f7a9b1278cc4d97e1f188da2e6145913c))
* **deps:** bump Compose Multiplatform to 1.11.1 so serve can render 1.11 catalogs ([#3462](https://github.com/yschimke/compose-ai-tools/issues/3462)) ([28af866](https://github.com/yschimke/compose-ai-tools/commit/28af866a45ab8d30d67cf771eba8ae09bb9ba48d))
* **design-artifacts:** match candidates to previews across both id spellings ([#3463](https://github.com/yschimke/compose-ai-tools/issues/3463)) ([2fd0ca5](https://github.com/yschimke/compose-ai-tools/commit/2fd0ca5f1d559d4210a4d693e62266dd66642376))
* **design-artifacts:** unblock the remote-m3 CMP/Wasm parity gate ([#3476](https://github.com/yschimke/compose-ai-tools/issues/3476)) ([cdbc522](https://github.com/yschimke/compose-ai-tools/commit/cdbc522348c02dd8c78060ee4bff1f26420294af))
* **rc-compare:** uncap the compositor frame rate so short viewports are not scored as slow ([#3459](https://github.com/yschimke/compose-ai-tools/issues/3459)) ([f2117df](https://github.com/yschimke/compose-ai-tools/commit/f2117dfc3b33803c43847ef564bf0e6837e547bd))
* **rc-player:** honour the size a component was asked for in the JS player ([#3474](https://github.com/yschimke/compose-ai-tools/issues/3474)) ([a48145f](https://github.com/yschimke/compose-ai-tools/commit/a48145f62d18a189c88a23f5dc3e3cede37b7fc9))
* **rc-player:** refresh the vendored player to 53e19e93 and keep weighted children measurable ([#3465](https://github.com/yschimke/compose-ai-tools/issues/3465)) ([ba67e0e](https://github.com/yschimke/compose-ai-tools/commit/ba67e0e87971a6325a9bd7094ce9e05d52acd419))
* **rc-player:** render text published by lookup operations on the CMP/Wasm lane ([#3461](https://github.com/yschimke/compose-ai-tools/issues/3461)) ([4bc692f](https://github.com/yschimke/compose-ai-tools/commit/4bc692ff87d68a9207cb00a64a35aa9979a11538))
* **rc-player:** resolve the default font family and honour requested weight ([#3468](https://github.com/yschimke/compose-ai-tools/issues/3468)) ([907b93e](https://github.com/yschimke/compose-ai-tools/commit/907b93ef5c5bddf05af35091caa5e564c358d9da))
* **serve:** construct the history repo path instead of passing DOM text through ([#3454](https://github.com/yschimke/compose-ai-tools/issues/3454)) ([58f8486](https://github.com/yschimke/compose-ai-tools/commit/58f84860e9effc42cfe103ce93b45873c9cce1fd))
* **serve:** make the parity feed actually reach a published catalog ([#3472](https://github.com/yschimke/compose-ai-tools/issues/3472)) ([69d2602](https://github.com/yschimke/compose-ai-tools/commit/69d2602ad2295c1f9cb9644627d1398c225935e2))
* **serve:** make the viewer's overlay toggles usable and actually render ([#3464](https://github.com/yschimke/compose-ai-tools/issues/3464)) ([662187f](https://github.com/yschimke/compose-ai-tools/commit/662187fb2460e2734706d251f69961f175d308c4))
* **serve:** refuse a dropped override on the vector and Storybook lanes too ([#3467](https://github.com/yschimke/compose-ai-tools/issues/3467)) ([14f3a60](https://github.com/yschimke/compose-ai-tools/commit/14f3a60338e139bf17b6c5a220086b9c78a0868d))
* **serve:** refuse a render whose validated overrides can't be applied ([#3460](https://github.com/yschimke/compose-ai-tools/issues/3460)) ([ca67bc7](https://github.com/yschimke/compose-ai-tools/commit/ca67bc70f06a5f96d5d0143f492e51d17ec8ad8c))
* **serve:** trip a circuit breaker on fatal render failures instead of retrying forever ([#3455](https://github.com/yschimke/compose-ai-tools/issues/3455)) ([77daf59](https://github.com/yschimke/compose-ai-tools/commit/77daf59d98ecc37fb5cfb33d62abffc9111baf42))


### Performance Improvements

* **rc-compare:** let the parity lane skip the player's snapshot-handoff tail ([#3466](https://github.com/yschimke/compose-ai-tools/issues/3466)) ([e95d2b5](https://github.com/yschimke/compose-ai-tools/commit/e95d2b5f005298d6059c6945cd940d1f7398f143))

## [0.19.44](https://github.com/yschimke/compose-ai-tools/compare/v0.19.43...v0.19.44) (2026-08-07)


### Features

* **design-artifacts:** shard the catalog render across parallel jobs ([#3439](https://github.com/yschimke/compose-ai-tools/issues/3439)) ([8985bea](https://github.com/yschimke/compose-ai-tools/commit/8985bea543a519f6459391acfc5ede6b6d0f28cd))
* **rc-compare:** score coverage and content separately ([#3434](https://github.com/yschimke/compose-ai-tools/issues/3434)) ([11b0be4](https://github.com/yschimke/compose-ai-tools/commit/11b0be48927644c6ccc926a17a0933a323002fa7))
* **rc-compare:** split coverage and content for every player lane ([#3438](https://github.com/yschimke/compose-ai-tools/issues/3438)) ([4fd379d](https://github.com/yschimke/compose-ai-tools/commit/4fd379d033ac8e98809efcca3c5cac57e6b10c05))
* **serve:** show a render-history timeline in the viewer ([#3430](https://github.com/yschimke/compose-ai-tools/issues/3430)) ([c650aeb](https://github.com/yschimke/compose-ai-tools/commit/c650aeb5d1453e18f709d9453cd8cced6aafa224))


### Bug Fixes

* **harness:** refresh the viewer.js hash in the serve-viewer-history fixture ([#3446](https://github.com/yschimke/compose-ai-tools/issues/3446)) ([3a4e57e](https://github.com/yschimke/compose-ai-tools/commit/3a4e57ed76ea86a522f5b2e4c028805dfbeef830))
* **harness:** serve the CLI viewer's assets to page fixtures ([#3437](https://github.com/yschimke/compose-ai-tools/issues/3437)) ([29e5ecb](https://github.com/yschimke/compose-ai-tools/commit/29e5ecb42d04d4a277c6d73c3310e5e569958fcb))
* **rc-compare:** serialize the per-lane split and scope its means correctly ([#3440](https://github.com/yschimke/compose-ai-tools/issues/3440)) ([a5d19f1](https://github.com/yschimke/compose-ai-tools/commit/a5d19f14a3cc854134c485fab2d32820ceadb474))
* **serve:** treat an empty knob value as a value, not a missing one ([#3435](https://github.com/yschimke/compose-ai-tools/issues/3435)) ([612b137](https://github.com/yschimke/compose-ai-tools/commit/612b1373fca5573d6a6923efffcba070af515a3b))

## [0.19.43](https://github.com/yschimke/compose-ai-tools/compare/v0.19.42...v0.19.43) (2026-08-07)


### Features

* **ci:** publish history.json from the baseline pipeline ([#3415](https://github.com/yschimke/compose-ai-tools/issues/3415)) ([25ceebe](https://github.com/yschimke/compose-ai-tools/commit/25ceebe5b6d7d16e67abdf00d9f5b45cb11f841b))
* **deploy:** serve the m3-catalog design system on preview.coo.ee ([#3414](https://github.com/yschimke/compose-ai-tools/issues/3414)) ([ef2b5f9](https://github.com/yschimke/compose-ai-tools/commit/ef2b5f913eec21b7f61b64b278d93141082b90c4))
* **design-artifacts:** make the render timeout an input ([#3421](https://github.com/yschimke/compose-ai-tools/issues/3421)) ([3317ec8](https://github.com/yschimke/compose-ai-tools/commit/3317ec81f74c4ffbb6a7ff02200a12108b665a7a))
* **figma-svg:** export Material icons as named fonts.google.com references ([#3429](https://github.com/yschimke/compose-ai-tools/issues/3429)) ([cd33240](https://github.com/yschimke/compose-ai-tools/commit/cd332409fc30b1cfe11f6c3752358a1f83cb242b))
* **figma-svg:** request the export background per preview, in four modes ([#3411](https://github.com/yschimke/compose-ai-tools/issues/3411)) ([50bc405](https://github.com/yschimke/compose-ai-tools/commit/50bc405defddf0a2d75b6afcbc48afb6c4bae8f2))
* **playground:** navigate every preview a snippet declares, and its knobs ([#3431](https://github.com/yschimke/compose-ai-tools/issues/3431)) ([10dda6c](https://github.com/yschimke/compose-ai-tools/commit/10dda6c65416bf3d8e5af1223e643b5c40a0894a))
* **rc-compare:** show every player, diff against a chosen one, none by default ([#3416](https://github.com/yschimke/compose-ai-tools/issues/3416)) ([ff327f8](https://github.com/yschimke/compose-ai-tools/commit/ff327f849decf137bd22a3d61a9e185753c2a980))
* **serve:** give the playground compile lane a per-caller budget ([#3404](https://github.com/yschimke/compose-ai-tools/issues/3404)) ([005a535](https://github.com/yschimke/compose-ai-tools/commit/005a535de48f51ec3172eda4516c5947370560e7))
* **serve:** open a served preview's source in the playground ([#3418](https://github.com/yschimke/compose-ai-tools/issues/3418)) ([fe062ba](https://github.com/yschimke/compose-ai-tools/commit/fe062ba23285fce3586d728518542075f7f3b042))


### Bug Fixes

* **catalog:** make the sticker helper visible to a playground snippet ([#3426](https://github.com/yschimke/compose-ai-tools/issues/3426)) ([6e81de2](https://github.com/yschimke/compose-ai-tools/commit/6e81de234032bf6c711acd67bab06169d647fb52))
* **ci:** give the history commit a committer identity ([#3433](https://github.com/yschimke/compose-ai-tools/issues/3433)) ([4d9894f](https://github.com/yschimke/compose-ai-tools/commit/4d9894f6017a9512623f5ebf2bd49719e78d2c24))
* **ci:** publish history.json on branches that do not have one yet ([#3424](https://github.com/yschimke/compose-ai-tools/issues/3424)) ([5ae59ef](https://github.com/yschimke/compose-ai-tools/commit/5ae59ef2e0cd4a952115a8f30245c95374c0248a))
* **ci:** stop the baseline push from dropping history.json ([#3417](https://github.com/yschimke/compose-ai-tools/issues/3417)) ([e5f6695](https://github.com/yschimke/compose-ai-tools/commit/e5f66959464be0720efdaa6759159b39fa3d704d))
* **design-artifacts:** publish design references for annotation-led catalogs ([#3420](https://github.com/yschimke/compose-ai-tools/issues/3420)) ([72e5832](https://github.com/yschimke/compose-ai-tools/commit/72e5832aa9233c7f80d438a5ba9ca27027256baa))
* **rc-player:** implement TEXT_LOOKUP_INT and fix integer-expression ids ([#3427](https://github.com/yschimke/compose-ai-tools/issues/3427)) ([57ff1b2](https://github.com/yschimke/compose-ai-tools/commit/57ff1b2dde9c2844d516997f94c1b87d9f8d2a96))
* **serve:** keep the playground seed fresh, off the event loop, and honest ([#3419](https://github.com/yschimke/compose-ai-tools/issues/3419)) ([6e4d709](https://github.com/yschimke/compose-ai-tools/commit/6e4d70994888912578025c0c03d9753c9f445502))
* **serve:** score design references on their content box, not their canvas ([#3413](https://github.com/yschimke/compose-ai-tools/issues/3413)) ([bb73297](https://github.com/yschimke/compose-ai-tools/commit/bb73297a682d5597b5f8abd6dab911356e13315d))
* **serve:** seed a variant's knob into the Wasm tier ([#3428](https://github.com/yschimke/compose-ai-tools/issues/3428)) ([baf74ef](https://github.com/yschimke/compose-ai-tools/commit/baf74efeda9ded36621b4173a3ea67ae3983d5a1))

## [0.19.42](https://github.com/yschimke/compose-ai-tools/compare/v0.19.41...v0.19.42) (2026-08-07)


### Features

* **catalogs:** make every M3 catalog component respond to a click ([#3407](https://github.com/yschimke/compose-ai-tools/issues/3407)) ([367f1ff](https://github.com/yschimke/compose-ai-tools/commit/367f1ff15d189810430efb43e039302e16618c21))
* **serve:** add the history.json manifest for delivery-branch render history ([#3403](https://github.com/yschimke/compose-ai-tools/issues/3403)) ([b9fd6e7](https://github.com/yschimke/compose-ai-tools/commit/b9fd6e7597bb6b3e471b0640612138385570a879))
* **serve:** let a playground snippet pick its catalog at runtime ([#3397](https://github.com/yschimke/compose-ai-tools/issues/3397)) ([d90be9a](https://github.com/yschimke/compose-ai-tools/commit/d90be9aa0f7027ed95eb33325ffee6f9c199d6ef))
* **serve:** long-press a catalog card to start a live daemon session in place ([#3408](https://github.com/yschimke/compose-ai-tools/issues/3408)) ([dc66c1f](https://github.com/yschimke/compose-ai-tools/commit/dc66c1f8eb662a6af7e629e70ba09bb96d05bee9))
* **serve:** read per-preview render history off the delivery branches ([#3394](https://github.com/yschimke/compose-ai-tools/issues/3394)) ([04be3cd](https://github.com/yschimke/compose-ai-tools/commit/04be3cd6ebdb2dd545795f13a4152a18fe9088e1))
* **serve:** widen the background render lane from 1 to 3 ([#3399](https://github.com/yschimke/compose-ai-tools/issues/3399)) ([643b497](https://github.com/yschimke/compose-ai-tools/commit/643b497d7386f86c38e1011f296fb2a39991c86a))


### Bug Fixes

* **history:** move the render-history archive out of the working tree ([#3410](https://github.com/yschimke/compose-ai-tools/issues/3410)) ([b11140d](https://github.com/yschimke/compose-ai-tools/commit/b11140d1dd927786b249377566a21ab357f3c8ea))
* **render:** make the live, SVG and Wasm lanes agree with the snapshot ([#3409](https://github.com/yschimke/compose-ai-tools/issues/3409)) ([1ffed6f](https://github.com/yschimke/compose-ai-tools/commit/1ffed6f388caa529e805c605cbef53675324e8d4))
* **serve:** close three gaps in the playground catalog selector ([#3398](https://github.com/yschimke/compose-ai-tools/issues/3398)) ([618a65f](https://github.com/yschimke/compose-ai-tools/commit/618a65ff2cbc12174ce6f38fc7b299f64dc4d427))
* **serve:** derive the background render lane from the seat budget ([#3401](https://github.com/yschimke/compose-ai-tools/issues/3401)) ([75acc64](https://github.com/yschimke/compose-ai-tools/commit/75acc648b5d78a904f70f84a9bf4bf74f29549b4))
* **serve:** drop a playground jail that cannot launch, keep the caps ([#3392](https://github.com/yschimke/compose-ai-tools/issues/3392)) ([2a8702f](https://github.com/yschimke/compose-ai-tools/commit/2a8702f53696b1728b1a5136e0d69d31c162de53))
* **serve:** keep prefetch off foreground and per-preview seats ([#3396](https://github.com/yschimke/compose-ai-tools/issues/3396)) ([2e8fe08](https://github.com/yschimke/compose-ai-tools/commit/2e8fe087bca3a7d6abaf69ef942d7a19eeddbf46))
* **serve:** offer sign-in instead of a dead Live preview toggle ([#3400](https://github.com/yschimke/compose-ai-tools/issues/3400)) ([17053ed](https://github.com/yschimke/compose-ai-tools/commit/17053ed5b324baa7ddd396615138924497bba34f))
* **serve:** price prefetch replicas as background residency ([#3393](https://github.com/yschimke/compose-ai-tools/issues/3393)) ([b2cbd2f](https://github.com/yschimke/compose-ai-tools/commit/b2cbd2f33125321639e32dd6523e99be31d24ce3))
* **vscode:** defer the applied-marker bootstrap to first view open ([#3406](https://github.com/yschimke/compose-ai-tools/issues/3406)) ([45f5ccb](https://github.com/yschimke/compose-ai-tools/commit/45f5ccbda34291732279eba88b0042e17aa687ee))

## [0.19.41](https://github.com/yschimke/compose-ai-tools/compare/v0.19.40...v0.19.41) (2026-08-06)


### Features

* **serve:** split optimizer wait time into gate wait and permit wait ([#3386](https://github.com/yschimke/compose-ai-tools/issues/3386)) ([ef56346](https://github.com/yschimke/compose-ai-tools/commit/ef563465fd0e335265b492947f9d05010c536e95))
* **serve:** split render time and report real batch concurrency ([#3389](https://github.com/yschimke/compose-ai-tools/issues/3389)) ([de7e3b4](https://github.com/yschimke/compose-ai-tools/commit/de7e3b4729281c4ce1d4eb61a25b688e25f18737))
* **serve:** use CodeMirror for the playground editor ([#3388](https://github.com/yschimke/compose-ai-tools/issues/3388)) ([59e118e](https://github.com/yschimke/compose-ai-tools/commit/59e118e860588bea466799153fb8829caf68cfe5))


### Bug Fixes

* **serve:** charge replica cold starts to the warm bucket ([#3390](https://github.com/yschimke/compose-ai-tools/issues/3390)) ([0751935](https://github.com/yschimke/compose-ai-tools/commit/07519354737ac512707aaf2862c4626ca3a7732b))
* **serve:** honour @FixedTheme in the viewer and on deferred records ([#3384](https://github.com/yschimke/compose-ai-tools/issues/3384)) ([00cdeb1](https://github.com/yschimke/compose-ai-tools/commit/00cdeb154df3a3681d316c97e40316bc7c30a918))
* **serve:** reserve a live seat for the per-preview daemon lane ([#3391](https://github.com/yschimke/compose-ai-tools/issues/3391)) ([8cdcc94](https://github.com/yschimke/compose-ai-tools/commit/8cdcc94fc8e0a28125614fb2f1a151439f77314a))


### Performance Improvements

* **serve:** gzip the text lanes ([#3380](https://github.com/yschimke/compose-ai-tools/issues/3380)) ([cf5dcd7](https://github.com/yschimke/compose-ai-tools/commit/cf5dcd7fda06221ec06f71842aa4dd7555202668))

## [0.19.40](https://github.com/yschimke/compose-ai-tools/compare/v0.19.39...v0.19.40) (2026-08-06)


### Features

* **serve:** add @FixedTheme so a theme specimen opts out anywhere ([#3381](https://github.com/yschimke/compose-ai-tools/issues/3381)) ([692ff38](https://github.com/yschimke/compose-ai-tools/commit/692ff381cce6b9c97739ba158d7342accb06a3a7))


### Bug Fixes

* **serve:** re-enter the theme prefetch, and give Busy a ceiling ([#3382](https://github.com/yschimke/compose-ai-tools/issues/3382)) ([17dd843](https://github.com/yschimke/compose-ai-tools/commit/17dd843a39a8e2890d8645cf0419fbeaf35d4c3d))

## [0.19.39](https://github.com/yschimke/compose-ai-tools/compare/v0.19.38...v0.19.39) (2026-08-05)


### Features

* **serve:** embed the render in the prefilled issue body instead of only linking it ([#3377](https://github.com/yschimke/compose-ai-tools/issues/3377)) ([1e94d16](https://github.com/yschimke/compose-ai-tools/commit/1e94d1619f85090b363ca5ddd39eb1044db8f049))
* **serve:** link the Figma node a preview is specified by, when the catalog names one ([#3366](https://github.com/yschimke/compose-ai-tools/issues/3366)) ([6424371](https://github.com/yschimke/compose-ai-tools/commit/642437190ab1b689cff33fc2e65d9e50b07e6434))
* **serve:** put every page selection in the URL ([#3371](https://github.com/yschimke/compose-ai-tools/issues/3371)) ([7245e55](https://github.com/yschimke/compose-ai-tools/commit/7245e55a9644593c504dddb45dcbb018f4bef4c5))
* **serve:** report prefetch rate, time split and observed batch width on /status ([#3373](https://github.com/yschimke/compose-ai-tools/issues/3373)) ([27f5700](https://github.com/yschimke/compose-ai-tools/commit/27f57003e548b8acf8aa589de89f4a9e8ffe39c7))


### Bug Fixes

* correct the theme-optimizer prefetch stats ([#3376](https://github.com/yschimke/compose-ai-tools/issues/3376)) ([e7db9ae](https://github.com/yschimke/compose-ai-tools/commit/e7db9ae3ff953692336aeb626dbf455efb4235ec))
* count only fresh renders as optimizer production ([#3379](https://github.com/yschimke/compose-ai-tools/issues/3379)) ([2213b69](https://github.com/yschimke/compose-ai-tools/commit/2213b690841d5450419df1f5ebcfc8ee4387d55f))
* **figma-svg:** drop retired subcomposition slots from the export ([#3367](https://github.com/yschimke/compose-ai-tools/issues/3367)) ([e426c77](https://github.com/yschimke/compose-ai-tools/commit/e426c776e1650216e3776d85968af734bc51c23d))
* **serve:** keep a Themes-tab specimen out of the theme override ([#3369](https://github.com/yschimke/compose-ai-tools/issues/3369)) ([f890511](https://github.com/yschimke/compose-ai-tools/commit/f89051198bf7c212c77ee5e320d21456f1820ead))
* **serve:** make the viewer's lane and the grid's background survive the URL round trip ([#3372](https://github.com/yschimke/compose-ai-tools/issues/3372)) ([9fe2a8b](https://github.com/yschimke/compose-ai-tools/commit/9fe2a8b29b321fe46c826c5ada83b1dd4630bfb5))
* **serve:** offer Wear watch shapes, not Pixel phones, for a Wear screen ([#3370](https://github.com/yschimke/compose-ai-tools/issues/3370)) ([bc102fa](https://github.com/yschimke/compose-ai-tools/commit/bc102fa93da380156066c6d82b40295a13396a5d))
* **serve:** overlay the whole Compose Multiplatform graph on the daemon parent ([#3378](https://github.com/yschimke/compose-ai-tools/issues/3378)) ([016565b](https://github.com/yschimke/compose-ai-tools/commit/016565b6968f9177938d6ad77a2a91b12e514385))
* **serve:** stop caching signed-in pages, and slide the GitHub session ([#3375](https://github.com/yschimke/compose-ai-tools/issues/3375)) ([c40e0b9](https://github.com/yschimke/compose-ai-tools/commit/c40e0b99706ed2dc88ad6221507f54971c2922b6))

## [0.19.38](https://github.com/yschimke/compose-ai-tools/compare/v0.19.37...v0.19.38) (2026-08-05)


### Features

* **serve:** file a preview bug from the viewer, with a link and a pasteable screenshot ([#3357](https://github.com/yschimke/compose-ai-tools/issues/3357)) ([1469755](https://github.com/yschimke/compose-ai-tools/commit/146975520f6b4f9f1df15734543f57c78403d526))


### Bug Fixes

* **figma-svg:** write an off-frame raster at its own size, not the frame crop ([#3364](https://github.com/yschimke/compose-ai-tools/issues/3364)) ([7ca39cf](https://github.com/yschimke/compose-ai-tools/commit/7ca39cf1d20869e2fbb7b2053eb5af55a65fc585))
* **gradle-plugin:** pack desktop classes, not the android compilation ([#3356](https://github.com/yschimke/compose-ai-tools/issues/3356)) ([d428231](https://github.com/yschimke/compose-ai-tools/commit/d428231c3b3314411a2452163d591695205be0e4))
* **serve:** charge leased burst replicas as foreground seats ([#3355](https://github.com/yschimke/compose-ai-tools/issues/3355)) ([27caacf](https://github.com/yschimke/compose-ai-tools/commit/27caacfd84825db55c8056d75d7cc47382cf46c3))
* **vscode:** keep elided composition spans inspectable in the Performance tab ([#3353](https://github.com/yschimke/compose-ai-tools/issues/3353)) ([04ece5a](https://github.com/yschimke/compose-ai-tools/commit/04ece5a08fce4ffc9a7715480072048ffe2cc74b))


### Performance Improvements

* **serve:** prefetch themes in per-preview batches through the replica pool ([#3363](https://github.com/yschimke/compose-ai-tools/issues/3363)) ([400989e](https://github.com/yschimke/compose-ai-tools/commit/400989e590db97ddbdcebc04a97795acec1b6fe6))

## [0.19.37](https://github.com/yschimke/compose-ai-tools/compare/v0.19.36...v0.19.37) (2026-08-05)


### Features

* **daemon:** trace composition composable-by-composable into render/trace ([#3352](https://github.com/yschimke/compose-ai-tools/issues/3352)) ([c78d866](https://github.com/yschimke/compose-ai-tools/commit/c78d866e642ef2727ab4f493c5084b4bcc5114fa))
* **rc-player:** apply a document's font-variation axes in the js lane ([#3348](https://github.com/yschimke/compose-ai-tools/issues/3348)) ([86be17e](https://github.com/yschimke/compose-ai-tools/commit/86be17e120b7d451a5f9edbdc471fdf81c010a05))


### Bug Fixes

* **daemon:** carry the trace origin alongside the overridden total ([#3349](https://github.com/yschimke/compose-ai-tools/issues/3349)) ([204cef0](https://github.com/yschimke/compose-ai-tools/commit/204cef02f797e9b8c1128f8921fa3532ebd737fd))
* **daemon:** report complete trace bounds past the span cap and drop delta fields from snapshots ([#3346](https://github.com/yschimke/compose-ai-tools/issues/3346)) ([d96ce9e](https://github.com/yschimke/compose-ai-tools/commit/d96ce9ee6020f10be14a1e485ac889d72521c1c9))
* **gradle-plugin:** resolve the desktop bundle classpath lazily ([#3351](https://github.com/yschimke/compose-ai-tools/issues/3351)) ([6835814](https://github.com/yschimke/compose-ai-tools/commit/68358143085f9215de6c6c85922bbc9d51d23814))
* **serve:** let the theme optimizer keep its turn while the server is quiet ([#3347](https://github.com/yschimke/compose-ai-tools/issues/3347)) ([33a26be](https://github.com/yschimke/compose-ai-tools/commit/33a26bedc739b22db6218131acc84bfcff1283a6))

## [0.19.36](https://github.com/yschimke/compose-ai-tools/compare/v0.19.35...v0.19.36) (2026-08-05)


### Features

* **daemon:** give render/trace real phase spans and reach recomposition from ordinary renders ([#3343](https://github.com/yschimke/compose-ai-tools/issues/3343)) ([5d4c407](https://github.com/yschimke/compose-ai-tools/commit/5d4c407eed2d284e550aec64189d0e628a774cdc))
* **design-catalog-remote-m3:** add typeface and font-variation specimens ([#3329](https://github.com/yschimke/compose-ai-tools/issues/3329)) ([bc38d93](https://github.com/yschimke/compose-ai-tools/commit/bc38d9313e65721eef754183e77dbe3e14be34bb))
* **fonts:** fetch a family's real variable font file, not a baked instance ([#3339](https://github.com/yschimke/compose-ai-tools/issues/3339)) ([98d6098](https://github.com/yschimke/compose-ai-tools/commit/98d6098c20318b5980f47866ed8c20f442fda2a2))
* **rc-player-compose:** resolve the default face by name and apply font axes ([#3334](https://github.com/yschimke/compose-ai-tools/issues/3334)) ([4afba97](https://github.com/yschimke/compose-ai-tools/commit/4afba97c8655688bfca1800535a62a33a51782a1))
* **rc-player-jvm:** apply a document's font-variation axes ([#3336](https://github.com/yschimke/compose-ai-tools/issues/3336)) ([51dab43](https://github.com/yschimke/compose-ai-tools/commit/51dab432da292b3064743a110170e3f6eb0e59e9))
* **rc-player:** trace the CMP players with androidx.tracing 2 and profile four documents ([#3341](https://github.com/yschimke/compose-ai-tools/issues/3341)) ([a015ed7](https://github.com/yschimke/compose-ai-tools/commit/a015ed71ffd1314b6a33ec37ca4d566a6a37ae6d))
* **remotecompose:** serve google: font families to the view player ([#3335](https://github.com/yschimke/compose-ai-tools/issues/3335)) ([27ea28c](https://github.com/yschimke/compose-ai-tools/commit/27ea28c1043b2758e3e206f8c98b4e0202cd9762))
* **serve:** give the preview server room to breathe and cards a real hover ([#3333](https://github.com/yschimke/compose-ai-tools/issues/3333)) ([42654cb](https://github.com/yschimke/compose-ai-tools/commit/42654cb4797e820b1e953e9ee36f99c617234684))
* **serve:** report playground health on /status.json ([#3330](https://github.com/yschimke/compose-ai-tools/issues/3330)) ([aec17a2](https://github.com/yschimke/compose-ai-tools/commit/aec17a2cffff8f4eafd80425c102dace6a0fb9d3))


### Bug Fixes

* **cli:** stream per-preview bundles out of bundle split instead of holding them all ([#3337](https://github.com/yschimke/compose-ai-tools/issues/3337)) ([03ecb67](https://github.com/yschimke/compose-ai-tools/commit/03ecb67993ff014ab379d352d8d7694358a01274))
* **daemon:** keep render/trace aggregates complete and ordinary-render recomposition in snapshot mode ([#3344](https://github.com/yschimke/compose-ai-tools/issues/3344)) ([a253e9c](https://github.com/yschimke/compose-ai-tools/commit/a253e9c2f758721bed78d63e1a1368cc4a962dd8))
* **serve:** render a cold-id theme request instead of abandoning it ([#3345](https://github.com/yschimke/compose-ai-tools/issues/3345)) ([b6e1fc3](https://github.com/yschimke/compose-ai-tools/commit/b6e1fc336859ad65683cd3e5d03fda2061ddf07f))

## [0.19.35](https://github.com/yschimke/compose-ai-tools/compare/v0.19.34...v0.19.35) (2026-08-05)


### Features

* **rc-player-jvm:** download google: font families for the cmp-jvm lane ([#3327](https://github.com/yschimke/compose-ai-tools/issues/3327)) ([09846aa](https://github.com/yschimke/compose-ai-tools/commit/09846aa1514273147f1cc60e8932cd26e45ff039))
* **serve:** make the playground deployable on preview.coo.ee ([#3320](https://github.com/yschimke/compose-ai-tools/issues/3320)) ([438ab8c](https://github.com/yschimke/compose-ai-tools/commit/438ab8ca1b0f4958a724a32127346584ccc4321e))


### Bug Fixes

* **android:** serialize the native-runtime cache lock within one JVM ([#3315](https://github.com/yschimke/compose-ai-tools/issues/3315)) ([c79f792](https://github.com/yschimke/compose-ai-tools/commit/c79f79280dbd198fdf3648bbadc7f241e89d3d3f))
* **figma-svg:** read FontFamily.Default as an unstated family ([#3319](https://github.com/yschimke/compose-ai-tools/issues/3319)) ([cf0710c](https://github.com/yschimke/compose-ai-tools/commit/cf0710cc8d2cbe7e419fc361de01c1754c44905f))
* **gradle-plugin:** require a recognised merge-blame schema before pruning ([#3314](https://github.com/yschimke/compose-ai-tools/issues/3314)) ([2ea2f4c](https://github.com/yschimke/compose-ai-tools/commit/2ea2f4c7cf3703cebfd87fa6fa6a09595fb165b5))
* **renderer:** keep smallestScreenWidthDp in step with the preview viewport ([#3312](https://github.com/yschimke/compose-ai-tools/issues/3312)) ([45e2981](https://github.com/yschimke/compose-ai-tools/commit/45e298130da0f2ee5c8d589312c0cf216ffc2afe))
* **serve:** hide device overrides for component previews ([#3303](https://github.com/yschimke/compose-ai-tools/issues/3303)) ([54187bc](https://github.com/yschimke/compose-ai-tools/commit/54187bc7d252f113fcdcec02c239381bcb8e5b80))
* **serve:** keep live-seat headroom for streams, not render pools ([#3326](https://github.com/yschimke/compose-ai-tools/issues/3326)) ([9ec0102](https://github.com/yschimke/compose-ai-tools/commit/9ec0102d4cadb9493378830b4698d900359fb581))
* **serve:** make the playground repo gate visibility-aware ([#3318](https://github.com/yschimke/compose-ai-tools/issues/3318)) ([19c92aa](https://github.com/yschimke/compose-ai-tools/commit/19c92aa90201c8fa174cef7492d38794e6c7aec5))
* **serve:** read the compile subprocess output under its own lock ([#3316](https://github.com/yschimke/compose-ai-tools/issues/3316)) ([21e495c](https://github.com/yschimke/compose-ai-tools/commit/21e495c995a6f51651cfe5e41ac6b7da439e807d))
* **serve:** reap idle pooled daemons, budget them, and stop retrying dead previews ([#3322](https://github.com/yschimke/compose-ai-tools/issues/3322)) ([d1fe8a4](https://github.com/yschimke/compose-ai-tools/commit/d1fe8a4ada936e0c0df5872f1b2f234617e123e6))
* **serve:** record the render URL behind the viewer's blob frame ([#3317](https://github.com/yschimke/compose-ai-tools/issues/3317)) ([c00b205](https://github.com/yschimke/compose-ai-tools/commit/c00b20528d0c4891088d79ade3c1fe93ba7cc970))
* **serve:** require write access for the playground repo gate ([#3313](https://github.com/yschimke/compose-ai-tools/issues/3313)) ([ae29d34](https://github.com/yschimke/compose-ai-tools/commit/ae29d34a11cbbd5fd79ee3e8243ad29f13071bb0))
* **serve:** stop asking every visitor for the repo OAuth scope ([#3325](https://github.com/yschimke/compose-ai-tools/issues/3325)) ([f79cd20](https://github.com/yschimke/compose-ai-tools/commit/f79cd209f445377d1e617e98c66ffb00c7713d03))

## [0.19.34](https://github.com/yschimke/compose-ai-tools/compare/v0.19.33...v0.19.34) (2026-08-04)


### Features

* **serve:** publish Home Assistant and Thunderbird catalogs ([#3304](https://github.com/yschimke/compose-ai-tools/issues/3304)) ([e292122](https://github.com/yschimke/compose-ai-tools/commit/e2921223836eebf99f68cbf99c87a6ae45c3eb90))


### Bug Fixes

* **serve:** clarify themed preview loading ([#3307](https://github.com/yschimke/compose-ai-tools/issues/3307)) ([b0e616f](https://github.com/yschimke/compose-ai-tools/commit/b0e616fff25a92d711ff57ec56463074dfe03a75))
* **serve:** normalize Gradle source paths ([#3310](https://github.com/yschimke/compose-ai-tools/issues/3310)) ([52fb54b](https://github.com/yschimke/compose-ai-tools/commit/52fb54ba9d754842ba39e7aa1db92d53e942fbd0))
* **serve:** only badge untrusted catalogs ([#3305](https://github.com/yschimke/compose-ai-tools/issues/3305)) ([e75c438](https://github.com/yschimke/compose-ai-tools/commit/e75c43854cf201112bd232a6a236276d0db69584))

## [0.19.33](https://github.com/yschimke/compose-ai-tools/compare/v0.19.32...v0.19.33) (2026-08-04)


### Features

* **serve:** show catalog build metadata on status ([#3302](https://github.com/yschimke/compose-ai-tools/issues/3302)) ([a7166dc](https://github.com/yschimke/compose-ai-tools/commit/a7166dcf4bfa3bbe34f69059b69a03df38b02c0e))


### Bug Fixes

* **android:** share Robolectric native runtime extraction ([#3296](https://github.com/yschimke/compose-ai-tools/issues/3296)) ([8eed4ae](https://github.com/yschimke/compose-ai-tools/commit/8eed4ae364c8efb5fff2839fd0f73ef71e78fd0f))
* **daemon-android:** route preview parameters from bundles ([#3293](https://github.com/yschimke/compose-ai-tools/issues/3293)) ([987d71c](https://github.com/yschimke/compose-ai-tools/commit/987d71cbf262dc272ee2aec89be71de60641d94e))
* **gradle-plugin:** distinguish unavailable resource ownership ([#3299](https://github.com/yschimke/compose-ai-tools/issues/3299)) ([ab12b11](https://github.com/yschimke/compose-ai-tools/commit/ab12b116af27b20010078796c23740e674946bea))
* **gradle-plugin:** make resource pruning fail safe ([#3297](https://github.com/yschimke/compose-ai-tools/issues/3297)) ([87ffed1](https://github.com/yschimke/compose-ai-tools/commit/87ffed1fd86ae96acc040d56506a985c6a0ab2c2))
* **release:** keep CLI updates available ([#3292](https://github.com/yschimke/compose-ai-tools/issues/3292)) ([547673f](https://github.com/yschimke/compose-ai-tools/commit/547673f232d33c66d07a723323873a0a5e7e58d5))
* **release:** publish Maven readiness marker ([#3295](https://github.com/yschimke/compose-ai-tools/issues/3295)) ([69fa108](https://github.com/yschimke/compose-ai-tools/commit/69fa10886b2edab2c4bfeddc37f84c939dda2f3c))
* **serve:** keep themes across snapshot overrides ([#3301](https://github.com/yschimke/compose-ai-tools/issues/3301)) ([a8cc181](https://github.com/yschimke/compose-ai-tools/commit/a8cc181c7f0a70cae4de580c617c95bc46bed810))


### Performance Improvements

* **serve:** cache previews by catalog generation ([#3298](https://github.com/yschimke/compose-ai-tools/issues/3298)) ([70463de](https://github.com/yschimke/compose-ai-tools/commit/70463decad85fead8fcb183d9688df3a7b4971e1))
* **serve:** enable gentle theme optimization ([#3300](https://github.com/yschimke/compose-ai-tools/issues/3300)) ([49f8858](https://github.com/yschimke/compose-ai-tools/commit/49f88589c8a42e8badd8dbc2d9eb1486af546647))

## [0.19.32](https://github.com/yschimke/compose-ai-tools/compare/v0.19.31...v0.19.32) (2026-08-04)


### Features

* **catalog:** declare a component's breakpoints on @CatalogComponent ([#3285](https://github.com/yschimke/compose-ai-tools/issues/3285)) ([74b3822](https://github.com/yschimke/compose-ai-tools/commit/74b38227cc8203c48283b3f85cdb537622689b87))
* **wear-m3:** publish the full-screen components as a card per breakpoint ([#3288](https://github.com/yschimke/compose-ai-tools/issues/3288)) ([047d6a0](https://github.com/yschimke/compose-ai-tools/commit/047d6a03ac970eb2ce83bcd27cd5e112404c8483))


### Bug Fixes

* **serve:** humanize catalog component names ([#3291](https://github.com/yschimke/compose-ai-tools/issues/3291)) ([0cbfb46](https://github.com/yschimke/compose-ai-tools/commit/0cbfb46cd0683d6e45693d4b3aa5ae855550a09d))
* **serve:** publish supplement preview controls ([#3279](https://github.com/yschimke/compose-ai-tools/issues/3279)) ([9544c96](https://github.com/yschimke/compose-ai-tools/commit/9544c969e20aae5b8baef5e7d3be9637324e9814))
* **serve:** support shared daemon lease bursts ([#3289](https://github.com/yschimke/compose-ai-tools/issues/3289)) ([4dfdbe4](https://github.com/yschimke/compose-ai-tools/commit/4dfdbe4af71e41cc7a25dab8914d514307c505ba))

## [0.19.31](https://github.com/yschimke/compose-ai-tools/compare/v0.19.30...v0.19.31) (2026-08-04)


### Features

* **catalog:** select one breakpoint of a multipreview without splitting the @Preview ([#3282](https://github.com/yschimke/compose-ai-tools/issues/3282)) ([f1969d0](https://github.com/yschimke/compose-ai-tools/commit/f1969d0161c98c26ebe08eefd3e29f9e7e6098dc))


### Bug Fixes

* **daemon:** honour the wrap sandbox in PreviewIndex-backed resolvers ([#3283](https://github.com/yschimke/compose-ai-tools/issues/3283)) ([a844116](https://github.com/yschimke/compose-ai-tools/commit/a844116aa3d1176e1938d30508416b5c9f01dab6))

## [0.19.30](https://github.com/yschimke/compose-ai-tools/compare/v0.19.29...v0.19.30) (2026-08-04)


### Bug Fixes

* **design-artifacts:** merge annotation manifests when folding a catalog section ([#3276](https://github.com/yschimke/compose-ai-tools/issues/3276)) ([8d3d4d0](https://github.com/yschimke/compose-ai-tools/commit/8d3d4d01aa685a62d8a3161d1bc7faab2e3ba227))
* **serve:** hold a card's pixels until its themed render arrives, and show the render server ([#3274](https://github.com/yschimke/compose-ai-tools/issues/3274)) ([37234b0](https://github.com/yschimke/compose-ai-tools/commit/37234b0eb02be02392ea5000cf338bc85242d5e6))
* **wear:** measure device-less Wear previews against the watch screen, don't pin it ([#3278](https://github.com/yschimke/compose-ai-tools/issues/3278)) ([6e23440](https://github.com/yschimke/compose-ai-tools/commit/6e23440a1b05b421d47f3e13b2daabf756a2430b))

## [0.19.29](https://github.com/yschimke/compose-ai-tools/compare/v0.19.28...v0.19.29) (2026-08-04)


### Bug Fixes

* **bundle:** keep sibling-module drawables out of the resource prune ([#3269](https://github.com/yschimke/compose-ai-tools/issues/3269)) ([ce30b50](https://github.com/yschimke/compose-ai-tools/commit/ce30b50794f2bce0da595e1f25bad584e0fe0d9e))
* **catalog:** improve theme presentation ([#3268](https://github.com/yschimke/compose-ai-tools/issues/3268)) ([4822114](https://github.com/yschimke/compose-ai-tools/commit/4822114107d5313d655ee3514319f64d2f9ad2b1))
* **serve:** close the daemon session that opens after its host was closed ([#3272](https://github.com/yschimke/compose-ai-tools/issues/3272)) ([5852b7f](https://github.com/yschimke/compose-ai-tools/commit/5852b7f2f3f4382ef24426c45be45fcc90250966))


### Performance Improvements

* **serve:** open a catalog's daemon on first use, not at registration ([#3270](https://github.com/yschimke/compose-ai-tools/issues/3270)) ([495d602](https://github.com/yschimke/compose-ai-tools/commit/495d602fa782ae84432536f3f1fee810252b86c1))

## [0.19.28](https://github.com/yschimke/compose-ai-tools/compare/v0.19.27...v0.19.28) (2026-08-04)


### Features

* **design-artifacts:** give an extra-module render its own live lane ([#3264](https://github.com/yschimke/compose-ai-tools/issues/3264)) ([1784e06](https://github.com/yschimke/compose-ai-tools/commit/1784e06ca8f3589ecef9e82d5d84081c0bcf0810))


### Bug Fixes

* export the resting corner of a wrapped Wear M3 button shape ([#3262](https://github.com/yschimke/compose-ai-tools/issues/3262)) ([74b8a9a](https://github.com/yschimke/compose-ai-tools/commit/74b8a9afa066f77fcc49e59238f4ed5286d385f9))
* **serve:** revalidate stable CMP Wasm assets ([#3260](https://github.com/yschimke/compose-ai-tools/issues/3260)) ([ba7b73e](https://github.com/yschimke/compose-ai-tools/commit/ba7b73e071e703132f268de5ca18c5e3f5d4ae3e))


### Performance Improvements

* **serve:** warm a catalog's daemon on arrival, not at boot ([#3258](https://github.com/yschimke/compose-ai-tools/issues/3258)) ([2602842](https://github.com/yschimke/compose-ai-tools/commit/26028429a9ac5e1c79506a1be9b094e6d5c89ef4))

## [0.19.27](https://github.com/yschimke/compose-ai-tools/compare/v0.19.26...v0.19.27) (2026-08-04)


### Features

* decode alpha16 modifier draw content ([#3245](https://github.com/yschimke/compose-ai-tools/issues/3245)) ([1098784](https://github.com/yschimke/compose-ai-tools/commit/1098784223f75c958a0b30ab2b944df69ec345c5))
* **design-artifacts:** publish reference-side annotations with the references ([#3252](https://github.com/yschimke/compose-ai-tools/issues/3252)) ([36878e6](https://github.com/yschimke/compose-ai-tools/commit/36878e6116310359952f21ca8305de135965a06b))


### Bug Fixes

* export structural SVG from RC-JVM ([#3253](https://github.com/yschimke/compose-ai-tools/issues/3253)) ([0b60edb](https://github.com/yschimke/compose-ai-tools/commit/0b60edb66d1f18da789e99c6774a6feb2c5726a9))


### Performance Improvements

* **serve:** render off-screen cards' themed pixels on scroll, not up front ([#3254](https://github.com/yschimke/compose-ai-tools/issues/3254)) ([135fa55](https://github.com/yschimke/compose-ai-tools/commit/135fa55ec2639939c7b7a77c05bbd220f65e66c7))

## [0.19.26](https://github.com/yschimke/compose-ai-tools/compare/v0.19.25...v0.19.26) (2026-08-04)


### Features

* implement alpha16 ComponentValue in CMP player ([#3244](https://github.com/yschimke/compose-ai-tools/issues/3244)) ([8031e81](https://github.com/yschimke/compose-ai-tools/commit/8031e81213ec839ddea071a13f506e07858bd1de))
* **serve:** keep a catalog's session alive while a visitor is reading it ([#3243](https://github.com/yschimke/compose-ai-tools/issues/3243)) ([a6946fa](https://github.com/yschimke/compose-ai-tools/commit/a6946faaf6c32c2f9a59062bd9e5bc1745a5058d))
* **serve:** typography and layout annotation layers on the compare page ([#3242](https://github.com/yschimke/compose-ai-tools/issues/3242)) ([1f674ec](https://github.com/yschimke/compose-ai-tools/commit/1f674eceab55e722c5174821492009738065013f))


### Bug Fixes

* **design-artifacts:** don't publish a light reference against a dark sticker ([#3238](https://github.com/yschimke/compose-ai-tools/issues/3238)) ([daf6800](https://github.com/yschimke/compose-ai-tools/commit/daf68000c846a8be9d8522896a2e1e9f62658c85))
* **serve:** count only real seat demand as a refusal ([#3235](https://github.com/yschimke/compose-ai-tools/issues/3235)) ([000df00](https://github.com/yschimke/compose-ai-tools/commit/000df007fe0b211844c5d997f356418616034147))
* **serve:** resolve Pocket Casts' eventhorizon from a8c-libs ([#3237](https://github.com/yschimke/compose-ai-tools/issues/3237)) ([74004f9](https://github.com/yschimke/compose-ai-tools/commit/74004f923e72323e3075d2a126063ca9c7bea96b))


### Performance Improvements

* **serve:** prebake catalog grid thumbnails ([#3240](https://github.com/yschimke/compose-ai-tools/issues/3240)) ([7a3ff5d](https://github.com/yschimke/compose-ai-tools/commit/7a3ff5dd3cb89bbcbb5df662585cca463ed5e047))

## [0.19.25](https://github.com/yschimke/compose-ai-tools/compare/v0.19.24...v0.19.25) (2026-08-03)


### Features

* **rc:** Add CMP Wasm Remote Compose player ([#3201](https://github.com/yschimke/compose-ai-tools/issues/3201)) ([9bb93d9](https://github.com/yschimke/compose-ai-tools/commit/9bb93d9e12c9d13565ba4b35f1342aace1f48006))
* **serve:** count live-seat refusals on status ([#3233](https://github.com/yschimke/compose-ai-tools/issues/3233)) ([3e2ad66](https://github.com/yschimke/compose-ai-tools/commit/3e2ad66f459c2349bbccc8b099ef71b2e2eb3c9c))
* **serve:** grant two theme-render bursts instead of one ([#3231](https://github.com/yschimke/compose-ai-tools/issues/3231)) ([8f55512](https://github.com/yschimke/compose-ai-tools/commit/8f55512bcfa97a26f3f8acac290d5b80f4077601))


### Bug Fixes

* **samples:** Wear device dp is per-density, not panel px / 2 ([#3228](https://github.com/yschimke/compose-ai-tools/issues/3228)) ([3da4d14](https://github.com/yschimke/compose-ai-tools/commit/3da4d14556ee393a455f1d4a77eb5f8a5e013525))


### Performance Improvements

* **serve:** route snapshot renders back to the shared daemon ([#3232](https://github.com/yschimke/compose-ai-tools/issues/3232)) ([39322d9](https://github.com/yschimke/compose-ai-tools/commit/39322d9529567ad29fee3654fcf5db4e5d494803))
* **serve:** serve baked pixels without entering render admission ([#3229](https://github.com/yschimke/compose-ai-tools/issues/3229)) ([8363d59](https://github.com/yschimke/compose-ai-tools/commit/8363d59d8daa5154f4b946ddd460e4104d165b24))

## [0.19.24](https://github.com/yschimke/compose-ai-tools/compare/v0.19.23...v0.19.24) (2026-08-03)


### Features

* **samples:** Wear device previews for xl_round, a custom spec device, and common large watches ([#3227](https://github.com/yschimke/compose-ai-tools/issues/3227)) ([dffd3bf](https://github.com/yschimke/compose-ai-tools/commit/dffd3bf6f381a96dd512d6af36faf18fe4b0e8ba))


### Performance Improvements

* **serve:** fill a catalog's baked vectors after it publishes ([#3226](https://github.com/yschimke/compose-ai-tools/issues/3226)) ([3dc387f](https://github.com/yschimke/compose-ai-tools/commit/3dc387f1e0ab0c22bbb6025bc475b665c4113d9b))
* **serve:** publish a catalog on its metadata, fetch its images on use ([#3224](https://github.com/yschimke/compose-ai-tools/issues/3224)) ([eecaa80](https://github.com/yschimke/compose-ai-tools/commit/eecaa80de335780cae994ddd3a5165199b851e38))

## [0.19.23](https://github.com/yschimke/compose-ai-tools/compare/v0.19.22...v0.19.23) (2026-08-03)


### Performance Improvements

* **serve:** fetch catalog assets concurrently and stop pre-rendering themes ([#3222](https://github.com/yschimke/compose-ai-tools/issues/3222)) ([d205488](https://github.com/yschimke/compose-ai-tools/commit/d20548885a2fa28d42fc8644bca9929c93bf1fa0))

## [0.19.22](https://github.com/yschimke/compose-ai-tools/compare/v0.19.21...v0.19.22) (2026-08-03)


### Features

* **serve:** paint a catalog's pages in its own design tokens ([#3208](https://github.com/yschimke/compose-ai-tools/issues/3208)) ([6c3634e](https://github.com/yschimke/compose-ai-tools/commit/6c3634eab190d8cfa19fdde615b2ad414682f8d9))


### Bug Fixes

* **serve:** park background theme optimization while catalogs load ([#3220](https://github.com/yschimke/compose-ai-tools/issues/3220)) ([6d84dbb](https://github.com/yschimke/compose-ai-tools/commit/6d84dbb12c6c44a746c111163fed48fc0630069e))

## [0.19.21](https://github.com/yschimke/compose-ai-tools/compare/v0.19.20...v0.19.21) (2026-08-03)


### Features

* **serve:** move home auth into header ([#3202](https://github.com/yschimke/compose-ai-tools/issues/3202)) ([01cff18](https://github.com/yschimke/compose-ai-tools/commit/01cff1829a106a2059a858f1c8d6f3bf434dbd6a))
* **serve:** publish design references from design-map.json ([#3203](https://github.com/yschimke/compose-ai-tools/issues/3203)) ([0cfa127](https://github.com/yschimke/compose-ai-tools/commit/0cfa127ccd95a7d84dffa42c85c7ab34d1e87583))


### Bug Fixes

* **bundle:** preserve semantics inactivity timeout ([#3199](https://github.com/yschimke/compose-ai-tools/issues/3199)) ([2f6cda9](https://github.com/yschimke/compose-ai-tools/commit/2f6cda9e64c4ebdad6ae3aa7890f07043a8dbb85))
* **serve:** align kotlinx-io for live bundles ([#3204](https://github.com/yschimke/compose-ai-tools/issues/3204)) ([a6f1cd7](https://github.com/yschimke/compose-ai-tools/commit/a6f1cd7ca56e2c707234b8350f196c302dcf1af5))
* **serve:** optimize catalog themes while idle ([#3206](https://github.com/yschimke/compose-ai-tools/issues/3206)) ([b090b1e](https://github.com/yschimke/compose-ai-tools/commit/b090b1edd9b74b3ca3102e6fb11b4d269de812b6))
* **serve:** scope kotlinx path matching ([#3207](https://github.com/yschimke/compose-ai-tools/issues/3207)) ([d8a5149](https://github.com/yschimke/compose-ai-tools/commit/d8a51497f9e08547fd601905951046b2dab78ff5))

## [0.19.20](https://github.com/yschimke/compose-ai-tools/compare/v0.19.19...v0.19.20) (2026-08-02)


### Features

* add native format comparisons ([#3183](https://github.com/yschimke/compose-ai-tools/issues/3183)) ([6ba5bd6](https://github.com/yschimke/compose-ai-tools/commit/6ba5bd68b7d0ab815a717f249f13d22b461b7c9d))
* **preview:** improve preview server layout ([#3191](https://github.com/yschimke/compose-ai-tools/issues/3191)) ([35a1df6](https://github.com/yschimke/compose-ai-tools/commit/35a1df68ecd09de1336bd13e5a80e53c53682db5))
* **preview:** use device sizes for app screens ([#3194](https://github.com/yschimke/compose-ai-tools/issues/3194)) ([4357f4d](https://github.com/yschimke/compose-ai-tools/commit/4357f4daeb13cf54f408594a86af9a3c5cd56c56))
* **serve:** add catalog refresh control ([#3178](https://github.com/yschimke/compose-ai-tools/issues/3178)) ([f17a802](https://github.com/yschimke/compose-ai-tools/commit/f17a802ecca726ae0b9efe31c9f0776717b3e426))
* **serve:** compare previews with design references ([#3189](https://github.com/yschimke/compose-ai-tools/issues/3189)) ([ca8ebc6](https://github.com/yschimke/compose-ai-tools/commit/ca8ebc6ef914985c3905990376cdc588915501fc))
* **serve:** support tall PNG preview exports ([#3190](https://github.com/yschimke/compose-ai-tools/issues/3190)) ([5839d11](https://github.com/yschimke/compose-ai-tools/commit/5839d1102be08965f41f234924271ce977ec6599))


### Bug Fixes

* **bundle:** honor semantics render timeout ([#3198](https://github.com/yschimke/compose-ai-tools/issues/3198)) ([291a094](https://github.com/yschimke/compose-ai-tools/commit/291a0949182d7c4d8e6c99e523a37b9e48320baf))
* **figma-svg:** keep a clipped node's fill at its painted width ([#3181](https://github.com/yschimke/compose-ai-tools/issues/3181)) ([d028c8f](https://github.com/yschimke/compose-ai-tools/commit/d028c8fd8c876e9dcb576fb321881968633dd043))
* keep catalog theme in preview viewer ([#3193](https://github.com/yschimke/compose-ai-tools/issues/3193)) ([fb7e4c0](https://github.com/yschimke/compose-ai-tools/commit/fb7e4c0d8b30ce1ca917e20f774b7cb2d0834317))
* scope comparison deep-link aliases ([#3186](https://github.com/yschimke/compose-ai-tools/issues/3186)) ([14e98ba](https://github.com/yschimke/compose-ai-tools/commit/14e98ba0a0a6fa219621ae2c7b7b2435856b3607))
* **serve:** complete design reference support ([#3195](https://github.com/yschimke/compose-ai-tools/issues/3195)) ([30f0ad7](https://github.com/yschimke/compose-ai-tools/commit/30f0ad7a6536df0eb97956630050c5b5125baadd))
* **serve:** enable IR-backed live lanes ([#3192](https://github.com/yschimke/compose-ai-tools/issues/3192)) ([7e3826f](https://github.com/yschimke/compose-ai-tools/commit/7e3826ff98f32b17504ff8e5be5ef3f5dbcb0411))
* **serve:** lease and cache catalog theme renders ([#3187](https://github.com/yschimke/compose-ai-tools/issues/3187)) ([563c0c9](https://github.com/yschimke/compose-ai-tools/commit/563c0c920b0f449e7bf71060133aef8b4bd966b3))
* **serve:** parallelize catalog theme renders ([#3185](https://github.com/yschimke/compose-ai-tools/issues/3185)) ([7bb4f4a](https://github.com/yschimke/compose-ai-tools/commit/7bb4f4aefb0a25ec588c6048e41f9950bbeb63a2))
* stabilize format comparison scoring ([#3184](https://github.com/yschimke/compose-ai-tools/issues/3184)) ([f2337c8](https://github.com/yschimke/compose-ai-tools/commit/f2337c80abddcc3810bc8aba4fd27b44ccb1fe56))
* **svg:** preserve text fidelity ([#3188](https://github.com/yschimke/compose-ai-tools/issues/3188)) ([6dcff6e](https://github.com/yschimke/compose-ai-tools/commit/6dcff6e9335f8948fb9f322b0beb99d417b43a8a))

## [0.19.19](https://github.com/yschimke/compose-ai-tools/compare/v0.19.18...v0.19.19) (2026-08-02)


### Features

* **serve:** show loading state while previews rerender ([#3161](https://github.com/yschimke/compose-ai-tools/issues/3161)) ([8c079ce](https://github.com/yschimke/compose-ai-tools/commit/8c079cef81965ca7ca47d7f7195b4eed6afaaaf3))


### Bug Fixes

* **catalog:** avoid refolding same-function axes ([#3164](https://github.com/yschimke/compose-ai-tools/issues/3164)) ([43f1956](https://github.com/yschimke/compose-ai-tools/commit/43f1956e814bbfc70a8220ef758a89e6a6b94768))
* **catalog:** default Wear device breakpoints ([#3172](https://github.com/yschimke/compose-ai-tools/issues/3172)) ([d8c9bb1](https://github.com/yschimke/compose-ai-tools/commit/d8c9bb1f73f4d7b971b0d7e9d51ccc70cb5895e8))
* **ci:** shard VS Code extension e2e ([#3174](https://github.com/yschimke/compose-ai-tools/issues/3174)) ([9dd991e](https://github.com/yschimke/compose-ai-tools/commit/9dd991ebb63dd97ffe2cf7b03f66d668e5f56691))
* **design-artifacts:** preserve lazy SVG blobs ([#3176](https://github.com/yschimke/compose-ai-tools/issues/3176)) ([5003c80](https://github.com/yschimke/compose-ai-tools/commit/5003c803dc235ab49140515e872336970dc8ddb2))
* **figma-svg:** clip a lookahead scroll container to its rendered viewport ([#3180](https://github.com/yschimke/compose-ai-tools/issues/3180)) ([a6f974a](https://github.com/yschimke/compose-ai-tools/commit/a6f974a3426880f390cbb14ede791e86fe71c136))
* **figma-svg:** remove orphaned raster references ([#3167](https://github.com/yschimke/compose-ai-tools/issues/3167)) ([75930eb](https://github.com/yschimke/compose-ai-tools/commit/75930eb2bc3e500634e7d4fa6ca0149bf94295c2))
* preserve Wear TimePicker SVG fidelity ([#3163](https://github.com/yschimke/compose-ai-tools/issues/3163)) ([910d2a8](https://github.com/yschimke/compose-ai-tools/commit/910d2a8c90921b78f94b7ab01578d2c4edfa4c1c))
* **rc-embedded:** match rounded clip density ([#3162](https://github.com/yschimke/compose-ai-tools/issues/3162)) ([f8a2d6c](https://github.com/yschimke/compose-ai-tools/commit/f8a2d6c597c69c220fa5f4385315bbcb0efcaccc))
* **renderer:** stabilize View-backed animated previews ([#3170](https://github.com/yschimke/compose-ai-tools/issues/3170)) ([205c8b6](https://github.com/yschimke/compose-ai-tools/commit/205c8b692153422e59fa4b3ffbc904ebbb1ff167))
* **serve:** clear cancelled render status ([#3171](https://github.com/yschimke/compose-ai-tools/issues/3171)) ([70f206b](https://github.com/yschimke/compose-ai-tools/commit/70f206bf9b77371b43e71c94f5fdabfa7aec8bd0))
* **serve:** explain disabled playground on preview hosts ([#3166](https://github.com/yschimke/compose-ai-tools/issues/3166)) ([3527605](https://github.com/yschimke/compose-ai-tools/commit/3527605093f2c558d30052dcfd8f2f7a8d108ea1))
* **serve:** prioritize visible theme renders ([#3165](https://github.com/yschimke/compose-ai-tools/issues/3165)) ([40a9b84](https://github.com/yschimke/compose-ai-tools/commit/40a9b84b3d914ad238122f98c5192a9d967c04fb))
* **serve:** require repo access for playground ([#3173](https://github.com/yschimke/compose-ai-tools/issues/3173)) ([415153c](https://github.com/yschimke/compose-ai-tools/commit/415153c3521e05062c9c0bc301cdce313ad092e5))
* **theme:** resolve bundle compose resources ([#3169](https://github.com/yschimke/compose-ai-tools/issues/3169)) ([bf200cd](https://github.com/yschimke/compose-ai-tools/commit/bf200cdb977ed30e39f65e735771f579d33a9a84))

## [0.19.18](https://github.com/yschimke/compose-ai-tools/compare/v0.19.17...v0.19.18) (2026-08-02)


### Bug Fixes

* **catalog:** pair a breakpoint sticker with its own render ([#2883](https://github.com/yschimke/compose-ai-tools/issues/2883)) ([#3155](https://github.com/yschimke/compose-ai-tools/issues/3155)) ([9cf3adc](https://github.com/yschimke/compose-ai-tools/commit/9cf3adc607dcae1a172aed76979981b5b74b976f))
* **ci:** pin the JetStream XR consumer patch to our androidx.xr.compose ([#3152](https://github.com/yschimke/compose-ai-tools/issues/3152)) ([24634b9](https://github.com/yschimke/compose-ai-tools/commit/24634b92fd851aea28f7d5b1d782d9f77bf96788))
* **daemon:** resolve a @Preview device to its own frame ([#2615](https://github.com/yschimke/compose-ai-tools/issues/2615)) ([#3151](https://github.com/yschimke/compose-ai-tools/issues/3151)) ([4a7430c](https://github.com/yschimke/compose-ai-tools/commit/4a7430c860dd39f676bcfeeb244a69128e0c39ea))
* **daemon:** size a device frame from the catalog on the live lane too ([#3153](https://github.com/yschimke/compose-ai-tools/issues/3153)) ([98003e1](https://github.com/yschimke/compose-ai-tools/commit/98003e15315bd8449da5a39cbe47c787b6044f2f))
* **serve:** always register the /wasm/ route ([#3150](https://github.com/yschimke/compose-ai-tools/issues/3150)) ([c131f95](https://github.com/yschimke/compose-ai-tools/commit/c131f956d7e8b18ae5c495195b8e860aa02be41c))

## [0.19.17](https://github.com/yschimke/compose-ai-tools/compare/v0.19.16...v0.19.17) (2026-08-01)


### Features

* **serve:** persist preview engagement ([#3136](https://github.com/yschimke/compose-ai-tools/issues/3136)) ([0c93552](https://github.com/yschimke/compose-ai-tools/commit/0c9355258f549468b06a99764de90efdbe0d2dea))


### Bug Fixes

* **catalog:** preserve Wear font-scale preview axes ([#3145](https://github.com/yschimke/compose-ai-tools/issues/3145)) ([1f0f751](https://github.com/yschimke/compose-ai-tools/commit/1f0f751f940e225bbc667ef3c9e5f01ec8afa218))
* **ci:** protect release publish runs from coalescing ([#3139](https://github.com/yschimke/compose-ai-tools/issues/3139)) ([dd2c559](https://github.com/yschimke/compose-ai-tools/commit/dd2c5595af1e7786f423e2cbfce6646acdc851ed))
* **serve:** show GitHub login on preview home ([#3140](https://github.com/yschimke/compose-ai-tools/issues/3140)) ([43010c9](https://github.com/yschimke/compose-ai-tools/commit/43010c9e0a3abd8479eb807675e8be5c9228a544))

## [0.19.16](https://github.com/yschimke/compose-ai-tools/compare/v0.19.15...v0.19.16) (2026-08-01)


### Features

* **serve:** persist preview engagement ([#3136](https://github.com/yschimke/compose-ai-tools/issues/3136)) ([0c93552](https://github.com/yschimke/compose-ai-tools/commit/0c9355258f549468b06a99764de90efdbe0d2dea))


### Bug Fixes

* **ci:** protect release publish runs from coalescing ([#3139](https://github.com/yschimke/compose-ai-tools/issues/3139)) ([dd2c559](https://github.com/yschimke/compose-ai-tools/commit/dd2c5595af1e7786f423e2cbfce6646acdc851ed))
* **deps:** update gradle minor/patch ([#3134](https://github.com/yschimke/compose-ai-tools/issues/3134)) ([1217032](https://github.com/yschimke/compose-ai-tools/commit/12170320bc405b65fece92a48b54880ba2977b8c))
* **figma-svg:** clip lookahead scrolls to rendered viewport ([#3137](https://github.com/yschimke/compose-ai-tools/issues/3137)) ([8fdaee5](https://github.com/yschimke/compose-ai-tools/commit/8fdaee56bcb3a2aabbfdef5b0dfe5c89a2f41d0c))
* **figma-svg:** support Compose 1.9 applied alpha ([#3132](https://github.com/yschimke/compose-ai-tools/issues/3132)) ([0ccd709](https://github.com/yschimke/compose-ai-tools/commit/0ccd709a6710b39710dbaaaed0e353e8c446aaaf))

## [0.19.15](https://github.com/yschimke/compose-ai-tools/compare/v0.19.14...v0.19.15) (2026-08-01)


### Features

* add accessibility preview permutations ([#3126](https://github.com/yschimke/compose-ai-tools/issues/3126)) ([1a7cabd](https://github.com/yschimke/compose-ai-tools/commit/1a7cabdf3afa95a781251295098f325de5683323))
* **bundle:** Test bundle upload end to end ([#3120](https://github.com/yschimke/compose-ai-tools/issues/3120)) ([00cc3d8](https://github.com/yschimke/compose-ai-tools/commit/00cc3d80ef806242388dae526673681eb36af48d))


### Bug Fixes

* **android-renderer:** consolidate dialog frame capture ([#3125](https://github.com/yschimke/compose-ai-tools/issues/3125)) ([edc11c8](https://github.com/yschimke/compose-ai-tools/commit/edc11c87b0d7fc2130a8193109c29dccc0880129))
* **cli:** add GitHub auth for preview server ([#3121](https://github.com/yschimke/compose-ai-tools/issues/3121)) ([93f4329](https://github.com/yschimke/compose-ai-tools/commit/93f432991c09c4afd7c8806a3ba0f8afe6a7f401))
* **cli:** make preview render orchestration reliable ([#3107](https://github.com/yschimke/compose-ai-tools/issues/3107)) ([b962e2f](https://github.com/yschimke/compose-ai-tools/commit/b962e2f04e0f133619e049a4a49d03d1b7794b7f))
* **coil:** fall back to request placeholders ([#3109](https://github.com/yschimke/compose-ai-tools/issues/3109)) ([5453405](https://github.com/yschimke/compose-ai-tools/commit/5453405dac139e3442e0436b7a44e07323bcbc80))
* **daemon:** preserve wrapped resource fallbacks ([#3119](https://github.com/yschimke/compose-ai-tools/issues/3119)) ([58113c4](https://github.com/yschimke/compose-ai-tools/commit/58113c4a1b54fd4b78142448501f7b868f1af3d9))
* **figma-svg:** isolate preview variant artifacts ([#3108](https://github.com/yschimke/compose-ai-tools/issues/3108)) ([55664e7](https://github.com/yschimke/compose-ai-tools/commit/55664e77c6650dacc0a06e703a30fb43ea3d33d0))
* **figma-svg:** preserve applied Wear graphics-layer alpha ([#3130](https://github.com/yschimke/compose-ai-tools/issues/3130)) ([2cf7891](https://github.com/yschimke/compose-ai-tools/commit/2cf7891aa3c46d98d065c97a14d46905008fbf17))
* **layout-inspector:** repair capture regressions ([#3110](https://github.com/yschimke/compose-ai-tools/issues/3110)) ([97f9853](https://github.com/yschimke/compose-ai-tools/commit/97f9853eb4e8e90caa8cc0ae039aaf70c4eaa765))
* match studio fixed preview geometry ([#3113](https://github.com/yschimke/compose-ai-tools/issues/3113)) ([afaa189](https://github.com/yschimke/compose-ai-tools/commit/afaa1899a96745d43c707f1abe9db1a92587df46))
* **serve:** bind before loading catalogs ([#3127](https://github.com/yschimke/compose-ai-tools/issues/3127)) ([23bb808](https://github.com/yschimke/compose-ai-tools/commit/23bb808ec1e9d13397f051b9d9b85ba4edffb817))

## [0.19.14](https://github.com/yschimke/compose-ai-tools/compare/v0.19.13...v0.19.14) (2026-08-01)


### Features

* **daemon-desktop:** resolve @PreviewParameter previews on the desktop daemon ([#3069](https://github.com/yschimke/compose-ai-tools/issues/3069)) ([ae59646](https://github.com/yschimke/compose-ai-tools/commit/ae5964664c55d28862313ddcd8d8a6239e6842fe))
* **daemon:** give each sandbox its own process so the pool can boot ([#3091](https://github.com/yschimke/compose-ai-tools/issues/3091)) ([e1e5035](https://github.com/yschimke/compose-ai-tools/commit/e1e503534594c43a84d593bb00bf0b05645436bd))
* **discovery:** warn when a preview installs a theme under declared theme catalogs ([#3068](https://github.com/yschimke/compose-ai-tools/issues/3068)) ([f1b430a](https://github.com/yschimke/compose-ai-tools/commit/f1b430a23ba8c98360dbf8f7ae441b15074870b8))
* **serve:** multi-file playground snippets, and settle the design doc's open questions ([#3102](https://github.com/yschimke/compose-ai-tools/issues/3102)) ([5806094](https://github.com/yschimke/compose-ai-tools/commit/5806094d745c2e5ae7ca7f82c4ba03b22a66c668))
* **serve:** run the playground compile inside the sandbox too ([#3105](https://github.com/yschimke/compose-ai-tools/issues/3105)) ([3c022df](https://github.com/yschimke/compose-ai-tools/commit/3c022df08f9e3377f579aac3bb19c6e622fc6cd7))
* **serve:** sandbox playground sessions and gate --public on a containment probe ([#3089](https://github.com/yschimke/compose-ai-tools/issues/3089)) ([e0af114](https://github.com/yschimke/compose-ai-tools/commit/e0af114072c802cf7552055868a680d6379d3348))


### Bug Fixes

* **daemon-tests:** assert the gradient export contract the exporter actually implements ([#3103](https://github.com/yschimke/compose-ai-tools/issues/3103)) ([57434f4](https://github.com/yschimke/compose-ai-tools/commit/57434f4772fceca1db9590d73aaa02eafe3866d2))
* **daemon-tests:** stop a test `fonts/` resource shadowing Robolectric's system fonts ([#3094](https://github.com/yschimke/compose-ai-tools/issues/3094)) ([5009aa1](https://github.com/yschimke/compose-ai-tools/commit/5009aa1e7617bbdf3d0b819cb8368e564f4035a1))
* **daemon:** require an explicit onExit so a forgotten override can't kill the test JVM ([#3104](https://github.com/yschimke/compose-ai-tools/issues/3104)) ([2f71a4c](https://github.com/yschimke/compose-ai-tools/commit/2f71a4c942a904954e380f5b71fd8e4ee73c7c93))
* **daemon:** restore the capture overload the forensic dump looks up reflectively ([#3071](https://github.com/yschimke/compose-ai-tools/issues/3071)) ([d82d03a](https://github.com/yschimke/compose-ai-tools/commit/d82d03ad49b8420f01b0038bc297f3b6f18dc109))
* **daemon:** stop encodeRenderPayload dropping 8 PreviewOverrides fields ([#3088](https://github.com/yschimke/compose-ai-tools/issues/3088)) ([a30b0f9](https://github.com/yschimke/compose-ai-tools/commit/a30b0f9431e5f157dcacebad4a067cdba4a8ee39))
* **daemon:** stop the Robolectric test JVM aborting mid-suite ([#3079](https://github.com/yschimke/compose-ai-tools/issues/3079)) ([a1df148](https://github.com/yschimke/compose-ai-tools/commit/a1df1483b841a2c90b1859157e3e601d213d7aba))
* **deps:** update gradle minor/patch ([#3101](https://github.com/yschimke/compose-ai-tools/issues/3101)) ([d53b097](https://github.com/yschimke/compose-ai-tools/commit/d53b0972c8a8e1cf1bc7f9f3945a05405406c3e5))
* **renderer-android:** frame dialog previews in the standalone renderer too ([#3084](https://github.com/yschimke/compose-ai-tools/issues/3084)) ([72facbd](https://github.com/yschimke/compose-ai-tools/commit/72facbde548dba8e087e91390dd0f8f5a8051af9))
* **serve:** bound concurrent playground compiles, and clamp them to the sandbox TTL ([#3106](https://github.com/yschimke/compose-ai-tools/issues/3106)) ([ad0ba4f](https://github.com/yschimke/compose-ai-tools/commit/ad0ba4fb322263dece817249548796deeb4bbe2b))

## [0.19.13](https://github.com/yschimke/compose-ai-tools/compare/v0.19.12...v0.19.13) (2026-07-31)


### Features

* **doctor:** flag unresolvable skiko native deps on CMP Desktop projects ([#3051](https://github.com/yschimke/compose-ai-tools/issues/3051)) ([ac7b0f0](https://github.com/yschimke/compose-ai-tools/commit/ac7b0f0d71393a094e6f987a84500a07c0834fa0))
* **playground:** add the Stage-1 editor page at GET /playground ([#3050](https://github.com/yschimke/compose-ai-tools/issues/3050)) ([8647ef0](https://github.com/yschimke/compose-ai-tools/commit/8647ef053219fc779a1eaf8c9348aa1fd0918106))
* **playground:** render the CMP first frame (desktop parity with Android) ([#3060](https://github.com/yschimke/compose-ai-tools/issues/3060)) ([e26e24a](https://github.com/yschimke/compose-ai-tools/commit/e26e24a74c34b90adff082fc56bed1a706013785))
* **playground:** Stage-2 live redemption at /pg/&lt;token&gt; ([#3054](https://github.com/yschimke/compose-ai-tools/issues/3054)) ([f1d1cee](https://github.com/yschimke/compose-ai-tools/commit/f1d1ceefd70eef4704b86cb1e1fbce98a53fa4d4))


### Bug Fixes

* **daemon:** capture the dialog window for Dialog / bottom-sheet previews ([#3067](https://github.com/yschimke/compose-ai-tools/issues/3067)) ([16e4e13](https://github.com/yschimke/compose-ai-tools/commit/16e4e130a6a0122d09f7468d8be2cedbd8a092e7))
* **figma-svg:** clip to the rendered box and keep text on clip-edge nodes ([#3065](https://github.com/yschimke/compose-ai-tools/issues/3065)) ([6850e73](https://github.com/yschimke/compose-ai-tools/commit/6850e738dec284597e5463fb22af862b246409ae))
* **playground:** gate the editor's mode selector and fence stale runs ([#3052](https://github.com/yschimke/compose-ai-tools/issues/3052)) ([8377eba](https://github.com/yschimke/compose-ai-tools/commit/8377ebaa8e43c0cd5b2476cc7719a9f6c5d563db))
* **playground:** redeem /pg/ on token-gated hosts, with a browser e2e ([#3066](https://github.com/yschimke/compose-ai-tools/issues/3066)) ([cc520d3](https://github.com/yschimke/compose-ai-tools/commit/cc520d303be707949515f2d8554d7f2e4dd8e3ce))
* survive a non-UTF-8 locale, and report show results from a failed render ([#3059](https://github.com/yschimke/compose-ai-tools/issues/3059)) ([b128120](https://github.com/yschimke/compose-ai-tools/commit/b128120f12938c10bdee540fe17f21616c3d199e))

## [0.19.12](https://github.com/yschimke/compose-ai-tools/compare/v0.19.11...v0.19.12) (2026-07-31)


### Features

* **playground:** render remote-compose snippets on the Android daemon → /d/&lt;id&gt; ([#3040](https://github.com/yschimke/compose-ai-tools/issues/3040)) ([9277a17](https://github.com/yschimke/compose-ai-tools/commit/9277a1772607193e9367c56b72df4d1083df2af0))
* **playground:** render the Android first frame for ANDROID-mode snippets ([#3044](https://github.com/yschimke/compose-ai-tools/issues/3044)) ([e3e78d6](https://github.com/yschimke/compose-ai-tools/commit/e3e78d60dcdfa7cbf2bcd7e6736ac313faaea7fb))
* **rc-compare:** add opt-in cmp-jvm desktop-player lane ([#3038](https://github.com/yschimke/compose-ai-tools/issues/3038)) ([315a909](https://github.com/yschimke/compose-ai-tools/commit/315a909a018abb9a26be7a2f6c37f5f810e8bcc4))
* **serve:** apply rc.* knob seeds in the cmp-jvm render ([#3035](https://github.com/yschimke/compose-ai-tools/issues/3035)) ([68cb596](https://github.com/yschimke/compose-ai-tools/commit/68cb59626e75279f72a2aaf2091b38d10f25c68e))
* **serve:** link each preview to its source file on GitHub ([#3033](https://github.com/yschimke/compose-ai-tools/issues/3033)) ([792b150](https://github.com/yschimke/compose-ai-tools/commit/792b15061146ada36745c6a52338db88c717ccc6))


### Bug Fixes

* **daemon:** resolve @PreviewParameter previews instead of failing on the parameterless lookup ([#3041](https://github.com/yschimke/compose-ai-tools/issues/3041)) ([5b28981](https://github.com/yschimke/compose-ai-tools/commit/5b289815c5735fba43f43b419125894d05f0c7b0))
* **figma-svg:** evaluate a graphics-layer block against the node's real box ([#2615](https://github.com/yschimke/compose-ai-tools/issues/2615)) ([#3046](https://github.com/yschimke/compose-ai-tools/issues/3046)) ([833cc94](https://github.com/yschimke/compose-ai-tools/commit/833cc942759832ecf7587225b62d0e47c78af815))
* **figma-svg:** export text at the size the render resolved, not sp × density × fontScale ([#3045](https://github.com/yschimke/compose-ai-tools/issues/3045)) ([d2189b5](https://github.com/yschimke/compose-ai-tools/commit/d2189b52abacdd4692f2775204115d0d4f5d24f9))
* **figma-svg:** fit a padded icon's vector to the box its painter fills ([#2853](https://github.com/yschimke/compose-ai-tools/issues/2853)) ([#3042](https://github.com/yschimke/compose-ai-tools/issues/3042)) ([e747a39](https://github.com/yschimke/compose-ai-tools/commit/e747a394a280e55247c9710e9afadabbaa7cc351))
* **figma-svg:** size the canvas from the rendered frame, not off-screen items ([#2853](https://github.com/yschimke/compose-ai-tools/issues/2853)) ([#3043](https://github.com/yschimke/compose-ai-tools/issues/3043)) ([c72dc6c](https://github.com/yschimke/compose-ai-tools/commit/c72dc6c983f4320db731d0b8db72b46ec950de63))
* **rc-embedded:** draw component chrome once, not twice ([#3037](https://github.com/yschimke/compose-ai-tools/issues/3037)) ([a53cd46](https://github.com/yschimke/compose-ai-tools/commit/a53cd46a10aef835b6eef307e1a98b676124bf32))

## [0.19.11](https://github.com/yschimke/compose-ai-tools/compare/v0.19.10...v0.19.11) (2026-07-30)


### Features

* **playground:** remote-compose data path — capture → /d/&lt;id&gt; permalink ([#3028](https://github.com/yschimke/compose-ai-tools/issues/3028)) ([bacd0f5](https://github.com/yschimke/compose-ai-tools/commit/bacd0f5ef4f3cb6f30d1963819808873421fd2c5))
* **remotecompose:** surface the captured .rc document as a fetchable data product ([#3034](https://github.com/yschimke/compose-ai-tools/issues/3034)) ([29c85ba](https://github.com/yschimke/compose-ai-tools/commit/29c85ba6f7b41a815dba6fd2e6f02cc09def43a5))
* **serve:** light up the cmp-jvm Remote Compose chip via an isolated desktop subprocess ([#3029](https://github.com/yschimke/compose-ai-tools/issues/3029)) ([0dd3447](https://github.com/yschimke/compose-ai-tools/commit/0dd3447d0f17cb1a30854e7ae3fb1c4aa37caac6))


### Bug Fixes

* **rc-embedded:** density-scale literal clip-corner radii ([#3032](https://github.com/yschimke/compose-ai-tools/issues/3032)) ([0463874](https://github.com/yschimke/compose-ai-tools/commit/04638741675964febbb5a8f5aba3cfe48b4817e4))

## [0.19.10](https://github.com/yschimke/compose-ai-tools/compare/v0.19.9...v0.19.10) (2026-07-30)


### Features

* **design-artifacts:** add a ref input to the reusable publish workflow ([#3022](https://github.com/yschimke/compose-ai-tools/issues/3022)) ([f189142](https://github.com/yschimke/compose-ai-tools/commit/f1891423711e898c639cb81b5c3800a7954e8ff2))
* **rc-embedded-jvm:** render Remote Compose documents to PNG on the desktop JVM ([#3025](https://github.com/yschimke/compose-ai-tools/issues/3025)) ([874eb17](https://github.com/yschimke/compose-ai-tools/commit/874eb172e50f13273a7278212440bd0aa1c9d5d9))
* **vscode:** navigate previews to their component source on title click ([#3018](https://github.com/yschimke/compose-ai-tools/issues/3018)) ([34e821a](https://github.com/yschimke/compose-ai-tools/commit/34e821a00d71ff3f0b8d45531043ca33c75344da))


### Bug Fixes

* **cli:** make bundle pack --with-semantics deadline an inactivity window ([#3020](https://github.com/yschimke/compose-ai-tools/issues/3020)) ([9e1eacd](https://github.com/yschimke/compose-ai-tools/commit/9e1eacd5041652b205c542f2a924233dc536d018))
* **design-artifacts:** reject an all-deferred catalog instead of rendering everything ([#3013](https://github.com/yschimke/compose-ai-tools/issues/3013)) ([c2fc252](https://github.com/yschimke/compose-ai-tools/commit/c2fc252f9ba9cb6c95c2488881ac6a141c48bf0a))
* **figma-svg:** clip children to a Modifier.clip shape ([#2852](https://github.com/yschimke/compose-ai-tools/issues/2852)) ([#3023](https://github.com/yschimke/compose-ai-tools/issues/3023)) ([ec66c29](https://github.com/yschimke/compose-ai-tools/commit/ec66c292c34505b67485b17a3c344c1f3befdf61))
* **rc-embedded:** resolve a gradient's bound colour-id stop in the embedded player ([#3019](https://github.com/yschimke/compose-ai-tools/issues/3019)) ([28f4def](https://github.com/yschimke/compose-ai-tools/commit/28f4defb4d3afbd708a6e2d5759b10a8ef59538c))

## [0.19.9](https://github.com/yschimke/compose-ai-tools/compare/v0.19.8...v0.19.9) (2026-07-30)


### Features

* **design-artifacts:** act on entry-level render priority now the serve host routes it ([#2987](https://github.com/yschimke/compose-ai-tools/issues/2987)) ([7c84f58](https://github.com/yschimke/compose-ai-tools/commit/7c84f5802080ba4eae2418c39c4139475683e716))
* **design-artifacts:** drive the per-preview-id filter from modePriority end to end ([#2980](https://github.com/yschimke/compose-ai-tools/issues/2980)) ([a51d18d](https://github.com/yschimke/compose-ai-tools/commit/a51d18dab5fcbcfd979b91b5cb0008fc4653dbee))
* **plugin:** forward preview filters to the Android/Robolectric render path ([#2994](https://github.com/yschimke/compose-ai-tools/issues/2994)) ([e77598d](https://github.com/yschimke/compose-ai-tools/commit/e77598d2c4dd5a2be132d6e2d5c612cc4ec54b20))
* **plugin:** honour @PreviewParameter row exclusions on the Robolectric render too ([#3001](https://github.com/yschimke/compose-ai-tools/issues/3001)) ([f3e605f](https://github.com/yschimke/compose-ai-tools/commit/f3e605f483abdca18e752dc1940899f460c71e8c))
* **plugin:** skip @PreviewParameter rows by label so a provider-supplied mode axis can defer ([#2992](https://github.com/yschimke/compose-ai-tools/issues/2992)) ([b730c12](https://github.com/yschimke/compose-ai-tools/commit/b730c12159943abb6758671a460d74d59e2ce752))
* **rc-embedded-jvm:** add JvmRemoteContext with a skiko bitmap decode ([#3012](https://github.com/yschimke/compose-ai-tools/issues/3012)) ([98b3838](https://github.com/yschimke/compose-ai-tools/commit/98b38381ae73b1a6e5e1dda5e1eb49dfba9d6add))
* **render:** let a module name the Android theme previews are hosted under ([#2995](https://github.com/yschimke/compose-ai-tools/issues/2995)) ([6358882](https://github.com/yschimke/compose-ai-tools/commit/63588820c65339c1f66821d57619465aa6beb0d1))
* **serve:** per-preview Remote Compose backend selector (js / java / cmp-android / cmp-jvm) ([#2999](https://github.com/yschimke/compose-ai-tools/issues/2999)) ([73506dd](https://github.com/yschimke/compose-ai-tools/commit/73506ddc5d53efef8403c14e637975bd5f30cc66))
* **serve:** playground preview-token store and REST DTOs ([#2989](https://github.com/yschimke/compose-ai-tools/issues/2989)) ([f627a2b](https://github.com/yschimke/compose-ai-tools/commit/f627a2b8171e42ef1ed93f01c1849f6983565f9d))
* **serve:** project playground diagnostics into the stock kotlin-playground errors map ([#3000](https://github.com/yschimke/compose-ai-tools/issues/3000)) ([ec77840](https://github.com/yschimke/compose-ai-tools/commit/ec778407d058a19003ccff17eb66bd1cb44b32b1))
* **serve:** resolve a catalog liveBundle into a playground compile classpath ([#3008](https://github.com/yschimke/compose-ai-tools/issues/3008)) ([b410a65](https://github.com/yschimke/compose-ai-tools/commit/b410a650fb4813e0f1de44de9b627b3d3bf8643e))
* **serve:** Stage-1 playground compile orchestrator ([#3004](https://github.com/yschimke/compose-ai-tools/issues/3004)) ([d86d19c](https://github.com/yschimke/compose-ai-tools/commit/d86d19c62172958b9dbe651db55ffe411d683f5f))
* **serve:** the playground — compile a snippet against a catalog's components ([#3010](https://github.com/yschimke/compose-ai-tools/issues/3010)) ([f56ac70](https://github.com/yschimke/compose-ai-tools/commit/f56ac70712b9d01c12faab8f2575894f3f05e154))


### Bug Fixes

* **cli:** keep a build failure's reason when it isn't a decorated Gradle cause ([#3003](https://github.com/yschimke/compose-ai-tools/issues/3003)) ([902c220](https://github.com/yschimke/compose-ai-tools/commit/902c220f8c2ab3d3d27a4ffa99fa199ccb6a80fe))
* **daemon:** degrade missing drawables read via getDrawableForDensity to a placeholder ([#2990](https://github.com/yschimke/compose-ai-tools/issues/2990)) ([6d9d700](https://github.com/yschimke/compose-ai-tools/commit/6d9d700f64c5bc7f5773c45bcea485b6bedb939d))
* **deploy:** don't abandon the config reconcile when one admin route is missing ([#2983](https://github.com/yschimke/compose-ai-tools/issues/2983)) ([9abc027](https://github.com/yschimke/compose-ai-tools/commit/9abc0272f2c9b354a28e4c84f19c928d9dbdf143))
* **design-artifacts:** defer a component's variants with it, so a mixed entry can't drop coverage ([#2991](https://github.com/yschimke/compose-ai-tools/issues/2991)) ([87ee1d2](https://github.com/yschimke/compose-ai-tools/commit/87ee1d24a82e8540fe37ef943e4b1db780ecc1ef))
* **figma-svg:** embed downloadable GoogleFont faces resolved at a non-default weight ([#2996](https://github.com/yschimke/compose-ai-tools/issues/2996)) ([c59bfad](https://github.com/yschimke/compose-ai-tools/commit/c59bfad8488a8e126c1787d2a2e6719822df31e6))
* **figma-svg:** fill showBackground across the full crop, not the thinnest child ([#2998](https://github.com/yschimke/compose-ai-tools/issues/2998)) ([32167d4](https://github.com/yschimke/compose-ai-tools/commit/32167d4512814f0bd42508bd7c33cde08b389678))
* **figma-svg:** fit a scaled vector to its drawn bounds and drop a raster under live text ([#3006](https://github.com/yschimke/compose-ai-tools/issues/3006)) ([920ac84](https://github.com/yschimke/compose-ai-tools/commit/920ac8427ad45e5bb7d147eddd15dc667c653545))
* **figma-svg:** keep a padded control's gradient ring on the inner box ([#2852](https://github.com/yschimke/compose-ai-tools/issues/2852)) ([#2997](https://github.com/yschimke/compose-ai-tools/issues/2997)) ([372fa9e](https://github.com/yschimke/compose-ai-tools/commit/372fa9ee16537bbbd0e86bf888f48754ebe1f76d))
* **plugin:** scope the render duplicate-guard coordinate map to each task's own classpath ([#3002](https://github.com/yschimke/compose-ai-tools/issues/3002)) ([eb2e4d6](https://github.com/yschimke/compose-ai-tools/commit/eb2e4d6df6cb94e95c68fa0a4aeeea0d9d249568))
* **plugin:** stop the XR render's cacheIf capturing Project ([#3007](https://github.com/yschimke/compose-ai-tools/issues/3007)) ([2c7459d](https://github.com/yschimke/compose-ai-tools/commit/2c7459deba5afe6f807b5a423d2ccd825acd9628))
* **preview-diff:** guard preview/resource removals against partial renders ([#2985](https://github.com/yschimke/compose-ai-tools/issues/2985)) ([c7047f9](https://github.com/yschimke/compose-ai-tools/commit/c7047f9818a8c28e9e3509be97af05cdca16df45))

## [0.19.8](https://github.com/yschimke/compose-ai-tools/compare/v0.19.7...v0.19.8) (2026-07-30)


### Features

* **design-artifacts:** let a spec declare a sticker-less component with capture: "none" ([#2956](https://github.com/yschimke/compose-ai-tools/issues/2956)) ([09dd981](https://github.com/yschimke/compose-ai-tools/commit/09dd981c86ae1766d4159024db6589ddc8ce29d8))
* **plugin:** per-preview-id render filter, so a deferred catalog palette skips its render ([#2973](https://github.com/yschimke/compose-ai-tools/issues/2973)) ([8a5c2df](https://github.com/yschimke/compose-ai-tools/commit/8a5c2dffb6083b6fcd728ab4954c2a1374951db0))
* **rc-embedded:** implement the canvas text seam's jvm half over skiko ([#2982](https://github.com/yschimke/compose-ai-tools/issues/2982)) ([234fa6a](https://github.com/yschimke/compose-ai-tools/commit/234fa6acf7aebe15a7cc201717e4fde3bcdd8294))
* **serve:** add /admin/groups so front-page sections are runtime config too ([#2981](https://github.com/yschimke/compose-ai-tools/issues/2981)) ([2611c9a](https://github.com/yschimke/compose-ai-tools/commit/2611c9a39fe9cbb9cec2ca409d11babab13820f0))
* **serve:** give catalog.json `deferred[]` entries a live lane ([#2979](https://github.com/yschimke/compose-ai-tools/issues/2979)) ([efd291f](https://github.com/yschimke/compose-ai-tools/commit/efd291f89b9cffc086ed2650f4310cc5ffec6802))
* **serve:** publish horologist, attribute pocketcasts to Automattic ([#2975](https://github.com/yschimke/compose-ai-tools/issues/2975)) ([3177236](https://github.com/yschimke/compose-ai-tools/commit/31772361a370e91c682eea0db59977de7fe7a597))


### Bug Fixes

* **design-artifacts:** make entry-level deferral inert until the serve host can route it ([#2972](https://github.com/yschimke/compose-ai-tools/issues/2972)) ([9357462](https://github.com/yschimke/compose-ai-tools/commit/935746294e35c102c8cad78b22e5377cf4b6e387))
* **rc-player:** resolve size-relative corner radii on MODIFIER_ROUNDED_CLIP_RECT ([#2978](https://github.com/yschimke/compose-ai-tools/issues/2978)) ([bcc48f6](https://github.com/yschimke/compose-ai-tools/commit/bcc48f630f49118bdb0d4bae620ef0f7655b502c))
* **renderer-android:** resolve coil AsyncImage during preview renders ([#2971](https://github.com/yschimke/compose-ai-tools/issues/2971)) ([e4b4392](https://github.com/yschimke/compose-ai-tools/commit/e4b43928b5277bcb1b78ab03d04755e47859525c))

## [0.19.7](https://github.com/yschimke/compose-ai-tools/compare/v0.19.6...v0.19.7) (2026-07-29)


### Features

* **design-artifacts:** opt-in embedded-player lane for rc-compare ([#2939](https://github.com/yschimke/compose-ai-tools/issues/2939)) ([473da70](https://github.com/yschimke/compose-ai-tools/commit/473da70e1317ad0e157153108d4aa46cea263701))
* **design-artifacts:** per-entry / per-axis render priority for catalog specs ([#2959](https://github.com/yschimke/compose-ai-tools/issues/2959)) ([ddf3413](https://github.com/yschimke/compose-ai-tools/commit/ddf34138fea0349b76fc4705afc776c9c77a54d2))
* **figma-svg:** export a node's own imperative draw by re-invoking it offscreen ([#2944](https://github.com/yschimke/compose-ai-tools/issues/2944)) ([eaa0729](https://github.com/yschimke/compose-ai-tools/commit/eaa0729c8215d5dbafe5eeb088bc348f6c6c9785))
* **rc-embedded:** run the player's value layer on the desktop JVM ([#2943](https://github.com/yschimke/compose-ai-tools/issues/2943)) ([26c4e29](https://github.com/yschimke/compose-ai-tools/commit/26c4e29505fc58f145f4de5e05065d99751ce020))
* **serve:** make the producer-trust store pure config with an admin API ([#2961](https://github.com/yschimke/compose-ai-tools/issues/2961)) ([aee444b](https://github.com/yschimke/compose-ai-tools/commit/aee444bf5eec16a3ff542b4877f751b69a1a7ae8))
* **serve:** publish the pocketcasts and pocketcasts-wear catalogs ([#2962](https://github.com/yschimke/compose-ai-tools/issues/2962)) ([4c502c1](https://github.com/yschimke/compose-ai-tools/commit/4c502c151893790fdc5fdf64d6daabf555539295))
* **trust:** trust yschimke/horologist design-artifacts branches ([#2953](https://github.com/yschimke/compose-ai-tools/issues/2953)) ([b896b3e](https://github.com/yschimke/compose-ai-tools/commit/b896b3e8097c85275f253dbd235a3604a79302af))


### Bug Fixes

* **rc-compare:** don't score a preview whose baked reference is blank ([#2933](https://github.com/yschimke/compose-ai-tools/issues/2933)) ([17b46be](https://github.com/yschimke/compose-ai-tools/commit/17b46bee65fe25883d2eeb30d34db123cf4cc9db))
* **rc-embedded-player:** let the composition reach idle, drop dead autoUpdate ([#2945](https://github.com/yschimke/compose-ai-tools/issues/2945)) ([5056931](https://github.com/yschimke/compose-ai-tools/commit/50569312b24c86e69b0b6ad403dd9a50c93e930d))
* **rc-embedded:** stop the guard certifying a file that can't move yet ([#2936](https://github.com/yschimke/compose-ai-tools/issues/2936)) ([62997bc](https://github.com/yschimke/compose-ai-tools/commit/62997bc224209ac3150e60e4f79cb37fdad33d75))

## [0.19.6](https://github.com/yschimke/compose-ai-tools/compare/v0.19.5...v0.19.6) (2026-07-29)


### Features

* **rc-embedded:** vendor AndroidX's embedded RC player as a third rc-compare lane ([#2929](https://github.com/yschimke/compose-ai-tools/issues/2929)) ([975061d](https://github.com/yschimke/compose-ai-tools/commit/975061d8ccbf6d4b4ad6ef5f92b6db632f1f0837))
* **rc-player:** resolve named font families and serve them from Google Fonts ([#2919](https://github.com/yschimke/compose-ai-tools/issues/2919)) ([aafa243](https://github.com/yschimke/compose-ai-tools/commit/aafa24396bb97517afe309c848c5f0d2b9e5c493))
* **serve:** show live "connecting…" on the preview badge ([#2921](https://github.com/yschimke/compose-ai-tools/issues/2921)) ([c672bdf](https://github.com/yschimke/compose-ai-tools/commit/c672bdf3ddff550fd030a1ec380c9b37f173dcb7))


### Bug Fixes

* **figma-svg:** emit brush fills and borders as real SVG gradients ([#2916](https://github.com/yschimke/compose-ai-tools/issues/2916)) ([81930cc](https://github.com/yschimke/compose-ai-tools/commit/81930cc601491d1deb9908590cec05f18fb5a4b0))
* **figma-svg:** only unwrap painters that alter nothing about the paint ([#2920](https://github.com/yschimke/compose-ai-tools/issues/2920)) ([27c1cf2](https://github.com/yschimke/compose-ai-tools/commit/27c1cf234d6bec30927c6bbb774d22613102f509))
* **figma-svg:** raster an icon a blend-mode tint draws over ([#2924](https://github.com/yschimke/compose-ai-tools/issues/2924)) ([806195d](https://github.com/yschimke/compose-ai-tools/commit/806195deb13ed36491586b594a2f72608fe874f0))
* **figma-svg:** stop squashing a clipped vector to its drawn box ([#2926](https://github.com/yschimke/compose-ai-tools/issues/2926)) ([009018b](https://github.com/yschimke/compose-ai-tools/commit/009018b71b29f8fbcab80f8d906456a7c4f9c727))
* **render:** showBackground respects the preview's night uiMode ([#2922](https://github.com/yschimke/compose-ai-tools/issues/2922)) ([79a45bd](https://github.com/yschimke/compose-ai-tools/commit/79a45bd13a053a20d50dc2cffc9247b795bf2031))
* **serve:** reject a themeProvider the catalog never declared ([#2923](https://github.com/yschimke/compose-ai-tools/issues/2923)) ([32c2055](https://github.com/yschimke/compose-ai-tools/commit/32c2055ea7f2da7993f505b75baf9f0c2e891ad9))

## [0.19.5](https://github.com/yschimke/compose-ai-tools/compare/v0.19.4...v0.19.5) (2026-07-29)


### Features

* **preview-image:** bake Google Sans Flex into the font cache ([#2912](https://github.com/yschimke/compose-ai-tools/issues/2912)) ([6c23f47](https://github.com/yschimke/compose-ai-tools/commit/6c23f47c9f49c8d0a39a41341549b937ddd158a0))
* **rc-player:** render Remote Compose with the renderer's own typefaces ([#2908](https://github.com/yschimke/compose-ai-tools/issues/2908)) ([c499d87](https://github.com/yschimke/compose-ai-tools/commit/c499d87574624ab85daac52d5ccb7a4131809ade))
* **serve:** make the published catalog set pure config ([#2897](https://github.com/yschimke/compose-ai-tools/issues/2897)) ([d8796af](https://github.com/yschimke/compose-ai-tools/commit/d8796afdafd528ac5e2781409003db5585c70247))
* **serve:** surface the lane toggles and export links in the page ([#2917](https://github.com/yschimke/compose-ai-tools/issues/2917)) ([2eb6350](https://github.com/yschimke/compose-ai-tools/commit/2eb63502e6c5d8f5ebf69e4a5df1b58b19160efc))


### Bug Fixes

* **catalog:** route untagged stickers to the light annotation ([#2911](https://github.com/yschimke/compose-ai-tools/issues/2911)) ([38ebdd7](https://github.com/yschimke/compose-ai-tools/commit/38ebdd7e9588077502e172d583ab9c330e37b293))
* **daemon:** carry themeProvider through the renderNow wire ([#2915](https://github.com/yschimke/compose-ai-tools/issues/2915)) ([c048c20](https://github.com/yschimke/compose-ai-tools/commit/c048c20ecee01d6fa144d35215db888b2c62c553))
* **daemon:** match live-preview fonts to the baked PNG ([#2909](https://github.com/yschimke/compose-ai-tools/issues/2909)) ([b9c8f97](https://github.com/yschimke/compose-ai-tools/commit/b9c8f971824e854c8524b1ffcfa26d01a7ca425e))
* **figma-svg:** embed the downloadable face the render resolved ([#2913](https://github.com/yschimke/compose-ai-tools/issues/2913)) ([da38afc](https://github.com/yschimke/compose-ai-tools/commit/da38afc5a9b2ab2758e144c5c74d3e360e2f9fab))
* **figma-svg:** read alpha from lambda-form graphicsLayer blocks ([#2914](https://github.com/yschimke/compose-ai-tools/issues/2914)) ([dc692cc](https://github.com/yschimke/compose-ai-tools/commit/dc692cc2dfb8e556356027b6dd6309d0fa64c843))
* **figma-svg:** unwrap delegating painters so Wear surfaces stay vectorised ([#2918](https://github.com/yschimke/compose-ai-tools/issues/2918)) ([d910f3d](https://github.com/yschimke/compose-ai-tools/commit/d910f3d42268db5549ca9417e135eb05ab8bd614))
* **preview-harness:** resync the Wear capsule fixture and run renderer-android in CI ([#2910](https://github.com/yschimke/compose-ai-tools/issues/2910)) ([3cfb5f1](https://github.com/yschimke/compose-ai-tools/commit/3cfb5f1cb521a4223e899244b7fd906a4ce207f1))
* **rc-player:** paint TEXT_LAYOUT (208) instead of silently dropping it ([#2905](https://github.com/yschimke/compose-ai-tools/issues/2905)) ([e42e4e2](https://github.com/yschimke/compose-ai-tools/commit/e42e4e2f4c540b21c49fe9e298eb0b1da751cfc4))

## [0.19.4](https://github.com/yschimke/compose-ai-tools/compare/v0.19.3...v0.19.4) (2026-07-28)


### Features

* **catalog:** front the compose-m3 and remote-m3 catalogs with screen heroes ([#2892](https://github.com/yschimke/compose-ai-tools/issues/2892)) ([376a983](https://github.com/yschimke/compose-ai-tools/commit/376a98392a2887f2a6d09d9bd15b93ba487866f7))
* **figma-svg:** model placeholders first-class and state-aware ([#2900](https://github.com/yschimke/compose-ai-tools/issues/2900)) ([36c9377](https://github.com/yschimke/compose-ai-tools/commit/36c9377f6a3404285a1de75b7a7befe8598a804e))
* **serve:** list every configured theme on the catalog page ([#2891](https://github.com/yschimke/compose-ai-tools/issues/2891)) ([65723f3](https://github.com/yschimke/compose-ai-tools/commit/65723f35b99aa685d40add38d291b3b690e6fc3e))
* **serve:** share generated documents via expiring permalinks ([#2893](https://github.com/yschimke/compose-ai-tools/issues/2893)) ([aca5c13](https://github.com/yschimke/compose-ai-tools/commit/aca5c13bbc34a16ad4512dd915b5ecd58b978a35))


### Bug Fixes

* **catalog:** reject GIF-only previews in spec validation ([#2887](https://github.com/yschimke/compose-ai-tools/issues/2887)) ([58dd8e9](https://github.com/yschimke/compose-ai-tools/commit/58dd8e9d1ad17d0b6f161112bbab3d1095e29105))
* **ci:** stop sticky PR comments duplicating on every run ([#2896](https://github.com/yschimke/compose-ai-tools/issues/2896)) ([b80fdba](https://github.com/yschimke/compose-ai-tools/commit/b80fdba398c37313587b598463af75ed7ded5d83))
* **figma-svg:** honor per-variant identity, preview background, text align and resource fonts ([#2894](https://github.com/yschimke/compose-ai-tools/issues/2894)) ([b40bb1b](https://github.com/yschimke/compose-ai-tools/commit/b40bb1b80319d4fe7000f2001babb9eb7647a855))
* **figma-svg:** honour graphics-layer scale in the viewport export ([#2901](https://github.com/yschimke/compose-ai-tools/issues/2901)) ([7896762](https://github.com/yschimke/compose-ai-tools/commit/78967623f1a8c8464b9e25fe0f0ff44577c9170b))
* **lottie:** settle each swept APNG frame before capturing ([#2888](https://github.com/yschimke/compose-ai-tools/issues/2888)) ([7e582cc](https://github.com/yschimke/compose-ai-tools/commit/7e582cc9dd4cca438ab33e4d6a71a6bb375f52df))
* **wear-catalog:** render the TimeText status strip on screen previews ([#2889](https://github.com/yschimke/compose-ai-tools/issues/2889)) ([44820e9](https://github.com/yschimke/compose-ai-tools/commit/44820e94c4029c58306938cb4a7eca855016e507))
* **wear-widget:** paint the widget fill as the container background, not inside content ([#2895](https://github.com/yschimke/compose-ai-tools/issues/2895)) ([715ad42](https://github.com/yschimke/compose-ai-tools/commit/715ad42b5c9f84cbeeb877ebcebb6ef31b6a4cdc))

## [0.19.3](https://github.com/yschimke/compose-ai-tools/compare/v0.19.2...v0.19.3) (2026-07-28)


### Features

* **serve:** add page-aware link unfurling ([#2874](https://github.com/yschimke/compose-ai-tools/issues/2874)) ([125f4c8](https://github.com/yschimke/compose-ai-tools/commit/125f4c8c436326471c930937a4111f2ef406104e))
* **serve:** register Jetcaster Wear catalog ([#2882](https://github.com/yschimke/compose-ai-tools/issues/2882)) ([b47ad01](https://github.com/yschimke/compose-ai-tools/commit/b47ad01b22d1f36296a03be85846252cc1e7008b))


### Bug Fixes

* **bundle:** render Wear theme catalog semantics ([#2876](https://github.com/yschimke/compose-ai-tools/issues/2876)) ([cea1294](https://github.com/yschimke/compose-ai-tools/commit/cea12940f06a469f69e3cdf85a663e155f49fdfe))
* **catalog:** preserve declared Wear breakpoints ([#2875](https://github.com/yschimke/compose-ai-tools/issues/2875)) ([3ac3059](https://github.com/yschimke/compose-ai-tools/commit/3ac3059b8cf4d78ce965d798b2b36b19d092f617))
* **semantics:** support multiple Compose roots ([#2878](https://github.com/yschimke/compose-ai-tools/issues/2878)) ([bb2fd3d](https://github.com/yschimke/compose-ai-tools/commit/bb2fd3d81f26e0f1460d44340e3e51747657ac9b))

## [0.19.2](https://github.com/yschimke/compose-ai-tools/compare/v0.19.1...v0.19.2) (2026-07-28)


### Bug Fixes

* **android:** preserve preview wrappers in all render modes ([#2866](https://github.com/yschimke/compose-ai-tools/issues/2866)) ([685e005](https://github.com/yschimke/compose-ai-tools/commit/685e005e9bd55b6e548ff4b8b7609fa043023c9f))
* **bundle:** honor timeout while collecting SVG data ([#2863](https://github.com/yschimke/compose-ai-tools/issues/2863)) ([025d200](https://github.com/yschimke/compose-ai-tools/commit/025d2004b1117616a11442b0daa8a5e82aabeae1))
* **figma-svg:** preserve brush backgrounds and expressive shapes ([#2860](https://github.com/yschimke/compose-ai-tools/issues/2860)) ([9227b60](https://github.com/yschimke/compose-ai-tools/commit/9227b608acc93fae2422aacdfd9a835822b54e90))
* **figma-svg:** preserve emoji and annotated text fonts ([#2862](https://github.com/yschimke/compose-ai-tools/issues/2862)) ([f7d1c3b](https://github.com/yschimke/compose-ai-tools/commit/f7d1c3b061cd0c67068946537375a1dc646c0568))
* **figma-svg:** preserve graphics-layer transforms ([#2861](https://github.com/yschimke/compose-ai-tools/issues/2861)) ([7269992](https://github.com/yschimke/compose-ai-tools/commit/7269992bd6c0da8f721d5d70f84a3342f1eec130))

## [0.19.1](https://github.com/yschimke/compose-ai-tools/compare/v0.19.0...v0.19.1) (2026-07-28)


### Features

* **catalog:** carry parallel component annotations ([#2851](https://github.com/yschimke/compose-ai-tools/issues/2851)) ([7834d9b](https://github.com/yschimke/compose-ai-tools/commit/7834d9b18143a5ff7437d896f0f29318808942ac))


### Bug Fixes

* **catalog:** reject colliding variants ([#2858](https://github.com/yschimke/compose-ai-tools/issues/2858)) ([f73f3de](https://github.com/yschimke/compose-ai-tools/commit/f73f3de0217fb67f2fe16acf41e961dd7ca29456))
* **serve:** keep IR previews out of daemon lane ([#2856](https://github.com/yschimke/compose-ai-tools/issues/2856)) ([8ab99f5](https://github.com/yschimke/compose-ai-tools/commit/8ab99f54c37fd5b9b5e35056bb36725aa2283214))
* **tiles:** avoid linking 1.6 scope on older runtimes ([#2850](https://github.com/yschimke/compose-ai-tools/issues/2850)) ([b263833](https://github.com/yschimke/compose-ai-tools/commit/b26383375f50c1b76a840e94e0b2cfb6054f6117))

## [0.19.0](https://github.com/yschimke/compose-ai-tools/compare/v0.18.6...v0.19.0) (2026-07-28)


### ⚠ BREAKING CHANGES

* **wear:** migrate gesture indicators to alpha06 ([#2849](https://github.com/yschimke/compose-ai-tools/issues/2849))

### Features

* **wear:** migrate gesture indicators to alpha06 ([#2849](https://github.com/yschimke/compose-ai-tools/issues/2849)) ([82ddbd9](https://github.com/yschimke/compose-ai-tools/commit/82ddbd9d7b1d2ab1b7c85dfb06116d8ca89e0af3))


### Bug Fixes

* **figma:** Fix Code Connect target inference for preview wrappers ([#2846](https://github.com/yschimke/compose-ai-tools/issues/2846)) ([cd5f08c](https://github.com/yschimke/compose-ai-tools/commit/cd5f08c4711c3e984fb411e6178c6aad4d44342f))
* **previews:** label parameterized captures ([#2845](https://github.com/yschimke/compose-ai-tools/issues/2845)) ([23bbc0d](https://github.com/yschimke/compose-ai-tools/commit/23bbc0da1d430de9fb6871e78f166df2fc910313))
* **serve:** allow larger catalog live bundles ([#2841](https://github.com/yschimke/compose-ai-tools/issues/2841)) ([e994433](https://github.com/yschimke/compose-ai-tools/commit/e994433dace879c5548b86c2dafe6d695215a7c5))
* **serve:** honor live catalog runtime versions ([#2844](https://github.com/yschimke/compose-ai-tools/issues/2844)) ([4f218fa](https://github.com/yschimke/compose-ai-tools/commit/4f218fa00cc87acdb25f7304fd40d4c7e4af0ae7))

## [0.18.6](https://github.com/yschimke/compose-ai-tools/compare/v0.18.5...v0.18.6) (2026-07-28)


### Features

* **serve:** add preview zoom modes ([#2837](https://github.com/yschimke/compose-ai-tools/issues/2837)) ([67d4df4](https://github.com/yschimke/compose-ai-tools/commit/67d4df4c8bf060101727b49925943e6d93df2af5))


### Bug Fixes

* **serve:** expose and retry partial catalog failures ([#2831](https://github.com/yschimke/compose-ai-tools/issues/2831)) ([ed525ba](https://github.com/yschimke/compose-ai-tools/commit/ed525ba1df513e99dfe520053e11780b600ad15c))
* **serve:** make catalog navigation tabs sticky ([#2836](https://github.com/yschimke/compose-ai-tools/issues/2836)) ([8ee0f11](https://github.com/yschimke/compose-ai-tools/commit/8ee0f11bfcb4b436140f9f97d49cc6160405ea6f))

## [0.18.5](https://github.com/yschimke/compose-ai-tools/compare/v0.18.4...v0.18.5) (2026-07-27)


### Bug Fixes

* **server:** accept JSON catalog props ([#2829](https://github.com/yschimke/compose-ai-tools/issues/2829)) ([1065cb0](https://github.com/yschimke/compose-ai-tools/commit/1065cb0308caa79c60555457f889ce0424c60c91))

## [0.18.4](https://github.com/yschimke/compose-ai-tools/compare/v0.18.3...v0.18.4) (2026-07-27)


### Features

* **server:** Remove obsolete preview-host sample project ([#2827](https://github.com/yschimke/compose-ai-tools/issues/2827)) ([c422e2e](https://github.com/yschimke/compose-ai-tools/commit/c422e2e32ee306d0329575cb49eac1586c2ad57d))


### Bug Fixes

* **deps:** move the XR line to beta01 and null-guard the panel tag lookup ([#2819](https://github.com/yschimke/compose-ai-tools/issues/2819)) ([3b72252](https://github.com/yschimke/compose-ai-tools/commit/3b72252bce3c3a87d3c011af5bae1007b3006bbb))
* **deps:** revert wear-compose-remote to alpha07 ([#2826](https://github.com/yschimke/compose-ai-tools/issues/2826)) ([b789cac](https://github.com/yschimke/compose-ai-tools/commit/b789cacd505b612360a83cbfd01864506b677a10))
* **deps:** update gradle minor/patch ([#2805](https://github.com/yschimke/compose-ai-tools/issues/2805)) ([bae86c7](https://github.com/yschimke/compose-ai-tools/commit/bae86c7fdb547f62e0571581010712bf6fcc77ca))
* **renderer:** provide LocalSession so XR 2D-fallback previews render ([#2822](https://github.com/yschimke/compose-ai-tools/issues/2822)) ([7326067](https://github.com/yschimke/compose-ai-tools/commit/7326067622a83da06615c4093cbb6cca72c03c09))
* **renderer:** stop swallowing locale-local reflection failures, and cache the lookup ([#2817](https://github.com/yschimke/compose-ai-tools/issues/2817)) ([ed54a0f](https://github.com/yschimke/compose-ai-tools/commit/ed54a0f1e0a8279e80d5787644874eef55a714b9))
* **serve:** attribute every homepage section by provenance, not by catalog id ([#2816](https://github.com/yschimke/compose-ai-tools/issues/2816)) ([4d3d8e8](https://github.com/yschimke/compose-ai-tools/commit/4d3d8e86a9af5a424358d4c6398f63048e4ce0d8))

## [0.18.3](https://github.com/yschimke/compose-ai-tools/compare/v0.18.2...v0.18.3) (2026-07-27)


### Features

* **serve:** attribute homepage catalogs by sourceRepo ([#2801](https://github.com/yschimke/compose-ai-tools/issues/2801)) ([ea2704b](https://github.com/yschimke/compose-ai-tools/commit/ea2704b9d0b18b93e146e86d4f41bd4a10044a72))


### Bug Fixes

* **deploy:** migrate legacy compose-samples catalog override ([#2802](https://github.com/yschimke/compose-ai-tools/issues/2802)) ([0c6b33e](https://github.com/yschimke/compose-ai-tools/commit/0c6b33ed4de7e89f5bacd73b32548fb299f3c68f))
* **deps:** update dependency pixelmatch to v7 ([#2808](https://github.com/yschimke/compose-ai-tools/issues/2808)) ([577f735](https://github.com/yschimke/compose-ai-tools/commit/577f73543aaeb0f54c3d69c96332788f2601ffa2))

## [0.18.2](https://github.com/yschimke/compose-ai-tools/compare/v0.18.1...v0.18.2) (2026-07-26)


### Features

* **renderer:** Support LocalLocale and LocalLocaleList in Android previews ([#2799](https://github.com/yschimke/compose-ai-tools/issues/2799)) ([1282f3f](https://github.com/yschimke/compose-ai-tools/commit/1282f3f44fab6ffc49fa84fbdd8026dc03a18a15))
* **server:** Attribute Compose sample catalogs to android/compose-samples ([#2798](https://github.com/yschimke/compose-ai-tools/issues/2798)) ([45f65c9](https://github.com/yschimke/compose-ai-tools/commit/45f65c9ef23bb64f250c955b65448a80c28b74e8))
* **server:** Keep Wear previews in dark mode ([#2793](https://github.com/yschimke/compose-ai-tools/issues/2793)) ([4f27afa](https://github.com/yschimke/compose-ai-tools/commit/4f27afa9f6bffc8829ef1699ffa52d46041b7451))
* **wear:** Wear dark-mode normalization and catalog-scoped theme persistence ([#2800](https://github.com/yschimke/compose-ai-tools/issues/2800)) ([8baa882](https://github.com/yschimke/compose-ai-tools/commit/8baa882cb5be28cafd95557f3bfa00f9d237cd96))


### Performance Improvements

* **serve:** add generation header and cache static preview responses ([#2794](https://github.com/yschimke/compose-ai-tools/issues/2794)) ([9602def](https://github.com/yschimke/compose-ai-tools/commit/9602defbdb57c90962d6700b3723990d120b5688))

## [0.18.1](https://github.com/yschimke/compose-ai-tools/compare/v0.18.0...v0.18.1) (2026-07-26)


### Features

* **server:** Show the last 10 render failures on the status page ([#2791](https://github.com/yschimke/compose-ai-tools/issues/2791)) ([b6241d2](https://github.com/yschimke/compose-ai-tools/commit/b6241d23992c5a50ebd2425d2b97aee2a1d54e8d))


### Bug Fixes

* **ci:** avoid phantom release PRs after publishing ([#2792](https://github.com/yschimke/compose-ai-tools/issues/2792)) ([4ad3ee6](https://github.com/yschimke/compose-ai-tools/commit/4ad3ee6a9fbce8f488d42966062d638afddb145e))

## [0.18.0](https://github.com/yschimke/compose-ai-tools/compare/v0.17.27...v0.18.0) (2026-07-26)


### ⚠ BREAKING CHANGES

* **daemon:** default backgroundSandboxBoot to true ([#2788](https://github.com/yschimke/compose-ai-tools/issues/2788))

### Features

* **ci:** persist the downloadable-font cache across apply-action runs ([#2787](https://github.com/yschimke/compose-ai-tools/issues/2787)) ([ca3b367](https://github.com/yschimke/compose-ai-tools/commit/ca3b367a6ca9b5097b92d8598adfc932c893eb06))
* **daemon:** default backgroundSandboxBoot to true ([#2788](https://github.com/yschimke/compose-ai-tools/issues/2788)) ([834594e](https://github.com/yschimke/compose-ai-tools/commit/834594e6f54bf419b6958383c02e6c165ab6c9c4))
* **daemon:** expose backgroundSandboxBoot to the Gradle-plugin launch path ([#2783](https://github.com/yschimke/compose-ai-tools/issues/2783)) ([d4907c2](https://github.com/yschimke/compose-ai-tools/commit/d4907c240189a339a1d6e54d08444babf67188f2))


### Bug Fixes

* **ci:** key the android-all cache on every coordinate a cell needs ([#2785](https://github.com/yschimke/compose-ai-tools/issues/2785)) ([8335c74](https://github.com/yschimke/compose-ai-tools/commit/8335c74d18023025c4cdd8db85eee853b8a35878))
* **scroll:** deflake stitched LONG captures and downloadable-font resolution ([#2786](https://github.com/yschimke/compose-ai-tools/issues/2786)) ([36bd523](https://github.com/yschimke/compose-ai-tools/commit/36bd523c4b52197ad63ca028241a1530e99e69d8))


### Performance Improvements

* **ci:** cache the android-all runtime and wire BuildFetch into build-plugin ([#2781](https://github.com/yschimke/compose-ai-tools/issues/2781)) ([37990aa](https://github.com/yschimke/compose-ai-tools/commit/37990aa9e8137085ff5c93166e1c8b48b8c50025))
* **serve:** prebake the front-door hero images ([#2789](https://github.com/yschimke/compose-ai-tools/issues/2789)) ([4ef47c8](https://github.com/yschimke/compose-ai-tools/commit/4ef47c89a67996d479a392e8cdd9683e77b490ae))

## [0.17.27](https://github.com/yschimke/compose-ai-tools/compare/v0.17.26...v0.17.27) (2026-07-26)


### Features

* **preview:** add @WearThemeCatalog so Wear theme sheets read the Wear theme ([#2778](https://github.com/yschimke/compose-ai-tools/issues/2778)) ([d8dafae](https://github.com/yschimke/compose-ai-tools/commit/d8dafae270621023392ee9db7a5ef366d95f0c4b))
* **server:** Group home index catalogs into Design Systems, yschimke org, and Other ([#2779](https://github.com/yschimke/compose-ai-tools/issues/2779)) ([e888156](https://github.com/yschimke/compose-ai-tools/commit/e88815666ac62f37cca96f52742d243ff9170f09))


### Bug Fixes

* **ci:** wait out the daemon's cold sandbox boot in the round-trip test ([#2780](https://github.com/yschimke/compose-ai-tools/issues/2780)) ([2358a6a](https://github.com/yschimke/compose-ai-tools/commit/2358a6ac2148e99fbfd837f89d815c552f74a809))
* **serve:** keep catalog index latency independent of daemon resumes ([#2776](https://github.com/yschimke/compose-ai-tools/issues/2776)) ([95737d8](https://github.com/yschimke/compose-ai-tools/commit/95737d8adcf3bd94f31fe136418712e7ab841cb4))

## [0.17.26](https://github.com/yschimke/compose-ai-tools/compare/v0.17.25...v0.17.26) (2026-07-26)


### Bug Fixes

* **figma-svg:** name branded downloadable faces, and box text that can't be named ([#2774](https://github.com/yschimke/compose-ai-tools/issues/2774)) ([55e7790](https://github.com/yschimke/compose-ai-tools/commit/55e77900336d26e8e18f3fcd7c256f217c9fbfe5))

## [0.17.25](https://github.com/yschimke/compose-ai-tools/compare/v0.17.24...v0.17.25) (2026-07-25)


### Bug Fixes

* **bundle:** bound figma-raster crops when packing, not only when serving ([#2772](https://github.com/yschimke/compose-ai-tools/issues/2772)) ([53099c1](https://github.com/yschimke/compose-ai-tools/commit/53099c1b4fd7c6a2db4e60c057e8b16213ae6b7f))

## [0.17.24](https://github.com/yschimke/compose-ai-tools/compare/v0.17.23...v0.17.24) (2026-07-25)


### Features

* **design-catalog-remote-m3:** declare Wear render density (2.0) in the preview config ([#2763](https://github.com/yschimke/compose-ai-tools/issues/2763)) ([16a64b3](https://github.com/yschimke/compose-ai-tools/commit/16a64b33afe2505815b8075d58b193df70adcde8))
* **gradle-plugin:** detect split-family version skew on the render classpath ([#2761](https://github.com/yschimke/compose-ai-tools/issues/2761)) ([d9f5e0f](https://github.com/yschimke/compose-ai-tools/commit/d9f5e0f5fdaf09eb61d10b0efbea285c4e4ee1b5))


### Bug Fixes

* **catalog:** tag remote-m3 as dark-theme so its stickers aren't lost on a white stage ([#2759](https://github.com/yschimke/compose-ai-tools/issues/2759)) ([f5b5c0f](https://github.com/yschimke/compose-ai-tools/commit/f5b5c0f4776bad7f0f4449c86314cf9f4d795156))
* **gradle-plugin:** order composePreviewDaemonStart after composePreviewDiscover ([#2766](https://github.com/yschimke/compose-ai-tools/issues/2766)) ([77217e8](https://github.com/yschimke/compose-ai-tools/commit/77217e81199dff3a435a7790278aece225cdb36d))
* **rc-player:** capture Remote Compose docs in Dp behavior + stamp generation density ([#2760](https://github.com/yschimke/compose-ai-tools/issues/2760)) ([1c06766](https://github.com/yschimke/compose-ai-tools/commit/1c067665fe12b9023a7277338c4afb054d3b119d))
* **rc-player:** scale dp-typed size modifiers by generation density ([#2757](https://github.com/yschimke/compose-ai-tools/issues/2757)) ([b3ecc85](https://github.com/yschimke/compose-ai-tools/commit/b3ecc851820911aac090d9a71710616dfc038ba7))
* **remote-compose-player:** honor DP density behavior for padding ([#2768](https://github.com/yschimke/compose-ai-tools/issues/2768)) ([0f633bc](https://github.com/yschimke/compose-ai-tools/commit/0f633bc80bcd8ba2b9ee091cf9d78c8f7f5caedb))
* **remote-compose-player:** scale corners, spacing, offset, border under DP density ([#2769](https://github.com/yschimke/compose-ai-tools/issues/2769)) ([5b26706](https://github.com/yschimke/compose-ai-tools/commit/5b26706677e991f40fefdfadab2e1ffc14520686))
* **renderer:** settle each Lottie APNG step so the sweep stops dropping frames ([#2762](https://github.com/yschimke/compose-ai-tools/issues/2762)) ([2640f88](https://github.com/yschimke/compose-ai-tools/commit/2640f88b939e5f4458b789b8364b8f5f5fa186c3))
* **serve:** trust compose-samples in the trust store that is actually baked ([#2764](https://github.com/yschimke/compose-ai-tools/issues/2764)) ([87bbc0b](https://github.com/yschimke/compose-ai-tools/commit/87bbc0ba4ac747373a4f7f80cf7119542186efb6))

## [0.17.23](https://github.com/yschimke/compose-ai-tools/compare/v0.17.22...v0.17.23) (2026-07-25)


### Features

* **catalog:** theme.fonts knob to brand the M3 type scale per role group ([#2752](https://github.com/yschimke/compose-ai-tools/issues/2752)) ([9d120c7](https://github.com/yschimke/compose-ai-tools/commit/9d120c751a8a30786bb1c027c68c03d705966401))
* **discovery:** support @Preview functions whose parameters are all defaulted ([#2745](https://github.com/yschimke/compose-ai-tools/issues/2745)) ([b313d43](https://github.com/yschimke/compose-ai-tools/commit/b313d43e23896a98093c97d3d8c11b479d3cdbbf))


### Bug Fixes

* **rc-player:** paint drawWithContent fills at full component bounds ([#2755](https://github.com/yschimke/compose-ai-tools/issues/2755)) ([5107630](https://github.com/yschimke/compose-ai-tools/commit/51076304bda782837405a246a69f7de3aa9bc789))
* **rc-player:** resolve dynamic path coords + content-wrapper sizes ([#2753](https://github.com/yschimke/compose-ai-tools/issues/2753)) ([1bf6c16](https://github.com/yschimke/compose-ai-tools/commit/1bf6c16a69a6536ceb5902407ea51eb17ac81da0))

## [0.17.22](https://github.com/yschimke/compose-ai-tools/compare/v0.17.21...v0.17.22) (2026-07-25)


### Features

* **design-artifacts:** support multi-build monorepos and external multipreviews ([#2746](https://github.com/yschimke/compose-ai-tools/issues/2746)) ([4d7f4cd](https://github.com/yschimke/compose-ai-tools/commit/4d7f4cd65d5d852b2c3f77fda4c6866d938d79aa))
* **serve:** list the compose-samples catalogs on preview.coo.ee ([#2750](https://github.com/yschimke/compose-ai-tools/issues/2750)) ([9bb1e7e](https://github.com/yschimke/compose-ai-tools/commit/9bb1e7e5f89c5fa95f6810c5f91f941df0bc8d1c))


### Bug Fixes

* **bundle:** fetch --with-semantics by raw preview id, inject by bundle id ([#2744](https://github.com/yschimke/compose-ai-tools/issues/2744)) ([30e1c68](https://github.com/yschimke/compose-ai-tools/commit/30e1c68541f962026fcfc5f7cd975f6de8bfd572))
* **design-artifacts:** derive --source-repo from the git remote ([#2747](https://github.com/yschimke/compose-ai-tools/issues/2747)) ([a8024b1](https://github.com/yschimke/compose-ai-tools/commit/a8024b1337a081ca8063cd7da37b514412d72ffa))
* **rc-player:** only adopt same-component ComponentValues nested in containers ([#2751](https://github.com/yschimke/compose-ai-tools/issues/2751)) ([68545e1](https://github.com/yschimke/compose-ai-tools/commit/68545e1c2f644342e0414d8d6b470d35b1c722fe))
* **renderer:** never write a still PNG into a .gif output path ([#2748](https://github.com/yschimke/compose-ai-tools/issues/2748)) ([7aa92c7](https://github.com/yschimke/compose-ai-tools/commit/7aa92c71c8240c0bf047cb4cf4f0e0fb0317b3a5))
* treat renderFailed as terminal in the daemon wait loops ([#2749](https://github.com/yschimke/compose-ai-tools/issues/2749)) ([5aacb78](https://github.com/yschimke/compose-ai-tools/commit/5aacb786244b68e200d5c498c45e42e052b0232b))

## [0.17.21](https://github.com/yschimke/compose-ai-tools/compare/v0.17.20...v0.17.21) (2026-07-25)


### Features

* **design-artifacts:** PNG vs Remote Compose (JS player) parity page ([#2737](https://github.com/yschimke/compose-ai-tools/issues/2737)) ([eda1108](https://github.com/yschimke/compose-ai-tools/commit/eda1108b407c475b8c8f5598b6d1d8fbfaea5b6d))
* **rc-player:** render canvas-operations draw blocks (opcode 173) ([#2740](https://github.com/yschimke/compose-ai-tools/issues/2740)) ([b2a8167](https://github.com/yschimke/compose-ai-tools/commit/b2a8167582f9585edb9f6851acf0c5b7b7773603))


### Bug Fixes

* **gradle-plugin:** resolve the render classpath as one graph, not four ([#2739](https://github.com/yschimke/compose-ai-tools/issues/2739)) ([ec4b0d9](https://github.com/yschimke/compose-ai-tools/commit/ec4b0d97350828222f1ba36c1eeaf2b4f2be52ed))
* **serve:** don't open a replacement daemon while the outgoing one is closing ([#2742](https://github.com/yschimke/compose-ai-tools/issues/2742)) ([063c6bb](https://github.com/yschimke/compose-ai-tools/commit/063c6bb8fa774565e8fa3f9efb2b0444d6c8959f))
* **serve:** keep a suspended catalog's trust on /status instead of a blank row ([#2738](https://github.com/yschimke/compose-ai-tools/issues/2738)) ([0da3749](https://github.com/yschimke/compose-ai-tools/commit/0da37499d0824580382b02a40f9637fe1c59f0ad))

## [0.17.20](https://github.com/yschimke/compose-ai-tools/compare/v0.17.19...v0.17.20) (2026-07-25)


### Features

* **apptour:** app-level previews — activity heroes and scripted app tours ([#2731](https://github.com/yschimke/compose-ai-tools/issues/2731)) ([44b0b53](https://github.com/yschimke/compose-ai-tools/commit/44b0b53f546b4ec9d19a1b25975c7e354c27ef98))


### Bug Fixes

* **bundle:** sanitize preview ids in bundle entry names so no file name carries spaces ([#2733](https://github.com/yschimke/compose-ai-tools/issues/2733)) ([d24239a](https://github.com/yschimke/compose-ai-tools/commit/d24239a4198ed875c0c5bb46839db0a1b8e5caf6))
* **rc-player:** parse the accessibility-semantics op (250) instead of truncating ([#2734](https://github.com/yschimke/compose-ai-tools/issues/2734)) ([d407833](https://github.com/yschimke/compose-ai-tools/commit/d407833dedc894876587760347f654152caa698a))

## [0.17.19](https://github.com/yschimke/compose-ai-tools/compare/v0.17.18...v0.17.19) (2026-07-25)


### Features

* **wear-widget:** real Glance Wear RemoteCompose widgets that preserve the encoded doc ([#2727](https://github.com/yschimke/compose-ai-tools/issues/2727)) ([a34ffbf](https://github.com/yschimke/compose-ai-tools/commit/a34ffbfc23ff7567714c4c57b016f10ebaae736b))


### Bug Fixes

* **bundle:** pack per-param IR for @PreviewParameter previews ([#2729](https://github.com/yschimke/compose-ai-tools/issues/2729)) ([a346d04](https://github.com/yschimke/compose-ai-tools/commit/a346d042565f9a2319166155281699cf14ebaaa6))
* **figma-svg:** name downloadable Google fonts instead of collapsing to Roboto ([#2730](https://github.com/yschimke/compose-ai-tools/issues/2730)) ([d78c096](https://github.com/yschimke/compose-ai-tools/commit/d78c096db28d41d1db263a7f617aa9b4097fe9fd))
* **serve:** bound RC-doc decompression when materialising catalog docs ([#2725](https://github.com/yschimke/compose-ai-tools/issues/2725)) ([e2ee3db](https://github.com/yschimke/compose-ai-tools/commit/e2ee3db21a99c0dd9a2ce8576e45f08ee0e69f15))

## [0.17.18](https://github.com/yschimke/compose-ai-tools/compare/v0.17.17...v0.17.18) (2026-07-25)


### Features

* **serve:** materialise catalog RC docs so the browser canvas lane works on catalogs ([#2724](https://github.com/yschimke/compose-ai-tools/issues/2724)) ([fef57fc](https://github.com/yschimke/compose-ai-tools/commit/fef57fcb6a78a0d7e232a7b4eaa3612d08b107be))


### Bug Fixes

* **gradle-plugin:** pin artifactType on the desktop daemon-start consumer classpath ([#2722](https://github.com/yschimke/compose-ai-tools/issues/2722)) ([702d56b](https://github.com/yschimke/compose-ai-tools/commit/702d56bea92f88168c66f69108c7b95c0e7ce50c))

## [0.17.17](https://github.com/yschimke/compose-ai-tools/compare/v0.17.16...v0.17.17) (2026-07-24)


### Features

* **discovery:** add retargetWearPreviews opt-out for Wear widget previews ([#2708](https://github.com/yschimke/compose-ai-tools/issues/2708)) ([8f6b704](https://github.com/yschimke/compose-ai-tools/commit/8f6b704fdd0755ebe84f1ecf14d468cd42d51f59))
* **plugin:** match the desktop render JVM to the consumer's bytecode ([#2718](https://github.com/yschimke/compose-ai-tools/issues/2718)) ([e0e2332](https://github.com/yschimke/compose-ai-tools/commit/e0e23323710fc2be145d0b24ef334ef40152788b))
* **serve:** in-browser Remote Compose canvas render lane (+ rename .rcdoc → .rc) ([#2720](https://github.com/yschimke/compose-ai-tools/issues/2720)) ([ac0953f](https://github.com/yschimke/compose-ai-tools/commit/ac0953f8b26e8e66a59f1be0c59cf69ba6e4a8e7))

## [0.17.16](https://github.com/yschimke/compose-ai-tools/compare/v0.17.15...v0.17.16) (2026-07-24)


### Features

* **catalog:** drive the wear-m3 catalog inventory from annotations ([#2703](https://github.com/yschimke/compose-ai-tools/issues/2703)) ([9c0ed4b](https://github.com/yschimke/compose-ai-tools/commit/9c0ed4bf91635bf7a7ebbe60318f0576a0d9e162))
* **deploy:** instant-roll webhook so preview.coo.ee deploys the moment an image publishes ([#2710](https://github.com/yschimke/compose-ai-tools/issues/2710)) ([ba7c182](https://github.com/yschimke/compose-ai-tools/commit/ba7c182dfc44d2241a8f9b826783efc335a61b2f))
* **lottie:** emit the animated companion as transparent APNG, not GIF ([#2714](https://github.com/yschimke/compose-ai-tools/issues/2714)) ([d4f94d4](https://github.com/yschimke/compose-ai-tools/commit/d4f94d425650a3d7e11e454053f8e34010668a80))
* **plugin:** render previews on a JDK matching the consumer's bytecode ([#2712](https://github.com/yschimke/compose-ai-tools/issues/2712)) ([54cc743](https://github.com/yschimke/compose-ai-tools/commit/54cc7437af12c052dc3a93b617e1dc46c67f57d1))
* **remotecompose:** add float/dp overridable knobs + expose useful catalog knobs ([#2704](https://github.com/yschimke/compose-ai-tools/issues/2704)) ([ba2d2aa](https://github.com/yschimke/compose-ai-tools/commit/ba2d2aa34e218650bcd6c97a1ae97f3a451a93f2))
* **serve:** per-variant figma svg, branch-linked web rasters, capped inline crops ([#2709](https://github.com/yschimke/compose-ai-tools/issues/2709)) ([6f78b03](https://github.com/yschimke/compose-ai-tools/commit/6f78b0390b43e9646ab1d0198fc8e220e06c3dea))
* **serve:** serve captured Remote Compose docs over /render/&lt;id&gt;.rcdoc ([#2715](https://github.com/yschimke/compose-ai-tools/issues/2715)) ([41feffd](https://github.com/yschimke/compose-ai-tools/commit/41feffd6a8aa8de73cc8f49b22a9fd91a0eea249))
* **serve:** serve the vendored Remote Compose player bundle ([#2716](https://github.com/yschimke/compose-ai-tools/issues/2716)) ([e34db63](https://github.com/yschimke/compose-ai-tools/commit/e34db632337bab100671eecca9a20dc58da69bd4))


### Bug Fixes

* **plugin:** detect jvmTarget on the KMP Android compile task ([#2713](https://github.com/yschimke/compose-ai-tools/issues/2713)) ([ed3595b](https://github.com/yschimke/compose-ai-tools/commit/ed3595b8246bda2cef9c2a668174664e2cdfdf03))
* **renderer:** per-mode reduce motion — LONG always flattens, GIF always animates ([#2705](https://github.com/yschimke/compose-ai-tools/issues/2705)) ([2e5f89b](https://github.com/yschimke/compose-ai-tools/commit/2e5f89b25c37347fe6f6be4aa1accf93af7e7b6e))
* **renderer:** render downloadable GoogleFonts on the daemon path (+ enforce fatal-on-fallback there) ([#2717](https://github.com/yschimke/compose-ai-tools/issues/2717)) ([d3e0983](https://github.com/yschimke/compose-ai-tools/commit/d3e09831ebee52649a6d3a05511828a1af90884e))

## [0.17.15](https://github.com/yschimke/compose-ai-tools/compare/v0.17.14...v0.17.15) (2026-07-24)


### Features

* **catalog:** drive the M3 catalog inventory from annotations ([#2700](https://github.com/yschimke/compose-ai-tools/issues/2700)) ([73a3038](https://github.com/yschimke/compose-ai-tools/commit/73a3038b6a370884c78a944704f10508dda03673))
* **serve:** advertise Remote Compose knobs from the bundle sidecar ([#2693](https://github.com/yschimke/compose-ai-tools/issues/2693)) ([64c49f0](https://github.com/yschimke/compose-ai-tools/commit/64c49f032892944c2a5b04afdd95276bb6e1ca29))
* **serve:** carry the last failure reason in /status renderStats ([#2698](https://github.com/yschimke/compose-ai-tools/issues/2698)) ([a7f9a79](https://github.com/yschimke/compose-ai-tools/commit/a7f9a7913dc5ac38dc4974e7f2f46c3403b1bf01))
* **serve:** render a control per declared Remote Compose knob in the viewer ([#2696](https://github.com/yschimke/compose-ai-tools/issues/2696)) ([f600cd2](https://github.com/yschimke/compose-ai-tools/commit/f600cd2f49bcb979b2c645711521cbfd8c603d0d))
* **serve:** web-mode figma-svg that references Google Fonts instead of embedding ([#2701](https://github.com/yschimke/compose-ai-tools/issues/2701)) ([fffa668](https://github.com/yschimke/compose-ai-tools/commit/fffa6680e85868a7e8209021a70fcd869c550538))


### Bug Fixes

* **serve:** disable Remote Compose knobs while the Wasm lane is active ([#2699](https://github.com/yschimke/compose-ai-tools/issues/2699)) ([394a6ae](https://github.com/yschimke/compose-ai-tools/commit/394a6aee449b4e67f1c3c4417e42c68d1e0a849d))

## [0.17.14](https://github.com/yschimke/compose-ai-tools/compare/v0.17.13...v0.17.14) (2026-07-23)


### Features

* **catalog:** build export inventory from annotations, spec as override ([#2688](https://github.com/yschimke/compose-ai-tools/issues/2688)) ([fe59aeb](https://github.com/yschimke/compose-ai-tools/commit/fe59aebf002ea4dd174f0d817f0197853a8498b0))
* **catalog:** source design-catalog identity from annotations ([#2680](https://github.com/yschimke/compose-ai-tools/issues/2680)) ([204a1d1](https://github.com/yschimke/compose-ai-tools/commit/204a1d1577323e8b3d1d381239d1a039ac5e9487))
* **preview:** render @OverrideVariant everywhere + fold under parent; convert wear/m3 catalogs ([#2683](https://github.com/yschimke/compose-ai-tools/issues/2683)) ([fa7874a](https://github.com/yschimke/compose-ai-tools/commit/fa7874acf666845581a2b3a659ec43d70cfb20a8))
* **remotecompose:** declare catalog knobs so the bundle carries them ([#2690](https://github.com/yschimke/compose-ai-tools/issues/2690)) ([6d8595b](https://github.com/yschimke/compose-ai-tools/commit/6d8595b795631edff74aa070ebfc73affbee3b8b))
* **remotecompose:** pack declared knobs into the preview bundle sidecar ([#2686](https://github.com/yschimke/compose-ai-tools/issues/2686)) ([0073df2](https://github.com/yschimke/compose-ai-tools/commit/0073df2f8f156ec4bd7a240f6b0fedd667145c68))
* **renderer:** fail (or warn) when a downloadable font falls back to Roboto ([#2689](https://github.com/yschimke/compose-ai-tools/issues/2689)) ([b5573c9](https://github.com/yschimke/compose-ai-tools/commit/b5573c97b9601edcbb6f51d19af56bdaef35e9f4))
* **serve:** aggregate render-performance stats on /status + /status.json ([#2685](https://github.com/yschimke/compose-ai-tools/issues/2685)) ([5bf0ac9](https://github.com/yschimke/compose-ai-tools/commit/5bf0ac91ba37e5af0cbc02785cf0948e49cf273d))


### Bug Fixes

* **figma-svg:** raster Coil AsyncImage + derive uiMode from the manifest ([#2691](https://github.com/yschimke/compose-ai-tools/issues/2691)) ([dd2afbd](https://github.com/yschimke/compose-ai-tools/commit/dd2afbd66c62e0a5c26ff4a1604c7b1e7f419539))
* **serve:** complete the render wait on renderFailed instead of sleeping out the budget ([#2687](https://github.com/yschimke/compose-ai-tools/issues/2687)) ([f25caec](https://github.com/yschimke/compose-ai-tools/commit/f25caecd6aecbf0ff99b67219159ebbda2d96714))

## [0.17.13](https://github.com/yschimke/compose-ai-tools/compare/v0.17.12...v0.17.13) (2026-07-23)


### Features

* **preview:** @OverrideVariant — baked override-driven preview variants ([#2678](https://github.com/yschimke/compose-ai-tools/issues/2678)) ([bfa9239](https://github.com/yschimke/compose-ai-tools/commit/bfa923979d1eca5752479c386a2ea28c4858ad58))
* **remotecompose:** auto-capture editable named-value knob declarations ([#2677](https://github.com/yschimke/compose-ai-tools/issues/2677)) ([964271a](https://github.com/yschimke/compose-ai-tools/commit/964271a10996246ec2997828eff400c86f0c73af))
* **remotecompose:** bridge knob declarations across the Robolectric sandbox ([#2681](https://github.com/yschimke/compose-ai-tools/issues/2681)) ([62339fc](https://github.com/yschimke/compose-ai-tools/commit/62339fcb382a4e230d7a1fa53adf559b8ecde97d))
* **serve:** drive Remote Compose named values + profile from the live serve host ([#2674](https://github.com/yschimke/compose-ai-tools/issues/2674)) ([48df42d](https://github.com/yschimke/compose-ai-tools/commit/48df42dd507fd5384c82761ac6c82b07c9c6954b))


### Bug Fixes

* **serve:** bound the per-daemon render lock so a slow render can't starve the render queue ([#2679](https://github.com/yschimke/compose-ai-tools/issues/2679)) ([2a8f0e8](https://github.com/yschimke/compose-ai-tools/commit/2a8f0e8edb6ffc79db5f86eac4b0b2852dcb90b7))
* **serve:** gate rolling update on readiness, not just liveness ([#2673](https://github.com/yschimke/compose-ai-tools/issues/2673)) ([9a5488b](https://github.com/yschimke/compose-ai-tools/commit/9a5488b37e9176f6e9680d41b0960a8fbb5326d3))
* **serve:** group flat catalogs, de-dup viewer nav, styled 404, page landmarks ([#2676](https://github.com/yschimke/compose-ai-tools/issues/2676)) ([cf2bd7f](https://github.com/yschimke/compose-ai-tools/commit/cf2bd7fdf92c1cd31ad6ed93af35dd396eba3c4b))


### Performance Improvements

* **daemon:** background sandbox boot + warm renders for Android cold start ([#2682](https://github.com/yschimke/compose-ai-tools/issues/2682)) ([a7cdb23](https://github.com/yschimke/compose-ai-tools/commit/a7cdb23bb03577cfdd2c2c2883cb2c57cf6b67cb))

## [0.17.12](https://github.com/yschimke/compose-ai-tools/compare/v0.17.11...v0.17.12) (2026-07-23)


### Features

* **deploy:** persist runtime download caches across image rolls ([#2668](https://github.com/yschimke/compose-ai-tools/issues/2668)) ([a1e5fe4](https://github.com/yschimke/compose-ai-tools/commit/a1e5fe40dac2e36b2306bd9cd868971ee70d05d8))


### Bug Fixes

* **cli:** pin android.app.Application for detached daemon renders (fixes live Android catalogs) ([#2669](https://github.com/yschimke/compose-ai-tools/issues/2669)) ([9525958](https://github.com/yschimke/compose-ai-tools/commit/9525958912c29a99625a0e17bd4211af618eb62c))

## [0.17.11](https://github.com/yschimke/compose-ai-tools/compare/v0.17.10...v0.17.11) (2026-07-23)


### Features

* **deploy:** default SERVE_EXTRA_MAVEN_REPOS to all required catalog repos ([#2666](https://github.com/yschimke/compose-ai-tools/issues/2666)) ([731fbc4](https://github.com/yschimke/compose-ai-tools/commit/731fbc4873f72e2962ebdf01d44aec7e680c52cd))
* **serve:** resolve live-bundle deps from extra Maven repos (SERVE_EXTRA_MAVEN_REPOS) ([#2665](https://github.com/yschimke/compose-ai-tools/issues/2665)) ([74a8150](https://github.com/yschimke/compose-ai-tools/commit/74a815045dbbcb0b644a4dc4745614e0b89ceac3))

## [0.17.10](https://github.com/yschimke/compose-ai-tools/compare/v0.17.9...v0.17.10) (2026-07-23)


### Features

* **design-artifacts:** add embed-deps input to the reusable export workflow ([#2662](https://github.com/yschimke/compose-ai-tools/issues/2662)) ([7e2d75e](https://github.com/yschimke/compose-ai-tools/commit/7e2d75e2eb226132ac57571187e8b50d254e03b2))
* **serve:** add a /status page (HTML + JSON) for preview.coo.ee ([#2661](https://github.com/yschimke/compose-ai-tools/issues/2661)) ([76c3e3a](https://github.com/yschimke/compose-ai-tools/commit/76c3e3a5f3ed176bef6c3fe68b8eabff6e59dc23))


### Bug Fixes

* **serve:** fold props-axis variants onto one preview card ([#2663](https://github.com/yschimke/compose-ai-tools/issues/2663)) ([df3d066](https://github.com/yschimke/compose-ai-tools/commit/df3d066f2ac6f2ecfc4f1f9d96719e122ad5821f))

## [0.17.9](https://github.com/yschimke/compose-ai-tools/compare/v0.17.8...v0.17.9) (2026-07-23)


### Bug Fixes

* **cli:** strip manifest &lt;application android:name&gt; for the daemon render ([#2659](https://github.com/yschimke/compose-ai-tools/issues/2659)) ([ad50bbe](https://github.com/yschimke/compose-ai-tools/commit/ad50bbe35a97075924720577ec30dca5edcf0b52))
* **design-artifacts:** bridge preview ids from every render bundle, not just the primary ([#2657](https://github.com/yschimke/compose-ai-tools/issues/2657)) ([4c70da1](https://github.com/yschimke/compose-ai-tools/commit/4c70da1c32d5454b48a0d321ffad139862f3f40a))
* **design-artifacts:** key the live-preview bridge on theme, not just state/props ([#2656](https://github.com/yschimke/compose-ai-tools/issues/2656)) ([65c0ab0](https://github.com/yschimke/compose-ai-tools/commit/65c0ab09574d435f39ae650749f9812a6cadcd07))

## [0.17.8](https://github.com/yschimke/compose-ai-tools/compare/v0.17.7...v0.17.8) (2026-07-22)


### Features

* **design-artifacts:** link split light/dark screen previews via a theme variant ([#2654](https://github.com/yschimke/compose-ai-tools/issues/2654)) ([6effd55](https://github.com/yschimke/compose-ai-tools/commit/6effd554d9ba8946cf134978355652295254dc38))
* **figma-svg:** ship a figma-svg per preview variant, not one per component ([#2653](https://github.com/yschimke/compose-ai-tools/issues/2653)) ([308d917](https://github.com/yschimke/compose-ai-tools/commit/308d917da404ad6e480e5838c254c69b26d3d2cb))
* **resources:** record render failures/fallbacks into the bundle and surface them ([#2649](https://github.com/yschimke/compose-ai-tools/issues/2649)) ([2bff200](https://github.com/yschimke/compose-ai-tools/commit/2bff20009eb390bcc3849a8cba71057e233ceb74))
* **vscode:** show resource render errors in the manifest icon hover ([#2651](https://github.com/yschimke/compose-ai-tools/issues/2651)) ([ac442f0](https://github.com/yschimke/compose-ai-tools/commit/ac442f06bacedcf3eb28290e529d59f70ab47ffb))


### Bug Fixes

* **android:** resolve wireframe corner-radius tokens density-aware, matching desktop ([#2652](https://github.com/yschimke/compose-ai-tools/issues/2652)) ([8c3a02e](https://github.com/yschimke/compose-ai-tools/commit/8c3a02e24d145fc4fc443463e7ca074dc1d60fd3))
* don't let a placeholder overlay dictate the figma-svg container corner ([#2645](https://github.com/yschimke/compose-ai-tools/issues/2645)) ([94e305b](https://github.com/yschimke/compose-ai-tools/commit/94e305beb6b0b3b3c168aeffd085d1832065c528))
* **serve:** stop the thumbnail crop window overflowing narrow grid cards ([#2648](https://github.com/yschimke/compose-ai-tools/issues/2648)) ([ed71ec0](https://github.com/yschimke/compose-ai-tools/commit/ed71ec00411128c63c1224a5daa1bb32f59598bb))

## [0.17.7](https://github.com/yschimke/compose-ai-tools/compare/v0.17.6...v0.17.7) (2026-07-22)


### Features

* **deploy:** serve confetti-mobile on the public preview server ([#2643](https://github.com/yschimke/compose-ai-tools/issues/2643)) ([f494d27](https://github.com/yschimke/compose-ai-tools/commit/f494d27bb69dbc2d7085db0ec5fd858cc29c18ea))


### Bug Fixes

* **resources:** keep output/filesystem failures fatal in the resource render ([#2642](https://github.com/yschimke/compose-ai-tools/issues/2642)) ([e768e24](https://github.com/yschimke/compose-ai-tools/commit/e768e2444a80e2dfc2c205f8b685fa219d6e3925))

## [0.17.6](https://github.com/yschimke/compose-ai-tools/compare/v0.17.5...v0.17.6) (2026-07-22)


### Features

* **discovery:** expand known off-classpath multi-preview annotations, warn on the rest ([#2631](https://github.com/yschimke/compose-ai-tools/issues/2631)) ([4f0058e](https://github.com/yschimke/compose-ai-tools/commit/4f0058e122d84970c1fb4d28990c26c0abaea683))
* **serve:** surface why a preview session is snapshot-only ([#2635](https://github.com/yschimke/compose-ai-tools/issues/2635)) ([efa94d0](https://github.com/yschimke/compose-ai-tools/commit/efa94d0ad94ee427b53a61ef074aae7bb62cd78f))


### Bug Fixes

* **resources:** tolerate un-rasterisable resources instead of hard-failing the render ([#2638](https://github.com/yschimke/compose-ai-tools/issues/2638)) ([d141707](https://github.com/yschimke/compose-ai-tools/commit/d141707b5872a9a8290c354ba5d91a9e54e017c8))

## [0.17.5](https://github.com/yschimke/compose-ai-tools/compare/v0.17.4...v0.17.5) (2026-07-21)


### Features

* **renderer:** render emoji through emoji2-bundled when the consumer ships it ([#2623](https://github.com/yschimke/compose-ai-tools/issues/2623)) ([da96214](https://github.com/yschimke/compose-ai-tools/commit/da962140bbd50add2375a16ee712b240be1f3b68))


### Bug Fixes

* **scroll:** drop redundant end-of-scroll slices in the long-scroll stitcher ([#2629](https://github.com/yschimke/compose-ai-tools/issues/2629)) ([5e12fc8](https://github.com/yschimke/compose-ai-tools/commit/5e12fc8ae448d5b35a013f8bc15fd813d7e6e205))
* **svg:** embed text fonts in the layered SVG export by default ([#2626](https://github.com/yschimke/compose-ai-tools/issues/2626)) ([bf0300a](https://github.com/yschimke/compose-ai-tools/commit/bf0300ac1406636213ad45c3887641b419dca080))

## [0.17.4](https://github.com/yschimke/compose-ai-tools/compare/v0.17.3...v0.17.4) (2026-07-21)


### Features

* **design-catalog:** add Confetti mobile Material 3 catalog ([#2617](https://github.com/yschimke/compose-ai-tools/issues/2617)) ([d85b203](https://github.com/yschimke/compose-ai-tools/commit/d85b2030f464557789799c94566c1d540c0238b7))
* **serve:** systematise per-system stage + hero on the public front door ([#2614](https://github.com/yschimke/compose-ai-tools/issues/2614)) ([410a913](https://github.com/yschimke/compose-ai-tools/commit/410a913291c55a580a62a539f18cd780f552f5fd))


### Bug Fixes

* **auto-inject:** apply the preview plugin under the exclusiveContent shape ([#2616](https://github.com/yschimke/compose-ai-tools/issues/2616)) ([58bccde](https://github.com/yschimke/compose-ai-tools/commit/58bccde5c39fd3d2ca94b861cea2d1f5dc379128))
* **cli:** fail Android bundle render batch on subprocess timeout ([#2620](https://github.com/yschimke/compose-ai-tools/issues/2620)) ([7334aff](https://github.com/yschimke/compose-ai-tools/commit/7334affb867abad0db643662c3f4dfaba517b5b7))

## [0.17.3](https://github.com/yschimke/compose-ai-tools/compare/v0.17.2...v0.17.3) (2026-07-21)


### Features

* **design-artifacts:** add catalog-spec init/validate tooling + schema ([#2606](https://github.com/yschimke/compose-ai-tools/issues/2606)) ([16d034c](https://github.com/yschimke/compose-ai-tools/commit/16d034c587a4afd391164609039dc3698db16a4f))
* render i18n/a11y axes (locale/direction/fontScale) in the Compose M3 catalog ([#220](https://github.com/yschimke/compose-ai-tools/issues/220)) ([#2612](https://github.com/yschimke/compose-ai-tools/issues/2612)) ([bbbfac5](https://github.com/yschimke/compose-ai-tools/commit/bbbfac581314d5e6ceeeb89ed0a2a54adefbe12c))


### Bug Fixes

* **deps:** update gradle minor/patch to v1.3.2 ([#2608](https://github.com/yschimke/compose-ai-tools/issues/2608)) ([0304a1a](https://github.com/yschimke/compose-ai-tools/commit/0304a1a398f38c5dbc22081a3cdeacaaec7f61da))
* **figma-svg:** resolve Modifier.shadow elevation for drop-shadow export ([#2603](https://github.com/yschimke/compose-ai-tools/issues/2603)) ([2c6e3ba](https://github.com/yschimke/compose-ai-tools/commit/2c6e3ba52a2c18424f170191f9d4a5e408e4bdbd))
* resource-leak and subprocess-hang fixes from an architecture review ([#2611](https://github.com/yschimke/compose-ai-tools/issues/2611)) ([ff63c36](https://github.com/yschimke/compose-ai-tools/commit/ff63c36c93113bbb89bdb8bac35bc73ca497a464))
* **serve:** gate the SVG control on per-preview export availability ([#2605](https://github.com/yschimke/compose-ai-tools/issues/2605)) ([e0fd4be](https://github.com/yschimke/compose-ai-tools/commit/e0fd4beaddef1fc601446cd2d3b48da52ed8b40f))
* **serve:** re-pin overlays when the snapshot image loads ([#2607](https://github.com/yschimke/compose-ai-tools/issues/2607)) ([3651797](https://github.com/yschimke/compose-ai-tools/commit/365179778f44dbcdc920f24e232c043065c11c4a))

## [0.17.2](https://github.com/yschimke/compose-ai-tools/compare/v0.17.1...v0.17.2) (2026-07-20)


### Bug Fixes

* **daemon:** spawn the render daemon via a classpath [@argfile](https://github.com/argfile) to survive large modules ([#2601](https://github.com/yschimke/compose-ai-tools/issues/2601)) ([3e91712](https://github.com/yschimke/compose-ai-tools/commit/3e917124306a0866d1822971c54ea292dffb4a52))

## [0.17.1](https://github.com/yschimke/compose-ai-tools/compare/v0.17.0...v0.17.1) (2026-07-20)


### Bug Fixes

* **render:** localize CMP string resources for @Preview(locale=…) on desktop ([#2597](https://github.com/yschimke/compose-ai-tools/issues/2597)) ([17c86ba](https://github.com/yschimke/compose-ai-tools/commit/17c86ba6d077bdee6f67f2d114caf4c97c7e2d1f))

## [0.17.0](https://github.com/yschimke/compose-ai-tools/compare/v0.16.61...v0.17.0) (2026-07-20)


### Miscellaneous Chores

* release as 0.17.0 ([#2598](https://github.com/yschimke/compose-ai-tools/issues/2598)) ([ea005f1](https://github.com/yschimke/compose-ai-tools/commit/ea005f1cc1cb6df25298bb71c45b7d0436c55e2e))

## [0.16.61](https://github.com/yschimke/compose-ai-tools/compare/v0.16.60...v0.16.61) (2026-07-20)


### Bug Fixes

* **design-artifacts:** stamp spec group sections onto the built catalog manifest ([#2594](https://github.com/yschimke/compose-ai-tools/issues/2594)) ([355c571](https://github.com/yschimke/compose-ai-tools/commit/355c571780e161535fa5d951ff99156dea1fce28))

## [0.16.60](https://github.com/yschimke/compose-ai-tools/compare/v0.16.59...v0.16.60) (2026-07-20)


### Features

* **design-artifacts:** fold a borrowed catalog in as a re-themed tab (merge-catalog-section) ([#2586](https://github.com/yschimke/compose-ai-tools/issues/2586)) ([de451c9](https://github.com/yschimke/compose-ai-tools/commit/de451c9be9972672032838dda41b34e4bc392fb1))


### Bug Fixes

* **design-artifacts:** fold only nested assets when merging catalogs ([#2590](https://github.com/yschimke/compose-ai-tools/issues/2590)) ([574db23](https://github.com/yschimke/compose-ai-tools/commit/574db2337d3f3908297fb514eb8491714ca46ec7))
* **renderer:** propagate min-size bound onto the component, not just the wrapper box ([#2588](https://github.com/yschimke/compose-ai-tools/issues/2588)) ([2e9af6f](https://github.com/yschimke/compose-ai-tools/commit/2e9af6fc078a684cbb1a761f58aa9bbbe2fbdff0))

## [0.16.59](https://github.com/yschimke/compose-ai-tools/compare/v0.16.58...v0.16.59) (2026-07-20)


### Features

* **cli:** re-theme a published bundle into a drop-in re-themed bundle (render --res/--svg + repack) ([#2582](https://github.com/yschimke/compose-ai-tools/issues/2582)) ([0bfee3c](https://github.com/yschimke/compose-ai-tools/commit/0bfee3ce722802a318350a23031c1c9387ee5a77))


### Bug Fixes

* **deps:** update gradle minor/patch ([#2584](https://github.com/yschimke/compose-ai-tools/issues/2584)) ([cd1b54a](https://github.com/yschimke/compose-ai-tools/commit/cd1b54adbb9858b826d7972052708f2a56bf4234))
* **figma-svg:** retry Google-Fonts fetch so a cold-start blip doesn't strand a preview ([#2580](https://github.com/yschimke/compose-ai-tools/issues/2580)) ([d345215](https://github.com/yschimke/compose-ai-tools/commit/d3452151ab6c09310f8872634f681f3899e5089d))

## [0.16.58](https://github.com/yschimke/compose-ai-tools/compare/v0.16.57...v0.16.58) (2026-07-19)


### Features

* **serve:** surface the in-browser (Wasm) tier as its own viewer toggle ([#2575](https://github.com/yschimke/compose-ai-tools/issues/2575)) ([f71d502](https://github.com/yschimke/compose-ai-tools/commit/f71d502d0365f01602268881b0e66aa6915b641b))
* **theme:** attribute the shape leg of the M3 triad to component nodes ([#2574](https://github.com/yschimke/compose-ai-tools/issues/2574)) ([2d8f656](https://github.com/yschimke/compose-ai-tools/commit/2d8f656c17bc0fac06d606ec6e8798ea5aae4347))
* **theme:** capture the node shape leg of the M3 triad from the render ([#2578](https://github.com/yschimke/compose-ai-tools/issues/2578)) ([8160fd5](https://github.com/yschimke/compose-ai-tools/commit/8160fd524db01bb702c741f830af7e5949445409))


### Bug Fixes

* **design-artifacts:** carry figma-svg + wireframes from the --extra-renders bundle ([#2576](https://github.com/yschimke/compose-ai-tools/issues/2576)) ([d9bf043](https://github.com/yschimke/compose-ai-tools/commit/d9bf0433b9ac8c695d3c825ccba9235d7b02862e))

## [0.16.57](https://github.com/yschimke/compose-ai-tools/compare/v0.16.56...v0.16.57) (2026-07-19)


### Features

* **catalog:** infer each theme's supported light/dark mode(s) ([#2569](https://github.com/yschimke/compose-ai-tools/issues/2569)) ([a417c52](https://github.com/yschimke/compose-ai-tools/commit/a417c52bd56354775b1bc53af682f29cf4fa2980))
* **cli:** add `bundle render --knob` to re-theme a published bundle offline ([#2571](https://github.com/yschimke/compose-ai-tools/issues/2571)) ([9695daf](https://github.com/yschimke/compose-ai-tools/commit/9695dafabd613781fd184ff59ed568dff89b21b3))
* **design-catalog-m3:** decode an app-supplied palette on the theme.colors knob ([#2562](https://github.com/yschimke/compose-ai-tools/issues/2562)) ([52021c5](https://github.com/yschimke/compose-ai-tools/commit/52021c57b536fe9b7483306508e51c66d244f351))
* **design-catalog-m3:** re-skin catalog shapes & typography via theme knobs ([#2564](https://github.com/yschimke/compose-ai-tools/issues/2564)) ([220b5fd](https://github.com/yschimke/compose-ai-tools/commit/220b5fd45204a37372bcc5244d09b32a11806d28))
* **serve:** list app catalogs on the front page; keep cadence unlisted ([#2568](https://github.com/yschimke/compose-ai-tools/issues/2568)) ([2e5512f](https://github.com/yschimke/compose-ai-tools/commit/2e5512fa4ff6cd54f9a7a10afcccd3887a7254e0))
* **serve:** tab the catalog landing page by section ([#2561](https://github.com/yschimke/compose-ai-tools/issues/2561)) ([535c869](https://github.com/yschimke/compose-ai-tools/commit/535c869217a5536c2c09efd5ef99d44666516a92))


### Bug Fixes

* **design-artifacts:** link README htmlpreview URLs at the publishing repo ([#2567](https://github.com/yschimke/compose-ai-tools/issues/2567)) ([8a27d45](https://github.com/yschimke/compose-ai-tools/commit/8a27d45c43bed5260408bb57ecdde8f8d07fd8db))
* **wear-m3:** keep the catalog's own authored locales ([#2566](https://github.com/yschimke/compose-ai-tools/issues/2566)) ([f5ab9f9](https://github.com/yschimke/compose-ai-tools/commit/f5ab9f9d5a9b94f6d34ae7c6e2b490958465c98d))


### Performance Improvements

* **wear-m3:** shrink catalog resource payload 1.86 MB → 57 KB ([#2563](https://github.com/yschimke/compose-ai-tools/issues/2563)) ([86c0e8c](https://github.com/yschimke/compose-ai-tools/commit/86c0e8cebad146822695bc6b278f4bed303cee14))

## [0.16.56](https://github.com/yschimke/compose-ai-tools/compare/v0.16.55...v0.16.56) (2026-07-18)


### Bug Fixes

* **cli:** carry the android resource payload into split per-preview bundles ([#2559](https://github.com/yschimke/compose-ai-tools/issues/2559)) ([6c3ee07](https://github.com/yschimke/compose-ai-tools/commit/6c3ee07ec57c3d6e8ae6fc0607c05fc868c9c564))

## [0.16.55](https://github.com/yschimke/compose-ai-tools/compare/v0.16.54...v0.16.55) (2026-07-18)


### Features

* **daemon:** Android recording assertions — textEquals, role/text targets, pixels ([#2519](https://github.com/yschimke/compose-ai-tools/issues/2519)) ([#2549](https://github.com/yschimke/compose-ai-tools/issues/2549)) ([5d08201](https://github.com/yschimke/compose-ai-tools/commit/5d08201668c599d32e09586d9ba64c741fe00181))
* **deploy/image:** zero-downtime preview updates via docker-rollout ([#2557](https://github.com/yschimke/compose-ai-tools/issues/2557)) ([57b5a66](https://github.com/yschimke/compose-ai-tools/commit/57b5a662af72b76e889425aede5a6897340bf950))
* **overrides:** honour wrapped-axis size bounds in the Android backend and standalone renderer ([#2554](https://github.com/yschimke/compose-ai-tools/issues/2554)) ([0fe519f](https://github.com/yschimke/compose-ai-tools/commit/0fe519f43011223bd3b2081b814d161fa4016c90))
* **serve:** make the preview viewer responsive on mobile ([#2547](https://github.com/yschimke/compose-ai-tools/issues/2547)) ([1bc34ec](https://github.com/yschimke/compose-ai-tools/commit/1bc34ec8d22b5c1085c02a79d2f6634bcc35a2ec))
* **serve:** reorganize preview overrides into sticky collapsible sections ([#2550](https://github.com/yschimke/compose-ai-tools/issues/2550)) ([cc8e2c8](https://github.com/yschimke/compose-ai-tools/commit/cc8e2c8fbf2c026f5118a3791dc3e6e6228f29f0))
* **serve:** surface version on the home page and catalog provenance ([#2553](https://github.com/yschimke/compose-ai-tools/issues/2553)) ([15ee1ba](https://github.com/yschimke/compose-ai-tools/commit/15ee1ba2d2e3d2adeded67c8d2f2b32aec36c250))


### Bug Fixes

* **renderer:** render private @PreviewParameter providers without sinking the shard ([#2548](https://github.com/yschimke/compose-ai-tools/issues/2548)) ([c60f9ea](https://github.com/yschimke/compose-ai-tools/commit/c60f9ea0cf3ca8f171ce3be13b05894871d2931f))

## [0.16.54](https://github.com/yschimke/compose-ai-tools/compare/v0.16.53...v0.16.54) (2026-07-18)


### Features

* add BuildFetch remote Gradle build cache ([#2532](https://github.com/yschimke/compose-ai-tools/issues/2532)) ([0ebc476](https://github.com/yschimke/compose-ai-tools/commit/0ebc47667d0a8caf462edfddfc4cafef1f7f52ca))
* capture imperative control draws as editable SVG instead of rastering ([#2526](https://github.com/yschimke/compose-ai-tools/issues/2526)) ([851777a](https://github.com/yschimke/compose-ai-tools/commit/851777ac8e9429e266c29b326fa6f0e8a6b99b0b))
* carry stroke cap/join through draw capture as stroke-linecap/linejoin ([#2538](https://github.com/yschimke/compose-ai-tools/issues/2538)) ([f618b49](https://github.com/yschimke/compose-ai-tools/commit/f618b495177d5c5dd6edc930dce3974a6563d02f))
* compose/figma-svg output from render, bundles, and a VS Code copy button ([#2530](https://github.com/yschimke/compose-ai-tools/issues/2530)) ([66348d4](https://github.com/yschimke/compose-ai-tools/commit/66348d41ed40570049e9a886e21cf5dcd37e0962))
* **design-artifacts:** bind Code Connect params to Figma variant properties ([#2531](https://github.com/yschimke/compose-ai-tools/issues/2531)) ([5e3cc55](https://github.com/yschimke/compose-ai-tools/commit/5e3cc55ee86fb85b2ad6df354d823a840c008908))
* **design-artifacts:** emit Figma Code Connect mappings from the catalog export ([#2524](https://github.com/yschimke/compose-ai-tools/issues/2524)) ([9ad3346](https://github.com/yschimke/compose-ai-tools/commit/9ad33464dfbc506a28c475136d05094f2e1d6f23))
* **design-artifacts:** map Code Connect to the real composable, not the @Preview ([#2525](https://github.com/yschimke/compose-ai-tools/issues/2525)) ([4ef47d6](https://github.com/yschimke/compose-ai-tools/commit/4ef47d610014e9c6a66695392b59b2e9b60d934f))
* **discovery:** capture target composable parameters for Code Connect call sites ([#2528](https://github.com/yschimke/compose-ai-tools/issues/2528)) ([9d2e24e](https://github.com/yschimke/compose-ai-tools/commit/9d2e24e48a3fb2f6144af0e7c6324725057b25df))
* **samples:** now-playing container transform with lookahead debug overlay ([#2535](https://github.com/yschimke/compose-ai-tools/issues/2535)) ([00e268e](https://github.com/yschimke/compose-ai-tools/commit/00e268e5b17ef2d45fc34c2fc9a633e787735f23))
* **serve:** rework the preview.coo.ee overrides bar ([#2544](https://github.com/yschimke/compose-ai-tools/issues/2544)) ([c48ef9d](https://github.com/yschimke/compose-ai-tools/commit/c48ef9d22af012bce011eb9ef8ea35129fb28f4c))
* **serve:** serve inline SVG from iframe.html for DOM-capture visual tools ([#2522](https://github.com/yschimke/compose-ai-tools/issues/2522)) ([47b4385](https://github.com/yschimke/compose-ai-tools/commit/47b4385a392c506bdd59cfbb118db9368a668fc7))
* vectorize Slider chrome via draw-capture instead of rastering by name ([#2539](https://github.com/yschimke/compose-ai-tools/issues/2539)) ([ca5800d](https://github.com/yschimke/compose-ai-tools/commit/ca5800d9a29f8c32e5168cf3e043d1cf4dfd0e97))


### Bug Fixes

* **catalog:** make Live Compose stickers interactive via LocalInspectionMode ([#2529](https://github.com/yschimke/compose-ai-tools/issues/2529)) ([eb6ca63](https://github.com/yschimke/compose-ai-tools/commit/eb6ca63fe2ee8dc29e53e24808df11e8821d5667))
* **daemon:** apply missing-resource fallback on the Android interactive held path ([#2545](https://github.com/yschimke/compose-ai-tools/issues/2545)) ([e922d2b](https://github.com/yschimke/compose-ai-tools/commit/e922d2b80e4a8cc1f8fc7ad262d7c06e527afb05))
* size draw capture to placed bounds so touch-inflated controls aren't shrunk ([#2536](https://github.com/yschimke/compose-ai-tools/issues/2536)) ([c8d85e4](https://github.com/yschimke/compose-ai-tools/commit/c8d85e4dfa2f7751e9b5cc1b9a9bdecf890703f2))

## [0.16.53](https://github.com/yschimke/compose-ai-tools/compare/v0.16.52...v0.16.53) (2026-07-17)


### Features

* **compare:** add override controls to the PNG-vs-SVG compare page ([#2518](https://github.com/yschimke/compose-ai-tools/issues/2518)) ([ead0f82](https://github.com/yschimke/compose-ai-tools/commit/ead0f82eff5125e26f55877edf239936e4954e5d))
* **mcp:** Storybook-compatible MCP profile (--storybook) ([#2517](https://github.com/yschimke/compose-ai-tools/issues/2517)) ([975d356](https://github.com/yschimke/compose-ai-tools/commit/975d3568fd4b10302cbb434dd52c22510be1e71f))


### Bug Fixes

* **figma-svg:** apply the render's font scale to exported text ([#2520](https://github.com/yschimke/compose-ai-tools/issues/2520)) ([5a95cd5](https://github.com/yschimke/compose-ai-tools/commit/5a95cd5dd2c5c53d15de4d977de21b1346316e82))
* **figma-svg:** keep Wear scaling-list card labels as editable text ([#2514](https://github.com/yschimke/compose-ai-tools/issues/2514)) ([f2db7f2](https://github.com/yschimke/compose-ai-tools/commit/f2db7f24b9d9e41b2baae1249f148e2157b11e83))
* **figma-svg:** round vectorized Wear scaling-card corners + small cleanups ([#2516](https://github.com/yschimke/compose-ai-tools/issues/2516)) ([ad1fc10](https://github.com/yschimke/compose-ai-tools/commit/ad1fc10acb2f6b56803ae51f4d543e5e93013320))
* **serve:** surface the live lane's original failure instead of a bare input error ([#2515](https://github.com/yschimke/compose-ai-tools/issues/2515)) ([6477b07](https://github.com/yschimke/compose-ai-tools/commit/6477b074ced55b03a6be43856878634aa9d970e8))

## [0.16.52](https://github.com/yschimke/compose-ai-tools/compare/v0.16.51...v0.16.52) (2026-07-16)


### Bug Fixes

* **desktop:** run the desktop preview JVM as a macOS background agent ([#2510](https://github.com/yschimke/compose-ai-tools/issues/2510)) ([c48209d](https://github.com/yschimke/compose-ai-tools/commit/c48209d87c435cc551a07b0fbc417787d32d49c1))
* **figma-svg:** match opaque raster components case-sensitively ([#2512](https://github.com/yschimke/compose-ai-tools/issues/2512)) ([f9dcb21](https://github.com/yschimke/compose-ai-tools/commit/f9dcb2156e2a41e35ef49e6128bcd43005950030))
* **serve:** re-enable Live Compose axis controls on a catalog live session ([#2511](https://github.com/yschimke/compose-ai-tools/issues/2511)) ([bbc7f5c](https://github.com/yschimke/compose-ai-tools/commit/bbc7f5c97765337e98cb1079c15c92ac7d351a4f))

## [0.16.51](https://github.com/yschimke/compose-ai-tools/compare/v0.16.50...v0.16.51) (2026-07-16)


### Features

* **figma-svg:** vectorize ImageVector icons in the layered SVG export ([#2504](https://github.com/yschimke/compose-ai-tools/issues/2504)) ([d8b0080](https://github.com/yschimke/compose-ai-tools/commit/d8b00800d3084c65d14283107d3a1987e8ed3fc0))


### Bug Fixes

* **daemon:** invalidate cached launch descriptor on schema bump ([#2507](https://github.com/yschimke/compose-ai-tools/issues/2507)) ([d8c1faa](https://github.com/yschimke/compose-ai-tools/commit/d8c1faa2daf9127894af676ffea2b50e37b27f88))
* **daemon:** make missing-resource placeholder opt-in (serve/bundle-daemon only) ([#2503](https://github.com/yschimke/compose-ai-tools/issues/2503)) ([b02f47f](https://github.com/yschimke/compose-ai-tools/commit/b02f47f42bd9275bd3f2e315ab0f628eaf17572c))
* **figma-svg:** apply the icon tint and raster icons with unrepresentable paths ([#2505](https://github.com/yschimke/compose-ai-tools/issues/2505)) ([86140f2](https://github.com/yschimke/compose-ai-tools/commit/86140f2dbcc0572a2b9ef1df2eee9135dd9f122f))
* **figma-svg:** raster mixed-paint icon paths and probe the intrinsic tint ([#2506](https://github.com/yschimke/compose-ai-tools/issues/2506)) ([77de77e](https://github.com/yschimke/compose-ai-tools/commit/77de77e16eaac66e47b1b809bf981cfca0d531dc))
* **serve:** keep the live render lane up for knob overrides and Wear ([#2508](https://github.com/yschimke/compose-ai-tools/issues/2508)) ([e06c639](https://github.com/yschimke/compose-ai-tools/commit/e06c639e7a3433e8bfa9bd9b9c1d43feacecd47a))

## [0.16.50](https://github.com/yschimke/compose-ai-tools/compare/v0.16.49...v0.16.50) (2026-07-15)


### Features

* **samples:** add compose-m3 RadioButton unselected state variant ([#2497](https://github.com/yschimke/compose-ai-tools/issues/2497)) ([6091e95](https://github.com/yschimke/compose-ai-tools/commit/6091e95e11b5ed85e136f063fc67812e8b67742b))
* **serve:** fold component states into one grid card + add a viewer state switcher ([#2496](https://github.com/yschimke/compose-ai-tools/issues/2496)) ([f2c6789](https://github.com/yschimke/compose-ai-tools/commit/f2c6789ba310a486e2330d66c39dbbf2d3983b64))
* **serve:** make the theme.font override an autocompleting Google Fonts field ([#2495](https://github.com/yschimke/compose-ai-tools/issues/2495)) ([7250125](https://github.com/yschimke/compose-ai-tools/commit/7250125bd0e1607377b94b8bdc49eb85afc3b0b4))


### Bug Fixes

* **bundle:** carry + load app resources for classic Android previews ([#2498](https://github.com/yschimke/compose-ai-tools/issues/2498)) ([ee16896](https://github.com/yschimke/compose-ai-tools/commit/ee168969fc915ac158b951281d14ae7a069d4f9b))

## [0.16.49](https://github.com/yschimke/compose-ai-tools/compare/v0.16.48...v0.16.49) (2026-07-15)


### Features

* **bundle:** content-crop sticker PNGs in `bundle split` so exported wear stickers are tight ([#2489](https://github.com/yschimke/compose-ai-tools/issues/2489)) ([7e2d9f5](https://github.com/yschimke/compose-ai-tools/commit/7e2d9f58c0737c08bc8b162482fcdd110d77aa50))
* **catalog:** font + palette theme overrides on clean previews ([#2490](https://github.com/yschimke/compose-ai-tools/issues/2490)) ([49140d1](https://github.com/yschimke/compose-ai-tools/commit/49140d10c2900ec1e320a30420643fcb2797fc16))
* **catalog:** honour theme.font / theme.colors in the Wasm viewer ([#2491](https://github.com/yschimke/compose-ai-tools/issues/2491)) ([b0bc64b](https://github.com/yschimke/compose-ai-tools/commit/b0bc64b34c7da43457c039adbad8120a63abb0aa))


### Bug Fixes

* **design-artifacts:** bridge live preview ids for un-themed state-variant catalogs ([#2492](https://github.com/yschimke/compose-ai-tools/issues/2492)) ([adaa867](https://github.com/yschimke/compose-ai-tools/commit/adaa867d95fa416659b0a4e6eac278270db5b137))

## [0.16.48](https://github.com/yschimke/compose-ai-tools/compare/v0.16.47...v0.16.48) (2026-07-15)


### Features

* **catalog:** whole-object + @ShapeCatalog theme catalogs; Roboto Flex default ([#2485](https://github.com/yschimke/compose-ai-tools/issues/2485)) ([4416933](https://github.com/yschimke/compose-ai-tools/commit/441693384a1ccbbcdffb2257b80989617b74b70e))
* **design-artifacts:** static preview embeds + fix remote OutlinedButton ([#2479](https://github.com/yschimke/compose-ai-tools/issues/2479)) ([e459a9f](https://github.com/yschimke/compose-ai-tools/commit/e459a9f27558f6b3156b332930fd86a31941fbf9))
* **design-catalog-remote-m3:** sticker the Wear widget container via glance-wear preview tooling ([#2486](https://github.com/yschimke/compose-ai-tools/issues/2486)) ([2adc8aa](https://github.com/yschimke/compose-ai-tools/commit/2adc8aad17502f825c23ea0c43b6acae47474795))
* **samples:** add wear-m3 OutlinedCard sticker so outlined cards pair outlined↔outlined ([#2484](https://github.com/yschimke/compose-ai-tools/issues/2484)) ([4d07984](https://github.com/yschimke/compose-ai-tools/commit/4d0798496984d519cf0f192ea1345e54db6dfbf0))
* **serve:** add an "Apps" front-page section and fix the near-dead theme toggle ([#2480](https://github.com/yschimke/compose-ai-tools/issues/2480)) ([54bca95](https://github.com/yschimke/compose-ai-tools/commit/54bca95e02d01de6adc65e9dfd5218cf83506a22))
* **serve:** swap catalog previews between light/dark in place instead of filtering ([#2481](https://github.com/yschimke/compose-ai-tools/issues/2481)) ([7651bde](https://github.com/yschimke/compose-ai-tools/commit/7651bded98ff975e9bf25bde91a71795cce9ec35))


### Bug Fixes

* **serve:** crop catalog thumbnails to the component box so Wear stickers aren't a speck ([#2482](https://github.com/yschimke/compose-ai-tools/issues/2482)) ([324fcd4](https://github.com/yschimke/compose-ai-tools/commit/324fcd453c7aa0c86785bd01b6bf74c8fa5d234a))

## [0.16.47](https://github.com/yschimke/compose-ai-tools/compare/v0.16.46...v0.16.47) (2026-07-14)


### Features

* **deploy:** default the preview container to unbounded memory so the live-seat budget scales to the box ([#2476](https://github.com/yschimke/compose-ai-tools/issues/2476)) ([a3af39d](https://github.com/yschimke/compose-ai-tools/commit/a3af39dc41cd0c5d8ca70ea6a0d1b623d57adaa5))
* **samples:** remote-m3 ↔ wear-m3 catalog parity + cross-system compare page ([#2474](https://github.com/yschimke/compose-ai-tools/issues/2474)) ([6b33824](https://github.com/yschimke/compose-ai-tools/commit/6b33824c3fcc3b7095d4f0d6abfe8e8407fe85b7))
* **serve:** weight live seats by backend and auto-size the budget from box memory ([#2473](https://github.com/yschimke/compose-ai-tools/issues/2473)) ([f00b5fc](https://github.com/yschimke/compose-ai-tools/commit/f00b5fcab904901508c9745e7058be156e7a0810))


### Bug Fixes

* **deps:** update gradle minor/patch ([#2457](https://github.com/yschimke/compose-ai-tools/issues/2457)) ([bf8c7d3](https://github.com/yschimke/compose-ai-tools/commit/bf8c7d3f16f564dbdf99ab2790acc7b6d740bf68))
* **remotecompose:** track compose-remote alpha14 RemoteDensity.from signature ([#2475](https://github.com/yschimke/compose-ai-tools/issues/2475)) ([baa723c](https://github.com/yschimke/compose-ai-tools/commit/baa723c720241bfcb1fc128013d71572a8f1398d))

## [0.16.46](https://github.com/yschimke/compose-ai-tools/compare/v0.16.45...v0.16.46) (2026-07-13)


### Features

* **layoutinspector:** flatten synthetic SVG layers and inherit composable names ([#2469](https://github.com/yschimke/compose-ai-tools/issues/2469)) ([35e9643](https://github.com/yschimke/compose-ai-tools/commit/35e964332e514b61942e02630f84f24ed48f95b7))
* **serve:** copy PNG or SVG artefact to clipboard from the viewer ([#2467](https://github.com/yschimke/compose-ai-tools/issues/2467)) ([0cc65bf](https://github.com/yschimke/compose-ai-tools/commit/0cc65bf819fae6874570d2d230aa98ae3d3bd2f7))


### Bug Fixes

* **figma-svg:** XML-escape [@font-face](https://github.com/font-face) family names in the layered SVG ([#2466](https://github.com/yschimke/compose-ai-tools/issues/2466)) ([4b932d7](https://github.com/yschimke/compose-ai-tools/commit/4b932d7e5437c967012823c42bb52eecd89cf046))
* **layoutinspector:** drop LayoutNode fallback so SVG layers keep their layout identity ([#2471](https://github.com/yschimke/compose-ai-tools/issues/2471)) ([1a43e81](https://github.com/yschimke/compose-ai-tools/commit/1a43e817980b0c5086cd423524c13869d880220a))
* **layoutinspector:** key SVG raster matching off own component, not inherited name ([#2470](https://github.com/yschimke/compose-ai-tools/issues/2470)) ([c5578e2](https://github.com/yschimke/compose-ai-tools/commit/c5578e255c6a0fddc5025042f40af448c6f12db0))
* **layoutinspector:** strip MeasurePolicy suffix from SVG layer names ([#2468](https://github.com/yschimke/compose-ai-tools/issues/2468)) ([70718d6](https://github.com/yschimke/compose-ai-tools/commit/70718d6ff1325589d5267412197d1456e4b5e740))

## [0.16.45](https://github.com/yschimke/compose-ai-tools/compare/v0.16.44...v0.16.45) (2026-07-13)


### Features

* **catalog:** render a downloadable GoogleFont (Orbitron) in the wasm + desktop tiers ([#2456](https://github.com/yschimke/compose-ai-tools/issues/2456)) ([05b0274](https://github.com/yschimke/compose-ai-tools/commit/05b02747506f4a7a8f79d1f7866f220d866c01e3))
* **design-artifacts:** crop catalog stickers to the component in the gallery + compare ([#2464](https://github.com/yschimke/compose-ai-tools/issues/2464)) ([fefcc68](https://github.com/yschimke/compose-ai-tools/commit/fefcc6812a9a8c98ae953274ecdbc796e27e1b6c))
* **design-artifacts:** render downloadable GoogleFonts in the wasm/desktop catalog tiers ([#2455](https://github.com/yschimke/compose-ai-tools/issues/2455)) ([f5ed52c](https://github.com/yschimke/compose-ai-tools/commit/f5ed52c4c20dd6045573725b615b7d433cf49b67))
* **serve:** render wear-m3 live via an Android daemon so the SVG lane is per-variant ([#2460](https://github.com/yschimke/compose-ai-tools/issues/2460)) ([edd2ec3](https://github.com/yschimke/compose-ai-tools/commit/edd2ec3b98b22f89a20e52337398b2ee6279253e))


### Bug Fixes

* **deps:** update dependency org.apache.pdfbox:fontbox to v3 ([#2454](https://github.com/yschimke/compose-ai-tools/issues/2454)) ([bbdb917](https://github.com/yschimke/compose-ai-tools/commit/bbdb917604e24465828eb603f0fb3fc3d4b0846a))
* **deps:** update gradle minor/patch ([#2453](https://github.com/yschimke/compose-ai-tools/issues/2453)) ([d1911f2](https://github.com/yschimke/compose-ai-tools/commit/d1911f2b6334feb30eb52a8eebfad076afbd429b))
* **design-artifacts:** preserve catalog-token sheets when filtering non-raster previews ([#2459](https://github.com/yschimke/compose-ai-tools/issues/2459)) ([602fb2d](https://github.com/yschimke/compose-ai-tools/commit/602fb2db4ef260b32eafb3a723d6aafb52bc8388))
* **design-artifacts:** skip animated-GIF previews in the catalog export ([#2458](https://github.com/yschimke/compose-ai-tools/issues/2458)) ([0cef01d](https://github.com/yschimke/compose-ai-tools/commit/0cef01d7ab609dd4fb97b190e63c3285b25d6895))

## [0.16.44](https://github.com/yschimke/compose-ai-tools/compare/v0.16.43...v0.16.44) (2026-07-12)


### Features

* **serve:** make local Gradle discovery opt-in via --discover ([#2451](https://github.com/yschimke/compose-ai-tools/issues/2451)) ([460ad9c](https://github.com/yschimke/compose-ai-tools/commit/460ad9c61fe62eff61b892e986a8d5db77e749f5))


### Bug Fixes

* **serve:** render SVG on the daemon so dark variants aren't served the light vector ([#2448](https://github.com/yschimke/compose-ai-tools/issues/2448)) ([8ecdd47](https://github.com/yschimke/compose-ai-tools/commit/8ecdd47a548fc8492625c5672478953f86f06624))
* **serve:** separate stage background theme from the Light/Dark filter axis and sync it to the Theme choice ([#2450](https://github.com/yschimke/compose-ai-tools/issues/2450)) ([c76fd93](https://github.com/yschimke/compose-ai-tools/commit/c76fd93224b54c5dd079ed44dbc203da65924fef))
* **serve:** stage background follows the preview theme; Wear is dark-first ([#2449](https://github.com/yschimke/compose-ai-tools/issues/2449)) ([8bb22cc](https://github.com/yschimke/compose-ai-tools/commit/8bb22cc81395cfdf57b9a534716cf33200d78d94))

## [0.16.43](https://github.com/yschimke/compose-ai-tools/compare/v0.16.42...v0.16.43) (2026-07-12)


### Features

* **deploy:** bake Caddyfile into a Watchtower-watched image ([#2445](https://github.com/yschimke/compose-ai-tools/issues/2445)) ([d5fb4f7](https://github.com/yschimke/compose-ai-tools/commit/d5fb4f7a29a2db969c7e42728f0f65ab126e1dd6))


### Bug Fixes

* **ci:** boot serve-lanes module-less so the daemon actually comes up ([#2446](https://github.com/yschimke/compose-ai-tools/issues/2446)) ([62b6d93](https://github.com/yschimke/compose-ai-tools/commit/62b6d93405e87532235181f5c9013d5a3365b738))

## [0.16.42](https://github.com/yschimke/compose-ai-tools/compare/v0.16.41...v0.16.42) (2026-07-12)


### Features

* **serve:** e2e-test every render lane's overrides + surface mode-activation errors ([#2443](https://github.com/yschimke/compose-ai-tools/issues/2443)) ([fc5c56a](https://github.com/yschimke/compose-ai-tools/commit/fc5c56a96f4d1bda8bbe8c41fa39a829cd920573))
* **serve:** make figma-svg-long re-render override-aware ([#2442](https://github.com/yschimke/compose-ai-tools/issues/2442)) ([51be959](https://github.com/yschimke/compose-ai-tools/commit/51be9593404bcc4f0d4c725e2032f91a7f63e63e))


### Bug Fixes

* **deploy:** drop Caddy flush_interval -1 so aborted renders cancel ([#2441](https://github.com/yschimke/compose-ai-tools/issues/2441)) ([072049c](https://github.com/yschimke/compose-ai-tools/commit/072049c9a7fd35ca9768abac77c090f2ecb3f8ca))
* **deploy:** make Caddy WebSocket-safe so Live Compose /ws/ streams ([#2440](https://github.com/yschimke/compose-ai-tools/issues/2440)) ([6d30996](https://github.com/yschimke/compose-ai-tools/commit/6d30996a9f109e33b1918772bb8459454cc35171))

## [0.16.41](https://github.com/yschimke/compose-ai-tools/compare/v0.16.40...v0.16.41) (2026-07-12)


### Features

* **daemon:** serve Wear scroll-slice capsule via figma-svg-long + viewer toggle ([#2436](https://github.com/yschimke/compose-ai-tools/issues/2436)) ([bcd1059](https://github.com/yschimke/compose-ai-tools/commit/bcd1059604ed8e0c5f422b7286ac0850671d844c))
* **design-catalog-m3:** transparent component stickers, text specimens keep a surface ([#2432](https://github.com/yschimke/compose-ai-tools/issues/2432)) ([5649f6c](https://github.com/yschimke/compose-ai-tools/commit/5649f6c4e5a5cd7ea3d745cd025842753bd43732))
* **layoutinspector:** slice-stitch real Wear preview into a tall capsule SVG ([#2430](https://github.com/yschimke/compose-ai-tools/issues/2430)) ([2faab0c](https://github.com/yschimke/compose-ai-tools/commit/2faab0c281656e410213a2a4bdb255c3677e443a))
* **serve:** default sticker backing to a solid surface, add Background/Transparent toggle ([#2435](https://github.com/yschimke/compose-ai-tools/issues/2435)) ([2ec6a2e](https://github.com/yschimke/compose-ai-tools/commit/2ec6a2e76bf18aae4223fbb9d52a1042c09559b8))
* **wear:** publish wear-preview-runtime for isolated TLC item scaling ([#2433](https://github.com/yschimke/compose-ai-tools/issues/2433)) ([186312d](https://github.com/yschimke/compose-ai-tools/commit/186312dee62633a6ad567efa41974d292c021344))


### Bug Fixes

* **daemon:** wrap-height Android previews instead of clamping to 320px ([#2434](https://github.com/yschimke/compose-ai-tools/issues/2434)) ([4d81128](https://github.com/yschimke/compose-ai-tools/commit/4d81128c6491f06101637a8aa20abf7169c87bf0))
* **serve:** keep transparent catalog stickers legible via theme-aware backing ([#2437](https://github.com/yschimke/compose-ai-tools/issues/2437)) ([5af3aac](https://github.com/yschimke/compose-ai-tools/commit/5af3aac266a29619cb4caae424a66da6d4cd002d))

## [0.16.40](https://github.com/yschimke/compose-ai-tools/compare/v0.16.39...v0.16.40) (2026-07-11)


### Features

* **overrides:** fake-clock injector for deterministic time-dependent previews ([#2428](https://github.com/yschimke/compose-ai-tools/issues/2428)) ([f3a65f2](https://github.com/yschimke/compose-ai-tools/commit/f3a65f250a2535a62389f3aa474476a8434949a3))
* **serve:** re-fetch catalog branches on change so a live server stays fresh ([#2425](https://github.com/yschimke/compose-ai-tools/issues/2425)) ([d72f4ce](https://github.com/yschimke/compose-ai-tools/commit/d72f4ce4763a7cbe3e7354544f6ef69d4f86b8c8))
* **serve:** wrap-mode preview boxes to cut wasted space ([#2427](https://github.com/yschimke/compose-ai-tools/issues/2427)) ([a3faac3](https://github.com/yschimke/compose-ai-tools/commit/a3faac34c120cb9ce3789a1338df7762e65b3f9d))


### Bug Fixes

* **serve:** set render outputDir so the bundle daemon registers figma-svg ([#2429](https://github.com/yschimke/compose-ai-tools/issues/2429)) ([3851b93](https://github.com/yschimke/compose-ai-tools/commit/3851b9315fd9e87dd00812cdf47e27ebba23e539))

## [0.16.39](https://github.com/yschimke/compose-ai-tools/compare/v0.16.38...v0.16.39) (2026-07-11)


### Features

* **serve:** GC long-idle suspended forked sessions and prune their worktrees ([#2422](https://github.com/yschimke/compose-ai-tools/issues/2422)) ([979afbe](https://github.com/yschimke/compose-ai-tools/commit/979afbe9924437021c238cc4db79b7f3d3a3bd4a))


### Bug Fixes

* **figma-svg:** recover zero-area bounds so detached nodes don't collapse the SVG ([#2421](https://github.com/yschimke/compose-ai-tools/issues/2421)) ([9ecf454](https://github.com/yschimke/compose-ai-tools/commit/9ecf454eefc629bfa5e2ee68c54f5bf321402e43))

## [0.16.38](https://github.com/yschimke/compose-ai-tools/compare/v0.16.37...v0.16.38) (2026-07-11)


### Features

* **gradle:** add --preview name filter to composePreviewRender ([#2410](https://github.com/yschimke/compose-ai-tools/issues/2410)) ([a0a3333](https://github.com/yschimke/compose-ai-tools/commit/a0a3333ffb7c12b581555e67faa74b0154ec1b8d))
* **serve:** add size overrides (Fixed/Max/Min/Within) to the preview page ([#2416](https://github.com/yschimke/compose-ai-tools/issues/2416)) ([ee8f3be](https://github.com/yschimke/compose-ai-tools/commit/ee8f3bede4d240633c471fc3b78ab044661f60e0))
* **serve:** apply author-declared knob overrides in the Wasm tier ([#2414](https://github.com/yschimke/compose-ai-tools/issues/2414)) ([6c680d6](https://github.com/yschimke/compose-ai-tools/commit/6c680d65aac09f5ddf7618342c43f5aca25070ea))
* **serve:** expose a Storybook-compatible index.json + iframe.html surface ([#2413](https://github.com/yschimke/compose-ai-tools/issues/2413)) ([f8c3359](https://github.com/yschimke/compose-ai-tools/commit/f8c33596a46e5116186b5cfaa867f63ca3a0efc4))
* **slots:** record slot scope (Row/Column/Box/Lazy) and scrolling ([#2411](https://github.com/yschimke/compose-ai-tools/issues/2411)) ([d0cbc7d](https://github.com/yschimke/compose-ai-tools/commit/d0cbc7d3f97d040547e8fc8ecdb29997c19491c0))
* **wear:** capsule clip + reduce-motion for the Wear scroll-SVG export ([#2412](https://github.com/yschimke/compose-ai-tools/issues/2412)) ([576ae69](https://github.com/yschimke/compose-ai-tools/commit/576ae696453c0a55d59d65fb00d8a45304a91072))


### Bug Fixes

* **figma-svg:** mirror the final render setup in the Android scroll-SVG probe ([#2406](https://github.com/yschimke/compose-ai-tools/issues/2406)) ([a614a29](https://github.com/yschimke/compose-ai-tools/commit/a614a2901c528bd3aae0dbff77a5c031f07dc3af))
* **mcp:** rescan descriptor index on a miss instead of caching the negative ([#2407](https://github.com/yschimke/compose-ai-tools/issues/2407)) ([ff94051](https://github.com/yschimke/compose-ai-tools/commit/ff94051a8fcb2f1fa897180d5612c5de6fe0c7dc))
* **serve:** enable figma-svg data products so override SVG renders ([#2418](https://github.com/yschimke/compose-ai-tools/issues/2418)) ([bbf2410](https://github.com/yschimke/compose-ai-tools/commit/bbf24104a5b297e361dae7e0d9d256430190457d))
* **serve:** short-circuit SVG render when figma-svg is unavailable ([#2419](https://github.com/yschimke/compose-ai-tools/issues/2419)) ([3ac7843](https://github.com/yschimke/compose-ai-tools/commit/3ac7843e30a0dc0490c264f49d988ea5ca7c7391))
* **vscode:** gate exportPreviewBundle on composePreview.enabled too ([#2417](https://github.com/yschimke/compose-ai-tools/issues/2417)) ([a21fcb3](https://github.com/yschimke/compose-ai-tools/commit/a21fcb3382101469af070a7e7fc729ab898e0269))
* **vscode:** honor composePreview.enabled=false in task scheduling ([#2415](https://github.com/yschimke/compose-ai-tools/issues/2415)) ([1754b80](https://github.com/yschimke/compose-ai-tools/commit/1754b80ff29ee09204a6212f42722052dbbea314))

## [0.16.37](https://github.com/yschimke/compose-ai-tools/compare/v0.16.36...v0.16.37) (2026-07-11)


### Features

* **figma-svg:** export Wear curved TimeText as an SVG textPath ([#2395](https://github.com/yschimke/compose-ai-tools/issues/2395)) ([8e1c233](https://github.com/yschimke/compose-ai-tools/commit/8e1c233dfe4e32b831d92789af8908541a19bb33))
* **figma-svg:** full-page scrolling SVG on Android + raster/cache fixes ([#2405](https://github.com/yschimke/compose-ai-tools/issues/2405)) ([022acf4](https://github.com/yschimke/compose-ai-tools/commit/022acf4b5537fbbfcc4184e4bb9fadc6df118f21))
* **figma-svg:** full-page SVG for scrolling previews (compose/figma-svg-long) ([#2399](https://github.com/yschimke/compose-ai-tools/issues/2399)) ([edc1983](https://github.com/yschimke/compose-ai-tools/commit/edc198321ef396e28bc0c9ef404e6899b88a4a55))
* **serve:** collapsible overrides drawer + component nav drawer in the viewer ([#2400](https://github.com/yschimke/compose-ai-tools/issues/2400)) ([58eba7c](https://github.com/yschimke/compose-ai-tools/commit/58eba7c7a7cb9fcbb652e3057a5de6755e5624f7))
* **serve:** register cadence catalog for preview.coo.ee ([#2396](https://github.com/yschimke/compose-ai-tools/issues/2396)) ([6761a11](https://github.com/yschimke/compose-ai-tools/commit/6761a11a29f5f7962f57ca4acdbc8a92f4d4da79))


### Bug Fixes

* **build:** publish data-preview-overrides modules for bundle-render e2e ([#2402](https://github.com/yschimke/compose-ai-tools/issues/2402)) ([2cceead](https://github.com/yschimke/compose-ai-tools/commit/2cceeadb93ad1dbf509dc64ea16c73ec94ac28ab))
* **mcp:** resolve daemon descriptors for projectDir-remapped modules; bump stale descriptor schema check ([#2397](https://github.com/yschimke/compose-ai-tools/issues/2397)) ([705f16a](https://github.com/yschimke/compose-ai-tools/commit/705f16a855d52345fded9b552ef4391075b4d461))
* **serve:** carry named-knob overrides through the previewId render path ([#2401](https://github.com/yschimke/compose-ai-tools/issues/2401)) ([ab32ea0](https://github.com/yschimke/compose-ai-tools/commit/ab32ea0c3f8689099985cf0d946a102be1cab054))
* **serve:** merge previewId override bags instead of replacing base ([#2403](https://github.com/yschimke/compose-ai-tools/issues/2403)) ([0549818](https://github.com/yschimke/compose-ai-tools/commit/0549818ee0b9586a0db850b941bac06b1e1ae246))
* **wear-catalog:** centre component stickers in the pinned Wear canvas ([#2404](https://github.com/yschimke/compose-ai-tools/issues/2404)) ([01ed4bd](https://github.com/yschimke/compose-ai-tools/commit/01ed4bd026748e85cefcf7d09903aacb8e30d3fa))

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
