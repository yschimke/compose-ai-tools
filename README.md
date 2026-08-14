# adaptive-apps-samples (AdaptiveJetStream — XR spatial Compose) — Compose previews

Auto-rendered by the integration matrix from [`android/adaptive-apps-samples@main`](https://github.com/android/adaptive-apps-samples/tree/main). Updated on every push to `main`.

## CI notes

- XR spatial Compose. Upstream tracks `androidx.xr.compose`
  alpha07, whose `ApplicationSubspace` / `MovePolicy` /
  `SpatialConfiguration` APIs were removed or deprecated by the
  version our XR render path compiles against. The integration run
  therefore applies the in-repo
  `adaptive-apps-samples-xr-upgrade.patch` before configuring the
  build, pinned to the same version as `xr-compose`.
- Poster art is loaded from remote URLs that can't resolve under
  the offline Robolectric render, so a second CI patch installs a
  Coil `FakeImageLoaderEngine` singleton that serves bundled
  local posters — otherwise the cards render blank.
- 12 device-targeted previews via custom multi-preview
  annotations (`@PhonePreview` / `@TvPreview` / …).


### Workarounds applied by the integration harness

- Source: [`android/adaptive-apps-samples@main`](https://github.com/android/adaptive-apps-samples/tree/main)
- Consumer patch(es) applied before configuring the build: `adaptive-apps-samples-xr-upgrade.patch adaptive-apps-samples-xr-spatial-previews.patch adaptive-apps-samples-coil-fake-images.patch` (idempotent — auto-skipped once the change lands upstream).

> [!WARNING]
> 2 preview(s) failed to render in the latest update (2 with no prior baseline to retain). See **Render Failures** below.

## Render Failures

The render task completed but no PNG was produced for these previews. Entries with a prior baseline keep their previous image; the rest are absent from the gallery until a successful render lands.

| Preview | Module | Function | Source | Baseline |
|---------|--------|----------|--------|----------|
| `jetstream/activity__MainActivity` | jetstream | `MainActivity` | `—` | none |
| `jetstream/com.google.jetstream.presentation.screens.profile.compoents.AccountsSectionKt.AccountsSection_TV` | jetstream | `AccountsSection` | `src/main/java/com/google/jetstream/presentation/screens/profile/compoents/AccountsSection.kt` | none |

## jetstream

| Preview | Image |
|---------|-------|
| `BackButtonScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/BackButtonScreenshot-2e667f37.png" width="150" /> |
| `ErrorScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ErrorScreenshot-a461ee2b.png" width="150" /> |
| `LoadingScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LoadingScreenshot-f25393a3.png" width="150" /> |
| `MovieCardScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieCardScreenshot-fb65058f.png" width="150" /> |
| `RequestFullSpaceModeItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/RequestFullSpaceModeItemPreview-3e8b248b.png" width="150" /> |
| `TopAppBarPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopAppBarPreview-e829f20f.png" width="150" /> |
| `UserAvatarScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/UserAvatarScreenshot-78eb7cfa.png" width="150" /> |
| `WatchNowButtonScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/WatchNowButtonScreenshot-e9418688.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Auto-c1c781eb.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Desktop-bf4ac5c7.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Foldable-16956b60.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Phone-85149f4c.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_TV-adf23bbc.png" width="150" /> |
| `CategoriesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenScreenshot_Tablet-67b90a48.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Auto-96cdf6ba.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Desktop-7dc73a15.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Foldable-14419fc9.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Phone-7702053c.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_TV-b653c66f.png" width="150" /> |
| `CategoryMovieListScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoryMovieListScreenScreenshot_Tablet-fff5e27e.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Auto-3d2edf18.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Desktop-5e4692a8.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Foldable-1e55c263.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Phone-cfa9c024.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_TV-aad4d9e5.png" width="150" /> |
| `FavouritesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/FavouritesScreenScreenshot_Tablet-e81aae1c.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Auto-1b3116dc.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Desktop-5030e881.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Foldable-cc0d286c.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Phone-7680605f.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_TV-a79ff167.png" width="150" /> |
| `HomeScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HomeScreenScreenshot_Tablet-524b492e.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Auto-7ec0963d.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Desktop-d1aea646.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Foldable-0ce63c91.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Phone-f8626a69.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_TV-1c90edf4.png" width="150" /> |
| `MovieDetailsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MovieDetailsScreenScreenshot_Tablet-af82042c.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Auto-f77a139f.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Desktop-4a709725.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Foldable-bd01533a.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Phone-f756178e.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_TV-4ee05bf3.png" width="150" /> |
| `MoviesScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MoviesScreenScreenshot_Tablet-5e2ecc61.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Foldable-1d72fd86.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Phone-e8707a21.png" width="150" /> |
| `NavigationSuiteScaffoldLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/NavigationSuiteScaffoldLayoutPreview_Tablet-c620628c.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Auto-73e3d0a3.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Desktop-eb50d5fc.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Foldable-deb22cab.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Phone-c0d9968e.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_TV-5d03a024.png" width="150" /> |
| `SearchScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchScreenScreenshot_Tablet-a654bca6.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Auto-eb75eef6.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Desktop-bacb0b1a.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Foldable-826736e2.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Phone-891381ff.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_TV-1d0ecfb4.png" width="150" /> |
| `ShowsScreenScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ShowsScreenScreenshot_Tablet-308844b0.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_Auto-78b3e0ec.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_Desktop-506e9307.png" width="150" /> |
| `TopBarWithNavigationLayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/TopBarWithNavigationLayoutPreview_TV-eb045659.png" width="150" /> |
| `CategoriesScreenFoldablePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenFoldablePreview_Foldable-f93c7023.png" width="150" /> |
| `CategoriesScreenPhonePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenPhonePreview_Phone-b61f82e7.png" width="150" /> |
| `CategoriesScreenTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/CategoriesScreenTvPreview_TV-84b7a6e7.png" width="150" /> |
| `ProfileScreenFoldablePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenFoldablePreview_Foldable-1b668cbc.png" width="150" /> |
| `ProfileScreenPhonePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenPhonePreview_Phone-ecab9bc7.png" width="150" /> |
| `ProfileScreenTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/ProfileScreenTvPreview_TV-9f946396.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Auto-8085a02d.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Desktop-b478acfc.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Foldable-eb451c56.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Phone-3fac345a.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_TV-eeadf8a3.png" width="150" /> |
| `AboutSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionScreenshot_Tablet-5fe61213.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Auto-91748d5e.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Desktop-38f1824b.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Foldable-998cdc72.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Phone-29953bcc.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_TV-89956f49.png" width="150" /> |
| `AccountsSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountsSectionScreenshot_Tablet-d803b39c.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Auto-1790df0d.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Desktop-e74230b9.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Foldable-66ea4d6e.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Phone-1b982e96.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_TV-c931b2f2.png" width="150" /> |
| `HelpAndSupportSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpAndSupportSectionScreenshot_Tablet-0db6663d.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Auto-a351bd7e.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Desktop-4f9d32f3.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Foldable-c9643036.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Phone-cfaf9b5a.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_TV-0c52f92f.png" width="150" /> |
| `LanguageSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageSectionScreenshot_Tablet-04649749.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Auto-cd602f36.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Desktop-0bbf8425.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Foldable-52951d1d.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Phone-23f2ca3a.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_TV-e3576900.png" width="150" /> |
| `SearchHistorySectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionScreenshot_Tablet-f3129b70.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Auto-47bc5f5b.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Desktop-eb9664c7.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Foldable-514cd8f6.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Phone-bf66006e.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_TV-22bd417f.png" width="150" /> |
| `SubtitlesSectionScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionScreenshot_Tablet-5eee8b52.png" width="150" /> |
| `AboutSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionCompactPreview_Foldable-44cd8761.png" width="150" /> |
| `AboutSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionCompactPreview_Phone-05dbe7d6.png" width="150" /> |
| `AboutSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AboutSectionExpandedPreview_TV-d03533f7.png" width="150" /> |
| `SingleColumnAccountPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SingleColumnAccountPreview_Foldable-357c1aa7.png" width="150" /> |
| `SingleColumnAccountPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SingleColumnAccountPreview_Phone-7dc1a239.png" width="150" /> |
| `AccountSelectionItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemPreview_Foldable-f422a80a.png" width="150" /> |
| `AccountSelectionItemPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemPreview_Phone-8904fa7d.png" width="150" /> |
| `AccountSelectionItemTvPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/AccountSelectionItemTvPreview_TV-c66788eb.png" width="150" /> |
| `HelpSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionCompactPreview_Foldable-ea3841f4.png" width="150" /> |
| `HelpSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionCompactPreview_Phone-1c92dec2.png" width="150" /> |
| `HelpSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/HelpSectionExpandedPreview_TV-614bbb87.png" width="150" /> |
| `LanguageScreenCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenCompactPreview_Foldable-2a35fbb6.png" width="150" /> |
| `LanguageScreenCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenCompactPreview_Phone-b72b6dc7.png" width="150" /> |
| `LanguageScreenExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/LanguageScreenExpandedPreview_TV-08d19ee3.png" width="150" /> |
| `SearchHistorySectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionCompactPreview_Foldable-48a60e1f.png" width="150" /> |
| `SearchHistorySectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionCompactPreview_Phone-7a88e8f6.png" width="150" /> |
| `SearchHistorySectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SearchHistorySectionExpandedPreview_TV-aa0311b7.png" width="150" /> |
| `SubtitlesSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionCompactPreview_Foldable-e0452ab9.png" width="150" /> |
| `SubtitlesSectionCompactPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionCompactPreview_Phone-c2428713.png" width="150" /> |
| `SubtitlesSectionExpandedPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/SubtitlesSectionExpandedPreview_TV-7244c800.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayoutOnPhone` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayoutOnPhone_Phone-06686cec.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayoutWithoutMore` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayoutWithoutMore_tv_4k-4a18c67b.png" width="150" /> |
| `MediaPlayerMainFramePreviewLayout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/MediaPlayerMainFramePreviewLayout_tv_4k-8631b36a.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewAd` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewAd_Ads-307a7545.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewLive` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewLive_Live-e2b08a18.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewSeries` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewSeries_TV_Series-98732e61.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewAdScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewAdScreenshot_Ads-aa97c67d.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewLiveScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewLiveScreenshot_Live-1ebf14b8.png" width="150" /> |
| `VideoPlayerMediaTitlePreviewSeriesScreenshot` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerMediaTitlePreviewSeriesScreenshot_TV_Series-4a8a639f.png" width="150" /> |
| `VideoPlayerOverlayPreviewForPhone` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerOverlayPreviewForPhone_Phone-7133c714.png" width="150" /> |
| `VideoPlayerOverlayPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/jetstream-xr/renders/jetstream/VideoPlayerOverlayPreview_tv_4k-e715e4d3.png" width="150" /> |


<!-- xr-spatial-previews -->
## XR spatial previews

Each `@XrSubspacePreview` renders to a `scene.json` (the framework-computed spatial panel layout) plus one texture per `SpatialPanel`, recovered offline by `composePreviewRenderXr` — no headset, no OpenXR.

### `com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialStackPreview` — 2 panel(s)

[`scene.json`](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialStackPreview/scene.json)

| `appbar` (720×96) | `queue` (720×360) |
| --- | --- |
| ![appbar](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialStackPreview/appbar.png) | ![queue](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialStackPreview/queue.png) |

### `com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialWorkspacePreview` — 3 panel(s)

[`scene.json`](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialWorkspacePreview/scene.json)

| `browse` (360×520) | `detail` (480×520) | `profile` (320×520) |
| --- | --- | --- |
| ![browse](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialWorkspacePreview/browse.png) | ![detail](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialWorkspacePreview/detail.png) | ![profile](renders/xr/com.google.jetstream.presentation.xr.SpatialJetStreamPreviewsKt.JetStreamSpatialWorkspacePreview/profile.png) |
