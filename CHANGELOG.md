# Changelog

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
